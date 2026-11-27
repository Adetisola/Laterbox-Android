package com.laterbox.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.laterbox.app.LaterboxApplication
import com.laterbox.app.data.local.entity.SavedLinkEntity
import com.laterbox.app.data.preferences.UserPreferences
import com.laterbox.app.data.preferences.UserPreferencesRepository
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.movies.data.local.entity.MovieViewingReminderEntity
import com.laterbox.app.movies.data.local.entity.MovieWatchlistEntity
import com.laterbox.app.reminders.smart.ScheduleCalculator
import com.laterbox.app.reminders.smart.SmartReminderScheduler
import com.laterbox.app.reminders.smart.SmartScheduleType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(application = LaterboxApplication::class, sdk = [34])
class NotificationGovernorTest {

    private lateinit var app: LaterboxApplication
    private lateinit var prefsRepo: UserPreferencesRepository
    private lateinit var governor: NotificationGovernor

    private val fixedZone: ZoneId = ZoneId.of("UTC")

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        prefsRepo = app.container.userPreferencesRepository
        prefsRepo.resetNotificationGovernorStateForTest()
        governor = NotificationGovernor(
            userPreferencesRepository = prefsRepo,
            zoneIdProvider = { fixedZone }
        )
        governor.resetInMemorySwipeStateForTest()
    }

    @Test
    fun notificationIds_allBandsAreStrictlyDisjointAcross1000Inputs() {
        val dlProg = mutableSetOf<Int>()
        val dlCancel = mutableSetOf<Int>()
        val linkIds = mutableSetOf<Int>()
        val movieNotif = mutableSetOf<Int>()
        val movieDismiss = mutableSetOf<Int>()
        val tier2 = mutableSetOf<Int>()
        val tier3 = mutableSetOf<Int>()

        for (i in 0 until 1_200) {
            val linkId = i.toLong() + 1L
            val taskId = "task-$i-${i * 31}"
            val mediaKey = "tvmaze:$i:s${i % 5}"
            val tipId = "tip_$i"

            val dp = NotificationIds.downloadProgressNotificationId(taskId)
            val dc = NotificationIds.downloadCancelRequestCode(taskId)
            assertTrue(dp in 10_000..42_767)
            assertTrue(dc in 50_000..82_767)
            dlProg.add(dp)
            dlCancel.add(dc)

            val la = NotificationIds.linkReminderAlarmRequestCode(linkId)
            val lc = NotificationIds.linkReminderContentRequestCode(linkId)
            val lb = NotificationIds.linkReminderOpenBrowserRequestCode(linkId)
            val ld = NotificationIds.linkReminderDismissRequestCode(linkId)
            assertTrue(la in 100_000..199_999)
            assertTrue(lc in 200_000..299_999)
            assertTrue(lb in 300_000..399_999)
            assertTrue(ld in 400_000..499_999)
            linkIds.addAll(listOf(la, lc, lb, ld))

            val mn = NotificationIds.movieReminderId(mediaKey)
            val md = NotificationIds.movieReminderDismissRequestCode(mediaKey)
            assertTrue(mn in 500_000..699_999)
            assertTrue(md in 700_000..899_999)
            movieNotif.add(mn)
            movieDismiss.add(md)

            val ext = NotificationIds.extractionFailedNotificationId(linkId)
            val extAi = NotificationIds.extractionFailedAskAiRequestCode(linkId)
            val dlf = NotificationIds.downloadFailedNotificationId(taskId)
            assertTrue(ext in 900_000..909_999)
            assertTrue(extAi in 910_000..919_999)
            assertTrue(dlf in 920_000..928_999)
            tier2.addAll(listOf(ext, extAi, dlf, NotificationIds.APP_HEALTH_NOTIFICATION_ID))

            val sd = NotificationIds.smartDigestNotificationId(linkId)
            val sdc = NotificationIds.smartDigestContentRequestCode(linkId)
            val sdb = NotificationIds.smartDigestOpenBrowserRequestCode(linkId)
            val sdd = NotificationIds.smartDigestDismissRequestCode(linkId)
            val tip = NotificationIds.helpfulTipNotificationId(tipId)
            val mcw = NotificationIds.moviesContinueWatchingNotificationId(mediaKey)
            val mns = NotificationIds.moviesNewSeasonNotificationId(i.toString())
            val mwl = NotificationIds.moviesWatchlistNotificationId(mediaKey)
            val del = NotificationIds.tier3DeleteIntentRequestCode(sd)
            listOf(sd, sdc, sdb, sdd, tip, mcw, mns, mwl, del, NotificationIds.CONTEXTUAL_SUMMARY_NOTIFICATION_ID).forEach { id ->
                assertTrue("Tier3 ID $id out of 930_000..999_999", id in 930_000..999_999)
                tier3.add(id)
            }
        }
        SmartScheduleType.values().forEach { type ->
            val code = NotificationIds.smartScheduleAlarmRequestCode(type.ordinal)
            assertTrue(code in 930_001..930_006)
            tier3.add(code)
        }

        val bands = listOf(
            setOf(0, 4107, 4108, 5101, 5102, 5103),
            dlProg,
            dlCancel,
            linkIds,
            movieNotif,
            movieDismiss,
            tier2,
            tier3
        )
        for (a in bands.indices) {
            for (b in a + 1 until bands.size) {
                val intersection = bands[a].intersect(bands[b])
                assertTrue("Bands $a and $b overlap: $intersection", intersection.isEmpty())
            }
        }
    }

    @Test
    fun tier3DeleteIntentRequestCode_isOneToOneAcrossEntireTier3SpaceAndDisjointFromAllOtherBands() {
        val allTier3NotificationIds = buildList {
            addAll(931_000..935_999)
            add(NotificationIds.CONTEXTUAL_SUMMARY_NOTIFICATION_ID)
            addAll(952_000..956_095)
            addAll(960_000..964_095)
            addAll(965_000..969_095)
            addAll(970_000..974_095)
        }
        val deleteRequestCodes = mutableSetOf<Int>()
        for (notifId in allTier3NotificationIds) {
            val code = NotificationIds.tier3DeleteIntentRequestCode(notifId)
            assertTrue("Delete requestCode $code out of 975_000..999_191 for notifId=$notifId", code in 975_000..999_191)
            assertTrue("Tier-3 deleteIntent collision at notifId=$notifId -> code=$code", deleteRequestCodes.add(code))
        }
        assertEquals(allTier3NotificationIds.size, deleteRequestCodes.size)

        val otherTier3Ids = buildSet {
            addAll(allTier3NotificationIds)
            SmartScheduleType.values().forEach { add(NotificationIds.smartScheduleAlarmRequestCode(it.ordinal)) }
            addAll(936_000..950_999)
        }
        assertTrue(deleteRequestCodes.intersect(otherTier3Ids).isEmpty())
        assertTrue(deleteRequestCodes.all { it in 930_000..999_999 })
    }

    @Test
    fun tier1_commitments_alwaysBypassQuietHoursAndDailyCapWithoutConsumingQuota() = runBlocking {
        val nowMs = Instant.parse("2026-12-01T23:30:00Z").toEpochMilli()
        val epochDay = Instant.ofEpochMilli(nowMs).atZone(fixedZone).toLocalDate().toEpochDay()
        val exhaustedPrefs = UserPreferences(
            quietHoursEnabled = true,
            quietHoursStartHour = 22,
            quietHoursEndHour = 8,
            smartRemindersEnabled = false,
            tier3BudgetDayEpoch = epochDay,
            tier3DailyCount = 2,
            lastTier3NotificationAt = nowMs - 60_000L
        )

        assertEquals(
            GovernorDecision.Allow,
            governor.evaluate(NotificationCategory.MANUAL_LINK_REMINDER, exhaustedPrefs, nowMs = nowMs, currentHour = 23, postedTier3InCurrentWakeup = true)
        )
        assertEquals(
            GovernorDecision.Allow,
            governor.evaluate(NotificationCategory.MOVIE_VIEWING_REMINDER, exhaustedPrefs, nowMs = nowMs, currentHour = 2, postedTier3InCurrentWakeup = true)
        )
        assertEquals(0, prefsRepo.userPreferencesFlow.first().tier3DailyCount)
    }

    @Test
    fun tier2_operational_respectsQuietHoursAndCooldown_exceptForegroundProgress_andNeverUsesTier3Quota() = runBlocking {
        val nowMs = Instant.parse("2026-12-01T14:00:00Z").toEpochMilli()
        val quietPrefs = UserPreferences(quietHoursEnabled = true, quietHoursStartHour = 22, quietHoursEndHour = 8)

        assertTrue(
            governor.evaluate(NotificationCategory.EXTRACTION_FAILURE, quietPrefs, nowMs = nowMs, currentHour = 23)
                is GovernorDecision.Suppress
        )
        assertEquals(
            GovernorDecision.Allow,
            governor.evaluate(NotificationCategory.FOREGROUND_PROGRESS, quietPrefs, nowMs = nowMs, currentHour = 23, isForegroundProgress = true)
        )

        val recentHealthPrefs = quietPrefs.copy(lastAppHealthAlertAt = nowMs - 3_600_000L)
        assertTrue(
            governor.evaluate(NotificationCategory.APP_HEALTH, recentHealthPrefs, nowMs = nowMs, currentHour = 14)
                is GovernorDecision.Suppress
        )
        assertEquals(
            GovernorDecision.Allow,
            governor.evaluate(NotificationCategory.DOWNLOAD_FAILURE, recentHealthPrefs, nowMs = nowMs, currentHour = 14)
        )
    }

    @Test
    fun tier3_suggestions_enforceQuietHours_wakeupCap_dailyCap_spacing_andNextDayReset() = runBlocking {
        val day1Morning = Instant.parse("2026-12-01T09:00:00Z").toEpochMilli()
        val day1Evening = day1Morning + 7L * 3_600_000L
        val day2Morning = day1Morning + 24L * 3_600_000L

        val basePrefs = UserPreferences(
            smartRemindersEnabled = true,
            smartNotificationsSummaries = true,
            smartNotificationsTips = true,
            smartNotificationsMoviesTv = true,
            quietHoursEnabled = true,
            quietHoursStartHour = 22,
            quietHoursEndHour = 8
        )

        // Quiet hours suppression
        assertTrue(
            governor.evaluate(NotificationCategory.SMART_DIGEST, basePrefs, nowMs = day1Morning, currentHour = 23)
                is GovernorDecision.Suppress
        )
        // Wakeup burst cap suppression
        assertTrue(
            governor.evaluate(
                NotificationCategory.CONTEXTUAL_SUMMARY,
                basePrefs,
                nowMs = day1Morning,
                currentHour = 9,
                postedTier3InCurrentWakeup = true
            ) is GovernorDecision.Suppress
        )

        // Post #1
        assertEquals(
            GovernorDecision.Allow,
            governor.evaluate(NotificationCategory.SMART_DIGEST, basePrefs, nowMs = day1Morning, currentHour = 9)
        )
        governor.recordTier3Posted(NotificationCategory.SMART_DIGEST, day1Morning)

        // Spacing cooldown (< 6h) suppresses #2
        val prefsAfter1 = prefsRepo.userPreferencesFlow.first().copy(
            smartNotificationsSummaries = true,
            quietHoursEnabled = false
        )
        assertTrue(
            governor.evaluate(
                NotificationCategory.CONTEXTUAL_SUMMARY,
                prefsAfter1,
                nowMs = day1Morning + 2L * 3_600_000L,
                currentHour = 11
            ) is GovernorDecision.Suppress
        )

        // After >= 6h spacing, #2 allowed
        assertEquals(
            GovernorDecision.Allow,
            governor.evaluate(
                NotificationCategory.CONTEXTUAL_SUMMARY,
                prefsAfter1,
                nowMs = day1Evening,
                currentHour = 16
            )
        )
        governor.recordTier3Posted(NotificationCategory.CONTEXTUAL_SUMMARY, day1Evening)

        // Shared daily cap (2/day) blocks #3 on same day even after spacing
        val prefsAfter2 = prefsRepo.userPreferencesFlow.first().copy(
            smartNotificationsTips = true,
            quietHoursEnabled = false
        )
        assertTrue(
            governor.evaluate(
                NotificationCategory.HELPFUL_TIP,
                prefsAfter2,
                nowMs = day1Evening + 7L * 3_600_000L,
                currentHour = 23
            ) is GovernorDecision.Suppress
        )

        // Next calendar day resets budget cleanly
        assertEquals(
            GovernorDecision.Allow,
            governor.evaluate(
                NotificationCategory.HELPFUL_TIP,
                prefsAfter2,
                nowMs = day2Morning,
                currentHour = 9
            )
        )
    }

    @Test
    fun clearAllBatchGuard_ignoresRapidBatchSwipes_whileIsolatedSwipeAppliesBackoff() = runBlocking {
        val baseMs = Instant.parse("2026-12-01T12:00:00Z").toEpochMilli()
        val prefs = UserPreferences(
            smartRemindersEnabled = true,
            smartNotificationsSummaries = true,
            smartNotificationsTips = true,
            quietHoursEnabled = false
        )

        // Clear-All burst: 2 swipes within 1500 ms -> zero backoff
        governor.onTier3NotificationSwiped(NotificationCategory.CONTEXTUAL_SUMMARY, baseMs)
        governor.onTier3NotificationSwiped(NotificationCategory.HELPFUL_TIP, baseMs + 250L)
        governor.flushPendingSwipeIfMature(baseMs + 5_000L)

        val afterClearAll = prefsRepo.userPreferencesFlow.first().copy(
            smartNotificationsSummaries = true,
            smartNotificationsTips = true,
            quietHoursEnabled = false
        )
        assertTrue(afterClearAll.tier3BackoffUntilMap().isEmpty())
        assertEquals(
            GovernorDecision.Allow,
            governor.evaluate(NotificationCategory.CONTEXTUAL_SUMMARY, afterClearAll, nowMs = baseMs + 5_000L, currentHour = 12)
        )

        // Isolated single swipe followed by > 1500 ms -> applies 24h backoff to HELPFUL_TIP only
        val swipeMs = baseMs + 10_000L
        governor.onTier3NotificationSwiped(NotificationCategory.HELPFUL_TIP, swipeMs)
        assertTrue(governor.flushPendingSwipeIfMature(swipeMs + 2_000L))

        val afterIsolated = prefsRepo.userPreferencesFlow.first().copy(
            smartNotificationsSummaries = true,
            smartNotificationsTips = true,
            quietHoursEnabled = false
        )
        assertTrue(
            governor.evaluate(NotificationCategory.HELPFUL_TIP, afterIsolated, nowMs = swipeMs + 3_000L, currentHour = 12)
                is GovernorDecision.Suppress
        )
        assertEquals(
            GovernorDecision.Allow,
            governor.evaluate(NotificationCategory.CONTEXTUAL_SUMMARY, afterIsolated, nowMs = swipeMs + 3_000L, currentHour = 12)
        )
    }

    @Test
    fun reminderReceiver_smartSchedulePostsAtMostOneTier3NotificationAndReschedulesNext() = runBlocking {
        val db = app.container.database
        val linkDao = db.savedLinkDao()
        val now = System.currentTimeMillis()
        // Seed unread old links + watchlist item + tip enabled so all 3 Tier-3 engines have candidates
        repeat(4) { idx ->
            linkDao.insertLink(
                SavedLinkEntity(
                    originalUrl = "https://example.com/smart-$idx",
                    normalizedUrl = "https://example.com/smart-$idx",
                    title = "Smart Candidate $idx",
                    domain = "example.com",
                    contentType = ContentType.ARTICLE,
                    isRead = false,
                    isArchived = false,
                    createdAt = now - 48L * 3_600_000L
                )
            )
        }
        db.moviesDao().insertWatchlistItem(
            MovieWatchlistEntity(
                compositeId = "ia:metropolis",
                providerId = "ia",
                titleId = "metropolis",
                kind = "movie",
                title = "Metropolis",
                posterUrl = null,
                backdropUrl = null,
                releaseYear = 1927,
                rating = 8.3,
                genresCsv = "Sci-Fi",
                summary = null,
                sourceBadge = "OPEN_ARCHIVE",
                isPlayableHint = true,
                addedAt = now - 48L * 3_600_000L
            )
        )

        prefsRepo.setSmartRemindersEnabled(true)
        prefsRepo.setDailyDigestEnabled(true)
        prefsRepo.setSmartNotificationsSummaries(true)
        prefsRepo.setSmartNotificationsTips(true)
        prefsRepo.setSmartNotificationsMoviesTv(true)
        prefsRepo.setQuietHoursEnabled(false)

        val realNm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        realNm.cancelAll()
        val nm = Shadows.shadowOf(realNm)

        val slotKey = ScheduleCalculator.slotKey(SmartScheduleType.DAILY, ZonedDateTime.now())
        val receiver = ReminderReceiver()
        val intent = Intent(app, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TRIGGER_SMART_SCHEDULE
            putExtra(ReminderReceiver.EXTRA_SCHEDULE_TYPE, SmartScheduleType.DAILY.name)
            putExtra(ReminderReceiver.EXTRA_SLOT_KEY, slotKey)
            putExtra(ReminderReceiver.EXTRA_TARGET_MILLIS, now)
        }
        receiver.onReceive(app, intent)

        fun findNextDailyAlarm(): PendingIntent? = PendingIntent.getBroadcast(
            app,
            SmartReminderScheduler.requestCodeFor(SmartScheduleType.DAILY),
            Intent(app, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_TRIGGER_SMART_SCHEDULE
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline &&
            (app.container.userPreferencesRepository.userPreferencesFlow.first().tier3DailyCount == 0 ||
                findNextDailyAlarm() == null)
        ) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // At most 1 Tier-3 notification posted (no 4-notification burst)
        assertEquals(1, nm.allNotifications.size)
        assertEquals(1, prefsRepo.userPreferencesFlow.first().tier3DailyCount)

        // scheduleNext registered the next DAILY alarm on 930_005
        assertNotNull(findNextDailyAlarm())
    }

    @Test
    fun reminderReceiver_tier1ManualLinkDeliversDuringQuietHours() = runBlocking {
        val linkId = app.container.database.savedLinkDao().insertLink(
            SavedLinkEntity(
                originalUrl = "https://example.com/quiet-test",
                normalizedUrl = "https://example.com/quiet-test",
                title = "Urgent Manual Reminder",
                domain = "example.com",
                contentType = ContentType.ARTICLE,
                reminderAt = System.currentTimeMillis() - 1_000L
            )
        )
        val currentHour = ZonedDateTime.now().hour
        // Force Quiet Hours covering currentHour
        prefsRepo.setQuietHoursEnabled(true)
        prefsRepo.setQuietHoursWindow(currentHour, (currentHour + 2) % 24)
        prefsRepo.setSmartRemindersEnabled(false)

        val receiver = ReminderReceiver()
        val intent = Intent(app, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TRIGGER_REMINDER
            putExtra(ReminderReceiver.EXTRA_LINK_ID, linkId)
        }
        receiver.onReceive(app, intent)

        val nm = Shadows.shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        val expectedId = NotificationIds.linkReminderNotificationId(linkId)
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline && nm.getNotification(expectedId) == null) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull("Tier-1 manual reminder must deliver even during Quiet Hours", nm.getNotification(expectedId))
    }

    @Test
    fun alarmMigrationManager_cancelsLegacyPendingIntentsAndReschedulesOnNewBands() = runBlocking {
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val futureMs = System.currentTimeMillis() + 86_400_000L

        // Seed Room data for active link + movie reminder
        val linkId = app.container.database.savedLinkDao().insertLink(
            SavedLinkEntity(
                originalUrl = "https://example.com/migrated-link",
                normalizedUrl = "https://example.com/migrated-link",
                title = "Migrated Link",
                domain = "example.com",
                contentType = ContentType.ARTICLE,
                reminderAt = futureMs,
                isRead = false,
                isArchived = false,
                nudgeDismissed = false
            )
        )
        val mediaKey = "tvmaze:777"
        app.container.database.moviesDao().upsertViewingReminder(
            MovieViewingReminderEntity(
                mediaKey = mediaKey,
                compositeId = mediaKey,
                providerId = "tvmaze",
                titleId = "777",
                kind = "tv_series",
                title = "Migrated Series",
                triggerAtMillis = futureMs,
                isDismissed = false,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        // Pre-populate legacy v1.0.84 PendingIntents
        fun createLegacyPi(code: Int, action: String): PendingIntent {
            val pi = PendingIntent.getBroadcast(
                app,
                code,
                Intent(app, ReminderReceiver::class.java).apply { this.action = action },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.set(AlarmManager.RTC_WAKEUP, futureMs, pi)
            return pi
        }

        createLegacyPi(NotificationIds.LEGACY_SMART_DIGEST_REQUEST_CODE, ReminderReceiver.ACTION_TRIGGER_SMART_DIGEST)
        createLegacyPi(NotificationIds.LEGACY_SMART_ALARM_BASE_REQUEST_CODE + 1, ReminderReceiver.ACTION_TRIGGER_SMART_SCHEDULE)
        createLegacyPi(NotificationIds.legacyMovieReminderRequestCode(mediaKey), ReminderReceiver.ACTION_TRIGGER_MOVIE_REMINDER)
        createLegacyPi(linkId.hashCode(), ReminderReceiver.ACTION_TRIGGER_REMINDER)

        val migrated = AlarmMigrationManager.migrateIfNeeded(app, app.container)
        assertTrue(migrated)
        assertTrue(prefsRepo.userPreferencesFlow.first().alarmsMigratedV2)

        // Legacy PendingIntents must be canceled
        fun findPi(code: Int, action: String): PendingIntent? = PendingIntent.getBroadcast(
            app,
            code,
            Intent(app, ReminderReceiver::class.java).apply { this.action = action },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNull(findPi(NotificationIds.LEGACY_SMART_DIGEST_REQUEST_CODE, ReminderReceiver.ACTION_TRIGGER_SMART_DIGEST))
        assertNull(findPi(NotificationIds.LEGACY_SMART_ALARM_BASE_REQUEST_CODE + 1, ReminderReceiver.ACTION_TRIGGER_SMART_SCHEDULE))
        assertNull(findPi(NotificationIds.legacyMovieReminderRequestCode(mediaKey), ReminderReceiver.ACTION_TRIGGER_MOVIE_REMINDER))
        assertNull(findPi(linkId.hashCode(), ReminderReceiver.ACTION_TRIGGER_REMINDER))

        // Active future alarms must be rescheduled on new disjoint bands
        assertNotNull(findPi(NotificationIds.linkReminderAlarmRequestCode(linkId), ReminderReceiver.ACTION_TRIGGER_REMINDER))
        assertNotNull(findPi(NotificationIds.movieReminderId(mediaKey), ReminderReceiver.ACTION_TRIGGER_MOVIE_REMINDER))
        assertNotNull(findPi(SmartReminderScheduler.requestCodeFor(SmartScheduleType.DAILY), ReminderReceiver.ACTION_TRIGGER_SMART_SCHEDULE))
    }
}
