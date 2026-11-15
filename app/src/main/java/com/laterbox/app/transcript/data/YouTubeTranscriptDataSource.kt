package com.laterbox.app.transcript.data

import android.content.Context
import android.util.Log
import com.laterbox.app.media.data.adapters.ytdlp.QuickJsHelper
import com.laterbox.app.media.data.adapters.ytdlp.WarmYtDlpServer
import com.laterbox.app.media.data.adapters.ytdlp.YtDlpOptimizer
import com.laterbox.app.media.data.adapters.ytdlp.YtDlpRuntimeManager
import com.laterbox.app.transcript.domain.CaptionTrackCandidate
import com.laterbox.app.transcript.domain.FetchedVideoInfo
import com.laterbox.app.transcript.domain.PlaylistEntrySummary
import com.laterbox.app.transcript.domain.PlaylistResolution
import com.laterbox.app.transcript.domain.VideoTranscriptStatus
import com.laterbox.app.transcript.domain.YouTubeUrlParser
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

sealed interface VideoInfoFetchResult {
    data class Success(val info: FetchedVideoInfo) : VideoInfoFetchResult
    data class Failed(
        val status: VideoTranscriptStatus,
        val message: String,
        val retryable: Boolean = false
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

class HttpStatusException(val code: Int, override val message: String) : IOException(message)

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

    fun cancelProcess(processId: String)
}

class LaterboxYouTubeTranscriptDataSource(
    private val appContext: Context,
    private val runtimeManager: YtDlpRuntimeManager,
    private val warmServer: WarmYtDlpServer? = null,
    private val okHttpClient: OkHttpClient = defaultHttpClient(),
    private val customExecutor: ((YoutubeDLRequest) -> YoutubeDLResponse)? = null
) : YouTubeTranscriptDataSource {

    private val tag = "YTTranscriptDataSource"

    override suspend fun fetchVideoInfo(
        videoId: String,
        videoUrl: String,
        processId: String
    ): VideoInfoFetchResult = withContext(Dispatchers.IO) {
        val canonicalUrl = YouTubeUrlParser.canonicalVideoUrl(videoId)

        // 1. Primary path: Laterbox's existing yt-dlp runtime / WarmYtDlpServer
        val ytdlpResult = fetchVideoInfoViaYtDlp(videoId, canonicalUrl, processId)
        if (ytdlpResult is VideoInfoFetchResult.Success && ytdlpResult.info.captionTracks.isNotEmpty()) {
            return@withContext ytdlpResult
        }

        // 2. Fallback path: Direct watch page ytInitialPlayerResponse via OkHttp
        //    Handles runtime initialization failure or missing caption map in yt-dlp output.
        val htmlResult = fetchVideoInfoViaWatchHtml(videoId, canonicalUrl)
        when {
            htmlResult is VideoInfoFetchResult.Success &&
                (htmlResult.info.captionTracks.isNotEmpty() || ytdlpResult !is VideoInfoFetchResult.Success) -> {
                htmlResult
            }
            ytdlpResult is VideoInfoFetchResult.Success -> ytdlpResult
            htmlResult is VideoInfoFetchResult.Failed &&
                ytdlpResult is VideoInfoFetchResult.Failed &&
                ytdlpResult.status != VideoTranscriptStatus.ACCESS_ERROR -> htmlResult
            else -> ytdlpResult
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
                status = VideoTranscriptStatus.ACCESS_ERROR,
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

            val jsonOutput = try {
                executeYtDlpJson(canonicalUrl, options, pageProcessId, timeoutMs = 45_000L)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (collected.isNotEmpty()) {
                    // Preserve already fetched pages instead of failing everything
                    break
                }
                return@withContext mapPlaylistError(e.message ?: "Failed to load playlist")
            }

            if (jsonOutput.isNullOrBlank()) {
                if (collected.isNotEmpty()) break
                return@withContext PlaylistFetchResult.Failed(
                    status = VideoTranscriptStatus.UNAVAILABLE,
                    message = "Playlist metadata could not be retrieved.",
                    retryable = true
                )
            }

            val root = try {
                JSONObject(jsonOutput)
            } catch (e: Exception) {
                if (collected.isNotEmpty()) break
                return@withContext PlaylistFetchResult.Failed(
                    status = VideoTranscriptStatus.ACCESS_ERROR,
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
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    return@withContext Result.failure(HttpStatusException(429, "YouTube rate-limited caption requests (HTTP 429)"))
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        HttpStatusException(response.code, "Caption request failed (HTTP ${response.code})")
                    )
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext Result.failure(IOException("Caption response body was empty"))
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
        if (customExecutor == null) {
            val initResult = runtimeManager.ensureYtDlpInitialized()
            if (initResult.isFailure) {
                return VideoInfoFetchResult.Failed(
                    status = VideoTranscriptStatus.ACCESS_ERROR,
                    message = initResult.exceptionOrNull()?.message ?: "Extractor runtime unavailable",
                    retryable = true
                )
            }
        }

        val options = mutableListOf(
            "--dump-single-json",
            "--no-playlist",
            "--skip-download",
            "--socket-timeout=25",
            "--cache-dir=${YtDlpOptimizer.getCacheDir(appContext).absolutePath}",
            "--extractor-args=youtube:player_client=default"
        )
        QuickJsHelper.getJsRuntimeArgument(appContext)?.let {
            options.add("--js-runtimes=$it")
        }

        return try {
            val jsonOutput = executeYtDlpJson(canonicalUrl, options, processId, timeoutMs = 35_000L)
            if (jsonOutput.isNullOrBlank()) {
                VideoInfoFetchResult.Failed(
                    status = VideoTranscriptStatus.ACCESS_ERROR,
                    message = "Empty video metadata response",
                    retryable = true
                )
            } else {
                parseYtDlpVideoJson(videoId, canonicalUrl, jsonOutput)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            mapVideoError(e.message ?: "Failed to retrieve video metadata")
        }
    }

    private suspend fun executeYtDlpJson(
        url: String,
        options: List<String>,
        processId: String,
        timeoutMs: Long
    ): String? {
        if (customExecutor == null && warmServer != null) {
            try {
                val warmResult = warmServer.execute(
                    url = url,
                    options = options,
                    processId = processId,
                    timeoutMs = timeoutMs
                )
                if (warmResult != null && warmResult.output.isNotBlank()) {
                    return warmResult.output
                }
            } catch (ye: YoutubeDLException) {
                throw ye
            } catch (ce: CancellationException) {
                warmServer.cancel(processId)
                throw ce
            } catch (t: Throwable) {
                Log.w(tag, "Warm daemon fallback to process execution: ${t.message}")
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
        return response.out
    }

    private suspend fun executeCancellable(
        request: YoutubeDLRequest,
        processId: String,
        timeoutMs: Long
    ): YoutubeDLResponse = coroutineScope {
        val deferred = async(Dispatchers.IO) {
            if (customExecutor != null) {
                customExecutor.invoke(request)
            } else {
                try {
                    YoutubeDL.getInstance().execute(request, processId)
                } catch (ce: YoutubeDL.CanceledException) {
                    throw CancellationException("Process $processId cancelled", ce)
                }
            }
        }

        val cleanup = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                cancelProcess(processId)
            }
        }

        try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                cancelProcess(processId)
            }
            deferred.cancel()
            throw t
        } finally {
            cleanup?.dispose()
        }
    }

    internal fun parseYtDlpVideoJson(
        fallbackVideoId: String,
        canonicalUrl: String,
        jsonStr: String
    ): VideoInfoFetchResult {
        val root = try {
            JSONObject(jsonStr)
        } catch (_: Exception) {
            return VideoInfoFetchResult.Failed(
                status = VideoTranscriptStatus.ACCESS_ERROR,
                message = "Failed to parse video metadata JSON",
                retryable = true
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

        return VideoInfoFetchResult.Success(
            FetchedVideoInfo(
                videoId = videoId,
                title = title,
                url = webpageUrl,
                channel = channel,
                durationSeconds = durationSec,
                thumbnailUrl = thumbnail,
                captionTracks = tracks
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

    private fun fetchVideoInfoViaWatchHtml(
        videoId: String,
        canonicalUrl: String
    ): VideoInfoFetchResult {
        return try {
            val request = Request.Builder()
                .url("$canonicalUrl&hl=en")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    return VideoInfoFetchResult.Failed(
                        status = VideoTranscriptStatus.NETWORK_ERROR,
                        message = "YouTube temporarily rate-limited requests (HTTP 429).",
                        retryable = true
                    )
                }
                if (!response.isSuccessful) {
                    return VideoInfoFetchResult.Failed(
                        status = VideoTranscriptStatus.NETWORK_ERROR,
                        message = "Failed to load YouTube page (HTTP ${response.code}).",
                        retryable = true
                    )
                }

                val html = response.body?.string().orEmpty()
                parseWatchHtmlPlayerResponse(videoId, canonicalUrl, html)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            VideoInfoFetchResult.Failed(
                status = VideoTranscriptStatus.NETWORK_ERROR,
                message = e.message ?: "Network error retrieving YouTube video.",
                retryable = true
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
                status = VideoTranscriptStatus.ACCESS_ERROR,
                message = "Could not extract video player metadata from YouTube page.",
                retryable = true
            )

        val root = try {
            JSONObject(jsonStr)
        } catch (_: Exception) {
            return VideoInfoFetchResult.Failed(
                status = VideoTranscriptStatus.ACCESS_ERROR,
                message = "Malformed player metadata from YouTube.",
                retryable = true
            )
        }

        val playability = root.optJSONObject("playabilityStatus")
        val playStatus = playability?.optString("status").orEmpty()
        if (playStatus in setOf("ERROR", "UNPLAYABLE", "LOGIN_REQUIRED")) {
            val reason = playability?.optString("reason").orEmpty().ifBlank { "Video is unavailable ($playStatus)" }
            val mappedStatus = if (playStatus == "LOGIN_REQUIRED" || reason.contains("private", ignoreCase = true)) {
                VideoTranscriptStatus.ACCESS_ERROR
            } else {
                VideoTranscriptStatus.UNAVAILABLE
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
                captionTracks = tracks
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

    private fun mapVideoError(rawError: String): VideoInfoFetchResult.Failed {
        val lower = rawError.lowercase()
        return when {
            lower.contains("429") || lower.contains("too many requests") || lower.contains("rate limit") ->
                VideoInfoFetchResult.Failed(VideoTranscriptStatus.NETWORK_ERROR, "YouTube rate-limited requests (HTTP 429).", retryable = true)
            lower.contains("private") || lower.contains("sign in") || lower.contains("login") || lower.contains("members-only") ->
                VideoInfoFetchResult.Failed(VideoTranscriptStatus.ACCESS_ERROR, rawError.take(220), retryable = false)
            lower.contains("unavailable") || lower.contains("removed") || lower.contains("not available") || lower.contains("copyright") ->
                VideoInfoFetchResult.Failed(VideoTranscriptStatus.UNAVAILABLE, rawError.take(220), retryable = false)
            lower.contains("timed out") || lower.contains("timeout") || lower.contains("unable to resolve host") || lower.contains("network") ->
                VideoInfoFetchResult.Failed(VideoTranscriptStatus.NETWORK_ERROR, rawError.take(220), retryable = true)
            else ->
                VideoInfoFetchResult.Failed(VideoTranscriptStatus.ACCESS_ERROR, rawError.take(220), retryable = true)
        }
    }

    private fun mapPlaylistError(rawError: String): PlaylistFetchResult.Failed {
        val lower = rawError.lowercase()
        return when {
            lower.contains("429") || lower.contains("too many requests") ->
                PlaylistFetchResult.Failed(VideoTranscriptStatus.NETWORK_ERROR, "YouTube rate-limited playlist request (HTTP 429).", retryable = true)
            lower.contains("private") || lower.contains("sign in") || lower.contains("does not exist") ->
                PlaylistFetchResult.Failed(VideoTranscriptStatus.ACCESS_ERROR, "Playlist is private or does not exist.", retryable = false)
            else ->
                PlaylistFetchResult.Failed(VideoTranscriptStatus.ACCESS_ERROR, rawError.take(220), retryable = true)
        }
    }

    companion object {
        const val PLAYLIST_PAGE_SIZE = 50

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
