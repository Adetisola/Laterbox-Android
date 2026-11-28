package com.laterbox.app.intelligence

import com.laterbox.app.data.local.dao.IntelligenceDao
import com.laterbox.app.data.local.dao.SavedLinkDao
import com.laterbox.app.data.local.entity.IntelligenceSignalEntity
import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.SuggestionDismissalEntity
import com.laterbox.app.data.local.entity.SuggestionDismissalType
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.TitleFeedbackEntity
import com.laterbox.app.data.local.entity.TitleReaction
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.movies.data.local.dao.MoviesDao
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * Always-on, local-first User Understanding repository managing [UserTasteFacetEntity],
 * [IntelligenceSignalEntity], [TitleFeedbackEntity], and [SuggestionDismissalEntity].
 *
 * Strictly local-only: zero network calls, zero Firestore sync, zero backend user profiles.
 */
class UserUnderstandingRepository(
    private val intelligenceDao: IntelligenceDao,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val moviesDao: MoviesDao? = null,
    private val savedLinkDao: SavedLinkDao? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val writeMutex = Mutex()

    fun observeAllFacets(): Flow<List<UserTasteFacetEntity>> = intelligenceDao.observeAllFacets()

    fun observeSignalCount(): Flow<Int> = intelligenceDao.observeSignalCount()

    fun observeAllTitleFeedback(): Flow<List<TitleFeedbackEntity>> =
        intelligenceDao.observeAllTitleFeedback()

    fun observeTitleReaction(mediaKey: String, compositeId: String): Flow<TitleFeedbackEntity?> =
        intelligenceDao.observeTitleFeedback(mediaKey, compositeId)

    fun observeActiveDismissals(): Flow<List<SuggestionDismissalEntity>> =
        intelligenceDao.observeActiveDismissals(nowMs())

    suspend fun getActiveFacetsForMovies(now: Long = nowMs()): List<UserTasteFacetEntity> =
        withContext(Dispatchers.IO) {
            intelligenceDao.getActiveFacetsForScope(IntelligenceSurfaceScope.MOVIES.name, now)
        }

    suspend fun getRecentMediaCueSignals(
        windowMs: Long = MEDIA_CUE_WINDOW_MS,
        limit: Int = 30
    ): List<IntelligenceSignalEntity> = withContext(Dispatchers.IO) {
        val since = nowMs() - windowMs
        intelligenceDao.getRecentMediaCueSignals(sinceMs = since, limit = limit)
    }

    suspend fun getRecentSignals(limit: Int = 200): List<IntelligenceSignalEntity> =
        withContext(Dispatchers.IO) {
            intelligenceDao.getRecentSignalsSnapshot(limit)
        }

    /**
     * Silently bootstraps enduring taste facets from existing local Movies & Library items
     * if bootstrap hasn't run yet (always-on EI; no onboarding questionnaire).
     */
    suspend fun bootstrapFromExistingLibraryIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = userPreferencesRepository.userPreferencesFlow.first()
        if (prefs.personalizationBootstrapped) {
            // Also ensure any legacy search queries on upgraded installs are sanitized.
            runCatching { intelligenceDao.sanitizeLegacySearchSignals() }
            return@withContext
        }

        writeMutex.withLock {
            val latestPrefs = userPreferencesRepository.userPreferencesFlow.first()
            if (latestPrefs.personalizationBootstrapped) {
                return@withLock
            }
            runCatching { intelligenceDao.sanitizeLegacySearchSignals() }
            if (intelligenceDao.countFacets() > 0) {
                userPreferencesRepository.setPersonalizationBootstrapped(true)
                return@withLock
            }

            val ts = nowMs()
            val accumulated = linkedMapOf<String, Triple<ExtractedFacetCandidate, Double, Int>>()

            fun accumulate(candidate: ExtractedFacetCandidate, weight: Double) {
                val existing = accumulated[candidate.facetKey]
                val nextWeight = ((existing?.second ?: 0.0) + weight).coerceAtMost(1.0)
                val nextCount = (existing?.third ?: 0) + 1
                accumulated[candidate.facetKey] = Triple(candidate, nextWeight, nextCount)
            }

            moviesDao?.let { dao ->
                val watchlist = runCatching { dao.getWatchlistSnapshot() }.getOrDefault(emptyList())
                for (w in watchlist.take(40)) {
                    val kind = MediaKind.fromWire(w.kind)
                    w.genresCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { g ->
                        accumulate(
                            ExtractedFacetCandidate(
                                facetKey = TopicExtractor.genreFacetKey(g),
                                displayLabel = g,
                                surfaceScope = IntelligenceSurfaceScope.MOVIES,
                                isMediaCue = true
                            ),
                            weight = 0.20
                        )
                    }
                    accumulate(
                        ExtractedFacetCandidate(
                            facetKey = TopicExtractor.kindFacetKey(kind),
                            displayLabel = kind.label,
                            surfaceScope = IntelligenceSurfaceScope.MOVIES,
                            isMediaCue = true
                        ),
                        weight = 0.12
                    )
                }

                val history = runCatching { dao.getWatchHistorySnapshot(40) }.getOrDefault(emptyList())
                    .filter { !it.isTrailer && !it.isHiddenTitle }
                for (h in history) {
                    val kind = MediaKind.fromWire(h.kind)
                    val weight = if (h.isCompleted) 0.24 else 0.10
                    accumulate(
                        ExtractedFacetCandidate(
                            facetKey = TopicExtractor.kindFacetKey(kind),
                            displayLabel = kind.label,
                            surfaceScope = IntelligenceSurfaceScope.MOVIES,
                            isMediaCue = true
                        ),
                        weight = weight
                    )
                }
            }

            savedLinkDao?.let { linkDao ->
                val recentLinks = runCatching { linkDao.getRecentInboxLinksSnapshot(30) }.getOrDefault(emptyList())
                for (item in recentLinks) {
                    val topics = TopicExtractor.extractLibraryTopics(
                        url = item.link.originalUrl,
                        domain = item.link.domain,
                        title = item.link.title,
                        description = item.link.description
                    )
                    val boost = if (item.link.isRead) 0.18 else 0.10
                    for (t in topics) {
                        accumulate(t, boost)
                    }
                }
            }

            val entities = accumulated.values.map { (cand, score, count) ->
                val capByCount = when {
                    count >= 3 -> 0.85
                    count == 2 -> 0.72
                    else -> 0.56
                }
                val conf = (0.35 + score * 0.45).coerceIn(0.35, capByCount)
                val evidenceText = if (cand.surfaceScope == IntelligenceSurfaceScope.MOVIES) {
                    "From your Movies & TV activity"
                } else {
                    "From your saved links"
                }
                UserTasteFacetEntity(
                    facetKey = cand.facetKey,
                    facetType = TasteFacetType.ENDURING_INTEREST.name,
                    surfaceScope = cand.surfaceScope.name,
                    displayLabel = cand.displayLabel,
                    score = score.coerceIn(0.10, 1.0),
                    confidence = conf,
                    provenance = evidenceText,
                    evidenceSummary = evidenceText,
                    includeInAssistantContext = false,
                    isSuppressedByUser = false,
                    firstObservedAtMs = ts,
                    lastReinforcedAtMs = ts,
                    expiresAtMs = null,
                    hasBehavioralEvidence = true,
                    independentEvidenceCount = count.coerceAtLeast(1),
                    supportingSignalTypesCsv = "BOOTSTRAP"
                )
            }
            if (entities.isNotEmpty()) {
                intelligenceDao.upsertFacets(entities)
            }
            userPreferencesRepository.setPersonalizationBootstrapped(true)
        }
    }

    suspend fun reinforceFacet(
        candidate: ExtractedFacetCandidate,
        delta: Double,
        provenance: String,
        evidenceSummary: String,
        facetType: TasteFacetType = TasteFacetType.ENDURING_INTEREST,
        ttlMs: Long? = if (facetType == TasteFacetType.SHORT_TERM_INTENT) SHORT_TERM_TTL_MS else null,
        timestampMs: Long = nowMs(),
        signalType: String = "",
        signalQuality: Double = 0.18,
        isBehavioralEvidence: Boolean = (facetType != TasteFacetType.SHORT_TERM_INTENT)
    ) = withContext(Dispatchers.IO) {
        if (!TopicExtractor.isSafeFacetLabel(candidate.displayLabel)) return@withContext
        val cleanSignalType = signalType.trim().uppercase()
        val quality = signalQuality.coerceIn(0.02, 1.0)
        writeMutex.withLock {
            val existing = intelligenceDao.getFacetByKey(candidate.facetKey)
            if (existing != null) {
                if (existing.isSuppressedByUser) return@withLock
                if (existing.facetType == TasteFacetType.EXPLICIT_DISLIKE.name && delta > 0.0) {
                    // Passive or search signals must never override an explicit dislike.
                    return@withLock
                }

                val existingSignals = existing.supportingSignalTypesCsv
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toMutableSet()
                val isNewSignalType = cleanSignalType.isNotBlank() && cleanSignalType !in existingSignals
                if (cleanSignalType.isNotBlank()) {
                    existingSignals.add(cleanSignalType)
                }
                val timeSeparatedBehavioral = isBehavioralEvidence &&
                    (timestampMs - existing.lastReinforcedAtMs) >= INDEPENDENT_EVENT_GAP_MS
                val nextEvidenceCount = existing.independentEvidenceCount + when {
                    isNewSignalType && isBehavioralEvidence -> 1
                    timeSeparatedBehavioral -> 1
                    isNewSignalType && existing.independentEvidenceCount == 0 -> 1
                    else -> 0
                }
                val nextHasBehavioral = existing.hasBehavioralEvidence || isBehavioralEvidence

                val effectiveDelta = if (!isBehavioralEvidence) {
                    // Cap search-only score accumulation so repeated searches cannot dominate enduring tastes.
                    delta.coerceAtMost(0.14)
                } else {
                    delta
                }
                val updatedScore = when {
                    existing.facetType == TasteFacetType.EXPLICIT_LIKE.name ->
                        (existing.score + (effectiveDelta * 0.25)).coerceIn(0.25, 1.0)
                    !nextHasBehavioral ->
                        (existing.score + effectiveDelta).coerceIn(0.05, SEARCH_ONLY_MAX_SCORE)
                    else ->
                        (existing.score + effectiveDelta).coerceIn(0.05, 1.0)
                }

                val nextType = when {
                    existing.facetType == TasteFacetType.EXPLICIT_LIKE.name -> existing.facetType
                    existing.facetType == TasteFacetType.ENDURING_INTEREST.name -> existing.facetType
                    nextHasBehavioral && (nextEvidenceCount >= 2 || updatedScore >= 0.45) ->
                        TasteFacetType.ENDURING_INTEREST.name
                    else -> TasteFacetType.SHORT_TERM_INTENT.name
                }

                val updatedConfidence = computeUpdatedConfidence(
                    currentConfidence = existing.confidence,
                    signalQuality = quality,
                    isNewSignalType = isNewSignalType,
                    hasBehavioralEvidence = nextHasBehavioral,
                    independentEvidenceCount = nextEvidenceCount,
                    isExplicitLike = nextType == TasteFacetType.EXPLICIT_LIKE.name
                )

                val nextExpires = when {
                    nextType == TasteFacetType.ENDURING_INTEREST.name ||
                        nextType == TasteFacetType.EXPLICIT_LIKE.name -> null
                    ttlMs != null -> timestampMs + ttlMs
                    else -> existing.expiresAtMs ?: (timestampMs + SHORT_TERM_TTL_MS)
                }

                intelligenceDao.upsertFacet(
                    existing.copy(
                        facetType = nextType,
                        surfaceScope = resolveMergedScope(existing.surfaceScope, candidate.surfaceScope),
                        score = updatedScore,
                        confidence = updatedConfidence,
                        provenance = provenance.take(120),
                        evidenceSummary = evidenceSummary.take(140),
                        lastReinforcedAtMs = timestampMs,
                        expiresAtMs = nextExpires,
                        hasBehavioralEvidence = nextHasBehavioral,
                        independentEvidenceCount = nextEvidenceCount,
                        supportingSignalTypesCsv = existingSignals.take(12).joinToString(",")
                    )
                )
            } else if (delta > 0.0) {
                val initialType = if (!isBehavioralEvidence) {
                    TasteFacetType.SHORT_TERM_INTENT
                } else {
                    facetType
                }
                val initialScore = if (!isBehavioralEvidence) {
                    delta.coerceIn(0.08, SEARCH_ONLY_MAX_SCORE)
                } else {
                    delta.coerceIn(0.05, 1.0)
                }
                val expiresAt = if (initialType == TasteFacetType.SHORT_TERM_INTENT) {
                    timestampMs + (ttlMs ?: SHORT_TERM_TTL_MS)
                } else {
                    null
                }
                val initialConfidence = computeInitialConfidence(
                    signalQuality = quality,
                    initialScore = initialScore,
                    hasBehavioralEvidence = isBehavioralEvidence,
                    isExplicitLike = initialType == TasteFacetType.EXPLICIT_LIKE
                )
                intelligenceDao.upsertFacet(
                    UserTasteFacetEntity(
                        facetKey = candidate.facetKey,
                        facetType = initialType.name,
                        surfaceScope = candidate.surfaceScope.name,
                        displayLabel = candidate.displayLabel.take(48),
                        score = initialScore,
                        confidence = initialConfidence,
                        provenance = provenance.take(120),
                        evidenceSummary = evidenceSummary.take(140),
                        includeInAssistantContext = false,
                        isSuppressedByUser = false,
                        firstObservedAtMs = timestampMs,
                        lastReinforcedAtMs = timestampMs,
                        expiresAtMs = expiresAt,
                        hasBehavioralEvidence = isBehavioralEvidence,
                        independentEvidenceCount = 1,
                        supportingSignalTypesCsv = cleanSignalType
                    )
                )
            }
        }
    }

    /**
     * Cautiously adjusts an inferred facet downward without erasing the user's explicit likes
     * or falsely resetting the time-decay clock.
     */
    suspend fun penalizeFacet(
        facetKey: String,
        penalty: Double,
        @Suppress("UNUSED_PARAMETER") timestampMs: Long = nowMs()
    ) = withContext(Dispatchers.IO) {
        if (penalty <= 0.0) return@withContext
        writeMutex.withLock {
            val existing = intelligenceDao.getFacetByKey(facetKey) ?: return@withLock
            if (existing.facetType == TasteFacetType.EXPLICIT_LIKE.name ||
                existing.facetType == TasteFacetType.EXPLICIT_DISLIKE.name
            ) {
                return@withLock
            }
            val nextScore = (existing.score - penalty).coerceAtLeast(0.0)
            val nextConfidence = (existing.confidence - penalty * 0.30).coerceAtLeast(0.15)
            if (nextScore < MIN_RETAINED_SCORE && !existing.isSuppressedByUser) {
                intelligenceDao.deleteFacetByKey(facetKey)
            } else {
                intelligenceDao.upsertFacet(
                    existing.copy(
                        score = nextScore,
                        confidence = nextConfidence
                    )
                )
            }
        }
    }

    suspend fun setExplicitPreference(
        facetKey: String,
        displayLabel: String,
        surfaceScope: IntelligenceSurfaceScope,
        liked: Boolean,
        includeInAssistant: Boolean = true
    ) = withContext(Dispatchers.IO) {
        if (!TopicExtractor.isSafeFacetLabel(displayLabel)) return@withContext
        val ts = nowMs()
        writeMutex.withLock {
            val existing = intelligenceDao.getFacetByKey(facetKey)
            intelligenceDao.upsertFacet(
                UserTasteFacetEntity(
                    facetKey = facetKey,
                    facetType = if (liked) TasteFacetType.EXPLICIT_LIKE.name else TasteFacetType.EXPLICIT_DISLIKE.name,
                    surfaceScope = surfaceScope.name,
                    displayLabel = displayLabel.take(48),
                    score = if (liked) 0.95 else 0.90,
                    confidence = 1.0,
                    provenance = if (liked) "Liked" else "Disliked",
                    evidenceSummary = if (liked) "Explicit positive signal" else "Explicit negative signal",
                    includeInAssistantContext = includeInAssistant && liked,
                    isSuppressedByUser = false,
                    firstObservedAtMs = existing?.firstObservedAtMs ?: ts,
                    lastReinforcedAtMs = ts,
                    expiresAtMs = null,
                    hasBehavioralEvidence = true,
                    independentEvidenceCount = (existing?.independentEvidenceCount ?: 0).coerceAtLeast(1),
                    supportingSignalTypesCsv = "EXPLICIT_FEEDBACK"
                )
            )
        }
    }

    suspend fun setTitleReaction(
        item: MovieTitleSummary,
        reaction: TitleReaction?,
        timestampMs: Long = nowMs()
    ) = withContext(Dispatchers.IO) {
        val mediaKey = item.stableMediaKey
        writeMutex.withLock {
            if (reaction == null) {
                intelligenceDao.deleteTitleFeedback(mediaKey, item.compositeId)
            } else {
                intelligenceDao.upsertTitleFeedback(
                    TitleFeedbackEntity(
                        mediaKey = mediaKey,
                        compositeId = item.compositeId,
                        providerId = item.providerId,
                        titleId = item.id,
                        canonicalId = item.canonicalId,
                        kind = item.kind.wireValue,
                        title = item.title.take(100),
                        reaction = reaction.name,
                        genresCsv = item.genres.joinToString(","),
                        updatedAtMs = timestampMs
                    )
                )
            }
        }
    }

    suspend fun getTitleReaction(mediaKey: String, compositeId: String): TitleReaction? =
        withContext(Dispatchers.IO) {
            val entity = intelligenceDao.getTitleFeedback(mediaKey, compositeId) ?: return@withContext null
            runCatching { TitleReaction.valueOf(entity.reaction) }.getOrNull()
        }

    suspend fun getAllTitleFeedbackSnapshot(): List<TitleFeedbackEntity> =
        withContext(Dispatchers.IO) {
            intelligenceDao.getAllTitleFeedbackSnapshot()
        }

    suspend fun recordSuggestionDismissal(
        type: SuggestionDismissalType,
        linkId: Long,
        normalizedUrl: String,
        timestampMs: Long = nowMs()
    ) = withContext(Dispatchers.IO) {
        val linkHash = TopicExtractor.stableLinkIdentityHash(normalizedUrl)
        val dismissalKey = "${type.name}:$linkHash"
        val suppressUntil = when (type) {
            SuggestionDismissalType.PICK_BACK_UP -> timestampMs + PICK_BACK_UP_COOLDOWN_MS
            SuggestionDismissalType.COLLECTION_SUGGESTION -> null
        }
        writeMutex.withLock {
            intelligenceDao.upsertDismissal(
                SuggestionDismissalEntity(
                    dismissalKey = dismissalKey,
                    suggestionType = type.name,
                    linkId = linkId,
                    linkIdentityHash = linkHash,
                    dismissedAtMs = timestampMs,
                    suppressUntilMs = suppressUntil
                )
            )
        }
    }

    suspend fun clearSuggestionDismissal(
        type: SuggestionDismissalType,
        normalizedUrl: String
    ) = withContext(Dispatchers.IO) {
        val linkHash = TopicExtractor.stableLinkIdentityHash(normalizedUrl)
        writeMutex.withLock {
            intelligenceDao.deleteDismissalByKey("${type.name}:$linkHash")
        }
    }

    /**
     * Daily maintenance invoked by [com.laterbox.app.storage.StorageMaintenanceWorker]
     * and catch-up scheduling:
     * - Sanitizes any legacy search records containing raw queries
     * - Deletes expired SHORT_TERM_INTENT facets and expired PICK_BACK_UP dismissals
     * - Decays unreinforced non-explicit facets (both score and confidence) and removes rows < MIN_RETAINED_SCORE
     * - Prunes intelligence_signals to <= 30 days and <= 500 rows
     */
    suspend fun runDailyMaintenance(currentMs: Long = nowMs()): MaintenanceStats =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                intelligenceDao.sanitizeLegacySearchSignals()
                val expiredRemoved = intelligenceDao.deleteExpiredFacets(currentMs)
                intelligenceDao.deleteExpiredDismissals(currentMs)

                val allFacets = intelligenceDao.getAllFacetsSnapshot()
                var decayedRemoved = 0
                var decayedUpdated = 0

                for (facet in allFacets) {
                    if (facet.isSuppressedByUser) continue
                    if (facet.facetType == TasteFacetType.EXPLICIT_LIKE.name ||
                        facet.facetType == TasteFacetType.EXPLICIT_DISLIKE.name
                    ) {
                        continue
                    }
                    // Ensure search-only facets never sit around without an expiration.
                    if (!facet.hasBehavioralEvidence && facet.expiresAtMs == null) {
                        intelligenceDao.upsertFacet(
                            facet.copy(
                                facetType = TasteFacetType.SHORT_TERM_INTENT.name,
                                expiresAtMs = facet.lastReinforcedAtMs + SHORT_TERM_TTL_MS
                            )
                        )
                    }
                    val ageMs = (currentMs - facet.lastReinforcedAtMs).coerceAtLeast(0L)
                    if (ageMs < DAY_MS) continue
                    val halfLifeDays = when (facet.facetType) {
                        TasteFacetType.SHORT_TERM_INTENT.name -> 7.0
                        TasteFacetType.ACTIVE_GOAL.name -> 21.0
                        else -> 30.0
                    }
                    val ageDays = ageMs.toDouble() / DAY_MS.toDouble()
                    val decayFactor = 0.5.pow(ageDays / halfLifeDays)
                    val newScore = facet.score * decayFactor
                    val newConfidence = (facet.confidence * (0.65 + 0.35 * decayFactor)).coerceIn(0.20, 0.99)
                    if (newScore < MIN_RETAINED_SCORE) {
                        intelligenceDao.deleteFacetByKey(facet.facetKey)
                        decayedRemoved++
                    } else if (newScore < facet.score - 0.005 || newConfidence < facet.confidence - 0.005) {
                        intelligenceDao.upsertFacet(
                            facet.copy(
                                score = newScore,
                                confidence = newConfidence
                            )
                        )
                        decayedUpdated++
                    }
                }

                val cutoffMs = currentMs - SIGNAL_MAX_AGE_MS
                val oldSignalsRemoved = intelligenceDao.deleteSignalsOlderThan(cutoffMs)
                val trimmedSignals = intelligenceDao.trimSignalsToMaxRows(SIGNAL_MAX_ROWS)

                MaintenanceStats(
                    expiredFacetsRemoved = expiredRemoved,
                    decayedFacetsRemoved = decayedRemoved,
                    decayedFacetsUpdated = decayedUpdated,
                    prunedSignalsCount = oldSignalsRemoved + trimmedSignals
                )
            }
        }

    /**
     * Compact, privacy-safe taste summary for Laterbox Assistant.
     * Returns empty string unless the user explicitly enabled assistantPersonalContextEnabled.
     * Never exposes raw collection titles, raw searches, or sensitive categories.
     */
    suspend fun buildPrivacySafeAssistantSummary(now: Long = nowMs()): String =
        withContext(Dispatchers.IO) {
            val prefs = userPreferencesRepository.userPreferencesFlow.first()
            if (!prefs.assistantPersonalContextEnabled) {
                return@withContext ""
            }
            val eligible = intelligenceDao.getAssistantEligibleFacets(limit = 8, nowMs = now)
                .filter { TopicExtractor.isSafeFacetLabel(it.displayLabel) }
            val feedback = intelligenceDao.getAllTitleFeedbackSnapshot()
            val likedTitles = feedback
                .filter { it.reaction == TitleReaction.LIKE.name && TopicExtractor.isSafeFacetLabel(it.title) }
                .take(4)
                .map { it.title.take(40) }
            val dislikedTitles = feedback
                .filter { it.reaction == TitleReaction.DISLIKE.name && TopicExtractor.isSafeFacetLabel(it.title) }
                .take(2)
                .map { it.title.take(40) }

            if (eligible.isEmpty() && likedTitles.isEmpty() && dislikedTitles.isEmpty()) {
                return@withContext ""
            }

            val likes = eligible
                .filter { it.facetType != TasteFacetType.EXPLICIT_DISLIKE.name }
                .take(6)
                .map { "${it.displayLabel} (${it.surfaceScope.lowercase()})" }
            val dislikes = eligible
                .filter { it.facetType == TasteFacetType.EXPLICIT_DISLIKE.name }
                .take(3)
                .map { it.displayLabel }

            buildString {
                if (likes.isNotEmpty()) {
                    append("Preferred topics & genres: ${likes.joinToString(", ")}")
                }
                if (likedTitles.isNotEmpty()) {
                    if (isNotEmpty()) append(" | ")
                    append("Liked titles: ${likedTitles.joinToString(", ")}")
                }
                if (dislikes.isNotEmpty()) {
                    if (isNotEmpty()) append(" | ")
                    append("Avoided genres: ${dislikes.joinToString(", ")}")
                }
                if (dislikedTitles.isNotEmpty()) {
                    if (isNotEmpty()) append(" | ")
                    append("Disliked titles: ${dislikedTitles.joinToString(", ")}")
                }
            }
        }

    private fun resolveMergedScope(existingScope: String, incomingScope: IntelligenceSurfaceScope): String {
        if (existingScope == incomingScope.name) return existingScope
        // Never silently widen LIBRARY facets into MOVIES/ALL from generic topics
        if (existingScope == IntelligenceSurfaceScope.LIBRARY.name || incomingScope == IntelligenceSurfaceScope.LIBRARY) {
            return existingScope
        }
        return existingScope
    }

    private fun computeInitialConfidence(
        signalQuality: Double,
        initialScore: Double,
        hasBehavioralEvidence: Boolean,
        isExplicitLike: Boolean
    ): Double {
        if (isExplicitLike) return 1.0
        val raw = 0.22 + signalQuality * 0.30 + initialScore * 0.14
        val cap = if (hasBehavioralEvidence) SINGLE_SOURCE_CONFIDENCE_CAP else SEARCH_ONLY_CONFIDENCE_CAP
        return raw.coerceIn(0.22, cap)
    }

    private fun computeUpdatedConfidence(
        currentConfidence: Double,
        signalQuality: Double,
        isNewSignalType: Boolean,
        hasBehavioralEvidence: Boolean,
        independentEvidenceCount: Int,
        isExplicitLike: Boolean
    ): Double {
        if (isExplicitLike) return 1.0
        val diversityBonus = if (isNewSignalType && hasBehavioralEvidence) 0.08 else 0.0
        val gain = signalQuality * (1.0 - currentConfidence) * 0.40 + diversityBonus
        val cap = when {
            !hasBehavioralEvidence -> SEARCH_ONLY_CONFIDENCE_CAP
            independentEvidenceCount <= 1 -> SINGLE_SOURCE_CONFIDENCE_CAP
            independentEvidenceCount == 2 -> TWO_SOURCE_CONFIDENCE_CAP
            else -> 0.97
        }
        return (currentConfidence + gain).coerceIn(0.22, cap)
    }

    data class MaintenanceStats(
        val expiredFacetsRemoved: Int,
        val decayedFacetsRemoved: Int,
        val decayedFacetsUpdated: Int,
        val prunedSignalsCount: Int
    )

    companion object {
        const val SIGNAL_MAX_ROWS = 500
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val SIGNAL_MAX_AGE_MS = 30L * DAY_MS
        const val SHORT_TERM_TTL_MS = 14L * DAY_MS
        const val MEDIA_CUE_WINDOW_MS = 14L * DAY_MS
        const val PICK_BACK_UP_COOLDOWN_MS = 7L * DAY_MS
        const val INDEPENDENT_EVENT_GAP_MS = 60L * 60L * 1000L
        const val MIN_RETAINED_SCORE = 0.05
        const val SEARCH_ONLY_MAX_SCORE = 0.42
        const val SEARCH_ONLY_CONFIDENCE_CAP = 0.55
        const val SINGLE_SOURCE_CONFIDENCE_CAP = 0.62
        const val TWO_SOURCE_CONFIDENCE_CAP = 0.80
    }
}
