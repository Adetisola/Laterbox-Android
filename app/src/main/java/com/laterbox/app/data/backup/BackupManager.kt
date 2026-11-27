package com.laterbox.app.data.backup

import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.local.entity.CollectionEntity
import com.laterbox.app.data.local.entity.LinkCollectionCrossRef
import com.laterbox.app.data.local.entity.SavedLinkEntity
import com.laterbox.app.data.local.entity.TasteFacetType
import com.laterbox.app.data.local.entity.UserTasteFacetEntity
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.domain.model.MetadataStatus
import com.laterbox.app.intelligence.TopicExtractor
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

            // Omit ephemeral intelligence_signals & movie_cache_entries; export only user taste facets & watchlist
            val facets = intelligenceDao.getAllFacetsSnapshot().map { f ->
                BackupTasteFacet(
                    facetKey = f.facetKey,
                    facetType = f.facetType,
                    surfaceScope = f.surfaceScope,
                    displayLabel = f.displayLabel,
                    score = f.score,
                    confidence = f.confidence,
                    provenance = f.provenance,
                    evidenceSummary = f.evidenceSummary,
                    includeInAssistantContext = f.includeInAssistantContext,
                    isSuppressedByUser = f.isSuppressedByUser,
                    firstObservedAtMs = f.firstObservedAtMs,
                    lastReinforcedAtMs = f.lastReinforcedAtMs,
                    expiresAtMs = f.expiresAtMs
                )
            }

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
                    personalizationEnabled = prefs.personalizationEnabled,
                    assistantPersonalContextEnabled = prefs.assistantPersonalContextEnabled
                )
            }

            val backup = LaterboxBackup(
                version = LaterboxBackup.CURRENT_BACKUP_VERSION,
                exportedAt = nowMsProvider(),
                collections = collections,
                links = allLinks,
                userTasteFacets = facets,
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

            // 1. Resolve / Create Collections
            val existingCollections = collectionDao.getCollections().first()
            val collectionMap = existingCollections.associate { it.name.lowercase() to it.id }.toMutableMap()

            for (bCol in backup.collections) {
                val key = bCol.name.trim().lowercase()
                if (!collectionMap.containsKey(key)) {
                    val id = collectionDao.insertCollection(CollectionEntity(name = bCol.name.trim()))
                    collectionMap[key] = id
                    restoredCollectionsCount++
                }
            }

            // 2. Restore Links with duplicate detection, multi-collections, and alarm re-hydration
            for (bLink in backup.links) {
                val targetNames = bLink.collectionNames.ifEmpty { listOfNotNull(bLink.collectionName) }
                val targetColIds = targetNames.mapNotNull { name ->
                    collectionMap[name.trim().lowercase()]
                }

                val existing = savedLinkDao.findLinkByNormalizedUrl(bLink.normalizedUrl)
                if (existing != null) {
                    // Duplicate found: update notes/reminder if missing locally
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

            // 3. Merge User Taste Facets safely (explicit/user-corrected/suppressed take precedence, else newest timestamp)
            for (bFacet in backup.userTasteFacets) {
                if (bFacet.facetKey.isBlank() || !TopicExtractor.isSafeFacetLabel(bFacet.displayLabel)) continue
                val importedEntity = UserTasteFacetEntity(
                    facetKey = bFacet.facetKey,
                    facetType = bFacet.facetType,
                    surfaceScope = bFacet.surfaceScope,
                    displayLabel = bFacet.displayLabel,
                    score = bFacet.score,
                    confidence = bFacet.confidence,
                    provenance = bFacet.provenance,
                    evidenceSummary = bFacet.evidenceSummary,
                    includeInAssistantContext = bFacet.includeInAssistantContext,
                    isSuppressedByUser = bFacet.isSuppressedByUser,
                    firstObservedAtMs = bFacet.firstObservedAtMs,
                    lastReinforcedAtMs = bFacet.lastReinforcedAtMs,
                    expiresAtMs = bFacet.expiresAtMs
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

            // 4. Merge Movies & TV Watchlist without duplicating canonical or composite IDs
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

            // 5. Restore Personalization Preferences (v2)
            backup.personalizationPreferences?.let { pPrefs ->
                userPreferencesRepository?.let { repo ->
                    repo.setPersonalizationEnabled(pPrefs.personalizationEnabled)
                    repo.setAssistantPersonalContextEnabled(pPrefs.assistantPersonalContextEnabled)
                }
            }

            Result.success(
                BackupRestoreResult(
                    restoredLinksCount = restoredLinksCount,
                    skippedDuplicatesCount = skippedDuplicatesCount,
                    restoredCollectionsCount = restoredCollectionsCount,
                    restoredFacetsCount = restoredFacetsCount,
                    restoredWatchlistCount = restoredWatchlistCount
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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
            facetsArray.put(fObj)
        }
        root.put("userTasteFacets", facetsArray)

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
            pObj.put("personalizationEnabled", pPrefs.personalizationEnabled)
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
                        facetKey = fObj.getString("facetKey"),
                        facetType = fObj.optString("facetType", TasteFacetType.ENDURING_INTEREST.name),
                        surfaceScope = fObj.optString("surfaceScope", "ALL"),
                        displayLabel = fObj.getString("displayLabel"),
                        score = fObj.optDouble("score", 0.5),
                        confidence = fObj.optDouble("confidence", 0.5),
                        provenance = fObj.optString("provenance", ""),
                        evidenceSummary = fObj.optString("evidenceSummary", ""),
                        includeInAssistantContext = fObj.optBoolean("includeInAssistantContext", false),
                        isSuppressedByUser = fObj.optBoolean("isSuppressedByUser", false),
                        firstObservedAtMs = fObj.optLong("firstObservedAtMs", 0L),
                        lastReinforcedAtMs = fObj.optLong("lastReinforcedAtMs", 0L),
                        expiresAtMs = expiresAtMs
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
                personalizationEnabled = pObj.optBoolean("personalizationEnabled", true),
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
            movieWatchlist = watchlist,
            personalizationPreferences = personalizationPrefs
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        val str = optString(key)
        return str.ifEmpty { null }
    }
}
