/*
 * Copyright (C) 2024 AutoGLM
 *
 * Screen capture service running in Shizuku UserService with shell privileges.
 * Uses SurfaceControl hidden APIs for efficient screen capture without temp files.
 * 
 * This implementation is inspired by the scrcpy project:
 * https://github.com/Genymobile/scrcpy
 * 
 * scrcpy is licensed under the Apache License, Version 2.0:
 * Copyright (C) 2018 Genymobile
 * Copyright (C) 2018-2024 Romain Vimont
 * 
 * Key concepts derived from scrcpy:
 * - Using SurfaceControl.screenshot() via reflection for direct screen capture
 * - Hidden API exemption bypass using VMRuntime.setHiddenApiExemptions()
 * - Display token acquisition via SurfaceControl.getBuiltInDisplay()
 * 
 * Modifications from scrcpy approach:
 * - Simplified for single-frame screenshot (not video streaming)
 * - Returns encoded image bytes instead of raw frames
 * - Runs in Shizuku UserService instead of standalone server
 * - Added screencap fallback for maximum compatibility
 */

package com.autoglm.android.shizuku

import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import androidx.annotation.Keep
import com.autoglm.android.capture.DisplayInfoCompat
import com.autoglm.android.capture.SurfaceControlCompat
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader

class ScreenCaptureService : IScreenCaptureService.Stub {
    
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val DEFAULT_JPEG_QUALITY = 80
        private const val MAX_IMAGE_SIZE = 4 * 1024 * 1024 // 4MB limit for binder
    }
    
    @Volatile
    private var useFallback = false
    
    constructor() : super() {
        Log.d(TAG, "ScreenCaptureService created")
        initializeCapture()
    }
    
    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: android.content.Context) : super() {
        Log.d(TAG, "ScreenCaptureService created with context")
        initializeCapture()
    }
    
    private fun initializeCapture() {
        if (!SurfaceControlCompat.isAvailable()) {
            Log.w(TAG, "SurfaceControl not available, will use fallback")
            useFallback = true
        } else {
            Log.d(TAG, "SurfaceControl available, using direct capture")
        }
    }
    
    override fun destroy() {
        Log.d(TAG, "ScreenCaptureService destroy")
        System.exit(0)
    }
    
    override fun captureScreen(maxWidth: Int, maxHeight: Int, quality: Int): ScreenCaptureResult {
        Log.d(TAG, "captureScreen: maxWidth=$maxWidth, maxHeight=$maxHeight, quality=$quality")
        
        return try {
            if (!useFallback) {
                captureViaSurfaceControl(maxWidth, maxHeight, quality)
                    ?: run {
                        Log.w(TAG, "SurfaceControl capture failed, trying fallback")
                        useFallback = true
                        captureViaScreencap(maxWidth, maxHeight, quality)
                    }
            } else {
                captureViaScreencap(maxWidth, maxHeight, quality)
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureScreen failed", e)
            ScreenCaptureResult.error("Capture failed: ${e.message}")
        }
    }
    
    /**
     * Primary capture method using SurfaceControl hidden APIs.
     * This is the scrcpy-inspired approach for efficient capture.
     */
    private fun captureViaSurfaceControl(maxWidth: Int, maxHeight: Int, quality: Int): ScreenCaptureResult? {
        val displayInfo = DisplayInfoCompat.getDisplayInfo() ?: run {
            Log.w(TAG, "Failed to get display info")
            return null
        }
        
        val captureWidth = if (maxWidth > 0 && maxWidth < displayInfo.width) maxWidth else displayInfo.width
        val captureHeight = if (maxHeight > 0 && maxHeight < displayInfo.height) maxHeight else displayInfo.height
        
        val bitmap = SurfaceControlCompat.screenshot(
            displayToken = null,
            crop = null,
            width = captureWidth,
            height = captureHeight,
            rotation = 0
        ) ?: run {
            Log.w(TAG, "SurfaceControl.screenshot returned null")
            return null
        }
        
        return encodeBitmap(bitmap, displayInfo.rotation, quality)
    }
    
    /**
     * Fallback capture method using screencap command.
     * Used when SurfaceControl is not available or fails.
     */
    private fun captureViaScreencap(maxWidth: Int, maxHeight: Int, quality: Int): ScreenCaptureResult {
        val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p"))
        
        val imageBytes = process.inputStream.readBytes()
        val exitCode = process.waitFor()
        
        if (exitCode != 0 || imageBytes.isEmpty()) {
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
            return ScreenCaptureResult.error("screencap failed: $error")
        }
        
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return ScreenCaptureResult.error("Failed to decode screenshot")
        
        val displayInfo = DisplayInfoCompat.getDisplayInfo()
        val rotation = displayInfo?.rotation ?: 0
        
        val scaledBitmap = if (maxWidth > 0 || maxHeight > 0) {
            scaleBitmap(bitmap, maxWidth, maxHeight)
        } else {
            bitmap
        }
        
        return encodeBitmap(scaledBitmap, rotation, quality).also {
            if (scaledBitmap !== bitmap) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()
        }
    }
    
    private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        if ((maxWidth <= 0 || width <= maxWidth) && (maxHeight <= 0 || height <= maxHeight)) {
            return bitmap
        }
        
        val scaleWidth = if (maxWidth > 0) maxWidth.toFloat() / width else Float.MAX_VALUE
        val scaleHeight = if (maxHeight > 0) maxHeight.toFloat() / height else Float.MAX_VALUE
        val scale = minOf(scaleWidth, scaleHeight, 1f)
        
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    private fun encodeBitmap(bitmap: Bitmap, rotation: Int, quality: Int): ScreenCaptureResult {
        val outputStream = ByteArrayOutputStream()
        
        val format = if (quality <= 0) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val actualQuality = if (quality <= 0) 100 else quality.coerceIn(1, 100)
        
        bitmap.compress(format, actualQuality, outputStream)
        val imageData = outputStream.toByteArray()
        
        if (imageData.size > MAX_IMAGE_SIZE) {
            Log.w(TAG, "Image too large (${imageData.size} bytes), compressing further")
            outputStream.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
        }
        
        Log.d(TAG, "Captured: ${bitmap.width}x${bitmap.height}, size=${imageData.size} bytes")
        
        return ScreenCaptureResult.success(
            width = bitmap.width,
            height = bitmap.height,
            rotation = rotation,
            imageData = outputStream.toByteArray()
        )
    }
    
    override fun getDisplayInfo(): String {
        val info = DisplayInfoCompat.getDisplayInfo()
        return if (info != null) {
            "${info.width},${info.height},${info.rotation},${info.density}"
        } else {
            getDisplayInfoViaWm()
        }
    }
    
    private fun getDisplayInfoViaWm(): String {
        return try {
            val sizeProcess = Runtime.getRuntime().exec(arrayOf("wm", "size"))
            val sizeOutput = BufferedReader(InputStreamReader(sizeProcess.inputStream)).readText()
            sizeProcess.waitFor()
            
            val densityProcess = Runtime.getRuntime().exec(arrayOf("wm", "density"))
            val densityOutput = BufferedReader(InputStreamReader(densityProcess.inputStream)).readText()
            densityProcess.waitFor()
            
            val sizeMatch = """(\d+)x(\d+)""".toRegex().find(sizeOutput)
            val densityMatch = """(\d+)""".toRegex().find(densityOutput)
            
            val width = sizeMatch?.groupValues?.get(1)?.toInt() ?: 0
            val height = sizeMatch?.groupValues?.get(2)?.toInt() ?: 0
            val density = densityMatch?.groupValues?.get(1)?.toInt() ?: 0
            
            "$width,$height,0,$density"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get display info via wm", e)
            "0,0,0,0"
        }
    }
}
