package com.laterbox.app.transcript.data

import android.content.Context
import android.util.Log
import com.laterbox.app.media.data.adapters.ytdlp.QuickJsHelper
import com.laterbox.app.media.data.adapters.ytdlp.WarmYtDlpServer
import com.laterbox.app.media.data.adapters.ytdlp.YtDlpInitState
import com.laterbox.app.media.data.adapters.ytdlp.YtDlpOptimizer
import com.laterbox.app.media.data.adapters.ytdlp.YtDlpRuntimeManager
import com.laterbox.app.transcript.domain.CaptionTrackCandidate
import com.laterbox.app.transcript.domain.FetchedVideoInfo
import com.laterbox.app.transcript.domain.PlaylistEntrySummary
import com.laterbox.app.transcript.domain.PlaylistResolution
import com.laterbox.app.transcript.domain.TranscriptDiagnosticInfo
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
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
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
        val diagnosticInfo: TranscriptDiagnosticInfo? = null
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

interface YouTubeTranscriptDataSource {
    suspend fun fetchVideoInfo(
        videoId: String,
        videoUrl: String,
        processId: String
    ): VideoInfoFetchResult

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
    private val metadataTimeoutMs: Long = VIDEO_METADATA_TIMEOUT_MS
) : YouTubeTranscriptDataSource {

    private val tag = "YTTranscriptDataSource"

    override fun currentYtDlpVersion(): String? {
        return (runtimeManager.ytDlpState.value as? YtDlpInitState.Initialized)?.version
    }

    override suspend fun fetchVideoInfo(
        videoId: String,
        videoUrl: String,
        processId: String
    ): VideoInfoFetchResult = withContext(Dispatchers.IO) {
        val canonicalUrl = YouTubeUrlParser.canonicalVideoUrl(videoId)

        // 1. Primary path: Laterbox's embedded yt-dlp runtime / WarmYtDlpServer
        val ytdlpResult = fetchVideoInfoViaYtDlp(videoId, canonicalUrl, processId)
        if (ytdlpResult is VideoInfoFetchResult.Success && ytdlpResult.info.captionTracks.isNotEmpty()) {
            return@withContext ytdlpResult
        }

        // 2. If yt-dlp skipped client subtitles due to PO token policy on web/default, retry native
        //    yt-dlp subtitle extraction using clients that do not enforce web subtitle PO tokens.
        if (ytdlpResult is VideoInfoFetchResult.Success &&
            ytdlpResult.info.captionTracks.isEmpty() &&
            ytdlpResult.info.poTokenSkippedWarning
        ) {
            val altNative = fetchNativeSubtitles(
                videoId = videoId,
                videoUrl = canonicalUrl,
                processId = "${processId}_altcli",
                playerClientOverride = "tv,android_vr,web_embedded"
            )
            if (altNative is VideoInfoFetchResult.Success && altNative.info.captionTracks.isNotEmpty()) {
                return@withContext altNative
            }
        }

        // 3. Fallback path: Direct watch page ytInitialPlayerResponse via OkHttp
        //    Handles uninitialized runtime or missing caption metadata, while NEVER letting an empty
        //    watch-HTML track list mask a real yt-dlp timeout, rate-limit, or access failure.
        val htmlResult = fetchVideoInfoViaWatchHtml(videoId, canonicalUrl)
        when {
            htmlResult is VideoInfoFetchResult.Success && htmlResult.info.captionTracks.isNotEmpty() -> {
                htmlResult
            }
            ytdlpResult is VideoInfoFetchResult.Success -> {
                // Only accept empty captions when primary yt-dlp metadata itself succeeded.
                ytdlpResult
            }
            htmlResult is VideoInfoFetchResult.Success &&
                ytdlpResult is VideoInfoFetchResult.Failed &&
                ytdlpResult.diagnosticInfo?.stage == "runtime_init" -> {
                // Runtime unavailable (e.g. cold/failed local unpack), but public watch page loaded cleanly.
                htmlResult
            }
            ytdlpResult is VideoInfoFetchResult.Failed &&
                htmlResult is VideoInfoFetchResult.Failed &&
                ytdlpResult.status == VideoTranscriptStatus.EXTRACTION_ERROR &&
                htmlResult.status != VideoTranscriptStatus.NETWORK_ERROR -> {
                htmlResult
            }
            else -> ytdlpResult
        }
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
                return@withContext VideoInfoFetchResult.Failed(
                    status = VideoTranscriptStatus.EXTRACTION_ERROR,
                    message = "Extractor runtime unavailable: ${TranscriptRedactor.sanitize(initResult.exceptionOrNull()?.message, 120)}",
                    retryable = true,
                    diagnosticInfo = TranscriptDiagnosticInfo(
                        videoId = videoId,
                        stage = "runtime_init",
                        ytDlpVersion = currentYtDlpVersion(),
                        playerClient = clientArg,
                        errorCategory = VideoTranscriptStatus.EXTRACTION_ERROR,
                        exceptionType = initResult.exceptionOrNull()?.javaClass?.simpleName,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        sanitizedDetail = TranscriptRedactor.sanitize(initResult.exceptionOrNull()?.message)
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
                retryable = false
            )
        }

        try {
            val request = YoutubeDLRequest(canonicalUrl).apply {
                addOption("--no-playlist")
                addOption("--skip-download")
                addOption("--write-subs")
                addOption("--write-auto-subs")
                addOption("--write-info-json")
                addOption("--sub-langs", preferredLangSpec)
                addOption("--sub-format", "json3/vtt/srv3/srv1/ttml")
                addOption("--socket-timeout", "25")
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
                    playerClientUsed = clientArg
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
                elapsedMs = System.currentTimeMillis() - startedAt
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
                "--playlist-items=$startIndex:$endIndex",
                "--socket-timeout=25",
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
        warmServer?.cancel(processId)
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (_: Throwable) {}
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
                        exceptionType = initErr?.javaClass?.simpleName,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        sanitizedDetail = TranscriptRedactor.sanitize(initErr?.message)
                    )
                )
            }
        }

        val options = mutableListOf(
            "--dump-single-json",
            "--no-playlist",
            "--skip-download",
            "--socket-timeout=25",
            "--cache-dir=${YtDlpOptimizer.getCacheDir(appContext).absolutePath}",
            "--extractor-args=youtube:player_client=$clientArg"
        )
        QuickJsHelper.getJsRuntimeArgument(appContext)?.let {
            options.add("--js-runtimes=$it")
        }

        return try {
            val execution = executeYtDlpJson(canonicalUrl, options, processId, timeoutMs = metadataTimeoutMs)
            if (execution.output.isBlank()) {
                VideoInfoFetchResult.Failed(
                    status = VideoTranscriptStatus.EXTRACTION_ERROR,
                    message = "Empty video metadata response.",
                    retryable = true,
                    diagnosticInfo = TranscriptDiagnosticInfo(
                        videoId = videoId,
                        stage = "video_metadata",
                        ytDlpVersion = currentYtDlpVersion(),
                        playerClient = clientArg,
                        errorCategory = VideoTranscriptStatus.EXTRACTION_ERROR,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        sanitizedDetail = "Empty JSON output from extractor."
                    )
                )
            } else {
                parseYtDlpVideoJson(
                    fallbackVideoId = videoId,
                    canonicalUrl = canonicalUrl,
                    jsonStr = execution.output,
                    playerClient = clientArg,
                    warnings = execution.warnings
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
                elapsedMs = System.currentTimeMillis() - startedAt
            )
        }
    }

    private data class JsonExecutionOutput(
        val output: String,
        val warnings: String? = null
    )

    private suspend fun executeYtDlpJson(
        url: String,
        options: List<String>,
        processId: String,
        timeoutMs: Long
    ): JsonExecutionOutput {
        if (customExecutor == null && warmServer != null) {
            try {
                val warmResult = warmServer.execute(
                    url = url,
                    options = options,
                    processId = processId,
                    timeoutMs = timeoutMs
                )
                if (warmResult != null && warmResult.output.isNotBlank()) {
                    return JsonExecutionOutput(warmResult.output, warmResult.warnings)
                }
            } catch (ye: YoutubeDLException) {
                throw ye
            } catch (ste: SocketTimeoutException) {
                throw InternalTimeoutException(timeoutMs, ste)
            } catch (ce: CancellationException) {
                warmServer.cancel(processId)
                throw ce
            } catch (t: Throwable) {
                Log.w(tag, "Warm daemon fallback to process execution: ${TranscriptRedactor.sanitize(t.message, 120)}")
            }
        }

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

        val response = executeCancellable(request, processId, timeoutMs)
        if (response.out.isNullOrBlank()) {
            val err = response.err?.takeIf { it.isNotBlank() } ?: "Empty response from extractor"
            throw YoutubeDLException(err)
        }
        return JsonExecutionOutput(response.out, response.err)
    }

    private suspend fun executeCancellable(
        request: YoutubeDLRequest,
        processId: String,
        timeoutMs: Long
    ): YoutubeDLResponse {
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
        }
    }

    internal fun parseYtDlpVideoJson(
        fallbackVideoId: String,
        canonicalUrl: String,
        jsonStr: String,
        playerClient: String? = null,
        warnings: String? = null
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
                playerClientUsed = playerClient,
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
                if (response.code == 429) {
                    return VideoInfoFetchResult.Failed(
                        status = VideoTranscriptStatus.RATE_LIMITED,
                        message = "YouTube temporarily rate-limited requests (HTTP 429).",
                        retryable = true,
                        diagnosticInfo = TranscriptDiagnosticInfo(
                            videoId = videoId,
                            stage = "watch_html",
                            playerClient = "html_fallback",
                            errorCategory = VideoTranscriptStatus.RATE_LIMITED,
                            httpStatusCode = 429,
                            elapsedMs = System.currentTimeMillis() - startedAt
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
                            elapsedMs = System.currentTimeMillis() - startedAt
                        )
                    )
                }
                if (!response.isSuccessful) {
                    return VideoInfoFetchResult.Failed(
                        status = VideoTranscriptStatus.NETWORK_ERROR,
                        message = "Failed to load YouTube page (HTTP ${response.code}).",
                        retryable = true,
                        diagnosticInfo = TranscriptDiagnosticInfo(
                            videoId = videoId,
                            stage = "watch_html",
                            playerClient = "html_fallback",
                            errorCategory = VideoTranscriptStatus.NETWORK_ERROR,
                            httpStatusCode = response.code,
                            elapsedMs = System.currentTimeMillis() - startedAt
                        )
                    )
                }

                val html = response.body?.string().orEmpty()
                parseWatchHtmlPlayerResponse(videoId, canonicalUrl, html)
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
                    exceptionType = ste.javaClass.simpleName,
                    elapsedMs = System.currentTimeMillis() - startedAt,
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
                    exceptionType = e.javaClass.simpleName,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    sanitizedDetail = TranscriptRedactor.sanitize(e.message)
                )
            )
        }
    }

    internal fun parseWatchHtmlPlayerResponse(
        videoId: String,
        canonicalUrl: String,
        html: String
    ): VideoInfoFetchResult {
        val jsonStr = extractPlayerResponseJson(html)
            ?: return VideoInfoFetchResult.Failed(
                status = VideoTranscriptStatus.EXTRACTION_ERROR,
                message = "Could not extract video player metadata from YouTube page.",
                retryable = true
            )

        val root = try {
            JSONObject(jsonStr)
        } catch (_: Exception) {
            return VideoInfoFetchResult.Failed(
                status = VideoTranscriptStatus.EXTRACTION_ERROR,
                message = "Malformed player metadata from YouTube.",
                retryable = true
            )
        }

        val playability = root.optJSONObject("playabilityStatus")
        val playStatus = playability?.optString("status").orEmpty()
        if (playStatus in setOf("ERROR", "UNPLAYABLE", "LOGIN_REQUIRED")) {
            val reason = playability?.optString("reason").orEmpty().ifBlank { "Video is unavailable ($playStatus)" }
            val mappedStatus = when {
                playStatus == "LOGIN_REQUIRED" -> VideoTranscriptStatus.ACCESS_ERROR
                reason.contains("private", ignoreCase = true) ||
                    reason.contains("members", ignoreCase = true) ||
                    reason.contains("age", ignoreCase = true) ||
                    reason.contains("bot", ignoreCase = true) ||
                    reason.contains("sign in", ignoreCase = true) -> VideoTranscriptStatus.ACCESS_ERROR
                else -> VideoTranscriptStatus.UNAVAILABLE
            }
            return VideoInfoFetchResult.Failed(mappedStatus, reason, retryable = false)
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

    private fun mapVideoException(
        exception: Exception,
        videoId: String,
        stage: String,
        playerClient: String,
        elapsedMs: Long
    ): VideoInfoFetchResult.Failed {
        if (exception is InternalTimeoutException || exception is SocketTimeoutException) {
            val sanitized = TranscriptRedactor.sanitize(exception.message ?: "Request timed out", 200)
            return VideoInfoFetchResult.Failed(
                status = VideoTranscriptStatus.TIMEOUT,
                message = "Timed out while retrieving video metadata from YouTube.",
                retryable = true,
                diagnosticInfo = TranscriptDiagnosticInfo(
                    videoId = videoId,
                    stage = stage,
                    ytDlpVersion = currentYtDlpVersion(),
                    playerClient = playerClient,
                    errorCategory = VideoTranscriptStatus.TIMEOUT,
                    exceptionType = exception.javaClass.simpleName,
                    elapsedMs = elapsedMs,
                    sanitizedDetail = sanitized
                )
            )
        }
        return mapVideoError(
            rawError = exception.message ?: exception.javaClass.simpleName,
            videoId = videoId,
            stage = stage,
            playerClient = playerClient,
            elapsedMs = elapsedMs,
            exceptionType = exception.javaClass.simpleName
        )
    }

    private fun mapVideoError(
        rawError: String,
        videoId: String = "",
        stage: String = "video_metadata",
        playerClient: String? = null,
        elapsedMs: Long? = null,
        exceptionType: String? = null
    ): VideoInfoFetchResult.Failed {
        val sanitized = TranscriptRedactor.sanitize(rawError, 220)
        val lower = rawError.lowercase()
        val (status, message, retryable) = when {
            lower.contains("timed out") || lower.contains("timeout") -> Triple(
                VideoTranscriptStatus.TIMEOUT,
                "Timed out while retrieving video metadata from YouTube.",
                true
            )
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
            else -> Triple(
                VideoTranscriptStatus.EXTRACTION_ERROR,
                sanitized,
                true
            )
        }

        val httpCode = Regex("""\bHTTP\s*(\d{3})\b""", RegexOption.IGNORE_CASE)
            .find(rawError)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return VideoInfoFetchResult.Failed(
            status = status,
            message = message,
            retryable = retryable,
            diagnosticInfo = TranscriptDiagnosticInfo(
                videoId = videoId,
                stage = stage,
                ytDlpVersion = currentYtDlpVersion(),
                playerClient = playerClient,
                errorCategory = status,
                exceptionType = exceptionType,
                httpStatusCode = httpCode,
                elapsedMs = elapsedMs,
                sanitizedDetail = sanitized
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
        const val VIDEO_METADATA_TIMEOUT_MS = 35_000L
        const val NATIVE_SUBTITLE_TIMEOUT_MS = 40_000L
        const val PLAYLIST_METADATA_TIMEOUT_MS = 45_000L

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
