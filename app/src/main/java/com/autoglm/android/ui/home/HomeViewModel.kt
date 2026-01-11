package com.autoglm.android.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.autoglm.android.agent.AgentConfig
import com.autoglm.android.agent.AgentState
import com.autoglm.android.agent.PhoneAgent
import com.autoglm.android.data.SettingsRepository
import com.autoglm.android.device.AppLauncher
import com.autoglm.android.model.MessageBuilder
import com.autoglm.android.model.ModelConfig
import com.autoglm.android.service.AgentForegroundService
import com.autoglm.android.shizuku.ShizukuManager
import com.autoglm.android.shizuku.ShizukuState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class HomeUiState(
    val agentState: AgentState = AgentState.Idle,
    val logs: List<LogEntry> = emptyList()
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    
    private val settingsRepository = SettingsRepository(application)
    
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    
    val shizukuState: StateFlow<ShizukuState> = ShizukuManager.state
    
    private val _taskInput = MutableStateFlow("")
    val taskInput: StateFlow<String> = _taskInput.asStateFlow()
    
    private var agent: PhoneAgent? = null
    private var confirmationContinuation: Continuation<Boolean>? = null
    private var takeoverContinuation: Continuation<Unit>? = null
    
    init {
        ShizukuManager.init()
    }
    
    override fun onCleared() {
        super.onCleared()
        ShizukuManager.destroy()
    }
    
    fun updateTaskInput(input: String) {
        _taskInput.value = input
    }
    
    fun requestShizukuPermission() {
        ShizukuManager.requestPermission()
    }
    
    fun startTask() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            
            if (settings.apiKey.isBlank()) {
                _uiState.update { it.copy(agentState = AgentState.Failed("Please configure API key first")) }
                return@launch
            }
            
            val modelConfig = ModelConfig(
                baseUrl = settings.apiUrl,
                apiKey = settings.apiKey,
                modelName = settings.modelName,
                lang = settings.language
            )
            
            val agentConfig = AgentConfig(
                maxSteps = settings.maxSteps,
                lang = settings.language
            )
            
            MessageBuilder.setUseLargeModelMode(settings.useLargeModelAppList)
            AppLauncher.setUseLargeModelMode(settings.useLargeModelAppList)
            
            agent = PhoneAgent(
                modelConfig = modelConfig,
                agentConfig = agentConfig,
                onConfirmationRequired = { message ->
                    _uiState.update { it.copy(agentState = AgentState.WaitingForConfirmation(message)) }
                    suspendCoroutine { continuation ->
                        confirmationContinuation = continuation
                    }
                },
                onTakeoverRequired = { message ->
                    _uiState.update { it.copy(agentState = AgentState.WaitingForTakeover(message)) }
                    suspendCoroutine { continuation ->
                        takeoverContinuation = continuation
                    }
                },
                onStepCompleted = { result ->
                    val log = LogEntry(
                        step = _uiState.value.logs.size + 1,
                        thinking = result.thinking,
                        action = result.action?.actionType,
                        message = result.message,
                        screenshotBase64 = result.screenshotBase64
                    )
                    _uiState.update { it.copy(logs = it.logs + log) }
                    
                    AgentForegroundService.updateProgress(
                        context = getApplication(),
                        step = log.step,
                        maxSteps = agentConfig.maxSteps,
                        actionType = result.action?.actionType,
                        thinking = result.thinking
                    )
                }
            )
            
            _uiState.update { it.copy(logs = emptyList()) }
            
            viewModelScope.launch {
                agent?.state?.collect { agentState ->
                    _uiState.update { it.copy(agentState = agentState) }
                }
            }
            
            val task = _taskInput.value
            AgentForegroundService.start(getApplication(), task, agentConfig.maxSteps)
            
            try {
                val result = agent?.run(task)
                AgentForegroundService.notifyCompleted(getApplication(), result ?: "Task completed")
            } catch (e: Exception) {
                _uiState.update { it.copy(agentState = AgentState.Failed(e.message ?: "Unknown error")) }
                AgentForegroundService.notifyFailed(getApplication(), e.message ?: "Unknown error")
            } finally {
                _uiState.update { it.copy(agentState = AgentState.Idle) }
            }
        }
    }
    
    fun stopTask() {
        agent?.stop()
        AgentForegroundService.stop(getApplication())
    }
    
    fun pauseTask() {
        agent?.pause()
        AgentForegroundService.pause(getApplication())
    }
    
    fun resumeTask() {
        agent?.resume()
        AgentForegroundService.resume(getApplication())
    }
    
    fun isAgentPaused(): Boolean = agent?.isPaused() ?: false
    
    fun confirmAction(confirmed: Boolean) {
        confirmationContinuation?.resume(confirmed)
        confirmationContinuation = null
    }
    
    fun continueTakeover() {
        takeoverContinuation?.resume(Unit)
        takeoverContinuation = null
    }
    
    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as Application
                HomeViewModel(application)
            }
        }
    }
}
