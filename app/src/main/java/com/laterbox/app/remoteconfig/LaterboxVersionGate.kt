package com.laterbox.app.remoteconfig

import com.laterbox.app.ui.util.BrowserLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject

data class VersionEnforcementBlockState(
    val minimumSupportedVersionCode: Int,
    val minimumSupportedTag: String = "",
    val minimumSupportedVersion: String = "",
    val recommendedVersionCode: Int = 0,
    val recommendedTag: String = "",
    val latestStableTag: String = "",
    val latestStableVersion: String = "",
    val updateUrl: String = DEFAULT_UPDATE_URL,
    val message: String = "",
    val triggeredByServer426: Boolean = false,
    val detectedAtEpochMs: Long = 0L,
) {
    companion object {
        const val DEFAULT_UPDATE_URL = "https://laterbox-website.vercel.app"
    }
}

/**
 * Coordinates reactive backend HTTP 426 Upgrade Required responses with proactive Remote Config version policies
 * so obsolete builds are blocked cleanly without stacking dialogs or blocking offline local access unnecessarily.
 */
class LaterboxVersionGate(
    private val currentVersionCode: Int,
    private val onForceRefreshRequested: (() -> Unit)? = null,
    private val onBlocked426: (() -> Unit)? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _serverBlockState = MutableStateFlow<VersionEnforcementBlockState?>(null)
    val serverBlockState: StateFlow<VersionEnforcementBlockState?> = _serverBlockState.asStateFlow()

    fun onHttp426(rawBody: String?, requestPath: String = "") {
        val parsed = parse426Payload(rawBody, clockMs()) ?: return
        if (currentVersionCode >= parsed.minimumSupportedVersionCode) {
            return
        }
        val wasBlocked = _serverBlockState.value != null
        _serverBlockState.value = parsed
        if (!wasBlocked) {
            onBlocked426?.invoke()
        }
        // Refresh remote config asynchronously so UI & features stay synchronized with published policy
        onForceRefreshRequested?.invoke()
    }

    fun onSupportedBackendResponse(requestPath: String) {
        // Intentionally do not clear an active server block from arbitrary 2xx endpoints;
        // clearing happens strictly via authoritative NETWORK published config confirmation.
        val _ignored = requestPath
    }

    fun syncWithPublishedConfig(snapshot: RemoteConfigSnapshot) {
        if (snapshot.source != RemoteConfigSource.NETWORK) return
        val policy = snapshot.versionPolicy
        if (!policy.isEnforceModeActive ||
            policy.minimumSupportedVersionCode <= 0 ||
            currentVersionCode >= policy.minimumSupportedVersionCode
        ) {
            _serverBlockState.value = null
        }
    }

    fun clearForTesting() {
        _serverBlockState.value = null
    }

    companion object {
        private val ALLOWLIST_PREFIXES = listOf(
            "/health",
            "/ai/v1/health",
            "/config/v1/",
            "/updates/",
            "/analytics/v1/",
            "/share/v1/",
            "/ai/v1/capabilities/manifest",
        )

        fun isPolicyEnforcedPath(rawPath: String): Boolean {
            val clean = rawPath.trim()
            if (clean.isEmpty()) return false
            return ALLOWLIST_PREFIXES.none { prefix ->
                if (prefix.endsWith("/")) clean.startsWith(prefix) else clean == prefix || clean.startsWith("$prefix/")
            }
        }

        fun resolveEffectiveBlock(
            versionPolicy: RemoteVersionPolicy,
            currentVersionCode: Int,
            serverBlock: VersionEnforcementBlockState?,
        ): VersionEnforcementBlockState? {
            if (RemoteConfigLayoutHelper.shouldShowBlockingUpdateDialog(versionPolicy, currentVersionCode)) {
                val targetTag = serverBlock?.latestStableTag?.takeIf { it.isNotBlank() }
                    ?: versionPolicy.recommendedTag
                val targetUrl = serverBlock?.updateUrl?.takeIf { BrowserLauncher.isSafeHttpUrl(it) }
                    ?: VersionEnforcementBlockState.DEFAULT_UPDATE_URL
                return VersionEnforcementBlockState(
                    minimumSupportedVersionCode = versionPolicy.minimumSupportedVersionCode,
                    minimumSupportedTag = versionPolicy.minimumSupportedTag,
                    minimumSupportedVersion = serverBlock?.minimumSupportedVersion.orEmpty(),
                    recommendedVersionCode = versionPolicy.recommendedVersionCode,
                    recommendedTag = versionPolicy.recommendedTag,
                    latestStableTag = targetTag,
                    latestStableVersion = serverBlock?.latestStableVersion.orEmpty(),
                    updateUrl = targetUrl,
                    message = versionPolicy.updateMessage.ifBlank { serverBlock?.message.orEmpty() },
                    triggeredByServer426 = serverBlock != null,
                    detectedAtEpochMs = serverBlock?.detectedAtEpochMs ?: 0L,
                )
            }
            if (serverBlock != null &&
                serverBlock.triggeredByServer426 &&
                serverBlock.minimumSupportedVersionCode > 0 &&
                currentVersionCode < serverBlock.minimumSupportedVersionCode
            ) {
                return serverBlock
            }
            return null
        }

        fun parse426Payload(
            rawBody: String?,
            detectedAtEpochMs: Long = System.currentTimeMillis(),
        ): VersionEnforcementBlockState? {
            val raw = rawBody?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val detail = root.optJSONObject("detail") ?: root
            val code = detail.optString("code", "").trim()
            if (code != "UNSUPPORTED_APP_VERSION") {
                return null
            }
            val minCode = detail.optInt("minimumSupportedVersionCode", 0)
            if (minCode <= 0) {
                return null
            }
            val minTag = detail.optString("minimumSupportedTag", "").trim().take(32)
            val minVersion = detail.optString("minimumSupportedVersion", "").trim().take(32)
            val recCode = detail.optInt("recommendedVersionCode", 0).coerceAtLeast(0)
            val recTag = detail.optString("recommendedTag", "").trim().take(32)
            val latestTag = detail.optString("latestStableTag", "").trim().take(32)
            val latestVersion = detail.optString("latestStableVersion", "").trim().take(32)
            val rawUpdateUrl = detail.optString("updateUrl", "").trim()
            val safeUpdateUrl = if (BrowserLauncher.isSafeHttpUrl(rawUpdateUrl)) {
                rawUpdateUrl
            } else {
                VersionEnforcementBlockState.DEFAULT_UPDATE_URL
            }
            val msg = detail.optString("message", "").trim().take(500)
            return VersionEnforcementBlockState(
                minimumSupportedVersionCode = minCode,
                minimumSupportedTag = minTag,
                minimumSupportedVersion = minVersion,
                recommendedVersionCode = recCode,
                recommendedTag = recTag,
                latestStableTag = latestTag,
                latestStableVersion = latestVersion,
                updateUrl = safeUpdateUrl,
                message = msg,
                triggeredByServer426 = true,
                detectedAtEpochMs = detectedAtEpochMs,
            )
        }
    }
}

/**
 * Attaches Laterbox client version headers ONLY to Laterbox backend requests (never third-party websites or external
 * media providers like Internet Archive / TVMaze / TMDB) and feeds HTTP 426 Upgrade Required responses into [versionGate].
 */
class LaterboxClientHeadersInterceptor(
    private val clientVersionCode: Int,
    private val clientVersionName: String,
    private val backendBaseUrlProvider: () -> String,
    private val versionGate: LaterboxVersionGate? = null,
    private val attachOnAnyHostForTesting: Boolean = false,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val isBackend = attachOnAnyHostForTesting || isLaterboxBackendUrl(original.url, backendBaseUrlProvider())
        if (!isBackend) {
            return chain.proceed(original)
        }

        val updatedRequest = original.newBuilder()
            .header(HEADER_CLIENT, CLIENT_ANDROID)
            .header(HEADER_VERSION_CODE, clientVersionCode.toString())
            .header(HEADER_VERSION_NAME, clientVersionName)
            .build()

        val response = chain.proceed(updatedRequest)
        if (response.code == 426) {
            val bodyPeek = runCatching { response.peekBody(16_384L).string() }.getOrNull()
            versionGate?.onHttp426(bodyPeek, original.url.encodedPath)
        } else if (response.isSuccessful) {
            versionGate?.onSupportedBackendResponse(original.url.encodedPath)
        }
        return response
    }

    companion object {
        const val HEADER_CLIENT = "X-Laterbox-Client"
        const val HEADER_VERSION_CODE = "X-Laterbox-Version-Code"
        const val HEADER_VERSION_NAME = "X-Laterbox-Version-Name"
        const val CLIENT_ANDROID = "android"

        fun isLaterboxBackendUrl(requestUrl: HttpUrl, backendBaseUrl: String): Boolean {
            val parsedBase = backendBaseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return false
            return requestUrl.scheme.equals(parsedBase.scheme, ignoreCase = true) &&
                requestUrl.host.equals(parsedBase.host, ignoreCase = true) &&
                requestUrl.port == parsedBase.port
        }
    }
}
