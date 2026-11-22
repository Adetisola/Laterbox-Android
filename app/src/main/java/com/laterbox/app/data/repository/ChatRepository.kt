package com.laterbox.app.data.repository

import com.laterbox.app.data.local.dao.ChatDao
import com.laterbox.app.data.local.entity.ChatActionPermissionEntity
import com.laterbox.app.data.local.entity.ChatConversationEntity
import com.laterbox.app.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

enum class ChatRole {
    USER,
    ASSISTANT
}

enum class ChatMessageStatus {
    COMPLETE,
    STREAMING,
    FAILED,
    CANCELLED
}

class ChatRepository(
    private val chatDao: ChatDao
) {

    fun observeRecentConversations(limit: Int = 30): Flow<List<ChatConversationEntity>> =
        chatDao.getRecentConversations(limit)

    fun observeConversation(conversationId: String): Flow<ChatConversationEntity?> =
        chatDao.observeConversationById(conversationId)

    fun observeMessages(conversationId: String): Flow<List<ChatMessageEntity>> =
        chatDao.getMessagesForConversation(conversationId)

    suspend fun getConversation(conversationId: String): ChatConversationEntity? =
        chatDao.getConversationById(conversationId)

    suspend fun getMessagesSnapshot(conversationId: String): List<ChatMessageEntity> =
        chatDao.getMessagesSnapshot(conversationId)

    suspend fun createConversation(initialPrompt: String? = null): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val title = initialPrompt?.let(::deriveTitleFromPrompt) ?: "New conversation"
        val preview = initialPrompt?.trim()?.take(120)?.takeIf { it.isNotEmpty() }
        chatDao.upsertConversation(
            ChatConversationEntity(
                id = id,
                title = title,
                createdAt = now,
                updatedAt = now,
                lastMessagePreview = preview,
                isPinned = false
            )
        )
        return id
    }

    /**
     * Persists a user message immediately BEFORE any remote Gemini call runs,
     * creating the conversation row first if needed.
     */
    suspend fun addUserMessage(conversationId: String, text: String): ChatMessageEntity {
        val trimmed = text.trim()
        val now = System.currentTimeMillis()
        val existingConv = chatDao.getConversationById(conversationId)
        val derivedTitle = deriveTitleFromPrompt(trimmed)
        if (existingConv == null) {
            chatDao.upsertConversation(
                ChatConversationEntity(
                    id = conversationId,
                    title = derivedTitle,
                    createdAt = now,
                    updatedAt = now,
                    lastMessagePreview = trimmed.take(120),
                    isPinned = false
                )
            )
        } else {
            val updatedTitle = if (!existingConv.isTitleManual && existingConv.title == "New conversation") {
                derivedTitle
            } else {
                existingConv.title
            }
            chatDao.upsertConversation(
                existingConv.copy(
                    title = updatedTitle,
                    updatedAt = now,
                    lastMessagePreview = trimmed.take(120)
                )
            )
        }

        val message = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = ChatRole.USER.name,
            content = trimmed,
            actionsJson = null,
            status = ChatMessageStatus.COMPLETE.name,
            errorMessage = null,
            createdAt = now
        )
        chatDao.upsertMessage(message)
        return message
    }

    suspend fun createAssistantPlaceholder(conversationId: String, createdAt: Long = System.currentTimeMillis()): ChatMessageEntity {
        val message = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = ChatRole.ASSISTANT.name,
            content = "",
            actionsJson = null,
            status = ChatMessageStatus.STREAMING.name,
            errorMessage = null,
            createdAt = createdAt
        )
        chatDao.upsertMessage(message)
        return message
    }

    suspend fun updateAssistantMessage(
        messageId: String,
        conversationId: String,
        content: String,
        status: ChatMessageStatus,
        actionsJson: String? = null,
        errorMessage: String? = null
    ) {
        val snapshot = chatDao.getMessagesSnapshot(conversationId).firstOrNull { it.id == messageId }
        val now = System.currentTimeMillis()
        val entity = ChatMessageEntity(
            id = messageId,
            conversationId = conversationId,
            role = ChatRole.ASSISTANT.name,
            content = content,
            actionsJson = actionsJson,
            status = status.name,
            errorMessage = errorMessage,
            createdAt = snapshot?.createdAt ?: now
        )
        chatDao.upsertMessage(entity)

        if (status != ChatMessageStatus.STREAMING) {
            val conv = chatDao.getConversationById(conversationId)
            if (conv != null) {
                val previewSource = content.trim().ifBlank { errorMessage ?: conv.lastMessagePreview.orEmpty() }
                chatDao.upsertConversation(
                    conv.copy(
                        updatedAt = now,
                        lastMessagePreview = previewSource.take(120).ifBlank { conv.lastMessagePreview }
                    )
                )
            }
        }
    }

    suspend fun deleteMessage(messageId: String) {
        chatDao.deleteMessageById(messageId)
    }

    suspend fun deleteConversation(conversationId: String) {
        chatDao.deleteConversationById(conversationId)
    }

    suspend fun renameConversation(conversationId: String, newTitle: String): Boolean {
        val cleaned = newTitle.trim().replace(Regex("\\s+"), " ").take(60).trim()
        if (cleaned.isEmpty()) return false
        if (chatDao.getConversationById(conversationId) == null) return false
        chatDao.renameConversationManually(
            id = conversationId,
            title = cleaned,
            updatedAt = System.currentTimeMillis()
        )
        return true
    }

    suspend fun applyAutoGeneratedTitle(
        conversationId: String,
        generatedTitle: String,
        expectedCurrentTitle: String
    ): Boolean {
        val cleaned = generatedTitle.trim().replace(Regex("\\s+"), " ").take(60).trim()
        if (cleaned.isEmpty() || cleaned.equals("New conversation", ignoreCase = true)) {
            return false
        }
        return chatDao.updateAutoGeneratedTitleIfNotManual(
            id = conversationId,
            title = cleaned,
            expectedCurrentTitle = expectedCurrentTitle
        ) > 0
    }

    suspend fun togglePinned(conversationId: String) {
        val conv = chatDao.getConversationById(conversationId) ?: return
        chatDao.upsertConversation(conv.copy(isPinned = !conv.isPinned))
    }

    suspend fun isActionAlwaysAllowed(conversationId: String, actionType: String): Boolean =
        chatDao.countActionPermission(conversationId, actionType) > 0

    suspend fun grantActionPermission(conversationId: String, actionType: String) {
        if (chatDao.getConversationById(conversationId) == null) return
        chatDao.grantActionPermission(
            ChatActionPermissionEntity(conversationId, actionType, System.currentTimeMillis())
        )
    }

    suspend fun getGrantedActionTypes(conversationId: String): List<String> =
        chatDao.getGrantedActionTypes(conversationId)

    suspend fun updateMessageActionStatus(
        conversationId: String,
        messageId: String,
        actionId: String,
        status: com.laterbox.app.ai.ChatActionStatus,
        statusDetail: String? = null
    ) {
        val msg = chatDao.getMessagesSnapshot(conversationId).firstOrNull { it.id == messageId } ?: return
        val updatedJson = com.laterbox.app.ai.AssistantActionHandler.updateTrackedActionStatus(
            actionsJson = msg.actionsJson,
            actionId = actionId,
            status = status,
            statusDetail = statusDetail
        )
        if (updatedJson != msg.actionsJson) {
            chatDao.upsertMessage(msg.copy(actionsJson = updatedJson))
        }
    }

    /** Persists a completed assistant message (e.g. an action receipt) without a streaming phase. */
    suspend fun addAssistantMessage(conversationId: String, text: String): ChatMessageEntity {
        val placeholder = createAssistantPlaceholder(conversationId)
        updateAssistantMessage(placeholder.id, conversationId, text, ChatMessageStatus.COMPLETE)
        return placeholder.copy(content = text, status = ChatMessageStatus.COMPLETE.name)
    }

    companion object {
        fun deriveTitleFromPrompt(prompt: String): String {
            val collapsed = prompt.trim().replace(Regex("\\s+"), " ")
            if (collapsed.isBlank()) return "New conversation"
            return if (collapsed.length <= 44) {
                collapsed
            } else {
                collapsed.take(44).substringBeforeLast(' ', collapsed.take(44)).trimEnd(',', '.', ';', ':') + "…"
            }
        }
    }
}
