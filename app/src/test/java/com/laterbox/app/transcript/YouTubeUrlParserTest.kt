package com.laterbox.app.transcript.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeUrlParserTest {

    @Test
    fun parse_standardWatchUrl_returnsSingleVideo() {
        val target = YouTubeUrlParser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue(target is YouTubeUrlTarget.SingleVideo)
        target as YouTubeUrlTarget.SingleVideo
        assertEquals("dQw4w9WgXcQ", target.videoId)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", target.canonicalUrl)
    }

    @Test
    fun parse_youtuBeAndMobileAndShortsAndLiveAndEmbed_returnSingleVideo() {
        val cases = listOf(
            "https://youtu.be/abc123_XYZ-?t=42" to "abc123_XYZ-",
            "https://m.youtube.com/watch?v=mob12345" to "mob12345",
            "https://www.youtube.com/shorts/short999" to "short999",
            "https://www.youtube.com/live/live777" to "live777",
            "https://www.youtube-nocookie.com/embed/emb555" to "emb555",
            "youtube.com/watch?v=noscheme1" to "noscheme1"
        )
        for ((url, expectedId) in cases) {
            val target = YouTubeUrlParser.parse(url)
            assertTrue("Expected SingleVideo for $url", target is YouTubeUrlTarget.SingleVideo)
            assertEquals(expectedId, (target as YouTubeUrlTarget.SingleVideo).videoId)
        }
    }

    @Test
    fun parse_playlistUrl_returnsPlaylistTarget() {
        val target = YouTubeUrlParser.parse("https://www.youtube.com/playlist?list=PL1234567890ABCDEF")
        assertTrue(target is YouTubeUrlTarget.Playlist)
        target as YouTubeUrlTarget.Playlist
        assertEquals("PL1234567890ABCDEF", target.playlistId)
        assertEquals("https://www.youtube.com/playlist?list=PL1234567890ABCDEF", target.canonicalUrl)
        assertTrue(YouTubeUrlParser.isPlaylistUrl("https://www.youtube.com/playlist?list=PL1234567890ABCDEF"))
    }

    @Test
    fun parse_watchUrlWithList_distinguishesNormalPlaylistAndAutoMix() {
        val normalMix = YouTubeUrlParser.parse("https://www.youtube.com/watch?v=vid123&list=PLxyz987&index=2")
        assertTrue(normalMix is YouTubeUrlTarget.VideoInPlaylist)
        normalMix as YouTubeUrlTarget.VideoInPlaylist
        assertEquals("vid123", normalMix.videoId)
        assertEquals("PLxyz987", normalMix.playlistId)
        assertFalse(normalMix.isAutoMix)
        assertTrue(YouTubeUrlParser.isPlaylistUrl("https://www.youtube.com/watch?v=vid123&list=PLxyz987"))

        val autoMix = YouTubeUrlParser.parse("https://www.youtube.com/watch?v=vid123&list=RDMMvid123")
        assertTrue(autoMix is YouTubeUrlTarget.VideoInPlaylist)
        assertTrue((autoMix as YouTubeUrlTarget.VideoInPlaylist).isAutoMix)
        assertFalse(YouTubeUrlParser.isPlaylistUrl("https://www.youtube.com/watch?v=vid123&list=RDMMvid123"))
    }

    @Test
    fun parse_invalidOrNonYouTubeUrls_returnsInvalid() {
        val invalidUrls = listOf(
            "",
            "   ",
            "ftp://www.youtube.com/watch?v=abc",
            "https://vimeo.com/123456",
            "https://www.youtube.com/watch",
            "https://www.youtube.com/playlist",
            "https://youtu.be/"
        )
        for (url in invalidUrls) {
            val target = YouTubeUrlParser.parse(url)
            assertTrue("Expected Invalid for $url", target is YouTubeUrlTarget.Invalid)
            assertFalse(YouTubeUrlParser.isYouTubeUrl(url))
        }
    }
}
