package com.laterbox.app.transcript.data

import com.laterbox.app.transcript.domain.CaptionType
import com.laterbox.app.transcript.domain.PlaylistEntrySummary
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
import java.util.UUID

sealed interface TranscriptExtractionOutcome {
    data class Completed(val document: TranscriptDocument) : TranscriptExtractionOutcome
    data class Cancelled(val partialDocument: TranscriptDocument?) : TranscriptExtractionOutcome
    data class Failed(val message: String) : TranscriptExtractionOutcome
}

class YouTubeTranscriptExtractor(
    private val dataSource: YouTubeTranscriptDataSource,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

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

            val entry = extractSingleEntry(
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
                        entry.statusMessage ?: "No captions available."
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

        try {
            for ((idx, summary) in playlistResolution.entries.withIndex()) {
                currentCoroutineContext().ensureActive()
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
                        statusMessage = summary.unavailableReason ?: summary.unavailabilityStatus.displayLabel
                    )
                } else {
                    val subProcId = "${processId}_v$humanPos"
                    activeSubProcessId = subProcId
                    try {
                        extractSingleEntry(
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

                extractedEntries.add(entry)
                when (entry.status) {
                    VideoTranscriptStatus.SUCCESS -> successCount++
                    VideoTranscriptStatus.NO_CAPTIONS -> noCaptionsCount++
                    VideoTranscriptStatus.UNAVAILABLE,
                    VideoTranscriptStatus.ACCESS_ERROR,
                    VideoTranscriptStatus.NETWORK_ERROR,
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

    private suspend fun extractSingleEntry(
        position: Int,
        videoId: String,
        videoUrl: String,
        fallbackSummary: PlaylistEntrySummary?,
        processId: String
    ): VideoTranscriptEntry {
        val infoResult = fetchVideoInfoWithRateLimitRetry(videoId, videoUrl, processId)
        val info = when (infoResult) {
            is VideoInfoFetchResult.Success -> infoResult.info
            is VideoInfoFetchResult.Failed -> {
                return VideoTranscriptEntry(
                    position = position,
                    videoId = videoId,
                    title = fallbackSummary?.title ?: "Video $position ($videoId)",
                    url = videoUrl,
                    channel = fallbackSummary?.channel,
                    durationSeconds = fallbackSummary?.durationSeconds,
                    thumbnailUrl = fallbackSummary?.thumbnailUrl,
                    status = infoResult.status,
                    statusMessage = infoResult.message
                )
            }
        }

        val selected = CaptionLanguageSelector.selectBest(info.captionTracks)
        if (selected == null) {
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

        // Try selected format, then fallback format(s) on the same track if parsing/fetch fails
        val formatOrder = buildList {
            add(selected.format to selected.url)
            val preferredOrder = listOf("json3", "vtt", "srv3", "srv1", "ttml")
            for (fmt in preferredOrder) {
                val altUrl = selected.track.formatUrls[fmt]
                if (!altUrl.isNullOrBlank() && fmt != selected.format) {
                    add(fmt to altUrl)
                }
            }
        }

        var lastError: Throwable? = null
        for ((fmt, url) in formatOrder) {
            currentCoroutineContext().ensureActive()
            val payloadResult = fetchCaptionPayloadWithRetry(url)
            val payload = payloadResult.getOrNull()
            if (payload != null) {
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
                }
            } else {
                lastError = payloadResult.exceptionOrNull()
            }
        }

        val isRateLimited = (lastError as? HttpStatusException)?.code == 429
        return if (lastError != null && (lastError is HttpStatusException || isRateLimited)) {
            VideoTranscriptEntry(
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
                status = VideoTranscriptStatus.NETWORK_ERROR,
                statusMessage = lastError.message ?: "Failed to retrieve caption track."
            )
        } else {
            VideoTranscriptEntry(
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
    }

    private suspend fun fetchVideoInfoWithRateLimitRetry(
        videoId: String,
        videoUrl: String,
        processId: String
    ): VideoInfoFetchResult {
        val first = dataSource.fetchVideoInfo(videoId, videoUrl, processId)
        if (first is VideoInfoFetchResult.Failed &&
            first.status == VideoTranscriptStatus.NETWORK_ERROR &&
            first.retryable &&
            first.message.contains("429")
        ) {
            delay(1_200L)
            currentCoroutineContext().ensureActive()
            return dataSource.fetchVideoInfo(videoId, videoUrl, "${processId}_r1")
        }
        return first
    }

    private suspend fun fetchCaptionPayloadWithRetry(url: String): Result<String> {
        val first = dataSource.fetchCaptionPayload(url)
        val ex = first.exceptionOrNull()
        if (ex is HttpStatusException && ex.code == 429) {
            delay(1_200L)
            currentCoroutineContext().ensureActive()
            return dataSource.fetchCaptionPayload(url)
        }
        return first
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
