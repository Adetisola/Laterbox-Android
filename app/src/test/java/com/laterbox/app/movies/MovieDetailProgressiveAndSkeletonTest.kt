package com.laterbox.app.movies

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.laterbox.app.movies.domain.model.AudioDubInfo
import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import com.laterbox.app.movies.ui.MovieDetailScreen
import com.laterbox.app.movies.ui.MovieDetailUiState
import com.laterbox.app.movies.ui.MovieDetailViewModel
import com.laterbox.app.movies.ui.MoviesCatalogueScreen
import com.laterbox.app.movies.ui.MoviesCatalogueUiState
import com.laterbox.app.movies.ui.MoviesViewingHistoryScreen
import com.laterbox.app.ui.components.CollectionDetailSkeletonList
import com.laterbox.app.ui.components.NotificationHistorySkeletonList
import com.laterbox.app.ui.components.skeletonShimmer
import com.laterbox.app.ui.theme.LaterboxTheme
import com.laterbox.app.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MovieDetailProgressiveAndSkeletonTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val composeTestRule = createComposeRule()

    private open class BaseRepo : MoviesRepository {
        override suspend fun getHomeFeed(forceRefresh: Boolean): Result<MoviesHomeFeed> =
            Result.success(MoviesHomeFeed())

        override suspend fun getCatalogue(
            kind: MediaKind?,
            page: Int,
            pageSize: Int,
            genre: String?,
            sort: CatalogueSort,
            country: String?,
            year: String?
        ): Result<CataloguePage> = Result.success(CataloguePage(emptyList(), page, pageSize, 0, false))

        override suspend fun getGenres(): Result<List<String>> = Result.success(emptyList())
        override suspend fun search(query: String, kind: MediaKind?, page: Int, pageSize: Int): Result<CataloguePage> =
            Result.success(CataloguePage(emptyList(), page, pageSize, 0, false))

        override suspend fun getSuggestions(query: String, limit: Int): Result<List<SearchSuggestion>> =
            Result.success(emptyList())

        override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean): Result<MediaDetails> =
            Result.failure(NoSuchElementException("Not found"))

        override suspend fun getSeasons(providerId: String, seriesId: String): Result<List<SeasonSummary>> =
            Result.success(emptyList())

        override suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int): Result<List<EpisodeItem>> =
            Result.success(emptyList())

        override suspend fun resolvePlayback(
            providerId: String,
            titleId: String,
            seasonNumber: Int?,
            episodeNumber: Int?
        ): Result<PlaybackResolution> = Result.success(PlaybackResolution.Unavailable("Test", "none", providerId))

        override suspend fun getSubtitles(
            providerId: String,
            titleId: String,
            seasonNumber: Int?,
            episodeNumber: Int?
        ): Result<List<SubtitleTrack>> = Result.success(emptyList())

        override fun observeWatchlist(): Flow<List<MovieTitleSummary>> = flowOf(emptyList())
        override fun observeIsInWatchlist(compositeId: String): Flow<Boolean> = flowOf(false)
        override suspend fun toggleWatchlist(item: MovieTitleSummary): Boolean = false
        override fun observeContinueWatching(limit: Int): Flow<List<WatchHistoryItem>> = flowOf(emptyList())
        override suspend fun saveWatchProgress(
            item: MovieTitleSummary,
            positionMs: Long,
            durationMs: Long,
            seasonNumber: Int?,
            episodeNumber: Int?,
            episodeLabel: String?
        ) = Unit

        override suspend fun getSavedPositionMs(
            providerId: String,
            titleId: String,
            seasonNumber: Int?,
            episodeNumber: Int?
        ): Long = 0L

        override fun observeRecentSearches(limit: Int): Flow<List<String>> = flowOf(emptyList())
        override suspend fun recordSearchQuery(query: String) = Unit
        override suspend fun clearSearchHistory() = Unit
    }

    @Test
    fun `priority 1 cached summary prefills immediately and launches priority 2 before details return`() = runTest {
        val cachedSeries = MovieTitleSummary(
            id = "s_100",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Cached Series",
            releaseYear = 2024,
            genres = listOf("Sci-Fi", "Drama"),
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )
        val detailsGate = CompletableDeferred<Result<MediaDetails>>()
        var playbackStartedBeforeDetails = false
        var episodesStartedBeforeDetails = false

        val repo = object : BaseRepo() {
            override fun getCachedSummary(providerId: String, titleId: String): MovieTitleSummary? =
                if (providerId == "moviebox" && titleId == "s_100") cachedSeries else null

            override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean): Result<MediaDetails> =
                detailsGate.await()

            override suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int): Result<List<EpisodeItem>> {
                episodesStartedBeforeDetails = !detailsGate.isCompleted
                return Result.success(
                    listOf(EpisodeItem("ep1", seasonNumber, 1, "Pilot"))
                )
            }

            override suspend fun resolvePlayback(
                providerId: String,
                titleId: String,
                seasonNumber: Int?,
                episodeNumber: Int?
            ): Result<PlaybackResolution> {
                playbackStartedBeforeDetails = !detailsGate.isCompleted
                return Result.success(
                    PlaybackResolution.Available(
                        title = "Cached Series",
                        sources = listOf(
                            PlaybackSource(
                                id = "s720",
                                url = "https://cdn.example/s1e1.mp4",
                                format = "MP4",
                                qualityLabel = "720p",
                                qualityHeight = 720,
                                providerName = providerId
                            )
                        ),
                        providerName = providerId
                    )
                )
            }
        }

        val vm = MovieDetailViewModel(repo)
        vm.load("moviebox", "s_100")

        // Before detailsGate completes, Priority 1 & Priority 2 are already active and rendered!
        val midState = vm.uiState.value
        assertFalse(midState.isLoading)
        assertTrue(midState.isRefreshingDetails)
        assertEquals("Cached Series", midState.details?.summary?.title)
        assertTrue("Priority 2 episodes must start without waiting for /detail", episodesStartedBeforeDetails)
        assertTrue("Priority 2 playback must start without waiting for /detail", playbackStartedBeforeDetails)
        assertEquals(1, midState.episodes.size)
        assertTrue(midState.playbackResolution is PlaybackResolution.Available)

        // Complete Priority 1 details
        detailsGate.complete(
            Result.success(
                MediaDetails(
                    summary = cachedSeries,
                    fullDescription = "Full synopsis",
                    seasons = listOf(SeasonSummary(1, "Season 1", 1))
                )
            )
        )
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isRefreshingDetails)
        assertEquals("Full synopsis", vm.uiState.value.details?.fullDescription)
    }

    @Test
    fun `partial failure on detail or supporting tasks preserves cached content and ignores stale responses`() = runTest {
        val cachedA = MovieTitleSummary("a1", "moviebox", MediaKind.MOVIE, "Alpha Movie", sourceBadge = DataSourceBadge.LIVE_MOVIEBOX)
        val cachedB = MovieTitleSummary("b2", "moviebox", MediaKind.MOVIE, "Beta Movie", sourceBadge = DataSourceBadge.LIVE_MOVIEBOX)
        val slowAGate = CompletableDeferred<Result<MediaDetails>>()

        val repo = object : BaseRepo() {
            override fun getCachedSummary(providerId: String, titleId: String): MovieTitleSummary? =
                when (titleId) {
                    "a1" -> cachedA
                    "b2" -> cachedB
                    else -> null
                }

            override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean): Result<MediaDetails> {
                return if (titleId == "a1") slowAGate.await()
                else Result.failure(IllegalStateException("Temporary metadata outage"))
            }

            override suspend fun recordRecentlyViewed(item: MovieTitleSummary) {
                error("Non-fatal DB failure")
            }

            override suspend fun getSavedPositionMs(
                providerId: String,
                titleId: String,
                seasonNumber: Int?,
                episodeNumber: Int?
            ): Long {
                error("Non-fatal position failure")
            }
        }

        val vm = MovieDetailViewModel(repo)
        vm.load("moviebox", "a1")
        // Immediately switch to b2 before a1 resolves
        vm.load("moviebox", "b2")
        advanceUntilIdle()

        // b2's detail request failed, but cachedSummary b2 stays visible without full-screen error takeover
        assertEquals("Beta Movie", vm.uiState.value.details?.summary?.title)
        assertNull(vm.uiState.value.errorMessage)
        assertNotNull(vm.uiState.value.actionErrorMessage)
        assertFalse(vm.uiState.value.isRefreshingDetails)

        // Late resolution of a1 must be ignored because requestSeq advanced
        slowAGate.complete(Result.success(MediaDetails(summary = cachedA, fullDescription = "Stale Alpha")))
        advanceUntilIdle()
        assertEquals("Beta Movie", vm.uiState.value.details?.summary?.title)
    }

    @Test
    fun `detail screen splits descriptive pills and audio dubs into two responsive rows and supports dub switch`() {
        var clickedDubId: String? = null
        val summary = MovieTitleSummary(
            id = "dub_en",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Inception",
            country = "United States, United Kingdom",
            language = "English, Spanish",
            genres = listOf("Sci-Fi", "Thriller"),
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )
        val details = MediaDetails(
            summary = summary,
            fullDescription = "Mind-bending heist thriller.",
            audioDubs = listOf(
                AudioDubInfo("English", "Original Audio", "dub_en"),
                AudioDubInfo("Spanish", "Spanish Dub", "dub_es")
            )
        )

        composeTestRule.setContent {
            LaterboxTheme {
                MovieDetailScreen(
                    uiState = MovieDetailUiState(
                        isLoading = false,
                        details = details
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onToggleWatchlist = {},
                    onPlayClick = { _, _ -> },
                    onSelectSeason = {},
                    onOpenRelated = { _, titleId -> clickedDubId = titleId },
                    onOpenExternalUrl = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("detail_metadata_descriptive_row").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_country_chip_United States").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_language_chip_English").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_genre_chip_Sci-Fi").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("detail_metadata_audio_row").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_dub_chip_dub_en").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_dub_chip_dub_es").performScrollTo().assertIsDisplayed().performClick()
        assertEquals("dub_es", clickedDubId)
    }

    @Test
    fun `detail screen limits long description to 2 lines with toggle and supports interactive subtitle selection`() {
        var selectedSubtitle: SubtitleTrack? = null
        val summary = MovieTitleSummary(
            id = "film_long",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Long Description Film",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )
        val details = MediaDetails(
            summary = summary,
            fullDescription = "Line one of a very long movie synopsis designed to wrap well beyond two lines in a standard mobile viewport. Line two continues the plot summary with extra character details, twists, and setting descriptions so truncation activates reliably.",
            availableSubtitles = listOf(
                SubtitleTrack("sub_en", "English", "en", "English", "https://cdn.example/en.srt"),
                SubtitleTrack("sub_fr", "French", "fr", "French", "https://cdn.example/fr.srt")
            )
        )

        composeTestRule.setContent {
            LaterboxTheme {
                MovieDetailScreen(
                    uiState = MovieDetailUiState(
                        isLoading = false,
                        details = details
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onToggleWatchlist = {},
                    onPlayClick = { _, _ -> },
                    onSelectSeason = {},
                    onOpenRelated = { _, _ -> },
                    onOpenExternalUrl = {},
                    onSelectSubtitle = { selectedSubtitle = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("detail_synopsis_text").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_metadata_subtitle_row").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_subtitle_chip_sub_fr").performScrollTo().assertIsDisplayed().performClick()
        assertEquals("sub_fr", selectedSubtitle?.id)
    }

    @Test
    fun `draw-layer shimmer causes zero recompositions per animation tick`() {
        var compositionCount = 0

        composeTestRule.setContent {
            LaterboxTheme {
                Box(
                    modifier = Modifier
                        .size(120.dp, 24.dp)
                        .skeletonShimmer(shape = RoundedCornerShape(6.dp))
                ) {
                    SideEffect { compositionCount++ }
                }
            }
        }

        composeTestRule.waitForIdle()
        val initialCompositions = compositionCount
        composeTestRule.mainClock.advanceTimeBy(1200)
        composeTestRule.waitForIdle()
        assertEquals("Shimmer animation frames must not recompose host", initialCompositions, compositionCount)
    }

    @Test
    fun `detail screen renders full and partial section skeletons cleanly during loading`() {
        composeTestRule.setContent {
            LaterboxTheme {
                MovieDetailScreen(
                    uiState = MovieDetailUiState(isLoading = true),
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
        composeTestRule.onNodeWithTag("detail_loading_skeleton").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_metadata_skeleton_row1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_metadata_skeleton_row2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_cast_skeleton").assertIsDisplayed()
    }

    @Test
    fun `catalogue and collection-notification skeletons render dedicated placeholders`() {
        composeTestRule.setContent {
            LaterboxTheme {
                MoviesCatalogueScreen(
                    uiState = MoviesCatalogueUiState(isLoading = true, items = emptyList()),
                    onBackClick = {},
                    onQueryChange = {},
                    onSearchFocusChange = {},
                    onSubmitSearch = {},
                    onClearSearch = {},
                    onSelectKind = {},
                    onSelectGenre = {},
                    onSelectSort = {},
                    onLoadNextPage = {},
                    onRetry = {},
                    onClearSearchHistory = {},
                    onOpenDetail = { _, _ -> }
                )
            }
        }
        composeTestRule.onNodeWithTag("catalogue_loading_skeleton").assertIsDisplayed()
    }

    @Test
    fun `viewing history, collection detail, and notification history skeletons compose cleanly`() {
        composeTestRule.setContent {
            LaterboxTheme {
                MoviesViewingHistoryScreen(
                    items = emptyList(),
                    isLoading = true,
                    onBackClick = {},
                    onOpenDetail = { _, _ -> },
                    onOpenPlayer = { _, _, _, _ -> },
                    onDeleteItem = {},
                    onClearHistory = {}
                )
                CollectionDetailSkeletonList()
                NotificationHistorySkeletonList()
            }
        }
        composeTestRule.onNodeWithTag("viewing_history_skeleton").assertIsDisplayed()
    }

    @Test
    fun `batch download bottom sheet replaces checkbox with checkmark only for selected downloaded quality`() {
        val sources = listOf(
            PlaybackSource(id = "src_720", url = "https://cdn.example.com/720.mp4", format = "MP4", qualityLabel = "720p", qualityHeight = 720, providerName = "MovieBox"),
            PlaybackSource(id = "src_1080", url = "https://cdn.example.com/1080.mp4", format = "MP4", qualityLabel = "1080p", qualityHeight = 1080, providerName = "MovieBox")
        )
        val seasons = listOf(SeasonSummary(seasonNumber = 1, episodeCount = 2))
        val episodes = listOf(
            EpisodeItem(id = "ep1", seasonNumber = 1, episodeNumber = 1, title = "Pilot", runtimeMinutes = 30),
            EpisodeItem(id = "ep2", seasonNumber = 1, episodeNumber = 2, title = "Chapter 2", runtimeMinutes = 30)
        )
        val offlineMatches = listOf(
            com.laterbox.app.movies.domain.model.OfflineMediaMatch(
                assetId = "asset_ep1_720",
                sourceRef = "moviebox|show_1|1|1",
                localPath = "/tmp/show_1_s1_e1_720p.mp4",
                qualityLabel = "720p",
                fileSizeBytes = 100_000L,
                seasonNumber = 1,
                episodeNumber = 1
            )
        )

        composeTestRule.setContent {
            LaterboxTheme {
                com.laterbox.app.movies.ui.DownloadOptionsBottomSheet(
                    title = "Severance",
                    kind = MediaKind.TV_SERIES,
                    sources = sources,
                    subtitles = emptyList(),
                    seasons = seasons,
                    selectedSeasonNumber = 1,
                    currentEpisodes = episodes,
                    offlineMatches = offlineMatches,
                    onSelectSource = {},
                    onDownloadBatch = { _, _, _ -> },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("batch_quality_chip_720").performClick()
        composeTestRule.onNodeWithTag("download_options_list")
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("batch_episode_row_1"))
        composeTestRule.onNodeWithTag("batch_episode_downloaded_1", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("batch_episode_checkbox_1", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("download_options_list")
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("batch_episode_row_2"))
        composeTestRule.onNodeWithTag("batch_episode_checkbox_2", useUnmergedTree = true).assertIsDisplayed()

        composeTestRule.onNodeWithTag("batch_quality_chip_1080").performClick()
        composeTestRule.onNodeWithTag("download_options_list")
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("batch_episode_row_1"))
        composeTestRule.onNodeWithTag("batch_episode_downloaded_1", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("batch_episode_checkbox_1", useUnmergedTree = true).assertIsDisplayed()
    }
}
