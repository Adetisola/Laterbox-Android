package com.laterbox.app.movies

import com.laterbox.app.data.local.entity.IntelligenceSignalEntity
import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.movies.domain.engine.IntelligenceDiscoveryProfile
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

    @Test
    fun deduplicator_prefersMovieBoxAcrossStrongMatchesWithoutCollapsingUnrelatedSameTitles() {
        val tvMazeMatch = MovieTitleSummary(
            id = "169",
            providerId = "tvmaze",
            kind = MediaKind.TV_SERIES,
            title = "Breaking Bad",
            releaseYear = 2008,
            imdbId = "tt0903747",
            tvmazeId = "169",
            isPlayableHint = false
        )
        val movieBoxMatch = MovieTitleSummary(
            id = "mb_bb",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Breaking Bad",
            releaseYear = 2008,
            imdbId = "tt0903747",
            isPlayableHint = true
        )
        val unrelatedSameName = MovieTitleSummary(
            id = "ia_bb_doc",
            providerId = "ia",
            kind = MediaKind.MOVIE,
            title = "Breaking Bad",
            releaseYear = 1995,
            isPlayableHint = true
        )

        // Even when TVMaze appears first (e.g., watchlist before catalogue), MovieBox replaces it in-place
        val deduped = com.laterbox.app.movies.domain.engine.MovieBoxPriorityDeduplicator
            .deduplicateSummariesPreferringMovieBox(listOf(tvMazeMatch, movieBoxMatch, unrelatedSameName))

        assertEquals(2, deduped.size)
        assertEquals("moviebox", deduped[0].providerId)
        assertEquals("mb_bb", deduped[0].id)
        assertEquals("169", deduped[0].tvmazeId)
        assertEquals("ia_bb_doc", deduped[1].id)

        // Surprise Me picks the MovieBox item instead of the duplicate TVMaze entry
        val surprise = MoviesDiscoveryEngine.pickSurpriseMe(
            watchHistory = emptyList(),
            recentlyViewed = emptyList(),
            hiddenMediaKeys = emptySet(),
            watchlist = listOf(tvMazeMatch),
            candidatePool = listOf(movieBoxMatch),
            seed = 7L,
        )
        assertNotNull(surprise)
        assertEquals("moviebox", surprise!!.pick.providerId)
        assertEquals("mb_bb", surprise.pick.id)
    }

    private fun testFacet(
        key: String,
        label: String,
        type: TasteFacetType,
        score: Double = 0.85,
        scope: IntelligenceSurfaceScope = IntelligenceSurfaceScope.MOVIES,
        suppressed: Boolean = false
    ) = UserTasteFacetEntity(
        facetKey = key,
        facetType = type.name,
        surfaceScope = scope.name,
        displayLabel = label,
        score = score,
        confidence = 0.9,
        provenance = "Test evidence",
        evidenceSummary = "Test summary",
        includeInAssistantContext = false,
        isSuppressedByUser = suppressed,
        firstObservedAtMs = 1000L,
        lastReinforcedAtMs = 2000L,
        expiresAtMs = null
    )

    @Test
    fun buildPersonalizedForYouRail_demotesDislikesAppliesMmrDiversityAndExplainsWhy() {
        val sciFi1 = sampleTitle("sf1", "Orbital Drift", genres = listOf("Sci-Fi"), rating = 8.4)
        val sciFi2 = sampleTitle("sf2", "Quantum Echo", genres = listOf("Sci-Fi"), rating = 8.3)
        val sciFi3 = sampleTitle("sf3", "Nebula Gate", genres = listOf("Sci-Fi"), rating = 8.2)
        val doc1 = sampleTitle("doc1", "Deep Ocean", genres = listOf("Documentary"), rating = 8.0)
        val horrorDisliked = sampleTitle("hor1", "Midnight Haunt", genres = listOf("Horror"), rating = 9.5)

        val profile = IntelligenceDiscoveryProfile(
            enabled = true,
            facets = listOf(
                testFacet("genre:sci_fi", "Sci-Fi", TasteFacetType.EXPLICIT_LIKE, score = 0.95),
                testFacet("genre:documentary", "Documentary", TasteFacetType.ENDURING_INTEREST, score = 0.80),
                testFacet("genre:horror", "Horror", TasteFacetType.EXPLICIT_DISLIKE, score = 0.90)
            )
        )

        val picks = MoviesDiscoveryEngine.buildPersonalizedForYouRail(
            watchHistory = emptyList(),
            watchlist = emptyList(),
            candidatePool = listOf(horrorDisliked, sciFi1, sciFi2, sciFi3, doc1),
            profile = profile,
            maxItems = 3
        )

        // Disliked genre excluded despite high rating
        assertFalse(picks.any { it.item.id == "hor1" })
        // Top pick matches pinned Sci-Fi with explicit rationale
        assertEquals("sf1", picks.first().item.id)
        assertTrue(picks.first().rationale.contains("Sci-Fi"))
        // MMR promotes Documentary alongside Sci-Fi within top 3 instead of 3 pure Sci-Fi duplicates
        assertTrue(picks.any { it.item.id == "doc1" })
    }

    @Test
    fun personalizedDiscovery_usesMediaOnlyInboxCuesAndIgnoresGeneralArticleTopics() {
        val thriller = sampleTitle("thr1", "Zero Signal", genres = listOf("Thriller"), rating = 7.6)
        val sciFi = sampleTitle("sf1", "Starlight", genres = listOf("Sci-Fi"), rating = 7.6)

        val explicitTrailerSignal = IntelligenceSignalEntity(
            id = 1L,
            signalType = "LINK_SAVE",
            surfaceScope = IntelligenceSurfaceScope.ALL.name,
            targetKey = "link:10",
            topicsCsv = "topic:cinema_tv,genre:sci_fi,kind:movie",
            isMediaCue = true,
            strength = 0.85,
            createdAtMs = 5000L
        )
        val generalArticleNoise = IntelligenceSignalEntity(
            id = 2L,
            signalType = "LINK_SAVE",
            surfaceScope = IntelligenceSurfaceScope.LIBRARY.name,
            targetKey = "link:11",
            topicsCsv = "genre:thriller,topic:technology",
            isMediaCue = false, // General web article, MUST NOT cross over to Movies!
            strength = 0.95,
            createdAtMs = 5500L
        )

        val picks = MoviesDiscoveryEngine.buildPersonalizedForYouRail(
            watchHistory = emptyList(),
            watchlist = emptyList(),
            candidatePool = listOf(thriller, sciFi),
            profile = IntelligenceDiscoveryProfile(
                enabled = true,
                facets = emptyList(),
                recentMediaCues = listOf(explicitTrailerSignal, generalArticleNoise)
            )
        )

        assertEquals(listOf("sf1"), picks.map { it.item.id })
        assertTrue(picks.first().rationale.contains("saved media links"))
    }

    @Test
    fun personalizedDiscovery_sessionExposureCooldownRotatesRepeatedCardsAndPauseDisablesRail() {
        val first = sampleTitle("a1", "Alpha", genres = listOf("Sci-Fi"), rating = 8.0)
        val second = sampleTitle("a2", "Beta", genres = listOf("Sci-Fi"), rating = 7.9)

        val facet = testFacet("genre:sci_fi", "Sci-Fi", TasteFacetType.ENDURING_INTEREST, score = 0.9)

        val rotated = MoviesDiscoveryEngine.buildPersonalizedForYouRail(
            watchHistory = emptyList(),
            watchlist = emptyList(),
            candidatePool = listOf(first, second),
            profile = IntelligenceDiscoveryProfile(
                enabled = true,
                facets = listOf(facet),
                sessionExposureCounts = mapOf(first.stableMediaKey to 3)
            )
        )
        assertEquals("a2", rotated.first().item.id)

        val paused = MoviesDiscoveryEngine.buildPersonalizedForYouRail(
            watchHistory = emptyList(),
            watchlist = emptyList(),
            candidatePool = listOf(first, second),
            profile = IntelligenceDiscoveryProfile(enabled = false, facets = listOf(facet))
        )
        assertTrue(paused.isEmpty())
    }
}
