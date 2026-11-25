package com.laterbox.app.update

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * Classification of updater network failures. Used to decide whether a retry is useful,
 * whether the public GitHub fallback may be attempted, and which user-facing copy to show.
 */
enum class UpdateFailureKind {
    /** Hostname could not be resolved (UnknownHostException). */
    DNS,
    /** TCP connection refused / unreachable. */
    CONNECT,
    /** Connect or read timeout. */
    TIMEOUT,
    /** HTTP 401 / 403 (not a rate limit). */
    AUTH,
    /** HTTP 429 or explicit rate-limit signal. */
    RATE_LIMIT,
    /** Signed CDN URL no longer valid (HTTP 410, or 401/403 from the CDN). */
    EXPIRED_URL,
    /** HTTP 5xx. */
    SERVER,
    /** Stream dropped mid-transfer; partial bytes can be resumed. */
    INTERRUPTED,
    /** Permanent client/config problem (bad URL, disallowed host, HTTP 404, ...). */
    CONFIG,
    UNKNOWN;

    /** True when the backend itself could not be reached at the network level. */
    val isBackendUnreachable: Boolean
        get() = this == DNS || this == CONNECT || this == TIMEOUT
}

/** Bounded retry configuration for one failure kind. */
internal data class UpdateRetryPolicy(
    val maxConsecutiveAttempts: Int,
    val baseDelayMs: Long,
    val maxDelayMs: Long
)

internal object UpdateNetworkSupport {

    /** Hard cap across every attempt of a single download, whatever the failure mix. */
    const val MAX_TOTAL_ATTEMPTS = 8

    fun classifyThrowable(t: Throwable): UpdateFailureKind = when (t) {
        is UnknownHostException -> UpdateFailureKind.DNS
        is SocketTimeoutException -> UpdateFailureKind.TIMEOUT
        is ConnectException, is NoRouteToHostException -> UpdateFailureKind.CONNECT
        is SSLException -> UpdateFailureKind.CONNECT
        is SocketException -> UpdateFailureKind.INTERRUPTED
        is IOException -> UpdateFailureKind.INTERRUPTED
        else -> UpdateFailureKind.UNKNOWN
    }

    fun classifyHttpStatus(code: Int): UpdateFailureKind = when {
        code == 429 -> UpdateFailureKind.RATE_LIMIT
        code == 410 -> UpdateFailureKind.EXPIRED_URL
        code == 401 || code == 403 -> UpdateFailureKind.AUTH
        code in 500..599 -> UpdateFailureKind.SERVER
        code in 400..499 -> UpdateFailureKind.CONFIG
        else -> UpdateFailureKind.UNKNOWN
    }

    fun retryPolicyFor(kind: UpdateFailureKind): UpdateRetryPolicy = when (kind) {
        // One quick retry covers a transient resolver hiccup; a persistent DNS failure is a
        // network/configuration problem that identical retries will not fix.
        UpdateFailureKind.DNS -> UpdateRetryPolicy(2, 1_000L, 1_000L)
        UpdateFailureKind.CONNECT -> UpdateRetryPolicy(3, 800L, 6_000L)
        UpdateFailureKind.TIMEOUT -> UpdateRetryPolicy(3, 800L, 6_000L)
        UpdateFailureKind.SERVER -> UpdateRetryPolicy(3, 1_000L, 8_000L)
        UpdateFailureKind.INTERRUPTED -> UpdateRetryPolicy(5, 600L, 5_000L)
        UpdateFailureKind.UNKNOWN -> UpdateRetryPolicy(2, 800L, 3_000L)
        UpdateFailureKind.AUTH,
        UpdateFailureKind.RATE_LIMIT,
        UpdateFailureKind.EXPIRED_URL,
        UpdateFailureKind.CONFIG -> UpdateRetryPolicy(1, 0L, 0L)
    }

    /** Exponential backoff: base * 2^(failuresSoFar-1), capped. [failuresSoFar] starts at 1. */
    fun backoffDelayMs(policy: UpdateRetryPolicy, failuresSoFar: Int): Long {
        if (policy.baseDelayMs <= 0L) return 0L
        val shift = (failuresSoFar - 1).coerceIn(0, 10)
        return (policy.baseDelayMs shl shift).coerceAtMost(policy.maxDelayMs)
    }

    fun shouldRetry(kind: UpdateFailureKind, consecutiveFailures: Int, totalAttempts: Int): Boolean {
        if (totalAttempts >= MAX_TOTAL_ATTEMPTS) return false
        return consecutiveFailures < retryPolicyFor(kind).maxConsecutiveAttempts
    }

    /** Short, user-safe copy. Never mentions backend vendors, hostnames or tokens. */
    fun userMessage(kind: UpdateFailureKind, httpCode: Int? = null): String = when (kind) {
        UpdateFailureKind.DNS ->
            "Couldn't reach the update server (network name lookup failed). Check your connection or Private DNS setting and try again."
        UpdateFailureKind.CONNECT ->
            "Couldn't connect to the update server. Check your connection and try again."
        UpdateFailureKind.TIMEOUT ->
            "The update server took too long to respond. Please try again."
        UpdateFailureKind.AUTH ->
            "Update check couldn't be verified right now. Please try again later."
        UpdateFailureKind.RATE_LIMIT ->
            "Too many update checks right now. Please try again in a little while."
        UpdateFailureKind.EXPIRED_URL ->
            "The update download timed out. Please try again."
        UpdateFailureKind.SERVER ->
            "Laterbox updates are temporarily unavailable. Please try again shortly."
        UpdateFailureKind.INTERRUPTED ->
            "The download was interrupted. Please try again; progress is kept."
        UpdateFailureKind.CONFIG ->
            "The update download could not be started."
        UpdateFailureKind.UNKNOWN ->
            "Update download failed. Please try again."
    }

    // ---- Host restrictions -------------------------------------------------------------

    /** Mirrors the backend's allowed signed-asset hosts (updates.py `_ALLOWED_ASSET_HOST_SUFFIXES`). */
    private val allowedHostSuffixes = listOf(
        ".githubusercontent.com",
        ".blob.core.windows.net",
        ".amazonaws.com"
    )
    private val allowedExactHosts = setOf("github.com", "api.github.com", "githubusercontent.com")

    fun isLoopbackHost(host: String): Boolean =
        host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2"

    /**
     * True when [rawUrl] may be downloaded from: HTTPS to GitHub / its CDN hosts, or plain HTTP to
     * loopback (local test servers). Credentials are never attached to any of these requests.
     */
    fun isAllowedAssetUrl(rawUrl: String): Boolean = try {
        val uri = URI(rawUrl.trim())
        val scheme = uri.scheme?.lowercase(Locale.US)
        val host = uri.host?.lowercase(Locale.US)
        when {
            scheme == null || host.isNullOrBlank() -> false
            scheme == "http" -> isLoopbackHost(host)
            scheme == "https" -> host in allowedExactHosts || allowedHostSuffixes.any { host.endsWith(it) } ||
                isLoopbackHost(host)
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    /** Host only (no path/query) so signed CDN URLs are never written to logs. */
    fun safeHost(rawUrl: String): String = try {
        URI(rawUrl.trim()).host ?: "unknown-host"
    } catch (_: Exception) {
        "unknown-host"
    }

    // ---- Official backend host ---------------------------------------------------------

    const val OFFICIAL_BACKEND_HOST = "laterbox-moviebox-api.vercel.app"

    fun isOfficialBackendHost(baseUrl: String): Boolean =
        safeHost(baseUrl).equals(OFFICIAL_BACKEND_HOST, ignoreCase = true)

    // ---- Public GitHub fallback URLs ----------------------------------------------------

    const val PUBLIC_RELEASES_LATEST_URL = "https://api.github.com/repos/Adetisola/Laterbox/releases/latest"
    private const val PUBLIC_RELEASE_DOWNLOAD_BASE = "https://github.com/Adetisola/Laterbox/releases/download"

    /** Official public asset URL for [assetFilename] in release [versionName]; null if inputs are unusable. */
    fun publicAssetUrl(versionName: String, assetFilename: String): String? {
        val version = versionName.trim().removePrefix("v").removePrefix("V")
        val name = assetFilename.trim()
        if (version.isBlank() || name.isBlank()) return null
        if (!version.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "$PUBLIC_RELEASE_DOWNLOAD_BASE/v$version/$encoded"
    }

    // ---- Injectable seams (tests) -------------------------------------------------------

    /** Opens connections; tests replace it to simulate DNS failures and slow/interrupted transfers. */
    @Volatile
    internal var connectionFactory: (URL) -> HttpURLConnection = { url -> url.openConnection() as HttpURLConnection }

    /** Backoff sleeper; tests replace it to avoid real waiting and to assert delays. */
    @Volatile
    internal var sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }

    internal fun resetSeams() {
        connectionFactory = { url -> url.openConnection() as HttpURLConnection }
        sleeper = { kotlinx.coroutines.delay(it) }
    }
}
