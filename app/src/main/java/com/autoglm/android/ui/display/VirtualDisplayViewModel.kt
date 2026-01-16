/*
 * Copyright (C) 2024 AutoGLM
 *
 * ViewModel for Virtual Display Manager UI.
 * Manages virtual display state with both screenshot and streaming modes.
 */

package com.autoglm.android.ui.display

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.autoglm.android.display.DisplayScreenshotService
import com.autoglm.android.display.DisplayStreamClient
import com.autoglm.android.display.VirtualDisplayManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DisplayPreview(
    val displayInfo: VirtualDisplayManager.VirtualDisplayInfo,
    val previewBitmap: Bitmap? = null,
    val lastUpdateTime: Long = 0,
    val isStreaming: Boolean = false
)

data class VirtualDisplayUiState(
    val displays: List<DisplayPreview> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedDisplay: DisplayPreview? = null,
    val isCreatingDisplay: Boolean = false,
    val streamingEnabled: Boolean = false,
    val isStreamServiceConnected: Boolean = false
)

class VirtualDisplayViewModel : ViewModel() {
    
    companion object {
        private const val TAG = "VirtualDisplayVM"
        private const val PREVIEW_UPDATE_INTERVAL_MS = 2500L
        private const val PREVIEW_QUALITY = 50
        
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return VirtualDisplayViewModel() as T
            }
        }
    }
    
    private val _uiState = MutableStateFlow(VirtualDisplayUiState())
    val uiState: StateFlow<VirtualDisplayUiState> = _uiState.asStateFlow()
    
    private var previewUpdateJob: Job? = null
    private var isScreenVisible = false
    
    init {
        refreshDisplays()
    }
    
    fun onScreenVisible() {
        isScreenVisible = true
        startPreviewUpdates()
    }
    
    fun onScreenHidden() {
        isScreenVisible = false
        stopPreviewUpdates()
    }
    
    fun refreshDisplays() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            
            try {
                // Get both internal and system displays
                val internalDisplays = VirtualDisplayManager.getActiveDisplays()
                val systemDisplays = VirtualDisplayManager.getDisplayList()
                
                // Merge: prefer internal info but include system displays not in internal list
                val internalIds = internalDisplays.map { it.displayId }.toSet()
                val allDisplays = internalDisplays + systemDisplays.filter { it.displayId !in internalIds }
                
                val previews = allDisplays.map { displayInfo ->
                    DisplayPreview(displayInfo = displayInfo)
                }
                
                _uiState.update { it.copy(displays = previews, isLoading = false) }
                
                if (isScreenVisible) {
                    updateAllPreviews()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh displays", e)
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = "获取显示列表失败: ${e.message}"
                    ) 
                }
            }
        }
    }
    
    fun clearSystemOverlays() {
        viewModelScope.launch {
            try {
                VirtualDisplayManager.clearOverlayDisplays()
                Log.i(TAG, "Cleared system overlay displays")
                delay(500)
                refreshDisplays()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear overlays", e)
                _uiState.update { 
                    it.copy(error = "清理系统叠加显示失败: ${e.message}") 
                }
            }
        }
    }
    
    fun createDisplay(width: Int, height: Int, density: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingDisplay = true, error = null) }
            
            try {
                val displayInfo = VirtualDisplayManager.createVirtualDisplay(
                    width = width,
                    height = height,
                    density = density
                )
                
                if (displayInfo != null) {
                    Log.i(TAG, "Created virtual display: ${displayInfo.displayId}")
                    refreshDisplays()
                } else {
                    _uiState.update { 
                        it.copy(
                            isCreatingDisplay = false,
                            error = "创建虚拟显示失败"
                        ) 
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create display", e)
                _uiState.update { 
                    it.copy(
                        isCreatingDisplay = false,
                        error = "创建虚拟显示失败: ${e.message}"
                    ) 
                }
            }
            
            _uiState.update { it.copy(isCreatingDisplay = false) }
        }
    }
    
    fun destroyDisplay(displayId: Int) {
        viewModelScope.launch {
            try {
                VirtualDisplayManager.destroyVirtualDisplay(displayId)
                Log.i(TAG, "Destroyed virtual display: $displayId")
                
                _uiState.update { state ->
                    state.copy(
                        displays = state.displays.filter { it.displayInfo.displayId != displayId },
                        selectedDisplay = if (state.selectedDisplay?.displayInfo?.displayId == displayId) {
                            null
                        } else {
                            state.selectedDisplay
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to destroy display $displayId", e)
                _uiState.update { 
                    it.copy(error = "销毁虚拟显示失败: ${e.message}") 
                }
            }
        }
    }
    
    fun destroyAllDisplays() {
        viewModelScope.launch {
            try {
                VirtualDisplayManager.destroyAllDisplays()
                Log.i(TAG, "Destroyed all virtual displays")
                _uiState.update { 
                    it.copy(displays = emptyList(), selectedDisplay = null) 
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to destroy all displays", e)
                _uiState.update { 
                    it.copy(error = "清理虚拟显示失败: ${e.message}") 
                }
            }
        }
    }
    
    fun selectDisplay(preview: DisplayPreview?) {
        _uiState.update { it.copy(selectedDisplay = preview) }
    }
    
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
    
    private fun startPreviewUpdates() {
        if (previewUpdateJob?.isActive == true) return
        
        previewUpdateJob = viewModelScope.launch {
            while (isActive && isScreenVisible) {
                updateAllPreviews()
                delay(PREVIEW_UPDATE_INTERVAL_MS)
            }
        }
    }
    
    private fun stopPreviewUpdates() {
        previewUpdateJob?.cancel()
        previewUpdateJob = null
    }
    
    private suspend fun updateAllPreviews() {
        val currentDisplays = _uiState.value.displays
        if (currentDisplays.isEmpty()) return
        
        val updatedPreviews = currentDisplays.map { preview ->
            try {
                val screenshot = DisplayScreenshotService.capture(
                    displayId = preview.displayInfo.displayId,
                    quality = PREVIEW_QUALITY
                )
                
                if (screenshot != null) {
                    preview.copy(
                        previewBitmap = screenshot.bitmap,
                        lastUpdateTime = System.currentTimeMillis()
                    )
                } else {
                    preview
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to capture preview for display ${preview.displayInfo.displayId}", e)
                preview
            }
        }
        
        _uiState.update { state ->
            val updatedSelected = state.selectedDisplay?.let { selected ->
                updatedPreviews.find { it.displayInfo.displayId == selected.displayInfo.displayId }
            }
            state.copy(displays = updatedPreviews, selectedDisplay = updatedSelected)
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        stopPreviewUpdates()
        // Disconnect stream service
        viewModelScope.launch {
            DisplayStreamClient.disconnect()
        }
    }
    
    fun toggleStreamingMode() {
        val newStreamingEnabled = !_uiState.value.streamingEnabled
        
        viewModelScope.launch {
            if (newStreamingEnabled) {
                // Connect to streaming service
                val connected = DisplayStreamClient.connect()
                _uiState.update { 
                    it.copy(
                        streamingEnabled = true,
                        isStreamServiceConnected = connected,
                        error = if (!connected) "无法连接到流式显示服务" else null
                    )
                }
                
                if (connected) {
                    stopPreviewUpdates() // Stop screenshot updates when streaming
                }
            } else {
                _uiState.update { it.copy(streamingEnabled = false) }
                if (isScreenVisible) {
                    startPreviewUpdates() // Resume screenshot updates
                }
            }
        }
    }
    
    fun createStreamingDisplay(width: Int, height: Int, density: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingDisplay = true, error = null) }
            
            try {
                val displayId = DisplayStreamClient.createDisplay(width, height, density, "AutoGLM-Stream")
                
                if (displayId > 0) {
                    Log.i(TAG, "Created streaming display: $displayId")
                    refreshDisplays()
                } else {
                    _uiState.update { 
                        it.copy(
                            isCreatingDisplay = false,
                            error = "创建流式虚拟显示失败"
                        ) 
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create streaming display", e)
                _uiState.update { 
                    it.copy(
                        isCreatingDisplay = false,
                        error = "创建流式虚拟显示失败: ${e.message}"
                    ) 
                }
            }
            
            _uiState.update { it.copy(isCreatingDisplay = false) }
        }
    }
    
    fun startAppOnDisplay(displayId: Int, packageName: String) {
        viewModelScope.launch {
            try {
                val success = DisplayStreamClient.startAppOnDisplay(displayId, packageName)
                if (!success) {
                    _uiState.update { it.copy(error = "无法在显示器上启动应用") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startAppOnDisplay failed", e)
                _uiState.update { it.copy(error = "启动应用失败: ${e.message}") }
            }
        }
    }
}
