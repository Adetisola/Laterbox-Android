package com.laterbox.app.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.laterbox.app.LaterboxApplication
import com.laterbox.app.ai.RemoteCapabilities
import com.laterbox.app.di.AppContainer
import com.laterbox.app.reminders.smart.SmartReminderScheduler
import com.laterbox.app.reminders.smart.SmartScheduleType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One-time idempotent migration (`alarms_migrated_v2`) that cancels legacy `v1.0.84`
 * PendingIntents and active notifications before rescheduling active future commitments
 * and smart schedules on the disjoint [NotificationIds] bands.
 */
object AlarmMigrationManager {

    private const val TAG = "AlarmMigrationManager"
    private val migrationMutex = Mutex()

    suspend fun migrateIfNeeded(
        context: Context,
        containerOverride: AppContainer? = null
    ): Boolean {
        val appContainer = containerOverride
            ?: (context.applicationContext as? LaterboxApplication)?.container
            ?: return false

        return migrationMutex.withLock {
            val prefs = appContainer.userPreferencesRepository.userPreferencesFlow.first()
            if (prefs.alarmsMigratedV2) {
                return@withLock false
            }

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val scheduler = ReminderScheduler(context)

            // 1. Cancel legacy v1.0.84 Smart Digest alarm (requestCode = 888_888)
            scheduler.cancelLegacySmartDigest()

            // 2. Cancel legacy v1.0.84 SmartSchedule alarms (900_001 .. 900_006)
            if (alarmManager != null) {
                val smartIntent = Intent(context, ReminderReceiver::class.java).apply {
                    action = ReminderReceiver.ACTION_TRIGGER_SMART_SCHEDULE
                }
                for (type in SmartScheduleType.values()) {
                    val legacyCode = NotificationIds.LEGACY_SMART_ALARM_BASE_REQUEST_CODE + type.ordinal + 1
                    cancelPendingIntentIfExists(context, alarmManager, legacyCode, smartIntent)
                }
            }

            // 3. Cancel legacy v1.0.84 Movie Viewing Reminder alarms (700_000 .. 949_999)
            val allMovieReminders = runCatching {
                appContainer.database.moviesDao().getAllViewingRemindersSnapshot()
            }.getOrDefault(emptyList())
            if (alarmManager != null) {
                val movieIntent = Intent(context, ReminderReceiver::class.java).apply {
                    action = ReminderReceiver.ACTION_TRIGGER_MOVIE_REMINDER
                }
                for (reminder in allMovieReminders) {
                    val legacyCode = NotificationIds.legacyMovieReminderRequestCode(reminder.mediaKey)
                    cancelPendingIntentIfExists(context, alarmManager, legacyCode, movieIntent)
                }
            }

            // 4. Cancel legacy v1.0.84 SavedLink alarms (requestCode = linkId.hashCode())
            val scheduledLinks = runCatching {
                appContainer.database.savedLinkDao().getScheduledReminders(0L)
            }.getOrDefault(emptyList())
            if (alarmManager != null) {
                val linkIntent = Intent(context, ReminderReceiver::class.java).apply {
                    action = ReminderReceiver.ACTION_TRIGGER_REMINDER
                }
                for (item in scheduledLinks) {
                    cancelPendingIntentIfExists(context, alarmManager, item.link.id.hashCode(), linkIntent)
                }
            }

            // 5. Cancel legacy active notifications in colliding bands
            if (notificationManager != null) {
                notificationManager.cancel(NotificationIds.LEGACY_APP_HEALTH_NOTIFICATION_ID)
                notificationManager.cancel(NotificationIds.LEGACY_SUMMARY_NOTIFICATION_ID)
                for (item in scheduledLinks) {
                    if (item.link.id < 100_000L) {
                        notificationManager.cancel(item.link.id.toInt())
                    }
                }
                for (reminder in allMovieReminders) {
                    notificationManager.cancel(NotificationIds.legacyMovieReminderRequestCode(reminder.mediaKey))
                }
                runCatching {
                    for (sbn in notificationManager.activeNotifications.orEmpty()) {
                        if (sbn.id in 700_000..959_999) {
                            notificationManager.cancel(sbn.id)
                        }
                    }
                }
            }

            // 6. Persist migration marker
            appContainer.userPreferencesRepository.markAlarmsMigratedV2()

            // 7. Reschedule active future alarms on new disjoint bands
            val nowMs = System.currentTimeMillis()
            for (item in scheduledLinks) {
                val link = item.link
                val reminderAt = link.reminderAt ?: continue
                if (!link.isRead && !link.isArchived && !link.nudgeDismissed && reminderAt > nowMs) {
                    scheduler.scheduleReminder(
                        linkId = link.id,
                        triggerAtMillis = reminderAt,
                        title = link.title,
                        url = link.originalUrl
                    )
                }
            }

            for (reminder in allMovieReminders) {
                if (!reminder.isDismissed && reminder.triggerAtMillis > nowMs) {
                    scheduler.scheduleMovieViewingReminder(
                        mediaKey = reminder.mediaKey,
                        providerId = reminder.providerId,
                        titleId = reminder.titleId,
                        title = reminder.title,
                        triggerAtMillis = reminder.triggerAtMillis,
                        note = reminder.note
                    )
                }
            }

            val smartSettings = prefs.toSmartReminderSettings()
            val remoteEnabled = runCatching {
                appContainer.remoteConfigManager.isFeatureEnabled(RemoteCapabilities.KEY_SMART_REMINDERS)
            }.getOrDefault(true)
            SmartReminderScheduler(context).sync(
                settings = smartSettings.copy(enabled = smartSettings.enabled && remoteEnabled),
                skipMigration = true
            )

            Log.d(
                TAG,
                "Completed alarms_migrated_v2: migrated ${scheduledLinks.size} links and ${allMovieReminders.size} movie reminders"
            )
            true
        }
    }

    private fun cancelPendingIntentIfExists(
        context: Context,
        alarmManager: AlarmManager,
        requestCode: Int,
        intent: Intent
    ) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }
}
