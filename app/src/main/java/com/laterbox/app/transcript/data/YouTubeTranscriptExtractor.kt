package com.laterbox.app.transcript.data

import android.util.Log
import com.laterbox.app.transcript.domain.CaptionTrackCandidate
import com.laterbox.app.transcript.domain.CaptionType
import com.laterbox.app.transcript.domain.PlaylistEntrySummary
import com.laterbox.app.transcript.domain.TranscriptDiagnosticInfo
import com.laterbox.app.transcript.domain.TranscriptDocument
import com.laterbox.app.transcript.domain.TranscriptExtractionProgress
import com.laterbox.app.transcript.domain.TranscriptExtractionStage
import com.laterbox.app.transcript.domain.TranscriptFailureClassification
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException

sealed interface TranscriptExtractionOutcome {
    data class Completed(val document: TranscriptDocument) : TranscriptExtractionOutcome
    data class Cancelled(val partialDocument: TranscriptDocument?) : TranscriptExtractionOutcome
    data class Failed(val message: String) : TranscriptExtractionOutcome
}

data class PlaylistResilienceConfig(
    val baseInterVideoDelayMs: Long = DEFAULT_INTER_VIDEO_DELAY_MS,
    val maxInterVideoDelayMs: Long = 5_000L,
    val rateLimitCooldownMs: Long = 4_000L,
    val transientBackoffStepMs: Long = 600L,
    val recoverySuccessStreak: Int = 3,
    val maxMetadataRetries: Int = 1,
    val baseTransientRetryDelayMs: Long = 1_000L,
    val maxTransientRetryDelayMs: Long = 5_000L,
    val baseRateLimitRetryDelayMs: Long = 2_500L,
    val maxRateLimitRetryDelayMs: Long = 15_000L
) {
    companion object {
        const val DEFAULT_INTER_VIDEO_DELAY_MS = 650L
    }
}

internal class PlaylistPacingController(
    private val config: PlaylistResilienceConfig
) {
    var currentDelayMs: Long = config.baseInterVideoDelayMs.coerceAtLeast(0L)
        private set

    var consecutiveSuccesses: Int = 0
        private set

    var consecutiveTransientFailures: Int = 0
        private set

    private var pendingCooldownMs: Long = 0L

    fun recordOutcome(
        status: VideoTranscriptStatus,
        retryAfterSeconds: Long? = null,
        wasListingUnavailable: Boolean = false
    ) {
        if (wasListingUnavailable) return
        when (status) {
            VideoTranscriptStatus.SUCCESS -> {
                consecutiveSuccesses++
                consecutiveTransientFailures = 0
                if (consecutiveSuccesses >= config.recoverySuccessStreak &&
                    currentDelayMs > config.baseInterVideoDelayMs
                ) {
                    currentDelayMs = (currentDelayMs - config.transientBackoffStepMs)
                        .coerceAtLeast(config.baseInterVideoDelayMs)
                }
            }
            VideoTranscriptStatus.RATE_LIMITED -> {
                consecutiveSuccesses = 0
                consecutiveTransientFailures++
                val elevated = (currentDelayMs + 1_000L).coerceAtMost(config.maxInterVideoDelayMs)
                currentDelayMs = maxOf(config.baseInterVideoDelayMs, elevated)
                val retryAfterMs = retryAfterSeconds?.times(1000L)?.coerceIn(1_000L, 30_000L)
                pendingCooldownMs = maxOf(config.rateLimitCooldownMs, retryAfterMs ?: 0L)
            }
            VideoTranscriptStatus.TIMEOUT,
            VideoTranscriptStatus.NETWORK_ERROR,
            VideoTranscriptStatus.EXTRACTION_ERROR -> {
                consecutiveSuccesses = 0
                consecutiveTransientFailures++
                val step = config.transientBackoffStepMs * consecutiveTransientFailures.coerceAtMost(3)
                currentDelayMs = (currentDelayMs + step).coerceAtMost(config.maxInterVideoDelayMs)
            }
            VideoTranscriptStatus.UNAVAILABLE,
            VideoTranscriptStatus.ACCESS_ERROR,
            VideoTranscriptStatus.NO_CAPTIONS,
            VideoTranscriptStatus.CANCELLED -> {
                // Non-throttling outcomes do not escalate request pacing
            }
        }
    }

    fun consumeNextDelayMs(): Long {
        val next = maxOf(currentDelayMs, pendingCooldownMs).coerceAtLeast(0L)
        pendingCooldownMs = 0L
        return next
    }
}

class YouTubeTranscriptExtractor(
    private val dataSource: YouTubeTranscriptDataSource,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val playlistInterVideoDelayMs: Long = PlaylistResilienceConfig.DEFAULT_INTER_VIDEO_DELAY_MS,
    private val resilienceConfig: PlaylistResilienceConfig = PlaylistResilienceConfig(
        baseInterVideoDelayMs = playlistInterVideoDelayMs.coerceAtLeast(0L)
    ),
    private val isOnlineServicesPaused: () -> Boolean = { false },
) {
    private val tag = "YTTranscriptExtractor"

    suspend fun extract(
        rawUrl: String,
        preference: TranscriptTargetPreference? = null,
        onProgress: suspend (TranscriptExtractionProgress) -> Unit = {}
    ): TranscriptExtractionOutcome = withContext(ioDispatcher) {
        if (isOnlineServicesPaused()) {
            val msg = "Transcript extraction is temporarily paused during maintenance."
            onProgress(TranscriptExtractionProgress(stage = TranscriptExtractionStage.FAILED, message = msg))
            return@withContext TranscriptExtractionOutcome.Failed(msg)
        }
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

    /**
     * Retries only failed/retryable entries in an existing [TranscriptDocument], preserving all
     * already extracted transcripts and non-retryable terminal entries without re-fetching them.
     */
    suspend fun retryFailedEntries(
        existingDocument: TranscriptDocument,
        onProgress: suspend (TranscriptExtractionProgress) -> Unit = {}
    ): TranscriptExtractionOutcome = withContext(ioDispatcher) {
        if (!existingDocument.isPlaylist) {
            val target = YouTubeUrlParser.parse(existingDocument.sourceUrl)
            val videoUrl = when (target) {
                is YouTubeUrlTarget.SingleVideo -> target.canonicalUrl
                is YouTubeUrlTarget.VideoInPlaylist -> target.videoCanonicalUrl
                else -> existingDocument.entries.firstOrNull()?.url ?: existingDocument.sourceUrl
            }
            val videoId = when (target) {
                is YouTubeUrlTarget.SingleVideo -> target.videoId
                is YouTubeUrlTarget.VideoInPlaylist -> target.videoId
                else -> existingDocument.entries.firstOrNull()?.videoId.orEmpty()
            }
            if (videoId.isBlank()) {
                return@withContext TranscriptExtractionOutcome.Failed("Cannot determine video to retry.")
            }
            return@withContext extractSingleVideo(
                videoId = videoId,
                videoUrl = videoUrl,
                sourceUrl = existingDocument.sourceUrl,
                processId = "transcript_retry_${clock()}_${UUID.randomUUID().toString().take(6)}",
                onProgress = onProgress
            )
        }

        val retryIndices = existingDocument.entries.mapIndexedNotNull { idx, entry ->
            if (entry.isRetryableFailure) idx else null
        }
        if (retryIndices.isEmpty()) {
            return@withContext TranscriptExtractionOutcome.Completed(existingDocument)
        }

        val processRoot = "transcript_retry_${clock()}_${UUID.randomUUID().toString().take(6)}"
        val updatedEntries = existingDocument.entries.toMutableList()
        val pacing = PlaylistPacingController(resilienceConfig)
        val totalRetryCount = retryIndices.size
        var activeSubProcessId: String? = null

        var successCount = updatedEntries.count { it.status == VideoTranscriptStatus.SUCCESS }
        var noCaptionsCount = updatedEntries.count { it.status == VideoTranscriptStatus.NO_CAPTIONS }
        var failedCount = updatedEntries.count {
            it.status != VideoTranscriptStatus.SUCCESS && it.status != VideoTranscriptStatus.NO_CAPTIONS
        }

        try {
            for ((retryOrdinal, entryIdx) in retryIndices.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (retryOrdinal > 0) {
                    val paceDelay = pacing.consumeNextDelayMs()
                    if (paceDelay > 0L) {
                        delay(paceDelay)
                        currentCoroutineContext().ensureActive()
                    }
                }

                val existingEntry = updatedEntries[entryIdx]
                val retryHuman = retryOrdinal + 1
                onProgress(
                    TranscriptExtractionProgress(
                        stage = TranscriptExtractionStage.EXTRACTING_VIDEO,
                        currentVideoIndex = retryHuman,
                        totalVideos = totalRetryCount,
                        currentVideoTitle = existingEntry.title,
                        successfulCount = successCount,
                        noCaptionsCount = noCaptionsCount,
                        failedCount = failedCount,
                        message = "Retrying failed video $retryHuman of $totalRetryCount"
                    )
                )

                val summary = PlaylistEntrySummary(
                    position = existingEntry.position,
                    videoId = existingEntry.videoId,
                    title = existingEntry.title,
                    url = existingEntry.url,
                    channel = existingEntry.channel,
                    durationSeconds = existingEntry.durationSeconds,
                    thumbnailUrl = existingEntry.thumbnailUrl
                )
                val subProcId = "${processRoot}_v${existingEntry.position}"
                activeSubProcessId = subProcId
                val preferAltFirst = existingEntry.status == VideoTranscriptStatus.TIMEOUT ||
                    existingEntry.status == VideoTranscriptStatus.EXTRACTION_ERROR ||
                    existingEntry.diagnosticInfo?.failureClassification in setOf(
                        TranscriptFailureClassification.PLAYER_CLIENT_FAILURE,
                        TranscriptFailureClassification.METADATA_OPERATION_DEADLINE,
                        TranscriptFailureClassification.STALLED_PROGRESS_TIMEOUT,
                        TranscriptFailureClassification.DAEMON_INACTIVITY_TIMEOUT,
                        TranscriptFailureClassification.JS_CHALLENGE_TIMEOUT
                    )
                var observedRetryAfterSeconds: Long? = null
                val refreshed = try {
                    extractSingleEntryIsolated(
                        position = existingEntry.position,
                        videoId = existingEntry.videoId,
                        videoUrl = existingEntry.url.ifBlank { YouTubeUrlParser.canonicalVideoUrl(existingEntry.videoId) },
                        fallbackSummary = summary,
                        processId = subProcId,
                        allowAlternativeRecovery = true,
                        preferAlternativeFirst = preferAltFirst,
                        onRetryAfterObserved = { retryAfter -> observedRetryAfterSeconds = retryAfter },
                        onRetryStatus = { customMsg ->
                            onProgress(
                                TranscriptExtractionProgress(
                                    stage = TranscriptExtractionStage.EXTRACTING_VIDEO,
                                    currentVideoIndex = retryHuman,
                                    totalVideos = totalRetryCount,
                                    currentVideoTitle = existingEntry.title,
                                    successfulCount = successCount,
                                    noCaptionsCount = noCaptionsCount,
                                    failedCount = failedCount,
                                    message = customMsg ?: "Retrying video $retryHuman of $totalRetryCount after transient delay..."
                                )
                            )
                        }
                    )
                } finally {
                    activeSubProcessId = null
                }

                updatedEntries[entryIdx] = refreshed
                pacing.recordOutcome(refreshed.status, retryAfterSeconds = observedRetryAfterSeconds)

                successCount = updatedEntries.count { it.status == VideoTranscriptStatus.SUCCESS }
                noCaptionsCount = updatedEntries.count { it.status == VideoTranscriptStatus.NO_CAPTIONS }
                failedCount = updatedEntries.count {
                    it.status != VideoTranscriptStatus.SUCCESS && it.status != VideoTranscriptStatus.NO_CAPTIONS
                }
            }
        } catch (ce: CancellationException) {
            var partialDoc: TranscriptDocument? = null
            withContext(NonCancellable) {
                activeSubProcessId?.let { dataSource.cancelProcess(it) }
                partialDoc = buildDocument(
                    isPlaylist = true,
                    title = existingDocument.title,
                    sourceUrl = existingDocument.sourceUrl,
                    entries = updatedEntries,
                    totalFoundOverride = existingDocument.videosFound
                )
                onProgress(
                    TranscriptExtractionProgress(
                        stage = TranscriptExtractionStage.CANCELLED,
                        currentVideoIndex = retryIndices.size,
                        totalVideos = totalRetryCount,
                        successfulCount = partialDoc!!.successfulCount,
                        noCaptionsCount = partialDoc!!.noCaptionsCount,
                        failedCount = partialDoc!!.inaccessibleCount,
                        message = "Retry cancelled; preserved ${partialDoc!!.successfulCount} extracted transcripts."
                    )
                )
            }
            return@withContext TranscriptExtractionOutcome.Cancelled(partialDoc)
        }

        val doc = buildDocument(
            isPlaylist = true,
            title = existingDocument.title,
            sourceUrl = existingDocument.sourceUrl,
            entries = updatedEntries,
            totalFoundOverride = existingDocument.videosFound
        )
        onProgress(
            TranscriptExtractionProgress(
                stage = TranscriptExtractionStage.COMPLETED,
                currentVideoIndex = totalRetryCount,
                totalVideos = totalRetryCount,
                successfulCount = doc.successfulCount,
                noCaptionsCount = doc.noCaptionsCount,
                failedCount = doc.inaccessibleCount,
                message = "Retry complete: ${doc.successfulCount} of ${doc.videosFound} transcripts extracted."
            )
        )
        TranscriptExtractionOutcome.Completed(doc)
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
                processId = "${processId}_v1",
                allowAlternativeRecovery = true,
                onRetryStatus = { customMsg ->
                    onProgress(
                        TranscriptExtractionProgress(
                            stage = TranscriptExtractionStage.EXTRACTING_VIDEO,
                            currentVideoIndex = 1,
                            totalVideos = 1,
                            message = customMsg ?: "Retrying metadata retrieval..."
                        )
                    )
                }
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
        val pacing = PlaylistPacingController(resilienceConfig)
        var successCount = 0
        var noCaptionsCount = 0
        var failedCount = 0
        var activeSubProcessId: String? = null

        try {
            for ((idx, summary) in playlistResolution.entries.withIndex()) {
                currentCoroutineContext().ensureActive()

                if (idx > 0) {
                    val paceDelay = pacing.consumeNextDelayMs()
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

                var observedRetryAfterSeconds: Long? = null
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
                            playlistPosition = summary.position,
                            ytDlpVersion = dataSource.currentYtDlpVersion(),
                            errorCategory = summary.unavailabilityStatus,
                            failureClassification = if (summary.unavailabilityStatus == VideoTranscriptStatus.ACCESS_ERROR) {
                                TranscriptFailureClassification.YOUTUBE_HTTP_403
                            } else {
                                TranscriptFailureClassification.VIDEO_UNAVAILABLE
                            },
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
                            processId = subProcId,
                            onRetryAfterObserved = { retryAfter -> observedRetryAfterSeconds = retryAfter },
                            onRetryStatus = { customMsg ->
                                onProgress(
                                    TranscriptExtractionProgress(
                                        stage = TranscriptExtractionStage.EXTRACTING_VIDEO,
                                        currentVideoIndex = humanPos,
                                        totalVideos = totalVideos,
                                        currentVideoTitle = summary.title,
                                        successfulCount = successCount,
                                        noCaptionsCount = noCaptionsCount,
                                        failedCount = failedCount,
                                        message = customMsg ?: "Retrying video $humanPos of $totalVideos..."
                                    )
                                )
                            }
                        )
                    } finally {
                        activeSubProcessId = null
                    }
                }

                pacing.recordOutcome(
                    status = entry.status,
                    retryAfterSeconds = observedRetryAfterSeconds,
                    wasListingUnavailable = summary.unavailabilityStatus != null
                )
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
        processId: String,
        allowAlternativeRecovery: Boolean = false,
        preferAlternativeFirst: Boolean = false,
        onRetryAfterObserved: (Long?) -> Unit = {},
        onRetryStatus: suspend (String?) -> Unit = {}
    ): VideoTranscriptEntry {
        return try {
            extractSingleEntry(
                position = position,
                videoId = videoId,
                videoUrl = videoUrl,
                fallbackSummary = fallbackSummary,
                processId = processId,
                allowAlternativeRecovery = allowAlternativeRecovery,
                preferAlternativeFirst = preferAlternativeFirst,
                onRetryAfterObserved = onRetryAfterObserved,
                onRetryStatus = onRetryStatus
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val dnsCause = findCauseInChain<UnknownHostException>(t)
            val tlsCause = findCauseInChain<SSLException>(t)
            val connectCause = findCauseInChain<ConnectException>(t)
            val socketTimeoutCause = findCauseInChain<SocketTimeoutException>(t)
            val status = when {
                t is InternalTimeoutException || socketTimeoutCause != null -> VideoTranscriptStatus.TIMEOUT
                t is HttpStatusException -> mapHttpStatus(t.code)
                dnsCause != null || tlsCause != null || connectCause != null || t is IOException -> VideoTranscriptStatus.NETWORK_ERROR
                else -> VideoTranscriptStatus.EXTRACTION_ERROR
            }
            if (t is HttpStatusException && t.code == 429) {
                onRetryAfterObserved(t.retryAfterSeconds)
            }
            val nowMs = clock()
            val msg = TranscriptRedactor.sanitize(t.message ?: status.displayLabel, 220)
            val diag = TranscriptDiagnosticInfo(
                videoId = videoId,
                stage = "entry_isolation",
                playlistPosition = position,
                ytDlpVersion = dataSource.currentYtDlpVersion(),
                errorCategory = status,
                exceptionType = describeTranscriptExceptionType(t),
                exceptionChain = buildTranscriptExceptionChain(t),
                httpStatusCode = (t as? HttpStatusException)?.code,
                retryAfterSeconds = (t as? HttpStatusException)?.retryAfterSeconds,
                endedAtMillis = nowMs,
                sanitizedDetail = msg,
                failureClassification = when {
                    t is InternalTimeoutException -> TranscriptFailureClassification.METADATA_OPERATION_DEADLINE
                    socketTimeoutCause != null -> TranscriptFailureClassification.YT_DLP_NETWORK_TIMEOUT
                    dnsCause != null -> TranscriptFailureClassification.DNS_RESOLUTION_FAILURE
                    tlsCause != null -> TranscriptFailureClassification.TLS_HANDSHAKE_FAILURE
                    connectCause != null -> TranscriptFailureClassification.CONNECTION_FAILURE
                    t is HttpStatusException -> when (t.code) {
                        429 -> TranscriptFailureClassification.YOUTUBE_HTTP_429
                        401, 403 -> TranscriptFailureClassification.YOUTUBE_HTTP_403
                        404, 410 -> TranscriptFailureClassification.VIDEO_UNAVAILABLE
                        in 500..599 -> TranscriptFailureClassification.YOUTUBE_HTTP_5XX
                        else -> TranscriptFailureClassification.PLAYER_CLIENT_FAILURE
                    }
                    t is IOException -> TranscriptFailureClassification.YT_DLP_NETWORK_TIMEOUT
                    else -> TranscriptFailureClassification.PLAYER_CLIENT_FAILURE
                }
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
        processId: String,
        allowAlternativeRecovery: Boolean = false,
        preferAlternativeFirst: Boolean = false,
        onRetryAfterObserved: (Long?) -> Unit = {},
        onRetryStatus: suspend (String?) -> Unit = {}
    ): VideoTranscriptEntry {
        val startedAt = clock()
        val retryOutcome = fetchVideoInfoWithBoundedRetry(
            videoId = videoId,
            videoUrl = videoUrl,
            processId = processId,
            allowAlternativeRecovery = allowAlternativeRecovery,
            preferAlternativeFirst = preferAlternativeFirst,
            onRetryStarting = onRetryStatus
        )
        val infoRetries = retryOutcome.retriesExecuted
        val infoResult = retryOutcome.result

        val info = when (infoResult) {
            is VideoInfoFetchResult.Success -> infoResult.info
            is VideoInfoFetchResult.Failed -> {
                if (infoResult.status == VideoTranscriptStatus.RATE_LIMITED) {
                    onRetryAfterObserved(infoResult.retryAfterSeconds)
                }
                val endedAt = clock()
                val baseDiag = infoResult.diagnosticInfo
                val effectiveStartedAt = baseDiag?.startedAtMillis ?: startedAt
                val diag = (baseDiag ?: TranscriptDiagnosticInfo(
                    videoId = videoId,
                    stage = "video_metadata",
                    ytDlpVersion = dataSource.currentYtDlpVersion(),
                    errorCategory = infoResult.status,
                    elapsedMs = (endedAt - effectiveStartedAt).coerceAtLeast(0L),
                    sanitizedDetail = TranscriptRedactor.sanitize(infoResult.message)
                )).copy(
                    playlistPosition = position,
                    retryCount = infoRetries,
                    attemptNumber = 1 + infoRetries,
                    startedAtMillis = effectiveStartedAt,
                    endedAtMillis = baseDiag?.endedAtMillis ?: endedAt,
                    elapsedMs = (endedAt - effectiveStartedAt).coerceAtLeast(0L),
                    retryAfterSeconds = baseDiag?.retryAfterSeconds ?: infoResult.retryAfterSeconds,
                    retryReasons = retryOutcome.retryReasons
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

        val rankedSources = CaptionLanguageSelector.selectRankedSources(info.captionTracks)
        if (rankedSources.isEmpty()) {
            val endedAt = clock()
            val diag = TranscriptDiagnosticInfo(
                videoId = info.videoId,
                stage = "track_selection",
                playlistPosition = position,
                ytDlpVersion = dataSource.currentYtDlpVersion(),
                playerClient = info.playerClientUsed,
                effectivePlayerClient = info.playerClientUsed,
                errorCategory = VideoTranscriptStatus.EXTRACTION_ERROR,
                startedAtMillis = startedAt,
                endedAtMillis = endedAt,
                elapsedMs = (endedAt - startedAt).coerceAtLeast(0L),
                retryCount = infoRetries,
                attemptNumber = 1 + infoRetries,
                retryReasons = retryOutcome.retryReasons,
                failureClassification = TranscriptFailureClassification.CAPTION_TRACKS_MISSING,
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

        // Restrict fallback candidates to the top track's language family (avoiding cross-language mixups),
        // e.g. trying 'en-orig' after an auto-translated 'en' (tlang=en) track hits HTTP 429/403.
        val topLangNorm = rankedSources.first().track.languageCode.trim().lowercase()
        val topBaseLang = topLangNorm.substringBefore('-')
        val candidateSources = rankedSources.filter { src ->
            val norm = src.track.languageCode.trim().lowercase()
            if (topBaseLang == "en") {
                norm == "en" || norm == "en-orig" || norm.startsWith("en-")
            } else {
                norm == topLangNorm || norm.substringBefore('-') == topBaseLang
            }
        }.ifEmpty { listOf(rankedSources.first()) }.take(3)
        val primarySelected = candidateSources.first()

        var lastFetchError: Throwable? = null
        var lastParseError: String? = null
        var captionRetries = 0
        val preferredFormatOrder = listOf("json3", "vtt", "srv3", "srv1", "ttml", "srt")

        for (candidate in candidateSources) {
            currentCoroutineContext().ensureActive()

            // Build ordered formats (inline native content first if present, then HTTP format order)
            val formatOrder = buildList {
                add(candidate.format to candidate.url)
                for (fmt in preferredFormatOrder) {
                    val hasInline = !candidate.track.inlineContentByFormat[fmt].isNullOrBlank()
                    val altUrl = candidate.track.formatUrls[fmt]
                    if ((hasInline || !altUrl.isNullOrBlank()) && fmt != candidate.format) {
                        add(fmt to (altUrl ?: "native://${candidate.track.languageCode}.$fmt"))
                    }
                }
            }

            var skipRemainingFormatsForRateLimitedTranslation = false
            for ((fmt, url) in formatOrder) {
                currentCoroutineContext().ensureActive()

                val inlinePayload = candidate.track.inlineContentByFormat[fmt]
                val isAutoTranslatedUrl = CaptionLanguageSelector.hasAutoTranslateParam(url)
                val payload: String? = if (!inlinePayload.isNullOrBlank()) {
                    inlinePayload
                } else if (url.startsWith("http://") || url.startsWith("https://")) {
                    val payloadResult = fetchCaptionPayloadWithRetry(
                        url = url,
                        allowRateLimitRetry = !isAutoTranslatedUrl || candidateSources.size == 1,
                        onRetry = { msg ->
                            captionRetries++
                            onRetryStatus(msg)
                        }
                    )
                    if (payloadResult.isFailure) {
                        lastFetchError = payloadResult.exceptionOrNull()
                        val httpErr = lastFetchError as? HttpStatusException
                        if (httpErr?.code == 429 && isAutoTranslatedUrl && candidateSources.size > 1) {
                            skipRemainingFormatsForRateLimitedTranslation = true
                        }
                        null
                    } else {
                        payloadResult.getOrNull()
                    }
                } else {
                    null
                }

                if (skipRemainingFormatsForRateLimitedTranslation) {
                    break
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
                                captionLanguage = CaptionLanguageSelector.formatDisplayLanguage(candidate.track),
                                captionLanguageCode = candidate.track.languageCode,
                                captionType = if (candidate.track.isAutoGenerated) CaptionType.AUTOMATIC else CaptionType.MANUAL,
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
        }

        // Fallback to tight native yt-dlp subtitle download when HTTP timedtext download failed with 401/403/429 or unparseable output
        val hadNoInlineContent = candidateSources.all { it.track.inlineContentByFormat.isEmpty() }
        val lastHttpStatusCode = (lastFetchError as? HttpStatusException)?.code
        val shouldTryNativeFallback = hadNoInlineContent &&
            (lastHttpStatusCode in setOf(401, 403, 429) || lastParseError != null)
        var captionRecoveryOutcome: String? = null
        if (shouldTryNativeFallback) {
            currentCoroutineContext().ensureActive()
            val nativeRes = dataSource.fetchNativeSubtitles(
                videoId = info.videoId,
                videoUrl = info.url,
                processId = "${processId}_sub",
                preferredLangSpec = buildTightNativeLangSpec(candidateSources.map { it.track }),
                playerClientOverride = LaterboxYouTubeTranscriptDataSource.NATIVE_SUBTITLE_PLAYER_CLIENTS
            )
            captionRecoveryOutcome = when (nativeRes) {
                is VideoInfoFetchResult.Success -> "native_caption_recovery:tracks(${nativeRes.info.captionTracks.size})"
                is VideoInfoFetchResult.Failed -> "native_caption_recovery:${nativeRes.status.name.lowercase()}"
            }
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

        val httpException = lastFetchError as? HttpStatusException
        if (httpException?.code == 429) {
            onRetryAfterObserved(httpException.retryAfterSeconds)
        }

        val endedAt = clock()
        // Caption tracks WERE listed, so never turn failure/unparseable payload into a misleading NO_CAPTIONS!
        val (status, message, failureClass) = classifyCaptionDownloadFailure(lastFetchError, lastParseError)
        val diag = TranscriptDiagnosticInfo(
            videoId = info.videoId,
            stage = "caption_download",
            playlistPosition = position,
            ytDlpVersion = dataSource.currentYtDlpVersion(),
            playerClient = info.playerClientUsed,
            effectivePlayerClient = info.playerClientUsed,
            errorCategory = status,
            exceptionType = describeTranscriptExceptionType(lastFetchError),
            exceptionChain = buildTranscriptExceptionChain(lastFetchError),
            httpStatusCode = httpException?.code,
            retryAfterSeconds = httpException?.retryAfterSeconds,
            startedAtMillis = startedAt,
            endedAtMillis = endedAt,
            elapsedMs = (endedAt - startedAt).coerceAtLeast(0L),
            retryCount = infoRetries + captionRetries,
            attemptNumber = 1 + infoRetries + captionRetries,
            executionEngine = "http_timedtext",
            failureClassification = failureClass,
            retryReasons = retryOutcome.retryReasons,
            recoveryRouteOutcome = captionRecoveryOutcome,
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
            captionLanguage = CaptionLanguageSelector.formatDisplayLanguage(primarySelected.track),
            captionLanguageCode = primarySelected.track.languageCode,
            captionType = if (primarySelected.track.isAutoGenerated) CaptionType.AUTOMATIC else CaptionType.MANUAL,
            status = status,
            statusMessage = message,
            diagnosticInfo = diag
        )
    }

    internal fun buildTightNativeLangSpec(tracks: List<CaptionTrackCandidate>): String {
        val codes = LinkedHashSet<String>()
        tracks.forEach { tr ->
            val c = tr.languageCode.trim()
            if (c.isNotBlank() && !c.contains('*')) {
                codes.add(c)
            }
        }
        val firstBase = tracks.firstOrNull()?.languageCode?.trim()?.lowercase()?.substringBefore('-')
        if (firstBase.isNullOrBlank() || firstBase == "en") {
            listOf("en-orig", "en", "en-US", "en-GB", "en-CA", "en-AU").forEach { codes.add(it) }
        } else {
            codes.add(firstBase)
            codes.add("en-orig")
            codes.add("en")
        }
        codes.add("-live_chat")
        return codes.joinToString(",")
    }

    private data class CaptionFailureMapping(
        val status: VideoTranscriptStatus,
        val message: String,
        val classification: String
    )

    private fun classifyCaptionDownloadFailure(
        lastFetchError: Throwable?,
        lastParseError: String?
    ): CaptionFailureMapping {
        if (lastFetchError == null) {
            return CaptionFailureMapping(
                status = VideoTranscriptStatus.EXTRACTION_ERROR,
                message = lastParseError ?: "Caption data was returned in an empty or unsupported format.",
                classification = TranscriptFailureClassification.MALFORMED_CAPTION_DATA
            )
        }
        if (lastFetchError is InternalTimeoutException || findCauseInChain<SocketTimeoutException>(lastFetchError) != null) {
            return CaptionFailureMapping(
                status = VideoTranscriptStatus.TIMEOUT,
                message = "Timed out while downloading captions.",
                classification = TranscriptFailureClassification.YT_DLP_NETWORK_TIMEOUT
            )
        }
        if (findCauseInChain<UnknownHostException>(lastFetchError) != null) {
            return CaptionFailureMapping(
                status = VideoTranscriptStatus.NETWORK_ERROR,
                message = "DNS resolution failed while downloading captions.",
                classification = TranscriptFailureClassification.DNS_RESOLUTION_FAILURE
            )
        }
        if (findCauseInChain<SSLException>(lastFetchError) != null) {
            return CaptionFailureMapping(
                status = VideoTranscriptStatus.NETWORK_ERROR,
                message = "TLS handshake failed while downloading captions.",
                classification = TranscriptFailureClassification.TLS_HANDSHAKE_FAILURE
            )
        }
        if (findCauseInChain<ConnectException>(lastFetchError) != null) {
            return CaptionFailureMapping(
                status = VideoTranscriptStatus.NETWORK_ERROR,
                message = "Connection failed while downloading captions.",
                classification = TranscriptFailureClassification.CONNECTION_FAILURE
            )
        }
        return when (lastFetchError) {
            is HttpStatusException -> {
                val st = mapHttpStatus(lastFetchError.code)
                val msgLower = lastFetchError.message.lowercase()
                val cls = when (lastFetchError.code) {
                    429 -> TranscriptFailureClassification.YOUTUBE_HTTP_429
                    401, 403 -> if (msgLower.contains("verification") || msgLower.contains("html") || msgLower.contains("bot")) {
                        TranscriptFailureClassification.YOUTUBE_VERIFICATION_REQUIRED
                    } else {
                        TranscriptFailureClassification.YOUTUBE_HTTP_403
                    }
                    404, 410 -> TranscriptFailureClassification.VIDEO_UNAVAILABLE
                    in 500..599 -> TranscriptFailureClassification.YOUTUBE_HTTP_5XX
                    else -> TranscriptFailureClassification.CAPTION_DOWNLOAD_FAILURE
                }
                CaptionFailureMapping(
                    status = st,
                    message = TranscriptRedactor.sanitize(lastFetchError.message, 200),
                    classification = cls
                )
            }
            is IOException -> CaptionFailureMapping(
                status = VideoTranscriptStatus.NETWORK_ERROR,
                message = TranscriptRedactor.sanitize(
                    lastFetchError.message ?: "Network error while downloading captions.",
                    200
                ),
                classification = TranscriptFailureClassification.CAPTION_DOWNLOAD_FAILURE
            )
            else -> CaptionFailureMapping(
                status = VideoTranscriptStatus.EXTRACTION_ERROR,
                message = TranscriptRedactor.sanitize(
                    lastFetchError.message ?: "Failed to extract caption track.",
                    200
                ),
                classification = TranscriptFailureClassification.CAPTION_DOWNLOAD_FAILURE
            )
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

    private data class MetadataRetryOutcome(
        val result: VideoInfoFetchResult,
        val retriesExecuted: Int,
        val retryReasons: List<String>
    )

    internal fun isRetryableMetadataFailure(failed: VideoInfoFetchResult.Failed): Boolean {
        if (!failed.retryable) return false
        if (failed.status !in RETRYABLE_METADATA_STATUSES) return false

        val classification = failed.diagnosticInfo?.failureClassification
        if (classification != null && classification in NON_RETRYABLE_CLASSIFICATIONS) {
            return false
        }

        val lower = failed.message.lowercase()
        val detailLower = failed.diagnosticInfo?.sanitizedDetail?.lowercase().orEmpty()
        val combined = "$lower $detailLower"
        if (NON_RETRYABLE_KEYWORDS.any { combined.contains(it) }) {
            return false
        }
        return true
    }

    internal fun computeMetadataRetryDelayMs(
        failed: VideoInfoFetchResult.Failed,
        retryIndex: Int
    ): Long {
        if (failed.status == VideoTranscriptStatus.RATE_LIMITED ||
            failed.diagnosticInfo?.httpStatusCode == 429
        ) {
            val retryAfterMs = failed.retryAfterSeconds?.times(1000L)
            if (retryAfterMs != null && retryAfterMs > 0L) {
                return retryAfterMs.coerceIn(1_000L, resilienceConfig.maxRateLimitRetryDelayMs)
            }
            val multiplier = 1L shl retryIndex.coerceIn(0, 3)
            return (resilienceConfig.baseRateLimitRetryDelayMs * multiplier)
                .coerceAtMost(resilienceConfig.maxRateLimitRetryDelayMs)
        }

        val multiplier = 1L shl retryIndex.coerceIn(0, 3)
        return (resilienceConfig.baseTransientRetryDelayMs * multiplier)
            .coerceAtMost(resilienceConfig.maxTransientRetryDelayMs)
    }

    private fun shouldInvokeAlternativeRecovery(failed: VideoInfoFetchResult.Failed): Boolean {
        val cls = failed.diagnosticInfo?.failureClassification
        if (cls in NON_RECOVERY_CLASSIFICATIONS) return false
        return failed.status == VideoTranscriptStatus.TIMEOUT ||
            cls in setOf(
                TranscriptFailureClassification.STALLED_PROGRESS_TIMEOUT,
                TranscriptFailureClassification.PLAYER_CLIENT_FAILURE,
                TranscriptFailureClassification.JS_CHALLENGE_TIMEOUT,
                TranscriptFailureClassification.DAEMON_INACTIVITY_TIMEOUT,
                TranscriptFailureClassification.METADATA_OPERATION_DEADLINE
            )
    }

    private suspend fun fetchVideoInfoWithBoundedRetry(
        videoId: String,
        videoUrl: String,
        processId: String,
        allowAlternativeRecovery: Boolean = false,
        preferAlternativeFirst: Boolean = false,
        onRetryStarting: suspend (String?) -> Unit = {}
    ): MetadataRetryOutcome {
        val retryReasons = mutableListOf<String>()

        if (preferAlternativeFirst) {
            val altFirst = dataSource.fetchVideoInfoAlternative(videoId, videoUrl, "${processId}_alt")
            if (altFirst is VideoInfoFetchResult.Success && altFirst.info.captionTracks.isNotEmpty()) {
                return MetadataRetryOutcome(result = altFirst, retriesExecuted = 0, retryReasons = retryReasons)
            }
            val altSummary = when (altFirst) {
                is VideoInfoFetchResult.Success -> "native_recovery:no_tracks"
                is VideoInfoFetchResult.Failed -> altFirst.diagnosticInfo?.recoveryRouteOutcome
                    ?: "native_recovery:${altFirst.status.name.lowercase()}"
            }
            retryReasons.add("Alternative route did not return captions ($altSummary); trying primary route")
            onRetryStarting(null)
            val fallbackPrimary = dataSource.fetchVideoInfo(videoId, videoUrl, "${processId}_pri")
            val mergedResult = when (fallbackPrimary) {
                is VideoInfoFetchResult.Success -> fallbackPrimary
                is VideoInfoFetchResult.Failed -> fallbackPrimary.diagnosticInfo?.let { d ->
                    fallbackPrimary.copy(diagnosticInfo = d.copy(recoveryRouteOutcome = altSummary))
                } ?: fallbackPrimary
            }
            return MetadataRetryOutcome(
                result = mergedResult,
                retriesExecuted = 1,
                retryReasons = retryReasons
            )
        }

        val maxRetries = resilienceConfig.maxMetadataRetries.coerceAtLeast(0)
        var current = dataSource.fetchVideoInfo(videoId, videoUrl, processId)
        var retriesExecuted = 0

        while (retriesExecuted < maxRetries && current is VideoInfoFetchResult.Failed && isRetryableMetadataFailure(current)) {
            currentCoroutineContext().ensureActive()
            val classification = current.diagnosticInfo?.failureClassification ?: current.status.name
            retryReasons.add("Attempt ${1 + retriesExecuted} failed: $classification")

            val waitMs = computeMetadataRetryDelayMs(current, retriesExecuted)
            retriesExecuted++
            val rateLimitedMsg = if (current.status == VideoTranscriptStatus.RATE_LIMITED ||
                current.diagnosticInfo?.httpStatusCode == 429
            ) {
                val secs = (waitMs / 1000L).coerceAtLeast(1L)
                "Rate-limited by YouTube (HTTP 429); retrying in ${secs}s..."
            } else {
                null
            }
            onRetryStarting(rateLimitedMsg)
            if (waitMs > 0L) {
                delay(waitMs)
                currentCoroutineContext().ensureActive()
            }
            current = dataSource.fetchVideoInfo(videoId, videoUrl, "${processId}_r$retriesExecuted")
        }

        if (allowAlternativeRecovery &&
            current is VideoInfoFetchResult.Failed &&
            shouldInvokeAlternativeRecovery(current)
        ) {
            currentCoroutineContext().ensureActive()
            val classification = current.diagnosticInfo?.failureClassification ?: current.status.name
            retryReasons.add("Attempt ${1 + retriesExecuted} failed: $classification; invoking alternative recovery")
            onRetryStarting("Trying alternative YouTube client route...")
            retriesExecuted++
            val altResult = dataSource.fetchVideoInfoAlternative(videoId, videoUrl, "${processId}_alt")
            if (altResult is VideoInfoFetchResult.Success && altResult.info.captionTracks.isNotEmpty()) {
                current = altResult
            } else {
                val altSummary = when (altResult) {
                    is VideoInfoFetchResult.Success -> "native_recovery:no_tracks"
                    is VideoInfoFetchResult.Failed -> altResult.diagnosticInfo?.recoveryRouteOutcome
                        ?: "native_recovery:${altResult.status.name.lowercase()}"
                }
                current = current.diagnosticInfo?.let { d ->
                    current.copy(diagnosticInfo = d.copy(recoveryRouteOutcome = altSummary))
                } ?: current
            }
        }

        return MetadataRetryOutcome(
            result = current,
            retriesExecuted = retriesExecuted,
            retryReasons = retryReasons
        )
    }

    private suspend fun fetchCaptionPayloadWithRetry(
        url: String,
        allowRateLimitRetry: Boolean = true,
        onRetry: suspend (String?) -> Unit
    ): Result<String> {
        var attemptResult = dataSource.fetchCaptionPayload(url)
        if (!allowRateLimitRetry) {
            return attemptResult
        }
        val delaysMs = listOf(1_200L, 2_500L)
        for (waitMs in delaysMs) {
            val ex = attemptResult.exceptionOrNull()
            if (ex is HttpStatusException && ex.code == 429) {
                val maxAllowedMs = resilienceConfig.maxRateLimitRetryDelayMs.coerceAtLeast(1L)
                val retryAfterMs = (ex.retryAfterSeconds?.times(1000L) ?: waitMs)
                    .coerceIn(minOf(500L, maxAllowedMs), maxAllowedMs)
                onRetry("Rate-limited by YouTube while loading captions (HTTP 429); retrying...")
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
                "Transcript failure video=${diag.videoId} pos=${diag.playlistPosition ?: "-"} " +
                    "stage=${diag.stage} lastStage=${diag.lastKnownStage ?: "-"} " +
                    "lastActAgeMs=${diag.lastMeaningfulActivityAgeMs ?: "-"} stalled=${diag.progressStalled ?: "-"} " +
                    "status=${diag.errorCategory} class=${diag.failureClassification ?: "-"} " +
                    "engine=${diag.executionEngine ?: "-"} warmHealthy=${diag.warmDaemonHealthyAtStart ?: "-"} " +
                    "fallback=${if (diag.fallbackAttempted) (diag.fallbackResult ?: "yes") else "no"} " +
                    "client=${diag.playerClient ?: "-"} effClient=${diag.effectivePlayerClient ?: "-"} " +
                    "ytdlp=${diag.ytDlpVersion ?: "-"} qjs=${diag.quickJsIdentity ?: "-"}/${diag.quickJsVersion ?: "-"} " +
                    "ejs=${diag.ejsSolverAvailable ?: "-"} " +
                    "primary=${diag.primaryRouteOutcome ?: "-"} watchHtml=${diag.watchHtmlOutcome ?: "-"} " +
                    "recovery=${diag.recoveryRouteOutcome ?: "-"} " +
                    "http=${diag.httpStatusCode ?: "-"} retryAfter=${diag.retryAfterSeconds ?: "-"} " +
                    "ex=${diag.exceptionType ?: "-"} chain=${diag.exceptionChain ?: "-"} " +
                    "attempt=${diag.attemptNumber} retries=${diag.retryCount} " +
                    "timeoutMs=${diag.timeoutMs ?: "-"} extMs=${diag.deadlineExtendedMs ?: "-"} elapsedMs=${diag.elapsedMs ?: "-"} " +
                    "hb=${diag.heartbeatCount ?: "-"} jsCh=${diag.jsChallengesSolved ?: "-"} " +
                    "detail=${diag.sanitizedDetail ?: "-"}"
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

    companion object {
        private val RETRYABLE_METADATA_STATUSES = setOf(
            VideoTranscriptStatus.TIMEOUT,
            VideoTranscriptStatus.NETWORK_ERROR,
            VideoTranscriptStatus.RATE_LIMITED,
            VideoTranscriptStatus.EXTRACTION_ERROR
        )

        private val NON_RETRYABLE_CLASSIFICATIONS = setOf(
            TranscriptFailureClassification.STALLED_PROGRESS_TIMEOUT,
            TranscriptFailureClassification.DAEMON_INACTIVITY_TIMEOUT,
            TranscriptFailureClassification.METADATA_OPERATION_DEADLINE,
            TranscriptFailureClassification.JS_CHALLENGE_TIMEOUT,
            TranscriptFailureClassification.DNS_RESOLUTION_FAILURE,
            TranscriptFailureClassification.TLS_HANDSHAKE_FAILURE,
            TranscriptFailureClassification.PO_TOKEN_REQUIRED,
            TranscriptFailureClassification.CONSENT_WALL_REQUIRED,
            TranscriptFailureClassification.YOUTUBE_VERIFICATION_REQUIRED,
            TranscriptFailureClassification.YOUTUBE_HTTP_403,
            TranscriptFailureClassification.VIDEO_UNAVAILABLE,
            TranscriptFailureClassification.CAPTION_TRACKS_MISSING,
            TranscriptFailureClassification.USER_CANCELLED
        )

        private val NON_RECOVERY_CLASSIFICATIONS = setOf(
            TranscriptFailureClassification.DNS_RESOLUTION_FAILURE,
            TranscriptFailureClassification.TLS_HANDSHAKE_FAILURE,
            TranscriptFailureClassification.PO_TOKEN_REQUIRED,
            TranscriptFailureClassification.YOUTUBE_VERIFICATION_REQUIRED,
            TranscriptFailureClassification.CONSENT_WALL_REQUIRED,
            TranscriptFailureClassification.YOUTUBE_HTTP_403,
            TranscriptFailureClassification.VIDEO_UNAVAILABLE
        )

        private val NON_RETRYABLE_KEYWORDS = listOf(
            "po token",
            "not a bot",
            "confirm you're not",
            "confirm you’re not",
            "private video",
            "members-only",
            "join this channel",
            "sign in to confirm",
            "video is private",
            "does not exist",
            "has been removed",
            "account associated with this video has been terminated"
        )
    }
}
