package com.autoglm.android.ui.home

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autoglm.android.R
import com.autoglm.android.agent.AgentState
import com.autoglm.android.shizuku.ShizukuState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()
    val shizukuState by viewModel.shizukuState.collectAsState()
    val taskInput by viewModel.taskInput.collectAsState()
    val logListState = rememberLazyListState()
    
    LaunchedEffect(uiState.logs.size) {
        if (uiState.logs.isNotEmpty()) {
            logListState.animateScrollToItem(uiState.logs.size - 1)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Shizuku Status Card
        ShizukuStatusCard(
            shizukuState = shizukuState,
            onRequestPermission = viewModel::requestShizukuPermission
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Task Input
        OutlinedTextField(
            value = taskInput,
            onValueChange = viewModel::updateTaskInput,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.task_input_hint)) },
            enabled = uiState.agentState is AgentState.Idle,
            minLines = 2,
            maxLines = 4
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Execute Button
        val isRunning = uiState.agentState is AgentState.Running
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = viewModel::startTask,
                modifier = Modifier.weight(1f),
                enabled = shizukuState.isReady && 
                    taskInput.isNotBlank() && 
                    uiState.agentState is AgentState.Idle
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.executing))
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_start))
                }
            }
            
            if (isRunning) {
                FilledTonalButton(
                    onClick = viewModel::stopTask,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_stop))
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Status
        when (val state = uiState.agentState) {
            is AgentState.Idle -> {}
            is AgentState.Running -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.step_count, state.stepCount, state.maxSteps),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (state.currentThinking.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.currentThinking.take(200) + if (state.currentThinking.length > 200) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
            is AgentState.Completed -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = "✅ ${state.message}",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            is AgentState.Failed -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "❌ ${state.error}",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Execution Log
        Text(
            text = stringResource(R.string.execution_log),
            style = MaterialTheme.typography.titleMedium
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyColumn(
            state = logListState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.logs) { log ->
                LogItem(log)
            }
        }
    }
}

@Composable
fun ShizukuStatusCard(
    shizukuState: ShizukuState,
    onRequestPermission: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (shizukuState.isReady) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
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
fun LogItem(log: LogEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Step ${log.step}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (log.action != null) {
                Text(
                    text = log.action,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        
        if (log.thinking.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = log.thinking,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        if (log.screenshotBase64 != null) {
            Spacer(modifier = Modifier.height(8.dp))
            val bitmap = remember(log.screenshotBase64) {
                try {
                    val bytes = Base64.decode(log.screenshotBase64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    null
                }
            }
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
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

data class LogEntry(
    val step: Int,
    val thinking: String,
    val action: String?,
    val message: String?,
    val screenshotBase64: String? = null
)
