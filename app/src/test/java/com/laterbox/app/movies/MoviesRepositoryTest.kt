package com.laterbox.app.movies

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.movies.data.fixtures.DevelopmentMoviesFixtureProvider
import com.laterbox.app.movies.data.provider.InternetArchiveMoviesProvider
import com.laterbox.app.movies.data.provider.TmdbOptionalMoviesProvider
import com.laterbox.app.movies.data.provider.TvMazeSeriesProvider
import com.laterbox.app.movies.data.repository.MoviesRepositoryImpl
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.PlaybackResolution
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoviesRepositoryTest {

    private lateinit var db: LaterboxDatabase
    private lateinit var server: MockWebServer
    private var fakeClockMs = 1_000_000L
    private var useFixtures = false
    private var tmdbKey = ""

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun createRepository(): MoviesRepositoryImpl {
        val client = OkHttpClient()
        val base = server.url("/").toString().trimEnd('/')
        return MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(client, base),
            tvMazeProvider = TvMazeSeriesProvider(client, base),
            tmdbProvider = TmdbOptionalMoviesProvider(client, apiKeyProvider = { tmdbKey }, baseUrl = base),
            fixtureProvider = DevelopmentMoviesFixtureProvider(),
            useSampleFixturesOnlyProvider = { useFixtures },
            nowMs = { fakeClockMs }
        )
    }

    @Test
    fun homeFeed_mergesLiveProvidersCachesWithinTtlAndFallsBackOfflineWhenExpired() = runTest {
        var failNetwork = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (failNetwork) return MockResponse().setResponseCode(503)
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/advancedsearch.php") -> MockResponse().setResponseCode(200).setBody(
                        """{"response":{"numFound":1,"docs":[{"identifier":"ia_1","title":"Archive Film","year":1941}]}}"""
                    )
                    path.startsWith("/shows?page=") -> MockResponse().setResponseCode(200).setBody(
                        """[{"id":50,"name":"Live Series","weight":90,"genres":["Drama"],"premiered":"2020-01-01","rating":{"average":8.5}}]"""
                    )
                    path.startsWith("/schedule") -> MockResponse().setResponseCode(200).setBody("[]")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val repo = createRepository()
        val liveFeed = repo.getHomeFeed(forceRefresh = false).getOrThrow()
        assertFalse(liveFeed.isOfflineCached)
        assertFalse(liveFeed.containsSampleFixtures)
        assertTrue(liveFeed.featuredBanners.any { it.providerId == "ia" })
        assertTrue(liveFeed.featuredBanners.any { it.providerId == "tvmaze" })

        // Break network: within TTL returns fresh cache cleanly
        failNetwork = true
        val cachedFeed = repo.getHomeFeed(forceRefresh = false).getOrThrow()
        assertFalse(cachedFeed.isOfflineCached)

        // Advance clock past 6h TTL with network still down: falls back to stale cache tagged offline
        fakeClockMs += MoviesRepositoryImpl.CACHE_TTL_MS + 60_000L
        val offlineFallback = repo.getHomeFeed(forceRefresh = false).getOrThrow()
        assertTrue(offlineFallback.isOfflineCached)
        assertTrue(offlineFallback.activeProviderNotice.orEmpty().contains("Offline cache"))
    }

    @Test
    fun sampleFixturesMode_staysStrictlyIsolatedAndTaggedAsFixture() = runTest {
        useFixtures = true
        val repo = createRepository()
        val feed = repo.getHomeFeed().getOrThrow()
        assertTrue(feed.containsSampleFixtures)
        assertTrue(feed.featuredBanners.all { it.sourceBadge == DataSourceBadge.SAMPLE_FIXTURE })

        val ep1Playable = repo.resolvePlayback("fixture", "cosmos_series_fixture", 1, 1).getOrThrow()
        assertTrue(ep1Playable is PlaybackResolution.Available)

        val ep2Unavailable = repo.resolvePlayback("fixture", "cosmos_series_fixture", 1, 2).getOrThrow()
        assertTrue(ep2Unavailable is PlaybackResolution.Unavailable)
    }

    @Test
    fun watchlistAndWatchProgress_persistCorrectlyAndResetAt96Percent() = runTest {
        val repo = createRepository()
        val item = MovieTitleSummary(
            id = "his_girl_friday",
            providerId = "ia",
            kind = MediaKind.MOVIE,
            title = "His Girl Friday",
            sourceBadge = DataSourceBadge.LIVE_INTERNET_ARCHIVE,
            isPlayableHint = true
        )

        assertTrue(repo.toggleWatchlist(item))
        assertTrue(repo.observeIsInWatchlist(item.compositeId).first())
        assertEquals(1, repo.observeWatchlist().first().size)

        repo.saveWatchProgress(item, positionMs = 120_000L, durationMs = 1_000_000L)
        assertEquals(120_000L, repo.getSavedPositionMs("ia", "his_girl_friday"))
        assertEquals(1, repo.observeContinueWatching().first().size)

        // >= 96% completed restarts from 0
        repo.saveWatchProgress(item, positionMs = 970_000L, durationMs = 1_000_000L)
        assertEquals(0L, repo.getSavedPositionMs("ia", "his_girl_friday"))

        assertFalse(repo.toggleWatchlist(item))
        assertTrue(repo.observeWatchlist().first().isEmpty())
    }

    @Test
    fun unifiedResolver_prefersMovieBoxAndFallsBackToLiveOpenArchiveOrMovieBoxStaleCache() = runTest {
        var failMovieBox = false
        var failOpenArchive = false
        val iaOrTvMazeHits = mutableListOf<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/advancedsearch.php") || path.startsWith("/shows") || path.startsWith("/schedule")) {
                    iaOrTvMazeHits.add(path)
                    if (failOpenArchive) return MockResponse().setResponseCode(503)
                    return when {
                        path.startsWith("/advancedsearch.php") -> MockResponse().setResponseCode(200).setBody(
                            """{"response":{"numFound":1,"docs":[{"identifier":"ia_fallback","title":"Fallback Classic","year":1950}]}}"""
                        )
                        path.startsWith("/shows") -> MockResponse().setResponseCode(200).setBody("[]")
                        else -> MockResponse().setResponseCode(200).setBody("[]")
                    }
                }
                if (failMovieBox) {
                    return MockResponse().setResponseCode(502).setBody("""{"detail":"MovieBox upstream offline"}""")
                }
                return when {
                    path == "/home" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "code": 0,
                          "data": {
                            "operatingList": [
                              {
                                "type": "SUBJECTS_MOVIE",
                                "title": "MovieBox Picks",
                                "subjects": [
                                  {
                                    "subjectId": "777",
                                    "subjectType": 1,
                                    "title": "MovieBox Feature",
                                    "releaseDate": "2024-05-01",
                                    "imdbRatingValue": "8.4",
                                    "cover": {"url": "https://cdn.example/mb.jpg"},
                                    "detailPath": "moviebox-feature"
                                  }
                                ]
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/search?") -> MockResponse().setResponseCode(200).setBody(
                        """{"query":"Feature","page":1,"results":[{"subject_id":"777","title":"MovieBox Feature","type":"Movie","subject_type":1}]}"""
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = OkHttpClient()
        val base = server.url("/").toString().trimEnd('/')
        val mbProvider = com.laterbox.app.movies.data.provider.MovieBoxProvider(client, baseUrlProvider = { base })
        val repo = MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(client, base),
            tvMazeProvider = TvMazeSeriesProvider(client, base),
            tmdbProvider = TmdbOptionalMoviesProvider(client, apiKeyProvider = { "" }, baseUrl = base),
            movieBoxProvider = mbProvider,
            useSampleFixturesOnlyProvider = { false },
            nowMs = { fakeClockMs }
        )

        // When MovieBox fails initially while IA is up, unified resolver falls back cleanly with notice
        failMovieBox = true
        failOpenArchive = false
        val fallbackFeed = repo.getHomeFeed(forceRefresh = false).getOrThrow()
        assertFalse(fallbackFeed.isOfflineCached)
        assertEquals(MoviesRepositoryImpl.MOVIEBOX_FALLBACK_NOTICE, fallbackFeed.activeProviderNotice)
        assertTrue(fallbackFeed.featuredBanners.any { it.providerId == "ia" })
        assertTrue(iaOrTvMazeHits.isNotEmpty())

        // Recover MovieBox: caches MovieBox response in Room and clears fallback notice
        iaOrTvMazeHits.clear()
        failMovieBox = false
        val liveFeed = repo.getHomeFeed(forceRefresh = true).getOrThrow()
        assertFalse(liveFeed.isOfflineCached)
        assertEquals("777", liveFeed.featuredBanners.first().id)
        assertEquals("moviebox", liveFeed.featuredBanners.first().providerId)
        assertEquals(DataSourceBadge.LIVE_MOVIEBOX, liveFeed.featuredBanners.first().sourceBadge)
        assertTrue(iaOrTvMazeHits.isEmpty())

        // Search routes to MovieBox first
        val searchPage = repo.search("Feature").getOrThrow()
        assertEquals(1, searchPage.items.size)
        assertEquals("moviebox", searchPage.items.first().providerId)

        // Break BOTH MovieBox and Open Archive after stale TTL -> returns MovieBox's own stale offline cache
        failMovieBox = true
        failOpenArchive = true
        fakeClockMs += MoviesRepositoryImpl.CACHE_TTL_MS + 60_000L
        val staleFeed = repo.getHomeFeed(forceRefresh = false).getOrThrow()
        assertTrue(staleFeed.isOfflineCached)
        assertEquals("moviebox", staleFeed.featuredBanners.first().providerId)
        assertTrue(staleFeed.activeProviderNotice.orEmpty().contains("Offline cache", ignoreCase = true))
        assertFalse(staleFeed.activeProviderNotice.orEmpty().contains("MovieBox", ignoreCase = true))
    }

    @Test
    fun canonicalIdentity_linksAcrossProvidersPromotesPlayableWatchlistAndUnlinksCleanly() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/shows/169") && !path.contains("/seasons") && !path.contains("/episodes") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "id": 169,
                          "name": "Breaking Bad",
                          "premiered": "2008-01-20",
                          "runtime": 47,
                          "externals": {"imdb": "tt0903747"},
                          "rating": {"average": 9.5},
                          "genres": ["Drama"],
                          "_embedded": {"seasons": [{"id": 1, "number": 1, "name": "Season 1", "episodeOrder": 7}]}
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/detail/mb_bb") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "subject_id": "mb_bb",
                          "subject_type": 2,
                          "title": "Breaking Bad",
                          "release_date": "2008-01-20",
                          "duration": 2820,
                          "imdb_id": "tt0903747",
                          "description": "Linked MovieBox entry.",
                          "dubs": []
                        }
                        """.trimIndent()
                    )
                    path.startsWith("/episodes/mb_bb") -> MockResponse().setResponseCode(200).setBody(
                        """{"subject_id":"mb_bb","seasons":[{"season":1,"episode_count":7}]}"""
                    )
                    path.startsWith("/api/stream/mb_bb/captions") -> MockResponse().setResponseCode(200).setBody(
                        """{"subject_id":"mb_bb","captions":[]}"""
                    )
                    path.startsWith("/api/stream/mb_bb") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "streams": [
                            {"id": "s1", "url": "https://cdn.example/bb_720.mp4", "format": "MP4", "quality": "720p", "quality_value": 720}
                          ]
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = OkHttpClient()
        val base = server.url("/").toString().trimEnd('/')
        val repo = MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(client, base),
            tvMazeProvider = TvMazeSeriesProvider(client, base),
            tmdbProvider = TmdbOptionalMoviesProvider(client, apiKeyProvider = { "" }, baseUrl = base),
            movieBoxProvider = com.laterbox.app.movies.data.provider.MovieBoxProvider(client, baseUrlProvider = { base }),
            useSampleFixturesOnlyProvider = { false },
            nowMs = { fakeClockMs }
        )

        val tvDetails = repo.getDetails("tvmaze", "169").getOrThrow()
        val mbDetails = repo.getDetails("moviebox", "mb_bb").getOrThrow()
        assertEquals(tvDetails.summary.canonicalId, mbDetails.summary.canonicalId)

        val links = repo.getProviderLinks("tvmaze", "169")
        assertEquals(2, links.size)

        // Watchlist saved on MovieBox matches TVMaze & canonical composite IDs
        assertTrue(repo.toggleWatchlist(mbDetails.summary))
        assertTrue(repo.observeIsInWatchlist("tvmaze:169").first())
        assertTrue(repo.observeIsInWatchlist("canonical:${mbDetails.summary.canonicalId}").first())

        // Playback on TVMaze promoted automatically to linked MovieBox source
        val promotedPlayback = repo.resolvePlayback("tvmaze", "169", seasonNumber = 1, episodeNumber = 1).getOrThrow()
        assertTrue(promotedPlayback is PlaybackResolution.Available)

        // Unlink TVMaze separates canonical identity and stops playback promotion
        val newCanonical = repo.unlinkProviderTitle("tvmaze", "169")
        assertTrue(!newCanonical.isNullOrBlank() && newCanonical != mbDetails.summary.canonicalId)
        assertEquals(1, repo.getProviderLinks("tvmaze", "169").size)
        assertEquals(1, repo.getProviderLinks("moviebox", "mb_bb").size)
        assertFalse(repo.observeIsInWatchlist("tvmaze:169").first())
        val unlinkedPlayback = repo.resolvePlayback("tvmaze", "169", seasonNumber = 1, episodeNumber = 1).getOrThrow()
        assertTrue(unlinkedPlayback is PlaybackResolution.Unavailable)
    }

    @Test
    fun productionSourcesContainZeroUnauthorisedMovieBoxHostsOrSecrets() {
        val forbiddenTokens = listOf(
            "aoneroom.com",
            "inmoviebox.com",
            "moviebox.ph",
            "h5-api.aoneroom",
            "x-tr-signature",
            "com.community.mbox.in"
        )
        val mainSrcDir = listOf(
            File("app/src/main/java"),
            File("Laterbox/app/src/main/java")
        ).firstOrNull { it.exists() } ?: File("src/main/java")

        assertTrue("Expected main source directory at ${mainSrcDir.absolutePath}", mainSrcDir.exists())
        mainSrcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val content = file.readText()
                forbiddenTokens.forEach { token ->
                    assertFalse(
                        "Forbidden token '$token' found in ${file.path}",
                        content.contains(token, ignoreCase = true)
                    )
                }
            }
    }

    @Test
    fun viewingHistory_recordsTrailersIsolatedFromContinueWatchingAndSupportsSelectiveDeletes() = runTest {
        val repo = createRepository()
        val movie = MovieTitleSummary(
            id = "dune_2",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Dune: Part Two",
            posterUrl = "https://cdn.example/dune_poster.jpg",
            backdropUrl = "https://cdn.example/dune_backdrop.jpg",
            trailerUrl = "https://cdn.example/dune_trailer.mp4",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )
        val series = MovieTitleSummary(
            id = "sev_1",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Severance",
            backdropUrl = "https://cdn.example/sev_backdrop.jpg",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )

        // Save feature film progress & trailer watch
        repo.saveWatchProgress(movie, positionMs = 240_000L, durationMs = 1_800_000L)
        fakeClockMs += 5_000L
        repo.recordTrailerWatch(series, positionMs = 18_000L, durationMs = 120_000L)

        // Continue Watching strictly excludes trailers & preserves crisp 16:9 backdropUrl
        val continueList = repo.observeContinueWatching().first()
        assertEquals(1, continueList.size)
        assertEquals("moviebox:dune_2", continueList.first().compositeId)
        assertEquals("https://cdn.example/dune_backdrop.jpg", continueList.first().backdropUrl)
        assertFalse(continueList.first().isTrailer)

        // Viewing History includes both regular watch entries and trailers
        val allHistory = repo.observeViewingHistory().first()
        assertEquals(2, allHistory.size)
        assertTrue(allHistory.any { it.compositeId == "moviebox:dune_2" && !it.isTrailer })
        val trailerEntry = allHistory.first { it.compositeId == "moviebox:sev_1" && it.isTrailer }
        assertEquals("https://cdn.example/sev_backdrop.jpg", trailerEntry.backdropUrl)

        // Selective trailer clear leaves main movie entry intact
        repo.clearViewingHistory(trailersOnly = true)
        val afterClearTrailers = repo.observeViewingHistory().first()
        assertEquals(1, afterClearTrailers.size)
        assertEquals("moviebox:dune_2", afterClearTrailers.first().compositeId)

        // Single-item delete & full clear
        val movieKey = afterClearTrailers.first().historyKey
        repo.deleteViewingHistoryEntry(movieKey)
        assertTrue(repo.observeViewingHistory().first().isEmpty())

        repo.saveWatchProgress(movie, positionMs = 300_000L, durationMs = 1_800_000L)
        repo.recordTrailerWatch(series, positionMs = 10_000L, durationMs = 90_000L)
        assertEquals(2, repo.observeViewingHistory().first().size)
        repo.clearViewingHistory(trailersOnly = null)
        assertTrue(repo.observeViewingHistory().first().isEmpty())
    }

    @Test
    fun personalLibrary_splitHistoryRewatchRestartSeriesProgressAndHideControls() = runTest {
        val repo = createRepository()
        val movie = MovieTitleSummary(
            id = "interstellar_1",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Interstellar",
            durationMinutes = 169,
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )
        val series = MovieTitleSummary(
            id = "andor_1",
            providerId = "moviebox",
            kind = MediaKind.TV_SERIES,
            title = "Andor",
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )

        // 1. Record movie, episode, and trailer separately
        repo.saveWatchProgress(movie, positionMs = 300_000L, durationMs = 1_000_000L)
        fakeClockMs += 1_000L
        repo.saveWatchProgress(
            series,
            positionMs = 980_000L,
            durationMs = 1_000_000L,
            seasonNumber = 1,
            episodeNumber = 1,
            episodeLabel = "S1 · E1"
        )
        fakeClockMs += 1_000L
        repo.saveWatchProgress(
            series,
            positionMs = 120_000L,
            durationMs = 1_000_000L,
            seasonNumber = 1,
            episodeNumber = 2,
            episodeLabel = "S1 · E2"
        )
        fakeClockMs += 1_000L
        repo.recordTrailerWatch(movie, positionMs = 15_000L, durationMs = 90_000L)

        assertEquals(1, repo.observeMovieWatchHistory().first().size)
        assertEquals(2, repo.observeEpisodeWatchHistory().first().size)
        assertEquals(1, repo.observeTrailerHistory().first().size)

        // Next unwatched episode skips S1E1 (completed) and returns S1E2
        val seasons = listOf(
            com.laterbox.app.movies.domain.model.SeasonSummary(seasonNumber = 1, episodeCount = 3)
        )
        assertEquals(1 to 2, repo.computeNextUnwatchedEpisode("moviebox", "andor_1", seasons))

        // Mark S1E2 watched -> next unwatched becomes S1E3
        repo.markWatched(series, durationMs = 1_000_000L, seasonNumber = 1, episodeNumber = 2, episodeLabel = "S1 · E2")
        assertEquals(1 to 3, repo.computeNextUnwatchedEpisode("moviebox", "andor_1", seasons))

        // Series progress reflects 2 completed episodes
        val progress = repo.observeSeriesProgress("moviebox", "andor_1", "Andor").first()
        assertEquals(2, progress.totalWatchedEpisodes)
        assertTrue(progress.episodeStates[1 to 1]?.isCompleted == true)
        assertTrue(progress.episodeStates[1 to 2]?.isCompleted == true)

        // 2. Restarting completed movie preserves completion & rewatchCount, and completing again increments rewatchCount
        repo.markWatched(movie, durationMs = 1_000_000L)
        val afterComplete = repo.observeMovieWatchHistory().first().first()
        assertTrue(afterComplete.isCompleted)
        assertEquals(0, afterComplete.rewatchCount)

        fakeClockMs += 2_000L
        repo.restartFromBeginning("moviebox", "interstellar_1")
        val afterRestart = repo.observeMovieWatchHistory().first().first()
        assertEquals(0L, afterRestart.positionMs)
        assertTrue(afterRestart.isCompleted)
        assertEquals(0, afterRestart.rewatchCount)

        // Watching partway and then completing again increments rewatchCount -> 1
        fakeClockMs += 2_000L
        repo.saveWatchProgress(movie, positionMs = 50_000L, durationMs = 1_000_000L)
        assertTrue(repo.observeContinueWatching().first().any { it.compositeId == "moviebox:interstellar_1" })

        fakeClockMs += 2_000L
        repo.saveWatchProgress(movie, positionMs = 970_000L, durationMs = 1_000_000L)
        val afterRewatch = repo.observeMovieWatchHistory().first().first()
        assertEquals(1, afterRewatch.rewatchCount)
        assertTrue(afterRewatch.isCompleted)

        // 3. Remove from Continue Watching & Hide/Unhide Title
        repo.saveWatchProgress(movie, positionMs = 200_000L, durationMs = 1_000_000L)
        repo.removeFromContinueWatching("moviebox", "interstellar_1")
        assertFalse(repo.observeContinueWatching().first().any { it.compositeId == "moviebox:interstellar_1" })

        repo.setTitleHidden("moviebox", "interstellar_1", hidden = true)
        assertTrue(repo.observeMovieWatchHistory().first().isEmpty())
        assertTrue(repo.observeHiddenHistory().first().any { it.compositeId == "moviebox:interstellar_1" })

        repo.setTitleHidden("moviebox", "interstellar_1", hidden = false)
        assertEquals(1, repo.observeMovieWatchHistory().first().size)

        // 4. Mark unwatched removes episode completion
        repo.markUnwatched("moviebox", "andor_1", seasonNumber = 1, episodeNumber = 1)
        assertEquals(1 to 1, repo.computeNextUnwatchedEpisode("moviebox", "andor_1", seasons))
    }

    @Test
    fun personalLibrary_recentlyViewedCollectionsSeriesPrefsRemindersAndOfflinePlayback() = runTest {
        val mediaFile = File.createTempFile("laterbox_offline_test", ".mp4").apply {
            writeText("fake video bytes")
            deleteOnExit()
        }
        val repo = MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(OkHttpClient(), server.url("/").toString().trimEnd('/')),
            tvMazeProvider = TvMazeSeriesProvider(OkHttpClient(), server.url("/").toString().trimEnd('/')),
            tmdbProvider = TmdbOptionalMoviesProvider(OkHttpClient(), apiKeyProvider = { "" }, baseUrl = server.url("/").toString().trimEnd('/')),
            useSampleFixturesOnlyProvider = { false },
            nowMs = { fakeClockMs },
            downloadedAssetDao = db.downloadedAssetDao(),
            localMediaUriResolver = { relPath -> if (relPath == "offline/clip.mp4") mediaFile.toURI().toString() else null }
        )

        val item = MovieTitleSummary(
            id = "arrival_99",
            providerId = "moviebox",
            kind = MediaKind.MOVIE,
            title = "Arrival",
            releaseYear = 2016,
            genres = listOf("Sci-Fi"),
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true
        )

        // Recently viewed
        repo.recordRecentlyViewed(item)
        assertEquals(1, repo.observeRecentlyViewed().first().size)
        assertEquals("Arrival", repo.observeRecentlyViewed().first().first().title)

        // Collections membership
        val colId = db.collectionDao().insertCollection(
            com.laterbox.app.data.local.entity.CollectionEntity(name = "Sci-Fi Classics")
        )
        repo.addMediaToCollection(item, colId)
        assertEquals(listOf(colId), repo.observeCollectionIdsForMedia("moviebox", "arrival_99").first())
        assertEquals(1, repo.observeMediaInCollection(colId).first().size)

        // Series preferences & reminder mute
        repo.saveSeriesAudioPreference("moviebox", "arrival_99", language = "ja", label = "Japanese")
        repo.saveSeriesSubtitlePreference("moviebox", "arrival_99", language = "en", label = "English", disabled = false)
        repo.setSeriesRemindersMuted("moviebox", "arrival_99", muted = true)
        val prefs = repo.getSeriesPreferences("moviebox", "arrival_99")
        assertEquals("ja", prefs?.preferredAudioLanguage)
        assertEquals("en", prefs?.preferredSubtitleLanguage)
        assertFalse(prefs?.subtitlesDisabled ?: true)
        assertTrue(prefs?.remindersMuted ?: false)

        // Viewing reminder
        repo.scheduleViewingReminder(item, triggerAtMillis = fakeClockMs + 60_000L, note = "Watch tonight")
        assertEquals(1, repo.observeActiveViewingReminders().first().size)
        assertEquals("Watch tonight", repo.observeViewingReminderForTitle("moviebox", "arrival_99").first()?.note)
        repo.dismissViewingReminder("moviebox", "arrival_99")
        assertTrue(repo.observeActiveViewingReminders().first().isEmpty())

        // Offline playback availability and fallback when remote provider is unreachable
        db.downloadedAssetDao().insertAsset(
            com.laterbox.app.media.data.db.entity.DownloadedAssetEntity(
                id = "off_1",
                savedLinkId = null,
                sourcePlatform = "MovieBox",
                originalSourceUrl = "https://cdn.example/arrival.mp4",
                title = "Arrival",
                mimeType = "video/mp4",
                mediaType = "VIDEO",
                contentCategory = "MOVIE",
                localPath = "offline/clip.mp4",
                fileStatus = "AVAILABLE",
                fileSize = 4096L,
                checksum = "sum",
                qualityLabel = "1080p",
                sourceRef = "moviebox|arrival_99|0|0|1080p",
                createdAt = fakeClockMs
            )
        )
        assertEquals(1, repo.observeOfflineAvailability().first().size)
        server.shutdown() // Force remote network error -> offline source still plays!
        val offlinePlay = repo.resolvePlayback("moviebox", "arrival_99").getOrThrow()
        assertTrue(offlinePlay is PlaybackResolution.Available)
        val available = offlinePlay as PlaybackResolution.Available
        assertTrue(available.sources.first().isOfflineLocal)
        assertEquals(mediaFile.toURI().toString(), available.sources.first().url)
    }
}
