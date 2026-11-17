package com.laterbox.app.movies

import com.laterbox.app.movies.data.resolver.MatchCandidate
import com.laterbox.app.movies.data.resolver.MatchConfidence
import com.laterbox.app.movies.data.resolver.MediaMatcher
import com.laterbox.app.movies.domain.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMatcherTest {

    @Test
    fun evaluate_tier1MatchesOnSharedExternalIds() {
        val mb = MatchCandidate(
            providerId = "moviebox",
            titleId = "1001",
            kind = MediaKind.TV_SERIES,
            title = "Severance",
            releaseYear = 2022,
            imdbId = "TT11280740"
        )
        val tvmaze = MatchCandidate(
            providerId = "tvmaze",
            titleId = "44933",
            kind = MediaKind.TV_SERIES,
            title = "Severance (US)",
            releaseYear = 2022,
            imdbId = "tt11280740",
            tvmazeId = "44933"
        )
        val res = MediaMatcher.evaluate(mb, tvmaze)
        assertTrue(res.isMatch)
        assertEquals(MatchConfidence.T1, res.confidence)

        val mbTvIdMatch = MatchCandidate(
            providerId = "moviebox",
            titleId = "1002",
            kind = MediaKind.TV_SERIES,
            title = "Different Localization Title",
            tvmazeId = "44933"
        )
        val resTv = MediaMatcher.evaluate(mbTvIdMatch, tvmaze)
        assertTrue(resTv.isMatch)
        assertEquals(MatchConfidence.T1, resTv.confidence)
    }

    @Test
    fun evaluate_tier2MatchesOnSameKindNormTitleYearWithin1AndCorroboratingField() {
        val a = MatchCandidate(
            providerId = "moviebox",
            titleId = "2001",
            kind = MediaKind.MOVIE,
            title = "His Girl Friday!",
            releaseYear = 1940,
            durationMinutes = 92
        )
        val b = MatchCandidate(
            providerId = "ia",
            titleId = "his_girl_friday",
            kind = MediaKind.MOVIE,
            title = "His Girl Friday",
            releaseYear = 1941, // ±1 tolerance
            durationMinutes = 90 // ±10 tolerance
        )
        val res = MediaMatcher.evaluate(a, b)
        assertTrue(res.isMatch)
        assertEquals(MatchConfidence.T2, res.confidence)

        val seriesA = MatchCandidate(
            providerId = "moviebox",
            titleId = "s1",
            kind = MediaKind.TV_SERIES,
            title = "Dark",
            releaseYear = 2017,
            seasonCount = 3
        )
        val seriesB = MatchCandidate(
            providerId = "tvmaze",
            titleId = "17861",
            kind = MediaKind.TV_SERIES,
            title = "Dark",
            releaseYear = 2017,
            seasonCount = 3
        )
        val resSeries = MediaMatcher.evaluate(seriesA, seriesB)
        assertTrue(resSeries.isMatch)
        assertEquals(MatchConfidence.T2, resSeries.confidence)
    }

    @Test
    fun evaluate_tier3RejectsTitleOnlyAndUnCorroboratedMatchesAndKindMismatches() {
        // Title-only match (missing year & corroborating fields) MUST return T3 false
        val titleOnlyA = MatchCandidate(
            providerId = "moviebox",
            titleId = "m1",
            kind = MediaKind.MOVIE,
            title = "The Flash"
        )
        val titleOnlyB = MatchCandidate(
            providerId = "ia",
            titleId = "m2",
            kind = MediaKind.MOVIE,
            title = "The Flash"
        )
        val resTitleOnly = MediaMatcher.evaluate(titleOnlyA, titleOnlyB)
        assertFalse(resTitleOnly.isMatch)
        assertEquals(MatchConfidence.T3, resTitleOnly.confidence)

        // Title + exact year WITHOUT runtime or season corroboration MUST return T3 false
        val yearOnlyA = titleOnlyA.copy(releaseYear = 2023)
        val yearOnlyB = titleOnlyB.copy(releaseYear = 2023)
        val resYearOnly = MediaMatcher.evaluate(yearOnlyA, yearOnlyB)
        assertFalse(resYearOnly.isMatch)
        assertEquals(MatchConfidence.T3, resYearOnly.confidence)

        // Kind mismatch MUST fail even with matching year & runtime
        val movieNamed = MatchCandidate(providerId = "moviebox", titleId = "x", kind = MediaKind.MOVIE, title = "Fargo", releaseYear = 1996, durationMinutes = 98)
        val seriesNamed = MatchCandidate(providerId = "tvmaze", titleId = "y", kind = MediaKind.TV_SERIES, title = "Fargo", releaseYear = 1996, durationMinutes = 98)
        assertFalse(MediaMatcher.evaluate(movieNamed, seriesNamed).isMatch)
        assertEquals(MatchConfidence.T3, MediaMatcher.evaluate(movieNamed, seriesNamed).confidence)
    }
}
