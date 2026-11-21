package com.laterbox.app.movies

import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.EpisodeProgressState
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SeriesProgressSummary
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import com.laterbox.app.movies.ui.MovieDetailViewModel
import com.laterbox.app.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class MoviesWatchHistoryToggleTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class InMemoryMoviesRepo(
        private val detailsByKey: Map<String, MediaDetails>,
        private val episodesBySeasonKey: Map<Pair<String, Int>, List<EpisodeItem>> = emptyMap()
    ) : MoviesRepository {
        val movieHistoryFlow = MutableStateFlow<List<WatchHistoryItem>>(emptyList())
        val seriesHistoryFlow = MutableStateFlow<Map<Pair<Int, Int>, EpisodeProgressState>>(emptyMap())
        val watchlistKeys = MutableStateFlow<Set<String>>(emptySet())
        var rewatchCalls = 0
            private set

        override suspend fun getHomeFeed(forceRefresh: Boolean) = Result.success(MoviesHomeFeed())
        override suspend fun getCatalogue(
            kind: MediaKind?,
            page: Int,
            pageSize: Int,
            genre: String?,
            sort: CatalogueSort,
            country: String?,
            year: String?
        ) = Result.success(CataloguePage())
        override suspend fun getGenres() = Result.success(emptyList<String>())
        override suspend fun search(query: String, kind: MediaKind?, page: Int, pageSize: Int) =
            Result.success(CataloguePage())
        override suspend fun getSuggestions(query: String, limit: Int) =
            Result.success(emptyList<SearchSuggestion>())

        override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean): Result<MediaDetails> {
            val details = detailsByKey["$providerId:$titleId"]
                ?: return Result.failure(IllegalArgumentException("Not found"))
            return Result.success(details)
        }

        override suspend fun getSeasons(providerId: String, seriesId: String): Result<List<SeasonSummary>> =
            Result.success(detailsByKey["$providerId:$seriesId"]?.seasons.orEmpty())

        override suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int): Result<List<EpisodeItem>> =
            Result.success(episodesBySeasonKey["$providerId:$seriesId" to seasonNumber].orEmpty())

        override suspend fun resolvePlayback(
            providerId: String,
            titleId: String,
            seasonNumber: Int?,
            episodeNumber: Int?
        ): Result<PlaybackResolution> = Result.success(
            PlaybackResolution.Available(
                title = "Sample",
                sources = listOf(
                    PlaybackSource(
                        id = "s1",
                        url = "https://example.com/v.mp4",
                        format = "MP4",
                        qualityLabel = "720p",
                        qualityHeight = 720,
                        providerName = "Internet Archive",
                        isAuthorised = true
                    )
                )
            )
        )

        override suspend fun getSubtitles(
            providerId: String,
            titleId: String,
            seasonNumber: Int?,
            episodeNumber: Int?
        ) = Result.success(emptyList<SubtitleTrack>())

        override fun observeWatchlist(): Flow<List<MovieTitleSummary>> = emptyFlow()
        override fun observeIsInWatchlist(compositeId: String): Flow<Boolean> =
            watchlistKeys.map { compositeId in it }
        override suspend fun toggleWatchlist(item: MovieTitleSummary): Boolean = true
        override fun observeContinueWatching(limit: Int): Flow<List<WatchHistoryItem>> = emptyFlow()
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

        override fun observeRecentSearches(limit: Int): Flow<List<String>> = emptyFlow()
        override suspend fun recordSearchQuery(query: String) = Unit
        override suspend fun clearSearchHistory() = Unit

        override fun observeMovieWatchHistory(limit: Int): Flow<List<WatchHistoryItem>> = movieHistoryFlow

        override fun observeSeriesProgress(providerId: String, titleId: String, title: String): Flow<SeriesProgressSummary> =
            seriesHistoryFlow.map { epMap ->
                SeriesProgressSummary(
                    mediaKey = "$providerId:$titleId",
                    compositeId = "$providerId:$titleId",
                    providerId = providerId,
                    titleId = titleId,
                    title = title,
                    totalWatchedEpisodes = epMap.values.count { it.isCompleted },
                    lastWatchedSeason = epMap.keys.maxByOrNull { it.first * 1000 + it.second }?.first,
                    lastWatchedEpisode = epMap.keys.maxByOrNull { it.first * 1000 + it.second }?.second,
                    lastWatchedAt = epMap.values.maxOfOrNull { it.lastWatchedAt } ?: 0L,
                    nextUnwatchedSeason = null,
                    nextUnwatchedEpisode = null,
                    episodeStates = epMap
                )
            }

        override suspend fun computeNextUnwatchedEpisode(
            providerId: String,
            titleId: String,
            seasons: List<SeasonSummary>,
            episodesBySeason: Map<Int, List<EpisodeItem>>
        ): Pair<Int, Int>? {
            val completed = seriesHistoryFlow.value.filterValues { it.isCompleted }.keys
            for (season in seasons.sortedBy { it.seasonNumber }) {
                val loaded = episodesBySeason[season.seasonNumber]
                    ?: episodesBySeasonKey["$providerId:$titleId" to season.seasonNumber]
                if (!loaded.isNullOrEmpty()) {
                    for (ep in loaded.sortedBy { it.episodeNumber }) {
                        if ((season.seasonNumber to ep.episodeNumber) !in completed) {
                            return season.seasonNumber to ep.episodeNumber
                        }
                    }
                } else if (season.availableEpisodes.isNotEmpty()) {
                    for (epNum in season.availableEpisodes.sorted()) {
                        if ((season.seasonNumber to epNum) !in completed) {
                            return season.seasonNumber to epNum
                        }
                    }
                } else if (season.episodeCount > 0) {
                    for (epNum in 1..season.episodeCount) {
                        if ((season.seasonNumber to epNum) !in completed) {
                            return season.seasonNumber to epNum
                        }
                    }
                }
            }
            return null
        }

        override suspend fun markWatched(
            item: MovieTitleSummary,
            durationMs: Long,
            seasonNumber: Int?,
            episodeNumber: Int?,
            episodeLabel: String?,
            isRewatch: Boolean
        ) {
            if (isRewatch) rewatchCalls++
            if (seasonNumber != null && episodeNumber != null) {
                val current = seriesHistoryFlow.value.toMutableMap()
                val existing = current[seasonNumber to episodeNumber]
                current[seasonNumber to episodeNumber] = EpisodeProgressState(
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    isCompleted = true,
                    positionMs = durationMs.coerceAtLeast(1_000L),
                    durationMs = durationMs.coerceAtLeast(1_000L),
                    rewatchCount = if (isRewatch) (existing?.rewatchCount ?: 0) + 1 else (existing?.rewatchCount ?: 0),
                    lastWatchedAt = 1_000L
                )
                seriesHistoryFlow.value = current
            } else {
                val current = movieHistoryFlow.value.toMutableList()
                val existing = current.firstOrNull { it.compositeId == item.compositeId }
                current.removeAll { it.compositeId == item.compositeId }
                current.add(
                    WatchHistoryItem(
                        historyKey = "${item.compositeId}:0:0",
                        compositeId = item.compositeId,
                        providerId = item.providerId,
                        titleId = item.id,
                        kind = item.kind,
                        title = item.title,
                        episodeLabel = null,
                        seasonNumber = null,
                        episodeNumber = null,
                        posterUrl = item.posterUrl,
                        backdropUrl = item.backdropUrl,
                        positionMs = durationMs,
                        durationMs = durationMs,
                        lastWatchedAt = 1_000L,
                        isCompleted = true,
                        rewatchCount = if (isRewatch || existing?.isCompleted == true) (existing?.rewatchCount ?: 0) + 1 else 0
                    )
                )
                movieHistoryFlow.value = current
            }
        }

        override suspend fun markUnwatched(
            providerId: String,
            titleId: String,
            seasonNumber: Int?,
            episodeNumber: Int?
        ) {
            if (seasonNumber == null && episodeNumber == null) {
                movieHistoryFlow.value = movieHistoryFlow.value.filterNot { it.compositeId == "$providerId:$titleId" }
                seriesHistoryFlow.value = emptyMap()
            } else {
                val current = seriesHistoryFlow.value.toMutableMap()
                current.remove(seasonNumber!! to episodeNumber!!)
                seriesHistoryFlow.value = current
            }
        }
    }

    @Test
    fun moviePrimaryWatchedToggle_togglesBetweenWatchedAndUnwatched_withoutLoggingRewatch() = runTest {
        val movie = MovieTitleSummary(
            id = "arrival",
            providerId = "ia",
            kind = MediaKind.MOVIE,
            title = "Arrival",
            durationMinutes = 116,
            sourceBadge = DataSourceBadge.LIVE_INTERNET_ARCHIVE
        )
        val repo = InMemoryMoviesRepo(
            detailsByKey = mapOf("ia:arrival" to MediaDetails(summary = movie))
        )
        val vm = MovieDetailViewModel(moviesRepository = repo)
        vm.load("ia", "arrival")
        vm.uiState.first { !it.isLoading }

        assertFalse(vm.uiState.value.isTitleCompleted)

        // Toggle watched ON
        vm.markTitleWatched(isRewatch = false)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isTitleCompleted)
        assertEquals(0, repo.rewatchCalls)
        assertEquals(0, repo.movieHistoryFlow.value.first().rewatchCount)

        // Toggle watched OFF -> marks unwatched instead of logging rewatch
        vm.markTitleUnwatched()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isTitleCompleted)
        assertEquals(0, repo.rewatchCalls)
        assertTrue(repo.movieHistoryFlow.value.isEmpty())

        // Separate Log Rewatch action still increments rewatch count when already watched
        vm.markTitleWatched(isRewatch = false)
        advanceUntilIdle()
        vm.markTitleWatched(isRewatch = true)
        advanceUntilIdle()
        assertEquals(1, repo.rewatchCalls)
        assertEquals(1, repo.movieHistoryFlow.value.first().rewatchCount)
    }

    @Test
    fun seriesToggle_marksAllKnownSeasonsEpisodesWatchedAndUnwatched_withoutFabricatingFakeEpisodes() = runTest {
        val series = MovieTitleSummary(
            id = "severance",
            providerId = "tvmaze",
            kind = MediaKind.TV_SERIES,
            title = "Severance",
            sourceBadge = DataSourceBadge.LIVE_TVMAZE
        )
        val seasons = listOf(
            SeasonSummary(seasonNumber = 1, episodeCount = 2, availableEpisodes = listOf(1, 2)),
            SeasonSummary(seasonNumber = 2, episodeCount = 2, availableEpisodes = listOf(1, 2))
        )
        val s1Eps = listOf(
            EpisodeItem("e1", 1, 1, "Good News About Hell", runtimeMinutes = 57),
            EpisodeItem("e2", 1, 2, "Half Loop", runtimeMinutes = 47)
        )
        val s2Eps = listOf(
            EpisodeItem("e3", 2, 1, "Hello, Ms. Cobel", runtimeMinutes = 50),
            EpisodeItem("e4", 2, 2, "Goodbye, Mrs. Selvig", runtimeMinutes = 52)
        )
        val repo = InMemoryMoviesRepo(
            detailsByKey = mapOf("tvmaze:severance" to MediaDetails(summary = series, seasons = seasons)),
            episodesBySeasonKey = mapOf(
                ("tvmaze:severance" to 1) to s1Eps,
                ("tvmaze:severance" to 2) to s2Eps
            )
        )
        val vm = MovieDetailViewModel(moviesRepository = repo)
        vm.load("tvmaze", "severance")
        vm.uiState.first { !it.isLoading && it.episodes.isNotEmpty() }

        assertFalse(vm.uiState.value.isTitleCompleted)
        assertEquals(1 to 1, vm.uiState.value.nextUnwatchedTarget)

        // Toggle whole series watched -> all 4 episodes across S1 & S2 marked watched
        vm.markTitleWatched(isRewatch = false)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isTitleCompleted)
        assertNull(vm.uiState.value.nextUnwatchedTarget)
        assertEquals(4, repo.seriesHistoryFlow.value.size)

        // Toggle individual episode (S1E2) unwatched -> series becomes incomplete, others stay watched
        vm.toggleEpisodeWatched(s1Eps[1])
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isTitleCompleted)
        assertEquals(1 to 2, vm.uiState.value.nextUnwatchedTarget)
        assertEquals(3, repo.seriesHistoryFlow.value.size)
        assertFalse((1 to 2) in repo.seriesHistoryFlow.value)
        assertTrue((2 to 2) in repo.seriesHistoryFlow.value)

        // Toggle S1E2 back watched -> series completes again
        vm.toggleEpisodeWatched(s1Eps[1])
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isTitleCompleted)
        assertNull(vm.uiState.value.nextUnwatchedTarget)
        assertEquals(4, repo.seriesHistoryFlow.value.size)

        // Series-level Mark Unwatched clears all episodes across all seasons
        vm.markTitleUnwatched()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isTitleCompleted)
        assertEquals(1 to 1, vm.uiState.value.nextUnwatchedTarget)
        assertTrue(repo.seriesHistoryFlow.value.isEmpty())
    }

    @Test
    fun seriesWithUnknownEpisodes_doesNotFabricateFakeEpisodesOnMarkWatched() = runTest {
        val series = MovieTitleSummary(
            id = "mystery_show",
            providerId = "tvmaze",
            kind = MediaKind.TV_SERIES,
            title = "Mystery Show",
            sourceBadge = DataSourceBadge.LIVE_TVMAZE
        )
        val repo = InMemoryMoviesRepo(
            detailsByKey = mapOf("tvmaze:mystery_show" to MediaDetails(summary = series, seasons = emptyList()))
        )
        val vm = MovieDetailViewModel(moviesRepository = repo)
        vm.load("tvmaze", "mystery_show")
        vm.uiState.first { !it.isLoading }

        vm.markTitleWatched(isRewatch = false)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isTitleCompleted)
        assertTrue(repo.seriesHistoryFlow.value.isEmpty())
        assertEquals("No episode list available to mark watched", vm.uiState.value.actionFeedbackMessage)
    }
}
