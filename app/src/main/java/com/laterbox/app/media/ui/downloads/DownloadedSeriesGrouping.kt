package com.laterbox.app.media.ui.downloads

import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.movies.data.download.MoviesDownloadBridge

data class ParsedEpisodeSourceRef(
    val providerId: String,
    val titleId: String,
    val seriesKey: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val qualityLabel: String?
)

data class DownloadedEpisodeEntry(
    val asset: MediaAsset,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val qualityLabel: String?
)

data class DownloadedSeasonGroup(
    val seasonNumber: Int,
    val episodes: List<DownloadedEpisodeEntry>
)

sealed interface DownloadLibraryEntry {
    val entryKey: String
    val sortTimestamp: Long

    data class SeriesGroup(
        val seriesKey: String,
        val providerId: String,
        val titleId: String,
        val seriesTitle: String,
        val posterUrl: String?,
        val seasons: List<DownloadedSeasonGroup>,
        val allAssets: List<MediaAsset>,
        val distinctEpisodeCount: Int,
        val totalFileCount: Int,
        val totalBytes: Long,
        override val sortTimestamp: Long
    ) : DownloadLibraryEntry {
        override val entryKey: String get() = "series_$seriesKey"
    }

    data class SingleAsset(
        val asset: MediaAsset
    ) : DownloadLibraryEntry {
        override val entryKey: String get() = "asset_${asset.id}"
        override val sortTimestamp: Long get() = asset.createdAt
    }
}

object DownloadedSeriesGrouper {

    private val EPISODE_SUFFIX_REGEX = Regex(
        """\s*[·\-]\s*S\d+(?:\s*[·\-]?\s*E\d+)?(?:\s*\([^)]*\))?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val QUALITY_SUFFIX_REGEX = Regex("""\s*\([^)]*\)\s*$""")

    fun parseEpisodeSourceRef(asset: MediaAsset): ParsedEpisodeSourceRef? {
        if (!asset.contentCategory.equals(MoviesDownloadBridge.CATEGORY_EPISODE, ignoreCase = true)) {
            return null
        }
        val rawRef = asset.sourceRef?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        val parts = rawRef.split("|")
        if (parts.size < 4) return null

        val providerId = parts[0].trim()
        val titleId = parts[1].trim()
        val seasonNumber = parts[2].trim().toIntOrNull() ?: return null
        val episodeNumber = parts[3].trim().toIntOrNull() ?: return null
        if (providerId.isBlank() || titleId.isBlank() || seasonNumber <= 0 || episodeNumber <= 0) {
            return null
        }
        val refQuality = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedQuality = refQuality
            ?: asset.versionTag?.trim()?.takeIf { it.isNotEmpty() }
            ?: asset.qualityLabel?.trim()?.takeIf { it.isNotEmpty() }

        return ParsedEpisodeSourceRef(
            providerId = providerId,
            titleId = titleId,
            seriesKey = "$providerId:$titleId",
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            qualityLabel = resolvedQuality
        )
    }

    fun extractSeriesDisplayTitle(rawTitle: String?): String {
        val cleaned = rawTitle?.trim().orEmpty()
        if (cleaned.isBlank()) return "TV Series"
        val stripped = cleaned
            .replace(EPISODE_SUFFIX_REGEX, "")
            .replace(QUALITY_SUFFIX_REGEX, "")
            .trim()
        return stripped.ifBlank { cleaned }
    }

    fun group(assets: List<MediaAsset>): List<DownloadLibraryEntry> {
        if (assets.isEmpty()) return emptyList()

        data class ParsedPair(
            val asset: MediaAsset,
            val ref: ParsedEpisodeSourceRef
        )

        val groupedEpisodes = linkedMapOf<String, MutableList<ParsedPair>>()
        val singleEntries = mutableListOf<DownloadLibraryEntry.SingleAsset>()

        for (asset in assets) {
            val parsed = parseEpisodeSourceRef(asset)
            if (parsed == null) {
                singleEntries.add(DownloadLibraryEntry.SingleAsset(asset))
            } else {
                groupedEpisodes.getOrPut(parsed.seriesKey) { mutableListOf() }
                    .add(ParsedPair(asset, parsed))
            }
        }

        val seriesEntries = groupedEpisodes.map { (seriesKey, pairs) ->
            val firstRef = pairs.first().ref
            val sortedAssets = pairs.sortedWith(
                compareBy<ParsedPair> { it.ref.seasonNumber }
                    .thenBy { it.ref.episodeNumber }
                    .thenBy { it.asset.createdAt }
            )
            val seasons = sortedAssets
                .groupBy { it.ref.seasonNumber }
                .toSortedMap()
                .map { (seasonNumber, seasonPairs) ->
                    DownloadedSeasonGroup(
                        seasonNumber = seasonNumber,
                        episodes = seasonPairs.map { pair ->
                            DownloadedEpisodeEntry(
                                asset = pair.asset,
                                seasonNumber = pair.ref.seasonNumber,
                                episodeNumber = pair.ref.episodeNumber,
                                qualityLabel = pair.ref.qualityLabel
                            )
                        }
                    )
                }

            val seriesTitle = sortedAssets
                .firstNotNullOfOrNull { pair ->
                    pair.asset.album?.trim()?.takeIf { it.isNotEmpty() }
                }
                ?: extractSeriesDisplayTitle(sortedAssets.first().asset.title)

            val posterUrl = sortedAssets.firstNotNullOfOrNull { pair ->
                pair.asset.artworkUrl?.takeIf { it.isNotBlank() }
                    ?: pair.asset.thumbnailPath?.takeIf { it.isNotBlank() }
            }

            val distinctEpisodes = sortedAssets
                .map { it.ref.seasonNumber to it.ref.episodeNumber }
                .toSet()
                .size

            DownloadLibraryEntry.SeriesGroup(
                seriesKey = seriesKey,
                providerId = firstRef.providerId,
                titleId = firstRef.titleId,
                seriesTitle = seriesTitle,
                posterUrl = posterUrl,
                seasons = seasons,
                allAssets = sortedAssets.map { it.asset },
                distinctEpisodeCount = distinctEpisodes,
                totalFileCount = sortedAssets.size,
                totalBytes = sortedAssets.sumOf { it.asset.fileSize.coerceAtLeast(0L) },
                sortTimestamp = sortedAssets.maxOf { it.asset.createdAt }
            )
        }

        return (seriesEntries + singleEntries).sortedByDescending { it.sortTimestamp }
    }
}
