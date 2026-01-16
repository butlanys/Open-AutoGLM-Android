/*
 * Copyright (C) 2024 AutoGLM
 *
 * ViewModel for managing multi-task concurrent execution UI state.
 */

package com.autoglm.android.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.autoglm.android.agent.MultiAgentState
import com.autoglm.android.agent.MultiTaskConfig
import com.autoglm.android.agent.MultiTaskPhoneAgent
import com.autoglm.android.agent.TaskDefinition
import com.autoglm.android.agent.TaskProgress
import com.autoglm.android.agent.TaskState
import com.autoglm.android.data.SettingsRepository
import com.autoglm.android.display.VirtualDisplayManager
import com.autoglm.android.model.ModelConfig
import com.autoglm.android.shizuku.ShizukuManager
import com.autoglm.android.shizuku.ShizukuState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class MultiTaskUiState(
    val tasks: List<TaskDefinition> = emptyList(),
    val taskProgress: Map<String, TaskProgress> = emptyMap(),
    val agentState: MultiAgentState = MultiAgentState.Idle,
    val results: Map<String, String> = emptyMap(),
    val isInitializing: Boolean = false,
    val error: String? = null,
    val activeDisplays: Int = 0
)

class MultiTaskViewModel(application: Application) : AndroidViewModel(application) {
    
    private val settingsRepository = SettingsRepository(application)
    
    private val _uiState = MutableStateFlow(MultiTaskUiState())
    val uiState: StateFlow<MultiTaskUiState> = _uiState.asStateFlow()
    
    val shizukuState: StateFlow<ShizukuState> = ShizukuManager.state
    
    private val _pendingTasks = MutableStateFlow<List<TaskDefinition>>(emptyList())
    val pendingTasks: StateFlow<List<TaskDefinition>> = _pendingTasks.asStateFlow()
    
    private var multiTaskAgent: MultiTaskPhoneAgent? = null
    private var confirmationContinuation: Continuation<Boolean>? = null
    
    init {
        ShizukuManager.init()
        observeDisplays()
    }
    
    private fun observeDisplays() {
        viewModelScope.launch {
            while (true) {
                val displays = VirtualDisplayManager.getActiveDisplays()
                _uiState.update { it.copy(activeDisplays = displays.size) }
                kotlinx.coroutines.delay(2000)
            }
        }
    }
    
    fun addTask(description: String, targetApp: String? = null, priority: Int = 0) {
        val taskId = "task_${System.currentTimeMillis()}"
        val task = TaskDefinition(
            id = taskId,
            description = description,
            targetApp = targetApp,
            priority = priority
        )
        _pendingTasks.update { it + task }
        _uiState.update { it.copy(tasks = _pendingTasks.value) }
    }
    
    fun addTaskWithDependencies(
        description: String,
        targetApp: String? = null,
        priority: Int = 0,
        dependsOn: List<String> = emptyList()
    ) {
        val taskId = "task_${System.currentTimeMillis()}"
        val task = TaskDefinition(
            id = taskId,
            description = description,
            targetApp = targetApp,
            priority = priority,
            dependsOn = dependsOn
        )
        _pendingTasks.update { it + task }
        _uiState.update { it.copy(tasks = _pendingTasks.value) }
    }
    
    fun removeTask(taskId: String) {
        _pendingTasks.update { tasks -> tasks.filter { it.id != taskId } }
        _uiState.update { it.copy(tasks = _pendingTasks.value) }
    }
    
    fun clearTasks() {
        _pendingTasks.value = emptyList()
        _uiState.update { it.copy(tasks = emptyList()) }
    }
    
    fun updateTaskPriority(taskId: String, priority: Int) {
        _pendingTasks.update { tasks ->
            tasks.map { task ->
                if (task.id == taskId) task.copy(priority = priority) else task
            }
        }
        _uiState.update { it.copy(tasks = _pendingTasks.value) }
    }
    
    fun runTasks(maxConcurrent: Int = 3, enableVirtualDisplays: Boolean = true) {
        val tasks = _pendingTasks.value
        if (tasks.isEmpty()) {
            _uiState.update { it.copy(error = "No tasks to run") }
            return
        }
        
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            
            if (settings.apiKey.isBlank()) {
                _uiState.update { it.copy(error = "Please configure API key first") }
                return@launch
            }
            
            _uiState.update { 
                it.copy(
                    isInitializing = true,
                    error = null,
                    results = emptyMap(),
                    taskProgress = emptyMap()
                )
            }
            
            val modelConfig = ModelConfig(
                baseUrl = settings.apiUrl,
                apiKey = settings.apiKey,
                modelName = settings.modelName,
                lang = settings.language
            )
            
            val displaySize = VirtualDisplayManager.getDefaultDisplaySize()
            val density = VirtualDisplayManager.getDefaultDisplayDensity()
            
            val multiTaskConfig = MultiTaskConfig(
                maxConcurrentTasks = maxConcurrent,
                maxStepsPerTask = settings.maxSteps,
                displayWidth = displaySize?.x ?: 1080,
                displayHeight = displaySize?.y ?: 2400,
                displayDensity = density,
                enableVirtualDisplays = enableVirtualDisplays,
                fallbackToSequential = true,
                lang = settings.language
            )
            
            multiTaskAgent = MultiTaskPhoneAgent(
                modelConfig = modelConfig,
                multiTaskConfig = multiTaskConfig,
                packageManager = getApplication<Application>().packageManager,
                onTaskProgress = { progress ->
                    _uiState.update { state ->
                        state.copy(
                            taskProgress = state.taskProgress + (progress.taskId to progress)
                        )
                    }
                },
                onConfirmationRequired = { message ->
                    suspendCoroutine { continuation ->
                        confirmationContinuation = continuation
                        _uiState.update { 
                            it.copy(error = "Confirmation required: $message")
                        }
                    }
                },
                onTakeoverRequired = { message ->
                    _uiState.update { 
                        it.copy(error = "User takeover required: $message")
                    }
                }
            )
            
            viewModelScope.launch {
                multiTaskAgent?.state?.collect { agentState ->
                    _uiState.update { it.copy(agentState = agentState) }
                }
            }
            
            _uiState.update { it.copy(isInitializing = false) }
            
            try {
                val results = multiTaskAgent?.runTasks(tasks) ?: emptyMap()
                _uiState.update { 
                    it.copy(
                        results = results,
                        agentState = MultiAgentState.Completed(results)
                    )
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        error = e.message,
                        agentState = MultiAgentState.Failed(e.message ?: "Unknown error")
                    )
                }
            }
        }
    }
    
    fun stopAllTasks() {
        multiTaskAgent?.stop()
        viewModelScope.launch {
            VirtualDisplayManager.destroyAllDisplays()
        }
    }
    
    fun pauseAllTasks() {
        multiTaskAgent?.pause()
    }
    
    fun resumeAllTasks() {
        multiTaskAgent?.resume()
    }
    
    fun confirmAction(confirmed: Boolean) {
        confirmationContinuation?.resume(confirmed)
        confirmationContinuation = null
        _uiState.update { it.copy(error = null) }
    }
    
    fun getTaskState(taskId: String): TaskState? {
        return multiTaskAgent?.getTaskState(taskId)?.value
    }
    
    fun getTaskResult(taskId: String): String? {
        return multiTaskAgent?.getTaskResult(taskId)
    }
    
    suspend fun initializeVirtualDisplays(count: Int): Boolean {
        _uiState.update { it.copy(isInitializing = true) }
        
        return try {
            val displaySize = VirtualDisplayManager.getDefaultDisplaySize()
            val density = VirtualDisplayManager.getDefaultDisplayDensity()
            
            repeat(count) { index ->
                VirtualDisplayManager.createVirtualDisplay(
                    width = displaySize?.x ?: 1080,
                    height = displaySize?.y ?: 2400,
                    density = density,
                    name = "AutoGLM-Worker-$index"
                )
            }
            
            _uiState.update { 
                it.copy(
                    isInitializing = false,
                    activeDisplays = VirtualDisplayManager.getActiveDisplays().size
                )
            }
            true
        } catch (e: Exception) {
            _uiState.update { 
                it.copy(
                    isInitializing = false,
                    error = "Failed to create virtual displays: ${e.message}"
                )
            }
            false
        }
    }
    
    suspend fun cleanupVirtualDisplays() {
        VirtualDisplayManager.destroyAllDisplays()
        _uiState.update { it.copy(activeDisplays = 0) }
    }
    
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
    
    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            multiTaskAgent?.destroyAllVirtualDisplays()
        }
    }
    
    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as Application
                MultiTaskViewModel(application)
            }
        }
    }
}
