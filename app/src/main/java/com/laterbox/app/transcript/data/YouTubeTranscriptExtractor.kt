package com.laterbox.app.transcript.data

import android.util.Log
import com.laterbox.app.transcript.domain.CaptionType
import com.laterbox.app.transcript.domain.PlaylistEntrySummary
import com.laterbox.app.transcript.domain.TranscriptDiagnosticInfo
import com.laterbox.app.transcript.domain.TranscriptDocument
import com.laterbox.app.transcript.domain.TranscriptExtractionProgress
import com.laterbox.app.transcript.domain.TranscriptExtractionStage
import com.laterbox.app.transcript.domain.TranscriptTargetPreference
import com.laterbox.app.transcript.domain.VideoTranscriptEntry
import com.laterbox.app.transcript.domain.VideoTranscriptStatus
import com.laterbox.app.transcript.domain.YouTubeUrlParser
import com.laterbox.app.transcript.domain.YouTubeUrlTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

sealed interface TranscriptExtractionOutcome {
    data class Completed(val document: TranscriptDocument) : TranscriptExtractionOutcome
    data class Cancelled(val partialDocument: TranscriptDocument?) : TranscriptExtractionOutcome
    data class Failed(val message: String) : TranscriptExtractionOutcome
}

class YouTubeTranscriptExtractor(
    private val dataSource: YouTubeTranscriptDataSource,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val playlistInterVideoDelayMs: Long = 150L
) {
    private val tag = "YTTranscriptExtractor"

    suspend fun extract(
        rawUrl: String,
        preference: TranscriptTargetPreference? = null,
        onProgress: suspend (TranscriptExtractionProgress) -> Unit = {}
    ): TranscriptExtractionOutcome = withContext(ioDispatcher) {
        val processRoot = "transcript_${clock()}_${UUID.randomUUID().toString().take(6)}"

        onProgress(
            TranscriptExtractionProgress(
                stage = TranscriptExtractionStage.RESOLVING_TARGET,
                message = "Resolving YouTube URL..."
            )
        )

        val target = YouTubeUrlParser.parse(rawUrl)
        when (target) {
            is YouTubeUrlTarget.Invalid -> {
                onProgress(
                    TranscriptExtractionProgress(
                        stage = TranscriptExtractionStage.FAILED,
                        message = target.reason
                    )
                )
                TranscriptExtractionOutcome.Failed(target.reason)
            }
            is YouTubeUrlTarget.SingleVideo -> {
                extractSingleVideo(
                    videoId = target.videoId,
                    videoUrl = target.canonicalUrl,
                    sourceUrl = target.originalUrl,
                    processId = processRoot,
                    onProgress = onProgress
                )
            }
            is YouTubeUrlTarget.Playlist -> {
                extractPlaylist(
                    playlistId = target.playlistId,
                    playlistUrl = target.canonicalUrl,
                    sourceUrl = target.originalUrl,
                    processId = processRoot,
                    onProgress = onProgress
                )
            }
            is YouTubeUrlTarget.VideoInPlaylist -> {
                val chosen = preference ?: if (target.isAutoMix) {
                    TranscriptTargetPreference.VIDEO
                } else {
                    TranscriptTargetPreference.PLAYLIST
                }
                if (chosen == TranscriptTargetPreference.PLAYLIST) {
                    extractPlaylist(
                        playlistId = target.playlistId,
                        playlistUrl = target.playlistCanonicalUrl,
                        sourceUrl = target.originalUrl,
                        processId = processRoot,
                        onProgress = onProgress
                    )
                } else {
                    extractSingleVideo(
                        videoId = target.videoId,
                        videoUrl = target.videoCanonicalUrl,
                        sourceUrl = target.originalUrl,
                        processId = processRoot,
                        onProgress = onProgress
                    )
                }
            }
        }
    }

    private suspend fun extractSingleVideo(
        videoId: String,
        videoUrl: String,
        sourceUrl: String,
        processId: String,
        onProgress: suspend (TranscriptExtractionProgress) -> Unit
    ): TranscriptExtractionOutcome {
        return try {
            currentCoroutineContext().ensureActive()
            onProgress(
                TranscriptExtractionProgress(
                    stage = TranscriptExtractionStage.EXTRACTING_VIDEO,
                    currentVideoIndex = 1,
                    totalVideos = 1,
                    message = "Retrieving video metadata and captions..."
                )
            )

            val entry = extractSingleEntryIsolated(
                position = 1,
                videoId = videoId,
                videoUrl = videoUrl,
                fallbackSummary = null,
                processId = "${processId}_v1"
            )

            val doc = buildDocument(
                isPlaylist = false,
                title = entry.title,
                sourceUrl = sourceUrl,
                entries = listOf(entry),
                totalFoundOverride = 1
            )

            onProgress(
                TranscriptExtractionProgress(
                    stage = TranscriptExtractionStage.COMPLETED,
                    currentVideoIndex = 1,
                    totalVideos = 1,
                    currentVideoTitle = entry.title,
                    successfulCount = doc.successfulCount,
                    noCaptionsCount = doc.noCaptionsCount,
                    failedCount = doc.inaccessibleCount,
                    message = if (doc.successfulCount > 0) {
                        "Transcript extracted."
                    } else {
                        entry.statusMessage ?: entry.status.displayLabel
                    }
                )
            )
            TranscriptExtractionOutcome.Completed(doc)
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                dataSource.cancelProcess("${processId}_v1")
                onProgress(
                    TranscriptExtractionProgress(
                        stage = TranscriptExtractionStage.CANCELLED,
                        message = "Extraction cancelled."
                    )
                )
            }
            TranscriptExtractionOutcome.Cancelled(null)
        }
    }

    private suspend fun extractPlaylist(
        playlistId: String,
        playlistUrl: String,
        sourceUrl: String,
        processId: String,
        onProgress: suspend (TranscriptExtractionProgress) -> Unit
    ): TranscriptExtractionOutcome {
        onProgress(
            TranscriptExtractionProgress(
                stage = TranscriptExtractionStage.FETCHING_PLAYLIST,
                message = "Loading playlist entries..."
            )
        )

        val playlistResolution = try {
            when (val res = dataSource.fetchPlaylistEntries(
                playlistId = playlistId,
                playlistUrl = playlistUrl,
                processId = "${processId}_pl",
                onPageLoaded = { loaded, totalHint ->
                    val hintLabel = if (totalHint != null && totalHint > 0) " of $totalHint" else ""
                    onProgress(
                        TranscriptExtractionProgress(
                            stage = TranscriptExtractionStage.FETCHING_PLAYLIST,
                            totalVideos = totalHint ?: loaded,
                            message = "Loading playlist videos ($loaded$hintLabel found)..."
                        )
                    )
                }
            )) {
                is PlaylistFetchResult.Success -> res.playlist
                is PlaylistFetchResult.Failed -> {
                    onProgress(
                        TranscriptExtractionProgress(
                            stage = TranscriptExtractionStage.FAILED,
                            message = res.message
                        )
                    )
                    return TranscriptExtractionOutcome.Failed(res.message)
                }
            }
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                dataSource.cancelProcess("${processId}_pl")
                onProgress(
                    TranscriptExtractionProgress(
                        stage = TranscriptExtractionStage.CANCELLED,
                        message = "Playlist extraction cancelled."
                    )
                )
            }
            return TranscriptExtractionOutcome.Cancelled(null)
        }

        val totalVideos = playlistResolution.entries.size
        val extractedEntries = mutableListOf<VideoTranscriptEntry>()
        var successCount = 0
        var noCaptionsCount = 0
        var failedCount = 0
        var activeSubProcessId: String? = null
        var previousWasRateLimited = false

        try {
            for ((idx, summary) in playlistResolution.entries.withIndex()) {
                currentCoroutineContext().ensureActive()

                if (idx > 0) {
                    val paceDelay = if (previousWasRateLimited) 1_200L else playlistInterVideoDelayMs
                    if (paceDelay > 0L) {
                        delay(paceDelay)
                        currentCoroutineContext().ensureActive()
                    }
                }

                val humanPos = idx + 1
                onProgress(
                    TranscriptExtractionProgress(
                        stage = TranscriptExtractionStage.EXTRACTING_VIDEO,
                        currentVideoIndex = humanPos,
                        totalVideos = totalVideos,
                        currentVideoTitle = summary.title,
                        successfulCount = successCount,
                        noCaptionsCount = noCaptionsCount,
                        failedCount = failedCount,
                        message = "Extracting video $humanPos of $totalVideos"
                    )
                )

                val entry = if (summary.unavailabilityStatus != null) {
                    VideoTranscriptEntry(
                        position = summary.position,
                        videoId = summary.videoId,
                        title = summary.title,
                        url = summary.url.ifBlank { YouTubeUrlParser.canonicalVideoUrl(summary.videoId) },
                        channel = summary.channel,
                        durationSeconds = summary.durationSeconds,
                        thumbnailUrl = summary.thumbnailUrl,
                        status = summary.unavailabilityStatus,
                        statusMessage = summary.unavailableReason ?: summary.unavailabilityStatus.displayLabel,
                        diagnosticInfo = TranscriptDiagnosticInfo(
                            videoId = summary.videoId,
                            stage = "playlist_listing",
                            ytDlpVersion = dataSource.currentYtDlpVersion(),
                            errorCategory = summary.unavailabilityStatus,
                            sanitizedDetail = TranscriptRedactor.sanitize(summary.unavailableReason)
                        )
                    )
                } else {
                    val subProcId = "${processId}_v$humanPos"
                    activeSubProcessId = subProcId
                    try {
                        extractSingleEntryIsolated(
                            position = summary.position,
                            videoId = summary.videoId,
                            videoUrl = summary.url.ifBlank { YouTubeUrlParser.canonicalVideoUrl(summary.videoId) },
                            fallbackSummary = summary,
                            processId = subProcId
                        )
                    } finally {
                        activeSubProcessId = null
                    }
                }

                previousWasRateLimited = entry.status == VideoTranscriptStatus.RATE_LIMITED
                extractedEntries.add(entry)
                when (entry.status) {
                    VideoTranscriptStatus.SUCCESS -> successCount++
                    VideoTranscriptStatus.NO_CAPTIONS -> noCaptionsCount++
                    VideoTranscriptStatus.UNAVAILABLE,
                    VideoTranscriptStatus.ACCESS_ERROR,
                    VideoTranscriptStatus.NETWORK_ERROR,
                    VideoTranscriptStatus.TIMEOUT,
                    VideoTranscriptStatus.RATE_LIMITED,
                    VideoTranscriptStatus.EXTRACTION_ERROR,
                    VideoTranscriptStatus.CANCELLED -> failedCount++
                }
            }
        } catch (ce: CancellationException) {
            var partialDoc: TranscriptDocument? = null
            withContext(NonCancellable) {
                activeSubProcessId?.let { dataSource.cancelProcess(it) }
                partialDoc = if (extractedEntries.isNotEmpty()) {
                    buildDocument(
                        isPlaylist = true,
                        title = playlistResolution.title,
                        sourceUrl = sourceUrl,
                        entries = extractedEntries,
                        totalFoundOverride = totalVideos
                    )
                } else null
                onProgress(
                    TranscriptExtractionProgress(
                        stage = TranscriptExtractionStage.CANCELLED,
                        currentVideoIndex = extractedEntries.size,
                        totalVideos = totalVideos,
                        successfulCount = successCount,
                        noCaptionsCount = noCaptionsCount,
                        failedCount = failedCount,
                        message = "Extraction cancelled (${extractedEntries.size} of $totalVideos processed)."
                    )
                )
            }
            return TranscriptExtractionOutcome.Cancelled(partialDoc)
        }

        val doc = buildDocument(
            isPlaylist = true,
            title = playlistResolution.title,
            sourceUrl = sourceUrl,
            entries = extractedEntries,
            totalFoundOverride = totalVideos
        )

        onProgress(
            TranscriptExtractionProgress(
                stage = TranscriptExtractionStage.COMPLETED,
                currentVideoIndex = totalVideos,
                totalVideos = totalVideos,
                successfulCount = doc.successfulCount,
                noCaptionsCount = doc.noCaptionsCount,
                failedCount = doc.inaccessibleCount,
                message = "Completed: ${doc.successfulCount} of $totalVideos transcripts extracted."
            )
        )
        return TranscriptExtractionOutcome.Completed(doc)
    }

    private suspend fun extractSingleEntryIsolated(
        position: Int,
        videoId: String,
        videoUrl: String,
        fallbackSummary: PlaylistEntrySummary?,
        processId: String
    ): VideoTranscriptEntry {
        return try {
            extractSingleEntry(position, videoId, videoUrl, fallbackSummary, processId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val status = when (t) {
                is InternalTimeoutException, is SocketTimeoutException -> VideoTranscriptStatus.TIMEOUT
                is HttpStatusException -> mapHttpStatus(t.code)
                is IOException -> VideoTranscriptStatus.NETWORK_ERROR
                else -> VideoTranscriptStatus.EXTRACTION_ERROR
            }
            val msg = TranscriptRedactor.sanitize(t.message ?: status.displayLabel, 220)
            val diag = TranscriptDiagnosticInfo(
                videoId = videoId,
                stage = "entry_isolation",
                ytDlpVersion = dataSource.currentYtDlpVersion(),
                errorCategory = status,
                exceptionType = t.javaClass.simpleName,
                httpStatusCode = (t as? HttpStatusException)?.code,
                sanitizedDetail = msg
            )
            logDiagnostic(diag)
            VideoTranscriptEntry(
                position = position,
                videoId = videoId,
                title = fallbackSummary?.title ?: "Video $position ($videoId)",
                url = videoUrl,
                channel = fallbackSummary?.channel,
                durationSeconds = fallbackSummary?.durationSeconds,
                thumbnailUrl = fallbackSummary?.thumbnailUrl,
                status = status,
                statusMessage = msg,
                diagnosticInfo = diag
            )
        }
    }

    private suspend fun extractSingleEntry(
        position: Int,
        videoId: String,
        videoUrl: String,
        fallbackSummary: PlaylistEntrySummary?,
        processId: String
    ): VideoTranscriptEntry {
        val startedAt = clock()
        var infoRetries = 0
        val infoResult = fetchVideoInfoWithRateLimitRetry(videoId, videoUrl, processId) {
            infoRetries++
        }
        val info = when (infoResult) {
            is VideoInfoFetchResult.Success -> infoResult.info
            is VideoInfoFetchResult.Failed -> {
                val diag = infoResult.diagnosticInfo?.copy(retryCount = infoRetries)
                    ?: TranscriptDiagnosticInfo(
                        videoId = videoId,
                        stage = "video_metadata",
                        ytDlpVersion = dataSource.currentYtDlpVersion(),
                        errorCategory = infoResult.status,
                        retryCount = infoRetries,
                        elapsedMs = clock() - startedAt,
                        sanitizedDetail = TranscriptRedactor.sanitize(infoResult.message)
                    )
                logDiagnostic(diag)
                return VideoTranscriptEntry(
                    position = position,
                    videoId = videoId,
                    title = fallbackSummary?.title ?: "Video $position ($videoId)",
                    url = videoUrl,
                    channel = fallbackSummary?.channel,
                    durationSeconds = fallbackSummary?.durationSeconds,
                    thumbnailUrl = fallbackSummary?.thumbnailUrl,
                    status = infoResult.status,
                    statusMessage = infoResult.message,
                    diagnosticInfo = diag
                )
            }
        }

        // Only report NO_CAPTIONS when metadata succeeded AND confirmed zero caption tracks exist.
        if (info.captionTracks.isEmpty()) {
            return VideoTranscriptEntry(
                position = position,
                videoId = info.videoId,
                title = info.title,
                url = info.url,
                channel = info.channel ?: fallbackSummary?.channel,
                durationSeconds = info.durationSeconds ?: fallbackSummary?.durationSeconds,
                thumbnailUrl = info.thumbnailUrl ?: fallbackSummary?.thumbnailUrl,
                status = VideoTranscriptStatus.NO_CAPTIONS,
                statusMessage = "No accessible captions were found for this video."
            )
        }

        val selected = CaptionLanguageSelector.selectBest(info.captionTracks)
        if (selected == null) {
            val diag = TranscriptDiagnosticInfo(
                videoId = info.videoId,
                stage = "track_selection",
                ytDlpVersion = dataSource.currentYtDlpVersion(),
                playerClient = info.playerClientUsed,
                errorCategory = VideoTranscriptStatus.EXTRACTION_ERROR,
                elapsedMs = clock() - startedAt,
                sanitizedDetail = "Caption tracks were listed (${info.captionTracks.size}) but no usable format URLs were present."
            )
            logDiagnostic(diag)
            return VideoTranscriptEntry(
                position = position,
                videoId = info.videoId,
                title = info.title,
                url = info.url,
                channel = info.channel ?: fallbackSummary?.channel,
                durationSeconds = info.durationSeconds ?: fallbackSummary?.durationSeconds,
                thumbnailUrl = info.thumbnailUrl ?: fallbackSummary?.thumbnailUrl,
                status = VideoTranscriptStatus.EXTRACTION_ERROR,
                statusMessage = "Caption tracks were listed, but no downloadable caption format was available.",
                diagnosticInfo = diag
            )
        }

        // Build ordered formats (inline native content first if present, then HTTP format order)
        val formatOrder = buildList {
            add(selected.format to selected.url)
            val preferredOrder = listOf("json3", "vtt", "srv3", "srv1", "ttml", "srt")
            for (fmt in preferredOrder) {
                val hasInline = !selected.track.inlineContentByFormat[fmt].isNullOrBlank()
                val altUrl = selected.track.formatUrls[fmt]
                if ((hasInline || !altUrl.isNullOrBlank()) && fmt != selected.format) {
                    add(fmt to (altUrl ?: "native://${selected.track.languageCode}.$fmt"))
                }
            }
        }

        var lastFetchError: Throwable? = null
        var lastParseError: String? = null
        var captionRetries = 0

        for ((fmt, url) in formatOrder) {
            currentCoroutineContext().ensureActive()

            val inlinePayload = selected.track.inlineContentByFormat[fmt]
            val payload: String? = if (!inlinePayload.isNullOrBlank()) {
                inlinePayload
            } else if (url.startsWith("http://") || url.startsWith("https://")) {
                val payloadResult = fetchCaptionPayloadWithRetry(url) { captionRetries++ }
                if (payloadResult.isFailure) {
                    lastFetchError = payloadResult.exceptionOrNull()
                    null
                } else {
                    payloadResult.getOrNull()
                }
            } else {
                null
            }

            if (payload != null) {
                if (looksLikeHtmlResponse(payload)) {
                    lastFetchError = HttpStatusException(
                        code = 403,
                        message = "YouTube returned a verification or HTML error page instead of caption data."
                    )
                } else {
                    val parsed = CaptionTrackParser.parse(payload, formatHint = fmt)
                    if (parsed.paragraphs.isNotEmpty()) {
                        return VideoTranscriptEntry(
                            position = position,
                            videoId = info.videoId,
                            title = info.title,
                            url = info.url,
                            channel = info.channel ?: fallbackSummary?.channel,
                            durationSeconds = info.durationSeconds ?: fallbackSummary?.durationSeconds,
                            thumbnailUrl = info.thumbnailUrl ?: fallbackSummary?.thumbnailUrl,
                            captionLanguage = CaptionLanguageSelector.formatDisplayLanguage(selected.track),
                            captionLanguageCode = selected.track.languageCode,
                            captionType = if (selected.track.isAutoGenerated) CaptionType.AUTOMATIC else CaptionType.MANUAL,
                            status = VideoTranscriptStatus.SUCCESS,
                            statusMessage = VideoTranscriptStatus.SUCCESS.displayLabel,
                            segments = parsed.segments,
                            paragraphs = parsed.paragraphs
                        )
                    } else {
                        lastParseError = "Caption response ($fmt) was empty or could not be parsed."
                    }
                }
            }
        }

        // Fallback to native yt-dlp subtitle download when HTTP timedtext download failed with 403/unparseable
        val shouldTryNativeFallback = selected.track.inlineContentByFormat.isEmpty() &&
            ((lastFetchError as? HttpStatusException)?.code in setOf(401, 403) || lastParseError != null)
        if (shouldTryNativeFallback) {
            currentCoroutineContext().ensureActive()
            val nativeRes = dataSource.fetchNativeSubtitles(
                videoId = info.videoId,
                videoUrl = info.url,
                processId = "${processId}_sub",
                preferredLangSpec = "${selected.track.languageCode},en.*,en,.*",
                playerClientOverride = "tv,android_vr,web_embedded"
            )
            if (nativeRes is VideoInfoFetchResult.Success) {
                val nativeSelected = CaptionLanguageSelector.selectBest(nativeRes.info.captionTracks)
                val nativeContent = nativeSelected?.track?.inlineContentByFormat?.get(nativeSelected.format)
                if (nativeSelected != null && !nativeContent.isNullOrBlank()) {
                    val parsedNative = CaptionTrackParser.parse(nativeContent, formatHint = nativeSelected.format)
                    if (parsedNative.paragraphs.isNotEmpty()) {
                        return VideoTranscriptEntry(
                            position = position,
                            videoId = info.videoId,
                            title = info.title,
                            url = info.url,
                            channel = info.channel ?: fallbackSummary?.channel,
                            durationSeconds = info.durationSeconds ?: fallbackSummary?.durationSeconds,
                            thumbnailUrl = info.thumbnailUrl ?: fallbackSummary?.thumbnailUrl,
                            captionLanguage = CaptionLanguageSelector.formatDisplayLanguage(nativeSelected.track),
                            captionLanguageCode = nativeSelected.track.languageCode,
                            captionType = if (nativeSelected.track.isAutoGenerated) CaptionType.AUTOMATIC else CaptionType.MANUAL,
                            status = VideoTranscriptStatus.SUCCESS,
                            statusMessage = VideoTranscriptStatus.SUCCESS.displayLabel,
                            segments = parsedNative.segments,
                            paragraphs = parsedNative.paragraphs
                        )
                    }
                }
            }
        }

        // Caption tracks WERE listed, so never turn failure/unparseable payload into a misleading NO_CAPTIONS!
        val (status, message) = classifyCaptionDownloadFailure(lastFetchError, lastParseError)
        val diag = TranscriptDiagnosticInfo(
            videoId = info.videoId,
            stage = "caption_download",
            ytDlpVersion = dataSource.currentYtDlpVersion(),
            playerClient = info.playerClientUsed,
            errorCategory = status,
            exceptionType = lastFetchError?.javaClass?.simpleName,
            httpStatusCode = (lastFetchError as? HttpStatusException)?.code,
            elapsedMs = clock() - startedAt,
            retryCount = infoRetries + captionRetries,
            sanitizedDetail = TranscriptRedactor.sanitize(lastFetchError?.message ?: lastParseError ?: message)
        )
        logDiagnostic(diag)

        return VideoTranscriptEntry(
            position = position,
            videoId = info.videoId,
            title = info.title,
            url = info.url,
            channel = info.channel ?: fallbackSummary?.channel,
            durationSeconds = info.durationSeconds ?: fallbackSummary?.durationSeconds,
            thumbnailUrl = info.thumbnailUrl ?: fallbackSummary?.thumbnailUrl,
            captionLanguage = CaptionLanguageSelector.formatDisplayLanguage(selected.track),
            captionLanguageCode = selected.track.languageCode,
            captionType = if (selected.track.isAutoGenerated) CaptionType.AUTOMATIC else CaptionType.MANUAL,
            status = status,
            statusMessage = message,
            diagnosticInfo = diag
        )
    }

    private fun classifyCaptionDownloadFailure(
        lastFetchError: Throwable?,
        lastParseError: String?
    ): Pair<VideoTranscriptStatus, String> {
        return when (lastFetchError) {
            is InternalTimeoutException, is SocketTimeoutException -> {
                VideoTranscriptStatus.TIMEOUT to "Timed out while downloading captions."
            }
            is HttpStatusException -> {
                val st = mapHttpStatus(lastFetchError.code)
                st to TranscriptRedactor.sanitize(lastFetchError.message, 200)
            }
            is IOException -> {
                VideoTranscriptStatus.NETWORK_ERROR to TranscriptRedactor.sanitize(
                    lastFetchError.message ?: "Network error while downloading captions.",
                    200
                )
            }
            null -> {
                VideoTranscriptStatus.EXTRACTION_ERROR to (
                    lastParseError ?: "Caption data was returned in an empty or unsupported format."
                    )
            }
            else -> {
                VideoTranscriptStatus.EXTRACTION_ERROR to TranscriptRedactor.sanitize(
                    lastFetchError.message ?: "Failed to extract caption track.",
                    200
                )
            }
        }
    }

    private fun mapHttpStatus(code: Int): VideoTranscriptStatus {
        return when (code) {
            429 -> VideoTranscriptStatus.RATE_LIMITED
            401, 403 -> VideoTranscriptStatus.ACCESS_ERROR
            404, 410 -> VideoTranscriptStatus.UNAVAILABLE
            in 500..599 -> VideoTranscriptStatus.NETWORK_ERROR
            else -> VideoTranscriptStatus.EXTRACTION_ERROR
        }
    }

    private fun looksLikeHtmlResponse(payload: String): Boolean {
        val sample = payload.trimStart().take(240).lowercase()
        return sample.startsWith("<!doctype html") ||
            sample.startsWith("<html") ||
            (sample.contains("<html") && (sample.contains("sorry") || sample.contains("consent") || sample.contains("sign in")))
    }

    private suspend fun fetchVideoInfoWithRateLimitRetry(
        videoId: String,
        videoUrl: String,
        processId: String,
        onRetry: () -> Unit
    ): VideoInfoFetchResult {
        val first = dataSource.fetchVideoInfo(videoId, videoUrl, processId)
        if (first is VideoInfoFetchResult.Failed &&
            first.retryable &&
            (first.status == VideoTranscriptStatus.RATE_LIMITED || first.message.contains("429"))
        ) {
            onRetry()
            delay(1_200L)
            currentCoroutineContext().ensureActive()
            return dataSource.fetchVideoInfo(videoId, videoUrl, "${processId}_r1")
        }
        return first
    }

    private suspend fun fetchCaptionPayloadWithRetry(
        url: String,
        onRetry: () -> Unit
    ): Result<String> {
        var attemptResult = dataSource.fetchCaptionPayload(url)
        val delaysMs = listOf(1_200L, 2_500L)
        for (waitMs in delaysMs) {
            val ex = attemptResult.exceptionOrNull()
            if (ex is HttpStatusException && ex.code == 429) {
                onRetry()
                val retryAfterMs = ex.retryAfterSeconds?.times(1000L)?.coerceIn(500L, 10_000L) ?: waitMs
                delay(retryAfterMs)
                currentCoroutineContext().ensureActive()
                attemptResult = dataSource.fetchCaptionPayload(url)
            } else {
                break
            }
        }
        return attemptResult
    }

    private fun logDiagnostic(diag: TranscriptDiagnosticInfo) {
        try {
            Log.w(
                tag,
                "Transcript failure video=${diag.videoId} stage=${diag.stage} status=${diag.errorCategory} " +
                    "client=${diag.playerClient ?: "-"} ytdlp=${diag.ytDlpVersion ?: "-"} " +
                    "http=${diag.httpStatusCode ?: "-"} ex=${diag.exceptionType ?: "-"} " +
                    "retries=${diag.retryCount} elapsedMs=${diag.elapsedMs ?: "-"} detail=${diag.sanitizedDetail ?: "-"}"
            )
        } catch (_: Throwable) {
            // Safe no-op in unmocked unit environments if needed
        }
    }

    internal fun buildDocument(
        isPlaylist: Boolean,
        title: String,
        sourceUrl: String,
        entries: List<VideoTranscriptEntry>,
        totalFoundOverride: Int? = null
    ): TranscriptDocument {
        val successCount = entries.count { it.status == VideoTranscriptStatus.SUCCESS }
        val noCaptionsCount = entries.count { it.status == VideoTranscriptStatus.NO_CAPTIONS }
        val inaccessibleCount = entries.count {
            it.status != VideoTranscriptStatus.SUCCESS && it.status != VideoTranscriptStatus.NO_CAPTIONS
        }
        return TranscriptDocument(
            isPlaylist = isPlaylist,
            title = title.ifBlank { if (isPlaylist) "YouTube Playlist" else "YouTube Video" },
            sourceUrl = sourceUrl,
            extractedAtMillis = clock(),
            videosFound = totalFoundOverride ?: entries.size,
            successfulCount = successCount,
            noCaptionsCount = noCaptionsCount,
            inaccessibleCount = inaccessibleCount,
            entries = entries
        )
    }
}
