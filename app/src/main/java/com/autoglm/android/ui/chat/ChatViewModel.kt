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
import com.autoglm.android.agent.PhoneAgent
import com.autoglm.android.data.ConversationRepository
import com.autoglm.android.data.SettingsRepository
import com.autoglm.android.data.db.Conversation
import com.autoglm.android.data.db.ExecutionStep
import com.autoglm.android.data.db.Message
import com.autoglm.android.data.db.MessageRole
import com.autoglm.android.data.db.MessageStatus
import com.autoglm.android.device.AppLauncher
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
    val isLoading: Boolean = false
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
    private var confirmationContinuation: Continuation<Boolean>? = null
    private var takeoverContinuation: Continuation<Unit>? = null
    private var currentMessageId: String? = null
    private var messagesJob: Job? = null
    private var stepsJob: Job? = null
    private var serviceObserverJob: Job? = null
    
    init {
        ShizukuManager.init()
        observeServiceCommands()
        restoreRunningTaskIfNeeded()
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
    
    fun stopTask() {
        agent?.stop()
        AgentForegroundService.stop(getApplication())
        viewModelScope.launch {
            currentMessageId?.let { messageId ->
                conversationRepository.updateMessageStatus(messageId, MessageStatus.FAILED)
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
