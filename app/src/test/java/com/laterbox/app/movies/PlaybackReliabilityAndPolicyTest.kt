package com.laterbox.app.movies

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.media.data.db.entity.DownloadTaskEntity
import com.laterbox.app.media.data.download.DownloadSourceRefresher
import com.laterbox.app.media.domain.model.DownloadState
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.movies.data.cache.LaterboxLoadErrorHandlingPolicy
import com.laterbox.app.movies.data.cache.MoviesMediaCache
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.repository.MoviesRepository
import com.laterbox.app.movies.ui.MoviePlayerViewModel
import com.laterbox.app.movies.ui.PlaybackHttpFailureInfo
import com.laterbox.app.movies.ui.extractHttpFailureInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackReliabilityAndPolicyTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var appContext: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        appContext = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        MoviesMediaCache.releaseForTesting()
        Dispatchers.resetMain()
    }

    private fun buildLoadErrorInfo(
        exception: IOException,
        errorCount: Int,
        dataType: Int = C.DATA_TYPE_MEDIA
    ): LoadErrorHandlingPolicy.LoadErrorInfo {
        val uri = Uri.parse("https://bcdnxw.hakunaymatata.com/video.mp4")
        val dataSpec = DataSpec(uri)
        val loadEvent = LoadEventInfo(1L, dataSpec, 0L)
        val mediaLoadData = MediaLoadData(dataType)
        return LoadErrorHandlingPolicy.LoadErrorInfo(loadEvent, mediaLoadData, exception, errorCount)
    }

    private fun httpException(code: Int, headers: Map<String, List<String>> = emptyMap()): HttpDataSource.InvalidResponseCodeException {
        val dataSpec = DataSpec(Uri.parse("https://bcdnxw.hakunaymatata.com/video.mp4"))
        return HttpDataSource.InvalidResponseCodeException(
            code,
            "HTTP $code",
            null,
            headers,
            dataSpec,
            ByteArray(0)
        )
    }

    @Test
    fun loadErrorPolicy_stops429RapidRetriesWhilePreservingOtherErrors() {
        val policy = LaterboxLoadErrorHandlingPolicy()

        // HTTP 429 without Retry-After fails immediately (no 3 rapid retries)
        assertEquals(
            C.TIME_UNSET,
            policy.getRetryDelayMsFor(buildLoadErrorInfo(httpException(429), errorCount = 1))
        )

        // HTTP 429 with Retry-After > 5s interactive cap fails immediately so ViewModel handles cooldown
        assertEquals(
            C.TIME_UNSET,
            policy.getRetryDelayMsFor(
                buildLoadErrorInfo(
                    httpException(429, mapOf("Retry-After" to listOf("18"))),
                    errorCount = 1
                )
            )
        )

        // HTTP 429 with short Retry-After (<=5s) on DATA_TYPE_MEDIA allows at most ONE delayed retry
        assertEquals(
            3_000L,
            policy.getRetryDelayMsFor(
                buildLoadErrorInfo(
                    httpException(429, mapOf("Retry-After" to listOf("3"))),
                    errorCount = 1,
                    dataType = C.DATA_TYPE_MEDIA
                )
            )
        )
        assertEquals(
            C.TIME_UNSET,
            policy.getRetryDelayMsFor(
                buildLoadErrorInfo(
                    httpException(429, mapOf("Retry-After" to listOf("3"))),
                    errorCount = 2,
                    dataType = C.DATA_TYPE_MEDIA
                )
            )
        )

        // Manifest 429 fails immediately even if Retry-After is short
        assertEquals(
            C.TIME_UNSET,
            policy.getRetryDelayMsFor(
                buildLoadErrorInfo(
                    httpException(429, mapOf("Retry-After" to listOf("2"))),
                    errorCount = 1,
                    dataType = C.DATA_TYPE_MANIFEST
                )
            )
        )

        // Auth/signature failures (401, 403) fail immediately so ViewModel can switch or refresh once
        assertEquals(
            C.TIME_UNSET,
            policy.getRetryDelayMsFor(buildLoadErrorInfo(httpException(401), errorCount = 1))
        )
        assertEquals(
            C.TIME_UNSET,
            policy.getRetryDelayMsFor(buildLoadErrorInfo(httpException(403), errorCount = 1))
        )

        // Ordinary 503 and IOException preserve default Media3 retry behavior
        assertEquals(
            0L,
            policy.getRetryDelayMsFor(buildLoadErrorInfo(httpException(503), errorCount = 1))
        )
        assertEquals(
            1_000L,
            policy.getRetryDelayMsFor(buildLoadErrorInfo(IOException("timeout"), errorCount = 2))
        )
    }

    @Test
    fun exoPlayerMediaSourceFactory_issuesSingleRequestOn429AndPreservesRetryAfter() {
        val server = MockWebServer()
        server.start()
        try {
            repeat(4) {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(429)
                        .addHeader("Date", "Tue, 24 Nov 2026 12:00:00 GMT")
                        .addHeader("Retry-After", "Tue, 24 Nov 2026 12:00:25 GMT")
                        .addHeader("Server", "nginx")
                        .setBody("Too Many Requests")
                )
            }

            val player = ExoPlayer.Builder(appContext)
                .setLooper(Looper.getMainLooper())
                .build()
            var capturedError: PlaybackException? = null
            var errorCallbacks = 0
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    errorCallbacks++
                    capturedError = error
                }
            })

            try {
                val url = server.url("/stream/movie.mp4").toString()
                val mediaSource = MoviesMediaCache
                    .createMediaSourceFactory(appContext, useDiskCache = false)
                    .createMediaSource(MediaItem.fromUri(url))
                player.setMediaSource(mediaSource)
                player.playWhenReady = true
                player.prepare()

                val deadlineMs = System.currentTimeMillis() + 5_000L
                while (capturedError == null && System.currentTimeMillis() < deadlineMs) {
                    shadowOf(player.playbackLooper).idleFor(java.time.Duration.ofMillis(50))
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
                    Thread.sleep(10L)
                }
            } finally {
                player.release()
                shadowOf(Looper.getMainLooper()).idle()
            }

            assertNotNull(
                "Expected PlaybackException on HTTP 429 (requests=${server.requestCount})",
                capturedError
            )
            assertEquals("Expected exactly 1 error callback", 1, errorCallbacks)
            assertEquals("Expected 1 HTTP attempt (no 3x default Media3 retries)", 1, server.requestCount)

            val failureInfo: PlaybackHttpFailureInfo? = extractHttpFailureInfo(capturedError!!)
            assertNotNull(failureInfo)
            assertEquals(429, failureInfo?.statusCode)
            assertEquals(25L, failureInfo?.retryAfterSeconds)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun downloadSourceRefresher_excludesAliasFailureDomainWithoutFallingBackToCooledSources() = runTest {
        val db = Room.inMemoryDatabaseBuilder(appContext, LaterboxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val taskDao = db.downloadTaskDao()
            val task = DownloadTaskEntity(
                id = "t_alias_1",
                savedLinkId = null,
                sourceUrl = "https://bcdnxw.hakunaymatata.com/play/ep1.mp4",
                sourcePlatform = "MovieBox",
                candidateId = "c_ep1",
                formatSelector = null,
                mediaType = MediaType.VIDEO.name,
                qualityLabel = "720p · MP4",
                title = "Episode 1",
                status = DownloadState.DOWNLOADING.name,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                sourceRef = "moviebox|s_1|1|1|720p"
            )
            taskDao.insertTask(task)

            // When refresh only yields bcdnxw & bcdnw (same failure domain), must return null (not re-select bcdnw)
            val noneAllowed = DownloadSourceRefresher.refreshTaskSource(
                task = task,
                taskDao = taskDao,
                excludeHost = "bcdnxw.hakunaymatata.com",
                resolvePlayback = { _, _, _, _ ->
                    Result.success(
                        PlaybackResolution.Available(
                            title = "Show",
                            sources = listOf(
                                PlaybackSource(
                                    id = "p1",
                                    url = "https://bcdnxw.hakunaymatata.com/play/ep1_new.mp4",
                                    format = "MP4",
                                    qualityLabel = "720p",
                                    qualityHeight = 720,
                                    providerName = "MovieBox",
                                    isAuthorised = true
                                ),
                                PlaybackSource(
                                    id = "p2",
                                    url = "https://bcdnw.hakunaymatata.com/dl/ep1_new.mp4",
                                    format = "MP4",
                                    qualityLabel = "720p",
                                    qualityHeight = 720,
                                    providerName = "MovieBox",
                                    isAuthorised = true
                                )
                            )
                        )
                    )
                }
            )
            assertNull(noneAllowed)

            // Independent CDN candidate is still selected cleanly
            val independentPicked = DownloadSourceRefresher.refreshTaskSource(
                task = task,
                taskDao = taskDao,
                excludeHost = "bcdnxw.hakunaymatata.com",
                resolvePlayback = { _, _, _, _ ->
                    Result.success(
                        PlaybackResolution.Available(
                            title = "Show",
                            sources = listOf(
                                PlaybackSource(
                                    id = "p2",
                                    url = "https://bcdnw.hakunaymatata.com/dl/ep1_new.mp4",
                                    format = "MP4",
                                    qualityLabel = "720p",
                                    qualityHeight = 720,
                                    providerName = "MovieBox",
                                    isAuthorised = true
                                ),
                                PlaybackSource(
                                    id = "p3",
                                    url = "https://independent-cdn.example/ep1_new.mp4",
                                    format = "MP4",
                                    qualityLabel = "720p",
                                    qualityHeight = 720,
                                    providerName = "MovieBox",
                                    isAuthorised = true
                                )
                            )
                        )
                    )
                }
            )
            assertNotNull(independentPicked)
            assertEquals("https://independent-cdn.example/ep1_new.mp4", independentPicked?.sourceUrl)
        } finally {
            db.close()
        }
    }
}
