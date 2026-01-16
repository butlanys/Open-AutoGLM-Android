/*
 * Copyright (C) 2024 AutoGLM
 *
 * Orchestrator Agent - A high-level coordinator that manages task decomposition,
 * concurrent execution decisions, and result aggregation.
 * 
 * This agent uses a more capable model to:
 * 1. Analyze if a task requires multi-task concurrent execution
 * 2. Decompose complex tasks into sub-tasks
 * 3. Monitor sub-task completion and decide next steps
 * 4. Generate execution flow diagrams
 * 5. Summarize final results
 */

package com.autoglm.android.agent

import android.util.Log
import com.autoglm.android.data.LogManager
import com.autoglm.android.model.ModelClient
import com.autoglm.android.model.ModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TaskAnalysis(
    val requiresMultiTask: Boolean,
    val reasoning: String,
    val subTasks: List<SubTaskDefinition> = emptyList(),
    val executionStrategy: ExecutionStrategy = ExecutionStrategy.SEQUENTIAL,
    val estimatedComplexity: Int = 1
)

@Serializable
data class SubTaskDefinition(
    val id: String,
    val description: String,
    val targetApp: String? = null,
    val priority: Int = 0,
    val dependsOn: List<String> = emptyList(),
    val canRunConcurrently: Boolean = true,
    val estimatedSteps: Int = 10
)

@Serializable
enum class ExecutionStrategy {
    SEQUENTIAL,      // Execute one by one
    CONCURRENT,      // Execute all in parallel
    HYBRID,          // Some parallel, some sequential based on dependencies
    ADAPTIVE         // Dynamically adjust based on runtime feedback
}

@Serializable
data class SubTaskResult(
    val taskId: String,
    val success: Boolean,
    val result: String,
    val stepsExecuted: Int,
    val displayId: Int,
    val executionTimeMs: Long
)

@Serializable
data class NextStepDecision(
    val action: NextAction,
    val reasoning: String,
    val newSubTasks: List<SubTaskDefinition> = emptyList(),
    val retryTaskIds: List<String> = emptyList()
)

@Serializable
enum class NextAction {
    CONTINUE,        // Continue with remaining tasks
    SPAWN_NEW,       // Create new sub-tasks
    RETRY,           // Retry failed tasks
    COMPLETE,        // All done, generate summary
    ABORT            // Stop execution due to critical failure
}

data class ExecutionNode(
    val taskId: String,
    val taskDescription: String,
    val status: NodeStatus,
    val displayId: Int,
    val startTime: Long,
    val endTime: Long? = null,
    val result: String? = null,
    val children: MutableList<ExecutionNode> = mutableListOf()
)

enum class NodeStatus {
    PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
}

sealed class OrchestratorState {
    object Idle : OrchestratorState()
    data class Analyzing(val task: String) : OrchestratorState()
    data class Decomposing(val analysis: TaskAnalysis) : OrchestratorState()
    data class Executing(val activeTasks: Int, val completedTasks: Int, val totalTasks: Int) : OrchestratorState()
    data class Deciding(val completedResults: List<SubTaskResult>) : OrchestratorState()
    data class Summarizing(val allResults: List<SubTaskResult>) : OrchestratorState()
    data class Completed(val summary: String, val flowDiagram: String) : OrchestratorState()
    data class Failed(val error: String) : OrchestratorState()
}

class OrchestratorAgent(
    private val orchestratorModelConfig: ModelConfig,
    private val workerModelConfig: ModelConfig,
    private val multiTaskConfig: MultiTaskConfig = MultiTaskConfig(),
    private val onStateChange: suspend (OrchestratorState) -> Unit = {},
    private val onSubTaskProgress: suspend (TaskProgress) -> Unit = {},
    private val onConfirmationRequired: suspend (String) -> Boolean = { true },
    private val onTakeoverRequired: suspend (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "OrchestratorAgent"
        
        private val json = Json { 
            ignoreUnknownKeys = true 
            isLenient = true
        }
        
        private const val ORCHESTRATOR_SYSTEM_PROMPT = """你是一个智能任务编排助手。你的职责是：

1. 分析用户任务，判断是否需要多任务并发执行
2. 将复杂任务分解为可独立执行的子任务
3. 确定子任务之间的依赖关系
4. 监控执行进度并做出动态决策

判断需要多任务并发的情况：
- 任务涉及多个不同的应用程序
- 子任务之间相互独立，无依赖关系
- 并行执行可以显著节省时间

请始终以JSON格式返回结构化的响应。"""

        private const val SUMMARY_SYSTEM_PROMPT = """你是一个任务执行总结助手。你的职责是：

1. 清晰地总结任务执行结果
2. 突出关键信息和重要操作
3. 对失败的任务给出可能的原因分析
4. 如有必要，提供改进建议

请用简洁专业的语言进行总结。"""
    }
    
    private val orchestratorClient = ModelClient(orchestratorModelConfig)
    
    private val _state = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val state: StateFlow<OrchestratorState> = _state.asStateFlow()
    
    private val executionHistory = mutableListOf<ExecutionNode>()
    private val allResults = mutableListOf<SubTaskResult>()
    private var rootNode: ExecutionNode? = null
    
    @Volatile
    private var shouldStop = false
    
    suspend fun execute(userTask: String): OrchestratorResult {
        reset()
        shouldStop = false
        
        Log.i(TAG, "Starting orchestration for: $userTask")
        LogManager.i(TAG, "开始任务编排: $userTask")
        
        try {
            // Phase 1: Analyze task
            updateState(OrchestratorState.Analyzing(userTask))
            val analysis = analyzeTask(userTask)
            
            if (shouldStop) return OrchestratorResult.cancelled()
            
            Log.i(TAG, "Analysis complete: requiresMultiTask=${analysis.requiresMultiTask}, subTasks=${analysis.subTasks.size}")
            LogManager.i(TAG, "分析完成: 需要多任务=${analysis.requiresMultiTask}, 子任务数=${analysis.subTasks.size}")
            
            // Initialize execution tree
            rootNode = ExecutionNode(
                taskId = "root",
                taskDescription = userTask,
                status = NodeStatus.RUNNING,
                displayId = 0,
                startTime = System.currentTimeMillis()
            )
            
            // Phase 2: Execute based on analysis
            updateState(OrchestratorState.Decomposing(analysis))
            
            val results = if (analysis.requiresMultiTask && analysis.subTasks.isNotEmpty()) {
                executeMultiTask(analysis, userTask)
            } else {
                executeSingleTask(userTask)
            }
            
            if (shouldStop) return OrchestratorResult.cancelled()
            
            // Phase 3: Generate summary and flow diagram
            updateState(OrchestratorState.Summarizing(results))
            
            val summary = generateSummary(userTask, results)
            val flowDiagram = generateFlowDiagram()
            
            // Update root node
            rootNode = rootNode?.copy(
                status = if (results.all { it.success }) NodeStatus.COMPLETED else NodeStatus.FAILED,
                endTime = System.currentTimeMillis(),
                result = summary
            )
            
            updateState(OrchestratorState.Completed(summary, flowDiagram))
            
            return OrchestratorResult(
                success = results.all { it.success },
                summary = summary,
                flowDiagram = flowDiagram,
                subTaskResults = results,
                executionTree = rootNode
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Orchestration failed", e)
            LogManager.e(TAG, "任务编排失败: ${e.message}", e)
            updateState(OrchestratorState.Failed(e.message ?: "Unknown error"))
            return OrchestratorResult.failed(e.message ?: "Unknown error")
        }
    }
    
    private suspend fun analyzeTask(task: String): TaskAnalysis {
        val prompt = buildAnalysisPrompt(task)
        
        val response = orchestratorClient.request(listOf(
            mapOf("role" to "system", "content" to ORCHESTRATOR_SYSTEM_PROMPT),
            mapOf("role" to "user", "content" to prompt)
        ))
        
        return parseTaskAnalysis(response.action)
    }
    
    private fun buildAnalysisPrompt(task: String): String {
        return """
分析以下用户任务，判断是否需要多任务并发执行，并进行任务分解：

用户任务：$task

请分析：
1. 这个任务是否涉及多个独立的应用操作？
2. 这些操作之间是否有依赖关系？
3. 是否可以并行执行以提高效率？

请以JSON格式返回分析结果：
```json
{
    "requiresMultiTask": true/false,
    "reasoning": "分析理由",
    "subTasks": [
        {
            "id": "task_1",
            "description": "子任务描述",
            "targetApp": "应用包名或null",
            "priority": 0-10,
            "dependsOn": ["依赖的任务id"],
            "canRunConcurrently": true/false,
            "estimatedSteps": 10
        }
    ],
    "executionStrategy": "SEQUENTIAL/CONCURRENT/HYBRID/ADAPTIVE",
    "estimatedComplexity": 1-10
}
```
""".trimIndent()
    }
    
    private fun parseTaskAnalysis(response: String): TaskAnalysis {
        return try {
            val jsonMatch = Regex("""\{[\s\S]*\}""").find(response)
            if (jsonMatch != null) {
                json.decodeFromString<TaskAnalysis>(jsonMatch.value)
            } else {
                // Default to single task
                TaskAnalysis(
                    requiresMultiTask = false,
                    reasoning = "无法解析分析结果，默认单任务执行",
                    executionStrategy = ExecutionStrategy.SEQUENTIAL
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse analysis: ${e.message}")
            TaskAnalysis(
                requiresMultiTask = false,
                reasoning = "解析失败: ${e.message}",
                executionStrategy = ExecutionStrategy.SEQUENTIAL
            )
        }
    }
    
    private suspend fun executeMultiTask(
        analysis: TaskAnalysis,
        originalTask: String
    ): List<SubTaskResult> {
        val results = mutableListOf<SubTaskResult>()
        var currentSubTasks = analysis.subTasks.toMutableList()
        var iteration = 0
        val maxIterations = 5
        
        while (currentSubTasks.isNotEmpty() && iteration < maxIterations && !shouldStop) {
            iteration++
            Log.i(TAG, "Execution iteration $iteration with ${currentSubTasks.size} tasks")
            
            // Convert to TaskDefinitions for MultiTaskPhoneAgent
            val taskDefinitions = currentSubTasks.map { subTask ->
                TaskDefinition(
                    id = subTask.id,
                    description = subTask.description,
                    targetApp = subTask.targetApp,
                    priority = subTask.priority,
                    dependsOn = subTask.dependsOn
                )
            }
            
            // Create execution nodes
            val nodes = currentSubTasks.map { subTask ->
                ExecutionNode(
                    taskId = subTask.id,
                    taskDescription = subTask.description,
                    status = NodeStatus.PENDING,
                    displayId = 0,
                    startTime = System.currentTimeMillis()
                )
            }
            rootNode?.children?.addAll(nodes)
            
            // Execute with MultiTaskPhoneAgent
            val multiTaskAgent = MultiTaskPhoneAgent(
                modelConfig = workerModelConfig,
                multiTaskConfig = multiTaskConfig,
                onTaskProgress = { progress ->
                    // Update node status
                    nodes.find { it.taskId == progress.taskId }?.let { node ->
                        val updatedNode = node.copy(
                            status = when (progress.state) {
                                is TaskState.Running -> NodeStatus.RUNNING
                                is TaskState.Completed -> NodeStatus.COMPLETED
                                is TaskState.Failed -> NodeStatus.FAILED
                                else -> node.status
                            },
                            displayId = progress.displayId
                        )
                        val index = rootNode?.children?.indexOfFirst { it.taskId == node.taskId }
                        if (index != null && index >= 0) {
                            rootNode?.children?.set(index, updatedNode)
                        }
                    }
                    onSubTaskProgress(progress)
                },
                onConfirmationRequired = onConfirmationRequired,
                onTakeoverRequired = onTakeoverRequired
            )
            
            updateState(OrchestratorState.Executing(
                activeTasks = currentSubTasks.size,
                completedTasks = results.size,
                totalTasks = analysis.subTasks.size + results.size
            ))
            
            val taskResults = multiTaskAgent.runTasks(taskDefinitions)
            
            // Convert results
            val iterationResults = taskResults.map { (taskId, result) ->
                val subTask = currentSubTasks.find { it.id == taskId }
                SubTaskResult(
                    taskId = taskId,
                    success = !result.startsWith("Error"),
                    result = result,
                    stepsExecuted = multiTaskAgent.getTaskState(taskId)?.value?.let {
                        when (it) {
                            is TaskState.Running -> it.stepCount
                            is TaskState.Paused -> it.stepCount
                            else -> 0
                        }
                    } ?: 0,
                    displayId = 0,
                    executionTimeMs = System.currentTimeMillis() - (nodes.find { it.taskId == taskId }?.startTime ?: 0)
                )
            }
            
            results.addAll(iterationResults)
            
            // Update nodes with results
            iterationResults.forEach { result ->
                val node = nodes.find { it.taskId == result.taskId }
                node?.let {
                    val updatedNode = it.copy(
                        status = if (result.success) NodeStatus.COMPLETED else NodeStatus.FAILED,
                        endTime = System.currentTimeMillis(),
                        result = result.result
                    )
                    val index = rootNode?.children?.indexOfFirst { c -> c.taskId == it.taskId }
                    if (index != null && index >= 0) {
                        rootNode?.children?.set(index, updatedNode)
                    }
                }
            }
            
            // Ask orchestrator for next steps
            updateState(OrchestratorState.Deciding(iterationResults))
            val decision = decideNextStep(originalTask, analysis, results)
            
            Log.i(TAG, "Next step decision: ${decision.action}")
            LogManager.i(TAG, "下一步决策: ${decision.action} - ${decision.reasoning}")
            
            currentSubTasks = when (decision.action) {
                NextAction.CONTINUE -> mutableListOf() // No new tasks, continue
                NextAction.SPAWN_NEW -> decision.newSubTasks.toMutableList()
                NextAction.RETRY -> {
                    currentSubTasks.filter { it.id in decision.retryTaskIds }.toMutableList()
                }
                NextAction.COMPLETE -> mutableListOf()
                NextAction.ABORT -> {
                    Log.w(TAG, "Aborting execution: ${decision.reasoning}")
                    break
                }
            }
            
            // Cleanup
            multiTaskAgent.destroyAllVirtualDisplays()
        }
        
        return results
    }
    
    private suspend fun executeSingleTask(task: String): List<SubTaskResult> {
        val startTime = System.currentTimeMillis()
        
        val node = ExecutionNode(
            taskId = "single_task",
            taskDescription = task,
            status = NodeStatus.RUNNING,
            displayId = 0,
            startTime = startTime
        )
        rootNode?.children?.add(node)
        
        var stepCount = 0
        
        val agent = PhoneAgent(
            modelConfig = workerModelConfig,
            agentConfig = AgentConfig(
                maxSteps = multiTaskConfig.maxStepsPerTask,
                lang = multiTaskConfig.lang
            ),
            onConfirmationRequired = onConfirmationRequired,
            onTakeoverRequired = onTakeoverRequired,
            onStepCompleted = { result ->
                stepCount++
                onSubTaskProgress(TaskProgress(
                    taskId = "single_task",
                    state = TaskState.Running(0, stepCount),
                    stepCount = stepCount,
                    maxSteps = multiTaskConfig.maxStepsPerTask,
                    currentThinking = result.thinking,
                    displayId = 0,
                    screenshotBase64 = result.screenshotBase64
                ))
            }
        )
        
        updateState(OrchestratorState.Executing(1, 0, 1))
        
        val result = try {
            agent.run(task)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
        
        val endTime = System.currentTimeMillis()
        
        // Update node
        val updatedNode = node.copy(
            status = if (!result.startsWith("Error")) NodeStatus.COMPLETED else NodeStatus.FAILED,
            endTime = endTime,
            result = result
        )
        rootNode?.children?.set(0, updatedNode)
        
        return listOf(SubTaskResult(
            taskId = "single_task",
            success = !result.startsWith("Error"),
            result = result,
            stepsExecuted = agent.getTotalStepCount(),
            displayId = 0,
            executionTimeMs = endTime - startTime
        ))
    }
    
    private suspend fun decideNextStep(
        originalTask: String,
        analysis: TaskAnalysis,
        currentResults: List<SubTaskResult>
    ): NextStepDecision {
        val prompt = buildDecisionPrompt(originalTask, analysis, currentResults)
        
        val response = orchestratorClient.request(listOf(
            mapOf("role" to "system", "content" to ORCHESTRATOR_SYSTEM_PROMPT),
            mapOf("role" to "user", "content" to prompt)
        ))
        
        return parseNextStepDecision(response.action)
    }
    
    private fun buildDecisionPrompt(
        originalTask: String,
        analysis: TaskAnalysis,
        results: List<SubTaskResult>
    ): String {
        val resultsJson = results.joinToString("\n") { result ->
            """  - ${result.taskId}: ${if (result.success) "成功" else "失败"} - ${result.result}"""
        }
        
        return """
原始任务：$originalTask

任务分析：
- 执行策略：${analysis.executionStrategy}
- 预计复杂度：${analysis.estimatedComplexity}

已完成的子任务结果：
$resultsJson

请判断下一步操作：
1. CONTINUE - 所有任务已完成，继续到总结阶段
2. SPAWN_NEW - 需要创建新的子任务
3. RETRY - 需要重试失败的任务
4. COMPLETE - 任务全部完成
5. ABORT - 由于严重错误需要中止

请以JSON格式返回：
```json
{
    "action": "CONTINUE/SPAWN_NEW/RETRY/COMPLETE/ABORT",
    "reasoning": "决策理由",
    "newSubTasks": [],
    "retryTaskIds": []
}
```
""".trimIndent()
    }
    
    private fun parseNextStepDecision(response: String): NextStepDecision {
        return try {
            val jsonMatch = Regex("""\{[\s\S]*\}""").find(response)
            if (jsonMatch != null) {
                json.decodeFromString<NextStepDecision>(jsonMatch.value)
            } else {
                NextStepDecision(
                    action = NextAction.COMPLETE,
                    reasoning = "无法解析决策，默认完成"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse decision: ${e.message}")
            NextStepDecision(
                action = NextAction.COMPLETE,
                reasoning = "解析失败，默认完成"
            )
        }
    }
    
    private suspend fun generateSummary(
        originalTask: String,
        results: List<SubTaskResult>
    ): String {
        val prompt = buildSummaryPrompt(originalTask, results)
        
        val response = orchestratorClient.request(listOf(
            mapOf("role" to "system", "content" to SUMMARY_SYSTEM_PROMPT),
            mapOf("role" to "user", "content" to prompt)
        ))
        
        return response.action.ifBlank { 
            "任务执行完成。成功: ${results.count { it.success }}/${results.size}" 
        }
    }
    
    private fun buildSummaryPrompt(
        originalTask: String,
        results: List<SubTaskResult>
    ): String {
        val resultsDetail = results.joinToString("\n\n") { result ->
            """
子任务: ${result.taskId}
状态: ${if (result.success) "✓ 成功" else "✗ 失败"}
执行步骤: ${result.stepsExecuted}
执行时间: ${result.executionTimeMs}ms
结果: ${result.result}
""".trimIndent()
        }
        
        val totalTime = results.sumOf { it.executionTimeMs }
        val successCount = results.count { it.success }
        
        return """
请为以下任务执行生成总结报告：

原始任务：$originalTask

执行结果：
$resultsDetail

统计信息：
- 总任务数: ${results.size}
- 成功: $successCount
- 失败: ${results.size - successCount}
- 总耗时: ${totalTime}ms

请生成一份简洁的执行总结，包括：
1. 任务完成情况概述
2. 关键操作结果
3. 如有失败，说明原因
4. 改进建议（如有）
""".trimIndent()
    }
    
    private fun generateFlowDiagram(): String {
        val root = rootNode ?: return ""
        
        val sb = StringBuilder()
        sb.appendLine("```mermaid")
        sb.appendLine("flowchart TB")
        
        // Root node
        val rootStatus = getStatusEmoji(root.status)
        sb.appendLine("    root[\"$rootStatus ${escapeForMermaid(root.taskDescription)}\"]")
        
        // Child nodes
        root.children.forEachIndexed { index, child ->
            val childId = "task_$index"
            val status = getStatusEmoji(child.status)
            val displayInfo = if (child.displayId > 0) " [Display #${child.displayId}]" else ""
            val duration = child.endTime?.let { (it - child.startTime) / 1000.0 }?.let { "${it}s" } ?: "..."
            
            sb.appendLine("    $childId[\"$status ${escapeForMermaid(child.taskDescription)}$displayInfo\\n⏱️ $duration\"]")
            sb.appendLine("    root --> $childId")
            
            // Apply styling based on status
            val styleClass = when (child.status) {
                NodeStatus.COMPLETED -> "completed"
                NodeStatus.FAILED -> "failed"
                NodeStatus.RUNNING -> "running"
                else -> "pending"
            }
            sb.appendLine("    class $childId $styleClass")
        }
        
        // Style definitions
        sb.appendLine()
        sb.appendLine("    classDef completed fill:#1a472a,stroke:#2ecc71,color:#fff")
        sb.appendLine("    classDef failed fill:#641e16,stroke:#e74c3c,color:#fff")
        sb.appendLine("    classDef running fill:#1a3a5c,stroke:#3498db,color:#fff")
        sb.appendLine("    classDef pending fill:#2c2c2c,stroke:#95a5a6,color:#fff")
        
        sb.appendLine("```")
        
        return sb.toString()
    }
    
    private fun getStatusEmoji(status: NodeStatus): String {
        return when (status) {
            NodeStatus.COMPLETED -> "✅"
            NodeStatus.FAILED -> "❌"
            NodeStatus.RUNNING -> "🔄"
            NodeStatus.PENDING -> "⏳"
            NodeStatus.SKIPPED -> "⏭️"
        }
    }
    
    private fun escapeForMermaid(text: String): String {
        return text
            .replace("\"", "'")
            .replace("\n", " ")
            .take(50)
            .let { if (text.length > 50) "$it..." else it }
    }
    
    private suspend fun updateState(newState: OrchestratorState) {
        _state.value = newState
        onStateChange(newState)
    }
    
    fun stop() {
        shouldStop = true
    }
    
    fun reset() {
        shouldStop = false
        executionHistory.clear()
        allResults.clear()
        rootNode = null
        _state.value = OrchestratorState.Idle
    }
}

data class OrchestratorResult(
    val success: Boolean,
    val summary: String,
    val flowDiagram: String,
    val subTaskResults: List<SubTaskResult>,
    val executionTree: ExecutionNode?
) {
    companion object {
        fun cancelled() = OrchestratorResult(
            success = false,
            summary = "任务已取消",
            flowDiagram = "",
            subTaskResults = emptyList(),
            executionTree = null
        )
        
        fun failed(error: String) = OrchestratorResult(
            success = false,
            summary = "任务失败: $error",
            flowDiagram = "",
            subTaskResults = emptyList(),
            executionTree = null
        )
    }
}
