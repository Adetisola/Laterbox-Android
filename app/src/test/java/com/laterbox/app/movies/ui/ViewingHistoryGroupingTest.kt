package com.laterbox.app.movies.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.ui.theme.LaterboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewingHistoryGroupingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun movieItem(
        id: String,
        title: String,
        watchedAt: Long
    ) = WatchHistoryItem(
        compositeId = "tmdb:$id",
        providerId = "tmdb",
        titleId = id,
        kind = MediaKind.MOVIE,
        title = title,
        positionMs = 300_000L,
        durationMs = 600_000L,
        lastWatchedAt = watchedAt,
        historyKey = "tmdb:$id:0:0"
    )

    private fun trailerItem(
        id: String,
        title: String,
        watchedAt: Long
    ) = WatchHistoryItem(
        compositeId = "tmdb:$id",
        providerId = "tmdb",
        titleId = id,
        kind = MediaKind.TV_SERIES,
        title = title,
        positionMs = 15_000L,
        durationMs = 90_000L,
        lastWatchedAt = watchedAt,
        historyKey = "trailer:tmdb:$id",
        isTrailer = true
    )

    private fun episodeItem(
        seriesId: String,
        title: String,
        season: Int?,
        episode: Int?,
        watchedAt: Long,
        canonicalId: String? = null,
        posterUrl: String? = null,
        kind: MediaKind = MediaKind.TV_SERIES
    ) = WatchHistoryItem(
        compositeId = if (seriesId.isBlank()) "" else "moviebox:$seriesId",
        providerId = if (seriesId.isBlank()) "" else "moviebox",
        titleId = seriesId,
        kind = kind,
        title = title,
        episodeLabel = if (season != null && episode != null) "S$season · E$episode" else null,
        seasonNumber = season,
        episodeNumber = episode,
        posterUrl = posterUrl,
        positionMs = 600_000L,
        durationMs = 1_200_000L,
        lastWatchedAt = watchedAt,
        historyKey = "moviebox:$seriesId:${season ?: 0}:${episode ?: 0}",
        canonicalId = canonicalId
    )

    @Test
    fun scenario1_moviesOnly_remainFlatIndividualEntriesSortedByRecency() {
        val m1 = movieItem("m1", "Heat", 1_000L)
        val m2 = movieItem("m2", "Collateral", 3_000L)
        val entries = ViewingHistoryHierarchyBuilder.buildTopLevelEntries(listOf(m1, m2))

        assertEquals(2, entries.size)
        assertTrue(entries.all { it is ViewingHistoryTopLevelEntry.Individual })
        assertEquals("Collateral", (entries[0] as ViewingHistoryTopLevelEntry.Individual).item.title)
        assertEquals("Heat", (entries[1] as ViewingHistoryTopLevelEntry.Individual).item.title)
    }

    @Test
    fun scenario2_singleEpisodeSeries_isNotWrappedIntoSeriesParent() {
        val singleEp = episodeItem("s1", "Severance", season = 1, episode = 1, watchedAt = 5_000L)
        val entries = ViewingHistoryHierarchyBuilder.buildTopLevelEntries(listOf(singleEp))

        assertEquals(1, entries.size)
        assertTrue(entries[0] is ViewingHistoryTopLevelEntry.Individual)
        assertEquals(singleEp, (entries[0] as ViewingHistoryTopLevelEntry.Individual).item)
    }

    @Test
    fun scenario3And5And6_multiEpisodeSingleSeason_groupsAtSeriesButSkipsSeasonHierarchy() {
        val ep1 = episodeItem("s1", "Andor", season = 1, episode = 1, watchedAt = 1_000L)
        val ep2 = episodeItem("s1", "Andor", season = 1, episode = 3, watchedAt = 4_000L)
        // Even if series metadata says 2 total seasons exist, history only contains Season 1 -> skip seasons
        val entries = ViewingHistoryHierarchyBuilder.buildTopLevelEntries(
            items = listOf(ep1, ep2),
            metadataBySeriesKey = mapOf(
                "moviebox:s1" to ViewingHistorySeriesMetadata(knownTotalSeasons = 2)
            )
        )

        val series = (entries.single() as ViewingHistoryTopLevelEntry.SeriesGroup).series
        assertEquals(2, series.watchedEpisodeCount)
        assertEquals(1, series.seasonCountInHistory)
        assertFalse(series.shouldGroupBySeason)
        assertEquals(4_000L, series.latestWatchedAt)
        assertEquals(listOf(ep2, ep1), series.allEpisodesSorted)
    }

    @Test
    fun scenario6_singleTotalSeasonMetadata_skipsSeasonLevelEvenWithMixedSeasonNulls() {
        val ep1 = episodeItem("s1", "Chernobyl", season = 1, episode = 1, watchedAt = 2_000L)
        val ep2 = episodeItem("s1", "Chernobyl", season = null, episode = 2, watchedAt = 5_000L)
        val entries = ViewingHistoryHierarchyBuilder.buildTopLevelEntries(
            items = listOf(ep1, ep2),
            metadataBySeriesKey = mapOf(
                "moviebox:s1" to ViewingHistorySeriesMetadata(knownTotalSeasons = 1, knownTotalEpisodes = 5)
            )
        )

        val series = (entries.single() as ViewingHistoryTopLevelEntry.SeriesGroup).series
        assertFalse(series.shouldGroupBySeason)
        assertEquals(40, series.completionPercent)
    }

    @Test
    fun scenario4_multiSeasonSeries_groupsNonEmptySeasonsSortedByRecencyWithoutEmptySeasons() {
        val s1e1 = episodeItem("s1", "Succession", season = 1, episode = 1, watchedAt = 1_000L)
        val s3e4 = episodeItem("s1", "Succession", season = 3, episode = 4, watchedAt = 9_000L)
        val s3e2 = episodeItem("s1", "Succession", season = 3, episode = 2, watchedAt = 6_000L)
        // Intentionally omit Season 2: must NOT appear
        val entries = ViewingHistoryHierarchyBuilder.buildTopLevelEntries(listOf(s1e1, s3e4, s3e2))

        val series = (entries.single() as ViewingHistoryTopLevelEntry.SeriesGroup).series
        assertTrue(series.shouldGroupBySeason)
        assertEquals(2, series.seasonCountInHistory)
        assertEquals(3, series.watchedEpisodeCount)
        assertEquals(listOf(3, 1), series.seasons.map { it.seasonNumber })
        assertEquals(listOf(s3e4, s3e2), series.seasons[0].episodes)
        assertEquals(listOf(s1e1), series.seasons[1].episodes)
    }

    @Test
    fun scenario7_sameTitleDifferentSeriesIds_areNeverMergedByTitle() {
        val epA1 = episodeItem("show_1984", "Fargo", season = 1, episode = 1, watchedAt = 1_000L)
        val epA2 = episodeItem("show_1984", "Fargo", season = 1, episode = 2, watchedAt = 2_000L)
        val epB1 = episodeItem("show_2014", "Fargo", season = 1, episode = 1, watchedAt = 3_000L)
        val epB2 = episodeItem("show_2014", "Fargo", season = 1, episode = 2, watchedAt = 4_000L)

        val entries = ViewingHistoryHierarchyBuilder.buildTopLevelEntries(listOf(epA1, epA2, epB1, epB2))
        assertEquals(2, entries.size)
        assertEquals(
            setOf("moviebox:show_2014", "moviebox:show_1984"),
            entries.filterIsInstance<ViewingHistoryTopLevelEntry.SeriesGroup>().map { it.series.seriesKey }.toSet()
        )
    }

    @Test
    fun scenario8_chronologicalOrderingPlacesGroupedSeriesByLatestEpisodeAcrossMovies() {
        val epOld = episodeItem("s1", "Silo", season = 1, episode = 1, watchedAt = 1_000L)
        val movieMiddle = movieItem("m1", "Arrival", watchedAt = 5_000L)
        val epNewest = episodeItem("s1", "Silo", season = 1, episode = 2, watchedAt = 9_000L)
        val singleSeriesMiddle = episodeItem("s2", "Fallout", season = 1, episode = 1, watchedAt = 7_000L)

        val entries = ViewingHistoryHierarchyBuilder.buildTopLevelEntries(
            listOf(epOld, movieMiddle, epNewest, singleSeriesMiddle)
        )

        assertEquals(3, entries.size)
        assertTrue(entries[0] is ViewingHistoryTopLevelEntry.SeriesGroup) // Silo @ 9_000
        assertEquals("Fallout", (entries[1] as ViewingHistoryTopLevelEntry.Individual).item.title) // @ 7_000
        assertEquals("Arrival", (entries[2] as ViewingHistoryTopLevelEntry.Individual).item.title) // @ 5_000
    }

    @Test
    fun scenario11_malformedOrMissingMetadata_fallsBackSafelyAndNeverFabricatesPercentages() {
        val broken = episodeItem("", "Broken Entry", season = null, episode = null, watchedAt = 4_000L)
        val ep1 = episodeItem("s1", "Reacher", season = null, episode = 1, watchedAt = 2_000L)
        val ep2 = episodeItem("s1", "Reacher", season = null, episode = 2, watchedAt = 3_000L)

        val entries = ViewingHistoryHierarchyBuilder.buildTopLevelEntries(
            items = listOf(broken, ep1, ep2),
            // Deliberately supply invalid total (smaller than watched!) -> must clamp to null, not >100%
            metadataBySeriesKey = mapOf(
                "moviebox:s1" to ViewingHistorySeriesMetadata(knownTotalEpisodes = 1)
            )
        )

        assertEquals(2, entries.size)
        val brokenEntry = entries.filterIsInstance<ViewingHistoryTopLevelEntry.Individual>().single()
        assertEquals("Broken Entry", brokenEntry.item.title)

        val series = entries.filterIsInstance<ViewingHistoryTopLevelEntry.SeriesGroup>().single().series
        assertNull(series.completionPercent)
        assertFalse(series.shouldGroupBySeason)
    }

    @Test
    fun scenario9To12_composeHierarchyNavigation_seriesToSeasonToEpisodes_backAndDeletion() {
        val s1e1 = episodeItem("bb", "Breaking Bad", season = 1, episode = 1, watchedAt = 1_000L)
        val s1e2 = episodeItem("bb", "Breaking Bad", season = 1, episode = 2, watchedAt = 2_000L)
        val s2e1 = episodeItem("bb", "Breaking Bad", season = 2, episode = 1, watchedAt = 8_000L)
        val s2e2 = episodeItem("bb", "Breaking Bad", season = 2, episode = 2, watchedAt = 10_000L)
        val loneEp = episodeItem("shogun", "Shogun", season = 1, episode = 1, watchedAt = 9_000L)
        val movie = movieItem("dune", "Dune", watchedAt = 7_000L)
        val trailer = trailerItem("tr1", "Dune Trailer", watchedAt = 11_000L)

        var deletedKey: String? = null
        var openedDetail: Pair<String, String>? = null
        var openedEpisode: Pair<Int?, Int?>? = null

        composeTestRule.setContent {
            LaterboxTheme {
                MoviesViewingHistoryScreen(
                    items = listOf(s1e1, s1e2, s2e1, s2e2, loneEp, movie, trailer),
                    onBackClick = {},
                    onOpenDetail = { p, t -> openedDetail = p to t },
                    onOpenPlayer = { _, _, s, e -> openedEpisode = s to e },
                    onDeleteItem = { deletedKey = it },
                    onClearHistory = {}
                )
            }
        }

        // Top-level shows trailer, multi-ep series grouped, lone episode flat, and movie flat in recency order
        composeTestRule.onNodeWithTag("viewing_history_item_trailer:tmdb:tr1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_series_moviebox:bb").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_item_moviebox:shogun:1:1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_item_tmdb:dune:0:0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_item_moviebox:bb:2:2").assertDoesNotExist()

        val seriesTop = composeTestRule.onNodeWithTag("viewing_history_series_moviebox:bb").getBoundsInRoot().top
        val loneEpTop = composeTestRule.onNodeWithTag("viewing_history_item_moviebox:shogun:1:1").getBoundsInRoot().top
        val movieTop = composeTestRule.onNodeWithTag("viewing_history_item_tmdb:dune:0:0").getBoundsInRoot().top
        assertTrue(seriesTop < loneEpTop)
        assertTrue(loneEpTop < movieTop)

        // Filter Episodes tab excludes Movie & Trailer while preserving hierarchy
        composeTestRule.onNodeWithTag("viewing_history_tab_episodes").performClick()
        composeTestRule.onNodeWithTag("viewing_history_series_moviebox:bb").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_item_moviebox:shogun:1:1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_item_tmdb:dune:0:0").assertDoesNotExist()
        composeTestRule.onNodeWithTag("viewing_history_item_trailer:tmdb:tr1").assertDoesNotExist()

        // Tap Breaking Bad series parent -> opens Seasons view (Season 2 above Season 1 by recency)
        composeTestRule.onNodeWithTag("viewing_history_series_moviebox:bb").performClick()
        composeTestRule.onNodeWithTag("viewing_history_seasons_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_season_moviebox:bb_s_2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_season_moviebox:bb_s_1").assertIsDisplayed()
        val s2Top = composeTestRule.onNodeWithTag("viewing_history_season_moviebox:bb_s_2").getBoundsInRoot().top
        val s1Top = composeTestRule.onNodeWithTag("viewing_history_season_moviebox:bb_s_1").getBoundsInRoot().top
        assertTrue(s2Top < s1Top)

        // Tap Season 2 -> opens Season 2 episodes list with existing ViewingHistoryRowCard actions
        composeTestRule.onNodeWithTag("viewing_history_season_moviebox:bb_s_2").performClick()
        composeTestRule.onNodeWithTag("viewing_history_season_episodes_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_item_moviebox:bb:2:2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_item_moviebox:bb:2:1").assertIsDisplayed()

        // Tapping an episode launches player; tapping delete deletes that episode
        composeTestRule.onNodeWithTag("viewing_history_item_moviebox:bb:2:2").performClick()
        assertEquals(2 to 2, openedEpisode)
        composeTestRule.onNodeWithTag("viewing_history_delete_moviebox:bb:2:1").performClick()
        assertEquals("moviebox:bb:2:1", deletedKey)

        // Back arrow steps Season 2 -> Seasons list -> Top-level Viewing History
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.onNodeWithTag("viewing_history_seasons_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_series_view_details").performClick()
        assertEquals("moviebox" to "bb", openedDetail)

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.onNodeWithTag("viewing_history_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_series_moviebox:bb").assertIsDisplayed()
    }

    @Test
    fun scenario3And12_composeSingleSeasonSkipsSeasonListAndBackHandlesCleanly() {
        val ep1 = episodeItem("andor", "Andor", season = 1, episode = 1, watchedAt = 1_000L)
        val ep2 = episodeItem("andor", "Andor", season = 1, episode = 2, watchedAt = 3_000L)

        composeTestRule.setContent {
            LaterboxTheme {
                MoviesViewingHistoryScreen(
                    items = listOf(ep1, ep2),
                    seriesMetadataByKey = mapOf(
                        "moviebox:andor" to ViewingHistorySeriesMetadata(knownTotalSeasons = 2)
                    ),
                    onBackClick = {},
                    onOpenDetail = { _, _ -> },
                    onOpenPlayer = { _, _, _, _ -> },
                    onDeleteItem = {},
                    onClearHistory = {}
                )
            }
        }

        // Top-level groups into Andor Series card; clicking opens episodes directly (no Seasons screen)
        composeTestRule.onNodeWithTag("viewing_history_series_moviebox:andor").performClick()
        composeTestRule.onNodeWithTag("viewing_history_seasons_list").assertDoesNotExist()
        composeTestRule.onNodeWithTag("viewing_history_series_episodes_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_item_moviebox:andor:1:2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_item_moviebox:andor:1:1").assertIsDisplayed()

        // Pressing Back returns directly to top-level Viewing History
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.onNodeWithTag("viewing_history_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewing_history_series_moviebox:andor").assertIsDisplayed()
    }
}

