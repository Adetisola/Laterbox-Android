package com.laterbox.app.remoteconfig

import com.laterbox.app.ai.RemoteCapabilities
import java.security.MessageDigest
import java.time.Instant

const val SUPPORTED_REMOTE_CONFIG_SCHEMA_VERSION = 1

enum class NoticeExclusionReason {
    NONE,
    INVALID_ID,
    DUPLICATE_ID,
    DISABLED,
    EMPTY_MESSAGE,
    VERSION_BELOW_MIN,
    VERSION_ABOVE_MAX,
    SURFACE_MISMATCH,
    DISMISSED,
    OVER_LIMIT,
}

data class RemoteNoticeDiagnostic(
    val id: String,
    val reason: NoticeExclusionReason,
    val minVersionCode: Int = 0,
    val maxVersionCode: Int = 0,
    val severity: RemoteAnnouncementSeverity = RemoteAnnouncementSeverity.INFO,
)

enum class RemoteConfigSource {
    DEFAULT,
    CACHE,
    NETWORK,
}

enum class RemoteMaintenanceMode(val wireValue: String) {
    NONE("none"),
    PARTIAL("partial"),
    FULL("full");

    companion object {
        fun fromWire(raw: String?): RemoteMaintenanceMode = when (raw?.trim()?.lowercase()) {
            "partial" -> PARTIAL
            "full" -> FULL
            else -> NONE
        }
    }
}

enum class RemoteAnnouncementSeverity(val wireValue: String) {
    INFO("info"),
    WARNING("warning"),
    CRITICAL("critical");

    companion object {
        fun fromWire(raw: String?): RemoteAnnouncementSeverity = when (raw?.trim()?.lowercase()) {
            "warning" -> WARNING
            "critical" -> CRITICAL
            else -> INFO
        }
    }
}

enum class RemoteUiSurface(val wireValue: String) {
    INBOX("inbox"),
    COLLECTIONS("collections"),
    MOVIES("movies"),
    DOWNLOADS("downloads"),
    ARCHIVE("archive"),
    ASSISTANT("assistant");

    companion object {
        fun fromWire(raw: String?): RemoteUiSurface = when (raw?.trim()?.lowercase()) {
            "collections" -> COLLECTIONS
            "movies" -> MOVIES
            "downloads" -> DOWNLOADS
            "archive" -> ARCHIVE
            "assistant" -> ASSISTANT
            else -> INBOX
        }
    }
}

data class RemoteGeneralPolicy(
    val minRefreshIntervalSeconds: Int = 60,
    val staleCacheMaxAgeSeconds: Int = 604800,
)

data class RemoteVersionPolicy(
    val minimumSupportedVersionCode: Int = 0,
    val recommendedVersionCode: Int = 0,
    val enforcementEnabled: Boolean = false,
    val enforcementMode: String = "",
    val minimumSupportedTag: String = "",
    val recommendedTag: String = "",
    val updateMessage: String = "",
) {
    val normalizedEnforcementMode: String
        get() = when (enforcementMode.trim().lowercase()) {
            MODE_ENFORCE -> MODE_ENFORCE
            MODE_MONITOR -> MODE_MONITOR
            MODE_DISABLED -> MODE_DISABLED
            else -> if (enforcementEnabled) MODE_ENFORCE else MODE_DISABLED
        }

    val isEnforceModeActive: Boolean
        get() = normalizedEnforcementMode == MODE_ENFORCE

    companion object {
        const val MODE_DISABLED = "disabled"
        const val MODE_MONITOR = "monitor"
        const val MODE_ENFORCE = "enforce"
    }
}

internal fun parseScheduleEpochMs(raw: String?): Long? {
    val clean = raw?.trim().orEmpty()
    if (clean.isEmpty()) return null
    return runCatching { Instant.parse(clean).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(clean).toInstant().toEpochMilli() }.getOrNull()
}

internal fun isScheduleWindowActive(
    startsAt: String?,
    endsAt: String?,
    expiresAt: String?,
    nowEpochMs: Long = System.currentTimeMillis(),
): Boolean {
    if (!startsAt.isNullOrBlank()) {
        val startMs = parseScheduleEpochMs(startsAt) ?: return false
        if (startMs > nowEpochMs) return false
    }
    val endSource = endsAt?.takeIf { it.isNotBlank() } ?: expiresAt?.takeIf { it.isNotBlank() }
    if (endSource != null) {
        val endMs = parseScheduleEpochMs(endSource) ?: return false
        if (endMs < nowEpochMs) return false
    }
    return true
}

data class RemoteMaintenanceConfig(
    val enabled: Boolean = false,
    val mode: RemoteMaintenanceMode = RemoteMaintenanceMode.NONE,
    val title: String = "",
    val message: String = "",
    val allowLocalOfflineAccess: Boolean = true,
    val showBannerInNormalMode: Boolean = false,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val expiresAt: String? = null,
) {
    fun isWithinSchedule(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        isScheduleWindowActive(startsAt, endsAt, expiresAt, nowEpochMs)

    val isPartialActive: Boolean
        get() = enabled && mode == RemoteMaintenanceMode.PARTIAL && isWithinSchedule()

    val isFullActive: Boolean
        get() = enabled && mode == RemoteMaintenanceMode.FULL && isWithinSchedule()

    val isOnlineServicesPaused: Boolean
        get() = enabled &&
            (mode == RemoteMaintenanceMode.PARTIAL || mode == RemoteMaintenanceMode.FULL) &&
            isWithinSchedule()

    val shouldShowStatusStrip: Boolean
        get() = isWithinSchedule() && (
            (enabled && mode != RemoteMaintenanceMode.NONE) ||
                (!enabled && showBannerInNormalMode && message.isNotBlank())
            )
}

data class RemoteAnnouncementItem(
    val id: String,
    val title: String = "",
    val message: String,
    val severity: RemoteAnnouncementSeverity = RemoteAnnouncementSeverity.INFO,
    val surface: RemoteUiSurface = RemoteUiSurface.INBOX,
    val surfaces: List<RemoteUiSurface> = listOf(surface),
    val dismissible: Boolean = true,
    val actionLabel: String = "",
    val actionDestination: String = "",
    val minVersionCode: Int = 0,
    val maxVersionCode: Int = 0,
    val enabled: Boolean = true,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val expiresAt: String? = null,
) {
    fun isWithinSchedule(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        isScheduleWindowActive(startsAt, endsAt, expiresAt, nowEpochMs)

    fun targetsSurface(target: RemoteUiSurface): Boolean {
        return target in surfaces || (surfaces.isEmpty() && surface == target)
    }

    /**
     * Scopes dismissal persistence to the specific notice content and target range
     * so editing an existing notice ID re-prompts eligible users automatically.
     */
    fun dismissalKey(): String {
        val canonical = buildString {
            append(title.trim()).append('|')
            append(message.trim()).append('|')
            append(severity.wireValue).append('|')
            append(surfaces.joinToString(",") { it.wireValue }).append('|')
            append(dismissible).append('|')
            append(actionLabel.trim()).append('|')
            append(actionDestination.trim()).append('|')
            append(minVersionCode).append('|')
            append(maxVersionCode)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        val hexPrefix = buildString(12) {
            for (i in 0 until 6) {
                append(((digest[i].toInt() ushr 4) and 0x0F).toString(16))
                append((digest[i].toInt() and 0x0F).toString(16))
            }
        }
        return "$id#$hexPrefix"
    }
}

data class RemoteHeroSlide(
    val id: String,
    val imageUrl: String = "",
    val heading: String = "",
    val subtitle: String = "",
    val actionLabel: String = "",
    val actionDestination: String = "",
    val enabled: Boolean = true,
    val order: Int = 0,
)

data class RemoteUiComponent(
    val id: String,
    val type: String,
    val surface: RemoteUiSurface = RemoteUiSurface.MOVIES,
    val visible: Boolean = true,
    val order: Int = 0,
    val minVersionCode: Int = 0,
    val maxVersionCode: Int = 0,
    val railId: String? = null,
    val title: String = "",
    val subtitle: String = "",
    val severity: RemoteAnnouncementSeverity = RemoteAnnouncementSeverity.INFO,
    val dismissible: Boolean = true,
    val maxItems: Int = 12,
    val autoAdvanceMs: Int = 6000,
    val actionLabel: String = "",
    val actionDestination: String = "",
    val slides: List<RemoteHeroSlide> = emptyList(),
)

data class RemoteUiConfiguration(
    val components: List<RemoteUiComponent> = emptyList(),
)

data class RemoteConfigSnapshot(
    val schemaVersion: Int = SUPPORTED_REMOTE_CONFIG_SCHEMA_VERSION,
    val revision: Int = 0,
    val publishedAt: String = "",
    val expiresAt: String? = null,
    val etag: String? = null,
    val fetchedAtEpochMs: Long = 0L,
    val source: RemoteConfigSource = RemoteConfigSource.DEFAULT,
    val general: RemoteGeneralPolicy = RemoteGeneralPolicy(),
    val versionPolicy: RemoteVersionPolicy = RemoteVersionPolicy(),
    val maintenance: RemoteMaintenanceConfig = RemoteMaintenanceConfig(),
    val announcements: List<RemoteAnnouncementItem> = emptyList(),
    val features: Map<String, Boolean> = RemoteCapabilities.DEFAULT_FEATURES,
    val ui: RemoteUiConfiguration = RemoteUiConfiguration(),
    val analyticsCollectionEnabled: Boolean = true,
    val analyticsPausedReason: String = "",
) {
    fun isFeatureEnabled(featureKey: String, defaultValue: Boolean = true): Boolean {
        if (featureKey !in RemoteCapabilities.SUPPORTED_FEATURE_KEYS) {
            return defaultValue
        }
        return features[featureKey] ?: defaultValue
    }

    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val raw = expiresAt?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val parsed = runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull() ?: return true
        return parsed <= nowEpochMs
    }

    fun isStale(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        if (source == RemoteConfigSource.DEFAULT) return false
        if (fetchedAtEpochMs <= 0L) return true
        val maxAgeMs = general.staleCacheMaxAgeSeconds.coerceAtLeast(300) * 1000L
        return (nowEpochMs - fetchedAtEpochMs) > maxAgeMs
    }

    fun componentsForSurface(surface: RemoteUiSurface): List<RemoteUiComponent> {
        return ui.components
            .filter { it.surface == surface && it.type in RemoteCapabilities.SUPPORTED_UI_COMPONENT_TYPES }
            .sortedBy { it.order }
    }

    fun activeAnnouncementsForSurface(
        surface: RemoteUiSurface,
        dismissedIds: Set<String> = emptySet(),
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<RemoteAnnouncementItem> {
        return announcements
            .filter { item ->
                item.enabled &&
                    item.isWithinSchedule(nowEpochMs) &&
                    item.targetsSurface(surface) &&
                    (!item.dismissible || (item.id !in dismissedIds && item.dismissalKey() !in dismissedIds))
            }
            .sortedBy { item ->
                when (item.severity) {
                    RemoteAnnouncementSeverity.CRITICAL -> 0
                    RemoteAnnouncementSeverity.WARNING -> 1
                    RemoteAnnouncementSeverity.INFO -> 2
                }
            }
    }

    companion object {
        fun default(fetchedAtEpochMs: Long = 0L): RemoteConfigSnapshot = RemoteConfigSnapshot(
            schemaVersion = SUPPORTED_REMOTE_CONFIG_SCHEMA_VERSION,
            revision = 0,
            publishedAt = "",
            expiresAt = null,
            etag = null,
            fetchedAtEpochMs = fetchedAtEpochMs,
            source = RemoteConfigSource.DEFAULT,
            general = RemoteGeneralPolicy(),
            versionPolicy = RemoteVersionPolicy(),
            maintenance = RemoteMaintenanceConfig(),
            announcements = emptyList(),
            features = RemoteCapabilities.DEFAULT_FEATURES,
            ui = RemoteUiConfiguration(),
        )
    }
}

data class RemoteConfigState(
    val activeConfig: RemoteConfigSnapshot = RemoteConfigSnapshot.default(),
    val isRefreshing: Boolean = false,
    val lastFetchAttemptEpochMs: Long = 0L,
    val lastSuccessfulFetchEpochMs: Long = 0L,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
    val nextRetryAtEpochMs: Long = 0L,
)
