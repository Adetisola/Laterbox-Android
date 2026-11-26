package com.laterbox.app.diagnostics

import com.laterbox.app.BuildConfig
import com.laterbox.app.movies.data.provider.MovieBoxProvider
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.MediaKind
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V1_0_80CompatibilitySmokeTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: MovieBoxProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString()
        provider = MovieBoxProvider(
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { baseUrl }
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun versionCode80_parsesPhase5GzipAndA2SlimmedPayloadsCleanly() = runTest {
        assertEquals(80, BuildConfig.VERSION_CODE)
        assertTrue(
            "Expected versionName to start with 1.0.80, got ${BuildConfig.VERSION_NAME}",
            BuildConfig.VERSION_NAME.startsWith("1.0.80")
        )

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/home" -> gzipJson(
                        cacheControl = "private, max-age=120, stale-while-revalidate=300",
                        json = """
                        {
                          "code": 0,
                          "data": {
                            "genres": ["Action", "Drama"],
                            "operatingList": [
                              {
                                "type": "BANNER",
                                "title": "Hero",
                                "section_kind": "banner",
                                "banner": {
                                  "items": [
                                    {
                                      "id": "b1",
                                      "subjectId": "1001",
                                      "subjectType": 1,
                                      "title": "Smoke Film",
                                      "image": {"url": "https://cdn.example/hero.jpg", "blurHash": "LHERO"},
                                      "subject": {
                                        "subjectId": "1001",
                                        "subjectType": 1,
                                        "title": "Smoke Film",
                                        "detailPath": "smoke-film",
                                        "releaseDate": "2025-05-01",
                                        "duration": 6600,
                                        "imdbRatingValue": "8.2",
                                        "genre": "Action,Drama",
                                        "language": "English",
                                        "countryName": "United States",
                                        "hasResource": true,
                                        "cover": {"url": "https://cdn.example/cover.jpg", "blurHash": "LCOVER"},
                                        "stills": {"url": "https://cdn.example/stills.jpg", "blurHash": "LSTILL"},
                                        "trailer": {"videoAddress": {"url": "https://cdn.example/trailer.mp4"}}
                                      }
                                    }
                                  ]
                                }
                              },
                              {
                                "type": "SUBJECTS_MOVIE",
                                "opId": "trending",
                                "title": "Trending",
                                "section_kind": "rail",
                                "subjects": [
                                  {
                                    "subjectId": "1001",
                                    "subjectType": 1,
                                    "title": "Smoke Film",
                                    "detailPath": "smoke-film",
                                    "releaseDate": "2025-05-01",
                                    "duration": 6600,
                                    "imdbRatingValue": "8.2",
                                    "genre": "Action,Drama",
                                    "cover": {"url": "https://cdn.example/cover.jpg", "blurHash": "LCOVER"}
                                  }
                                ]
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )

                    path.startsWith("/movies") -> gzipJson(
                        cacheControl = "private, max-age=120, stale-while-revalidate=300",
                        json = """
                        {
                          "page": 1,
                          "per_page": 24,
                          "total": 1,
                          "items": [
                            {
                              "title": "Smoke Film",
                              "subject_id": "1001",
                              "subject_type": 1,
                              "detail_path": "smoke-film",
                              "cover": "https://cdn.example/cover.jpg",
                              "stills": {"url": "https://cdn.example/stills.jpg", "blur_hash": "LSTILL"},
                              "rating": "8.2",
                              "year": "2025",
                              "release_date": "2025-05-01",
                              "duration": 6600,
                              "language": "English",
                              "genre": "Action,Drama",
                              "country": "United States"
                            }
                          ]
                        }
                        """.trimIndent()
                    )

                    path.startsWith("/search") -> gzipJson(
                        cacheControl = "private, max-age=60, stale-while-revalidate=120",
                        json = """
                        {
                          "query": "Smoke",
                          "page": 1,
                          "results": [
                            {
                              "subject_id": "1001",
                              "title": "Smoke Film",
                              "type": "Movie",
                              "subject_type": 1,
                              "release_date": "2025-05-01",
                              "duration": 6600,
                              "imdb_rating": "8.2",
                              "genre": "Action,Drama",
                              "country": "United States",
                              "cover": "https://cdn.example/cover.jpg",
                              "stills": {"url": "https://cdn.example/stills.jpg", "blur_hash": "LSTILL"},
                              "detail_path": "smoke-film",
                              "language": "English"
                            }
                          ]
                        }
                        """.trimIndent()
                    )

                    path == "/detail/1001" -> gzipJson(
                        cacheControl = "private, max-age=120, stale-while-revalidate=300",
                        json = """
                        {
                          "subject_id": "1001",
                          "title": "Smoke Film",
                          "subject_type": 1,
                          "type": "Movie",
                          "description": "Smoke test synopsis.",
                          "release_date": "2025-05-01",
                          "duration": 6600,
                          "duration_sec": 6600,
                          "imdb_rating": "8.2",
                          "imdb_id": "tt1234567",
                          "genre": "Action,Drama",
                          "country": "United States",
                          "language": "English",
                          "cover": "https://cdn.example/cover.jpg",
                          "cover_width": 400,
                          "cover_height": 600,
                          "cover_blur_hash": "LCOVER",
                          "stills": {"url": "https://cdn.example/stills.jpg", "width": 1280, "height": 720, "blur_hash": "LSTILL"},
                          "detail_path": "smoke-film",
                          "trailer_url": "https://cdn.example/trailer.mp4",
                          "stars": [],
                          "seasons": [],
                          "related": []
                        }
                        """.trimIndent()
                    )

                    path == "/episodes/1001" -> gzipJson(
                        cacheControl = "private, max-age=60, stale-while-revalidate=120",
                        json = """
                        {
                          "subject_id": "1001",
                          "seasons": [
                            {"season": 1, "episode_count": 3, "all_ep": [1, 2, 3], "resolutions": [1080, 720]}
                          ]
                        }
                        """.trimIndent()
                    )

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val home = provider.getHomeFeed().getOrThrow()
        assertEquals(1, home.featuredBanners.size)
        assertEquals("LHERO", home.featuredBanners.first().backdropBlurHash)
        assertEquals("https://cdn.example/trailer.mp4", home.featuredBanners.first().trailerUrl)
        assertEquals(1, home.sections.size)
        assertEquals("LCOVER", home.sections.first().items.first().posterBlurHash)

        val catalogue = provider.getCatalogue(
            kind = MediaKind.MOVIE,
            page = 1,
            pageSize = 24,
            genre = null,
            sort = CatalogueSort.POPULAR,
            country = null,
            year = null
        ).getOrThrow()
        assertEquals(1, catalogue.items.size)
        assertEquals("LSTILL", catalogue.items.first().backdropBlurHash)

        val searchPage = provider.search(query = "Smoke", kind = null, page = 1, pageSize = 20).getOrThrow()
        assertEquals(1, searchPage.items.size)
        assertEquals("1001", searchPage.items.first().id)

        val detail = provider.getDetails("1001").getOrThrow()
        assertEquals("Smoke Film", detail.summary.title)
        assertEquals("https://cdn.example/trailer.mp4", detail.summary.trailerUrl)

        val episodes = provider.getEpisodes("1001", seasonNumber = 1).getOrThrow()
        assertEquals(3, episodes.size)
        assertNotNull(episodes.first())
    }

    private fun gzipJson(cacheControl: String, json: String): MockResponse {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            gz.write(json.toByteArray(Charsets.UTF_8))
        }
        val buffer = Buffer().write(baos.toByteArray())
        return MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .addHeader("Content-Encoding", "gzip")
            .addHeader("Cache-Control", cacheControl)
            .setBody(buffer)
    }
}
