package com.laterbox.app.movies

import com.laterbox.app.movies.data.download.SubtitleDownloadManager
import com.laterbox.app.movies.domain.model.SubtitleTrack
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class SubtitleDownloadManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var subtitlesRoot: File
    private lateinit var manager: SubtitleDownloadManager

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        subtitlesRoot = tempFolder.newFolder("subtitles")
        manager = SubtitleDownloadManager(
            subtitlesRootDir = subtitlesRoot,
            okHttpClient = OkHttpClient()
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun downloadAllSubtitles_persistsSrtAndVttWithHeadersAndResolvesZeroNetworkOffline() = runTest {
        val observedReferers = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                observedReferers.add(request.getHeader("Referer"))
                return when (request.path) {
                    "/subs/en.srt" -> MockResponse().setResponseCode(200)
                        .setBody("1\n00:00:01,000 --> 00:00:03,000\nHello world\n")
                    "/subs/es.vtt" -> MockResponse().setResponseCode(200)
                        .setBody("WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nHola mundo\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val tracks = listOf(
            SubtitleTrack(
                id = "sub_en",
                language = "English",
                languageCode = "en",
                label = "English",
                url = server.url("/subs/en.srt").toString(),
                format = "srt",
                headers = mapOf("Referer" to "https://player.example/")
            ),
            SubtitleTrack(
                id = "sub_es",
                language = "Spanish",
                languageCode = "es",
                label = "Spanish",
                url = server.url("/subs/es.vtt").toString(),
                format = "vtt"
            )
        )

        val summary = manager.downloadSubtitlesForAsset(
            assetId = "asset_movie_1",
            sourceRef = "moviebox|tt100|0|0|1080p",
            providerId = "moviebox",
            titleId = "tt100",
            seasonNumber = null,
            episodeNumber = null,
            subtitles = tracks,
            fallbackHeaders = mapOf("Referer" to "https://fallback.example/")
        )

        assertEquals(2, summary.totalCount)
        assertEquals(2, summary.completedCount)
        assertEquals(0, summary.failedCount)
        assertTrue(observedReferers.contains("https://player.example/"))
        assertTrue(observedReferers.contains("https://fallback.example/"))

        // Shutdown server to prove zero network access for offline lookups + recreate manager to verify disk persistence
        server.shutdown()
        val freshManager = SubtitleDownloadManager(subtitlesRootDir = subtitlesRoot)

        val offlineByAsset = freshManager.getOfflineSubtitleTracks("asset_movie_1")
        assertEquals(2, offlineByAsset.size)
        assertTrue(offlineByAsset.all { it.isOfflineLocal && it.url.startsWith("file:") })
        assertEquals(setOf("srt", "vtt"), offlineByAsset.map { it.format }.toSet())

        val offlineByTitle = freshManager.getOfflineSubtitleTracksForTitle("moviebox", "tt100", null, null)
        assertEquals(2, offlineByTitle.size)

        // Wrong episode must NOT match movie subtitles
        val wrongEpisode = freshManager.getOfflineSubtitleTracksForTitle("moviebox", "tt100", 1, 1)
        assertTrue(wrongEpisode.isEmpty())
    }

    @Test
    fun partialAndTotalFailures_preserveCompletedTracksAndRetryOnlyFailedTracks() = runTest {
        val enHits = AtomicInteger(0)
        val frHits = AtomicInteger(0)
        var allowFrSuccess = false

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/subs/en.srt" -> {
                        enHits.incrementAndGet()
                        MockResponse().setResponseCode(200).setBody("1\n00:00:01,000 --> 00:00:02,000\nEnglish line\n")
                    }
                    "/subs/fr.srt" -> {
                        frHits.incrementAndGet()
                        if (allowFrSuccess) {
                            MockResponse().setResponseCode(200).setBody("1\n00:00:01,000 --> 00:00:02,000\nLigne francaise\n")
                        } else {
                            MockResponse().setResponseCode(500)
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val partial = manager.downloadSubtitlesForAsset(
            assetId = "asset_ep_1_2",
            sourceRef = "moviebox|series_9|1|2|720p",
            providerId = "moviebox",
            titleId = "series_9",
            seasonNumber = 1,
            episodeNumber = 2,
            subtitles = listOf(
                SubtitleTrack("en", "English", "en", "English", server.url("/subs/en.srt").toString(), "srt"),
                SubtitleTrack("fr", "French", "fr", "French", server.url("/subs/fr.srt").toString(), "srt")
            )
        )

        assertEquals(2, partial.totalCount)
        assertEquals(1, partial.completedCount)
        assertEquals(1, partial.failedCount)
        assertTrue(partial.isPartialSuccess)
        assertTrue(partial.hasFailures)

        // Completed English track still available offline even while French failed
        val availableNow = manager.getOfflineSubtitleTracksForTitle("moviebox", "series_9", 1, 2)
        assertEquals(1, availableNow.size)
        assertEquals("en", availableNow.first().languageCode)
        // Episode 1 must NOT see Episode 2 subtitles
        assertTrue(manager.getOfflineSubtitleTracksForTitle("moviebox", "series_9", 1, 1).isEmpty())

        val enHitsBeforeRetry = enHits.get()
        allowFrSuccess = true
        val retried = manager.retryFailedSubtitles("asset_ep_1_2")!!
        assertEquals(2, retried.completedCount)
        assertEquals(0, retried.failedCount)
        assertFalse(retried.hasFailures)
        // Already completed English subtitle was NOT re-downloaded
        assertEquals(enHitsBeforeRetry, enHits.get())
        assertTrue(frHits.get() > 2)
        assertEquals(2, manager.getOfflineSubtitleTracks("asset_ep_1_2").size)

        // Total failure case does not throw
        val totalFailure = manager.downloadSubtitlesForAsset(
            assetId = "asset_all_fail",
            sourceRef = null,
            providerId = "moviebox",
            titleId = "missing_subs",
            seasonNumber = null,
            episodeNumber = null,
            subtitles = listOf(SubtitleTrack("de", "German", "de", "German", server.url("/subs/missing.srt").toString(), "srt"))
        )
        assertEquals(0, totalFailure.completedCount)
        assertEquals(1, totalFailure.failedCount)
        assertTrue(manager.getOfflineSubtitleTracks("asset_all_fail").isEmpty())
    }

    @Test
    fun deleteSubtitlesForAsset_removesSidecarFilesAndManifest() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody("1\n00:00:01,000 --> 00:00:02,000\nLine\n")
        }

        manager.downloadSubtitlesForAsset(
            assetId = "asset_del",
            sourceRef = "moviebox|m_del|0|0|720p",
            providerId = "moviebox",
            titleId = "m_del",
            seasonNumber = null,
            episodeNumber = null,
            subtitles = listOf(SubtitleTrack("en", "English", "en", "English", server.url("/sub.srt").toString(), "srt"))
        )
        assertEquals(1, manager.getOfflineSubtitleTracks("asset_del").size)
        assertTrue(File(subtitlesRoot, "asset_del").exists())

        assertTrue(manager.deleteSubtitlesForAsset("asset_del"))
        assertFalse(File(subtitlesRoot, "asset_del").exists())
        assertNull(manager.getSubtitleSummary("asset_del"))
        assertTrue(manager.getOfflineSubtitleTracks("asset_del").isEmpty())
    }
}
