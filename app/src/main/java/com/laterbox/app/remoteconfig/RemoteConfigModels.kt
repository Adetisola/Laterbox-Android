package com.laterbox.app.remoteconfig

import com.laterbox.app.ai.RemoteCapabilities
import java.time.Instant

const val SUPPORTED_REMOTE_CONFIG_SCHEMA_VERSION = 1

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
    MOVIES("movies");

    companion object {
        fun fromWire(raw: String?): RemoteUiSurface = when (raw?.trim()?.lowercase()) {
            "movies" -> MOVIES
            else -> INBOX
        }
    }
}

data class RemoteGeneralPolicy(
    val minRefreshIntervalSeconds: Int = 900,
    val staleCacheMaxAgeSeconds: Int = 604800,
)

data class RemoteVersionPolicy(
    val minimumSupportedVersionCode: Int = 0,
    val recommendedVersionCode: Int = 0,
    val enforcementEnabled: Boolean = false,
    val recommendedTag: String = "",
    val updateMessage: String = "",
)

data class RemoteMaintenanceConfig(
    val enabled: Boolean = false,
    val mode: RemoteMaintenanceMode = RemoteMaintenanceMode.NONE,
    val title: String = "",
    val message: String = "",
    val allowLocalOfflineAccess: Boolean = true,
) {
    val isPartialActive: Boolean
        get() = enabled && mode == RemoteMaintenanceMode.PARTIAL

    val isFullActive: Boolean
        get() = enabled && mode == RemoteMaintenanceMode.FULL
}

data class RemoteAnnouncementItem(
    val id: String,
    val title: String,
    val message: String,
    val severity: RemoteAnnouncementSeverity = RemoteAnnouncementSeverity.INFO,
    val surface: RemoteUiSurface = RemoteUiSurface.INBOX,
    val dismissible: Boolean = true,
    val actionLabel: String = "",
    val actionDestination: String = "",
    val minVersionCode: Int = 0,
    val maxVersionCode: Int = 0,
    val enabled: Boolean = true,
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
    ): List<RemoteAnnouncementItem> {
        return announcements.filter { item ->
            item.enabled &&
                item.surface == surface &&
                (!item.dismissible || item.id !in dismissedIds)
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
)
