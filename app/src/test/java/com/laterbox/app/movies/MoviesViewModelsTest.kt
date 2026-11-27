package com.laterbox.app.movies

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoviesViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun TestScope.createIsolatedPrefs(context: Context): UserPreferencesRepository =
        UserPreferencesRepository(
            context = context,
            dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
                tempFolder.newFile("prefs_${System.nanoTime()}.preferences_pb")
            }
        )

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
                MediaKind.SHORT_DRAMA -> listOf(sampleSeries.copy(id = "short_1", kind = MediaKind.SHORT_DRAMA, title = "Sample Short Drama"))
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
        val prefs = createIsolatedPrefs(context)
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
        val prefs = createIsolatedPrefs(context)
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
        val prefs = createIsolatedPrefs(context)
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
        val prefs = createIsolatedPrefs(context)
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
    fun catalogueViewModel_retainsVerifiedGenresAndSupportsShortcutsAndCountries() = runTest {
        val repo = FakeMoviesRepository()
        val vm = MoviesCatalogueViewModel(repo)
        vm.initialize("m2_cat")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.availableGenres.contains("Anime"))
        assertTrue(vm.uiState.value.availableGenres.contains("Sci-Fi & Fantasy"))

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
        val prefs = createIsolatedPrefs(context)
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
        val prefs = createIsolatedPrefs(context)
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

    @Test
    fun homeViewModel_expandedFiltersSurpriseRerollAndContextualLibraryActionsWork() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.laterbox.app.data.local.LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        try {
            val prefs = createIsolatedPrefs(context)
            val colRepo = com.laterbox.app.data.repository.CollectionRepository(db.collectionDao())
            val colId = colRepo.createCollection("Weekend Watch")
            val shortComedy = MovieTitleSummary(
                id = "m_short",
                providerId = "moviebox",
                kind = MediaKind.MOVIE,
                title = "Quick Laugh",
                releaseYear = 2024,
                durationMinutes = 78,
                language = "French",
                genres = listOf("Comedy"),
                sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                isPlayableHint = true
            )
            val longDrama = MovieTitleSummary(
                id = "m_long",
                providerId = "moviebox",
                kind = MediaKind.MOVIE,
                title = "Epic Journey",
                releaseYear = 2021,
                durationMinutes = 155,
                language = "English",
                genres = listOf("Drama"),
                sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                isPlayableHint = true
            )

            val historyFlow = MutableStateFlow<List<WatchHistoryItem>>(emptyList())
            val hiddenFlow = MutableStateFlow<List<WatchHistoryItem>>(emptyList())
            val addedCollections = mutableListOf<Pair<String, Long>>()
            val markedWatchedIds = mutableListOf<String>()
            val restartedIds = mutableListOf<String>()
            val removedCwIds = mutableListOf<String>()

            val baseFake = FakeMoviesRepository()
            val repo = object : MoviesRepository by baseFake {
                override suspend fun getHomeFeed(forceRefresh: Boolean) = Result.success(
                    MoviesHomeFeed(
                        featuredBanners = listOf(shortComedy, longDrama),
                        sections = listOf(
                            BrowseSection("s1", "Featured", null, SectionStyle.RAIL, listOf(shortComedy, longDrama))
                        ),
                        genres = listOf("All", "Comedy", "Drama")
                    )
                )

                override suspend fun getCatalogue(
                    kind: MediaKind?,
                    page: Int,
                    pageSize: Int,
                    genre: String?,
                    sort: CatalogueSort,
                    country: String?,
                    year: String?
                ): Result<CataloguePage> {
                    baseFake.catalogueCalls.add(FakeMoviesRepository.CatalogueCall(kind, genre, sort, country, year))
                    return Result.success(CataloguePage(listOf(shortComedy, longDrama), page, pageSize, 2, false))
                }

                override fun observeMovieWatchHistory(limit: Int) = historyFlow
                override fun observeHiddenHistory(limit: Int) = hiddenFlow

                override suspend fun markWatched(
                    item: MovieTitleSummary,
                    durationMs: Long,
                    seasonNumber: Int?,
                    episodeNumber: Int?,
                    episodeLabel: String?,
                    isRewatch: Boolean
                ) {
                    markedWatchedIds.add(item.id)
                    historyFlow.value = listOf(
                        WatchHistoryItem(
                            compositeId = item.compositeId,
                            providerId = item.providerId,
                            titleId = item.id,
                            kind = item.kind,
                            title = item.title,
                            positionMs = 100L,
                            durationMs = 100L,
                            lastWatchedAt = 5_000L,
                            isCompleted = true
                        )
                    )
                }

                override suspend fun setTitleHidden(providerId: String, titleId: String, hidden: Boolean) {
                    if (hidden) {
                        hiddenFlow.value = listOf(
                            WatchHistoryItem(
                                compositeId = "$providerId:$titleId",
                                providerId = providerId,
                                titleId = titleId,
                                kind = MediaKind.MOVIE,
                                title = "Hidden",
                                positionMs = 0L,
                                durationMs = 0L,
                                lastWatchedAt = 1L,
                                isHiddenTitle = true
                            )
                        )
                    }
                }

                override suspend fun restartFromBeginning(providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?) {
                    restartedIds.add(titleId)
                }

                override suspend fun removeFromContinueWatching(providerId: String, titleId: String) {
                    removedCwIds.add(titleId)
                }

                override suspend fun addMediaToCollection(item: MovieTitleSummary, collectionId: Long) {
                    addedCollections.add(item.id to collectionId)
                }
            }

            val vm = MoviesHomeViewModel(repo, prefs, colRepo)
            vm.uiState.first { it.feed != null && !it.isLoading }

            assertTrue(
                "Expected [All, English, French] in ${vm.uiState.value.availableLanguages} (topRated=${vm.uiState.value.topRatedRail.size}, feed=${vm.uiState.value.feed?.featuredBanners?.size})",
                vm.uiState.value.availableLanguages.containsAll(listOf("All", "English", "French"))
            )
            assertTrue(vm.uiState.value.quickRuntimePicks.any { it.id == "m_short" })
            assertTrue(vm.uiState.value.quickRuntimePicks.none { it.id == "m_long" })

            // Expanded filters: 4-digit year passes to getCatalogue, runtime & language filter client-side
            vm.selectYear("2024")
            vm.selectRuntimeBucket(com.laterbox.app.movies.domain.model.RuntimeBucket.QUICK)
            vm.selectLanguage("French")
            advanceUntilIdle()
            assertEquals("2024", baseFake.catalogueCalls.last().year)
            assertEquals(listOf("m_short"), vm.uiState.value.filteredItems.map { it.id })

            // Reroll surprise
            val firstSurprise = vm.uiState.value.surprisePick?.pick?.id
            vm.rerollSurpriseMe(MediaKind.MOVIE)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.surprisePick != null)
            assertFalse(firstSurprise.isNullOrBlank())

            // Contextual actions + collection add
            vm.markWatched(shortComedy)
            vm.addToCollection(shortComedy, colId)
            vm.createAndAddToCollection(longDrama, "Sci-Fi Vault")
            val cwEntry = WatchHistoryItem(
                compositeId = "moviebox:m_short",
                providerId = "moviebox",
                titleId = "m_short",
                kind = MediaKind.MOVIE,
                title = "Quick Laugh",
                positionMs = 10_000L,
                durationMs = 100_000L,
                lastWatchedAt = 1_000L
            )
            vm.restartFromBeginning(cwEntry)
            vm.removeFromContinueWatching(cwEntry)
            vm.hideTitle(longDrama)
            advanceUntilIdle()

            assertTrue(markedWatchedIds.contains("m_short"))
            assertTrue(addedCollections.any { it.first == "m_short" && it.second == colId })
            assertTrue(addedCollections.any { it.first == "m_long" })
            assertEquals(listOf("m_short"), restartedIds)
            assertEquals(listOf("m_short"), removedCwIds)
            assertTrue(vm.uiState.value.hiddenMediaKeys.contains(longDrama.stableMediaKey))
            assertTrue(vm.uiState.value.filteredItems.none { it.id == "m_long" })
        } finally {
            db.close()
        }
    }

    @Test
    fun catalogueViewModel_forwardsFourDigitYearAndFiltersRuntimeAndLanguage() = runTest {
        val shortMovie = MovieTitleSummary(
            id = "m_fast",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Short Film",
            releaseYear = 2024,
            durationMinutes = 82,
            language = "Korean",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )
        val longMovie = MovieTitleSummary(
            id = "m_long",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Long Film",
            releaseYear = 2024,
            durationMinutes = 148,
            language = "English",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )
        val baseFake = FakeMoviesRepository()
        val repo = object : MoviesRepository by baseFake {
            override suspend fun getCatalogue(
                kind: MediaKind?,
                page: Int,
                pageSize: Int,
                genre: String?,
                sort: CatalogueSort,
                country: String?,
                year: String?
            ): Result<CataloguePage> {
                baseFake.catalogueCalls.add(FakeMoviesRepository.CatalogueCall(kind, genre, sort, country, year))
                return Result.success(CataloguePage(listOf(shortMovie, longMovie), page, pageSize, 2, false))
            }
        }

        val vm = MoviesCatalogueViewModel(repo)
        vm.initialize("s1", MediaKind.MOVIE, null, null, CatalogueSort.POPULAR, null)
        advanceUntilIdle()

        vm.selectYear("2024")
        advanceUntilIdle()
        assertEquals("2024", baseFake.catalogueCalls.last().year)

        vm.selectRuntimeBucket(com.laterbox.app.movies.domain.model.RuntimeBucket.QUICK)
        advanceUntilIdle()
        assertEquals(listOf("m_fast"), vm.uiState.value.items.map { it.id })

        vm.selectLanguage("Korean")
        advanceUntilIdle()
        assertEquals(listOf("m_fast"), vm.uiState.value.items.map { it.id })

        vm.selectLanguage("English")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.items.isEmpty())
    }

    @Test
    fun detailViewModel_personalLibraryEpisodeToggleCollectionReminderAndMuteWork() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.laterbox.app.data.local.LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val colRepo = com.laterbox.app.data.repository.CollectionRepository(db.collectionDao())
            val colId = colRepo.createCollection("Top Series")
            val prefs = createIsolatedPrefs(context)
            val seriesSummary = MovieTitleSummary(
                id = "mb_series",
                providerId = "moviebox",
                kind = MediaKind.TV_SERIES,
                title = "Severance",
                sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                isPlayableHint = true
            )
            val memberCols = MutableStateFlow<List<Long>>(emptyList())
            val reminderFlow = MutableStateFlow<com.laterbox.app.movies.domain.model.ViewingReminderItem?>(null)
            val mutedFlow = MutableStateFlow(false)
            val watchedEpisodeNums = mutableSetOf<Int>()

            val baseFake = FakeMoviesRepository()
            val repo = object : MoviesRepository by baseFake {
                override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean) =
                    Result.success(
                        MediaDetails(
                            summary = seriesSummary,
                            seasons = listOf(SeasonSummary(1, "Season 1", 2, availableEpisodes = listOf(1, 2)))
                        )
                    )

                override suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int) =
                    Result.success(
                        listOf(
                            EpisodeItem("ep1", 1, 1, "Good News"),
                            EpisodeItem("ep2", 1, 2, "Half Loop")
                        )
                    )

                override fun observeCollectionIdsForMedia(providerId: String, titleId: String) = memberCols
                override suspend fun addMediaToCollection(item: MovieTitleSummary, collectionId: Long) {
                    memberCols.value = (memberCols.value + collectionId).distinct()
                }
                override suspend fun removeMediaFromCollection(item: MovieTitleSummary, collectionId: Long) {
                    memberCols.value = memberCols.value - collectionId
                }

                override fun observeViewingReminderForTitle(providerId: String, titleId: String) = reminderFlow
                override suspend fun scheduleViewingReminder(
                    item: MovieTitleSummary,
                    triggerAtMillis: Long,
                    note: String?,
                    seasonNumber: Int?,
                    episodeNumber: Int?
                ) {
                    reminderFlow.value = com.laterbox.app.movies.domain.model.ViewingReminderItem(
                        mediaKey = item.stableMediaKey,
                        compositeId = item.compositeId,
                        providerId = item.providerId,
                        titleId = item.id,
                        kind = item.kind,
                        title = item.title,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        posterUrl = item.posterUrl,
                        note = note,
                        triggerAtMillis = triggerAtMillis
                    )
                }
                override suspend fun dismissViewingReminder(providerId: String, titleId: String) {
                    reminderFlow.value = null
                }

                override fun observeSeriesPreferences(providerId: String, titleId: String) =
                    MutableStateFlow(com.laterbox.app.movies.domain.model.SeriesPreferences(seriesSummary.stableMediaKey, remindersMuted = mutedFlow.value))
                override suspend fun setSeriesRemindersMuted(providerId: String, titleId: String, muted: Boolean) {
                    mutedFlow.value = muted
                }

                override suspend fun markWatched(
                    item: MovieTitleSummary,
                    durationMs: Long,
                    seasonNumber: Int?,
                    episodeNumber: Int?,
                    episodeLabel: String?,
                    isRewatch: Boolean
                ) {
                    if (episodeNumber != null) watchedEpisodeNums.add(episodeNumber)
                }
                override suspend fun markUnwatched(providerId: String, titleId: String, seasonNumber: Int?, episodeNumber: Int?) {
                    if (episodeNumber != null) watchedEpisodeNums.remove(episodeNumber)
                }
            }

            val vm = MovieDetailViewModel(repo, null, prefs, colRepo, null)
            vm.load("moviebox", "mb_series")
            vm.uiState.first { !it.isLoading && it.episodes.isNotEmpty() }

            // Episode toggle
            vm.toggleEpisodeWatched(vm.uiState.value.episodes.first())
            advanceUntilIdle()
            assertTrue(1 in watchedEpisodeNums)

            // Collection toggle
            vm.toggleCollectionMembership(colId)
            advanceUntilIdle()
            assertTrue(colId in vm.uiState.value.memberCollectionIds)

            // Viewing reminder schedule & dismiss
            vm.scheduleViewingReminder(System.currentTimeMillis() + 3600_000L, "Watch tonight")
            advanceUntilIdle()
            assertEquals("Watch tonight", vm.uiState.value.viewingReminder?.note)

            vm.dismissViewingReminder()
            advanceUntilIdle()
            assertNull(vm.uiState.value.viewingReminder)

            // Mute series reminders
            vm.toggleMuteSeriesReminders()
            advanceUntilIdle()
            assertTrue(mutedFlow.value)
        } finally {
            db.close()
        }
    }

    @Test
    fun playerViewModel_episodeTransitionClearsStartPositionAndIgnoresStaleEpisodeCallbacks() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = createIsolatedPrefs(context)
        val recordedSaves = mutableListOf<Triple<Int?, Int?, Long>>()
        val seriesSummary = MovieTitleSummary(
            id = "mb_series_ep",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Severance",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )
        val baseFake = FakeMoviesRepository()
        val repo = object : MoviesRepository by baseFake {
            override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean) =
                Result.success(
                    MediaDetails(
                        summary = seriesSummary,
                        seasons = listOf(SeasonSummary(1, "Season 1", 2, availableEpisodes = listOf(1, 2)))
                    )
                )

            override suspend fun resolvePlayback(
                providerId: String,
                titleId: String,
                seasonNumber: Int?,
                episodeNumber: Int?
            ): Result<PlaybackResolution> {
                val src = PlaybackSource(
                    id = "s_${seasonNumber}_${episodeNumber}",
                    url = "https://cdn.example/s${seasonNumber}e${episodeNumber}.mp4",
                    format = "MP4",
                    qualityLabel = "720p",
                    qualityHeight = 720,
                    providerName = "MovieBox"
                )
                return Result.success(
                    PlaybackResolution.Available(
                        title = "Severance",
                        subtitleHeading = "S${seasonNumber}E${episodeNumber}",
                        sources = listOf(src),
                        startPositionMs = if (episodeNumber == 1) 1_190_000L else 0L
                    )
                )
            }

            override suspend fun getSavedPositionMs(
                providerId: String,
                titleId: String,
                seasonNumber: Int?,
                episodeNumber: Int?
            ): Long = if (episodeNumber == 1) 1_190_000L else 0L

            override suspend fun saveWatchProgress(
                item: MovieTitleSummary,
                positionMs: Long,
                durationMs: Long,
                seasonNumber: Int?,
                episodeNumber: Int?,
                episodeLabel: String?
            ) {
                recordedSaves.add(Triple(seasonNumber, episodeNumber, positionMs))
            }
        }

        val vm = MoviePlayerViewModel(
            moviesRepository = repo,
            moviesDownloadBridge = null,
            userPreferencesRepository = prefs
        )
        vm.load("moviebox", "mb_series_ep", 1, 1)
        val e1State = vm.uiState.first { !it.isLoading }
        assertEquals("mb_series_ep:1:1", e1State.episodeKey)
        assertEquals(1_190_000L, e1State.startPositionMs)

        // Transition to Episode 2 -> startPositionMs resets cleanly to 0L and episodeKey updates immediately
        vm.load("moviebox", "mb_series_ep", 1, 2)
        assertEquals(0L, vm.uiState.value.startPositionMs)
        val e2State = vm.uiState.first { !it.isLoading && it.episodeNumber == 2 }
        assertEquals("mb_series_ep:1:2", e2State.episodeKey)
        assertEquals(0L, e2State.startPositionMs)

        // Late onDispose callback from E1 must be ignored after transitioning to E2
        vm.saveProgressForEpisode(1_195_000L, 1_200_000L, "mb_series_ep:1:1")
        advanceUntilIdle()
        assertTrue(recordedSaves.none { it.second == 1 })

        // Saving E2 progress works cleanly with matching episodeKey
        vm.saveProgressForEpisode(42_000L, 1_200_000L, "mb_series_ep:1:2")
        advanceUntilIdle()
        assertEquals(listOf(Triple(1, 2, 42_000L)), recordedSaves)
    }

    @Test
    fun searchSuggestion_hasNavigableIdRejectsBlankNoneAndMissingProvider() {
        assertTrue(SearchSuggestion("123", "moviebox", "The Dark Knight").hasNavigableId)
        assertFalse(SearchSuggestion("", "moviebox", "The Dark Knight").hasNavigableId)
        assertFalse(SearchSuggestion("   ", "moviebox", "The Dark Knight").hasNavigableId)
        assertFalse(SearchSuggestion("0", "moviebox", "The Dark Knight").hasNavigableId)
        assertFalse(SearchSuggestion("None", "moviebox", "The Dark Knight").hasNavigableId)
        assertFalse(SearchSuggestion("null", "moviebox", "The Dark Knight").hasNavigableId)
        assertFalse(SearchSuggestion("123", "", "The Dark Knight").hasNavigableId)
        assertFalse(SearchSuggestion("123", "   ", "The Dark Knight").hasNavigableId)
        assertFalse(SearchSuggestion("123", "null", "The Dark Knight").hasNavigableId)
    }

    @Test
    fun catalogueViewModel_preservesMultiWordEditingAndDropsStaleSuggestionResponses() = runTest {
        val suggestedQueries = mutableListOf<String>()
        val delegate = FakeMoviesRepository()
        val repo = object : MoviesRepository by delegate {
            override suspend fun getSuggestions(query: String, limit: Int): Result<List<SearchSuggestion>> {
                suggestedQueries.add(query)
                return if (query == "the") {
                    delay(500L)
                    Result.success(listOf(SearchSuggestion("old_1", "moviebox", "Stale The")))
                } else {
                    delay(20L)
                    Result.success(listOf(SearchSuggestion("dk_1", "moviebox", "The Dark Knight")))
                }
            }
        }
        val vm = MoviesCatalogueViewModel(repo)
        vm.initialize("s1")
        advanceUntilIdle()

        // Preserves trailing & internal spaces in UI state while normalizing for suggestion requests
        vm.onQueryChange("the")
        advanceTimeBy(300L)
        vm.onQueryChange("the  dark   knight ")
        advanceTimeBy(400L)
        advanceUntilIdle()

        assertEquals("the  dark   knight ", vm.uiState.value.query)
        assertEquals(listOf("The Dark Knight"), vm.uiState.value.suggestions.map { it.title })
        assertTrue(suggestedQueries.contains("the dark knight"))

        vm.submitSearch()
        advanceUntilIdle()
        assertEquals("the dark knight", vm.uiState.value.query)

        vm.clearSearch()
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.query)
        assertTrue(vm.uiState.value.suggestions.isEmpty())
    }

    @Test
    fun moviesHomeViewModel_removeRecentlyViewedRemovesItemAndEmitsFeedback() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = createIsolatedPrefs(context)
        val removedTargets = mutableListOf<Pair<String, String>>()
        val recentItem = MovieTitleSummary(
            id = "rv_1",
            providerId = "moviebox",
            title = "Arrival",
            kind = MediaKind.MOVIE,
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
        )
        val recentlyViewedState = MutableStateFlow(listOf(recentItem))
        val delegate = FakeMoviesRepository()
        val repo = object : MoviesRepository by delegate {
            override fun observeRecentlyViewed(limit: Int): Flow<List<MovieTitleSummary>> = recentlyViewedState
            override suspend fun removeRecentlyViewed(providerId: String, titleId: String) {
                removedTargets.add(providerId to titleId)
                recentlyViewedState.value = recentlyViewedState.value.filterNot {
                    it.providerId == providerId && it.id == titleId
                }
            }
        }
        val vm = MoviesHomeViewModel(repo, prefs)
        vm.uiState.first { !it.isLoading }

        vm.removeRecentlyViewed(recentItem)
        advanceUntilIdle()

        assertEquals(listOf("moviebox" to "rv_1"), removedTargets)
        assertTrue(vm.uiState.value.recentlyViewed.isEmpty())
        assertEquals("Removed Arrival from Viewed", vm.uiState.value.actionFeedbackMessage)
    }

    @Test
    fun movieDetailViewModel_exposesSavedPlaybackPreferencesAndPreservesAcrossLoad() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = createIsolatedPrefs(context)
        prefs.setMoviesDoubleTapSeekSeconds(15)
        prefs.setMoviesSwipeSeekSensitivity(2.0f)
        prefs.setMoviesSwipeToSeekEnabled(false)
        prefs.setMoviesBrightnessGestureEnabled(false)
        prefs.setMoviesVolumeGestureEnabled(true)

        val repo = FakeMoviesRepository()
        val vm = MovieDetailViewModel(
            moviesRepository = repo,
            moviesDownloadBridge = null,
            userPreferencesRepository = prefs
        )
        val initial = vm.uiState.first { it.playbackPreferences.doubleTapSeekSeconds == 15 }
        assertEquals(2.0f, initial.playbackPreferences.swipeSeekSensitivity)
        assertFalse(initial.playbackPreferences.swipeToSeekEnabled)
        assertFalse(initial.playbackPreferences.brightnessGestureEnabled)
        assertTrue(initial.playbackPreferences.volumeGestureEnabled)

        vm.load("ia", "his_girl_friday")
        // Preserved synchronously during reset and after load completion
        assertEquals(15, vm.uiState.value.playbackPreferences.doubleTapSeekSeconds)
        val loaded = vm.uiState.first { !it.isLoading }
        assertEquals(15, loaded.playbackPreferences.doubleTapSeekSeconds)
        assertEquals(2.0f, loaded.playbackPreferences.swipeSeekSensitivity)
        assertFalse(loaded.playbackPreferences.swipeToSeekEnabled)
    }

    @Test
    fun moviePlayerViewModel_automaticallyFailsOverAcrossSources_and_classifiesHttp429() = runTest {
        var resolveCalls = 0
        val baseFake = FakeMoviesRepository()
        val customRepo = object : MoviesRepository by baseFake {
            override suspend fun resolvePlayback(
                providerId: String,
                titleId: String,
                seasonNumber: Int?,
                episodeNumber: Int?
            ): Result<PlaybackResolution> {
                resolveCalls++
                val sources = if (resolveCalls == 1) {
                    listOf(
                        PlaybackSource(
                            id = "s1080",
                            url = "https://cdn.example/1080.mp4",
                            format = "MP4",
                            qualityLabel = "1080p",
                            qualityHeight = 1080,
                            providerName = "MovieBox"
                        ),
                        PlaybackSource(
                            id = "s720",
                            url = "https://cdn.example/720.mp4",
                            format = "MP4",
                            qualityLabel = "720p",
                            qualityHeight = 720,
                            providerName = "MovieBox"
                        ),
                        PlaybackSource(
                            id = "sdash",
                            url = "https://cdn.example/master.mpd",
                            format = "DASH",
                            qualityLabel = "Adaptive",
                            qualityHeight = 999,
                            providerName = "MovieBox"
                        )
                    )
                } else {
                    listOf(
                        PlaybackSource(
                            id = "s_refreshed",
                            url = "https://cdn.example/fresh_720.mp4",
                            format = "MP4",
                            qualityLabel = "720p",
                            qualityHeight = 720,
                            providerName = "MovieBox"
                        )
                    )
                }
                return Result.success(
                    PlaybackResolution.Available(
                        title = "Test Title",
                        sources = sources,
                        subtitles = emptyList(),
                        providerName = "MovieBox",
                        sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
                    )
                )
            }
        }

        // Verify exhausted 403 triggers a one-shot stream refresh on customRepo
        val vm403 = MoviePlayerViewModel(customRepo)
        vm403.load("moviebox", "999")
        val init403 = vm403.uiState.first { !it.isLoading }
        assertEquals("https://cdn.example/720.mp4", init403.selectedSource?.url)
        assertTrue(vm403.handlePlaybackSourceFailure("https://cdn.example/720.mp4", 44_000L, 403))
        assertTrue(vm403.handlePlaybackSourceFailure("https://cdn.example/master.mpd", 44_000L, 403))
        assertTrue(vm403.handlePlaybackSourceFailure("https://cdn.example/1080.mp4", 44_000L, 403))
        advanceUntilIdle()
        assertEquals(2, resolveCalls)
        assertEquals("https://cdn.example/fresh_720.mp4", vm403.uiState.value.selectedSource?.url)

        // Multi-host 429 skips remaining sources on the rate-limited host (cdn.example) and fails over
        // only when an alternate un-throttled host is available; when all remaining sources share
        // the cooled host, 429 performs one controlled delayed refresh respecting Retry-After.
        val multiHostRepo = object : MoviesRepository by baseFake {
            override suspend fun resolvePlayback(
                providerId: String,
                titleId: String,
                seasonNumber: Int?,
                episodeNumber: Int?
            ): Result<PlaybackResolution> {
                resolveCalls++
                val sources = if (resolveCalls == 1) {
                    listOf(
                        PlaybackSource(
                            id = "s1080",
                            url = "https://cdn-primary.example/1080.mp4",
                            format = "MP4",
                            qualityLabel = "1080p",
                            qualityHeight = 1080,
                            providerName = "MovieBox"
                        ),
                        PlaybackSource(
                            id = "s720",
                            url = "https://cdn-primary.example/720.mp4",
                            format = "MP4",
                            qualityLabel = "720p",
                            qualityHeight = 720,
                            providerName = "MovieBox"
                        ),
                        PlaybackSource(
                            id = "s_alt",
                            url = "https://cdn-mirror.example/720_alt.mp4",
                            format = "MP4",
                            qualityLabel = "720p",
                            qualityHeight = 720,
                            providerName = "MovieBox"
                        )
                    )
                } else {
                    listOf(
                        PlaybackSource(
                            id = "s_refreshed",
                            url = "https://cdn-fresh.example/fresh_720.mp4",
                            format = "MP4",
                            qualityLabel = "720p",
                            qualityHeight = 720,
                            providerName = "MovieBox"
                        )
                    )
                }
                return Result.success(
                    PlaybackResolution.Available(
                        title = "Test Title",
                        sources = sources,
                        subtitles = emptyList(),
                        providerName = "MovieBox",
                        sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
                    )
                )
            }
        }

        resolveCalls = 0
        val vm = MoviePlayerViewModel(multiHostRepo)
        vm.load("moviebox", "999")
        val initial = vm.uiState.first { !it.isLoading }
        assertEquals("https://cdn-primary.example/720.mp4", initial.selectedSource?.url)

        // First 429 on cdn-primary.example skips 1080.mp4 (same cooled host!) and jumps straight to cdn-mirror.example
        val handled1 = vm.handlePlaybackSourceFailure(
            failedSourceUrl = "https://cdn-primary.example/720.mp4",
            currentPositionMs = 42_000L,
            httpStatusCode = 429,
            retryAfterSeconds = 3L,
            failingHost = "cdn-primary.example"
        )
        assertTrue(handled1)
        assertEquals("https://cdn-mirror.example/720_alt.mp4", vm.uiState.value.selectedSource?.url)
        assertEquals(42_000L, vm.uiState.value.startPositionMs)

        // When alternate host also gets 429, triggers ONE controlled delayed refresh (after Retry-After)
        val handled2 = vm.handlePlaybackSourceFailure(
            failedSourceUrl = "https://cdn-mirror.example/720_alt.mp4",
            currentPositionMs = 42_500L,
            httpStatusCode = 429,
            retryAfterSeconds = 2L,
            failingHost = "cdn-mirror.example"
        )
        assertTrue(handled2)
        advanceUntilIdle()
        assertEquals(2, resolveCalls)
        assertEquals("https://cdn-fresh.example/fresh_720.mp4", vm.uiState.value.selectedSource?.url)

        // If refreshed URL also fails, stops without infinite refresh loop
        val handled3 = vm.handlePlaybackSourceFailure(
            failedSourceUrl = "https://cdn-fresh.example/fresh_720.mp4",
            currentPositionMs = 43_000L,
            httpStatusCode = 429,
            retryAfterSeconds = 12L,
            failingHost = "cdn-fresh.example"
        )
        assertFalse(handled3)
        assertEquals(2, resolveCalls)

        // Selecting a source manually removes it from failedSourceUrls
        val manualPick = vm.uiState.value.resolution.let { res ->
            (res as PlaybackResolution.Available).sources.first()
        }
        vm.selectSource(manualPick, 10_000L)
        assertFalse(manualPick.url in vm.uiState.value.failedSourceUrls)

        // Verify HTTP 429 error metadata extraction & Retry-After user message
        val dataSpec = androidx.media3.datasource.DataSpec.Builder()
            .setUri("https://bcdnxw.hakunaymatata.com/stream/1080.mp4")
            .build()
        val http429Cause = androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(
            429,
            "Too Many Requests",
            null,
            mapOf("Retry-After" to listOf("18"), "Server" to listOf("nginx")),
            dataSpec,
            ByteArray(0)
        )
        val playbackException = androidx.media3.common.PlaybackException(
            "HTTP 429",
            http429Cause,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
        val info = com.laterbox.app.movies.ui.extractHttpFailureInfo(playbackException)
        assertEquals(429, info?.statusCode)
        assertEquals("bcdnxw.hakunaymatata.com", info?.failingHost)
        assertEquals(18L, info?.retryAfterSeconds)
        val msg = com.laterbox.app.movies.ui.formatPlayerErrorMessage(playbackException)
        assertTrue("Unexpected message: $msg", msg.contains("temporarily busy", ignoreCase = true) && msg.contains("18s"))
        assertFalse("Message should not leak rate-limited jargon: $msg", msg.contains("rate-limit", ignoreCase = true))
    }

    @Test
    fun detailViewModel_defersEpisodeSizesUntilExplicitlyRequested() = runTest {
        var episodeSizeCalls = 0
        val repo = object : MoviesRepository by FakeMoviesRepository() {
            override suspend fun getSeasonEpisodeSizes(
                providerId: String,
                seriesId: String,
                seasonNumber: Int,
                episodeNumbers: List<Int>
            ): Result<Map<Int, Map<Int, Double>>> {
                episodeSizeCalls++
                return Result.success(mapOf(2 to mapOf(720 to 250.0)))
            }
        }
        val vm = MovieDetailViewModel(repo)
        vm.load("tvmaze", "series_1")
        advanceUntilIdle()
        // Opening a TV series detail page does NOT eagerly trigger bulk episode-size requests
        assertEquals(0, episodeSizeCalls)

        // Explicitly opening batch/quality options triggers lazy episode-size resolution once
        vm.requestSeasonEpisodeSizes()
        advanceUntilIdle()
        assertEquals(1, episodeSizeCalls)
    }

    @Test
    fun playerViewModel_429OnBcdnxwCoolsBcdnwAcrossRefreshAndKeepsSbcdnwIndependent() = runTest {
        var fakeClockMs = 1_700_000_000_000L
        var resolveCalls = 0
        var refreshedReturnedOnlyBcdnw = false

        val repo = object : MoviesRepository by FakeMoviesRepository() {
            override suspend fun resolvePlayback(
                providerId: String,
                titleId: String,
                seasonNumber: Int?,
                episodeNumber: Int?
            ): Result<PlaybackResolution> {
                resolveCalls++
                val sources = if (resolveCalls == 1) {
                    listOf(
                        PlaybackSource(
                            id = "mp4_play_720",
                            url = "https://bcdnxw.hakunaymatata.com/play/movie_720.mp4",
                            format = "MP4",
                            qualityLabel = "720p",
                            qualityHeight = 720,
                            providerName = "MovieBox"
                        ),
                        PlaybackSource(
                            id = "mp4_dl_720",
                            url = "https://bcdnw.hakunaymatata.com/dl/movie_720.mp4",
                            format = "MP4",
                            qualityLabel = "720p",
                            qualityHeight = 720,
                            providerName = "MovieBox"
                        ),
                        PlaybackSource(
                            id = "dash_sbcdnw",
                            url = "https://sbcdnw.hakunaymatata.com/dash/index.mpd",
                            format = "DASH",
                            qualityLabel = "720p",
                            qualityHeight = 720,
                            providerName = "MovieBox"
                        )
                    )
                } else {
                    refreshedReturnedOnlyBcdnw = true
                    listOf(
                        PlaybackSource(
                            id = "mp4_dl_fresh",
                            url = "https://bcdnw.hakunaymatata.com/dl/fresh_720.mp4",
                            format = "MP4",
                            qualityLabel = "720p",
                            qualityHeight = 720,
                            providerName = "MovieBox"
                        )
                    )
                }
                return Result.success(
                    PlaybackResolution.Available(
                        title = "Test Movie",
                        sources = sources,
                        subtitles = emptyList(),
                        providerName = "MovieBox",
                        sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
                    )
                )
            }
        }

        val vm = MoviePlayerViewModel(repo, clockMs = { fakeClockMs })
        vm.load("moviebox", "m_1")
        val initial = vm.uiState.first { !it.isLoading }
        assertEquals("https://bcdnxw.hakunaymatata.com/play/movie_720.mp4", initial.selectedSource?.url)

        // 429 on bcdnxw (Retry-After: 25s) cools bcdnw alias and switches cleanly to independent sbcdnw, preserving position.
        val handled1 = vm.handlePlaybackSourceFailure(
            failedSourceUrl = "https://bcdnxw.hakunaymatata.com/play/movie_720.mp4",
            currentPositionMs = 67_500L,
            httpStatusCode = 429,
            retryAfterSeconds = 25L,
            failingHost = "bcdnxw.hakunaymatata.com"
        )
        assertTrue(handled1)
        assertEquals("https://sbcdnw.hakunaymatata.com/dash/index.mpd", vm.uiState.value.selectedSource?.url)
        assertEquals(67_500L, vm.uiState.value.startPositionMs)

        // Now sbcdnw gets 429 too; delayed refresh returns ONLY bcdnw while still cooled -> must NOT select bcdnw.
        val handled2 = vm.handlePlaybackSourceFailure(
            failedSourceUrl = "https://sbcdnw.hakunaymatata.com/dash/index.mpd",
            currentPositionMs = 68_000L,
            httpStatusCode = 429,
            retryAfterSeconds = 25L,
            failingHost = "sbcdnw.hakunaymatata.com"
        )
        assertTrue(handled2)
        fakeClockMs += 5_000L
        advanceUntilIdle()

        assertTrue(refreshedReturnedOnlyBcdnw)
        assertEquals(2, resolveCalls)
        assertEquals("https://sbcdnw.hakunaymatata.com/dash/index.mpd", vm.uiState.value.selectedSource?.url)
        assertEquals(68_000L, vm.uiState.value.startPositionMs)
        val err = vm.uiState.value.errorMessage.orEmpty()
        assertTrue("Expected busy unavailability message, got: $err", err.contains("temporarily busy", ignoreCase = true))
        assertFalse("Error should not leak rate-limited jargon: $err", err.contains("rate-limit", ignoreCase = true))
    }

    @Test
    fun playerViewModel_403OnCookieSignedDashAllowsRefreshRetryWithUpdatedCookieOnSameManifestUrl() = runTest {
        var resolveCalls = 0
        val manifestUrl = "https://sbcdnw.hakunaymatata.com/dash/movie_1/index_web.mpd"
        val repo = object : MoviesRepository by FakeMoviesRepository() {
            override suspend fun resolvePlayback(
                providerId: String,
                titleId: String,
                seasonNumber: Int?,
                episodeNumber: Int?
            ): Result<PlaybackResolution> {
                resolveCalls++
                val cookie = if (resolveCalls == 1) "Edge-Cache-Cookie=old_sig" else "Edge-Cache-Cookie=fresh_sig"
                return Result.success(
                    PlaybackResolution.Available(
                        title = "The Beast",
                        sources = listOf(
                            PlaybackSource(
                                id = "dash_$resolveCalls",
                                url = manifestUrl,
                                format = "DASH",
                                qualityLabel = "1080p (Adaptive)",
                                qualityHeight = 1080,
                                headers = mapOf("Cookie" to cookie, "X-MB-Token" to cookie),
                                providerName = "MovieBox"
                            )
                        ),
                        subtitles = emptyList(),
                        providerName = "MovieBox",
                        sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
                    )
                )
            }
        }

        val vm = MoviePlayerViewModel(repo)
        vm.load("moviebox", "3595914877702915152")
        val initial = vm.uiState.first { !it.isLoading }
        assertEquals("Edge-Cache-Cookie=old_sig", initial.selectedSource?.headers?.get("Cookie"))

        val handled = vm.handlePlaybackSourceFailure(
            failedSourceUrl = manifestUrl,
            currentPositionMs = 15_000L,
            httpStatusCode = 403,
            failingHost = "sbcdnw.hakunaymatata.com"
        )
        assertTrue(handled)
        advanceUntilIdle()
        assertEquals(2, resolveCalls)
        assertEquals(manifestUrl, vm.uiState.value.selectedSource?.url)
        assertEquals("Edge-Cache-Cookie=fresh_sig", vm.uiState.value.selectedSource?.headers?.get("Cookie"))
        assertNull(vm.uiState.value.errorMessage)

        val lifecycle = com.laterbox.app.diagnostics.MediaNetworkDiagnostics.default.snapshotLifecycle()
        assertTrue(lifecycle.any { it.stage == "REFRESH_REQUESTED" && it.refreshRequested && it.statusCode == 403 })
        assertTrue(lifecycle.any { it.stage == "REFRESH_APPLIED" && it.refreshedCredentialsApplied == true && it.host == "sbcdnw.hakunaymatata.com" })
    }

    @Test
    fun `playerViewModel sets fallback notice selects opensubtitles track and scopes subtitles when switching dubs`() = runTest {
        val repo = object : MoviesRepository by FakeMoviesRepository() {
            override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean): Result<MediaDetails> {
                return Result.success(
                    MediaDetails(
                        summary = MovieTitleSummary(
                            id = titleId,
                            providerId = providerId,
                            kind = MediaKind.MOVIE,
                            title = if (titleId == "dub_en") "Test [English]" else "Test",
                            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                            isPlayableHint = true
                        ),
                        fullDescription = "desc",
                        audioDubs = listOf(
                            com.laterbox.app.movies.domain.model.AudioDubInfo(language = "Hindi", label = "Hindi dub", referenceId = "dub_hi"),
                            com.laterbox.app.movies.domain.model.AudioDubInfo(language = "English", label = "English", referenceId = "dub_en")
                        )
                    )
                )
            }

            override suspend fun resolvePlayback(
                providerId: String,
                titleId: String,
                seasonNumber: Int?,
                episodeNumber: Int?
            ): Result<PlaybackResolution> {
                return if (titleId == "primary_id") {
                    Result.success(
                        PlaybackResolution.Available(
                            title = "Test",
                            sources = listOf(PlaybackSource("s1", "https://bcdnxw.hakunaymatata.com/hindi.mp4", "MP4", "1080p", 1080, providerName = "MovieBox")),
                            subtitles = listOf(
                                SubtitleTrack(
                                    id = "opensubtitles_222",
                                    language = "English",
                                    languageCode = "en",
                                    label = "English",
                                    url = "https://www.opensubtitles.com/download/sub.srt",
                                    format = "srt",
                                    source = "opensubtitles"
                                )
                            ),
                            providerName = "MovieBox",
                            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                            fallbackDubLabel = "Hindi",
                            fallbackDubTitleId = "dub_hi"
                        )
                    )
                } else {
                    Result.success(
                        PlaybackResolution.Available(
                            title = "Test [English]",
                            sources = listOf(PlaybackSource("s2", "https://bcdnxw.hakunaymatata.com/en.mp4", "MP4", "1080p", 1080, providerName = "MovieBox")),
                            subtitles = emptyList(),
                            providerName = "MovieBox",
                            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
                        )
                    )
                }
            }
        }

        val vm = MoviePlayerViewModel(repo)
        vm.load("moviebox", "primary_id")
        val state = vm.uiState.first { !it.isLoading }
        assertEquals("Hindi", state.fallbackDubLabel)
        assertEquals("Playing Hindi version", state.playbackNoticeMessage)
        assertEquals("opensubtitles_222", state.selectedSubtitleTrack?.id)

        vm.clearPlaybackNotice()
        assertNull(vm.uiState.value.playbackNoticeMessage)

        // Switching to explicit dub clears fallback state and does NOT carry OpenSubtitles track over
        vm.selectAudioDub(com.laterbox.app.movies.domain.model.AudioDubInfo("English", "English", "dub_en"), 5_000L)
        advanceUntilIdle()
        assertNull(vm.uiState.value.fallbackDubLabel)
        assertNull(vm.uiState.value.playbackNoticeMessage)
        assertNull(vm.uiState.value.selectedSubtitleTrack)
    }

    @Test
    fun `playerViewModel leaves fallback notice null for normal working title`() = runTest {
        val repo = object : MoviesRepository by FakeMoviesRepository() {
            override suspend fun resolvePlayback(
                providerId: String,
                titleId: String,
                seasonNumber: Int?,
                episodeNumber: Int?
            ): Result<PlaybackResolution> {
                return Result.success(
                    PlaybackResolution.Available(
                        title = "Normal Movie",
                        sources = listOf(PlaybackSource("s1", "https://bcdnxw.hakunaymatata.com/main.mp4", "MP4", "1080p", 1080, providerName = "MovieBox")),
                        providerName = "MovieBox",
                        sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
                    )
                )
            }
        }

        val vm = MoviePlayerViewModel(repo)
        vm.load("moviebox", "normal_id")
        val state = vm.uiState.first { !it.isLoading }
        assertNull(state.fallbackDubLabel)
        assertNull(state.playbackNoticeMessage)
    }
}
