/*
 * Copyright (C) 2024 AutoGLM
 *
 * SurfaceControl wrapper using reflection to access hidden Android APIs.
 * 
 * This code is inspired by and partially derived from the scrcpy project:
 * https://github.com/Genymobile/scrcpy
 * 
 * scrcpy is licensed under the Apache License, Version 2.0:
 * Copyright (C) 2018 Genymobile
 * Copyright (C) 2018-2024 Romain Vimont
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * Modifications:
 * - Adapted for Kotlin
 * - Simplified for screenshot-only use case (no video streaming)
 * - Added fallback chain for different Android versions
 */

package com.autoglm.android.capture

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Method

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object SurfaceControlCompat {
    
    private const val TAG = "SurfaceControlCompat"
    
    private val surfaceControlClass: Class<*>? by lazy {
        try {
            Class.forName("android.view.SurfaceControl")
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "SurfaceControl class not found", e)
            null
        }
    }
    
    private val displayClass: Class<*>? by lazy {
        try {
            Class.forName("android.view.Display")
        } catch (e: ClassNotFoundException) {
            null
        }
    }
    
    private var cachedScreenshotMethod: Method? = null
    private var cachedScreenshotMethodType: ScreenshotMethodType? = null
    
    private enum class ScreenshotMethodType {
        SCREENSHOT_HARDWARE_BUFFER,      // API 31+: screenshot(displayToken) -> ScreenshotHardwareBuffer
        SCREENSHOT_BITMAP_RECT_ROTATION, // API 28-30: screenshot(Rect, width, height, rotation) -> Bitmap
        SCREENSHOT_BITMAP_RECT,          // API 26-27: screenshot(Rect, width, height) -> Bitmap  
        SCREENSHOT_BITMAP_SIZE,          // Fallback: screenshot(width, height) -> Bitmap
    }
    
    init {
        applyHiddenApiExemptions()
    }
    
    /**
     * Apply hidden API exemptions to bypass Android's hidden API restrictions.
     * This approach is derived from scrcpy's server implementation.
     * 
     * Reference: https://github.com/Genymobile/scrcpy/blob/main/server/src/main/java/com/genymobile/scrcpy/wrappers/Workarounds.java
     */
    private fun applyHiddenApiExemptions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }
        
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntimeMethod = vmRuntimeClass.getDeclaredMethod("getRuntime")
            val setHiddenApiExemptionsMethod = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java
            )
            
            val vmRuntime = getRuntimeMethod.invoke(null)
            setHiddenApiExemptionsMethod.invoke(vmRuntime, arrayOf("L") as Any)
            
            Log.d(TAG, "Hidden API exemptions applied successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply hidden API exemptions: ${e.message}")
        }
    }
    
    /**
     * Get the built-in display token.
     * Derived from scrcpy's SurfaceControl.getBuiltInDisplay() implementation.
     */
    fun getBuiltInDisplay(): IBinder? {
        val clazz = surfaceControlClass ?: return null
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val method = clazz.getMethod("getInternalDisplayToken")
                method.invoke(null) as? IBinder
            } else {
                val method = clazz.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType)
                method.invoke(null, 0) as? IBinder
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get built-in display", e)
            null
        }
    }
    
    /**
     * Get physical display IDs (API 29+).
     */
    fun getPhysicalDisplayIds(): LongArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val clazz = surfaceControlClass ?: return null
        
        return try {
            val method = clazz.getMethod("getPhysicalDisplayIds")
            method.invoke(null) as? LongArray
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get physical display IDs", e)
            null
        }
    }
    
    /**
     * Get physical display token by ID (API 29+).
     */
    fun getPhysicalDisplayToken(displayId: Long): IBinder? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val clazz = surfaceControlClass ?: return null
        
        return try {
            val method = clazz.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            method.invoke(null, displayId) as? IBinder
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get physical display token", e)
            null
        }
    }
    
    /**
     * Capture screenshot using SurfaceControl hidden APIs.
     * This method tries multiple approaches based on Android version.
     * 
     * @param displayToken Display token (null for default display)
     * @param crop Crop rectangle (null for full screen)
     * @param width Desired width (0 for native)
     * @param height Desired height (0 for native)
     * @param rotation Display rotation (0, 1, 2, 3)
     * @return Captured Bitmap or null on failure
     */
    fun screenshot(
        displayToken: IBinder? = null,
        crop: Rect? = null,
        width: Int = 0,
        height: Int = 0,
        rotation: Int = 0
    ): Bitmap? {
        val clazz = surfaceControlClass ?: return null
        val token = displayToken ?: getBuiltInDisplay() ?: return null
        
        cachedScreenshotMethod?.let { method ->
            return invokeScreenshotMethod(method, cachedScreenshotMethodType!!, token, crop, width, height, rotation)
        }
        
        return tryScreenshotMethods(clazz, token, crop, width, height, rotation)
    }
    
    private fun tryScreenshotMethods(
        clazz: Class<*>,
        token: IBinder,
        crop: Rect?,
        width: Int,
        height: Int,
        rotation: Int
    ): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tryScreenshotHardwareBuffer(clazz, token)?.let { bitmap ->
                cacheMethod(clazz, ScreenshotMethodType.SCREENSHOT_HARDWARE_BUFFER)
                return bitmap
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tryScreenshotBitmapRectRotation(clazz, crop, width, height, rotation)?.let { bitmap ->
                cacheMethod(clazz, ScreenshotMethodType.SCREENSHOT_BITMAP_RECT_ROTATION)
                return bitmap
            }
        }
        
        tryScreenshotBitmapRect(clazz, crop, width, height)?.let { bitmap ->
            cacheMethod(clazz, ScreenshotMethodType.SCREENSHOT_BITMAP_RECT)
            return bitmap
        }
        
        tryScreenshotBitmapSize(clazz, width, height)?.let { bitmap ->
            cacheMethod(clazz, ScreenshotMethodType.SCREENSHOT_BITMAP_SIZE)
            return bitmap
        }
        
        Log.e(TAG, "All screenshot methods failed")
        return null
    }
    
    private fun cacheMethod(clazz: Class<*>, type: ScreenshotMethodType) {
        try {
            cachedScreenshotMethod = when (type) {
                ScreenshotMethodType.SCREENSHOT_HARDWARE_BUFFER -> 
                    clazz.getMethod("screenshot", IBinder::class.java)
                ScreenshotMethodType.SCREENSHOT_BITMAP_RECT_ROTATION ->
                    clazz.getMethod("screenshot", Rect::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                ScreenshotMethodType.SCREENSHOT_BITMAP_RECT ->
                    clazz.getMethod("screenshot", Rect::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                ScreenshotMethodType.SCREENSHOT_BITMAP_SIZE ->
                    clazz.getMethod("screenshot", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            }
            cachedScreenshotMethodType = type
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache screenshot method", e)
        }
    }
    
    private fun invokeScreenshotMethod(
        method: Method,
        type: ScreenshotMethodType,
        token: IBinder,
        crop: Rect?,
        width: Int,
        height: Int,
        rotation: Int
    ): Bitmap? {
        return try {
            when (type) {
                ScreenshotMethodType.SCREENSHOT_HARDWARE_BUFFER -> {
                    val result = method.invoke(null, token) ?: return null
                    extractBitmapFromHardwareBuffer(result)
                }
                ScreenshotMethodType.SCREENSHOT_BITMAP_RECT_ROTATION -> {
                    method.invoke(null, crop ?: Rect(), width, height, rotation) as? Bitmap
                }
                ScreenshotMethodType.SCREENSHOT_BITMAP_RECT -> {
                    method.invoke(null, crop ?: Rect(), width, height) as? Bitmap
                }
                ScreenshotMethodType.SCREENSHOT_BITMAP_SIZE -> {
                    method.invoke(null, width, height) as? Bitmap
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to invoke cached screenshot method", e)
            cachedScreenshotMethod = null
            cachedScreenshotMethodType = null
            null
        }
    }
    
    /**
     * API 31+ (Android 12): screenshot(displayToken) -> ScreenshotHardwareBuffer
     */
    private fun tryScreenshotHardwareBuffer(clazz: Class<*>, token: IBinder): Bitmap? {
        return try {
            val method = clazz.getMethod("screenshot", IBinder::class.java)
            val result = method.invoke(null, token) ?: return null
            extractBitmapFromHardwareBuffer(result)
        } catch (e: Exception) {
            Log.d(TAG, "ScreenshotHardwareBuffer method not available: ${e.message}")
            null
        }
    }
    
    /**
     * Extract Bitmap from ScreenshotHardwareBuffer (API 31+).
     */
    @SuppressLint("WrongConstant")
    private fun extractBitmapFromHardwareBuffer(screenshotResult: Any): Bitmap? {
        return try {
            val getHardwareBufferMethod = screenshotResult.javaClass.getMethod("getHardwareBuffer")
            val hardwareBuffer = getHardwareBufferMethod.invoke(screenshotResult) as? HardwareBuffer
                ?: return null
            
            val getColorSpaceMethod = try {
                screenshotResult.javaClass.getMethod("getColorSpace")
            } catch (e: NoSuchMethodException) {
                null
            }
            val colorSpace = getColorSpaceMethod?.invoke(screenshotResult) as? android.graphics.ColorSpace
            
            val bitmap = if (colorSpace != null) {
                Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
            } else {
                Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
            }
            
            hardwareBuffer.close()
            
            bitmap?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract bitmap from HardwareBuffer", e)
            null
        }
    }
    
    /**
     * API 28-30 (Android 9-11): screenshot(Rect, width, height, rotation) -> Bitmap
     */
    private fun tryScreenshotBitmapRectRotation(
        clazz: Class<*>,
        crop: Rect?,
        width: Int,
        height: Int,
        rotation: Int
    ): Bitmap? {
        return try {
            val method = clazz.getMethod(
                "screenshot",
                Rect::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            method.invoke(null, crop ?: Rect(), width, height, rotation) as? Bitmap
        } catch (e: Exception) {
            Log.d(TAG, "screenshot(Rect, w, h, rotation) not available: ${e.message}")
            null
        }
    }
    
    /**
     * API 26-27 (Android 8.0-8.1): screenshot(Rect, width, height) -> Bitmap
     */
    private fun tryScreenshotBitmapRect(clazz: Class<*>, crop: Rect?, width: Int, height: Int): Bitmap? {
        return try {
            val method = clazz.getMethod(
                "screenshot",
                Rect::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            method.invoke(null, crop ?: Rect(), width, height) as? Bitmap
        } catch (e: Exception) {
            Log.d(TAG, "screenshot(Rect, w, h) not available: ${e.message}")
            null
        }
    }
    
    /**
     * Fallback: screenshot(width, height) -> Bitmap
     */
    private fun tryScreenshotBitmapSize(clazz: Class<*>, width: Int, height: Int): Bitmap? {
        return try {
            val method = clazz.getMethod(
                "screenshot",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            method.invoke(null, width, height) as? Bitmap
        } catch (e: Exception) {
            Log.d(TAG, "screenshot(w, h) not available: ${e.message}")
            null
        }
    }
    
    /**
     * Check if SurfaceControl screenshot is available on this device.
     */
    fun isAvailable(): Boolean {
        return surfaceControlClass != null && getBuiltInDisplay() != null
    }
}
