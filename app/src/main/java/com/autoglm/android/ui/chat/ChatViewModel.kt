package com.autoglm.android.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.autoglm.android.agent.AgentConfig
import com.autoglm.android.agent.AgentState
import com.autoglm.android.agent.HistoryMessage
import com.autoglm.android.agent.MultiTaskConfig
import com.autoglm.android.agent.OrchestratorAgent
import com.autoglm.android.agent.OrchestratorState
import com.autoglm.android.agent.PhoneAgent
import com.autoglm.android.agent.SubTaskResult
import com.autoglm.android.agent.TaskAnalysis
import com.autoglm.android.agent.TaskProgress
import com.autoglm.android.data.ConversationRepository
import com.autoglm.android.data.SettingsRepository
import com.autoglm.android.data.db.Conversation
import com.autoglm.android.data.db.ExecutionStep
import com.autoglm.android.data.db.Message
import com.autoglm.android.data.db.MessageRole
import com.autoglm.android.data.db.MessageStatus
import com.autoglm.android.device.AppLauncher
import com.autoglm.android.display.VirtualDisplayManager
import com.autoglm.android.model.MessageBuilder
import com.autoglm.android.model.ModelConfig
import com.autoglm.android.service.AgentForegroundService
import com.autoglm.android.shizuku.ShizukuManager
import com.autoglm.android.shizuku.ShizukuState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class ChatUiState(
    val currentConversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val agentState: AgentState = AgentState.Idle,
    val currentExecutionSteps: List<ExecutionStep> = emptyList(),
    val isLoading: Boolean = false,
    // Orchestrator mode
    val orchestratorState: OrchestratorState = OrchestratorState.Idle,
    val taskAnalysis: TaskAnalysis? = null,
    val subTaskProgress: Map<String, TaskProgress> = emptyMap(),
    val orchestratorResults: List<SubTaskResult> = emptyList(),
    val orchestratorSummary: String = "",
    val flowDiagram: String = "",
    // Settings
    val showAdvancedSettings: Boolean = false,
    val virtualDisplaySupported: Boolean = false,
    val confirmationMessage: String? = null
)

data class AdvancedSettings(
    val useOrchestrator: Boolean = false,
    val useAdvancedModel: Boolean = false,
    val advancedModelUrl: String = "",
    val advancedModelApiKey: String = "",
    val advancedModelName: String = "",
    val enableVirtualDisplays: Boolean = true,
    val maxConcurrentTasks: Int = 3,
    val autoDecideMultiTask: Boolean = true
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    
    private val settingsRepository = SettingsRepository(application)
    private val conversationRepository = ConversationRepository(application)
    
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    val shizukuState: StateFlow<ShizukuState> = ShizukuManager.state
    
    val allConversations: Flow<List<Conversation>> = conversationRepository.allConversations
    
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()
    
    private var agent: PhoneAgent? = null
    private var orchestratorAgent: OrchestratorAgent? = null
    private var confirmationContinuation: Continuation<Boolean>? = null
    private var takeoverContinuation: Continuation<Unit>? = null
    private var currentMessageId: String? = null
    private var messagesJob: Job? = null
    private var stepsJob: Job? = null
    private var serviceObserverJob: Job? = null
    
    private val _advancedSettings = MutableStateFlow(AdvancedSettings())
    val advancedSettings: StateFlow<AdvancedSettings> = _advancedSettings.asStateFlow()
    
    init {
        ShizukuManager.init()
        observeServiceCommands()
        restoreRunningTaskIfNeeded()
        loadAdvancedSettings()
        observeShizukuAndCheckVirtualDisplay()
    }
    
    private fun loadAdvancedSettings() {
        viewModelScope.launch {
            settingsRepository.orchestratorSettings.collect { orchSettings ->
                _advancedSettings.update { current ->
                    current.copy(
                        useAdvancedModel = orchSettings.useAdvancedModel,
                        advancedModelUrl = orchSettings.advancedModelUrl,
                        advancedModelApiKey = orchSettings.advancedModelApiKey,
                        advancedModelName = orchSettings.advancedModelName,
                        enableVirtualDisplays = orchSettings.enableVirtualDisplays,
                        maxConcurrentTasks = orchSettings.maxConcurrentTasks,
                        autoDecideMultiTask = orchSettings.autoDecideMultiTask
                    )
                }
            }
        }
    }
    
    private fun observeShizukuAndCheckVirtualDisplay() {
        viewModelScope.launch {
            // Wait for Shizuku to be ready before checking virtual display support
            shizukuState.first { it.isReady }
            checkVirtualDisplaySupport()
        }
    }
    
    private suspend fun checkVirtualDisplaySupport() {
        val supported = VirtualDisplayManager.checkVirtualDisplaySupport()
        _uiState.update { it.copy(virtualDisplaySupported = supported) }
        if (!supported) {
            _advancedSettings.update { it.copy(enableVirtualDisplays = false) }
        }
    }
    
    fun updateAdvancedSettings(settings: AdvancedSettings) {
        _advancedSettings.value = settings
        viewModelScope.launch {
            settingsRepository.saveOrchestratorSettings(
                SettingsRepository.OrchestratorSettings(
                    useAdvancedModel = settings.useAdvancedModel,
                    advancedModelUrl = settings.advancedModelUrl,
                    advancedModelApiKey = settings.advancedModelApiKey,
                    advancedModelName = settings.advancedModelName,
                    enableVirtualDisplays = settings.enableVirtualDisplays,
                    maxConcurrentTasks = settings.maxConcurrentTasks,
                    autoDecideMultiTask = settings.autoDecideMultiTask
                )
            )
        }
    }
    
    fun toggleAdvancedSettings() {
        _uiState.update { it.copy(showAdvancedSettings = !it.showAdvancedSettings) }
    }
    
    fun dismissConfirmation() {
        confirmationContinuation?.resume(false)
        confirmationContinuation = null
        _uiState.update { it.copy(confirmationMessage = null) }
    }
    
    private fun restoreRunningTaskIfNeeded() {
        viewModelScope.launch {
            if (AgentForegroundService.isRunning.value) {
                val conversationId = AgentForegroundService.getCurrentConversationId()
                val messageId = AgentForegroundService.getCurrentMessageId()
                if (conversationId != null) {
                    loadConversation(conversationId)
                    if (messageId != null) {
                        currentMessageId = messageId
                        stepsJob?.cancel()
                        stepsJob = viewModelScope.launch {
                            conversationRepository.getExecutionSteps(messageId).collect { steps ->
                                _uiState.update { it.copy(currentExecutionSteps = steps) }
                            }
                        }
                    }
                    val isPaused = AgentForegroundService.isPaused.value
                    if (isPaused) {
                        _uiState.update { it.copy(agentState = AgentState.Paused(0, 100, "")) }
                    } else {
                        _uiState.update { it.copy(agentState = AgentState.Running(0, 100, "")) }
                    }
                }
            }
        }
    }
    
    private fun observeServiceCommands() {
        serviceObserverJob = viewModelScope.launch {
            launch {
                AgentForegroundService.stopRequested.collect { stopRequested ->
                    if (stopRequested && agent != null) {
                        agent?.stop()
                        AgentForegroundService.clearRequests()
                    }
                }
            }
            launch {
                AgentForegroundService.pauseRequested.collect { pauseRequested ->
                    val agent = agent ?: return@collect
                    if (pauseRequested && !agent.isPaused()) {
                        agent.pause()
                    } else if (!pauseRequested && agent.isPaused()) {
                        agent.resume()
                    }
                }
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        ShizukuManager.destroy()
    }
    
    fun updateInputText(text: String) {
        _inputText.value = text
    }
    
    fun requestShizukuPermission() {
        ShizukuManager.requestPermission()
    }
    
    fun loadConversation(conversationId: String) {
        messagesJob?.cancel()
        stepsJob?.cancel()
        
        viewModelScope.launch {
            val conversation = conversationRepository.getConversation(conversationId)
            _uiState.update { it.copy(currentConversation = conversation) }
            
            messagesJob = viewModelScope.launch {
                conversationRepository.getMessages(conversationId).collect { messages ->
                    _uiState.update { it.copy(messages = messages) }
                }
            }
        }
    }
    
    fun createNewConversation() {
        viewModelScope.launch {
            val conversation = conversationRepository.createConversation()
            loadConversation(conversation.id)
        }
    }
    
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(conversationId)
            if (_uiState.value.currentConversation?.id == conversationId) {
                _uiState.update { it.copy(currentConversation = null, messages = emptyList()) }
            }
        }
    }
    
    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isBlank()) return
        
        val advSettings = _advancedSettings.value
        if (advSettings.useOrchestrator) {
            executeWithOrchestrator(text)
        } else {
            executeNormalTask(text)
        }
    }
    
    private fun executeNormalTask(text: String) {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            
            if (settings.apiKey.isBlank()) {
                _uiState.update { it.copy(agentState = AgentState.Failed("Please configure API key first")) }
                return@launch
            }
            
            var conversation = _uiState.value.currentConversation
            if (conversation == null) {
                conversation = conversationRepository.createConversation()
                loadConversation(conversation.id)
            }
            
            val userMessage = conversationRepository.addUserMessage(conversation.id, text)
            _inputText.value = ""
            
            val assistantMessage = conversationRepository.addAssistantMessage(
                conversationId = conversation.id,
                status = MessageStatus.STREAMING
            )
            currentMessageId = assistantMessage.id
            AgentForegroundService.setCurrentIds(conversation.id, assistantMessage.id)
            
            stepsJob?.cancel()
            stepsJob = viewModelScope.launch {
                conversationRepository.getExecutionSteps(assistantMessage.id).collect { steps ->
                    _uiState.update { it.copy(currentExecutionSteps = steps) }
                }
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
            
            val existingMessages = conversationRepository.getMessagesSync(conversation.id)
                .filter { it.id != assistantMessage.id }
            
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
                    val stepNumber = agent?.getTotalStepCount() ?: 1
                    
                    conversationRepository.addExecutionStep(
                        messageId = assistantMessage.id,
                        stepNumber = stepNumber,
                        actionType = result.action?.actionType,
                        thinking = result.thinking,
                        screenshotBase64 = result.screenshotBase64,
                        success = result.success
                    )
                    
                    val actionSummary = result.action?.let { "${it.actionType}" }
                    conversationRepository.updateAssistantMessage(
                        messageId = assistantMessage.id,
                        content = result.message ?: "",
                        thinking = result.thinking,
                        actionSummary = actionSummary,
                        stepCount = stepNumber,
                        status = MessageStatus.STREAMING
                    )
                    
                    AgentForegroundService.updateProgress(
                        context = getApplication(),
                        step = stepNumber,
                        maxSteps = agentConfig.maxSteps,
                        actionType = result.action?.actionType,
                        thinking = result.thinking
                    )
                }
            )
            
            viewModelScope.launch {
                agent?.state?.collect { agentState ->
                    _uiState.update { it.copy(agentState = agentState) }
                }
            }
            
            if (existingMessages.size > 1) {
                val history = existingMessages.mapNotNull { msg ->
                    when (msg.role) {
                        MessageRole.USER -> HistoryMessage(
                            role = "user",
                            content = msg.content
                        )
                        MessageRole.ASSISTANT -> HistoryMessage(
                            role = "assistant",
                            content = msg.content,
                            thinking = msg.thinking
                        )
                        else -> null
                    }
                }
                agent?.restoreContext(history.dropLast(1))
            }
            
            AgentForegroundService.start(getApplication(), text, agentConfig.maxSteps)
            
            try {
                val result = if (existingMessages.size > 1) {
                    agent?.continueConversation(text)
                } else {
                    agent?.run(text)
                }
                
                conversationRepository.updateAssistantMessage(
                    messageId = assistantMessage.id,
                    content = result ?: "Task completed",
                    thinking = null,
                    actionSummary = null,
                    stepCount = agent?.getTotalStepCount() ?: 0,
                    status = MessageStatus.COMPLETED
                )
                
                AgentForegroundService.notifyCompleted(getApplication(), result ?: "Task completed")
            } catch (e: Exception) {
                conversationRepository.updateMessageStatus(assistantMessage.id, MessageStatus.FAILED)
                _uiState.update { it.copy(agentState = AgentState.Failed(e.message ?: "Unknown error")) }
                AgentForegroundService.notifyFailed(getApplication(), e.message ?: "Unknown error")
            } finally {
                _uiState.update { it.copy(agentState = AgentState.Idle) }
                currentMessageId = null
                AgentForegroundService.setCurrentIds(null, null)
            }
        }
    }
    
    private fun executeWithOrchestrator(text: String) {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val advSettings = _advancedSettings.value
            
            val apiKey = if (advSettings.useAdvancedModel && advSettings.advancedModelApiKey.isNotBlank()) {
                advSettings.advancedModelApiKey
            } else {
                settings.apiKey
            }
            
            if (apiKey.isBlank()) {
                _uiState.update { it.copy(agentState = AgentState.Failed("请先配置API密钥")) }
                return@launch
            }
            
            var conversation = _uiState.value.currentConversation
            if (conversation == null) {
                conversation = conversationRepository.createConversation()
                loadConversation(conversation.id)
            }
            
            conversationRepository.addUserMessage(conversation.id, text)
            _inputText.value = ""
            
            _uiState.update {
                it.copy(
                    orchestratorState = OrchestratorState.Analyzing(text),
                    taskAnalysis = null,
                    subTaskProgress = emptyMap(),
                    orchestratorResults = emptyList(),
                    orchestratorSummary = "",
                    flowDiagram = ""
                )
            }
            
            val orchestratorModelConfig = if (advSettings.useAdvancedModel && 
                                               advSettings.advancedModelApiKey.isNotBlank()) {
                ModelConfig(
                    baseUrl = advSettings.advancedModelUrl.ifBlank { settings.apiUrl },
                    apiKey = advSettings.advancedModelApiKey,
                    modelName = advSettings.advancedModelName.ifBlank { settings.modelName },
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
            
            val workerModelConfig = ModelConfig(
                baseUrl = settings.apiUrl,
                apiKey = settings.apiKey,
                modelName = settings.modelName,
                lang = settings.language
            )
            
            val displaySize = VirtualDisplayManager.getDefaultDisplaySize()
            val density = VirtualDisplayManager.getDefaultDisplayDensity()
            
            val multiTaskConfig = MultiTaskConfig(
                maxConcurrentTasks = advSettings.maxConcurrentTasks,
                enableVirtualDisplays = advSettings.enableVirtualDisplays && _uiState.value.virtualDisplaySupported,
                displayWidth = displaySize?.x ?: 1080,
                displayHeight = displaySize?.y ?: 2340,
                displayDensity = density,
                lang = settings.language
            )
            
            orchestratorAgent = OrchestratorAgent(
                orchestratorModelConfig = orchestratorModelConfig,
                workerModelConfig = workerModelConfig,
                multiTaskConfig = multiTaskConfig,
                onStateChange = { state ->
                    _uiState.update { it.copy(orchestratorState = state) }
                },
                onSubTaskProgress = { progress ->
                    _uiState.update { state ->
                        state.copy(
                            subTaskProgress = state.subTaskProgress + (progress.taskId to progress)
                        )
                    }
                },
                onConfirmationRequired = { message ->
                    suspendCoroutine { continuation ->
                        _uiState.update { it.copy(confirmationMessage = message) }
                        confirmationContinuation = continuation
                    }
                }
            )
            
            try {
                val result = orchestratorAgent?.execute(text)
                
                if (result != null) {
                    _uiState.update {
                        it.copy(
                            orchestratorResults = result.subTaskResults,
                            orchestratorSummary = result.summary,
                            flowDiagram = result.flowDiagram,
                            orchestratorState = if (result.success) 
                                OrchestratorState.Completed(result.summary, result.flowDiagram) 
                            else 
                                OrchestratorState.Failed(result.summary)
                        )
                    }
                    
                    conversationRepository.addAssistantMessage(
                        conversationId = conversation.id,
                        content = result.summary,
                        status = MessageStatus.COMPLETED
                    )
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(orchestratorState = OrchestratorState.Failed(e.message ?: "Unknown error")) 
                }
            } finally {
                viewModelScope.launch {
                    VirtualDisplayManager.destroyAllDisplays()
                }
            }
        }
    }
    
    fun stopTask() {
        agent?.stop()
        orchestratorAgent?.stop()
        viewModelScope.launch {
            VirtualDisplayManager.destroyAllDisplays()
        }
        AgentForegroundService.stop(getApplication())
        viewModelScope.launch {
            currentMessageId?.let { messageId ->
                conversationRepository.updateMessageStatus(messageId, MessageStatus.FAILED)
            }
            _uiState.update { 
                it.copy(
                    orchestratorState = OrchestratorState.Idle,
                    subTaskProgress = emptyMap()
                ) 
            }
        }
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
    
    fun retryMessage(message: Message) {
        val conversationId = _uiState.value.currentConversation?.id ?: return
        val isRunning = _uiState.value.agentState is AgentState.Running || 
                       _uiState.value.agentState is AgentState.Paused
        if (isRunning) return
        
        viewModelScope.launch {
            if (message.role == MessageRole.USER) {
                // Resend user message
                val text = message.content
                _inputText.value = text
                sendMessage()
            } else {
                // Regenerate assistant response - find previous user message and resend
                val messages = conversationRepository.getMessagesSync(conversationId)
                val messageIndex = messages.indexOfFirst { it.id == message.id }
                if (messageIndex > 0) {
                    val previousUserMessage = messages.subList(0, messageIndex)
                        .lastOrNull { it.role == MessageRole.USER }
                    previousUserMessage?.let {
                        _inputText.value = it.content
                        sendMessage()
                    }
                }
            }
        }
    }
    
    fun editAndResend(message: Message, newText: String) {
        val isRunning = _uiState.value.agentState is AgentState.Running || 
                       _uiState.value.agentState is AgentState.Paused
        if (isRunning) return
        
        _inputText.value = newText
        sendMessage()
    }
    
    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as Application
                ChatViewModel(application)
            }
        }
    }
}
