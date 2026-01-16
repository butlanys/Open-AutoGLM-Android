/*
 * Copyright (C) 2024 AutoGLM
 *
 * ViewModel for the Orchestrator Agent - manages intelligent task orchestration UI state.
 */

package com.autoglm.android.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.autoglm.android.agent.*
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

data class OrchestratorUiState(
    val userInput: String = "",
    val orchestratorState: OrchestratorState = OrchestratorState.Idle,
    val taskAnalysis: TaskAnalysis? = null,
    val subTaskProgress: Map<String, TaskProgress> = emptyMap(),
    val results: List<SubTaskResult> = emptyList(),
    val summary: String = "",
    val flowDiagram: String = "",
    val executionTree: ExecutionNode? = null,
    val isInitializing: Boolean = false,
    val error: String? = null,
    val confirmationMessage: String? = null,
    val showSettings: Boolean = false
)

data class OrchestratorSettings(
    val useAdvancedModel: Boolean = true,
    val advancedModelName: String = "",
    val advancedModelUrl: String = "",
    val advancedModelApiKey: String = "",
    val maxConcurrentTasks: Int = 3,
    val enableVirtualDisplays: Boolean = true,
    val autoDecideMultiTask: Boolean = true
) {
    fun toRepoSettings() = SettingsRepository.OrchestratorSettings(
        useAdvancedModel = useAdvancedModel,
        advancedModelUrl = advancedModelUrl,
        advancedModelApiKey = advancedModelApiKey,
        advancedModelName = advancedModelName,
        maxConcurrentTasks = maxConcurrentTasks,
        enableVirtualDisplays = enableVirtualDisplays,
        autoDecideMultiTask = autoDecideMultiTask
    )
    
    companion object {
        fun fromRepoSettings(repo: SettingsRepository.OrchestratorSettings) = OrchestratorSettings(
            useAdvancedModel = repo.useAdvancedModel,
            advancedModelUrl = repo.advancedModelUrl,
            advancedModelApiKey = repo.advancedModelApiKey,
            advancedModelName = repo.advancedModelName,
            maxConcurrentTasks = repo.maxConcurrentTasks,
            enableVirtualDisplays = repo.enableVirtualDisplays,
            autoDecideMultiTask = repo.autoDecideMultiTask
        )
    }
}

class OrchestratorViewModel(application: Application) : AndroidViewModel(application) {
    
    private val settingsRepository = SettingsRepository(application)
    
    private val _uiState = MutableStateFlow(OrchestratorUiState())
    val uiState: StateFlow<OrchestratorUiState> = _uiState.asStateFlow()
    
    private val _orchestratorSettings = MutableStateFlow(OrchestratorSettings())
    val orchestratorSettings: StateFlow<OrchestratorSettings> = _orchestratorSettings.asStateFlow()
    
    val shizukuState: StateFlow<ShizukuState> = ShizukuManager.state
    
    private var orchestratorAgent: OrchestratorAgent? = null
    private var confirmationContinuation: Continuation<Boolean>? = null
    
    init {
        ShizukuManager.init()
        loadSettings()
    }
    
    private fun loadSettings() {
        viewModelScope.launch {
            // Load orchestrator-specific settings first
            settingsRepository.orchestratorSettings.collect { orchSettings ->
                _orchestratorSettings.value = OrchestratorSettings.fromRepoSettings(orchSettings)
            }
        }
        viewModelScope.launch {
            // If advanced model settings are empty, use global settings as defaults
            settingsRepository.settings.collect { settings ->
                _orchestratorSettings.update { current ->
                    if (current.advancedModelUrl.isBlank()) {
                        current.copy(
                            advancedModelName = settings.modelName,
                            advancedModelUrl = settings.apiUrl,
                            advancedModelApiKey = settings.apiKey
                        )
                    } else current
                }
            }
        }
    }
    
    fun updateInput(text: String) {
        _uiState.update { it.copy(userInput = text) }
    }
    
    fun updateSettings(settings: OrchestratorSettings) {
        _orchestratorSettings.value = settings
        // Persist to DataStore
        viewModelScope.launch {
            settingsRepository.saveOrchestratorSettings(settings.toRepoSettings())
        }
    }
    
    fun toggleSettings() {
        _uiState.update { it.copy(showSettings = !it.showSettings) }
    }
    
    fun executeTask() {
        val task = _uiState.value.userInput.trim()
        if (task.isBlank()) {
            _uiState.update { it.copy(error = "请输入任务描述") }
            return
        }
        
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val orchestratorSettings = _orchestratorSettings.value
            
            if (settings.apiKey.isBlank() && orchestratorSettings.advancedModelApiKey.isBlank()) {
                _uiState.update { it.copy(error = "请先配置API密钥") }
                return@launch
            }
            
            _uiState.update { 
                it.copy(
                    isInitializing = true,
                    error = null,
                    results = emptyList(),
                    summary = "",
                    flowDiagram = "",
                    taskAnalysis = null,
                    subTaskProgress = emptyMap()
                )
            }
            
            // Configure orchestrator model (can be a more capable model)
            val orchestratorModelConfig = if (orchestratorSettings.useAdvancedModel && 
                                               orchestratorSettings.advancedModelApiKey.isNotBlank()) {
                ModelConfig(
                    baseUrl = orchestratorSettings.advancedModelUrl.ifBlank { settings.apiUrl },
                    apiKey = orchestratorSettings.advancedModelApiKey.ifBlank { settings.apiKey },
                    modelName = orchestratorSettings.advancedModelName.ifBlank { settings.modelName },
                    lang = settings.language
                )
            } else {
                ModelConfig(
                    baseUrl = settings.apiUrl,
                    apiKey = settings.apiKey,
                    modelName = settings.modelName,
                    lang = settings.language
                )
            }
            
            // Configure worker model (standard model for sub-tasks)
            val workerModelConfig = ModelConfig(
                baseUrl = settings.apiUrl,
                apiKey = settings.apiKey,
                modelName = settings.modelName,
                lang = settings.language
            )
            
            val displaySize = VirtualDisplayManager.getDefaultDisplaySize()
            val density = VirtualDisplayManager.getDefaultDisplayDensity()
            
            val multiTaskConfig = MultiTaskConfig(
                maxConcurrentTasks = orchestratorSettings.maxConcurrentTasks,
                maxStepsPerTask = settings.maxSteps,
                displayWidth = displaySize?.x ?: 1080,
                displayHeight = displaySize?.y ?: 2400,
                displayDensity = density,
                enableVirtualDisplays = orchestratorSettings.enableVirtualDisplays,
                fallbackToSequential = true,
                lang = settings.language
            )
            
            orchestratorAgent = OrchestratorAgent(
                orchestratorModelConfig = orchestratorModelConfig,
                workerModelConfig = workerModelConfig,
                multiTaskConfig = multiTaskConfig,
                onStateChange = { state ->
                    _uiState.update { it.copy(orchestratorState = state) }
                    
                    // Extract task analysis when available
                    if (state is OrchestratorState.Decomposing) {
                        _uiState.update { it.copy(taskAnalysis = state.analysis) }
                    }
                },
                onSubTaskProgress = { progress ->
                    _uiState.update { state ->
                        state.copy(
                            subTaskProgress = state.subTaskProgress + (progress.taskId to progress)
                        )
                    }
                },
                onConfirmationRequired = { message ->
                    _uiState.update { it.copy(confirmationMessage = message) }
                    suspendCoroutine { continuation ->
                        confirmationContinuation = continuation
                    }
                },
                onTakeoverRequired = { message ->
                    _uiState.update { it.copy(error = "需要用户介入: $message") }
                }
            )
            
            _uiState.update { it.copy(isInitializing = false) }
            
            try {
                val result = orchestratorAgent?.execute(task)
                
                if (result != null) {
                    _uiState.update { 
                        it.copy(
                            results = result.subTaskResults,
                            summary = result.summary,
                            flowDiagram = result.flowDiagram,
                            executionTree = result.executionTree,
                            orchestratorState = OrchestratorState.Completed(
                                result.summary, 
                                result.flowDiagram
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        error = e.message,
                        orchestratorState = OrchestratorState.Failed(e.message ?: "Unknown error")
                    )
                }
            }
        }
    }
    
    fun stopExecution() {
        orchestratorAgent?.stop()
        viewModelScope.launch {
            VirtualDisplayManager.destroyAllDisplays()
        }
    }
    
    fun confirmAction(confirmed: Boolean) {
        confirmationContinuation?.resume(confirmed)
        confirmationContinuation = null
        _uiState.update { it.copy(confirmationMessage = null) }
    }
    
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
    
    fun reset() {
        orchestratorAgent?.reset()
        _uiState.value = OrchestratorUiState()
    }
    
    fun requestShizukuPermission() {
        ShizukuManager.requestPermission()
    }
    
    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            VirtualDisplayManager.destroyAllDisplays()
        }
    }
    
    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as Application
                OrchestratorViewModel(application)
            }
        }
    }
}
