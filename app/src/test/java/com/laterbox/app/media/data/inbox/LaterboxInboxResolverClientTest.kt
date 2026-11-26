package com.laterbox.app.media.data.inbox

import com.laterbox.app.media.domain.model.AcquisitionSource
import com.laterbox.app.media.domain.model.InspectionResult
import com.laterbox.app.media.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaterboxInboxResolverClientTest {

    @Test
    fun inboxLinkRouter_routesXInstagramSpotifyOnly() {
        assertEquals(InboxResolvePlatform.TWITTER, InboxLinkRouter.routeForUrl("https://x.com/user/status/123"))
        assertEquals(InboxResolvePlatform.TWITTER, InboxLinkRouter.routeForUrl("https://twitter.com/user/status/123"))
        assertEquals(InboxResolvePlatform.TWITTER, InboxLinkRouter.routeForUrl("https://fxtwitter.com/user/status/123"))

        assertEquals(InboxResolvePlatform.INSTAGRAM, InboxLinkRouter.routeForUrl("https://www.instagram.com/reel/abc123/"))
        assertEquals(InboxResolvePlatform.INSTAGRAM, InboxLinkRouter.routeForUrl("https://instagram.com/p/xyz987/"))
        assertEquals(InboxResolvePlatform.INSTAGRAM, InboxLinkRouter.routeForUrl("https://instagr.am/p/xyz987/"))

        assertEquals(InboxResolvePlatform.SPOTIFY, InboxLinkRouter.routeForUrl("https://open.spotify.com/track/5XeFesFbtLpXzIVDNQP22n"))
        assertEquals(InboxResolvePlatform.SPOTIFY, InboxLinkRouter.routeForUrl("https://spotify.link/abc"))

        assertNull(InboxLinkRouter.routeForUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(InboxLinkRouter.routeForUrl("https://www.tiktok.com/@user/video/123456"))
        assertNull(InboxLinkRouter.routeForUrl("https://nytimes.com/article"))
    }

    @Test
    fun backendInboxAdapter_resolvesTwitterVariantsDirectHttpWithoutProxy() = runBlocking {
        var requestedPath: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                requestedPath = req.url.encodedPath
                assertEquals("appcheck-token-1", req.header("X-Firebase-AppCheck"))
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """
                        {
                          "type": "video",
                          "thumbnail": "https://pbs.twimg.com/thumb.jpg",
                          "duration_ms": 11093,
                          "variants": [
                            {"url": "https://video.twimg.com/720.mp4", "bitrate": 2176000, "quality": "720p"},
                            {"url": "https://video.twimg.com/360.mp4", "bitrate": 832000, "quality": "360p"}
                          ]
                        }
                        """.trimIndent().toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()

        val resolver = LaterboxInboxResolverClient(
            okHttpClient = client,
            baseUrlProvider = { "https://api.example.com/" },
            appCheckTokenProvider = { "appcheck-token-1" }
        )
        val adapter = BackendInboxMediaAdapter(resolver)

        assertTrue(adapter.canHandle("https://x.com/TwitterDev/status/1460323737035677698"))
        assertFalse(adapter.canHandle("https://www.youtube.com/watch?v=abc"))

        val result = adapter.inspect("https://x.com/TwitterDev/status/1460323737035677698")
        assertEquals("/api/inbox/resolve/twitter", requestedPath)
        assertTrue(result is InspectionResult.Available)
        val available = result as InspectionResult.Available
        assertEquals("X", available.platform)
        assertEquals("https://pbs.twimg.com/thumb.jpg", available.thumbnailUrl)
        assertEquals(2, available.candidates.size)
        assertTrue(available.candidates[0].isBest)
        assertEquals("720p MP4", available.candidates[0].formatLabel)
        val source = available.candidates[0].source as AcquisitionSource.DirectHttp
        assertEquals("https://video.twimg.com/720.mp4", source.url)
    }

    @Test
    fun backendInboxAdapter_routesInstagramRefererMediaThroughProxyWithAuthHeaders() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """
                        {
                          "type": "carousel",
                          "requires_referer": true,
                          "items": [
                            {"type": "image", "url": "https://scontent.cdninstagram.com/a.jpg", "thumbnail": "https://scontent.cdninstagram.com/a.jpg"},
                            {"type": "video", "url": "https://scontent.cdninstagram.com/b.mp4", "thumbnail": "https://scontent.cdninstagram.com/b_th.jpg"}
                          ]
                        }
                        """.trimIndent().toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()

        val resolver = LaterboxInboxResolverClient(
            okHttpClient = client,
            baseUrlProvider = { "https://api.example.com" },
            appCheckTokenProvider = { "appcheck-ig" }
        )
        val result = resolver.resolveToInspectionResult("https://www.instagram.com/p/BvRwa1tArMo/")
        assertTrue(result is InspectionResult.Available)
        val available = result as InspectionResult.Available
        assertEquals("Instagram", available.platform)
        assertEquals(2, available.candidates.size)
        assertEquals(MediaType.IMAGE, available.candidates[0].mediaType)
        assertEquals(MediaType.VIDEO, available.candidates[1].mediaType)

        val src0 = available.candidates[0].source as AcquisitionSource.DirectHttp
        assertTrue(src0.url.startsWith("https://api.example.com/api/inbox/download/instagram?url="))
        assertEquals("appcheck-ig", src0.headers["X-Firebase-AppCheck"])
    }

    @Test
    fun backendInboxAdapter_spotifyAndNoMediaReturnCleanUnsupportedWithoutLeakingInternals() = runBlocking {
        var callCount = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                callCount++
                val req = chain.request()
                val (code, body) = when (callCount) {
                    1 -> 401 to """{"detail":"expired"}"""
                    2 -> 200 to """{"type":"unsupported","reason":"token_refresh_required","fallback":"open_in_browser"}"""
                    else -> 404 to """{"detail":"no_media"}"""
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("status")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val tokensSeen = mutableListOf<Boolean>()
        val resolver = LaterboxInboxResolverClient(
            okHttpClient = client,
            baseUrlProvider = { "https://api.example.com" },
            appCheckTokenProvider = { refresh ->
                tokensSeen.add(refresh)
                if (refresh) "fresh-tok" else "stale-tok"
            }
        )

        val spRes = resolver.resolveToInspectionResult("https://open.spotify.com/track/5XeFesFbtLpXzIVDNQP22n")
        assertTrue(spRes is InspectionResult.Unsupported)
        val reason = (spRes as InspectionResult.Unsupported).reason.lowercase()
        assertFalse(reason.contains("spotidownloader"))
        assertFalse(reason.contains("token_refresh_required"))
        assertEquals(listOf(false, true), tokensSeen)

        val twRes = resolver.resolveToInspectionResult("https://twitter.com/SpaceX/status/736313781907685376")
        assertTrue(twRes is InspectionResult.Unsupported)
    }

    @Test
    fun backendInboxAdapter_resolvesSpotifyAudioMp3AndFallsBackCleanlyWhenUnavailable() = runBlocking {
        var callCount = 0
        var requestedPath: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                callCount++
                val req = chain.request()
                requestedPath = req.url.encodedPath
        val body = if (callCount == 1) {
            """
            {
              "type": "audio",
              "source": "youtube",
              "spotify": {
                "title": "Midnight City",
                "artist": "M83",
                "album": "Hurry Up, We're Dreaming",
                "cover": "https://i.scdn.co/image/m83.jpg",
                "duration_ms": 243000
              },
              "youtube": {
                "video_id": "dX3k_QDnzHE",
                "title": "M83 - Midnight City",
                "duration_ms": 243000,
                "audio_url": "https://rr1.googlevideo.com/midnight.m4a",
                "confidence": "high"
              }
            }
            """.trimIndent()
        } else {
            """
            {
              "type": "unsupported",
              "reason": "no_youtube_match",
              "fallback": "open_in_spotify",
              "spotify": {
                "title": "I Wanna Be Yours",
                "artist": "Arctic Monkeys",
                "album": "AM",
                "cover": "https://i.scdn.co/image/am.jpg",
                "duration_ms": 183000
              }
            }
            """.trimIndent()
        }
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
    .build()

        val resolver = LaterboxInboxResolverClient(
            okHttpClient = client,
            baseUrlProvider = { "https://api.example.com" },
            appCheckTokenProvider = { "appcheck-spotify" }
        )

        val audioRes = resolver.resolveToInspectionResult("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT")
        assertEquals("/api/inbox/resolve/spotify", requestedPath)
        assertTrue(audioRes is InspectionResult.Available)
        val available = audioRes as InspectionResult.Available
        assertEquals("Spotify", available.platform)
        assertEquals("Midnight City", available.title)
        assertEquals("M83", available.artist)
        assertEquals("https://i.scdn.co/image/m83.jpg", available.thumbnailUrl)
        assertEquals(1, available.musicMatches.size)
        assertEquals("via YouTube", available.musicMatches.single().sourceDescription)
        assertEquals(1, available.candidates.size)
        val candidate = available.candidates.single()
        assertEquals(MediaType.AUDIO, candidate.mediaType)
        assertEquals("m4a", candidate.ext)
        assertEquals("M83 — Midnight City", candidate.formatLabel)
        assertEquals("https://rr1.googlevideo.com/midnight.m4a", (candidate.source as AcquisitionSource.DirectHttp).url)

        val fallbackRes = resolver.resolveToInspectionResult("https://open.spotify.com/track/5XeFesFbtLpXzIVDNQP22n")
        assertTrue(fallbackRes is InspectionResult.Unsupported)
        val unsupported = fallbackRes as InspectionResult.Unsupported
        assertEquals("I Wanna Be Yours", unsupported.spotifyTitle)
        assertEquals("Arctic Monkeys", unsupported.spotifyArtist)
        assertEquals("AM", unsupported.spotifyAlbum)
        assertEquals("https://i.scdn.co/image/am.jpg", unsupported.spotifyCoverUrl)
        val fallbackReason = unsupported.reason.lowercase()
        assertFalse(fallbackReason.contains("spotidownloader"))
        assertFalse(fallbackReason.contains("gleeze"))
    }
}
