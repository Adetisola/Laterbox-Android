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
        data class CatalogueCall(
            val kind: MediaKind?,
            val genre: String?,
            val sort: CatalogueSort,
            val country: String?,
            val year: String?
        )
        val catalogueCalls = mutableListOf<CatalogueCall>()

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
            sort: CatalogueSort,
            country: String?,
            year: String?
        ): Result<CataloguePage> {
            catalogueCalls.add(CatalogueCall(kind, genre, sort, country, year))
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

        override suspend fun getGenres(): Result<List<String>> = Result.success(listOf("All", "Comedy", "Drama", "Fantasy"))

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

    @Test
    fun homeViewModel_handlesInlineSearchSuggestionsFiltersAndTmdbPromptDismiss() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UserPreferencesRepository(context)
        val repo = FakeMoviesRepository()
        val vm = MoviesHomeViewModel(repo, prefs)
        vm.uiState.first { !it.isLoading }

        // Select Kind filter -> enters filtered mode and loads catalogue
        vm.selectKind(MediaKind.MOVIE)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isFilteredMode)
        assertEquals(1, vm.uiState.value.filteredItems.size)
        assertTrue(vm.uiState.value.filteredHasMore)

        // Load more filtered
        vm.loadMoreFiltered()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.filteredItems.size)
        assertFalse(vm.uiState.value.filteredHasMore)

        // Inline suggestions + submit search
        vm.onSearchQueryChange("His")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.suggestions.size)

        vm.submitInlineSearch()
        advanceUntilIdle()
        assertEquals("His", vm.uiState.value.activeSearchQuery)
        assertEquals(1, vm.uiState.value.filteredItems.size)

        // Reset filters exits filtered mode
        vm.resetFilters()
        assertFalse(vm.uiState.value.isFilteredMode)
        assertTrue(vm.uiState.value.filteredItems.isEmpty())
    }

    @Test
    fun detailAndPlayerViewModels_enqueueDownloadsViaBridgeAndUpdateStatus() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.laterbox.app.data.local.LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val linkRepo = com.laterbox.app.data.repository.LinkRepository(db.savedLinkDao())
            val enqueuedTitles = mutableListOf<String?>()
            val fakeMediaRepo = object : com.laterbox.app.media.domain.repository.MediaRepository {
                override fun getAssetsForLink(linkId: Long) = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.MediaAsset>())
                override fun getAllAssets() = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.MediaAsset>())
                override fun filterAssets(mediaType: com.laterbox.app.media.domain.model.MediaType?, query: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.MediaAsset>())
                override fun getAssetById(id: String) = kotlinx.coroutines.flow.flowOf(null)
                override fun getTasksForLink(linkId: Long) = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.DownloadTask>())
                override fun getQueueTasks() = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.DownloadTask>())
                override fun getTaskById(taskId: String) = kotlinx.coroutines.flow.flowOf(null)
                override fun getStorageBreakdown() = kotlinx.coroutines.flow.flowOf(emptyMap<com.laterbox.app.media.domain.model.MediaType, Long>())
                override fun getAssetCount() = kotlinx.coroutines.flow.flowOf(0)
                override suspend fun enqueueDownload(
                    linkId: Long,
                    candidate: com.laterbox.app.media.domain.model.MediaCandidate,
                    sourcePlatform: String,
                    title: String?,
                    artist: String?,
                    album: String?,
                    artworkUrl: String?,
                    spotifyTrackId: String?,
                    versionTag: String?
                ): String {
                    enqueuedTitles.add(title)
                    return "dl_${enqueuedTitles.size}"
                }
                override suspend fun enqueueStandaloneDownload(
                    candidate: com.laterbox.app.media.domain.model.MediaCandidate,
                    sourcePlatform: String,
                    title: String?,
                    artworkUrl: String?,
                    versionTag: String?,
                    contentCategory: String?,
                    sourceRef: String?,
                    urlExpiresAt: Long?
                ): String {
                    enqueuedTitles.add(title)
                    return "dl_${enqueuedTitles.size}"
                }
                override suspend fun overrideCellularRequirement(taskId: String) {}
                override suspend fun pauseDownload(taskId: String) {}
                override suspend fun resumeDownload(taskId: String) {}
                override suspend fun cancelDownload(taskId: String) {}
                override suspend fun dismissTask(taskId: String) {}
                override suspend fun clearTerminalTasks() {}
                override suspend fun retryDownload(taskId: String) {}
                override suspend fun deleteAsset(assetId: String) {}
                override suspend fun exportAsset(assetId: String) = Result.success("")
                override suspend fun deleteAssetsForLink(linkId: Long, deleteFiles: Boolean) {}
                override suspend fun runOrphanSweep() {}
                override suspend fun recoverInterruptedTasks() {}
                override fun getInspectionForLink(linkId: Long) = kotlinx.coroutines.flow.flowOf(null)
                override fun isInspectingLink(linkId: Long) = kotlinx.coroutines.flow.flowOf(false)
                override fun startInspection(linkId: Long, url: String, previewImageUrl: String?, forceRefresh: Boolean) {}
                override suspend fun cancelInspection(linkId: Long) {}
            }

            val bridge = com.laterbox.app.movies.data.download.MoviesDownloadBridge(fakeMediaRepo)
            val repo = FakeMoviesRepository()

            val detailVm = MovieDetailViewModel(repo, bridge)
            detailVm.load("ia", "his_girl_friday")
            advanceUntilIdle()
            val src720 = (detailVm.uiState.value.playbackResolution as PlaybackResolution.Available).sources.first()
            detailVm.downloadSource(src720)
            val detailDownloaded = detailVm.uiState.first { it.downloadStatusMessage != null }
            assertTrue(
                "Unexpected status: ${detailDownloaded.downloadStatusMessage}",
                detailDownloaded.downloadStatusMessage?.contains("720p") == true
            )
            detailVm.clearDownloadStatus()
            assertNull(detailVm.uiState.value.downloadStatusMessage)

            val playerVm = MoviePlayerViewModel(repo, bridge)
            playerVm.load("ia", "his_girl_friday")
            advanceUntilIdle()
            val src480 = (playerVm.uiState.value.resolution as PlaybackResolution.Available).sources[1]
            playerVm.downloadSource(src480)
            val playerDownloaded = playerVm.uiState.first { it.downloadStatusMessage != null }
            assertTrue(
                "Unexpected status: ${playerDownloaded.downloadStatusMessage}",
                playerDownloaded.downloadStatusMessage?.contains("480p") == true
            )
            assertEquals(listOf("His Girl Friday (720p)", "His Girl Friday (480p)"), enqueuedTitles)
        } finally {
            db.close()
        }
    }

    @Test
    fun homeViewModel_updatesMovieBoxConfigAndTriggersRefresh() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UserPreferencesRepository(context)
        val repo = FakeMoviesRepository()
        val vm = MoviesHomeViewModel(repo, prefs)
        vm.uiState.first { !it.isLoading }

        val callsBefore = repo.homeCallCount
        vm.setMovieBoxBaseUrl("https://movies.example.com/")
        val updatedConfigState = vm.uiState.first {
            it.movieBoxApiBaseUrl == "https://movies.example.com/" && !it.isRefreshing
        }
        assertEquals("https://movies.example.com/", updatedConfigState.effectiveMovieBoxBaseUrl)
        assertTrue(repo.homeCallCount > callsBefore)
    }

    @Test
    fun detailViewModel_loadsDebugProviderLinksAndUnlinksSelectedProvider() = runTest {
        val baseFake = FakeMoviesRepository()
        val linksState = MutableStateFlow(
            listOf(
                com.laterbox.app.movies.domain.model.ProviderLinkRecord("cid_1", "moviebox", "mb_1", "T1", 100L),
                com.laterbox.app.movies.domain.model.ProviderLinkRecord("cid_1", "tvmaze", "169", "T1", 100L)
            )
        )
        val customRepo = object : MoviesRepository by baseFake {
            override suspend fun getProviderLinks(providerId: String, titleId: String) = linksState.value
            override suspend fun unlinkProviderTitle(providerId: String, titleId: String): String {
                linksState.value = linksState.value.filterNot { it.providerId == providerId && it.providerTitleId == titleId }
                return "cid_isolated"
            }
        }

        val vm = MovieDetailViewModel(customRepo)
        vm.load("moviebox", "mb_1")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.providerLinks.size)

        vm.unlinkProviderLink("tvmaze", "169")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.providerLinks.size)
        assertEquals("moviebox", vm.uiState.value.providerLinks.first().providerId)
    }

    @Test
    fun detailViewModel_selectSeasonRefreshesPlaybackAndLabelsEpisodeDownloads() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.laterbox.app.data.local.LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val linkRepo = com.laterbox.app.data.repository.LinkRepository(db.savedLinkDao())
            val enqueuedTitles = mutableListOf<String?>()
            val resolvedPairs = mutableListOf<Pair<Int?, Int?>>()
            var failNextPlayback = false

            val seriesSummary = MovieTitleSummary(
                id = "mb_series_1",
                providerId = "moviebox",
                kind = MediaKind.TV_SERIES,
                title = "Breaking Bad",
                releaseYear = 2008,
                sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                isPlayableHint = true
            )
            val baseFake = FakeMoviesRepository()
            val customRepo = object : MoviesRepository by baseFake {
                override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean) =
                    Result.success(
                        MediaDetails(
                            summary = seriesSummary,
                            fullDescription = "Chemistry teacher series.",
                            seasons = listOf(SeasonSummary(1, "Season 1", 7), SeasonSummary(2, "Season 2", 13))
                        )
                    )

                override suspend fun resolvePlayback(
                    providerId: String,
                    titleId: String,
                    seasonNumber: Int?,
                    episodeNumber: Int?
                ): Result<PlaybackResolution> {
                    resolvedPairs.add(seasonNumber to episodeNumber)
                    if (failNextPlayback) {
                        return Result.failure(IllegalStateException("Upstream bootstrap failed"))
                    }
                    return Result.success(
                        PlaybackResolution.Available(
                            title = "Breaking Bad",
                            subtitleHeading = "Season $seasonNumber · Episode $episodeNumber",
                            sources = listOf(
                                PlaybackSource("s1", "https://cdn.example/s${seasonNumber}e${episodeNumber}.mp4", "MP4", "720p", 720, providerName = "MovieBox")
                            ),
                            providerName = "MovieBox",
                            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
                        )
                    )
                }
            }

            val fakeMediaRepo = object : com.laterbox.app.media.domain.repository.MediaRepository {
                override fun getAssetsForLink(linkId: Long) = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.MediaAsset>())
                override fun getAllAssets() = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.MediaAsset>())
                override fun filterAssets(mediaType: com.laterbox.app.media.domain.model.MediaType?, query: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.MediaAsset>())
                override fun getAssetById(id: String) = kotlinx.coroutines.flow.flowOf(null)
                override fun getTasksForLink(linkId: Long) = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.DownloadTask>())
                override fun getQueueTasks() = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.DownloadTask>())
                override fun getTaskById(taskId: String) = kotlinx.coroutines.flow.flowOf(null)
                override fun getStorageBreakdown() = kotlinx.coroutines.flow.flowOf(emptyMap<com.laterbox.app.media.domain.model.MediaType, Long>())
                override fun getAssetCount() = kotlinx.coroutines.flow.flowOf(0)
                override suspend fun enqueueDownload(
                    linkId: Long,
                    candidate: com.laterbox.app.media.domain.model.MediaCandidate,
                    sourcePlatform: String,
                    title: String?,
                    artist: String?,
                    album: String?,
                    artworkUrl: String?,
                    spotifyTrackId: String?,
                    versionTag: String?
                ): String {
                    enqueuedTitles.add(title)
                    return "dl_1"
                }
                override suspend fun enqueueStandaloneDownload(
                    candidate: com.laterbox.app.media.domain.model.MediaCandidate,
                    sourcePlatform: String,
                    title: String?,
                    artworkUrl: String?,
                    versionTag: String?,
                    contentCategory: String?,
                    sourceRef: String?,
                    urlExpiresAt: Long?
                ): String {
                    enqueuedTitles.add(title)
                    return "dl_1"
                }
                override suspend fun overrideCellularRequirement(taskId: String) {}
                override suspend fun pauseDownload(taskId: String) {}
                override suspend fun resumeDownload(taskId: String) {}
                override suspend fun cancelDownload(taskId: String) {}
                override suspend fun dismissTask(taskId: String) {}
                override suspend fun clearTerminalTasks() {}
                override suspend fun retryDownload(taskId: String) {}
                override suspend fun deleteAsset(assetId: String) {}
                override suspend fun exportAsset(assetId: String) = Result.success("")
                override suspend fun deleteAssetsForLink(linkId: Long, deleteFiles: Boolean) {}
                override suspend fun runOrphanSweep() {}
                override suspend fun recoverInterruptedTasks() {}
                override fun getInspectionForLink(linkId: Long) = kotlinx.coroutines.flow.flowOf(null)
                override fun isInspectingLink(linkId: Long) = kotlinx.coroutines.flow.flowOf(false)
                override fun startInspection(linkId: Long, url: String, previewImageUrl: String?, forceRefresh: Boolean) {}
                override suspend fun cancelInspection(linkId: Long) {}
            }

            val bridge = com.laterbox.app.movies.data.download.MoviesDownloadBridge(fakeMediaRepo)
            val vm = MovieDetailViewModel(customRepo, bridge)
            vm.load("moviebox", "mb_series_1")
            advanceUntilIdle()
            assertEquals(listOf(1 to 1), resolvedPairs)

            vm.selectSeason(2)
            advanceUntilIdle()
            assertEquals(listOf(1 to 1, 2 to 1), resolvedPairs)
            val src = (vm.uiState.value.playbackResolution as PlaybackResolution.Available).sources.first()
            vm.downloadSource(src)
            vm.uiState.first { it.downloadStatusMessage != null }
            assertEquals(listOf("Breaking Bad · S2 · E1 (720p)"), enqueuedTitles)

            failNextPlayback = true
            vm.retryPlaybackCheck()
            advanceUntilIdle()
            assertEquals("Upstream bootstrap failed", vm.uiState.value.playbackError)
        } finally {
            db.close()
        }
    }

    @Test
    fun playerViewModel_switchesAudioDubPreservingPositionAndAdvancesNextEpisode() = runTest {
        val baseFake = FakeMoviesRepository()
        val seriesSummary = MovieTitleSummary(
            id = "mb_series_en",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Severance",
            releaseYear = 2022,
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )
        val frenchSummary = seriesSummary.copy(id = "mb_series_fr", title = "Severance (VF)")
        val dubs = listOf(
            com.laterbox.app.movies.domain.model.AudioDubInfo(language = "en", label = "English", referenceId = "mb_series_en"),
            com.laterbox.app.movies.domain.model.AudioDubInfo(language = "fr", label = "French", referenceId = "mb_series_fr")
        )
        val seasons = listOf(SeasonSummary(1, "Season 1", 2), SeasonSummary(2, "Season 2", 4))
        val customRepo = object : MoviesRepository by baseFake {
            override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean) =
                Result.success(
                    MediaDetails(
                        summary = if (titleId == "mb_series_fr") frenchSummary else seriesSummary,
                        fullDescription = "Series synopsis.",
                        audioDubs = dubs,
                        seasons = seasons
                    )
                )

            override suspend fun resolvePlayback(
                providerId: String,
                titleId: String,
                seasonNumber: Int?,
                episodeNumber: Int?
            ): Result<PlaybackResolution> {
                return Result.success(
                    PlaybackResolution.Available(
                        title = if (titleId == "mb_series_fr") "Severance (VF)" else "Severance",
                        subtitleHeading = "S$seasonNumber · E$episodeNumber",
                        sources = listOf(
                            PlaybackSource("${titleId}_720", "https://cdn.example/$titleId/s${seasonNumber}e${episodeNumber}.mp4", "MP4", "720p", 720, providerName = "MovieBox")
                        ),
                        subtitles = listOf(
                            SubtitleTrack("sub_en", "English", "en", "English", "https://cdn.example/en.srt")
                        ),
                        startPositionMs = 12_000L,
                        providerName = "MovieBox",
                        sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
                    )
                )
            }
        }

        val vm = MoviePlayerViewModel(customRepo)
        vm.load("moviebox", "mb_series_en", seasonNumber = 1, episodeNumber = 1)
        advanceUntilIdle()

        assertEquals("mb_series_en", vm.uiState.value.activeTitleId)
        assertEquals(2, vm.uiState.value.availableDubs.size)
        assertEquals(1 to 2, vm.uiState.value.nextEpisodeTarget)

        // Switch audio dub while preserving playback position
        vm.selectAudioDub(dubs[1], currentPositionMs = 95_000L)
        advanceUntilIdle()
        assertEquals("mb_series_fr", vm.uiState.value.activeTitleId)
        assertEquals(95_000L, vm.uiState.value.startPositionMs)
        assertEquals("mb_series_fr_720", vm.uiState.value.selectedSource?.id)

        // Advance to S1E2, then across boundary to S2E1
        vm.playNextEpisode()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.seasonNumber)
        assertEquals(2, vm.uiState.value.episodeNumber)
        assertEquals(2 to 1, vm.uiState.value.nextEpisodeTarget)

        vm.playNextEpisode()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.seasonNumber)
        assertEquals(1, vm.uiState.value.episodeNumber)
        assertEquals(2 to 2, vm.uiState.value.nextEpisodeTarget)
    }

    @Test
    fun catalogueViewModel_searchFocusAndDismissalTrackLoadingErrorAndCloseCleanly() = runTest {
        val failingSuggestRepo = object : MoviesRepository by FakeMoviesRepository() {
            override suspend fun getSuggestions(query: String, limit: Int): Result<List<SearchSuggestion>> =
                Result.failure(IllegalStateException("Search service busy"))
        }
        val vm = MoviesCatalogueViewModel(failingSuggestRepo)
        vm.initialize("s_focus")
        advanceUntilIdle()

        vm.onSearchFocusChange(true)
        assertTrue(vm.uiState.value.isSearchFocused)
        assertTrue(vm.uiState.value.showSuggestionsDropdown)

        vm.onQueryChange("incep")
        assertTrue(vm.uiState.value.isLoadingSuggestions)
        assertTrue(vm.uiState.value.showSuggestionsDropdown)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoadingSuggestions)
        assertEquals("Search service busy", vm.uiState.value.suggestionsError)

        vm.onSearchFocusChange(false)
        assertFalse(vm.uiState.value.isSearchFocused)
        assertFalse(vm.uiState.value.showSuggestionsDropdown)
        assertFalse(vm.uiState.value.isLoadingSuggestions)
    }

    @Test
    fun playerViewModel_updatesStartPositionOnSaveProgressAndDirectPositionUpdate() = runTest {
        val repo = FakeMoviesRepository()
        val vm = MoviePlayerViewModel(repo)
        vm.load("ia", "his_girl_friday")
        advanceUntilIdle()

        assertEquals(45_000L, vm.uiState.value.startPositionMs)
        vm.updatePlaybackPosition(132_500L)
        assertEquals(132_500L, vm.uiState.value.startPositionMs)

        vm.saveProgress(180_000L, 600_000L)
        advanceUntilIdle()
        assertEquals(180_000L, vm.uiState.value.startPositionMs)
    }

    @Test
    fun homeViewModel_loadsTopRatedAndNewestRailsAndAppliesShortcutsAndCountries() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UserPreferencesRepository(context)
        val repo = FakeMoviesRepository()
        val vm = MoviesHomeViewModel(repo, prefs)
        val initial = vm.uiState.first { !it.isLoading }

        assertTrue(initial.topRatedRail.isNotEmpty())
        assertTrue(initial.newestRail.isNotEmpty())
        assertTrue(repo.catalogueCalls.any { it.sort == CatalogueSort.TOP_RATED })
        assertTrue(repo.catalogueCalls.any { it.sort == CatalogueSort.NEWEST })

        vm.selectShortcut(com.laterbox.app.movies.domain.model.MoviesDiscoveryShortcut.K_DRAMA)
        advanceUntilIdle()
        assertEquals(com.laterbox.app.movies.domain.model.MoviesDiscoveryShortcut.K_DRAMA, vm.uiState.value.activeShortcut)
        assertEquals(MediaKind.TV_SERIES, vm.uiState.value.selectedKind)
        assertEquals("Drama", vm.uiState.value.selectedGenre)
        assertEquals("Korea", vm.uiState.value.selectedCountry)
        assertTrue(vm.uiState.value.isFilteredMode)
        val kDramaCall = repo.catalogueCalls.last()
        assertEquals(MediaKind.TV_SERIES, kDramaCall.kind)
        assertEquals("Drama", kDramaCall.genre)
        assertEquals("Korea", kDramaCall.country)

        vm.selectShortcut(com.laterbox.app.movies.domain.model.MoviesDiscoveryShortcut.K_DRAMA)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isFilteredMode)

        vm.selectCountry("India")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isFilteredMode)
        assertEquals("India", repo.catalogueCalls.last().country)
    }

    @Test
    fun catalogueViewModel_excludesFantasyAndSupportsShortcutsAndCountries() = runTest {
        val repo = FakeMoviesRepository()
        val vm = MoviesCatalogueViewModel(repo)
        vm.initialize("m2_cat")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.availableGenres.any { it.equals("Fantasy", ignoreCase = true) })

        vm.selectShortcut(com.laterbox.app.movies.domain.model.MoviesDiscoveryShortcut.HOLLYWOOD)
        advanceUntilIdle()
        assertEquals(MediaKind.MOVIE, vm.uiState.value.selectedKind)
        assertEquals("United States", vm.uiState.value.selectedCountry)
        assertEquals("United States", repo.catalogueCalls.last().country)

        vm.resetFilters()
        advanceUntilIdle()
        assertNull(vm.uiState.value.activeShortcut)
        assertEquals("All", vm.uiState.value.selectedCountry)
        assertEquals("All", vm.uiState.value.selectedGenre)
    }

    @Test
    fun detailViewModel_loadsPersonFilmographyAndBatchDownloadsSelectedEpisodes() = runTest {
        val enqueuedTitles = mutableListOf<String?>()
        val seriesSummary = MovieTitleSummary(
            id = "mb_s",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Severance",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )
        val baseFake = FakeMoviesRepository()
        val customRepo = object : MoviesRepository by baseFake {
            override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean) =
                Result.success(
                    MediaDetails(
                        summary = seriesSummary,
                        cast = listOf(
                            com.laterbox.app.movies.domain.model.CastMember(
                                name = "Adam Scott",
                                role = "Mark S.",
                                personId = "p_adam",
                                providerId = "tvmaze"
                            )
                        ),
                        seasons = listOf(SeasonSummary(1, "Season 1", 4, availableEpisodes = listOf(1, 2, 3, 4)))
                    )
                )

            override suspend fun getPersonFilmography(
                providerId: String,
                personId: String,
                personName: String,
                personImageUrl: String?
            ) = Result.success(
                com.laterbox.app.movies.domain.model.PersonFilmography(
                    personId = personId,
                    providerId = providerId,
                    name = personName,
                    credits = listOf(baseFake.sampleSeries.copy(title = "Parks and Recreation"))
                )
            )

            override suspend fun resolvePlayback(
                providerId: String,
                titleId: String,
                seasonNumber: Int?,
                episodeNumber: Int?
            ): Result<PlaybackResolution> {
                return Result.success(
                    PlaybackResolution.Available(
                        title = "Severance",
                        sources = listOf(
                            PlaybackSource("s1080", "https://cdn.example/s${seasonNumber}e${episodeNumber}_1080.mp4", "MP4", "1080p", 1080, providerName = "MovieBox"),
                            PlaybackSource("s720", "https://cdn.example/s${seasonNumber}e${episodeNumber}_720.mp4", "MP4", "720p", 720, providerName = "MovieBox")
                        ),
                        providerName = "MovieBox",
                        sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
                    )
                )
            }
        }
        val fakeMediaRepo = object : com.laterbox.app.media.domain.repository.MediaRepository {
            override fun getAssetsForLink(linkId: Long) = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.MediaAsset>())
            override fun getAllAssets() = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.MediaAsset>())
            override fun filterAssets(mediaType: com.laterbox.app.media.domain.model.MediaType?, query: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.MediaAsset>())
            override fun getAssetById(id: String) = kotlinx.coroutines.flow.flowOf(null)
            override fun getTasksForLink(linkId: Long) = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.DownloadTask>())
            override fun getQueueTasks() = kotlinx.coroutines.flow.flowOf(emptyList<com.laterbox.app.media.domain.model.DownloadTask>())
            override fun getTaskById(taskId: String) = kotlinx.coroutines.flow.flowOf(null)
            override fun getStorageBreakdown() = kotlinx.coroutines.flow.flowOf(emptyMap<com.laterbox.app.media.domain.model.MediaType, Long>())
            override fun getAssetCount() = kotlinx.coroutines.flow.flowOf(0)
            override suspend fun enqueueDownload(
                linkId: Long,
                candidate: com.laterbox.app.media.domain.model.MediaCandidate,
                sourcePlatform: String,
                title: String?,
                artist: String?,
                album: String?,
                artworkUrl: String?,
                spotifyTrackId: String?,
                versionTag: String?
            ) = "dl"
            override suspend fun enqueueStandaloneDownload(
                candidate: com.laterbox.app.media.domain.model.MediaCandidate,
                sourcePlatform: String,
                title: String?,
                artworkUrl: String?,
                versionTag: String?,
                contentCategory: String?,
                sourceRef: String?,
                urlExpiresAt: Long?
            ): String {
                enqueuedTitles.add(title)
                return "dl_${enqueuedTitles.size}"
            }
            override suspend fun overrideCellularRequirement(taskId: String) {}
            override suspend fun pauseDownload(taskId: String) {}
            override suspend fun resumeDownload(taskId: String) {}
            override suspend fun cancelDownload(taskId: String) {}
            override suspend fun dismissTask(taskId: String) {}
            override suspend fun clearTerminalTasks() {}
            override suspend fun retryDownload(taskId: String) {}
            override suspend fun deleteAsset(assetId: String) {}
            override suspend fun exportAsset(assetId: String) = Result.success("")
            override suspend fun deleteAssetsForLink(linkId: Long, deleteFiles: Boolean) {}
            override suspend fun runOrphanSweep() {}
            override suspend fun recoverInterruptedTasks() {}
            override fun getInspectionForLink(linkId: Long) = kotlinx.coroutines.flow.flowOf(null)
            override fun isInspectingLink(linkId: Long) = kotlinx.coroutines.flow.flowOf(false)
            override fun startInspection(linkId: Long, url: String, previewImageUrl: String?, forceRefresh: Boolean) {}
            override suspend fun cancelInspection(linkId: Long) {}
        }

        val vm = MovieDetailViewModel(customRepo, com.laterbox.app.movies.data.download.MoviesDownloadBridge(fakeMediaRepo))
        vm.load("moviebox", "mb_s")
        advanceUntilIdle()

        val castMember = vm.uiState.value.details!!.cast.first()
        vm.openCastMember(castMember)
        advanceUntilIdle()
        assertEquals("Adam Scott", vm.uiState.value.selectedPerson?.name)
        assertEquals("Parks and Recreation", vm.uiState.value.selectedPerson?.credits?.firstOrNull()?.title)
        vm.dismissPersonSheet()
        assertNull(vm.uiState.value.activePersonMember)

        vm.downloadEpisodesBatch(seasonNumber = 1, episodeNumbers = listOf(1, 3), preferredQualityHeight = 720)
        advanceUntilIdle()
        assertEquals(
            listOf("Severance · S1 · E1 (720p)", "Severance · S1 · E3 (720p)"),
            enqueuedTitles
        )
        assertTrue(vm.uiState.value.downloadStatusMessage?.contains("Queued 2 episodes") == true)

        // Requesting 480p when only 1080p and 720p exist must NOT silently substitute a higher resolution
        enqueuedTitles.clear()
        vm.downloadEpisodesBatch(seasonNumber = 1, episodeNumbers = listOf(1), preferredQualityHeight = 480)
        advanceUntilIdle()
        assertTrue(enqueuedTitles.isEmpty())
    }

    @Test
    fun playerViewModel_picksPreferredDefaultQualityLoadsNextEpisodePreviewAndRelatedTitles() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UserPreferencesRepository(context)
        prefs.setMoviesDefaultQualityHeight(1080)

        val baseFake = FakeMoviesRepository()
        val customRepo = object : MoviesRepository by baseFake {
            override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean) =
                Result.success(
                    MediaDetails(
                        summary = baseFake.sampleSeries.copy(
                            id = "mb_series",
                            providerId = "moviebox",
                            backdropUrl = "https://cdn.example/series_backdrop.jpg"
                        ),
                        seasons = listOf(SeasonSummary(1, "Season 1", 4)),
                        relatedTitles = listOf(baseFake.sampleMovie)
                    )
                )

            override suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int) =
                Result.success(
                    listOf(
                        EpisodeItem("ep1", seasonNumber, 1, "Good News"),
                        EpisodeItem("ep2", seasonNumber, 2, "Half Loop", stillUrl = "https://cdn.example/ep2_still.jpg")
                    )
                )

            override suspend fun resolvePlayback(
                providerId: String,
                titleId: String,
                seasonNumber: Int?,
                episodeNumber: Int?
            ): Result<PlaybackResolution> {
                return Result.success(
                    PlaybackResolution.Available(
                        title = "Breaking Bad",
                        sources = listOf(
                            PlaybackSource("s1080", "https://cdn.example/1080.mp4", "MP4", "1080p", 1080, providerName = "MovieBox"),
                            PlaybackSource("s720", "https://cdn.example/720.mp4", "MP4", "720p", 720, providerName = "MovieBox"),
                            PlaybackSource("s480", "https://cdn.example/480.mp4", "MP4", "480p", 480, providerName = "MovieBox")
                        ),
                        providerName = "MovieBox",
                        sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
                    )
                )
            }
        }

        val vm = MoviePlayerViewModel(customRepo, userPreferencesRepository = prefs)
        vm.load("moviebox", "mb_series", seasonNumber = 1, episodeNumber = 1)
        val state = vm.uiState.first { !it.isLoading }
        assertEquals(1080, state.selectedSource?.qualityHeight)
        assertEquals(1, state.nextEpisodePreview?.seasonNumber)
        assertEquals(2, state.nextEpisodePreview?.episodeNumber)
        assertEquals("Half Loop", state.nextEpisodePreview?.title)
        assertEquals("https://cdn.example/ep2_still.jpg", state.nextEpisodePreview?.thumbnailUrl)
        assertEquals(listOf("his_girl_friday"), state.relatedTitles.map { it.id })

        // Verify pickPreferredSource picks closest <= target, or nearest if all higher
        val pickedLow = MoviePlayerViewModel.pickPreferredSource(
            (state.resolution as PlaybackResolution.Available).sources,
            preferredQualityHeight = 720
        )
        assertEquals(720, pickedLow?.qualityHeight)
    }

    @Test
    fun detailViewModel_controlsInlineTrailerPersistsMutePreferenceAndHandlesError() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UserPreferencesRepository(context)
        prefs.setMoviesTrailerMuted(false)

        val trailerCalls = mutableListOf<Triple<String, Long, Long>>()
        val baseFake = FakeMoviesRepository()
        val customRepo = object : MoviesRepository by baseFake {
            override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean) =
                Result.success(
                    MediaDetails(
                        summary = baseFake.sampleSeries.copy(
                            id = "mb_series",
                            providerId = "moviebox",
                            trailerUrl = "https://cdn.example/trailer.mp4"
                        ),
                        seasons = listOf(SeasonSummary(1, "Season 1", 7))
                    )
                )

            override suspend fun recordTrailerWatch(item: MovieTitleSummary, positionMs: Long, durationMs: Long) {
                trailerCalls.add(Triple(item.id, positionMs, durationMs))
            }
        }

        val vm = MovieDetailViewModel(customRepo, userPreferencesRepository = prefs)
        vm.load("moviebox", "mb_series")
        vm.uiState.first { !it.isLoading && it.details != null }

        // Does NOT autoplay when detail opens
        assertFalse(vm.uiState.value.isTrailerPlaying)
        assertFalse(vm.uiState.value.trailerMuted)

        // Start inline trailer -> unmuted by default, records trailer watch
        vm.onPlayTrailerClick()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isTrailerPlaying)
        assertNull(vm.uiState.value.trailerError)
        assertEquals(1, trailerCalls.size)

        // Toggle mute -> updates state & persists preference
        vm.onToggleTrailerMute()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.trailerMuted)
        assertTrue(prefs.userPreferencesFlow.first { it.moviesTrailerMuted }.moviesTrailerMuted)

        // Fullscreen expansion preserves current timestamp & position update
        vm.onSetTrailerFullscreen(fullscreen = true, currentPositionMs = 24_500L)
        assertTrue(vm.uiState.value.isTrailerFullscreen)
        assertEquals(24_500L, vm.uiState.value.trailerPositionMs)

        vm.onSetTrailerFullscreen(fullscreen = false, currentPositionMs = 31_000L)
        assertFalse(vm.uiState.value.isTrailerFullscreen)
        assertEquals(31_000L, vm.uiState.value.trailerPositionMs)

        // Error stops cleanly with message
        vm.onTrailerPlaybackError("Trailer stream unavailable")
        assertFalse(vm.uiState.value.isTrailerPlaying)
        assertFalse(vm.uiState.value.isTrailerFullscreen)
        assertEquals("Trailer stream unavailable", vm.uiState.value.trailerError)

        // Clean stop resets state
        vm.onPlayTrailerClick()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isTrailerPlaying)
        vm.onStopTrailer()
        assertFalse(vm.uiState.value.isTrailerPlaying)
    }
}
