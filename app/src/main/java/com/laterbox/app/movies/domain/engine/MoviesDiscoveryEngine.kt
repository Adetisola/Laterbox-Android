package com.laterbox.app.movies.domain.engine

import com.laterbox.app.data.local.entity.IntelligenceSignalEntity
import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.intelligence.TopicExtractor
import com.laterbox.app.movies.domain.model.BecauseYouWatchedGroup
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.PersonalizedTitleRecommendation
import com.laterbox.app.movies.domain.model.RuntimeBucket
import com.laterbox.app.movies.domain.model.SurprisePickResult
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

data class IntelligenceDiscoveryProfile(
    val enabled: Boolean = true,
    val facets: List<UserTasteFacetEntity> = emptyList(),
    val recentMediaCues: List<IntelligenceSignalEntity> = emptyList(),
    val sessionExposureCounts: Map<String, Int> = emptyMap(),
    val nowMs: Long = System.currentTimeMillis()
) {
    val hasActiveSignals: Boolean
        get() = enabled && (
            facets.any {
                !it.isSuppressedByUser &&
                    (it.surfaceScope == IntelligenceSurfaceScope.MOVIES.name || it.surfaceScope == IntelligenceSurfaceScope.ALL.name) &&
                    (it.expiresAtMs == null || it.expiresAtMs > nowMs)
            } ||
                recentMediaCues.any { it.isMediaCue && it.strength > 0.0 } ||
                sessionExposureCounts.isNotEmpty()
            )
}

object MoviesDiscoveryEngine {

    private val DATE_KEY_REGEX = Regex("(\\d{4})(?:-(\\d{1,2}))?(?:-(\\d{1,2}))?")

    private data class CandidateProfileScore(
        val delta: Double,
        val positiveSignalScore: Double,
        val hasDislike: Boolean,
        val matchedTags: List<String>,
        val rationale: String?
    )

    private fun scoreCandidateWithProfile(
        candidate: MovieTitleSummary,
        profile: IntelligenceDiscoveryProfile
    ): CandidateProfileScore {
        if (!profile.enabled) {
            return CandidateProfileScore(0.0, 0.0, false, emptyList(), null)
        }

        val activeFacets = profile.facets.filter { facet ->
            !facet.isSuppressedByUser &&
                (facet.surfaceScope == IntelligenceSurfaceScope.MOVIES.name ||
                    facet.surfaceScope == IntelligenceSurfaceScope.ALL.name) &&
                (facet.expiresAtMs == null || facet.expiresAtMs > profile.nowMs)
        }

        val candidateFacetKeys = linkedMapOf<String, String>()
        for (genre in candidate.genres) {
            val clean = genre.trim()
            if (clean.isNotBlank() && !clean.equals("All", ignoreCase = true)) {
                candidateFacetKeys[TopicExtractor.genreFacetKey(clean)] = clean
            }
        }
        candidateFacetKeys[TopicExtractor.kindFacetKey(candidate.kind)] = candidate.kind.label

        var delta = 0.0
        var positiveSignalScore = 0.0
        var hasDislike = false
        val pinnedMatches = mutableListOf<String>()
        val enduringMatches = mutableListOf<String>()
        val mediaCueMatches = mutableListOf<String>()
        val matchedTags = mutableListOf<String>()

        for (facet in activeFacets) {
            val matchedLabel = candidateFacetKeys[facet.facetKey] ?: continue
            val isGenre = facet.facetKey.startsWith("genre:")
            when (facet.facetType) {
                TasteFacetType.EXPLICIT_DISLIKE.name -> {
                    val penalty = if (isGenre) 12.0 else 6.0
                    delta -= penalty
                    hasDislike = true
                }
                TasteFacetType.EXPLICIT_LIKE.name -> {
                    val boost = (if (isGenre) 4.2 else 2.2) * facet.score.coerceIn(0.2, 1.0)
                    delta += boost
                    positiveSignalScore += boost
                    pinnedMatches += matchedLabel
                    matchedTags += matchedLabel
                }
                TasteFacetType.ENDURING_INTEREST.name -> {
                    val boost = (if (isGenre) 2.8 else 1.3) * facet.score.coerceIn(0.1, 1.0)
                    delta += boost
                    positiveSignalScore += boost
                    if (isGenre) enduringMatches += matchedLabel
                    matchedTags += matchedLabel
                }
                else -> {
                    val boost = (if (isGenre) 2.2 else 1.0) * facet.score.coerceIn(0.1, 1.0)
                    delta += boost
                    positiveSignalScore += boost
                    if (isGenre) enduringMatches += matchedLabel
                    matchedTags += matchedLabel
                }
            }
        }

        // Strict isolation: ONLY explicit media cues (isMediaCue == true) may cross from Inbox into Movies & TV.
        for (cue in profile.recentMediaCues) {
            if (!cue.isMediaCue || cue.strength <= 0.0) continue
            if (cue.surfaceScope == IntelligenceSurfaceScope.LIBRARY.name) continue
            val rawTags = cue.topicsCsv.split(',').map { it.trim() }.filter { it.isNotBlank() }
            val genreTags = rawTags.filter { it.startsWith("genre:") }
            val cueTopics = if (genreTags.isNotEmpty()) genreTags else rawTags.filter { it.startsWith("kind:") }
            if (cueTopics.isEmpty()) continue
            val matchedCueGenres = cueTopics.mapNotNull { candidateFacetKeys[it] }
            if (matchedCueGenres.isNotEmpty()) {
                val cueBoost = (matchedCueGenres.size * 1.9 * cue.strength.coerceIn(0.2, 1.2))
                delta += cueBoost
                positiveSignalScore += cueBoost
                mediaCueMatches.addAll(matchedCueGenres)
                matchedTags.addAll(matchedCueGenres)
            }
        }

        val exposures = profile.sessionExposureCounts[candidate.stableMediaKey] ?: 0
        if (exposures > 0) {
            delta -= minOf(exposures, 5) * 1.35
        }

        val distinctTags = matchedTags.distinct().take(3)
        val rationale = when {
            pinnedMatches.isNotEmpty() ->
                "Pinned taste: ${pinnedMatches.distinct().take(2).joinToString(" & ")}"
            mediaCueMatches.isNotEmpty() ->
                "From your saved media links (${mediaCueMatches.distinct().take(2).joinToString(" & ")})"
            enduringMatches.isNotEmpty() ->
                "Matches your ${enduringMatches.distinct().take(2).joinToString(" & ")} taste"
            distinctTags.isNotEmpty() ->
                "Matches your ${distinctTags.first()} preference"
            else -> null
        }

        return CandidateProfileScore(
            delta = delta,
            positiveSignalScore = positiveSignalScore,
            hasDislike = hasDislike,
            matchedTags = distinctTags,
            rationale = rationale
        )
    }

    private fun genreAndKindSimilarity(a: MovieTitleSummary, b: MovieTitleSummary): Double {
        val g1 = a.genres.map { it.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }.toSet()
        val g2 = b.genres.map { it.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }.toSet()
        val union = g1.union(g2).size
        val jaccard = if (union > 0) g1.intersect(g2).size.toDouble() / union.toDouble() else 0.0
        val kindBonus = if (a.kind == b.kind) 0.15 else 0.0
        return (jaccard * 0.85 + kindBonus).coerceIn(0.0, 1.0)
    }

    internal fun rerankWithMmr(
        scored: List<Pair<MovieTitleSummary, Double>>,
        limit: Int,
        lambda: Double = 0.65
    ): List<Pair<MovieTitleSummary, Double>> {
        if (limit <= 0 || scored.isEmpty()) return emptyList()
        if (scored.size <= 2 || limit == 1) return scored.sortedByDescending { it.second }.take(limit)

        val remaining = scored.sortedByDescending { it.second }.toMutableList()
        val maxScore = remaining.maxOf { it.second }
        val minScore = remaining.minOf { it.second }
        val baselineMin = minOf(0.0, minScore)
        val range = (maxScore - baselineMin).takeIf { it > 0.0001 } ?: 1.0

        val selected = mutableListOf<Pair<MovieTitleSummary, Double>>()
        selected.add(remaining.removeAt(0))

        while (selected.size < limit && remaining.isNotEmpty()) {
            var bestIdx = 0
            var bestMmr = Double.NEGATIVE_INFINITY
            for (i in remaining.indices) {
                val (candidate, rawScore) = remaining[i]
                val normRel = (rawScore - baselineMin) / range
                val maxSim = selected.maxOf { (chosen, _) -> genreAndKindSimilarity(candidate, chosen) }
                val mmr = lambda * normRel - (1.0 - lambda) * maxSim
                if (mmr > bestMmr) {
                    bestMmr = mmr
                    bestIdx = i
                }
            }
            selected.add(remaining.removeAt(bestIdx))
        }
        return selected
    }

    fun buildPersonalizedForYouRail(
        watchHistory: List<WatchHistoryItem>,
        watchlist: List<MovieTitleSummary>,
        candidatePool: List<MovieTitleSummary>,
        hiddenMediaKeys: Set<String> = emptySet(),
        profile: IntelligenceDiscoveryProfile = IntelligenceDiscoveryProfile(),
        maxItems: Int = 10
    ): List<PersonalizedTitleRecommendation> {
        if (!profile.enabled || maxItems <= 0 || candidatePool.isEmpty()) return emptyList()

        val hasPositiveInput = profile.facets.any { facet ->
            !facet.isSuppressedByUser &&
                facet.facetType != TasteFacetType.EXPLICIT_DISLIKE.name &&
                (facet.surfaceScope == IntelligenceSurfaceScope.MOVIES.name ||
                    facet.surfaceScope == IntelligenceSurfaceScope.ALL.name) &&
                (facet.expiresAtMs == null || facet.expiresAtMs > profile.nowMs)
        } || profile.recentMediaCues.any { it.isMediaCue && it.strength > 0.0 }

        if (!hasPositiveInput) return emptyList()

        val watchedKeys = watchHistory
            .filterNot { it.isTrailer }
            .map { it.stableMediaKey }
            .toSet()
        val watchlistKeys = watchlist.map { it.stableMediaKey }.toSet()

        val uniquePool = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
            candidatePool.filter {
                it.stableMediaKey !in hiddenMediaKeys &&
                    it.stableMediaKey !in watchedKeys
            }
        )
        if (uniquePool.isEmpty()) return emptyList()

        val detailByKey = linkedMapOf<String, CandidateProfileScore>()
        val scored = uniquePool.mapNotNull { candidate ->
            val pScore = scoreCandidateWithProfile(candidate, profile)
            if (pScore.hasDislike || pScore.positiveSignalScore <= 0.0) {
                return@mapNotNull null
            }
            detailByKey[candidate.stableMediaKey] = pScore
            var total = pScore.delta
            total += (candidate.rating ?: 5.5) * 0.35
            if (candidate.isPlayableHint) total += 0.9
            if (candidate.stableMediaKey in watchlistKeys) total += 0.7
            candidate to total
        }

        if (scored.isEmpty()) return emptyList()

        return rerankWithMmr(scored, limit = maxItems).map { (item, score) ->
            val detail = detailByKey[item.stableMediaKey]
            PersonalizedTitleRecommendation(
                item = item,
                score = score,
                rationale = detail?.rationale ?: "Picked for your local taste profile"
            )
        }
    }

    fun buildBecauseYouWatchedGroups(
        watchHistory: List<WatchHistoryItem>,
        watchlist: List<MovieTitleSummary>,
        candidatePool: List<MovieTitleSummary>,
        hiddenMediaKeys: Set<String> = emptySet(),
        anchorSummaries: Map<String, MovieTitleSummary> = emptyMap(),
        maxGroups: Int = 2,
        maxItemsPerGroup: Int = 10,
        profile: IntelligenceDiscoveryProfile = IntelligenceDiscoveryProfile()
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

        val uniquePool = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
            candidatePool.filter { it.stableMediaKey !in hiddenMediaKeys }
        )

        val usedCandidateKeys = mutableSetOf<String>()
        val groups = mutableListOf<BecauseYouWatchedGroup>()
        val usePersonalization = profile.hasActiveSignals

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

            val profileDetails = linkedMapOf<String, CandidateProfileScore>()
            val scoredPairs = uniquePool.mapNotNull { candidate ->
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
                if (usePersonalization) {
                    val pScore = scoreCandidateWithProfile(candidate, profile)
                    profileDetails[cKey] = pScore
                    score += pScore.delta
                }
                candidate to score
            }

            val finalPairs = if (usePersonalization) {
                rerankWithMmr(scoredPairs, limit = maxItemsPerGroup, lambda = 0.74)
            } else {
                scoredPairs.sortedByDescending { it.second }.take(maxItemsPerGroup)
            }
            val scored = finalPairs.map { it.first }

            if (scored.isNotEmpty()) {
                scored.forEach { usedCandidateKeys.add(it.stableMediaKey) }
                val itemRationales = scored.mapNotNull { item ->
                    val reason = profileDetails[item.stableMediaKey]?.rationale
                        ?: "Similar to ${anchor.title}"
                    item.stableMediaKey to reason
                }.toMap()
                val topReason = scored.firstNotNullOfOrNull { profileDetails[it.stableMediaKey]?.rationale }
                groups.add(
                    BecauseYouWatchedGroup(
                        anchorTitle = anchor.title,
                        anchorKind = anchor.kind,
                        items = scored,
                        rationale = topReason ?: "Recommended from your viewing history",
                        itemRationales = itemRationales
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
        limit: Int = 12,
        profile: IntelligenceDiscoveryProfile = IntelligenceDiscoveryProfile()
    ): List<MovieTitleSummary> {
        val anchorKey = anchor.stableMediaKey
        val anchorGenres = anchor.genres.map { it.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }.toSet()
        val relatedKeys = relatedTitles.map { it.stableMediaKey }.toSet()
        val usePersonalization = profile.hasActiveSignals

        val mergedPool = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
            (relatedTitles + candidatePool)
                .filter { it.stableMediaKey != anchorKey && it.stableMediaKey !in hiddenMediaKeys }
        )

        val scoredPairs = mergedPool.mapNotNull { candidate ->
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
            if (usePersonalization) {
                score += scoreCandidateWithProfile(candidate, profile).delta
            }
            if (score < 2.5 && !isExplicitRelated) return@mapNotNull null
            candidate to score
        }

        val ranked = if (usePersonalization) {
            rerankWithMmr(scoredPairs, limit = limit, lambda = 0.74)
        } else {
            scoredPairs.sortedByDescending { it.second }.take(limit)
        }
        return ranked.map { it.first }
    }

    fun pickSurpriseMe(
        watchHistory: List<WatchHistoryItem>,
        recentlyViewed: List<MovieTitleSummary>,
        hiddenMediaKeys: Set<String>,
        watchlist: List<MovieTitleSummary>,
        candidatePool: List<MovieTitleSummary>,
        seed: Long = System.currentTimeMillis(),
        preferredKind: MediaKind? = null,
        profile: IntelligenceDiscoveryProfile = IntelligenceDiscoveryProfile()
    ): SurprisePickResult? {
        val combinedPool = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
            (watchlist + candidatePool)
                .filter {
                    it.stableMediaKey !in hiddenMediaKeys &&
                        (preferredKind == null || it.kind == preferredKind)
                }
        )

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

        val usePersonalization = profile.hasActiveSignals
        val details = linkedMapOf<String, CandidateProfileScore>()
        val rawScored = freshPool.map { item ->
            var score = (item.rating ?: 5.5) * 1.2
            if (item.isPlayableHint) score += 2.0
            if (item.stableMediaKey in watchlistKeys) score += 1.5
            val matchedCount = item.genres.count { it.trim().lowercase(Locale.US) in topGenres }
            score += matchedCount * 2.5
            if (usePersonalization) {
                val pScore = scoreCandidateWithProfile(item, profile)
                details[item.stableMediaKey] = pScore
                score += pScore.delta
            }
            Triple(item, score, details[item.stableMediaKey]?.hasDislike == true)
        }

        val nonDisliked = rawScored.filterNot { it.third }.ifEmpty { rawScored }
        val scored = nonDisliked.map { it.first to it.second }.sortedByDescending { it.second }

        val topWindow = scored.take(minOf(8, scored.size)).map { it.first }
        val chosenIndex = Random(seed).nextInt(topWindow.size)
        val pick = topWindow[chosenIndex]

        val pDetail = details[pick.stableMediaKey]
        val matchedGenres = pick.genres.filter { it.trim().lowercase(Locale.US) in topGenres }.take(2)
        val rationale = when {
            pDetail?.rationale != null -> pDetail.rationale
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

        val signalTags = buildList {
            pDetail?.matchedTags?.forEach { add(it) }
            matchedGenres.forEach { if (it !in this) add(it) }
        }.take(3)

        return SurprisePickResult(pick = pick, rationale = rationale, signalTags = signalTags)
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
        val eligible = items.filter { item ->
            item.stableMediaKey !in hiddenMediaKeys &&
                ((item.addedAtMs != null && item.addedAtMs > 0L) ||
                    parseSortableDateKey(item.releaseDate) > 0L)
        }
        return MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(eligible)
            .sortedByDescending { item ->
                val dateKey = parseSortableDateKey(item.releaseDate)
                val addedKey = item.addedAtMs ?: 0L
                maxOf(addedKey, dateKey)
            }
            .take(limit)
    }

    fun selectQuickRuntimePicks(
        items: List<MovieTitleSummary>,
        hiddenMediaKeys: Set<String> = emptySet(),
        limit: Int = 12
    ): List<MovieTitleSummary> {
        val eligible = items.filter {
            it.stableMediaKey !in hiddenMediaKeys &&
                RuntimeBucket.QUICK.matches(it.durationMinutes)
        }
        return MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(eligible)
            .sortedByDescending { it.rating ?: 0.0 }
            .take(limit)
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
        val parts = DATE_KEY_REGEX.find(clean) ?: return 0L
        val year = parts.groupValues[1].toIntOrNull()?.takeIf { it in 1880..2100 } ?: return 0L
        val month = parts.groupValues[2].toIntOrNull()?.coerceIn(1, 12) ?: 1
        val maxDay = YearMonth.of(year, month).lengthOfMonth()
        val day = (parts.groupValues[3].toIntOrNull() ?: 1).coerceIn(1, maxDay)
        val epochMillis = LocalDate.of(year, month, day).toEpochDay() * 86_400_000L
        return epochMillis.coerceAtLeast(1L)
    }
}
