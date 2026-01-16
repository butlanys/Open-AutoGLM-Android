package com.autoglm.android.ui.chat

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autoglm.android.R
import com.autoglm.android.agent.AgentState
import com.autoglm.android.agent.OrchestratorState
import com.autoglm.android.agent.TaskProgress
import com.autoglm.android.agent.TaskState
import com.autoglm.android.data.db.Message
import com.autoglm.android.data.db.MessageRole
import com.autoglm.android.shizuku.ShizukuState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory),
    onOpenDrawer: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToMultiTask: () -> Unit = {},
    onNavigateToOrchestrator: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val advancedSettings by viewModel.advancedSettings.collectAsState()
    val shizukuState by viewModel.shizukuState.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    
    var editingMessage by remember { mutableStateOf<Message?>(null) }
    
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }
    
    // Edit dialog
    editingMessage?.let { message ->
        EditMessageDialog(
            initialText = message.content,
            onDismiss = { editingMessage = null },
            onConfirm = { newText ->
                viewModel.editAndResend(message, newText)
                editingMessage = null
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.currentConversation?.title ?: stringResource(R.string.app_name),
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleAdvancedSettings() }) {
                        Icon(
                            if (advancedSettings.useOrchestrator) Icons.Default.AutoAwesome else Icons.Outlined.AutoAwesome, 
                            contentDescription = "高级设置",
                            tint = if (advancedSettings.useOrchestrator) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            if (!shizukuState.isReady) {
                ShizukuWarningCard(
                    shizukuState = shizukuState,
                    onRequestPermission = viewModel::requestShizukuPermission,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            
            // Advanced Settings Panel
            AnimatedVisibility(visible = uiState.showAdvancedSettings) {
                AdvancedSettingsPanel(
                    settings = advancedSettings,
                    virtualDisplaySupported = uiState.virtualDisplaySupported,
                    onSettingsChange = { viewModel.updateAdvancedSettings(it) },
                    onDismiss = { viewModel.toggleAdvancedSettings() }
                )
            }
            
            // Orchestrator State and SubTask Progress
            if (advancedSettings.useOrchestrator && uiState.orchestratorState !is OrchestratorState.Idle) {
                OrchestratorStatusCard(
                    state = uiState.orchestratorState,
                    subTaskProgress = uiState.subTaskProgress,
                    summary = uiState.orchestratorSummary,
                    onStop = { viewModel.stopTask() }
                )
            }
            
            // Confirmation dialog for orchestrator
            uiState.confirmationMessage?.let { message ->
                AlertDialog(
                    onDismissRequest = { viewModel.dismissConfirmation() },
                    title = { Text("确认操作") },
                    text = { Text(message) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.confirmAction(true) }) {
                            Text("确认")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissConfirmation() }) {
                            Text("取消")
                        }
                    }
                )
            }
            
            when (val state = uiState.agentState) {
                is AgentState.WaitingForConfirmation -> {
                    ConfirmationDialog(
                        message = state.message,
                        onConfirm = { viewModel.confirmAction(true) },
                        onDismiss = { viewModel.confirmAction(false) }
                    )
                }
                is AgentState.WaitingForTakeover -> {
                    TakeoverDialog(
                        message = state.message,
                        onContinue = viewModel::continueTakeover
                    )
                }
                else -> {}
            }
            
            if (uiState.currentConversation == null && uiState.messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_logo),
                            contentDescription = null,
                            modifier = Modifier.size(80.dp)
                        )
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "开始输入任务，AI 将帮你自动完成",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        val steps = if (message.role == MessageRole.ASSISTANT && 
                            message.id == uiState.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id) {
                            uiState.currentExecutionSteps
                        } else {
                            emptyList()
                        }
                        MessageBubble(
                            message = message,
                            executionSteps = steps,
                            onCopy = { text ->
                                clipboardManager.setText(AnnotatedString(text))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            },
                            onEdit = { editingMessage = it },
                            onRetry = { viewModel.retryMessage(it) }
                        )
                    }
                }
            }
            
            val isRunning = uiState.agentState is AgentState.Running
            val isPaused = uiState.agentState is AgentState.Paused
            val isActive = isRunning || isPaused
            
            if (isActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        Text(
                            text = when (val state = uiState.agentState) {
                                is AgentState.Running -> "执行中 ${state.stepCount}/${state.maxSteps}"
                                is AgentState.Paused -> "已暂停 ${state.stepCount}/${state.maxSteps}"
                                else -> ""
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(
                            onClick = if (isPaused) viewModel::resumeTask else viewModel::pauseTask
                        ) {
                            Icon(
                                if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = null
                            )
                        }
                        FilledTonalIconButton(
                            onClick = viewModel::stopTask,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                        }
                    }
                }
            }
            
            ChatInputBar(
                inputText = inputText,
                onInputChange = viewModel::updateInputText,
                onSend = viewModel::sendMessage,
                enabled = shizukuState.isReady && !isActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
        }
    }
}

@Composable
fun ShizukuWarningCard(
    shizukuState: ShizukuState,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.shizuku_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = when {
                        !shizukuState.isRunning -> stringResource(R.string.shizuku_not_running)
                        !shizukuState.hasPermission -> stringResource(R.string.shizuku_permission_denied)
                        else -> stringResource(R.string.shizuku_permission_granted)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            if (shizukuState.isRunning && !shizukuState.hasPermission) {
                Button(onClick = onRequestPermission) {
                    Text(stringResource(R.string.shizuku_request_permission))
                }
            }
        }
    }
}

@Composable
fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                enabled = enabled,
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            FilledIconButton(
                onClick = onSend,
                enabled = enabled && inputText.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
            }
        }
    }
}

@Composable
fun ConfirmationDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_title)) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.confirm_yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm_no))
            }
        }
    )
}

@Composable
fun TakeoverDialog(
    message: String,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.takeover_title)) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onContinue) {
                Text(stringResource(R.string.takeover_continue))
            }
        }
    )
}

@Composable
fun EditMessageDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑消息") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 6
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) {
                Text("发送")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun AdvancedSettingsPanel(
    settings: AdvancedSettings,
    virtualDisplaySupported: Boolean,
    onSettingsChange: (AdvancedSettings) -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                    text = "智能编排设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Use Orchestrator toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用智能编排", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "自动分析任务，智能决定多任务并发",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.useOrchestrator,
                    onCheckedChange = { onSettingsChange(settings.copy(useOrchestrator = it)) }
                )
            }
            
            AnimatedVisibility(visible = settings.useOrchestrator) {
                Column {
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
                                if (virtualDisplaySupported) "在虚拟显示器上并行运行" else "设备不支持虚拟显示器",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (virtualDisplaySupported) 
                                    MaterialTheme.colorScheme.onSurfaceVariant 
                                else 
                                    MaterialTheme.colorScheme.error
                            )
                        }
                        Switch(
                            checked = settings.enableVirtualDisplays,
                            onCheckedChange = { onSettingsChange(settings.copy(enableVirtualDisplays = it)) },
                            enabled = virtualDisplaySupported
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Max concurrent
                    Text(
                        text = "最大并发数: ${settings.maxConcurrentTasks}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = settings.maxConcurrentTasks.toFloat(),
                        onValueChange = { onSettingsChange(settings.copy(maxConcurrentTasks = it.toInt())) },
                        valueRange = 1f..5f,
                        steps = 3
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
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
                            onCheckedChange = { onSettingsChange(settings.copy(useAdvancedModel = it)) }
                        )
                    }
                    
                    AnimatedVisibility(visible = settings.useAdvancedModel) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = settings.advancedModelUrl,
                                onValueChange = { onSettingsChange(settings.copy(advancedModelUrl = it)) },
                                label = { Text("API URL") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = settings.advancedModelApiKey,
                                onValueChange = { onSettingsChange(settings.copy(advancedModelApiKey = it)) },
                                label = { Text("API Key") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation()
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            OutlinedTextField(
                                value = settings.advancedModelName,
                                onValueChange = { onSettingsChange(settings.copy(advancedModelName = it)) },
                                label = { Text("模型名称") },
                                placeholder = { Text("gpt-4o") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrchestratorStatusCard(
    state: OrchestratorState,
    subTaskProgress: Map<String, TaskProgress>,
    summary: String,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isRunning = state is OrchestratorState.Analyzing || 
                                   state is OrchestratorState.Decomposing ||
                                   state is OrchestratorState.Executing ||
                                   state is OrchestratorState.Deciding ||
                                   state is OrchestratorState.Summarizing
                    
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            if (state is OrchestratorState.Completed) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (state is OrchestratorState.Completed) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.error
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = getOrchestratorStateText(state),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                if (state !is OrchestratorState.Completed && state !is OrchestratorState.Failed) {
                    IconButton(onClick = onStop) {
                        Icon(
                            Icons.Default.Stop, 
                            contentDescription = "停止",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            // SubTask Progress List
            if (subTaskProgress.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                subTaskProgress.values.forEach { progress ->
                    SubTaskItem(progress = progress)
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            
            // Summary
            if (summary.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SubTaskItem(progress: TaskProgress) {
    var expanded by remember { mutableStateOf(false) }
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
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
                    val iconColor = when (progress.state) {
                        is TaskState.Completed -> MaterialTheme.colorScheme.primary
                        is TaskState.Failed -> MaterialTheme.colorScheme.error
                        else -> LocalContentColor.current
                    }
                    Icon(
                        stateIcon, 
                        contentDescription = null, 
                        modifier = Modifier.size(14.dp),
                        tint = iconColor
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = progress.taskId,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (progress.displayId > 0) {
                        Text(
                            text = "#${progress.displayId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (progress.state is TaskState.Running) {
                        Text(
                            text = "${progress.stepCount}/${progress.maxSteps}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            
            if (progress.state is TaskState.Running) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress.stepCount.toFloat() / progress.maxSteps.coerceAtLeast(1).toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            }
            
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(6.dp))
                    if (progress.currentThinking.isNotBlank()) {
                        Text(
                            text = progress.currentThinking,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 10
                        )
                    } else {
                        val stateText = when (progress.state) {
                            is TaskState.Pending -> "等待执行..."
                            is TaskState.Running -> "执行中 (步骤 ${progress.stepCount})"
                            is TaskState.Completed -> "✓ 执行成功"
                            is TaskState.Failed -> "✗ 失败: ${(progress.state as TaskState.Failed).error}"
                            else -> "状态: ${progress.state}"
                        }
                        Text(
                            text = stateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun getOrchestratorStateText(state: OrchestratorState): String {
    return when (state) {
        is OrchestratorState.Idle -> "就绪"
        is OrchestratorState.Analyzing -> "分析任务中..."
        is OrchestratorState.Decomposing -> "分解任务中..."
        is OrchestratorState.Executing -> "执行中 ${state.completedTasks}/${state.totalTasks}"
        is OrchestratorState.Deciding -> "决策中..."
        is OrchestratorState.Summarizing -> "生成总结..."
        is OrchestratorState.Completed -> "完成"
        is OrchestratorState.Failed -> "失败: ${state.error}"
    }
}
