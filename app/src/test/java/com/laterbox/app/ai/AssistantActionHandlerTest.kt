package com.laterbox.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantActionHandlerTest {

    @Test
    fun parseModelResponse_stripsActionsBlockAndParsesAllowlistedActions() {
        val raw = """
            Here are your failed links. You can inspect them or open settings.
            [ACTIONS]
            [
              {"type":"SEARCH_LINKS","query":"kotlin"},
              {"type":"OPEN_LINK_DETAIL","linkId":42,"title":"Kotlin Video"},
              {"type":"OPEN_DOWNLOADS","initialTab":1},
              {"type":"OPEN_COLLECTIONS"}
            ]
            [/ACTIONS]
        """.trimIndent()

        val parsed = AssistantActionHandler.parseModelResponse(raw)
        assertEquals("Here are your failed links. You can inspect them or open settings.", parsed.displayText)
        assertEquals(4, parsed.actions.size)
        assertEquals(ChatAction.SearchLinks("kotlin"), parsed.actions[0])
        assertEquals(ChatAction.OpenLinkDetail(42L, "Kotlin Video"), parsed.actions[1])
        assertEquals(ChatAction.OpenDownloads(1), parsed.actions[2])
        assertEquals(ChatAction.OpenCollections, parsed.actions[3])
    }

    @Test
    fun parseModelResponse_rejectsUnknownActionsAndForcesConfirmationOnMutatingActions() {
        val raw = """
            Checking status.
            [ACTIONS]
            ```json
            [
              {"type":"DELETE_ALL_DATA"},
              {"type":"OPEN_SETTINGS","route":"settings/secret_admin"},
              {"type":"RETRY_INSPECTION","linkId":10,"title":"Broken Link","requiresConfirmation":false},
              {"type":"RETRY_DOWNLOAD","taskId":"task-9","title":"Broken Video","requiresConfirmation":false},
              {"type":"REFRESH_METADATA","linkId":11,"title":"Article","requiresConfirmation":false}
            ]
            ```
            [/ACTIONS]
        """.trimIndent()

        val parsed = AssistantActionHandler.parseModelResponse(raw)
        assertEquals("Checking status.", parsed.displayText)
        assertEquals(4, parsed.actions.size)
        assertEquals(ChatAction.OpenSettings("settings"), parsed.actions[0])

        val retryInsp = parsed.actions[1] as ChatAction.RetryInspection
        assertTrue(retryInsp.requiresConfirmation)
        assertEquals(10L, retryInsp.linkId)

        val retryDl = parsed.actions[2] as ChatAction.RetryDownload
        assertTrue(retryDl.requiresConfirmation)
        assertEquals("task-9", retryDl.taskId)

        val refresh = parsed.actions[3] as ChatAction.RefreshMetadata
        assertTrue(refresh.requiresConfirmation)
        assertEquals(11L, refresh.linkId)
    }

    @Test
    fun roundTripSerialization_preservesAllowlistedActions() {
        val original = listOf(
            ChatAction.OpenMoviesHome,
            ChatAction.OpenSettings("settings/reminders_ai"),
            ChatAction.RetryInspection(linkId = 7L, title = "Sample", requiresConfirmation = true)
        )
        val json = AssistantActionHandler.toJsonList(original)
        val restored = AssistantActionHandler.fromJsonList(json)
        assertEquals(original, restored)

        val deprecatedParsed = AssistantActionHandler.parseModelResponse(
            "Done.\n[ACTIONS]\n[{\"type\":\"RUN_HEALTH_CHECK\"},{\"type\":\"OPEN_COLLECTIONS\"}]\n[/ACTIONS]"
        )
        assertEquals(listOf(ChatAction.OpenCollections), deprecatedParsed.actions)
    }

    @Test
    fun fromJsonList_returnsEmptyOnMalformedJson() {
        assertEquals(emptyList<ChatAction>(), AssistantActionHandler.fromJsonList("{not valid json"))
        assertEquals(emptyList<ChatAction>(), AssistantActionHandler.fromJsonList(null))
    }

    @Test
    fun openYouTubeTranscripts_validatesYouTubeUrlIsReadOnlyAndRoundTrips() {
        val raw = """
            You can open the YouTube transcript extractor directly.
            [ACTIONS]
            [
              {"type":"OPEN_YOUTUBE_TRANSCRIPTS","url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","title":"Extract Talk Transcript"},
              {"type":"OPEN_YOUTUBE_TRANSCRIPTS","url":"https://evil.example/not-youtube","title":"Manual Entry"}
            ]
            [/ACTIONS]
        """.trimIndent()

        val parsed = AssistantActionHandler.parseModelResponse(raw)
        assertEquals(2, parsed.actions.size)
        val first = parsed.actions[0] as ChatAction.OpenYouTubeTranscripts
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", first.url)
        assertEquals("Extract Talk Transcript", first.labelTitle)
        assertEquals(ActionCategory.READ_ONLY, first.category)

        val second = parsed.actions[1] as ChatAction.OpenYouTubeTranscripts
        assertEquals(null, second.url)
        assertEquals("Manual Entry", second.labelTitle)

        val roundTripped = AssistantActionHandler.fromJsonList(AssistantActionHandler.toJsonList(parsed.actions))
        assertEquals(parsed.actions, roundTripped)
    }

    @Test
    fun moviesActions_parseValidationEnforcesConfirmationAndRoundTrips() {
        val raw = """
            Here are your Movies & TV actions.
            [ACTIONS]
            [
              {"type":"SEARCH_MOVIES","query":"dune","kind":"movie","genre":"Sci-Fi"},
              {"type":"OPEN_MOVIE_DETAIL","providerId":"moviebox","titleId":"12345","title":"Dune"},
              {"type":"TOGGLE_MOVIE_WATCHLIST","providerId":"moviebox","titleId":"12345","add":true,"title":"Dune","requiresConfirmation":false},
              {"type":"SCHEDULE_MOVIE_REMINDER","providerId":"tvmaze","titleId":"66","triggerAtMillis":1800000000000,"note":"Episode 3 tonight","title":"Severance","requiresConfirmation":false}
            ]
            [/ACTIONS]
        """.trimIndent()

        val parsed = AssistantActionHandler.parseModelResponse(raw)
        assertEquals(4, parsed.actions.size)
        val search = parsed.actions[0] as ChatAction.SearchMovies
        assertEquals("dune", search.query)
        assertEquals("movie", search.kind)
        assertEquals("Sci-Fi", search.genre)
        assertEquals(ActionCategory.READ_ONLY, search.category)

        val detail = parsed.actions[1] as ChatAction.OpenMovieDetail
        assertEquals("moviebox", detail.providerId)
        assertEquals(ActionCategory.READ_ONLY, detail.category)

        val watchlist = parsed.actions[2] as ChatAction.ToggleMovieWatchlist
        assertEquals(ActionCategory.STATE_CHANGING, watchlist.category)
        assertTrue(watchlist.requiresConfirmation)
        assertEquals("TOGGLE_MOVIE_WATCHLIST", watchlist.permissionKey)

        val reminder = parsed.actions[3] as ChatAction.ScheduleMovieReminder
        assertEquals(ActionCategory.STATE_CHANGING, reminder.category)
        assertTrue(reminder.requiresConfirmation)
        assertEquals("SCHEDULE_MOVIE_REMINDER", reminder.permissionKey)

        val roundTripped = AssistantActionHandler.fromJsonList(AssistantActionHandler.toJsonList(parsed.actions))
        assertEquals(parsed.actions, roundTripped)
    }
}
