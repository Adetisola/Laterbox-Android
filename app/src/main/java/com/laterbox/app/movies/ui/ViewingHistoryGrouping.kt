package com.laterbox.app.movies.ui

import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.WatchHistoryItem

/**
 * Optional external metadata for a series (when reliably known from catalogue/detail records).
 * Any null or inconsistent counts are ignored so completion percentages are never fabricated.
 */
data class ViewingHistorySeriesMetadata(
    val knownTotalSeasons: Int? = null,
    val knownTotalEpisodes: Int? = null,
    val seasonEpisodeCounts: Map<Int, Int> = emptyMap(),
    val seasonTitles: Map<Int, String> = emptyMap(),
    val seasonArtworks: Map<Int, String?> = emptyMap()
)

data class ViewingHistorySeasonGroup(
    val seasonNumber: Int?,
    val seasonKey: String,
    val label: String,
    val artworkUrl: String?,
    val watchedEpisodeCount: Int,
    val knownTotalEpisodes: Int?,
    val completionPercent: Int?,
    val latestEpisode: WatchHistoryItem,
    val latestWatchedAt: Long,
    val episodes: List<WatchHistoryItem>
)

data class ViewingHistorySeriesGroup(
    val seriesKey: String,
    val title: String,
    val providerId: String,
    val titleId: String,
    val kind: MediaKind,
    val artworkUrl: String?,
    val watchedEpisodeCount: Int,
    val seasonCountInHistory: Int,
    val knownTotalEpisodes: Int?,
    val completionPercent: Int?,
    val latestEpisode: WatchHistoryItem,
    val latestWatchedAt: Long,
    val shouldGroupBySeason: Boolean,
    val seasons: List<ViewingHistorySeasonGroup>,
    val allEpisodesSorted: List<WatchHistoryItem>
)

sealed interface ViewingHistoryTopLevelEntry {
    val stableKey: String
    val sortTimestamp: Long

    data class Individual(
        val item: WatchHistoryItem,
        override val stableKey: String = ViewingHistoryHierarchyBuilder.individualStableKey(item),
        override val sortTimestamp: Long = item.lastWatchedAt
    ) : ViewingHistoryTopLevelEntry

    data class SeriesGroup(
        val series: ViewingHistorySeriesGroup,
        override val stableKey: String = "series_${series.seriesKey}",
        override val sortTimestamp: Long = series.latestWatchedAt
    ) : ViewingHistoryTopLevelEntry
}

object ViewingHistoryHierarchyBuilder {

    fun individualStableKey(item: WatchHistoryItem): String =
        "item_${item.historyKey.ifBlank { "${item.compositeId}_${item.lastWatchedAt}_${item.isTrailer}" }}"

    /**
     * Resolves a stable, non-title series identifier for episodic watch history entries.
     * Returns null when identity or title metadata is malformed/blank so broken items fall back
     * safely to flat individual rows without merging unrelated titles.
     */
    fun resolveSeriesKey(item: WatchHistoryItem): String? {
        if (item.isTrailer || !item.kind.isEpisodic) return null
        if (item.title.isBlank()) return null

        val canonical = item.canonicalId?.trim()?.takeIf { it.isNotEmpty() }
        if (canonical != null) return canonical

        val composite = item.compositeId.trim()
        if (composite.isNotEmpty() && !composite.startsWith(":") && !composite.endsWith(":")) {
            return composite
        }

        val provider = item.providerId.trim()
        val titleId = item.titleId.trim()
        return if (provider.isNotEmpty() && titleId.isNotEmpty()) {
            "$provider:$titleId"
        } else {
            null
        }
    }

    internal fun computeReliablePercentage(watchedCount: Int, totalCount: Int?): Int? {
        if (totalCount == null || totalCount <= 0 || watchedCount < 0 || watchedCount > totalCount) {
            return null
        }
        return ((watchedCount.toFloat() / totalCount.toFloat()) * 100f).toInt().coerceIn(0, 100)
    }

    fun formatEpisodeShortLabel(item: WatchHistoryItem): String {
        val label = item.episodeLabel?.trim()?.takeIf { it.isNotEmpty() }
        if (label != null) return label
        return when {
            item.seasonNumber != null && item.episodeNumber != null ->
                "S${item.seasonNumber} E${item.episodeNumber}"
            item.episodeNumber != null -> "Episode ${item.episodeNumber}"
            item.seasonNumber != null -> "Season ${item.seasonNumber}"
            else -> "Episode"
        }
    }

    fun formatSeasonLabel(seasonNumber: Int?, customTitle: String? = null): String {
        val cleanCustom = customTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanCustom != null) return cleanCustom
        return when {
            seasonNumber == null -> "Episodes"
            seasonNumber == 0 -> "Specials"
            else -> "Season $seasonNumber"
        }
    }

    fun buildTopLevelEntries(
        items: List<WatchHistoryItem>,
        metadataBySeriesKey: Map<String, ViewingHistorySeriesMetadata> = emptyMap()
    ): List<ViewingHistoryTopLevelEntry> {
        if (items.isEmpty()) return emptyList()

        val individualEntries = mutableListOf<ViewingHistoryTopLevelEntry.Individual>()
        val episodicBuckets = LinkedHashMap<String, MutableList<WatchHistoryItem>>()

        for (item in items) {
            val seriesKey = resolveSeriesKey(item)
            if (seriesKey == null) {
                individualEntries += ViewingHistoryTopLevelEntry.Individual(item)
            } else {
                episodicBuckets.getOrPut(seriesKey) { mutableListOf() }.add(item)
            }
        }

        val combined = ArrayList<ViewingHistoryTopLevelEntry>(individualEntries.size + episodicBuckets.size)
        combined.addAll(individualEntries)

        for ((seriesKey, seriesItems) in episodicBuckets) {
            if (seriesItems.size <= 1) {
                val single = seriesItems.singleOrNull()
                if (single != null) {
                    combined += ViewingHistoryTopLevelEntry.Individual(single)
                }
            } else {
                val metadata = metadataBySeriesKey[seriesKey]
                val group = buildSeriesGroup(
                    seriesKey = seriesKey,
                    rawItems = seriesItems,
                    metadata = metadata
                )
                combined += ViewingHistoryTopLevelEntry.SeriesGroup(group)
            }
        }

        return combined.sortedWith(
            compareByDescending<ViewingHistoryTopLevelEntry> { it.sortTimestamp }
                .thenBy { it.stableKey }
        )
    }

    fun buildSeriesGroup(
        seriesKey: String,
        rawItems: List<WatchHistoryItem>,
        metadata: ViewingHistorySeriesMetadata? = null
    ): ViewingHistorySeriesGroup {
        val sortedEpisodes = sortEpisodesByRecency(rawItems)
        val latest = sortedEpisodes.first()
        val seriesTitle = sortedEpisodes.firstOrNull { it.title.isNotBlank() }?.title ?: latest.title
        val artwork = sortedEpisodes.firstNotNullOfOrNull {
            (it.backdropUrl ?: it.posterUrl)?.takeIf { url -> url.isNotBlank() }
        }

        // Partition strictly by seasonNumber present in history; never invent unwatched seasons
        val bySeasonNumber = LinkedHashMap<Int?, MutableList<WatchHistoryItem>>()
        for (ep in sortedEpisodes) {
            bySeasonNumber.getOrPut(ep.seasonNumber) { mutableListOf() }.add(ep)
        }

        val seasons = bySeasonNumber.entries.map { (seasonNumber, seasonEpisodesRaw) ->
            val seasonEpisodes = sortEpisodesByRecency(seasonEpisodesRaw)
            val seasonLatest = seasonEpisodes.first()
            val seasonKey = seasonNumber?.let { "s_$it" } ?: "unassigned"
            val customTitle = seasonNumber?.let { metadata?.seasonTitles?.get(it) }
            val seasonArtwork = seasonNumber?.let { metadata?.seasonArtworks?.get(it)?.takeIf { url -> url.isNotBlank() } }
                ?: seasonEpisodes.firstNotNullOfOrNull {
                    (it.backdropUrl ?: it.posterUrl)?.takeIf { url -> url.isNotBlank() }
                }
                ?: artwork
            val distinctSeasonEpCount = countDistinctEpisodes(seasonEpisodes)
            val knownSeasonTotal = seasonNumber?.let { metadata?.seasonEpisodeCounts?.get(it) }
                ?.takeIf { it > 0 }
            ViewingHistorySeasonGroup(
                seasonNumber = seasonNumber,
                seasonKey = seasonKey,
                label = formatSeasonLabel(seasonNumber, customTitle),
                artworkUrl = seasonArtwork,
                watchedEpisodeCount = distinctSeasonEpCount,
                knownTotalEpisodes = knownSeasonTotal,
                completionPercent = computeReliablePercentage(distinctSeasonEpCount, knownSeasonTotal),
                latestEpisode = seasonLatest,
                latestWatchedAt = seasonLatest.lastWatchedAt,
                episodes = seasonEpisodes
            )
        }.sortedWith(
            compareByDescending<ViewingHistorySeasonGroup> { it.latestWatchedAt }
                .thenByDescending { it.seasonNumber ?: Int.MIN_VALUE }
                .thenBy { it.seasonKey }
        )

        val distinctSeriesEpCount = countDistinctEpisodes(sortedEpisodes)
        val distinctSeasonCount = seasons.size
        val knownSeriesTotal = metadata?.knownTotalEpisodes?.takeIf { it > 0 }
        val knownSingleSeasonOnly = metadata?.knownTotalSeasons == 1
        val shouldGroupBySeason = distinctSeasonCount > 1 && !knownSingleSeasonOnly

        return ViewingHistorySeriesGroup(
            seriesKey = seriesKey,
            title = seriesTitle,
            providerId = latest.providerId,
            titleId = latest.titleId,
            kind = latest.kind,
            artworkUrl = artwork,
            watchedEpisodeCount = distinctSeriesEpCount,
            seasonCountInHistory = distinctSeasonCount,
            knownTotalEpisodes = knownSeriesTotal,
            completionPercent = computeReliablePercentage(distinctSeriesEpCount, knownSeriesTotal),
            latestEpisode = latest,
            latestWatchedAt = latest.lastWatchedAt,
            shouldGroupBySeason = shouldGroupBySeason,
            seasons = seasons,
            allEpisodesSorted = sortedEpisodes
        )
    }

    private fun sortEpisodesByRecency(items: List<WatchHistoryItem>): List<WatchHistoryItem> =
        items.sortedWith(
            compareByDescending<WatchHistoryItem> { it.lastWatchedAt }
                .thenByDescending { it.seasonNumber ?: Int.MIN_VALUE }
                .thenByDescending { it.episodeNumber ?: Int.MIN_VALUE }
                .thenBy { it.historyKey }
        )

    private fun countDistinctEpisodes(items: List<WatchHistoryItem>): Int {
        if (items.size <= 1) return items.size
        val seen = HashSet<String>(items.size)
        items.forEachIndexed { index, item ->
            val token = when {
                item.seasonNumber != null || item.episodeNumber != null ->
                    "${item.seasonNumber ?: -1}:${item.episodeNumber ?: -1}"
                item.historyKey.isNotBlank() -> item.historyKey
                else -> "idx_$index"
            }
            seen.add(token)
        }
        return seen.size
    }
}
