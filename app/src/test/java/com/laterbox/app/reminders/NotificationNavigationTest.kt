package com.laterbox.app.reminders

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.ui.navigation.LaterboxDestination
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationNavigationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `createIntent configures MODE_OPEN_LINK_DETAIL for body tap`() {
        val intent = NotificationOpenActivity.createIntent(
            context = context,
            linkId = 42L,
            url = "https://example.com/test",
            notificationRecordId = 101L,
            notificationId = 1,
            mode = NotificationOpenActivity.MODE_OPEN_LINK_DETAIL
        )

        assertNotNull(intent)
        assertEquals(42L, intent.getLongExtra(NotificationOpenActivity.EXTRA_LINK_ID, -1L))
        assertEquals(NotificationOpenActivity.MODE_OPEN_LINK_DETAIL, intent.getStringExtra(NotificationOpenActivity.EXTRA_TARGET_MODE))
    }

    @Test
    fun `createIntent configures MODE_OPEN_BROWSER for action button`() {
        val intent = NotificationOpenActivity.createIntent(
            context = context,
            linkId = 42L,
            url = "https://example.com/test",
            notificationRecordId = 101L,
            notificationId = 1,
            mode = NotificationOpenActivity.MODE_OPEN_BROWSER
        )

        assertNotNull(intent)
        assertEquals(42L, intent.getLongExtra(NotificationOpenActivity.EXTRA_LINK_ID, -1L))
        assertEquals(NotificationOpenActivity.MODE_OPEN_BROWSER, intent.getStringExtra(NotificationOpenActivity.EXTRA_TARGET_MODE))
    }

    @Test
    fun `createIntent configures MODE_OPEN_DESTINATION for Movies detail deep link`() {
        val dest = LaterboxDestination.MovieDetail.createRoute("moviebox", "tt12345")
        val intent = NotificationOpenActivity.createIntent(
            context = context,
            linkId = -1L,
            url = "laterbox://$dest",
            notificationRecordId = 55L,
            notificationId = ReminderScheduler.movieReminderRequestCode("canonical:123"),
            mode = NotificationOpenActivity.MODE_OPEN_DESTINATION,
            destination = dest
        )

        assertNotNull(intent)
        assertEquals(NotificationOpenActivity.MODE_OPEN_DESTINATION, intent.getStringExtra(NotificationOpenActivity.EXTRA_TARGET_MODE))
        assertEquals("movies/detail/moviebox/tt12345", intent.getStringExtra(NotificationOpenActivity.EXTRA_DESTINATION))
    }

    @Test
    fun `showMovieViewingReminderNotification posts reminder with dismiss action`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val posted = NotificationHelper.showMovieViewingReminderNotification(
            context = ctx,
            mediaKey = "moviebox:m99",
            providerId = "moviebox",
            titleId = "m99",
            title = "Night Watch",
            note = "Watch tonight",
            posterUrl = null
        )

        assertTrue(posted)
        val nm = Shadows.shadowOf(ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        val notification = nm.getNotification(ReminderScheduler.movieReminderRequestCode("moviebox:m99"))
        assertNotNull(notification)
        assertEquals("Night Watch", notification.extras.getString("android.title"))
        assertTrue(notification.extras.getString("android.text").orEmpty().contains("Watch tonight"))
        assertEquals(1, notification.actions?.size ?: 0)
        assertEquals("Dismiss", notification.actions[0].title.toString())
    }
}
