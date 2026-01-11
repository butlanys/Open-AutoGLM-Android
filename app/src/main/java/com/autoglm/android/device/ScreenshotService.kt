/*
 * Copyright (C) 2024 AutoGLM
 *
 * Screenshot service with dual capture strategy:
 * 1. Primary: SurfaceControl-based capture (scrcpy-inspired, fast, no temp files)
 * 2. Fallback: Shell screencap command (slower but more compatible)
 * 
 * The SurfaceControl approach is inspired by the scrcpy project:
 * https://github.com/Genymobile/scrcpy
 * Copyright (C) 2018 Genymobile, Copyright (C) 2018-2024 Romain Vimont
 * Licensed under Apache License 2.0
 */

package com.autoglm.android.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.autoglm.android.shizuku.ScreenCaptureExecutor
import com.autoglm.android.shizuku.ShizukuExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val TAG = "ScreenshotService"

data class Screenshot(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val base64Data: String
)

enum class CaptureMethod {
    SURFACE_CONTROL,
    SHELL_SCREENCAP
}

object ScreenshotService {
    
    @Volatile
    private var preferredMethod = CaptureMethod.SURFACE_CONTROL
    
    @Volatile
    private var surfaceControlFailCount = 0
    
    private const val MAX_FAIL_COUNT = 3
    
    suspend fun capture(quality: Int = 80): Screenshot? = withContext(Dispatchers.IO) {
        try {
            when (preferredMethod) {
                CaptureMethod.SURFACE_CONTROL -> {
                    captureViaSurfaceControl(quality)?.also {
                        surfaceControlFailCount = 0
                    } ?: run {
                        surfaceControlFailCount++
                        if (surfaceControlFailCount >= MAX_FAIL_COUNT) {
                            Log.w(TAG, "SurfaceControl failed $MAX_FAIL_COUNT times, switching to fallback")
                            preferredMethod = CaptureMethod.SHELL_SCREENCAP
                        }
                        captureViaShell()
                    }
                }
                CaptureMethod.SHELL_SCREENCAP -> captureViaShell()
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            null
        }
    }
    
    private suspend fun captureViaSurfaceControl(quality: Int): Screenshot? {
        val result = ScreenCaptureExecutor.capture(quality = quality)
        
        if (!result.success || result.imageData == null) {
            Log.w(TAG, "SurfaceControl capture failed: ${result.errorMessage}")
            return null
        }
        
        val bitmap = BitmapFactory.decodeByteArray(result.imageData, 0, result.imageData.size)
            ?: return null
        
        val base64 = Base64.encodeToString(result.imageData, Base64.NO_WRAP)
        
        Log.d(TAG, "Captured via SurfaceControl: ${result.width}x${result.height}")
        
        return Screenshot(
            bitmap = bitmap,
            width = result.width,
            height = result.height,
            base64Data = base64
        )
    }
    
    private suspend fun captureViaShell(): Screenshot? {
        val result = ShizukuExecutor.execute("screencap -p")
        if (!result.success) {
            Log.e(TAG, "Shell screencap failed: ${result.error}")
            return null
        }
        
        val bytes = result.output.toByteArray(Charsets.ISO_8859_1)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return null
        
        val base64 = bitmapToBase64(bitmap)
        
        Log.d(TAG, "Captured via shell: ${bitmap.width}x${bitmap.height}")
        
        return Screenshot(
            bitmap = bitmap,
            width = bitmap.width,
            height = bitmap.height,
            base64Data = base64
        )
    }
    
    suspend fun captureToFile(path: String): Boolean = withContext(Dispatchers.IO) {
        val result = ShizukuExecutor.execute("screencap -p $path")
        result.success
    }
    
    suspend fun captureBase64(quality: Int = 80): String? = withContext(Dispatchers.IO) {
        try {
            val result = ScreenCaptureExecutor.capture(quality = quality)
            if (result.success && result.imageData != null) {
                Base64.encodeToString(result.imageData, Base64.NO_WRAP)
            } else {
                captureBase64ViaShell()
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureBase64 failed", e)
            captureBase64ViaShell()
        }
    }
    
    private suspend fun captureBase64ViaShell(): String? {
        val tempFile = "/data/local/tmp/autoglm_screenshot.png"
        val captureResult = ShizukuExecutor.execute("screencap -p $tempFile")
        if (!captureResult.success) return null
        
        val base64Result = ShizukuExecutor.execute("base64 -w 0 $tempFile")
        ShizukuExecutor.execute("rm $tempFile")
        
        return if (base64Result.success) base64Result.output else null
    }
    
    suspend fun captureWithDimensions(quality: Int = 80): Triple<String, Int, Int>? = withContext(Dispatchers.IO) {
        try {
            val result = ScreenCaptureExecutor.capture(quality = quality)
            if (result.success && result.imageData != null) {
                val base64 = Base64.encodeToString(result.imageData, Base64.NO_WRAP)
                Log.d(TAG, "captureWithDimensions via SurfaceControl: ${result.width}x${result.height}")
                Triple(base64, result.width, result.height)
            } else {
                Log.w(TAG, "SurfaceControl failed: ${result.errorMessage}, using fallback")
                captureWithDimensionsViaShell()
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureWithDimensions failed", e)
            captureWithDimensionsViaShell()
        }
    }
    
    private suspend fun captureWithDimensionsViaShell(): Triple<String, Int, Int>? {
        val tempFile = "/data/local/tmp/autoglm_screenshot.png"
        val base64File = "/data/local/tmp/autoglm_screenshot.b64"
        
        Log.d(TAG, "captureWithDimensionsViaShell: Taking screenshot...")
        val captureResult = ShizukuExecutor.execute("screencap -p $tempFile")
        if (!captureResult.success) {
            Log.e(TAG, "screencap failed: ${captureResult.error}")
            return null
        }
        
        val sizeResult = ShizukuExecutor.execute("wm size")
        val dimensions = parseDimensions(sizeResult.output)
        Log.d(TAG, "dimensions=$dimensions")
        
        val base64WriteResult = ShizukuExecutor.execute("base64 -w 0 $tempFile > $base64File")
        if (!base64WriteResult.success) {
            Log.e(TAG, "base64 write failed: ${base64WriteResult.error}")
            ShizukuExecutor.execute("rm $tempFile")
            return null
        }
        
        ShizukuExecutor.execute("chmod 644 $base64File")
        
        val base64Content = try {
            java.io.File(base64File).readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read base64 file", e)
            null
        }
        
        ShizukuExecutor.execute("rm $tempFile $base64File")
        
        return if (base64Content != null && dimensions != null) {
            Log.d(TAG, "Success via shell, base64 length=${base64Content.length}")
            Triple(base64Content, dimensions.first, dimensions.second)
        } else {
            Log.e(TAG, "Failed: base64=${base64Content != null}, dimensions=$dimensions")
            null
        }
    }
    
    private fun parseDimensions(wmOutput: String): Pair<Int, Int>? {
        val regex = """(\d+)x(\d+)""".toRegex()
        val match = regex.find(wmOutput) ?: return null
        val (width, height) = match.destructured
        return width.toInt() to height.toInt()
    }
    
    private fun bitmapToBase64(bitmap: Bitmap, quality: Int = 80): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    
    fun resetCaptureMethod() {
        preferredMethod = CaptureMethod.SURFACE_CONTROL
        surfaceControlFailCount = 0
        Log.d(TAG, "Capture method reset to SurfaceControl")
    }
    
    fun getCurrentMethod(): CaptureMethod = preferredMethod
}
