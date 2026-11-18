package com.laterbox.app.movies.domain.engine

import com.laterbox.app.movies.domain.model.BecauseYouWatchedGroup
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.RuntimeBucket
import com.laterbox.app.movies.domain.model.SurprisePickResult
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

object MoviesDiscoveryEngine {

    fun buildBecauseYouWatchedGroups(
        watchHistory: List<WatchHistoryItem>,
        watchlist: List<MovieTitleSummary>,
        candidatePool: List<MovieTitleSummary>,
        hiddenMediaKeys: Set<String> = emptySet(),
        anchorSummaries: Map<String, MovieTitleSummary> = emptyMap(),
        maxGroups: Int = 2,
        maxItemsPerGroup: Int = 10
    ): List<BecauseYouWatchedGroup> {
        if (maxGroups <= 0 || candidatePool.isEmpty()) return emptyList()

        val summaryByKey = linkedMapOf<String, MovieTitleSummary>()
        for (item in watchlist) {
            summaryByKey.putIfAbsent(item.stableMediaKey, item)
            summaryByKey.putIfAbsent(item.compositeId, item)
        }
        for (item in candidatePool) {
            summaryByKey.putIfAbsent(item.stableMediaKey, item)
            summaryByKey.putIfAbsent(item.compositeId, item)
        }
        anchorSummaries.forEach { (k, v) ->
            summaryByKey[k] = v
            summaryByKey[v.stableMediaKey] = v
        }

        val watchedKeys = watchHistory
            .filterNot { it.isTrailer }
            .map { it.stableMediaKey }
            .toSet()

        val anchorHistory = watchHistory
            .asSequence()
            .filter { !it.isTrailer && !it.isHiddenTitle && it.stableMediaKey !in hiddenMediaKeys }
            .sortedWith(
                compareByDescending<WatchHistoryItem> { it.isCompleted }
                    .thenByDescending { it.lastWatchedAt }
            )
            .distinctBy { it.stableMediaKey }
            .toList()

        if (anchorHistory.isEmpty()) return emptyList()

        val uniquePool = candidatePool
            .asSequence()
            .filter { it.stableMediaKey !in hiddenMediaKeys }
            .distinctBy { it.stableMediaKey }
            .toList()

        val usedCandidateKeys = mutableSetOf<String>()
        val groups = mutableListOf<BecauseYouWatchedGroup>()

        for (anchor in anchorHistory) {
            if (groups.size >= maxGroups) break
            val anchorSummary = summaryByKey[anchor.stableMediaKey] ?: summaryByKey[anchor.compositeId]
            val anchorGenres = anchorSummary?.genres
                ?.map { it.trim().lowercase(Locale.US) }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()

            if (anchorGenres.isEmpty()) {
                // Only build grounded recommendations when anchor has known genre metadata.
                continue
            }

            val scored = uniquePool.mapNotNull { candidate ->
                val cKey = candidate.stableMediaKey
                if (cKey == anchor.stableMediaKey || cKey in watchedKeys || cKey in usedCandidateKeys) {
                    return@mapNotNull null
                }
                val candidateGenres = candidate.genres
                    .map { it.trim().lowercase(Locale.US) }
                    .filter { it.isNotBlank() }
                    .toSet()
                val sharedGenres = anchorGenres.intersect(candidateGenres).size
                if (sharedGenres == 0) return@mapNotNull null

                var score = sharedGenres * 3.0
                if (candidate.kind == anchor.kind) score += 2.0
                if (!anchorSummary?.language.isNullOrBlank() &&
                    anchorSummary?.language.equals(candidate.language, ignoreCase = true)
                ) {
                    score += 1.0
                }
                if (!anchorSummary?.country.isNullOrBlank() &&
                    anchorSummary?.country.equals(candidate.country, ignoreCase = true)
                ) {
                    score += 1.0
                }
                score += (candidate.rating ?: 0.0) * 0.1
                candidate to score
            }.sortedByDescending { it.second }
                .take(maxItemsPerGroup)
                .map { it.first }

            if (scored.isNotEmpty()) {
                scored.forEach { usedCandidateKeys.add(it.stableMediaKey) }
                groups.add(
                    BecauseYouWatchedGroup(
                        anchorTitle = anchor.title,
                        anchorKind = anchor.kind,
                        items = scored
                    )
                )
            }
        }

        return groups
    }

    fun rankMoreLikeThis(
        anchor: MovieTitleSummary,
        relatedTitles: List<MovieTitleSummary>,
        candidatePool: List<MovieTitleSummary>,
        hiddenMediaKeys: Set<String> = emptySet(),
        limit: Int = 12
    ): List<MovieTitleSummary> {
        val anchorKey = anchor.stableMediaKey
        val anchorGenres = anchor.genres.map { it.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }.toSet()
        val relatedKeys = relatedTitles.map { it.stableMediaKey }.toSet()

        val mergedPool = (relatedTitles + candidatePool)
            .asSequence()
            .filter { it.stableMediaKey != anchorKey && it.stableMediaKey !in hiddenMediaKeys }
            .distinctBy { it.stableMediaKey }
            .toList()

        return mergedPool.mapNotNull { candidate ->
            val isExplicitRelated = candidate.stableMediaKey in relatedKeys
            val cGenres = candidate.genres.map { it.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }.toSet()
            val sharedGenres = anchorGenres.intersect(cGenres).size
            if (!isExplicitRelated && sharedGenres == 0 && candidate.kind != anchor.kind) {
                return@mapNotNull null
            }
            var score = if (isExplicitRelated) 5.0 else 0.0
            score += sharedGenres * 3.0
            if (candidate.kind == anchor.kind) score += 2.0
            if (!anchor.language.isNullOrBlank() && anchor.language.equals(candidate.language, ignoreCase = true)) {
                score += 1.0
            }
            if (!anchor.country.isNullOrBlank() && anchor.country.equals(candidate.country, ignoreCase = true)) {
                score += 1.0
            }
            val y1 = anchor.releaseYear
            val y2 = candidate.releaseYear
            if (y1 != null && y2 != null && abs(y1 - y2) <= 5) {
                score += 1.0
            }
            score += (candidate.rating ?: 0.0) * 0.1
            if (score < 2.5 && !isExplicitRelated) return@mapNotNull null
            candidate to score
        }.sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    fun pickSurpriseMe(
        watchHistory: List<WatchHistoryItem>,
        recentlyViewed: List<MovieTitleSummary>,
        hiddenMediaKeys: Set<String>,
        watchlist: List<MovieTitleSummary>,
        candidatePool: List<MovieTitleSummary>,
        seed: Long = System.currentTimeMillis(),
        preferredKind: MediaKind? = null
    ): SurprisePickResult? {
        val combinedPool = (watchlist + candidatePool)
            .asSequence()
            .filter {
                it.stableMediaKey !in hiddenMediaKeys &&
                    (preferredKind == null || it.kind == preferredKind)
            }
            .distinctBy { it.stableMediaKey }
            .toList()

        if (combinedPool.isEmpty()) return null

        val completedOrWatchedKeys = watchHistory
            .filter { !it.isTrailer && (it.isCompleted || it.positionMs > 0L) }
            .map { it.stableMediaKey }
            .toSet()
        val recentKeys = recentlyViewed.map { it.stableMediaKey }.toSet()
        val watchlistKeys = watchlist.map { it.stableMediaKey }.toSet()

        // Derive user's top genres from watched/watchlist/recently viewed
        val genreCounts = linkedMapOf<String, Int>()
        val summaryByKey = combinedPool.associateBy { it.stableMediaKey }
        for (h in watchHistory.filter { !it.isTrailer && !it.isHiddenTitle }) {
            summaryByKey[h.stableMediaKey]?.genres?.forEach { g ->
                val clean = g.trim()
                if (clean.isNotBlank()) genreCounts[clean] = (genreCounts[clean] ?: 0) + 2
            }
        }
        for (w in watchlist) {
            w.genres.forEach { g ->
                val clean = g.trim()
                if (clean.isNotBlank()) genreCounts[clean] = (genreCounts[clean] ?: 0) + 1
            }
        }
        val topGenres = genreCounts.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key.lowercase(Locale.US) }
            .toSet()

        val unwatchedPool = combinedPool.filter { it.stableMediaKey !in completedOrWatchedKeys }
        val activePool = unwatchedPool.ifEmpty { combinedPool }
        val freshPool = activePool.filter { it.stableMediaKey !in recentKeys }.ifEmpty { activePool }

        val scored = freshPool.map { item ->
            var score = (item.rating ?: 5.5) * 1.2
            if (item.isPlayableHint) score += 2.0
            if (item.stableMediaKey in watchlistKeys) score += 1.5
            val matchedCount = item.genres.count { it.trim().lowercase(Locale.US) in topGenres }
            score += matchedCount * 2.5
            item to score
        }.sortedByDescending { it.second }

        val topWindow = scored.take(minOf(8, scored.size)).map { it.first }
        val chosenIndex = Random(seed).nextInt(topWindow.size)
        val pick = topWindow[chosenIndex]

        val matchedGenres = pick.genres.filter { it.trim().lowercase(Locale.US) in topGenres }.take(2)
        val rationale = when {
            matchedGenres.isNotEmpty() ->
                "Matches your taste in ${matchedGenres.joinToString(" & ")}"
            pick.stableMediaKey in watchlistKeys ->
                "Waiting in your watchlist"
            pick.rating != null && pick.rating >= 7.5 ->
                "Highly rated ${pick.kind.label.lowercase(Locale.US)} (${String.format(Locale.US, "%.1f", pick.rating)})"
            pick.durationMinutes != null && pick.durationMinutes < 90 ->
                "Quick ${pick.durationMinutes}m watch"
            else ->
                "Top ${pick.kind.label.lowercase(Locale.US)} pick from your catalogue"
        }

        return SurprisePickResult(pick = pick, rationale = rationale)
    }

    fun filterTitles(
        items: List<MovieTitleSummary>,
        kind: MediaKind? = null,
        genre: String? = null,
        language: String? = null,
        country: String? = null,
        year: String? = null,
        runtimeBucket: RuntimeBucket = RuntimeBucket.ALL,
        hiddenMediaKeys: Set<String> = emptySet()
    ): List<MovieTitleSummary> {
        val cleanGenre = genre?.trim()?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
        val cleanLang = language?.trim()?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
        val cleanCountry = country?.trim()?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
        val cleanYear = year?.trim()?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) }

        return items.filter { item ->
            if (item.stableMediaKey in hiddenMediaKeys) return@filter false
            if (kind != null && item.kind != kind) return@filter false
            if (cleanGenre != null && item.genres.none { it.equals(cleanGenre, ignoreCase = true) }) {
                return@filter false
            }
            if (cleanLang != null && !matchesLanguage(item.language, cleanLang)) {
                return@filter false
            }
            if (cleanCountry != null && !matchesCountry(item.country, cleanCountry)) {
                return@filter false
            }
            if (cleanYear != null && !matchesYearFilter(item.releaseYear, cleanYear)) {
                return@filter false
            }
            if (!runtimeBucket.matches(item.durationMinutes)) {
                return@filter false
            }
            true
        }
    }

    fun selectRecentlyAdded(
        items: List<MovieTitleSummary>,
        hiddenMediaKeys: Set<String> = emptySet(),
        limit: Int = 15
    ): List<MovieTitleSummary> {
        return items
            .asSequence()
            .filter { item ->
                item.stableMediaKey !in hiddenMediaKeys &&
                    ((item.addedAtMs != null && item.addedAtMs > 0L) ||
                        parseSortableDateKey(item.releaseDate) > 0L)
            }
            .distinctBy { it.stableMediaKey }
            .sortedByDescending { item ->
                val dateKey = parseSortableDateKey(item.releaseDate)
                val addedKey = item.addedAtMs ?: 0L
                maxOf(addedKey, dateKey)
            }
            .take(limit)
            .toList()
    }

    fun selectQuickRuntimePicks(
        items: List<MovieTitleSummary>,
        hiddenMediaKeys: Set<String> = emptySet(),
        limit: Int = 12
    ): List<MovieTitleSummary> {
        return items
            .asSequence()
            .filter {
                it.stableMediaKey !in hiddenMediaKeys &&
                    RuntimeBucket.QUICK.matches(it.durationMinutes)
            }
            .distinctBy { it.stableMediaKey }
            .sortedByDescending { it.rating ?: 0.0 }
            .take(limit)
            .toList()
    }

    fun extractAvailableLanguages(items: List<MovieTitleSummary>): List<String> {
        val found = items
            .mapNotNull { it.language?.trim()?.takeIf { l -> l.isNotBlank() } }
            .distinctBy { it.lowercase(Locale.US) }
            .sorted()
        return listOf("All") + found
    }

    private fun matchesLanguage(itemLang: String?, filterLang: String): Boolean {
        val clean = itemLang?.trim()?.takeIf { it.isNotBlank() } ?: return false
        return clean.equals(filterLang, ignoreCase = true) ||
            clean.contains(filterLang, ignoreCase = true)
    }

    private fun matchesCountry(itemCountry: String?, filterCountry: String): Boolean {
        val clean = itemCountry?.trim()?.takeIf { it.isNotBlank() } ?: return false
        if (clean.equals(filterCountry, ignoreCase = true)) return true
        if (filterCountry.equals("South Korea", ignoreCase = true) &&
            clean.equals("Korea", ignoreCase = true)
        ) {
            return true
        }
        return false
    }

    private fun matchesYearFilter(releaseYear: Int?, filterYear: String): Boolean {
        val y = releaseYear ?: return false
        return when {
            filterYear.equals("Classic", ignoreCase = true) -> y < 2000
            filterYear.endsWith("s", ignoreCase = true) && filterYear.length == 5 -> {
                val base = filterYear.dropLast(1).toIntOrNull() ?: return false
                y in base..(base + 9)
            }
            else -> filterYear.toIntOrNull()?.let { y == it } ?: false
        }
    }

    /**
     * Converts YYYY-MM-DD, YYYY-MM, or YYYY into UTC epoch millis
     * strictly from real parsed dates (returns 0L if no valid date exists).
     */
    internal fun parseSortableDateKey(rawDate: String?): Long {
        val clean = rawDate?.trim()?.takeIf { it.isNotBlank() } ?: return 0L
        val parts = Regex("(\\d{4})(?:-(\\d{1,2}))?(?:-(\\d{1,2}))?").find(clean) ?: return 0L
        val year = parts.groupValues[1].toIntOrNull()?.takeIf { it in 1880..2100 } ?: return 0L
        val month = parts.groupValues[2].toIntOrNull()?.coerceIn(1, 12) ?: 1
        val day = parts.groupValues[3].toIntOrNull()?.coerceIn(1, 31) ?: 1
        val cal = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }
        return cal.timeInMillis.coerceAtLeast(1L)
    }
}
