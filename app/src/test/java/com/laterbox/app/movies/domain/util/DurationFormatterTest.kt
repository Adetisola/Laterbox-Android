package com.laterbox.app.movies.domain.util

import com.laterbox.app.movies.domain.model.EpisodeProgressState
import com.laterbox.app.movies.ui.computeEpisodePartialProgressFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DurationFormatterTest {

    @Test
    fun formatMinutes_usesHumanReadableGrammar() {
        assertEquals("1min", DurationFormatter.formatMinutes(1))
        assertEquals("30mins", DurationFormatter.formatMinutes(30))
        assertEquals("1hr", DurationFormatter.formatMinutes(60))
        assertEquals("1hr 1min", DurationFormatter.formatMinutes(61))
        assertEquals("1hr 30mins", DurationFormatter.formatMinutes(90))
        assertEquals("2hrs", DurationFormatter.formatMinutes(120))
        assertEquals("2hrs 30mins", DurationFormatter.formatMinutes(150))
    }

    @Test
    fun formatMinutes_handlesMissingOrInvalidValuesGracefully() {
        assertNull(DurationFormatter.formatMinutes(null))
        assertNull(DurationFormatter.formatMinutes(0))
        assertNull(DurationFormatter.formatMinutes(-15))
    }

    @Test
    fun computeEpisodePartialProgressFraction_showsOnlyWhenPartiallyWatchedWithValidDuration() {
        // Never started -> null
        assertNull(computeEpisodePartialProgressFraction(null, isWatched = false))
        assertNull(
            computeEpisodePartialProgressFraction(
                EpisodeProgressState(1, 1, isCompleted = false, positionMs = 0L, durationMs = 2_400_000L),
                isWatched = false
            )
        )

        // Unknown duration -> null (never invent percentage)
        assertNull(
            computeEpisodePartialProgressFraction(
                EpisodeProgressState(1, 2, isCompleted = false, positionMs = 600_000L, durationMs = 0L),
                isWatched = false
            )
        )

        // Completed / watched -> null
        assertNull(
            computeEpisodePartialProgressFraction(
                EpisodeProgressState(1, 3, isCompleted = true, positionMs = 2_400_000L, durationMs = 2_400_000L),
                isWatched = true
            )
        )
        assertNull(
            computeEpisodePartialProgressFraction(
                EpisodeProgressState(1, 4, isCompleted = false, positionMs = 1_200_000L, durationMs = 2_400_000L),
                isWatched = true
            )
        )

        // Partially watched -> accurate progress fraction
        val half = computeEpisodePartialProgressFraction(
            EpisodeProgressState(1, 5, isCompleted = false, positionMs = 1_200_000L, durationMs = 2_400_000L),
            isWatched = false
        )
        assertNotNull(half)
        assertEquals(0.5f, half!!, 0.001f)
    }
}
