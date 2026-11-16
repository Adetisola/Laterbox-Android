package com.laterbox.app.diagnostics

import com.laterbox.app.data.preferences.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductMetricsTrackerTest {

    @Test
    fun computeMetrics_emptyLibrary_returnsZeroCompletionAndNullRates() {
        val metrics = ProductMetricsTracker.computeMetrics(
            inboxCount = 0,
            inboxUnreadCount = 0,
            archivedCount = 0,
            totalReadCount = 0,
            activeScheduledReminders = 0,
            mediaAssetsCount = 0,
            estimatedStorageBytes = 0L,
            prefs = UserPreferences()
        )

        assertEquals(0, metrics.totalLinksSaved)
        assertEquals(0, metrics.readCompletionPercent)
        assertNull(metrics.reminderOpenRatePercent)
        assertNull(metrics.timeToFirstSaveMs)
    }

    @Test
    fun computeMetrics_populatedLibrary_calculatesCompletionOpenRateAndTimeToFirstSave() {
        val prefs = UserPreferences(
            firstLaunchAt = 1_000L,
            firstLinkSavedAt = 46_000L,
            remindersDeliveredCount = 8,
            remindersOpenedCount = 5,
            readerOpensCount = 14,
            shareCapturesCount = 7,
            quickTileCapturesCount = 2,
            manualCapturesCount = 3
        )

        val metrics = ProductMetricsTracker.computeMetrics(
            inboxCount = 6,
            inboxUnreadCount = 4,
            archivedCount = 4,
            totalReadCount = 6,
            activeScheduledReminders = 2,
            mediaAssetsCount = 3,
            estimatedStorageBytes = 12_582_912L,
            prefs = prefs
        )

        assertEquals(10, metrics.totalLinksSaved)
        assertEquals(4, metrics.inboxUnreadCount)
        assertEquals(2, metrics.inboxReadCount)
        assertEquals(4, metrics.archivedCount)
        assertEquals(60, metrics.readCompletionPercent)
        assertEquals(63, metrics.reminderOpenRatePercent)
        assertEquals(45_000L, metrics.timeToFirstSaveMs)
        assertEquals(14, metrics.readerOpensCount)
        assertEquals(7, metrics.shareCapturesCount)
        assertEquals(2, metrics.quickTileCapturesCount)
        assertEquals(3, metrics.manualCapturesCount)
    }

    @Test
    fun computeMetrics_handlesOutOfOrderTimestampsSafely() {
        val prefs = UserPreferences(
            firstLaunchAt = 10_000L,
            firstLinkSavedAt = 5_000L,
            remindersDeliveredCount = 2,
            remindersOpenedCount = 5
        )

        val metrics = ProductMetricsTracker.computeMetrics(
            inboxCount = 2,
            inboxUnreadCount = 5,
            archivedCount = 0,
            totalReadCount = 9,
            activeScheduledReminders = 1,
            mediaAssetsCount = 0,
            estimatedStorageBytes = 0L,
            prefs = prefs
        )

        assertNull(metrics.timeToFirstSaveMs)
        assertEquals(2, metrics.inboxUnreadCount)
        assertEquals(100, metrics.readCompletionPercent)
        assertEquals(100, metrics.reminderOpenRatePercent)
    }
}
