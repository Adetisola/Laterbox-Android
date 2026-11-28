package com.laterbox.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laterbox.app.ai.ActionCategory
import com.laterbox.app.ai.ActionExecutionOutcome
import com.laterbox.app.ai.AssistantActionCoordinatorHost
import com.laterbox.app.ai.AssistantActionHandler
import com.laterbox.app.ai.AssistantActionQueueCoordinator
import com.laterbox.app.ai.ChatActionStatus
import com.laterbox.app.ai.speech.ComposerSpeechUiState
import com.laterbox.app.ai.speech.SpeechTranscriptionCoordinator
import com.laterbox.app.media.domain.model.DownloadState
import com.laterbox.app.ai.ChatAction
import com.laterbox.app.ai.GeminiClient
import com.laterbox.app.ai.GeminiRole
import com.laterbox.app.ai.GeminiStreamEvent
import com.laterbox.app.ai.GeminiTurn
import com.laterbox.app.ai.LaterboxContextEngine
import com.laterbox.app.data.local.entity.ChatConversationEntity
import com.laterbox.app.data.local.entity.ChatMessageEntity
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.data.repository.ChatMessageStatus
import com.laterbox.app.data.repository.ChatRepository
import com.laterbox.app.data.repository.ChatRole
import com.laterbox.app.data.repository.CollectionRepository
import com.laterbox.app.data.repository.LinkRepository
import com.laterbox.app.media.domain.repository.MediaRepository
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.repository.MoviesRepository
import com.laterbox.app.reminders.ReminderScheduler
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

interface AssistantTtsAudioPlayer {
    fun play(wavBytes: ByteArray, onCompletion: () -> Unit, onError: () -> Unit)
    fun stop()
    fun release()
}

private class AndroidMediaPlayerTtsAudioPlayer(
    private val context: Context
) : AssistantTtsAudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var tempFile: File? = null

    override fun play(wavBytes: ByteArray, onCompletion: () -> Unit, onError: () -> Unit) {
        stop()
        try {
            val file = File(context.cacheDir, "laterbox_tts_play.wav")
            file.writeBytes(wavBytes)
            tempFile = file
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stop()
                    onCompletion()
                }
                setOnErrorListener { _, _, _ ->
                    stop()
                    onError()
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = player
        } catch (_: Throwable) {
            stop()
            onError()
        }
    }

    override fun stop() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { tempFile?.delete() }
        tempFile = null
    }

    override fun release() {
        stop()
    }
}

data class UiActionDispatch(
    val requestId: String,
    val messageId: String,
    val actionId: String,
    val action: ChatAction,
    val conversationId: String = ""
)

data class ChatUiState(
    val activeConversationId: String? = null,
    val activeConversationTitle: String = "Laterbox Assistant",
    val messages: List<ChatMessageEntity> = emptyList(),
    val recentConversations: List<ChatConversationEntity> = emptyList(),
    val assistantEnabled: Boolean = true,
    val aiDisclosureAccepted: Boolean = false,
    val audioConsentAccepted: Boolean = false,
    val composerText: String = "",
    val isStreaming: Boolean = false,
    val streamingAssistantMessageId: String? = null,
    val streamingPartialText: String = "",
    val pendingMutatingAction: ChatAction? = null,
    val statusNotice: String? = null,
    val manifestOutOfSync: Boolean = false,
    val ttsLoadingMessageId: String? = null,
    val ttsPlayingMessageId: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val laterboxContextEngine: LaterboxContextEngine,
    private val linkRepository: LinkRepository,
    private val mediaRepository: MediaRepository,
    private val speech: SpeechTranscriptionCoordinator? = null,
    private val moviesRepository: MoviesRepository? = null,
    private val collectionRepository: CollectionRepository? = null,
    private val reminderScheduler: ReminderScheduler? = null,
    private val backendAiClient: com.laterbox.app.ai.LaterboxBackendAiClient? = null,
    private val analyticsTracker: com.laterbox.app.analytics.LaterboxAnalyticsTracker? = null,
    private val applicationContext: Context? = null,
    private val ttsAudioPlayer: AssistantTtsAudioPlayer? = null,
    private val isAssistantRemotePaused: () -> Boolean = { false },
) : ViewModel() {

    private val _activeConversationId = MutableStateFlow<String?>(null)
    private val _composerText = MutableStateFlow("")
    private val _isStreaming = MutableStateFlow(false)
    private val _streamingAssistantMessageId = MutableStateFlow<String?>(null)
    private val _streamingPartialText = MutableStateFlow("")
    private val _statusNotice = MutableStateFlow<String?>(null)
    private val _manifestOutOfSync = MutableStateFlow(false)
    private val _pendingUiActionDispatch = MutableStateFlow<UiActionDispatch?>(null)
    val pendingUiActionDispatch: StateFlow<UiActionDispatch?> = _pendingUiActionDispatch.asStateFlow()

    private val pendingUiActionQueue = ArrayDeque<UiActionDispatch>()
    private val processedReadOnlyActionKeys = mutableSetOf<String>()
    private var streamingJob: Job? = null
    private var lastHandledInvocationKey: String? = null
    private var pendingConsentConversationId: String? = null

    private val actionQueueCoordinator = AssistantActionQueueCoordinator(
        host = object : AssistantActionCoordinatorHost {
            override fun activeConversationId(): String? = _activeConversationId.value

            override suspend fun validateMutatingTarget(action: ChatAction): String? =
                validateActionTarget(action)

            override suspend fun isActionAllowedWithoutPrompt(
                conversationId: String?,
                action: ChatAction
            ): Boolean {
                if (action.isIrreversibleDestructive) return false
                if (conversationId == null) return false
                if (chatRepository.isActionAlwaysAllowed(conversationId, action.permissionCategory.key)) {
                    return true
                }
                val permissionKey = action.permissionKey ?: return false
                return chatRepository.isActionAlwaysAllowed(conversationId, permissionKey)
            }

            override suspend fun grantAlwaysAllow(conversationId: String?, action: ChatAction) {
                if (action.isIrreversibleDestructive || conversationId == null) return
                chatRepository.grantActionPermission(conversationId, action.permissionCategory.key)
                val key = action.permissionKey
                if (key != null && key != action.permissionCategory.key) {
                    chatRepository.grantActionPermission(conversationId, key)
                }
            }

            override suspend fun executeMutatingAction(
                action: ChatAction,
                conversationId: String?,
                messageId: String?,
                actionId: String?
            ): ActionExecutionOutcome = executeMutatingActionInternal(action, conversationId, messageId, actionId)

            override suspend fun updateActionStatus(
                conversationId: String,
                messageId: String,
                actionId: String,
                status: ChatActionStatus,
                statusDetail: String?
            ) {
                chatRepository.updateMessageActionStatus(
                    conversationId = conversationId,
                    messageId = messageId,
                    actionId = actionId,
                    status = status,
                    statusDetail = statusDetail
                )
            }

            override suspend fun reportActionOutcome(
                conversationId: String?,
                action: ChatAction,
                status: String,
                detail: String,
                actionId: String?
            ) {
                this@ChatViewModel.reportActionOutcome(
                    conversationId = conversationId,
                    action = action,
                    status = status,
                    detail = detail,
                    actionId = actionId
                )
            }

            override suspend fun postReceipt(conversationId: String?, text: String) {
                this@ChatViewModel.postReceipt(conversationId, text)
            }
        }
    )

    val recentConversations: StateFlow<List<ChatConversationEntity>> =
        chatRepository.observeRecentConversations(30).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val activeMessagesFlow = _activeConversationId.flatMapLatest { convId ->
        if (convId.isNullOrBlank()) flowOf(emptyList()) else chatRepository.observeMessages(convId)
    }

    private val activeConversationFlow = _activeConversationId.flatMapLatest { convId ->
        if (convId.isNullOrBlank()) flowOf(null) else chatRepository.observeConversation(convId)
    }

    private val streamingTuple = combine(
        _isStreaming,
        _streamingAssistantMessageId,
        _streamingPartialText,
        _composerText
    ) { isStreaming, msgId, partial, composer ->
        Quad(isStreaming, msgId, partial, composer)
    }

    private val _ttsLoadingMessageId = MutableStateFlow<String?>(null)
    private val _ttsPlayingMessageId = MutableStateFlow<String?>(null)
    private val _ttsErrorEvents = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val ttsErrorEvents: SharedFlow<String> = _ttsErrorEvents.asSharedFlow()

    private val ttsSessionCache = mutableMapOf<String, ByteArray>()
    private var ttsJob: Job? = null
    private val activeTtsPlayer: AssistantTtsAudioPlayer? by lazy {
        ttsAudioPlayer ?: applicationContext?.let { AndroidMediaPlayerTtsAudioPlayer(it) }
    }

    private val ttsTuple = combine(_ttsLoadingMessageId, _ttsPlayingMessageId) { loadingId, playingId ->
        loadingId to playingId
    }

    private val auxiliaryTuple = combine(
        actionQueueCoordinator.pendingApproval,
        _statusNotice,
        _manifestOutOfSync
    ) { pendingAction, notice, manifestOutOfSync ->
        AuxState(pendingAction, notice, manifestOutOfSync)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        _activeConversationId,
        activeConversationFlow,
        activeMessagesFlow,
        recentConversations,
        userPreferencesRepository.userPreferences,
        streamingTuple,
        auxiliaryTuple,
        ttsTuple
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val activeId = args[0] as String?
        val activeConv = args[1] as ChatConversationEntity?
        @Suppress("UNCHECKED_CAST")
        val messages = args[2] as List<ChatMessageEntity>
        @Suppress("UNCHECKED_CAST")
        val recent = args[3] as List<ChatConversationEntity>
        val prefs = args[4] as com.laterbox.app.data.preferences.UserPreferences
        val stream = args[5] as Quad
        val aux = args[6] as AuxState
        @Suppress("UNCHECKED_CAST")
        val ttsState = args[7] as Pair<String?, String?>

        ChatUiState(
            activeConversationId = activeId,
            activeConversationTitle = activeConv?.title ?: "Laterbox Assistant",
            messages = messages,
            recentConversations = recent,
            assistantEnabled = prefs.assistantEnabled,
            aiDisclosureAccepted = prefs.assistantAiDisclosureAccepted,
            audioConsentAccepted = prefs.audioTranscriptionConsentAccepted,
            composerText = stream.composer,
            isStreaming = stream.isStreaming,
            streamingAssistantMessageId = stream.msgId,
            streamingPartialText = stream.partial,
            pendingMutatingAction = aux.pendingAction,
            statusNotice = aux.notice,
            manifestOutOfSync = aux.manifestOutOfSync,
            ttsLoadingMessageId = ttsState.first,
            ttsPlayingMessageId = ttsState.second
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatUiState()
    )

    fun toggleReadAloud(messageId: String, text: String) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return
        if (_ttsPlayingMessageId.value == messageId || _ttsLoadingMessageId.value == messageId) {
            stopReadAloud()
            return
        }
        stopReadAloud()

        val cachedBytes = ttsSessionCache[messageId]
        if (cachedBytes != null) {
            playTtsBytes(messageId, cachedBytes)
            return
        }

        val client = backendAiClient
        if (client == null) {
            _ttsErrorEvents.tryEmit(TTS_UNAVAILABLE_TOAST)
            return
        }

        _ttsLoadingMessageId.value = messageId
        ttsJob = viewModelScope.launch {
            val result = client.fetchTtsAudio(cleanText)
            if (_ttsLoadingMessageId.value == messageId) {
                _ttsLoadingMessageId.value = null
            }
            val payload = result.getOrNull()
            if (payload == null) {
                _ttsErrorEvents.tryEmit(TTS_UNAVAILABLE_TOAST)
                return@launch
            }
            ttsSessionCache[messageId] = payload.audioBytes
            playTtsBytes(messageId, payload.audioBytes)
        }
    }

    fun stopReadAloud() {
        ttsJob?.cancel()
        ttsJob = null
        _ttsLoadingMessageId.value = null
        _ttsPlayingMessageId.value = null
        runCatching { activeTtsPlayer?.stop() }
    }

    private fun playTtsBytes(messageId: String, audioBytes: ByteArray) {
        val player = activeTtsPlayer
        if (player == null) {
            _ttsErrorEvents.tryEmit(TTS_UNAVAILABLE_TOAST)
            return
        }
        _ttsPlayingMessageId.value = messageId
        player.play(
            wavBytes = audioBytes,
            onCompletion = {
                if (_ttsPlayingMessageId.value == messageId) {
                    _ttsPlayingMessageId.value = null
                }
            },
            onError = {
                if (_ttsPlayingMessageId.value == messageId) {
                    _ttsPlayingMessageId.value = null
                }
                _ttsErrorEvents.tryEmit(TTS_UNAVAILABLE_TOAST)
            }
        )
    }

    fun onComposerTextChange(text: String) {
        _composerText.value = text
    }

    fun clearStatusNotice() {
        _statusNotice.value = null
    }

    fun openChat(
        conversationId: String?,
        initialPrompt: String?,
        invocationKey: String? = null
    ) {
        val key = invocationKey ?: "${conversationId.orEmpty()}|${initialPrompt.orEmpty()}"
        if (lastHandledInvocationKey == key && key.isNotBlank()) {
            return
        }
        lastHandledInvocationKey = key

        val trimmedPrompt = initialPrompt?.trim().orEmpty()
        val targetConvId = conversationId?.trim()?.takeIf { it.isNotBlank() }

        if (trimmedPrompt.isBlank()) {
            if (targetConvId != null) {
                selectConversation(targetConvId)
            } else if (_activeConversationId.value == null) {
                startNewChat()
            }
            return
        }

        viewModelScope.launch {
            if (_activeConversationId.value != targetConvId) {
                cancelStreaming()
                clearPendingActionPipelineState(persistAbandonedMutating = true)
            }
            val convId = targetConvId ?: chatRepository.createConversation(trimmedPrompt)
            _activeConversationId.value = convId
            submitUserPromptToConversation(convId, trimmedPrompt)
        }
    }

    private fun clearPendingActionPipelineState(persistAbandonedMutating: Boolean = true) {
        pendingUiActionQueue.clear()
        _pendingUiActionDispatch.value = null
        if (persistAbandonedMutating) {
            val cancelled = actionQueueCoordinator.clearAndDrainPending()
            if (cancelled.isNotEmpty()) {
                viewModelScope.launch(NonCancellable) {
                    actionQueueCoordinator.markItemsDenied(cancelled)
                }
            }
        } else {
            actionQueueCoordinator.clearWithoutPersisting()
        }
    }

    private fun enqueueUiAction(dispatch: UiActionDispatch) {
        if (_activeConversationId.value != dispatch.conversationId) return
        if (_pendingUiActionDispatch.value == null) {
            _pendingUiActionDispatch.value = dispatch
        } else {
            pendingUiActionQueue.addLast(dispatch)
        }
    }

    private fun advanceUiActionQueue() {
        val activeConv = _activeConversationId.value
        while (pendingUiActionQueue.isNotEmpty()) {
            val next = pendingUiActionQueue.removeFirst()
            if (next.conversationId == activeConv) {
                _pendingUiActionDispatch.value = next
                return
            }
        }
        _pendingUiActionDispatch.value = null
    }

    fun startNewChat() {
        cancelStreaming()
        clearPendingActionPipelineState(persistAbandonedMutating = true)
        pendingConsentConversationId = null
        _activeConversationId.value = null
        _statusNotice.value = null
    }

    fun selectConversation(conversationId: String) {
        if (_activeConversationId.value != conversationId) {
            cancelStreaming()
            clearPendingActionPipelineState(persistAbandonedMutating = true)
            pendingConsentConversationId = null
            _activeConversationId.value = conversationId
            _statusNotice.value = null
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            if (_activeConversationId.value == conversationId) {
                cancelStreaming()
                clearPendingActionPipelineState(persistAbandonedMutating = false)
                pendingConsentConversationId = null
                _activeConversationId.value = null
            }
            chatRepository.deleteConversation(conversationId)
        }
    }

    fun renameConversation(conversationId: String, newTitle: String) {
        viewModelScope.launch {
            chatRepository.renameConversation(conversationId, newTitle)
        }
    }

    private fun launchContextualTitleGeneration(
        conversationId: String,
        firstUserPrompt: String,
        firstAssistantReply: String
    ) {
        viewModelScope.launch {
            val conv = chatRepository.getConversation(conversationId) ?: return@launch
            if (conv.isTitleManual) return@launch
            val messages = chatRepository.getMessagesSnapshot(conversationId)
            val userTurns = messages.count { it.role == ChatRole.USER.name }
            if (userTurns > 1) return@launch

            val generatedTitle = backendAiClient?.let { client ->
                runCatching {
                    client.generateConversationTitle(
                        firstUserMessage = firstUserPrompt,
                        firstAssistantReply = firstAssistantReply,
                        currentTitle = conv.title
                    )
                }.getOrNull()?.title
            } ?: ChatRepository.extractiveFallbackTitle(firstUserPrompt)

            chatRepository.applyAutoGeneratedTitle(
                conversationId = conversationId,
                generatedTitle = generatedTitle,
                expectedCurrentTitle = conv.title
            )
        }
    }

    fun acceptAiDisclosure() {
        viewModelScope.launch {
            userPreferencesRepository.setAssistantAiDisclosureAccepted(true)
            val pendingConvId = pendingConsentConversationId
            pendingConsentConversationId = null
            if (pendingConvId != null && _activeConversationId.value == pendingConvId) {
                val prefs = userPreferencesRepository.userPreferencesFlow.first()
                if (prefs.assistantEnabled) {
                    val lastUser = chatRepository.getMessagesSnapshot(pendingConvId)
                        .lastOrNull { it.role == ChatRole.USER.name }
                    if (lastUser != null) {
                        generateAssistantReply(pendingConvId, lastUser.content)
                    }
                }
            }
        }
    }

    fun sendCurrentMessage() {
        val prompt = _composerText.value.trim()
        if (prompt.isBlank() || _isStreaming.value) return
        _composerText.value = ""
        sendMessage(prompt)
    }

    fun sendMessage(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank() || _isStreaming.value) return

        viewModelScope.launch {
            val convId = _activeConversationId.value ?: chatRepository.createConversation(trimmed).also {
                _activeConversationId.value = it
            }
            submitUserPromptToConversation(convId, trimmed)
        }
    }

    private suspend fun submitUserPromptToConversation(conversationId: String, trimmedPrompt: String) {
        // Guarantee user prompt is persisted locally BEFORE checking consent / network / remote calls
        chatRepository.addUserMessage(conversationId, trimmedPrompt)

        val prefs = userPreferencesRepository.userPreferencesFlow.first()
        if (!prefs.assistantEnabled) {
            _statusNotice.value = "AI Assistant is turned off in Settings → Reminders & AI."
            return
        }
        if (isAssistantRemotePaused()) {
            _statusNotice.value = "Laterbox Assistant is temporarily paused during maintenance. Your local links and offline library remain available."
            return
        }
        if (!prefs.assistantAiDisclosureAccepted) {
            pendingConsentConversationId = conversationId
            return
        }
        pendingConsentConversationId = null
        analyticsTracker?.trackFeatureUsed("assistant_chat")
        generateAssistantReply(conversationId, trimmedPrompt)
    }

    fun retryMessage(assistantMessageId: String) {
        if (_isStreaming.value) return
        val convId = _activeConversationId.value ?: return

        viewModelScope.launch {
            val prefs = userPreferencesRepository.userPreferencesFlow.first()
            if (!prefs.assistantEnabled) {
                _statusNotice.value = "AI Assistant is turned off in Settings → Reminders & AI."
                return@launch
            }
            if (isAssistantRemotePaused()) {
                _statusNotice.value = "Laterbox Assistant is temporarily paused during maintenance. Your local links and offline library remain available."
                return@launch
            }
            if (!prefs.assistantAiDisclosureAccepted) {
                pendingConsentConversationId = convId
                return@launch
            }
            val snapshot = chatRepository.getMessagesSnapshot(convId)
            val targetIndex = snapshot.indexOfFirst { it.id == assistantMessageId }
            if (targetIndex < 0) return@launch
            val targetMessage = snapshot[targetIndex]
            if (targetMessage.role != ChatRole.ASSISTANT.name) return@launch
            if (hasBlockingMutatingActionForRetry(targetMessage.actionsJson)) {
                _statusNotice.value = "Cannot retry a response that already applied in-app changes."
                return@launch
            }
            val promptToRetry = snapshot.subList(0, targetIndex)
                .lastOrNull { it.role == ChatRole.USER.name }
                ?.content
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return@launch

            val latestAssistantIndex = snapshot.indexOfLast { it.role == ChatRole.ASSISTANT.name }
            if (targetIndex == latestAssistantIndex) {
                chatRepository.deleteMessage(assistantMessageId)
            } else {
                chatRepository.addUserMessage(convId, promptToRetry)
            }
            generateAssistantReply(convId, promptToRetry)
        }
    }

    fun cancelStreaming() {
        streamingJob?.cancel()
        streamingJob = null
    }

    fun reportUiActionOutcome(
        requestId: String?,
        messageId: String,
        actionId: String,
        action: ChatAction,
        executed: Boolean,
        detail: String
    ) {
        val currentDispatch = _pendingUiActionDispatch.value
        val matchedDispatch = if (requestId != null && currentDispatch?.requestId == requestId) {
            currentDispatch
        } else {
            null
        }
        if (matchedDispatch != null) {
            advanceUiActionQueue()
        }
        val convId = matchedDispatch?.conversationId?.takeIf { it.isNotBlank() }
            ?: _activeConversationId.value
        viewModelScope.launch {
            if (convId != null) {
                chatRepository.updateMessageActionStatus(
                    conversationId = convId,
                    messageId = messageId,
                    actionId = actionId,
                    status = if (executed) com.laterbox.app.ai.ChatActionStatus.EXECUTED else com.laterbox.app.ai.ChatActionStatus.FAILED,
                    statusDetail = detail.takeIf { it.isNotBlank() }
                )
            }
            if (!executed && detail.isNotBlank() && _activeConversationId.value == convId) {
                _statusNotice.value = detail
            }
            reportActionOutcome(
                conversationId = convId,
                action = action,
                status = if (executed) "executed" else "failed",
                detail = detail,
                actionId = actionId,
                postFollowUpIfMissing = !executed
            )
        }
    }

    /**
     * Entry point for state-changing assistant actions. Validates the target first, checks
     * category grants (while ALWAYS confirming irreversible destructive actions), and either
     * executes immediately or queues/surfaces the expandable approval panel via [actionQueueCoordinator].
     */
    fun requestMutatingActionConfirmation(
        action: ChatAction,
        messageId: String? = null,
        actionId: String? = null
    ) {
        val convId = _activeConversationId.value
        viewModelScope.launch {
            actionQueueCoordinator.enqueueSingle(
                action = action,
                conversationId = convId,
                messageId = messageId,
                actionId = actionId
            )
        }
    }

    /** "Don't allow": nothing runs; the conversation records that (and aborts any dependent batch actions). */
    fun dismissMutatingAction() {
        val active = actionQueueCoordinator.takeActiveItem() ?: return
        viewModelScope.launch {
            actionQueueCoordinator.denyItem(active)
        }
    }

    /** "Allow once": executes only this instance. */
    fun confirmMutatingAction() {
        val active = actionQueueCoordinator.takeActiveItem() ?: return
        viewModelScope.launch {
            actionQueueCoordinator.confirmItem(active)
        }
    }

    /** "Always allow [Category]": persists category grant in DataStore + conversation DB (blocked for destructive actions). */
    fun alwaysAllowMutatingActionInConversation() {
        val active = actionQueueCoordinator.takeActiveItem() ?: return
        viewModelScope.launch {
            actionQueueCoordinator.alwaysAllowItem(active)
        }
    }

    private fun reportActionOutcome(
        conversationId: String?,
        action: ChatAction,
        status: String,
        detail: String,
        actionId: String? = null,
        postFollowUpIfMissing: Boolean = false
    ) {
        if (status == "executed") {
            analyticsTracker?.trackFeatureUsed("assistant_action")
        }
        val client = backendAiClient
        val convId = conversationId ?: return
        viewModelScope.launch {
            val ack = if (client != null) {
                runCatching {
                    client.reportActionResult(
                        conversationId = convId,
                        actionType = action.actionType,
                        category = action.permissionCategory.key,
                        status = status,
                        detail = detail,
                        actionId = actionId
                    )
                }.getOrNull()
            } else {
                null
            }
            if (postFollowUpIfMissing && status == "failed") {
                val followUp = ack?.followUpMessage?.takeIf { it.isNotBlank() }
                    ?: detail.takeIf { it.isNotBlank() }?.let {
                        "I couldn't ${action.actionName.lowercase()}: $it"
                    }
                if (!followUp.isNullOrBlank()) {
                    postReceipt(convId, followUp)
                }
            }
        }
    }

    private suspend fun resolveMovieSummary(providerId: String, titleId: String): MovieTitleSummary? {
        val repo = moviesRepository ?: return null
        val targetComposite = "$providerId:$titleId"
        repo.observeWatchlist().firstOrNull().orEmpty().firstOrNull {
            (it.providerId == providerId && it.id == titleId) || it.compositeId == targetComposite
        }?.let { return it }

        repo.observeRecentlyViewed(30).firstOrNull().orEmpty().firstOrNull {
            (it.providerId == providerId && it.id == titleId) || it.compositeId == targetComposite
        }?.let { return it }

        repo.observeViewingHistory(50).firstOrNull().orEmpty().firstOrNull {
            (it.providerId == providerId && it.titleId == titleId) || it.compositeId == targetComposite
        }?.let { h ->
            return MovieTitleSummary(
                id = h.titleId,
                providerId = h.providerId,
                kind = h.kind,
                title = h.title,
                posterUrl = h.posterUrl,
                backdropUrl = h.backdropUrl,
                canonicalId = h.canonicalId
            )
        }

        repo.observeActiveViewingReminders().firstOrNull().orEmpty().firstOrNull {
            (it.providerId == providerId && it.titleId == titleId) || it.compositeId == targetComposite
        }?.let { r ->
            return MovieTitleSummary(
                id = r.titleId,
                providerId = r.providerId,
                kind = r.kind,
                title = r.title,
                posterUrl = r.posterUrl,
                canonicalId = r.canonicalId
            )
        }

        return repo.getDetails(providerId, titleId).getOrNull()?.summary
    }

    private suspend fun validateActionTarget(action: ChatAction): String? = when (action) {
        is ChatAction.RetryInspection -> validateLink(action.linkId)
        is ChatAction.RefreshMetadata -> validateLink(action.linkId)
        is ChatAction.RetryDownload -> {
            val task = mediaRepository.getTaskById(action.taskId).first()
            when {
                task == null -> "that download no longer exists."
                task.status != DownloadState.FAILED &&
                    task.status != DownloadState.CANCELLED &&
                    task.status != DownloadState.PAUSED -> "that download isn't stopped or failed."
                else -> null
            }
        }
        is ChatAction.ToggleMovieWatchlist -> when {
            moviesRepository == null -> "Movies & TV library isn't available."
            action.providerId.isBlank() || action.titleId.isBlank() -> "that title reference is incomplete."
            resolveMovieSummary(action.providerId, action.titleId) == null -> "that title could not be found."
            else -> null
        }
        is ChatAction.AddMovieToCollection -> when {
            moviesRepository == null || collectionRepository == null -> "collections aren't available here."
            action.providerId.isBlank() || action.titleId.isBlank() -> "that title reference is incomplete."
            action.collectionId == null && action.collectionName.isNullOrBlank() -> "no target collection was specified."
            action.collectionId != null && action.collectionName.isNullOrBlank() &&
                collectionRepository.getCollectionById(action.collectionId).first() == null ->
                "that collection no longer exists."
            resolveMovieSummary(action.providerId, action.titleId) == null -> "that title could not be found."
            else -> null
        }
        is ChatAction.MarkMovieWatched -> when {
            moviesRepository == null -> "Movies & TV library isn't available."
            action.providerId.isBlank() || action.titleId.isBlank() -> "that title reference is incomplete."
            action.watched && resolveMovieSummary(action.providerId, action.titleId) == null ->
                "that title could not be found."
            else -> null
        }
        is ChatAction.ScheduleMovieReminder -> when {
            moviesRepository == null -> "Movies & TV library isn't available."
            action.providerId.isBlank() || action.titleId.isBlank() -> "that title reference is incomplete."
            action.triggerAtMillis <= System.currentTimeMillis() -> "that reminder time is already in the past."
            resolveMovieSummary(action.providerId, action.titleId) == null -> "that title could not be found."
            else -> null
        }
        is ChatAction.MuteSeriesReminders -> when {
            moviesRepository == null -> "Movies & TV library isn't available."
            action.providerId.isBlank() || action.titleId.isBlank() -> "that title reference is incomplete."
            resolveMovieSummary(action.providerId, action.titleId) == null -> "that title could not be found."
            else -> null
        }
        is ChatAction.RenameConversation -> when {
            action.title.isBlank() -> "no new title was provided."
            else -> null
        }
        is ChatAction.DeleteConversation -> null
        is ChatAction.DeleteDownload -> when {
            action.taskId.isNullOrBlank() && action.assetId.isNullOrBlank() -> "that download reference is incomplete."
            else -> null
        }
        else -> validateReadOnlyActionTarget(action)
    }

    private suspend fun validateLink(linkId: Long): String? {
        val link = linkRepository.getLinkById(linkId).first()
        return when {
            link == null -> "that saved link no longer exists."
            link.originalUrl.isBlank() -> "that saved link has no address."
            else -> null
        }
    }

    private suspend fun executeMutatingActionInternal(
        action: ChatAction,
        conversationId: String?,
        messageId: String? = null,
        actionId: String? = null
    ): ActionExecutionOutcome {
        if (action.category == ActionCategory.READ_ONLY) {
            if (conversationId != null && messageId != null && actionId != null) {
                chatRepository.updateMessageActionStatus(
                    conversationId = conversationId,
                    messageId = messageId,
                    actionId = actionId,
                    status = ChatActionStatus.AUTO_EXECUTING
                )
            }
            enqueueUiAction(
                UiActionDispatch(
                    requestId = UUID.randomUUID().toString(),
                    messageId = messageId.orEmpty(),
                    actionId = actionId.orEmpty(),
                    action = action,
                    conversationId = conversationId.orEmpty()
                )
            )
            return ActionExecutionOutcome(
                succeeded = true,
                skipStandardStatusAndReceipt = true
            )
        }
        var actionSucceeded = true
        val receipt: String = when (action) {
            is ChatAction.RetryInspection -> {
                val link = linkRepository.getLinkById(action.linkId).first()
                if (link == null) {
                    actionSucceeded = false
                    "That saved link no longer exists. Nothing was changed."
                } else {
                    mediaRepository.startInspection(
                        linkId = link.id,
                        url = link.originalUrl,
                        previewImageUrl = link.previewImageUrl,
                        forceRefresh = true
                    )
                    "Started a fresh media inspection for \"${link.title}\". Check the item for the result."
                }
            }
            is ChatAction.RefreshMetadata -> {
                val ok = linkRepository.fetchMetadataNow(action.linkId, isManualRetry = true)
                actionSucceeded = ok
                if (ok) "Refreshed the details for \"${action.title}\"."
                else "I tried to refresh \"${action.title}\" but it didn't complete (likely a network problem). It will retry later."
            }
            is ChatAction.RetryDownload -> {
                mediaRepository.retryDownload(action.taskId)
                val after = mediaRepository.getTaskById(action.taskId).first()
                val ok = after != null && after.status != DownloadState.FAILED && after.status != DownloadState.CANCELLED
                actionSucceeded = ok
                if (ok) {
                    "Re-queued the download \"${action.title}\"."
                } else {
                    "I tried to re-queue \"${action.title}\" but it is still stopped."
                }
            }
            is ChatAction.ToggleMovieWatchlist -> {
                val repo = moviesRepository
                    ?: return ActionExecutionOutcome(succeeded = false, errorDetail = "Movies & TV library isn't available.")
                val summary = resolveMovieSummary(action.providerId, action.titleId)
                if (summary == null) {
                    actionSucceeded = false
                    "I couldn't find \"${action.title}\". Nothing was changed."
                } else {
                    val currentlyIn = repo.observeIsInWatchlist(summary.compositeId).first()
                    if (currentlyIn == action.add) {
                        if (action.add) "\"${summary.title}\" is already in your Movies & TV watchlist."
                        else "\"${summary.title}\" is already removed from your Movies & TV watchlist."
                    } else {
                        val nowIn = repo.toggleWatchlist(summary)
                        if (nowIn) "Added \"${summary.title}\" to your Movies & TV watchlist."
                        else "Removed \"${summary.title}\" from your Movies & TV watchlist."
                    }
                }
            }
            is ChatAction.AddMovieToCollection -> {
                val repo = moviesRepository
                    ?: return ActionExecutionOutcome(succeeded = false, errorDetail = "Movies & TV library isn't available.")
                val colRepo = collectionRepository
                    ?: return ActionExecutionOutcome(succeeded = false, errorDetail = "Collections aren't available.")
                val summary = resolveMovieSummary(action.providerId, action.titleId)
                if (summary == null) {
                    actionSucceeded = false
                    "I couldn't find \"${action.title}\". Nothing was changed."
                } else {
                    val existingById = action.collectionId?.let { colRepo.getCollectionById(it).first() }
                    val trimmedName = action.collectionName?.trim()?.takeIf { it.isNotBlank() }
                    val existingByName = if (existingById == null && trimmedName != null) {
                        colRepo.collectionsWithCounts.firstOrNull().orEmpty()
                            .firstOrNull { it.collection.name.equals(trimmedName, ignoreCase = true) }
                            ?.collection
                    } else null
                    val targetColId = existingById?.id
                        ?: existingByName?.id
                        ?: trimmedName?.let { colRepo.createCollection(it) }
                    if (targetColId == null) {
                        actionSucceeded = false
                        "I couldn't resolve the target collection. Nothing was changed."
                    } else {
                        val targetColName = existingById?.name ?: existingByName?.name ?: trimmedName ?: "collection"
                        repo.addMediaToCollection(summary, targetColId)
                        "Added \"${summary.title}\" to collection \"$targetColName\"."
                    }
                }
            }
            is ChatAction.MarkMovieWatched -> {
                val repo = moviesRepository
                    ?: return ActionExecutionOutcome(succeeded = false, errorDetail = "Movies & TV library isn't available.")
                val epSuffix = if (action.seasonNumber != null && action.episodeNumber != null) {
                    " (S${action.seasonNumber}E${action.episodeNumber})"
                } else ""
                if (action.watched) {
                    val summary = resolveMovieSummary(action.providerId, action.titleId)
                    if (summary == null) {
                        actionSucceeded = false
                        "I couldn't find \"${action.title}\". Nothing was changed."
                    } else {
                        val episodeLabel = if (action.seasonNumber != null && action.episodeNumber != null) {
                            "S${action.seasonNumber}E${action.episodeNumber}"
                        } else null
                        repo.markWatched(
                            item = summary,
                            seasonNumber = action.seasonNumber,
                            episodeNumber = action.episodeNumber,
                            episodeLabel = episodeLabel
                        )
                        "Marked \"${summary.title}\"$epSuffix as watched in your viewing history."
                    }
                } else {
                    repo.markUnwatched(
                        providerId = action.providerId,
                        titleId = action.titleId,
                        seasonNumber = action.seasonNumber,
                        episodeNumber = action.episodeNumber
                    )
                    "Cleared watched status for \"${action.title}\"$epSuffix."
                }
            }
            is ChatAction.ScheduleMovieReminder -> {
                val repo = moviesRepository
                    ?: return ActionExecutionOutcome(succeeded = false, errorDetail = "Movies & TV library isn't available.")
                val summary = resolveMovieSummary(action.providerId, action.titleId)
                if (summary == null) {
                    actionSucceeded = false
                    "I couldn't find \"${action.title}\". Nothing was changed."
                } else {
                    repo.scheduleViewingReminder(
                        item = summary,
                        triggerAtMillis = action.triggerAtMillis,
                        note = action.note
                    )
                    reminderScheduler?.scheduleMovieViewingReminder(
                        mediaKey = summary.stableMediaKey,
                        providerId = summary.providerId,
                        titleId = summary.id,
                        title = summary.title,
                        triggerAtMillis = action.triggerAtMillis,
                        note = action.note
                    )
                    val formattedTime = java.text.SimpleDateFormat("MMM d 'at' h:mm a", java.util.Locale.US)
                        .format(java.util.Date(action.triggerAtMillis))
                    "Scheduled a viewing reminder for \"${summary.title}\" on $formattedTime."
                }
            }
            is ChatAction.MuteSeriesReminders -> {
                val repo = moviesRepository
                    ?: return ActionExecutionOutcome(succeeded = false, errorDetail = "Movies & TV library isn't available.")
                val summary = resolveMovieSummary(action.providerId, action.titleId)
                if (summary == null) {
                    actionSucceeded = false
                    "I couldn't find \"${action.title}\". Nothing was changed."
                } else {
                    repo.setSeriesRemindersMuted(summary.providerId, summary.id, action.muted)
                    if (action.muted) "Muted proactive Smart Reminders for \"${summary.title}\"."
                    else "Unmuted proactive Smart Reminders for \"${summary.title}\"."
                }
            }
            is ChatAction.RenameConversation -> {
                val targetId = action.conversationId ?: conversationId
                if (targetId == null) {
                    actionSucceeded = false
                    "No active conversation to rename."
                } else {
                    val ok = chatRepository.renameConversation(targetId, action.title)
                    actionSucceeded = ok
                    if (ok) "Renamed conversation to \"${action.title}\"."
                    else "Unable to rename this conversation."
                }
            }
            is ChatAction.DeleteConversation -> {
                val targetId = action.conversationId ?: conversationId
                if (targetId == null) {
                    actionSucceeded = false
                    "No active conversation to delete."
                } else {
                    if (messageId != null && actionId != null) {
                        chatRepository.updateMessageActionStatus(
                            targetId,
                            messageId,
                            actionId,
                            ChatActionStatus.EXECUTED,
                            "Deleted conversation"
                        )
                    }
                    reportActionOutcome(targetId, action, "executed", "Deleted conversation", actionId)
                    deleteConversation(targetId)
                    _statusNotice.value = "Deleted conversation \"${action.title}\"."
                    return ActionExecutionOutcome(
                        succeeded = true,
                        receipt = "Deleted conversation \"${action.title}\".",
                        skipStandardStatusAndReceipt = true
                    )
                }
            }
            is ChatAction.DeleteDownload -> {
                if (!action.assetId.isNullOrBlank()) {
                    mediaRepository.deleteAsset(action.assetId)
                }
                if (!action.taskId.isNullOrBlank()) {
                    mediaRepository.cancelDownload(action.taskId)
                }
                "Removed \"${action.title}\" from downloads."
            }
            else -> return ActionExecutionOutcome(succeeded = false, errorDetail = "Unsupported action")
        }
        return ActionExecutionOutcome(
            succeeded = actionSucceeded,
            receipt = receipt
        )
    }

    private suspend fun postReceipt(conversationId: String?, text: String) {
        if (_activeConversationId.value == conversationId) {
            _statusNotice.value = text
        }
        if (conversationId != null && chatRepository.getConversation(conversationId) != null) {
            chatRepository.addAssistantMessage(conversationId, text)
        }
    }

    // ---- Voice dictation (shared by the Inbox and Chat composers) ----

    val speechState: StateFlow<ComposerSpeechUiState> =
        speech?.state ?: MutableStateFlow(ComposerSpeechUiState())

    fun startDictation(): Boolean = speech?.startRecording() ?: false

    fun startDictation(onTranscript: (String) -> Unit) {
        speech?.startDictation(onTranscript)
    }

    fun startDictationIntoChatDraft() {
        speech?.startDictation { text ->
            val current = _composerText.value
            _composerText.value = if (current.isBlank()) text.trim() else current.trimEnd() + " " + text.trim()
        }
    }

    fun cancelDictation() { speech?.cancel() }

    fun clearDictationError() { speech?.clearError() }

    /** Stops recording and delivers the transcript; never auto-sends. */
    fun stopDictation() {
        speech?.stopAndTranscribe()
    }

    fun stopDictation(onTranscript: (String) -> Unit) {
        speech?.stopAndTranscribe(onTranscript)
    }

    fun stopDictationIntoChatDraft() {
        stopDictation { text ->
            val current = _composerText.value
            _composerText.value = if (current.isBlank()) text.trim() else current.trimEnd() + " " + text.trim()
        }
    }

    fun sendDictatedChatPrompt() {
        stopDictation { text ->
            val cleanTranscript = text.trim()
            val current = _composerText.value
            val combined = when {
                current.isBlank() -> cleanTranscript
                cleanTranscript.isBlank() -> current.trim()
                else -> current.trimEnd() + " " + cleanTranscript
            }.trim()
            if (combined.isNotBlank()) {
                _composerText.value = ""
                sendMessage(combined)
            } else if (speechState.value.message.isNullOrBlank()) {
                _statusNotice.value = "Couldn't hear anything to send."
            }
        }
    }

    fun acceptAudioConsent() {
        viewModelScope.launch {
            userPreferencesRepository.setAudioTranscriptionConsentAccepted(true)
        }
    }

    override fun onCleared() {
        speech?.cancel()
        runCatching { activeTtsPlayer?.release() }
        super.onCleared()
    }

    private fun generateAssistantReply(conversationId: String, latestPrompt: String) {
        streamingJob?.cancel()
        streamingJob = viewModelScope.launch {
            val placeholder = chatRepository.createAssistantPlaceholder(conversationId)
            _isStreaming.value = true
            _streamingAssistantMessageId.value = placeholder.id
            _streamingPartialText.value = ""

            val requestStartedMs = System.currentTimeMillis()
            var firstTokenRecorded = false
            val accumulated = StringBuilder()
            var resolvedKey: String? = null
            var receivedTerminal = false

            try {
                val prefs = userPreferencesRepository.userPreferencesFlow.first()
                resolvedKey = GeminiClient.resolveApiKey(prefs.geminiApiKey.ifBlank { null })
                val builtContext = laterboxContextEngine.buildContextForPrompt(latestPrompt)

                val snapshot = chatRepository.getMessagesSnapshot(conversationId)
                    .filter { it.id != placeholder.id && it.content.isNotBlank() }
                val turns = buildTurnsFromSnapshot(snapshot)
                val recentActionReceipts = extractRecentActionReceipts(snapshot)

                var fallbackToDirectGemini = backendAiClient == null
                val proposedActionsMap = linkedMapOf<String, ChatAction>()

                if (backendAiClient != null) {
                    var backendNotConfigured = false
                    backendAiClient.streamChat(
                        conversationId = conversationId,
                        turns = turns,
                        capabilitySnapshot = builtContext.capabilitySnapshot,
                        runtimeContextBlock = builtContext.contextBlock,
                        actionSpecBlock = com.laterbox.app.ai.LaterboxKnowledgeBase.ALLOWED_ACTION_SPEC,
                        grantedCategories = emptySet(),
                        enableWebSearch = true,
                        recentActionResults = recentActionReceipts
                    ).collect { event ->
                        when (event) {
                            is com.laterbox.app.ai.BackendAiStreamEvent.Started -> {
                                _manifestOutOfSync.value = !event.manifestSynced
                                if (!event.manifestSynced) {
                                    viewModelScope.launch {
                                        val manifest = runCatching { backendAiClient.fetchCapabilityManifest() }.getOrNull()
                                        if (manifest != null) {
                                            _manifestOutOfSync.value = !manifest.clientInSync
                                        }
                                    }
                                }
                            }
                            is com.laterbox.app.ai.BackendAiStreamEvent.TextDelta -> {
                                if (!firstTokenRecorded && event.chunk.isNotEmpty()) {
                                    firstTokenRecorded = true
                                    analyticsTracker?.trackPerformanceMetric(
                                        metricName = "ai_first_token_ms",
                                        metricValueMs = (System.currentTimeMillis() - requestStartedMs)
                                            .coerceIn(0L, 600_000L)
                                            .toInt(),
                                        screenId = "chat",
                                        featureId = "assistant_chat",
                                    )
                                }
                                accumulated.append(event.chunk)
                                _streamingPartialText.value = accumulated.toString()
                            }
                            is com.laterbox.app.ai.BackendAiStreamEvent.ActionProposed -> {
                                val sig = AssistantActionHandler.actionSignature(event.action)
                                proposedActionsMap.putIfAbsent(sig, event.action)
                            }
                            is com.laterbox.app.ai.BackendAiStreamEvent.ApprovalRequired -> {
                                val sig = AssistantActionHandler.actionSignature(event.action)
                                proposedActionsMap.putIfAbsent(sig, event.action)
                            }
                            is com.laterbox.app.ai.BackendAiStreamEvent.Completed -> {
                                receivedTerminal = true
                                val finalDisplay = event.displayText.ifBlank {
                                    AssistantActionHandler.parseModelResponse(event.fullText).displayText
                                }
                                val cleanReply = GeminiClient.redactSecrets(finalDisplay, resolvedKey)
                                val parsedActions = if (event.actions.isNotEmpty()) {
                                    event.actions
                                } else {
                                    AssistantActionHandler.parseModelResponse(event.fullText).actions
                                }
                                val mergedActions = (proposedActionsMap.values + parsedActions)
                                    .distinctBy { AssistantActionHandler.actionSignature(it) }
                                val trackedActions = AssistantActionHandler.toTrackedActions(mergedActions)
                                chatRepository.updateAssistantMessage(
                                    messageId = placeholder.id,
                                    conversationId = conversationId,
                                    content = cleanReply,
                                    status = ChatMessageStatus.COMPLETE,
                                    actionsJson = AssistantActionHandler.toTrackedJsonList(trackedActions)
                                )
                                launchContextualTitleGeneration(conversationId, latestPrompt, cleanReply)
                                processMessageActionsAfterCompletion(
                                    conversationId = conversationId,
                                    messageId = placeholder.id,
                                    trackedActions = trackedActions,
                                    prefs = prefs
                                )
                            }
                            is com.laterbox.app.ai.BackendAiStreamEvent.Error -> {
                                if (event.code == "AI_NOT_CONFIGURED" && !resolvedKey.isNullOrBlank()) {
                                    backendNotConfigured = true
                                } else {
                                    receivedTerminal = true
                                    analyticsTracker?.trackErrorObserved(
                                        errorCategory = "ai_request_failed",
                                        screenId = "chat",
                                        featureId = "assistant_chat",
                                    )
                                    val cleanError = GeminiClient.redactSecrets(event.message, resolvedKey)
                                    chatRepository.updateAssistantMessage(
                                        messageId = placeholder.id,
                                        conversationId = conversationId,
                                        content = GeminiClient.redactSecrets(accumulated.toString(), resolvedKey),
                                        status = ChatMessageStatus.FAILED,
                                        errorMessage = cleanError
                                    )
                                }
                            }
                        }
                    }
                    if (backendNotConfigured && !receivedTerminal) {
                        fallbackToDirectGemini = true
                    }
                }

                if (fallbackToDirectGemini) {
                    GeminiClient.streamChat(
                        turns = turns,
                        systemInstruction = builtContext.systemInstruction,
                        customApiKey = prefs.geminiApiKey.ifBlank { null }
                    ).collect { event ->
                        when (event) {
                            is GeminiStreamEvent.Delta -> {
                                if (!firstTokenRecorded && event.chunk.isNotEmpty()) {
                                    firstTokenRecorded = true
                                    analyticsTracker?.trackPerformanceMetric(
                                        metricName = "ai_first_token_ms",
                                        metricValueMs = (System.currentTimeMillis() - requestStartedMs)
                                            .coerceIn(0L, 600_000L)
                                            .toInt(),
                                        screenId = "chat",
                                        featureId = "assistant_chat",
                                    )
                                }
                                accumulated.append(event.chunk)
                                _streamingPartialText.value = accumulated.toString()
                            }
                            is GeminiStreamEvent.Completed -> {
                                receivedTerminal = true
                                val parsed = AssistantActionHandler.parseModelResponse(event.fullText)
                                val cleanReply = GeminiClient.redactSecrets(parsed.displayText, resolvedKey)
                                val trackedActions = AssistantActionHandler.toTrackedActions(parsed.actions)
                                chatRepository.updateAssistantMessage(
                                    messageId = placeholder.id,
                                    conversationId = conversationId,
                                    content = cleanReply,
                                    status = ChatMessageStatus.COMPLETE,
                                    actionsJson = AssistantActionHandler.toTrackedJsonList(trackedActions)
                                )
                                launchContextualTitleGeneration(conversationId, latestPrompt, cleanReply)
                                processMessageActionsAfterCompletion(
                                    conversationId = conversationId,
                                    messageId = placeholder.id,
                                    trackedActions = trackedActions,
                                    prefs = prefs
                                )
                            }
                            is GeminiStreamEvent.Error -> {
                                receivedTerminal = true
                                analyticsTracker?.trackErrorObserved(
                                    errorCategory = "ai_request_failed",
                                    screenId = "chat",
                                    featureId = "assistant_chat",
                                )
                                val cleanError = GeminiClient.redactSecrets(event.reason.userMessage, resolvedKey)
                                chatRepository.updateAssistantMessage(
                                    messageId = placeholder.id,
                                    conversationId = conversationId,
                                    content = GeminiClient.redactSecrets(accumulated.toString(), resolvedKey),
                                    status = ChatMessageStatus.FAILED,
                                    errorMessage = cleanError
                                )
                            }
                        }
                    }
                }

                if (!receivedTerminal && accumulated.isNotEmpty()) {
                    receivedTerminal = true
                    val parsed = AssistantActionHandler.parseModelResponse(accumulated.toString())
                    val cleanReply = GeminiClient.redactSecrets(parsed.displayText, resolvedKey)
                    val trackedActions = AssistantActionHandler.toTrackedActions(parsed.actions)
                    withContext(NonCancellable) {
                        chatRepository.updateAssistantMessage(
                            messageId = placeholder.id,
                            conversationId = conversationId,
                            content = cleanReply,
                            status = ChatMessageStatus.COMPLETE,
                            actionsJson = AssistantActionHandler.toTrackedJsonList(trackedActions)
                        )
                    }
                    launchContextualTitleGeneration(conversationId, latestPrompt, cleanReply)
                    processMessageActionsAfterCompletion(
                        conversationId = conversationId,
                        messageId = placeholder.id,
                        trackedActions = trackedActions,
                        prefs = prefs
                    )
                }
            } catch (ce: CancellationException) {
                if (!receivedTerminal) {
                    withContext(NonCancellable) {
                        val partial = GeminiClient.redactSecrets(accumulated.toString(), resolvedKey).trim()
                        chatRepository.updateAssistantMessage(
                            messageId = placeholder.id,
                            conversationId = conversationId,
                            content = partial,
                            status = ChatMessageStatus.CANCELLED,
                            errorMessage = if (partial.isEmpty()) "Stopped before response started." else null
                        )
                    }
                }
                throw ce
            } catch (t: Throwable) {
                analyticsTracker?.trackErrorObserved(
                    errorCategory = "ai_request_failed",
                    screenId = "chat",
                    featureId = "assistant_chat",
                )
                val cleanMsg = GeminiClient.redactSecrets(t.message ?: "Assistant request failed.", resolvedKey)
                chatRepository.updateAssistantMessage(
                    messageId = placeholder.id,
                    conversationId = conversationId,
                    content = GeminiClient.redactSecrets(accumulated.toString(), resolvedKey),
                    status = ChatMessageStatus.FAILED,
                    errorMessage = cleanMsg.take(180)
                )
            } finally {
                _isStreaming.value = false
                _streamingAssistantMessageId.value = null
                _streamingPartialText.value = ""
            }
        }
    }

    private suspend fun processMessageActionsAfterCompletion(
        conversationId: String,
        messageId: String,
        trackedActions: List<com.laterbox.app.ai.TrackedChatAction>,
        @Suppress("UNUSED_PARAMETER") prefs: com.laterbox.app.data.preferences.UserPreferences
    ) {
        if (trackedActions.isEmpty()) return
        val proposedBatch = trackedActions.filter { it.status == ChatActionStatus.PROPOSED }
        if (proposedBatch.isNotEmpty()) {
            actionQueueCoordinator.processBatch(
                conversationId = conversationId,
                messageId = messageId,
                mutatingActions = proposedBatch
            )
        }
    }

    private fun validateReadOnlyActionTarget(action: ChatAction): String? = when (action) {
        is ChatAction.OpenMovieDetail ->
            if (action.providerId.isBlank() || action.titleId.isBlank()) {
                "Missing title reference for \"${action.title.ifBlank { "title" }}\"."
            } else null
        is ChatAction.OpenMoviePlayer ->
            if (action.providerId.isBlank() || action.titleId.isBlank()) {
                "Missing playback target for \"${action.title.ifBlank { "title" }}\"."
            } else null
        is ChatAction.OpenLinkDetail ->
            if (action.linkId <= 0L) "Missing saved link reference." else null
        else -> null
    }

    private fun extractRecentActionReceipts(
        messages: List<ChatMessageEntity>,
        maxItems: Int = 10
    ): List<com.laterbox.app.ai.RecentActionReceipt> {
        val receipts = mutableListOf<com.laterbox.app.ai.RecentActionReceipt>()
        for (msg in messages) {
            val tracked = AssistantActionHandler.fromTrackedJsonList(msg.actionsJson)
            for (item in tracked) {
                val statusStr = when (item.status) {
                    com.laterbox.app.ai.ChatActionStatus.EXECUTED -> "executed"
                    com.laterbox.app.ai.ChatActionStatus.FAILED -> "failed"
                    com.laterbox.app.ai.ChatActionStatus.DENIED -> "denied"
                    else -> null
                } ?: continue
                receipts.add(
                    com.laterbox.app.ai.RecentActionReceipt(
                        actionId = item.actionId,
                        actionType = item.action.actionType,
                        category = item.action.permissionCategory.key,
                        status = statusStr,
                        detail = item.statusDetail?.takeIf { it.isNotBlank() } ?: item.action.label
                    )
                )
            }
        }
        return receipts.takeLast(maxItems)
    }

    private fun buildTurnsFromSnapshot(messages: List<ChatMessageEntity>): List<GeminiTurn> {
        val recent = messages.takeLast(12)
        return recent.mapNotNull { entity ->
            val role = when (entity.role) {
                ChatRole.USER.name -> GeminiRole.USER
                ChatRole.ASSISTANT.name -> GeminiRole.MODEL
                else -> null
            } ?: return@mapNotNull null
            val cleanText = entity.content.trim()
            if (cleanText.isEmpty()) return@mapNotNull null
            GeminiTurn(role = role, text = cleanText)
        }
    }

    private data class Quad(
        val isStreaming: Boolean,
        val msgId: String?,
        val partial: String,
        val composer: String
    )

    private data class AuxState(
        val pendingAction: ChatAction?,
        val notice: String?,
        val manifestOutOfSync: Boolean = false
    )

    companion object {
        const val TTS_UNAVAILABLE_TOAST = "Read-aloud is unavailable right now."

        fun hasBlockingMutatingActionForRetry(actionsJson: String?): Boolean {
            val tracked = AssistantActionHandler.fromTrackedJsonList(actionsJson)
            return tracked.any { item ->
                item.action.category == ActionCategory.STATE_CHANGING &&
                    (item.status == com.laterbox.app.ai.ChatActionStatus.EXECUTED ||
                        item.status == com.laterbox.app.ai.ChatActionStatus.AUTO_EXECUTING)
            }
        }
    }
}
