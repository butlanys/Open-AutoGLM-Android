package com.autoglm.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autoglm.android.R
import com.autoglm.android.data.db.Conversation
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDrawer(
    conversations: List<Conversation>,
    currentConversationId: String?,
    onNewConversation: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var deleteDialogConversation by remember { mutableStateOf<Conversation?>(null) }
    
    ModalDrawerSheet(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Button(
                onClick = onNewConversation,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.new_chat))
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            HorizontalDivider()
            
            Spacer(modifier = Modifier.height(8.dp))
            
            val groupedConversations = groupConversationsByDate(conversations)
            
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                groupedConversations.forEach { (label, convs) ->
                    item {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    
                    items(convs, key = { it.id }) { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            isSelected = conversation.id == currentConversationId,
                            onClick = { onSelectConversation(conversation.id) },
                            onDelete = { deleteDialogConversation = conversation }
                        )
                    }
                }
            }
        }
    }
    
    deleteDialogConversation?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleteDialogConversation = null },
            title = { Text(stringResource(R.string.delete_conversation)) },
            text = { Text(stringResource(R.string.delete_conversation_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteConversation(conversation.id)
                        deleteDialogConversation = null
                    }
                ) {
                    Text(stringResource(R.string.confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogConversation = null }) {
                    Text(stringResource(R.string.confirm_no))
                }
            }
        )
    }
}

@Composable
fun ConversationItem(
    conversation: Conversation,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationDrawerItem(
        icon = {
            Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null)
        },
        label = {
            Text(
                text = conversation.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        selected = isSelected,
        onClick = onClick,
        modifier = modifier,
        badge = {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_conversation),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun groupConversationsByDate(conversations: List<Conversation>): List<Pair<String, List<Conversation>>> {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    
    val yesterday = today - 24 * 60 * 60 * 1000
    
    val todayLabel = stringResource(R.string.today)
    val yesterdayLabel = stringResource(R.string.yesterday)
    val earlierLabel = stringResource(R.string.earlier)
    
    val grouped = conversations.groupBy { conv ->
        when {
            conv.updatedAt >= today -> todayLabel
            conv.updatedAt >= yesterday -> yesterdayLabel
            else -> earlierLabel
        }
    }
    
    return listOfNotNull(
        grouped[todayLabel]?.let { todayLabel to it },
        grouped[yesterdayLabel]?.let { yesterdayLabel to it },
        grouped[earlierLabel]?.let { earlierLabel to it }
    )
}
