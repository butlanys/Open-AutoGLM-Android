/*
 * Copyright (C) 2024 AutoGLM
 *
 * Display stream client that connects to DisplayService and renders video.
 */

package com.autoglm.android.display

import android.content.ComponentName
import android.content.ServiceConnection
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import com.autoglm.android.shizuku.IDisplayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

object DisplayStreamClient {
    
    private const val TAG = "DisplayStreamClient"
    private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    
    @Volatile
    private var displayService: IDisplayService? = null
    
    @Volatile
    private var isBinding = false
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val activeDecoders = mutableMapOf<Int, StreamDecoder>()
    
    data class StreamDecoder(
        val displayId: Int,
        val decoder: MediaCodec,
        val inputStream: FileInputStream,
        val isRunning: AtomicBoolean = AtomicBoolean(false)
    )
    
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            "com.autoglm.android",
            "com.autoglm.android.shizuku.DisplayService"
        )
    )
        .daemon(false)
        .processNameSuffix("display")
        .debuggable(true)
        .version(1)
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "onServiceConnected callback: name=$name, service=$service")
            if (service != null && service.pingBinder()) {
                displayService = IDisplayService.Stub.asInterface(service)
                _isConnected.value = true
                Log.d(TAG, "DisplayService interface obtained successfully")
            } else {
                Log.e(TAG, "Service binder is null or dead: service=$service, pingBinder=${service?.pingBinder()}")
            }
            isBinding = false
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "onServiceDisconnected: $name")
            displayService = null
            _isConnected.value = false
            isBinding = false
        }
    }
    
    // Shizuku-specific callback for when the binder is received/dead
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
    }
    
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        displayService = null
        _isConnected.value = false
    }
    
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "connect() called, current service=$displayService, isBinding=$isBinding")
        
        if (displayService != null && displayService!!.asBinder().pingBinder()) {
            Log.d(TAG, "Already connected to DisplayService")
            return@withContext true
        }
        
        // Reset state if previous connection was stale
        displayService = null
        _isConnected.value = false
        
        if (!Shizuku.pingBinder()) {
            Log.e(TAG, "Shizuku binder not available")
            return@withContext false
        }
        
        // Try connecting with retry logic due to Shizuku provider null bug
        // See: https://github.com/RikkaApps/Shizuku/issues/451
        repeat(3) { attempt ->
            Log.d(TAG, "Connection attempt ${attempt + 1}/3")
            
            if (!isBinding) {
                isBinding = true
                try {
                    Log.d(TAG, "Calling Shizuku.bindUserService...")
                    Shizuku.bindUserService(userServiceArgs, serviceConnection)
                    Log.d(TAG, "bindUserService called successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind DisplayService", e)
                    isBinding = false
                    return@repeat
                }
            }
            
            // Wait for connection
            Log.d(TAG, "Waiting for service connection...")
            val connected = withTimeoutOrNull(5000L) {
                while (displayService == null) {
                    delay(200)
                    if (!isBinding && displayService != null) break
                }
                displayService != null
            } ?: false
            
            if (connected) {
                Log.d(TAG, "Successfully connected to DisplayService")
                return@withContext true
            }
            
            Log.w(TAG, "Connection attempt ${attempt + 1} failed, retrying...")
            isBinding = false
            
            // Unbind and wait before retry
            try {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unbind for retry", e)
            }
            delay(500)
        }
        
        Log.e(TAG, "All connection attempts failed - this may be a Shizuku bug (provider null)")
        isBinding = false
        false
    }
    
    fun disconnect() {
        activeDecoders.values.forEach { stopDecoder(it) }
        activeDecoders.clear()
        
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind service", e)
        }
        displayService = null
        _isConnected.value = false
    }
    
    suspend fun createDisplay(width: Int, height: Int, density: Int, name: String): Int {
        Log.d(TAG, "createDisplay called: ${width}x${height}, density=$density, name=$name")
        
        val service = displayService ?: run {
            Log.d(TAG, "No existing service, attempting to connect...")
            if (!connect()) {
                Log.e(TAG, "Failed to connect to DisplayService")
                return -1
            }
            displayService ?: run {
                Log.e(TAG, "Service still null after connect")
                return -1
            }
        }
        
        Log.d(TAG, "Calling service.createDisplay...")
        return withContext(Dispatchers.IO) {
            try {
                val result = service.createDisplay(width, height, density, name)
                Log.d(TAG, "service.createDisplay returned: $result")
                result
            } catch (e: Exception) {
                Log.e(TAG, "createDisplay IPC failed", e)
                -1
            }
        }
    }
    
    suspend fun destroyDisplay(displayId: Int) {
        stopStream(displayId)
        
        val service = displayService ?: return
        withContext(Dispatchers.IO) {
            try {
                service.destroyDisplay(displayId)
            } catch (e: Exception) {
                Log.e(TAG, "destroyDisplay failed", e)
            }
        }
    }
    
    suspend fun startStream(displayId: Int, surface: Surface, width: Int, height: Int): Boolean {
        val service = displayService ?: return false
        
        return withContext(Dispatchers.IO) {
            try {
                val streamFd = service.getDisplayStream(displayId) ?: return@withContext false
                
                // Create decoder
                val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
                val decoder = MediaCodec.createDecoderByType(MIME_TYPE)
                decoder.configure(format, surface, null, 0)
                decoder.start()
                
                val inputStream = FileInputStream(streamFd.fileDescriptor)
                
                val streamDecoder = StreamDecoder(
                    displayId = displayId,
                    decoder = decoder,
                    inputStream = inputStream
                )
                streamDecoder.isRunning.set(true)
                activeDecoders[displayId] = streamDecoder
                
                // Start decoding thread
                startDecodingThread(streamDecoder)
                
                Log.d(TAG, "Stream started for display $displayId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "startStream failed", e)
                false
            }
        }
    }
    
    private fun startDecodingThread(streamDecoder: StreamDecoder) {
        Thread {
            val decoder = streamDecoder.decoder
            val inputStream = streamDecoder.inputStream
            val bufferInfo = MediaCodec.BufferInfo()
            val buffer = ByteArray(65536)
            
            try {
                while (streamDecoder.isRunning.get()) {
                    // Read from stream and feed to decoder
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                            inputBuffer?.clear()
                            inputBuffer?.put(buffer, 0, bytesRead)
                            decoder.queueInputBuffer(inputBufferIndex, 0, bytesRead, 0, 0)
                        }
                    }
                    
                    // Get decoded frames
                    val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferIndex >= 0) {
                        decoder.releaseOutputBuffer(outputBufferIndex, true) // Render to surface
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Decoding error", e)
            } finally {
                try {
                    inputStream.close()
                    decoder.stop()
                    decoder.release()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }.apply {
            name = "DisplayDecoder-${streamDecoder.displayId}"
            start()
        }
    }
    
    fun stopStream(displayId: Int) {
        val decoder = activeDecoders.remove(displayId) ?: return
        stopDecoder(decoder)
    }
    
    private fun stopDecoder(decoder: StreamDecoder) {
        decoder.isRunning.set(false)
    }
    
    suspend fun injectTouch(displayId: Int, action: Int, x: Float, y: Float): Boolean {
        val service = displayService ?: return false
        return withContext(Dispatchers.IO) {
            try {
                service.injectTouch(displayId, action, x, y)
            } catch (e: Exception) {
                Log.e(TAG, "injectTouch failed", e)
                false
            }
        }
    }
    
    suspend fun startAppOnDisplay(displayId: Int, packageName: String): Boolean {
        val service = displayService ?: return false
        return withContext(Dispatchers.IO) {
            try {
                service.startAppOnDisplay(displayId, packageName)
            } catch (e: Exception) {
                Log.e(TAG, "startAppOnDisplay failed", e)
                false
            }
        }
    }
    
    fun getActiveDisplayIds(): IntArray {
        return try {
            displayService?.activeDisplayIds ?: intArrayOf()
        } catch (e: Exception) {
            Log.e(TAG, "getActiveDisplayIds failed", e)
            intArrayOf()
        }
    }
}
