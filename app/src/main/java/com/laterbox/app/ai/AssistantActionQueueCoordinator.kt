package com.laterbox.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class QueuedMutatingAction(
    val action: ChatAction,
    val conversationId: String?,
    val messageId: String?,
    val actionId: String?,
    val batchId: String? = messageId
)

data class ActionExecutionOutcome(
    val succeeded: Boolean,
    val receipt: String? = null,
    val errorDetail: String? = null,
    val skipStandardStatusAndReceipt: Boolean = false
)

interface AssistantActionCoordinatorHost {
    fun activeConversationId(): String?
    suspend fun validateMutatingTarget(action: ChatAction): String?
    suspend fun isActionAllowedWithoutPrompt(conversationId: String?, action: ChatAction): Boolean
    suspend fun grantAlwaysAllow(conversationId: String?, action: ChatAction)
    suspend fun executeMutatingAction(
        action: ChatAction,
        conversationId: String?,
        messageId: String?,
        actionId: String?
    ): ActionExecutionOutcome
    suspend fun updateActionStatus(
        conversationId: String,
        messageId: String,
        actionId: String,
        status: ChatActionStatus,
        statusDetail: String? = null
    )
    suspend fun reportActionOutcome(
        conversationId: String?,
        action: ChatAction,
        status: String,
        detail: String,
        actionId: String? = null
    )
    suspend fun postReceipt(conversationId: String?, text: String)
}

/**
 * Pure-Kotlin sequential FIFO coordinator for mutating Assistant actions.
 *
 * Enforces:
 * - Single active approval prompt at a time ([pendingApproval])
 * - Batch-scoped ([QueuedMutatingAction.batchId]) dependency-aware abort: if any action in a
 *   multi-action AI response batch is denied or fails validation/execution, remaining queued or
 *   unprocessed mutating actions in that same batch are marked [ChatActionStatus.DENIED] and skipped.
 * - Independent manual actions (`batchId == null`) remain unaffected by unrelated denials/failures.
 * - Re-evaluation of "Always allow" grants as queued items advance (while never auto-allowing
 *   [ChatAction.isIrreversibleDestructive] actions).
 * - Clean synchronous & persisted cancellation on conversation switch/new chat.
 */
class AssistantActionQueueCoordinator(
    private val host: AssistantActionCoordinatorHost
) {
    private val _pendingApproval = MutableStateFlow<ChatAction?>(null)
    val pendingApproval: StateFlow<ChatAction?> = _pendingApproval.asStateFlow()

    private val enqueueMutex = Mutex()
    private val stateLock = Any()
    private var activeItem: QueuedMutatingAction? = null
    private val pendingQueue = ArrayDeque<QueuedMutatingAction>()
    private val executingActionKeys = mutableSetOf<String>()
    private val processedMessageActionKeys = mutableSetOf<String>()

    fun takeActiveItem(): QueuedMutatingAction? = synchronized(stateLock) {
        val current = activeItem ?: return null
        activeItem = null
        _pendingApproval.value = null
        current
    }

    fun clearWithoutPersisting() {
        synchronized(stateLock) {
            activeItem = null
            _pendingApproval.value = null
            pendingQueue.clear()
        }
    }

    fun clearAndDrainPending(): List<QueuedMutatingAction> = synchronized(stateLock) {
        val drained = mutableListOf<QueuedMutatingAction>()
        activeItem?.let { drained.add(it) }
        drained.addAll(pendingQueue)
        activeItem = null
        _pendingApproval.value = null
        pendingQueue.clear()
        drained
    }

    suspend fun markItemsDenied(
        items: List<QueuedMutatingAction>,
        reason: String = ABANDON_STATUS_DETAIL
    ) {
        for (item in items) {
            val convId = item.conversationId
            val msgId = item.messageId
            val actId = item.actionId
            if (convId != null && msgId != null && actId != null) {
                host.updateActionStatus(
                    conversationId = convId,
                    messageId = msgId,
                    actionId = actId,
                    status = ChatActionStatus.DENIED,
                    statusDetail = reason
                )
            }
        }
    }

    suspend fun resetAndCancelPending(abandonMessage: String = ABANDON_STATUS_DETAIL) {
        val drained = clearAndDrainPending()
        markItemsDenied(drained, abandonMessage)
    }

    suspend fun enqueueSingle(
        action: ChatAction,
        conversationId: String?,
        messageId: String? = null,
        actionId: String? = null
    ) {
        if (action.category != ActionCategory.STATE_CHANGING) return
        val autoExecuteItem: QueuedMutatingAction? = enqueueMutex.withLock {
            val item = QueuedMutatingAction(
                action = action,
                conversationId = conversationId,
                messageId = messageId,
                actionId = actionId,
                batchId = null
            )
            try {
                if (host.activeConversationId() != conversationId) {
                    markItemsDenied(listOf(item), CANCELLED_CONVERSATION_CHANGE_DETAIL)
                    return@withLock null
                }

                val problem = host.validateMutatingTarget(action)
                if (host.activeConversationId() != conversationId) {
                    markItemsDenied(listOf(item), CANCELLED_CONVERSATION_CHANGE_DETAIL)
                    return@withLock null
                }

                if (problem != null) {
                    if (conversationId != null && messageId != null && actionId != null) {
                        host.updateActionStatus(
                            conversationId = conversationId,
                            messageId = messageId,
                            actionId = actionId,
                            status = ChatActionStatus.FAILED,
                            statusDetail = problem
                        )
                    }
                    host.reportActionOutcome(conversationId, action, "failed", problem, actionId)
                    host.postReceipt(
                        conversationId,
                        "I couldn't ${action.actionName.lowercase()}: $problem Nothing was changed."
                    )
                    return@withLock null
                }

                if (host.isActionAllowedWithoutPrompt(conversationId, action)) {
                    if (host.activeConversationId() != conversationId) {
                        markItemsDenied(listOf(item), CANCELLED_CONVERSATION_CHANGE_DETAIL)
                        return@withLock null
                    }
                    if (conversationId != null && messageId != null && actionId != null) {
                        host.updateActionStatus(
                            conversationId = conversationId,
                            messageId = messageId,
                            actionId = actionId,
                            status = ChatActionStatus.AUTO_EXECUTING
                        )
                    }
                    item
                } else {
                    enqueueOrPresent(item)
                    null
                }
            } catch (ce: CancellationException) {
                if (host.activeConversationId() != conversationId) {
                    withContext(NonCancellable) {
                        markItemsDenied(listOf(item), CANCELLED_CONVERSATION_CHANGE_DETAIL)
                    }
                }
                throw ce
            }
        }
        if (autoExecuteItem != null) {
            executeItem(autoExecuteItem)
        }
    }

    suspend fun processBatch(
        conversationId: String,
        messageId: String,
        mutatingActions: List<TrackedChatAction>
    ) = enqueueMutex.withLock {
        if (mutatingActions.isEmpty()) return@withLock
        val batchItems = mutableListOf<QueuedMutatingAction>()
        synchronized(stateLock) {
            for (tracked in mutatingActions) {
                if (tracked.status != ChatActionStatus.PROPOSED) continue
                if (tracked.action.category != ActionCategory.STATE_CHANGING) continue
                if (!processedMessageActionKeys.add("$messageId:${tracked.actionId}")) continue
                batchItems.add(
                    QueuedMutatingAction(
                        action = tracked.action,
                        conversationId = conversationId,
                        messageId = messageId,
                        actionId = tracked.actionId,
                        batchId = messageId
                    )
                )
            }
        }
        if (batchItems.isEmpty()) return@withLock

        var batchHasAwaitingPredecessor = false
        for (index in batchItems.indices) {
            try {
                if (host.activeConversationId() != conversationId) {
                    val remaining = batchItems.subList(index, batchItems.size)
                    markItemsDenied(remaining, CANCELLED_CONVERSATION_CHANGE_DETAIL)
                    return@withLock
                }

                val item = batchItems[index]
                if (batchHasAwaitingPredecessor) {
                    if (!enqueueOrPresent(item)) {
                        val remaining = batchItems.subList(index + 1, batchItems.size)
                        markItemsDenied(remaining, CANCELLED_CONVERSATION_CHANGE_DETAIL)
                        return@withLock
                    }
                    continue
                }

                val problem = host.validateMutatingTarget(item.action)
                if (host.activeConversationId() != conversationId) {
                    val remaining = batchItems.subList(index, batchItems.size)
                    markItemsDenied(remaining, CANCELLED_CONVERSATION_CHANGE_DETAIL)
                    return@withLock
                }
                if (problem != null) {
                    if (item.messageId != null && item.actionId != null) {
                        host.updateActionStatus(
                            conversationId = conversationId,
                            messageId = item.messageId,
                            actionId = item.actionId,
                            status = ChatActionStatus.FAILED,
                            statusDetail = problem
                        )
                    }
                    host.reportActionOutcome(conversationId, item.action, "failed", problem, item.actionId)
                    host.postReceipt(
                        conversationId,
                        "I couldn't ${item.action.actionName.lowercase()}: $problem Nothing was changed."
                    )
                    abortRemainingBatchItems(batchItems.subList(index + 1, batchItems.size))
                    return@withLock
                }

                if (host.isActionAllowedWithoutPrompt(conversationId, item.action)) {
                    if (host.activeConversationId() != conversationId) {
                        val remaining = batchItems.subList(index, batchItems.size)
                        markItemsDenied(remaining, CANCELLED_CONVERSATION_CHANGE_DETAIL)
                        return@withLock
                    }
                    if (item.messageId != null && item.actionId != null) {
                        host.updateActionStatus(
                            conversationId = conversationId,
                            messageId = item.messageId,
                            actionId = item.actionId,
                            status = ChatActionStatus.AUTO_EXECUTING
                        )
                    }
                    val succeeded = executeItem(item)
                    if (host.activeConversationId() != conversationId) {
                        val remaining = batchItems.subList(index + 1, batchItems.size)
                        markItemsDenied(remaining, CANCELLED_CONVERSATION_CHANGE_DETAIL)
                        return@withLock
                    }
                    if (!succeeded) {
                        abortRemainingBatchItems(batchItems.subList(index + 1, batchItems.size))
                        return@withLock
                    }
                } else {
                    if (!enqueueOrPresent(item)) {
                        val remaining = batchItems.subList(index + 1, batchItems.size)
                        markItemsDenied(remaining, CANCELLED_CONVERSATION_CHANGE_DETAIL)
                        return@withLock
                    }
                    batchHasAwaitingPredecessor = true
                }
            } catch (ce: CancellationException) {
                if (host.activeConversationId() != conversationId) {
                    withContext(NonCancellable) {
                        val remaining = batchItems.subList(index, batchItems.size)
                        markItemsDenied(remaining, CANCELLED_CONVERSATION_CHANGE_DETAIL)
                    }
                }
                throw ce
            }
        }
    }

    suspend fun confirmActive() {
        val active = takeActiveItem() ?: return
        confirmItem(active)
    }

    suspend fun confirmItem(active: QueuedMutatingAction) {
        val succeeded = executeItem(active)
        if (!succeeded && active.batchId != null) {
            if (host.activeConversationId() != active.conversationId) {
                cancelQueuedBatch(active.batchId, active.conversationId, CANCELLED_CONVERSATION_CHANGE_DETAIL)
            } else {
                abortQueuedBatch(active.batchId, active.conversationId)
            }
        }
        advanceQueue()
    }

    suspend fun alwaysAllowActive() {
        val active = takeActiveItem() ?: return
        alwaysAllowItem(active)
    }

    suspend fun alwaysAllowItem(active: QueuedMutatingAction) {
        if (!active.action.isIrreversibleDestructive) {
            host.grantAlwaysAllow(active.conversationId, active.action)
        }
        val succeeded = executeItem(active)
        if (!succeeded && active.batchId != null) {
            if (host.activeConversationId() != active.conversationId) {
                cancelQueuedBatch(active.batchId, active.conversationId, CANCELLED_CONVERSATION_CHANGE_DETAIL)
            } else {
                abortQueuedBatch(active.batchId, active.conversationId)
            }
        }
        advanceQueue()
    }

    suspend fun denyActive() {
        val active = takeActiveItem() ?: return
        denyItem(active)
    }

    suspend fun denyItem(active: QueuedMutatingAction) {
        val convId = active.conversationId
        if (convId != null && active.messageId != null && active.actionId != null) {
            host.updateActionStatus(
                conversationId = convId,
                messageId = active.messageId,
                actionId = active.actionId,
                status = ChatActionStatus.DENIED,
                statusDetail = "Declined by user"
            )
        }
        host.reportActionOutcome(convId, active.action, "denied", "Denied by user", active.actionId)
        host.postReceipt(
            convId,
            "Okay, I won't ${active.action.actionName.lowercase()}${active.action.targetLabel?.let { " for \"$it\"" }.orEmpty()}. Nothing was changed."
        )
        if (active.batchId != null) {
            abortQueuedBatch(active.batchId, convId)
        }
        advanceQueue()
    }

    private suspend fun enqueueOrPresent(item: QueuedMutatingAction): Boolean {
        val convId = item.conversationId
        if (host.activeConversationId() != convId) {
            markItemsDenied(listOf(item), CANCELLED_CONVERSATION_CHANGE_DETAIL)
            return false
        }
        var conversationChanged = false
        val shouldPersistAwaiting = synchronized(stateLock) {
            if (host.activeConversationId() != convId) {
                conversationChanged = true
                return@synchronized false
            }
            if (isActiveOrQueuedLocked(item)) return@synchronized false
            if (activeItem == null && _pendingApproval.value == null) {
                activeItem = item
                _pendingApproval.value = item.action
            } else {
                pendingQueue.addLast(item)
            }
            true
        }
        if (conversationChanged) {
            markItemsDenied(listOf(item), CANCELLED_CONVERSATION_CHANGE_DETAIL)
            return false
        }
        if (!shouldPersistAwaiting) return true
        if (convId != null && item.messageId != null && item.actionId != null) {
            host.updateActionStatus(
                conversationId = convId,
                messageId = item.messageId,
                actionId = item.actionId,
                status = ChatActionStatus.AWAITING_APPROVAL
            )
        }
        return true
    }

    private suspend fun advanceQueue() {
        while (true) {
            val next = synchronized(stateLock) {
                if (activeItem != null || _pendingApproval.value != null || pendingQueue.isEmpty()) {
                    return
                }
                pendingQueue.removeFirst()
            }
            val activeConvId = host.activeConversationId()
            if (next.conversationId != activeConvId) {
                markItemsDenied(listOf(next), CANCELLED_CONVERSATION_CHANGE_DETAIL)
                if (next.batchId != null) {
                    cancelQueuedBatch(next.batchId, next.conversationId, CANCELLED_CONVERSATION_CHANGE_DETAIL)
                }
                continue
            }

            val problem = host.validateMutatingTarget(next.action)
            if (host.activeConversationId() != next.conversationId) {
                markItemsDenied(listOf(next), CANCELLED_CONVERSATION_CHANGE_DETAIL)
                if (next.batchId != null) {
                    cancelQueuedBatch(next.batchId, next.conversationId, CANCELLED_CONVERSATION_CHANGE_DETAIL)
                }
                continue
            }
            if (problem != null) {
                if (activeConvId != null && next.messageId != null && next.actionId != null) {
                    host.updateActionStatus(
                        conversationId = activeConvId,
                        messageId = next.messageId,
                        actionId = next.actionId,
                        status = ChatActionStatus.FAILED,
                        statusDetail = problem
                    )
                }
                host.reportActionOutcome(activeConvId, next.action, "failed", problem, next.actionId)
                host.postReceipt(
                    activeConvId,
                    "I couldn't ${next.action.actionName.lowercase()}: $problem Nothing was changed."
                )
                if (next.batchId != null) {
                    abortQueuedBatch(next.batchId, next.conversationId)
                }
                continue
            }

            if (host.isActionAllowedWithoutPrompt(activeConvId, next.action)) {
                if (host.activeConversationId() != next.conversationId) {
                    markItemsDenied(listOf(next), CANCELLED_CONVERSATION_CHANGE_DETAIL)
                    if (next.batchId != null) {
                        cancelQueuedBatch(next.batchId, next.conversationId, CANCELLED_CONVERSATION_CHANGE_DETAIL)
                    }
                    continue
                }
                if (activeConvId != null && next.messageId != null && next.actionId != null) {
                    host.updateActionStatus(
                        conversationId = activeConvId,
                        messageId = next.messageId,
                        actionId = next.actionId,
                        status = ChatActionStatus.AUTO_EXECUTING
                    )
                }
                val succeeded = executeItem(next)
                if (!succeeded && next.batchId != null) {
                    if (host.activeConversationId() != next.conversationId) {
                        cancelQueuedBatch(next.batchId, next.conversationId, CANCELLED_CONVERSATION_CHANGE_DETAIL)
                    } else {
                        abortQueuedBatch(next.batchId, next.conversationId)
                    }
                }
                continue
            }

            var conversationChanged = false
            val presented = synchronized(stateLock) {
                if (host.activeConversationId() != next.conversationId) {
                    conversationChanged = true
                    false
                } else if (activeItem == null && _pendingApproval.value == null) {
                    activeItem = next
                    _pendingApproval.value = next.action
                    true
                } else {
                    pendingQueue.addFirst(next)
                    false
                }
            }
            if (conversationChanged) {
                markItemsDenied(listOf(next), CANCELLED_CONVERSATION_CHANGE_DETAIL)
                if (next.batchId != null) {
                    cancelQueuedBatch(next.batchId, next.conversationId, CANCELLED_CONVERSATION_CHANGE_DETAIL)
                }
                continue
            }
            if (presented && activeConvId != null && next.messageId != null && next.actionId != null) {
                host.updateActionStatus(
                    conversationId = activeConvId,
                    messageId = next.messageId,
                    actionId = next.actionId,
                    status = ChatActionStatus.AWAITING_APPROVAL
                )
            }
            return
        }
    }

    private suspend fun cancelQueuedBatch(batchId: String, conversationId: String?, reason: String) {
        val cancelled = drainQueuedBatch(batchId, conversationId)
        markItemsDenied(cancelled, reason)
    }

    private fun drainQueuedBatch(batchId: String, conversationId: String?): List<QueuedMutatingAction> =
        synchronized(stateLock) {
            val matching = mutableListOf<QueuedMutatingAction>()
            val iterator = pendingQueue.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.batchId == batchId && candidate.conversationId == conversationId) {
                    matching.add(candidate)
                    iterator.remove()
                }
            }
            matching
        }

    private suspend fun abortQueuedBatch(batchId: String, conversationId: String?) {
        val aborted = drainQueuedBatch(batchId, conversationId)
        abortRemainingBatchItems(aborted)
    }

    private suspend fun abortRemainingBatchItems(items: List<QueuedMutatingAction>) {
        for (item in items) {
            val convId = item.conversationId
            if (convId != null && item.messageId != null && item.actionId != null) {
                host.updateActionStatus(
                    conversationId = convId,
                    messageId = item.messageId,
                    actionId = item.actionId,
                    status = ChatActionStatus.DENIED,
                    statusDetail = SKIPPED_BATCH_DETAIL
                )
            }
            host.reportActionOutcome(
                conversationId = convId,
                action = item.action,
                status = "denied",
                detail = SKIPPED_BATCH_DETAIL,
                actionId = item.actionId
            )
        }
    }

    private suspend fun executeItem(item: QueuedMutatingAction): Boolean {
        val dedupeKey = dedupeKeyFor(item.action, item.conversationId) ?: return false
        val added = synchronized(stateLock) { executingActionKeys.add(dedupeKey) }
        if (!added) return false

        val convId = item.conversationId
        val msgId = item.messageId
        val actId = item.actionId

        suspend fun recordStatus(status: ChatActionStatus, detail: String?) {
            if (convId != null && msgId != null && actId != null) {
                host.updateActionStatus(convId, msgId, actId, status, detail)
            }
        }

        return try {
            if (host.activeConversationId() != convId) {
                recordStatus(ChatActionStatus.DENIED, CANCELLED_CONVERSATION_CHANGE_DETAIL)
                return false
            }
            val problem = host.validateMutatingTarget(item.action)
            if (host.activeConversationId() != convId) {
                recordStatus(ChatActionStatus.DENIED, CANCELLED_CONVERSATION_CHANGE_DETAIL)
                return false
            }
            if (problem != null) {
                recordStatus(ChatActionStatus.FAILED, problem)
                host.reportActionOutcome(convId, item.action, "failed", problem, actId)
                host.postReceipt(
                    convId,
                    "I couldn't ${item.action.actionName.lowercase()}: $problem Nothing was changed."
                )
                return false
            }

            val outcome = host.executeMutatingAction(item.action, convId, msgId, actId)
            if (!outcome.skipStandardStatusAndReceipt) {
                val receipt = outcome.receipt?.takeIf { it.isNotBlank() }
                    ?: if (outcome.succeeded) {
                        "Completed ${item.action.actionName.lowercase()}."
                    } else {
                        outcome.errorDetail?.takeIf { it.isNotBlank() }
                            ?: "I couldn't ${item.action.actionName.lowercase()}."
                    }
                recordStatus(
                    if (outcome.succeeded) ChatActionStatus.EXECUTED else ChatActionStatus.FAILED,
                    receipt
                )
                host.reportActionOutcome(
                    conversationId = convId,
                    action = item.action,
                    status = if (outcome.succeeded) "executed" else "failed",
                    detail = outcome.errorDetail?.takeIf { it.isNotBlank() } ?: receipt,
                    actionId = actId
                )
                host.postReceipt(convId, receipt)
            }
            outcome.succeeded
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val errDetail = t.message.orEmpty()
            recordStatus(ChatActionStatus.FAILED, errDetail)
            host.reportActionOutcome(convId, item.action, "failed", errDetail, actId)
            val safeError = errDetail.trim().take(120).ifBlank { "unexpected error" }
            host.postReceipt(convId, "That didn't work: $safeError.")
            false
        } finally {
            synchronized(stateLock) {
                executingActionKeys.remove(dedupeKey)
            }
        }
    }

    private fun isActiveOrQueuedLocked(item: QueuedMutatingAction): Boolean {
        val itemSig = AssistantActionHandler.actionSignature(item.action)
        val current = activeItem
        if (current != null) {
            if (item.messageId != null && item.actionId != null &&
                current.messageId != null && current.actionId != null
            ) {
                if (current.conversationId == item.conversationId &&
                    current.messageId == item.messageId &&
                    current.actionId == item.actionId
                ) {
                    return true
                }
            } else if (current.conversationId == item.conversationId &&
                AssistantActionHandler.actionSignature(current.action) == itemSig
            ) {
                return true
            }
        }
        return pendingQueue.any { queued ->
            if (item.messageId != null && item.actionId != null &&
                queued.messageId != null && queued.actionId != null
            ) {
                queued.conversationId == item.conversationId &&
                    queued.messageId == item.messageId &&
                    queued.actionId == item.actionId
            } else {
                queued.conversationId == item.conversationId &&
                    AssistantActionHandler.actionSignature(queued.action) == itemSig
            }
        }
    }

    private fun dedupeKeyFor(action: ChatAction, conversationId: String?): String? = when (action) {
        is ChatAction.RetryInspection -> "RI:${action.linkId}"
        is ChatAction.RefreshMetadata -> "RM:${action.linkId}"
        is ChatAction.RetryDownload -> "RD:${action.taskId}"
        is ChatAction.ToggleMovieWatchlist -> "TMW:${action.providerId}:${action.titleId}:${action.add}"
        is ChatAction.AddMovieToCollection -> "AMC:${action.providerId}:${action.titleId}:${action.collectionId ?: action.collectionName.orEmpty()}"
        is ChatAction.MarkMovieWatched -> "MMW:${action.providerId}:${action.titleId}:${action.seasonNumber}:${action.episodeNumber}:${action.watched}"
        is ChatAction.ScheduleMovieReminder -> "SMR:${action.providerId}:${action.titleId}:${action.triggerAtMillis}"
        is ChatAction.MuteSeriesReminders -> "MSR:${action.providerId}:${action.titleId}:${action.muted}"
        is ChatAction.RenameConversation -> "RC:${action.conversationId ?: conversationId.orEmpty()}:${action.title}"
        is ChatAction.DeleteConversation -> "DC:${action.conversationId ?: conversationId.orEmpty()}"
        is ChatAction.DeleteDownload -> "DD:${action.taskId.orEmpty()}:${action.assetId.orEmpty()}"
        else -> null
    }

    companion object {
        const val SKIPPED_BATCH_DETAIL = "Skipped because previous action was cancelled/failed"
        const val ABANDON_STATUS_DETAIL = "Cancelled when conversation changed"
        const val CANCELLED_CONVERSATION_CHANGE_DETAIL = ABANDON_STATUS_DETAIL
    }
}
