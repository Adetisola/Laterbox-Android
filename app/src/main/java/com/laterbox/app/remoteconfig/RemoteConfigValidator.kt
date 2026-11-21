package com.laterbox.app.remoteconfig

import com.laterbox.app.ai.RemoteCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

sealed class RemoteConfigParseResult {
    data class Valid(val snapshot: RemoteConfigSnapshot) : RemoteConfigParseResult()
    data class Rejected(val reason: String) : RemoteConfigParseResult()
}

object RemoteConfigValidator {
    private val safeIdRegex = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private val unsafeMarkupRegex = Regex("<\\s*script|javascript:|onerror=|onload=", RegexOption.IGNORE_CASE)

    fun parseAndValidate(
        rawJson: String,
        clientVersionCode: Int,
        source: RemoteConfigSource,
        etag: String? = null,
        fetchedAtEpochMs: Long = System.currentTimeMillis(),
        nowEpochMs: Long = fetchedAtEpochMs,
        enforceExpiry: Boolean = true,
    ): RemoteConfigParseResult {
        if (rawJson.isBlank()) {
            return RemoteConfigParseResult.Rejected("Empty remote configuration payload")
        }

        val root = try {
            JSONObject(rawJson)
        } catch (t: Throwable) {
            return RemoteConfigParseResult.Rejected("Malformed JSON: ${t.message}")
        }

        val schemaVersion = root.optInt("schemaVersion", -1)
        if (schemaVersion != SUPPORTED_REMOTE_CONFIG_SCHEMA_VERSION) {
            return RemoteConfigParseResult.Rejected("Unsupported schemaVersion: $schemaVersion")
        }

        val revision = root.optInt("revision", -1)
        if (revision < 0) {
            return RemoteConfigParseResult.Rejected("Invalid revision: $revision")
        }

        val publishedAt = sanitizePlainText(root.optString("publishedAt", ""), 64)
        val rawExpiresAt = root.opt("expiresAt")
            ?.takeIf { it != JSONObject.NULL }
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (rawExpiresAt != null) {
            val parsedExpiry = runCatching { Instant.parse(rawExpiresAt).toEpochMilli() }.getOrNull()
                ?: return RemoteConfigParseResult.Rejected("Malformed expiresAt timestamp")
            if (enforceExpiry && parsedExpiry <= nowEpochMs) {
                return RemoteConfigParseResult.Rejected("Remote configuration has expired at $rawExpiresAt")
            }
        }

        val generalObj = root.optJSONObject("general")
        val general = RemoteGeneralPolicy(
            minRefreshIntervalSeconds = (generalObj?.optInt("minRefreshIntervalSeconds", 900) ?: 900)
                .coerceIn(60, 86400),
            staleCacheMaxAgeSeconds = (generalObj?.optInt("staleCacheMaxAgeSeconds", 604800) ?: 604800)
                .coerceIn(300, 2592000),
        )

        val vpObj = root.optJSONObject("versionPolicy")
        val versionPolicy = RemoteVersionPolicy(
            minimumSupportedVersionCode = (vpObj?.optInt("minimumSupportedVersionCode", 0) ?: 0).coerceIn(0, 1_000_000),
            recommendedVersionCode = (vpObj?.optInt("recommendedVersionCode", 0) ?: 0).coerceIn(0, 1_000_000),
            enforcementEnabled = vpObj?.optBoolean("enforcementEnabled", false) ?: false,
            recommendedTag = sanitizePlainText(vpObj?.optString("recommendedTag", "") ?: "", 32),
            updateMessage = sanitizePlainText(vpObj?.optString("updateMessage", "") ?: "", 500),
        )

        val maintObj = root.optJSONObject("maintenance")
        val maintMode = RemoteMaintenanceMode.fromWire(maintObj?.optString("mode", "none"))
        val maintEnabled = maintObj?.optBoolean("enabled", false) ?: false
        val maintenance = RemoteMaintenanceConfig(
            enabled = maintEnabled && maintMode != RemoteMaintenanceMode.NONE,
            mode = if (maintEnabled) maintMode else RemoteMaintenanceMode.NONE,
            title = sanitizePlainText(maintObj?.optString("title", "") ?: "", 120),
            message = sanitizePlainText(maintObj?.optString("message", "") ?: "", 600),
            allowLocalOfflineAccess = maintObj?.optBoolean("allowLocalOfflineAccess", true) ?: true,
        )

        // Allowlist-filtered features; unknown keys ignored safely without failure
        val featuresMap = RemoteCapabilities.DEFAULT_FEATURES.toMutableMap()
        val featuresObj = root.optJSONObject("features")
        if (featuresObj != null) {
            for (key in RemoteCapabilities.SUPPORTED_FEATURE_KEYS) {
                if (featuresObj.has(key) && featuresObj.opt(key) is Boolean) {
                    featuresMap[key] = featuresObj.optBoolean(key, true)
                }
            }
        }

        // Announcements (up to 5, ignoring unsafe or out-of-range version items)
        val announcementsList = mutableListOf<RemoteAnnouncementItem>()
        val announcementsArr = root.optJSONArray("announcements")
        val seenAnnouncementIds = mutableSetOf<String>()
        if (announcementsArr != null) {
            for (i in 0 until minOf(announcementsArr.length(), 10)) {
                val item = announcementsArr.optJSONObject(i) ?: continue
                val parsed = parseAnnouncement(item, clientVersionCode) ?: continue
                if (seenAnnouncementIds.add(parsed.id)) {
                    announcementsList += parsed
                    if (announcementsList.size >= 5) break
                }
            }
        }

        // Native SDUI components (strict allowlist; unknown types/rails silently skipped)
        val uiObj = root.optJSONObject("ui")
        val componentsArr = uiObj?.optJSONArray("components")
        val componentList = mutableListOf<RemoteUiComponent>()
        val seenComponentIds = mutableSetOf<String>()
        if (componentsArr != null) {
            for (i in 0 until minOf(componentsArr.length(), 30)) {
                val item = componentsArr.optJSONObject(i) ?: continue
                val parsed = parseUiComponent(item, clientVersionCode) ?: continue
                if (seenComponentIds.add(parsed.id)) {
                    componentList += parsed
                    if (componentList.size >= 20) break
                }
            }
        }

        val sortedComponents = componentList.sortedBy { it.order }

        return RemoteConfigParseResult.Valid(
            RemoteConfigSnapshot(
                schemaVersion = schemaVersion,
                revision = revision,
                publishedAt = publishedAt,
                expiresAt = rawExpiresAt,
                etag = etag,
                fetchedAtEpochMs = fetchedAtEpochMs,
                source = source,
                general = general,
                versionPolicy = versionPolicy,
                maintenance = maintenance,
                announcements = announcementsList,
                features = featuresMap.toMap(),
                ui = RemoteUiConfiguration(components = sortedComponents),
            )
        )
    }

    fun isAllowedDestination(destination: String): Boolean {
        val clean = destination.trim()
        if (clean.isEmpty()) return true
        if (clean in RemoteCapabilities.SUPPORTED_DESTINATIONS) return true
        if (clean.startsWith("https://", ignoreCase = true) && clean.length <= 256) {
            return !unsafeMarkupRegex.containsMatchIn(clean)
        }
        return false
    }

    fun serializeToJson(snapshot: RemoteConfigSnapshot): String {
        val root = JSONObject()
        root.put("schemaVersion", snapshot.schemaVersion)
        root.put("revision", snapshot.revision)
        root.put("publishedAt", snapshot.publishedAt)
        root.put("expiresAt", snapshot.expiresAt ?: JSONObject.NULL)

        val general = JSONObject()
        general.put("minRefreshIntervalSeconds", snapshot.general.minRefreshIntervalSeconds)
        general.put("staleCacheMaxAgeSeconds", snapshot.general.staleCacheMaxAgeSeconds)
        root.put("general", general)

        val vp = JSONObject()
        vp.put("minimumSupportedVersionCode", snapshot.versionPolicy.minimumSupportedVersionCode)
        vp.put("recommendedVersionCode", snapshot.versionPolicy.recommendedVersionCode)
        vp.put("enforcementEnabled", snapshot.versionPolicy.enforcementEnabled)
        vp.put("recommendedTag", snapshot.versionPolicy.recommendedTag)
        vp.put("updateMessage", snapshot.versionPolicy.updateMessage)
        root.put("versionPolicy", vp)

        val maint = JSONObject()
        maint.put("enabled", snapshot.maintenance.enabled)
        maint.put("mode", snapshot.maintenance.mode.wireValue)
        maint.put("title", snapshot.maintenance.title)
        maint.put("message", snapshot.maintenance.message)
        maint.put("allowLocalOfflineAccess", snapshot.maintenance.allowLocalOfflineAccess)
        root.put("maintenance", maint)

        val announcementsArr = JSONArray()
        snapshot.announcements.forEach { ann ->
            announcementsArr.put(
                JSONObject().apply {
                    put("id", ann.id)
                    put("title", ann.title)
                    put("message", ann.message)
                    put("severity", ann.severity.wireValue)
                    put("surface", ann.surface.wireValue)
                    put("dismissible", ann.dismissible)
                    put("actionLabel", ann.actionLabel)
                    put("actionDestination", ann.actionDestination)
                    put("minVersionCode", ann.minVersionCode)
                    put("maxVersionCode", ann.maxVersionCode)
                    put("enabled", ann.enabled)
                }
            )
        }
        root.put("announcements", announcementsArr)

        val featuresObj = JSONObject()
        snapshot.features.toSortedMap().forEach { (k, v) ->
            featuresObj.put(k, v)
        }
        root.put("features", featuresObj)

        val compsArr = JSONArray()
        snapshot.ui.components.forEach { comp ->
            compsArr.put(
                JSONObject().apply {
                    put("id", comp.id)
                    put("type", comp.type)
                    put("surface", comp.surface.wireValue)
                    put("visible", comp.visible)
                    put("order", comp.order)
                    put("minVersionCode", comp.minVersionCode)
                    put("maxVersionCode", comp.maxVersionCode)
                    put("railId", comp.railId ?: JSONObject.NULL)
                    put("title", comp.title)
                    put("subtitle", comp.subtitle)
                    put("severity", comp.severity.wireValue)
                    put("dismissible", comp.dismissible)
                    put("maxItems", comp.maxItems)
                    put("autoAdvanceMs", comp.autoAdvanceMs)
                    put("actionLabel", comp.actionLabel)
                    put("actionDestination", comp.actionDestination)
                }
            )
        }
        root.put("ui", JSONObject().apply { put("components", compsArr) })

        return root.toString()
    }

    private fun parseAnnouncement(obj: JSONObject, clientVersionCode: Int): RemoteAnnouncementItem? {
        val id = obj.optString("id", "").trim()
        if (!safeIdRegex.matches(id)) return null
        val enabled = obj.optBoolean("enabled", true)
        if (!enabled) return null
        val minVersion = obj.optInt("minVersionCode", 0).coerceIn(0, 1_000_000)
        val maxVersion = obj.optInt("maxVersionCode", 0).coerceIn(0, 1_000_000)
        if (clientVersionCode > 0) {
            if (minVersion > 0 && clientVersionCode < minVersion) return null
            if (maxVersion > 0 && clientVersionCode > maxVersion) return null
        }

        val title = sanitizePlainText(obj.optString("title", ""), 120)
        val message = sanitizePlainText(obj.optString("message", ""), 500)
        if (title.isBlank() || message.isBlank()) return null

        val rawDest = obj.optString("actionDestination", "").trim()
        val safeDest = if (isAllowedDestination(rawDest)) rawDest else ""

        return RemoteAnnouncementItem(
            id = id,
            title = title,
            message = message,
            severity = RemoteAnnouncementSeverity.fromWire(obj.optString("severity", "info")),
            surface = RemoteUiSurface.fromWire(obj.optString("surface", "inbox")),
            dismissible = obj.optBoolean("dismissible", true),
            actionLabel = if (safeDest.isEmpty()) "" else sanitizePlainText(obj.optString("actionLabel", ""), 40),
            actionDestination = safeDest,
            minVersionCode = minVersion,
            maxVersionCode = maxVersion,
            enabled = true,
        )
    }

    private fun parseUiComponent(obj: JSONObject, clientVersionCode: Int): RemoteUiComponent? {
        val id = obj.optString("id", "").trim()
        val type = obj.optString("type", "").trim()
        if (!safeIdRegex.matches(id)) return null
        if (type !in RemoteCapabilities.SUPPORTED_UI_COMPONENT_TYPES) return null

        val minVersion = obj.optInt("minVersionCode", 0).coerceIn(0, 1_000_000)
        val maxVersion = obj.optInt("maxVersionCode", 0).coerceIn(0, 1_000_000)
        if (clientVersionCode > 0) {
            if (minVersion > 0 && clientVersionCode < minVersion) return null
            if (maxVersion > 0 && clientVersionCode > maxVersion) return null
        }

        val railIdRaw = obj.opt("railId")
            ?.takeIf { it != JSONObject.NULL }
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (type == RemoteCapabilities.TYPE_CONTENT_RAIL) {
            if (railIdRaw == null || railIdRaw !in RemoteCapabilities.SUPPORTED_RAIL_IDS) {
                return null
            }
        }

        val title = sanitizePlainText(obj.optString("title", ""), 120)
        val subtitle = sanitizePlainText(obj.optString("subtitle", ""), 300)
        val rawDest = obj.optString("actionDestination", "").trim()
        val safeDest = if (isAllowedDestination(rawDest)) rawDest else ""

        if (type == RemoteCapabilities.TYPE_ANNOUNCEMENT_BANNER && title.isBlank()) return null
        if (type == RemoteCapabilities.TYPE_FEATURED_CALLOUT_CARD && (title.isBlank() || safeDest.isBlank())) {
            return null
        }

        return RemoteUiComponent(
            id = id,
            type = type,
            surface = RemoteUiSurface.fromWire(obj.optString("surface", "movies")),
            visible = obj.optBoolean("visible", true),
            order = obj.optInt("order", 0).coerceIn(0, 500),
            minVersionCode = minVersion,
            maxVersionCode = maxVersion,
            railId = railIdRaw,
            title = title,
            subtitle = subtitle,
            severity = RemoteAnnouncementSeverity.fromWire(obj.optString("severity", "info")),
            dismissible = obj.optBoolean("dismissible", true),
            maxItems = obj.optInt("maxItems", 12).coerceIn(1, 50),
            autoAdvanceMs = obj.optInt("autoAdvanceMs", 6000).coerceIn(2000, 30000),
            actionLabel = if (safeDest.isEmpty()) "" else sanitizePlainText(obj.optString("actionLabel", ""), 40),
            actionDestination = safeDest,
        )
    }

    private fun sanitizePlainText(raw: String, maxLen: Int): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || unsafeMarkupRegex.containsMatchIn(trimmed)) {
            return ""
        }
        return if (trimmed.length > maxLen) trimmed.substring(0, maxLen) else trimmed
    }
}
