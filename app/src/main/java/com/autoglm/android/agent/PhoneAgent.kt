package com.autoglm.android.agent

import android.util.Log
import com.autoglm.android.action.ActionHandler
import com.autoglm.android.action.ActionParser
import com.autoglm.android.action.ActionResult
import com.autoglm.android.action.ParsedAction
import com.autoglm.android.config.SystemPrompts
import com.autoglm.android.config.TimingConfig
import com.autoglm.android.data.LogManager
import com.autoglm.android.device.CurrentAppDetector
import com.autoglm.android.device.ScreenshotService
import com.autoglm.android.model.MessageBuilder
import com.autoglm.android.model.ModelClient
import com.autoglm.android.model.ModelConfig
import com.autoglm.android.model.ModelResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AgentConfig(
    val maxSteps: Int = 100,
    val lang: String = "cn",
    val customSystemPrompt: String? = null,
    val verbose: Boolean = true
) {
    fun getEffectiveSystemPrompt(): String = customSystemPrompt ?: SystemPrompts.getSystemPrompt(lang)
}

data class StepResult(
    val success: Boolean,
    val finished: Boolean,
    val action: ParsedAction?,
    val thinking: String,
    val message: String? = null,
    val screenshotBase64: String? = null
)

sealed class AgentState {
    object Idle : AgentState()
    data class Running(val stepCount: Int, val maxSteps: Int, val currentThinking: String = "") : AgentState()
    data class Paused(val stepCount: Int, val maxSteps: Int, val currentThinking: String = "") : AgentState()
    data class WaitingForConfirmation(val message: String) : AgentState()
    data class WaitingForTakeover(val message: String) : AgentState()
    data class Completed(val message: String) : AgentState()
    data class Failed(val error: String) : AgentState()
}

data class HistoryMessage(
    val role: String,
    val content: String,
    val thinking: String? = null
)

class PhoneAgent(
    private val modelConfig: ModelConfig,
    private val agentConfig: AgentConfig = AgentConfig(),
    private val onConfirmationRequired: suspend (String) -> Boolean = { true },
    private val onTakeoverRequired: suspend (String) -> Unit = {},
    private val onStepCompleted: suspend (StepResult) -> Unit = {}
) {
    companion object {
        private const val TAG = "PhoneAgent"
    }
    
    private val modelClient = ModelClient(modelConfig)
    private val actionHandler = ActionHandler(
        onConfirmationRequired = onConfirmationRequired,
        onTakeoverRequired = onTakeoverRequired
    )
    
    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()
    
    private val context = mutableListOf<Map<String, Any>>()
    private var stepCount = 0
    private var totalStepCount = 0
    private var screenWidth = 0
    private var screenHeight = 0
    
    @Volatile
    private var shouldStop = false
    
    @Volatile
    private var isPaused = false
    
    private val pauseLock = Object()
    
    fun restoreContext(history: List<HistoryMessage>) {
        context.clear()
        context.add(MessageBuilder.createSystemMessage(agentConfig.getEffectiveSystemPrompt()))
        
        for (msg in history) {
            when (msg.role) {
                "user" -> {
                    context.add(MessageBuilder.createUserMessage(msg.content))
                }
                "assistant" -> {
                    val content = if (msg.thinking != null) {
                        "<think>${msg.thinking}</think><answer>${msg.content}</answer>"
                    } else {
                        msg.content
                    }
                    context.add(MessageBuilder.createAssistantMessage(content))
                }
            }
        }
        
        Log.d(TAG, "restoreContext: Restored ${history.size} messages, context size: ${context.size}")
        LogManager.i(TAG, "恢复上下文: ${history.size} 条消息")
    }
    
    fun getTotalStepCount(): Int = totalStepCount
    
    suspend fun run(task: String): String {
        reset()
        shouldStop = false
        
        val dimensions = CurrentAppDetector.getScreenSize()
        if (dimensions != null) {
            screenWidth = dimensions.first
            screenHeight = dimensions.second
        } else {
            screenWidth = 1080
            screenHeight = 2400
        }
        
        var result = executeStep(task, isFirst = true)
        onStepCompleted(result)
        
        if (result.finished) {
            _state.value = AgentState.Completed(result.message ?: "Task completed")
            return result.message ?: "Task completed"
        }
        
        while (stepCount < agentConfig.maxSteps && !shouldStop) {
            while (isPaused && !shouldStop) {
                val currentState = _state.value
                if (currentState is AgentState.Running) {
                    _state.value = AgentState.Paused(currentState.stepCount, currentState.maxSteps, currentState.currentThinking)
                }
                delay(100)
            }
            
            if (shouldStop) break
            
            result = executeStep(isFirst = false)
            onStepCompleted(result)
            
            if (result.finished) {
                _state.value = AgentState.Completed(result.message ?: "Task completed")
                return result.message ?: "Task completed"
            }
        }
        
        val message = if (shouldStop) "Task stopped by user" else "Max steps reached"
        _state.value = AgentState.Completed(message)
        return message
    }
    
    suspend fun continueConversation(newMessage: String): String {
        shouldStop = false
        isPaused = false
        stepCount = 0
        
        val dimensions = CurrentAppDetector.getScreenSize()
        if (dimensions != null) {
            screenWidth = dimensions.first
            screenHeight = dimensions.second
        } else {
            screenWidth = 1080
            screenHeight = 2400
        }
        
        val isFirstInContext = context.isEmpty()
        if (isFirstInContext) {
            context.add(MessageBuilder.createSystemMessage(agentConfig.getEffectiveSystemPrompt()))
        }
        
        var result = executeStep(newMessage, isFirst = isFirstInContext)
        onStepCompleted(result)
        
        if (result.finished) {
            _state.value = AgentState.Completed(result.message ?: "Task completed")
            return result.message ?: "Task completed"
        }
        
        while (stepCount < agentConfig.maxSteps && !shouldStop) {
            while (isPaused && !shouldStop) {
                val currentState = _state.value
                if (currentState is AgentState.Running) {
                    _state.value = AgentState.Paused(currentState.stepCount, currentState.maxSteps, currentState.currentThinking)
                }
                delay(100)
            }
            
            if (shouldStop) break
            
            result = executeStep(isFirst = false)
            onStepCompleted(result)
            
            if (result.finished) {
                _state.value = AgentState.Completed(result.message ?: "Task completed")
                return result.message ?: "Task completed"
            }
        }
        
        val message = if (shouldStop) "Task stopped by user" else "Max steps reached"
        _state.value = AgentState.Completed(message)
        return message
    }
    
    fun stop() {
        shouldStop = true
        isPaused = false
    }
    
    fun pause() {
        isPaused = true
    }
    
    fun resume() {
        isPaused = false
    }
    
    fun isPaused(): Boolean = isPaused
    
    fun reset() {
        context.clear()
        stepCount = 0
        totalStepCount = 0
        _state.value = AgentState.Idle
        shouldStop = false
        isPaused = false
    }
    
    fun softReset() {
        stepCount = 0
        _state.value = AgentState.Idle
        shouldStop = false
        isPaused = false
    }
    
    private suspend fun executeStep(
        userPrompt: String? = null,
        isFirst: Boolean = false
    ): StepResult {
        stepCount++
        totalStepCount++
        Log.d(TAG, "executeStep: step=$stepCount, totalStep=$totalStepCount, isFirst=$isFirst")
        LogManager.i(TAG, "开始执行步骤 $stepCount (总计: $totalStepCount)")
        _state.value = AgentState.Running(stepCount, agentConfig.maxSteps)
        
        delay(TimingConfig.Agent.SCREENSHOT_DELAY)
        val screenshotData = ScreenshotService.captureWithDimensions()
        if (screenshotData == null) {
            Log.e(TAG, "executeStep: Failed to capture screenshot")
            LogManager.e(TAG, "截图失败")
            return StepResult(
                success = false,
                finished = true,
                action = null,
                thinking = "",
                message = "Failed to capture screenshot"
            )
        }
        
        val (screenshotBase64, width, height) = screenshotData
        screenWidth = width
        screenHeight = height
        
        val currentApp = CurrentAppDetector.getCurrentApp()
        
        if (isFirst) {
            if (context.isEmpty()) {
                context.add(MessageBuilder.createSystemMessage(agentConfig.getEffectiveSystemPrompt()))
            }
            
            val textContent = MessageBuilder.buildFirstStepPrompt(userPrompt ?: "", currentApp)
            LogManager.d(TAG, "=== 第一步 Prompt ===\n$textContent")
            
            context.add(MessageBuilder.createUserMessage(textContent, screenshotBase64))
        } else {
            val screenInfo = MessageBuilder.buildScreenInfo(currentApp)
            val textContent = "** Screen Info **\n\n$screenInfo"
            LogManager.d(TAG, "=== 步骤 $stepCount Prompt ===\n$textContent")
            
            context.add(MessageBuilder.createUserMessage(textContent, screenshotBase64))
        }
        
        val response: ModelResponse
        try {
            Log.d(TAG, "executeStep: Calling model...")
            LogManager.i(TAG, "调用模型... (消息数: ${context.size})")
            response = modelClient.request(context)
            Log.d(TAG, "executeStep: Model response received, thinking=${response.thinking.take(100)}, action=${response.action.take(100)}")
            LogManager.i(TAG, "=== 模型响应 ===")
            LogManager.d(TAG, "Thinking: ${response.thinking}")
            LogManager.i(TAG, "Action: ${response.action}")
            _state.value = AgentState.Running(stepCount, agentConfig.maxSteps, response.thinking)
        } catch (e: Exception) {
            Log.e(TAG, "executeStep: Model error", e)
            LogManager.e(TAG, "模型调用失败: ${e.message}", e)
            return StepResult(
                success = false,
                finished = true,
                action = null,
                thinking = "",
                message = "Model error: ${e.message}",
                screenshotBase64 = screenshotBase64
            )
        }
        
        val action = try {
            val parsed = ActionParser.parse(response.action)
            
            // If response is empty and parsed as finish with empty message, treat as error and continue
            if (parsed.isFinish && response.action.isBlank() && response.thinking.isBlank()) {
                Log.w(TAG, "executeStep: Empty model response, continuing...")
                LogManager.w(TAG, "模型返回空响应，继续执行")
                return StepResult(
                    success = false,
                    finished = false,
                    action = null,
                    thinking = "",
                    message = "Empty model response",
                    screenshotBase64 = screenshotBase64
                )
            }
            
            parsed.also {
                Log.d(TAG, "executeStep: Parsed action: ${it.metadata}, params=${it.params}")
                LogManager.i(TAG, "解析动作: ${it.actionType}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "executeStep: Action parse failed, treating as finish", e)
            LogManager.w(TAG, "动作解析失败: ${e.message}", e)
            ParsedAction(metadata = "finish", params = mapOf("message" to response.action))
        }
        
        if (context.isNotEmpty()) {
            context[context.lastIndex] = MessageBuilder.removeImagesFromMessage(context.last())
        }
        
        val result = try {
            actionHandler.execute(action, screenWidth, screenHeight).also {
                Log.d(TAG, "executeStep: Action executed, success=${it.success}, shouldFinish=${it.shouldFinish}")
                LogManager.i(TAG, "动作执行完成: success=${it.success}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeStep: Action execution failed", e)
            LogManager.e(TAG, "动作执行失败: ${e.message}", e)
            ActionResult(success = false, shouldFinish = false, message = e.message)
        }
        
        context.add(
            MessageBuilder.createAssistantMessage(
                "<think>${response.thinking}</think><answer>${response.action}</answer>"
            )
        )
        
        val finished = action.isFinish || result.shouldFinish
        Log.d(TAG, "executeStep: step=$stepCount completed, finished=$finished, action.isFinish=${action.isFinish}")
        
        return StepResult(
            success = result.success,
            finished = finished,
            action = action,
            thinking = response.thinking,
            message = result.message ?: action.getString("message"),
            screenshotBase64 = screenshotBase64
        )
    }
}
