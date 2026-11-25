package com.laterbox.app.movies.data.provider

import android.os.Build
import android.util.Log
import com.laterbox.app.diagnostics.MediaLifecycleEvent
import com.laterbox.app.diagnostics.MediaNetworkDiagnostics
import com.laterbox.app.diagnostics.MediaNetworkDiagnosticsStore
import com.laterbox.app.diagnostics.MediaRequestKind
import com.laterbox.app.movies.domain.model.PlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class NativePlayInfoOutcome(
    val sources: List<PlaybackSource>,
    val resolvedTitle: String? = null,
    val apiHost: String? = null,
    val httpStatus: Int? = null,
    val cdnThrottleLevel: Int? = null,
    val rawStreamCount: Int = 0,
    val stubFilteredCount: Int = 0,
    val rawHosts: Set<String> = emptySet(),
    val regionBlocked: Boolean = false,
)

/**
 * Direct on-device native BFF client for stream resolution.
 *
 * Uses a clean [OkHttpClient] without Laterbox backend headers, builds signed requests,
 * recovers policy-embedded adaptive manifests, filters known stub clips, and records sanitized
 * metadata (hostnames, HTTP status, throttle flags, counts; never secrets/URLs) into
 * [MediaNetworkDiagnosticsStore].
 */
class MovieBoxNativeStreamResolver(
    private val okHttpClient: OkHttpClient,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val deviceIdProvider: () -> String = { generateRandomHex32() },
    private val bootstrapBaseUrl: String = DEFAULT_BOOTSTRAP_BASE_URL,
    private val nativeApiBaseUrls: List<String> = DEFAULT_NATIVE_API_BASES,
    private val diagnosticsStore: MediaNetworkDiagnosticsStore = MediaNetworkDiagnostics.default,
    private val logger: (String) -> Unit = { message ->
        runCatching { Log.i(TAG, message) }
    },
) {
    private val tokenMutex = Mutex()
    private val randomDeviceId: String by lazy { deviceIdProvider() }

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var regionBlockedUntilMs: Long = 0L

    suspend fun resolveStreams(
        subjectId: String,
        seasonNumber: Int,
        episodeNumber: Int,
    ): NativePlayInfoOutcome = withContext(Dispatchers.IO) {
        val cleanId = subjectId.trim()
        if (cleanId.isEmpty() || nativeApiBaseUrls.isEmpty()) {
            return@withContext NativePlayInfoOutcome(sources = emptyList())
        }

        val nowMs = clockMs()
        if (nowMs < regionBlockedUntilMs) {
            diagnosticsStore.recordLifecycle(
                MediaLifecycleEvent(
                    timestampMs = nowMs,
                    kind = MediaRequestKind.PLAYBACK,
                    stage = "NATIVE_PLAY_INFO",
                    host = nativeApiBaseUrls.firstOrNull()?.toHttpUrlOrNull()?.host,
                    refreshRequested = false,
                    failureCategory = "region_blocked_cooldown",
                    note = "skipped_cooldown",
                )
            )
            return@withContext NativePlayInfoOutcome(sources = emptyList(), regionBlocked = true)
        }

        var token = getOrBootstrapToken(forceRefresh = false)
        var allForbidden = true
        var lastStatus: Int? = null
        var lastHost: String? = null

        for (base in nativeApiBaseUrls) {
            val cleanBase = base.trim().trimEnd('/')
            val baseHttpUrl = cleanBase.toHttpUrlOrNull() ?: continue
            val apiHost = baseHttpUrl.host
            lastHost = apiHost

            val playUrl = baseHttpUrl.newBuilder()
                .addPathSegments("wefeed-mobile-bff/subject-api/play-info")
                .addQueryParameter("subjectId", cleanId)
                .addQueryParameter("se", seasonNumber.toString())
                .addQueryParameter("ep", episodeNumber.toString())
                .addQueryParameter("host", apiHost)
                .build()

            var response = executeSignedGet(playUrl, token)
            if (response.statusCode in setOf(401, 441)) {
                val refreshed = getOrBootstrapToken(forceRefresh = true, staleToken = token)
                if (!refreshed.isNullOrBlank()) {
                    token = refreshed
                    response = executeSignedGet(playUrl, token)
                }
            }

            lastStatus = response.statusCode
            if (response.statusCode == 200 && !response.body.isNullOrBlank()) {
                val parsed = parsePlayInfoResponse(
                    rawBody = response.body,
                    apiBaseUrl = cleanBase,
                    apiHost = apiHost,
                    statusCode = 200,
                )
                if (parsed != null) {
                    val returnedHosts = parsed.sources
                        .mapNotNull { it.url.toHttpUrlOrNull()?.host }
                        .distinct()
                        .joinToString("|")
                        .ifEmpty { "none" }
                    val rawHostsStr = parsed.rawHosts
                        .joinToString("|")
                        .ifEmpty { "none" }
                    diagnosticsStore.recordLifecycle(
                        MediaLifecycleEvent(
                            timestampMs = clockMs(),
                            kind = MediaRequestKind.PLAYBACK,
                            stage = "NATIVE_PLAY_INFO",
                            host = apiHost,
                            statusCode = 200,
                            refreshRequested = false,
                            note = "throttle=${parsed.cdnThrottleLevel ?: -1} raw=${parsed.rawStreamCount} usable=${parsed.sources.size} stub=${parsed.stubFilteredCount} cdnHosts=$returnedHosts rawHosts=$rawHostsStr",
                        )
                    )
                    logger("native play-info host=$apiHost status=200 throttle=${parsed.cdnThrottleLevel} raw=${parsed.rawStreamCount} usable=${parsed.sources.size} stub=${parsed.stubFilteredCount} cdnHosts=$returnedHosts rawHosts=$rawHostsStr")
                    return@withContext parsed
                }
            }

            if (response.statusCode != 403) {
                allForbidden = false
            }

            diagnosticsStore.recordLifecycle(
                MediaLifecycleEvent(
                    timestampMs = clockMs(),
                    kind = MediaRequestKind.PLAYBACK,
                    stage = "NATIVE_PLAY_INFO",
                    host = apiHost,
                    statusCode = response.statusCode,
                    refreshRequested = false,
                    failureCategory = classifyNativeFailure(response.statusCode, response.errorName),
                    note = response.errorName?.let { "err=$it" },
                )
            )
            logger("native play-info host=$apiHost status=${response.statusCode} err=${response.errorName ?: "none"}")
        }

        if (allForbidden && lastStatus == 403) {
            regionBlockedUntilMs = clockMs() + REGION_BLOCK_COOLDOWN_MS
        }

        NativePlayInfoOutcome(
            sources = emptyList(),
            apiHost = lastHost,
            httpStatus = lastStatus,
            regionBlocked = allForbidden && lastStatus == 403,
        )
    }

    private suspend fun getOrBootstrapToken(
        forceRefresh: Boolean,
        staleToken: String? = null,
    ): String? = tokenMutex.withLock {
        val current = cachedToken
        if (!forceRefresh && !current.isNullOrBlank()) {
            return current
        }
        if (forceRefresh && !staleToken.isNullOrBlank() && !current.isNullOrBlank() && current != staleToken) {
            return current
        }

        val bootUrl = bootstrapBaseUrl.trim().trimEnd('/').toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegments("wefeed-mobile-bff/tab/ranking-list")
            ?.addQueryParameter("tabId", "0")
            ?.addQueryParameter("categoryType", "4516404531735022304")
            ?.addQueryParameter("page", "1")
            ?.addQueryParameter("perPage", "1")
            ?.build()
            ?: return null

        val resp = executeSignedGet(bootUrl, bearerToken = null)
        val extracted = extractBootstrapToken(resp)
        if (!extracted.isNullOrBlank()) {
            cachedToken = extracted
        } else if (forceRefresh) {
            cachedToken = null
        }

        diagnosticsStore.recordLifecycle(
            MediaLifecycleEvent(
                timestampMs = clockMs(),
                kind = MediaRequestKind.PLAYBACK,
                stage = "NATIVE_BOOTSTRAP",
                host = bootUrl.host,
                statusCode = resp.statusCode,
                refreshRequested = forceRefresh,
                failureCategory = if (extracted == null) classifyNativeFailure(resp.statusCode, resp.errorName) else null,
                note = "hasToken=${!extracted.isNullOrBlank()}" + (resp.errorName?.let { " err=$it" } ?: ""),
            )
        )
        logger("native bootstrap host=${bootUrl.host} status=${resp.statusCode} hasToken=${!extracted.isNullOrBlank()}")
        extracted
    }

    private fun extractBootstrapToken(resp: NativeHttpResponse): String? {
        if (resp.statusCode != 200) return null
        val xUserRaw = resp.xUserHeader?.trim()
        if (!xUserRaw.isNullOrEmpty()) {
            val fromXUser = runCatching {
                JSONObject(xUserRaw).optString("token").trim().takeIf { it.isNotEmpty() }
            }.getOrNull()
            if (fromXUser != null) return fromXUser
        }
        for (cookieLine in resp.setCookies) {
            val match = TOKEN_COOKIE_REGEX.find(cookieLine)
            val tok = match?.groupValues?.getOrNull(1)?.trim()
            if (!tok.isNullOrEmpty()) return tok
        }
        return null
    }

    private fun parsePlayInfoResponse(
        rawBody: String,
        apiBaseUrl: String,
        apiHost: String,
        statusCode: Int,
    ): NativePlayInfoOutcome? {
        val root = runCatching { JSONObject(rawBody) }.getOrNull() ?: return null
        val data = root.optJSONObject("data") ?: return null
        val rawStreams = data.optJSONArray("streams") ?: JSONArray()
        val throttle = if (data.has("cdnThrottleLevel") && !data.isNull("cdnThrottleLevel")) {
            data.optInt("cdnThrottleLevel")
        } else {
            null
        }
        val title = data.optString("title").trim().takeIf { it.isNotEmpty() }

        val sources = mutableListOf<PlaybackSource>()
        val seenKeys = hashSetOf<String>()
        val rawHosts = linkedSetOf<String>()
        var stubCount = 0

        for (i in 0 until rawStreams.length()) {
            val item = rawStreams.optJSONObject(i) ?: continue
            val rawUrl = item.optCleanString("url")
            rawUrl?.toHttpUrlOrNull()?.host?.let { rawHosts += it }
            val signCookie = item.optCleanString("signCookie")
            val signHeaderKey = item.optCleanString("signHeaderKey")
            val codec = item.optCleanString("codecName")
            val sizeBytes = item.optCleanString("size")?.toLongOrNull() ?: 0L
            val durationSec = item.optCleanString("duration")?.toLongOrNull()
            val rawFormat = item.optCleanString("format")?.uppercase(Locale.US)
            val resString = item.optCleanString("resolutions") ?: item.optCleanString("resolution")

            val policyUrl = if (rawUrl.isNullOrBlank() || isKnownStubStream(rawUrl, sizeBytes, durationSec)) {
                extractPolicyResource(signCookie)
            } else {
                null
            }

            val resolvedUrl = policyUrl ?: rawUrl
            if (resolvedUrl.isNullOrBlank() || (policyUrl == null && isKnownStubStream(resolvedUrl, sizeBytes, durationSec))) {
                stubCount++
                continue
            }

            val lowerUrl = resolvedUrl.lowercase(Locale.US)
            val format = when {
                lowerUrl.contains(".mpd") || rawFormat == "DASH" -> "DASH"
                lowerUrl.contains(".m3u8") || rawFormat == "HLS" -> "HLS"
                !rawFormat.isNullOrBlank() -> rawFormat
                else -> "MP4"
            }

            val headers = buildStreamHeaders(
                apiBaseUrl = apiBaseUrl,
                streamUrl = resolvedUrl,
                signHeaderKey = signHeaderKey,
                signCookie = signCookie,
            )

            val qualityHeights = parseQualityHeights(resString)
            val sizeMb = if (sizeBytes > 0L) (sizeBytes.toDouble() / (1024.0 * 1024.0)) else null

            if (qualityHeights.isEmpty() || format == "DASH" || format == "HLS") {
                val qHeight = qualityHeights.maxOrNull() ?: 0
                val label = when {
                    format == "DASH" && qHeight > 0 -> "${qHeight}p (Adaptive)"
                    format == "DASH" -> "Adaptive DASH"
                    format == "HLS" && qHeight > 0 -> "${qHeight}p (HLS)"
                    format == "HLS" -> "HLS"
                    qHeight > 0 -> "${qHeight}p"
                    else -> resString ?: "Stream"
                }
                val dedupeKey = "$resolvedUrl|$qHeight|$format"
                if (seenKeys.add(dedupeKey)) {
                    sources += PlaybackSource(
                        id = "mb_nat_${i}_${qHeight}_${format.lowercase(Locale.US)}",
                        url = resolvedUrl,
                        format = format,
                        qualityLabel = label,
                        qualityHeight = qHeight,
                        sizeMb = if (format == "DASH" || format == "HLS") null else sizeMb,
                        codec = codec,
                        durationSeconds = durationSec?.takeIf { it > 0L },
                        headers = headers,
                        isAuthorised = true,
                        providerName = "Native Stream",
                    )
                }
            } else {
                for (qHeight in qualityHeights) {
                    val dedupeKey = "$resolvedUrl|$qHeight|$format"
                    if (seenKeys.add(dedupeKey)) {
                        sources += PlaybackSource(
                            id = "mb_nat_${i}_${qHeight}_${format.lowercase(Locale.US)}",
                            url = resolvedUrl,
                            format = format,
                            qualityLabel = "${qHeight}p",
                            qualityHeight = qHeight,
                            sizeMb = sizeMb,
                            codec = codec,
                            durationSeconds = durationSec?.takeIf { it > 0L },
                            headers = headers,
                            isAuthorised = true,
                            providerName = "Native Stream",
                        )
                    }
                }
            }
        }

        val sorted = sources.sortedWith(
            compareByDescending<PlaybackSource> { it.qualityHeight }
                .thenBy { it.format == "DASH" || it.format == "HLS" }
        )
        return NativePlayInfoOutcome(
            sources = sorted,
            resolvedTitle = title,
            apiHost = apiHost,
            httpStatus = statusCode,
            cdnThrottleLevel = throttle,
            rawStreamCount = rawStreams.length(),
            stubFilteredCount = stubCount,
            rawHosts = rawHosts,
        )
    }

    private fun executeSignedGet(url: HttpUrl, bearerToken: String?): NativeHttpResponse {
        val ts = clockMs()
        val clientInfo = buildClientInfoJson(randomDeviceId)
        val reqBuilder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", buildUserAgent())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Connection", "keep-alive")
            .header("X-Client-Token", generateClientToken(ts))
            .header("X-Tr-Signature", generateTrSignature("GET", "application/json", "application/json", url, null, ts))
            .header("X-Client-Info", clientInfo)
            .header("X-Client-Status", "0")

        if (!bearerToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $bearerToken")
        }

        return try {
            okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val xUser = resp.header("x-user")
                val cookies = resp.headers("Set-Cookie")
                val body = if (resp.code == 200) resp.body?.string() else null
                NativeHttpResponse(
                    statusCode = resp.code,
                    body = body,
                    xUserHeader = xUser,
                    setCookies = cookies,
                )
            }
        } catch (e: Exception) {
            NativeHttpResponse(
                statusCode = -1,
                errorName = e.javaClass.simpleName,
            )
        }
    }

    private fun buildClientInfoJson(deviceId: String): String {
        val osVersion = runCatching { Build.VERSION.RELEASE }.getOrNull()?.takeIf { it.isNotBlank() } ?: "12"
        val model = runCatching { Build.MODEL }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Android"
        val brand = runCatching { Build.BRAND }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Android"
        val region = Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: "NG"
        val timezone = TimeZone.getDefault().id.takeIf { it.isNotBlank() } ?: "Africa/Lagos"

        val ordered = linkedMapOf<String, Any>(
            "package_name" to PACKAGE_NAME,
            "version_name" to VERSION_NAME,
            "version_code" to VERSION_CODE,
            "os" to "android",
            "os_version" to osVersion,
            "device_id" to deviceId,
            "install_store" to "official",
            "brand" to brand,
            "model" to model,
            "system_language" to "en",
            "net" to "NETWORK_WIFI",
            "region" to region,
            "timezone" to timezone,
            "sp_code" to "",
        )
        return JSONObject(ordered).toString()
    }

    private fun buildUserAgent(): String {
        val osVersion = runCatching { Build.VERSION.RELEASE }.getOrNull()?.takeIf { it.isNotBlank() } ?: "12"
        val model = runCatching { Build.MODEL }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Android"
        val region = Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: "NG"
        return "$PACKAGE_NAME/$VERSION_CODE (Linux; U; Android $osVersion; en_$region; $model; Build/SP1A.210812.016; Cronet/145.0.7582.0)"
    }

    private data class NativeHttpResponse(
        val statusCode: Int,
        val body: String? = null,
        val xUserHeader: String? = null,
        val setCookies: List<String> = emptyList(),
        val errorName: String? = null,
    )

    companion object {
        private const val TAG = "MovieBoxNativeResolver"
        const val DEFAULT_BOOTSTRAP_BASE_URL = "https://apig.inmoviebox.com"
        val DEFAULT_NATIVE_API_BASES = listOf(
            "https://api6.aoneroom.com",
            "https://api4sg.aoneroom.com",
            "https://api3.aoneroom.com",
            "https://api.inmoviebox.com",
        )

        private const val PACKAGE_NAME = "com.community.oneroom"
        private const val VERSION_NAME = "3.0.01.0411.03"
        private const val VERSION_CODE = 30010411
        private const val SECRET_KEY_DEFAULT_B64 = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="
        private const val REGION_BLOCK_COOLDOWN_MS = 15 * 60_000L

        private val TOKEN_COOKIE_REGEX = Regex("""(?:^|;\s*)token=([^;]+)""", RegexOption.IGNORE_CASE)
        private val EDGE_URLPREFIX_REGEX = Regex("""Edge-Cache-Cookie=.*?urlprefix=([^:;\s]+)""", RegexOption.IGNORE_CASE)
        private val CF_POLICY_REGEX = Regex("""CloudFront-Policy=([^;]+)""")
        private val HEADER_TOKEN_REGEX = Regex("""^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$""")

        private fun generateRandomHex32(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        internal fun decodeSigningKey(): ByteArray {
            val inner = String(b64DecodeFlexible(SECRET_KEY_DEFAULT_B64), Charsets.UTF_8)
            return b64DecodeFlexible(inner)
        }

        private fun b64DecodeFlexible(input: String): ByteArray {
            val normalized = input.replace('-', '+').replace('_', '/')
            val pad = (4 - (normalized.length % 4)) % 4
            return Base64.getDecoder().decode(normalized + "=".repeat(pad))
        }

        internal fun md5Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("MD5").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        internal fun generateClientToken(timestampMs: Long): String {
            val ts = timestampMs.toString()
            return "$ts,${md5Hex(ts.reversed().toByteArray(Charsets.UTF_8))}"
        }

        internal fun buildCanonicalString(
            method: String,
            accept: String?,
            contentType: String?,
            url: HttpUrl,
            bodyBytes: ByteArray?,
            timestampMs: Long,
        ): String {
            val path = url.encodedPath.ifEmpty { "/" }
            val rawQuery = url.encodedQuery
            val canonicalUrl = if (!rawQuery.isNullOrEmpty()) {
                val pairs = rawQuery.split('&')
                    .filter { it.isNotEmpty() }
                    .map { seg ->
                        val idx = seg.indexOf('=')
                        if (idx >= 0) seg.substring(0, idx) to seg.substring(idx + 1) else seg to ""
                    }
                    .sortedBy { it.first }
                "$path?${pairs.joinToString("&") { "${it.first}=${it.second}" }}"
            } else {
                path
            }

            val bodyLen = if (bodyBytes != null && bodyBytes.isNotEmpty()) bodyBytes.size.toString() else ""
            val bodyHash = if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                val slice = if (bodyBytes.size > 102400) bodyBytes.copyOf(102400) else bodyBytes
                md5Hex(slice)
            } else {
                ""
            }

            return listOf(
                method.uppercase(Locale.US),
                accept.orEmpty(),
                contentType.orEmpty(),
                bodyLen,
                timestampMs.toString(),
                bodyHash,
                canonicalUrl,
            ).joinToString("\n")
        }

        internal fun generateTrSignature(
            method: String,
            accept: String?,
            contentType: String?,
            url: HttpUrl,
            bodyBytes: ByteArray?,
            timestampMs: Long,
        ): String {
            val canonical = buildCanonicalString(method, accept, contentType, url, bodyBytes, timestampMs)
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(decodeSigningKey(), "HmacMD5"))
            val sigB64 = Base64.getEncoder().encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
            return "$timestampMs|2|$sigB64"
        }

        internal fun isKnownStubStream(rawUrl: String?, sizeBytes: Long = 0L, durationSec: Long? = null): Boolean {
            val lower = rawUrl?.trim()?.lowercase(Locale.US).orEmpty()
            if (lower.isEmpty()) return true
            if ("b164fbfb4347792950bdfbfb563d39d9" in lower || "80da52722ee7de7a4303290af6783424" in lower) {
                return true
            }
            if ("/other/" in lower) {
                if (sizeBytes in 1..2_500_000L || (durationSec != null && durationSec in 1L..35L)) {
                    return true
                }
            }
            return false
        }

        internal fun extractPolicyResource(signCookie: String?): String? {
            if (signCookie.isNullOrBlank()) return null
            EDGE_URLPREFIX_REGEX.find(signCookie)?.let { match ->
                val decoded = runCatching {
                    String(b64DecodeFlexible(match.groupValues[1]), Charsets.UTF_8).trimEnd('/')
                }.getOrNull()
                if (!decoded.isNullOrBlank()) {
                    return "$decoded/index.mpd"
                }
            }
            CF_POLICY_REGEX.find(signCookie)?.let { match ->
                val raw = match.groupValues[1]
                for (variant in listOf(raw.replace('-', '+').replace('_', '='), raw)) {
                    val decoded = runCatching { String(b64DecodeFlexible(variant), Charsets.UTF_8) }.getOrNull() ?: continue
                    val resource = runCatching {
                        val statements = JSONObject(decoded).optJSONArray("Statement") ?: return@runCatching null
                        (0 until statements.length())
                            .asSequence()
                            .mapNotNull { statements.optJSONObject(it)?.optString("Resource")?.trim() }
                            .firstOrNull { it.isNotEmpty() }
                    }.getOrNull()
                    if (!resource.isNullOrBlank()) {
                        return "${resource.trimEnd('*').trimEnd('/')}/index.mpd"
                    }
                }
            }
            return null
        }

        internal fun buildStreamHeaders(
            apiBaseUrl: String,
            streamUrl: String,
            signHeaderKey: String?,
            signCookie: String?,
        ): Map<String, String> {
            val lowerUrl = streamUrl.lowercase(Locale.US)
            val cookieVal = signCookie?.trim().orEmpty()
            val headerKey = signHeaderKey?.trim().orEmpty()

            // Direct cookieless native CDN MP4s (e.g. bcdn.hakunaymatata.com/resource/h265/...)
            // play with OkHttp's clean default UA and no synthetic Referer/Origin/Cookie headers.
            if (cookieVal.isEmpty() && ("hakunaymatata.com" in lowerUrl || "?sign=" in lowerUrl || "&sign=" in lowerUrl)) {
                return mapOf("User-Agent" to "okhttp/4.9.3")
            }

            val refOrigin = if ("hakunaymatata.com" in lowerUrl) {
                "https://moviebox.ph"
            } else {
                apiBaseUrl.trimEnd('/')
            }
            val headers = linkedMapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36",
                "Referer" to "$refOrigin/",
                "Origin" to refOrigin,
            )
            if (cookieVal.isNotEmpty()) {
                if (headerKey.isNotEmpty() && HEADER_TOKEN_REGEX.matches(headerKey)) {
                    headers[headerKey] = cookieVal
                }
                headers["Cookie"] = cookieVal
            }
            return headers
        }

        internal fun parseQualityHeights(rawRes: String?): List<Int> {
            if (rawRes.isNullOrBlank()) return emptyList()
            val valid = listOf(2160, 1440, 1080, 720, 480, 360, 240)
            val out = mutableListOf<Int>()
            for (token in rawRes.split(',')) {
                val cleaned = token.trim()
                val match = valid.firstOrNull { q -> q.toString() in cleaned }
                if (match != null && match !in out) {
                    out += match
                }
            }
            return out.sortedDescending()
        }

        private fun classifyNativeFailure(status: Int, errName: String?): String = when (status) {
            403 -> "native_403_region_blocked"
            401, 441 -> "native_401_unauthorized"
            407 -> "native_407_signature_rejected"
            429 -> "native_429_rate_limited"
            in 500..599 -> "native_${status}_upstream_error"
            -1 -> "native_transport_${errName ?: "error"}"
            else -> "native_http_$status"
        }

        private fun JSONObject.optCleanString(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val raw = opt(key)?.toString()?.trim() ?: return null
            if (raw.isEmpty() || raw.equals("null", ignoreCase = true) || raw.equals("None", ignoreCase = true)) {
                return null
            }
            return raw
        }
    }
}
