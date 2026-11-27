package com.laterbox.app.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantActionQueueCoordinatorTest {

    private class RecordingHost : AssistantActionCoordinatorHost {
        var currentConversationId: String? = "conv-1"
        val validationErrors = mutableMapOf<String, String?>()
        val allowedPermissions = mutableSetOf<String>()
        val executionOutcomes = mutableMapOf<String, ActionExecutionOutcome>()
        var executionThrowsForAction: String? = null
        var executionDelayMs: Long = 0L
        var statusUpdateDelayMs: Long = 0L
        var onValidate: (suspend (ChatAction) -> Unit)? = null
        var onAfterExecute: (() -> Unit)? = null

        val executedActions = mutableListOf<ChatAction>()
        val statusUpdates = mutableListOf<StatusUpdate>()
        val outcomeReports = mutableListOf<OutcomeReport>()
        val receipts = mutableListOf<String>()

        data class StatusUpdate(
            val conversationId: String,
            val messageId: String,
            val actionId: String,
            val status: ChatActionStatus,
            val detail: String?
        )

        data class OutcomeReport(
            val conversationId: String?,
            val actionType: String,
            val status: String,
            val detail: String,
            val actionId: String?
        )

        override fun activeConversationId(): String? = currentConversationId

        override suspend fun validateMutatingTarget(action: ChatAction): String? {
            onValidate?.invoke(action)
            return validationErrors[action.actionType]
        }

        override suspend fun isActionAllowedWithoutPrompt(conversationId: String?, action: ChatAction): Boolean {
            if (action.isIrreversibleDestructive) return false
            if (!action.permissionCategory.requiresApprovalByDefault) return true
            return action.permissionCategory.key in allowedPermissions ||
                (action.permissionKey != null && action.permissionKey in allowedPermissions)
        }

        override suspend fun grantAlwaysAllow(conversationId: String?, action: ChatAction) {
            if (!action.isIrreversibleDestructive) {
                allowedPermissions.add(action.permissionCategory.key)
                action.permissionKey?.let { allowedPermissions.add(it) }
            }
        }

        override suspend fun executeMutatingAction(
            action: ChatAction,
            conversationId: String?,
            messageId: String?,
            actionId: String?
        ): ActionExecutionOutcome {
            if (executionDelayMs > 0L) delay(executionDelayMs)
            if (executionThrowsForAction == action.actionType) {
                throw IllegalStateException("Boom on ${action.actionType}")
            }
            executedActions.add(action)
            onAfterExecute?.invoke()
            return executionOutcomes[action.actionType]
                ?: ActionExecutionOutcome(succeeded = true, receipt = "Done ${action.actionType}")
        }

        override suspend fun updateActionStatus(
            conversationId: String,
            messageId: String,
            actionId: String,
            status: ChatActionStatus,
            statusDetail: String?
        ) {
            if (statusUpdateDelayMs > 0L) delay(statusUpdateDelayMs)
            statusUpdates.add(StatusUpdate(conversationId, messageId, actionId, status, statusDetail))
        }

        override suspend fun reportActionOutcome(
            conversationId: String?,
            action: ChatAction,
            status: String,
            detail: String,
            actionId: String?
        ) {
            outcomeReports.add(OutcomeReport(conversationId, action.actionType, status, detail, actionId))
        }

        override suspend fun postReceipt(conversationId: String?, text: String) {
            receipts.add(text)
        }
    }

    private fun action1() = ChatAction.ToggleMovieWatchlist("tvmaze", "66", true, "Severance")
    private fun action2() = ChatAction.MarkMovieWatched("tvmaze", "66", 1, 1, true, "Severance")
    private fun action3() = ChatAction.DeleteDownload(taskId = "t-del", title = "Clip.mp4")

    @Test
    fun processBatch_presentsInFifoOrder_andReEvaluatesAlwaysAllowExcludingDestructive() = runTest {
        val host = RecordingHost()
        val coordinator = AssistantActionQueueCoordinator(host)

        val tracked = listOf(
            TrackedChatAction("a1", action1()),
            TrackedChatAction("a2", action2()),
            TrackedChatAction("a3", action3())
        )
        coordinator.processBatch("conv-1", "msg-1", tracked)

        assertTrue(coordinator.pendingApproval.value is ChatAction.ToggleMovieWatchlist)
        assertEquals(
            3,
            host.statusUpdates.count { it.status == ChatActionStatus.AWAITING_APPROVAL }
        )

        // "Always allow" on #1 -> executes #1, auto-executes #2 (MarkMovieWatched), prompts #3 (DeleteDownload)
        coordinator.alwaysAllowActive()
        assertEquals(2, host.executedActions.size)
        assertTrue(host.executedActions[0] is ChatAction.ToggleMovieWatchlist)
        assertTrue(host.executedActions[1] is ChatAction.MarkMovieWatched)
        assertTrue(coordinator.pendingApproval.value is ChatAction.DeleteDownload)

        coordinator.confirmActive()
        assertEquals(3, host.executedActions.size)
        assertNull(coordinator.pendingApproval.value)
    }

    @Test
    fun processBatch_denyingFirstActionAbortsRemainingBatchActions() = runTest {
        val host = RecordingHost()
        val coordinator = AssistantActionQueueCoordinator(host)

        coordinator.processBatch(
            "conv-1",
            "msg-1",
            listOf(
                TrackedChatAction("a1", action1()),
                TrackedChatAction("a2", action2()),
                TrackedChatAction("a3", action3())
            )
        )
        assertTrue(coordinator.pendingApproval.value is ChatAction.ToggleMovieWatchlist)

        coordinator.denyActive()

        // Remaining batch items must NEVER be prompted and must be DENIED with skipped detail
        assertNull(coordinator.pendingApproval.value)
        assertTrue(host.executedActions.isEmpty())
        val a2Final = host.statusUpdates.last { it.actionId == "a2" }
        val a3Final = host.statusUpdates.last { it.actionId == "a3" }
        assertEquals(ChatActionStatus.DENIED, a2Final.status)
        assertEquals(AssistantActionQueueCoordinator.SKIPPED_BATCH_DETAIL, a2Final.detail)
        assertEquals(ChatActionStatus.DENIED, a3Final.status)
        assertEquals(AssistantActionQueueCoordinator.SKIPPED_BATCH_DETAIL, a3Final.detail)
        assertTrue(host.outcomeReports.any { it.actionId == "a2" && it.status == "denied" && it.detail == AssistantActionQueueCoordinator.SKIPPED_BATCH_DETAIL })
        assertTrue(host.outcomeReports.any { it.actionId == "a3" && it.status == "denied" && it.detail == AssistantActionQueueCoordinator.SKIPPED_BATCH_DETAIL })
    }

    @Test
    fun processBatch_validationOrExecutionFailureAbortsRemainingBatchActions() = runTest {
        // 1. Validation failure on Action #1
        val hostValFail = RecordingHost().apply {
            validationErrors["TOGGLE_MOVIE_WATCHLIST"] = "title missing"
        }
        val coordValFail = AssistantActionQueueCoordinator(hostValFail)
        coordValFail.processBatch(
            "conv-1",
            "msg-val",
            listOf(TrackedChatAction("a1", action1()), TrackedChatAction("a2", action2()))
        )
        assertNull(coordValFail.pendingApproval.value)
        assertEquals(ChatActionStatus.FAILED, hostValFail.statusUpdates.last { it.actionId == "a1" }.status)
        assertEquals(ChatActionStatus.DENIED, hostValFail.statusUpdates.last { it.actionId == "a2" }.status)
        assertEquals(AssistantActionQueueCoordinator.SKIPPED_BATCH_DETAIL, hostValFail.statusUpdates.last { it.actionId == "a2" }.detail)

        // 2. Execution returning succeeded=false on Action #1
        val hostExecFalse = RecordingHost().apply {
            executionOutcomes["TOGGLE_MOVIE_WATCHLIST"] = ActionExecutionOutcome(succeeded = false, errorDetail = "db error")
        }
        val coordExecFalse = AssistantActionQueueCoordinator(hostExecFalse)
        coordExecFalse.processBatch(
            "conv-1",
            "msg-exec-false",
            listOf(TrackedChatAction("a1", action1()), TrackedChatAction("a2", action2()))
        )
        assertTrue(coordExecFalse.pendingApproval.value is ChatAction.ToggleMovieWatchlist)
        coordExecFalse.confirmActive()
        assertNull(coordExecFalse.pendingApproval.value)
        assertEquals(ChatActionStatus.FAILED, hostExecFalse.statusUpdates.last { it.actionId == "a1" }.status)
        assertEquals(ChatActionStatus.DENIED, hostExecFalse.statusUpdates.last { it.actionId == "a2" }.status)

        // 3. Execution throwing exception on Action #1
        val hostThrow = RecordingHost().apply {
            executionThrowsForAction = "TOGGLE_MOVIE_WATCHLIST"
        }
        val coordThrow = AssistantActionQueueCoordinator(hostThrow)
        coordThrow.processBatch(
            "conv-1",
            "msg-throw",
            listOf(TrackedChatAction("a1", action1()), TrackedChatAction("a2", action2()))
        )
        coordThrow.confirmActive()
        assertNull(coordThrow.pendingApproval.value)
        assertEquals(ChatActionStatus.FAILED, hostThrow.statusUpdates.last { it.actionId == "a1" }.status)
        assertEquals(ChatActionStatus.DENIED, hostThrow.statusUpdates.last { it.actionId == "a2" }.status)
    }

    @Test
    fun enqueueSingle_independentManualActionsContinueAfterDenial() = runTest {
        val host = RecordingHost()
        val coordinator = AssistantActionQueueCoordinator(host)

        coordinator.enqueueSingle(action1(), "conv-1")
        coordinator.enqueueSingle(action2(), "conv-1")
        coordinator.enqueueSingle(action3(), "conv-1")

        assertTrue(coordinator.pendingApproval.value is ChatAction.ToggleMovieWatchlist)
        coordinator.denyActive()

        // Independent batchId=null actions continue sequentially
        assertTrue(coordinator.pendingApproval.value is ChatAction.MarkMovieWatched)
        coordinator.confirmActive()
        assertTrue(coordinator.pendingApproval.value is ChatAction.DeleteDownload)
        coordinator.denyActive()
        assertNull(coordinator.pendingApproval.value)
        assertEquals(1, host.executedActions.size)
    }

    @Test
    fun resetAndCancelPending_clearsPromptAndTransitionsAwaitingRowsToDenied() = runTest {
        val host = RecordingHost()
        val coordinator = AssistantActionQueueCoordinator(host)

        coordinator.processBatch(
            "conv-1",
            "msg-1",
            listOf(TrackedChatAction("a1", action1()), TrackedChatAction("a2", action2()))
        )
        assertTrue(coordinator.pendingApproval.value is ChatAction.ToggleMovieWatchlist)

        coordinator.resetAndCancelPending()

        assertNull(coordinator.pendingApproval.value)
        assertEquals(ChatActionStatus.DENIED, host.statusUpdates.last { it.actionId == "a1" }.status)
        assertEquals(ChatActionStatus.DENIED, host.statusUpdates.last { it.actionId == "a2" }.status)
        assertEquals(AssistantActionQueueCoordinator.ABANDON_STATUS_DETAIL, host.statusUpdates.last { it.actionId == "a1" }.detail)
    }

    @Test
    fun executeItem_deduplicatesConcurrentDuplicateExecution() = runTest {
        val host = RecordingHost().apply {
            allowedPermissions.add("LIBRARY_MANAGEMENT")
            executionDelayMs = 100L
        }
        val coordinator = AssistantActionQueueCoordinator(host)

        val job1 = async { coordinator.enqueueSingle(action1(), "conv-1") }
        val job2 = async { coordinator.enqueueSingle(action1(), "conv-1") }
        job1.await()
        job2.await()

        assertEquals(1, host.executedActions.size)
    }

    @Test
    fun processBatch_conversationSwitchDuringValidation_alwaysAllowTrue_deniesAllWithoutExecuting() = runTest {
        val host = RecordingHost().apply {
            allowedPermissions.add("LIBRARY_MANAGEMENT")
        }
        val coordinator = AssistantActionQueueCoordinator(host)
        host.onValidate = {
            host.currentConversationId = "conv-2"
            coordinator.resetAndCancelPending()
        }

        coordinator.processBatch(
            "conv-1",
            "msg-race-auto",
            listOf(TrackedChatAction("a1", action1()), TrackedChatAction("a2", action2()))
        )

        assertNull(coordinator.pendingApproval.value)
        assertTrue(host.executedActions.isEmpty())
        assertTrue(host.receipts.isEmpty())
        val a1 = host.statusUpdates.last { it.actionId == "a1" }
        val a2 = host.statusUpdates.last { it.actionId == "a2" }
        assertEquals(ChatActionStatus.DENIED, a1.status)
        assertEquals(AssistantActionQueueCoordinator.CANCELLED_CONVERSATION_CHANGE_DETAIL, a1.detail)
        assertEquals(ChatActionStatus.DENIED, a2.status)
        assertEquals(AssistantActionQueueCoordinator.CANCELLED_CONVERSATION_CHANGE_DETAIL, a2.detail)
    }

    @Test
    fun processBatch_conversationSwitchDuringValidation_alwaysAllowFalse_deniesAllWithoutPresenting() = runTest {
        val host = RecordingHost()
        val coordinator = AssistantActionQueueCoordinator(host)
        host.onValidate = {
            host.currentConversationId = "conv-2"
            coordinator.resetAndCancelPending()
        }

        coordinator.processBatch(
            "conv-1",
            "msg-race-prompt",
            listOf(TrackedChatAction("a1", action1()), TrackedChatAction("a2", action2()))
        )

        assertNull(coordinator.pendingApproval.value)
        assertTrue(host.executedActions.isEmpty())
        assertTrue(host.receipts.isEmpty())
        val a1 = host.statusUpdates.last { it.actionId == "a1" }
        val a2 = host.statusUpdates.last { it.actionId == "a2" }
        assertEquals(ChatActionStatus.DENIED, a1.status)
        assertEquals(AssistantActionQueueCoordinator.CANCELLED_CONVERSATION_CHANGE_DETAIL, a1.detail)
        assertEquals(ChatActionStatus.DENIED, a2.status)
        assertEquals(AssistantActionQueueCoordinator.CANCELLED_CONVERSATION_CHANGE_DETAIL, a2.detail)
    }

    @Test
    fun processBatch_conversationSwitchDuringValidationWithError_marksDeniedNotFailed() = runTest {
        val host = RecordingHost().apply {
            validationErrors["TOGGLE_MOVIE_WATCHLIST"] = "not found"
        }
        val coordinator = AssistantActionQueueCoordinator(host)
        host.onValidate = {
            host.currentConversationId = "conv-2"
        }

        coordinator.processBatch(
            "conv-1",
            "msg-race-err",
            listOf(TrackedChatAction("a1", action1()), TrackedChatAction("a2", action2()))
        )

        assertNull(coordinator.pendingApproval.value)
        assertTrue(host.executedActions.isEmpty())
        assertTrue(host.receipts.isEmpty())
        assertEquals(ChatActionStatus.DENIED, host.statusUpdates.last { it.actionId == "a1" }.status)
        assertEquals(ChatActionStatus.DENIED, host.statusUpdates.last { it.actionId == "a2" }.status)
    }

    @Test
    fun enqueueSingle_conversationSwitchDuringValidation_cancelsForBothAlwaysAllowTrueAndFalse() = runTest {
        for (alwaysAllow in listOf(true, false)) {
            val host = RecordingHost().apply {
                if (alwaysAllow) allowedPermissions.add("LIBRARY_MANAGEMENT")
            }
            val coordinator = AssistantActionQueueCoordinator(host)
            host.onValidate = {
                host.currentConversationId = "conv-2"
                coordinator.resetAndCancelPending()
            }

            coordinator.enqueueSingle(action1(), "conv-1", "msg-single", "act-1")

            assertNull(coordinator.pendingApproval.value)
            assertTrue(host.executedActions.isEmpty())
            assertTrue(host.receipts.isEmpty())
            val status = host.statusUpdates.last { it.actionId == "act-1" }
            assertEquals(ChatActionStatus.DENIED, status.status)
            assertEquals(AssistantActionQueueCoordinator.CANCELLED_CONVERSATION_CHANGE_DETAIL, status.detail)
        }
    }

    @Test
    fun processBatch_cancellationRightAfterExecution_stillPersistsExecutedStatusAndReceipt() = runTest {
        val host = RecordingHost().apply {
            allowedPermissions.add("LIBRARY_MANAGEMENT")
            statusUpdateDelayMs = 10L
        }
        val coordinator = AssistantActionQueueCoordinator(host)
        var jobToCancel: kotlinx.coroutines.Job? = null
        host.onAfterExecute = {
            jobToCancel?.cancel()
        }

        val job = launch {
            coordinator.processBatch(
                "conv-1",
                "msg-noncancel",
                listOf(TrackedChatAction("a1", action1()))
            )
        }
        jobToCancel = job
        job.join()

        assertEquals(1, host.executedActions.size)
        assertEquals(ChatActionStatus.EXECUTED, host.statusUpdates.last { it.actionId == "a1" }.status)
        assertEquals(1, host.outcomeReports.size)
        assertEquals(1, host.receipts.size)
    }
}
