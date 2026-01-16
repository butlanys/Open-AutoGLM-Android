/*
 * Copyright (C) 2024 AutoGLM
 *
 * Multi-task concurrent phone agent that orchestrates multiple app automations
 * running in parallel on virtual displays.
 */

package com.autoglm.android.agent

import android.content.pm.PackageManager
import android.util.Log
import com.autoglm.android.action.ActionHandler
import com.autoglm.android.action.ActionParser
import com.autoglm.android.action.ActionResult
import com.autoglm.android.action.ParsedAction
import com.autoglm.android.config.SystemPrompts
import com.autoglm.android.config.TimingConfig
import com.autoglm.android.data.LogManager
import com.autoglm.android.display.AppDisplayCompatibility
import com.autoglm.android.display.DisplayInputManager
import com.autoglm.android.display.DisplayScreenshotService
import com.autoglm.android.display.VirtualDisplayManager
import com.autoglm.android.model.MessageBuilder
import com.autoglm.android.model.ModelClient
import com.autoglm.android.model.ModelConfig
import com.autoglm.android.model.ModelResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

data class MultiTaskConfig(
    val maxConcurrentTasks: Int = 3,
    val maxStepsPerTask: Int = 100,
    val displayWidth: Int = 1080,
    val displayHeight: Int = 2400,
    val displayDensity: Int = 420,
    val enableVirtualDisplays: Boolean = true,
    val fallbackToSequential: Boolean = true,
    val lang: String = "cn"
)

data class TaskDefinition(
    val id: String,
    val description: String,
    val targetApp: String? = null,
    val priority: Int = 0,
    val dependsOn: List<String> = emptyList()
)

sealed class TaskState {
    object Pending : TaskState()
    object WaitingForDependencies : TaskState()
    data class Running(val displayId: Int, val stepCount: Int) : TaskState()
    data class Paused(val displayId: Int, val stepCount: Int) : TaskState()
    data class Completed(val message: String) : TaskState()
    data class Failed(val error: String) : TaskState()
    data class FallbackToMain(val reason: String) : TaskState()
}

data class TaskProgress(
    val taskId: String,
    val state: TaskState,
    val stepCount: Int = 0,
    val maxSteps: Int = 0,
    val currentThinking: String = "",
    val displayId: Int = 0,
    val screenshotBase64: String? = null
)

sealed class MultiAgentState {
    object Idle : MultiAgentState()
    data class Running(val activeTasks: Int, val completedTasks: Int, val totalTasks: Int) : MultiAgentState()
    data class Paused(val activeTasks: Int, val completedTasks: Int, val totalTasks: Int) : MultiAgentState()
    data class Completed(val results: Map<String, String>) : MultiAgentState()
    data class Failed(val error: String) : MultiAgentState()
}

class MultiTaskPhoneAgent(
    private val modelConfig: ModelConfig,
    private val multiTaskConfig: MultiTaskConfig = MultiTaskConfig(),
    private val packageManager: PackageManager? = null,
    private val onTaskProgress: suspend (TaskProgress) -> Unit = {},
    private val onConfirmationRequired: suspend (String) -> Boolean = { true },
    private val onTakeoverRequired: suspend (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "MultiTaskPhoneAgent"
    }
    
    private val modelClient = ModelClient(modelConfig)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val concurrencySemaphore = Semaphore(multiTaskConfig.maxConcurrentTasks)
    
    private val _state = MutableStateFlow<MultiAgentState>(MultiAgentState.Idle)
    val state: StateFlow<MultiAgentState> = _state.asStateFlow()
    
    private val taskStates = mutableMapOf<String, MutableStateFlow<TaskState>>()
    private val taskResults = mutableMapOf<String, String>()
    private val displayAssignments = mutableMapOf<String, Int>()
    private val taskContexts = mutableMapOf<String, MutableList<Map<String, Any>>>()
    private val taskStepCounts = mutableMapOf<String, Int>()
    
    private val mutex = Mutex()
    
    @Volatile
    private var shouldStop = false
    
    @Volatile
    private var isPaused = false
    
    suspend fun runTasks(tasks: List<TaskDefinition>): Map<String, String> {
        if (tasks.isEmpty()) return emptyMap()
        
        reset()
        shouldStop = false
        
        tasks.forEach { task ->
            taskStates[task.id] = MutableStateFlow(TaskState.Pending)
            taskContexts[task.id] = mutableListOf()
            taskStepCounts[task.id] = 0
        }
        
        _state.value = MultiAgentState.Running(0, 0, tasks.size)
        
        try {
            if (multiTaskConfig.enableVirtualDisplays && tasks.size > 1) {
                runConcurrently(tasks)
            } else {
                runSequentially(tasks)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Task execution failed", e)
            _state.value = MultiAgentState.Failed(e.message ?: "Unknown error")
        } finally {
            cleanupDisplays()
        }
        
        _state.value = MultiAgentState.Completed(taskResults.toMap())
        return taskResults.toMap()
    }
    
    private suspend fun runConcurrently(tasks: List<TaskDefinition>) = coroutineScope {
        val sortedTasks = tasks.sortedByDescending { it.priority }
        val tasksByDependency = groupByDependencies(sortedTasks)
        
        for (group in tasksByDependency) {
            if (shouldStop) break
            
            val jobs = group.map { task ->
                launch {
                    concurrencySemaphore.acquire()
                    try {
                        executeTask(task)
                    } finally {
                        concurrencySemaphore.release()
                    }
                }
            }
            
            jobs.joinAll()
            
            updateState(tasks)
        }
    }
    
    private fun groupByDependencies(tasks: List<TaskDefinition>): List<List<TaskDefinition>> {
        val completed = mutableSetOf<String>()
        val groups = mutableListOf<List<TaskDefinition>>()
        var remaining = tasks.toMutableList()
        
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { task ->
                task.dependsOn.all { it in completed }
            }
            
            if (ready.isEmpty()) {
                groups.add(remaining)
                break
            }
            
            groups.add(ready)
            completed.addAll(ready.map { it.id })
            remaining.removeAll(ready)
        }
        
        return groups
    }
    
    private suspend fun runSequentially(tasks: List<TaskDefinition>) {
        for (task in tasks) {
            if (shouldStop) break
            
            executeTask(task, useVirtualDisplay = false)
            updateState(tasks)
        }
    }
    
    private suspend fun executeTask(
        task: TaskDefinition,
        useVirtualDisplay: Boolean = multiTaskConfig.enableVirtualDisplays
    ) {
        Log.i(TAG, "Starting task: ${task.id} - ${task.description}")
        LogManager.i(TAG, "开始任务: ${task.id} - ${task.description}")
        
        var displayId = 0
        var fellBackToMain = false
        
        if (useVirtualDisplay && task.targetApp != null) {
            displayId = acquireDisplay(task)
            
            if (displayId > 0) {
                val launchResult = AppDisplayCompatibility.launchOnDisplay(
                    packageName = task.targetApp,
                    displayId = displayId
                )
                
                if (launchResult.fellBackToMainDisplay) {
                    Log.w(TAG, "App ${task.targetApp} fell back to main display")
                    LogManager.w(TAG, "应用 ${task.targetApp} 回落到主屏幕")
                    fellBackToMain = true
                    displayId = 0
                    
                    mutex.withLock {
                        taskStates[task.id]?.value = TaskState.FallbackToMain(
                            "App does not support virtual display"
                        )
                    }
                }
            }
        }
        
        displayAssignments[task.id] = displayId
        
        try {
            val result = runTaskLoop(task, displayId)
            
            mutex.withLock {
                taskResults[task.id] = result
                taskStates[task.id]?.value = TaskState.Completed(result)
            }
            
            Log.i(TAG, "Task ${task.id} completed: $result")
            LogManager.i(TAG, "任务 ${task.id} 完成: $result")
            
        } catch (e: Exception) {
            Log.e(TAG, "Task ${task.id} failed", e)
            LogManager.e(TAG, "任务 ${task.id} 失败: ${e.message}")
            
            mutex.withLock {
                taskResults[task.id] = "Error: ${e.message}"
                taskStates[task.id]?.value = TaskState.Failed(e.message ?: "Unknown error")
            }
        } finally {
            if (displayId > 0) {
                releaseDisplay(task.id, displayId)
            }
        }
    }
    
    private suspend fun runTaskLoop(task: TaskDefinition, displayId: Int): String {
        val context = taskContexts[task.id] ?: mutableListOf()
        var stepCount = 0
        
        context.add(MessageBuilder.createSystemMessage(
            SystemPrompts.getSystemPrompt(multiTaskConfig.lang)
        ))
        
        val dimensions = DisplayScreenshotService.getDisplayDimensions(displayId)
            ?: (multiTaskConfig.displayWidth to multiTaskConfig.displayHeight)
        val (screenWidth, screenHeight) = dimensions
        
        var result = executeTaskStep(
            task = task,
            displayId = displayId,
            context = context,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            isFirst = true,
            userPrompt = task.description
        )
        
        stepCount++
        taskStepCounts[task.id] = stepCount
        
        if (result.finished) {
            return result.message ?: "Task completed"
        }
        
        while (stepCount < multiTaskConfig.maxStepsPerTask && !shouldStop) {
            while (isPaused && !shouldStop) {
                mutex.withLock {
                    val currentState = taskStates[task.id]?.value
                    if (currentState is TaskState.Running) {
                        taskStates[task.id]?.value = TaskState.Paused(displayId, stepCount)
                    }
                }
                delay(100)
            }
            
            if (shouldStop) break
            
            result = executeTaskStep(
                task = task,
                displayId = displayId,
                context = context,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                isFirst = false
            )
            
            stepCount++
            taskStepCounts[task.id] = stepCount
            
            if (result.finished) {
                return result.message ?: "Task completed"
            }
        }
        
        return if (shouldStop) "Task stopped by user" else "Max steps reached"
    }
    
    private suspend fun executeTaskStep(
        task: TaskDefinition,
        displayId: Int,
        context: MutableList<Map<String, Any>>,
        screenWidth: Int,
        screenHeight: Int,
        isFirst: Boolean,
        userPrompt: String? = null
    ): StepResult {
        val stepCount = (taskStepCounts[task.id] ?: 0) + 1
        
        mutex.withLock {
            taskStates[task.id]?.value = TaskState.Running(displayId, stepCount)
        }
        
        onTaskProgress(TaskProgress(
            taskId = task.id,
            state = TaskState.Running(displayId, stepCount),
            stepCount = stepCount,
            maxSteps = multiTaskConfig.maxStepsPerTask,
            displayId = displayId
        ))
        
        delay(TimingConfig.Agent.SCREENSHOT_DELAY)
        
        val screenshotData = DisplayScreenshotService.captureWithDimensions(displayId)
        if (screenshotData == null) {
            return StepResult(
                success = false,
                finished = true,
                action = null,
                thinking = "",
                message = "Failed to capture screenshot for display $displayId"
            )
        }
        
        val (screenshotBase64, actualWidth, actualHeight) = screenshotData
        
        val currentApp = task.targetApp ?: "Unknown"
        
        if (isFirst && userPrompt != null) {
            val textContent = MessageBuilder.buildFirstStepPrompt(userPrompt, currentApp)
            context.add(MessageBuilder.createUserMessage(textContent, screenshotBase64))
        } else {
            val screenInfo = MessageBuilder.buildScreenInfo(currentApp)
            context.add(MessageBuilder.createUserMessage(
                "** Screen Info **\n\n$screenInfo",
                screenshotBase64
            ))
        }
        
        val response: ModelResponse
        try {
            response = modelClient.request(context)
            
            onTaskProgress(TaskProgress(
                taskId = task.id,
                state = TaskState.Running(displayId, stepCount),
                stepCount = stepCount,
                maxSteps = multiTaskConfig.maxStepsPerTask,
                currentThinking = response.thinking,
                displayId = displayId,
                screenshotBase64 = screenshotBase64
            ))
        } catch (e: Exception) {
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
            ActionParser.parse(response.action)
        } catch (e: Exception) {
            ParsedAction(metadata = "finish", params = mapOf("message" to response.action))
        }
        
        if (context.isNotEmpty()) {
            context[context.lastIndex] = MessageBuilder.removeImagesFromMessage(context.last())
        }
        
        val actionResult = executeActionOnDisplay(
            action = action,
            displayId = displayId,
            screenWidth = actualWidth,
            screenHeight = actualHeight
        )
        
        context.add(
            MessageBuilder.createAssistantMessage(
                "<think>${response.thinking}</think><answer>${response.action}</answer>"
            )
        )
        
        return StepResult(
            success = actionResult.success,
            finished = action.isFinish || actionResult.shouldFinish,
            action = action,
            thinking = response.thinking,
            message = actionResult.message ?: action.getString("message"),
            screenshotBase64 = screenshotBase64
        )
    }
    
    private suspend fun executeActionOnDisplay(
        action: ParsedAction,
        displayId: Int,
        screenWidth: Int,
        screenHeight: Int
    ): ActionResult {
        if (action.isFinish) {
            return ActionResult(
                success = true,
                shouldFinish = true,
                message = action.getString("message")
            )
        }
        
        if (!action.isDo) {
            return ActionResult(
                success = false,
                shouldFinish = true,
                message = "Unknown action type: ${action.metadata}"
            )
        }
        
        return when (action.actionType) {
            "Launch" -> handleLaunch(action, displayId)
            "Tap" -> handleTap(action, displayId, screenWidth, screenHeight)
            "Type", "Type_Name" -> handleType(action, displayId)
            "Swipe" -> handleSwipe(action, displayId, screenWidth, screenHeight)
            "Back" -> handleBack(displayId)
            "Home" -> handleHome(displayId)
            "Double Tap" -> handleDoubleTap(action, displayId, screenWidth, screenHeight)
            "Long Press" -> handleLongPress(action, displayId, screenWidth, screenHeight)
            "Wait" -> handleWait(action)
            "Take_over" -> handleTakeover(action)
            "Note" -> ActionResult(success = true, shouldFinish = false)
            "Call_API" -> ActionResult(success = true, shouldFinish = false)
            "Interact" -> ActionResult(success = true, shouldFinish = false, message = "User interaction required")
            else -> ActionResult(success = false, shouldFinish = false, message = "Unknown action: ${action.actionType}")
        }
    }
    
    private suspend fun handleLaunch(action: ParsedAction, displayId: Int): ActionResult {
        val appName = action.getString("app")
            ?: return ActionResult(false, false, "No app name specified")
        
        val result = AppDisplayCompatibility.launchOnDisplay(appName, displayId)
        return if (result.success) {
            ActionResult(true, false)
        } else {
            ActionResult(false, false, "App not found: $appName")
        }
    }
    
    private suspend fun handleTap(
        action: ParsedAction,
        displayId: Int,
        screenWidth: Int,
        screenHeight: Int
    ): ActionResult {
        val element = action.getIntList("element")
            ?: return ActionResult(false, false, "No element coordinates")
        
        val message = action.getString("message")
        if (message != null) {
            val confirmed = onConfirmationRequired(message)
            if (!confirmed) {
                return ActionResult(
                    success = false,
                    shouldFinish = true,
                    message = "User cancelled sensitive operation"
                )
            }
        }
        
        val (x, y) = DisplayInputManager.convertRelativeToAbsolute(element, screenWidth, screenHeight)
        val success = DisplayInputManager.tap(x, y, displayId)
        return ActionResult(success, false)
    }
    
    private suspend fun handleType(action: ParsedAction, displayId: Int): ActionResult {
        val text = action.getString("text") ?: ""
        com.autoglm.android.device.InputService.typeText(text)
        return ActionResult(true, false)
    }
    
    private suspend fun handleSwipe(
        action: ParsedAction,
        displayId: Int,
        screenWidth: Int,
        screenHeight: Int
    ): ActionResult {
        val start = action.getIntList("start")
            ?: return ActionResult(false, false, "Missing start coordinates")
        val end = action.getIntList("end")
            ?: return ActionResult(false, false, "Missing end coordinates")
        
        val (startX, startY) = DisplayInputManager.convertRelativeToAbsolute(start, screenWidth, screenHeight)
        val (endX, endY) = DisplayInputManager.convertRelativeToAbsolute(end, screenWidth, screenHeight)
        
        val success = DisplayInputManager.swipe(startX, startY, endX, endY, displayId)
        return ActionResult(success, false)
    }
    
    private suspend fun handleBack(displayId: Int): ActionResult {
        val success = DisplayInputManager.back(displayId)
        return ActionResult(success, false)
    }
    
    private suspend fun handleHome(displayId: Int): ActionResult {
        val success = DisplayInputManager.home(displayId)
        return ActionResult(success, false)
    }
    
    private suspend fun handleDoubleTap(
        action: ParsedAction,
        displayId: Int,
        screenWidth: Int,
        screenHeight: Int
    ): ActionResult {
        val element = action.getIntList("element")
            ?: return ActionResult(false, false, "No element coordinates")
        
        val (x, y) = DisplayInputManager.convertRelativeToAbsolute(element, screenWidth, screenHeight)
        val success = DisplayInputManager.doubleTap(x, y, displayId)
        return ActionResult(success, false)
    }
    
    private suspend fun handleLongPress(
        action: ParsedAction,
        displayId: Int,
        screenWidth: Int,
        screenHeight: Int
    ): ActionResult {
        val element = action.getIntList("element")
            ?: return ActionResult(false, false, "No element coordinates")
        
        val (x, y) = DisplayInputManager.convertRelativeToAbsolute(element, screenWidth, screenHeight)
        val success = DisplayInputManager.longPress(x, y, displayId)
        return ActionResult(success, false)
    }
    
    private suspend fun handleWait(action: ParsedAction): ActionResult {
        val durationStr = action.getString("duration") ?: "1 seconds"
        val duration = try {
            durationStr.replace("seconds", "").trim().toDouble()
        } catch (e: Exception) {
            1.0
        }
        
        delay((duration * 1000).toLong())
        return ActionResult(true, false)
    }
    
    private suspend fun handleTakeover(action: ParsedAction): ActionResult {
        val message = action.getString("message") ?: "User intervention required"
        onTakeoverRequired(message)
        return ActionResult(true, false)
    }
    
    private suspend fun acquireDisplay(task: TaskDefinition): Int {
        val available = VirtualDisplayManager.getAvailableDisplay()
        if (available != null) {
            VirtualDisplayManager.assignPackageToDisplay(available.displayId, task.targetApp ?: task.id)
            return available.displayId
        }
        
        if (VirtualDisplayManager.getActiveDisplays().size < multiTaskConfig.maxConcurrentTasks) {
            val newDisplay = VirtualDisplayManager.createVirtualDisplay(
                width = multiTaskConfig.displayWidth,
                height = multiTaskConfig.displayHeight,
                density = multiTaskConfig.displayDensity,
                name = "AutoGLM-${task.id}"
            )
            
            if (newDisplay != null) {
                VirtualDisplayManager.assignPackageToDisplay(newDisplay.displayId, task.targetApp ?: task.id)
                return newDisplay.displayId
            }
        }
        
        return 0
    }
    
    private suspend fun releaseDisplay(taskId: String, displayId: Int) {
        displayAssignments.remove(taskId)
    }
    
    private suspend fun cleanupDisplays() {
        displayAssignments.clear()
    }
    
    private fun updateState(tasks: List<TaskDefinition>) {
        val completed = taskStates.values.count { it.value is TaskState.Completed }
        val running = taskStates.values.count { 
            it.value is TaskState.Running || it.value is TaskState.Paused 
        }
        
        _state.value = MultiAgentState.Running(running, completed, tasks.size)
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
    
    fun reset() {
        shouldStop = false
        isPaused = false
        taskStates.clear()
        taskResults.clear()
        displayAssignments.clear()
        taskContexts.clear()
        taskStepCounts.clear()
        _state.value = MultiAgentState.Idle
    }
    
    fun getTaskState(taskId: String): StateFlow<TaskState>? = taskStates[taskId]
    
    fun getTaskResult(taskId: String): String? = taskResults[taskId]
    
    fun getAllTaskStates(): Map<String, TaskState> {
        return taskStates.mapValues { it.value.value }
    }
    
    suspend fun destroyAllVirtualDisplays() {
        VirtualDisplayManager.destroyAllDisplays()
    }
}
