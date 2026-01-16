/*
 * Copyright (C) 2024 AutoGLM
 *
 * Virtual Display Manager UI Screen.
 * Displays active virtual displays with live preview thumbnails.
 */

package com.autoglm.android.ui.display

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualDisplayScreen(
    viewModel: VirtualDisplayViewModel = viewModel(factory = VirtualDisplayViewModel.Factory),
    onNavigateBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    
    DisposableEffect(Unit) {
        viewModel.onScreenVisible()
        onDispose {
            viewModel.onScreenHidden()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("虚拟显示管理") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // Streaming mode toggle
                    IconButton(onClick = { viewModel.toggleStreamingMode() }) {
                        Icon(
                            if (uiState.streamingEnabled) Icons.Default.Videocam else Icons.Outlined.Videocam,
                            contentDescription = if (uiState.streamingEnabled) "切换到截图模式" else "切换到流式模式",
                            tint = if (uiState.streamingEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = { viewModel.refreshDisplays() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                    if (uiState.displays.isNotEmpty()) {
                        IconButton(onClick = { showDeleteAllDialog = true }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "清除全部",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "创建虚拟显示")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.displays.isEmpty() -> {
                    EmptyDisplayState(
                        modifier = Modifier.align(Alignment.Center),
                        onCreateClick = { showCreateDialog = true }
                    )
                }
                else -> {
                    DisplayGrid(
                        displays = uiState.displays,
                        onDisplayClick = { viewModel.selectDisplay(it) },
                        onDisplayDelete = { viewModel.destroyDisplay(it.displayInfo.displayId) }
                    )
                }
            }
        }
    }
    
    if (showCreateDialog) {
        CreateDisplayDialog(
            isCreating = uiState.isCreatingDisplay,
            onDismiss = { showCreateDialog = false },
            onCreate = { width, height, density ->
                viewModel.createDisplay(width, height, density)
                showCreateDialog = false
            }
        )
    }
    
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("确认清除") },
            text = { 
                Column {
                    Text("确定要清除所有虚拟显示吗？这将关闭所有在虚拟显示上运行的应用。")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.clearSystemOverlays()
                            showDeleteAllDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("仅清理系统叠加视图")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.destroyAllDisplays()
                        viewModel.clearSystemOverlays()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text("全部清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
    
    uiState.selectedDisplay?.let { display ->
        DisplayPreviewDialog(
            preview = display,
            onDismiss = { viewModel.selectDisplay(null) },
            onDelete = {
                viewModel.destroyDisplay(display.displayInfo.displayId)
                viewModel.selectDisplay(null)
            }
        )
    }
    
    uiState.error?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("错误") },
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
private fun EmptyDisplayState(
    modifier: Modifier = Modifier,
    onCreateClick: () -> Unit
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.ScreenshotMonitor,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Text(
            text = "暂无虚拟显示",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "创建虚拟显示以在后台并行运行多个应用",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        OutlinedButton(onClick = onCreateClick) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("创建虚拟显示")
        }
    }
}

@Composable
private fun DisplayGrid(
    displays: List<DisplayPreview>,
    onDisplayClick: (DisplayPreview) -> Unit,
    onDisplayDelete: (DisplayPreview) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(displays, key = { it.displayInfo.displayId }) { display ->
            DisplayCard(
                preview = display,
                onClick = { onDisplayClick(display) },
                onDelete = { onDisplayDelete(display) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayCard(
    preview: DisplayPreview,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.75f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (preview.previewBitmap != null) {
                    Image(
                        bitmap = preview.previewBitmap.asImageBitmap(),
                        contentDescription = "Display ${preview.displayInfo.displayId} preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
                ) {
                    Text(
                        text = "#${preview.displayInfo.displayId}",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Display ${preview.displayInfo.displayId}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "删除",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                
                Text(
                    text = "${preview.displayInfo.width} × ${preview.displayInfo.height}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                preview.displayInfo.assignedPackage?.let { pkg ->
                    Text(
                        text = pkg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
    
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除虚拟显示") },
            text = { Text("确定要删除 Display ${preview.displayInfo.displayId} 吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun CreateDisplayDialog(
    isCreating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (width: Int, height: Int, density: Int) -> Unit
) {
    var width by remember { mutableStateOf("1080") }
    var height by remember { mutableStateOf("1920") }
    var density by remember { mutableStateOf("320") }
    
    val presets = listOf(
        Triple("720p", 720, 1280),
        Triple("1080p", 1080, 1920),
        Triple("1440p", 1440, 2560)
    )
    
    AlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        title = { Text("创建虚拟显示") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "预设分辨率",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { (name, w, h) ->
                        FilterChip(
                            selected = width == w.toString() && height == h.toString(),
                            onClick = {
                                width = w.toString()
                                height = h.toString()
                            },
                            label = { Text(name) }
                        )
                    }
                }
                
                HorizontalDivider()
                
                Text(
                    text = "自定义分辨率",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { width = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.weight(1f),
                        label = { Text("宽度") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.weight(1f),
                        label = { Text("高度") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                
                OutlinedTextField(
                    value = density,
                    onValueChange = { density = it.filter { c -> c.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("DPI 密度") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    supportingText = { Text("推荐值: 240, 320, 420, 480") }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val w = width.toIntOrNull() ?: 1080
                    val h = height.toIntOrNull() ?: 1920
                    val d = density.toIntOrNull() ?: 320
                    onCreate(w, h, d)
                },
                enabled = !isCreating && width.isNotBlank() && height.isNotBlank() && density.isNotBlank()
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isCreating
            ) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun DisplayPreviewDialog(
    preview: DisplayPreview,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
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
                            text = "Display ${preview.displayInfo.displayId}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${preview.displayInfo.width} × ${preview.displayInfo.height} @ ${preview.displayInfo.density}dpi",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        preview.displayInfo.assignedPackage?.let { pkg ->
                            Text(
                                text = pkg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    
                    Row {
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                }
                
                HorizontalDivider()
                
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    contentAlignment = Alignment.Center
                ) {
                    if (preview.previewBitmap != null) {
                        Image(
                            bitmap = preview.previewBitmap.asImageBitmap(),
                            contentDescription = "Display ${preview.displayInfo.displayId} preview",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "正在加载预览...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                if (preview.lastUpdateTime > 0) {
                    Text(
                        text = "最后更新: ${formatTimestamp(preview.lastUpdateTime)}",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 1000 -> "刚刚"
        diff < 60000 -> "${diff / 1000}秒前"
        diff < 3600000 -> "${diff / 60000}分钟前"
        else -> "${diff / 3600000}小时前"
    }
}
