package com.laterbox.app.media.ui.downloads

import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.media.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedSeriesGroupingTest {

    private fun episodeAsset(
        id: String,
        title: String,
        sourceRef: String?,
        fileSize: Long = 100_000_000L,
        createdAt: Long = 1_000L,
        category: String? = "EPISODE",
        versionTag: String? = "720p",
        posterUrl: String? = "https://img.example/poster.jpg"
    ) = MediaAsset(
        id = id,
        savedLinkId = null,
        sourcePlatform = "MovieBox",
        originalSourceUrl = "https://cdn.example/$id.mp4",
        title = title,
        mimeType = "video/mp4",
        mediaType = MediaType.VIDEO,
        localPath = "video/$id.mp4",
        exportedUri = null,
        fileSize = fileSize,
        durationMs = 2_400_000L,
        width = 1280,
        height = 720,
        qualityLabel = versionTag,
        checksum = "chk_$id",
        createdAt = createdAt,
        artworkUrl = posterUrl,
        versionTag = versionTag,
        contentCategory = category,
        sourceRef = sourceRef
    )

    @Test
    fun group_separatesSameTitledSeriesWithDifferentProviderOrTitleIds_andGroupsBySeasonAndQuality() {
        val assets = listOf(
            episodeAsset("a1", "The Office · S1 · E1 (720p)", "moviebox|office_us|1|1|720p", fileSize = 200L, createdAt = 100L),
            episodeAsset("a2", "The Office · S1 · E1 (1080p)", "moviebox|office_us|1|1|1080p", fileSize = 400L, createdAt = 200L, versionTag = "1080p"),
            episodeAsset("a3", "The Office · S2 · E3 (720p)", "moviebox|office_us|2|3|720p", fileSize = 250L, createdAt = 300L),
            // Same series title, different provider/title ID -> separate group
            episodeAsset("b1", "The Office · S1 · E1 (720p)", "tvmaze|office_uk|1|1|720p", fileSize = 150L, createdAt = 250L),
            // Movie -> never grouped as a TV series
            episodeAsset("m1", "Arrival (1080p)", "ia|arrival|0|0|1080p", fileSize = 900L, createdAt = 400L, category = "MOVIE"),
            // Episode with incomplete metadata -> falls back to single asset
            episodeAsset("x1", "Mystery Clip · S1E1", sourceRef = null, fileSize = 50L, createdAt = 50L)
        )

        val entries = DownloadedSeriesGrouper.group(assets)
        val seriesGroups = entries.filterIsInstance<DownloadLibraryEntry.SeriesGroup>()
        val singles = entries.filterIsInstance<DownloadLibraryEntry.SingleAsset>()

        assertEquals(2, seriesGroups.size)
        assertEquals(2, singles.size)

        val officeUs = seriesGroups.first { it.seriesKey == "moviebox:office_us" }
        assertEquals("The Office", officeUs.seriesTitle)
        assertEquals(2, officeUs.distinctEpisodeCount) // S1E1 and S2E3
        assertEquals(3, officeUs.totalFileCount) // includes duplicate quality for S1E1
        assertEquals(850L, officeUs.totalBytes)
        assertEquals(2, officeUs.seasons.size)
        assertEquals(1, officeUs.seasons[0].seasonNumber)
        assertEquals(2, officeUs.seasons[0].episodes.size)
        assertEquals("720p", officeUs.seasons[0].episodes[0].qualityLabel)
        assertEquals("1080p", officeUs.seasons[0].episodes[1].qualityLabel)
        assertEquals(2, officeUs.seasons[1].seasonNumber)
        assertEquals(3, officeUs.seasons[1].episodes[0].episodeNumber)

        val officeUk = seriesGroups.first { it.seriesKey == "tvmaze:office_uk" }
        assertEquals(1, officeUk.totalFileCount)
        assertTrue(singles.any { it.asset.id == "m1" })
        assertTrue(singles.any { it.asset.id == "x1" })
    }
}
