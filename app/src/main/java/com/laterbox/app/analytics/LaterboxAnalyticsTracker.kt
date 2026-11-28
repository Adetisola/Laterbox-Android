package com.laterbox.app.analytics

import android.content.Context
import com.laterbox.app.BuildConfig
import com.laterbox.app.ai.AppCapabilityRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object LaterboxAnalyticsSchema {
    val ALLOWED_EVENT_TYPES = setOf(
        "app_session_started",
        "app_session_heartbeat",
        "app_session_ended",
        "screen_viewed",
        "feature_used",
        "application_version_seen",
        "app_error_observed",
        "performance_metric",
    )

    val ALLOWED_SCREEN_IDS = setOf(
        "inbox",
        "collections",
        "movies",
        "downloads",
        "archive",
        "chat",
        "settings",
        "notifications",
        "movie_detail",
        "player",
        "collection_detail",
    )

    val ALLOWED_FEATURE_IDS = setOf(
        "assistant_chat",
        "assistant_action",
        "smart_reminders",
        "youtube_transcripts",
        "media_download",
        "media_share",
        "deep_link_open",
        "pip_playback",
        "mini_player",
    )

    val ALLOWED_ERROR_CATEGORIES = setOf(
        "network_timeout",
        "api_http_error",
        "playback_error",
        "stream_resolution_failed",
        "metadata_fetch_error",
        "download_error",
        "transcript_extraction_failed",
        "ai_request_failed",
        "database_error",
        "backup_failed",
        "unsupported_version_blocked",
        "unexpected_error",
    )

    val ALLOWED_PERFORMANCE_METRICS = setOf(
        "app_startup_ms",
        "config_refresh_ms",
        "catalogue_load_ms",
        "stream_resolve_ms",
        "player_first_frame_ms",
        "media_inspection_ms",
        "transcript_extract_ms",
        "ai_first_token_ms",
    )

    const val MAX_QUEUE_SIZE = 200
    const val HEARTBEAT_INTERVAL_MS = 120_000L

    fun normalizeErrorCategory(raw: String?): String? {
        val clean = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val canonical = when (clean) {
            "http_error" -> "api_http_error"
            "download_failed" -> "download_error"
            else -> clean
        }
        return canonical.takeIf { it in ALLOWED_ERROR_CATEGORIES }
    }

    fun normalizePerformanceMetric(raw: String?): String? {
        val clean = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val canonical = when (clean) {
            "config_fetch_ms" -> "config_refresh_ms"
            else -> clean
        }
        return canonical.takeIf { it in ALLOWED_PERFORMANCE_METRICS }
    }

    fun mapRouteToScreenId(route: String?): String? {
        val clean = route?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            clean == "inbox" -> "inbox"
            clean == "collections" -> "collections"
            clean.startsWith("collection/") -> "collection_detail"
            clean == "movies" ||
                clean == "movies/watchlist" ||
                clean == "movies/catalogue" ||
                clean == "movies/viewing_history" -> "movies"
            clean.startsWith("movies/detail") -> "movie_detail"
            clean == "movies/player" || clean.startsWith("movies/player/") || clean.startsWith("movies/player?") -> "player"
            clean == "downloads" || clean.startsWith("downloads/") -> "downloads"
            clean == "archive" -> "archive"
            clean.startsWith("chat") -> "chat"
            clean.startsWith("settings") -> "settings"
            clean == "notification_history" -> "notifications"
            clean == "player" -> "player"
            else -> null
        }
    }
}

data class AnalyticsEventRecord(
    val eventId: String,
    val eventType: String,
    val installId: String,
    val sessionId: String,
    val versionCode: Int,
    val versionName: String,
    val manifestRevision: String,
    val screenId: String? = null,
    val featureId: String? = null,
    val errorCategory: String? = null,
    val httpStatus: Int? = null,
    val metricName: String? = null,
    val metricValueMs: Int? = null,
    val timestampMs: Long,
    val isOfflineReplay: Boolean = false,
    val coldStart: Boolean? = null,
) {
    fun toJson(overrideOfflineReplay: Boolean = isOfflineReplay): JSONObject = JSONObject().apply {
        put("eventId", eventId)
        put("eventType", eventType)
        put("installId", installId)
        put("sessionId", sessionId)
        put("versionCode", versionCode)
        put("versionName", versionName)
        put("manifestRevision", manifestRevision)
        if (screenId != null) put("screenId", screenId)
        if (featureId != null) put("featureId", featureId)
        if (errorCategory != null) put("errorCategory", errorCategory)
        if (httpStatus != null) put("httpStatus", httpStatus)
        if (metricName != null) put("metricName", metricName)
        if (metricValueMs != null) put("durationMs", metricValueMs)
        if (coldStart != null && eventType == "app_session_started") put("coldStart", coldStart)
        put("timestampMs", timestampMs)
        put("isOfflineReplay", overrideOfflineReplay)
    }

    companion object {
        fun fromJson(obj: JSONObject): AnalyticsEventRecord? {
            val eventId = obj.optString("eventId", "").trim()
            val eventType = obj.optString("eventType", "").trim()
            val installId = obj.optString("installId", "").trim()
            val sessionId = obj.optString("sessionId", "").trim()
            if (eventId.length < 8 || installId.length < 8 || sessionId.length < 8) return null
            if (eventType !in LaterboxAnalyticsSchema.ALLOWED_EVENT_TYPES) return null

            val screenId = obj.optString("screenId", "").trim().takeIf { it.isNotEmpty() }
            if (screenId != null && screenId !in LaterboxAnalyticsSchema.ALLOWED_SCREEN_IDS) return null

            val featureId = obj.optString("featureId", "").trim().takeIf { it.isNotEmpty() }
            if (featureId != null && featureId !in LaterboxAnalyticsSchema.ALLOWED_FEATURE_IDS) return null

            val rawErrorCategory = obj.optString("errorCategory", "").trim().takeIf { it.isNotEmpty() }
            val errorCategory = if (rawErrorCategory != null) {
                LaterboxAnalyticsSchema.normalizeErrorCategory(rawErrorCategory) ?: return null
            } else {
                null
            }

            val httpStatus = if (obj.has("httpStatus") && !obj.isNull("httpStatus")) {
                obj.optInt("httpStatus", -1).takeIf { it in 100..599 } ?: return null
            } else null

            val rawMetricName = obj.optString("metricName", "").trim().takeIf { it.isNotEmpty() }
            val metricName = if (rawMetricName != null) {
                LaterboxAnalyticsSchema.normalizePerformanceMetric(rawMetricName) ?: return null
            } else {
                null
            }

            val durationMs = when {
                obj.has("durationMs") && !obj.isNull("durationMs") ->
                    obj.optInt("durationMs", -1).takeIf { it in 0..600_000 } ?: return null
                obj.has("metricValueMs") && !obj.isNull("metricValueMs") ->
                    obj.optInt("metricValueMs", -1).takeIf { it in 0..600_000 } ?: return null
                else -> null
            }

            if (eventType == "screen_viewed" && screenId == null) return null
            if (eventType == "feature_used" && featureId == null) return null
            if (eventType == "app_error_observed" && errorCategory == null) return null
            if (eventType == "performance_metric" && (metricName == null || durationMs == null)) return null

            val versionCode = obj.optInt("versionCode", 0)
            val versionName = obj.optString("versionName", "").trim()
            val timestampMs = obj.optLong("timestampMs", 0L)
            if (versionCode <= 0 || versionName.isEmpty() || timestampMs <= 0L) return null

            val coldStart = if (eventType == "app_session_started" && obj.has("coldStart") && !obj.isNull("coldStart")) {
                obj.optBoolean("coldStart")
            } else {
                null
            }

            return AnalyticsEventRecord(
                eventId = eventId,
                eventType = eventType,
                installId = installId,
                sessionId = sessionId,
                versionCode = versionCode,
                versionName = versionName,
                manifestRevision = obj.optString("manifestRevision", ""),
                screenId = screenId,
                featureId = featureId,
                errorCategory = errorCategory,
                httpStatus = httpStatus,
                metricName = metricName,
                metricValueMs = durationMs,
                timestampMs = timestampMs,
                isOfflineReplay = obj.optBoolean("isOfflineReplay", false),
                coldStart = coldStart,
            )
        }
    }
}

class LaterboxAnalyticsTracker(
    appContext: Context,
    private val okHttpClient: OkHttpClient,
    private val baseUrlProvider: suspend () -> String,
    private val appCheckTokenProvider: suspend (forceRefresh: Boolean) -> String?,
    private val remoteCollectionEnabledProvider: (() -> Boolean)? = null,
    private val remoteCollectionFlow: StateFlow<Boolean>? = null,
    private val heartbeatIntervalMs: Long = LaterboxAnalyticsSchema.HEARTBEAT_INTERVAL_MS,
    private val authHeaderProvider: (suspend (forceRefresh: Boolean) -> Pair<String, String>?)? = null,
) {
    private suspend fun resolveAuthHeader(forceRefresh: Boolean): Pair<String, String>? {
        if (authHeaderProvider != null) {
            return authHeaderProvider.invoke(forceRefresh)?.takeIf { it.second.isNotBlank() }
        }
        return appCheckTokenProvider(forceRefresh)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            com.laterbox.app.auth.LaterboxBackendAuthProvider.HEADER_APP_CHECK to it
        }
    }
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private val flushMutex = Mutex()
    private val heartbeatLock = Any()

    @Volatile
    private var remoteCollectionEnabled: Boolean =
        remoteCollectionFlow?.value ?: remoteCollectionEnabledProvider?.invoke() ?: true

    @Volatile
    private var serverPausedUntilMs: Long = 0L

    @Volatile
    private var isForegrounded: Boolean = false

    @Volatile
    private var isColdLaunch: Boolean = true

    @Volatile
    private var currentSessionId: String = UUID.randomUUID().toString()

    @Volatile
    private var lastTrackedScreenId: String? = null

    @Volatile
    var lastPermanentDropReason: String? = null
        private set

    private val permanentDroppedCount = java.util.concurrent.atomic.AtomicInteger(0)

    fun getPermanentDropCount(): Int = permanentDroppedCount.get()

    private val _foregroundEpoch = kotlinx.coroutines.flow.MutableStateFlow(0)
    val foregroundEpoch: StateFlow<Int> = _foregroundEpoch

    private var heartbeatJob: Job? = null

    private val enabled: Boolean
        get() = remoteCollectionEnabled &&
            (remoteCollectionEnabledProvider?.invoke() ?: true) &&
            System.currentTimeMillis() >= serverPausedUntilMs

    private sealed interface DispatchResult {
        data class Accepted(val pausedByServer: Boolean = false) : DispatchResult
        data class DropPermanent(val statusCode: Int, val reason: String) : DispatchResult
        data object RetryLater : DispatchResult
    }

    init {
        // Idempotently retire any historical per-device opt-out flag without modifying KEY_INSTALL_ID or offline queue.
        if (prefs.contains(LEGACY_KEY_USER_OPTED_OUT)) {
            prefs.edit().remove(LEGACY_KEY_USER_OPTED_OUT).apply()
        }
        if (remoteCollectionFlow != null) {
            scope.launch {
                remoteCollectionFlow.collect { allowed ->
                    val wasAllowed = remoteCollectionEnabled
                    remoteCollectionEnabled = allowed
                    if (!allowed) {
                        cancelHeartbeatLoop()
                    } else if (!wasAllowed) {
                        serverPausedUntilMs = 0L
                        if (enabled) {
                            if (isForegrounded) {
                                startHeartbeatLoop()
                            }
                            flushOfflineQueue()
                        }
                    }
                }
            }
        }
    }

    private fun getOrCreateInstallId(): String {
        val existing = prefs.getString(KEY_INSTALL_ID, null)?.trim()
        if (!existing.isNullOrBlank() && existing.length >= 8) {
            return existing
        }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, fresh).apply()
        return fresh
    }

    /**
     * Generates a fresh pseudonymous installation identifier so users exercising privacy rights can rotate their ID
     * locally without transmitting any personal information.
     */
    fun resetInstallId(): String {
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, fresh).apply()
        scope.launch { clearQueuedEvents() }
        return fresh
    }

    fun onAppForegrounded(coldStartOverride: Boolean? = null) {
        isForegrounded = true
        val coldStart = coldStartOverride ?: isColdLaunch
        isColdLaunch = false
        currentSessionId = UUID.randomUUID().toString()
        lastTrackedScreenId = null
        _foregroundEpoch.value = _foregroundEpoch.value + 1
        val sessionIdSnapshot = currentSessionId
        scope.launch {
            if (!enabled) return@launch
            recordAndDispatchUnlocked(
                eventType = "app_session_started",
                sessionId = sessionIdSnapshot,
                coldStart = coldStart,
            )
            recordAndDispatchUnlocked("application_version_seen", sessionId = sessionIdSnapshot)
            if (isForegrounded && enabled) {
                startHeartbeatLoop()
            }
        }
    }

    fun onAppBackgrounded() {
        isForegrounded = false
        cancelHeartbeatLoop()
        val sessionIdSnapshot = currentSessionId
        scope.launch {
            if (!enabled) return@launch
            withContext(NonCancellable) {
                val record = createRecord("app_session_ended", sessionId = sessionIdSnapshot)
                // Enqueue first so abrupt process freeze never loses session-ended signal; immediate dispatch drains it cleanly
                enqueueOffline(record)
                flushOfflineQueue()
            }
        }
    }

    fun trackScreenView(screenId: String) {
        val clean = screenId.trim().lowercase()
        if (clean !in LaterboxAnalyticsSchema.ALLOWED_SCREEN_IDS) return
        val sessionIdSnapshot = currentSessionId
        scope.launch {
            if (!enabled) return@launch
            if (lastTrackedScreenId == clean) return@launch
            lastTrackedScreenId = clean
            recordAndDispatchUnlocked("screen_viewed", sessionId = sessionIdSnapshot, screenId = clean)
        }
    }

    fun trackScreenRoute(route: String?) {
        val mapped = LaterboxAnalyticsSchema.mapRouteToScreenId(route) ?: return
        trackScreenView(mapped)
    }

    fun trackFeatureUsed(featureId: String) {
        val clean = featureId.trim().lowercase()
        if (clean !in LaterboxAnalyticsSchema.ALLOWED_FEATURE_IDS) return
        val sessionIdSnapshot = currentSessionId
        scope.launch {
            if (!enabled) return@launch
            recordAndDispatchUnlocked("feature_used", sessionId = sessionIdSnapshot, featureId = clean)
        }
    }

    fun trackErrorObserved(
        errorCategory: String,
        httpStatus: Int? = null,
        screenId: String? = null,
        featureId: String? = null,
    ) {
        val cleanCat = LaterboxAnalyticsSchema.normalizeErrorCategory(errorCategory) ?: return
        val safeHttp = httpStatus?.takeIf { it in 100..599 }
        val safeScreen = screenId?.trim()?.lowercase()?.takeIf { it in LaterboxAnalyticsSchema.ALLOWED_SCREEN_IDS }
        val safeFeature = featureId?.trim()?.lowercase()?.takeIf { it in LaterboxAnalyticsSchema.ALLOWED_FEATURE_IDS }
        val sessionIdSnapshot = currentSessionId
        scope.launch {
            if (!enabled) return@launch
            recordAndDispatchUnlocked(
                eventType = "app_error_observed",
                sessionId = sessionIdSnapshot,
                screenId = safeScreen,
                featureId = safeFeature,
                errorCategory = cleanCat,
                httpStatus = safeHttp,
            )
        }
    }

    fun trackPerformanceMetric(
        metricName: String,
        metricValueMs: Int,
        screenId: String? = null,
        featureId: String? = null,
    ) {
        val cleanMetric = LaterboxAnalyticsSchema.normalizePerformanceMetric(metricName) ?: return
        val boundedMs = metricValueMs.coerceIn(0, 600_000)
        val safeScreen = screenId?.trim()?.lowercase()?.takeIf { it in LaterboxAnalyticsSchema.ALLOWED_SCREEN_IDS }
        val safeFeature = featureId?.trim()?.lowercase()?.takeIf { it in LaterboxAnalyticsSchema.ALLOWED_FEATURE_IDS }
        val sessionIdSnapshot = currentSessionId
        scope.launch {
            if (!enabled) return@launch
            recordAndDispatchUnlocked(
                eventType = "performance_metric",
                sessionId = sessionIdSnapshot,
                screenId = safeScreen,
                featureId = safeFeature,
                metricName = cleanMetric,
                metricValueMs = boundedMs,
            )
        }
    }

    private fun startHeartbeatLoop() {
        synchronized(heartbeatLock) {
            if (heartbeatJob?.isActive == true || !isForegrounded) {
                return
            }
            val intervalMs = heartbeatIntervalMs.coerceAtLeast(10L)
            heartbeatJob = scope.launch {
                while (isActive && isForegrounded) {
                    delay(intervalMs)
                    if (!isForegrounded) break
                    if (enabled) {
                        recordAndDispatchUnlocked("app_session_heartbeat", sessionId = currentSessionId, flushImmediately = true)
                    }
                }
            }
        }
    }

    private fun cancelHeartbeatLoop() {
        synchronized(heartbeatLock) {
            heartbeatJob?.cancel()
            heartbeatJob = null
        }
    }

    private fun createRecord(
        eventType: String,
        sessionId: String = currentSessionId,
        screenId: String? = null,
        featureId: String? = null,
        errorCategory: String? = null,
        httpStatus: Int? = null,
        metricName: String? = null,
        metricValueMs: Int? = null,
        coldStart: Boolean? = null,
    ): AnalyticsEventRecord = AnalyticsEventRecord(
        eventId = UUID.randomUUID().toString(),
        eventType = eventType,
        installId = getOrCreateInstallId(),
        sessionId = sessionId,
        versionCode = BuildConfig.VERSION_CODE,
        versionName = BuildConfig.VERSION_NAME,
        manifestRevision = AppCapabilityRegistry.MANIFEST_REVISION,
        screenId = screenId,
        featureId = featureId,
        errorCategory = errorCategory,
        httpStatus = httpStatus,
        metricName = metricName,
        metricValueMs = metricValueMs,
        timestampMs = System.currentTimeMillis(),
        isOfflineReplay = false,
        coldStart = if (eventType == "app_session_started") coldStart else null,
    )

    private suspend fun recordAndDispatchUnlocked(
        eventType: String,
        sessionId: String = currentSessionId,
        screenId: String? = null,
        featureId: String? = null,
        errorCategory: String? = null,
        httpStatus: Int? = null,
        metricName: String? = null,
        metricValueMs: Int? = null,
        coldStart: Boolean? = null,
        flushImmediately: Boolean = true,
    ) {
        if (!enabled) return
        if (eventType !in LaterboxAnalyticsSchema.ALLOWED_EVENT_TYPES) return
        val record = createRecord(
            eventType = eventType,
            sessionId = sessionId,
            screenId = screenId,
            featureId = featureId,
            errorCategory = errorCategory,
            httpStatus = httpStatus,
            metricName = metricName,
            metricValueMs = metricValueMs,
            coldStart = coldStart,
        )
        if (!flushImmediately) {
            enqueueOffline(record)
            return
        }
        when (val result = sendBatch(listOf(record), markQueuedAsReplay = false)) {
            is DispatchResult.Accepted -> flushOfflineQueue()
            is DispatchResult.DropPermanent -> recordPermanentDrop(listOf(record), result)
            DispatchResult.RetryLater -> enqueueOffline(record)
        }
    }

    private fun recordPermanentDrop(events: List<AnalyticsEventRecord>, result: DispatchResult.DropPermanent) {
        if (events.isEmpty()) return
        permanentDroppedCount.addAndGet(events.size)
        val typesSummary = events.map { it.eventType }.distinct().joinToString(",")
        val diagnostic = "HTTP ${result.statusCode} [$typesSummary]: ${result.reason}"
        lastPermanentDropReason = diagnostic
        runCatching { android.util.Log.w(TAG, "Dropped invalid analytics payload permanently: $diagnostic") }
    }

    private suspend fun enqueueOffline(record: AnalyticsEventRecord) = queueMutex.withLock {
        if (!remoteCollectionEnabled || !(remoteCollectionEnabledProvider?.invoke() ?: true)) return@withLock
        val list = loadQueueUnlocked().toMutableList()
        list.add(record.copy(isOfflineReplay = true))
        while (list.size > LaterboxAnalyticsSchema.MAX_QUEUE_SIZE) {
            list.removeAt(0)
        }
        saveQueueUnlocked(list)
    }

    private suspend fun clearQueuedEvents() = queueMutex.withLock {
        prefs.edit().remove(KEY_OFFLINE_QUEUE).apply()
    }

    private suspend fun flushOfflineQueue() = flushMutex.withLock {
        if (!enabled) return@withLock
        while (enabled) {
            val batch = queueMutex.withLock {
                loadQueueUnlocked().take(25)
            }
            if (batch.isEmpty()) break
            when (val result = sendBatch(batch, markQueuedAsReplay = true)) {
                is DispatchResult.Accepted -> {
                    val sentIds = batch.map { it.eventId }.toSet()
                    queueMutex.withLock {
                        val remaining = loadQueueUnlocked().filterNot { it.eventId in sentIds }
                        saveQueueUnlocked(remaining)
                    }
                    if (result.pausedByServer) {
                        serverPausedUntilMs = System.currentTimeMillis() + SERVER_PAUSE_BACKOFF_MS
                        break
                    }
                }
                is DispatchResult.DropPermanent -> {
                    if (batch.size == 1) {
                        val droppedIds = setOf(batch.first().eventId)
                        queueMutex.withLock {
                            val remaining = loadQueueUnlocked().filterNot { it.eventId in droppedIds }
                            saveQueueUnlocked(remaining)
                        }
                        recordPermanentDrop(batch, result)
                    } else {
                        // Isolate individual malformed event(s) so valid sibling events are still delivered
                        val settledIds = mutableSetOf<String>()
                        val permanentDrops = mutableListOf<Pair<AnalyticsEventRecord, DispatchResult.DropPermanent>>()
                        var hitTransientFailure = false
                        var paused = false
                        for (item in batch) {
                            when (val singleResult = sendBatch(listOf(item), markQueuedAsReplay = true)) {
                                is DispatchResult.Accepted -> {
                                    settledIds += item.eventId
                                    if (singleResult.pausedByServer) {
                                        paused = true
                                        break
                                    }
                                }
                                is DispatchResult.DropPermanent -> {
                                    permanentDrops += item to singleResult
                                    settledIds += item.eventId
                                }
                                DispatchResult.RetryLater -> {
                                    hitTransientFailure = true
                                    break
                                }
                            }
                        }
                        if (settledIds.isNotEmpty()) {
                            queueMutex.withLock {
                                val remaining = loadQueueUnlocked().filterNot { it.eventId in settledIds }
                                saveQueueUnlocked(remaining)
                            }
                        }
                        permanentDrops.forEach { (item, dropResult) ->
                            recordPermanentDrop(listOf(item), dropResult)
                        }
                        if (paused) {
                            serverPausedUntilMs = System.currentTimeMillis() + SERVER_PAUSE_BACKOFF_MS
                            break
                        }
                        if (hitTransientFailure) break
                    }
                }
                DispatchResult.RetryLater -> break
            }
        }
    }

    private fun extractSafeRejectionReason(rawBody: String): String {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return "rejected"
        val parsed = runCatching { JSONObject(trimmed) }.getOrNull() ?: return "schema_error"
        val detail = parsed.opt("detail") ?: return "rejected"
        return when (detail) {
            is String -> detail.take(120)
            is JSONArray -> {
                val first = detail.optJSONObject(0)
                val loc = first?.optJSONArray("loc")?.let { arr ->
                    (0 until arr.length()).joinToString(".") { idx -> arr.optString(idx) }
                }.orEmpty()
                val type = first?.optString("type", "").orEmpty()
                if (loc.isNotBlank() || type.isNotBlank()) {
                    "field=${loc.ifBlank { "unknown" }} type=${type.ifBlank { "invalid" }}".take(120)
                } else {
                    "validation_error"
                }
            }
            else -> "validation_error"
        }
    }

    private fun loadQueueUnlocked(): List<AnalyticsEventRecord> {
        val raw = prefs.getString(KEY_OFFLINE_QUEUE, null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                AnalyticsEventRecord.fromJson(obj)?.let { add(it) }
            }
        }
    }

    private fun saveQueueUnlocked(items: List<AnalyticsEventRecord>) {
        if (items.isEmpty()) {
            prefs.edit().remove(KEY_OFFLINE_QUEUE).apply()
            return
        }
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson(overrideOfflineReplay = true)) }
        prefs.edit().putString(KEY_OFFLINE_QUEUE, arr.toString()).apply()
    }

    private suspend fun sendBatch(
        events: List<AnalyticsEventRecord>,
        markQueuedAsReplay: Boolean,
    ): DispatchResult {
        if (events.isEmpty() || !enabled) return DispatchResult.RetryLater
        val baseUrl = runCatching { baseUrlProvider() }.getOrNull()?.trim()?.trimEnd('/') ?: return DispatchResult.RetryLater
        val parsedBase = baseUrl.toHttpUrlOrNull() ?: return DispatchResult.RetryLater
        val url = parsedBase.newBuilder()
            .addPathSegments("analytics/v1/ingest")
            .build()

        val eventsArray = JSONArray().apply {
            events.forEach { put(it.toJson(overrideOfflineReplay = if (markQueuedAsReplay) true else it.isOfflineReplay)) }
        }
        val payload = JSONObject().put("events", eventsArray).toString()

        var authHeader = runCatching { resolveAuthHeader(false) }.getOrNull()
        for (attempt in 0..1) {
            val requestBuilder = Request.Builder()
                .url(url)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
            if (authHeader != null && authHeader.second.isNotBlank()) {
                requestBuilder.header(authHeader.first, authHeader.second)
            }
            try {
                okHttpClient.newCall(requestBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val raw = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                        val paused = raw.isNotBlank() &&
                            runCatching { JSONObject(raw).optBoolean("paused", false) }.getOrDefault(false)
                        if (paused) {
                            serverPausedUntilMs = System.currentTimeMillis() + SERVER_PAUSE_BACKOFF_MS
                        }
                        return DispatchResult.Accepted(pausedByServer = paused)
                    }
                    val isGuestRestricted403 = resp.code == 403 &&
                        authHeader?.first == com.laterbox.app.auth.LaterboxBackendAuthProvider.HEADER_GUEST_AUTH
                    if ((resp.code == 401 || (resp.code == 403 && !isGuestRestricted403)) && attempt == 0) {
                        val refreshed = runCatching { resolveAuthHeader(true) }.getOrNull()
                        if (refreshed != null && refreshed != authHeader) {
                            authHeader = refreshed
                            return@use
                        }
                    }
                    return when {
                        resp.code == 400 || resp.code == 422 -> {
                            val errBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                            DispatchResult.DropPermanent(resp.code, extractSafeRejectionReason(errBody))
                        }
                        else -> DispatchResult.RetryLater
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                return DispatchResult.RetryLater
            }
        }
        return DispatchResult.RetryLater
    }

    companion object {
        private const val TAG = "LaterboxAnalytics"
        private const val PREFS_NAME = "laterbox_analytics_prefs"
        private const val KEY_INSTALL_ID = "anonymous_install_id"
        private const val KEY_OFFLINE_QUEUE = "offline_event_queue_v1"
        private const val LEGACY_KEY_USER_OPTED_OUT = "analytics_user_opted_out"
        private const val SERVER_PAUSE_BACKOFF_MS = 5 * 60_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
