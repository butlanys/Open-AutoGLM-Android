/*
 * Copyright (C) 2024 AutoGLM
 *
 * Screenshot service for virtual displays.
 * Supports capturing from specific display IDs.
 */

package com.autoglm.android.display

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import com.autoglm.android.capture.DisplayInfoCompat
import com.autoglm.android.capture.SurfaceControlCompat
import com.autoglm.android.shizuku.ShizukuExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object DisplayScreenshotService {
    
    private const val TAG = "DisplayScreenshotService"
    
    data class DisplayScreenshot(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val base64Data: String,
        val displayId: Int
    )
    
    suspend fun capture(
        displayId: Int = 0,
        quality: Int = 80
    ): DisplayScreenshot? = withContext(Dispatchers.IO) {
        try {
            if (displayId == 0) {
                captureMainDisplay(quality)
            } else {
                captureVirtualDisplay(displayId, quality)
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed for display $displayId", e)
            null
        }
    }
    
    suspend fun captureWithDimensions(
        displayId: Int = 0,
        quality: Int = 80
    ): Triple<String, Int, Int>? = withContext(Dispatchers.IO) {
        val screenshot = capture(displayId, quality) ?: return@withContext null
        Triple(screenshot.base64Data, screenshot.width, screenshot.height)
    }
    
    private suspend fun captureMainDisplay(quality: Int): DisplayScreenshot? {
        val displayInfo = DisplayInfoCompat.getDisplayInfo()
        val bitmap = SurfaceControlCompat.screenshot()
        
        if (bitmap != null) {
            val base64 = bitmapToBase64(bitmap, quality)
            return DisplayScreenshot(
                bitmap = bitmap,
                width = bitmap.width,
                height = bitmap.height,
                base64Data = base64,
                displayId = 0
            )
        }
        
        return captureViaShell(0, quality)
    }
    
    private suspend fun captureVirtualDisplay(displayId: Int, quality: Int): DisplayScreenshot? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            captureViaSurfaceControlForDisplay(displayId, quality)?.let { return it }
        }
        
        return captureViaShell(displayId, quality)
    }
    
    private fun captureViaSurfaceControlForDisplay(displayId: Int, quality: Int): DisplayScreenshot? {
        return try {
            val displayToken = getDisplayToken(displayId) ?: return null
            
            val bitmap = SurfaceControlCompat.screenshot(
                displayToken = displayToken,
                crop = null,
                width = 0,
                height = 0,
                rotation = 0
            ) ?: return null
            
            val base64 = bitmapToBase64(bitmap, quality)
            
            DisplayScreenshot(
                bitmap = bitmap,
                width = bitmap.width,
                height = bitmap.height,
                base64Data = base64,
                displayId = displayId
            )
        } catch (e: Exception) {
            Log.e(TAG, "captureViaSurfaceControlForDisplay failed", e)
            null
        }
    }
    
    private fun getDisplayToken(displayId: Int): IBinder? {
        return try {
            if (displayId == 0) {
                SurfaceControlCompat.getBuiltInDisplay()
            } else {
                val displayIds = SurfaceControlCompat.getPhysicalDisplayIds()
                if (displayIds != null && displayId < displayIds.size) {
                    SurfaceControlCompat.getPhysicalDisplayToken(displayIds[displayId])
                } else {
                    getVirtualDisplayToken(displayId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getDisplayToken failed", e)
            null
        }
    }
    
    @Suppress("UNCHECKED_CAST")
    private fun getVirtualDisplayToken(displayId: Int): IBinder? {
        return try {
            val surfaceFlinger = Class.forName("android.view.SurfaceControl")
            
            val method = try {
                surfaceFlinger.getMethod("getDisplayToken", Int::class.javaPrimitiveType)
            } catch (e: NoSuchMethodException) {
                return null
            }
            
            method.invoke(null, displayId) as? IBinder
        } catch (e: Exception) {
            Log.w(TAG, "getVirtualDisplayToken failed: ${e.message}")
            null
        }
    }
    
    private suspend fun captureViaShell(displayId: Int, quality: Int): DisplayScreenshot? {
        val tempFile = "/data/local/tmp/autoglm_display_${displayId}_screenshot.png"
        
        // Try to capture from specific display
        val captureCmd = if (displayId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "screencap -d $displayId -p $tempFile"
        } else {
            "screencap -p $tempFile"
        }
        
        Log.d(TAG, "Executing: $captureCmd")
        val captureResult = ShizukuExecutor.execute(captureCmd)
        
        if (!captureResult.success) {
            Log.w(TAG, "screencap -d $displayId failed: ${captureResult.error}, trying without -d")
            // Only fallback if this is a secondary display that failed
            if (displayId > 0) {
                // For secondary displays, try using DISPLAY environment variable
                val altCmd = "DISPLAY=:$displayId screencap -p $tempFile 2>/dev/null"
                val altResult = ShizukuExecutor.execute(altCmd)
                if (!altResult.success) {
                    Log.e(TAG, "All screencap attempts failed for display $displayId")
                    return null
                }
            } else {
                val fallbackResult = ShizukuExecutor.execute("screencap -p $tempFile")
                if (!fallbackResult.success) {
                    Log.e(TAG, "screencap failed: ${captureResult.error}")
                    return null
                }
            }
        }
        
        // Read file in chunks to avoid Binder buffer overflow
        val bitmap = readScreenshotFile(tempFile)
        
        // Clean up temp file
        ShizukuExecutor.execute("rm $tempFile")
        
        if (bitmap == null) {
            Log.e(TAG, "Failed to read screenshot file")
            return null
        }
        
        val finalBase64 = bitmapToBase64(bitmap, quality)
        
        return DisplayScreenshot(
            bitmap = bitmap,
            width = bitmap.width,
            height = bitmap.height,
            base64Data = finalBase64,
            displayId = displayId
        )
    }
    
    private suspend fun readScreenshotFile(filePath: String): Bitmap? {
        // Get file size first
        val sizeResult = ShizukuExecutor.execute("stat -c %s $filePath 2>/dev/null || wc -c < $filePath")
        val fileSize = sizeResult.output.trim().toLongOrNull() ?: 0L
        
        if (fileSize <= 0) {
            Log.e(TAG, "Screenshot file is empty or doesn't exist")
            return null
        }
        
        // For small files (< 500KB), try direct base64
        if (fileSize < 500 * 1024) {
            val base64Result = ShizukuExecutor.execute("base64 -w 0 $filePath")
            if (base64Result.success && base64Result.output.isNotEmpty()) {
                return try {
                    val imageBytes = Base64.decode(base64Result.output.trim(), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode base64", e)
                    null
                }
            }
        }
        
        // For larger files, read in chunks
        return readFileInChunks(filePath, fileSize)
    }
    
    private suspend fun readFileInChunks(filePath: String, fileSize: Long): Bitmap? {
        val chunkSize = 256 * 1024  // 256KB per chunk to stay within Binder limits
        val chunks = mutableListOf<ByteArray>()
        var offset = 0L
        
        while (offset < fileSize) {
            val remaining = fileSize - offset
            val currentChunkSize = minOf(chunkSize.toLong(), remaining).toInt()
            
            // Use dd to read specific chunk and base64 encode it
            val cmd = "dd if=$filePath bs=1 skip=$offset count=$currentChunkSize 2>/dev/null | base64 -w 0"
            val result = ShizukuExecutor.execute(cmd)
            
            if (!result.success || result.output.isEmpty()) {
                Log.e(TAG, "Failed to read chunk at offset $offset")
                return null
            }
            
            try {
                val chunkData = Base64.decode(result.output.trim(), Base64.DEFAULT)
                chunks.add(chunkData)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode chunk at offset $offset", e)
                return null
            }
            
            offset += currentChunkSize
        }
        
        // Combine all chunks
        val totalSize = chunks.sumOf { it.size }
        val combinedData = ByteArray(totalSize)
        var pos = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, combinedData, pos, chunk.size)
            pos += chunk.size
        }
        
        return BitmapFactory.decodeByteArray(combinedData, 0, combinedData.size)
    }
    
    private fun bitmapToBase64(bitmap: Bitmap, quality: Int = 80): String {
        val outputStream = ByteArrayOutputStream()
        val format = if (quality <= 0) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val actualQuality = if (quality <= 0) 100 else quality.coerceIn(1, 100)
        bitmap.compress(format, actualQuality, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
    
    suspend fun getDisplayDimensions(displayId: Int): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        if (displayId == 0) {
            val info = DisplayInfoCompat.getDisplayInfo()
            if (info != null) {
                return@withContext info.width to info.height
            }
        }
        
        val displayInfo = VirtualDisplayManager.getDisplayInfo(displayId)
        if (displayInfo != null) {
            return@withContext displayInfo.width to displayInfo.height
        }
        
        val result = ShizukuExecutor.execute("dumpsys display | grep -A5 'mDisplayId=$displayId'")
        if (!result.success) return@withContext null
        
        val match = Regex("""(\d+)\s*x\s*(\d+)""").find(result.output)
        match?.let {
            it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }?.let { (w, h) ->
            if (w != null && h != null) w to h else null
        }
    }
}
