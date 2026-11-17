package com.laterbox.app.movies

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.movies.domain.model.BrowseSection
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
import com.laterbox.app.movies.domain.model.SectionStyle
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import com.laterbox.app.movies.ui.MovieDetailViewModel
import com.laterbox.app.movies.ui.MoviePlayerViewModel
import com.laterbox.app.movies.ui.MoviesCatalogueViewModel
import com.laterbox.app.movies.ui.MoviesHomeViewModel
import com.laterbox.app.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoviesViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeMoviesRepository : MoviesRepository {
        var shouldFailHome = false
        var homeCallCount = 0
        val watchlistFlow = MutableStateFlow<List<MovieTitleSummary>>(emptyList())
        val continueFlow = MutableStateFlow<List<WatchHistoryItem>>(emptyList())
        val recentSearchesFlow = MutableStateFlow<List<String>>(listOf("noir"))
        val savedProgressCalls = mutableListOf<Pair<Long, Long>>()

        val sampleMovie = MovieTitleSummary(
            id = "his_girl_friday",
            providerId = "ia",
            kind = MediaKind.MOVIE,
            title = "His Girl Friday",
            releaseYear = 1940,
            rating = 8.8,
            genres = listOf("Comedy", "Drama"),
            sourceBadge = DataSourceBadge.LIVE_INTERNET_ARCHIVE,
            isPlayableHint = true
        )

        val sampleSeries = MovieTitleSummary(
            id = "169",
            providerId = "tvmaze",
            kind = MediaKind.TV_SERIES,
            title = "Breaking Bad",
            releaseYear = 2008,
            rating = 9.2,
            genres = listOf("Drama", "Crime"),
            sourceBadge = DataSourceBadge.LIVE_TVMAZE,
            isPlayableHint = false
        )

        override suspend fun getHomeFeed(forceRefresh: Boolean): Result<MoviesHomeFeed> {
            homeCallCount++
            if (shouldFailHome) return Result.failure(IllegalStateException("Network offline"))
            return Result.success(
                MoviesHomeFeed(
                    featuredBanners = listOf(sampleMovie, sampleSeries),
                    sections = listOf(
                        BrowseSection("sec_1", "Playable Cinema", null, SectionStyle.RAIL, listOf(sampleMovie))
                    ),
                    genres = listOf("All", "Comedy", "Drama")
                )
            )
        }

        override suspend fun getCatalogue(
            kind: MediaKind?,
            page: Int,
            pageSize: Int,
            genre: String?,
            sort: CatalogueSort
        ): Result<CataloguePage> {
            val pool = when (kind) {
                MediaKind.MOVIE -> listOf(sampleMovie)
                MediaKind.TV_SERIES -> listOf(sampleSeries)
                null -> listOf(sampleMovie, sampleSeries)
            }
            return Result.success(
                CataloguePage(
                    items = pool.map { it.copy(id = "${it.id}_p$page") },
                    page = page,
                    pageSize = pageSize,
                    totalCount = 40,
                    hasMore = page < 2
                )
            )
        }

        override suspend fun getGenres(): Result<List<String>> = Result.success(listOf("All", "Comedy", "Drama"))

        override suspend fun search(query: String, kind: MediaKind?, page: Int, pageSize: Int): Result<CataloguePage> =
            Result.success(CataloguePage(listOf(sampleMovie), page, pageSize, 1, false))

        override suspend fun getSuggestions(query: String, limit: Int): Result<List<SearchSuggestion>> =
            Result.success(listOf(SearchSuggestion("his_girl_friday", "ia", "His Girl Friday", MediaKind.MOVIE, 1940)))

        override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean): Result<MediaDetails> {
            val base = if (providerId == "tvmaze") sampleSeries else sampleMovie
            val seasons = if (providerId == "tvmaze") listOf(SeasonSummary(1, "Season 1", 7), SeasonSummary(2, "Season 2", 13)) else emptyList()
            return Result.success(
                MediaDetails(
                    summary = base,
                    fullDescription = "Full synopsis for ${base.title}",
                    seasons = seasons,
                    availableSubtitles = listOf(SubtitleTrack("sub_en", "English", "en", "English (SRT)", "https://example.com/en.srt"))
                )
            )
        }

        override suspend fun getSeasons(providerId: String, seriesId: String): Result<List<SeasonSummary>> =
            Result.success(listOf(SeasonSummary(1, "Season 1", 7), SeasonSummary(2, "Season 2", 13)))

        override suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int): Result<List<EpisodeItem>> =
            Result.success(listOf(EpisodeItem("ep_${seasonNumber}_1", seasonNumber, 1, "Episode S${seasonNumber}E1")))

        override suspend fun resolvePlayback(
            providerId: String,
            titleId: String,
            seasonNumber: Int?,
            episodeNumber: Int?
        ): Result<PlaybackResolution> {
            return if (providerId == "tvmaze") {
                Result.success(
                    PlaybackResolution.Unavailable(
                        title = "Breaking Bad",
                        reason = "TVMaze is an authorised metadata guide only.",
                        providerName = "TVMaze",
                        externalReferenceUrl = "https://www.tvmaze.com/shows/169"
                    )
                )
            } else {
                Result.success(
                    PlaybackResolution.Available(
                        title = "His Girl Friday",
                        sources = listOf(
                            PlaybackSource("s720", "https://archive.org/720.mp4", "MP4", "720p", 720, providerName = "IA"),
                            PlaybackSource("s480", "https://archive.org/480.mp4", "MP4", "480p", 480, providerName = "IA")
                        ),
                        subtitles = listOf(
                            SubtitleTrack("sub_en", "English", "en", "English (SRT)", "https://archive.org/en.srt")
                        ),
                        startPositionMs = 45_000L,
                        providerName = "Internet Archive"
                    )
                )
            }
        }

        override suspend fun getSubtitles(providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?): Result<List<SubtitleTrack>> =
            Result.success(emptyList())

        override fun observeWatchlist(): Flow<List<MovieTitleSummary>> = watchlistFlow
        override fun observeIsInWatchlist(compositeId: String): Flow<Boolean> =
            watchlistFlow.map { list -> list.any { it.compositeId == compositeId } }

        override suspend fun toggleWatchlist(item: MovieTitleSummary): Boolean {
            val current = watchlistFlow.value
            val exists = current.any { it.compositeId == item.compositeId }
            watchlistFlow.value = if (exists) current.filterNot { it.compositeId == item.compositeId } else current + item
            return !exists
        }

        override fun observeContinueWatching(limit: Int): Flow<List<WatchHistoryItem>> = continueFlow
        override suspend fun saveWatchProgress(
            item: MovieTitleSummary,
            positionMs: Long,
            durationMs: Long,
            seasonNumber: Int?,
            episodeNumber: Int?,
            episodeLabel: String?
        ) {
            savedProgressCalls.add(positionMs to durationMs)
        }

        override suspend fun getSavedPositionMs(providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?): Long = 45_000L
        override fun observeRecentSearches(limit: Int): Flow<List<String>> = recentSearchesFlow
        override suspend fun recordSearchQuery(query: String) {
            recentSearchesFlow.value = (listOf(query) + recentSearchesFlow.value).distinct()
        }
        override suspend fun clearSearchHistory() {
            recentSearchesFlow.value = emptyList()
        }
    }

    @Test
    fun homeViewModel_handlesErrorRetryAndWatchlistToggle() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UserPreferencesRepository(context)
        val repo = FakeMoviesRepository().apply { shouldFailHome = true }
        val vm = MoviesHomeViewModel(repo, prefs)
        val failedState = vm.uiState.first { !it.isLoading }

        assertFalse(failedState.isLoading)
        assertNull(failedState.feed)
        assertTrue(failedState.errorMessage!!.contains("Network offline"))

        repo.shouldFailHome = false
        vm.refresh()
        val refreshedState = vm.uiState.first { it.feed != null }
        assertEquals(2, refreshedState.feed?.featuredBanners?.size)

        vm.toggleWatchlist(repo.sampleMovie)
        val watchlistedState = vm.uiState.first { it.watchlist.isNotEmpty() }
        assertEquals(1, watchlistedState.watchlist.size)
    }

    @Test
    fun catalogueViewModel_handlesFiltersPaginationAndSuggestions() = runTest {
        val repo = FakeMoviesRepository()
        val vm = MoviesCatalogueViewModel(repo)
        vm.initialize("s1", initialKind = MediaKind.MOVIE)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.items.size)
        assertTrue(vm.uiState.value.hasMore)

        vm.loadNextPage()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.items.size)
        assertFalse(vm.uiState.value.hasMore)

        vm.onQueryChange("Girl")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.suggestions.size)
        assertEquals("His Girl Friday", vm.uiState.value.suggestions.first().title)
    }

    @Test
    fun detailViewModel_loadsSeriesSeasonsEpisodesAndUnavailableStatus() = runTest {
        val repo = FakeMoviesRepository()
        val vm = MovieDetailViewModel(repo)
        vm.load("tvmaze", "169")
        advanceUntilIdle()

        assertEquals("Breaking Bad", vm.uiState.value.details?.summary?.title)
        assertEquals(1, vm.uiState.value.selectedSeasonNumber)
        assertEquals("Episode S1E1", vm.uiState.value.episodes.first().title)
        assertTrue(vm.uiState.value.playbackResolution is PlaybackResolution.Unavailable)

        vm.selectSeason(2)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.selectedSeasonNumber)
        assertEquals("Episode S2E1", vm.uiState.value.episodes.first().title)
    }

    @Test
    fun playerViewModel_resolvesStreamsSwitchesQualityAndSubtitlesAndSavesProgress() = runTest {
        val repo = FakeMoviesRepository()
        val vm = MoviePlayerViewModel(repo)
        vm.load("ia", "his_girl_friday")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.resolution is PlaybackResolution.Available)
        assertEquals("720p", state.selectedSource?.qualityLabel)
        assertEquals("en", state.selectedSubtitleTrack?.languageCode)
        assertEquals(45_000L, state.startPositionMs)

        val altSource = (state.resolution as PlaybackResolution.Available).sources[1]
        vm.selectSource(altSource, 60_000L)
        assertEquals("480p", vm.uiState.value.selectedSource?.qualityLabel)
        assertEquals(60_000L, vm.uiState.value.startPositionMs)

        vm.selectSubtitle(null, 62_000L)
        assertNull(vm.uiState.value.selectedSubtitleTrack)

        vm.saveProgress(90_000L, 500_000L)
        advanceUntilIdle()
        assertEquals(listOf(90_000L to 500_000L), repo.savedProgressCalls)
    }
}
