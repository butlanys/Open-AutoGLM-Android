/*
 * Copyright (C) 2024 AutoGLM
 *
 * Composable for displaying a streaming virtual display.
 */

package com.autoglm.android.ui.display

import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.autoglm.android.display.DisplayStreamClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DisplayStreamView(
    displayId: Int,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    showControls: Boolean = true
) {
    val scope = rememberCoroutineScope()
    var isStreaming by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf<android.view.Surface?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    
    // Start streaming when surface is ready
    LaunchedEffect(surface, displayId) {
        val s = surface ?: return@LaunchedEffect
        
        try {
            val success = DisplayStreamClient.startStream(displayId, s, width, height)
            isStreaming = success
            if (!success) {
                error = "无法启动视频流"
            }
        } catch (e: Exception) {
            error = e.message
        }
    }
    
    // Cleanup on dispose
    DisposableEffect(displayId) {
        onDispose {
            DisplayStreamClient.stopStream(displayId)
        }
    }
    
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column {
            // Header with controls
            if (showControls) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Display #$displayId",
                        style = MaterialTheme.typography.titleSmall
                    )
                    
                    Row {
                        if (isStreaming) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.Green,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        
                        onClose?.let {
                            IconButton(
                                onClick = it,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "关闭",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
            
            // Video surface
            Box(
                modifier = Modifier
                    .aspectRatio(width.toFloat() / height.toFloat())
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    .background(Color.Black)
                    .pointerInteropFilter { event ->
                        // Forward touch events to virtual display
                        scope.launch {
                            DisplayStreamClient.injectTouch(
                                displayId = displayId,
                                action = event.action,
                                x = event.x * (width.toFloat() / event.device.getMotionRange(MotionEvent.AXIS_X).max),
                                y = event.y * (height.toFloat() / event.device.getMotionRange(MotionEvent.AXIS_Y).max)
                            )
                        }
                        true
                    }
            ) {
                AndroidView(
                    factory = { context ->
                        SurfaceView(context).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    surface = holder.surface
                                }
                                
                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) {
                                    // Surface changed
                                }
                                
                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    surface = null
                                    DisplayStreamClient.stopStream(displayId)
                                    isStreaming = false
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                // Loading or error overlay
                if (!isStreaming && error == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                
                error?.let { err ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.8f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = err,
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DisplayStreamGrid(
    displays: List<Triple<Int, Int, Int>>, // displayId, width, height
    modifier: Modifier = Modifier,
    onDisplayClose: (Int) -> Unit = {}
) {
    val columns = when {
        displays.size <= 1 -> 1
        displays.size <= 4 -> 2
        else -> 3
    }
    
    Column(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        displays.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { (displayId, width, height) ->
                    DisplayStreamView(
                        displayId = displayId,
                        width = width,
                        height = height,
                        modifier = Modifier.weight(1f),
                        onClose = { onDisplayClose(displayId) }
                    )
                }
                
                // Fill empty space if row is not complete
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
