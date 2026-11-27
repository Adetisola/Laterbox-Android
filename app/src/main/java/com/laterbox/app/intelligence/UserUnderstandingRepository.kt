package com.laterbox.app.intelligence

import com.laterbox.app.data.local.dao.IntelligenceDao
import com.laterbox.app.data.local.dao.SavedLinkDao
import com.laterbox.app.data.local.entity.IntelligenceSignalEntity
import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.movies.data.local.dao.MoviesDao
import com.laterbox.app.movies.domain.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * On-device User Understanding repository managing [UserTasteFacetEntity] and
 * [IntelligenceSignalEntity].
 *
 * Strictly local-only pre-accounts: zero network calls, zero Firestore sync.
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

    /**
     * Silently bootstraps enduring taste facets from existing local Movies & Library items
     * if personalization is enabled and bootstrap hasn't run yet (no onboarding survey needed).
     */
    suspend fun bootstrapFromExistingLibraryIfNeeded() = withContext(Dispatchers.IO) {
        val prefs = userPreferencesRepository.userPreferencesFlow.first()
        if (!prefs.personalizationEnabled || prefs.personalizationBootstrapped) return@withContext

        writeMutex.withLock {
            val latestPrefs = userPreferencesRepository.userPreferencesFlow.first()
            if (!latestPrefs.personalizationEnabled || latestPrefs.personalizationBootstrapped) {
                return@withLock
            }
            if (intelligenceDao.countFacets() > 0) {
                userPreferencesRepository.setPersonalizationBootstrapped(true)
                return@withLock
            }

            val ts = nowMs()
            val accumulated = linkedMapOf<String, Triple<ExtractedFacetCandidate, Double, String>>()

            fun accumulate(candidate: ExtractedFacetCandidate, weight: Double, evidence: String) {
                val existing = accumulated[candidate.facetKey]
                val nextWeight = ((existing?.second ?: 0.0) + weight).coerceAtMost(1.0)
                accumulated[candidate.facetKey] = Triple(candidate, nextWeight, existing?.third ?: evidence)
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
                            weight = 0.22,
                            evidence = "From your Movies & TV watchlist"
                        )
                    }
                    accumulate(
                        ExtractedFacetCandidate(
                            facetKey = TopicExtractor.kindFacetKey(kind),
                            displayLabel = kind.label,
                            surfaceScope = IntelligenceSurfaceScope.MOVIES,
                            isMediaCue = true
                        ),
                        weight = 0.14,
                        evidence = "From your Movies & TV watchlist"
                    )
                }

                val history = runCatching { dao.getWatchHistorySnapshot(40) }.getOrDefault(emptyList())
                    .filter { !it.isTrailer && !it.isHiddenTitle }
                for (h in history) {
                    val kind = MediaKind.fromWire(h.kind)
                    val weight = if (h.isCompleted) 0.25 else 0.12
                    accumulate(
                        ExtractedFacetCandidate(
                            facetKey = TopicExtractor.kindFacetKey(kind),
                            displayLabel = kind.label,
                            surfaceScope = IntelligenceSurfaceScope.MOVIES,
                            isMediaCue = true
                        ),
                        weight = weight,
                        evidence = "From your watch history"
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
                    val boost = if (item.link.isRead) 0.20 else 0.12
                    for (t in topics) {
                        accumulate(t, boost, "From your saved links")
                    }
                }
            }

            val entities = accumulated.values.map { (cand, score, evidence) ->
                UserTasteFacetEntity(
                    facetKey = cand.facetKey,
                    facetType = TasteFacetType.ENDURING_INTEREST.name,
                    surfaceScope = cand.surfaceScope.name,
                    displayLabel = cand.displayLabel,
                    score = score.coerceIn(0.10, 1.0),
                    confidence = (0.45 + score * 0.45).coerceIn(0.45, 0.95),
                    provenance = evidence,
                    evidenceSummary = evidence,
                    includeInAssistantContext = false,
                    isSuppressedByUser = false,
                    firstObservedAtMs = ts,
                    lastReinforcedAtMs = ts,
                    expiresAtMs = null
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
        timestampMs: Long = nowMs()
    ) = withContext(Dispatchers.IO) {
        if (!TopicExtractor.isSafeFacetLabel(candidate.displayLabel)) return@withContext
        writeMutex.withLock {
            val existing = intelligenceDao.getFacetByKey(candidate.facetKey)
            if (existing != null) {
                // Never override user's explicit suppression or explicit dislike via passive signals
                if (existing.isSuppressedByUser) return@withLock
                if (existing.facetType == TasteFacetType.EXPLICIT_DISLIKE.name && delta > 0.0) return@withLock

                val updatedScore = if (existing.facetType == TasteFacetType.EXPLICIT_LIKE.name) {
                    (existing.score + (delta * 0.25)).coerceIn(0.25, 1.0)
                } else {
                    (existing.score + delta).coerceIn(0.05, 1.0)
                }
                val updatedConfidence = (existing.confidence + 0.06).coerceIn(0.35, 0.99)
                val nextType = when {
                    existing.facetType == TasteFacetType.EXPLICIT_LIKE.name -> existing.facetType
                    existing.facetType == TasteFacetType.ENDURING_INTEREST.name -> existing.facetType
                    updatedScore >= 0.45 -> TasteFacetType.ENDURING_INTEREST.name
                    else -> facetType.name
                }
                val nextExpires = if (nextType == TasteFacetType.SHORT_TERM_INTENT.name && ttlMs != null) {
                    timestampMs + ttlMs
                } else if (nextType == TasteFacetType.ENDURING_INTEREST.name || nextType == TasteFacetType.EXPLICIT_LIKE.name) {
                    null
                } else {
                    existing.expiresAtMs
                }
                intelligenceDao.upsertFacet(
                    existing.copy(
                        facetType = nextType,
                        score = updatedScore,
                        confidence = updatedConfidence,
                        provenance = provenance.take(120),
                        evidenceSummary = evidenceSummary.take(140),
                        lastReinforcedAtMs = timestampMs,
                        expiresAtMs = nextExpires
                    )
                )
            } else if (delta > 0.0) {
                val initialScore = delta.coerceIn(0.10, 1.0)
                val expiresAt = if (facetType == TasteFacetType.SHORT_TERM_INTENT && ttlMs != null) {
                    timestampMs + ttlMs
                } else {
                    null
                }
                intelligenceDao.upsertFacet(
                    UserTasteFacetEntity(
                        facetKey = candidate.facetKey,
                        facetType = facetType.name,
                        surfaceScope = candidate.surfaceScope.name,
                        displayLabel = candidate.displayLabel.take(48),
                        score = initialScore,
                        confidence = (0.40 + initialScore * 0.35).coerceIn(0.40, 0.95),
                        provenance = provenance.take(120),
                        evidenceSummary = evidenceSummary.take(140),
                        includeInAssistantContext = false,
                        isSuppressedByUser = false,
                        firstObservedAtMs = timestampMs,
                        lastReinforcedAtMs = timestampMs,
                        expiresAtMs = expiresAt
                    )
                )
            }
        }
    }

    suspend fun penalizeFacet(
        facetKey: String,
        penalty: Double,
        timestampMs: Long = nowMs()
    ) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val existing = intelligenceDao.getFacetByKey(facetKey) ?: return@withLock
            if (existing.facetType == TasteFacetType.EXPLICIT_LIKE.name ||
                existing.facetType == TasteFacetType.EXPLICIT_DISLIKE.name
            ) {
                return@withLock
            }
            val nextScore = (existing.score - penalty).coerceAtLeast(0.0)
            if (nextScore < MIN_RETAINED_SCORE && !existing.isSuppressedByUser) {
                intelligenceDao.deleteFacetByKey(facetKey)
            } else {
                intelligenceDao.upsertFacet(
                    existing.copy(
                        score = nextScore,
                        lastReinforcedAtMs = timestampMs
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
                    provenance = if (liked) "Liked by you" else "Disliked by you",
                    evidenceSummary = if (liked) "Explicitly pinned in preferences" else "Explicitly muted/disliked by user",
                    includeInAssistantContext = includeInAssistant && liked,
                    isSuppressedByUser = false,
                    firstObservedAtMs = existing?.firstObservedAtMs ?: ts,
                    lastReinforcedAtMs = ts,
                    expiresAtMs = null
                )
            )
        }
    }

    suspend fun toggleSuppressFacet(facetKey: String, suppressed: Boolean) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            intelligenceDao.setFacetSuppressed(facetKey, suppressed)
        }
    }

    suspend fun setFacetIncludeInAssistant(facetKey: String, include: Boolean) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            intelligenceDao.setFacetIncludeInAssistant(facetKey, include)
        }
    }

    suspend fun deleteFacet(facetKey: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            intelligenceDao.deleteFacetByKey(facetKey)
        }
    }

    /**
     * 1-tap wipe: clears all learned taste facets & intelligence signals locally
     * without touching saved links, collections, watchlists, watch history, or downloads.
     */
    suspend fun resetModel() = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            intelligenceDao.clearAllFacets()
            intelligenceDao.clearAllSignals()
            userPreferencesRepository.setPersonalizationBootstrapped(true)
        }
    }

    /**
     * Explicit user-initiated rebuild from local library after a reset.
     */
    suspend fun rebuildFromLibrary() = withContext(Dispatchers.IO) {
        userPreferencesRepository.setPersonalizationBootstrapped(false)
        bootstrapFromExistingLibraryIfNeeded()
    }

    /**
     * Daily maintenance invoked by [com.laterbox.app.storage.StorageMaintenanceWorker]:
     * - Deletes expired SHORT_TERM_INTENT facets
     * - Decays unreinforced non-explicit facets and removes decayed rows < MIN_RETAINED_SCORE
     * - Prunes intelligence_signals to <= 30 days and <= 500 rows
     */
    suspend fun runDailyMaintenance(currentMs: Long = nowMs()): MaintenanceStats =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val expiredRemoved = intelligenceDao.deleteExpiredFacets(currentMs)
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
                    if (newScore < MIN_RETAINED_SCORE) {
                        intelligenceDao.deleteFacetByKey(facet.facetKey)
                        decayedRemoved++
                    } else if (newScore < facet.score - 0.005) {
                        intelligenceDao.upsertFacet(facet.copy(score = newScore))
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
     * Returns empty string unless both personalization & assistantPersonalContextEnabled are active.
     * Never exposes raw collection titles or sensitive categories.
     */
    suspend fun buildPrivacySafeAssistantSummary(now: Long = nowMs()): String =
        withContext(Dispatchers.IO) {
            val prefs = userPreferencesRepository.userPreferencesFlow.first()
            if (!prefs.personalizationEnabled || !prefs.assistantPersonalContextEnabled) {
                return@withContext ""
            }
            val eligible = intelligenceDao.getAssistantEligibleFacets(limit = 8, nowMs = now)
                .filter { TopicExtractor.isSafeFacetLabel(it.displayLabel) }
            if (eligible.isEmpty()) return@withContext ""

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
                if (dislikes.isNotEmpty()) {
                    if (isNotEmpty()) append(" | ")
                    append("Muted/disliked: ${dislikes.joinToString(", ")}")
                }
            }
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
        const val MIN_RETAINED_SCORE = 0.05
    }
}
