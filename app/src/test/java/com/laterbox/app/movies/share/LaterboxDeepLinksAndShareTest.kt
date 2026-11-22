package com.laterbox.app.movies.share

import com.laterbox.app.media.domain.model.AssetFileStatus
import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.media.share.LaterboxShareHelper
import com.laterbox.app.media.share.LaterboxShareTarget
import com.laterbox.app.media.ui.downloads.DownloadLibraryEntry
import com.laterbox.app.movies.data.download.MoviesDownloadBridge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.ui.navigation.DeepLinkMediaType
import com.laterbox.app.ui.navigation.LaterboxDeepLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaterboxDeepLinksAndShareTest {

    @Test
    fun `buildTitleUrl generates provider-neutral links for primary catalogue and retains external provider segment`() {
        val movieUrl = LaterboxDeepLinks.buildTitleUrl("moviebox", "12345", MediaKind.MOVIE)
        val catalogueMovieUrl = LaterboxDeepLinks.buildTitleUrl("catalogue", "12345", MediaKind.MOVIE)
        val seriesUrl = LaterboxDeepLinks.buildTitleUrl("tmdb", "tv-987", MediaKind.TV_SERIES)
        val shortDramaUrl = LaterboxDeepLinks.buildTitleUrl("moviebox", "sd_42", MediaKind.SHORT_DRAMA)
        val iaMovieUrl = LaterboxDeepLinks.buildTitleUrl("ia", "charade-1963", MediaKind.MOVIE)

        assertEquals("https://laterbox-website.vercel.app/movie/12345", movieUrl)
        assertEquals("https://laterbox-website.vercel.app/movie/12345", catalogueMovieUrl)
        assertEquals("https://laterbox-website.vercel.app/series/tmdb/tv-987", seriesUrl)
        assertEquals("https://laterbox-website.vercel.app/series/sd_42", shortDramaUrl)
        assertEquals("https://laterbox-website.vercel.app/movie/ia/charade-1963", iaMovieUrl)

        assertFalse(movieUrl!!.contains("moviebox", ignoreCase = true))
        assertFalse(shortDramaUrl!!.contains("moviebox", ignoreCase = true))
    }

    @Test
    fun `parseUrl accepts neutral, legacy moviebox, catalogue alias, and encoded provider links`() {
        val neutralMovie = LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app/movie/tt123456")
        assertNotNull(neutralMovie)
        assertEquals(DeepLinkMediaType.MOVIE, neutralMovie?.mediaType)
        assertEquals("moviebox", neutralMovie?.providerId)
        assertEquals("tt123456", neutralMovie?.titleId)
        assertEquals("movies/detail/moviebox/tt123456", LaterboxDeepLinks.toDestinationRoute(neutralMovie!!))

        val neutralSeries = LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app/series/sd_42/")
        assertNotNull(neutralSeries)
        assertEquals(DeepLinkMediaType.SERIES, neutralSeries?.mediaType)
        assertEquals("moviebox", neutralSeries?.providerId)
        assertEquals("sd_42", neutralSeries?.titleId)
        assertEquals("movies/detail/moviebox/sd_42", LaterboxDeepLinks.toDestinationRoute(neutralSeries!!))

        val legacyMovie = LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app/movie/moviebox/tt123456")
        assertNotNull(legacyMovie)
        assertEquals("moviebox", legacyMovie?.providerId)
        assertEquals("tt123456", legacyMovie?.titleId)
        assertEquals("movies/detail/moviebox/tt123456", LaterboxDeepLinks.toDestinationRoute(legacyMovie!!))

        val catalogueAlias = LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app/series/catalogue/show_99")
        assertNotNull(catalogueAlias)
        assertEquals(DeepLinkMediaType.SERIES, catalogueAlias?.mediaType)
        assertEquals("moviebox", catalogueAlias?.providerId)
        assertEquals("show_99", catalogueAlias?.titleId)

        val encodedNeutral = LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app/movie/tt%3A123456")
        assertNotNull(encodedNeutral)
        assertEquals("tt:123456", encodedNeutral?.titleId)
        assertEquals("movies/detail/moviebox/tt%3A123456", LaterboxDeepLinks.toDestinationRoute(encodedNeutral!!))

        val parsedSeries = LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app/series/TMDB/show_88/")
        assertNotNull(parsedSeries)
        assertEquals(DeepLinkMediaType.SERIES, parsedSeries?.mediaType)
        assertEquals("tmdb", parsedSeries?.providerId)
        assertEquals("show_88", parsedSeries?.titleId)
        assertEquals("movies/detail/tmdb/show_88", LaterboxDeepLinks.toDestinationRoute(parsedSeries!!))
    }

    @Test
    fun `parseUrl rejects untrusted hosts, invalid schemes, traversal attempts, bare provider segments, and unallowlisted providers`() {
        assertNull(LaterboxDeepLinks.parseUrl("http://laterbox-website.vercel.app/movie/123"))
        assertNull(LaterboxDeepLinks.parseUrl("https://evil.example.com/movie/123"))
        assertNull(LaterboxDeepLinks.parseUrl("https://user@laterbox-website.vercel.app/movie/123"))
        assertNull(LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app:8443/movie/123"))
        assertNull(LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app/movie/unknown_provider/123"))
        assertNull(LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app/movie/moviebox"))
        assertNull(LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app/series/catalogue"))
        assertNull(LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app/movie/..%2Fsecret"))
        assertNull(LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app/movie/moviebox/..%2Fsecret"))
        assertNull(LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app/movie/0"))
        assertNull(LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app/movie/null"))
        assertNull(LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app/episode/moviebox/123"))
        assertNull(LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app/unknown/123"))
        assertNull(LaterboxDeepLinks.parseUrl("https://laterbox-website.vercel.app/movie/moviebox/123/extra"))
    }

    @Test
    fun `isBlockedEpisodeAsset identifies individual downloaded episodes and blocks them`() {
        val episodeByCategory = sampleAsset(
            id = "ep1",
            contentCategory = MoviesDownloadBridge.CATEGORY_EPISODE,
            sourceRef = "moviebox|999|1|2|720p"
        )
        val episodeByRefOnly = sampleAsset(
            id = "ep2",
            contentCategory = null,
            sourceRef = "moviebox|999|1|3|1080p"
        )
        val movieAsset = sampleAsset(
            id = "mov1",
            contentCategory = MoviesDownloadBridge.CATEGORY_MOVIE,
            sourceRef = "moviebox|555|0|0|1080p"
        )
        val genericVideo = sampleAsset(
            id = "vid1",
            contentCategory = null,
            sourceRef = null
        )

        assertTrue(LaterboxShareHelper.isBlockedEpisodeAsset(episodeByCategory))
        assertTrue(LaterboxShareHelper.isBlockedEpisodeAsset(episodeByRefOnly))
        assertFalse(LaterboxShareHelper.isBlockedEpisodeAsset(movieAsset))
        assertFalse(LaterboxShareHelper.isBlockedEpisodeAsset(genericVideo))
    }

    @Test
    fun `share url helpers resolve deep links for MovieTitleSummary and SeriesGroup`() {
        val summary = MovieTitleSummary(
            id = "m42",
            providerId = "moviebox",
            title = "Example Film",
            kind = MediaKind.MOVIE,
            posterUrl = null,
            releaseYear = 2025
        )
        assertEquals(
            "https://laterbox-website.vercel.app/movie/m42",
            LaterboxShareHelper.resolveTitleShareUrl(summary)
        )

        val group = DownloadLibraryEntry.SeriesGroup(
            seriesKey = "tmdb:777",
            providerId = "tmdb",
            titleId = "777",
            seriesTitle = "Example Series",
            posterUrl = null,
            seasons = emptyList(),
            allAssets = emptyList(),
            distinctEpisodeCount = 2,
            totalFileCount = 2,
            totalBytes = 1024L,
            sortTimestamp = 100L
        )
        assertEquals(
            "https://laterbox-website.vercel.app/series/tmdb/777",
            LaterboxShareHelper.resolveSeriesGroupShareUrl(group)
        )
    }

    @Test
    fun `isPathWithinRoot rejects directory traversal outside allowed storage root`() {
        assertTrue(
            LaterboxShareHelper.isPathWithinRoot(
                "/data/user/0/com.laterbox.app/files/media",
                "/data/user/0/com.laterbox.app/files/media/video.mp4"
            )
        )
        assertFalse(
            LaterboxShareHelper.isPathWithinRoot(
                "/data/user/0/com.laterbox.app/files/media",
                "/data/user/0/com.laterbox.app/files/media_evil/video.mp4"
            )
        )
        assertFalse(
            LaterboxShareHelper.isPathWithinRoot(
                "/data/user/0/com.laterbox.app/files/media",
                "/data/user/0/com.laterbox.app/shared_prefs/secrets.xml"
            )
        )
    }

    private fun sampleAsset(
        id: String,
        contentCategory: String?,
        sourceRef: String?
    ): MediaAsset = MediaAsset(
        id = id,
        savedLinkId = null,
        sourcePlatform = "moviebox",
        originalSourceUrl = "https://example.com/$id.mp4",
        title = "Sample $id",
        mimeType = "video/mp4",
        mediaType = MediaType.VIDEO,
        localPath = "$id.mp4",
        exportedUri = null,
        fileStatus = AssetFileStatus.AVAILABLE,
        fileSize = 1024L,
        durationMs = 60_000L,
        width = 1920,
        height = 1080,
        qualityLabel = "1080p",
        checksum = "abc",
        createdAt = 1L,
        contentCategory = contentCategory,
        sourceRef = sourceRef
    )
}
