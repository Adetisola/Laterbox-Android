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
    fun movieBoxMode_routesAllOperationsToMovieBox_andNeverSilentlyFallsBackToOpenArchive() = runTest {
        var failMovieBox = false
        val iaOrTvMazeHits = mutableListOf<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/advancedsearch.php") || path.startsWith("/shows") || path.startsWith("/schedule")) {
                    iaOrTvMazeHits.add(path)
                    return MockResponse().setResponseCode(200).setBody("""{"response":{"numFound":0,"docs":[]}}""")
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
            providerModeProvider = { com.laterbox.app.data.preferences.MoviesProviderMode.MOVIEBOX },
            useSampleFixturesOnlyProvider = { false },
            nowMs = { fakeClockMs }
        )

        // When MovieBox fails initially with no cache, must return failure and NEVER silently call IA/TVMaze
        failMovieBox = true
        val initialErr = repo.getHomeFeed(forceRefresh = false)
        assertTrue(initialErr.isFailure)
        assertTrue("IA/TVMaze must not be hit in MovieBox mode: $iaOrTvMazeHits", iaOrTvMazeHits.isEmpty())

        // Recover MovieBox: caches MovieBox response in Room
        failMovieBox = false
        val liveFeed = repo.getHomeFeed(forceRefresh = true).getOrThrow()
        assertFalse(liveFeed.isOfflineCached)
        assertEquals("777", liveFeed.featuredBanners.first().id)
        assertEquals("moviebox", liveFeed.featuredBanners.first().providerId)
        assertEquals(DataSourceBadge.LIVE_MOVIEBOX, liveFeed.featuredBanners.first().sourceBadge)

        // Search routes strictly to MovieBox
        val searchPage = repo.search("Feature").getOrThrow()
        assertEquals(1, searchPage.items.size)
        assertEquals("moviebox", searchPage.items.first().providerId)

        // Break MovieBox after stale TTL -> returns MovieBox's own offline cache, still zero IA/TVMaze calls
        failMovieBox = true
        fakeClockMs += MoviesRepositoryImpl.CACHE_TTL_MS + 60_000L
        val staleFeed = repo.getHomeFeed(forceRefresh = false).getOrThrow()
        assertTrue(staleFeed.isOfflineCached)
        assertEquals("moviebox", staleFeed.featuredBanners.first().providerId)
        assertTrue(staleFeed.activeProviderNotice.orEmpty().contains("MovieBox"))
        assertTrue(iaOrTvMazeHits.isEmpty())
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
}
