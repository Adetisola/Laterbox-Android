package com.laterbox.app.reminders

/**
 * Single source of truth for all Laterbox notification IDs and PendingIntent request codes.
 *
 * All bands are strictly disjoint:
 * - Downloads foreground progress / cancel PI: `10_000 .. 82_767`
 * - Tier 1 (Link Reminders): `100_000 .. 499_999`
 * - Tier 1 (Movie Viewing Reminders): `500_000 .. 699_999` (notif + content), `700_000 .. 899_999` (dismiss)
 * - Tier 2 (Recovery & App Health): `900_000 .. 929_999`
 * - Tier 3 (Smart Schedule Alarms & Contextual Nudges & deleteIntent): `930_000 .. 999_999`
 */
object NotificationIds {

    // ---- Downloads Foreground Worker (outside 100k..999k band) ----
    fun downloadProgressNotificationId(taskId: String): Int =
        10_000 + (taskId.hashCode() and 0x7FFF)

    fun downloadCancelRequestCode(taskId: String): Int =
        50_000 + (taskId.hashCode() and 0x7FFF)

    // ---- Tier 1: Manual Link Reminders (100_000 .. 499_999) ----
    private fun linkOffset(linkId: Long): Int =
        Math.floorMod(linkId, 100_000L).toInt()

    fun linkReminderAlarmRequestCode(linkId: Long): Int =
        100_000 + linkOffset(linkId)

    fun linkReminderNotificationId(linkId: Long): Int =
        100_000 + linkOffset(linkId)

    fun linkReminderContentRequestCode(linkId: Long): Int =
        200_000 + linkOffset(linkId)

    fun linkReminderOpenBrowserRequestCode(linkId: Long): Int =
        300_000 + linkOffset(linkId)

    fun linkReminderDismissRequestCode(linkId: Long): Int =
        400_000 + linkOffset(linkId)

    // ---- Tier 1: Movie Viewing Reminders (500_000 .. 899_999) ----
    fun movieReminderId(mediaKey: String): Int =
        500_000 + (mediaKey.hashCode() and 0x7FFF_FFFF) % 200_000

    fun movieReminderDismissRequestCode(mediaKey: String): Int =
        movieReminderId(mediaKey) + 200_000

    // ---- Tier 2: Recovery & App Health (900_000 .. 929_999) ----
    private fun recoveryLinkOffset(savedLinkId: Long): Int =
        Math.floorMod(savedLinkId, 10_000L).toInt()

    fun extractionFailedNotificationId(savedLinkId: Long): Int =
        900_000 + recoveryLinkOffset(savedLinkId)

    fun extractionFailedAskAiRequestCode(savedLinkId: Long): Int =
        910_000 + recoveryLinkOffset(savedLinkId)

    fun downloadFailedNotificationId(taskId: String): Int =
        920_000 + (taskId.hashCode() and 0x7FFF_FFFF) % 9_000

    const val APP_HEALTH_NOTIFICATION_ID = 929_100

    // ---- Tier 3: Smart Schedule Alarms & Contextual Nudges (930_000 .. 999_999) ----
    const val SMART_ALARM_BASE_REQUEST_CODE = 930_000

    fun smartScheduleAlarmRequestCode(scheduleOrdinal: Int): Int =
        SMART_ALARM_BASE_REQUEST_CODE + scheduleOrdinal + 1

    private fun smartDigestOffset(linkId: Long): Int =
        Math.floorMod(linkId, 5_000L).toInt()

    fun smartDigestNotificationId(linkId: Long): Int =
        931_000 + smartDigestOffset(linkId)

    fun smartDigestContentRequestCode(linkId: Long): Int =
        936_000 + smartDigestOffset(linkId)

    fun smartDigestOpenBrowserRequestCode(linkId: Long): Int =
        941_000 + smartDigestOffset(linkId)

    fun smartDigestDismissRequestCode(linkId: Long): Int =
        946_000 + smartDigestOffset(linkId)

    const val CONTEXTUAL_SUMMARY_NOTIFICATION_ID = 951_000

    fun helpfulTipNotificationId(tipId: String): Int =
        952_000 + (tipId.hashCode() and 0x0FFF)

    fun moviesContinueWatchingNotificationId(compositeId: String): Int =
        960_000 + (compositeId.hashCode() and 0x0FFF)

    fun moviesNewSeasonNotificationId(titleId: String): Int =
        965_000 + (titleId.hashCode() and 0x0FFF)

    fun moviesWatchlistNotificationId(compositeId: String): Int =
        970_000 + (compositeId.hashCode() and 0x0FFF)

    fun tier3DeleteIntentRequestCode(notificationId: Int): Int = when (notificationId) {
        in 931_000..935_999 -> 975_000 + (notificationId - 931_000) // 975_000 .. 979_999
        in 951_000..956_095 -> 980_000 + (notificationId - 951_000) // 980_000 .. 985_095
        in 960_000..974_095 -> 985_096 + (notificationId - 960_000) // 985_096 .. 999_191
        else -> 999_192 + Math.floorMod(notificationId, 808)        // 999_192 .. 999_999
    }

    // ---- Legacy v1.0.84 Constants (used ONLY by AlarmMigrationManager for one-time cleanup) ----
    const val LEGACY_SMART_DIGEST_REQUEST_CODE = 888_888
    const val LEGACY_SMART_ALARM_BASE_REQUEST_CODE = 900_000
    const val LEGACY_APP_HEALTH_NOTIFICATION_ID = 890_100
    const val LEGACY_SUMMARY_NOTIFICATION_ID = 940_100

    fun legacyMovieReminderRequestCode(mediaKey: String): Int =
        700_000 + (mediaKey.hashCode() and 0x0FFF_FFFF) % 250_000
}
