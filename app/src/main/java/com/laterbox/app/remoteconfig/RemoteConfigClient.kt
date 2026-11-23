package com.laterbox.app.remoteconfig

import com.laterbox.app.ai.RemoteCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed class RemoteConfigFetchOutcome {
    data class Updated(
        val rawJson: String,
        val snapshot: RemoteConfigSnapshot,
        val etag: String?,
    ) : RemoteConfigFetchOutcome()

    data class NotModified(
        val etag: String?,
    ) : RemoteConfigFetchOutcome()

    data class Failed(
        val message: String,
        val statusCode: Int? = null,
        val retryable: Boolean = true,
    ) : RemoteConfigFetchOutcome()
}

class RemoteConfigClient(
    private val okHttpClient: OkHttpClient,
    private val baseUrlProvider: suspend () -> String,
    private val appCheckTokenProvider: suspend (forceRefresh: Boolean) -> String?,
    private val clientVersionCode: Int,
    private val clientVersionName: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val authHeaderProvider: (suspend (forceRefresh: Boolean) -> Pair<String, String>?)? = null,
) {
    private suspend fun resolveAuthHeader(forceRefresh: Boolean): Pair<String, String>? {
        val explicit = authHeaderProvider?.invoke(forceRefresh)
        if (explicit != null && explicit.second.isNotBlank()) {
            return explicit
        }
        if (authHeaderProvider != null) {
            return null
        }
        return appCheckTokenProvider(forceRefresh)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            com.laterbox.app.auth.LaterboxBackendAuthProvider.HEADER_APP_CHECK to it
        }
    }

    suspend fun fetchPublishedConfig(
        cachedEtag: String?,
        forceRefresh: Boolean = false,
    ): RemoteConfigFetchOutcome {
        return try {
            val baseUrl = baseUrlProvider().trim().trimEnd('/')
            val parsedBase = baseUrl.toHttpUrlOrNull()
                ?: return RemoteConfigFetchOutcome.Failed("Invalid backend base URL", retryable = false)

            val urlBuilder = parsedBase.newBuilder()
                .addPathSegments("config/v1/published")
                .addQueryParameter("versionCode", clientVersionCode.toString())
                .addQueryParameter("versionName", clientVersionName)
                .addQueryParameter("capabilities", RemoteCapabilities.declaredCapabilitiesCsv())
            if (forceRefresh) {
                urlBuilder.addQueryParameter("refresh", "true")
            }
            val url = urlBuilder.build()

            val authHeader = resolveAuthHeader(false)
            val firstAttempt = executeRequest(
                url = url,
                authHeader = authHeader,
                cachedEtag = if (forceRefresh) null else cachedEtag,
            )

            // Retry once with force-refreshed credential on 401 (or 403 when not guest-restricted)
            val isGuestRestricted403 = firstAttempt is RemoteConfigFetchOutcome.Failed &&
                firstAttempt.statusCode == 403 &&
                authHeader?.first == com.laterbox.app.auth.LaterboxBackendAuthProvider.HEADER_GUEST_AUTH
            if (firstAttempt is RemoteConfigFetchOutcome.Failed &&
                (firstAttempt.statusCode == 401 || (firstAttempt.statusCode == 403 && !isGuestRestricted403))
            ) {
                val refreshedHeader = resolveAuthHeader(true)
                if (refreshedHeader != null && refreshedHeader != authHeader) {
                    return executeRequest(
                        url = url,
                        authHeader = refreshedHeader,
                        cachedEtag = if (forceRefresh) null else cachedEtag,
                    )
                }
            }

            firstAttempt
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            RemoteConfigFetchOutcome.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private suspend fun executeRequest(
        url: okhttp3.HttpUrl,
        authHeader: Pair<String, String>?,
        cachedEtag: String?,
    ): RemoteConfigFetchOutcome = withContext(ioDispatcher) {
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
        if (authHeader != null && authHeader.second.isNotBlank()) {
            requestBuilder.header(authHeader.first, authHeader.second)
        }
        if (!cachedEtag.isNullOrBlank()) {
            requestBuilder.header("If-None-Match", cachedEtag)
        }

        try {
            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                val responseEtag = response.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                if (response.code == 304) {
                    return@withContext RemoteConfigFetchOutcome.NotModified(responseEtag ?: cachedEtag)
                }
                if (!response.isSuccessful) {
                    return@withContext RemoteConfigFetchOutcome.Failed(
                        message = "HTTP ${response.code}",
                        statusCode = response.code,
                        retryable = response.code >= 500 || response.code == 429,
                    )
                }

                val body = response.body?.string().orEmpty()
                val now = clockMs()
                val baseOrigin = "${url.scheme}://${url.host}" + if (
                    (url.scheme == "https" && url.port != 443) ||
                    (url.scheme == "http" && url.port != 80)
                ) ":${url.port}" else ""
                when (
                    val parsed = RemoteConfigValidator.parseAndValidate(
                        rawJson = body,
                        clientVersionCode = clientVersionCode,
                        source = RemoteConfigSource.NETWORK,
                        etag = responseEtag,
                        fetchedAtEpochMs = now,
                        nowEpochMs = now,
                        enforceExpiry = true,
                        backendBaseUrl = baseOrigin,
                    )
                ) {
                    is RemoteConfigParseResult.Valid -> RemoteConfigFetchOutcome.Updated(
                        rawJson = body,
                        snapshot = parsed.snapshot,
                        etag = responseEtag,
                    )
                    is RemoteConfigParseResult.Rejected -> RemoteConfigFetchOutcome.Failed(
                        message = "Invalid remote configuration payload: ${parsed.reason}",
                        statusCode = response.code,
                        retryable = false,
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ioe: IOException) {
            RemoteConfigFetchOutcome.Failed(ioe.message ?: "Network I/O failure")
        }
    }
}
