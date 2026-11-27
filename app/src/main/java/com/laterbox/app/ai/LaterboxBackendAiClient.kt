package com.laterbox.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class GeneratedTitleResult(
    val title: String,
    val source: String
)

data class AiGroundingSource(
    val title: String,
    val url: String
)

data class ManifestSyncResult(
    val manifestVersion: Int,
    val manifestRevision: String,
    val manifestHash: String,
    val clientInSync: Boolean,
    val serverCapabilitiesCount: Int
)

data class ActionResultAck(
    val accepted: Boolean,
    val status: String,
    val followUpMessage: String? = null
)

data class TtsAudioPayload(
    val audioBytes: ByteArray,
    val mimeType: String,
    val durationEstimateMs: Int
)

data class RecentActionReceipt(
    val actionId: String? = null,
    val actionType: String,
    val category: String,
    val status: String,
    val detail: String = ""
)

sealed interface BackendAiStreamEvent {
    data class Started(
        val manifestRevision: String,
        val manifestSynced: Boolean
    ) : BackendAiStreamEvent

    data class TextDelta(val chunk: String) : BackendAiStreamEvent

    data class ActionProposed(
        val action: ChatAction,
        val category: String,
        val requiresApproval: Boolean
    ) : BackendAiStreamEvent

    data class ApprovalRequired(
        val action: ChatAction,
        val category: String
    ) : BackendAiStreamEvent

    data class Completed(
        val fullText: String,
        val displayText: String,
        val actions: List<ChatAction>,
        val webGrounded: Boolean,
        val groundingSources: List<AiGroundingSource>
    ) : BackendAiStreamEvent

    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean
    ) : BackendAiStreamEvent
}

/**
 * Client for Laterbox's authenticated FastAPI AI backend `/ai/v1/` endpoints.
 */
class LaterboxBackendAiClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val baseUrlProvider: suspend () -> String,
    private val appCheckTokenProvider: suspend (forceRefresh: Boolean) -> String?,
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

    fun streamChat(
        conversationId: String,
        turns: List<GeminiTurn>,
        capabilitySnapshot: CapabilitySnapshot,
        runtimeContextBlock: String,
        actionSpecBlock: String = LaterboxKnowledgeBase.ALLOWED_ACTION_SPEC,
        grantedCategories: Set<String> = emptySet(),
        enableWebSearch: Boolean = true,
        recentActionResults: List<RecentActionReceipt> = emptyList()
    ): Flow<BackendAiStreamEvent> = flow {
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        val parsedBase = baseUrl.toHttpUrlOrNull()
        if (parsedBase == null) {
            emit(
                BackendAiStreamEvent.Error(
                    code = "INVALID_BACKEND_URL",
                    message = "Backend server address isn't valid.",
                    retryable = false
                )
            )
            return@flow
        }

        val url = parsedBase.newBuilder()
            .addPathSegments("ai/v1/chat/stream")
            .build()

        val messagesArray = JSONArray().apply {
            for (t in turns) {
                put(
                    JSONObject().apply {
                        put("role", t.role.apiValue)
                        put("text", t.text)
                    }
                )
            }
        }

        val receiptsArray = JSONArray().apply {
            for (r in recentActionResults.takeLast(10)) {
                put(
                    JSONObject().apply {
                        if (!r.actionId.isNullOrBlank()) put("actionId", r.actionId)
                        put("actionType", r.actionType)
                        put("category", r.category)
                        put("status", r.status)
                        put("detail", r.detail.take(240))
                    }
                )
            }
        }

        val payload = JSONObject().apply {
            put("conversationId", conversationId)
            put("messages", messagesArray)
            put("runtimeSnapshot", AppCapabilityRegistry.toRuntimeSnapshotJson(capabilitySnapshot))
            put("runtimeContextBlock", runtimeContextBlock.take(32000))
            put("actionSpecBlock", actionSpecBlock.take(8000))
            put("grantedCategories", JSONArray(grantedCategories.toList()))
            put("enableWebSearch", enableWebSearch)
            if (receiptsArray.length() > 0) {
                put("recentActionResults", receiptsArray)
            }
        }.toString()

        val activeCall = AtomicReference<Call?>(null)
        val watcherJob = kotlinx.coroutines.Job(currentCoroutineContext()[Job])
        val cancelWatcher = kotlinx.coroutines.CoroutineScope(
            watcherJob + Dispatchers.Unconfined
        ).launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                activeCall.get()?.cancel()
            }
        }

        try {
            var authHeader = runCatching { resolveAuthHeader(false) }.getOrNull()
            var response = executeStreamCall(url, payload, authHeader, activeCall)
            val isGuestRestricted403 = response != null &&
                response.code == 403 &&
                authHeader?.first == com.laterbox.app.auth.LaterboxBackendAuthProvider.HEADER_GUEST_AUTH
            if (response != null && (response.code == 401 || (response.code == 403 && !isGuestRestricted403))) {
                response.close()
                val refreshed = runCatching { resolveAuthHeader(true) }.getOrNull()
                if (refreshed != null && refreshed != authHeader) {
                    authHeader = refreshed
                    response = executeStreamCall(url, payload, authHeader, activeCall)
                }
            }

            currentCoroutineContext().ensureActive()

            if (response == null) {
                emit(
                    BackendAiStreamEvent.Error(
                        code = "NETWORK_UNREACHABLE",
                        message = "Unable to connect right now. Check your internet connection and try again.",
                        retryable = true
                    )
                )
                return@flow
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    val msg = when {
                        resp.code == 403 && authHeader?.first == com.laterbox.app.auth.LaterboxBackendAuthProvider.HEADER_GUEST_AUTH ->
                            "Laterbox Assistant couldn't verify this session right now. Please try again shortly."
                        resp.code == 401 || resp.code == 403 ->
                            "App verification couldn't be completed for this request. Please try again."
                        resp.code == 426 ->
                            "This version of Laterbox is no longer supported. Please update the app to continue using Assistant."
                        else ->
                            "Laterbox Assistant is temporarily unavailable (${resp.code})."
                    }
                    emit(
                        BackendAiStreamEvent.Error(
                            code = if (resp.code == 426) "UNSUPPORTED_APP_VERSION" else "HTTP_${resp.code}",
                            message = msg,
                            retryable = resp.code >= 500 || resp.code == 429
                        )
                    )
                    return@flow
                }

                val body = resp.body
                if (body == null) {
                    emit(
                        BackendAiStreamEvent.Error(
                            code = "EMPTY_BODY",
                            message = "Empty response from Laterbox Assistant.",
                            retryable = true
                        )
                    )
                    return@flow
                }

                BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                    var currentEvent: String? = null
                    val dataBuffer = StringBuilder()

                    fun dispatchBufferedEvent(): BackendAiStreamEvent? {
                        val ev = currentEvent?.trim()
                        val dataStr = dataBuffer.toString().trim()
                        currentEvent = null
                        dataBuffer.setLength(0)
                        if (ev.isNullOrEmpty() || dataStr.isEmpty()) return null
                        val obj = runCatching { JSONObject(dataStr) }.getOrNull() ?: return null
                        return when (ev) {
                            "response_start" -> BackendAiStreamEvent.Started(
                                manifestRevision = obj.optString("manifestRevision", ""),
                                manifestSynced = obj.optBoolean("manifestSynced", true)
                            )
                            "text_delta" -> {
                                val chunk = obj.optString("chunk", "")
                                if (chunk.isNotEmpty()) BackendAiStreamEvent.TextDelta(chunk) else null
                            }
                            "action_proposed" -> {
                                val actionObj = obj.optJSONObject("action") ?: return null
                                val parsedAction = AssistantActionHandler.fromJsonList("[$actionObj]").firstOrNull()
                                    ?: return null
                                BackendAiStreamEvent.ActionProposed(
                                    action = parsedAction,
                                    category = obj.optString("category", ""),
                                    requiresApproval = obj.optBoolean("requiresApproval", true)
                                )
                            }
                            "approval_required" -> {
                                val actionObj = obj.optJSONObject("action") ?: return null
                                val parsedAction = AssistantActionHandler.fromJsonList("[$actionObj]").firstOrNull()
                                    ?: return null
                                BackendAiStreamEvent.ApprovalRequired(
                                    action = parsedAction,
                                    category = obj.optString("category", "")
                                )
                            }
                            "response_complete" -> {
                                val fullText = obj.optString("fullText", "")
                                val displayText = obj.optString("displayText", fullText)
                                val actionsArr = obj.optJSONArray("actions")
                                val actions = if (actionsArr != null) {
                                    AssistantActionHandler.fromJsonList(actionsArr.toString())
                                } else emptyList()
                                val webGrounded = obj.optBoolean("webGrounded", false)
                                val sourcesArr = obj.optJSONArray("groundingSources")
                                val sources = buildList {
                                    if (sourcesArr != null) {
                                        for (i in 0 until sourcesArr.length()) {
                                            val sObj = sourcesArr.optJSONObject(i) ?: continue
                                            val sUrl = sObj.optString("url", "").trim()
                                            if (sUrl.startsWith("https://")) {
                                                add(AiGroundingSource(sObj.optString("title", sUrl), sUrl))
                                            }
                                        }
                                    }
                                }
                                BackendAiStreamEvent.Completed(
                                    fullText = fullText,
                                    displayText = displayText,
                                    actions = actions,
                                    webGrounded = webGrounded,
                                    groundingSources = sources
                                )
                            }
                            "response_error" -> BackendAiStreamEvent.Error(
                                code = obj.optString("code", "ERROR"),
                                message = obj.optString("message", "Unable to answer right now."),
                                retryable = obj.optBoolean("retryable", true)
                            )
                            else -> null
                        }
                    }

                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = try {
                            reader.readLine()
                        } catch (t: Throwable) {
                            currentCoroutineContext().ensureActive()
                            if (activeCall.get()?.isCanceled() == true) {
                                throw CancellationException("Stream cancelled", t)
                            }
                            throw t
                        } ?: break
                        when {
                            line.isEmpty() -> {
                                dispatchBufferedEvent()?.let { emit(it) }
                            }
                            line.startsWith("event:") -> {
                                currentEvent = line.substring(6).trim()
                            }
                            line.startsWith("data:") -> {
                                if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                                dataBuffer.append(line.substring(5).trim())
                            }
                        }
                    }
                    if (currentEvent != null && dataBuffer.isNotEmpty()) {
                        dispatchBufferedEvent()?.let { emit(it) }
                    }
                }
            }
        } finally {
            cancelWatcher.cancel()
            watcherJob.cancel()
            activeCall.set(null)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun executeStreamCall(
        url: okhttp3.HttpUrl,
        payload: String,
        authHeader: Pair<String, String>?,
        activeCall: AtomicReference<Call?>
    ): okhttp3.Response? {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
        if (authHeader != null && authHeader.second.isNotBlank()) {
            requestBuilder.header(authHeader.first, authHeader.second)
        }
        val call = okHttpClient.newCall(requestBuilder.build())
        activeCall.set(call)
        return try {
            currentCoroutineContext().ensureActive()
            call.execute()
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            if (call.isCanceled()) {
                throw CancellationException("Stream call cancelled", t)
            }
            null
        }
    }

    suspend fun reportActionResult(
        conversationId: String,
        actionType: String,
        category: String,
        status: String,
        detail: String = "",
        actionId: String? = null
    ): ActionResultAck? {
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        val parsedBase = baseUrl.toHttpUrlOrNull() ?: return null
        val url = parsedBase.newBuilder()
            .addPathSegments("ai/v1/chat/action-result")
            .build()
        val payload = JSONObject().apply {
            put("conversationId", conversationId)
            if (!actionId.isNullOrBlank()) put("actionId", actionId)
            put("actionType", actionType)
            put("category", category)
            put("status", status)
            put("detail", detail.take(500))
        }.toString()
        val authHeader = runCatching { resolveAuthHeader(false) }.getOrNull()
        if (isGuestAuth(authHeader)) {
            return ActionResultAck(
                accepted = true,
                status = status,
                followUpMessage = null
            )
        }
        val firstAttempt = executePostJson(url, payload, authHeader)
        val isGuestRestricted403 = firstAttempt.statusCode == 403 && isGuestAuth(authHeader)
        val finalAttempt = if (firstAttempt.statusCode == 401 || (firstAttempt.statusCode == 403 && !isGuestRestricted403)) {
            val refreshed = runCatching { resolveAuthHeader(true) }.getOrNull()
            if (refreshed != null && refreshed != authHeader) {
                executePostJson(url, payload, refreshed)
            } else {
                firstAttempt
            }
        } else {
            firstAttempt
        }
        val obj = finalAttempt.json ?: return null
        return ActionResultAck(
            accepted = obj.optBoolean("accepted", true),
            status = obj.optString("status", status),
            followUpMessage = obj.optString("followUpMessage", "").trim().takeIf { it.isNotEmpty() }
        )
    }

    suspend fun generateConversationTitle(
        firstUserMessage: String,
        firstAssistantReply: String? = null,
        currentTitle: String? = null
    ): GeneratedTitleResult? {
        val cleanPrompt = firstUserMessage.trim()
        if (cleanPrompt.isEmpty()) return null
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        val parsedBase = baseUrl.toHttpUrlOrNull() ?: return null
        val url = parsedBase.newBuilder()
            .addPathSegments("ai/v1/titles/generate")
            .build()

        val authHeader = runCatching { resolveAuthHeader(false) }.getOrNull()
        if (isGuestAuth(authHeader)) {
            return null
        }

        val bodyJson = JSONObject().apply {
            put("firstUserMessage", cleanPrompt.take(2000))
            if (!firstAssistantReply.isNullOrBlank()) {
                put("firstAssistantReply", firstAssistantReply.trim().take(4000))
            }
            if (!currentTitle.isNullOrBlank()) {
                put("currentTitle", currentTitle.trim().take(120))
            }
        }

        val firstAttempt = executePostJson(url, bodyJson.toString(), authHeader)
        val isGuestRestricted403 = firstAttempt.statusCode == 403 && isGuestAuth(authHeader)
        val finalAttempt = if (firstAttempt.statusCode == 401 || (firstAttempt.statusCode == 403 && !isGuestRestricted403)) {
            val refreshed = runCatching { resolveAuthHeader(true) }.getOrNull()
            if (refreshed != null && refreshed != authHeader) {
                executePostJson(url, bodyJson.toString(), refreshed)
            } else {
                firstAttempt
            }
        } else {
            firstAttempt
        }

        val responseJson = finalAttempt.json ?: return null
        val generated = responseJson.optString("title", "").trim()
        if (generated.isEmpty() || generated.equals("New conversation", ignoreCase = true)) {
            return null
        }
        return GeneratedTitleResult(
            title = generated,
            source = responseJson.optString("source", "fallback")
        )
    }

    suspend fun fetchCapabilityManifest(): ManifestSyncResult? = withContext(Dispatchers.IO) {
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        val parsedBase = baseUrl.toHttpUrlOrNull() ?: return@withContext null
        val url = parsedBase.newBuilder()
            .addPathSegments("ai/v1/capabilities/manifest")
            .addQueryParameter("clientHash", AppCapabilityRegistry.MANIFEST_HASH)
            .build()

        val authHeader = runCatching { resolveAuthHeader(false) }.getOrNull()
        val firstAttempt = executeGetJson(url, authHeader)
        val isGuestRestricted403 = firstAttempt.statusCode == 403 &&
            authHeader?.first == com.laterbox.app.auth.LaterboxBackendAuthProvider.HEADER_GUEST_AUTH
        val finalAttempt = if (firstAttempt.statusCode == 401 || (firstAttempt.statusCode == 403 && !isGuestRestricted403)) {
            val refreshed = runCatching { resolveAuthHeader(true) }.getOrNull()
            if (refreshed != null && refreshed != authHeader) {
                executeGetJson(url, refreshed)
            } else {
                firstAttempt
            }
        } else {
            firstAttempt
        }

        val obj = finalAttempt.json ?: return@withContext null
        val serverHash = obj.optString("manifestHash", "").trim()
        val itemsCount = obj.optJSONArray("items")?.length() ?: 0
        val inSync = if (obj.has("clientInSync") && !obj.isNull("clientInSync")) {
            obj.getBoolean("clientInSync")
        } else {
            serverHash.isNotEmpty() && serverHash == AppCapabilityRegistry.MANIFEST_HASH
        }
        ManifestSyncResult(
            manifestVersion = obj.optInt("manifestVersion", 0),
            manifestRevision = obj.optString("manifestRevision", ""),
            manifestHash = serverHash,
            clientInSync = inSync,
            serverCapabilitiesCount = itemsCount
        )
    }

    suspend fun checkCapabilityManifestSync(): Boolean? =
        fetchCapabilityManifest()?.clientInSync

    suspend fun fetchTtsAudio(
        text: String,
        voice: String = "Aoede",
        speed: Double = 1.0
    ): Result<TtsAudioPayload> {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return Result.failure(IllegalArgumentException("tts_empty_text"))
        }
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        val parsedBase = baseUrl.toHttpUrlOrNull()
            ?: return Result.failure(IllegalStateException("tts_invalid_base_url"))
        val url = parsedBase.newBuilder()
            .addPathSegments("api/assistant/tts")
            .build()

        val bodyJson = JSONObject().apply {
            put("text", cleanText.take(4000))
            put("voice", voice.trim().ifBlank { "Aoede" })
            put("speed", speed.coerceIn(0.5, 2.0))
        }

        val authHeader = runCatching { resolveAuthHeader(false) }.getOrNull()
        val firstAttempt = executePostJson(url, bodyJson.toString(), authHeader)
        val isGuestRestricted403 = firstAttempt.statusCode == 403 &&
            authHeader?.first == com.laterbox.app.auth.LaterboxBackendAuthProvider.HEADER_GUEST_AUTH
        // Only refresh auth token on 401/403 token expiry; never retry on 503/5xx/network failure.
        val finalAttempt = if (firstAttempt.statusCode == 401 || (firstAttempt.statusCode == 403 && !isGuestRestricted403)) {
            val refreshed = runCatching { resolveAuthHeader(true) }.getOrNull()
            if (refreshed != null && refreshed != authHeader) {
                executePostJson(url, bodyJson.toString(), refreshed)
            } else {
                firstAttempt
            }
        } else {
            firstAttempt
        }

        val responseJson = finalAttempt.json
            ?: return Result.failure(IllegalStateException("tts_unavailable"))
        val b64Audio = responseJson.optString("audio_base64", "").trim()
        if (b64Audio.isEmpty()) {
            return Result.failure(IllegalStateException("tts_unavailable"))
        }
        val bytes = runCatching {
            android.util.Base64.decode(b64Audio, android.util.Base64.DEFAULT)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return Result.failure(IllegalStateException("tts_unavailable"))
        val mimeType = responseJson.optString("mime_type", "audio/wav").trim().ifBlank { "audio/wav" }
        val durationMs = responseJson.optInt("duration_estimate_ms", 0)
        return Result.success(
            TtsAudioPayload(
                audioBytes = bytes,
                mimeType = mimeType,
                durationEstimateMs = durationMs
            )
        )
    }

    private fun isGuestAuth(authHeader: Pair<String, String>?): Boolean =
        authHeader?.first == com.laterbox.app.auth.LaterboxBackendAuthProvider.HEADER_GUEST_AUTH

    private data class PostJsonOutcome(
        val statusCode: Int,
        val json: JSONObject?
    )

    private suspend fun executeGetJson(
        url: okhttp3.HttpUrl,
        authHeader: Pair<String, String>?
    ): PostJsonOutcome = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
        if (authHeader != null && authHeader.second.isNotBlank()) {
            requestBuilder.header(authHeader.first, authHeader.second)
        }
        try {
            okHttpClient.newCall(requestBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val parsed = if (resp.isSuccessful && raw.isNotBlank()) {
                    runCatching { JSONObject(raw) }.getOrNull()
                } else null
                PostJsonOutcome(resp.code, parsed)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            PostJsonOutcome(0, null)
        }
    }

    private suspend fun executePostJson(
        url: okhttp3.HttpUrl,
        payload: String,
        authHeader: Pair<String, String>?
    ): PostJsonOutcome = withContext(Dispatchers.IO) {
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
                val raw = resp.body?.string().orEmpty()
                val parsed = if (resp.isSuccessful && raw.isNotBlank()) {
                    runCatching { JSONObject(raw) }.getOrNull()
                } else null
                PostJsonOutcome(resp.code, parsed)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            PostJsonOutcome(0, null)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
