package com.laterbox.app.analytics

import android.content.Context
import com.laterbox.app.BuildConfig
import com.laterbox.app.ai.AppCapabilityRegistry
import com.laterbox.app.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        "application_version_seen"
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
        "collection_detail"
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
        "mini_player"
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
    val timestampMs: Long,
    val isOfflineReplay: Boolean = false
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
                timestampMs = timestampMs,
                isOfflineReplay = obj.optBoolean("isOfflineReplay", false)
            )
        }
    }
}

class LaterboxAnalyticsTracker(
    appContext: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val okHttpClient: OkHttpClient,
    private val baseUrlProvider: suspend () -> String,
    private val appCheckTokenProvider: suspend (forceRefresh: Boolean) -> String?
) {
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()

    @Volatile
    private var enabled = true

    @Volatile
    private var currentSessionId: String = UUID.randomUUID().toString()

    @Volatile
    private var lastTrackedScreenId: String? = null

    private var heartbeatJob: Job? = null

    init {
        scope.launch {
            userPreferencesRepository.userPreferencesFlow.collect { userPrefs ->
                val wasEnabled = enabled
                enabled = userPrefs.analyticsEnabled
                if (!enabled && wasEnabled) {
                    heartbeatJob?.cancel()
                    heartbeatJob = null
                    clearQueuedEvents()
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

    fun onAppForegrounded() {
        if (!enabled) return
        currentSessionId = UUID.randomUUID().toString()
        lastTrackedScreenId = null
        recordAndDispatch("app_session_started")
        recordAndDispatch("application_version_seen")
        startHeartbeatLoop()
    }

    fun onAppBackgrounded() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        if (!enabled) return
        recordAndDispatch("app_session_ended", flushImmediately = true)
    }

    fun trackScreenView(screenId: String) {
        if (!enabled) return
        val clean = screenId.trim().lowercase()
        if (clean !in LaterboxAnalyticsSchema.ALLOWED_SCREEN_IDS) return
        if (lastTrackedScreenId == clean) return
        lastTrackedScreenId = clean
        recordAndDispatch("screen_viewed", screenId = clean)
    }

    fun trackScreenRoute(route: String?) {
        val mapped = LaterboxAnalyticsSchema.mapRouteToScreenId(route) ?: return
        trackScreenView(mapped)
    }

    fun trackFeatureUsed(featureId: String) {
        if (!enabled) return
        val clean = featureId.trim().lowercase()
        if (clean !in LaterboxAnalyticsSchema.ALLOWED_FEATURE_IDS) return
        recordAndDispatch("feature_used", featureId = clean)
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && enabled) {
                delay(LaterboxAnalyticsSchema.HEARTBEAT_INTERVAL_MS)
                if (!enabled) break
                recordAndDispatch("app_session_heartbeat", flushImmediately = true)
            }
        }
    }

    private fun recordAndDispatch(
        eventType: String,
        screenId: String? = null,
        featureId: String? = null,
        flushImmediately: Boolean = true
    ) {
        if (eventType !in LaterboxAnalyticsSchema.ALLOWED_EVENT_TYPES) return
        val record = AnalyticsEventRecord(
            eventId = UUID.randomUUID().toString(),
            eventType = eventType,
            installId = getOrCreateInstallId(),
            sessionId = currentSessionId,
            versionCode = BuildConfig.VERSION_CODE,
            versionName = BuildConfig.VERSION_NAME,
            manifestRevision = AppCapabilityRegistry.MANIFEST_REVISION,
            screenId = screenId,
            featureId = featureId,
            timestampMs = System.currentTimeMillis(),
            isOfflineReplay = false
        )
        scope.launch {
            if (!enabled) return@launch
            val sent = if (flushImmediately) sendBatch(listOf(record), markQueuedAsReplay = false) else false
            if (!sent) {
                enqueueOffline(record)
            } else {
                flushOfflineQueue()
            }
        }
    }

    private suspend fun enqueueOffline(record: AnalyticsEventRecord) = queueMutex.withLock {
        if (!enabled) return@withLock
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

    private suspend fun flushOfflineQueue() {
        val batch = queueMutex.withLock {
            loadQueueUnlocked().take(25)
        }
        if (batch.isEmpty()) return
        val sent = sendBatch(batch, markQueuedAsReplay = true)
        if (sent) {
            val sentIds = batch.map { it.eventId }.toSet()
            queueMutex.withLock {
                val remaining = loadQueueUnlocked().filterNot { it.eventId in sentIds }
                saveQueueUnlocked(remaining)
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
        markQueuedAsReplay: Boolean
    ): Boolean {
        if (events.isEmpty() || !enabled) return false
        val baseUrl = runCatching { baseUrlProvider() }.getOrNull()?.trim()?.trimEnd('/') ?: return false
        val parsedBase = baseUrl.toHttpUrlOrNull() ?: return false
        val url = parsedBase.newBuilder()
            .addPathSegments("analytics/v1/ingest")
            .build()

        val eventsArray = JSONArray().apply {
            events.forEach { put(it.toJson(overrideOfflineReplay = if (markQueuedAsReplay) true else it.isOfflineReplay)) }
        }
        val payload = JSONObject().put("events", eventsArray).toString()

        val token = runCatching { appCheckTokenProvider(false) }.getOrNull()
        val requestBuilder = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
        if (!token.isNullOrBlank()) {
            requestBuilder.header("X-Firebase-AppCheck", token)
        }
        return try {
            okHttpClient.newCall(requestBuilder.build()).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private const val PREFS_NAME = "laterbox_analytics_prefs"
        private const val KEY_INSTALL_ID = "anonymous_install_id"
        private const val KEY_OFFLINE_QUEUE = "offline_event_queue_v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
