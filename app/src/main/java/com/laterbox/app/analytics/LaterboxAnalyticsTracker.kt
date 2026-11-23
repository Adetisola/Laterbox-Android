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
        "http_error",
        "stream_resolution_failed",
        "download_failed",
        "playback_error",
        "transcript_extraction_failed",
        "ai_request_failed",
        "backup_failed",
        "unexpected_error",
    )

    val ALLOWED_PERFORMANCE_METRICS = setOf(
        "app_startup_ms",
        "media_inspection_ms",
        "stream_resolve_ms",
        "transcript_extract_ms",
        "ai_first_token_ms",
        "config_fetch_ms",
    )

    const val MAX_QUEUE_SIZE = 200
    const val HEARTBEAT_INTERVAL_MS = 120_000L

    fun mapRouteToScreenId(route: String?): String? {
        val clean = route?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            clean == "inbox" -> "inbox"
            clean == "collections" -> "collections"
            clean.startsWith("collection/") -> "collection_detail"
            clean == "movies" || clean == "movies/watchlist" || clean == "movies/catalogue" -> "movies"
            clean.startsWith("movies/detail") -> "movie_detail"
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
        if (metricValueMs != null) put("metricValueMs", metricValueMs)
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

            val errorCategory = obj.optString("errorCategory", "").trim().takeIf { it.isNotEmpty() }
            if (errorCategory != null && errorCategory !in LaterboxAnalyticsSchema.ALLOWED_ERROR_CATEGORIES) return null

            val httpStatus = if (obj.has("httpStatus") && !obj.isNull("httpStatus")) {
                obj.optInt("httpStatus", -1).takeIf { it in 100..599 } ?: return null
            } else null

            val metricName = obj.optString("metricName", "").trim().takeIf { it.isNotEmpty() }
            if (metricName != null && metricName !in LaterboxAnalyticsSchema.ALLOWED_PERFORMANCE_METRICS) return null

            val metricValueMs = if (obj.has("metricValueMs") && !obj.isNull("metricValueMs")) {
                obj.optInt("metricValueMs", -1).takeIf { it in 0..600_000 } ?: return null
            } else null

            if (eventType == "screen_viewed" && screenId == null) return null
            if (eventType == "feature_used" && featureId == null) return null
            if (eventType == "app_error_observed" && errorCategory == null) return null
            if (eventType == "performance_metric" && (metricName == null || metricValueMs == null)) return null

            val versionCode = obj.optInt("versionCode", 0)
            val versionName = obj.optString("versionName", "").trim()
            val timestampMs = obj.optLong("timestampMs", 0L)
            if (versionCode <= 0 || versionName.isEmpty() || timestampMs <= 0L) return null

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
                metricValueMs = metricValueMs,
                timestampMs = timestampMs,
                isOfflineReplay = obj.optBoolean("isOfflineReplay", false),
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
    private var currentSessionId: String = UUID.randomUUID().toString()

    @Volatile
    private var lastTrackedScreenId: String? = null

    private var heartbeatJob: Job? = null

    private val enabled: Boolean
        get() = remoteCollectionEnabled &&
            (remoteCollectionEnabledProvider?.invoke() ?: true) &&
            System.currentTimeMillis() >= serverPausedUntilMs

    private sealed interface DispatchResult {
        data class Accepted(val pausedByServer: Boolean = false) : DispatchResult
        data object DropPermanent : DispatchResult
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

    fun onAppForegrounded() {
        isForegrounded = true
        currentSessionId = UUID.randomUUID().toString()
        lastTrackedScreenId = null
        val sessionIdSnapshot = currentSessionId
        scope.launch {
            if (!enabled) return@launch
            recordAndDispatchUnlocked("app_session_started", sessionId = sessionIdSnapshot)
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
        val cleanCat = errorCategory.trim().lowercase()
        if (cleanCat !in LaterboxAnalyticsSchema.ALLOWED_ERROR_CATEGORIES) return
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
        val cleanMetric = metricName.trim().lowercase()
        if (cleanMetric !in LaterboxAnalyticsSchema.ALLOWED_PERFORMANCE_METRICS) return
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
        )
        if (!flushImmediately) {
            enqueueOffline(record)
            return
        }
        when (sendBatch(listOf(record), markQueuedAsReplay = false)) {
            is DispatchResult.Accepted -> flushOfflineQueue()
            DispatchResult.DropPermanent -> Unit
            DispatchResult.RetryLater -> enqueueOffline(record)
        }
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
                DispatchResult.DropPermanent -> {
                    // Drop malformed/rejected 4xx batch so it never wedges newer analytics behind it
                    val droppedIds = batch.map { it.eventId }.toSet()
                    queueMutex.withLock {
                        val remaining = loadQueueUnlocked().filterNot { it.eventId in droppedIds }
                        saveQueueUnlocked(remaining)
                    }
                }
                DispatchResult.RetryLater -> break
            }
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
                        resp.code == 400 || resp.code == 422 -> DispatchResult.DropPermanent
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
        private const val PREFS_NAME = "laterbox_analytics_prefs"
        private const val KEY_INSTALL_ID = "anonymous_install_id"
        private const val KEY_OFFLINE_QUEUE = "offline_event_queue_v1"
        private const val LEGACY_KEY_USER_OPTED_OUT = "analytics_user_opted_out"
        private const val SERVER_PAUSE_BACKOFF_MS = 5 * 60_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
