package com.laterbox.app.ai

import com.laterbox.app.ui.components.normalizeStreamingMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPermissionAndMarkdownTest {

    @Test
    fun `action permission categories and irreversible destructive guards are enforced`() {
        val navAction = ChatAction.OpenMoviesHome
        assertEquals(AssistantPermissionCategory.NAVIGATION_DISCOVERY, navAction.permissionCategory)
        assertTrue(navAction.permissionCategory.requiresApprovalByDefault)
        assertFalse(navAction.isIrreversibleDestructive)

        val playAction = ChatAction.OpenMoviePlayer("moviebox", "123", title = "Film")
        assertEquals(AssistantPermissionCategory.PLAYBACK_CONTROL, playAction.permissionCategory)
        assertTrue(playAction.permissionCategory.requiresApprovalByDefault)
        assertFalse(playAction.isIrreversibleDestructive)

        val watchlistAction = ChatAction.ToggleMovieWatchlist("moviebox", "123", add = true, title = "Film")
        assertEquals(AssistantPermissionCategory.LIBRARY_MANAGEMENT, watchlistAction.permissionCategory)
        assertTrue(watchlistAction.permissionCategory.requiresApprovalByDefault)
        assertFalse(watchlistAction.isIrreversibleDestructive)

        val reminderAction = ChatAction.ScheduleMovieReminder("moviebox", "123", 1900000000000L, title = "Film")
        assertEquals(AssistantPermissionCategory.REMINDER_MANAGEMENT, reminderAction.permissionCategory)
        assertTrue(reminderAction.permissionCategory.requiresApprovalByDefault)
        assertFalse(reminderAction.isIrreversibleDestructive)

        val renameAction = ChatAction.RenameConversation(title = "Weekend Picks")
        assertEquals(AssistantPermissionCategory.CONVERSATION_MANAGEMENT, renameAction.permissionCategory)
        assertTrue(renameAction.permissionCategory.requiresApprovalByDefault)
        assertFalse(renameAction.isIrreversibleDestructive)

        val deleteConvAction = ChatAction.DeleteConversation(conversationId = "c1", title = "Weekend Picks")
        assertEquals(AssistantPermissionCategory.CONVERSATION_MANAGEMENT, deleteConvAction.permissionCategory)
        assertTrue(deleteConvAction.isIrreversibleDestructive)

        val deleteDownloadAction = ChatAction.DeleteDownload(assetId = "a1", title = "Sample.mp4")
        assertEquals(AssistantPermissionCategory.LIBRARY_MANAGEMENT, deleteDownloadAction.permissionCategory)
        assertTrue(deleteDownloadAction.isIrreversibleDestructive)
    }

    @Test
    fun `parser and serializer round-trip new conversation and download actions cleanly`() {
        val raw = """
            Done!
            [ACTIONS]
            [
              {"type":"RENAME_CONVERSATION","title":"Sci-Fi List"},
              {"type":"DELETE_CONVERSATION","conversationId":"conv_9","title":"Old chat"},
              {"type":"DELETE_DOWNLOAD","assetId":"asset_7","title":"clip.mp4"}
            ]
            [/ACTIONS]
        """.trimIndent()
        val parsed = AssistantActionHandler.parseModelResponse(raw)
        assertEquals("Done!", parsed.displayText)
        assertEquals(3, parsed.actions.size)
        assertTrue(parsed.actions[0] is ChatAction.RenameConversation)
        assertTrue(parsed.actions[1] is ChatAction.DeleteConversation)
        assertTrue(parsed.actions[2] is ChatAction.DeleteDownload)

        val roundTripped = AssistantActionHandler.fromJsonList(AssistantActionHandler.toJsonList(parsed.actions))
        assertEquals(3, roundTripped.size)
        assertEquals("Sci-Fi List", (roundTripped[0] as ChatAction.RenameConversation).title)
        assertEquals("conv_9", (roundTripped[1] as ChatAction.DeleteConversation).conversationId)
        assertEquals("asset_7", (roundTripped[2] as ChatAction.DeleteDownload).assetId)
    }

    @Test
    fun `normalizeStreamingMarkdown closes open code fences while streaming only`() {
        val partialFence = "Here is code:\n```kotlin\nval x = 1"
        val normalizedStreaming = normalizeStreamingMarkdown(partialFence, isStreaming = true)
        assertTrue(normalizedStreaming.endsWith("\n```"))

        val closedFence = "Here is code:\n```kotlin\nval x = 1\n```"
        assertEquals(closedFence, normalizeStreamingMarkdown(closedFence, isStreaming = true))
        assertEquals(partialFence, normalizeStreamingMarkdown(partialFence, isStreaming = false))
    }

    @Test
    fun `canonical actionType returns wire name for all actions while destructive permissionKey stays null`() {
        val deleteDownload = ChatAction.DeleteDownload(taskId = "t-1", title = "Clip.mp4")
        assertEquals("DELETE_DOWNLOAD", deleteDownload.actionType)
        assertEquals(null, deleteDownload.permissionKey)
        assertTrue(deleteDownload.isIrreversibleDestructive)

        val deleteConversation = ChatAction.DeleteConversation(conversationId = "c-1", title = "Old chat")
        assertEquals("DELETE_CONVERSATION", deleteConversation.actionType)
        assertEquals(null, deleteConversation.permissionKey)
        assertTrue(deleteConversation.isIrreversibleDestructive)

        val allActions: List<ChatAction> = listOf(
            ChatAction.SearchLinks("kotlin"),
            ChatAction.OpenLinkDetail(1L, "Link"),
            ChatAction.OpenDownloads(0),
            ChatAction.OpenYouTubeTranscripts(),
            ChatAction.OpenCollections,
            ChatAction.OpenSettings("settings"),
            ChatAction.RetryInspection(1L, "Link"),
            ChatAction.RetryDownload("t1", "DL"),
            ChatAction.RefreshMetadata(1L, "Link"),
            ChatAction.OpenMoviesHome,
            ChatAction.SearchMovies("dune"),
            ChatAction.OpenMovieDetail("moviebox", "1", "Dune"),
            ChatAction.OpenMoviePlayer("moviebox", "1", title = "Dune"),
            ChatAction.ToggleMovieWatchlist("moviebox", "1", true, "Dune"),
            ChatAction.AddMovieToCollection("moviebox", "1", collectionName = "Sci-Fi", title = "Dune"),
            ChatAction.MarkMovieWatched("moviebox", "1", watched = true, title = "Dune"),
            ChatAction.ScheduleMovieReminder("moviebox", "1", 1900000000000L, title = "Dune"),
            ChatAction.MuteSeriesReminders("tvmaze", "1", true, "Show"),
            ChatAction.RenameConversation(title = "Renamed"),
            deleteConversation,
            deleteDownload
        )
        assertEquals(21, allActions.map { it.actionType }.toSet().size)
        for (act in allActions) {
            assertTrue(act.actionType.matches(Regex("[A-Z_]+")))
        }
    }

    @Test
    fun `schedule reminder action parses triggerAtIso and normalizes unix seconds to future millis`() {
        val futureIso = java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 7_200_000L).toString()
        val futureSeconds = (System.currentTimeMillis() / 1000L) + 3_600L
        val raw = """
            Got it, scheduling those reminders.
            [ACTIONS]
            [
              {"type":"SCHEDULE_MOVIE_REMINDER","providerId":"moviebox","titleId":"101","triggerAtMillis":0,"triggerAtIso":"$futureIso","title":"Dune"},
              {"type":"SCHEDULE_MOVIE_REMINDER","providerId":"moviebox","titleId":"102","triggerAtMillis":$futureSeconds,"title":"Andor"}
            ]
            [/ACTIONS]
        """.trimIndent()

        val parsed = AssistantActionHandler.parseModelResponse(raw)
        assertEquals(2, parsed.actions.size)
        val first = parsed.actions[0] as ChatAction.ScheduleMovieReminder
        val second = parsed.actions[1] as ChatAction.ScheduleMovieReminder
        assertTrue(first.triggerAtMillis > System.currentTimeMillis() + 3_600_000L)
        assertEquals(futureSeconds * 1000L, second.triggerAtMillis)
    }
}
