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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
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
                    if (parsed.sources.isEmpty() && !token.isNullOrBlank()) {
                        val probeSummary = runCatching {
                            probeNativeResourceRoute(
                                subjectId = cleanId,
                                seasonNumber = seasonNumber,
                                episodeNumber = episodeNumber,
                                bearerToken = token,
                                preferredBaseUrl = cleanBase,
                            )
                        }.getOrNull()
                        if (probeSummary != null && probeSummary.recoveredSources.isNotEmpty()) {
                            return@withContext parsed.copy(sources = probeSummary.recoveredSources)
                        }
                    }
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

    /**
     * Diagnostic-only probe of `/wefeed-mobile-bff/subject-api/resource` plus one bounded
     * `Range: bytes=0-1023` fetch when a non-stub URL is returned. Never mutates or replaces
     * production playback sources; records only sanitized metadata (no queries/tokens/cookies).
     */
    internal suspend fun probeNativeResourceRoute(
        subjectId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        bearerToken: String?,
        preferredBaseUrl: String? = null,
    ): ResourceProbeSummary? = withContext(Dispatchers.IO) {
        val cleanId = subjectId.trim()
        if (cleanId.isEmpty()) return@withContext null
        var activeToken = bearerToken?.takeIf { it.isNotBlank() } ?: getOrBootstrapToken(forceRefresh = false)
        if (activeToken.isNullOrBlank()) return@withContext null

        val candidateBases = buildList {
            preferredBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { add(it) }
            nativeApiBaseUrls.map { it.trim().trimEnd('/') }
                .firstOrNull { it.contains("api3.aoneroom.com") }
                ?.let { if (it !in this) add(it) }
            if (isEmpty()) {
                nativeApiBaseUrls.firstOrNull()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }.take(2)

        var lastSummary: ResourceProbeSummary? = null

        for (cleanBase in candidateBases) {
            val baseHttpUrl = cleanBase.toHttpUrlOrNull() ?: continue
            val apiHost = baseHttpUrl.host

            val variants = listOf(
                ResourceProbeVariant(
                    name = "official",
                    playMode = PLAY_MODE_DOWNLOAD,
                    perPage = 8,
                    resolution = 0,
                    includePagingExtras = true,
                ),
                ResourceProbeVariant(
                    name = "r720_stream",
                    playMode = PLAY_MODE_STREAM,
                    perPage = 10,
                    resolution = 720,
                    includePagingExtras = false,
                ),
            )

            for (variant in variants) {
                val urlBuilder = baseHttpUrl.newBuilder()
                    .addPathSegments("wefeed-mobile-bff/subject-api/resource")
                    .addQueryParameter("host", apiHost)
                    .addQueryParameter("subjectId", cleanId)
                    .addQueryParameter("page", "1")
                    .addQueryParameter("perPage", variant.perPage.toString())
                    .addQueryParameter("se", seasonNumber.toString())
                    .addQueryParameter("epFrom", episodeNumber.toString())
                    .addQueryParameter("epTo", episodeNumber.toString())
                if (variant.includePagingExtras) {
                    urlBuilder
                        .addQueryParameter("all", "0")
                        .addQueryParameter("startPosition", "1")
                        .addQueryParameter("endPosition", "1")
                        .addQueryParameter("pagerMode", "0")
                        .addQueryParameter("resolution", variant.resolution.toString())
                } else {
                    urlBuilder
                        .addQueryParameter("resolution", variant.resolution.toString())
                        .addQueryParameter("ep", episodeNumber.toString())
                }
                val probeUrl = urlBuilder.build()

                var resp = executeSignedGet(probeUrl, activeToken, playMode = variant.playMode)
                if (resp.statusCode in setOf(401, 441)) {
                    val refreshed = getOrBootstrapToken(forceRefresh = true, staleToken = activeToken)
                    if (!refreshed.isNullOrBlank()) {
                        activeToken = refreshed
                        resp = executeSignedGet(probeUrl, activeToken, playMode = variant.playMode)
                    }
                }

                val parsed = if (resp.statusCode == 200 && !resp.body.isNullOrBlank()) {
                    parseResourceListResponse(resp.body)
                } else {
                    null
                }
                val bestItem = parsed?.items?.firstOrNull { !it.isStub } ?: parsed?.items?.firstOrNull()
                val bestHttpUrl = bestItem?.url?.toHttpUrlOrNull()
                val safeHost = bestHttpUrl?.host ?: "none"
                val safePath = bestHttpUrl?.let { sanitizePathForDiagnostics(it.encodedPath) } ?: "none"
                val nonStubCount = parsed?.items?.count { !it.isStub } ?: 0
                val distinctHosts = parsed?.items
                    ?.mapNotNull { it.url.toHttpUrlOrNull()?.host }
                    ?.distinct()
                    ?.joinToString("|")
                    ?.ifEmpty { "none" }
                    ?: "none"
                val safeResId = bestItem?.resourceId
                    ?.takeIf { it.isNotBlank() && it.length <= 32 }
                    ?: "none"

                val failureCat = if (resp.statusCode != 200) {
                    classifyNativeFailure(resp.statusCode, resp.errorName)
                } else if (parsed == null) {
                    "resource_parse_error"
                } else if (parsed.apiCode != null && parsed.apiCode != 0) {
                    "resource_api_code_${parsed.apiCode}"
                } else if (nonStubCount == 0) {
                    if (parsed.items.isEmpty()) "resource_empty_list" else "resource_stub_only"
                } else {
                    null
                }

                val note = buildString {
                    append("endpoint=/wefeed-mobile-bff/subject-api/resource")
                    append(" variant=${variant.name}")
                    append(" apiCode=${parsed?.apiCode ?: -1}")
                    append(" items=${parsed?.items?.size ?: 0}")
                    append(" nonStub=$nonStubCount")
                    append(" urlHost=$safeHost")
                    append(" urlPath=$safePath")
                    append(" resId=$safeResId")
                    append(" res=${bestItem?.resolution ?: 0}")
                    append(" size=${bestItem?.sizeBytes ?: 0L}")
                    append(" dur=${bestItem?.durationSec ?: 0L}")
                    append(" linkType=${bestItem?.linkType ?: -1}")
                    append(" stub=${bestItem?.isStub ?: false}")
                    append(" cdnHosts=$distinctHosts")
                    resp.errorName?.let { append(" err=$it") }
                }

                diagnosticsStore.recordLifecycle(
                    MediaLifecycleEvent(
                        timestampMs = clockMs(),
                        kind = MediaRequestKind.PLAYBACK,
                        stage = "NATIVE_RESOURCE_PROBE",
                        host = apiHost,
                        statusCode = resp.statusCode,
                        refreshRequested = false,
                        failureCategory = failureCat,
                        note = note,
                    )
                )
                logger("native resource probe host=$apiHost status=${resp.statusCode} $note")

                var rangeResult: ResourceRangeProbeResult? = null
                if (bestItem != null && !bestItem.isStub && bestHttpUrl != null) {
                    val cookieCandidate = resp.setCookies
                        .mapNotNull { it.substringBefore(';').trim().takeIf(String::isNotEmpty) }
                        .joinToString("; ")
                        .takeIf { it.isNotEmpty() }
                    rangeResult = executeBoundedRangeProbe(bestHttpUrl, fallbackCookie = cookieCandidate)
                }

                val summary = ResourceProbeSummary(
                    apiHost = apiHost,
                    variant = variant.name,
                    httpStatus = resp.statusCode,
                    totalItems = parsed?.items?.size ?: 0,
                    nonStubItems = nonStubCount,
                    selectedHost = bestHttpUrl?.host,
                    selectedSanitizedPath = bestHttpUrl?.let { sanitizePathForDiagnostics(it.encodedPath) },
                    rangeProbe = rangeResult,
                )
                lastSummary = summary

                if (rangeResult != null || (resp.statusCode == 200 && nonStubCount > 0)) {
                    return@withContext summary
                }
                if (resp.statusCode == 403 || resp.statusCode == -1) {
                    break
                }
            }
        }

        val recoveryHost = candidateBases.firstOrNull { base ->
            // Prefer a host that did not fail with 403/transport error if possible; fallback to first.
            lastSummary?.apiHost != base.toHttpUrlOrNull()?.host || (lastSummary?.httpStatus != 403 && lastSummary?.httpStatus != -1)
        } ?: candidateBases.firstOrNull()
        if (recoveryHost != null) {
            val recoverySummary = probeV2AndRecoveryDiagnostics(
                apiBase = recoveryHost,
                subjectId = cleanId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                fallbackToken = activeToken,
            )
            if (recoverySummary != null) {
                return@withContext recoverySummary
            }
        }
        lastSummary
    }

    private suspend fun probeV2AndRecoveryDiagnostics(
        apiBase: String,
        subjectId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        fallbackToken: String?,
    ): ResourceProbeSummary? {
        val cleanBase = apiBase.trim().trimEnd('/')
        val baseHttpUrl = cleanBase.toHttpUrlOrNull() ?: return null
        val apiHost = baseHttpUrl.host
        val visitorToken = bootstrapVisitorLoginToken()
        val diagToken = visitorToken ?: fallbackToken
        val stubCandidates = mutableListOf<Pair<String, String>>()

        // 1. play-info/v2 (omitting se/ep for movies, se==0 && ep==0)
        val playV2Builder = baseHttpUrl.newBuilder()
            .addPathSegments("wefeed-mobile-bff/subject-api/play-info/v2")
            .addQueryParameter("subjectId", subjectId)
        val isMovie = seasonNumber == 0 && episodeNumber == 0
        if (!isMovie) {
            playV2Builder
                .addQueryParameter("se", seasonNumber.toString())
                .addQueryParameter("ep", episodeNumber.toString())
        }
        val playV2Resp = executeSignedRequest(
            method = "GET",
            url = playV2Builder.build(),
            bearerToken = diagToken,
            playMode = PLAY_MODE_STREAM,
            useV4Profile = true,
        )
        val playV2Summary = evaluateAndRecordDiagnosticResponse(
            apiBaseUrl = cleanBase,
            apiHost = apiHost,
            variant = if (isMovie) "play_info_v2_no_se_ep" else "play_info_v2",
            endpointPath = "/wefeed-mobile-bff/subject-api/play-info/v2",
            resp = playV2Resp,
            arrayKeys = listOf("streams", "streamList"),
            stubCollector = stubCandidates,
            allowPolicyExtraction = true,
            collectPlaybackSources = true,
        )
        if (playV2Summary != null && (playV2Summary.recoveredSources.isNotEmpty() || playV2Summary.rangeProbe != null || playV2Summary.nonStubItems > 0)) {
            return playV2Summary
        }

        // 2. resource/v2 with exact MovieBox-Tui scoped params (including se=0&epFrom=0&epTo=0 for movies)
        val resV2Url = baseHttpUrl.newBuilder()
            .addPathSegments("wefeed-mobile-bff/subject-api/resource/v2")
            .addQueryParameter("subjectId", subjectId)
            .addQueryParameter("page", "1")
            .addQueryParameter("perPage", "20")
            .addQueryParameter("all", "0")
            .addQueryParameter("startPosition", "1")
            .addQueryParameter("endPosition", "1")
            .addQueryParameter("pagerMode", "2")
            .addQueryParameter("resolution", "0")
            .addQueryParameter("se", seasonNumber.toString())
            .addQueryParameter("epFrom", episodeNumber.toString())
            .addQueryParameter("epTo", episodeNumber.toString())
            .addQueryParameter("isVip", "true")
            .build()
        val resV2Resp = executeSignedRequest(
            method = "GET",
            url = resV2Url,
            bearerToken = diagToken,
            playMode = PLAY_MODE_DOWNLOAD,
            useV4Profile = true,
        )
        val resV2Collector = mutableListOf<Pair<String, String>>()
        val resV2Summary = evaluateAndRecordDiagnosticResponse(
            apiBaseUrl = cleanBase,
            apiHost = apiHost,
            variant = "resource_v2_vip",
            endpointPath = "/wefeed-mobile-bff/subject-api/resource/v2",
            resp = resV2Resp,
            arrayKeys = listOf("list", "items", "resources"),
            stubCollector = resV2Collector,
            allowPolicyExtraction = false,
            collectPlaybackSources = true,
        )
        if (resV2Summary != null && (resV2Summary.recoveredSources.isNotEmpty() || resV2Summary.rangeProbe != null || resV2Summary.nonStubItems > 0)) {
            return resV2Summary
        }

        // Prefer resource/v2 resourceIds over play-info streamIds (play-info IDs return 404 on resource-position).
        val targetStub = resV2Collector.firstOrNull() ?: stubCandidates.firstOrNull() ?: return null
        val (stubRid, stubUrl) = targetStub

        // 3. resource-position/v2 (isVip=true) and resource-position (v1) trap-clip recovery
        val posV2Url = baseHttpUrl.newBuilder()
            .addPathSegments("wefeed-mobile-bff/subject-api/resource-position/v2")
            .addQueryParameter("subjectId", subjectId)
            .addQueryParameter("resourceId", stubRid)
            .addQueryParameter("failUrl", stubUrl)
            .addQueryParameter("failCode", "404")
            .addQueryParameter("resourceNum", "0")
            .addQueryParameter("isVip", "true")
            .build()
        val posV2Resp = executeSignedRequest(
            method = "GET",
            url = posV2Url,
            bearerToken = diagToken,
            playMode = PLAY_MODE_DOWNLOAD,
            useV4Profile = true,
        )
        val posV2Summary = evaluateAndRecordDiagnosticResponse(
            apiBaseUrl = cleanBase,
            apiHost = apiHost,
            variant = "resource_position_v2_vip",
            endpointPath = "/wefeed-mobile-bff/subject-api/resource-position/v2",
            resp = posV2Resp,
            arrayKeys = listOf("list", "items", "resources"),
            stubCollector = null,
            allowPolicyExtraction = false,
            collectPlaybackSources = true,
        )
        if (posV2Summary != null && (posV2Summary.recoveredSources.isNotEmpty() || posV2Summary.rangeProbe != null || posV2Summary.nonStubItems > 0)) {
            return posV2Summary
        }

        val posV1Url = baseHttpUrl.newBuilder()
            .addPathSegments("wefeed-mobile-bff/subject-api/resource-position")
            .addQueryParameter("subjectId", subjectId)
            .addQueryParameter("resourceId", stubRid)
            .addQueryParameter("failUrl", stubUrl)
            .addQueryParameter("failCode", "404")
            .addQueryParameter("resourceNum", "0")
            .build()
        val posV1Resp = executeSignedRequest(
            method = "GET",
            url = posV1Url,
            bearerToken = diagToken,
            playMode = PLAY_MODE_DOWNLOAD,
            useV4Profile = false,
        )
        val posV1Summary = evaluateAndRecordDiagnosticResponse(
            apiBaseUrl = cleanBase,
            apiHost = apiHost,
            variant = "resource_position_v1",
            endpointPath = "/wefeed-mobile-bff/subject-api/resource-position",
            resp = posV1Resp,
            arrayKeys = listOf("list", "items", "resources"),
            stubCollector = null,
            allowPolicyExtraction = false,
            collectPlaybackSources = true,
        )
        if (posV1Summary != null && (posV1Summary.recoveredSources.isNotEmpty() || posV1Summary.rangeProbe != null || posV1Summary.nonStubItems > 0)) {
            return posV1Summary
        }
        return null
    }

    private fun bootstrapVisitorLoginToken(): String? {
        val bootBase = bootstrapBaseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
        val loginUrl = bootBase.newBuilder()
            .addPathSegments("wefeed-mobile-bff/user-api/visitor-login")
            .build()
        val resp = executeSignedRequest(
            method = "POST",
            url = loginUrl,
            bearerToken = null,
            playMode = PLAY_MODE_STREAM,
            bodyJson = "{}",
            useV4Profile = true,
        )
        val token = if (resp.statusCode == 200 && !resp.body.isNullOrBlank()) {
            runCatching {
                JSONObject(resp.body).optJSONObject("data")?.optCleanString("token")
            }.getOrNull()
        } else {
            null
        }
        val apiCode = if (!resp.body.isNullOrBlank()) {
            runCatching { JSONObject(resp.body).optInt("code", -1) }.getOrNull()
        } else {
            null
        }
        diagnosticsStore.recordLifecycle(
            MediaLifecycleEvent(
                timestampMs = clockMs(),
                kind = MediaRequestKind.PLAYBACK,
                stage = "NATIVE_VISITOR_LOGIN",
                host = bootBase.host,
                statusCode = resp.statusCode,
                refreshRequested = false,
                failureCategory = if (token == null) classifyNativeFailure(resp.statusCode, resp.errorName) else null,
                note = "apiCode=${apiCode ?: -1} hasToken=${!token.isNullOrBlank()}" + (resp.errorName?.let { " err=$it" } ?: ""),
            )
        )
        logger("native visitor-login host=${bootBase.host} status=${resp.statusCode} hasToken=${!token.isNullOrBlank()}")
        return token
    }

    private fun evaluateAndRecordDiagnosticResponse(
        apiBaseUrl: String,
        apiHost: String,
        variant: String,
        endpointPath: String,
        resp: NativeHttpResponse,
        arrayKeys: List<String>,
        stubCollector: MutableList<Pair<String, String>>?,
        allowPolicyExtraction: Boolean,
        collectPlaybackSources: Boolean = false,
    ): ResourceProbeSummary? {
        val parsed = if (resp.statusCode == 200 && !resp.body.isNullOrBlank()) {
            parseResourceListResponse(
                rawBody = resp.body,
                arrayKeys = arrayKeys,
                allowPolicyExtraction = allowPolicyExtraction,
            )
        } else {
            null
        }
        if (stubCollector != null && parsed != null) {
            parsed.items.filter { it.isStub && !it.resourceId.isNullOrBlank() }
                .forEach { stubCollector += it.resourceId!! to it.url }
        }

        val bestItem = parsed?.items?.firstOrNull { !it.isStub } ?: parsed?.items?.firstOrNull()
        val bestHttpUrl = bestItem?.url?.toHttpUrlOrNull()
        val safeHost = bestHttpUrl?.host ?: "none"
        val safePath = bestHttpUrl?.let { sanitizePathForDiagnostics(it.encodedPath) } ?: "none"
        val nonStubCount = parsed?.items?.count { !it.isStub } ?: 0
        val distinctHosts = parsed?.items
            ?.mapNotNull { it.url.toHttpUrlOrNull()?.host }
            ?.distinct()
            ?.joinToString("|")
            ?.ifEmpty { "none" }
            ?: "none"
        val safeResId = bestItem?.resourceId
            ?.takeIf { it.isNotBlank() && it.length <= 32 }
            ?: "none"

        val failureCat = if (resp.statusCode != 200) {
            classifyNativeFailure(resp.statusCode, resp.errorName)
        } else if (parsed == null) {
            "resource_parse_error"
        } else if (parsed.apiCode != null && parsed.apiCode != 0) {
            "resource_api_code_${parsed.apiCode}"
        } else if (nonStubCount == 0) {
            if (parsed.items.isEmpty()) "resource_empty_list" else "resource_stub_only"
        } else {
            null
        }

        val note = buildString {
            append("endpoint=$endpointPath")
            append(" variant=$variant")
            append(" apiCode=${parsed?.apiCode ?: -1}")
            append(" items=${parsed?.items?.size ?: 0}")
            append(" nonStub=$nonStubCount")
            append(" urlHost=$safeHost")
            append(" urlPath=$safePath")
            append(" resId=$safeResId")
            append(" res=${bestItem?.resolution ?: 0}")
            append(" size=${bestItem?.sizeBytes ?: 0L}")
            append(" dur=${bestItem?.durationSec ?: 0L}")
            append(" linkType=${bestItem?.linkType ?: -1}")
            append(" stub=${bestItem?.isStub ?: false}")
            append(" cdnHosts=$distinctHosts")
            resp.errorName?.let { append(" err=$it") }
        }

        diagnosticsStore.recordLifecycle(
            MediaLifecycleEvent(
                timestampMs = clockMs(),
                kind = MediaRequestKind.PLAYBACK,
                stage = "NATIVE_RESOURCE_PROBE",
                host = apiHost,
                statusCode = resp.statusCode,
                refreshRequested = false,
                failureCategory = failureCat,
                note = note,
            )
        )
        logger("native resource probe host=$apiHost status=${resp.statusCode} $note")

        var rangeResult: ResourceRangeProbeResult? = null
        if (bestItem != null && !bestItem.isStub && bestHttpUrl != null) {
            val itemCookie = bestItem.signCookie?.takeIf { it.isNotBlank() }
            val respCookie = resp.setCookies
                .mapNotNull { it.substringBefore(';').trim().takeIf(String::isNotEmpty) }
                .joinToString("; ")
                .takeIf { it.isNotEmpty() }
            rangeResult = executeBoundedRangeProbe(bestHttpUrl, fallbackCookie = itemCookie ?: respCookie)
        }

        val recovered = if (collectPlaybackSources && parsed != null) {
            val seenUrls = hashSetOf<String>()
            parsed.items.asSequence()
                .filter { !it.isStub && it.url.startsWith("http") && seenUrls.add(it.url) }
                .mapIndexed { idx, item ->
                    val lower = item.url.lowercase(Locale.US)
                    val format = when {
                        ".m3u8" in lower -> "HLS"
                        ".mpd" in lower || !extractPolicyResource(item.signCookie).isNullOrBlank() -> "DASH"
                        else -> "MP4"
                    }
                    val isAdaptive = format == "DASH" || format == "HLS"
                    val qHeight = item.resolution
                    val qLabel = when {
                        qHeight > 0 -> "${qHeight}p"
                        isAdaptive -> "Adaptive"
                        else -> "Default"
                    }
                    val sizeMb = if (!isAdaptive && item.sizeBytes > 0L) {
                        item.sizeBytes / (1024.0 * 1024.0)
                    } else {
                        null
                    }
                    PlaybackSource(
                        id = "mb_rec_${idx}_${qHeight}_${format.lowercase(Locale.US)}",
                        url = item.url,
                        format = format,
                        qualityLabel = qLabel,
                        qualityHeight = qHeight,
                        sizeMb = sizeMb,
                        durationSeconds = item.durationSec.takeIf { it > 0L },
                        headers = buildStreamHeaders(apiBaseUrl, item.url, null, item.signCookie),
                        isAuthorised = true,
                        providerName = "Native Stream",
                    )
                }
                .toList()
        } else {
            emptyList()
        }

        return ResourceProbeSummary(
            apiHost = apiHost,
            variant = variant,
            httpStatus = resp.statusCode,
            totalItems = parsed?.items?.size ?: 0,
            nonStubItems = nonStubCount,
            selectedHost = bestHttpUrl?.host,
            selectedSanitizedPath = bestHttpUrl?.let { sanitizePathForDiagnostics(it.encodedPath) },
            rangeProbe = rangeResult,
            recoveredSources = recovered,
        )
    }

    private fun parseResourceListResponse(
        rawBody: String,
        arrayKeys: List<String> = listOf("list", "items", "resources"),
        allowPolicyExtraction: Boolean = false,
    ): ParsedResourceList? {
        val root = runCatching { JSONObject(rawBody) }.getOrNull() ?: return null
        val apiCode = if (root.has("code") && !root.isNull("code")) root.optInt("code", -1) else null
        val data = root.optJSONObject("data") ?: return ParsedResourceList(apiCode, emptyList())
        val rawArray = arrayKeys.asSequence()
            .mapNotNull { key -> data.optJSONArray(key) }
            .firstOrNull()
            ?: JSONArray()
        val parsed = mutableListOf<ResourceProbeItem>()
        for (i in 0 until rawArray.length()) {
            val item = rawArray.optJSONObject(i) ?: continue
            val rawUrl = item.optCleanString("resourceLink")
                ?: item.optCleanString("url")
                ?: item.optCleanString("sourceUrl")
                ?: item.optCleanString("downloadUrl")
            val signCookie = item.optCleanString("signCookie")
            val sizeBytes = item.optCleanString("size")?.toLongOrNull() ?: item.optLong("size", 0L)
            val durationSec = item.optLong("duration", 0L)
            val resolution = item.optInt("resolution", 0)
            val resourceId = item.optCleanString("resourceId") ?: item.optCleanString("id")
            val linkType = item.optInt("linkType", -1)
            val rawIsStub = isKnownStubStream(rawUrl, sizeBytes, durationSec)
            val policyUrl = if (allowPolicyExtraction && (rawUrl.isNullOrBlank() || rawIsStub)) {
                extractPolicyResource(signCookie)
            } else {
                null
            }
            val effectiveUrl = policyUrl ?: rawUrl ?: continue
            val isStub = policyUrl == null && rawIsStub
            parsed += ResourceProbeItem(
                url = effectiveUrl,
                resourceId = resourceId,
                resolution = resolution,
                sizeBytes = sizeBytes,
                durationSec = durationSec,
                linkType = linkType,
                isStub = isStub,
                signCookie = signCookie,
            )
        }
        val sorted = parsed.sortedWith(
            compareBy<ResourceProbeItem> { it.isStub }
                .thenByDescending { it.resolution }
                .thenByDescending { it.sizeBytes }
        )
        return ParsedResourceList(apiCode, sorted)
    }

    private fun executeBoundedRangeProbe(
        targetUrl: HttpUrl,
        fallbackCookie: String?,
    ): ResourceRangeProbeResult {
        fun doSingle(cookieHeader: String?): ResourceRangeProbeResult {
            val reqBuilder = Request.Builder()
                .url(targetUrl)
                .get()
                .header("User-Agent", "okhttp/4.9.3")
                .header("Range", RESOURCE_PROBE_RANGE)
            if (!cookieHeader.isNullOrBlank()) {
                reqBuilder.header("Cookie", cookieHeader)
            }
            return try {
                okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val bytesRead = runCatching {
                        resp.body?.source()?.let { src ->
                            val buf = okio.Buffer()
                            var total = 0L
                            while (total < RESOURCE_PROBE_MAX_BYTES) {
                                val n = src.read(buf, RESOURCE_PROBE_MAX_BYTES - total)
                                if (n <= 0L) break
                                total += n
                            }
                            buf.clear()
                            total
                        } ?: 0L
                    }.getOrDefault(0L)
                    val prior = resp.priorResponse
                    val redirectHost = prior?.request?.url?.host?.takeIf { it != targetUrl.host }
                        ?: resp.request.url.host.takeIf { it != targetUrl.host }
                    val redirectStatus = prior?.code
                    val contentRange = resp.header("Content-Range")?.take(64)
                    val contentLength = resp.header("Content-Length")?.take(32)
                    val serverHeader = resp.header("Server")?.take(32)
                    ResourceRangeProbeResult(
                        statusCode = resp.code,
                        finalHost = resp.request.url.host,
                        redirectHost = redirectHost,
                        redirectStatus = redirectStatus,
                        contentRange = contentRange,
                        contentLength = contentLength,
                        serverHeader = serverHeader,
                        bytesRead = bytesRead,
                        cookieRequired = resp.code in setOf(401, 403),
                        cookieApplied = !cookieHeader.isNullOrBlank(),
                    )
                }
            } catch (e: Exception) {
                ResourceRangeProbeResult(
                    statusCode = -1,
                    finalHost = targetUrl.host,
                    errorName = e.javaClass.simpleName,
                )
            }
        }

        var result = doSingle(cookieHeader = null)
        if (result.statusCode in setOf(401, 403) && !fallbackCookie.isNullOrBlank()) {
            val retried = doSingle(cookieHeader = fallbackCookie)
            result = retried.copy(cookieRequired = retried.statusCode in setOf(200, 206))
        }

        val safePath = sanitizePathForDiagnostics(targetUrl.encodedPath)
        val redirectInfo = when {
            result.redirectHost != null && result.redirectStatus != null -> "${result.redirectStatus}->${result.redirectHost}"
            result.redirectHost != null -> result.redirectHost
            else -> "none"
        }
        val valid = result.statusCode in setOf(200, 206) && result.bytesRead > 0L
        val failureCat = if (valid) null else classifyNativeFailure(result.statusCode, result.errorName)
        val note = buildString {
            append("path=$safePath")
            append(" finalHost=${result.finalHost}")
            append(" redirect=$redirectInfo")
            append(" range=$RESOURCE_PROBE_RANGE")
            append(" contentRange=${result.contentRange ?: "none"}")
            append(" contentLength=${result.contentLength ?: "none"}")
            append(" server=${result.serverHeader ?: "none"}")
            append(" bytesRead=${result.bytesRead}")
            append(" cookieRequired=${result.cookieRequired}")
            append(" cookieApplied=${result.cookieApplied}")
            append(" valid=$valid")
            result.errorName?.let { append(" err=$it") }
        }
        diagnosticsStore.recordLifecycle(
            MediaLifecycleEvent(
                timestampMs = clockMs(),
                kind = MediaRequestKind.PLAYBACK,
                stage = "NATIVE_RESOURCE_RANGE",
                host = targetUrl.host,
                statusCode = result.statusCode,
                refreshRequested = result.cookieApplied,
                refreshedCredentialsApplied = if (result.cookieApplied) true else null,
                failureCategory = failureCat,
                note = note,
            )
        )
        logger("native resource range host=${targetUrl.host} status=${result.statusCode} $note")
        return result
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

    private fun executeSignedGet(
        url: HttpUrl,
        bearerToken: String?,
        playMode: String = PLAY_MODE_STREAM,
    ): NativeHttpResponse = executeSignedRequest(
        method = "GET",
        url = url,
        bearerToken = bearerToken,
        playMode = playMode,
        bodyJson = null,
        useV4Profile = false,
    )

    private fun executeSignedRequest(
        method: String,
        url: HttpUrl,
        bearerToken: String?,
        playMode: String = PLAY_MODE_STREAM,
        bodyJson: String? = null,
        useV4Profile: Boolean = false,
    ): NativeHttpResponse {
        val ts = clockMs()
        val accept = "application/json"
        val contentType = "application/json"
        val bodyBytes = bodyJson?.toByteArray(Charsets.UTF_8)
        val clientBuildTag = generateClientBuildTag(ts)
        val clientInfo = buildClientInfoJson(randomDeviceId, playMode, useV4Profile, clientBuildTag)
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", buildUserAgent(useV4Profile))
            .header("Accept", accept)
            .header("Content-Type", contentType)
            .header("Connection", "keep-alive")
            .header("X-Client-Token", generateClientToken(ts))
            .header("X-Tr-Signature", generateTrSignature(method, accept, contentType, url, bodyBytes, ts))
            .header("X-Client-Build", clientBuildTag)
            .header("X-Client-Info", clientInfo)
            .header("X-Client-Status", "0")
            .header("X-Play-Mode", playMode)

        if (useV4Profile) {
            reqBuilder
                .header("X-M-Version", V4_VERSION_NAME)
                .header("X-Sign-Version", "2.0")
        }

        if (!bearerToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $bearerToken")
        }

        if (method.equals("POST", ignoreCase = true)) {
            val payload = (bodyJson ?: "{}").toRequestBody(contentType.toMediaType())
            reqBuilder.post(payload)
        } else {
            reqBuilder.get()
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

    private fun buildClientInfoJson(
        deviceId: String,
        playMode: String = PLAY_MODE_STREAM,
        useV4Profile: Boolean = false,
        clientBuildTag: String? = null,
    ): String {
        val osVersion = runCatching { Build.VERSION.RELEASE }.getOrNull()?.takeIf { it.isNotBlank() } ?: "12"
        val model = runCatching { Build.MODEL }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Android"
        val brand = runCatching { Build.BRAND }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Android"
        val region = Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: "NG"
        val timezone = TimeZone.getDefault().id.takeIf { it.isNotBlank() } ?: "Africa/Lagos"

        val ordered = linkedMapOf<String, Any>(
            "package_name" to PACKAGE_NAME,
            "version_name" to if (useV4Profile) V4_VERSION_NAME else VERSION_NAME,
            "version_code" to if (useV4Profile) V4_VERSION_CODE else VERSION_CODE,
            "os" to "android",
            "os_version" to osVersion,
            "device_id" to deviceId,
            "install_store" to if (useV4Profile) "ps" else "official",
            "brand" to brand,
            "model" to model,
            "system_language" to "en",
            "net" to "NETWORK_WIFI",
            "region" to region,
            "timezone" to timezone,
            "sp_code" to if (useV4Profile) "40401" else "",
            "X-Play-Mode" to playMode,
        )
        if (useV4Profile) {
            ordered["install_ch"] = "ps"
        }
        if (!clientBuildTag.isNullOrBlank()) {
            ordered["X-Client-Build"] = clientBuildTag
        }
        return JSONObject(ordered).toString()
    }

    private fun buildUserAgent(useV4Profile: Boolean = false): String {
        val osVersion = runCatching { Build.VERSION.RELEASE }.getOrNull()?.takeIf { it.isNotBlank() } ?: "12"
        val model = runCatching { Build.MODEL }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Android"
        val region = Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: "NG"
        val vCode = if (useV4Profile) V4_VERSION_CODE else VERSION_CODE
        return "$PACKAGE_NAME/$vCode (Linux; U; Android $osVersion; en_$region; $model; Build/SP1A.210812.016; Cronet/145.0.7582.0)"
    }

    private data class NativeHttpResponse(
        val statusCode: Int,
        val body: String? = null,
        val xUserHeader: String? = null,
        val setCookies: List<String> = emptyList(),
        val errorName: String? = null,
    )

    private data class ResourceProbeVariant(
        val name: String,
        val playMode: String,
        val perPage: Int,
        val resolution: Int,
        val includePagingExtras: Boolean,
    )

    private data class ResourceProbeItem(
        val url: String,
        val resourceId: String?,
        val resolution: Int,
        val sizeBytes: Long,
        val durationSec: Long,
        val linkType: Int,
        val isStub: Boolean,
        val signCookie: String? = null,
    )

    private data class ParsedResourceList(
        val apiCode: Int?,
        val items: List<ResourceProbeItem>,
    )

    internal data class ResourceRangeProbeResult(
        val statusCode: Int,
        val finalHost: String,
        val redirectHost: String? = null,
        val redirectStatus: Int? = null,
        val contentRange: String? = null,
        val contentLength: String? = null,
        val serverHeader: String? = null,
        val bytesRead: Long = 0L,
        val cookieRequired: Boolean = false,
        val cookieApplied: Boolean = false,
        val errorName: String? = null,
    )

    internal data class ResourceProbeSummary(
        val apiHost: String,
        val variant: String,
        val httpStatus: Int,
        val totalItems: Int,
        val nonStubItems: Int,
        val selectedHost: String? = null,
        val selectedSanitizedPath: String? = null,
        val rangeProbe: ResourceRangeProbeResult? = null,
        val recoveredSources: List<PlaybackSource> = emptyList(),
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
        private const val VERSION_CODE = 50020038
        private const val V4_VERSION_NAME = "4.0.02"
        private const val V4_VERSION_CODE = 50020126
        private const val PLAY_MODE_DOWNLOAD = "1"
        private const val PLAY_MODE_STREAM = "2"
        private const val RESOURCE_PROBE_RANGE = "bytes=0-1023"
        private const val RESOURCE_PROBE_MAX_BYTES = 1024L
        private const val STOCK_CERT_SHA256 = "03d4b741632d136bc23e2de4c6adcdb7712f1980b619c183221801730bbd0410"
        private const val SECRET_KEY_DEFAULT_B64 = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="
        private const val REGION_BLOCK_COOLDOWN_MS = 15 * 60_000L

        internal fun sanitizePathForDiagnostics(encodedPath: String): String {
            val trimmed = encodedPath.trim()
            if (trimmed.isEmpty() || trimmed == "/") return "/"
            return trimmed.split('/')
                .filter { it.isNotEmpty() }
                .joinToString("/", prefix = "/") { seg ->
                    val dotIdx = seg.lastIndexOf('.')
                    val base = if (dotIdx > 0) seg.substring(0, dotIdx) else seg
                    val ext = if (dotIdx > 0 && seg.length - dotIdx <= 6) seg.substring(dotIdx) else ""
                    if (base.length > 24 || '=' in base || '+' in base) "<id>$ext" else seg
                }
        }

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

        internal fun generateClientBuildTag(
            timestampMs: Long,
            randPart: Int = SecureRandom().nextInt(1000),
        ): String {
            val boundedRand = randPart.coerceIn(0, 999)
            val stamp = "${timestampMs / 1000}${"%03d".format(Locale.US, timestampMs % 1000)}000$boundedRand"
            val hash = md5Hex((STOCK_CERT_SHA256 + "oneroom" + stamp).toByteArray(Charsets.UTF_8))
            return "$stamp.$hash"
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
                        val rawKey = if (idx >= 0) seg.substring(0, idx) else seg
                        val rawVal = if (idx >= 0) seg.substring(idx + 1) else ""
                        val decKey = runCatching { URLDecoder.decode(rawKey, "UTF-8") }.getOrDefault(rawKey)
                        val decVal = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
                        decKey to decVal
                    }
                    .filter { it.first.isNotEmpty() }
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
            if (
                "notice.mp4" in lower ||
                "b164fbfb4347792950bdfbfb563d39d9" in lower ||
                "80da52722ee7de7a4303290af6783424" in lower ||
                "1c7de0bd3393702d9191801f15f88f8d" in lower ||
                "9a0461bc39da389663bf3dbb17091d3f" in lower
            ) {
                return true
            }
            if (sizeBytes in 800_000L..860_000L) {
                return true
            }
            if (durationSec != null && durationSec in 20L..24L && (sizeBytes <= 0L || sizeBytes <= 2_500_000L)) {
                return true
            }
            if ("macdn.aoneroom.com" in lower || "/other/" in lower) {
                if ((sizeBytes <= 0L && durationSec == null) || sizeBytes in 1..2_500_000L || (durationSec != null && durationSec in 1L..35L)) {
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
