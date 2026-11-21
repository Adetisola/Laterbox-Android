package com.laterbox.app.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaterboxAnalyticsTrackerTest {

    @Test
    fun `mapRouteToScreenId maps only allowlisted app sections cleanly`() {
        assertEquals("inbox", LaterboxAnalyticsSchema.mapRouteToScreenId("inbox"))
        assertEquals("movies", LaterboxAnalyticsSchema.mapRouteToScreenId("movies"))
        assertEquals("movies", LaterboxAnalyticsSchema.mapRouteToScreenId("movies/catalogue"))
        assertEquals("movie_detail", LaterboxAnalyticsSchema.mapRouteToScreenId("movies/detail/moviebox/123"))
        assertEquals("chat", LaterboxAnalyticsSchema.mapRouteToScreenId("chat?initialPrompt=hi"))
        assertEquals("settings", LaterboxAnalyticsSchema.mapRouteToScreenId("settings/reminders_ai"))
        assertEquals("collection_detail", LaterboxAnalyticsSchema.mapRouteToScreenId("collection/1/Work"))
        assertNull(LaterboxAnalyticsSchema.mapRouteToScreenId("unknown_route"))
    }

    @Test
    fun `AnalyticsEventRecord serialization enforces allowlists and preserves offline replay flag`() {
        val valid = AnalyticsEventRecord(
            eventId = "ev_12345678",
            eventType = "screen_viewed",
            installId = "inst_12345678",
            sessionId = "sess_12345678",
            versionCode = 62,
            versionName = "1.0.62",
            manifestRevision = "2026.11.21-v2",
            screenId = "movies",
            timestampMs = 1760000000000L,
            isOfflineReplay = false
        )
        val replayedJson = valid.toJson(overrideOfflineReplay = true)
        assertTrue(replayedJson.getBoolean("isOfflineReplay"))
        assertFalse(replayedJson.has("title"))
        assertFalse(replayedJson.has("query"))

        val parsed = AnalyticsEventRecord.fromJson(replayedJson)
        assertNotNull(parsed)
        assertEquals("movies", parsed?.screenId)
        assertTrue(parsed?.isOfflineReplay == true)

        // Reject unallowlisted screenId
        val badJson = replayedJson.put("screenId", "secret_user_title")
        assertNull(AnalyticsEventRecord.fromJson(badJson))
    }
}
