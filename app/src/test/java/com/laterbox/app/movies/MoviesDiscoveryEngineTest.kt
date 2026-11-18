package com.laterbox.app.movies

import com.laterbox.app.movies.domain.engine.MoviesDiscoveryEngine
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.RuntimeBucket
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoviesDiscoveryEngineTest {

    private fun sampleTitle(
        id: String,
        title: String,
        kind: MediaKind = MediaKind.MOVIE,
        genres: List<String> = listOf("Sci-Fi"),
        rating: Double? = 7.5,
        durationMinutes: Int? = 110,
        language: String? = "English",
        country: String? = "United States",
        year: Int? = 2023,
        releaseDate: String? = null,
        addedAtMs: Long? = null
    ) = MovieTitleSummary(
        id = id,
        providerId = "moviebox",
        kind = kind,
        title = title,
        releaseYear = year,
        rating = rating,
        durationMinutes = durationMinutes,
        genres = genres,
        language = language,
        country = country,
        releaseDate = releaseDate,
        addedAtMs = addedAtMs,
        isPlayableHint = true
    )

    @Test
    fun buildBecauseYouWatchedGroups_matchesGenresAndExcludesWatchedAndHidden() {
        val anchorFilm = sampleTitle("1", "Interstellar", genres = listOf("Sci-Fi", "Drama"))
        val sciFiRec = sampleTitle("2", "Arrival", genres = listOf("Sci-Fi", "Drama"), rating = 8.0)
        val hiddenSciFi = sampleTitle("3", "Hidden Mars", genres = listOf("Sci-Fi"), rating = 9.0)
        val comedyFilm = sampleTitle("4", "Superbad", genres = listOf("Comedy"), rating = 7.6)

        val history = listOf(
            WatchHistoryItem(
                compositeId = "moviebox:1",
                providerId = "moviebox",
                titleId = "1",
                kind = MediaKind.MOVIE,
                title = "Interstellar",
                positionMs = 600_000L,
                durationMs = 600_000L,
                lastWatchedAt = 5_000L,
                isCompleted = true
            )
        )

        val groups = MoviesDiscoveryEngine.buildBecauseYouWatchedGroups(
            watchHistory = history,
            watchlist = listOf(anchorFilm),
            candidatePool = listOf(anchorFilm, sciFiRec, hiddenSciFi, comedyFilm),
            hiddenMediaKeys = setOf("moviebox:3")
        )

        assertEquals(1, groups.size)
        assertEquals("Interstellar", groups[0].anchorTitle)
        assertEquals(listOf("2"), groups[0].items.map { it.id })
    }

    @Test
    fun pickSurpriseMe_respectsHiddenTitlesAndProducesGroundedRationale() {
        val sciFiFav = sampleTitle("10", "Ex Machina", genres = listOf("Sci-Fi", "Thriller"), rating = 7.7)
        val candidateSciFi = sampleTitle("11", "Blade Runner 2049", genres = listOf("Sci-Fi", "Drama"), rating = 8.0)
        val hiddenTitle = sampleTitle("12", "Secret Pick", genres = listOf("Sci-Fi"), rating = 9.5)

        val history = listOf(
            WatchHistoryItem(
                compositeId = "moviebox:10",
                providerId = "moviebox",
                titleId = "10",
                kind = MediaKind.MOVIE,
                title = "Ex Machina",
                positionMs = 500L,
                durationMs = 500L,
                lastWatchedAt = 1000L,
                isCompleted = true
            )
        )

        val result = MoviesDiscoveryEngine.pickSurpriseMe(
            watchHistory = history,
            recentlyViewed = emptyList(),
            hiddenMediaKeys = setOf("moviebox:12"),
            watchlist = listOf(sciFiFav),
            candidatePool = listOf(sciFiFav, candidateSciFi, hiddenTitle),
            seed = 42L
        )

        assertNotNull(result)
        assertEquals("11", result!!.pick.id)
        assertTrue(result.rationale.contains("Sci-Fi"))
    }

    @Test
    fun filterTitles_handlesLanguageCountryYearDecadeAndRuntimeBucket() {
        val quickFilm = sampleTitle("1", "Quick Short", durationMinutes = 82, language = "Japanese", country = "Japan", year = 2024)
        val epicKoreanSeries = sampleTitle("2", "Seoul Saga", kind = MediaKind.TV_SERIES, genres = listOf("Drama"), durationMinutes = 155, language = "Korean", country = "Korea", year = 2018)
        val classicFilm = sampleTitle("3", "Old Noir", durationMinutes = 98, language = "English", country = "United States", year = 1955)

        val pool = listOf(quickFilm, epicKoreanSeries, classicFilm)

        assertEquals(
            listOf("1"),
            MoviesDiscoveryEngine.filterTitles(pool, runtimeBucket = RuntimeBucket.QUICK, language = "Japanese").map { it.id }
        )
        assertEquals(
            listOf("2"),
            MoviesDiscoveryEngine.filterTitles(pool, kind = MediaKind.TV_SERIES, country = "South Korea", year = "2010s").map { it.id }
        )
        assertEquals(
            listOf("3"),
            MoviesDiscoveryEngine.filterTitles(pool, year = "Classic", runtimeBucket = RuntimeBucket.FEATURE).map { it.id }
        )
    }

    @Test
    fun selectRecentlyAdded_onlyIncludesItemsWithRealDates() {
        val noDate = sampleTitle("1", "Undated Title", releaseDate = null, addedAtMs = null)
        val olderDate = sampleTitle("2", "Older Release", releaseDate = "2023-05-01")
        val newerDate = sampleTitle("3", "Newer Release", releaseDate = "2025-02-14")
        val explicitAdded = sampleTitle("4", "Recently Added Item", releaseDate = null, addedAtMs = 1_800_000_000_000L)

        val recent = MoviesDiscoveryEngine.selectRecentlyAdded(listOf(noDate, olderDate, newerDate, explicitAdded))
        assertFalse(recent.any { it.id == "1" })
        assertEquals(listOf("4", "3", "2"), recent.map { it.id })
        assertEquals(0L, MoviesDiscoveryEngine.parseSortableDateKey(null))
        assertNull(MoviesDiscoveryEngine.pickSurpriseMe(emptyList(), emptyList(), emptySet(), emptyList(), emptyList())?.pick)
    }
}
