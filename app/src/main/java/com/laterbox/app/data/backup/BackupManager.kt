package com.laterbox.app.data.backup

import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.local.entity.CollectionEntity
import com.laterbox.app.data.local.entity.IntelligenceSignalEntity
import com.laterbox.app.data.local.entity.IntelligenceSurfaceScope
import com.laterbox.app.data.local.entity.LinkCollectionCrossRef
import com.laterbox.app.data.local.entity.SavedLinkEntity
import com.laterbox.app.data.local.entity.SuggestionDismissalEntity
import com.laterbox.app.data.local.entity.SuggestionDismissalType
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.TitleFeedbackEntity
import com.laterbox.app.data.local.entity.TitleReaction
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.domain.model.MetadataStatus
import com.laterbox.app.intelligence.IntelligenceSignalLogger
import com.laterbox.app.intelligence.TopicExtractor
import com.laterbox.app.intelligence.UserUnderstandingRepository
import com.laterbox.app.movies.data.local.entity.MovieWatchlistEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

class BackupManager(
    private val database: LaterboxDatabase,
    private val reminderScheduler: com.laterbox.app.reminders.ReminderScheduler? = null,
    private val onScheduleReminder: ((linkId: Long, triggerAtMillis: Long, title: String?, url: String?) -> Unit)? = null,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
    private val userPreferencesRepository: UserPreferencesRepository? = null
) {

    suspend fun exportToJson(outputStream: OutputStream): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val collectionDao = database.collectionDao()
            val savedLinkDao = database.savedLinkDao()
            val intelligenceDao = database.intelligenceDao()
            val moviesDao = database.moviesDao()
            val nowMs = nowMsProvider()

            val collections = collectionDao.getCollections().first().map {
                BackupCollection(name = it.name, createdAt = it.createdAt)
            }

            val inboxLinks = savedLinkDao.getInboxLinks().first()
            val archivedLinks = savedLinkDao.getArchivedLinks().first()
            val allLinks = (inboxLinks + archivedLinks).map { item ->
                val colNames = item.collections.map { it.name }
                BackupLink(
                    originalUrl = item.link.originalUrl,
                    normalizedUrl = item.link.normalizedUrl,
                    title = item.link.title,
                    description = item.link.description,
                    siteName = item.link.siteName,
                    domain = item.link.domain,
                    faviconUrl = item.link.faviconUrl,
                    previewImageUrl = item.link.previewImageUrl,
                    contentType = item.link.contentType,
                    personalNote = item.link.personalNote,
                    collectionName = colNames.firstOrNull(),
                    collectionNames = colNames,
                    isRead = item.link.isRead,
                    isArchived = item.link.isArchived,
                    reminderAt = item.link.reminderAt,
                    createdAt = item.link.createdAt
                )
            }

            val facets = intelligenceDao.getAllFacetsSnapshot()
                .filter {
                    TopicExtractor.isSafeFacetLabel(it.displayLabel) &&
                        (it.expiresAtMs == null || it.expiresAtMs > nowMs)
                }
                .map { f ->
                    BackupTasteFacet(
                        facetKey = f.facetKey,
                        facetType = normalizeFacetType(f.facetType),
                        surfaceScope = normalizeSurfaceScope(f.surfaceScope),
                        displayLabel = f.displayLabel.take(48),
                        score = f.score.coerceIn(0.0, 1.0),
                        confidence = f.confidence.coerceIn(0.0, 1.0),
                        provenance = sanitizeIntelligenceText(f.provenance, fallback = "On-device activity"),
                        evidenceSummary = sanitizeIntelligenceText(f.evidenceSummary, fallback = f.displayLabel.take(48)),
                        includeInAssistantContext = f.includeInAssistantContext,
                        isSuppressedByUser = f.isSuppressedByUser,
                        firstObservedAtMs = f.firstObservedAtMs.coerceAtLeast(0L),
                        lastReinforcedAtMs = f.lastReinforcedAtMs.coerceAtLeast(0L),
                        expiresAtMs = f.expiresAtMs?.takeIf { it > 0L },
                        hasBehavioralEvidence = f.hasBehavioralEvidence,
                        independentEvidenceCount = f.independentEvidenceCount.coerceAtLeast(1),
                        supportingSignalTypesCsv = sanitizeSignalTypesCsv(f.supportingSignalTypesCsv)
                    )
                }

            val signalCutoffMs = nowMs - UserUnderstandingRepository.SIGNAL_MAX_AGE_MS
            val signals = intelligenceDao.getRecentSignalsSnapshot(UserUnderstandingRepository.SIGNAL_MAX_ROWS)
                .asSequence()
                .filter { it.createdAtMs >= signalCutoffMs && it.createdAtMs <= nowMs + MAX_FUTURE_SKEW_MS }
                .take(UserUnderstandingRepository.SIGNAL_MAX_ROWS)
                .map { s ->
                    BackupIntelligenceSignal(
                        signalType = s.signalType,
                        surfaceScope = normalizeSurfaceScope(s.surfaceScope),
                        targetKey = sanitizeSignalTargetKey(s.signalType, s.surfaceScope, s.targetKey, s.isMediaCue),
                        topicsCsv = sanitizeTopicsCsv(s.topicsCsv),
                        isMediaCue = s.isMediaCue,
                        strength = s.strength.coerceIn(-1.0, 1.0),
                        createdAtMs = s.createdAtMs
                    )
                }
                .toList()

            val titleFeedback = intelligenceDao.getAllTitleFeedbackSnapshot()
                .mapNotNull { tf ->
                    val reaction = runCatching { TitleReaction.valueOf(tf.reaction) }.getOrNull()
                        ?: return@mapNotNull null
                    BackupTitleFeedback(
                        mediaKey = tf.mediaKey,
                        compositeId = tf.compositeId,
                        providerId = tf.providerId,
                        titleId = tf.titleId,
                        canonicalId = tf.canonicalId,
                        kind = tf.kind,
                        reaction = reaction.name,
                        genresCsv = sanitizeGenresCsv(tf.genresCsv),
                        updatedAtMs = tf.updatedAtMs
                    )
                }

            val dismissals = intelligenceDao.getAllDismissalsSnapshot()
                .asSequence()
                .filter { it.suppressUntilMs == null || it.suppressUntilMs > nowMs }
                .mapNotNull { d ->
                    val type = runCatching { SuggestionDismissalType.valueOf(d.suggestionType) }.getOrNull()
                        ?: return@mapNotNull null
                    if (d.linkIdentityHash.isBlank()) return@mapNotNull null
                    BackupSuggestionDismissal(
                        suggestionType = type.name,
                        linkIdentityHash = d.linkIdentityHash,
                        dismissedAtMs = d.dismissedAtMs,
                        suppressUntilMs = d.suppressUntilMs
                    )
                }
                .toList()

            val watchlist = moviesDao.getWatchlistSnapshot().map { w ->
                BackupMovieWatchlistItem(
                    compositeId = w.compositeId,
                    providerId = w.providerId,
                    titleId = w.titleId,
                    kind = w.kind,
                    title = w.title,
                    posterUrl = w.posterUrl,
                    backdropUrl = w.backdropUrl,
                    releaseYear = w.releaseYear,
                    rating = w.rating,
                    genresCsv = w.genresCsv,
                    summary = w.summary,
                    sourceBadge = w.sourceBadge,
                    isPlayableHint = w.isPlayableHint,
                    addedAt = w.addedAt,
                    canonicalId = w.canonicalId
                )
            }

            val personalizationPrefs = userPreferencesRepository?.let { repo ->
                val prefs = repo.userPreferencesFlow.first()
                BackupPersonalizationPreferences(
                    assistantPersonalContextEnabled = prefs.assistantPersonalContextEnabled
                )
            }

            val backup = LaterboxBackup(
                version = LaterboxBackup.CURRENT_BACKUP_VERSION,
                exportedAt = nowMs,
                collections = collections,
                links = allLinks,
                userTasteFacets = facets,
                intelligenceSignals = signals,
                titleFeedback = titleFeedback,
                suggestionDismissals = dismissals,
                movieWatchlist = watchlist,
                personalizationPreferences = personalizationPrefs
            )

            val jsonString = serializeBackup(backup)
            outputStream.bufferedWriter().use { it.write(jsonString) }

            Result.success(allLinks.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importFromJson(inputStream: InputStream): Result<BackupRestoreResult> = withContext(Dispatchers.IO) {
        try {
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val backup = deserializeBackup(jsonString)

            val collectionDao = database.collectionDao()
            val savedLinkDao = database.savedLinkDao()
            val intelligenceDao = database.intelligenceDao()
            val moviesDao = database.moviesDao()
            val nowMs = nowMsProvider()

            var restoredCollectionsCount = 0
            var restoredLinksCount = 0
            var skippedDuplicatesCount = 0
            var restoredFacetsCount = 0
            var restoredWatchlistCount = 0
            var restoredSignalsCount = 0
            var restoredDismissalsCount = 0
            var restoredTitleFeedbackCount = 0

            // 1. Resolve / Create Collections
            val existingCollections = collectionDao.getCollections().first()
            val collectionMap = existingCollections.associate { it.name.lowercase() to it.id }.toMutableMap()

            for (bCol in backup.collections) {
                val key = bCol.name.trim().lowercase()
                if (key.isNotBlank() && !collectionMap.containsKey(key)) {
                    val id = collectionDao.insertCollection(CollectionEntity(name = bCol.name.trim()))
                    collectionMap[key] = id
                    restoredCollectionsCount++
                }
            }

            // 2. Restore Links with duplicate detection, multi-collections, and alarm re-hydration
            val linkIdByHash = mutableMapOf<String, Long>()
            for (bLink in backup.links) {
                val targetNames = bLink.collectionNames.ifEmpty { listOfNotNull(bLink.collectionName) }
                val targetColIds = targetNames.mapNotNull { name ->
                    collectionMap[name.trim().lowercase()]
                }
                val linkHash = TopicExtractor.stableLinkIdentityHash(bLink.normalizedUrl)

                val existing = savedLinkDao.findLinkByNormalizedUrl(bLink.normalizedUrl)
                if (existing != null) {
                    linkIdByHash[linkHash] = existing.link.id
                    val needsNote = existing.link.personalNote.isNullOrBlank() && !bLink.personalNote.isNullOrBlank()
                    val needsReminder = existing.link.reminderAt == null && bLink.reminderAt != null
                    val effectiveReminderAt = if (needsReminder) bLink.reminderAt else existing.link.reminderAt
                    val effectiveDismissed = if (needsReminder) false else existing.link.nudgeDismissed
                    if (needsNote || needsReminder) {
                        savedLinkDao.updateLink(
                            existing.link.copy(
                                personalNote = if (needsNote) bLink.personalNote else existing.link.personalNote,
                                reminderAt = effectiveReminderAt,
                                nudgeDismissed = effectiveDismissed
                            )
                        )
                    }
                    scheduleReminderIfEligible(
                        linkId = existing.link.id,
                        reminderAt = effectiveReminderAt,
                        isRead = existing.link.isRead,
                        isArchived = existing.link.isArchived,
                        nudgeDismissed = effectiveDismissed,
                        title = existing.link.title,
                        originalUrl = existing.link.originalUrl,
                        nowMs = nowMs
                    )
                    for (colId in targetColIds) {
                        savedLinkDao.insertLinkCollectionCrossRef(
                            LinkCollectionCrossRef(linkId = existing.link.id, collectionId = colId)
                        )
                    }
                    skippedDuplicatesCount++
                } else {
                    val entity = SavedLinkEntity(
                        originalUrl = bLink.originalUrl,
                        normalizedUrl = bLink.normalizedUrl,
                        title = bLink.title,
                        description = bLink.description,
                        siteName = bLink.siteName,
                        domain = bLink.domain,
                        faviconUrl = bLink.faviconUrl,
                        previewImageUrl = bLink.previewImageUrl,
                        contentType = bLink.contentType,
                        personalNote = bLink.personalNote,
                        collectionId = targetColIds.firstOrNull(),
                        isRead = bLink.isRead,
                        isArchived = bLink.isArchived,
                        reminderAt = bLink.reminderAt,
                        metadataStatus = if (bLink.previewImageUrl != null) MetadataStatus.SUCCESS else MetadataStatus.PARTIAL,
                        createdAt = bLink.createdAt,
                        updatedAt = nowMs
                    )
                    val insertedId = savedLinkDao.insertLink(entity)
                    if (insertedId > 0L) {
                        linkIdByHash[linkHash] = insertedId
                    }
                    scheduleReminderIfEligible(
                        linkId = insertedId,
                        reminderAt = entity.reminderAt,
                        isRead = entity.isRead,
                        isArchived = entity.isArchived,
                        nudgeDismissed = entity.nudgeDismissed,
                        title = entity.title,
                        originalUrl = entity.originalUrl,
                        nowMs = nowMs
                    )
                    for (colId in targetColIds) {
                        savedLinkDao.insertLinkCollectionCrossRef(
                            LinkCollectionCrossRef(linkId = insertedId, collectionId = colId)
                        )
                    }
                    restoredLinksCount++
                }
            }
            // Also index any already-present links so dismissals match existing library links even if backup had fewer links
            val currentAllLinks = savedLinkDao.getInboxLinks().first() + savedLinkDao.getArchivedLinks().first()
            for (item in currentAllLinks) {
                linkIdByHash.putIfAbsent(TopicExtractor.stableLinkIdentityHash(item.link.normalizedUrl), item.link.id)
            }

            // 3. Merge User Taste Facets safely with strict validation & sanitization
            for (bFacet in backup.userTasteFacets) {
                if (bFacet.facetKey.isBlank() || !TopicExtractor.isSafeFacetLabel(bFacet.displayLabel)) continue
                val validatedType = runCatching { TasteFacetType.valueOf(bFacet.facetType) }.getOrNull()
                    ?: continue
                val validatedScope = runCatching { IntelligenceSurfaceScope.valueOf(bFacet.surfaceScope) }.getOrNull()
                    ?: continue
                if (bFacet.score !in 0.0..1.0 || bFacet.confidence !in 0.0..1.0) continue
                if (bFacet.firstObservedAtMs < 0L || bFacet.lastReinforcedAtMs <= 0L) continue
                if (bFacet.lastReinforcedAtMs > nowMs + MAX_FUTURE_SKEW_MS) continue
                if (bFacet.expiresAtMs != null && bFacet.expiresAtMs <= nowMs) continue

                val fixedScope = if (bFacet.facetKey == "topic:cinema_tv" && validatedScope == IntelligenceSurfaceScope.ALL) {
                    IntelligenceSurfaceScope.LIBRARY.name
                } else {
                    validatedScope.name
                }
                val effectiveBehavioral = if (validatedType == TasteFacetType.SHORT_TERM_INTENT && !bFacet.hasBehavioralEvidence) {
                    false
                } else {
                    bFacet.hasBehavioralEvidence
                }

                val importedEntity = UserTasteFacetEntity(
                    facetKey = bFacet.facetKey,
                    facetType = validatedType.name,
                    surfaceScope = fixedScope,
                    displayLabel = bFacet.displayLabel.take(48),
                    score = bFacet.score.coerceIn(0.0, 1.0),
                    confidence = bFacet.confidence.coerceIn(0.0, 1.0),
                    provenance = sanitizeIntelligenceText(bFacet.provenance, fallback = "Restored interest"),
                    evidenceSummary = sanitizeIntelligenceText(bFacet.evidenceSummary, fallback = bFacet.displayLabel.take(48)),
                    includeInAssistantContext = bFacet.includeInAssistantContext,
                    isSuppressedByUser = bFacet.isSuppressedByUser,
                    firstObservedAtMs = bFacet.firstObservedAtMs,
                    lastReinforcedAtMs = bFacet.lastReinforcedAtMs,
                    expiresAtMs = bFacet.expiresAtMs,
                    hasBehavioralEvidence = effectiveBehavioral,
                    independentEvidenceCount = bFacet.independentEvidenceCount.coerceAtLeast(1),
                    supportingSignalTypesCsv = sanitizeSignalTypesCsv(bFacet.supportingSignalTypesCsv)
                )
                val existingFacet = intelligenceDao.getFacetByKey(bFacet.facetKey)
                val shouldWrite = when {
                    existingFacet == null -> true
                    isExplicitOrSuppressed(existingFacet) && !isExplicitOrSuppressed(importedEntity) -> false
                    !isExplicitOrSuppressed(existingFacet) && isExplicitOrSuppressed(importedEntity) -> true
                    else -> importedEntity.lastReinforcedAtMs > existingFacet.lastReinforcedAtMs
                }
                if (shouldWrite) {
                    intelligenceDao.upsertFacet(importedEntity)
                    restoredFacetsCount++
                }
            }

            // 4. Restore bounded, sanitized Intelligence Signals with deduplication
            if (backup.intelligenceSignals.isNotEmpty()) {
                val cutoffMs = nowMs - UserUnderstandingRepository.SIGNAL_MAX_AGE_MS
                val existingSignatures = intelligenceDao.getRecentSignalsSnapshot(UserUnderstandingRepository.SIGNAL_MAX_ROWS)
                    .mapTo(mutableSetOf()) { signalSignature(it.signalType, it.surfaceScope, it.targetKey, it.topicsCsv, it.createdAtMs) }

                val toInsert = mutableListOf<IntelligenceSignalEntity>()
                for (bSig in backup.intelligenceSignals) {
                    if (bSig.signalType.isBlank()) continue
                    if (bSig.createdAtMs < cutoffMs || bSig.createdAtMs > nowMs + MAX_FUTURE_SKEW_MS) continue
                    val validatedScope = runCatching { IntelligenceSurfaceScope.valueOf(bSig.surfaceScope) }.getOrNull()
                        ?: continue
                    val safeTarget = sanitizeSignalTargetKey(bSig.signalType, validatedScope.name, bSig.targetKey, bSig.isMediaCue)
                    val safeTopics = sanitizeTopicsCsv(bSig.topicsCsv)
                    val sigKey = signalSignature(bSig.signalType, validatedScope.name, safeTarget, safeTopics, bSig.createdAtMs)
                    if (sigKey in existingSignatures) continue
                    existingSignatures.add(sigKey)
                    toInsert.add(
                        IntelligenceSignalEntity(
                            signalType = bSig.signalType,
                            surfaceScope = validatedScope.name,
                            targetKey = safeTarget,
                            topicsCsv = safeTopics,
                            isMediaCue = bSig.isMediaCue,
                            strength = bSig.strength.coerceIn(-1.0, 1.0),
                            createdAtMs = bSig.createdAtMs
                        )
                    )
                }
                if (toInsert.isNotEmpty()) {
                    intelligenceDao.insertSignals(toInsert)
                    intelligenceDao.deleteSignalsOlderThan(cutoffMs)
                    intelligenceDao.trimSignalsToMaxRows(UserUnderstandingRepository.SIGNAL_MAX_ROWS)
                    restoredSignalsCount = toInsert.size
                }
            }

            // 5. Restore Title-Level Like/Dislike Reactions (explicit user feedback)
            for (bFeedback in backup.titleFeedback) {
                if (bFeedback.mediaKey.isBlank() || bFeedback.compositeId.isBlank()) continue
                val reaction = runCatching { TitleReaction.valueOf(bFeedback.reaction) }.getOrNull()
                    ?: continue
                if (bFeedback.updatedAtMs <= 0L || bFeedback.updatedAtMs > nowMs + MAX_FUTURE_SKEW_MS) continue
                val existing = intelligenceDao.getTitleFeedback(bFeedback.mediaKey, bFeedback.compositeId)
                if (existing == null || bFeedback.updatedAtMs > existing.updatedAtMs) {
                    intelligenceDao.upsertTitleFeedback(
                        TitleFeedbackEntity(
                            mediaKey = bFeedback.mediaKey,
                            compositeId = bFeedback.compositeId,
                            providerId = bFeedback.providerId,
                            titleId = bFeedback.titleId,
                            canonicalId = bFeedback.canonicalId,
                            kind = bFeedback.kind,
                            title = existing?.title ?: bFeedback.mediaKey,
                            reaction = reaction.name,
                            genresCsv = sanitizeGenresCsv(bFeedback.genresCsv),
                            updatedAtMs = bFeedback.updatedAtMs
                        )
                    )
                    restoredTitleFeedbackCount++
                }
            }

            // 6. Restore Persisted Suggestion Dismissals (so dismissed suggestions stay dismissed)
            if (backup.suggestionDismissals.isNotEmpty()) {
                val existingByKey = intelligenceDao.getAllDismissalsSnapshot().associateBy { it.dismissalKey }
                val toUpsert = mutableListOf<SuggestionDismissalEntity>()
                for (bDis in backup.suggestionDismissals) {
                    val type = runCatching { SuggestionDismissalType.valueOf(bDis.suggestionType) }.getOrNull()
                        ?: continue
                    if (bDis.linkIdentityHash.isBlank() || bDis.dismissedAtMs <= 0L) continue
                    if (bDis.suppressUntilMs != null && bDis.suppressUntilMs <= nowMs) continue
                    val dismissalKey = "${type.name}:${bDis.linkIdentityHash}"
                    val existing = existingByKey[dismissalKey]
                    if (existing == null || bDis.dismissedAtMs > existing.dismissedAtMs) {
                        val resolvedLinkId = linkIdByHash[bDis.linkIdentityHash] ?: existing?.linkId ?: 0L
                        toUpsert.add(
                            SuggestionDismissalEntity(
                                dismissalKey = dismissalKey,
                                suggestionType = type.name,
                                linkId = resolvedLinkId,
                                linkIdentityHash = bDis.linkIdentityHash,
                                dismissedAtMs = bDis.dismissedAtMs,
                                suppressUntilMs = bDis.suppressUntilMs
                            )
                        )
                    }
                }
                if (toUpsert.isNotEmpty()) {
                    intelligenceDao.upsertDismissals(toUpsert)
                    restoredDismissalsCount = toUpsert.size
                }
            }

            // 7. Merge Movies & TV Watchlist without duplicating canonical or composite IDs
            val existingWatchlist = moviesDao.getWatchlistSnapshot()
            val knownCompositeIds = existingWatchlist.mapTo(mutableSetOf()) { it.compositeId }
            val knownCanonicalIds = existingWatchlist.mapNotNullTo(mutableSetOf()) { it.canonicalId?.takeIf { c -> c.isNotBlank() } }
            for (bWatch in backup.movieWatchlist) {
                if (bWatch.compositeId.isBlank()) continue
                val canonical = bWatch.canonicalId?.takeIf { it.isNotBlank() }
                if (bWatch.compositeId in knownCompositeIds || (canonical != null && canonical in knownCanonicalIds)) {
                    continue
                }
                moviesDao.insertWatchlistItem(
                    MovieWatchlistEntity(
                        compositeId = bWatch.compositeId,
                        providerId = bWatch.providerId,
                        titleId = bWatch.titleId,
                        kind = bWatch.kind,
                        title = bWatch.title,
                        posterUrl = bWatch.posterUrl,
                        backdropUrl = bWatch.backdropUrl,
                        releaseYear = bWatch.releaseYear,
                        rating = bWatch.rating,
                        genresCsv = bWatch.genresCsv,
                        summary = bWatch.summary,
                        sourceBadge = bWatch.sourceBadge,
                        isPlayableHint = bWatch.isPlayableHint,
                        addedAt = bWatch.addedAt,
                        canonicalId = canonical
                    )
                )
                knownCompositeIds.add(bWatch.compositeId)
                if (canonical != null) knownCanonicalIds.add(canonical)
                restoredWatchlistCount++
            }

            // 8. Restore Assistant Taste Sharing Preference (EI itself is always-on)
            backup.personalizationPreferences?.let { pPrefs ->
                userPreferencesRepository?.setAssistantPersonalContextEnabled(pPrefs.assistantPersonalContextEnabled)
            }

            Result.success(
                BackupRestoreResult(
                    restoredLinksCount = restoredLinksCount,
                    skippedDuplicatesCount = skippedDuplicatesCount,
                    restoredCollectionsCount = restoredCollectionsCount,
                    restoredFacetsCount = restoredFacetsCount,
                    restoredWatchlistCount = restoredWatchlistCount,
                    restoredSignalsCount = restoredSignalsCount,
                    restoredDismissalsCount = restoredDismissalsCount,
                    restoredTitleFeedbackCount = restoredTitleFeedbackCount
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun signalSignature(
        signalType: String,
        surfaceScope: String,
        targetKey: String,
        topicsCsv: String,
        createdAtMs: Long
    ): String = "$signalType|$surfaceScope|$targetKey|$topicsCsv|$createdAtMs"

    private fun normalizeFacetType(raw: String): String =
        runCatching { TasteFacetType.valueOf(raw).name }.getOrDefault(TasteFacetType.ENDURING_INTEREST.name)

    private fun normalizeSurfaceScope(raw: String): String =
        runCatching { IntelligenceSurfaceScope.valueOf(raw).name }.getOrDefault(IntelligenceSurfaceScope.LIBRARY.name)

    private fun sanitizeIntelligenceText(raw: String, fallback: String): String {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return fallback
        if (TopicExtractor.containsSensitiveText(cleaned)) return fallback
        if (cleaned.contains("http://", ignoreCase = true) || cleaned.contains("https://", ignoreCase = true)) {
            return fallback
        }
        return cleaned.take(120)
    }

    private fun sanitizeSignalTargetKey(
        signalType: String,
        surfaceScope: String,
        targetKey: String,
        isMediaCue: Boolean
    ): String {
        val clean = targetKey.trim()
        if (signalType == IntelligenceSignalLogger.SIGNAL_SEARCH || clean.startsWith("search:", ignoreCase = true)) {
            return if (clean.startsWith("search:sanitized:", ignoreCase = true)) {
                clean.take(80)
            } else {
                "search:sanitized:${surfaceScope.lowercase()}:legacy"
            }
        }
        if (clean.contains("http://", ignoreCase = true) || clean.contains("https://", ignoreCase = true)) {
            return if (isMediaCue) "media:cue" else "library:item"
        }
        if (clean.startsWith("link:")) {
            return "link:item"
        }
        return clean.take(80).ifBlank { if (isMediaCue) "media:cue" else "library:item" }
    }

    private fun sanitizeTopicsCsv(topicsCsv: String): String = topicsCsv
        .split(',')
        .map { it.trim() }
        .filter { token ->
            token.isNotBlank() &&
                (token.startsWith("topic:") || token.startsWith("genre:") || token.startsWith("kind:")) &&
                TopicExtractor.isSafeFacetLabel(token.substringAfter(':').replace('_', ' '))
        }
        .distinct()
        .take(12)
        .joinToString(",")

    private fun sanitizeGenresCsv(genresCsv: String): String = genresCsv
        .split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() && TopicExtractor.isSafeFacetLabel(it) }
        .distinct()
        .take(8)
        .joinToString(",")

    private fun sanitizeSignalTypesCsv(csv: String): String = csv
        .split(',')
        .map { it.trim().uppercase() }
        .filter { it.isNotBlank() && it.all { ch -> ch.isLetterOrDigit() || ch == '_' } }
        .distinct()
        .take(12)
        .joinToString(",")

    private fun isExplicitOrSuppressed(facet: UserTasteFacetEntity): Boolean {
        return facet.isSuppressedByUser ||
            facet.facetType == TasteFacetType.EXPLICIT_LIKE.name ||
            facet.facetType == TasteFacetType.EXPLICIT_DISLIKE.name ||
            facet.facetType == "USER_STATED" ||
            facet.facetType == "USER_CORRECTED"
    }

    private fun scheduleReminderIfEligible(
        linkId: Long,
        reminderAt: Long?,
        isRead: Boolean,
        isArchived: Boolean,
        nudgeDismissed: Boolean,
        title: String,
        originalUrl: String,
        nowMs: Long
    ) {
        if (linkId <= 0L || isRead || isArchived || nudgeDismissed) return
        if (reminderAt == null || reminderAt <= nowMs) return
        runCatching {
            onScheduleReminder?.invoke(linkId, reminderAt, title, originalUrl)
                ?: reminderScheduler?.scheduleReminder(
                    linkId = linkId,
                    triggerAtMillis = reminderAt,
                    title = title,
                    url = originalUrl
                )
        }
    }

    fun serializeBackup(backup: LaterboxBackup): String {
        val root = JSONObject()
        root.put("version", backup.version)
        root.put("appName", backup.appName)
        root.put("exportedAt", backup.exportedAt)

        val colArray = JSONArray()
        for (col in backup.collections) {
            val cObj = JSONObject()
            cObj.put("name", col.name)
            cObj.put("createdAt", col.createdAt)
            colArray.put(cObj)
        }
        root.put("collections", colArray)

        val linkArray = JSONArray()
        for (link in backup.links) {
            val lObj = JSONObject()
            lObj.put("originalUrl", link.originalUrl)
            lObj.put("normalizedUrl", link.normalizedUrl)
            lObj.put("title", link.title)
            lObj.put("description", link.description ?: JSONObject.NULL)
            lObj.put("siteName", link.siteName ?: JSONObject.NULL)
            lObj.put("domain", link.domain)
            lObj.put("faviconUrl", link.faviconUrl ?: JSONObject.NULL)
            lObj.put("previewImageUrl", link.previewImageUrl ?: JSONObject.NULL)
            lObj.put("contentType", link.contentType.name)
            lObj.put("personalNote", link.personalNote ?: JSONObject.NULL)
            val effectiveColNames = link.collectionNames.ifEmpty { listOfNotNull(link.collectionName) }
            lObj.put("collectionName", link.collectionName ?: effectiveColNames.firstOrNull() ?: JSONObject.NULL)
            val colNamesArray = JSONArray()
            for (cName in effectiveColNames) {
                colNamesArray.put(cName)
            }
            lObj.put("collectionNames", colNamesArray)
            lObj.put("isRead", link.isRead)
            lObj.put("isArchived", link.isArchived)
            lObj.put("reminderAt", link.reminderAt ?: JSONObject.NULL)
            lObj.put("createdAt", link.createdAt)
            linkArray.put(lObj)
        }
        root.put("links", linkArray)

        val facetsArray = JSONArray()
        for (facet in backup.userTasteFacets) {
            val fObj = JSONObject()
            fObj.put("facetKey", facet.facetKey)
            fObj.put("facetType", facet.facetType)
            fObj.put("surfaceScope", facet.surfaceScope)
            fObj.put("displayLabel", facet.displayLabel)
            fObj.put("score", facet.score)
            fObj.put("confidence", facet.confidence)
            fObj.put("provenance", facet.provenance)
            fObj.put("evidenceSummary", facet.evidenceSummary)
            fObj.put("includeInAssistantContext", facet.includeInAssistantContext)
            fObj.put("isSuppressedByUser", facet.isSuppressedByUser)
            fObj.put("firstObservedAtMs", facet.firstObservedAtMs)
            fObj.put("lastReinforcedAtMs", facet.lastReinforcedAtMs)
            fObj.put("expiresAtMs", facet.expiresAtMs ?: JSONObject.NULL)
            fObj.put("hasBehavioralEvidence", facet.hasBehavioralEvidence)
            fObj.put("independentEvidenceCount", facet.independentEvidenceCount)
            fObj.put("supportingSignalTypesCsv", facet.supportingSignalTypesCsv)
            facetsArray.put(fObj)
        }
        root.put("userTasteFacets", facetsArray)

        val signalsArray = JSONArray()
        for (sig in backup.intelligenceSignals) {
            val sObj = JSONObject()
            sObj.put("signalType", sig.signalType)
            sObj.put("surfaceScope", sig.surfaceScope)
            sObj.put("targetKey", sig.targetKey)
            sObj.put("topicsCsv", sig.topicsCsv)
            sObj.put("isMediaCue", sig.isMediaCue)
            sObj.put("strength", sig.strength)
            sObj.put("createdAtMs", sig.createdAtMs)
            signalsArray.put(sObj)
        }
        root.put("intelligenceSignals", signalsArray)

        val feedbackArray = JSONArray()
        for (tf in backup.titleFeedback) {
            val tfObj = JSONObject()
            tfObj.put("mediaKey", tf.mediaKey)
            tfObj.put("compositeId", tf.compositeId)
            tfObj.put("providerId", tf.providerId)
            tfObj.put("titleId", tf.titleId)
            tfObj.put("canonicalId", tf.canonicalId ?: JSONObject.NULL)
            tfObj.put("kind", tf.kind)
            tfObj.put("reaction", tf.reaction)
            tfObj.put("genresCsv", tf.genresCsv)
            tfObj.put("updatedAtMs", tf.updatedAtMs)
            feedbackArray.put(tfObj)
        }
        root.put("titleFeedback", feedbackArray)

        val dismissalsArray = JSONArray()
        for (d in backup.suggestionDismissals) {
            val dObj = JSONObject()
            dObj.put("suggestionType", d.suggestionType)
            dObj.put("linkIdentityHash", d.linkIdentityHash)
            dObj.put("dismissedAtMs", d.dismissedAtMs)
            dObj.put("suppressUntilMs", d.suppressUntilMs ?: JSONObject.NULL)
            dismissalsArray.put(dObj)
        }
        root.put("suggestionDismissals", dismissalsArray)

        val watchlistArray = JSONArray()
        for (item in backup.movieWatchlist) {
            val wObj = JSONObject()
            wObj.put("compositeId", item.compositeId)
            wObj.put("providerId", item.providerId)
            wObj.put("titleId", item.titleId)
            wObj.put("kind", item.kind)
            wObj.put("title", item.title)
            wObj.put("posterUrl", item.posterUrl ?: JSONObject.NULL)
            wObj.put("backdropUrl", item.backdropUrl ?: JSONObject.NULL)
            wObj.put("releaseYear", item.releaseYear ?: JSONObject.NULL)
            wObj.put("rating", item.rating ?: JSONObject.NULL)
            wObj.put("genresCsv", item.genresCsv)
            wObj.put("summary", item.summary ?: JSONObject.NULL)
            wObj.put("sourceBadge", item.sourceBadge)
            wObj.put("isPlayableHint", item.isPlayableHint)
            wObj.put("addedAt", item.addedAt)
            wObj.put("canonicalId", item.canonicalId ?: JSONObject.NULL)
            watchlistArray.put(wObj)
        }
        root.put("movieWatchlist", watchlistArray)

        backup.personalizationPreferences?.let { pPrefs ->
            val pObj = JSONObject()
            pObj.put("assistantPersonalContextEnabled", pPrefs.assistantPersonalContextEnabled)
            root.put("personalizationPreferences", pObj)
        }

        return root.toString(2)
    }

    fun deserializeBackup(jsonString: String): LaterboxBackup {
        val root = JSONObject(jsonString)
        val version = root.optInt("version", 1)
        val appName = root.optString("appName", "Laterbox")
        val exportedAt = root.optLong("exportedAt", System.currentTimeMillis())

        val collections = mutableListOf<BackupCollection>()
        val colArray = root.optJSONArray("collections")
        if (colArray != null) {
            for (i in 0 until colArray.length()) {
                val cObj = colArray.getJSONObject(i)
                collections.add(
                    BackupCollection(
                        name = cObj.getString("name"),
                        createdAt = cObj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        }

        val links = mutableListOf<BackupLink>()
        val linkArray = root.optJSONArray("links")
        if (linkArray != null) {
            for (i in 0 until linkArray.length()) {
                val lObj = linkArray.getJSONObject(i)
                val contentType = try {
                    ContentType.valueOf(lObj.optString("contentType", "WEBSITE"))
                } catch (e: Exception) {
                    ContentType.WEBSITE
                }

                val colNamesList = mutableListOf<String>()
                val cNamesArray = lObj.optJSONArray("collectionNames")
                if (cNamesArray != null) {
                    for (j in 0 until cNamesArray.length()) {
                        colNamesList.add(cNamesArray.getString(j))
                    }
                }
                val legacyCol = lObj.optNullableString("collectionName")
                if (colNamesList.isEmpty() && legacyCol != null) {
                    colNamesList.add(legacyCol)
                }

                val reminderAt = if (lObj.has("reminderAt") && !lObj.isNull("reminderAt")) {
                    lObj.optLong("reminderAt", 0L).takeIf { it > 0L }
                } else {
                    null
                }

                links.add(
                    BackupLink(
                        originalUrl = lObj.getString("originalUrl"),
                        normalizedUrl = lObj.optString("normalizedUrl", lObj.getString("originalUrl")),
                        title = lObj.getString("title"),
                        description = lObj.optNullableString("description"),
                        siteName = lObj.optNullableString("siteName"),
                        domain = lObj.getString("domain"),
                        faviconUrl = lObj.optNullableString("faviconUrl"),
                        previewImageUrl = lObj.optNullableString("previewImageUrl"),
                        contentType = contentType,
                        personalNote = lObj.optNullableString("personalNote"),
                        collectionName = legacyCol ?: colNamesList.firstOrNull(),
                        collectionNames = colNamesList,
                        isRead = lObj.optBoolean("isRead", false),
                        isArchived = lObj.optBoolean("isArchived", false),
                        reminderAt = reminderAt,
                        createdAt = lObj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        }

        val facets = mutableListOf<BackupTasteFacet>()
        val facetsArray = root.optJSONArray("userTasteFacets")
        if (facetsArray != null) {
            for (i in 0 until facetsArray.length()) {
                val fObj = facetsArray.getJSONObject(i)
                val expiresAtMs = if (fObj.has("expiresAtMs") && !fObj.isNull("expiresAtMs")) {
                    fObj.optLong("expiresAtMs", 0L).takeIf { it > 0L }
                } else {
                    null
                }
                facets.add(
                    BackupTasteFacet(
                        facetKey = fObj.optString("facetKey", ""),
                        facetType = fObj.optString("facetType", TasteFacetType.ENDURING_INTEREST.name),
                        surfaceScope = fObj.optString("surfaceScope", IntelligenceSurfaceScope.LIBRARY.name),
                        displayLabel = fObj.optString("displayLabel", ""),
                        score = fObj.optDouble("score", 0.5),
                        confidence = fObj.optDouble("confidence", 0.5),
                        provenance = fObj.optString("provenance", ""),
                        evidenceSummary = fObj.optString("evidenceSummary", ""),
                        includeInAssistantContext = fObj.optBoolean("includeInAssistantContext", false),
                        isSuppressedByUser = fObj.optBoolean("isSuppressedByUser", false),
                        firstObservedAtMs = fObj.optLong("firstObservedAtMs", 0L),
                        lastReinforcedAtMs = fObj.optLong("lastReinforcedAtMs", 0L),
                        expiresAtMs = expiresAtMs,
                        hasBehavioralEvidence = fObj.optBoolean("hasBehavioralEvidence", true),
                        independentEvidenceCount = fObj.optInt("independentEvidenceCount", 1),
                        supportingSignalTypesCsv = fObj.optString("supportingSignalTypesCsv", "")
                    )
                )
            }
        }

        val signals = mutableListOf<BackupIntelligenceSignal>()
        val signalsArray = root.optJSONArray("intelligenceSignals")
        if (signalsArray != null) {
            for (i in 0 until signalsArray.length()) {
                val sObj = signalsArray.optJSONObject(i) ?: continue
                signals.add(
                    BackupIntelligenceSignal(
                        signalType = sObj.optString("signalType", ""),
                        surfaceScope = sObj.optString("surfaceScope", IntelligenceSurfaceScope.LIBRARY.name),
                        targetKey = sObj.optString("targetKey", ""),
                        topicsCsv = sObj.optString("topicsCsv", ""),
                        isMediaCue = sObj.optBoolean("isMediaCue", false),
                        strength = sObj.optDouble("strength", 0.5),
                        createdAtMs = sObj.optLong("createdAtMs", 0L)
                    )
                )
            }
        }

        val titleFeedback = mutableListOf<BackupTitleFeedback>()
        val feedbackArray = root.optJSONArray("titleFeedback")
        if (feedbackArray != null) {
            for (i in 0 until feedbackArray.length()) {
                val tfObj = feedbackArray.optJSONObject(i) ?: continue
                titleFeedback.add(
                    BackupTitleFeedback(
                        mediaKey = tfObj.optString("mediaKey", ""),
                        compositeId = tfObj.optString("compositeId", ""),
                        providerId = tfObj.optString("providerId", ""),
                        titleId = tfObj.optString("titleId", ""),
                        canonicalId = tfObj.optNullableString("canonicalId"),
                        kind = tfObj.optString("kind", "movie"),
                        reaction = tfObj.optString("reaction", ""),
                        genresCsv = tfObj.optString("genresCsv", ""),
                        updatedAtMs = tfObj.optLong("updatedAtMs", 0L)
                    )
                )
            }
        }

        val dismissals = mutableListOf<BackupSuggestionDismissal>()
        val dismissalsArray = root.optJSONArray("suggestionDismissals")
        if (dismissalsArray != null) {
            for (i in 0 until dismissalsArray.length()) {
                val dObj = dismissalsArray.optJSONObject(i) ?: continue
                val suppressUntil = if (dObj.has("suppressUntilMs") && !dObj.isNull("suppressUntilMs")) {
                    dObj.optLong("suppressUntilMs", 0L).takeIf { it > 0L }
                } else {
                    null
                }
                dismissals.add(
                    BackupSuggestionDismissal(
                        suggestionType = dObj.optString("suggestionType", ""),
                        linkIdentityHash = dObj.optString("linkIdentityHash", ""),
                        dismissedAtMs = dObj.optLong("dismissedAtMs", 0L),
                        suppressUntilMs = suppressUntil
                    )
                )
            }
        }

        val watchlist = mutableListOf<BackupMovieWatchlistItem>()
        val watchlistArray = root.optJSONArray("movieWatchlist")
        if (watchlistArray != null) {
            for (i in 0 until watchlistArray.length()) {
                val wObj = watchlistArray.getJSONObject(i)
                val releaseYear = if (wObj.has("releaseYear") && !wObj.isNull("releaseYear")) {
                    wObj.optInt("releaseYear")
                } else {
                    null
                }
                val rating = if (wObj.has("rating") && !wObj.isNull("rating")) {
                    wObj.optDouble("rating")
                } else {
                    null
                }
                watchlist.add(
                    BackupMovieWatchlistItem(
                        compositeId = wObj.getString("compositeId"),
                        providerId = wObj.getString("providerId"),
                        titleId = wObj.getString("titleId"),
                        kind = wObj.optString("kind", "movie"),
                        title = wObj.getString("title"),
                        posterUrl = wObj.optNullableString("posterUrl"),
                        backdropUrl = wObj.optNullableString("backdropUrl"),
                        releaseYear = releaseYear,
                        rating = rating,
                        genresCsv = wObj.optString("genresCsv", ""),
                        summary = wObj.optNullableString("summary"),
                        sourceBadge = wObj.optString("sourceBadge", "OPEN_ARCHIVE"),
                        isPlayableHint = wObj.optBoolean("isPlayableHint", false),
                        addedAt = wObj.optLong("addedAt", System.currentTimeMillis()),
                        canonicalId = wObj.optNullableString("canonicalId")
                    )
                )
            }
        }

        val personalizationPrefs = root.optJSONObject("personalizationPreferences")?.let { pObj ->
            BackupPersonalizationPreferences(
                assistantPersonalContextEnabled = pObj.optBoolean("assistantPersonalContextEnabled", false)
            )
        }

        return LaterboxBackup(
            version = version,
            exportedAt = exportedAt,
            appName = appName,
            collections = collections,
            links = links,
            userTasteFacets = facets,
            intelligenceSignals = signals,
            titleFeedback = titleFeedback,
            suggestionDismissals = dismissals,
            movieWatchlist = watchlist,
            personalizationPreferences = personalizationPrefs
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        val str = optString(key)
        return str.ifEmpty { null }
    }

    companion object {
        private const val MAX_FUTURE_SKEW_MS = 24L * 60L * 60L * 1000L
    }
}
