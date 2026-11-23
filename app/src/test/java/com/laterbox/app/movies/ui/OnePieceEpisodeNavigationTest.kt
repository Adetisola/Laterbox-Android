package com.laterbox.app.movies.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.SeasonSummary
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
class OnePieceEpisodeNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun onePieceAnimeSummary(
        id: String = "op_mb_1",
        providerId: String = "moviebox",
        title: String = "One Piece",
        imdbId: String? = "tt0388629",
        tvmazeId: String? = null,
        canonicalId: String? = null,
        releaseYear: Int? = 1999,
        genres: List<String> = listOf("Animation", "Action", "Adventure"),
        country: String? = "Japan"
    ) = MovieTitleSummary(
        id = id,
        providerId = providerId,
        kind = MediaKind.TV_SERIES,
        title = title,
        releaseYear = releaseYear,
        genres = genres,
        country = country,
        imdbId = imdbId,
        tvmazeId = tvmazeId,
        canonicalId = canonicalId
    )

    private fun sampleEpisodes(range: IntRange, season: Int = 1): List<EpisodeItem> =
        range.map { ep ->
            EpisodeItem(
                id = "op_s${season}_e$ep",
                seasonNumber = season,
                episodeNumber = ep,
                title = "Adventure $ep"
            )
        }

    @Test
    fun identification_matchesOnePieceAnimeIdsAndCanonicalFallback_excludesLiveActionAndOtherShows() {
        val seasons1180 = listOf(SeasonSummary(seasonNumber = 1, episodeCount = 1180))

        // Primary external IDs match
        assertTrue(OnePieceEpisodeRangeBuilder.isOnePieceAnime(onePieceAnimeSummary(imdbId = "tt0388629"), seasons1180))
        assertTrue(OnePieceEpisodeRangeBuilder.isOnePieceAnime(onePieceAnimeSummary(imdbId = null, tvmazeId = "1505"), seasons1180))
        assertTrue(
            OnePieceEpisodeRangeBuilder.isOnePieceAnime(
                onePieceAnimeSummary(id = "tv_37854", providerId = "tmdb", imdbId = null, releaseYear = null),
                seasons1180
            )
        )
        assertTrue(
            OnePieceEpisodeRangeBuilder.isOnePieceAnime(
                onePieceAnimeSummary(imdbId = null, canonicalId = "imdb:tt0388629"),
                seasons1180
            )
        )

        // Constrained fallback matches anime without external IDs
        assertTrue(
            OnePieceEpisodeRangeBuilder.isOnePieceAnime(
                onePieceAnimeSummary(imdbId = null, releaseYear = 1999, genres = emptyList(), country = null),
                seasons1180
            )
        )

        // Live-action One Piece (by ID, year/ep count, or title) MUST be excluded
        assertFalse(
            OnePieceEpisodeRangeBuilder.isOnePieceAnime(
                onePieceAnimeSummary(imdbId = "tt11737520", releaseYear = 2023, genres = listOf("Action")),
                listOf(SeasonSummary(1, episodeCount = 8))
            )
        )
        assertFalse(
            OnePieceEpisodeRangeBuilder.isOnePieceAnime(
                onePieceAnimeSummary(imdbId = null, tvmazeId = "46123", releaseYear = 2023),
                listOf(SeasonSummary(1, episodeCount = 8))
            )
        )
        assertFalse(
            OnePieceEpisodeRangeBuilder.isOnePieceAnime(
                onePieceAnimeSummary(imdbId = null, releaseYear = 2023, genres = listOf("Action"), country = "United States"),
                listOf(SeasonSummary(1, episodeCount = 8))
            )
        )
        assertFalse(
            OnePieceEpisodeRangeBuilder.isOnePieceAnime(
                onePieceAnimeSummary(title = "One Piece (Live Action)", imdbId = null, releaseYear = 2023),
                listOf(SeasonSummary(1, episodeCount = 8))
            )
        )

        // Derivative films or unrelated 1000+ episode single-season shows MUST be excluded
        assertFalse(
            OnePieceEpisodeRangeBuilder.isOnePieceAnime(
                onePieceAnimeSummary(title = "One Piece Film: Red", imdbId = null, releaseYear = 2022).copy(kind = MediaKind.MOVIE),
                emptyList()
            )
        )
        assertFalse(
            OnePieceEpisodeRangeBuilder.isOnePieceAnime(
                onePieceAnimeSummary(title = "Detective Conan", imdbId = "tt0131179", releaseYear = 1996),
                listOf(SeasonSummary(1, episodeCount = 1150))
            )
        )
    }

    @Test
    fun rangeGeneration_handles1180EpisodesBoundariesGapsSpecialsAndSorting() {
        val full1180 = sampleEpisodes(1..1180)
        val ranges = OnePieceEpisodeRangeBuilder.buildRanges(full1180)
        assertEquals(12, ranges.size)
        assertEquals("1–100", ranges.first().label)
        assertEquals(1..100, ranges.first().episodes.first().episodeNumber..ranges.first().episodes.last().episodeNumber)
        assertEquals(101, ranges[1].episodes.first().episodeNumber)
        assertEquals(200, ranges[1].episodes.last().episodeNumber)
        assertEquals("201–300", ranges[2].label)
        assertEquals("1101–1200", ranges.last().label)
        assertEquals(80, ranges.last().episodes.size)
        assertEquals(1101, ranges.last().episodes.first().episodeNumber)
        assertEquals(1180, ranges.last().episodes.last().episodeNumber)

        // No episode omitted or duplicated across boundaries
        val flattenedNumbers = ranges.flatMap { it.episodes }.map { it.episodeNumber }
        assertEquals(1180, flattenedNumbers.size)
        assertEquals((1..1180).toList(), flattenedNumbers)

        // Adapts dynamically when episode count grows
        val expanded = OnePieceEpisodeRangeBuilder.buildRanges(sampleEpisodes(1..1205))
        assertEquals(13, expanded.size)
        assertEquals("1201–1300", expanded.last().label)
        assertEquals(5, expanded.last().episodes.size)
    }

    @Test
    fun rangeGeneration_handlesGapsUnorderedMetadataDuplicatesAndSpecialsSafely() {
        val mixedEpisodes = listOf(
            EpisodeItem("op_305", 1, 305, "Ep 305"),
            EpisodeItem("op_special_1", 1, 0, "Special: Episode of Nami"),
            EpisodeItem("op_5", 1, 5, "Ep 5"),
            EpisodeItem("op_5", 1, 5, "Ep 5"), // duplicate metadata id
            EpisodeItem("op_unknown", 1, -1, "Recap OVA"),
            EpisodeItem("op_1", 1, 1, "Ep 1"),
            EpisodeItem("op_301", 1, 301, "Ep 301")
        )

        val ranges = OnePieceEpisodeRangeBuilder.buildRanges(mixedEpisodes)
        // Gap 101-300 must not emit empty ranges 101-200 or 201-300
        assertEquals(listOf("1–100", "301–400", "Specials"), ranges.map { it.label })
        assertEquals(listOf(1, 5), ranges[0].episodes.map { it.episodeNumber })
        assertEquals(listOf(301, 305), ranges[1].episodes.map { it.episodeNumber })
        assertEquals(listOf("Recap OVA", "Special: Episode of Nami"), ranges[2].episodes.map { it.title })
    }

    @Test
    fun composeUi_onePieceRendersOutlinedRangesWithDividerAndSwitchesDistantRangesCleanly() {
        val summary = onePieceAnimeSummary()
        val episodes = sampleEpisodes(1..1180) + listOf(
            EpisodeItem("op_sp_0", 1, 0, "Special Prologue")
        )
        val details = MediaDetails(
            summary = summary,
            seasons = listOf(SeasonSummary(seasonNumber = 1, title = "Season 1", episodeCount = 1180))
        )
        var playedEpisode: Pair<Int?, Int?>? = null

        composeTestRule.setContent {
            LaterboxTheme(darkTheme = true) {
                MovieDetailScreen(
                    uiState = MovieDetailUiState(
                        isLoading = false,
                        details = details,
                        selectedSeasonNumber = 1,
                        episodes = episodes
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onToggleWatchlist = {},
                    onPlayClick = { s, e -> playedEpisode = s to e },
                    onSelectSeason = {},
                    onOpenRelated = { _, _ -> },
                    onOpenExternalUrl = {}
                )
            }
        }

        // Verify Season selector + vertical divider + initial range pills render
        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("detail_seasons_and_ranges_row"))
        composeTestRule.onNodeWithTag("season_chip_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("one_piece_range_divider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("one_piece_range_chip_range_1_100").assertIsDisplayed()

        // Initially shows episodes 1..100 without truncating behind Load More
        composeTestRule.onNodeWithTag("detail_episodes_load_more").assertDoesNotExist()
        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("episode_row_1_1"))
        composeTestRule.onNodeWithTag("episode_row_1_1").assertIsDisplayed()

        // Scroll horizontally across range pills to distant 1101-1200 and tap it
        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("detail_seasons_and_ranges_row"))
        composeTestRule.onNodeWithTag("detail_seasons_and_ranges_row").performScrollToIndex(13)
        composeTestRule.onNodeWithTag("one_piece_range_chip_range_1101_1200").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("episode_row_1_1101").assertIsDisplayed()
        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("episode_row_1_1180"))
        composeTestRule.onNodeWithTag("episode_row_1_1180").performClick()
        assertEquals(1 to 1180, playedEpisode)

        // Tap Specials range pill and confirm unnumbered special renders without E0 / 0. prefix
        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("detail_seasons_and_ranges_row"))
        composeTestRule.onNodeWithTag("detail_seasons_and_ranges_row").performScrollToIndex(14)
        composeTestRule.onNodeWithTag("one_piece_range_chip_specials").performClick()
        composeTestRule.onNodeWithText("Special Prologue").assertIsDisplayed()
        composeTestRule.onNodeWithText("0. Special Prologue").assertDoesNotExist()
    }

    @Test
    fun composeUi_liveActionAndOtherShowsDoNotReceiveRangeSelectors_lightThemeWorks() {
        val liveActionDetails = MediaDetails(
            summary = onePieceAnimeSummary(
                id = "op_la",
                imdbId = "tt11737520",
                releaseYear = 2023,
                genres = listOf("Action", "Adventure")
            ),
            seasons = listOf(SeasonSummary(seasonNumber = 1, title = "Season 1", episodeCount = 8))
        )

        composeTestRule.setContent {
            LaterboxTheme(darkTheme = false) {
                MovieDetailScreen(
                    uiState = MovieDetailUiState(
                        isLoading = false,
                        details = liveActionDetails,
                        selectedSeasonNumber = 1,
                        episodes = sampleEpisodes(1..8)
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onToggleWatchlist = {},
                    onPlayClick = { _, _ -> },
                    onSelectSeason = {},
                    onOpenRelated = { _, _ -> },
                    onOpenExternalUrl = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("movie_detail_list").performScrollToNode(hasTestTag("detail_seasons_and_ranges_row"))
        composeTestRule.onNodeWithTag("season_chip_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("one_piece_range_divider").assertDoesNotExist()
        composeTestRule.onNodeWithTag("one_piece_range_chip_range_1_100").assertDoesNotExist()
        assertNull(OnePieceEpisodeRangeBuilder.buildUiState(liveActionDetails, sampleEpisodes(1..8)))
    }

    @Test
    fun downloadBottomSheet_onePiecePreservesMultiRangeSelectionsAndTriggersBatchDownload() {
        val summary = onePieceAnimeSummary()
        val episodes = sampleEpisodes(1..1180)
        val seasons = listOf(SeasonSummary(seasonNumber = 1, title = "Season 1", episodeCount = 1180))
        var downloadedSeason: Int? = null
        var downloadedEpisodes: List<Int> = emptyList()

        composeTestRule.setContent {
            LaterboxTheme(darkTheme = true) {
                DownloadOptionsBottomSheet(
                    title = summary.title,
                    kind = summary.kind,
                    sources = emptyList(),
                    subtitles = emptyList(),
                    seasons = seasons,
                    selectedSeasonNumber = 1,
                    currentEpisodes = episodes,
                    seriesSummary = summary,
                    onSelectSource = {},
                    onDownloadBatch = { s, eps, _ ->
                        downloadedSeason = s
                        downloadedEpisodes = eps
                    },
                    onDismiss = {}
                )
            }
        }

        // Verify Season selector + divider + range selector pills appear in download sheet
        composeTestRule.onNodeWithTag("batch_season_chip_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("batch_one_piece_range_divider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("batch_one_piece_range_chip_range_1_100").assertIsDisplayed()

        // Select episodes 1 and 2 in range 1-100
        composeTestRule.onNodeWithTag("batch_episode_row_1").performClick()
        composeTestRule.onNodeWithTag("batch_episode_row_2").performClick()
        composeTestRule.onNodeWithText("Download 2 episodes").assertIsDisplayed()

        // Switch to 101-200 and select episode 105
        composeTestRule.onNodeWithTag("batch_one_piece_range_chip_range_101_200").performClick()
        composeTestRule.onNodeWithTag("download_options_list").performScrollToNode(hasTestTag("batch_episode_row_105"))
        composeTestRule.onNodeWithTag("batch_episode_row_105").performClick()
        composeTestRule.onNodeWithText("Download 3 episodes").assertIsDisplayed()

        // Jump to distant final range 1101-1200 and select episode 1150
        composeTestRule.onNodeWithTag("batch_seasons_and_ranges_row").performScrollToIndex(13)
        composeTestRule.onNodeWithTag("batch_one_piece_range_chip_range_1101_1200").performClick()
        composeTestRule.onNodeWithTag("download_options_list").performScrollToNode(hasTestTag("batch_episode_row_1150"))
        composeTestRule.onNodeWithTag("batch_episode_row_1150").performClick()
        composeTestRule.onNodeWithText("Download 4 episodes").assertIsDisplayed()

        // Switch back to 1-100 and confirm episode 1 & 2 selections are still preserved!
        composeTestRule.onNodeWithTag("batch_seasons_and_ranges_row").performScrollToIndex(0)
        composeTestRule.onNodeWithTag("batch_one_piece_range_chip_range_1_100").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Episodes (100) · 4 selected").assertIsDisplayed()

        // Confirm Download enqueues all 4 episodes across all 3 ranges cleanly
        composeTestRule.onNodeWithText("Download 4 episodes").performClick()
        assertEquals(1, downloadedSeason)
        assertEquals(listOf(1, 2, 105, 1150), downloadedEpisodes)
    }

    @Test
    fun downloadBottomSheet_liveActionAndOtherSeriesDoNotShowRangeSelectors() {
        val liveActionSummary = onePieceAnimeSummary(
            id = "op_la",
            imdbId = "tt11737520",
            releaseYear = 2023,
            genres = listOf("Action", "Adventure")
        )
        val seasons = listOf(SeasonSummary(seasonNumber = 1, title = "Season 1", episodeCount = 8))

        composeTestRule.setContent {
            LaterboxTheme(darkTheme = false) {
                DownloadOptionsBottomSheet(
                    title = liveActionSummary.title,
                    kind = liveActionSummary.kind,
                    sources = emptyList(),
                    subtitles = emptyList(),
                    seasons = seasons,
                    selectedSeasonNumber = 1,
                    currentEpisodes = sampleEpisodes(1..8),
                    seriesSummary = liveActionSummary,
                    onSelectSource = {},
                    onDownloadBatch = { _, _, _ -> },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("batch_season_chip_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("batch_one_piece_range_divider").assertDoesNotExist()
        composeTestRule.onNodeWithTag("batch_one_piece_range_chip_range_1_100").assertDoesNotExist()
    }
}

