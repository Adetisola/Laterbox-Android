package com.laterbox.app.movies.domain.engine

import com.laterbox.app.movies.data.provider.InternetArchiveMoviesProvider
import com.laterbox.app.movies.data.provider.MovieBoxProvider
import com.laterbox.app.movies.data.provider.TmdbOptionalMoviesProvider
import com.laterbox.app.movies.data.provider.TvMazeSeriesProvider
import com.laterbox.app.movies.data.resolver.MatchCandidate
import com.laterbox.app.movies.data.resolver.MediaMatcher
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.SearchSuggestion

/**
 * Deduplicates multi-provider catalogue/feed/search/recommendation lists while strictly
 * preferring MovieBox entries when strong canonical or T1/T2 identity matches exist,
 * never merging unrelated titles by title alone.
 */
object MovieBoxPriorityDeduplicator {

    fun providerPriority(providerId: String): Int = when (providerId.trim().lowercase()) {
        MovieBoxProvider.PROVIDER_ID -> 4
        TmdbOptionalMoviesProvider.PROVIDER_ID -> 2
        TvMazeSeriesProvider.PROVIDER_ID -> 1
        InternetArchiveMoviesProvider.PROVIDER_ID -> 1
        else -> 0
    }

    fun deduplicateSummariesPreferringMovieBox(
        items: List<MovieTitleSummary>,
        seasonCountsByCompositeId: Map<String, Int> = emptyMap(),
    ): List<MovieTitleSummary> {
        if (items.size <= 1) return items

        val accepted = ArrayList<MovieTitleSummary>(items.size)
        val indexByCompositeId = HashMap<String, Int>(items.size)
        val indexByCanonicalId = HashMap<String, Int>(items.size)
        val indexByImdbId = HashMap<String, Int>(items.size)
        val indexByTvMazeId = HashMap<String, Int>(items.size)
        val indicesByKindTitle = HashMap<Pair<String, String>, MutableList<Int>>(items.size)

        fun candidateFor(item: MovieTitleSummary): MatchCandidate = MatchCandidate.fromSummary(
            summary = item,
            seasonCount = seasonCountsByCompositeId[item.compositeId],
        )

        fun indexItem(idx: Int, item: MovieTitleSummary) {
            indexByCompositeId[item.compositeId] = idx
            item.canonicalId?.takeIf { it.isNotBlank() }?.let { indexByCanonicalId[it] = idx }
            MediaMatcher.normalizeImdbId(item.imdbId)?.let { indexByImdbId[it] = idx }
            MediaMatcher.normalizeTvMazeId(item.tvmazeId ?: if (item.providerId == TvMazeSeriesProvider.PROVIDER_ID) item.id else null)
                ?.let { indexByTvMazeId[it] = idx }
            val normTitle = MediaMatcher.normalizeTitle(item.title)
            if (normTitle.isNotBlank()) {
                val bucket = indicesByKindTitle.getOrPut(item.kind.wireValue to normTitle) { mutableListOf() }
                if (idx !in bucket) bucket.add(idx)
            }
        }

        fun mergeSupportingIds(preferred: MovieTitleSummary, secondary: MovieTitleSummary): MovieTitleSummary {
            val mergedCanonical = preferred.canonicalId?.takeIf { it.isNotBlank() } ?: secondary.canonicalId
            val mergedImdb = preferred.imdbId?.takeIf { it.isNotBlank() } ?: secondary.imdbId
            val mergedTvMaze = preferred.tvmazeId?.takeIf { it.isNotBlank() }
                ?: secondary.tvmazeId
                ?: if (secondary.providerId == TvMazeSeriesProvider.PROVIDER_ID) secondary.id else null
            return if (
                mergedCanonical != preferred.canonicalId ||
                mergedImdb != preferred.imdbId ||
                mergedTvMaze != preferred.tvmazeId
            ) {
                preferred.copy(
                    canonicalId = mergedCanonical,
                    imdbId = mergedImdb,
                    tvmazeId = mergedTvMaze,
                )
            } else {
                preferred
            }
        }

        for (item in items) {
            val normImdb = MediaMatcher.normalizeImdbId(item.imdbId)
            val normTvMaze = MediaMatcher.normalizeTvMazeId(
                item.tvmazeId ?: if (item.providerId == TvMazeSeriesProvider.PROVIDER_ID) item.id else null
            )
            val normTitle = MediaMatcher.normalizeTitle(item.title)

            var matchIndex: Int? = indexByCompositeId[item.compositeId]
                ?: item.canonicalId?.takeIf { it.isNotBlank() }?.let { indexByCanonicalId[it] }
                ?: normImdb?.let { indexByImdbId[it] }
                ?: normTvMaze?.let { indexByTvMazeId[it] }

            if (matchIndex == null && normTitle.isNotBlank()) {
                val candItem = candidateFor(item)
                val bucket = indicesByKindTitle[item.kind.wireValue to normTitle].orEmpty()
                for (candidateIdx in bucket) {
                    val existing = accepted[candidateIdx]
                    if (MediaMatcher.evaluate(candidateFor(existing), candItem).isMatch) {
                        matchIndex = candidateIdx
                        break
                    }
                }
            }

            if (matchIndex == null) {
                val nextIdx = accepted.size
                accepted.add(item)
                indexItem(nextIdx, item)
            } else {
                val existing = accepted[matchIndex]
                val incomingPriority = providerPriority(item.providerId)
                val existingPriority = providerPriority(existing.providerId)
                val winner = if (incomingPriority > existingPriority) {
                    mergeSupportingIds(item, existing)
                } else {
                    mergeSupportingIds(existing, item)
                }
                accepted[matchIndex] = winner
                indexItem(matchIndex, winner)
            }
        }

        return accepted
    }

    fun deduplicateSuggestionsPreferringMovieBox(
        suggestions: List<SearchSuggestion>,
    ): List<SearchSuggestion> {
        if (suggestions.size <= 1) return suggestions
        val accepted = ArrayList<SearchSuggestion>(suggestions.size)
        val indexByComposite = HashMap<String, Int>(suggestions.size)
        val indexByKindTitleYear = HashMap<Triple<String, String, Int>, Int>(suggestions.size)

        fun record(idx: Int, s: SearchSuggestion) {
            indexByComposite["${s.providerId}:${s.id}"] = idx
            val year = s.releaseYear
            val kind = s.kind
            val normTitle = MediaMatcher.normalizeTitle(s.title)
            if (kind != null && year != null && normTitle.isNotBlank()) {
                indexByKindTitleYear[Triple(kind.wireValue, normTitle, year)] = idx
            }
        }

        for (suggestion in suggestions) {
            val compositeKey = "${suggestion.providerId}:${suggestion.id}"
            val year = suggestion.releaseYear
            val kind = suggestion.kind
            val normTitle = MediaMatcher.normalizeTitle(suggestion.title)
            val keyIndex = indexByComposite[compositeKey]
                ?: if (kind != null && year != null && normTitle.isNotBlank()) {
                    indexByKindTitleYear[Triple(kind.wireValue, normTitle, year)]
                } else {
                    null
                }
            if (keyIndex == null) {
                val idx = accepted.size
                accepted.add(suggestion)
                record(idx, suggestion)
            } else {
                val existing = accepted[keyIndex]
                if (providerPriority(suggestion.providerId) > providerPriority(existing.providerId)) {
                    accepted[keyIndex] = suggestion
                    record(keyIndex, suggestion)
                }
            }
        }
        return accepted
    }
}
