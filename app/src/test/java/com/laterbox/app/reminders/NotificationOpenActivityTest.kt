package com.laterbox.app.reminders

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.LaterboxApplication
import com.laterbox.app.data.local.entity.SavedLinkEntity
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.ui.navigation.LaterboxDestination
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = LaterboxApplication::class, sdk = [34])
class NotificationOpenActivityTest {

    private lateinit var app: LaterboxApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<LaterboxApplication>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private suspend fun awaitCount(expected: Int, timeoutMs: Long = 3_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (app.container.userPreferencesRepository.userPreferencesFlow.first().remindersOpenedCount == expected) {
                return
            }
            Thread.sleep(20)
        }
    }

    @Test
    fun movieViewingReminderOpen_incrementsRemindersOpenedCount_whileNonReminderAlertsDoNot() = runBlocking {
        val initialOpened = app.container.userPreferencesRepository.userPreferencesFlow.first().remindersOpenedCount

        // 1. Movie viewing reminder (isReminder = true, linkId = -1L, MODE_OPEN_DESTINATION)
        val postedMovie = NotificationHelper.showMovieViewingReminderNotification(
            context = app,
            mediaKey = "moviebox:m42",
            providerId = "moviebox",
            titleId = "m42",
            title = "Arrival"
        )
        assertTrue(postedMovie)
        val nm = Shadows.shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        val movieNotif = nm.getNotification(ReminderScheduler.movieReminderRequestCode("moviebox:m42"))
        assertNotNull(movieNotif)
        val movieIntent = Shadows.shadowOf(movieNotif.contentIntent).savedIntent
        assertTrue(movieIntent.getBooleanExtra(NotificationOpenActivity.EXTRA_IS_REMINDER, false))

        Robolectric.buildActivity(NotificationOpenActivity::class.java, movieIntent).create().destroy()
        awaitCount(initialOpened + 1)
        assertEquals(
            initialOpened + 1,
            app.container.userPreferencesRepository.userPreferencesFlow.first().remindersOpenedCount
        )

        // 2. App Health alert (isReminder = false) -> must NOT increment remindersOpenedCount
        val postedHealth = NotificationHelper.showAppHealthNotification(
            context = app,
            title = "Storage Check",
            body = "Low disk space"
        )
        assertTrue(postedHealth)
        val healthNotif = nm.getNotification(NotificationIds.APP_HEALTH_NOTIFICATION_ID)
        assertNotNull(healthNotif)
        val healthIntent = Shadows.shadowOf(healthNotif.contentIntent).savedIntent
        assertFalse(healthIntent.getBooleanExtra(NotificationOpenActivity.EXTRA_IS_REMINDER, true))

        Robolectric.buildActivity(NotificationOpenActivity::class.java, healthIntent).create().destroy()
        Thread.sleep(150)
        assertEquals(
            initialOpened + 1,
            app.container.userPreferencesRepository.userPreferencesFlow.first().remindersOpenedCount
        )

        // 3. Download failed alert (isReminder = false) -> must NOT increment remindersOpenedCount
        val postedDownloadFail = NotificationHelper.showDownloadFailedNotification(
            context = app,
            taskId = "task-fail-1",
            savedLinkId = -1L,
            title = "Broken Video",
            errorMessage = "Network timeout",
            dedupKey = "dl_fail_test_${System.nanoTime()}"
        )
        assertTrue(postedDownloadFail)
        val dlNotifId = NotificationIds.downloadFailedNotificationId("task-fail-1")
        val dlNotif = nm.getNotification(dlNotifId)
        assertNotNull(dlNotif)
        val dlIntent = Shadows.shadowOf(dlNotif.contentIntent).savedIntent
        assertFalse(dlIntent.getBooleanExtra(NotificationOpenActivity.EXTRA_IS_REMINDER, true))

        Robolectric.buildActivity(NotificationOpenActivity::class.java, dlIntent).create().destroy()
        Thread.sleep(150)
        assertEquals(
            initialOpened + 1,
            app.container.userPreferencesRepository.userPreferencesFlow.first().remindersOpenedCount
        )

        // 4. Extraction failed alert with linkId > 0 -> must NOT increment remindersOpenedCount
        val failedLinkId = app.container.database.savedLinkDao().insertLink(
            SavedLinkEntity(
                originalUrl = "https://example.com/failed",
                normalizedUrl = "https://example.com/failed",
                title = "Failed Clip",
                domain = "example.com",
                contentType = ContentType.VIDEO
            )
        )
        val postedExtractFail = NotificationHelper.showExtractionFailedNotification(
            context = app,
            savedLinkId = failedLinkId,
            url = "https://example.com/failed",
            linkTitle = "Failed Clip",
            errorMessage = "Unsupported format",
            dedupKey = "extract_fail_test_${System.nanoTime()}"
        )
        assertTrue(postedExtractFail)
        val extractNotif = nm.getNotification(NotificationIds.extractionFailedNotificationId(failedLinkId))
        assertNotNull(extractNotif)
        val extractIntent = Shadows.shadowOf(extractNotif.contentIntent).savedIntent
        assertFalse(extractIntent.getBooleanExtra(NotificationOpenActivity.EXTRA_IS_REMINDER, true))

        Robolectric.buildActivity(NotificationOpenActivity::class.java, extractIntent).create().destroy()
        Thread.sleep(150)
        assertEquals(
            initialOpened + 1,
            app.container.userPreferencesRepository.userPreferencesFlow.first().remindersOpenedCount
        )

        // 5. Link reminder (isReminder = true) -> increments remindersOpenedCount AND marks link read
        val reminderLinkId = app.container.database.savedLinkDao().insertLink(
            SavedLinkEntity(
                originalUrl = "https://example.com/read-later",
                normalizedUrl = "https://example.com/read-later",
                title = "Read Later",
                domain = "example.com",
                contentType = ContentType.ARTICLE
            )
        )
        val linkIntent = NotificationOpenActivity.createIntent(
            context = app,
            linkId = reminderLinkId,
            url = "https://example.com/read-later",
            notificationRecordId = -1L,
            notificationId = reminderLinkId.toInt(),
            mode = NotificationOpenActivity.MODE_OPEN_LINK_DETAIL,
            isReminder = true
        )
        Robolectric.buildActivity(NotificationOpenActivity::class.java, linkIntent).create().destroy()
        awaitCount(initialOpened + 2)
        assertEquals(
            initialOpened + 2,
            app.container.userPreferencesRepository.userPreferencesFlow.first().remindersOpenedCount
        )
        val updatedLink = app.container.database.savedLinkDao().getLinkById(reminderLinkId).first()
        assertTrue(updatedLink?.link?.isRead == true)

        // Ensure createIntent defaults isReminder to false when omitted
        val defaultDest = LaterboxDestination.MovieDetail.createRoute("moviebox", "m1")
        val defaultIntent = NotificationOpenActivity.createIntent(
            context = app,
            linkId = -1L,
            url = "laterbox://$defaultDest",
            notificationRecordId = -1L,
            notificationId = 1,
            mode = NotificationOpenActivity.MODE_OPEN_DESTINATION,
            destination = defaultDest
        )
        assertFalse(defaultIntent.getBooleanExtra(NotificationOpenActivity.EXTRA_IS_REMINDER, true))
    }
}
