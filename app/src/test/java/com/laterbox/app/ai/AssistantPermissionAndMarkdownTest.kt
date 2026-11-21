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
        assertFalse(navAction.permissionCategory.requiresApprovalByDefault)
        assertFalse(navAction.isIrreversibleDestructive)

        val playAction = ChatAction.OpenMoviePlayer("moviebox", "123", title = "Film")
        assertEquals(AssistantPermissionCategory.PLAYBACK_CONTROL, playAction.permissionCategory)
        assertFalse(playAction.permissionCategory.requiresApprovalByDefault)
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
}
