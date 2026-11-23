package com.laterbox.app.movies.ui

import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.SeasonSummary

data class OnePieceEpisodeRangeGroup(
    val key: String,
    val label: String,
    val startEpisode: Int?,
    val endEpisode: Int?,
    val episodes: List<EpisodeItem>
) {
    fun containsEpisode(episodeNumber: Int): Boolean =
        startEpisode != null && endEpisode != null && episodeNumber in startEpisode..endEpisode
}

data class OnePieceEpisodeRangeUiState(
    val ranges: List<OnePieceEpisodeRangeGroup>,
    val selectedRangeKey: String
) {
    val selectedRange: OnePieceEpisodeRangeGroup?
        get() = ranges.firstOrNull { it.key == selectedRangeKey } ?: ranges.firstOrNull()
}

object OnePieceEpisodeRangeBuilder {
    const val RANGE_SIZE = 100
    const val SPECIALS_RANGE_KEY = "specials"

    private const val ONE_PIECE_ANIME_IMDB_ID = "tt0388629"
    private const val ONE_PIECE_ANIME_TVMAZE_ID = "1505"
    private const val ONE_PIECE_ANIME_TMDB_ID = "37854"

    private const val ONE_PIECE_LIVE_ACTION_IMDB_ID = "tt11737520"
    private const val ONE_PIECE_LIVE_ACTION_TVMAZE_ID = "46123"
    private const val ONE_PIECE_LIVE_ACTION_TMDB_ID = "111110"

    private val PRIMARY_ANIME_CANONICAL_TOKENS = setOf(
        "imdb:$ONE_PIECE_ANIME_IMDB_ID",
        "tvmaze:$ONE_PIECE_ANIME_TVMAZE_ID",
        "tmdb:$ONE_PIECE_ANIME_TMDB_ID",
        "tmdb:tv_$ONE_PIECE_ANIME_TMDB_ID",
        "one_piece_1999_anime"
    )

    private val LIVE_ACTION_CANONICAL_TOKENS = setOf(
        "imdb:$ONE_PIECE_LIVE_ACTION_IMDB_ID",
        "tvmaze:$ONE_PIECE_LIVE_ACTION_TVMAZE_ID",
        "tmdb:$ONE_PIECE_LIVE_ACTION_TMDB_ID",
        "tmdb:tv_$ONE_PIECE_LIVE_ACTION_TMDB_ID"
    )

    /**
     * Strictly identifies only the 1999 One Piece anime series, excluding the 2023 live-action
     * adaptation, films, specials, and unrelated high-episode-count TV shows.
     */
    fun isOnePieceAnime(
        summary: MovieTitleSummary?,
        seasons: List<SeasonSummary> = emptyList(),
        episodes: List<EpisodeItem> = emptyList()
    ): Boolean {
        if (summary == null || summary.kind != MediaKind.TV_SERIES) return false

        val normalizedImdb = summary.imdbId?.trim()?.lowercase()
        val normalizedTvMaze = summary.tvmazeId?.trim()
        val normalizedId = summary.id.trim().lowercase()
        val normalizedProvider = summary.providerId.trim().lowercase()
        val normalizedCanonical = summary.canonicalId?.trim()?.lowercase()
        val lowerTitle = summary.title.lowercase()

        // Explicit exclusion for the 2023 live-action series or derivative films
        if (normalizedImdb == ONE_PIECE_LIVE_ACTION_IMDB_ID ||
            normalizedTvMaze == ONE_PIECE_LIVE_ACTION_TVMAZE_ID ||
            (normalizedProvider == "tmdb" && (normalizedId == ONE_PIECE_LIVE_ACTION_TMDB_ID || normalizedId == "tv_$ONE_PIECE_LIVE_ACTION_TMDB_ID")) ||
            (normalizedCanonical != null && LIVE_ACTION_CANONICAL_TOKENS.any { normalizedCanonical.contains(it) }) ||
            lowerTitle.contains("live action") ||
            lowerTitle.contains("live-action")
        ) {
            return false
        }

        // Primary check: stable external / canonical identifiers
        if (normalizedImdb == ONE_PIECE_ANIME_IMDB_ID ||
            normalizedTvMaze == ONE_PIECE_ANIME_TVMAZE_ID ||
            (normalizedProvider == "tmdb" && (normalizedId == ONE_PIECE_ANIME_TMDB_ID || normalizedId == "tv_$ONE_PIECE_ANIME_TMDB_ID")) ||
            (normalizedCanonical != null && PRIMARY_ANIME_CANONICAL_TOKENS.any { normalizedCanonical.contains(it) })
        ) {
            return true
        }

        // Strictly constrained fallback when provider does not supply external IDs
        val canonicalTitle = normalizeCanonicalTitle(summary.title)
        if (canonicalTitle != "one piece") return false

        val maxSeasonEpisodeCount = seasons.maxOfOrNull { season ->
            maxOf(season.episodeCount, season.availableEpisodes.size)
        } ?: 0
        val effectiveEpisodeTotal = maxOf(maxSeasonEpisodeCount, episodes.size)

        // Exclude live-action / recent reboot entries with low episode count
        if (summary.releaseYear == 2023 && effectiveEpisodeTotal < RANGE_SIZE) {
            return false
        }

        val hasAnimationGenre = summary.genres.any { genre ->
            genre.equals("Animation", ignoreCase = true) || genre.equals("Anime", ignoreCase = true)
        }
        val isJapaneseOrigin = summary.country?.contains("Japan", ignoreCase = true) == true ||
            summary.language?.equals("ja", ignoreCase = true) == true ||
            summary.language?.contains("Japanese", ignoreCase = true) == true

        return when {
            summary.releaseYear == 1999 -> true
            hasAnimationGenre && (isJapaneseOrigin || effectiveEpisodeTotal >= RANGE_SIZE) -> true
            seasons.size == 1 && effectiveEpisodeTotal >= RANGE_SIZE -> true
            episodes.size >= RANGE_SIZE -> true
            else -> false
        }
    }

    /**
     * Dynamically groups available episodes into contiguous 100-episode ranges
     * ("1–100", "101–200", ..., "1101–1200"), placing unnumbered or special entries
     * (episodeNumber <= 0) into a trailing "Specials" group without emitting empty ranges.
     */
    fun buildRanges(episodes: List<EpisodeItem>): List<OnePieceEpisodeRangeGroup> {
        if (episodes.isEmpty()) return emptyList()

        val deduplicated = deduplicateEpisodes(episodes)
        val numberedBuckets = LinkedHashMap<Int, MutableList<EpisodeItem>>()
        val specialEpisodes = mutableListOf<EpisodeItem>()

        for (ep in deduplicated) {
            if (ep.episodeNumber <= 0) {
                specialEpisodes += ep
            } else {
                val bucketIndex = (ep.episodeNumber - 1) / RANGE_SIZE
                numberedBuckets.getOrPut(bucketIndex) { mutableListOf() }.add(ep)
            }
        }

        val result = ArrayList<OnePieceEpisodeRangeGroup>(numberedBuckets.size + 1)
        val sortedBuckets = numberedBuckets.keys.sorted()
        for (bucketIndex in sortedBuckets) {
            val bucketEpisodes = numberedBuckets[bucketIndex] ?: continue
            if (bucketEpisodes.isEmpty()) continue
            val startEp = bucketIndex * RANGE_SIZE + 1
            val endEp = startEp + RANGE_SIZE - 1
            val sorted = bucketEpisodes.sortedWith(
                compareBy<EpisodeItem> { it.episodeNumber }
                    .thenBy { it.seasonNumber }
                    .thenBy { it.id }
            )
            result += OnePieceEpisodeRangeGroup(
                key = "range_${startEp}_${endEp}",
                label = "${startEp}–${endEp}",
                startEpisode = startEp,
                endEpisode = endEp,
                episodes = sorted
            )
        }

        if (specialEpisodes.isNotEmpty()) {
            val sortedSpecials = specialEpisodes.sortedWith(
                compareBy<EpisodeItem> { it.seasonNumber }
                    .thenBy { it.episodeNumber }
                    .thenBy { it.id }
            )
            result += OnePieceEpisodeRangeGroup(
                key = SPECIALS_RANGE_KEY,
                label = "Specials",
                startEpisode = null,
                endEpisode = null,
                episodes = sortedSpecials
            )
        }

        return result
    }

    fun buildUiState(
        details: MediaDetails?,
        episodes: List<EpisodeItem>,
        preferredSelectedKey: String? = null,
        targetEpisodeHint: Int? = null
    ): OnePieceEpisodeRangeUiState? {
        if (details == null || episodes.isEmpty()) return null
        if (!isOnePieceAnime(details.summary, details.seasons, episodes)) return null

        val ranges = buildRanges(episodes)
        if (ranges.isEmpty()) return null

        val selectedKey = when {
            preferredSelectedKey != null && ranges.any { it.key == preferredSelectedKey } -> preferredSelectedKey
            targetEpisodeHint != null -> ranges.firstOrNull { it.containsEpisode(targetEpisodeHint) }?.key ?: ranges.first().key
            else -> ranges.first().key
        }
        return OnePieceEpisodeRangeUiState(
            ranges = ranges,
            selectedRangeKey = selectedKey
        )
    }

    private fun deduplicateEpisodes(episodes: List<EpisodeItem>): List<EpisodeItem> {
        val seenKeys = HashSet<String>(episodes.size)
        val out = ArrayList<EpisodeItem>(episodes.size)
        episodes.forEachIndexed { idx, ep ->
            val stableDedupKey = when {
                ep.id.isNotBlank() -> "id:${ep.id}:${ep.seasonNumber}:${ep.episodeNumber}"
                else -> "ep:${ep.seasonNumber}:${ep.episodeNumber}:${ep.title.trim()}:$idx"
            }
            if (seenKeys.add(stableDedupKey)) {
                out += ep
            }
        }
        return out
    }

    private fun normalizeCanonicalTitle(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
