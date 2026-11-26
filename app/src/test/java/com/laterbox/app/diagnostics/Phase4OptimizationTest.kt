package com.laterbox.app.diagnostics

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.movies.data.cache.StaleAge
import com.laterbox.app.movies.data.fixtures.DevelopmentMoviesFixtureProvider
import com.laterbox.app.movies.data.local.entity.MovieCacheEntryEntity
import com.laterbox.app.movies.data.provider.InternetArchiveMoviesProvider
import com.laterbox.app.movies.data.provider.MovieBoxProvider
import com.laterbox.app.movies.data.provider.TmdbOptionalMoviesProvider
import com.laterbox.app.movies.data.provider.TvMazeSeriesProvider
import com.laterbox.app.movies.data.repository.MoviesRepositoryImpl
import com.laterbox.app.util.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class Phase4OptimizationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var db: LaterboxDatabase
    private val recordedPaths = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        NetworkWaterfallLogger.resetForTest()
        recordedPaths.clear()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                recordedPaths.add(path)
                return when {
                    path.startsWith("/detail/") -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "subject_id":"777",
                              "title":"SWR Test Movie",
                              "subject_type":1,
                              "type":"Movie",
                              "description":"Desc",
                              "release_date":"2024-01-01",
                              "duration":6000,
                              "duration_sec":6000,
                              "imdb_rating":"8.0",
                              "genre":"Action",
                              "country":"US",
                              "language":"English",
                              "cover":"https://cdn.example/p.jpg",
                              "detail_path":"swr-test-movie",
                              "seasons":[],
                              "stars":[],
                              "related":[]
                            }
                            """.trimIndent()
                        )
                    path.startsWith("/api/stream/777/captions") -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"captions":[{"id":"en1","language":"English","code":"en","url":"https://cdn.example/en.srt"}]}""")
                    path.startsWith("/api/stream/777") -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"streams":[{"url":"https://cdn.example/v.mp4","format":"MP4","quality":"720p","quality_value":720,"size_mb":250.0}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LaterboxDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test
    fun staleAgeConstants_matchPerEndpointPolicyC2() {
        assertEquals(24L * 3_600_000L, StaleAge.HOME_MS)
        assertEquals(24L * 3_600_000L, StaleAge.CATALOGUE_MS)
        assertEquals(24L * 3_600_000L, StaleAge.DETAIL_MS)
        assertEquals(24L * 3_600_000L, StaleAge.EPISODES_MS)
        assertEquals(24L * 3_600_000L, StaleAge.EPISODE_METADATA_MS)
        assertEquals(24L * 3_600_000L, StaleAge.STAFF_TITLES_MS)
        assertEquals(6L * 3_600_000L, StaleAge.SEARCH_MS)
        assertEquals(1L * 3_600_000L, StaleAge.SUGGEST_MS)
    }

    @Test
    fun getCachedDetailsFast_honorsLruCacheAnd24hStaleCeiling() = runBlocking {
        var fakeClockMs = 1_700_000_000_000L
        val client = OkHttpClient.Builder().build()
        val baseUrl = server.url("/").toString().trimEnd('/')
        val mb = MovieBoxProvider(
            okHttpClient = client,
            baseUrlProvider = { baseUrl },
            appCheckTokenProvider = { "tok" }
        )
        val repo = MoviesRepositoryImpl(
            moviesDao = db.moviesDao(),
            internetArchiveProvider = InternetArchiveMoviesProvider(client),
            tvMazeProvider = TvMazeSeriesProvider(client),
            tmdbProvider = TmdbOptionalMoviesProvider(client, apiKeyProvider = { "" }),
            fixtureProvider = DevelopmentMoviesFixtureProvider(),
            movieBoxProvider = mb,
            nowMs = { fakeClockMs },
            isInternetArchiveEnabled = { false },
            isTvMazeEnabled = { false },
            isTmdbEnabled = { false },
            ioDispatcher = Dispatchers.IO,
            defaultDispatcher = Dispatchers.Default
        )

        // Fresh fetch populates Room + LruCache(48)
        repo.getDetails("moviebox", "777").getOrThrow()
        val memHit = repo.getCachedDetailsFast("moviebox", "777")
        assertNotNull(memHit)
        assertFalse(memHit!!.isStale)

        // Advance past 6h CACHE_TTL_MS but within 24h StaleAge.DETAIL_MS -> bounded stale hit
        fakeClockMs += MoviesRepositoryImpl.CACHE_TTL_MS + 60_000L
        val staleHit = repo.getCachedDetailsFast("moviebox", "777")
        assertNotNull(staleHit)
        assertTrue(staleHit!!.isStale)

        // Advance past 24h StaleAge.DETAIL_MS -> null (forces loading shell + fresh fetch)
        fakeClockMs += StaleAge.DETAIL_MS
        assertNull(repo.getCachedDetailsFast("moviebox", "777"))

        // Also test direct Room expired >24h entry -> null
        val entry = db.moviesDao().getCacheEntry("details:moviebox:777")
        if (entry != null) {
            db.moviesDao().upsertCacheEntry(
                MovieCacheEntryEntity(
                    cacheKey = entry.cacheKey,
                    providerId = entry.providerId,
                    payloadJson = entry.payloadJson,
                    updatedAt = fakeClockMs - StaleAge.DETAIL_MS - 1_000L,
                    expiresAt = fakeClockMs - StaleAge.DETAIL_MS - 500L
                )
            )
            assertNull(repo.getCachedDetailsFast("moviebox", "777"))
        }
    }

    @Test
    fun backendFallbackPlayback_keepsCaptionsSequentialAfterStreamCallA6() = runBlocking {
        val client = OkHttpClient.Builder().build()
        val baseUrl = server.url("/").toString().trimEnd('/')
        val mb = MovieBoxProvider(
            okHttpClient = client,
            baseUrlProvider = { baseUrl },
            appCheckTokenProvider = { "tok" }
        )
        val res = mb.resolvePlaybackSources("777", null, null).getOrThrow()
        assertTrue(res is com.laterbox.app.movies.domain.model.PlaybackResolution.Available)
        val streamIdx = recordedPaths.indexOfFirst { it.startsWith("/api/stream/777") && !it.contains("/captions") }
        val captionsIdx = recordedPaths.indexOfFirst { it.startsWith("/api/stream/777/captions") }
        assertTrue("Expected both stream and captions calls", streamIdx >= 0 && captionsIdx >= 0)
        assertTrue("Expected sequential captions AFTER /api/stream/777 in fallback path (A6)", captionsIdx > streamIdx)
    }
}
