package com.laterbox.app.diagnostics

import com.laterbox.app.data.local.LaterboxDatabase
import com.laterbox.app.data.preferences.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Snapshot of purely on-device library health, reading completion, and capture metrics.
 * Computed strictly locally from Room + DataStore and never transmitted off-device.
 */
data class LocalLibraryMetrics(
    val totalLinksSaved: Int = 0,
    val inboxUnreadCount: Int = 0,
    val inboxReadCount: Int = 0,
    val archivedCount: Int = 0,
    val totalReadCount: Int = 0,
    val readCompletionPercent: Int = 0,
    val collectionsCount: Int = 0,
    val mediaAssetsCount: Int = 0,
    val estimatedStorageBytes: Long = 0L,
    val activeScheduledReminders: Int = 0,
    val readerOpensCount: Int = 0,
    val remindersDeliveredCount: Int = 0,
    val remindersOpenedCount: Int = 0,
    val reminderOpenRatePercent: Int? = null,
    val shareCapturesCount: Int = 0,
    val quickTileCapturesCount: Int = 0,
    val manualCapturesCount: Int = 0,
    val timeToFirstSaveMs: Long? = null
)

object ProductMetricsTracker {

    fun computeMetrics(
        inboxCount: Int,
        inboxUnreadCount: Int,
        archivedCount: Int,
        totalReadCount: Int,
        activeScheduledReminders: Int,
        mediaAssetsCount: Int,
        estimatedStorageBytes: Long,
        prefs: UserPreferences,
        collectionsCount: Int = 0
    ): LocalLibraryMetrics {
        val safeInbox = inboxCount.coerceAtLeast(0)
        val safeArchived = archivedCount.coerceAtLeast(0)
        val safeUnread = inboxUnreadCount.coerceIn(0, safeInbox)
        val inboxRead = (safeInbox - safeUnread).coerceAtLeast(0)
        val totalSaved = safeInbox + safeArchived
        val safeTotalRead = totalReadCount.coerceIn(0, totalSaved)

        val completionPercent = if (totalSaved > 0) {
            ((safeTotalRead.toDouble() / totalSaved.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
        } else {
            0
        }

        val delivered = prefs.remindersDeliveredCount.coerceAtLeast(0)
        val opened = prefs.remindersOpenedCount.coerceAtLeast(0)
        val openRate = if (delivered > 0) {
            ((opened.toDouble() / delivered.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
        } else {
            null
        }

        val timeToFirstSaveMs = if (prefs.firstLaunchAt > 0L && prefs.firstLinkSavedAt >= prefs.firstLaunchAt) {
            prefs.firstLinkSavedAt - prefs.firstLaunchAt
        } else {
            null
        }

        return LocalLibraryMetrics(
            totalLinksSaved = totalSaved,
            inboxUnreadCount = safeUnread,
            inboxReadCount = inboxRead,
            archivedCount = safeArchived,
            totalReadCount = safeTotalRead,
            readCompletionPercent = completionPercent,
            collectionsCount = collectionsCount.coerceAtLeast(0),
            mediaAssetsCount = mediaAssetsCount.coerceAtLeast(0),
            estimatedStorageBytes = estimatedStorageBytes.coerceAtLeast(0L),
            activeScheduledReminders = activeScheduledReminders.coerceAtLeast(0),
            readerOpensCount = prefs.readerOpensCount.coerceAtLeast(0),
            remindersDeliveredCount = delivered,
            remindersOpenedCount = opened,
            reminderOpenRatePercent = openRate,
            shareCapturesCount = prefs.shareCapturesCount.coerceAtLeast(0),
            quickTileCapturesCount = prefs.quickTileCapturesCount.coerceAtLeast(0),
            manualCapturesCount = prefs.manualCapturesCount.coerceAtLeast(0),
            timeToFirstSaveMs = timeToFirstSaveMs
        )
    }

    suspend fun collectLocalMetrics(
        database: LaterboxDatabase,
        prefs: UserPreferences
    ): LocalLibraryMetrics = withContext(Dispatchers.IO) {
        val linkDao = database.savedLinkDao()
        val collectionDao = database.collectionDao()
        val assetDao = database.downloadedAssetDao()

        val inboxCount = runCatching { linkDao.getInboxCount() }.getOrDefault(0)
        val inboxUnreadCount = runCatching { linkDao.getUnreadInboxCount() }.getOrDefault(0)
        val archivedCount = runCatching { linkDao.getArchivedCount() }.getOrDefault(0)
        val totalReadCount = runCatching { linkDao.getTotalReadCount() }.getOrDefault(0)
        val collectionsCount = runCatching { collectionDao.getCollections().first().size }.getOrDefault(0)
        val activeScheduledReminders = runCatching { linkDao.getActiveReminderCount() }.getOrDefault(0)
        val mediaAssetsCount = runCatching { assetDao.getAssetCount().first() }.getOrDefault(0)
        val storageBytes = runCatching {
            assetDao.getStorageBreakdown().first().sumOf { it.totalBytes }
        }.getOrDefault(0L)

        computeMetrics(
            inboxCount = inboxCount,
            inboxUnreadCount = inboxUnreadCount,
            archivedCount = archivedCount,
            totalReadCount = totalReadCount,
            activeScheduledReminders = activeScheduledReminders,
            mediaAssetsCount = mediaAssetsCount,
            estimatedStorageBytes = storageBytes,
            prefs = prefs,
            collectionsCount = collectionsCount
        )
    }
}
