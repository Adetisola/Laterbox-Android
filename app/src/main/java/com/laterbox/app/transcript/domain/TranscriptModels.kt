package com.laterbox.app.transcript.domain

import androidx.annotation.Keep

@Keep
enum class CaptionType(val displayLabel: String) {
    MANUAL("Manual"),
    AUTOMATIC("Automatic"),
    UNKNOWN("Unknown")
}

@Keep
enum class VideoTranscriptStatus(val displayLabel: String) {
    SUCCESS("Successful"),
    NO_CAPTIONS("Transcript unavailable"),
    UNAVAILABLE("Video unavailable"),
    ACCESS_ERROR("Could not be accessed"),
    NETWORK_ERROR("Network error"),
    TIMEOUT("Timed out"),
    RATE_LIMITED("Temporarily busy"),
    EXTRACTION_ERROR("Extraction failed"),
    CANCELLED("Cancelled")
}

@Keep
object TranscriptFailureClassification {
    const val DAEMON_TRANSPORT_FAILURE = "DAEMON_TRANSPORT_FAILURE"
    const val DAEMON_INACTIVITY_TIMEOUT = "DAEMON_INACTIVITY_TIMEOUT"
    const val STALLED_PROGRESS_TIMEOUT = "STALLED_PROGRESS_TIMEOUT"
    const val METADATA_OPERATION_DEADLINE = "METADATA_OPERATION_DEADLINE"
    const val YT_DLP_NETWORK_TIMEOUT = "YT_DLP_NETWORK_TIMEOUT"
    const val DNS_RESOLUTION_FAILURE = "DNS_RESOLUTION_FAILURE"
    const val TLS_HANDSHAKE_FAILURE = "TLS_HANDSHAKE_FAILURE"
    const val CONNECTION_FAILURE = "CONNECTION_FAILURE"
    const val YOUTUBE_HTTP_403 = "YOUTUBE_HTTP_403"
    const val YOUTUBE_HTTP_429 = "YOUTUBE_HTTP_429"
    const val YOUTUBE_HTTP_5XX = "YOUTUBE_HTTP_5XX"
    const val PLAYER_CLIENT_FAILURE = "PLAYER_CLIENT_FAILURE"
    const val JS_CHALLENGE_TIMEOUT = "JS_CHALLENGE_TIMEOUT"
    const val PO_TOKEN_REQUIRED = "PO_TOKEN_REQUIRED"
    const val YOUTUBE_VERIFICATION_REQUIRED = "YOUTUBE_VERIFICATION_REQUIRED"
    const val CONSENT_WALL_REQUIRED = "CONSENT_WALL_REQUIRED"
    const val WATCH_HTML_EMPTY = "WATCH_HTML_EMPTY"
    const val WATCH_HTML_MISSING_PLAYER_RESPONSE = "WATCH_HTML_MISSING_PLAYER_RESPONSE"
    const val WATCH_HTML_MALFORMED_JSON = "WATCH_HTML_MALFORMED_JSON"
    const val CAPTION_TRACKS_MISSING = "CAPTION_TRACKS_MISSING"
    const val CAPTION_DOWNLOAD_FAILURE = "CAPTION_DOWNLOAD_FAILURE"
    const val MALFORMED_CAPTION_DATA = "MALFORMED_CAPTION_DATA"
    const val VIDEO_UNAVAILABLE = "VIDEO_UNAVAILABLE"
    const val USER_CANCELLED = "USER_CANCELLED"
}

@Keep
data class TranscriptDiagnosticInfo(
    val videoId: String,
    val stage: String,
    val ytDlpVersion: String? = null,
    val playerClient: String? = null,
    val errorCategory: VideoTranscriptStatus,
    val exceptionType: String? = null,
    val exceptionChain: String? = null,
    val httpStatusCode: Int? = null,
    val retryAfterSeconds: Long? = null,
    val elapsedMs: Long? = null,
    val retryCount: Int = 0,
    val sanitizedDetail: String? = null,
    val playlistPosition: Int? = null,
    val executionEngine: String? = null,
    val attemptNumber: Int = 1,
    val startedAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
    val timeoutMs: Long? = null,
    val warmDaemonHealthyAtStart: Boolean? = null,
    val fallbackAttempted: Boolean = false,
    val fallbackResult: String? = null,
    val failureClassification: String? = null,
    val retryReasons: List<String> = emptyList(),
    val effectivePlayerClient: String? = null,
    val quickJsVersion: String? = null,
    val quickJsIdentity: String? = null,
    val ejsSolverAvailable: Boolean? = null,
    val ejsSolverVersion: String? = null,
    val isPlayerCachePatched: Boolean? = null,
    val daemonStartupMs: Long? = null,
    val socketWaitMs: Long? = null,
    val extractMs: Long? = null,
    val jsChallengesSolved: Int? = null,
    val heartbeatCount: Int? = null,
    val progressStalled: Boolean? = null,
    val lastKnownStage: String? = null,
    val lastMeaningfulEvent: String? = null,
    val lastMeaningfulActivityAgeMs: Long? = null,
    val recentDebugLogs: List<String> = emptyList(),
    val warningsCount: Int? = null,
    val lastWarning: String? = null,
    val deadlineExtendedMs: Long? = null,
    val primaryRouteOutcome: String? = null,
    val watchHtmlOutcome: String? = null,
    val recoveryRouteOutcome: String? = null
)

data class TranscriptSegment(
    val startMs: Long? = null,
    val endMs: Long? = null,
    val text: String
)

data class TranscriptParagraph(
    val startMs: Long? = null,
    val text: String
)

data class VideoTranscriptEntry(
    val position: Int,
    val videoId: String,
    val title: String,
    val url: String,
    val channel: String? = null,
    val durationSeconds: Long? = null,
    val thumbnailUrl: String? = null,
    val captionLanguage: String? = null,
    val captionLanguageCode: String? = null,
    val captionType: CaptionType = CaptionType.UNKNOWN,
    val status: VideoTranscriptStatus,
    val statusMessage: String? = null,
    val segments: List<TranscriptSegment> = emptyList(),
    val paragraphs: List<TranscriptParagraph> = emptyList(),
    val diagnosticInfo: TranscriptDiagnosticInfo? = null
) {
    val hasTranscript: Boolean
        get() = status == VideoTranscriptStatus.SUCCESS && paragraphs.isNotEmpty()

    val isRetryableFailure: Boolean
        get() = !hasTranscript && status in RETRYABLE_FAILURE_STATUSES

    companion object {
        val RETRYABLE_FAILURE_STATUSES = setOf(
            VideoTranscriptStatus.TIMEOUT,
            VideoTranscriptStatus.NETWORK_ERROR,
            VideoTranscriptStatus.RATE_LIMITED,
            VideoTranscriptStatus.EXTRACTION_ERROR,
            VideoTranscriptStatus.CANCELLED
        )
    }
}

data class TranscriptDocument(
    val isPlaylist: Boolean,
    val title: String,
    val sourceUrl: String,
    val extractedAtMillis: Long,
    val videosFound: Int,
    val successfulCount: Int,
    val noCaptionsCount: Int,
    val inaccessibleCount: Int,
    val entries: List<VideoTranscriptEntry>
) {
    val hasAnySuccessfulTranscript: Boolean
        get() = successfulCount > 0 && entries.any { it.hasTranscript }

    val retryableFailedCount: Int
        get() = entries.count { it.isRetryableFailure }
}

data class CaptionTrackCandidate(
    val languageCode: String,
    val languageName: String? = null,
    val isAutoGenerated: Boolean,
    val formatUrls: Map<String, String>,
    val inlineContentByFormat: Map<String, String> = emptyMap()
)

data class FetchedVideoInfo(
    val videoId: String,
    val title: String,
    val url: String,
    val channel: String? = null,
    val durationSeconds: Long? = null,
    val thumbnailUrl: String? = null,
    val captionTracks: List<CaptionTrackCandidate> = emptyList(),
    val playerClientUsed: String? = null,
    val poTokenSkippedWarning: Boolean = false
)

data class PlaylistEntrySummary(
    val position: Int,
    val videoId: String,
    val title: String,
    val url: String,
    val channel: String? = null,
    val durationSeconds: Long? = null,
    val thumbnailUrl: String? = null,
    val unavailableReason: String? = null,
    val unavailabilityStatus: VideoTranscriptStatus? = null
)

data class PlaylistResolution(
    val playlistId: String,
    val title: String,
    val sourceUrl: String,
    val totalCountHint: Int? = null,
    val entries: List<PlaylistEntrySummary>
)

enum class TranscriptExtractionStage {
    IDLE,
    RESOLVING_TARGET,
    FETCHING_PLAYLIST,
    EXTRACTING_VIDEO,
    COMPLETED,
    CANCELLED,
    FAILED
}

data class TranscriptExtractionProgress(
    val stage: TranscriptExtractionStage = TranscriptExtractionStage.IDLE,
    val currentVideoIndex: Int = 0,
    val totalVideos: Int = 0,
    val currentVideoTitle: String? = null,
    val successfulCount: Int = 0,
    val noCaptionsCount: Int = 0,
    val failedCount: Int = 0,
    val message: String? = null
)

enum class ExportFormat(
    val extension: String,
    val mimeType: String,
    val displayName: String,
    val description: String
) {
    MARKDOWN(
        extension = "md",
        mimeType = "text/markdown",
        displayName = "Markdown (.md)",
        description = "Structured headings, contents links, metadata & timestamps"
    ),
    PLAIN_TEXT(
        extension = "txt",
        mimeType = "text/plain",
        displayName = "Plain text (.txt)",
        description = "Clean UTF-8 text readable in any editor without markup"
    ),
    PDF(
        extension = "pdf",
        mimeType = "application/pdf",
        displayName = "PDF (.pdf)",
        description = "Formatted document with page numbers & clickable video URLs"
    )
}

data class SingleExportOutcome(
    val format: ExportFormat,
    val filename: String,
    val success: Boolean,
    val uri: String? = null,
    val displayLocation: String? = null,
    val errorMessage: String? = null,
    val savedToLibrary: Boolean = false,
    val libraryAssetId: String? = null,
    val externalExportSucceeded: Boolean = false
)

data class MultiFormatExportResult(
    val outcomes: List<SingleExportOutcome>
) {
    val allSucceeded: Boolean
        get() = outcomes.isNotEmpty() && outcomes.all { it.success }

    val anySucceeded: Boolean
        get() = outcomes.any { it.success }

    val anyFailed: Boolean
        get() = outcomes.any { !it.success }

    val anySavedToLibrary: Boolean
        get() = outcomes.any { it.savedToLibrary }
}
