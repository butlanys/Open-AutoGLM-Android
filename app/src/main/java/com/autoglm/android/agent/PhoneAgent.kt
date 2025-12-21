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
    data class WaitingForConfirmation(val message: String) : AgentState()
    data class WaitingForTakeover(val message: String) : AgentState()
    data class Completed(val message: String) : AgentState()
    data class Failed(val error: String) : AgentState()
}

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
    private var screenWidth = 0
    private var screenHeight = 0
    
    @Volatile
    private var shouldStop = false
    
    suspend fun run(task: String): String {
        reset()
        shouldStop = false
        
        // Get screen dimensions
        val dimensions = CurrentAppDetector.getScreenSize()
        if (dimensions != null) {
            screenWidth = dimensions.first
            screenHeight = dimensions.second
        } else {
            screenWidth = 1080
            screenHeight = 2400
        }
        
        // First step
        var result = executeStep(task, isFirst = true)
        onStepCompleted(result)
        
        if (result.finished) {
            _state.value = AgentState.Completed(result.message ?: "Task completed")
            return result.message ?: "Task completed"
        }
        
        // Continue until finished or max steps
        while (stepCount < agentConfig.maxSteps && !shouldStop) {
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
    }
    
    fun reset() {
        context.clear()
        stepCount = 0
        _state.value = AgentState.Idle
        shouldStop = false
    }
    
    private suspend fun executeStep(
        userPrompt: String? = null,
        isFirst: Boolean = false
    ): StepResult {
        stepCount++
        Log.d(TAG, "executeStep: step=$stepCount, isFirst=$isFirst")
        LogManager.i(TAG, "开始执行步骤 $stepCount")
        _state.value = AgentState.Running(stepCount, agentConfig.maxSteps)
        
        // Capture screen
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
        
        // Build messages
        if (isFirst) {
            context.add(MessageBuilder.createSystemMessage(agentConfig.getEffectiveSystemPrompt()))
            
            val screenInfo = MessageBuilder.buildScreenInfo(currentApp)
            val textContent = "$userPrompt\n\n$screenInfo"
            
            context.add(MessageBuilder.createUserMessage(textContent, screenshotBase64))
        } else {
            val screenInfo = MessageBuilder.buildScreenInfo(currentApp)
            val textContent = "** Screen Info **\n\n$screenInfo"
            
            context.add(MessageBuilder.createUserMessage(textContent, screenshotBase64))
        }
        
        // Get model response
        val response: ModelResponse
        try {
            Log.d(TAG, "executeStep: Calling model...")
            LogManager.i(TAG, "调用模型...")
            response = modelClient.request(context)
            Log.d(TAG, "executeStep: Model response received, thinking=${response.thinking.take(100)}, action=${response.action.take(100)}")
            LogManager.i(TAG, "模型响应: ${response.action.take(50)}...")
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
        
        // Parse action
        val action = try {
            ActionParser.parse(response.action).also {
                Log.d(TAG, "executeStep: Parsed action: ${it.metadata}, params=${it.params}")
                LogManager.i(TAG, "解析动作: ${it.actionType}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "executeStep: Action parse failed, treating as finish", e)
            LogManager.w(TAG, "动作解析失败: ${e.message}", e)
            ParsedAction(metadata = "finish", params = mapOf("message" to response.action))
        }
        
        // Remove image from context to save memory
        if (context.isNotEmpty()) {
            context[context.lastIndex] = MessageBuilder.removeImagesFromMessage(context.last())
        }
        
        // Execute action
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
        
        // Add assistant response to context
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
