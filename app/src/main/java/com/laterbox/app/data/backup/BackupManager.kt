package com.laterbox.app.data.backup

import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.local.entity.CollectionEntity
import com.laterbox.app.data.local.entity.LinkCollectionCrossRef
import com.laterbox.app.data.local.entity.SavedLinkEntity
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.domain.model.MetadataStatus
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
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun exportToJson(outputStream: OutputStream): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val collectionDao = database.collectionDao()
            val savedLinkDao = database.savedLinkDao()

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

            val backup = LaterboxBackup(
                version = 1,
                exportedAt = nowMsProvider(),
                collections = collections,
                links = allLinks
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
            val nowMs = nowMsProvider()

            var restoredCollectionsCount = 0
            var restoredLinksCount = 0
            var skippedDuplicatesCount = 0

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

            Result.success(
                BackupRestoreResult(
                    restoredLinksCount = restoredLinksCount,
                    skippedDuplicatesCount = skippedDuplicatesCount,
                    restoredCollectionsCount = restoredCollectionsCount
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
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

        return LaterboxBackup(
            version = version,
            exportedAt = exportedAt,
            appName = appName,
            collections = collections,
            links = links
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        val str = optString(key)
        return str.ifEmpty { null }
    }
}
