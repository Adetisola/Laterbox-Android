package com.laterbox.app.movies.data.download

import com.laterbox.app.data.repository.LinkRepository
import com.laterbox.app.domain.model.ContentType
import com.laterbox.app.media.domain.model.AcquisitionSource
import com.laterbox.app.media.domain.model.CandidateStatus
import com.laterbox.app.media.domain.model.MediaCandidate
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.media.domain.repository.MediaRepository
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.PlaybackSource

/**
 * Enqueues authorised open-license movie streams into Laterbox's native Downloads queue
 * at the user's chosen resolution.
 */
class MoviesDownloadBridge(
    private val linkRepository: LinkRepository,
    private val mediaRepository: MediaRepository
) {
    suspend fun enqueueAuthorisedDownload(
        summary: MovieTitleSummary,
        source: PlaybackSource,
        episodeLabel: String? = null
    ): Result<String> = runCatching {
        require(source.isAuthorised) {
            "Download is only enabled for authorised open-license media streams."
        }
        require(source.url.startsWith("http://") || source.url.startsWith("https://")) {
            "Invalid download stream URL."
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

        val linkId = linkRepository.saveLink(
            originalUrl = source.url,
            title = displayTitle,
            personalNote = "Saved from Movies & TV · ${source.providerName}",
            contentType = ContentType.VIDEO,
            autoFetchMetadata = false
        )

        val estimatedBytes = source.sizeMb?.let { mb ->
            if (mb > 0.0) (mb * 1024.0 * 1024.0).toLong() else null
        }

        val ext = when {
            source.format.equals("MP4", ignoreCase = true) -> "mp4"
            source.format.equals("WEBM", ignoreCase = true) -> "webm"
            source.format.equals("MKV", ignoreCase = true) -> "mkv"
            else -> source.format.lowercase().ifBlank { "mp4" }
        }

        val candidate = MediaCandidate(
            candidateId = "movies_${summary.providerId}_${summary.id}_${source.id}",
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

        mediaRepository.enqueueDownload(
            linkId = linkId,
            candidate = candidate,
            sourcePlatform = source.providerName,
            title = displayTitle,
            artworkUrl = summary.posterUrl,
            versionTag = source.qualityLabel
        )
    }
}
