package com.autoglm.android.data

import android.content.Context
import com.autoglm.android.data.db.AppDatabase
import com.autoglm.android.data.db.Conversation
import com.autoglm.android.data.db.ExecutionStep
import com.autoglm.android.data.db.Message
import com.autoglm.android.data.db.MessageRole
import com.autoglm.android.data.db.MessageStatus
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ConversationRepository(context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val conversationDao = database.conversationDao()
    private val messageDao = database.messageDao()
    private val executionStepDao = database.executionStepDao()

    val allConversations: Flow<List<Conversation>> = conversationDao.getAllConversations()

    suspend fun createConversation(title: String = "New Chat"): Conversation {
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        conversationDao.insertConversation(conversation)
        return conversation
    }

    suspend fun getConversation(id: String): Conversation? {
        return conversationDao.getConversationById(id)
    }

    fun getConversationFlow(id: String): Flow<Conversation?> {
        return conversationDao.getConversationByIdFlow(id)
    }

    suspend fun updateConversationTitle(id: String, title: String) {
        conversationDao.updateTitle(id, title)
    }

    suspend fun deleteConversation(id: String) {
        conversationDao.deleteConversationById(id)
    }

    suspend fun archiveConversation(id: String) {
        conversationDao.archiveConversation(id)
    }

    suspend fun deleteAllConversations() {
        conversationDao.deleteAllConversations()
    }

    fun getMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesByConversationId(conversationId)
    }

    suspend fun getMessagesSync(conversationId: String): List<Message> {
        return messageDao.getMessagesByConversationIdSync(conversationId)
    }

    suspend fun addUserMessage(conversationId: String, content: String): Message {
        val message = Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = MessageRole.USER,
            content = content,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.COMPLETED
        )
        messageDao.insertMessage(message)
        conversationDao.updateTimestamp(conversationId)
        
        val messages = messageDao.getMessagesByConversationIdSync(conversationId)
        if (messages.size == 1) {
            val title = content.take(30) + if (content.length > 30) "..." else ""
            conversationDao.updateTitle(conversationId, title)
        }
        
        return message
    }

    suspend fun addAssistantMessage(
        conversationId: String,
        content: String = "",
        status: MessageStatus = MessageStatus.PENDING
    ): Message {
        val message = Message(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = content,
            timestamp = System.currentTimeMillis(),
            status = status
        )
        messageDao.insertMessage(message)
        conversationDao.updateTimestamp(conversationId)
        return message
    }

    suspend fun updateAssistantMessage(
        messageId: String,
        content: String,
        thinking: String?,
        actionSummary: String?,
        stepCount: Int,
        status: MessageStatus
    ) {
        messageDao.updateMessageContent(
            id = messageId,
            content = content,
            thinking = thinking,
            actionSummary = actionSummary,
            stepCount = stepCount,
            status = status
        )
    }

    suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        messageDao.updateMessageStatus(messageId, status)
    }

    suspend fun getMessage(messageId: String): Message? {
        return messageDao.getMessageById(messageId)
    }

    fun getExecutionSteps(messageId: String): Flow<List<ExecutionStep>> {
        return executionStepDao.getStepsByMessageId(messageId)
    }

    suspend fun getExecutionStepsSync(messageId: String): List<ExecutionStep> {
        return executionStepDao.getStepsByMessageIdSync(messageId)
    }

    suspend fun addExecutionStep(
        messageId: String,
        stepNumber: Int,
        actionType: String?,
        thinking: String,
        screenshotBase64: String?,
        success: Boolean = true
    ): ExecutionStep {
        val step = ExecutionStep(
            id = UUID.randomUUID().toString(),
            messageId = messageId,
            stepNumber = stepNumber,
            actionType = actionType,
            thinking = thinking,
            screenshotBase64 = screenshotBase64,
            timestamp = System.currentTimeMillis(),
            success = success
        )
        executionStepDao.insertStep(step)
        return step
    }

    suspend fun getLastMessage(conversationId: String): Message? {
        return messageDao.getLastMessage(conversationId)
    }
}
