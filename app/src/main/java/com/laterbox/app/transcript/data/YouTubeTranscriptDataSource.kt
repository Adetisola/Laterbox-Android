package com.laterbox.app.transcript.data

import android.content.Context
import android.util.Log
import com.laterbox.app.media.data.adapters.ytdlp.QuickJsHelper
import com.laterbox.app.media.data.adapters.ytdlp.WarmExecuteOutcome
import com.laterbox.app.media.data.adapters.ytdlp.WarmFailureReason
import com.laterbox.app.media.data.adapters.ytdlp.WarmYtDlpServer
import com.laterbox.app.media.data.adapters.ytdlp.YtDlpInitState
import com.laterbox.app.media.data.adapters.ytdlp.YtDlpOptimizer
import com.laterbox.app.media.data.adapters.ytdlp.YtDlpRuntimeManager
import com.laterbox.app.transcript.domain.CaptionTrackCandidate
import com.laterbox.app.transcript.domain.FetchedVideoInfo
import com.laterbox.app.transcript.domain.PlaylistEntrySummary
import com.laterbox.app.transcript.domain.PlaylistResolution
import com.laterbox.app.transcript.domain.TranscriptDiagnosticInfo
import com.laterbox.app.transcript.domain.TranscriptFailureClassification
import com.laterbox.app.transcript.domain.VideoTranscriptStatus
import com.laterbox.app.transcript.domain.YouTubeUrlParser
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong

sealed interface VideoInfoFetchResult {
    data class Success(val info: FetchedVideoInfo) : VideoInfoFetchResult
    data class Failed(
        val status: VideoTranscriptStatus,
        val message: String,
        val retryable: Boolean = false,
        val diagnosticInfo: TranscriptDiagnosticInfo? = null,
        val retryAfterSeconds: Long? = null
    ) : VideoInfoFetchResult
}

sealed interface PlaylistFetchResult {
    data class Success(val playlist: PlaylistResolution) : PlaylistFetchResult
    data class Failed(
        val status: VideoTranscriptStatus,
        val message: String,
        val retryable: Boolean = false
    ) : PlaylistFetchResult
}

class HttpStatusException(
    val code: Int,
    override val message: String,
    val retryAfterSeconds: Long? = null
) : IOException(message)

class InternalTimeoutException(
    val timeoutMs: Long,
    cause: Throwable? = null
) : IOException("Request timed out after ${(timeoutMs / 1000L).coerceAtLeast(1L)}s.", cause)

internal fun describeTranscriptExceptionType(t: Throwable?): String? {
    if (t == null) return null
    return when (t) {
        is InternalTimeoutException -> "InternalTimeoutException"
        is SocketTimeoutException -> "SocketTimeoutException"
        is LaterboxYouTubeTranscriptDataSource.YtDlpExecutionException -> "YtDlpExecutionException"
        is HttpStatusException -> "HttpStatusException"
        is YoutubeDL.CanceledException -> "YoutubeDLCanceledException"
        is YoutubeDLException -> "YoutubeDLException"
        is EOFException -> "EOFException"
        is ConnectException -> "ConnectException"
        is SocketException -> "SocketException"
        is UnknownHostException -> "UnknownHostException"
        is IOException -> "IOException"
        is JSONException -> "JSONException"
        is CancellationException -> "CancellationException"
        else -> {
            val simple = t.javaClass.simpleName
            if (simple.length >= 3) {
                simple
            } else {
                val tail = t.javaClass.name.substringAfterLast('.')
                if (tail.length >= 3) tail else "ExtractionException"
            }
        }
    }
}

interface YouTubeTranscriptDataSource {
    suspend fun fetchVideoInfo(
        videoId: String,
        videoUrl: String,
        processId: String
    ): VideoInfoFetchResult

    suspend fun fetchVideoInfoAlternative(
        videoId: String,
        videoUrl: String,
        processId: String
    ): VideoInfoFetchResult = VideoInfoFetchResult.Failed(
        status = VideoTranscriptStatus.EXTRACTION_ERROR,
        message = "Alternative recovery route is unavailable.",
        retryable = false
    )

    suspend fun fetchPlaylistEntries(
        playlistId: String,
        playlistUrl: String,
        processId: String,
        onPageLoaded: suspend (loadedCount: Int, totalEstimate: Int?) -> Unit
    ): PlaylistFetchResult

    suspend fun fetchCaptionPayload(url: String): Result<String>

    suspend fun fetchNativeSubtitles(
        videoId: String,
        videoUrl: String,
        processId: String,
        preferredLangSpec: String = "en.*,en,.*",
        playerClientOverride: String? = null
    ): VideoInfoFetchResult = VideoInfoFetchResult.Failed(
        status = VideoTranscriptStatus.EXTRACTION_ERROR,
        message = "Native subtitle fallback is unavailable.",
        retryable = false
    )

    fun currentYtDlpVersion(): String? = null

    fun cancelProcess(processId: String)
}

class LaterboxYouTubeTranscriptDataSource(
    private val appContext: Context,
    private val runtimeManager: YtDlpRuntimeManager,
    private val warmServer: WarmYtDlpServer? = null,
    private val okHttpClient: OkHttpClient = defaultHttpClient(),
    private val customExecutor: ((YoutubeDLRequest) -> YoutubeDLResponse)? = null,
    private val metadataTimeoutMs: Long = VIDEO_METADATA_TIMEOUT_MS,
    private val maxStageExtendedTimeoutMs: Long = VIDEO_METADATA_MAX_JS_CHALLENGE_TIMEOUT_MS
) : YouTubeTranscriptDataSource {

    private val tag = "YTTranscriptDataSource"
    private val activeEngineByProcessId = ConcurrentHashMap<String, String>()

    @Volatile
    private var cachedEjsSig: String? = null

    @Volatile
    private var cachedEjsStatus: YtDlpOptimizer.EjsSolverStatus? = null

    @Volatile
    internal var onStandardExecute: ((processId: String, request: YoutubeDLRequest) -> Unit)? = null

    override fun currentYtDlpVersion(): String? {
        return (runtimeManager.ytDlpState.value as? YtDlpInitState.Initialized)?.version
    }

    private fun currentQuickJsInfo() = try {
        QuickJsHelper.inspectQuickJsRuntime(appContext)
    } catch (_: Throwable) {
        null
    }

    private fun currentEjsStatus(): YtDlpOptimizer.EjsSolverStatus? {
        val target = YtDlpOptimizer.getYtDlpBinaryPath() ?: return null
        if (!target.exists()) return null
        val sig = "${target.absolutePath}:${target.lastModified()}"
        if (cachedEjsSig == sig) {
            cachedEjsStatus?.let { return it }
        }
        val status = try {
            YtDlpOptimizer.inspectEjsSolver(target)
        } catch (_: Throwable) {
            null
        }
        if (status != null) {
            cachedEjsSig = sig
            cachedEjsStatus = status
        }
        return status
    }

    private fun summarizeRouteOutcome(label: String, result: VideoInfoFetchResult): String {
        return when (result) {
            is VideoInfoFetchResult.Success -> {
                if (result.info.captionTracks.isNotEmpty()) {
                    "$label:success(${result.info.captionTracks.size}_tracks)"
                } else {
                    val client = result.info.playerClientUsed ?: "default"
                    if (result.info.poTokenSkippedWarning) {
                        "$label:no_tracks(client=$client,po_token_skipped)"
                    } else {
                        "$label:no_tracks(client=$client)"
                    }
                }
            }
            is VideoInfoFetchResult.Failed -> {
                val httpPart = result.diagnosticInfo?.httpStatusCode?.let { "http_$it" }
                val detailPart = httpPart
                    ?: result.diagnosticInfo?.failureClassification
                    ?: result.status.name.lowercase()
                "$label:${result.status.name.lowercase()}($detailPart)"
            }
        }
    }

    private fun VideoInfoFetchResult.withRouteOutcomes(
        primaryRouteOutcome: String? = null,
        watchHtmlOutcome: String? = null,
        recoveryRouteOutcome: String? = null
    ): VideoInfoFetchResult {
        if (this !is VideoInfoFetchResult.Failed) return this
        val existing = diagnosticInfo ?: return this
        return copy(
            diagnosticInfo = existing.copy(
                primaryRouteOutcome = primaryRouteOutcome ?: existing.primaryRouteOutcome,
                watchHtmlOutcome = watchHtmlOutcome ?: existing.watchHtmlOutcome,
                recoveryRouteOutcome = recoveryRouteOutcome ?: existing.recoveryRouteOutcome
            )
        )
    }

    override suspend fun fetchVideoInfo(
        videoId: String,
        videoUrl: String,
        processId: String
    ): VideoInfoFetchResult = withContext(Dispatchers.IO) {
        val canonicalUrl = YouTubeUrlParser.canonicalVideoUrl(videoId)

        // 1. Primary path: Fast JSless inspection via WarmYtDlpServer / embedded yt-dlp
        val ytdlpResult = fetchVideoInfoViaYtDlp(videoId, canonicalUrl, processId)
        val primaryOutcome = summarizeRouteOutcome("primary_jsless", ytdlpResult)
        if (ytdlpResult is VideoInfoFetchResult.Success && ytdlpResult.info.captionTracks.isNotEmpty()) {
            return@withContext ytdlpResult
        }

        // 2. Fast watch HTML fallback via OkHttp before invoking slower QuickJS multi-client path.
        //    Handles uninitialized runtime or missing caption metadata, while NEVER letting an empty
        //    watch-HTML track list mask a real yt-dlp timeout, rate-limit, or access failure.
        val htmlResult = fetchVideoInfoViaWatchHtml(videoId, canonicalUrl)
        val watchOutcome = summarizeRouteOutcome("watch_html", htmlResult)
        if (htmlResult is VideoInfoFetchResult.Success && htmlResult.info.captionTracks.isNotEmpty()) {
            return@withContext htmlResult
        }

        // 3. If yt-dlp skipped client subtitles due to PO token policy on web/default, retry native
        //    yt-dlp subtitle extraction using clients that do not enforce web subtitle PO tokens.
        var recoveryOutcome: String? = null
        if (ytdlpResult is VideoInfoFetchResult.Success &&
            ytdlpResult.info.captionTracks.isEmpty() &&
            ytdlpResult.info.poTokenSkippedWarning
        ) {
            val altNative = fetchNativeSubtitles(
                videoId = videoId,
                videoUrl = canonicalUrl,
                processId = "${processId}_altcli",
                playerClientOverride = "android_vr,tv,web_embedded"
            )
            recoveryOutcome = summarizeRouteOutcome("altcli_native", altNative)
            if (altNative is VideoInfoFetchResult.Success && altNative.info.captionTracks.isNotEmpty()) {
                return@withContext altNative
            }
        }

        val chosen = when {
            ytdlpResult is VideoInfoFetchResult.Success -> ytdlpResult
            htmlResult is VideoInfoFetchResult.Success &&
                ytdlpResult is VideoInfoFetchResult.Failed &&
                ytdlpResult.diagnosticInfo?.stage == "runtime_init" -> htmlResult
            ytdlpResult is VideoInfoFetchResult.Failed &&
                htmlResult is VideoInfoFetchResult.Failed &&
                ytdlpResult.status == VideoTranscriptStatus.EXTRACTION_ERROR &&
                htmlResult.status != VideoTranscriptStatus.NETWORK_ERROR -> htmlResult
            else -> ytdlpResult
        }

        chosen.withRouteOutcomes(
            primaryRouteOutcome = primaryOutcome,
            watchHtmlOutcome = watchOutcome,
            recoveryRouteOutcome = recoveryOutcome
        )
    }

    /**
     * Distinct recovery path invoked only after an initial video metadata failure (e.g., deadline timeout
     * or client failure) or during targeted retry of failed entries. Uses non-web clients with JS runtime
     * and native subtitle metadata without downloading media.
     */
    override suspend fun fetchVideoInfoAlternative(
        videoId: String,
        videoUrl: String,
        processId: String
    ): VideoInfoFetchResult = withContext(Dispatchers.IO) {
        val canonicalUrl = YouTubeUrlParser.canonicalVideoUrl(videoId)
        val altNative = fetchNativeSubtitles(
            videoId = videoId,
            videoUrl = canonicalUrl,
            processId = "${processId}_natrec",
            preferredLangSpec = "en.*,en,.*",
            playerClientOverride = "android_vr,tv,web_embedded"
        )
        val recOutcome = summarizeRouteOutcome("native_recovery", altNative)
        if (altNative is VideoInfoFetchResult.Success && altNative.info.captionTracks.isNotEmpty()) {
            return@withContext altNative
        }
        altNative.withRouteOutcomes(recoveryRouteOutcome = recOutcome)
    }

    override suspend fun fetchNativeSubtitles(
        videoId: String,
        videoUrl: String,
        processId: String,
        preferredLangSpec: String,
        playerClientOverride: String?
    ): VideoInfoFetchResult = withContext(Dispatchers.IO) {
        val canonicalUrl = YouTubeUrlParser.canonicalVideoUrl(videoId)
        val startedAt = System.currentTimeMillis()
        val clientArg = playerClientOverride ?: "default"

        if (customExecutor == null) {
            val initResult = runtimeManager.ensureYtDlpInitialized()
            if (initResult.isFailure) {
                val qjsInfo = currentQuickJsInfo()
                val ejsInfo = currentEjsStatus()
                val initErr = initResult.exceptionOrNull()
                return@withContext VideoInfoFetchResult.Failed(
                    status = VideoTranscriptStatus.EXTRACTION_ERROR,
                    message = "Extractor runtime unavailable: ${TranscriptRedactor.sanitize(initErr?.message, 120)}",
                    retryable = true,
                    diagnosticInfo = TranscriptDiagnosticInfo(
                        videoId = videoId,
                        stage = "runtime_init",
                        ytDlpVersion = currentYtDlpVersion(),
                        playerClient = clientArg,
                        errorCategory = VideoTranscriptStatus.EXTRACTION_ERROR,
                        exceptionType = describeTranscriptExceptionType(initErr),
                        failureClassification = TranscriptFailureClassification.PLAYER_CLIENT_FAILURE,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        sanitizedDetail = TranscriptRedactor.sanitize(initErr?.message),
                        quickJsIdentity = qjsInfo?.identity,
                        quickJsVersion = qjsInfo?.version,
                        ejsSolverAvailable = ejsInfo?.isAvailable,
                        ejsSolverVersion = ejsInfo?.version,
                        isPlayerCachePatched = ejsInfo?.isPlayerCachePatched
                    )
                )
            }
        }

        val tempDir = File(
            appContext.cacheDir,
            "ytdlp_subs_${videoId.take(16)}_${UUID.randomUUID().toString().take(8)}"
        )
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            return@withContext VideoInfoFetchResult.Failed(
                status = VideoTranscriptStatus.EXTRACTION_ERROR,
                message = "Could not create temporary caption workspace.",
                retryable = false,
                diagnosticInfo = TranscriptDiagnosticInfo(
                    videoId = videoId,
                    stage = "native_subtitles",
                    ytDlpVersion = currentYtDlpVersion(),
                    playerClient = clientArg,
                    errorCategory = VideoTranscriptStatus.EXTRACTION_ERROR,
                    failureClassification = TranscriptFailureClassification.CAPTION_DOWNLOAD_FAILURE,
                    sanitizedDetail = "Could not create temporary caption workspace."
                )
            )
        }

        try {
            val request = YoutubeDLRequest(canonicalUrl).apply {
                addOption("--no-playlist")
                addOption("--skip-download")
                addOption("--extractor-retries", "1")
                addOption("--write-subs")
                addOption("--write-auto-subs")
                addOption("--write-info-json")
                addOption("--sub-langs", preferredLangSpec)
                addOption("--sub-format", "json3/vtt/srv3/srv1/ttml")
                addOption("--socket-timeout", "20")
                addOption("--cache-dir", YtDlpOptimizer.getCacheDir(appContext).absolutePath)
                addOption("--paths", tempDir.absolutePath)
                addOption("-o", "%(id)s.%(ext)s")
                addOption("--extractor-args", "youtube:player_client=$clientArg")
                QuickJsHelper.getJsRuntimeArgument(appContext)?.let {
                    addOption("--js-runtimes", it)
                }
            }

            val response = executeCancellable(request, processId, timeoutMs = NATIVE_SUBTITLE_TIMEOUT_MS)
            val infoJsonFile = tempDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".info.json") }
            val subtitleFiles = tempDir.listFiles()
                ?.filter { it.isFile && isSupportedSubtitleExtension(it.extension) }
                .orEmpty()

            val baseInfoResult = if (infoJsonFile != null && infoJsonFile.length() > 0L) {
                parseYtDlpVideoJson(
                    fallbackVideoId = videoId,
                    canonicalUrl = canonicalUrl,
                    jsonStr = infoJsonFile.readText(Charsets.UTF_8),
                    playerClient = clientArg,
                    warnings = response.err
                )
            } else {
                VideoInfoFetchResult.Success(
                    FetchedVideoInfo(
                        videoId = videoId,
                        title = "YouTube Video ($videoId)",
                        url = canonicalUrl,
                        playerClientUsed = clientArg
                    )
                )
            }

            if (baseInfoResult !is VideoInfoFetchResult.Success) {
                return@withContext baseInfoResult
            }

            if (subtitleFiles.isEmpty()) {
                val errText = response.err.orEmpty()
                if (errText.isNotBlank() && looksLikeHardYtDlpError(errText)) {
                    return@withContext mapVideoError(
                        rawError = errText,
                        videoId = videoId,
                        stage = "native_subtitles",
                        playerClient = clientArg,
                        elapsedMs = System.currentTimeMillis() - startedAt
                    )
                }
                return@withContext baseInfoResult
            }

            val mergedTracks = mergeNativeSubtitleFiles(baseInfoResult.info.captionTracks, subtitleFiles, videoId)
            VideoInfoFetchResult.Success(
                baseInfoResult.info.copy(
                    captionTracks = mergedTracks,
                    playerClientUsed = baseInfoResult.info.playerClientUsed ?: clientArg
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            mapVideoException(
                exception = e,
                videoId = videoId,
                stage = "native_subtitles",
                playerClient = clientArg,
                elapsedMs = System.currentTimeMillis() - startedAt,
                timeoutMs = NATIVE_SUBTITLE_TIMEOUT_MS,
                startedAtMillis = startedAt
            )
        } finally {
            try {
                tempDir.deleteRecursively()
            } catch (_: Throwable) {}
        }
    }

    override suspend fun fetchPlaylistEntries(
        playlistId: String,
        playlistUrl: String,
        processId: String,
        onPageLoaded: suspend (loadedCount: Int, totalEstimate: Int?) -> Unit
    ): PlaylistFetchResult = withContext(Dispatchers.IO) {
        val canonicalUrl = YouTubeUrlParser.canonicalPlaylistUrl(playlistId)
        val initOk = customExecutor != null || runtimeManager.ensureYtDlpInitialized().isSuccess
        if (!initOk) {
            return@withContext PlaylistFetchResult.Failed(
                status = VideoTranscriptStatus.EXTRACTION_ERROR,
                message = "Media extractor is not initialized on this device yet.",
                retryable = true
            )
        }

        val collected = mutableListOf<PlaylistEntrySummary>()
        var playlistTitle: String? = null
        var totalEstimate: Int? = null
        var startIndex = 1
        var pageNum = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            pageNum++
            val endIndex = startIndex + PLAYLIST_PAGE_SIZE - 1
            val pageProcessId = "${processId}_p$pageNum"
            val options = mutableListOf(
                "--flat-playlist",
                "--dump-single-json",
                "--yes-playlist",
                "--ignore-errors",
                "--skip-download",
                "--extractor-retries=1",
                "--playlist-items=$startIndex:$endIndex",
                "--socket-timeout=20",
                "--cache-dir=${YtDlpOptimizer.getCacheDir(appContext).absolutePath}"
            )

            val executionResult = try {
                executeYtDlpJson(canonicalUrl, options, pageProcessId, timeoutMs = PLAYLIST_METADATA_TIMEOUT_MS)
            } catch (ce: CancellationException) {
                throw ce
            } catch (te: InternalTimeoutException) {
                if (collected.isNotEmpty()) break
                return@withContext PlaylistFetchResult.Failed(
                    status = VideoTranscriptStatus.TIMEOUT,
                    message = "Timed out while loading playlist entries.",
                    retryable = true
                )
            } catch (ste: SocketTimeoutException) {
                if (collected.isNotEmpty()) break
                return@withContext PlaylistFetchResult.Failed(
                    status = VideoTranscriptStatus.TIMEOUT,
                    message = "Timed out while loading playlist entries.",
                    retryable = true
                )
            } catch (ye: YtDlpExecutionException) {
                if (collected.isNotEmpty()) break
                val isTimeout = ye.cause is InternalTimeoutException ||
                    ye.cause is SocketTimeoutException ||
                    ye.warmFailureReason == WarmFailureReason.DAEMON_INACTIVITY_TIMEOUT ||
                    ye.warmFailureReason == WarmFailureReason.SOCKET_TIMEOUT ||
                    ye.warmFailureReason == WarmFailureReason.OVERALL_DEADLINE_TIMEOUT
                if (isTimeout) {
                    return@withContext PlaylistFetchResult.Failed(
                        status = VideoTranscriptStatus.TIMEOUT,
                        message = "Timed out while loading playlist entries.",
                        retryable = true
                    )
                }
                return@withContext mapPlaylistError(ye.message ?: "Failed to load playlist")
            } catch (e: Exception) {
                if (collected.isNotEmpty()) {
                    // Preserve already fetched pages instead of failing everything
                    break
                }
                return@withContext mapPlaylistError(e.message ?: "Failed to load playlist")
            }

            val jsonOutput = executionResult.output
            if (jsonOutput.isBlank()) {
                if (collected.isNotEmpty()) break
                return@withContext PlaylistFetchResult.Failed(
                    status = VideoTranscriptStatus.UNAVAILABLE,
                    message = "Playlist metadata could not be retrieved.",
                    retryable = true
                )
            }

            val root = try {
                JSONObject(jsonOutput)
            } catch (_: Exception) {
                if (collected.isNotEmpty()) break
                return@withContext PlaylistFetchResult.Failed(
                    status = VideoTranscriptStatus.EXTRACTION_ERROR,
                    message = "Invalid playlist metadata received.",
                    retryable = true
                )
            }

            if (playlistTitle.isNullOrBlank()) {
                playlistTitle = root.optString("title").takeIf { it.isNotBlank() }
            }
            if (totalEstimate == null) {
                val hint = root.optInt("playlist_count", -1).takeIf { it > 0 }
                    ?: root.optInt("n_entries", -1).takeIf { it > 0 }
                totalEstimate = hint
            }

            val entries = root.optJSONArray("entries")
            if (entries == null || entries.length() == 0) {
                break
            }

            val pageEntries = parsePlaylistEntriesPage(entries, startIndex)
            val newEntries = pageEntries.filter { candidate ->
                collected.none { existing -> existing.position == candidate.position && existing.videoId == candidate.videoId }
            }
            collected.addAll(newEntries)
            onPageLoaded(collected.size, totalEstimate)

            // Stop when page returned fewer than requested items, or no new entries, or we reached total estimate
            val reachedEnd = entries.length() < PLAYLIST_PAGE_SIZE ||
                newEntries.isEmpty() ||
                (totalEstimate != null && collected.size >= totalEstimate)
            if (reachedEnd) {
                break
            }

            startIndex += entries.length()
        }

        if (collected.isEmpty()) {
            return@withContext PlaylistFetchResult.Failed(
                status = VideoTranscriptStatus.UNAVAILABLE,
                message = "No videos were found in this playlist, or the playlist is private.",
                retryable = false
            )
        }

        PlaylistFetchResult.Success(
            PlaylistResolution(
                playlistId = playlistId,
                title = playlistTitle ?: "YouTube Playlist",
                sourceUrl = playlistUrl.ifBlank { canonicalUrl },
                totalCountHint = totalEstimate ?: collected.size,
                entries = collected
            )
        )
    }

    override suspend fun fetchCaptionPayload(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            okHttpClient.awaitResponse(request).use { response ->
                val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()?.coerceIn(1L, 30L)
                if (response.code == 429) {
                    return@withContext Result.failure(
                        HttpStatusException(
                            code = 429,
                            message = "YouTube temporarily rate-limited caption requests (HTTP 429).",
                            retryAfterSeconds = retryAfter
                        )
                    )
                }
                if (response.code == 401 || response.code == 403) {
                    return@withContext Result.failure(
                        HttpStatusException(
                            code = response.code,
                            message = "YouTube rejected the caption request (HTTP ${response.code})."
                        )
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        HttpStatusException(
                            code = response.code,
                            message = "Caption request failed (HTTP ${response.code}).",
                            retryAfterSeconds = retryAfter
                        )
                    )
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext Result.failure(IOException("Caption response body was empty."))
                }
                Result.success(body)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun cancelProcess(processId: String) {
        val engine = activeEngineByProcessId[processId]
        when (engine) {
            "warm" -> warmServer?.cancel(processId)
            "standard" -> {
                try {
                    YoutubeDL.getInstance().destroyProcessById(processId)
                } catch (_: Throwable) {}
            }
            else -> {
                if (!processId.endsWith("_std")) {
                    warmServer?.cancel(processId)
                }
                try {
                    YoutubeDL.getInstance().destroyProcessById(processId)
                } catch (_: Throwable) {}
            }
        }
    }

    internal fun buildPrimaryVideoMetadataOptions(): List<String> {
        return listOf(
            "--dump-single-json",
            "--no-playlist",
            "--skip-download",
            "--extractor-retries=1",
            "--socket-timeout=15",
            "--cache-dir=${YtDlpOptimizer.getCacheDir(appContext).absolutePath}",
            "--extractor-args=youtube:player_client=default;player_skip=js;skip=dash,hls,translated_subs"
        )
    }

    internal fun buildAlternativeVideoMetadataOptions(
        playerClients: String = "android_vr,tv,web_embedded"
    ): List<String> {
        return buildList {
            add("--dump-single-json")
            add("--no-playlist")
            add("--skip-download")
            add("--extractor-retries=1")
            add("--socket-timeout=20")
            add("--cache-dir=${YtDlpOptimizer.getCacheDir(appContext).absolutePath}")
            add("--extractor-args=youtube:player_client=$playerClients;skip=dash,hls,translated_subs")
            QuickJsHelper.getJsRuntimeArgument(appContext)?.let {
                add("--js-runtimes=$it")
            }
        }
    }

    private fun shouldFallbackToStandard(reason: WarmFailureReason): Boolean {
        return when (reason) {
            WarmFailureReason.DISABLED,
            WarmFailureReason.RUNTIME_NOT_READY,
            WarmFailureReason.STARTUP_FAILED,
            WarmFailureReason.HANDSHAKE_FAILED,
            WarmFailureReason.HANDSHAKE_TIMEOUT,
            WarmFailureReason.SOCKET_CONNECT_FAILED,
            WarmFailureReason.PREMATURE_EOF,
            WarmFailureReason.PROTOCOL_MISMATCH,
            WarmFailureReason.UNEXPECTED_ERROR -> true
            WarmFailureReason.DAEMON_INACTIVITY_TIMEOUT,
            WarmFailureReason.SOCKET_TIMEOUT,
            WarmFailureReason.OVERALL_DEADLINE_TIMEOUT,
            WarmFailureReason.EXTRACTOR_ERROR -> false
        }
    }

    private fun effectiveStageExtendedBudgetMs(): Long {
        return if (metadataTimeoutMs < VIDEO_METADATA_TIMEOUT_MS) {
            metadataTimeoutMs
        } else {
            maxStageExtendedTimeoutMs.coerceAtLeast(metadataTimeoutMs)
        }
    }

    private suspend fun fetchVideoInfoViaYtDlp(
        videoId: String,
        canonicalUrl: String,
        processId: String
    ): VideoInfoFetchResult {
        val startedAt = System.currentTimeMillis()
        val clientArg = "default"
        if (customExecutor == null) {
            val initResult = runtimeManager.ensureYtDlpInitialized()
            if (initResult.isFailure) {
                val initErr = initResult.exceptionOrNull()
                val qjsInfo = currentQuickJsInfo()
                val ejsInfo = currentEjsStatus()
                return VideoInfoFetchResult.Failed(
                    status = VideoTranscriptStatus.EXTRACTION_ERROR,
                    message = TranscriptRedactor.sanitize(initErr?.message ?: "Extractor runtime unavailable", 180),
                    retryable = true,
                    diagnosticInfo = TranscriptDiagnosticInfo(
                        videoId = videoId,
                        stage = "runtime_init",
                        ytDlpVersion = currentYtDlpVersion(),
                        playerClient = clientArg,
                        errorCategory = VideoTranscriptStatus.EXTRACTION_ERROR,
                        exceptionType = describeTranscriptExceptionType(initErr),
                        failureClassification = TranscriptFailureClassification.PLAYER_CLIENT_FAILURE,
                        startedAtMillis = startedAt,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        timeoutMs = metadataTimeoutMs,
                        sanitizedDetail = TranscriptRedactor.sanitize(initErr?.message),
                        quickJsIdentity = qjsInfo?.identity,
                        quickJsVersion = qjsInfo?.version,
                        ejsSolverAvailable = ejsInfo?.isAvailable,
                        ejsSolverVersion = ejsInfo?.version,
                        isPlayerCachePatched = ejsInfo?.isPlayerCachePatched
                    )
                )
            }
        }

        val options = buildPrimaryVideoMetadataOptions()
        val maxExtendedBudget = effectiveStageExtendedBudgetMs()

        return try {
            val execution = executeYtDlpJson(
                url = canonicalUrl,
                options = options,
                processId = processId,
                timeoutMs = metadataTimeoutMs,
                compactTranscript = true,
                maxStageExtendedTimeoutMs = maxExtendedBudget
            )
            if (execution.fallbackAttempted && execution.fallbackResult == "succeeded") {
                Log.i(
                    tag,
                    "Standard-process fallback recovered metadata for video=$videoId after warm ${execution.warmFailureReason ?: "unavailable"} (elapsed=${System.currentTimeMillis() - startedAt}ms)"
                )
            }
            if (execution.output.isBlank()) {
                val qjsInfo = currentQuickJsInfo()
                val ejsInfo = currentEjsStatus()
                VideoInfoFetchResult.Failed(
                    status = VideoTranscriptStatus.EXTRACTION_ERROR,
                    message = "Empty video metadata response.",
                    retryable = true,
                    diagnosticInfo = TranscriptDiagnosticInfo(
                        videoId = videoId,
                        stage = "video_metadata",
                        ytDlpVersion = currentYtDlpVersion(),
                        playerClient = clientArg,
                        effectivePlayerClient = execution.effectiveClients.joinToString(",").takeIf { it.isNotBlank() },
                        errorCategory = VideoTranscriptStatus.EXTRACTION_ERROR,
                        startedAtMillis = startedAt,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        timeoutMs = metadataTimeoutMs,
                        executionEngine = execution.executionEngine,
                        warmDaemonHealthyAtStart = execution.warmDaemonHealthyAtStart,
                        fallbackAttempted = execution.fallbackAttempted,
                        fallbackResult = execution.fallbackResult,
                        failureClassification = TranscriptFailureClassification.PLAYER_CLIENT_FAILURE,
                        sanitizedDetail = "Empty JSON output from extractor.",
                        quickJsIdentity = qjsInfo?.identity,
                        quickJsVersion = qjsInfo?.version,
                        ejsSolverAvailable = ejsInfo?.isAvailable,
                        ejsSolverVersion = ejsInfo?.version,
                        isPlayerCachePatched = ejsInfo?.isPlayerCachePatched,
                        daemonStartupMs = execution.daemonStartupMs,
                        socketWaitMs = execution.socketWaitMs,
                        extractMs = execution.extractMs,
                        jsChallengesSolved = execution.jsChallengesSolved,
                        heartbeatCount = execution.heartbeatCount,
                        lastKnownStage = execution.lastStage,
                        lastMeaningfulEvent = execution.lastEvent,
                        lastMeaningfulActivityAgeMs = execution.lastEventAgeMs,
                        recentDebugLogs = execution.recentDebugLogs,
                        warningsCount = execution.warningsCount,
                        lastWarning = execution.lastWarning,
                        deadlineExtendedMs = execution.deadlineExtendedMs
                    )
                )
            } else {
                parseYtDlpVideoJson(
                    fallbackVideoId = videoId,
                    canonicalUrl = canonicalUrl,
                    jsonStr = execution.output,
                    playerClient = clientArg,
                    warnings = execution.warnings,
                    effectiveClients = execution.effectiveClients
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            mapVideoException(
                exception = e,
                videoId = videoId,
                stage = "video_metadata",
                playerClient = clientArg,
                elapsedMs = System.currentTimeMillis() - startedAt,
                executionEngine = if (warmServer != null) "warm_daemon" else "standard_process",
                timeoutMs = metadataTimeoutMs,
                startedAtMillis = startedAt
            )
        }
    }

    private data class JsonExecutionOutput(
        val output: String,
        val warnings: String? = null,
        val executionEngine: String = "standard_process",
        val warmDaemonHealthyAtStart: Boolean? = null,
        val fallbackAttempted: Boolean = false,
        val fallbackResult: String? = null,
        val warmFailureReason: WarmFailureReason? = null,
        val effectiveClients: List<String> = emptyList(),
        val daemonStartupMs: Long? = null,
        val socketWaitMs: Long? = null,
        val extractMs: Long? = null,
        val jsChallengesSolved: Int? = null,
        val heartbeatCount: Int? = null,
        val lastStage: String? = null,
        val lastEvent: String? = null,
        val lastEventAgeMs: Long? = null,
        val lastWarning: String? = null,
        val warningsCount: Int? = null,
        val recentDebugLogs: List<String> = emptyList(),
        val deadlineExtendedMs: Long? = null
    )

    internal class YtDlpExecutionException(
        message: String,
        cause: Throwable? = null,
        val executionEngine: String,
        val warmDaemonHealthyAtStart: Boolean? = null,
        val fallbackAttempted: Boolean = false,
        val fallbackResult: String? = null,
        val warmFailureReason: WarmFailureReason? = null,
        val effectiveClients: List<String> = emptyList(),
        val daemonStartupMs: Long? = null,
        val socketWaitMs: Long? = null,
        val extractMs: Long? = null,
        val jsChallengesSolved: Int? = null,
        val heartbeatCount: Int? = null,
        val lastStage: String? = null,
        val lastEvent: String? = null,
        val lastEventAgeMs: Long? = null,
        val lastWarning: String? = null,
        val warningsCount: Int? = null,
        val recentDebugLogs: List<String> = emptyList(),
        val deadlineExtendedMs: Long? = null
    ) : Exception(message, cause)

    private suspend fun executeYtDlpJson(
        url: String,
        options: List<String>,
        processId: String,
        timeoutMs: Long,
        compactTranscript: Boolean = false,
        maxStageExtendedTimeoutMs: Long = timeoutMs
    ): JsonExecutionOutput {
        var warmFailureReason: WarmFailureReason? = null
        var warmHealthyAtStart: Boolean? = null
        var warmFailureDetail: String? = null
        var warmDaemonStartupMs: Long? = null
        var warmSocketWaitMs: Long? = null
        var warmExtractMs: Long? = null
        var warmJsChallenges: Int? = null
        var warmHeartbeats: Int? = null
        var warmClients: List<String> = emptyList()
        var warmLastStage: String? = null
        var warmLastEvent: String? = null
        var warmLastEventAgeMs: Long? = null
        var warmLastWarning: String? = null
        var warmWarningsCount: Int? = null
        var warmRecentLogs: List<String> = emptyList()
        var warmDeadlineExtendedMs: Long? = null

        if (warmServer != null) {
            activeEngineByProcessId[processId] = "warm"
            val warmOutcome = try {
                warmServer.executeDetailed(
                    url = url,
                    options = options,
                    processId = processId,
                    timeoutMs = timeoutMs,
                    compactTranscript = compactTranscript,
                    maxStageExtendedTimeoutMs = maxStageExtendedTimeoutMs
                )
            } finally {
                activeEngineByProcessId.remove(processId, "warm")
            }

            when (warmOutcome) {
                is WarmExecuteOutcome.Success -> {
                    return JsonExecutionOutput(
                        output = warmOutcome.output,
                        warnings = warmOutcome.warnings,
                        executionEngine = "warm_daemon",
                        warmDaemonHealthyAtStart = warmOutcome.warmHealthyAtStart,
                        fallbackAttempted = false,
                        effectiveClients = warmOutcome.effectiveClients,
                        daemonStartupMs = (warmOutcome.startupMs + warmOutcome.handshakeMs).takeIf { it > 0L },
                        socketWaitMs = warmOutcome.socketWaitMs.takeIf { it > 0L },
                        extractMs = warmOutcome.extractMs.takeIf { it > 0L },
                        jsChallengesSolved = warmOutcome.jsChallengesSolved.takeIf { it > 0 },
                        heartbeatCount = warmOutcome.heartbeatCount.takeIf { it > 0 },
                        lastStage = warmOutcome.lastStage,
                        lastEvent = warmOutcome.lastEvent,
                        lastEventAgeMs = warmOutcome.lastEventAgeMs,
                        lastWarning = warmOutcome.lastWarning,
                        warningsCount = warmOutcome.warningsCount.takeIf { it > 0 },
                        recentDebugLogs = warmOutcome.debugLogs,
                        deadlineExtendedMs = warmOutcome.deadlineExtendedMs.takeIf { it > 0L }
                    )
                }
                is WarmExecuteOutcome.ExtractorError -> {
                    // Authoritative extractor error from a healthy daemon: do NOT mask or retry via standard process
                    throw YtDlpExecutionException(
                        message = warmOutcome.message,
                        cause = YoutubeDLException(warmOutcome.message),
                        executionEngine = "warm_daemon",
                        warmDaemonHealthyAtStart = warmOutcome.warmHealthyAtStart,
                        fallbackAttempted = false,
                        fallbackResult = "not_attempted",
                        effectiveClients = warmOutcome.effectiveClients,
                        daemonStartupMs = (warmOutcome.startupMs + warmOutcome.handshakeMs).takeIf { it > 0L },
                        socketWaitMs = warmOutcome.socketWaitMs.takeIf { it > 0L },
                        extractMs = warmOutcome.extractMs.takeIf { it > 0L },
                        jsChallengesSolved = warmOutcome.jsChallengesSolved.takeIf { it > 0 },
                        heartbeatCount = warmOutcome.heartbeatCount.takeIf { it > 0 },
                        lastStage = warmOutcome.lastStage,
                        lastEvent = warmOutcome.lastEvent,
                        lastEventAgeMs = warmOutcome.lastEventAgeMs,
                        lastWarning = warmOutcome.lastWarning,
                        warningsCount = warmOutcome.warningsCount.takeIf { it > 0 },
                        recentDebugLogs = warmOutcome.debugLogs,
                        deadlineExtendedMs = warmOutcome.deadlineExtendedMs.takeIf { it > 0L }
                    )
                }
                is WarmExecuteOutcome.MechanismFailure -> {
                    // Preserve genuine cancellation before ever deciding whether fallback applies
                    currentCoroutineContext().ensureActive()
                    warmHealthyAtStart = warmOutcome.warmHealthyAtStart
                    warmDaemonStartupMs = (warmOutcome.startupMs + warmOutcome.handshakeMs).takeIf { it > 0L }
                    warmSocketWaitMs = warmOutcome.socketWaitMs.takeIf { it > 0L }
                    warmExtractMs = warmOutcome.extractMs.takeIf { it > 0L }
                    warmJsChallenges = warmOutcome.jsChallengesSolved.takeIf { it > 0 }
                    warmHeartbeats = warmOutcome.heartbeatCount.takeIf { it > 0 }
                    warmClients = warmOutcome.effectiveClients
                    warmLastStage = warmOutcome.lastStage
                    warmLastEvent = warmOutcome.lastEvent
                    warmLastEventAgeMs = warmOutcome.lastEventAgeMs
                    warmLastWarning = warmOutcome.lastWarning
                    warmWarningsCount = warmOutcome.warningsCount.takeIf { it > 0 }
                    warmRecentLogs = warmOutcome.debugLogs
                    warmDeadlineExtendedMs = warmOutcome.deadlineExtendedMs.takeIf { it > 0L }

                    if (!shouldFallbackToStandard(warmOutcome.reason)) {
                        // Execution already ran or stalled inside the warm daemon (DAEMON_INACTIVITY_TIMEOUT / OVERALL_DEADLINE_TIMEOUT):
                        // Do NOT burn another 60s running an identical slow process fallback.
                        throw YtDlpExecutionException(
                            message = warmOutcome.detail,
                            cause = warmOutcome.cause ?: SocketTimeoutException(warmOutcome.detail),
                            executionEngine = "warm_daemon",
                            warmDaemonHealthyAtStart = warmOutcome.warmHealthyAtStart,
                            fallbackAttempted = false,
                            fallbackResult = "not_attempted",
                            warmFailureReason = warmOutcome.reason,
                            effectiveClients = warmClients,
                            daemonStartupMs = warmDaemonStartupMs,
                            socketWaitMs = warmSocketWaitMs,
                            extractMs = warmExtractMs,
                            jsChallengesSolved = warmJsChallenges,
                            heartbeatCount = warmHeartbeats,
                            lastStage = warmLastStage,
                            lastEvent = warmLastEvent,
                            lastEventAgeMs = warmLastEventAgeMs,
                            lastWarning = warmLastWarning,
                            warningsCount = warmWarningsCount,
                            recentDebugLogs = warmRecentLogs,
                            deadlineExtendedMs = warmDeadlineExtendedMs
                        )
                    }

                    if (warmOutcome.reason != WarmFailureReason.DISABLED) {
                        warmFailureReason = warmOutcome.reason
                        warmFailureDetail = warmOutcome.detail
                        Log.w(
                            tag,
                            "Warm daemon transport/startup failure (${warmOutcome.reason}): ${TranscriptRedactor.sanitize(warmOutcome.detail, 120)}. Falling back to standard process."
                        )
                    }
                }
            }
        }

        currentCoroutineContext().ensureActive()
        val fallbackAttempted = warmFailureReason != null
        val standardProcessId = if (fallbackAttempted) "${processId}_std" else processId
        val engineLabel = if (fallbackAttempted) "warm_daemon->standard_process" else "standard_process"

        val request = YoutubeDLRequest(url).apply {
            options.forEach { opt ->
                val eqIdx = opt.indexOf('=')
                if (eqIdx > 0 && opt.startsWith("--")) {
                    addOption(opt.substring(0, eqIdx), opt.substring(eqIdx + 1))
                } else {
                    addOption(opt)
                }
            }
        }

        val response = try {
            executeCancellable(request, standardProcessId, timeoutMs)
        } catch (ce: CancellationException) {
            throw ce
        } catch (te: InternalTimeoutException) {
            val msg = if (fallbackAttempted) {
                "Warm daemon failed ($warmFailureReason) and standard process fallback timed out after ${timeoutMs}ms"
            } else {
                te.message ?: "Standard extractor timed out after ${timeoutMs}ms"
            }
            throw YtDlpExecutionException(
                message = msg,
                cause = te,
                executionEngine = engineLabel,
                warmDaemonHealthyAtStart = warmHealthyAtStart,
                fallbackAttempted = fallbackAttempted,
                fallbackResult = if (fallbackAttempted) "timeout" else null,
                warmFailureReason = warmFailureReason,
                effectiveClients = warmClients,
                daemonStartupMs = warmDaemonStartupMs,
                socketWaitMs = warmSocketWaitMs,
                extractMs = warmExtractMs,
                jsChallengesSolved = warmJsChallenges,
                heartbeatCount = warmHeartbeats,
                lastStage = warmLastStage,
                lastEvent = warmLastEvent,
                lastEventAgeMs = warmLastEventAgeMs,
                lastWarning = warmLastWarning,
                warningsCount = warmWarningsCount,
                recentDebugLogs = warmRecentLogs,
                deadlineExtendedMs = warmDeadlineExtendedMs
            )
        } catch (e: Exception) {
            throw YtDlpExecutionException(
                message = e.message ?: "Extractor execution failed",
                cause = e,
                executionEngine = engineLabel,
                warmDaemonHealthyAtStart = warmHealthyAtStart,
                fallbackAttempted = fallbackAttempted,
                fallbackResult = if (fallbackAttempted) "failed" else null,
                warmFailureReason = warmFailureReason,
                effectiveClients = warmClients,
                daemonStartupMs = warmDaemonStartupMs,
                socketWaitMs = warmSocketWaitMs,
                extractMs = warmExtractMs,
                jsChallengesSolved = warmJsChallenges,
                heartbeatCount = warmHeartbeats,
                lastStage = warmLastStage,
                lastEvent = warmLastEvent,
                lastEventAgeMs = warmLastEventAgeMs,
                lastWarning = warmLastWarning,
                warningsCount = warmWarningsCount,
                recentDebugLogs = warmRecentLogs,
                deadlineExtendedMs = warmDeadlineExtendedMs
            )
        }

        if (response.out.isNullOrBlank()) {
            val err = response.err?.takeIf { it.isNotBlank() }
                ?: warmFailureDetail?.takeIf { it.isNotBlank() }
                ?: "Empty response from extractor"
            throw YtDlpExecutionException(
                message = err,
                cause = YoutubeDLException(err),
                executionEngine = engineLabel,
                warmDaemonHealthyAtStart = warmHealthyAtStart,
                fallbackAttempted = fallbackAttempted,
                fallbackResult = if (fallbackAttempted) "failed" else null,
                warmFailureReason = warmFailureReason,
                effectiveClients = warmClients,
                daemonStartupMs = warmDaemonStartupMs,
                socketWaitMs = warmSocketWaitMs,
                extractMs = warmExtractMs,
                jsChallengesSolved = warmJsChallenges,
                heartbeatCount = warmHeartbeats,
                lastStage = warmLastStage,
                lastEvent = warmLastEvent,
                lastEventAgeMs = warmLastEventAgeMs,
                lastWarning = warmLastWarning,
                warningsCount = warmWarningsCount,
                recentDebugLogs = warmRecentLogs,
                deadlineExtendedMs = warmDeadlineExtendedMs
            )
        }
        return JsonExecutionOutput(
            output = response.out,
            warnings = response.err,
            executionEngine = engineLabel,
            warmDaemonHealthyAtStart = warmHealthyAtStart,
            fallbackAttempted = fallbackAttempted,
            fallbackResult = if (fallbackAttempted) "succeeded" else null,
            warmFailureReason = warmFailureReason,
            effectiveClients = warmClients,
            daemonStartupMs = warmDaemonStartupMs,
            socketWaitMs = warmSocketWaitMs,
            extractMs = warmExtractMs,
            jsChallengesSolved = warmJsChallenges,
            heartbeatCount = warmHeartbeats,
            lastStage = warmLastStage,
            lastEvent = warmLastEvent,
            lastEventAgeMs = warmLastEventAgeMs,
            lastWarning = warmLastWarning,
            warningsCount = warmWarningsCount,
            recentDebugLogs = warmRecentLogs,
            deadlineExtendedMs = warmDeadlineExtendedMs
        )
    }

    private suspend fun executeCancellable(
        request: YoutubeDLRequest,
        processId: String,
        timeoutMs: Long
    ): YoutubeDLResponse {
        activeEngineByProcessId[processId] = "standard"
        val parentJob = currentCoroutineContext()[Job]
        val timedOut = AtomicBoolean(false)
        val callerCancelled = AtomicBoolean(false)
        val cleanup = parentJob?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                callerCancelled.set(true)
                cancelProcess(processId)
            }
        }

        return try {
            coroutineScope {
                val deferred = async(Dispatchers.IO) {
                    try {
                        onStandardExecute?.invoke(processId, request)
                        if (customExecutor != null) {
                            customExecutor.invoke(request)
                        } else {
                            YoutubeDL.getInstance().execute(request, processId)
                        }
                    } catch (ce: YoutubeDL.CanceledException) {
                        when {
                            callerCancelled.get() || parentJob?.isCancelled == true ->
                                throw CancellationException("Process $processId cancelled by user", ce)
                            timedOut.get() ->
                                throw InternalTimeoutException(timeoutMs, ce)
                            else ->
                                throw YoutubeDLException("Extractor process was interrupted before finishing.", ce)
                        }
                    }
                }

                val watchdog = launch {
                    delay(timeoutMs)
                    timedOut.set(true)
                    cancelProcess(processId)
                    deferred.cancel(CancellationException("Internal timeout after ${timeoutMs}ms"))
                }

                try {
                    deferred.await()
                } catch (ce: CancellationException) {
                    if (timedOut.get() && !callerCancelled.get() && parentJob?.isActive != false) {
                        throw InternalTimeoutException(timeoutMs, ce)
                    }
                    throw ce
                } finally {
                    watchdog.cancel()
                }
            }
        } finally {
            cleanup?.dispose()
            activeEngineByProcessId.remove(processId, "standard")
        }
    }

    internal fun parseYtDlpVideoJson(
        fallbackVideoId: String,
        canonicalUrl: String,
        jsonStr: String,
        playerClient: String? = null,
        warnings: String? = null,
        effectiveClients: List<String> = emptyList()
    ): VideoInfoFetchResult {
        val root = try {
            JSONObject(jsonStr)
        } catch (_: Exception) {
            return VideoInfoFetchResult.Failed(
                status = VideoTranscriptStatus.EXTRACTION_ERROR,
                message = "Failed to parse video metadata JSON.",
                retryable = true,
                diagnosticInfo = TranscriptDiagnosticInfo(
                    videoId = fallbackVideoId,
                    stage = "metadata_parse",
                    ytDlpVersion = currentYtDlpVersion(),
                    playerClient = playerClient,
                    errorCategory = VideoTranscriptStatus.EXTRACTION_ERROR,
                    failureClassification = TranscriptFailureClassification.PLAYER_CLIENT_FAILURE,
                    sanitizedDetail = "Malformed JSON metadata returned by extractor."
                )
            )
        }

        val videoId = root.optString("id").ifBlank { fallbackVideoId }
        val title = root.optString("title").ifBlank { "YouTube Video ($videoId)" }
        val channel = root.optString("channel").ifBlank { root.optString("uploader") }.takeIf { it.isNotBlank() }
        val durationSec = if (root.has("duration") && !root.isNull("duration")) {
            root.optDouble("duration", -1.0).takeIf { it > 0 }?.roundToLong()
        } else null
        val thumbnail = root.optString("thumbnail").takeIf { it.isNotBlank() }
        val webpageUrl = root.optString("webpage_url").ifBlank { canonicalUrl }

        val parsedClients = mutableListOf<String>()
        root.optJSONArray("_laterbox_clients")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { parsedClients.add(it) }
            }
        }
        val effectiveClientLabel = (effectiveClients + parsedClients)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")
            ?: playerClient

        val tracks = mutableListOf<CaptionTrackCandidate>()
        root.optJSONObject("subtitles")?.let { subsObj ->
            parseYtDlpCaptionObject(subsObj, isAutoGenerated = false, out = tracks)
        }
        root.optJSONObject("automatic_captions")?.let { autoObj ->
            parseYtDlpCaptionObject(autoObj, isAutoGenerated = true, out = tracks)
        }

        val poTokenWarning = warnings?.let { w ->
            val lower = w.lowercase()
            lower.contains("po token") || (lower.contains("skipping") && lower.contains("subs"))
        } == true

        return VideoInfoFetchResult.Success(
            FetchedVideoInfo(
                videoId = videoId,
                title = title,
                url = webpageUrl,
                channel = channel,
                durationSeconds = durationSec,
                thumbnailUrl = thumbnail,
                captionTracks = tracks,
                playerClientUsed = effectiveClientLabel,
                poTokenSkippedWarning = poTokenWarning
            )
        )
    }

    private fun parseYtDlpCaptionObject(
        obj: JSONObject,
        isAutoGenerated: Boolean,
        out: MutableList<CaptionTrackCandidate>
    ) {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val langCode = keys.next() ?: continue
            // Skip live chat pseudo-subtitle track
            if (langCode.equals("live_chat", ignoreCase = true)) continue
            val arr = obj.optJSONArray(langCode) ?: continue
            val formatMap = linkedMapOf<String, String>()
            var langName: String? = null
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val ext = item.optString("ext").lowercase().trim()
                val url = item.optString("url").trim()
                if (langName == null) {
                    langName = item.optString("name").takeIf { it.isNotBlank() }
                }
                if (ext.isNotEmpty() && url.isNotEmpty() && !formatMap.containsKey(ext)) {
                    formatMap[ext] = url
                }
            }
            if (formatMap.isNotEmpty()) {
                out.add(
                    CaptionTrackCandidate(
                        languageCode = langCode,
                        languageName = langName,
                        isAutoGenerated = isAutoGenerated,
                        formatUrls = formatMap
                    )
                )
            }
        }
    }

    private fun mergeNativeSubtitleFiles(
        existingTracks: List<CaptionTrackCandidate>,
        subtitleFiles: List<File>,
        videoId: String
    ): List<CaptionTrackCandidate> {
        val groupedByLang = linkedMapOf<String, MutableMap<String, String>>()
        for (file in subtitleFiles) {
            val content = try {
                file.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
            if (content.isBlank()) continue
            val ext = file.extension.lowercase()
            // Filename format from -o "%(id)s.%(ext)s" is typically "<videoId>.<lang>.<ext>"
            val nameWithoutExt = file.nameWithoutExtension
            val prefix = "$videoId."
            val lang = if (nameWithoutExt.startsWith(prefix)) {
                nameWithoutExt.removePrefix(prefix).ifBlank { "en" }
            } else {
                nameWithoutExt.substringAfterLast('.', "en").ifBlank { "en" }
            }
            groupedByLang.getOrPut(lang) { linkedMapOf() }[ext] = content
        }

        if (groupedByLang.isEmpty()) return existingTracks

        val merged = existingTracks.map { track ->
            val inline = groupedByLang.remove(track.languageCode)
            if (inline != null) {
                track.copy(inlineContentByFormat = track.inlineContentByFormat + inline)
            } else {
                track
            }
        }.toMutableList()

        for ((lang, inlineMap) in groupedByLang) {
            merged.add(
                CaptionTrackCandidate(
                    languageCode = lang,
                    languageName = null,
                    isAutoGenerated = false,
                    formatUrls = emptyMap(),
                    inlineContentByFormat = inlineMap
                )
            )
        }
        return merged
    }

    private fun isSupportedSubtitleExtension(ext: String?): Boolean {
        val norm = ext?.lowercase()?.trim() ?: return false
        return norm in setOf("json3", "vtt", "srv3", "srv1", "ttml", "srt")
    }

    private fun looksLikeHardYtDlpError(stderr: String): Boolean {
        val lower = stderr.lowercase()
        return lower.contains("error:") && (
            lower.contains("private") ||
                lower.contains("unavailable") ||
                lower.contains("429") ||
                lower.contains("403") ||
                lower.contains("sign in") ||
                lower.contains("po token")
            )
    }

    private fun parsePlaylistEntriesPage(entries: JSONArray, startPosition: Int): List<PlaylistEntrySummary> {
        val result = mutableListOf<PlaylistEntrySummary>()
        for (i in 0 until entries.length()) {
            val pos = startPosition + i
            val entry = entries.optJSONObject(i)
            if (entry == null) {
                result.add(
                    PlaylistEntrySummary(
                        position = pos,
                        videoId = "unavailable_$pos",
                        title = "Unavailable video #$pos",
                        url = "",
                        unavailableReason = "Entry was omitted or unavailable in playlist.",
                        unavailabilityStatus = VideoTranscriptStatus.UNAVAILABLE
                    )
                )
                continue
            }

            val id = entry.optString("id").ifBlank { "item_$pos" }
            val rawTitle = entry.optString("title").trim()
            val availability = entry.optString("availability").lowercase().trim()
            val rawUrl = entry.optString("url").ifBlank { entry.optString("webpage_url") }
            val resolvedUrl = when {
                rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
                id.isNotBlank() && !id.startsWith("unavailable_") -> YouTubeUrlParser.canonicalVideoUrl(id)
                else -> ""
            }
            val channel = entry.optString("channel").ifBlank { entry.optString("uploader") }.takeIf { it.isNotBlank() }
            val durationSec = if (entry.has("duration") && !entry.isNull("duration")) {
                entry.optDouble("duration", -1.0).takeIf { it > 0 }?.roundToLong()
            } else null
            val thumbnail = entry.optString("thumbnail").takeIf { it.isNotBlank() }
                ?: entry.optJSONArray("thumbnails")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }

            val unavailability = classifyPlaylistEntryAvailability(rawTitle, availability)
            result.add(
                PlaylistEntrySummary(
                    position = pos,
                    videoId = id,
                    title = rawTitle.ifBlank { "Video $pos ($id)" },
                    url = resolvedUrl,
                    channel = channel,
                    durationSeconds = durationSec,
                    thumbnailUrl = thumbnail,
                    unavailableReason = unavailability?.second,
                    unavailabilityStatus = unavailability?.first
                )
            )
        }
        return result
    }

    private fun classifyPlaylistEntryAvailability(
        title: String,
        availability: String
    ): Pair<VideoTranscriptStatus, String>? {
        val lowerTitle = title.lowercase()
        return when {
            lowerTitle == "[private video]" || availability == "private" ->
                VideoTranscriptStatus.ACCESS_ERROR to "Video is private and could not be accessed."
            lowerTitle == "[deleted video]" || lowerTitle == "[unavailable video]" || availability == "unavailable" ->
                VideoTranscriptStatus.UNAVAILABLE to "Video is deleted or unavailable."
            availability in setOf("needs_auth", "subscriber_only", "premium_only") ->
                VideoTranscriptStatus.ACCESS_ERROR to "Video requires authentication or membership to access."
            else -> null
        }
    }

    private suspend fun fetchVideoInfoViaWatchHtml(
        videoId: String,
        canonicalUrl: String
    ): VideoInfoFetchResult {
        val startedAt = System.currentTimeMillis()
        return try {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder()
                .url("$canonicalUrl&hl=en")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            okHttpClient.awaitResponse(request).use { response ->
                val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()?.coerceIn(1L, 30L)
                if (response.code == 429) {
                    return VideoInfoFetchResult.Failed(
                        status = VideoTranscriptStatus.RATE_LIMITED,
                        message = "YouTube temporarily rate-limited requests (HTTP 429).",
                        retryable = true,
                        retryAfterSeconds = retryAfter,
                        diagnosticInfo = TranscriptDiagnosticInfo(
                            videoId = videoId,
                            stage = "watch_html",
                            playerClient = "html_fallback",
                            errorCategory = VideoTranscriptStatus.RATE_LIMITED,
                            httpStatusCode = 429,
                            startedAtMillis = startedAt,
                            elapsedMs = System.currentTimeMillis() - startedAt,
                            executionEngine = "watch_html",
                            failureClassification = TranscriptFailureClassification.YOUTUBE_HTTP_429
                        )
                    )
                }
                if (response.code == 401 || response.code == 403) {
                    return VideoInfoFetchResult.Failed(
                        status = VideoTranscriptStatus.ACCESS_ERROR,
                        message = "YouTube rejected watch page request (HTTP ${response.code}).",
                        retryable = false,
                        diagnosticInfo = TranscriptDiagnosticInfo(
                            videoId = videoId,
                            stage = "watch_html",
                            playerClient = "html_fallback",
                            errorCategory = VideoTranscriptStatus.ACCESS_ERROR,
                            httpStatusCode = response.code,
                            startedAtMillis = startedAt,
                            elapsedMs = System.currentTimeMillis() - startedAt,
                            executionEngine = "watch_html",
                            failureClassification = TranscriptFailureClassification.YOUTUBE_HTTP_403
                        )
                    )
                }
                if (!response.isSuccessful) {
                    val cls = if (response.code in 500..599) {
                        TranscriptFailureClassification.YOUTUBE_HTTP_5XX
                    } else {
                        TranscriptFailureClassification.PLAYER_CLIENT_FAILURE
                    }
                    return VideoInfoFetchResult.Failed(
                        status = VideoTranscriptStatus.NETWORK_ERROR,
                        message = "Failed to load YouTube page (HTTP ${response.code}).",
                        retryable = true,
                        retryAfterSeconds = retryAfter,
                        diagnosticInfo = TranscriptDiagnosticInfo(
                            videoId = videoId,
                            stage = "watch_html",
                            playerClient = "html_fallback",
                            errorCategory = VideoTranscriptStatus.NETWORK_ERROR,
                            httpStatusCode = response.code,
                            startedAtMillis = startedAt,
                            elapsedMs = System.currentTimeMillis() - startedAt,
                            executionEngine = "watch_html",
                            failureClassification = cls
                        )
                    )
                }

                val html = response.body?.string().orEmpty()
                parseWatchHtmlPlayerResponse(videoId, canonicalUrl, html, startedAt)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ste: SocketTimeoutException) {
            VideoInfoFetchResult.Failed(
                status = VideoTranscriptStatus.TIMEOUT,
                message = "Timed out waiting for YouTube watch page.",
                retryable = true,
                diagnosticInfo = TranscriptDiagnosticInfo(
                    videoId = videoId,
                    stage = "watch_html",
                    playerClient = "html_fallback",
                    errorCategory = VideoTranscriptStatus.TIMEOUT,
                    exceptionType = describeTranscriptExceptionType(ste),
                    startedAtMillis = startedAt,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    executionEngine = "watch_html",
                    failureClassification = TranscriptFailureClassification.YT_DLP_NETWORK_TIMEOUT,
                    sanitizedDetail = TranscriptRedactor.sanitize(ste.message)
                )
            )
        } catch (e: Exception) {
            VideoInfoFetchResult.Failed(
                status = VideoTranscriptStatus.NETWORK_ERROR,
                message = TranscriptRedactor.sanitize(e.message ?: "Network error retrieving YouTube video.", 180),
                retryable = true,
                diagnosticInfo = TranscriptDiagnosticInfo(
                    videoId = videoId,
                    stage = "watch_html",
                    playerClient = "html_fallback",
                    errorCategory = VideoTranscriptStatus.NETWORK_ERROR,
                    exceptionType = describeTranscriptExceptionType(e),
                    startedAtMillis = startedAt,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    executionEngine = "watch_html",
                    failureClassification = TranscriptFailureClassification.YT_DLP_NETWORK_TIMEOUT,
                    sanitizedDetail = TranscriptRedactor.sanitize(e.message)
                )
            )
        }
    }

    internal fun parseWatchHtmlPlayerResponse(
        videoId: String,
        canonicalUrl: String,
        html: String,
        startedAtMillis: Long? = null
    ): VideoInfoFetchResult {
        val jsonStr = extractPlayerResponseJson(html)
            ?: return VideoInfoFetchResult.Failed(
                status = VideoTranscriptStatus.EXTRACTION_ERROR,
                message = "Could not extract video player metadata from YouTube page.",
                retryable = true,
                diagnosticInfo = TranscriptDiagnosticInfo(
                    videoId = videoId,
                    stage = "watch_html",
                    playerClient = "html_fallback",
                    errorCategory = VideoTranscriptStatus.EXTRACTION_ERROR,
                    startedAtMillis = startedAtMillis,
                    elapsedMs = startedAtMillis?.let { System.currentTimeMillis() - it },
                    executionEngine = "watch_html",
                    failureClassification = TranscriptFailureClassification.PLAYER_CLIENT_FAILURE,
                    sanitizedDetail = "Missing ytInitialPlayerResponse in watch HTML."
                )
            )

        val root = try {
            JSONObject(jsonStr)
        } catch (_: Exception) {
            return VideoInfoFetchResult.Failed(
                status = VideoTranscriptStatus.EXTRACTION_ERROR,
                message = "Malformed player metadata from YouTube.",
                retryable = true,
                diagnosticInfo = TranscriptDiagnosticInfo(
                    videoId = videoId,
                    stage = "watch_html",
                    playerClient = "html_fallback",
                    errorCategory = VideoTranscriptStatus.EXTRACTION_ERROR,
                    startedAtMillis = startedAtMillis,
                    elapsedMs = startedAtMillis?.let { System.currentTimeMillis() - it },
                    executionEngine = "watch_html",
                    failureClassification = TranscriptFailureClassification.PLAYER_CLIENT_FAILURE,
                    sanitizedDetail = "Malformed ytInitialPlayerResponse JSON."
                )
            )
        }

        val playability = root.optJSONObject("playabilityStatus")
        val playStatus = playability?.optString("status").orEmpty()
        if (playStatus in setOf("ERROR", "UNPLAYABLE", "LOGIN_REQUIRED")) {
            val reason = playability?.optString("reason").orEmpty().ifBlank { "Video is unavailable ($playStatus)" }
            val lowerReason = reason.lowercase()
            val mappedStatus = when {
                playStatus == "LOGIN_REQUIRED" -> VideoTranscriptStatus.ACCESS_ERROR
                lowerReason.contains("private") ||
                    lowerReason.contains("members") ||
                    lowerReason.contains("age") ||
                    lowerReason.contains("bot") ||
                    lowerReason.contains("sign in") -> VideoTranscriptStatus.ACCESS_ERROR
                else -> VideoTranscriptStatus.UNAVAILABLE
            }
            val cls = when {
                playStatus == "LOGIN_REQUIRED" || lowerReason.contains("bot") || lowerReason.contains("sign in") ->
                    TranscriptFailureClassification.YOUTUBE_VERIFICATION_REQUIRED
                mappedStatus == VideoTranscriptStatus.ACCESS_ERROR ->
                    TranscriptFailureClassification.YOUTUBE_HTTP_403
                else -> TranscriptFailureClassification.VIDEO_UNAVAILABLE
            }
            return VideoInfoFetchResult.Failed(
                status = mappedStatus,
                message = reason,
                retryable = false,
                diagnosticInfo = TranscriptDiagnosticInfo(
                    videoId = videoId,
                    stage = "watch_html",
                    playerClient = "html_fallback",
                    errorCategory = mappedStatus,
                    startedAtMillis = startedAtMillis,
                    elapsedMs = startedAtMillis?.let { System.currentTimeMillis() - it },
                    executionEngine = "watch_html",
                    failureClassification = cls,
                    sanitizedDetail = TranscriptRedactor.sanitize(reason)
                )
            )
        }

        val details = root.optJSONObject("videoDetails")
        val title = details?.optString("title")?.takeIf { it.isNotBlank() } ?: "YouTube Video ($videoId)"
        val channel = details?.optString("author")?.takeIf { it.isNotBlank() }
        val durationSec = details?.optString("lengthSeconds")?.toLongOrNull()?.takeIf { it > 0 }
        val thumbs = details?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumbnail = thumbs?.optJSONObject((thumbs.length() - 1).coerceAtLeast(0))?.optString("url")

        val rawTracks = root.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks")

        val tracks = mutableListOf<CaptionTrackCandidate>()
        if (rawTracks != null) {
            for (i in 0 until rawTracks.length()) {
                val trackObj = rawTracks.optJSONObject(i) ?: continue
                val baseUrl = trackObj.optString("baseUrl").trim()
                val langCode = trackObj.optString("languageCode").trim()
                if (baseUrl.isEmpty() || langCode.isEmpty()) continue
                val isAsr = trackObj.optString("kind").equals("asr", ignoreCase = true)
                val nameObj = trackObj.optJSONObject("name")
                val langName = nameObj?.optString("simpleText")?.takeIf { it.isNotBlank() }
                    ?: nameObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }

                val cleanBase = baseUrl.replace(Regex("&fmt=[^&]+"), "")
                val formats = linkedMapOf(
                    "json3" to "$cleanBase&fmt=json3",
                    "vtt" to "$cleanBase&fmt=vtt",
                    "srv3" to "$cleanBase&fmt=srv3",
                    "srv1" to cleanBase
                )
                tracks.add(
                    CaptionTrackCandidate(
                        languageCode = langCode,
                        languageName = langName,
                        isAutoGenerated = isAsr,
                        formatUrls = formats
                    )
                )
            }
        }

        return VideoInfoFetchResult.Success(
            FetchedVideoInfo(
                videoId = videoId,
                title = title,
                url = canonicalUrl,
                channel = channel,
                durationSeconds = durationSec,
                thumbnailUrl = thumbnail,
                captionTracks = tracks,
                playerClientUsed = "html_fallback"
            )
        )
    }

    internal fun extractPlayerResponseJson(html: String): String? {
        val markers = listOf(
            "var ytInitialPlayerResponse = ",
            "window[\"ytInitialPlayerResponse\"] = ",
            "ytInitialPlayerResponse = "
        )
        for (marker in markers) {
            val startMarker = html.indexOf(marker)
            if (startMarker < 0) continue
            val braceStart = html.indexOf('{', startMarker + marker.length)
            if (braceStart < 0) continue
            val extracted = extractBalancedJsonObject(html, braceStart)
            if (extracted != null) return extracted
        }
        return null
    }

    private fun extractBalancedJsonObject(source: String, openBraceIndex: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openBraceIndex until source.length) {
            val c = source[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return source.substring(openBraceIndex, i + 1)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun formatActivityContext(lastStage: String?, lastEventAgeMs: Long?): String {
        val parts = buildList {
            lastStage?.takeIf { it.isNotBlank() }?.let { add("stage: $it") }
            lastEventAgeMs?.let { add("last activity ${(it / 1000L)}s ago") }
        }
        return if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
    }

    private fun formatTimeoutMessage(
        classification: String,
        lastStage: String? = null,
        lastEventAgeMs: Long? = null,
        budgetMs: Long? = null
    ): String {
        val ctx = formatActivityContext(lastStage, lastEventAgeMs)
        val budgetSecs = budgetMs?.let { (it / 1000L).coerceAtLeast(1L) }
        return when (classification) {
            TranscriptFailureClassification.JS_CHALLENGE_TIMEOUT ->
                "YouTube player challenge did not complete within the extraction timeout$ctx."
            TranscriptFailureClassification.METADATA_OPERATION_DEADLINE ->
                if (budgetSecs != null) {
                    "YouTube metadata extraction stayed active$ctx but did not complete within ${budgetSecs}s."
                } else {
                    "YouTube metadata extraction stayed active$ctx but exceeded its time budget."
                }
            TranscriptFailureClassification.DAEMON_INACTIVITY_TIMEOUT ->
                "Local extractor became unresponsive while retrieving YouTube metadata$ctx."
            TranscriptFailureClassification.DAEMON_TRANSPORT_FAILURE ->
                "Local extractor daemon failed to communicate; fallback extraction did not complete."
            TranscriptFailureClassification.YT_DLP_NETWORK_TIMEOUT ->
                "Timed out waiting for a YouTube network response$ctx."
            else -> "Timed out while retrieving video metadata from YouTube$ctx."
        }
    }

    private fun mapVideoException(
        exception: Exception,
        videoId: String,
        stage: String,
        playerClient: String,
        elapsedMs: Long,
        executionEngine: String? = null,
        warmDaemonHealthyAtStart: Boolean? = null,
        fallbackAttempted: Boolean = false,
        fallbackResult: String? = null,
        timeoutMs: Long? = null,
        startedAtMillis: Long? = null
    ): VideoInfoFetchResult.Failed {
        val qjsInfo = currentQuickJsInfo()
        val ejsInfo = currentEjsStatus()

        if (exception is YtDlpExecutionException) {
            val causeEx = exception.cause as? Exception
            val isTimeout = causeEx is InternalTimeoutException ||
                causeEx is SocketTimeoutException ||
                exception.warmFailureReason == WarmFailureReason.DAEMON_INACTIVITY_TIMEOUT ||
                exception.warmFailureReason == WarmFailureReason.SOCKET_TIMEOUT ||
                exception.warmFailureReason == WarmFailureReason.OVERALL_DEADLINE_TIMEOUT

            val effectiveClientStr = exception.effectiveClients
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(",")
                .takeIf { it.isNotBlank() }

            if (isTimeout) {
                val detail = TranscriptRedactor.sanitize(exception.message ?: "Request timed out", 220)
                val lowerDetail = (exception.message ?: "").lowercase()
                val isWarmTransportReason = exception.warmFailureReason in setOf(
                    WarmFailureReason.STARTUP_FAILED,
                    WarmFailureReason.HANDSHAKE_FAILED,
                    WarmFailureReason.HANDSHAKE_TIMEOUT,
                    WarmFailureReason.SOCKET_CONNECT_FAILED,
                    WarmFailureReason.PREMATURE_EOF,
                    WarmFailureReason.PROTOCOL_MISMATCH
                )
                val classification = when {
                    (exception.jsChallengesSolved ?: 0) > 0 ||
                        exception.lastStage == "js_challenge" ||
                        exception.lastStage == "downloading_player_js" ||
                        lowerDetail.contains("solving js challenge") ||
                        lowerDetail.contains("js challenge") ->
                        TranscriptFailureClassification.JS_CHALLENGE_TIMEOUT
                    isWarmTransportReason ->
                        TranscriptFailureClassification.DAEMON_TRANSPORT_FAILURE
                    exception.warmFailureReason == WarmFailureReason.OVERALL_DEADLINE_TIMEOUT ->
                        TranscriptFailureClassification.METADATA_OPERATION_DEADLINE
                    exception.warmFailureReason == WarmFailureReason.DAEMON_INACTIVITY_TIMEOUT ||
                        exception.warmFailureReason == WarmFailureReason.SOCKET_TIMEOUT ->
                        TranscriptFailureClassification.DAEMON_INACTIVITY_TIMEOUT
                    causeEx is SocketTimeoutException ->
                        TranscriptFailureClassification.YT_DLP_NETWORK_TIMEOUT
                    causeEx is InternalTimeoutException ->
                        TranscriptFailureClassification.METADATA_OPERATION_DEADLINE
                    else -> TranscriptFailureClassification.YT_DLP_NETWORK_TIMEOUT
                }
                val effectiveBudgetMs = timeoutMs?.let { it + (exception.deadlineExtendedMs ?: 0L) }
                return VideoInfoFetchResult.Failed(
                    status = VideoTranscriptStatus.TIMEOUT,
                    message = formatTimeoutMessage(
                        classification = classification,
                        lastStage = exception.lastStage,
                        lastEventAgeMs = exception.lastEventAgeMs,
                        budgetMs = effectiveBudgetMs
                    ),
                    retryable = true,
                    diagnosticInfo = TranscriptDiagnosticInfo(
                        videoId = videoId,
                        stage = stage,
                        ytDlpVersion = currentYtDlpVersion(),
                        playerClient = playerClient,
                        effectivePlayerClient = effectiveClientStr,
                        errorCategory = VideoTranscriptStatus.TIMEOUT,
                        exceptionType = describeTranscriptExceptionType(causeEx ?: exception),
                        elapsedMs = elapsedMs,
                        sanitizedDetail = detail,
                        executionEngine = exception.executionEngine,
                        startedAtMillis = startedAtMillis,
                        timeoutMs = timeoutMs,
                        warmDaemonHealthyAtStart = exception.warmDaemonHealthyAtStart,
                        fallbackAttempted = exception.fallbackAttempted,
                        fallbackResult = exception.fallbackResult,
                        failureClassification = classification,
                        quickJsIdentity = qjsInfo?.identity,
                        quickJsVersion = qjsInfo?.version,
                        ejsSolverAvailable = ejsInfo?.isAvailable,
                        ejsSolverVersion = ejsInfo?.version,
                        isPlayerCachePatched = ejsInfo?.isPlayerCachePatched,
                        daemonStartupMs = exception.daemonStartupMs,
                        socketWaitMs = exception.socketWaitMs,
                        extractMs = exception.extractMs,
                        jsChallengesSolved = exception.jsChallengesSolved,
                        heartbeatCount = exception.heartbeatCount,
                        lastKnownStage = exception.lastStage,
                        lastMeaningfulEvent = exception.lastEvent,
                        lastMeaningfulActivityAgeMs = exception.lastEventAgeMs,
                        recentDebugLogs = exception.recentDebugLogs,
                        warningsCount = exception.warningsCount,
                        lastWarning = exception.lastWarning,
                        deadlineExtendedMs = exception.deadlineExtendedMs
                    )
                )
            }
            val rawErr = causeEx?.message?.takeIf { it.isNotBlank() }
                ?: exception.message
                ?: describeTranscriptExceptionType(causeEx ?: exception)
                ?: "ExtractorError"
            return mapVideoError(
                rawError = rawErr,
                videoId = videoId,
                stage = stage,
                playerClient = playerClient,
                effectivePlayerClient = effectiveClientStr,
                elapsedMs = elapsedMs,
                exceptionType = describeTranscriptExceptionType(causeEx ?: exception),
                executionEngine = exception.executionEngine,
                warmDaemonHealthyAtStart = exception.warmDaemonHealthyAtStart,
                fallbackAttempted = exception.fallbackAttempted,
                fallbackResult = exception.fallbackResult,
                warmFailureReason = exception.warmFailureReason,
                timeoutMs = timeoutMs,
                startedAtMillis = startedAtMillis,
                daemonStartupMs = exception.daemonStartupMs,
                socketWaitMs = exception.socketWaitMs,
                extractMs = exception.extractMs,
                jsChallengesSolved = exception.jsChallengesSolved,
                heartbeatCount = exception.heartbeatCount,
                lastStage = exception.lastStage,
                lastEvent = exception.lastEvent,
                lastEventAgeMs = exception.lastEventAgeMs,
                recentDebugLogs = exception.recentDebugLogs,
                warningsCount = exception.warningsCount,
                lastWarning = exception.lastWarning,
                deadlineExtendedMs = exception.deadlineExtendedMs
            )
        }

        if (exception is InternalTimeoutException || exception is SocketTimeoutException) {
            val sanitized = TranscriptRedactor.sanitize(exception.message ?: "Request timed out", 200)
            val classification = if (exception is SocketTimeoutException) {
                TranscriptFailureClassification.YT_DLP_NETWORK_TIMEOUT
            } else {
                TranscriptFailureClassification.METADATA_OPERATION_DEADLINE
            }
            return VideoInfoFetchResult.Failed(
                status = VideoTranscriptStatus.TIMEOUT,
                message = formatTimeoutMessage(classification = classification, budgetMs = timeoutMs),
                retryable = true,
                diagnosticInfo = TranscriptDiagnosticInfo(
                    videoId = videoId,
                    stage = stage,
                    ytDlpVersion = currentYtDlpVersion(),
                    playerClient = playerClient,
                    errorCategory = VideoTranscriptStatus.TIMEOUT,
                    exceptionType = describeTranscriptExceptionType(exception),
                    elapsedMs = elapsedMs,
                    sanitizedDetail = sanitized,
                    executionEngine = executionEngine,
                    startedAtMillis = startedAtMillis,
                    timeoutMs = timeoutMs,
                    warmDaemonHealthyAtStart = warmDaemonHealthyAtStart,
                    fallbackAttempted = fallbackAttempted,
                    fallbackResult = fallbackResult,
                    failureClassification = classification,
                    quickJsIdentity = qjsInfo?.identity,
                    quickJsVersion = qjsInfo?.version,
                    ejsSolverAvailable = ejsInfo?.isAvailable,
                    ejsSolverVersion = ejsInfo?.version,
                    isPlayerCachePatched = ejsInfo?.isPlayerCachePatched
                )
            )
        }
        return mapVideoError(
            rawError = exception.message ?: describeTranscriptExceptionType(exception) ?: "ExtractionError",
            videoId = videoId,
            stage = stage,
            playerClient = playerClient,
            elapsedMs = elapsedMs,
            exceptionType = describeTranscriptExceptionType(exception),
            executionEngine = executionEngine,
            warmDaemonHealthyAtStart = warmDaemonHealthyAtStart,
            fallbackAttempted = fallbackAttempted,
            fallbackResult = fallbackResult,
            timeoutMs = timeoutMs,
            startedAtMillis = startedAtMillis
        )
    }

    private fun mapVideoError(
        rawError: String,
        videoId: String = "",
        stage: String = "video_metadata",
        playerClient: String? = null,
        effectivePlayerClient: String? = null,
        elapsedMs: Long? = null,
        exceptionType: String? = null,
        executionEngine: String? = null,
        warmDaemonHealthyAtStart: Boolean? = null,
        fallbackAttempted: Boolean = false,
        fallbackResult: String? = null,
        warmFailureReason: WarmFailureReason? = null,
        timeoutMs: Long? = null,
        startedAtMillis: Long? = null,
        daemonStartupMs: Long? = null,
        socketWaitMs: Long? = null,
        extractMs: Long? = null,
        jsChallengesSolved: Int? = null,
        heartbeatCount: Int? = null,
        lastStage: String? = null,
        lastEvent: String? = null,
        lastEventAgeMs: Long? = null,
        recentDebugLogs: List<String> = emptyList(),
        warningsCount: Int? = null,
        lastWarning: String? = null,
        deadlineExtendedMs: Long? = null
    ): VideoInfoFetchResult.Failed {
        val sanitized = TranscriptRedactor.sanitize(rawError, 220)
        val lower = rawError.lowercase()
        val httpCode = Regex("""\bHTTP\s*(?:Error\s*)?(\d{3})\b""", RegexOption.IGNORE_CASE)
            .find(rawError)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val qjsInfo = currentQuickJsInfo()
        val ejsInfo = currentEjsStatus()

        val isQuickJsError = (lower.contains("quickjs") || lower.contains("libqjs") || lower.contains("js runtime")) &&
            (lower.contains("not found") || lower.contains("incompatible") || lower.contains("permission") ||
                lower.contains("no such file") || lower.contains("unsupported") || lower.contains("failed") ||
                qjsInfo?.isAvailable == false || qjsInfo?.isCompatible == false)
        val isEjsError = lower.contains("yt_dlp_ejs") ||
            lower.contains("challenge solver") ||
            (lower.contains("ejs") && (lower.contains("missing") || lower.contains("failed") || lower.contains("error") || ejsInfo?.isAvailable == false))

        val isWarmTransportReason = warmFailureReason in setOf(
            WarmFailureReason.STARTUP_FAILED,
            WarmFailureReason.HANDSHAKE_FAILED,
            WarmFailureReason.HANDSHAKE_TIMEOUT,
            WarmFailureReason.SOCKET_CONNECT_FAILED,
            WarmFailureReason.PREMATURE_EOF,
            WarmFailureReason.PROTOCOL_MISMATCH
        )

        val (status, message, retryable) = when {
            lower.contains("timed out") || lower.contains("timeout") -> {
                val timeoutCls = when {
                    (jsChallengesSolved ?: 0) > 0 || lastStage == "js_challenge" || lower.contains("js challenge") ->
                        TranscriptFailureClassification.JS_CHALLENGE_TIMEOUT
                    warmFailureReason == WarmFailureReason.OVERALL_DEADLINE_TIMEOUT ->
                        TranscriptFailureClassification.METADATA_OPERATION_DEADLINE
                    warmFailureReason == WarmFailureReason.DAEMON_INACTIVITY_TIMEOUT ||
                        warmFailureReason == WarmFailureReason.SOCKET_TIMEOUT ->
                        TranscriptFailureClassification.DAEMON_INACTIVITY_TIMEOUT
                    isWarmTransportReason ->
                        TranscriptFailureClassification.DAEMON_TRANSPORT_FAILURE
                    else -> TranscriptFailureClassification.YT_DLP_NETWORK_TIMEOUT
                }
                Triple(
                    VideoTranscriptStatus.TIMEOUT,
                    formatTimeoutMessage(
                        classification = timeoutCls,
                        lastStage = lastStage,
                        lastEventAgeMs = lastEventAgeMs,
                        budgetMs = timeoutMs?.let { it + (deadlineExtendedMs ?: 0L) }
                    ),
                    true
                )
            }
            lower.contains("429") || lower.contains("too many requests") || lower.contains("rate limit") -> Triple(
                VideoTranscriptStatus.RATE_LIMITED,
                "YouTube temporarily rate-limited requests (HTTP 429). Retry shortly.",
                true
            )
            lower.contains("po token") ||
                lower.contains("not a bot") ||
                lower.contains("confirm you're not") ||
                lower.contains("confirm you’re not") -> Triple(
                VideoTranscriptStatus.ACCESS_ERROR,
                "YouTube requested bot verification or a PO token for this video.",
                false
            )
            lower.contains("private") ||
                lower.contains("sign in") ||
                lower.contains("login") ||
                lower.contains("members-only") ||
                lower.contains("join this channel") ||
                lower.contains("age") ||
                lower.contains("cookies") ||
                lower.contains("403") ||
                lower.contains("401") -> Triple(
                VideoTranscriptStatus.ACCESS_ERROR,
                sanitized,
                false
            )
            lower.contains("unavailable") ||
                lower.contains("removed") ||
                lower.contains("deleted") ||
                lower.contains("terminated") ||
                lower.contains("not available") ||
                lower.contains("country") ||
                lower.contains("copyright") ||
                lower.contains("does not exist") -> Triple(
                VideoTranscriptStatus.UNAVAILABLE,
                sanitized,
                false
            )
            lower.contains("unable to resolve host") ||
                lower.contains("unknownhost") ||
                lower.contains("failed to connect") ||
                lower.contains("connection reset") ||
                lower.contains("network") ||
                lower.contains("500") ||
                lower.contains("502") ||
                lower.contains("503") ||
                lower.contains("504") -> Triple(
                VideoTranscriptStatus.NETWORK_ERROR,
                sanitized,
                true
            )
            isQuickJsError || isEjsError -> Triple(
                VideoTranscriptStatus.EXTRACTION_ERROR,
                sanitized,
                false
            )
            else -> Triple(
                VideoTranscriptStatus.EXTRACTION_ERROR,
                sanitized,
                true
            )
        }

        val effectiveHttpCode = httpCode ?: when {
            status == VideoTranscriptStatus.RATE_LIMITED -> 429
            lower.contains("403") -> 403
            lower.contains("503") -> 503
            lower.contains("502") -> 502
            lower.contains("500") -> 500
            lower.contains("504") -> 504
            else -> null
        }
        val classification = when (status) {
            VideoTranscriptStatus.TIMEOUT -> when {
                (jsChallengesSolved ?: 0) > 0 || lastStage == "js_challenge" || lower.contains("js challenge") ->
                    TranscriptFailureClassification.JS_CHALLENGE_TIMEOUT
                warmFailureReason == WarmFailureReason.OVERALL_DEADLINE_TIMEOUT ->
                    TranscriptFailureClassification.METADATA_OPERATION_DEADLINE
                warmFailureReason == WarmFailureReason.DAEMON_INACTIVITY_TIMEOUT ||
                    warmFailureReason == WarmFailureReason.SOCKET_TIMEOUT ->
                    TranscriptFailureClassification.DAEMON_INACTIVITY_TIMEOUT
                isWarmTransportReason ->
                    TranscriptFailureClassification.DAEMON_TRANSPORT_FAILURE
                else -> TranscriptFailureClassification.YT_DLP_NETWORK_TIMEOUT
            }
            VideoTranscriptStatus.RATE_LIMITED -> TranscriptFailureClassification.YOUTUBE_HTTP_429
            VideoTranscriptStatus.ACCESS_ERROR -> when {
                lower.contains("po token") -> TranscriptFailureClassification.PO_TOKEN_REQUIRED
                lower.contains("not a bot") ||
                    lower.contains("confirm you're not") ||
                    lower.contains("confirm you’re not") ||
                    lower.contains("sign in to confirm") ->
                    TranscriptFailureClassification.YOUTUBE_VERIFICATION_REQUIRED
                else -> TranscriptFailureClassification.YOUTUBE_HTTP_403
            }
            VideoTranscriptStatus.UNAVAILABLE -> TranscriptFailureClassification.VIDEO_UNAVAILABLE
            VideoTranscriptStatus.NETWORK_ERROR -> when {
                effectiveHttpCode in 500..599 -> TranscriptFailureClassification.YOUTUBE_HTTP_5XX
                else -> TranscriptFailureClassification.YT_DLP_NETWORK_TIMEOUT
            }
            VideoTranscriptStatus.EXTRACTION_ERROR -> when {
                isQuickJsError || isEjsError || (jsChallengesSolved ?: 0) > 0 || lower.contains("js challenge") ->
                    TranscriptFailureClassification.JS_CHALLENGE_TIMEOUT
                isWarmTransportReason ->
                    TranscriptFailureClassification.DAEMON_TRANSPORT_FAILURE
                else -> TranscriptFailureClassification.PLAYER_CLIENT_FAILURE
            }
            VideoTranscriptStatus.NO_CAPTIONS -> TranscriptFailureClassification.CAPTION_TRACKS_MISSING
            VideoTranscriptStatus.CANCELLED -> TranscriptFailureClassification.USER_CANCELLED
            VideoTranscriptStatus.SUCCESS -> TranscriptFailureClassification.PLAYER_CLIENT_FAILURE
        }

        return VideoInfoFetchResult.Failed(
            status = status,
            message = message,
            retryable = retryable,
            diagnosticInfo = TranscriptDiagnosticInfo(
                videoId = videoId,
                stage = stage,
                ytDlpVersion = currentYtDlpVersion(),
                playerClient = playerClient,
                effectivePlayerClient = effectivePlayerClient,
                errorCategory = status,
                exceptionType = exceptionType,
                httpStatusCode = effectiveHttpCode,
                elapsedMs = elapsedMs,
                sanitizedDetail = sanitized,
                executionEngine = executionEngine,
                startedAtMillis = startedAtMillis,
                timeoutMs = timeoutMs,
                warmDaemonHealthyAtStart = warmDaemonHealthyAtStart,
                fallbackAttempted = fallbackAttempted,
                fallbackResult = fallbackResult,
                failureClassification = classification,
                quickJsIdentity = qjsInfo?.identity,
                quickJsVersion = qjsInfo?.version,
                ejsSolverAvailable = ejsInfo?.isAvailable,
                ejsSolverVersion = ejsInfo?.version,
                isPlayerCachePatched = ejsInfo?.isPlayerCachePatched,
                daemonStartupMs = daemonStartupMs,
                socketWaitMs = socketWaitMs,
                extractMs = extractMs,
                jsChallengesSolved = jsChallengesSolved,
                heartbeatCount = heartbeatCount,
                lastKnownStage = lastStage,
                lastMeaningfulEvent = lastEvent,
                lastMeaningfulActivityAgeMs = lastEventAgeMs,
                recentDebugLogs = recentDebugLogs,
                warningsCount = warningsCount,
                lastWarning = lastWarning,
                deadlineExtendedMs = deadlineExtendedMs
            )
        )
    }

    private fun mapPlaylistError(rawError: String): PlaylistFetchResult.Failed {
        val sanitized = TranscriptRedactor.sanitize(rawError, 220)
        val lower = rawError.lowercase()
        return when {
            lower.contains("timed out") || lower.contains("timeout") ->
                PlaylistFetchResult.Failed(VideoTranscriptStatus.TIMEOUT, "Timed out while loading playlist.", retryable = true)
            lower.contains("429") || lower.contains("too many requests") ->
                PlaylistFetchResult.Failed(VideoTranscriptStatus.RATE_LIMITED, "YouTube rate-limited playlist request (HTTP 429).", retryable = true)
            lower.contains("private") || lower.contains("sign in") || lower.contains("does not exist") ->
                PlaylistFetchResult.Failed(VideoTranscriptStatus.ACCESS_ERROR, "Playlist is private or does not exist.", retryable = false)
            lower.contains("unable to resolve host") || lower.contains("network") ->
                PlaylistFetchResult.Failed(VideoTranscriptStatus.NETWORK_ERROR, sanitized, retryable = true)
            else ->
                PlaylistFetchResult.Failed(VideoTranscriptStatus.EXTRACTION_ERROR, sanitized, retryable = true)
        }
    }

    private suspend fun OkHttpClient.awaitResponse(request: Request): Response {
        currentCoroutineContext().ensureActive()
        return suspendCancellableCoroutine { cont ->
            val call = newCall(request)
            cont.invokeOnCancellation {
                call.cancel()
            }
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) {
                            cont.resumeWithException(e)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (cont.isActive) {
                            cont.resume(response)
                        } else {
                            response.close()
                        }
                    }
                })
            } catch (t: Throwable) {
                if (cont.isActive) {
                    cont.resumeWithException(t)
                }
            }
        }
    }

    companion object {
        const val PLAYLIST_PAGE_SIZE = 50
        const val VIDEO_METADATA_TIMEOUT_MS = 60_000L
        const val VIDEO_METADATA_MAX_JS_CHALLENGE_TIMEOUT_MS = 90_000L
        const val NATIVE_SUBTITLE_TIMEOUT_MS = 45_000L
        const val PLAYLIST_METADATA_TIMEOUT_MS = 45_000L

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
