/*
 * Copyright (C) 2024 AutoGLM
 *
 * Multi-task concurrent execution screen.
 * Allows users to define and run multiple tasks in parallel.
 */

package com.autoglm.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autoglm.android.agent.MultiAgentState
import com.autoglm.android.agent.TaskDefinition
import com.autoglm.android.agent.TaskProgress
import com.autoglm.android.agent.TaskState
import com.autoglm.android.shizuku.ShizukuState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiTaskScreen(
    viewModel: MultiTaskViewModel = viewModel(factory = MultiTaskViewModel.Factory),
    onNavigateBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val shizukuState by viewModel.shizukuState.collectAsState()
    val pendingTasks by viewModel.pendingTasks.collectAsState()
    
    var showAddTaskDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var maxConcurrent by remember { mutableStateOf(3) }
    var enableVirtualDisplays by remember { mutableStateOf(true) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("多任务并发") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            val isRunning = uiState.agentState is MultiAgentState.Running
            FloatingActionButton(
                onClick = {
                    if (isRunning) {
                        viewModel.stopAllTasks()
                    } else {
                        showAddTaskDialog = true
                    }
                },
                containerColor = if (isRunning) MaterialTheme.colorScheme.error 
                               else MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.Add,
                    contentDescription = if (isRunning) "停止" else "添加任务"
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!shizukuState.isReady) {
                ShizukuWarningCard(
                    state = shizukuState,
                    onRequestPermission = { viewModel.requestShizukuPermission() }
                )
            }
            
            StatusBar(uiState = uiState)
            
            if (pendingTasks.isEmpty() && uiState.results.isEmpty()) {
                EmptyState(onAddTask = { showAddTaskDialog = true })
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(pendingTasks) { task ->
                        TaskCard(
                            task = task,
                            progress = uiState.taskProgress[task.id],
                            result = uiState.results[task.id],
                            onRemove = { viewModel.removeTask(task.id) },
                            onPriorityChange = { priority ->
                                viewModel.updateTaskPriority(task.id, priority)
                            }
                        )
                    }
                }
                
                if (pendingTasks.isNotEmpty() && uiState.agentState is MultiAgentState.Idle) {
                    RunTasksButton(
                        taskCount = pendingTasks.size,
                        maxConcurrent = maxConcurrent,
                        onRun = {
                            viewModel.runTasks(
                                maxConcurrent = maxConcurrent,
                                enableVirtualDisplays = enableVirtualDisplays
                            )
                        }
                    )
                }
                
                if (uiState.agentState is MultiAgentState.Running) {
                    RunningControlBar(
                        state = uiState.agentState as MultiAgentState.Running,
                        onPause = { viewModel.pauseAllTasks() },
                        onResume = { viewModel.resumeAllTasks() },
                        onStop = { viewModel.stopAllTasks() }
                    )
                }
            }
        }
    }
    
    if (showAddTaskDialog) {
        AddTaskDialog(
            onDismiss = { showAddTaskDialog = false },
            onAddTask = { description, targetApp, priority ->
                viewModel.addTask(description, targetApp, priority)
                showAddTaskDialog = false
            }
        )
    }
    
    if (showSettingsDialog) {
        MultiTaskSettingsDialog(
            maxConcurrent = maxConcurrent,
            enableVirtualDisplays = enableVirtualDisplays,
            onDismiss = { showSettingsDialog = false },
            onSave = { concurrent, virtualDisplays ->
                maxConcurrent = concurrent
                enableVirtualDisplays = virtualDisplays
                showSettingsDialog = false
            }
        )
    }
    
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
private fun ShizukuWarningCard(
    state: ShizukuState,
    onRequestPermission: () -> Unit
) {
    val message = when {
        !state.isRunning -> "Shizuku 未运行"
        !state.hasPermission -> "Shizuku 权限未授予"
        else -> "Shizuku 状态未知"
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (state.isRunning && !state.hasPermission) {
                TextButton(onClick = onRequestPermission) {
                    Text("授权")
                }
            }
        }
    }
}

@Composable
private fun StatusBar(uiState: MultiTaskUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            StatusItem(
                icon = Icons.Outlined.Layers,
                label = "虚拟显示器",
                value = "${uiState.activeDisplays}"
            )
            StatusItem(
                icon = Icons.Outlined.Task,
                label = "任务数",
                value = "${uiState.tasks.size}"
            )
            StatusItem(
                icon = Icons.Outlined.CheckCircle,
                label = "已完成",
                value = "${uiState.results.size}"
            )
        }
    }
}

@Composable
private fun StatusItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyState(onAddTask: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.PlaylistAdd,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "添加任务开始并发执行",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "多个任务可以在虚拟显示器上同时运行",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(onClick = onAddTask) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("添加任务")
        }
    }
}

@Composable
private fun TaskCard(
    task: TaskDefinition,
    progress: TaskProgress?,
    result: String?,
    onRemove: () -> Unit,
    onPriorityChange: (Int) -> Unit
) {
    val state = progress?.state
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                is TaskState.Running -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                is TaskState.Completed -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                is TaskState.Failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                is TaskState.FallbackToMain -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.description,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    if (task.targetApp != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Apps,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = task.targetApp,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                if (state == null || state is TaskState.Pending) {
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            if (progress != null) {
                Spacer(modifier = Modifier.height(12.dp))
                TaskProgressIndicator(progress)
            }
            
            if (result != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            AnimatedVisibility(visible = state is TaskState.FallbackToMain) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "已回落到主屏幕执行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskProgressIndicator(progress: TaskProgress) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val stateIcon = when (progress.state) {
                    is TaskState.Running -> Icons.Filled.PlayArrow
                    is TaskState.Paused -> Icons.Filled.Pause
                    is TaskState.Completed -> Icons.Filled.CheckCircle
                    is TaskState.Failed -> Icons.Filled.Error
                    is TaskState.FallbackToMain -> Icons.Outlined.ScreenRotation
                    else -> Icons.Outlined.Schedule
                }
                
                val stateColor = when (progress.state) {
                    is TaskState.Running -> MaterialTheme.colorScheme.primary
                    is TaskState.Completed -> MaterialTheme.colorScheme.tertiary
                    is TaskState.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                
                Icon(
                    stateIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = stateColor
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = when (progress.state) {
                        is TaskState.Running -> "运行中"
                        is TaskState.Paused -> "已暂停"
                        is TaskState.Completed -> "已完成"
                        is TaskState.Failed -> "失败"
                        is TaskState.FallbackToMain -> "主屏幕"
                        is TaskState.WaitingForDependencies -> "等待依赖"
                        else -> "等待中"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = stateColor
                )
            }
            
            Text(
                text = "步骤 ${progress.stepCount}/${progress.maxSteps}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        }
        
        if (progress.displayId > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            MaterialTheme.colorScheme.tertiary,
                            CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "显示器 #${progress.displayId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun RunTasksButton(
    taskCount: Int,
    maxConcurrent: Int,
    onRun: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "$taskCount 个任务待执行",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "最大并发: $maxConcurrent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Button(
                onClick = onRun,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("开始执行")
            }
        }
    }
}

@Composable
private fun RunningControlBar(
    state: MultiAgentState.Running,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    var isPaused by remember { mutableStateOf(false) }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "正在执行 ${state.activeTasks} 个任务",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "已完成 ${state.completedTasks}/${state.totalTasks}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
            
            Row {
                IconButton(
                    onClick = {
                        if (isPaused) {
                            onResume()
                        } else {
                            onPause()
                        }
                        isPaused = !isPaused
                    }
                ) {
                    Icon(
                        if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "继续" else "暂停"
                    )
                }
                
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "停止",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTaskDialog(
    onDismiss: () -> Unit,
    onAddTask: (description: String, targetApp: String?, priority: Int) -> Unit
) {
    var description by remember { mutableStateOf("") }
    var targetApp by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(0) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加任务") },
        text = {
            Column {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("任务描述") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    minLines = 2
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                OutlinedTextField(
                    value = targetApp,
                    onValueChange = { targetApp = it },
                    label = { Text("目标应用包名 (可选)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "优先级: $priority",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = priority.toFloat(),
                    onValueChange = { priority = it.toInt() },
                    valueRange = 0f..10f,
                    steps = 9
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (description.isNotBlank()) {
                        onAddTask(
                            description,
                            targetApp.takeIf { it.isNotBlank() },
                            priority
                        )
                    }
                },
                enabled = description.isNotBlank()
            ) {
                Text("添加")
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
private fun MultiTaskSettingsDialog(
    maxConcurrent: Int,
    enableVirtualDisplays: Boolean,
    onDismiss: () -> Unit,
    onSave: (maxConcurrent: Int, enableVirtualDisplays: Boolean) -> Unit
) {
    var concurrent by remember { mutableStateOf(maxConcurrent) }
    var virtualDisplays by remember { mutableStateOf(enableVirtualDisplays) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("多任务设置") },
        text = {
            Column {
                Text(
                    text = "最大并发任务数: $concurrent",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = concurrent.toFloat(),
                    onValueChange = { concurrent = it.toInt() },
                    valueRange = 1f..5f,
                    steps = 3
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "启用虚拟显示器",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "在虚拟显示器上并行运行应用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = virtualDisplays,
                        onCheckedChange = { virtualDisplays = it }
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "注意: 部分应用可能不支持虚拟显示器，将自动回落到主屏幕执行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(concurrent, virtualDisplays) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun MultiTaskViewModel.requestShizukuPermission() {
    com.autoglm.android.shizuku.ShizukuManager.requestPermission()
}
