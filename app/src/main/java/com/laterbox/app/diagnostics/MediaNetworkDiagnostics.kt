package com.laterbox.app.diagnostics

import com.laterbox.app.network.InitialMediaHost
import com.laterbox.app.network.RedirectHeaderGuardInterceptor
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

enum class MediaRequestKind { PLAYBACK, DOWNLOAD, SUBTITLE, UNKNOWN }

/** Per-request context attached as a request tag by the code that issues the media request. */
data class MediaDiagContext(
    val kind: MediaRequestKind,
    val format: String,
    val engine: String = ENGINE_OKHTTP,
    /** True when the caller supplied a provider cookie header (e.g. signed Edge-Cache-Cookie). */
    val signedCookieExpected: Boolean = false,
) {
    companion object {
        const val ENGINE_OKHTTP = "okhttp"

        fun inferFormat(url: HttpUrl): String {
            val path = url.encodedPath.lowercase(Locale.ROOT)
            return when {
                path.endsWith(".mpd") -> "DASH_MANIFEST"
                path.endsWith(".m3u8") -> "HLS_MANIFEST"
                path.endsWith(".m4s") -> "DASH_SEGMENT"
                path.endsWith(".ts") -> "HLS_SEGMENT"
                path.endsWith(".srt") || path.endsWith(".vtt") || path.endsWith(".ass") ||
                    path.endsWith(".ssa") || path.endsWith(".ttml") -> "SUBTITLE"
                path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mkv") ||
                    path.endsWith(".webm") -> "PROGRESSIVE"
                else -> "UNKNOWN"
            }
        }
    }
}

data class SignedCookieInfo(
    val present: Boolean,
    val names: List<String>,
    /** "none", "valid (Ns left)", "expired (Ns ago)" or "unknown". Never contains cookie values. */
    val expiry: String,
) {
    companion object {
        private val EXPIRES = Regex("""(?:^|[^A-Za-z])(?:Expires|exp)=(\d{9,13})""", RegexOption.IGNORE_CASE)

        fun inspect(cookieHeader: String?, nowMs: Long): SignedCookieInfo {
            if (cookieHeader.isNullOrBlank()) return SignedCookieInfo(false, emptyList(), "none")
            val names = mutableListOf<String>()
            var expiresAtSec: Long? = null
            cookieHeader.split(";").forEach { raw ->
                val pair = raw.trim()
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    names += pair.substring(0, eq).trim()
                    EXPIRES.find(pair.substring(eq + 1))?.let { m ->
                        val rawNum = m.groupValues[1].toLongOrNull()
                        val v = if (rawNum != null && rawNum > 10_000_000_000L) rawNum / 1000L else rawNum
                        if (v != null && (expiresAtSec == null || v < expiresAtSec!!)) expiresAtSec = v
                    }
                }
            }
            val exp = expiresAtSec
            val state = if (exp == null) {
                "unknown"
            } else {
                val delta = exp - nowMs / 1000L
                if (delta >= 0) "valid (${delta}s left)" else "expired (${-delta}s ago)"
            }
            return SignedCookieInfo(names.isNotEmpty(), names, state)
        }
    }
}

data class MediaHop(
    val timestampMs: Long,
    val method: String,
    val host: String,
    val range: String?,
    val requestHeaderNames: List<String>,
    val cookie: SignedCookieInfo,
    val refererPresent: Boolean,
    val originPresent: Boolean,
    val userAgentPresent: Boolean,
    val tokenHeaderPresent: Boolean,
    val authorizationPresent: Boolean,
    /** HTTP status, or -1 when the hop failed before a response. */
    val status: Int,
    val responseHeaders: Map<String, String>,
    val responseHeaderNames: List<String>,
    val setCookieNames: List<String>,
    val redirectToHost: String?,
    val bodyPreview: String?,
    val error: String?,
    val durationMs: Long,
)

data class MediaNetworkEvent(
    val timestampMs: Long,
    val kind: MediaRequestKind,
    val format: String,
    val engine: String,
    val pathShape: String,
    val initialHost: String,
    val finalHost: String,
    val hops: List<MediaHop>,
    val outcome: String,
    val signedCookieExpected: Boolean,
    val durationMs: Long,
) {
    val finalStatus: Int get() = hops.lastOrNull()?.status ?: -1
    val redirectCount: Int get() = hops.count { it.redirectToHost != null }
}

/** Mutable per-call trace shared between the application and network interceptors. */
class MediaTrace(val ctx: MediaDiagContext) {
    val hops = CopyOnWriteArrayList<MediaHop>()
}

class MediaNetworkDiagnosticsStore(private val maxEvents: Int = 100) {
    private val lock = Any()
    private val events = ArrayDeque<MediaNetworkEvent>()

    fun record(event: MediaNetworkEvent) = synchronized(lock) {
        events.addLast(event)
        while (events.size > maxEvents) events.removeFirst()
    }

    fun snapshot(): List<MediaNetworkEvent> = synchronized(lock) { events.toList() }

    fun clear() = synchronized(lock) { events.clear() }

    /**
     * Human-readable report. Contains only hostnames, header NAMES, status codes, safe response
     * headers and cookie metadata (names/expiry). It never contains URLs with paths/queries,
     * cookie values, tokens, signatures or authorization values.
     */
    fun exportText(nowMs: Long = System.currentTimeMillis(), limit: Int = 40): String {
        val all = snapshot()
        val sb = StringBuilder()
        sb.appendLine("MEDIA NETWORK DIAGNOSTICS")
        sb.appendLine("Generated: ${formatTime(nowMs)}")
        sb.appendLine("Events recorded this session: ${all.size} (showing latest ${minOf(limit, all.size)})")
        sb.appendLine("Engine: Laterbox media stack uses OkHttp for playback, downloads and subtitles (no Cronet).")
        sb.appendLine("Credentials are never recorded: only header names, cookie names/expiry and presence flags.")
        if (all.isEmpty()) {
            sb.appendLine()
            sb.appendLine("No media requests captured yet. Reproduce the playback/download failure, then reopen this report.")
            return sb.toString().trimEnd()
        }
        sb.appendLine()
        sb.appendLine("SUMMARY (kind / format / host -> status counts)")
        all.groupBy { Triple(it.kind, it.format, it.finalHost) }.forEach { (k, list) ->
            val counts = list.groupingBy { it.outcome }.eachCount().entries.joinToString(", ") { "${it.key}x${it.value}" }
            sb.appendLine("- ${k.first} / ${k.second} / ${k.third}: $counts")
        }
        sb.appendLine()
        all.asReversed().take(limit).forEachIndexed { idx, e ->
            sb.appendLine("#${idx + 1} ${formatTime(e.timestampMs)} ${e.kind} ${e.format} engine=${e.engine} outcome=${e.outcome} (${e.durationMs}ms)")
            sb.appendLine("   path=${e.pathShape} initialHost=${e.initialHost} finalHost=${e.finalHost} redirects=${e.redirectCount}")
            sb.appendLine("   signedCookieExpected=${e.signedCookieExpected}")
            e.hops.forEachIndexed { h, hop ->
                sb.appendLine("   hop${h + 1}: ${hop.method} ${hop.host} range=${hop.range ?: "none"} -> ${if (hop.status >= 0) hop.status else "FAILED"}" +
                    (hop.redirectToHost?.let { " redirectTo=$it" } ?: "") + " (${hop.durationMs}ms)")
                sb.appendLine("      reqHeaders=${hop.requestHeaderNames.joinToString(",")}")
                sb.appendLine("      attached: cookie=${hop.cookie.present}${if (hop.cookie.present) " names=${hop.cookie.names.joinToString("|")} expiry=${hop.cookie.expiry}" else ""} referer=${hop.refererPresent} origin=${hop.originPresent} userAgent=${hop.userAgentPresent} tokenHeader=${hop.tokenHeaderPresent} authorization=${hop.authorizationPresent}")
                if (hop.responseHeaders.isNotEmpty()) {
                    sb.appendLine("      resp: " + hop.responseHeaders.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
                if (hop.responseHeaderNames.isNotEmpty()) {
                    sb.appendLine("      respHeaderNames=${hop.responseHeaderNames.joinToString(",")}")
                }
                if (hop.setCookieNames.isNotEmpty()) sb.appendLine("      setCookieNames=${hop.setCookieNames.joinToString("|")}")
                hop.bodyPreview?.let { sb.appendLine("      bodyPreview=\"$it\"") }
                hop.error?.let { sb.appendLine("      error=$it") }
            }
            sb.appendLine()
        }
        return sb.toString().trimEnd()
    }

    private fun formatTime(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }
}

object MediaNetworkDiagnostics {
    val default = MediaNetworkDiagnosticsStore()

    private val SAFE_RESPONSE_HEADERS = listOf(
        "Retry-After", "Date", "Content-Range", "Content-Length", "Content-Type", "Accept-Ranges",
        "Server", "Via", "Age", "X-Cache", "Cache-Control", "X-Request-Id", "CF-Ray", "ETag",
    )
    private val LONG_TOKEN = Regex("""[A-Za-z0-9_\-=:%.~]{40,}""")

    internal fun sanitizeBody(raw: String): String {
        val flat = raw.replace(Regex("""\s+"""), " ").filter { it.code in 32..126 }.trim()
        return LONG_TOKEN.replace(flat, "[redacted]").take(160)
    }

    internal fun pathShape(url: HttpUrl): String {
        val segs = url.pathSegments.filter { it.isNotEmpty() }
        val last = segs.lastOrNull().orEmpty()
        val ext = last.substringAfterLast('.', "").takeIf { it.length in 1..5 && last.contains('.') }
        return "${segs.size} segments${ext?.let { ", ext .$it" } ?: ""}"
    }

    internal fun buildHop(
        startMs: Long,
        request: okhttp3.Request,
        response: Response?,
        error: Throwable?,
        nowMs: Long,
    ): MediaHop {
        val names = request.headers.names().sortedBy { it.lowercase(Locale.ROOT) }
        val cookie = SignedCookieInfo.inspect(request.header("Cookie"), nowMs)
        val safe = linkedMapOf<String, String>()
        var setCookieNames = emptyList<String>()
        var redirectTo: String? = null
        var body: String? = null
        var respNames = emptyList<String>()
        if (response != null) {
            respNames = response.headers.names().sortedBy { it.lowercase(Locale.ROOT) }
            for (h in SAFE_RESPONSE_HEADERS) response.header(h)?.let { safe[h] = it.take(120) }
            setCookieNames = response.headers("Set-Cookie").mapNotNull { it.substringBefore('=', "").trim().takeIf(String::isNotEmpty) }
            if (response.isRedirect) {
                redirectTo = response.header("Location")?.let { request.url.resolve(it)?.host }
            }
            if (response.code >= 400) {
                body = runCatching { sanitizeBody(response.peekBody(256).string()) }.getOrNull()?.takeIf { it.isNotEmpty() }
            }
        }
        return MediaHop(
            timestampMs = startMs,
            method = request.method,
            host = request.url.host,
            range = request.header("Range"),
            requestHeaderNames = names,
            cookie = cookie,
            refererPresent = request.header("Referer") != null,
            originPresent = request.header("Origin") != null,
            userAgentPresent = request.header("User-Agent") != null,
            tokenHeaderPresent = names.any { n ->
                val l = n.lowercase(Locale.ROOT)
                l == "x-mb-token" || (l.startsWith("x-") && l.endsWith("-token"))
            },
            authorizationPresent = request.header("Authorization") != null,
            status = response?.code ?: -1,
            responseHeaders = safe,
            responseHeaderNames = respNames,
            setCookieNames = setCookieNames,
            redirectToHost = redirectTo,
            bodyPreview = body,
            error = error?.let { it.javaClass.simpleName },
            durationMs = (nowMs - startMs).coerceAtLeast(0),
        )
    }
}

/**
 * Application interceptor: opens a [MediaTrace], tags the request, and records one
 * [MediaNetworkEvent] per logical request after redirects have been followed.
 */
class MediaDiagnosticsInterceptor(
    private val defaultKind: MediaRequestKind,
    private val store: MediaNetworkDiagnosticsStore = MediaNetworkDiagnostics.default,
    private val clock: () -> Long = System::currentTimeMillis,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val ctx = request.tag(MediaDiagContext::class.java) ?: MediaDiagContext(
            kind = defaultKind,
            format = MediaDiagContext.inferFormat(request.url),
            signedCookieExpected = request.header("Cookie") != null,
        )
        val trace = MediaTrace(ctx)
        val tagged = request.newBuilder()
            .tag(MediaTrace::class.java, trace)
            .apply {
                if (request.tag(InitialMediaHost::class.java) == null) {
                    tag(InitialMediaHost::class.java, InitialMediaHost(request.url.host))
                }
            }
            .build()
        val start = clock()
        var outcome = "ERROR"
        try {
            val response = chain.proceed(tagged)
            outcome = response.code.toString()
            return response
        } catch (e: IOException) {
            outcome = "EXCEPTION ${e.javaClass.simpleName}"
            throw e
        } finally {
            val hops = trace.hops.toList()
            store.record(
                MediaNetworkEvent(
                    timestampMs = start,
                    kind = ctx.kind,
                    format = ctx.format,
                    engine = ctx.engine,
                    pathShape = MediaNetworkDiagnostics.pathShape(request.url),
                    initialHost = request.url.host,
                    finalHost = hops.lastOrNull()?.host ?: request.url.host,
                    hops = hops,
                    outcome = outcome,
                    signedCookieExpected = ctx.signedCookieExpected,
                    durationMs = (clock() - start).coerceAtLeast(0),
                )
            )
        }
    }
}

/** Network interceptor: records every hop (including redirects) with the headers actually sent. */
class MediaDiagnosticsNetworkInterceptor(
    private val clock: () -> Long = System::currentTimeMillis,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val trace = request.tag(MediaTrace::class.java) ?: return chain.proceed(request)
        val start = clock()
        try {
            val response = chain.proceed(request)
            trace.hops += MediaNetworkDiagnostics.buildHop(start, request, response, null, clock())
            return response
        } catch (e: IOException) {
            trace.hops += MediaNetworkDiagnostics.buildHop(start, request, null, e, clock())
            throw e
        }
    }
}

/** Adds redirect-safe header scoping and media diagnostics to [this] client. Idempotent. */
fun OkHttpClient.withMediaDiagnostics(
    defaultKind: MediaRequestKind,
    store: MediaNetworkDiagnosticsStore = MediaNetworkDiagnostics.default,
    cookieFor: ((HttpUrl) -> String?)? = null,
): OkHttpClient {
    if (interceptors.any { it is MediaDiagnosticsInterceptor }) return this
    return newBuilder()
        .addInterceptor(MediaDiagnosticsInterceptor(defaultKind, store))
        // Guard first so the diagnostics network interceptor records the headers truly sent.
        .addNetworkInterceptor(RedirectHeaderGuardInterceptor(cookieFor))
        .addNetworkInterceptor(MediaDiagnosticsNetworkInterceptor())
        .build()
}
