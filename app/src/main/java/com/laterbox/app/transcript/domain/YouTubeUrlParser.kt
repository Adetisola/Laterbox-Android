package com.laterbox.app.transcript.domain

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

sealed interface YouTubeUrlTarget {
    val originalUrl: String

    data class SingleVideo(
        val videoId: String,
        val canonicalUrl: String,
        override val originalUrl: String
    ) : YouTubeUrlTarget

    data class Playlist(
        val playlistId: String,
        val canonicalUrl: String,
        override val originalUrl: String
    ) : YouTubeUrlTarget

    data class VideoInPlaylist(
        val videoId: String,
        val playlistId: String,
        val videoCanonicalUrl: String,
        val playlistCanonicalUrl: String,
        val isAutoMix: Boolean,
        override val originalUrl: String
    ) : YouTubeUrlTarget

    data class Invalid(
        val reason: String,
        override val originalUrl: String
    ) : YouTubeUrlTarget
}

enum class TranscriptTargetPreference {
    VIDEO,
    PLAYLIST
}

object YouTubeUrlParser {

    private val YOUTUBE_HOSTS = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtube-nocookie.com",
        "www.youtube-nocookie.com"
    )

    private val ID_REGEX = Regex("^[A-Za-z0-9_-]+$")

    fun isYouTubeUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        return parse(rawUrl) !is YouTubeUrlTarget.Invalid
    }

    fun isPlaylistUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        return when (val target = parse(rawUrl)) {
            is YouTubeUrlTarget.Playlist -> true
            is YouTubeUrlTarget.VideoInPlaylist -> !target.isAutoMix
            else -> false
        }
    }

    fun parse(rawUrl: String): YouTubeUrlTarget {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return YouTubeUrlTarget.Invalid("URL is empty", rawUrl)
        }

        val withScheme = if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            "https://$trimmed"
        } else {
            trimmed
        }

        val uri = try {
            URI(withScheme)
        } catch (_: Exception) {
            return YouTubeUrlTarget.Invalid("Malformed YouTube URL", rawUrl)
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return YouTubeUrlTarget.Invalid("Unsupported URL scheme", rawUrl)
        }

        val host = uri.host?.lowercase() ?: return YouTubeUrlTarget.Invalid("Invalid video web link", rawUrl)
        val path = (uri.rawPath ?: "").trimEnd('/')
        val queryParams = parseQueryParams(uri.rawQuery.orEmpty())
        val listId = queryParams["list"]?.takeIf { isValidId(it) }

        if (host == "youtu.be") {
            val segments = path.split('/').filter { it.isNotBlank() }
            val videoId = segments.firstOrNull()?.takeIf { isValidId(it) }
                ?: return YouTubeUrlTarget.Invalid("Missing YouTube video ID", rawUrl)
            return buildVideoOrMixedTarget(videoId, listId, trimmed)
        }

        if (host !in YOUTUBE_HOSTS) {
            return YouTubeUrlTarget.Invalid("Not a YouTube URL", rawUrl)
        }

        val segments = path.split('/').filter { it.isNotBlank() }
        val firstSegment = segments.firstOrNull()?.lowercase()

        if (firstSegment == "playlist") {
            val playlistId = listId
                ?: return YouTubeUrlTarget.Invalid("Missing YouTube playlist ID", rawUrl)
            return YouTubeUrlTarget.Playlist(
                playlistId = playlistId,
                canonicalUrl = canonicalPlaylistUrl(playlistId),
                originalUrl = withScheme
            )
        }

        if (firstSegment == "watch" || segments.isEmpty()) {
            val videoId = queryParams["v"]?.takeIf { isValidId(it) }
            if (videoId != null) {
                return buildVideoOrMixedTarget(videoId, listId, withScheme)
            }
            if (listId != null && firstSegment == "watch") {
                return YouTubeUrlTarget.Playlist(
                    playlistId = listId,
                    canonicalUrl = canonicalPlaylistUrl(listId),
                    originalUrl = withScheme
                )
            }
            return YouTubeUrlTarget.Invalid("Missing YouTube video or playlist ID", rawUrl)
        }

        if (firstSegment in setOf("shorts", "live", "embed", "v") && segments.size >= 2) {
            val videoId = segments[1].takeIf { isValidId(it) }
                ?: return YouTubeUrlTarget.Invalid("Invalid YouTube video ID", rawUrl)
            return buildVideoOrMixedTarget(videoId, listId, withScheme)
        }

        return YouTubeUrlTarget.Invalid("Unsupported YouTube URL path", rawUrl)
    }

    fun canonicalVideoUrl(videoId: String): String =
        "https://www.youtube.com/watch?v=$videoId"

    fun canonicalPlaylistUrl(playlistId: String): String =
        "https://www.youtube.com/playlist?list=$playlistId"

    private fun buildVideoOrMixedTarget(
        videoId: String,
        playlistId: String?,
        originalUrl: String
    ): YouTubeUrlTarget {
        val videoCanonical = canonicalVideoUrl(videoId)
        if (playlistId == null) {
            return YouTubeUrlTarget.SingleVideo(
                videoId = videoId,
                canonicalUrl = videoCanonical,
                originalUrl = originalUrl
            )
        }
        val isAutoMix = playlistId.startsWith("RD", ignoreCase = true) ||
            playlistId.equals("WL", ignoreCase = true) ||
            playlistId.equals("LL", ignoreCase = true)
        return YouTubeUrlTarget.VideoInPlaylist(
            videoId = videoId,
            playlistId = playlistId,
            videoCanonicalUrl = videoCanonical,
            playlistCanonicalUrl = canonicalPlaylistUrl(playlistId),
            isAutoMix = isAutoMix,
            originalUrl = originalUrl
        )
    }

    private fun isValidId(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        return ID_REGEX.matches(candidate)
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isBlank()) continue
            val idx = pair.indexOf('=')
            val rawKey = if (idx >= 0) pair.substring(0, idx) else pair
            val rawValue = if (idx >= 0 && idx + 1 < pair.length) pair.substring(idx + 1) else ""
            val key = urlDecode(rawKey).lowercase()
            val value = urlDecode(rawValue).trim()
            if (key.isNotEmpty() && !result.containsKey(key)) {
                result[key] = value
            }
        }
        return result
    }

    private fun urlDecode(value: String): String {
        return try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
    }
}
