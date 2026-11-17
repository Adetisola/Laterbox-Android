package com.laterbox.app.movies.data.download

import com.laterbox.app.media.domain.model.AcquisitionSource
import com.laterbox.app.media.domain.model.CandidateStatus
import com.laterbox.app.media.domain.model.MediaCandidate
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.media.domain.repository.MediaRepository
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.PlaybackSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Enqueues authorised movie and TV series streams into Laterbox's native Downloads queue
 * at the user's chosen resolution without creating Inbox link clutter.
 */
class MoviesDownloadBridge(
    private val mediaRepository: MediaRepository
) {
    suspend fun enqueueAuthorisedDownload(
        summary: MovieTitleSummary,
        source: PlaybackSource,
        episodeLabel: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): Result<String> = runCatching {
        require(source.isAuthorised) {
            "Download is only enabled for authorised open-license media streams."
        }
        require(source.url.startsWith("http://") || source.url.startsWith("https://")) {
            "Invalid download stream URL."
        }
        val isAdaptiveManifest = source.format.equals("DASH", ignoreCase = true) ||
            source.format.equals("HLS", ignoreCase = true) ||
            source.url.contains(".mpd", ignoreCase = true) ||
            source.url.contains(".m3u8", ignoreCase = true)
        require(!isAdaptiveManifest) {
            "Adaptive DASH/HLS streams can only be streamed online. Choose a progressive MP4 source to download."
        }

        val displayTitle = buildString {
            append(summary.title)
            if (!episodeLabel.isNullOrBlank()) {
                append(" · ")
                append(episodeLabel)
            }
            if (source.qualityLabel.isNotBlank()) {
                append(" (${source.qualityLabel})")
            }
        }

        val estimatedBytes = source.sizeMb?.let { mb ->
            if (mb > 0.0) (mb * 1024.0 * 1024.0).toLong() else null
        }

        val ext = when {
            source.format.equals("MP4", ignoreCase = true) -> "mp4"
            source.format.equals("WEBM", ignoreCase = true) -> "webm"
            source.format.equals("MKV", ignoreCase = true) -> "mkv"
            else -> source.format.lowercase().ifBlank { "mp4" }
        }

        val candidateSuffix = if (seasonNumber != null && episodeNumber != null) {
            "_s${seasonNumber}_e${episodeNumber}"
        } else ""

        val candidate = MediaCandidate(
            candidateId = "movies_${summary.providerId}_${summary.id}${candidateSuffix}_${source.id}",
            mediaType = MediaType.VIDEO,
            formatLabel = "${source.qualityLabel} · ${source.format}",
            estimatedBytes = estimatedBytes,
            durationMs = source.durationSeconds?.let { it * 1_000L },
            width = null,
            height = source.qualityHeight.takeIf { it > 0 },
            source = AcquisitionSource.DirectHttp(
                url = source.url,
                headers = source.headers
            ),
            ext = ext,
            vcodec = source.codec,
            hasVideo = true,
            hasAudio = true,
            qualityLabel = source.qualityLabel,
            status = CandidateStatus.AVAILABLE,
            extractor = source.providerName
        )

        val contentCategory = if (summary.kind == MediaKind.TV_SERIES || episodeNumber != null) {
            CATEGORY_EPISODE
        } else {
            CATEGORY_MOVIE
        }
        val sourceRef = buildSourceRef(
            providerId = summary.providerId,
            titleId = summary.id,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            qualityLabel = source.qualityLabel
        )
        val urlExpiresAt = parseUrlExpiryMillis(source.url)

        mediaRepository.enqueueStandaloneDownload(
            candidate = candidate,
            sourcePlatform = source.providerName,
            title = displayTitle,
            artworkUrl = summary.posterUrl,
            versionTag = source.qualityLabel,
            contentCategory = contentCategory,
            sourceRef = sourceRef,
            urlExpiresAt = urlExpiresAt
        )
    }

    companion object {
        const val CATEGORY_MOVIE = "MOVIE"
        const val CATEGORY_EPISODE = "EPISODE"

        fun buildSourceRef(
            providerId: String,
            titleId: String,
            seasonNumber: Int?,
            episodeNumber: Int?,
            qualityLabel: String
        ): String = listOf(
            providerId.trim(),
            titleId.trim(),
            (seasonNumber ?: 0).toString(),
            (episodeNumber ?: 0).toString(),
            qualityLabel.trim()
        ).joinToString("|")

        fun parseUrlExpiryMillis(url: String): Long? {
            val httpUrl = url.toHttpUrlOrNull() ?: return null
            val rawExp = httpUrl.queryParameter("exp")
                ?: httpUrl.queryParameter("expires")
                ?: httpUrl.queryParameter("Expires")
                ?: return null
            val sec = rawExp.trim().toLongOrNull() ?: return null
            if (sec <= 0L) return null
            return if (sec < 10_000_000_000L) sec * 1_000L else sec
        }
    }
}
