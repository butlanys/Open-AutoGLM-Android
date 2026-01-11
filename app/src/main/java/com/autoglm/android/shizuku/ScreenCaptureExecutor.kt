/*
 * Copyright (C) 2024 AutoGLM
 *
 * Executor for screen capture service via Shizuku.
 * Manages connection lifecycle and provides suspend functions for capture.
 */

package com.autoglm.android.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

private const val TAG = "ScreenCaptureExecutor"

object ScreenCaptureExecutor {
    
    @Volatile
    private var captureService: IScreenCaptureService? = null
    
    @Volatile
    private var isBinding = false
    
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            "com.autoglm.android",
            ScreenCaptureService::class.java.name
        )
    )
        .daemon(false)
        .processNameSuffix("capture")
        .debuggable(true)
        .version(2)
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "ScreenCaptureService connected: $name")
            if (service != null && service.pingBinder()) {
                captureService = IScreenCaptureService.Stub.asInterface(service)
                Log.d(TAG, "ScreenCaptureService interface obtained")
            }
            isBinding = false
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "ScreenCaptureService disconnected")
            captureService = null
            isBinding = false
        }
    }
    
    suspend fun capture(
        maxWidth: Int = 0,
        maxHeight: Int = 0,
        quality: Int = 80
    ): ScreenCaptureResult = withContext(Dispatchers.IO) {
        if (!ShizukuManager.checkPermission()) {
            Log.e(TAG, "Shizuku permission not granted")
            return@withContext ScreenCaptureResult.error("Shizuku permission not granted")
        }
        
        val service = getService() ?: return@withContext ScreenCaptureResult.error(
            "ScreenCaptureService connection failed"
        )
        
        try {
            service.captureScreen(maxWidth, maxHeight, quality)
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            captureService = null
            ScreenCaptureResult.error("Capture failed: ${e.message}")
        }
    }
    
    suspend fun getDisplayInfo(): DisplayInfo? = withContext(Dispatchers.IO) {
        if (!ShizukuManager.checkPermission()) return@withContext null
        
        val service = getService() ?: return@withContext null
        
        try {
            val info = service.displayInfo
            parseDisplayInfo(info)
        } catch (e: Exception) {
            Log.e(TAG, "getDisplayInfo failed", e)
            captureService = null
            null
        }
    }
    
    private fun parseDisplayInfo(info: String): DisplayInfo? {
        val parts = info.split(",")
        if (parts.size < 4) return null
        return DisplayInfo(
            width = parts[0].toIntOrNull() ?: return null,
            height = parts[1].toIntOrNull() ?: return null,
            rotation = parts[2].toIntOrNull() ?: 0,
            density = parts[3].toIntOrNull() ?: 0
        )
    }
    
    private suspend fun getService(): IScreenCaptureService? {
        captureService?.let { return it }
        
        if (!isBinding) {
            bindService()
        }
        
        val connected = withTimeoutOrNull(5000L) {
            while (captureService == null) {
                delay(50)
            }
            true
        } ?: false
        
        if (!connected) {
            Log.e(TAG, "ScreenCaptureService connection timeout")
            isBinding = false
            return null
        }
        
        return captureService
    }
    
    private fun bindService() {
        if (isBinding) return
        isBinding = true
        Log.d(TAG, "Binding ScreenCaptureService...")
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind ScreenCaptureService", e)
            isBinding = false
        }
    }
    
    fun unbindService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind service", e)
        }
        captureService = null
    }
    
    data class DisplayInfo(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val density: Int
    )
}
