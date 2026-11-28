package com.laterbox.app.movies.domain.engine

import com.laterbox.app.data.local.entity.IntelligenceSignalEntity
import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.TitleFeedbackEntity
import com.laterbox.app.data.local.entity.TitleReaction
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.intelligence.IntelligenceSignalLogger
import com.laterbox.app.intelligence.TopicExtractor
import com.laterbox.app.movies.domain.model.BecauseYouWatchedGroup
import com.laterbox.app.movies.domain.model.BrowseSection
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
import kotlin.math.exp
import kotlin.random.Random

data class IntelligenceDiscoveryProfile(
    val enabled: Boolean = true,
    val facets: List<UserTasteFacetEntity> = emptyList(),
    val recentMediaCues: List<IntelligenceSignalEntity> = emptyList(),
    val titleFeedback: List<TitleFeedbackEntity> = emptyList(),
    val behavioralSignals: List<IntelligenceSignalEntity> = emptyList(),
    val sessionExposureCounts: Map<String, Int> = emptyMap(),
    val nowMs: Long = System.currentTimeMillis()
) {
    private val feedbackByAnyKey: Map<String, TitleReaction> by lazy {
        if (titleFeedback.isEmpty()) return@lazy emptyMap()
        val map = LinkedHashMap<String, TitleReaction>(titleFeedback.size * 3)
        for (tf in titleFeedback) {
            val reaction = runCatching { TitleReaction.valueOf(tf.reaction) }.getOrNull() ?: continue
            map[tf.mediaKey] = reaction
            map[tf.compositeId] = reaction
            tf.canonicalId?.takeIf { it.isNotBlank() }?.let { map[it] = reaction }
        }
        map
    }

    fun reactionFor(item: MovieTitleSummary): TitleReaction? =
        feedbackByAnyKey[item.stableMediaKey]
            ?: item.canonicalId?.let { feedbackByAnyKey[it] }
            ?: feedbackByAnyKey[item.compositeId]

    fun reactionForKey(stableKey: String, compositeId: String? = null): TitleReaction? =
        feedbackByAnyKey[stableKey] ?: compositeId?.let { feedbackByAnyKey[it] }

    val dislikedKeys: Set<String> by lazy {
        feedbackByAnyKey.filterValues { it == TitleReaction.DISLIKE }.keys
    }

    val likedKeys: Set<String> by lazy {
        feedbackByAnyKey.filterValues { it == TitleReaction.LIKE }.keys
    }

    val hasActiveSignals: Boolean
        get() = enabled && (
            facets.any {
                !it.isSuppressedByUser &&
                    (it.surfaceScope == IntelligenceSurfaceScope.MOVIES.name || it.surfaceScope == IntelligenceSurfaceScope.ALL.name) &&
                    (it.expiresAtMs == null || it.expiresAtMs > nowMs)
            } ||
                titleFeedback.isNotEmpty() ||
                recentMediaCues.any { it.isMediaCue && it.strength > 0.0 } ||
                behavioralSignals.any { it.surfaceScope == IntelligenceSurfaceScope.MOVIES.name || it.isMediaCue } ||
                sessionExposureCounts.isNotEmpty()
            )
}

object MoviesDiscoveryEngine {

    private val DATE_KEY_REGEX = Regex("(\\d{4})(?:-(\\d{1,2}))?(?:-(\\d{1,2}))?")
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val RECENTLY_ADDED_BUCKET_MS = 14L * DAY_MS
    private const val MIN_FOR_YOU_RELEVANCE = 1.20

    private val GLOBAL_SECTION_KEYWORDS = listOf(
        "trending",
        "top rated",
        "popular",
        "most watched",
        "featured",
        "staff picks",
        "box office"
    )

    private data class BehavioralTasteVector(
        val themeWeights: Map<String, Double> = emptyMap(),
        val toneWeights: Map<String, Double> = emptyMap(),
        val subgenreWeights: Map<String, Double> = emptyMap(),
        val settingWeights: Map<String, Double> = emptyMap(),
        val structureWeights: Map<String, Double> = emptyMap(),
        val regionWeights: Map<String, Double> = emptyMap(),
        val franchiseWeights: Map<String, Double> = emptyMap(),
        val dislikedThemeNudges: Map<String, Double> = emptyMap(),
        val dislikedSubgenreNudges: Map<String, Double> = emptyMap(),
        val dislikedGenreNudges: Map<String, Double> = emptyMap(),
        val demotedTitleKeys: Set<String> = emptySet(),
        val behavioralEvidenceStrength: Double = 0.0
    )

    private data class CandidateProfileScore(
        val delta: Double,
        val positiveSignalScore: Double,
        val hasTitleDislike: Boolean,
        val matchedTags: List<String>,
        val rationale: String?
    )

    private fun buildBehavioralTasteVector(
        profile: IntelligenceDiscoveryProfile,
        watchHistory: List<WatchHistoryItem>,
        watchlist: List<MovieTitleSummary>,
        summaryLookup: Map<String, MovieTitleSummary>
    ): BehavioralTasteVector {
        if (!profile.enabled) return BehavioralTasteVector()

        val themeWeights = linkedMapOf<String, Double>()
        val toneWeights = linkedMapOf<String, Double>()
        val subgenreWeights = linkedMapOf<String, Double>()
        val settingWeights = linkedMapOf<String, Double>()
        val structureWeights = linkedMapOf<String, Double>()
        val regionWeights = linkedMapOf<String, Double>()
        val franchiseWeights = linkedMapOf<String, Double>()
        val dislikedThemes = linkedMapOf<String, Double>()
        val dislikedSubgenres = linkedMapOf<String, Double>()
        val dislikedGenres = linkedMapOf<String, Double>()
        val demotedTitles = linkedSetOf<String>()

        var evidenceStrength = 0.0

        fun addProfileEvidence(content: MediaContentProfile, weight: Double) {
            if (weight <= 0.0) return
            val qualityWeight = weight * (0.55 + 0.45 * content.metadataQuality)
            content.themes.forEach { t -> themeWeights[t] = (themeWeights[t] ?: 0.0) + qualityWeight * 1.15 }
            content.tones.forEach { t -> toneWeights[t] = (toneWeights[t] ?: 0.0) + qualityWeight * 0.85 }
            content.subgenres.forEach { s -> subgenreWeights[s] = (subgenreWeights[s] ?: 0.0) + qualityWeight * 1.1 }
            content.settings.forEach { s -> settingWeights[s] = (settingWeights[s] ?: 0.0) + qualityWeight * 0.65 }
            content.narrativeStructures.forEach { s -> structureWeights[s] = (structureWeights[s] ?: 0.0) + qualityWeight * 0.65 }
            content.regionCluster?.let { r -> regionWeights[r] = (regionWeights[r] ?: 0.0) + qualityWeight * 0.7 }
            content.franchiseStem?.let { f -> franchiseWeights[f] = (franchiseWeights[f] ?: 0.0) + qualityWeight }
            evidenceStrength += qualityWeight
        }

        // 1. Explicit title reactions (Like & Dislike)
        for (tf in profile.titleFeedback) {
            val reaction = runCatching { TitleReaction.valueOf(tf.reaction) }.getOrNull() ?: continue
            val ageDays = ((profile.nowMs - tf.updatedAtMs).coerceAtLeast(0L) / DAY_MS.toDouble())
            val recency = exp(-ageDays / 90.0)
            val resolvedSummary = summaryLookup[tf.mediaKey]
                ?: tf.canonicalId?.let { summaryLookup[it] }
                ?: summaryLookup[tf.compositeId]
                ?: MovieTitleSummary(
                    id = tf.titleId,
                    providerId = tf.providerId,
                    kind = MediaKind.fromWire(tf.kind),
                    title = tf.title,
                    genres = tf.genresCsv.split(',').map { it.trim() }.filter { it.isNotBlank() },
                    canonicalId = tf.canonicalId
                )
            val content = MediaContentAnalyzer.analyze(resolvedSummary)
            if (reaction == TitleReaction.LIKE) {
                addProfileEvidence(content, 1.55 * recency)
            } else {
                demotedTitles.add(tf.mediaKey)
                demotedTitles.add(tf.compositeId)
                tf.canonicalId?.let { demotedTitles.add(it) }
                // Cautious attribute nudge: never bans a whole genre from 1 dislike
                content.themes.forEach { t -> dislikedThemes[t] = ((dislikedThemes[t] ?: 0.0) + 0.30 * recency).coerceAtMost(0.95) }
                content.subgenres.forEach { s -> dislikedSubgenres[s] = ((dislikedSubgenres[s] ?: 0.0) + 0.35 * recency).coerceAtMost(1.0) }
                content.normalizedGenres.forEach { g ->
                    if (g !in setOf("drama", "comedy", "action", "movie", "series")) {
                        dislikedGenres[g] = ((dislikedGenres[g] ?: 0.0) + 0.16 * recency).coerceAtMost(0.55)
                    } else {
                        dislikedGenres[g] = ((dislikedGenres[g] ?: 0.0) + 0.08 * recency).coerceAtMost(0.30)
                    }
                }
            }
        }

        // 2. Behavioral watch history & rewatch patterns
        for (history in watchHistory) {
            if (history.isTrailer || history.isHiddenTitle) continue
            val resolvedSummary = summaryLookup[history.stableMediaKey]
                ?: history.canonicalId?.let { summaryLookup[it] }
                ?: summaryLookup[history.compositeId]
            val ageDays = ((profile.nowMs - history.lastWatchedAt).coerceAtLeast(0L) / DAY_MS.toDouble())
            val recency = exp(-ageDays / 45.0)
            val progressFrac = history.progressFraction
            val baseWeight = when {
                history.rewatchCount > 0 -> (1.45 + (history.rewatchCount.coerceAtMost(3) * 0.25))
                history.isCompleted -> 1.25
                progressFrac >= 0.45f -> 0.85
                progressFrac >= 0.15f -> 0.40
                else -> 0.15
            } * recency

            if (history.hiddenFromContinueWatching && !history.isCompleted && progressFrac < 0.25f) {
                demotedTitles.add(history.stableMediaKey)
                demotedTitles.add(history.compositeId)
            }
            if (resolvedSummary != null && baseWeight > 0.1) {
                addProfileEvidence(MediaContentAnalyzer.analyze(resolvedSummary), baseWeight)
            }
        }

        // 3. Watchlist intent (interested but unwatched yet)
        for (item in watchlist) {
            val ageDays = if (item.addedAtMs != null && item.addedAtMs > 0L) {
                ((profile.nowMs - item.addedAtMs).coerceAtLeast(0L) / DAY_MS.toDouble())
            } else 7.0
            val recency = exp(-ageDays / 60.0)
            addProfileEvidence(MediaContentAnalyzer.analyze(item), 0.72 * recency)
        }

        // 4. Recent behavioral signals (downloads, shares, collections, reminders, playback exit, continue watching remove)
        for (sig in profile.behavioralSignals) {
            if (sig.surfaceScope != IntelligenceSurfaceScope.MOVIES.name && !sig.isMediaCue) continue
            val ageDays = ((profile.nowMs - sig.createdAtMs).coerceAtLeast(0L) / DAY_MS.toDouble())
            val recency = exp(-ageDays / 30.0)
            when (sig.signalType) {
                IntelligenceSignalLogger.SIGNAL_CONTINUE_WATCHING_REMOVE,
                IntelligenceSignalLogger.SIGNAL_EARLY_PLAYBACK_EXIT,
                IntelligenceSignalLogger.SIGNAL_MOVIE_HIDE -> {
                    demotedTitles.add(sig.targetKey)
                }
                else -> {
                    val weight = when (sig.signalType) {
                        IntelligenceSignalLogger.SIGNAL_TITLE_SHARE,
                        IntelligenceSignalLogger.SIGNAL_COLLECTION_ADD -> 0.95
                        IntelligenceSignalLogger.SIGNAL_MOVIE_DOWNLOAD,
                        IntelligenceSignalLogger.SIGNAL_REMINDER_SET -> 0.85
                        IntelligenceSignalLogger.SIGNAL_REWATCH -> 1.15
                        IntelligenceSignalLogger.SIGNAL_WATCH_COMPLETED -> 1.0
                        IntelligenceSignalLogger.SIGNAL_MANUAL_WATCHED -> 0.85
                        IntelligenceSignalLogger.SIGNAL_PLAYBACK_START -> 0.35
                        IntelligenceSignalLogger.SIGNAL_DETAIL_OPEN -> 0.18
                        IntelligenceSignalLogger.SIGNAL_SEARCH -> 0.22
                        else -> 0.30
                    } * recency
                    val summary = summaryLookup[sig.targetKey]
                    if (summary != null) {
                        addProfileEvidence(MediaContentAnalyzer.analyze(summary), weight)
                    }
                }
            }
        }

        return BehavioralTasteVector(
            themeWeights = themeWeights,
            toneWeights = toneWeights,
            subgenreWeights = subgenreWeights,
            settingWeights = settingWeights,
            structureWeights = structureWeights,
            regionWeights = regionWeights,
            franchiseWeights = franchiseWeights,
            dislikedThemeNudges = dislikedThemes,
            dislikedSubgenreNudges = dislikedSubgenres,
            dislikedGenreNudges = dislikedGenres,
            demotedTitleKeys = demotedTitles,
            behavioralEvidenceStrength = evidenceStrength
        )
    }

    private fun scoreCandidateWithProfile(
        candidate: MovieTitleSummary,
        profile: IntelligenceDiscoveryProfile,
        behaviorVector: BehavioralTasteVector = BehavioralTasteVector(),
        candidateProfile: MediaContentProfile = MediaContentAnalyzer.analyze(candidate)
    ): CandidateProfileScore {
        if (!profile.enabled) {
            return CandidateProfileScore(0.0, 0.0, false, emptyList(), null)
        }

        val explicitReaction = profile.reactionFor(candidate)
        if (explicitReaction == TitleReaction.DISLIKE) {
            return CandidateProfileScore(
                delta = -24.0,
                positiveSignalScore = 0.0,
                hasTitleDislike = true,
                matchedTags = emptyList(),
                rationale = null
            )
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
        var hasFacetDislike = false
        val likedMatches = mutableListOf<String>()
        val enduringMatches = mutableListOf<String>()
        val mediaCueMatches = mutableListOf<String>()
        val matchedTags = mutableListOf<String>()

        // Score facets weighted by confidence, behavioral support, and recency
        for (facet in activeFacets) {
            val matchedLabel = candidateFacetKeys[facet.facetKey] ?: continue
            val isGenre = facet.facetKey.startsWith("genre:")
            val confidenceWeight = facet.confidence.coerceIn(0.25, 1.0)
            val behavioralMult = if (facet.hasBehavioralEvidence) 1.0 else 0.45
            val ageDays = ((profile.nowMs - facet.lastReinforcedAtMs).coerceAtLeast(0L) / DAY_MS.toDouble())
            val recency = exp(-ageDays / 60.0)

            when (facet.facetType) {
                TasteFacetType.EXPLICIT_DISLIKE.name -> {
                    val penalty = (if (isGenre) 4.0 else 1.8) * confidenceWeight
                    delta -= penalty
                    hasFacetDislike = true
                }
                TasteFacetType.EXPLICIT_LIKE.name -> {
                    val boost = (if (isGenre) 3.4 else 1.6) * facet.score.coerceIn(0.2, 1.0) * confidenceWeight
                    delta += boost
                    positiveSignalScore += boost
                    likedMatches += matchedLabel
                    matchedTags += matchedLabel
                }
                TasteFacetType.ENDURING_INTEREST.name -> {
                    val enduringRecency = 0.55 + 0.45 * recency
                    val boost = (if (isGenre) 2.5 else 0.9) * facet.score.coerceIn(0.1, 1.0) * confidenceWeight * behavioralMult * enduringRecency
                    delta += boost
                    positiveSignalScore += boost
                    if (isGenre) enduringMatches += matchedLabel
                    matchedTags += matchedLabel
                }
                TasteFacetType.SHORT_TERM_INTENT.name -> {
                    val boost = (if (isGenre) 1.55 else 0.55) * facet.score.coerceIn(0.1, 1.0) * confidenceWeight * recency
                    delta += boost
                    positiveSignalScore += boost
                    if (isGenre) enduringMatches += matchedLabel
                    matchedTags += matchedLabel
                }
                else -> {
                    val boost = (if (isGenre) 1.8 else 0.7) * facet.score.coerceIn(0.1, 1.0) * confidenceWeight * recency
                    delta += boost
                    positiveSignalScore += boost
                    if (isGenre) enduringMatches += matchedLabel
                    matchedTags += matchedLabel
                }
            }
        }

        // Rich content understanding alignment from behavioral patterns (themes, tones, subgenres, settings, structure, region, franchise)
        if (behaviorVector.behavioralEvidenceStrength > 0.0) {
            var contentDelta = 0.0
            candidateProfile.themes.forEach { t -> contentDelta += (behaviorVector.themeWeights[t] ?: 0.0).coerceAtMost(2.5) * 1.15 }
            candidateProfile.subgenres.forEach { s -> contentDelta += (behaviorVector.subgenreWeights[s] ?: 0.0).coerceAtMost(2.5) * 1.10 }
            candidateProfile.tones.forEach { t -> contentDelta += (behaviorVector.toneWeights[t] ?: 0.0).coerceAtMost(2.0) * 0.70 }
            candidateProfile.settings.forEach { s -> contentDelta += (behaviorVector.settingWeights[s] ?: 0.0).coerceAtMost(1.8) * 0.65 }
            candidateProfile.narrativeStructures.forEach { s -> contentDelta += (behaviorVector.structureWeights[s] ?: 0.0).coerceAtMost(1.8) * 0.65 }
            candidateProfile.regionCluster?.let { r -> contentDelta += (behaviorVector.regionWeights[r] ?: 0.0).coerceAtMost(1.5) * 0.60 }
            candidateProfile.franchiseStem?.let { f -> contentDelta += (behaviorVector.franchiseWeights[f] ?: 0.0).coerceAtMost(2.0) * 1.25 }

            val cappedContentBoost = (contentDelta * (0.60 + 0.40 * candidateProfile.metadataQuality)).coerceAtMost(6.5)
            delta += cappedContentBoost
            positiveSignalScore += cappedContentBoost

            // Cautious attribute nudge from previously disliked titles (never bans whole genre on 1 dislike)
            var softCautionPenalty = 0.0
            candidateProfile.themes.forEach { t -> softCautionPenalty += (behaviorVector.dislikedThemeNudges[t] ?: 0.0) }
            candidateProfile.subgenres.forEach { s -> softCautionPenalty += (behaviorVector.dislikedSubgenreNudges[s] ?: 0.0) }
            candidateProfile.normalizedGenres.forEach { g -> softCautionPenalty += (behaviorVector.dislikedGenreNudges[g] ?: 0.0) }
            delta -= softCautionPenalty.coerceAtMost(2.2)
        }

        if (candidate.stableMediaKey in behaviorVector.demotedTitleKeys ||
            candidate.compositeId in behaviorVector.demotedTitleKeys
        ) {
            delta -= 2.0
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
                val cueBoost = (matchedCueGenres.size * 1.5 * cue.strength.coerceIn(0.2, 1.2))
                delta += cueBoost
                positiveSignalScore += cueBoost
                mediaCueMatches.addAll(matchedCueGenres)
                matchedTags.addAll(matchedCueGenres)
            }
        }

        val exposures = profile.sessionExposureCounts[candidate.stableMediaKey] ?: 0
        if (exposures > 0) {
            delta -= minOf(exposures, 5) * 1.25
        }

        val distinctTags = matchedTags.distinct().take(3)
        val rationale = when {
            likedMatches.isNotEmpty() ->
                "Matches your ${likedMatches.distinct().take(2).joinToString(" & ")} favorites"
            mediaCueMatches.isNotEmpty() ->
                "From your saved media links"
            enduringMatches.isNotEmpty() ->
                "Matches your ${enduringMatches.distinct().take(2).joinToString(" & ")} taste"
            distinctTags.isNotEmpty() ->
                "Fits your recent interest in ${distinctTags.first()}"
            else -> null
        }

        return CandidateProfileScore(
            delta = delta,
            positiveSignalScore = positiveSignalScore,
            hasTitleDislike = explicitReaction == TitleReaction.DISLIKE || (hasFacetDislike && delta < -1.5),
            matchedTags = distinctTags,
            rationale = rationale
        )
    }

    internal fun rerankWithMmr(
        scored: List<Pair<MovieTitleSummary, Double>>,
        limit: Int,
        lambda: Double = 0.65,
        analyzedProfiles: Map<String, MediaContentProfile> = emptyMap()
    ): List<Pair<MovieTitleSummary, Double>> {
        if (limit <= 0 || scored.isEmpty()) return emptyList()
        if (scored.size <= 2 || limit == 1) return scored.sortedByDescending { it.second }.take(limit)

        val profilesByKey = HashMap<String, MediaContentProfile>(scored.size).apply {
            for ((item, _) in scored) {
                put(item.stableMediaKey, analyzedProfiles[item.stableMediaKey] ?: MediaContentAnalyzer.analyze(item))
            }
        }

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
                val candProf = profilesByKey.getValue(candidate.stableMediaKey)
                val normRel = (rawScore - baselineMin) / range
                val maxSim = selected.maxOf { (chosen, _) ->
                    val chosenProf = profilesByKey.getValue(chosen.stableMediaKey)
                    val contentSim = MediaContentAnalyzer.computeContentSimilarity(candProf, chosenProf).score
                    val genreUnion = (candProf.normalizedGenres + chosenProf.normalizedGenres).size
                    val genreJaccard = if (genreUnion > 0) {
                        candProf.normalizedGenres.intersect(chosenProf.normalizedGenres).size.toDouble() / genreUnion
                    } else 0.0
                    val kindTie = if (candidate.kind == chosen.kind) 0.10 else 0.0
                    maxOf(contentSim * 0.90 + kindTie, genreJaccard * 0.70 + kindTie).coerceIn(0.0, 1.0)
                }
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

        val activePositiveFacets = profile.facets.filter { facet ->
            !facet.isSuppressedByUser &&
                facet.facetType != TasteFacetType.EXPLICIT_DISLIKE.name &&
                (facet.surfaceScope == IntelligenceSurfaceScope.MOVIES.name ||
                    facet.surfaceScope == IntelligenceSurfaceScope.ALL.name) &&
                (facet.expiresAtMs == null || facet.expiresAtMs > profile.nowMs) &&
                facet.score > 0.15
        }
        val activeBehavioralWatches = watchHistory.count { !it.isTrailer && !it.isHiddenTitle && (it.isCompleted || it.positionMs > 0L) }
        val activeLikes = profile.titleFeedback.count { it.reaction == TitleReaction.LIKE.name }
        val hasMediaCues = profile.recentMediaCues.any {
            it.isMediaCue && it.strength > 0.0 && it.surfaceScope != IntelligenceSurfaceScope.LIBRARY.name
        }

        // Evidence sufficiency guard: don't build Picked For You out of thin air or 1 search keystroke
        val hasSufficientEvidence = activeLikes > 0 ||
            activeBehavioralWatches > 0 ||
            watchlist.size >= 2 ||
            hasMediaCues ||
            activePositiveFacets.any { it.hasBehavioralEvidence && it.confidence >= 0.45 } ||
            activePositiveFacets.count { it.score >= 0.30 } >= 2

        if (!hasSufficientEvidence) return emptyList()

        val watchedKeys = watchHistory
            .filterNot { it.isTrailer }
            .map { it.stableMediaKey }
            .toSet()
        val watchlistKeys = watchlist.map { it.stableMediaKey }.toSet()

        val summaryLookup = linkedMapOf<String, MovieTitleSummary>().apply {
            watchlist.forEach {
                putIfAbsent(it.stableMediaKey, it)
                putIfAbsent(it.compositeId, it)
            }
            candidatePool.forEach {
                putIfAbsent(it.stableMediaKey, it)
                putIfAbsent(it.compositeId, it)
            }
        }
        val behaviorVector = buildBehavioralTasteVector(profile, watchHistory, watchlist, summaryLookup)

        val uniquePool = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
            candidatePool.filter { item ->
                item.stableMediaKey !in hiddenMediaKeys &&
                    item.stableMediaKey !in watchedKeys &&
                    profile.reactionFor(item) != TitleReaction.DISLIKE
            }
        )
        if (uniquePool.isEmpty()) return emptyList()

        val analyzedProfiles = HashMap<String, MediaContentProfile>(uniquePool.size)
        val detailByKey = linkedMapOf<String, CandidateProfileScore>()
        val scored = uniquePool.mapNotNull { candidate ->
            val contentProf = MediaContentAnalyzer.analyze(candidate)
            analyzedProfiles[candidate.stableMediaKey] = contentProf
            val pScore = scoreCandidateWithProfile(candidate, profile, behaviorVector, contentProf)
            if (pScore.hasTitleDislike || pScore.positiveSignalScore < MIN_FOR_YOU_RELEVANCE) {
                return@mapNotNull null
            }
            detailByKey[candidate.stableMediaKey] = pScore
            var total = pScore.delta
            total += (candidate.rating ?: 5.5) * 0.30
            if (candidate.isPlayableHint) total += 0.75
            if (candidate.stableMediaKey in watchlistKeys) total += 0.55
            if (contentProf.metadataQuality < 0.35) total -= 0.65
            candidate to total
        }

        if (scored.isEmpty()) return emptyList()

        return rerankWithMmr(
            scored = scored,
            limit = maxItems,
            lambda = 0.66,
            analyzedProfiles = analyzedProfiles
        ).map { (item, score) ->
            val detail = detailByKey[item.stableMediaKey]
            PersonalizedTitleRecommendation(
                item = item,
                score = score,
                rationale = detail?.rationale ?: "Tailored to your viewing activity"
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
            summaryByKey[v.compositeId] = v
        }

        val watchedKeys = watchHistory
            .filterNot { it.isTrailer }
            .map { it.stableMediaKey }
            .toSet()

        // Prefer strongly enjoyed / completed / rewatched titles; never anchor on disliked or hidden titles
        val anchorHistory = watchHistory
            .asSequence()
            .filter {
                !it.isTrailer &&
                    !it.isHiddenTitle &&
                    it.stableMediaKey !in hiddenMediaKeys &&
                    profile.reactionForKey(it.stableMediaKey, it.compositeId) != TitleReaction.DISLIKE
            }
            .sortedWith(
                compareByDescending<WatchHistoryItem> { profile.reactionForKey(it.stableMediaKey, it.compositeId) == TitleReaction.LIKE }
                    .thenByDescending { it.rewatchCount > 0 }
                    .thenByDescending { it.isCompleted }
                    .thenByDescending { it.lastWatchedAt }
            )
            .distinctBy { it.stableMediaKey }
            .toList()

        if (anchorHistory.isEmpty()) return emptyList()

        val behaviorVector = buildBehavioralTasteVector(profile, watchHistory, watchlist, summaryByKey)

        val uniquePool = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
            candidatePool.filter { item ->
                item.stableMediaKey !in hiddenMediaKeys &&
                    profile.reactionFor(item) != TitleReaction.DISLIKE
            }
        )

        val analyzedPool = HashMap<String, MediaContentProfile>(uniquePool.size)
        val usedCandidateKeys = mutableSetOf<String>()
        val groups = mutableListOf<BecauseYouWatchedGroup>()
        val usePersonalization = profile.hasActiveSignals

        for (anchor in anchorHistory) {
            if (groups.size >= maxGroups) break
            val anchorSummary = summaryByKey[anchor.stableMediaKey]
                ?: anchor.canonicalId?.let { summaryByKey[it] }
                ?: summaryByKey[anchor.compositeId]
                ?: continue

            val anchorProfile = MediaContentAnalyzer.analyze(anchorSummary)
            if (anchorProfile.metadataQuality < 0.25 && anchorSummary.genres.isEmpty()) {
                continue
            }

            val profileDetails = linkedMapOf<String, CandidateProfileScore>()
            val contentReasons = linkedMapOf<String, List<String>>()
            val scoredPairs = uniquePool.mapNotNull { candidate ->
                val cKey = candidate.stableMediaKey
                if (cKey == anchor.stableMediaKey || cKey in watchedKeys || cKey in usedCandidateKeys) {
                    return@mapNotNull null
                }
                val candidateProfile = analyzedPool.getOrPut(cKey) { MediaContentAnalyzer.analyze(candidate) }
                val sim = MediaContentAnalyzer.computeContentSimilarity(anchorProfile, candidateProfile)
                if (!sim.hasMeaningfulSignal) {
                    return@mapNotNull null
                }

                // Content similarity is the foundation (0..1 -> 0..10), not MediaKind + generic genre
                var score = sim.score * 10.0
                score += (candidate.rating ?: 5.5) * 0.14
                if (usePersonalization) {
                    val pScore = scoreCandidateWithProfile(candidate, profile, behaviorVector, candidateProfile)
                    profileDetails[cKey] = pScore
                    if (!pScore.hasTitleDislike) {
                        score += pScore.delta * 0.45
                    } else {
                        return@mapNotNull null
                    }
                }
                contentReasons[cKey] = sim.sharedReasons
                candidate to score
            }

            val finalPairs = rerankWithMmr(
                scored = scoredPairs,
                limit = maxItemsPerGroup,
                lambda = 0.72,
                analyzedProfiles = analyzedPool
            )
            val scored = finalPairs.map { it.first }

            if (scored.isNotEmpty()) {
                scored.forEach { usedCandidateKeys.add(it.stableMediaKey) }
                val itemRationales = scored.associate { item ->
                    val reasons = contentReasons[item.stableMediaKey].orEmpty()
                    val text = if (reasons.isNotEmpty()) {
                        "Shares ${reasons.joinToString(" & ").lowercase(Locale.US)}"
                    } else {
                        "Similar storytelling to ${anchor.title}"
                    }
                    item.stableMediaKey to text
                }
                val topReason = scored.firstNotNullOfOrNull { contentReasons[it.stableMediaKey]?.firstOrNull() }
                groups.add(
                    BecauseYouWatchedGroup(
                        anchorTitle = anchor.title,
                        anchorKind = anchor.kind,
                        items = scored,
                        rationale = topReason?.let { "Matched on $it" } ?: "Matched by story themes and tone",
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
        val anchorProfile = MediaContentAnalyzer.analyze(anchor)
        val relatedKeys = relatedTitles.map { it.stableMediaKey }.toSet()
        val usePersonalization = profile.hasActiveSignals
        val summaryLookup = (relatedTitles + candidatePool + anchor).associateBy { it.stableMediaKey }
        val behaviorVector = buildBehavioralTasteVector(profile, emptyList(), emptyList(), summaryLookup)

        val mergedPool = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
            (relatedTitles + candidatePool)
                .filter {
                    it.stableMediaKey != anchorKey &&
                        it.stableMediaKey !in hiddenMediaKeys &&
                        profile.reactionFor(it) != TitleReaction.DISLIKE
                }
        )

        val analyzedPool = HashMap<String, MediaContentProfile>(mergedPool.size)
        val scoredPairs = mergedPool.mapNotNull { candidate ->
            val isExplicitRelated = candidate.stableMediaKey in relatedKeys
            val candProfile = analyzedPool.getOrPut(candidate.stableMediaKey) { MediaContentAnalyzer.analyze(candidate) }
            val sim = MediaContentAnalyzer.computeContentSimilarity(anchorProfile, candProfile)
            if (!isExplicitRelated && !sim.hasMeaningfulSignal) {
                return@mapNotNull null
            }
            var score = (if (isExplicitRelated) 2.2 else 0.0) + (sim.score * 9.5)
            val y1 = anchor.releaseYear
            val y2 = candidate.releaseYear
            if (y1 != null && y2 != null && abs(y1 - y2) <= 6) {
                score += 0.6
            }
            score += (candidate.rating ?: 5.5) * 0.12
            if (usePersonalization) {
                score += scoreCandidateWithProfile(candidate, profile, behaviorVector, candProfile).delta * 0.45
            }
            if (score < 2.2 && !isExplicitRelated) return@mapNotNull null
            candidate to score
        }

        return rerankWithMmr(
            scored = scoredPairs,
            limit = limit,
            lambda = 0.72,
            analyzedProfiles = analyzedPool
        ).map { it.first }
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
                        profile.reactionFor(it) != TitleReaction.DISLIKE &&
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

        val summaryByKey = combinedPool.associateBy { it.stableMediaKey }
        val behaviorVector = buildBehavioralTasteVector(profile, watchHistory, watchlist, summaryByKey)

        // Identify dominant genres so Surprise Me rewards refreshing serendipity instead of repeating the same top 1 genre
        val genreCounts = linkedMapOf<String, Int>()
        for (h in watchHistory.filter { !it.isTrailer && !it.isHiddenTitle }) {
            summaryByKey[h.stableMediaKey]?.genres?.forEach { g ->
                val clean = g.trim().lowercase(Locale.US)
                if (clean.isNotBlank()) genreCounts[clean] = (genreCounts[clean] ?: 0) + 2
            }
        }
        val dominantGenre = genreCounts.maxByOrNull { it.value }?.key
        val topGenres = genreCounts.entries.sortedByDescending { it.value }.take(3).map { it.key }.toSet()

        val unwatchedPool = combinedPool.filter { it.stableMediaKey !in completedOrWatchedKeys }
        val activePool = unwatchedPool.ifEmpty { combinedPool }
        val freshPool = activePool.filter { it.stableMediaKey !in recentKeys }.ifEmpty { activePool }

        val usePersonalization = profile.hasActiveSignals
        val details = linkedMapOf<String, CandidateProfileScore>()
        val rawScored = freshPool.map { item ->
            val candProfile = MediaContentAnalyzer.analyze(item)
            var score = (item.rating ?: 6.0) * 1.15
            if (item.isPlayableHint) score += 1.6
            if (item.stableMediaKey in watchlistKeys) score += 0.9

            val lowerGenres = item.genres.map { it.trim().lowercase(Locale.US) }.toSet()
            // Serendipity: gentle boost for adjacent or fresh themes rather than pure repetition
            val isFreshAngle = candProfile.themes.any { it !in behaviorVector.themeWeights } ||
                (dominantGenre != null && dominantGenre !in lowerGenres)
            if (isFreshAngle && (item.rating ?: 0.0) >= 6.8) {
                score += 1.55
            }

            if (usePersonalization) {
                val pScore = scoreCandidateWithProfile(item, profile, behaviorVector, candProfile)
                details[item.stableMediaKey] = pScore
                // Mild EI influence so Surprise Me never degenerates into Picked For You
                score += (pScore.delta * 0.40)
            }
            Triple(item, score, details[item.stableMediaKey]?.hasTitleDislike == true)
        }

        val nonDisliked = rawScored.filterNot { it.third }.ifEmpty { rawScored }
        val scored = nonDisliked.map { it.first to it.second }.sortedByDescending { it.second }

        val topWindow = scored.take(minOf(10, scored.size)).map { it.first }
        val chosenIndex = Random(seed).nextInt(topWindow.size)
        val pick = topWindow[chosenIndex]

        val pDetail = details[pick.stableMediaKey]
        val matchedGenres = pick.genres.filter { it.trim().lowercase(Locale.US) in topGenres }.take(2)
        val rationale = when {
            pDetail?.matchedTags?.isNotEmpty() == true && (dominantGenre == null || dominantGenre !in pick.genres.map { it.lowercase(Locale.US) }) ->
                "A fresh angle aligned with your taste"
            pDetail?.rationale != null -> pDetail.rationale
            matchedGenres.isNotEmpty() ->
                "Fresh ${matchedGenres.joinToString(" & ")} pick aligned with what you watch"
            pick.stableMediaKey in watchlistKeys ->
                "Waiting in your watchlist"
            pick.rating != null && pick.rating >= 7.5 ->
                "Highly rated ${pick.kind.label.lowercase(Locale.US)} (${String.format(Locale.US, "%.1f", pick.rating)})"
            else ->
                "Fresh ${pick.kind.label.lowercase(Locale.US)} pick worth exploring"
        }

        val signalTags = buildList {
            pDetail?.matchedTags?.forEach { add(it) }
            matchedGenres.forEach { g -> if (g !in this) add(g) }
        }.take(3)

        return SurprisePickResult(pick = pick, rationale = rationale, signalTags = signalTags)
    }

    /**
     * Recently Added stays primary-sorted by verified recency (`maxOf(addedAtMs, releaseDate)`),
     * applying only a subtle EI tie-break inside narrow ~14-day recency bands so fresh additions
     * remain fresh while subtly surfacing items closer to the user's tastes within the same window.
     */
    fun selectRecentlyAdded(
        items: List<MovieTitleSummary>,
        hiddenMediaKeys: Set<String> = emptySet(),
        limit: Int = 15,
        profile: IntelligenceDiscoveryProfile = IntelligenceDiscoveryProfile()
    ): List<MovieTitleSummary> {
        val eligible = items.filter { item ->
            item.stableMediaKey !in hiddenMediaKeys &&
                profile.reactionFor(item) != TitleReaction.DISLIKE &&
                ((item.addedAtMs != null && item.addedAtMs > 0L) ||
                    parseSortableDateKey(item.releaseDate) > 0L)
        }
        val deduplicated = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(eligible)
        val useTieBreak = profile.hasActiveSignals
        return deduplicated
            .sortedWith { a, b ->
                val recA = maxOf(a.addedAtMs ?: 0L, parseSortableDateKey(a.releaseDate))
                val recB = maxOf(b.addedAtMs ?: 0L, parseSortableDateKey(b.releaseDate))
                val bucketA = recA / RECENTLY_ADDED_BUCKET_MS
                val bucketB = recB / RECENTLY_ADDED_BUCKET_MS
                if (bucketA != bucketB) {
                    return@sortedWith bucketB.compareTo(bucketA)
                }
                if (useTieBreak) {
                    val deltaA = scoreCandidateWithProfile(a, profile).delta.coerceIn(-1.5, 1.5)
                    val deltaB = scoreCandidateWithProfile(b, profile).delta.coerceIn(-1.5, 1.5)
                    val adjA = recA.toDouble() + (deltaA * 12L * 3600L * 1000L)
                    val adjB = recB.toDouble() + (deltaB * 12L * 3600L * 1000L)
                    val cmp = adjB.compareTo(adjA)
                    if (cmp != 0) return@sortedWith cmp
                }
                recB.compareTo(recA)
            }
            .take(limit)
    }

    /**
     * Global browse sections (`Trending`, `Top Rated`, `Popular Movies`, etc.) keep their global
     * canonical ordering untouched. Specific category/theme/regional sections (`Nollywood Movies`,
     * `Adult Animation`, `Epic Fantasy`, etc.) preserve strict category eligibility while applying
     * subtle EI ordering within eligible items.
     */
    fun personalizeBrowseSections(
        sections: List<BrowseSection>,
        hiddenMediaKeys: Set<String> = emptySet(),
        profile: IntelligenceDiscoveryProfile = IntelligenceDiscoveryProfile()
    ): List<BrowseSection> {
        if (sections.isEmpty()) return emptyList()
        val usePersonalization = profile.hasActiveSignals
        return sections.map { section ->
            val visibleItems = section.items.filter { it.stableMediaKey !in hiddenMediaKeys }
            if (!usePersonalization || isGlobalSection(section) || visibleItems.size <= 2) {
                section.copy(items = visibleItems)
            } else {
                // Light EI ordering strictly inside existing eligible section items
                val indexed = visibleItems.mapIndexed { idx, item ->
                    val reaction = profile.reactionFor(item)
                    val basePosWeight = (visibleItems.size - idx).toDouble() * 0.15
                    val eiDelta = when (reaction) {
                        TitleReaction.DISLIKE -> -12.0
                        TitleReaction.LIKE -> 3.0
                        null -> scoreCandidateWithProfile(item, profile).delta.coerceIn(-2.5, 2.5) * 0.45
                    }
                    item to (basePosWeight + eiDelta)
                }
                section.copy(items = indexed.sortedByDescending { it.second }.map { it.first })
            }
        }
    }

    internal fun isGlobalSection(section: BrowseSection): Boolean {
        val idLower = section.id.trim().lowercase(Locale.US)
        val titleLower = section.title.trim().lowercase(Locale.US)
        return GLOBAL_SECTION_KEYWORDS.any { kw -> kw in idLower || kw in titleLower }
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
