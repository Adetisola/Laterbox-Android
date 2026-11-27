package com.laterbox.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.laterbox.app.reminders.smart.CustomScheduleConfig
import com.laterbox.app.reminders.smart.DailyDigestConfig
import com.laterbox.app.reminders.smart.MorningEveningConfig
import com.laterbox.app.reminders.smart.SmartReminderSettings
import com.laterbox.app.reminders.smart.WeekendConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class InboxViewMode {
    HERO,
    COMPACT,
    EDITORIAL
}

enum class FontSizeScale(val scaleFactor: Float, val label: String) {
    SMALL(0.85f, "Small"),
    SYSTEM(1.0f, "System"),
    LARGE(1.15f, "Large")
}

@Deprecated("Migrated to independent schedule toggles in SmartReminderSettings")
enum class SmartReminderSchedule(val label: String, val description: String) {
    DAILY_DIGEST("Daily Digest", "Once a day at your chosen time"),
    TWICE_DAILY("Morning & Evening", "Twice a day at 9:00 AM and 8:00 PM"),
    WEEKEND_ROUNDUP("Weekend Roundup", "Every Saturday & Sunday morning"),
    CONTINUOUS_MONITOR("Continuous Monitor", "Monitors unread links and nudges when idle")
}

enum class SmartReminderPersistence(val maxNudges: Int, val label: String) {
    ONCE(1, "1 time"),
    TWICE(2, "2 times"),
    THRICE(3, "3 times"),
    UNTIL_READ(Int.MAX_VALUE, "Until read")
}

enum class MediaDownloadNetworkPolicy(val label: String) {
    WIFI_ONLY("Wi-Fi only"),
    WIFI_OVER_20MB("Wi-Fi for files over 20 MB"),
    ANY_NETWORK("Any network")
}

enum class CardTapAction(val label: String, val description: String) {
    DETAIL_HUB("Open Detail Hub", "Show notes, offline media, transcript tools, and reader"),
    IN_APP_READER("Open In-App Reader", "Jump straight into distraction-free reading mode"),
    EXTERNAL_BROWSER("Open in Browser", "Launch directly in your external browser tab")
}

object MovieBoxEndpointConfig {
    const val DEFAULT_PRODUCTION_BASE_URL = "https://laterbox-moviebox-api.vercel.app/"
    private val DEBUG_LOOPBACK_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")

    fun normalizeBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return ""
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    fun isValidBaseUrl(
        rawUrl: String,
        allowDebugCleartextLoopback: Boolean
    ): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return true
        val parsed = try {
            java.net.URI(trimmed)
        } catch (_: Exception) {
            return false
        }
        val scheme = parsed.scheme?.lowercase() ?: return false
        val host = parsed.host?.lowercase() ?: return false
        return when (scheme) {
            "https" -> host.isNotBlank()
            "http" -> allowDebugCleartextLoopback && host in DEBUG_LOOPBACK_HOSTS
            else -> false
        }
    }
}

data class SubtitleStyleConfig(
    val useSystemCaptions: Boolean = false,
    val fontSizeSp: Float = 20f,
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    val backgroundColorArgb: Int = 0xFF000000.toInt(),
    val backgroundOpacity: Float = 0.67f,
    val edgeType: Int = 2 // 0=none, 1=outline, 2=drop_shadow
)

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val autoMarkAsRead: Boolean = true,
    val defaultCardTapAction: CardTapAction = CardTapAction.DETAIL_HUB,
    val includeInBackup: Boolean = true,
    val defaultCollectionId: Long? = null,
    val inboxViewMode: InboxViewMode = InboxViewMode.HERO,
    val fontSizeScale: FontSizeScale = FontSizeScale.SYSTEM,
    val hasCompletedOnboarding: Boolean = false,
    val userName: String = "",
    val smartRemindersEnabled: Boolean = true,
    // Morning & Evening schedule
    val morningEveningEnabled: Boolean = false,
    val morningHour: Int = 9,
    val morningMinute: Int = 0,
    val eveningHour: Int = 20,
    val eveningMinute: Int = 0,
    // Weekend Roundup schedule
    val weekendRoundupEnabled: Boolean = false,
    val weekendRoundupHour: Int = 10,
    val weekendRoundupMinute: Int = 0,
    // Daily Digest schedule
    val dailyDigestEnabled: Boolean = true,
    val dailyDigestHour: Int = 20,
    val dailyDigestMinute: Int = 0,
    val dailyDigestWeekdaysOnly: Boolean = false,
    // Custom schedule
    val customScheduleEnabled: Boolean = false,
    val customScheduleDays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val customScheduleHour: Int = 12,
    val customScheduleMinute: Int = 0,
    // Persistence & API key
    val smartReminderPersistence: SmartReminderPersistence = SmartReminderPersistence.TWICE,
    val geminiApiKey: String = "",
    val deliveredSlotKeys: Set<String> = emptySet(),
    val accessibilityCaptureEnabled: Boolean = false,
    val clipboardSuggestionEnabled: Boolean = true,
    val recentClipboardHistory: Set<String> = emptySet(),
    val excludedCapturePackages: Set<String> = emptySet(),
    val overlayXRatio: Float = 0.5f,
    val overlayYRatio: Float = 0.82f,
    val lastUpdateCheckTime: Long = 0L,
    val dismissedUpdateVersion: String = "",
    val dismissedUpdateTimestamp: Long = 0L,
    val mediaDownloadNetworkPolicy: MediaDownloadNetworkPolicy = MediaDownloadNetworkPolicy.WIFI_OVER_20MB,
    val autoInspectMediaOnSave: Boolean = true,
    val fastEngineEnabled: Boolean = true,
    val ytDlpVersion: String = "",
    val lastYtDlpUpdateCheck: Long = 0L,
    val customExportDirectoryUri: String? = null,
    val customExportDirectoryName: String? = null,
    // Hybrid AI Assistant & Contextual Notifications
    val assistantEnabled: Boolean = true,
    val assistantAiDisclosureAccepted: Boolean = false,
    val audioTranscriptionConsentAccepted: Boolean = false,
    val smartNotificationsExtractionFailure: Boolean = true,
    val smartNotificationsDownloadFailure: Boolean = true,
    val smartNotificationsAppHealth: Boolean = true,
    val smartNotificationsSummaries: Boolean = true,
    val smartNotificationsTips: Boolean = false,
    val smartNotificationsMoviesTv: Boolean = true,
    val quietHoursEnabled: Boolean = true,
    val quietHoursStartHour: Int = 22,
    val quietHoursEndHour: Int = 8,
    val shownTipIds: Set<String> = emptySet(),
    val lastAppHealthAlertAt: Long = 0L,
    val lastTipNotificationAt: Long = 0L,
    val lastSummaryNotificationAt: Long = 0L,
    val lastMoviesNotificationAt: Long = 0L,
    // On-device only product activity counters (never sent off-device)
    val firstLaunchAt: Long = 0L,
    val firstLinkSavedAt: Long = 0L,
    val remindersDeliveredCount: Int = 0,
    val remindersOpenedCount: Int = 0,
    val readerOpensCount: Int = 0,
    val shareCapturesCount: Int = 0,
    val quickTileCapturesCount: Int = 0,
    val manualCapturesCount: Int = 0,
    val tmdbApiKey: String = "",
    val moviesUseSampleFixturesOnly: Boolean = false,
    val movieBoxApiBaseUrl: String = "",
    // Movies & TV player, gesture, subtitle, and playback preferences
    val moviesDoubleTapSeekSeconds: Int = 10,
    val moviesSwipeToSeekEnabled: Boolean = true,
    val moviesSwipeSeekSensitivity: Float = 1.0f,
    val moviesBrightnessGestureEnabled: Boolean = true,
    val moviesVolumeGestureEnabled: Boolean = true,
    val moviesAutoplayNextEpisode: Boolean = true,
    val moviesAutoplayCountdownSeconds: Int = 10,
    val moviesDefaultQualityHeight: Int = 720,
    val moviesTrailerMuted: Boolean = false,
    val moviesAmbientModeEnabled: Boolean = false,
    val subtitleUseSystemCaptions: Boolean = false,
    val subtitleFontSizeSp: Float = 20f,
    val subtitleTextColorArgb: Int = 0xFFFFFFFF.toInt(),
    val subtitleBackgroundColorArgb: Int = 0xFF000000.toInt(),
    val subtitleBackgroundOpacity: Float = 0.67f,
    val subtitleEdgeType: Int = 2,
    val grantedAssistantActionCategories: Set<String> = emptySet(),
    val alarmsMigratedV2: Boolean = false,
    val tier3BudgetDayEpoch: Long = 0L,
    val tier3DailyCount: Int = 0,
    val lastTier3NotificationAt: Long = 0L,
    val tier3CategoryBackoffUntil: Set<String> = emptySet(),
    val tier3SwipeStreak: Set<String> = emptySet()
) {
    val allowSystemBackup: Boolean get() = includeInBackup

    fun tier3BackoffUntilMap(): Map<String, Long> =
        tier3CategoryBackoffUntil.mapNotNull { entry ->
            val sep = entry.indexOf(':')
            if (sep <= 0 || sep >= entry.length - 1) return@mapNotNull null
            val key = entry.substring(0, sep)
            val until = entry.substring(sep + 1).toLongOrNull() ?: return@mapNotNull null
            key to until
        }.toMap()

    fun tier3SwipeStreakMap(): Map<String, Int> =
        tier3SwipeStreak.mapNotNull { entry ->
            val sep = entry.indexOf(':')
            if (sep <= 0 || sep >= entry.length - 1) return@mapNotNull null
            val key = entry.substring(0, sep)
            val count = entry.substring(sep + 1).toIntOrNull() ?: return@mapNotNull null
            key to count
        }.toMap()

    fun toSubtitleStyleConfig(): SubtitleStyleConfig = SubtitleStyleConfig(
        useSystemCaptions = subtitleUseSystemCaptions,
        fontSizeSp = subtitleFontSizeSp.coerceIn(12f, 36f),
        textColorArgb = subtitleTextColorArgb,
        backgroundColorArgb = subtitleBackgroundColorArgb,
        backgroundOpacity = subtitleBackgroundOpacity.coerceIn(0f, 1f),
        edgeType = subtitleEdgeType.coerceIn(0, 2)
    )

    fun toSmartReminderSettings(): SmartReminderSettings = SmartReminderSettings(
        enabled = smartRemindersEnabled,
        morningEvening = MorningEveningConfig(
            enabled = morningEveningEnabled,
            morningHour = morningHour,
            morningMinute = morningMinute,
            eveningHour = eveningHour,
            eveningMinute = eveningMinute
        ),
        weekendRoundup = WeekendConfig(
            enabled = weekendRoundupEnabled,
            hour = weekendRoundupHour,
            minute = weekendRoundupMinute
        ),
        dailyDigest = DailyDigestConfig(
            enabled = dailyDigestEnabled,
            hour = dailyDigestHour,
            minute = dailyDigestMinute,
            weekdaysOnly = dailyDigestWeekdaysOnly
        ),
        custom = CustomScheduleConfig(
            enabled = customScheduleEnabled,
            daysOfWeek = customScheduleDays,
            hour = customScheduleHour,
            minute = customScheduleMinute
        ),
        persistence = smartReminderPersistence
    )
}

val Context.userDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesRepository(
    private val context: Context,
    private val dataStore: DataStore<Preferences> = context.userDataStore
) {

    private object PreferencesKeys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AUTO_MARK_AS_READ = booleanPreferencesKey("auto_mark_as_read")
        val DEFAULT_CARD_TAP_ACTION = stringPreferencesKey("default_card_tap_action")
        val INCLUDE_IN_BACKUP = booleanPreferencesKey("include_in_backup")
        val DEFAULT_COLLECTION_ID = longPreferencesKey("default_collection_id")
        val INBOX_VIEW_MODE = stringPreferencesKey("inbox_view_mode")
        val FONT_SIZE_SCALE = stringPreferencesKey("font_size_scale")
        val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
        val USER_NAME = stringPreferencesKey("user_name")
        val SMART_REMINDERS_ENABLED = booleanPreferencesKey("smart_reminders_enabled")
        val SCHEDULES_MIGRATED_V2 = booleanPreferencesKey("schedules_migrated_v2")

        // Legacy schedule keys for migration
        val LEGACY_SMART_REMINDER_SCHEDULE = stringPreferencesKey("smart_reminder_schedule")
        val LEGACY_SMART_REMINDER_HOUR = intPreferencesKey("smart_reminder_hour")
        val LEGACY_SMART_REMINDER_MINUTE = intPreferencesKey("smart_reminder_minute")

        // Morning & Evening
        val MORNING_EVENING_ENABLED = booleanPreferencesKey("morning_evening_enabled")
        val MORNING_HOUR = intPreferencesKey("morning_hour")
        val MORNING_MINUTE = intPreferencesKey("morning_minute")
        val EVENING_HOUR = intPreferencesKey("evening_hour")
        val EVENING_MINUTE = intPreferencesKey("evening_minute")

        // Weekend Roundup
        val WEEKEND_ROUNDUP_ENABLED = booleanPreferencesKey("weekend_roundup_enabled")
        val WEEKEND_ROUNDUP_HOUR = intPreferencesKey("weekend_roundup_hour")
        val WEEKEND_ROUNDUP_MINUTE = intPreferencesKey("weekend_roundup_minute")

        // Daily Digest
        val DAILY_DIGEST_ENABLED = booleanPreferencesKey("daily_digest_enabled")
        val DAILY_DIGEST_HOUR = intPreferencesKey("daily_digest_hour")
        val DAILY_DIGEST_MINUTE = intPreferencesKey("daily_digest_minute")
        val DAILY_DIGEST_WEEKDAYS_ONLY = booleanPreferencesKey("daily_digest_weekdays_only")

        // Custom Schedule
        val CUSTOM_SCHEDULE_ENABLED = booleanPreferencesKey("custom_schedule_enabled")
        val CUSTOM_SCHEDULE_DAYS = stringSetPreferencesKey("custom_schedule_days")
        val CUSTOM_SCHEDULE_HOUR = intPreferencesKey("custom_schedule_hour")
        val CUSTOM_SCHEDULE_MINUTE = intPreferencesKey("custom_schedule_minute")

        val SMART_REMINDER_PERSISTENCE = stringPreferencesKey("smart_reminder_persistence")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val DELIVERED_SLOT_KEYS = stringSetPreferencesKey("delivered_slot_keys")
        val ACCESSIBILITY_CAPTURE_ENABLED = booleanPreferencesKey("accessibility_capture_enabled")
        val CLIPBOARD_SUGGESTION_ENABLED = booleanPreferencesKey("clipboard_suggestion_enabled")
        val RECENT_CLIPBOARD_HISTORY = stringSetPreferencesKey("recent_clipboard_history")
        val EXCLUDED_CAPTURE_PACKAGES = stringSetPreferencesKey("excluded_capture_packages")
        val OVERLAY_X_RATIO = floatPreferencesKey("overlay_x_ratio")
        val OVERLAY_Y_RATIO = floatPreferencesKey("overlay_y_ratio")
        val LAST_UPDATE_CHECK_TIME = longPreferencesKey("last_update_check_time")
        val DISMISSED_UPDATE_VERSION = stringPreferencesKey("dismissed_update_version")
        val DISMISSED_UPDATE_TIMESTAMP = longPreferencesKey("dismissed_update_timestamp")
        val MEDIA_DOWNLOAD_NETWORK_POLICY = stringPreferencesKey("media_download_network_policy")
        val AUTO_INSPECT_MEDIA_ON_SAVE = booleanPreferencesKey("auto_inspect_media_on_save")
        val FAST_ENGINE_ENABLED = booleanPreferencesKey("fast_engine_enabled")
        val YTDLP_VERSION = stringPreferencesKey("ytdlp_version")
        val LAST_YTDLP_UPDATE_CHECK = longPreferencesKey("last_ytdlp_update_check")
        val CUSTOM_EXPORT_DIRECTORY_URI = stringPreferencesKey("custom_export_directory_uri")
        val CUSTOM_EXPORT_DIRECTORY_NAME = stringPreferencesKey("custom_export_directory_name")

        val ASSISTANT_ENABLED = booleanPreferencesKey("assistant_enabled")
        val ASSISTANT_AI_DISCLOSURE_ACCEPTED = booleanPreferencesKey("assistant_ai_disclosure_accepted")
        val AUDIO_TRANSCRIPTION_CONSENT_ACCEPTED = booleanPreferencesKey("audio_transcription_consent_accepted")
        val SMART_NOTIF_EXTRACTION_FAILURE = booleanPreferencesKey("smart_notif_extraction_failure")
        val SMART_NOTIF_DOWNLOAD_FAILURE = booleanPreferencesKey("smart_notif_download_failure")
        val SMART_NOTIF_APP_HEALTH = booleanPreferencesKey("smart_notif_app_health")
        val SMART_NOTIF_SUMMARIES = booleanPreferencesKey("smart_notif_summaries")
        val SMART_NOTIF_TIPS = booleanPreferencesKey("smart_notif_tips")
        val SMART_NOTIF_MOVIES_TV = booleanPreferencesKey("smart_notif_movies_tv")
        val QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        val QUIET_HOURS_START_HOUR = intPreferencesKey("quiet_hours_start_hour")
        val QUIET_HOURS_END_HOUR = intPreferencesKey("quiet_hours_end_hour")
        val SHOWN_TIP_IDS = stringSetPreferencesKey("shown_tip_ids")
        val LAST_APP_HEALTH_ALERT_AT = longPreferencesKey("last_app_health_alert_at")
        val LAST_TIP_NOTIFICATION_AT = longPreferencesKey("last_tip_notification_at")
        val LAST_SUMMARY_NOTIFICATION_AT = longPreferencesKey("last_summary_notification_at")
        val LAST_MOVIES_NOTIFICATION_AT = longPreferencesKey("last_movies_notif_at")

        val FIRST_LAUNCH_AT = longPreferencesKey("first_launch_at")
        val FIRST_LINK_SAVED_AT = longPreferencesKey("first_link_saved_at")
        val REMINDERS_DELIVERED_COUNT = intPreferencesKey("reminders_delivered_count")
        val REMINDERS_OPENED_COUNT = intPreferencesKey("reminders_opened_count")
        val READER_OPENS_COUNT = intPreferencesKey("reader_opens_count")
        val SHARE_CAPTURES_COUNT = intPreferencesKey("share_captures_count")
        val QUICK_TILE_CAPTURES_COUNT = intPreferencesKey("quick_tile_captures_count")
        val MANUAL_CAPTURES_COUNT = intPreferencesKey("manual_captures_count")
        val TMDB_API_KEY = stringPreferencesKey("tmdb_api_key")
        val MOVIES_USE_SAMPLE_FIXTURES_ONLY = booleanPreferencesKey("movies_use_sample_fixtures_only")
        val MOVIES_PROVIDER_MODE = stringPreferencesKey("movies_provider_mode")
        val DEBUG_MOVIES_SAMPLE_FIXTURES = booleanPreferencesKey("debug_movies_sample_fixtures")
        val MOVIES_PREFS_MIGRATED_V2 = booleanPreferencesKey("movies_prefs_migrated_v2")
        val MOVIEBOX_API_BASE_URL = stringPreferencesKey("moviebox_api_base_url")
        val MOVIEBOX_API_KEY = stringPreferencesKey("moviebox_api_key")
        val MOVIES_DOUBLE_TAP_SEEK_SECONDS = intPreferencesKey("movies_double_tap_seek_seconds")
        val MOVIES_SWIPE_TO_SEEK_ENABLED = booleanPreferencesKey("movies_swipe_to_seek_enabled")
        val MOVIES_SWIPE_SEEK_SENSITIVITY = floatPreferencesKey("movies_swipe_seek_sensitivity")
        val MOVIES_BRIGHTNESS_GESTURE_ENABLED = booleanPreferencesKey("movies_brightness_gesture_enabled")
        val MOVIES_VOLUME_GESTURE_ENABLED = booleanPreferencesKey("movies_volume_gesture_enabled")
        val MOVIES_AUTOPLAY_NEXT_EPISODE = booleanPreferencesKey("movies_autoplay_next_episode")
        val MOVIES_AUTOPLAY_COUNTDOWN_SECONDS = intPreferencesKey("movies_autoplay_countdown_seconds")
        val MOVIES_DEFAULT_QUALITY_HEIGHT = intPreferencesKey("movies_default_quality_height")
        val MOVIES_TRAILER_MUTED = booleanPreferencesKey("movies_trailer_muted")
        val MOVIES_AMBIENT_MODE_ENABLED = booleanPreferencesKey("movies_ambient_mode_enabled")
        val SUBTITLE_USE_SYSTEM_CAPTIONS = booleanPreferencesKey("subtitle_use_system_captions")
        val SUBTITLE_FONT_SIZE_SP = floatPreferencesKey("subtitle_font_size_sp")
        val SUBTITLE_TEXT_COLOR_ARGB = intPreferencesKey("subtitle_text_color_argb")
        val SUBTITLE_BACKGROUND_COLOR_ARGB = intPreferencesKey("subtitle_background_color_argb")
        val SUBTITLE_BACKGROUND_OPACITY = floatPreferencesKey("subtitle_background_opacity")
        val SUBTITLE_EDGE_TYPE = intPreferencesKey("subtitle_edge_type")
        val GRANTED_ASSISTANT_CATEGORIES = stringSetPreferencesKey("granted_assistant_categories")
        val ALARMS_MIGRATED_V2 = booleanPreferencesKey("alarms_migrated_v2")
        val TIER3_BUDGET_DAY_EPOCH = longPreferencesKey("tier3_budget_day_epoch")
        val TIER3_DAILY_COUNT = intPreferencesKey("tier3_daily_count")
        val LAST_TIER3_NOTIFICATION_AT = longPreferencesKey("last_tier3_notif_at")
        val TIER3_CATEGORY_BACKOFF_UNTIL = stringSetPreferencesKey("tier3_cat_backoff_until")
        val TIER3_SWIPE_STREAK = stringSetPreferencesKey("tier3_swipe_streak")
    }

    val userPreferencesFlow: Flow<UserPreferences> = dataStore.data.map { preferences ->
        val themeModeStr = preferences[PreferencesKeys.THEME_MODE] ?: ThemeMode.SYSTEM.name
        val themeMode = try {
            ThemeMode.valueOf(themeModeStr)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }

        val autoMarkAsRead = preferences[PreferencesKeys.AUTO_MARK_AS_READ] ?: true
        val cardTapStr = preferences[PreferencesKeys.DEFAULT_CARD_TAP_ACTION] ?: CardTapAction.DETAIL_HUB.name
        val defaultCardTapAction = try {
            CardTapAction.valueOf(cardTapStr)
        } catch (_: Exception) {
            CardTapAction.DETAIL_HUB
        }
        val includeInBackup = preferences[PreferencesKeys.INCLUDE_IN_BACKUP] ?: true
        val defaultCollectionId = preferences[PreferencesKeys.DEFAULT_COLLECTION_ID]

        val viewModeStr = preferences[PreferencesKeys.INBOX_VIEW_MODE] ?: InboxViewMode.HERO.name
        val inboxViewMode = try {
            InboxViewMode.valueOf(viewModeStr)
        } catch (_: Exception) {
            InboxViewMode.HERO
        }

        val fontScaleStr = preferences[PreferencesKeys.FONT_SIZE_SCALE] ?: FontSizeScale.SYSTEM.name
        val fontSizeScale = try {
            FontSizeScale.valueOf(fontScaleStr)
        } catch (_: Exception) {
            FontSizeScale.SYSTEM
        }

        val hasCompletedOnboarding = preferences[PreferencesKeys.HAS_COMPLETED_ONBOARDING] ?: false
        val userName = preferences[PreferencesKeys.USER_NAME] ?: ""
        val smartRemindersEnabled = preferences[PreferencesKeys.SMART_REMINDERS_ENABLED] ?: true

        // Schedule migration logic
        val isMigrated = preferences[PreferencesKeys.SCHEDULES_MIGRATED_V2] ?: false
        val legacySchedule = preferences[PreferencesKeys.LEGACY_SMART_REMINDER_SCHEDULE]
        val legacyHour = preferences[PreferencesKeys.LEGACY_SMART_REMINDER_HOUR] ?: 20
        val legacyMinute = preferences[PreferencesKeys.LEGACY_SMART_REMINDER_MINUTE] ?: 0

        val morningEveningEnabled: Boolean
        val weekendRoundupEnabled: Boolean
        val dailyDigestEnabled: Boolean
        val dailyDigestHour: Int
        val dailyDigestMinute: Int

        if (!isMigrated && legacySchedule != null) {
            when (legacySchedule) {
                "TWICE_DAILY" -> {
                    morningEveningEnabled = true
                    weekendRoundupEnabled = false
                    dailyDigestEnabled = false
                    dailyDigestHour = legacyHour
                    dailyDigestMinute = legacyMinute
                }
                "WEEKEND_ROUNDUP" -> {
                    morningEveningEnabled = false
                    weekendRoundupEnabled = true
                    dailyDigestEnabled = false
                    dailyDigestHour = legacyHour
                    dailyDigestMinute = legacyMinute
                }
                else -> {
                    morningEveningEnabled = false
                    weekendRoundupEnabled = false
                    dailyDigestEnabled = true
                    dailyDigestHour = legacyHour
                    dailyDigestMinute = legacyMinute
                }
            }
        } else {
            morningEveningEnabled = preferences[PreferencesKeys.MORNING_EVENING_ENABLED] ?: false
            weekendRoundupEnabled = preferences[PreferencesKeys.WEEKEND_ROUNDUP_ENABLED] ?: false
            dailyDigestEnabled = preferences[PreferencesKeys.DAILY_DIGEST_ENABLED] ?: true
            dailyDigestHour = preferences[PreferencesKeys.DAILY_DIGEST_HOUR] ?: 20
            dailyDigestMinute = preferences[PreferencesKeys.DAILY_DIGEST_MINUTE] ?: 0
        }

        val morningHour = preferences[PreferencesKeys.MORNING_HOUR] ?: 9
        val morningMinute = preferences[PreferencesKeys.MORNING_MINUTE] ?: 0
        val eveningHour = preferences[PreferencesKeys.EVENING_HOUR] ?: 20
        val eveningMinute = preferences[PreferencesKeys.EVENING_MINUTE] ?: 0

        val weekendRoundupHour = preferences[PreferencesKeys.WEEKEND_ROUNDUP_HOUR] ?: 10
        val weekendRoundupMinute = preferences[PreferencesKeys.WEEKEND_ROUNDUP_MINUTE] ?: 0

        val dailyDigestWeekdaysOnly = preferences[PreferencesKeys.DAILY_DIGEST_WEEKDAYS_ONLY] ?: false

        val customScheduleEnabled = preferences[PreferencesKeys.CUSTOM_SCHEDULE_ENABLED] ?: false
        val customDaysRaw = preferences[PreferencesKeys.CUSTOM_SCHEDULE_DAYS] ?: setOf("1", "2", "3", "4", "5")
        val customScheduleDays = customDaysRaw.mapNotNull { it.toIntOrNull() }.toSet()
        val customScheduleHour = preferences[PreferencesKeys.CUSTOM_SCHEDULE_HOUR] ?: 12
        val customScheduleMinute = preferences[PreferencesKeys.CUSTOM_SCHEDULE_MINUTE] ?: 0

        val persistenceStr = preferences[PreferencesKeys.SMART_REMINDER_PERSISTENCE] ?: SmartReminderPersistence.TWICE.name
        val smartReminderPersistence = try {
            SmartReminderPersistence.valueOf(persistenceStr)
        } catch (_: Exception) {
            SmartReminderPersistence.TWICE
        }

        val geminiApiKey = preferences[PreferencesKeys.GEMINI_API_KEY] ?: ""
        val deliveredSlotKeys = preferences[PreferencesKeys.DELIVERED_SLOT_KEYS] ?: emptySet()

        val accessibilityCaptureEnabled = preferences[PreferencesKeys.ACCESSIBILITY_CAPTURE_ENABLED] ?: false
        val clipboardSuggestionEnabled = preferences[PreferencesKeys.CLIPBOARD_SUGGESTION_ENABLED] ?: true
        val recentClipboardHistory = preferences[PreferencesKeys.RECENT_CLIPBOARD_HISTORY] ?: emptySet()
        val excludedCapturePackages = preferences[PreferencesKeys.EXCLUDED_CAPTURE_PACKAGES] ?: emptySet()
        val overlayXRatio = preferences[PreferencesKeys.OVERLAY_X_RATIO] ?: 0.5f
        val overlayYRatio = preferences[PreferencesKeys.OVERLAY_Y_RATIO] ?: 0.82f
        val lastUpdateCheckTime = preferences[PreferencesKeys.LAST_UPDATE_CHECK_TIME] ?: 0L
        val dismissedUpdateVersion = preferences[PreferencesKeys.DISMISSED_UPDATE_VERSION] ?: ""
        val dismissedUpdateTimestamp = preferences[PreferencesKeys.DISMISSED_UPDATE_TIMESTAMP] ?: 0L
        val mediaPolicyStr = preferences[PreferencesKeys.MEDIA_DOWNLOAD_NETWORK_POLICY] ?: MediaDownloadNetworkPolicy.WIFI_OVER_20MB.name
        val mediaDownloadNetworkPolicy = try {
            MediaDownloadNetworkPolicy.valueOf(mediaPolicyStr)
        } catch (_: Exception) {
            MediaDownloadNetworkPolicy.WIFI_OVER_20MB
        }
        val autoInspectMediaOnSave = preferences[PreferencesKeys.AUTO_INSPECT_MEDIA_ON_SAVE] ?: true
        val fastEngineEnabled = preferences[PreferencesKeys.FAST_ENGINE_ENABLED] ?: true
        val ytDlpVersion = preferences[PreferencesKeys.YTDLP_VERSION] ?: ""
        val lastYtDlpUpdateCheck = preferences[PreferencesKeys.LAST_YTDLP_UPDATE_CHECK] ?: 0L

        val debugSampleFixturesOnly = com.laterbox.app.BuildConfig.DEBUG &&
            (preferences[PreferencesKeys.DEBUG_MOVIES_SAMPLE_FIXTURES] ?: false)
        val movieBoxApiBaseUrl = MovieBoxEndpointConfig.normalizeBaseUrl(
            preferences[PreferencesKeys.MOVIEBOX_API_BASE_URL] ?: ""
        )

        UserPreferences(
            themeMode = themeMode,
            autoMarkAsRead = autoMarkAsRead,
            defaultCardTapAction = defaultCardTapAction,
            includeInBackup = includeInBackup,
            defaultCollectionId = defaultCollectionId,
            inboxViewMode = inboxViewMode,
            fontSizeScale = fontSizeScale,
            hasCompletedOnboarding = hasCompletedOnboarding,
            userName = userName,
            smartRemindersEnabled = smartRemindersEnabled,
            morningEveningEnabled = morningEveningEnabled,
            morningHour = morningHour,
            morningMinute = morningMinute,
            eveningHour = eveningHour,
            eveningMinute = eveningMinute,
            weekendRoundupEnabled = weekendRoundupEnabled,
            weekendRoundupHour = weekendRoundupHour,
            weekendRoundupMinute = weekendRoundupMinute,
            dailyDigestEnabled = dailyDigestEnabled,
            dailyDigestHour = dailyDigestHour,
            dailyDigestMinute = dailyDigestMinute,
            dailyDigestWeekdaysOnly = dailyDigestWeekdaysOnly,
            customScheduleEnabled = customScheduleEnabled,
            customScheduleDays = customScheduleDays,
            customScheduleHour = customScheduleHour,
            customScheduleMinute = customScheduleMinute,
            smartReminderPersistence = smartReminderPersistence,
            geminiApiKey = geminiApiKey,
            deliveredSlotKeys = deliveredSlotKeys,
            accessibilityCaptureEnabled = accessibilityCaptureEnabled,
            clipboardSuggestionEnabled = clipboardSuggestionEnabled,
            recentClipboardHistory = recentClipboardHistory,
            excludedCapturePackages = excludedCapturePackages,
            overlayXRatio = overlayXRatio,
            overlayYRatio = overlayYRatio,
            lastUpdateCheckTime = lastUpdateCheckTime,
            dismissedUpdateVersion = dismissedUpdateVersion,
            dismissedUpdateTimestamp = dismissedUpdateTimestamp,
            mediaDownloadNetworkPolicy = mediaDownloadNetworkPolicy,
            autoInspectMediaOnSave = autoInspectMediaOnSave,
            fastEngineEnabled = fastEngineEnabled,
            ytDlpVersion = ytDlpVersion,
            lastYtDlpUpdateCheck = lastYtDlpUpdateCheck,
            customExportDirectoryUri = preferences[PreferencesKeys.CUSTOM_EXPORT_DIRECTORY_URI],
            customExportDirectoryName = preferences[PreferencesKeys.CUSTOM_EXPORT_DIRECTORY_NAME],
            assistantEnabled = preferences[PreferencesKeys.ASSISTANT_ENABLED] ?: true,
            assistantAiDisclosureAccepted = preferences[PreferencesKeys.ASSISTANT_AI_DISCLOSURE_ACCEPTED] ?: false,
            audioTranscriptionConsentAccepted = preferences[PreferencesKeys.AUDIO_TRANSCRIPTION_CONSENT_ACCEPTED] ?: false,
            smartNotificationsExtractionFailure = preferences[PreferencesKeys.SMART_NOTIF_EXTRACTION_FAILURE] ?: true,
            smartNotificationsDownloadFailure = preferences[PreferencesKeys.SMART_NOTIF_DOWNLOAD_FAILURE] ?: true,
            smartNotificationsAppHealth = preferences[PreferencesKeys.SMART_NOTIF_APP_HEALTH] ?: true,
            smartNotificationsSummaries = preferences[PreferencesKeys.SMART_NOTIF_SUMMARIES] ?: true,
            smartNotificationsTips = preferences[PreferencesKeys.SMART_NOTIF_TIPS] ?: false,
            smartNotificationsMoviesTv = preferences[PreferencesKeys.SMART_NOTIF_MOVIES_TV] ?: true,
            quietHoursEnabled = preferences[PreferencesKeys.QUIET_HOURS_ENABLED] ?: true,
            quietHoursStartHour = (preferences[PreferencesKeys.QUIET_HOURS_START_HOUR] ?: 22).coerceIn(0, 23),
            quietHoursEndHour = (preferences[PreferencesKeys.QUIET_HOURS_END_HOUR] ?: 8).coerceIn(0, 23),
            shownTipIds = preferences[PreferencesKeys.SHOWN_TIP_IDS] ?: emptySet(),
            lastAppHealthAlertAt = preferences[PreferencesKeys.LAST_APP_HEALTH_ALERT_AT] ?: 0L,
            lastTipNotificationAt = preferences[PreferencesKeys.LAST_TIP_NOTIFICATION_AT] ?: 0L,
            lastSummaryNotificationAt = preferences[PreferencesKeys.LAST_SUMMARY_NOTIFICATION_AT] ?: 0L,
            lastMoviesNotificationAt = preferences[PreferencesKeys.LAST_MOVIES_NOTIFICATION_AT] ?: 0L,
            firstLaunchAt = preferences[PreferencesKeys.FIRST_LAUNCH_AT] ?: 0L,
            firstLinkSavedAt = preferences[PreferencesKeys.FIRST_LINK_SAVED_AT] ?: 0L,
            remindersDeliveredCount = preferences[PreferencesKeys.REMINDERS_DELIVERED_COUNT] ?: 0,
            remindersOpenedCount = preferences[PreferencesKeys.REMINDERS_OPENED_COUNT] ?: 0,
            readerOpensCount = preferences[PreferencesKeys.READER_OPENS_COUNT] ?: 0,
            shareCapturesCount = preferences[PreferencesKeys.SHARE_CAPTURES_COUNT] ?: 0,
            quickTileCapturesCount = preferences[PreferencesKeys.QUICK_TILE_CAPTURES_COUNT] ?: 0,
            manualCapturesCount = preferences[PreferencesKeys.MANUAL_CAPTURES_COUNT] ?: 0,
            tmdbApiKey = preferences[PreferencesKeys.TMDB_API_KEY] ?: "",
            moviesUseSampleFixturesOnly = debugSampleFixturesOnly,
            movieBoxApiBaseUrl = movieBoxApiBaseUrl,
            moviesDoubleTapSeekSeconds = (preferences[PreferencesKeys.MOVIES_DOUBLE_TAP_SEEK_SECONDS] ?: 10).coerceIn(5, 60),
            moviesSwipeToSeekEnabled = preferences[PreferencesKeys.MOVIES_SWIPE_TO_SEEK_ENABLED] ?: true,
            moviesSwipeSeekSensitivity = (preferences[PreferencesKeys.MOVIES_SWIPE_SEEK_SENSITIVITY] ?: 1.0f).coerceIn(0.25f, 2.0f),
            moviesBrightnessGestureEnabled = preferences[PreferencesKeys.MOVIES_BRIGHTNESS_GESTURE_ENABLED] ?: true,
            moviesVolumeGestureEnabled = preferences[PreferencesKeys.MOVIES_VOLUME_GESTURE_ENABLED] ?: true,
            moviesAutoplayNextEpisode = preferences[PreferencesKeys.MOVIES_AUTOPLAY_NEXT_EPISODE] ?: true,
            moviesAutoplayCountdownSeconds = (preferences[PreferencesKeys.MOVIES_AUTOPLAY_COUNTDOWN_SECONDS] ?: 10).coerceIn(3, 30),
            moviesDefaultQualityHeight = (preferences[PreferencesKeys.MOVIES_DEFAULT_QUALITY_HEIGHT] ?: 720).coerceIn(240, 2160),
            moviesTrailerMuted = preferences[PreferencesKeys.MOVIES_TRAILER_MUTED] ?: false,
            moviesAmbientModeEnabled = preferences[PreferencesKeys.MOVIES_AMBIENT_MODE_ENABLED] ?: false,
            subtitleUseSystemCaptions = preferences[PreferencesKeys.SUBTITLE_USE_SYSTEM_CAPTIONS] ?: false,
            subtitleFontSizeSp = (preferences[PreferencesKeys.SUBTITLE_FONT_SIZE_SP] ?: 20f).coerceIn(12f, 36f),
            subtitleTextColorArgb = preferences[PreferencesKeys.SUBTITLE_TEXT_COLOR_ARGB] ?: 0xFFFFFFFF.toInt(),
            subtitleBackgroundColorArgb = preferences[PreferencesKeys.SUBTITLE_BACKGROUND_COLOR_ARGB] ?: 0xFF000000.toInt(),
            subtitleBackgroundOpacity = (preferences[PreferencesKeys.SUBTITLE_BACKGROUND_OPACITY] ?: 0.67f).coerceIn(0f, 1f),
            subtitleEdgeType = (preferences[PreferencesKeys.SUBTITLE_EDGE_TYPE] ?: 2).coerceIn(0, 2),
            grantedAssistantActionCategories = preferences[PreferencesKeys.GRANTED_ASSISTANT_CATEGORIES] ?: emptySet(),
            alarmsMigratedV2 = preferences[PreferencesKeys.ALARMS_MIGRATED_V2] ?: false,
            tier3BudgetDayEpoch = preferences[PreferencesKeys.TIER3_BUDGET_DAY_EPOCH] ?: 0L,
            tier3DailyCount = (preferences[PreferencesKeys.TIER3_DAILY_COUNT] ?: 0).coerceAtLeast(0),
            lastTier3NotificationAt = preferences[PreferencesKeys.LAST_TIER3_NOTIFICATION_AT] ?: 0L,
            tier3CategoryBackoffUntil = preferences[PreferencesKeys.TIER3_CATEGORY_BACKOFF_UNTIL] ?: emptySet(),
            tier3SwipeStreak = preferences[PreferencesKeys.TIER3_SWIPE_STREAK] ?: emptySet()
        )
    }

    val userPreferences: Flow<UserPreferences>
        get() = userPreferencesFlow

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME_MODE] = mode.name
        }
    }

    suspend fun setAutoMarkAsRead(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_MARK_AS_READ] = enabled
        }
    }

    suspend fun setDefaultCardTapAction(action: CardTapAction) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_CARD_TAP_ACTION] = action.name
        }
    }

    suspend fun setIncludeInBackup(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.INCLUDE_IN_BACKUP] = enabled
        }
    }

    suspend fun setAllowSystemBackup(enabled: Boolean) = setIncludeInBackup(enabled)

    suspend fun setDefaultCollectionId(collectionId: Long?) {
        dataStore.edit { preferences ->
            if (collectionId != null) {
                preferences[PreferencesKeys.DEFAULT_COLLECTION_ID] = collectionId
            } else {
                preferences.remove(PreferencesKeys.DEFAULT_COLLECTION_ID)
            }
        }
    }

    suspend fun setInboxViewMode(mode: InboxViewMode) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.INBOX_VIEW_MODE] = mode.name
        }
    }

    suspend fun setFontSizeScale(scale: FontSizeScale) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FONT_SIZE_SCALE] = scale.name
        }
    }

    suspend fun setHasCompletedOnboarding(completed: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.HAS_COMPLETED_ONBOARDING] = completed
        }
    }

    suspend fun setUserName(name: String) {
        dataStore.edit { preferences ->
            val trimmed = name.trim().take(50)
            if (trimmed.isNotBlank()) {
                preferences[PreferencesKeys.USER_NAME] = trimmed
            } else {
                preferences.remove(PreferencesKeys.USER_NAME)
            }
        }
    }

    suspend fun setSmartRemindersEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SMART_REMINDERS_ENABLED] = enabled
        }
    }

    suspend fun setMorningEveningEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MORNING_EVENING_ENABLED] = enabled
            preferences[PreferencesKeys.SCHEDULES_MIGRATED_V2] = true
        }
    }

    suspend fun setMorningTime(hour: Int, minute: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MORNING_HOUR] = hour
            preferences[PreferencesKeys.MORNING_MINUTE] = minute
        }
    }

    suspend fun setEveningTime(hour: Int, minute: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.EVENING_HOUR] = hour
            preferences[PreferencesKeys.EVENING_MINUTE] = minute
        }
    }

    suspend fun setWeekendRoundupEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.WEEKEND_ROUNDUP_ENABLED] = enabled
            preferences[PreferencesKeys.SCHEDULES_MIGRATED_V2] = true
        }
    }

    suspend fun setWeekendRoundupTime(hour: Int, minute: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.WEEKEND_ROUNDUP_HOUR] = hour
            preferences[PreferencesKeys.WEEKEND_ROUNDUP_MINUTE] = minute
        }
    }

    suspend fun setDailyDigestEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DAILY_DIGEST_ENABLED] = enabled
            preferences[PreferencesKeys.SCHEDULES_MIGRATED_V2] = true
        }
    }

    suspend fun setDailyDigestTime(hour: Int, minute: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DAILY_DIGEST_HOUR] = hour
            preferences[PreferencesKeys.DAILY_DIGEST_MINUTE] = minute
        }
    }

    suspend fun setDailyDigestWeekdaysOnly(weekdaysOnly: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DAILY_DIGEST_WEEKDAYS_ONLY] = weekdaysOnly
        }
    }

    suspend fun setCustomScheduleEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CUSTOM_SCHEDULE_ENABLED] = enabled
            preferences[PreferencesKeys.SCHEDULES_MIGRATED_V2] = true
        }
    }

    suspend fun setCustomScheduleDays(days: Set<Int>) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CUSTOM_SCHEDULE_DAYS] = days.map { it.toString() }.toSet()
        }
    }

    suspend fun setCustomScheduleTime(hour: Int, minute: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CUSTOM_SCHEDULE_HOUR] = hour
            preferences[PreferencesKeys.CUSTOM_SCHEDULE_MINUTE] = minute
        }
    }

    suspend fun setSmartReminderPersistence(persistence: SmartReminderPersistence) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SMART_REMINDER_PERSISTENCE] = persistence.name
        }
    }

    suspend fun setGeminiApiKey(apiKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.GEMINI_API_KEY] = apiKey.trim()
        }
    }

    suspend fun recordDeliveredSlot(slotKey: String) {
        dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.DELIVERED_SLOT_KEYS] ?: emptySet()
            // Keep at most 60 recent slot keys to prevent unbounded growth
            val updated = (current + slotKey).toList().takeLast(60).toSet()
            preferences[PreferencesKeys.DELIVERED_SLOT_KEYS] = updated
        }
    }

    suspend fun setAccessibilityCaptureEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ACCESSIBILITY_CAPTURE_ENABLED] = enabled
        }
    }

    suspend fun setClipboardSuggestionEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CLIPBOARD_SUGGESTION_ENABLED] = enabled
        }
    }

    suspend fun recordHandledClipboardHash(hash: String, nowMs: Long = System.currentTimeMillis()) {
        dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.RECENT_CLIPBOARD_HISTORY] ?: emptySet()
            val retentionMs = 7L * 24 * 60 * 60 * 1000L // 7-day retention
            val cutoff = nowMs - retentionMs

            val valid = current.mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val entryHash = parts[0]
                    val entryTime = parts[1].toLongOrNull() ?: 0L
                    if (entryTime >= cutoff && entryHash != hash) entryHash to entryTime else null
                } else null
            }.sortedBy { it.second }

            val updatedList = (valid.map { "${it.first}:${it.second}" } + "$hash:$nowMs").takeLast(25)
            preferences[PreferencesKeys.RECENT_CLIPBOARD_HISTORY] = updatedList.toSet()
        }
    }

    suspend fun setExcludedCapturePackages(packages: Set<String>) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.EXCLUDED_CAPTURE_PACKAGES] = packages
        }
    }

    suspend fun toggleExcludedCapturePackage(packageName: String) {
        dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.EXCLUDED_CAPTURE_PACKAGES] ?: emptySet()
            val updated = if (current.contains(packageName)) current - packageName else current + packageName
            preferences[PreferencesKeys.EXCLUDED_CAPTURE_PACKAGES] = updated
        }
    }

    suspend fun setOverlayPosition(xRatio: Float, yRatio: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.OVERLAY_X_RATIO] = xRatio.coerceIn(0f, 1f)
            preferences[PreferencesKeys.OVERLAY_Y_RATIO] = yRatio.coerceIn(0f, 1f)
        }
    }

    suspend fun recordUpdateCheck(timestamp: Long = System.currentTimeMillis()) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_UPDATE_CHECK_TIME] = timestamp
        }
    }

    suspend fun dismissUpdate(version: String, timestamp: Long = System.currentTimeMillis()) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DISMISSED_UPDATE_VERSION] = version
            preferences[PreferencesKeys.DISMISSED_UPDATE_TIMESTAMP] = timestamp
        }
    }

    suspend fun setMediaDownloadNetworkPolicy(policy: MediaDownloadNetworkPolicy) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MEDIA_DOWNLOAD_NETWORK_POLICY] = policy.name
        }
    }

    suspend fun setAutoInspectMediaOnSave(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_INSPECT_MEDIA_ON_SAVE] = enabled
        }
    }

    suspend fun setFastEngineEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.FAST_ENGINE_ENABLED] = enabled
        }
    }

    suspend fun recordYtDlpUpdate(version: String, timestamp: Long = System.currentTimeMillis()) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.YTDLP_VERSION] = version
            preferences[PreferencesKeys.LAST_YTDLP_UPDATE_CHECK] = timestamp
        }
    }

    suspend fun setCustomExportDirectory(uriString: String?, displayName: String?) {
        dataStore.edit { preferences ->
            if (uriString != null) {
                preferences[PreferencesKeys.CUSTOM_EXPORT_DIRECTORY_URI] = uriString
            } else {
                preferences.remove(PreferencesKeys.CUSTOM_EXPORT_DIRECTORY_URI)
            }
            if (displayName != null) {
                preferences[PreferencesKeys.CUSTOM_EXPORT_DIRECTORY_NAME] = displayName
            } else {
                preferences.remove(PreferencesKeys.CUSTOM_EXPORT_DIRECTORY_NAME)
            }
        }
    }

    suspend fun clearCustomExportDirectory() {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.CUSTOM_EXPORT_DIRECTORY_URI)
            preferences.remove(PreferencesKeys.CUSTOM_EXPORT_DIRECTORY_NAME)
        }
    }

    suspend fun setAssistantEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ASSISTANT_ENABLED] = enabled
        }
    }

    suspend fun setAssistantAiDisclosureAccepted(accepted: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ASSISTANT_AI_DISCLOSURE_ACCEPTED] = accepted
        }
    }

    suspend fun setAudioTranscriptionConsentAccepted(accepted: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUDIO_TRANSCRIPTION_CONSENT_ACCEPTED] = accepted
        }
    }

    suspend fun setSmartNotificationsExtractionFailure(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SMART_NOTIF_EXTRACTION_FAILURE] = enabled
        }
    }

    suspend fun setSmartNotificationsDownloadFailure(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SMART_NOTIF_DOWNLOAD_FAILURE] = enabled
        }
    }

    suspend fun setSmartNotificationsAppHealth(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SMART_NOTIF_APP_HEALTH] = enabled
        }
    }

    suspend fun setSmartNotificationsSummaries(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SMART_NOTIF_SUMMARIES] = enabled
        }
    }

    suspend fun setSmartNotificationsTips(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SMART_NOTIF_TIPS] = enabled
        }
    }

    suspend fun setSmartNotificationsMoviesTv(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SMART_NOTIF_MOVIES_TV] = enabled
        }
    }

    suspend fun setQuietHoursEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.QUIET_HOURS_ENABLED] = enabled
        }
    }

    suspend fun setQuietHoursWindow(startHour: Int, endHour: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.QUIET_HOURS_START_HOUR] = startHour.coerceIn(0, 23)
            preferences[PreferencesKeys.QUIET_HOURS_END_HOUR] = endHour.coerceIn(0, 23)
        }
    }

    suspend fun recordShownTip(tipId: String, timestamp: Long = System.currentTimeMillis()) {
        dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.SHOWN_TIP_IDS] ?: emptySet()
            preferences[PreferencesKeys.SHOWN_TIP_IDS] = current + tipId
            preferences[PreferencesKeys.LAST_TIP_NOTIFICATION_AT] = timestamp
        }
    }

    suspend fun resetShownTips() {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.SHOWN_TIP_IDS)
        }
    }

    suspend fun recordAppHealthAlertPosted(timestamp: Long = System.currentTimeMillis()) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_APP_HEALTH_ALERT_AT] = timestamp
        }
    }

    suspend fun recordSummaryNotificationPosted(timestamp: Long = System.currentTimeMillis()) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_SUMMARY_NOTIFICATION_AT] = timestamp
        }
    }

    suspend fun recordMoviesNotificationPosted(timestamp: Long = System.currentTimeMillis()) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_MOVIES_NOTIFICATION_AT] = timestamp
        }
    }

    suspend fun recordFirstLaunchIfNeeded(timestamp: Long = System.currentTimeMillis()) {
        dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.FIRST_LAUNCH_AT] ?: 0L
            if (current <= 0L) {
                preferences[PreferencesKeys.FIRST_LAUNCH_AT] = timestamp
            }
        }
    }

    suspend fun recordFirstLinkSavedIfNeeded(timestamp: Long = System.currentTimeMillis()) {
        dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.FIRST_LINK_SAVED_AT] ?: 0L
            if (current <= 0L) {
                preferences[PreferencesKeys.FIRST_LINK_SAVED_AT] = timestamp
            }
        }
    }

    suspend fun incrementReaderOpens() {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.READER_OPENS_COUNT] =
                (preferences[PreferencesKeys.READER_OPENS_COUNT] ?: 0) + 1
        }
    }

    suspend fun incrementRemindersDelivered() {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.REMINDERS_DELIVERED_COUNT] =
                (preferences[PreferencesKeys.REMINDERS_DELIVERED_COUNT] ?: 0) + 1
        }
    }

    suspend fun incrementRemindersOpened() {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.REMINDERS_OPENED_COUNT] =
                (preferences[PreferencesKeys.REMINDERS_OPENED_COUNT] ?: 0) + 1
        }
    }

    suspend fun recordCaptureSource(source: com.laterbox.app.capture.CaptureSource) {
        dataStore.edit { preferences ->
            when (source) {
                com.laterbox.app.capture.CaptureSource.SHARE_SHEET,
                com.laterbox.app.capture.CaptureSource.PROCESS_TEXT -> {
                    preferences[PreferencesKeys.SHARE_CAPTURES_COUNT] =
                        (preferences[PreferencesKeys.SHARE_CAPTURES_COUNT] ?: 0) + 1
                }
                com.laterbox.app.capture.CaptureSource.QUICK_SETTINGS_TILE,
                com.laterbox.app.capture.CaptureSource.ACCESSIBILITY_SELECTION -> {
                    preferences[PreferencesKeys.QUICK_TILE_CAPTURES_COUNT] =
                        (preferences[PreferencesKeys.QUICK_TILE_CAPTURES_COUNT] ?: 0) + 1
                }
                com.laterbox.app.capture.CaptureSource.IN_APP,
                com.laterbox.app.capture.CaptureSource.CLIPBOARD_SUGGESTION -> {
                    preferences[PreferencesKeys.MANUAL_CAPTURES_COUNT] =
                        (preferences[PreferencesKeys.MANUAL_CAPTURES_COUNT] ?: 0) + 1
                }
            }
        }
    }

    suspend fun resetActivityCounters() {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.REMINDERS_DELIVERED_COUNT)
            preferences.remove(PreferencesKeys.REMINDERS_OPENED_COUNT)
            preferences.remove(PreferencesKeys.READER_OPENS_COUNT)
            preferences.remove(PreferencesKeys.SHARE_CAPTURES_COUNT)
            preferences.remove(PreferencesKeys.QUICK_TILE_CAPTURES_COUNT)
            preferences.remove(PreferencesKeys.MANUAL_CAPTURES_COUNT)
        }
    }

    suspend fun setTmdbApiKey(apiKey: String) {
        dataStore.edit { preferences ->
            val trimmed = apiKey.trim()
            if (trimmed.isNotBlank()) {
                preferences[PreferencesKeys.TMDB_API_KEY] = trimmed
            } else {
                preferences.remove(PreferencesKeys.TMDB_API_KEY)
            }
        }
    }

    suspend fun setMoviesUseSampleFixturesOnly(enabled: Boolean) {
        dataStore.edit { preferences ->
            if (com.laterbox.app.BuildConfig.DEBUG) {
                preferences[PreferencesKeys.DEBUG_MOVIES_SAMPLE_FIXTURES] = enabled
            } else {
                preferences.remove(PreferencesKeys.DEBUG_MOVIES_SAMPLE_FIXTURES)
            }
            preferences.remove(PreferencesKeys.MOVIES_PROVIDER_MODE)
            preferences.remove(PreferencesKeys.MOVIES_USE_SAMPLE_FIXTURES_ONLY)
            preferences.remove(PreferencesKeys.MOVIEBOX_API_KEY)
            preferences[PreferencesKeys.MOVIES_PREFS_MIGRATED_V2] = true
        }
    }

    @Volatile
    private var legacyMoviesPrefsVerifiedClean: Boolean = false

    suspend fun migrateLegacyMoviesPreferences() {
        if (legacyMoviesPrefsVerifiedClean) return
        val current = dataStore.data.first()
        val alreadyMigrated = current[PreferencesKeys.MOVIES_PREFS_MIGRATED_V2] ?: false
        val hasLegacyKeys = current.contains(PreferencesKeys.MOVIES_PROVIDER_MODE) ||
            current.contains(PreferencesKeys.MOVIES_USE_SAMPLE_FIXTURES_ONLY) ||
            current.contains(PreferencesKeys.MOVIEBOX_API_KEY)
        if (alreadyMigrated && !hasLegacyKeys) {
            legacyMoviesPrefsVerifiedClean = true
            return
        }
        dataStore.edit { preferences ->
            val migratedNow = preferences[PreferencesKeys.MOVIES_PREFS_MIGRATED_V2] ?: false
            val legacyNow = preferences.contains(PreferencesKeys.MOVIES_PROVIDER_MODE) ||
                preferences.contains(PreferencesKeys.MOVIES_USE_SAMPLE_FIXTURES_ONLY) ||
                preferences.contains(PreferencesKeys.MOVIEBOX_API_KEY)
            if (!migratedNow || legacyNow) {
                preferences.remove(PreferencesKeys.MOVIES_PROVIDER_MODE)
                preferences.remove(PreferencesKeys.MOVIES_USE_SAMPLE_FIXTURES_ONLY)
                preferences.remove(PreferencesKeys.MOVIEBOX_API_KEY)
                preferences[PreferencesKeys.MOVIES_PREFS_MIGRATED_V2] = true
            }
        }
        legacyMoviesPrefsVerifiedClean = true
    }

    suspend fun setMovieBoxApiBaseUrl(baseUrl: String) {
        val normalized = MovieBoxEndpointConfig.normalizeBaseUrl(baseUrl)
        dataStore.edit { preferences ->
            if (normalized.isNotBlank()) {
                preferences[PreferencesKeys.MOVIEBOX_API_BASE_URL] = normalized
            } else {
                preferences.remove(PreferencesKeys.MOVIEBOX_API_BASE_URL)
            }
        }
    }

    suspend fun setMoviesDoubleTapSeekSeconds(seconds: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MOVIES_DOUBLE_TAP_SEEK_SECONDS] = seconds.coerceIn(5, 60)
        }
    }

    suspend fun setMoviesSwipeToSeekEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MOVIES_SWIPE_TO_SEEK_ENABLED] = enabled
        }
    }

    suspend fun setMoviesSwipeSeekSensitivity(sensitivity: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MOVIES_SWIPE_SEEK_SENSITIVITY] = sensitivity.coerceIn(0.25f, 2.0f)
        }
    }

    suspend fun setMoviesBrightnessGestureEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MOVIES_BRIGHTNESS_GESTURE_ENABLED] = enabled
        }
    }

    suspend fun setMoviesVolumeGestureEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MOVIES_VOLUME_GESTURE_ENABLED] = enabled
        }
    }

    suspend fun setMoviesAutoplayNextEpisode(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MOVIES_AUTOPLAY_NEXT_EPISODE] = enabled
        }
    }

    suspend fun setMoviesAutoplayCountdownSeconds(seconds: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MOVIES_AUTOPLAY_COUNTDOWN_SECONDS] = seconds.coerceIn(3, 30)
        }
    }

    suspend fun setMoviesDefaultQualityHeight(height: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MOVIES_DEFAULT_QUALITY_HEIGHT] = height.coerceIn(240, 2160)
        }
    }

    suspend fun setMoviesTrailerMuted(muted: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MOVIES_TRAILER_MUTED] = muted
        }
    }

    suspend fun setMoviesAmbientModeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MOVIES_AMBIENT_MODE_ENABLED] = enabled
        }
    }

    suspend fun setSubtitleUseSystemCaptions(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SUBTITLE_USE_SYSTEM_CAPTIONS] = enabled
        }
    }

    suspend fun setSubtitleStyleConfig(config: SubtitleStyleConfig) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SUBTITLE_USE_SYSTEM_CAPTIONS] = config.useSystemCaptions
            preferences[PreferencesKeys.SUBTITLE_FONT_SIZE_SP] = config.fontSizeSp.coerceIn(12f, 36f)
            preferences[PreferencesKeys.SUBTITLE_TEXT_COLOR_ARGB] = config.textColorArgb
            preferences[PreferencesKeys.SUBTITLE_BACKGROUND_COLOR_ARGB] = config.backgroundColorArgb
            preferences[PreferencesKeys.SUBTITLE_BACKGROUND_OPACITY] = config.backgroundOpacity.coerceIn(0f, 1f)
            preferences[PreferencesKeys.SUBTITLE_EDGE_TYPE] = config.edgeType.coerceIn(0, 2)
        }
    }

    suspend fun setAssistantCategoryPermission(categoryKey: String, allowed: Boolean) {
        val clean = categoryKey.trim().uppercase()
        if (clean.isEmpty()) return
        dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.GRANTED_ASSISTANT_CATEGORIES] ?: emptySet()
            preferences[PreferencesKeys.GRANTED_ASSISTANT_CATEGORIES] = if (allowed) {
                current + clean
            } else {
                current - clean
            }
        }
    }

    suspend fun revokeAllAssistantCategoryPermissions() {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.GRANTED_ASSISTANT_CATEGORIES)
        }
    }

    /**
     * Idempotently retires legacy individual analytics opt-out DataStore keys from earlier builds without
     * modifying or resetting any unrelated preferences.
     */
    suspend fun retireLegacyAnalyticsOptOutPreferences() {
        val legacyAnalyticsEnabledKey = booleanPreferencesKey("analytics_enabled")
        val legacyMigratedKey = booleanPreferencesKey("analytics_policy_migrated_v1")
        dataStore.edit { preferences ->
            if (preferences.contains(legacyAnalyticsEnabledKey)) {
                preferences.remove(legacyAnalyticsEnabledKey)
            }
            if (preferences.contains(legacyMigratedKey)) {
                preferences.remove(legacyMigratedKey)
            }
        }
    }

    suspend fun markAlarmsMigratedV2() {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ALARMS_MIGRATED_V2] = true
        }
    }

    suspend fun recordTier3NotificationPosted(dayEpoch: Long, timestampMs: Long) {
        dataStore.edit { preferences ->
            val currentDay = preferences[PreferencesKeys.TIER3_BUDGET_DAY_EPOCH] ?: 0L
            val currentCount = (preferences[PreferencesKeys.TIER3_DAILY_COUNT] ?: 0).coerceAtLeast(0)
            preferences[PreferencesKeys.TIER3_BUDGET_DAY_EPOCH] = dayEpoch
            preferences[PreferencesKeys.TIER3_DAILY_COUNT] = if (currentDay == dayEpoch) currentCount + 1 else 1
            preferences[PreferencesKeys.LAST_TIER3_NOTIFICATION_AT] = timestampMs
        }
    }

    suspend fun applyTier3SwipeBackoff(
        categoryName: String,
        backoffUntilMs: Long,
        newStreak: Int
    ) {
        dataStore.edit { preferences ->
            val backoffs = (preferences[PreferencesKeys.TIER3_CATEGORY_BACKOFF_UNTIL] ?: emptySet())
                .filterNot { it.startsWith("$categoryName:") }
                .toMutableSet()
            backoffs.add("$categoryName:$backoffUntilMs")
            preferences[PreferencesKeys.TIER3_CATEGORY_BACKOFF_UNTIL] = backoffs

            val streaks = (preferences[PreferencesKeys.TIER3_SWIPE_STREAK] ?: emptySet())
                .filterNot { it.startsWith("$categoryName:") }
                .toMutableSet()
            streaks.add("$categoryName:$newStreak")
            preferences[PreferencesKeys.TIER3_SWIPE_STREAK] = streaks
        }
    }

    suspend fun clearTier3SwipeBackoff(categoryName: String) {
        dataStore.edit { preferences ->
            val backoffs = (preferences[PreferencesKeys.TIER3_CATEGORY_BACKOFF_UNTIL] ?: emptySet())
                .filterNot { it.startsWith("$categoryName:") }
                .toSet()
            preferences[PreferencesKeys.TIER3_CATEGORY_BACKOFF_UNTIL] = backoffs

            val streaks = (preferences[PreferencesKeys.TIER3_SWIPE_STREAK] ?: emptySet())
                .filterNot { it.startsWith("$categoryName:") }
                .toSet()
            preferences[PreferencesKeys.TIER3_SWIPE_STREAK] = streaks
        }
    }

    suspend fun resetNotificationGovernorStateForTest() {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.TIER3_BUDGET_DAY_EPOCH)
            preferences.remove(PreferencesKeys.TIER3_DAILY_COUNT)
            preferences.remove(PreferencesKeys.LAST_TIER3_NOTIFICATION_AT)
            preferences.remove(PreferencesKeys.TIER3_CATEGORY_BACKOFF_UNTIL)
            preferences.remove(PreferencesKeys.TIER3_SWIPE_STREAK)
            preferences.remove(PreferencesKeys.LAST_APP_HEALTH_ALERT_AT)
            preferences.remove(PreferencesKeys.LAST_TIP_NOTIFICATION_AT)
            preferences.remove(PreferencesKeys.LAST_SUMMARY_NOTIFICATION_AT)
            preferences.remove(PreferencesKeys.LAST_MOVIES_NOTIFICATION_AT)
        }
    }
}
