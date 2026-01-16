/*
 * Copyright (C) 2024 AutoGLM
 *
 * Virtual display manager for concurrent multi-app automation.
 * Creates and manages virtual displays similar to scrcpy's new-display feature.
 * 
 * Inspired by scrcpy project (Apache License 2.0)
 * https://github.com/Genymobile/scrcpy
 */

package com.autoglm.android.display

import android.annotation.SuppressLint
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.autoglm.android.shizuku.ShizukuExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object VirtualDisplayManager {
    
    private const val TAG = "VirtualDisplayManager"
    
    private const val VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 shl 0
    private const val VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 shl 1
    private const val VIRTUAL_DISPLAY_FLAG_SECURE = 1 shl 2
    private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 1 shl 4
    private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
    private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
    
    private val displayManagerGlobalClass: Class<*>? by lazy {
        try {
            Class.forName("android.hardware.display.DisplayManagerGlobal")
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "DisplayManagerGlobal not found", e)
            null
        }
    }
    
    private val virtualDisplayClass: Class<*>? by lazy {
        try {
            Class.forName("android.hardware.display.VirtualDisplay")
        } catch (e: ClassNotFoundException) {
            null
        }
    }
    
    private val virtualDisplayConfigClass: Class<*>? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                Class.forName("android.hardware.display.VirtualDisplayConfig")
            } catch (e: ClassNotFoundException) {
                null
            }
        } else null
    }
    
    private val displayMutex = Mutex()
    private val activeDisplays = mutableMapOf<Int, VirtualDisplayInfo>()
    private var nextDisplayId = 1000
    
    data class VirtualDisplayInfo(
        val displayId: Int,
        val width: Int,
        val height: Int,
        val density: Int,
        val name: String,
        val virtualDisplay: Any?,
        val surface: Surface?,
        var assignedPackage: String? = null,
        var supportsVirtualDisplay: Boolean = true,
        val isSimulated: Boolean = false
    )
    
    init {
        applyHiddenApiExemptions()
    }
    
    private fun applyHiddenApiExemptions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntimeMethod = vmRuntimeClass.getDeclaredMethod("getRuntime")
            val setHiddenApiExemptionsMethod = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java
            )
            
            val vmRuntime = getRuntimeMethod.invoke(null)
            setHiddenApiExemptionsMethod.invoke(vmRuntime, arrayOf("L") as Any)
            
            Log.d(TAG, "Hidden API exemptions applied")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply hidden API exemptions: ${e.message}")
        }
    }
    
    suspend fun createVirtualDisplay(
        width: Int,
        height: Int,
        density: Int = 320,
        name: String = "AutoGLM-Display"
    ): VirtualDisplayInfo? = displayMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                // Priority 1: Use DisplayStreamClient (Shizuku UserService) - this runs with shell privileges
                // and can create real virtual displays like scrcpy does
                val displayIdFromService = DisplayStreamClient.createDisplay(
                    width = width,
                    height = height,
                    density = density,
                    name = "${name}-${nextDisplayId}"
                )
                
                if (displayIdFromService > 0) {
                    val info = VirtualDisplayInfo(
                        displayId = displayIdFromService,
                        width = width,
                        height = height,
                        density = density,
                        name = name,
                        virtualDisplay = null,
                        surface = null,
                        isSimulated = false
                    )
                    activeDisplays[displayIdFromService] = info
                    nextDisplayId++
                    Log.i(TAG, "Created virtual display via DisplayService: $displayIdFromService ($width x $height)")
                    return@withContext info
                }
                
                Log.w(TAG, "DisplayService failed, trying direct reflection")
                
                // Priority 2: Try direct reflection (may not work on newer Android versions)
                val displayId = createVirtualDisplayViaReflection(
                    name = "${name}-${nextDisplayId}",
                    width = width,
                    height = height,
                    density = density
                )
                
                if (displayId != null && displayId > 0) {
                    val info = VirtualDisplayInfo(
                        displayId = displayId,
                        width = width,
                        height = height,
                        density = density,
                        name = name,
                        virtualDisplay = null,
                        surface = null,
                        isSimulated = false
                    )
                    activeDisplays[displayId] = info
                    nextDisplayId++
                    Log.i(TAG, "Created virtual display via reflection: $displayId ($width x $height)")
                    info
                } else {
                    Log.w(TAG, "Failed to create virtual display via reflection, trying shell")
                    createVirtualDisplayViaShell(width, height, density)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create virtual display", e)
                null
            }
        }
    }
    
    private fun createVirtualDisplayViaReflection(
        name: String,
        width: Int,
        height: Int,
        density: Int
    ): Int? {
        val dmgClass = displayManagerGlobalClass ?: return null
        
        return try {
            val getInstance = dmgClass.getMethod("getInstance")
            val dmgInstance = getInstance.invoke(null) ?: return null
            
            val flags = VIRTUAL_DISPLAY_FLAG_PUBLIC or
                       VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                       VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                createVirtualDisplayApi33(dmgInstance, name, width, height, density, flags)
            } else {
                createVirtualDisplayLegacy(dmgInstance, name, width, height, density, flags)
            }
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplayViaReflection failed", e)
            null
        }
    }
    
    private fun createVirtualDisplayApi33(
        dmgInstance: Any,
        name: String,
        width: Int,
        height: Int,
        density: Int,
        flags: Int
    ): Int? {
        val configBuilderClass = try {
            Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        } catch (e: ClassNotFoundException) {
            return null
        }
        
        return try {
            val builderConstructor = configBuilderClass.getConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val builder = builderConstructor.newInstance(name, width, height, density)
            
            val setFlags = configBuilderClass.getMethod("setFlags", Int::class.javaPrimitiveType)
            setFlags.invoke(builder, flags)
            
            val build = configBuilderClass.getMethod("build")
            val config = build.invoke(builder)
            
            // Try different method signatures for createVirtualDisplay
            val methods = dmgInstance.javaClass.declaredMethods.filter { it.name == "createVirtualDisplay" }
            
            for (method in methods) {
                try {
                    method.isAccessible = true
                    val paramCount = method.parameterCount
                    
                    val result = when (paramCount) {
                        3 -> method.invoke(dmgInstance, config, null, null)
                        4 -> method.invoke(dmgInstance, null, config, null, null)
                        else -> continue
                    }
                    
                    if (result != null) {
                        val getDisplay = result.javaClass.getMethod("getDisplay")
                        val display = getDisplay.invoke(result)
                        val getDisplayId = display.javaClass.getMethod("getDisplayId")
                        val displayId = getDisplayId.invoke(display) as? Int
                        if (displayId != null && displayId > 0) {
                            Log.i(TAG, "Created virtual display via API33: $displayId")
                            return displayId
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Method ${method.name}(${method.parameterCount} params) failed: ${e.message}")
                    continue
                }
            }
            
            // Note: SurfaceControl.createDisplay was removed in Android 15+
            // If we reach here, creation failed
            Log.w(TAG, "All createVirtualDisplay methods failed")
            null
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplayApi33 failed", e)
            null
        }
    }
    
    private fun createVirtualDisplayLegacy(
        dmgInstance: Any,
        name: String,
        width: Int,
        height: Int,
        density: Int,
        flags: Int
    ): Int? {
        return try {
            val createVirtualDisplay = dmgInstance.javaClass.getMethod(
                "createVirtualDisplay",
                android.content.Context::class.java,
                android.media.projection.MediaProjection::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java,
                Int::class.javaPrimitiveType,
                android.hardware.display.VirtualDisplay.Callback::class.java,
                android.os.Handler::class.java,
                String::class.java
            )
            
            val virtualDisplay = createVirtualDisplay.invoke(
                dmgInstance,
                null, null, name, width, height, density, null, flags, null, null, null
            )
            
            virtualDisplay?.let {
                val getDisplay = it.javaClass.getMethod("getDisplay")
                val display = getDisplay.invoke(it)
                val getDisplayId = display.javaClass.getMethod("getDisplayId")
                getDisplayId.invoke(display) as? Int
            }
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplayLegacy failed", e)
            null
        }
    }
    
    private suspend fun createVirtualDisplayViaShell(
        width: Int,
        height: Int,
        density: Int
    ): VirtualDisplayInfo? {
        Log.d(TAG, "Trying to create virtual display via shell command")
        
        // Try to create virtual display using wm command (Android 13+)
        val wmResult = ShizukuExecutor.execute(
            "wm create-display $width $height $density"
        )
        
        Log.d(TAG, "wm create-display result: success=${wmResult.success}, output=${wmResult.output}, error=${wmResult.error}")
        
        if (wmResult.success && wmResult.output.contains("display", ignoreCase = true)) {
            // Parse display ID from output like "Created display #2"
            val match = Regex("""#(\d+)""").find(wmResult.output)
            val displayId = match?.groupValues?.get(1)?.toIntOrNull()
            
            if (displayId != null && displayId > 0) {
                val info = VirtualDisplayInfo(
                    displayId = displayId,
                    width = width,
                    height = height,
                    density = density,
                    name = "AutoGLM-Display-$displayId",
                    virtualDisplay = null,
                    surface = null
                )
                activeDisplays[displayId] = info
                Log.i(TAG, "Created virtual display via wm: $displayId")
                return info
            }
        }
        
        // Try cmd display create-display (alternative command on some devices)
        val cmdResult = ShizukuExecutor.execute(
            "cmd display create-display $width $height $density"
        )
        Log.d(TAG, "cmd display create-display result: success=${cmdResult.success}, output=${cmdResult.output}")
        
        if (cmdResult.success && cmdResult.output.contains("display", ignoreCase = true)) {
            val match = Regex("""(\d+)""").find(cmdResult.output)
            val displayId = match?.groupValues?.get(1)?.toIntOrNull()
            
            if (displayId != null && displayId > 0) {
                val info = VirtualDisplayInfo(
                    displayId = displayId,
                    width = width,
                    height = height,
                    density = density,
                    name = "AutoGLM-Display-$displayId",
                    virtualDisplay = null,
                    surface = null
                )
                activeDisplays[displayId] = info
                Log.i(TAG, "Created virtual display via cmd: $displayId")
                return info
            }
        }
        
        // Fallback: scan existing displays and use next available secondary display
        val displays = getDisplayList()
        val existingIds = activeDisplays.keys
        val availableDisplay = displays.find { 
            it.displayId > 0 && it.displayId !in existingIds 
        }
        
        if (availableDisplay != null) {
            activeDisplays[availableDisplay.displayId] = availableDisplay.copy(
                width = width,
                height = height,
                density = density
            )
            Log.i(TAG, "Using existing secondary display: ${availableDisplay.displayId}")
            return activeDisplays[availableDisplay.displayId]
        }
        
        // Create a simulated virtual display entry for internal tracking
        // Apps will still run on main display but we track them separately
        val simulatedId = nextDisplayId++
        val info = VirtualDisplayInfo(
            displayId = simulatedId,
            width = width,
            height = height,
            density = density,
            name = "AutoGLM-Simulated-$simulatedId",
            virtualDisplay = null,
            surface = null,
            isSimulated = true
        )
        activeDisplays[simulatedId] = info
        Log.i(TAG, "Created simulated display entry: $simulatedId")
        return info
    }
    
    suspend fun destroyVirtualDisplay(displayId: Int): Boolean = displayMutex.withLock {
        withContext(Dispatchers.IO) {
            val info = activeDisplays.remove(displayId) ?: return@withContext false
            
            try {
                // Try to destroy via wm command if not simulated
                if (!info.isSimulated) {
                    ShizukuExecutor.execute("wm destroy-display $displayId 2>/dev/null")
                }
                
                info.virtualDisplay?.let { vd ->
                    val release = vd.javaClass.getMethod("release")
                    release.invoke(vd)
                }
                
                info.surface?.release()
                
                Log.i(TAG, "Destroyed virtual display: $displayId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to destroy virtual display $displayId", e)
                true
            }
        }
    }
    
    suspend fun destroyAllDisplays() = displayMutex.withLock {
        withContext(Dispatchers.IO) {
            val displayIds = activeDisplays.keys.toList()
            displayIds.forEach { displayId ->
                try {
                    val info = activeDisplays.remove(displayId)
                    if (info != null && !info.isSimulated) {
                        ShizukuExecutor.execute("wm destroy-display $displayId 2>/dev/null")
                    }
                    info?.virtualDisplay?.let { vd ->
                        try {
                            val release = vd.javaClass.getMethod("release")
                            release.invoke(vd)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to release virtual display $displayId")
                        }
                    }
                    info?.surface?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to destroy display $displayId", e)
                }
            }
            activeDisplays.clear()
            Log.i(TAG, "All virtual displays destroyed")
        }
    }
    
    suspend fun clearOverlayDisplays() {
        withContext(Dispatchers.IO) {
            ShizukuExecutor.execute("settings put global overlay_display_devices \"\"")
            Log.i(TAG, "Cleared overlay display settings")
        }
    }
    
    fun getActiveDisplays(): List<VirtualDisplayInfo> = activeDisplays.values.toList()
    
    fun getDisplayInfo(displayId: Int): VirtualDisplayInfo? = activeDisplays[displayId]
    
    suspend fun getDisplayList(): List<VirtualDisplayInfo> = withContext(Dispatchers.IO) {
        val result = ShizukuExecutor.execute("dumpsys display | grep -E 'mDisplayId|mBaseDisplayInfo'")
        if (!result.success) return@withContext emptyList()
        
        val displays = mutableListOf<VirtualDisplayInfo>()
        val lines = result.output.lines()
        
        var currentId: Int? = null
        for (line in lines) {
            val idMatch = Regex("""mDisplayId=(\d+)""").find(line)
            if (idMatch != null) {
                currentId = idMatch.groupValues[1].toIntOrNull()
            }
            
            val infoMatch = Regex("""(\d+)\s*x\s*(\d+).*density\s*(\d+)""").find(line)
            if (infoMatch != null && currentId != null && currentId > 0) {
                displays.add(VirtualDisplayInfo(
                    displayId = currentId,
                    width = infoMatch.groupValues[1].toIntOrNull() ?: 0,
                    height = infoMatch.groupValues[2].toIntOrNull() ?: 0,
                    density = infoMatch.groupValues[3].toIntOrNull() ?: 0,
                    name = "Display-$currentId",
                    virtualDisplay = null,
                    surface = null
                ))
                currentId = null
            }
        }
        
        displays
    }
    
    suspend fun assignPackageToDisplay(displayId: Int, packageName: String) {
        displayMutex.withLock {
            activeDisplays[displayId]?.assignedPackage = packageName
        }
    }
    
    suspend fun markDisplayAsUnsupported(displayId: Int) {
        displayMutex.withLock {
            activeDisplays[displayId]?.supportsVirtualDisplay = false
        }
    }
    
    fun getDisplayForPackage(packageName: String): VirtualDisplayInfo? {
        return activeDisplays.values.find { it.assignedPackage == packageName }
    }
    
    fun getAvailableDisplay(): VirtualDisplayInfo? {
        return activeDisplays.values.find { it.assignedPackage == null && it.supportsVirtualDisplay }
    }
    
    suspend fun getDefaultDisplaySize(): Point? = withContext(Dispatchers.IO) {
        val result = ShizukuExecutor.execute("wm size")
        if (!result.success) return@withContext null
        
        val match = Regex("""(\d+)x(\d+)""").find(result.output)
        match?.let {
            Point(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
    }
    
    suspend fun getDefaultDisplayDensity(): Int = withContext(Dispatchers.IO) {
        val result = ShizukuExecutor.execute("wm density")
        if (!result.success) return@withContext 320
        
        val match = Regex("""(\d+)""").find(result.output)
        match?.groupValues?.get(1)?.toIntOrNull() ?: 320
    }
    
    suspend fun checkVirtualDisplaySupport(): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Virtual display requires Android 10+")
            return@withContext false
        }
        
        if (displayManagerGlobalClass == null) {
            Log.w(TAG, "DisplayManagerGlobal not available")
            return@withContext false
        }
        
        try {
            val testDisplay = createVirtualDisplay(
                width = 100,
                height = 100,
                density = 160,
                name = "test-support-check"
            )
            
            if (testDisplay != null) {
                // Check if it's a real display or just a simulated one
                if (testDisplay.isSimulated) {
                    Log.w(TAG, "Only simulated displays available - virtual display not truly supported")
                    destroyVirtualDisplay(testDisplay.displayId)
                    return@withContext false
                }
                
                // Verify the display can actually be used for screenshots
                val screencapResult = ShizukuExecutor.execute(
                    "screencap -d ${testDisplay.displayId} -p /dev/null 2>&1"
                )
                destroyVirtualDisplay(testDisplay.displayId)
                
                if (!screencapResult.success || screencapResult.output.contains("not valid")) {
                    Log.w(TAG, "Virtual display created but screencap not supported")
                    return@withContext false
                }
                
                Log.i(TAG, "Virtual display is fully supported")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Virtual display not supported: ${e.message}")
        }
        
        false
    }
}
