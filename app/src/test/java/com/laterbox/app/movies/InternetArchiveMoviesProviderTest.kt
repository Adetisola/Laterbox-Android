package com.laterbox.app.movies

import com.laterbox.app.movies.data.provider.InternetArchiveMoviesProvider
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.PlaybackResolution
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InternetArchiveMoviesProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: InternetArchiveMoviesProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = InternetArchiveMoviesProvider(
            okHttpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/')
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun homeFeedAndCatalogue_parseArchiveDocsCleanly() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/advancedsearch.php") -> MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "response": {
                            "numFound": 2,
                            "docs": [
                              {
                                "identifier": "his_girl_friday",
                                "title": "His Girl Friday",
                                "year": "1940",
                                "description": "<p>Classic screwball comedy.</p>",
                                "avg_rating": 4.7,
                                "subject": ["Comedy", "Drama"]
                              },
                              {
                                "identifier": "detour_1945",
                                "title": "Detour",
                                "year": 1945,
                                "description": "Film noir cult classic",
                                "subject": "Film Noir; Mystery"
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val feed = provider.getHomeFeed().getOrThrow()
        assertTrue(feed.featuredBanners.isNotEmpty())
        assertTrue(feed.sections.isNotEmpty())
        assertEquals(DataSourceBadge.LIVE_INTERNET_ARCHIVE, feed.featuredBanners.first().sourceBadge)
        assertEquals("His Girl Friday", feed.featuredBanners.first().title)

        val catalogue = provider.getCatalogue(
            kind = MediaKind.MOVIE,
            page = 1,
            pageSize = 20,
            genre = "Comedy",
            sort = CatalogueSort.TOP_RATED
        ).getOrThrow()
        assertEquals(2, catalogue.items.size)
        assertEquals("detour_1945", catalogue.items[1].id)
    }

    @Test
    fun resolvePlaybackSources_extractsMp4QualityOrderAndSubtitles() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "metadata": {
                    "identifier": "his_girl_friday",
                    "title": "His Girl Friday",
                    "year": "1940",
                    "director": "Howard Hawks",
                    "description": "Fast-talking newsroom comedy.",
                    "subject": ["Comedy", "Classics"]
                  },
                  "files": [
                    {
                      "name": "his_girl_friday_512kb.mp4",
                      "format": "512Kb MPEG4",
                      "size": "420000000",
                      "length": "5500"
                    },
                    {
                      "name": "his_girl_friday_720p.mp4",
                      "format": "MPEG4",
                      "height": "720",
                      "size": "950000000",
                      "length": "5500"
                    },
                    {
                      "name": "his_girl_friday.asr.srt",
                      "format": "SubRip"
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val resolution = provider.resolvePlaybackSources("his_girl_friday").getOrThrow()
        assertTrue(resolution is PlaybackResolution.Available)
        val available = resolution as PlaybackResolution.Available
        assertEquals(2, available.sources.size)
        assertEquals("720p", available.sources.first().qualityLabel)
        assertTrue(available.sources.first().url.endsWith("/download/his_girl_friday/his_girl_friday_720p.mp4"))
        assertEquals(1, available.subtitles.size)
        assertEquals("srt", available.subtitles.first().format)
        assertEquals("en", available.subtitles.first().languageCode)
    }

    @Test
    fun resolvePlaybackSources_returnsUnavailableWhenNoCompatibleVideoFiles() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "metadata": {"title": "Metadata Only Archive Item"},
                  "files": [
                    {"name": "poster.jpg", "format": "JPEG"}
                  ]
                }
                """.trimIndent()
            )
        )

        val resolution = provider.resolvePlaybackSources("no_video_item").getOrThrow()
        assertTrue(resolution is PlaybackResolution.Unavailable)
        val unavailable = resolution as PlaybackResolution.Unavailable
        assertTrue(unavailable.externalReferenceUrl!!.endsWith("/details/no_video_item"))
    }
}
