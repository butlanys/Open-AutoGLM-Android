/*
 * Copyright (C) 2024 AutoGLM
 *
 * Orchestrator Screen - UI for intelligent task orchestration with
 * automatic multi-task detection, execution visualization, and summary.
 */

package com.autoglm.android.ui.chat

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autoglm.android.agent.*
import com.autoglm.android.shizuku.ShizukuState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrchestratorScreen(
    viewModel: OrchestratorViewModel = viewModel(factory = OrchestratorViewModel.Factory),
    onNavigateBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings by viewModel.orchestratorSettings.collectAsState()
    val shizukuState by viewModel.shizukuState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("智能任务编排")
                        Text(
                            text = getStateDescription(uiState.orchestratorState),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleSettings() }) {
                        Icon(Icons.Outlined.Tune, contentDescription = "设置")
                    }
                    if (uiState.orchestratorState is OrchestratorState.Executing ||
                        uiState.orchestratorState is OrchestratorState.Analyzing) {
                        IconButton(onClick = { viewModel.stopExecution() }) {
                            Icon(
                                Icons.Default.Stop, 
                                contentDescription = "停止",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (!shizukuState.isReady) {
                ShizukuWarningBanner(
                    state = shizukuState,
                    onRequestPermission = { viewModel.requestShizukuPermission() }
                )
            }
            
            // Settings Panel
            AnimatedVisibility(visible = uiState.showSettings) {
                OrchestratorSettingsPanel(
                    settings = settings,
                    onSettingsChange = { viewModel.updateSettings(it) },
                    onDismiss = { viewModel.toggleSettings() }
                )
            }
            
            // Main Content
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Task Analysis Card
                uiState.taskAnalysis?.let { analysis ->
                    item {
                        TaskAnalysisCard(analysis = analysis)
                    }
                }
                
                // Execution Progress
                if (uiState.subTaskProgress.isNotEmpty()) {
                    item {
                        Text(
                            text = "执行进度",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    items(uiState.subTaskProgress.values.toList()) { progress ->
                        SubTaskProgressCard(progress = progress)
                    }
                }
                
                // Flow Diagram
                if (uiState.flowDiagram.isNotBlank()) {
                    item {
                        FlowDiagramCard(diagram = uiState.flowDiagram)
                    }
                }
                
                // Summary
                if (uiState.summary.isNotBlank()) {
                    item {
                        SummaryCard(summary = uiState.summary)
                    }
                }
                
                // Results
                if (uiState.results.isNotEmpty()) {
                    item {
                        Text(
                            text = "执行结果",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    items(uiState.results) { result ->
                        SubTaskResultCard(result = result)
                    }
                }
                
                // Empty State
                if (uiState.orchestratorState is OrchestratorState.Idle && 
                    uiState.results.isEmpty()) {
                    item {
                        EmptyStateCard()
                    }
                }
            }
            
            // Input Area
            InputArea(
                value = uiState.userInput,
                onValueChange = { viewModel.updateInput(it) },
                onSend = { viewModel.executeTask() },
                isLoading = uiState.isInitializing || 
                           uiState.orchestratorState is OrchestratorState.Analyzing ||
                           uiState.orchestratorState is OrchestratorState.Executing
            )
        }
    }
    
    // Confirmation Dialog
    uiState.confirmationMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.confirmAction(false) },
            title = { Text("确认操作") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmAction(true) }) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmAction(false) }) {
                    Text("取消")
                }
            }
        )
    }
    
    // Error Dialog
    uiState.error?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("提示") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) {
                    Text("确定")
                }
            }
        )
    }
}

@Composable
private fun ShizukuWarningBanner(
    state: ShizukuState,
    onRequestPermission: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (!state.isRunning) "Shizuku 未运行" else "Shizuku 权限未授予",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            if (state.isRunning && !state.hasPermission) {
                TextButton(onClick = onRequestPermission) {
                    Text("授权")
                }
            }
        }
    }
}

@Composable
private fun OrchestratorSettingsPanel(
    settings: OrchestratorSettings,
    onSettingsChange: (OrchestratorSettings) -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .imePadding(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "编排设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Auto decide multi-task
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动判断多任务", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "由高级模型自动决定是否启用并发",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.autoDecideMultiTask,
                    onCheckedChange = { 
                        onSettingsChange(settings.copy(autoDecideMultiTask = it))
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Enable virtual displays
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用虚拟显示器", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "在虚拟显示器上并行运行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.enableVirtualDisplays,
                    onCheckedChange = { 
                        onSettingsChange(settings.copy(enableVirtualDisplays = it))
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Max concurrent
            Text(
                text = "最大并发数: ${settings.maxConcurrentTasks}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = settings.maxConcurrentTasks.toFloat(),
                onValueChange = { 
                    onSettingsChange(settings.copy(maxConcurrentTasks = it.toInt()))
                },
                valueRange = 1f..5f,
                steps = 3
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            
            // Use advanced model toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("使用高级模型", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "为编排器使用独立的高级模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.useAdvancedModel,
                    onCheckedChange = { 
                        onSettingsChange(settings.copy(useAdvancedModel = it))
                    }
                )
            }
            
            // Advanced model settings (only show when enabled)
            AnimatedVisibility(visible = settings.useAdvancedModel) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = settings.advancedModelUrl,
                        onValueChange = { 
                            onSettingsChange(settings.copy(advancedModelUrl = it))
                        },
                        label = { Text("高级模型 API URL") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = settings.advancedModelApiKey,
                        onValueChange = { 
                            onSettingsChange(settings.copy(advancedModelApiKey = it))
                        },
                        label = { Text("高级模型 API Key") },
                        placeholder = { Text("sk-...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = settings.advancedModelName,
                        onValueChange = { 
                            onSettingsChange(settings.copy(advancedModelName = it))
                        },
                        label = { Text("高级模型名称") },
                        placeholder = { Text("gpt-4o") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        "提示: 高级模型用于任务分析和编排决策，工作模型使用全局设置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskAnalysisCard(analysis: TaskAnalysis) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Analytics,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "任务分析",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Multi-task indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (analysis.requiresMultiTask) 
                                MaterialTheme.colorScheme.tertiary 
                            else MaterialTheme.colorScheme.outline,
                            CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (analysis.requiresMultiTask) "需要多任务并发" else "单任务执行",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Strategy
            Row {
                AssistChip(
                    onClick = { },
                    label = { Text(analysis.executionStrategy.name) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Route,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                AssistChip(
                    onClick = { },
                    label = { Text("复杂度: ${analysis.estimatedComplexity}") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
            
            // Sub-tasks
            if (analysis.subTasks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "子任务 (${analysis.subTasks.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                
                analysis.subTasks.forEachIndexed { index, subTask ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = subTask.description,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (subTask.targetApp != null) {
                                Text(
                                    text = "📱 ${subTask.targetApp}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
            }
            
            // Reasoning
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = analysis.reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SubTaskProgressCard(progress: TaskProgress) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (progress.state) {
                is TaskState.Running -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                is TaskState.Completed -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                is TaskState.Failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val stateIcon = when (progress.state) {
                        is TaskState.Running -> Icons.Default.PlayArrow
                        is TaskState.Completed -> Icons.Default.CheckCircle
                        is TaskState.Failed -> Icons.Default.Error
                        else -> Icons.Outlined.Schedule
                    }
                    Icon(stateIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = progress.taskId,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                if (progress.displayId > 0) {
                    AssistChip(
                        onClick = { },
                        label = { Text("Display #${progress.displayId}") },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
            
            if (progress.state is TaskState.Running) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.stepCount.toFloat() / progress.maxSteps.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "步骤 ${progress.stepCount}/${progress.maxSteps}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (progress.currentThinking.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = progress.currentThinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FlowDiagramCard(diagram: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.AccountTree,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "执行流程图",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Display the mermaid diagram as code block
            // In a real app, you might want to render this with a WebView
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = diagram,
                    modifier = Modifier
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    color = Color(0xFFD4D4D4)
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Summarize,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "执行总结",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SubTaskResultCard(result: SubTaskResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (result.success) 
                MaterialTheme.colorScheme.surface 
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (result.success) 
                            MaterialTheme.colorScheme.tertiary 
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = result.taskId,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Text(
                    text = "${result.executionTimeMs / 1000.0}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = result.result,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "执行 ${result.stepsExecuted} 步",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun EmptyStateCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "智能任务编排",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "输入任务描述，AI将自动分析并决定最优执行策略",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• 自动判断是否需要多任务并发\n• 智能分解复杂任务\n• 动态调度与资源管理\n• 生成执行流程图与总结",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun InputArea(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("描述你的任务...") },
                minLines = 1,
                maxLines = 4,
                enabled = !isLoading
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            FilledIconButton(
                onClick = onSend,
                enabled = value.isNotBlank() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

private fun getStateDescription(state: OrchestratorState): String {
    return when (state) {
        is OrchestratorState.Idle -> "就绪"
        is OrchestratorState.Analyzing -> "正在分析任务..."
        is OrchestratorState.Decomposing -> "正在分解任务..."
        is OrchestratorState.Executing -> "执行中 ${state.completedTasks}/${state.totalTasks}"
        is OrchestratorState.Deciding -> "决策中..."
        is OrchestratorState.Summarizing -> "生成总结..."
        is OrchestratorState.Completed -> "完成"
        is OrchestratorState.Failed -> "失败"
    }
}
