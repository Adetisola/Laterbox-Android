package com.laterbox.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GeneratedTitleResult(
    val title: String,
    val source: String
)

/**
 * Client for Laterbox's authenticated FastAPI AI backend `/ai/v1/` endpoints.
 */
class LaterboxBackendAiClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val baseUrlProvider: suspend () -> String,
    private val appCheckTokenProvider: suspend (forceRefresh: Boolean) -> String?
) {

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

        val bodyJson = JSONObject().apply {
            put("firstUserMessage", cleanPrompt.take(2000))
            if (!firstAssistantReply.isNullOrBlank()) {
                put("firstAssistantReply", firstAssistantReply.trim().take(4000))
            }
            if (!currentTitle.isNullOrBlank()) {
                put("currentTitle", currentTitle.trim().take(120))
            }
        }

        val token = runCatching { appCheckTokenProvider(false) }.getOrNull()
        val firstAttempt = executePostJson(url, bodyJson.toString(), token)
        val finalAttempt = if (firstAttempt.statusCode == 401 || firstAttempt.statusCode == 403) {
            val refreshed = runCatching { appCheckTokenProvider(true) }.getOrNull()
            if (!refreshed.isNullOrBlank() && refreshed != token) {
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

    suspend fun checkCapabilityManifestSync(): Boolean? = withContext(Dispatchers.IO) {
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        val parsedBase = baseUrl.toHttpUrlOrNull() ?: return@withContext null
        val url = parsedBase.newBuilder()
            .addPathSegments("ai/v1/capabilities/manifest")
            .addQueryParameter("clientHash", AppCapabilityRegistry.MANIFEST_HASH)
            .build()

        val token = runCatching { appCheckTokenProvider(false) }.getOrNull()
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
        if (!token.isNullOrBlank()) {
            requestBuilder.header("X-Firebase-AppCheck", token)
        }
        try {
            okHttpClient.newCall(requestBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val text = resp.body?.string().orEmpty()
                val obj = JSONObject(text)
                if (obj.has("clientInSync") && !obj.isNull("clientInSync")) {
                    obj.getBoolean("clientInSync")
                } else {
                    null
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
    }

    private data class PostJsonOutcome(
        val statusCode: Int,
        val json: JSONObject?
    )

    private suspend fun executePostJson(
        url: okhttp3.HttpUrl,
        payload: String,
        appCheckToken: String?
    ): PostJsonOutcome = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
        if (!appCheckToken.isNullOrBlank()) {
            requestBuilder.header("X-Firebase-AppCheck", appCheckToken)
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
