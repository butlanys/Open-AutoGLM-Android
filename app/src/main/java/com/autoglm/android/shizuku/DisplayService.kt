/*
 * Copyright (C) 2024 AutoGLM
 *
 * Display streaming service running in Shizuku user service context.
 * Provides video streaming of virtual displays using MediaCodec.
 * 
 * Inspired by scrcpy project (Apache License 2.0)
 * https://github.com/Genymobile/scrcpy
 */

package com.autoglm.android.shizuku

import android.annotation.SuppressLint
import android.content.ContextWrapper
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class DisplayService : IDisplayService.Stub {
    
    private val activeStreams = ConcurrentHashMap<Int, DisplayStream>()
    private var nextDisplayId = 1000
    
    data class DisplayStream(
        val displayId: Int,
        val width: Int,
        val height: Int,
        val virtualDisplay: Any?,
        val encoder: MediaCodec?,
        val inputSurface: Surface?,
        val outputPipe: ParcelFileDescriptor?,
        val isStreaming: AtomicBoolean = AtomicBoolean(false)
    )
    
    @Keep
    constructor() {
        try {
            Log.d(TAG, "DisplayService constructor (no-arg) called")
            applyHiddenApiExemptions()
            Log.d(TAG, "DisplayService created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "DisplayService constructor failed", e)
            throw e
        }
    }
    
    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: android.content.Context) {
        try {
            Log.d(TAG, "DisplayService constructor (with context) called")
            applyHiddenApiExemptions()
            Log.d(TAG, "DisplayService created with context successfully")
        } catch (e: Exception) {
            Log.e(TAG, "DisplayService constructor with context failed", e)
            throw e
        }
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
    
    override fun destroy() {
        Log.d(TAG, "DisplayService destroy")
        activeStreams.keys.toList().forEach { destroyDisplay(it) }
        System.exit(0)
    }
    
    override fun createDisplay(width: Int, height: Int, density: Int, name: String): Int {
        Log.d(TAG, "createDisplay: ${width}x${height} @ $density dpi, name=$name")
        
        return try {
            val displayId = createVirtualDisplayInternal(width, height, density, name)
            if (displayId > 0) {
                setupEncoder(displayId, width, height)
            }
            displayId
        } catch (e: Exception) {
            Log.e(TAG, "createDisplay failed", e)
            -1
        }
    }
    
    private fun createVirtualDisplayInternal(width: Int, height: Int, density: Int, name: String): Int {
        // Scrcpy-style: Create DisplayManager instance with FakeContext and call public API
        // This works because Shizuku UserService runs with shell privileges
        
        // Base flags matching scrcpy for maximum compatibility
        var flags = VIRTUAL_DISPLAY_FLAG_PUBLIC or
                   VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                   VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                   VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                   VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT
        
        // Android 13+ (API 33): Add trusted display flags like scrcpy does
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or 
                   VIRTUAL_DISPLAY_FLAG_TRUSTED or
                   VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
                   VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                   VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
            Log.d(TAG, "Added Android 13+ flags: TRUSTED, OWN_DISPLAY_GROUP, ALWAYS_UNLOCKED, TOUCH_FEEDBACK_DISABLED")
        }
        
        // Android 14+ (API 34): Add focus and device display group flags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flags or
                   VIRTUAL_DISPLAY_FLAG_OWN_FOCUS or
                   VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
            Log.d(TAG, "Added Android 14+ flags: OWN_FOCUS, DEVICE_DISPLAY_GROUP")
        }
        
        Log.d(TAG, "Creating virtual display with flags: 0x${flags.toString(16)}")
        
        // Try scrcpy-style approach first
        val scrcpyResult = createDisplayScrcpyStyle(name, width, height, density, flags)
        if (scrcpyResult > 0) {
            return scrcpyResult
        }
        
        // Fallback to DisplayManagerGlobal approach
        try {
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val getInstance = dmgClass.getMethod("getInstance")
            val dmgInstance = getInstance.invoke(null)
            
            if (dmgInstance != null) {
                val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    createDisplayApi33(dmgInstance, name, width, height, density, flags)
                } else {
                    createDisplayLegacy(dmgInstance, name, width, height, density, flags)
                }
                if (result != null && result > 0) {
                    return result
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DisplayManagerGlobal approach failed: ${e.message}")
        }
        
        // Last fallback: use wm command
        return createDisplayViaShell(width, height, density)
    }
    
    /**
     * Create virtual display using scrcpy's approach:
     * Instantiate DisplayManager with FakeContext and call public createVirtualDisplay API.
     * 
     * Key insight from scrcpy: FakeContext must extend ContextWrapper with a REAL system context,
     * not null. The FakeContext pretends to be com.android.shell with shell UID.
     */
    private fun createDisplayScrcpyStyle(name: String, width: Int, height: Int, density: Int, flags: Int): Int {
        try {
            Log.d(TAG, "Trying scrcpy-style virtual display creation")
            
            // Get a real system context like scrcpy does (Workarounds.getSystemContext())
            val systemContext = getSystemContext()
            if (systemContext == null) {
                Log.w(TAG, "Failed to get system context")
                return -1
            }
            
            // Create a FakeContext that wraps the real system context
            val fakeContext = createFakeContext(systemContext)
            
            // Get DisplayManager constructor that takes Context
            val dmClass = android.hardware.display.DisplayManager::class.java
            val constructor = dmClass.getDeclaredConstructor(android.content.Context::class.java)
            constructor.isAccessible = true
            
            val displayManager = constructor.newInstance(fakeContext)
            
            // Create a dummy surface to render to (required for createVirtualDisplay)
            // We'll use SurfaceTexture for this
            val surfaceTexture = android.graphics.SurfaceTexture(0)
            surfaceTexture.setDefaultBufferSize(width, height)
            val surface = Surface(surfaceTexture)
            
            // Call public createVirtualDisplay method
            val virtualDisplay = displayManager.createVirtualDisplay(
                name,
                width,
                height,
                density,
                surface,
                flags
            )
            
            if (virtualDisplay != null) {
                val displayId = virtualDisplay.display.displayId
                if (displayId > 0) {
                    activeStreams[displayId] = DisplayStream(
                        displayId = displayId,
                        width = width,
                        height = height,
                        virtualDisplay = virtualDisplay,
                        encoder = null,
                        inputSurface = surface,
                        outputPipe = null
                    )
                    Log.i(TAG, "Created virtual display via scrcpy-style: $displayId")
                    return displayId
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Scrcpy-style creation failed: ${e.message}", e)
        }
        return -1
    }
    
    /**
     * Get the system context like scrcpy's Workarounds.getSystemContext().
     * This obtains a real Context from ActivityThread that can be used as base for FakeContext.
     */
    private fun getSystemContext(): android.content.Context? {
        return try {
            // Method 1: Use ActivityThread.systemMain() or currentActivityThread()
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            
            // Try to get current activity thread first
            val currentActivityThread = try {
                val currentMethod = activityThreadClass.getMethod("currentActivityThread")
                currentMethod.invoke(null)
            } catch (e: Exception) {
                null
            }
            
            val activityThread = currentActivityThread ?: run {
                // Try systemMain() as fallback
                val systemMainMethod = activityThreadClass.getMethod("systemMain")
                systemMainMethod.invoke(null)
            }
            
            if (activityThread != null) {
                val getSystemContext = activityThreadClass.getMethod("getSystemContext")
                getSystemContext.invoke(activityThread) as? android.content.Context
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get system context via ActivityThread: ${e.message}")
            
            // Method 2: Try AppGlobals.getInitialApplication()
            try {
                val appGlobalsClass = Class.forName("android.app.AppGlobals")
                val getInitialApplication = appGlobalsClass.getMethod("getInitialApplication")
                getInitialApplication.invoke(null) as? android.content.Context
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to get context via AppGlobals: ${e2.message}")
                null
            }
        }
    }
    
    /**
     * Create a FakeContext that wraps a real system context, like scrcpy does.
     * This pretends to be com.android.shell package with shell UID.
     */
    private fun createFakeContext(baseContext: android.content.Context): android.content.Context {
        return object : android.content.ContextWrapper(baseContext) {
            override fun getPackageName(): String {
                return SHELL_PACKAGE_NAME
            }
            
            override fun getOpPackageName(): String {
                return SHELL_PACKAGE_NAME
            }
            
            override fun getAttributionTag(): String? {
                return null
            }
            
            override fun getApplicationContext(): android.content.Context {
                return this
            }
            
            // Override getAttributionSource for Android 12+
            @Suppress("NewApi")
            override fun getAttributionSource(): android.content.AttributionSource {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.content.AttributionSource.Builder(android.os.Process.SHELL_UID)
                        .setPackageName(SHELL_PACKAGE_NAME)
                        .build()
                } else {
                    super.getAttributionSource()
                }
            }
        }
    }
    
    companion object {
        private const val TAG = "DisplayService"
        private const val SHELL_PACKAGE_NAME = "com.android.shell"
        private const val VIDEO_BITRATE = 4_000_000  // 4 Mbps
        private const val VIDEO_FPS = 30
        private const val I_FRAME_INTERVAL = 10
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        
        // Virtual display flags (from android.hardware.display.DisplayManager)
        private const val VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 shl 0
        private const val VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 shl 1
        private const val VIRTUAL_DISPLAY_FLAG_SECURE = 1 shl 2
        private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 1 shl 4
        private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
        private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13  // Android 13+
        private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14  // Android 14+
        private const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15  // Android 14+
    }
    
    private fun createDisplayApi33(
        dmgInstance: Any,
        name: String,
        width: Int,
        height: Int,
        density: Int,
        flags: Int
    ): Int? {
        return try {
            val configBuilderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
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
            
            val virtualDisplayConfigClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val callbackClass = Class.forName("android.hardware.display.VirtualDisplay\$Callback")
            
            // Find the correct createVirtualDisplay method
            val methods = dmgInstance.javaClass.declaredMethods.filter { it.name == "createVirtualDisplay" }
            
            for (method in methods) {
                try {
                    method.isAccessible = true
                    val result = when (method.parameterCount) {
                        3 -> method.invoke(dmgInstance, config, null, null)
                        4 -> method.invoke(dmgInstance, null, config, null, null)
                        else -> continue
                    }
                    
                    if (result != null) {
                        val getDisplay = result.javaClass.getMethod("getDisplay")
                        val display = getDisplay.invoke(result)
                        val getDisplayId = display.javaClass.getMethod("getDisplayId")
                        val displayId = getDisplayId.invoke(display) as Int
                        
                        if (displayId > 0) {
                            activeStreams[displayId] = DisplayStream(
                                displayId = displayId,
                                width = width,
                                height = height,
                                virtualDisplay = result,
                                encoder = null,
                                inputSurface = null,
                                outputPipe = null
                            )
                            Log.i(TAG, "Created virtual display via API33: $displayId")
                            return displayId
                        }
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "createDisplayApi33 failed", e)
            null
        }
    }
    
    private fun createDisplayLegacy(
        dmgInstance: Any,
        name: String,
        width: Int,
        height: Int,
        density: Int,
        flags: Int
    ): Int? {
        // Implementation for older APIs
        return null
    }
    
    private fun createDisplayViaShell(width: Int, height: Int, density: Int): Int {
        try {
            // Try wm create-display
            val process = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "wm create-display $width $height $density 2>/dev/null")
            )
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            
            val match = Regex("""#(\d+)""").find(output)
            val displayId = match?.groupValues?.get(1)?.toIntOrNull()
            
            if (displayId != null && displayId > 0) {
                activeStreams[displayId] = DisplayStream(
                    displayId = displayId,
                    width = width,
                    height = height,
                    virtualDisplay = null,
                    encoder = null,
                    inputSurface = null,
                    outputPipe = null
                )
                Log.i(TAG, "Created display via shell: $displayId")
                return displayId
            }
        } catch (e: Exception) {
            Log.e(TAG, "createDisplayViaShell failed", e)
        }
        
        // Fallback: simulated display
        val simId = nextDisplayId++
        activeStreams[simId] = DisplayStream(
            displayId = simId,
            width = width,
            height = height,
            virtualDisplay = null,
            encoder = null,
            inputSurface = null,
            outputPipe = null
        )
        Log.i(TAG, "Created simulated display: $simId")
        return simId
    }
    
    private fun setupEncoder(displayId: Int, width: Int, height: Int) {
        val stream = activeStreams[displayId] ?: return
        
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
                }
            }
            
            val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            
            val inputSurface = encoder.createInputSurface()
            
            // Create pipe for streaming
            val pipes = ParcelFileDescriptor.createPipe()
            val readPipe = pipes[0]
            val writePipe = pipes[1]
            
            activeStreams[displayId] = stream.copy(
                encoder = encoder,
                inputSurface = inputSurface,
                outputPipe = readPipe
            )
            
            // Start encoding thread
            startEncodingThread(displayId, encoder, writePipe)
            
            encoder.start()
            stream.isStreaming.set(true)
            
            Log.d(TAG, "Encoder setup complete for display $displayId")
        } catch (e: Exception) {
            Log.e(TAG, "setupEncoder failed", e)
        }
    }
    
    private fun startEncodingThread(displayId: Int, encoder: MediaCodec, writePipe: ParcelFileDescriptor) {
        Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            val outputStream = FileOutputStream(writePipe.fileDescriptor)
            
            try {
                while (activeStreams[displayId]?.isStreaming?.get() == true) {
                    val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                    
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.get(data)
                            outputStream.write(data)
                        }
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Encoding thread error", e)
            } finally {
                try {
                    outputStream.close()
                    writePipe.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }.apply {
            name = "DisplayEncoder-$displayId"
            start()
        }
    }
    
    override fun getDisplayStream(displayId: Int): ParcelFileDescriptor? {
        return activeStreams[displayId]?.outputPipe
    }
    
    override fun destroyDisplay(displayId: Int) {
        Log.d(TAG, "destroyDisplay: $displayId")
        
        val stream = activeStreams.remove(displayId) ?: return
        
        stream.isStreaming.set(false)
        
        try {
            stream.encoder?.stop()
            stream.encoder?.release()
            stream.inputSurface?.release()
            stream.outputPipe?.close()
            
            // Release virtual display
            stream.virtualDisplay?.let { vd ->
                try {
                    val release = vd.javaClass.getMethod("release")
                    release.invoke(vd)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to release virtual display", e)
                }
            }
            
            // Try shell command
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "wm destroy-display $displayId 2>/dev/null"))
        } catch (e: Exception) {
            Log.e(TAG, "destroyDisplay error", e)
        }
    }
    
    override fun injectTouch(displayId: Int, action: Int, x: Float, y: Float): Boolean {
        return try {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            
            // Use InputManager to inject
            val imClass = Class.forName("android.hardware.input.InputManager")
            val getInstance = imClass.getMethod("getInstance")
            val im = getInstance.invoke(null)
            
            val injectMethod = imClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            
            // INJECT_INPUT_EVENT_MODE_ASYNC = 0
            val result = injectMethod.invoke(im, event, 0) as Boolean
            event.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "injectTouch failed", e)
            false
        }
    }
    
    override fun getActiveDisplayIds(): IntArray {
        return activeStreams.keys.toIntArray()
    }
    
    override fun startAppOnDisplay(displayId: Int, packageName: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", 
                    "cmd package resolve-activity --brief $packageName | tail -1 | xargs -I{} am start -n {} --display $displayId"
                )
            )
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "startAppOnDisplay failed", e)
            false
        }
    }
}
