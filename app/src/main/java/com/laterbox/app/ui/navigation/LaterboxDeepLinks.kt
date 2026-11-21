package com.laterbox.app.ui.navigation

import android.content.Intent
import android.net.Uri
import com.laterbox.app.movies.domain.model.MediaKind
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class DeepLinkMediaType(val pathSegment: String) {
    MOVIE("movie"),
    SERIES("series")
}

data class ParsedTitleDeepLink(
    val mediaType: DeepLinkMediaType,
    val providerId: String,
    val titleId: String
)

/**
 * Canonical builder and hardened parser for verified Laterbox Android App Links.
 *
 * Canonical URLs:
 * - https://laterbox-website.vercel.app/movie/{provider}/{id}
 * - https://laterbox-website.vercel.app/series/{provider}/{id}
 */
object LaterboxDeepLinks {

    const val CANONICAL_SCHEME = "https"
    const val CANONICAL_HOST = "laterbox-website.vercel.app"
    const val CANONICAL_ORIGIN = "$CANONICAL_SCHEME://$CANONICAL_HOST"

    val ALLOWED_HOSTS: Set<String> = setOf(CANONICAL_HOST)

    val ALLOWED_PROVIDERS: Set<String> = setOf(
        "moviebox",
        "ia",
        "tmdb",
        "tvmaze",
        "canonical",
        "fixture"
    )

    private val PROVIDER_REGEX = Regex("^[a-zA-Z0-9_-]{1,40}$")
    private val TITLE_ID_REGEX = Regex("^[a-zA-Z0-9._:-]{1,120}$")
    private val INVALID_LITERALS = setOf("0", "null", "none", "undefined", ".", "..")

    fun buildTitleUrl(
        providerId: String,
        titleId: String,
        kind: MediaKind
    ): String? = if (kind.isEpisodic) {
        buildSeriesUrl(providerId, titleId)
    } else {
        buildMovieUrl(providerId, titleId)
    }

    fun buildMovieUrl(providerId: String, titleId: String): String? =
        buildUrl(DeepLinkMediaType.MOVIE, providerId, titleId)

    fun buildSeriesUrl(providerId: String, titleId: String): String? =
        buildUrl(DeepLinkMediaType.SERIES, providerId, titleId)

    private fun buildUrl(
        mediaType: DeepLinkMediaType,
        providerId: String,
        titleId: String
    ): String? {
        val cleanProvider = normalizeProvider(providerId) ?: return null
        val cleanTitleId = normalizeTitleId(titleId) ?: return null
        val encodedProvider = URLEncoder.encode(cleanProvider, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        val encodedTitleId = URLEncoder.encode(cleanTitleId, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return "$CANONICAL_ORIGIN/${mediaType.pathSegment}/$encodedProvider/$encodedTitleId"
    }

    fun parseUri(uri: Uri?): ParsedTitleDeepLink? = parseUrl(uri?.toString())

    fun parseUrl(rawUrl: String?): ParsedTitleDeepLink? {
        val trimmed = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.length > 1024) return null

        val parsedUri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = parsedUri.scheme?.lowercase() ?: return null
        if (scheme != CANONICAL_SCHEME) return null

        val host = parsedUri.host?.lowercase() ?: return null
        if (host !in ALLOWED_HOSTS) return null
        if (parsedUri.userInfo != null || parsedUri.port != -1) return null

        val rawPath = parsedUri.rawPath ?: return null
        if (rawPath.contains("%2f", ignoreCase = true) ||
            rawPath.contains("%5c", ignoreCase = true) ||
            rawPath.contains("\\")
        ) {
            return null
        }

        val segments = rawPath.split('/').filter { it.isNotEmpty() }
        if (segments.size != 3) return null

        val mediaType = when (segments[0].lowercase()) {
            DeepLinkMediaType.MOVIE.pathSegment -> DeepLinkMediaType.MOVIE
            DeepLinkMediaType.SERIES.pathSegment -> DeepLinkMediaType.SERIES
            else -> return null
        }

        val decodedProvider = runCatching {
            URLDecoder.decode(segments[1], StandardCharsets.UTF_8.name())
        }.getOrNull() ?: return null
        val decodedTitleId = runCatching {
            URLDecoder.decode(segments[2], StandardCharsets.UTF_8.name())
        }.getOrNull() ?: return null

        val cleanProvider = normalizeProvider(decodedProvider) ?: return null
        val cleanTitleId = normalizeTitleId(decodedTitleId) ?: return null

        return ParsedTitleDeepLink(
            mediaType = mediaType,
            providerId = cleanProvider,
            titleId = cleanTitleId
        )
    }

    fun toDestinationRoute(parsed: ParsedTitleDeepLink): String {
        val encodedProvider = URLEncoder.encode(parsed.providerId, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        val encodedId = URLEncoder.encode(parsed.titleId, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return "movies/detail/$encodedProvider/$encodedId"
    }

    fun parseIncomingIntent(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val parsed = parseUri(intent.data) ?: return null
        return toDestinationRoute(parsed)
    }

    private fun normalizeProvider(raw: String): String? {
        val clean = raw.trim().lowercase()
        if (clean.isEmpty() || clean in INVALID_LITERALS) return null
        if (!PROVIDER_REGEX.matches(clean)) return null
        if (clean !in ALLOWED_PROVIDERS) return null
        return clean
    }

    private fun normalizeTitleId(raw: String): String? {
        val clean = raw.trim()
        if (clean.isEmpty() || clean.lowercase() in INVALID_LITERALS) return null
        if (clean.contains('/') || clean.contains('\\') || clean.contains("..")) return null
        if (!TITLE_ID_REGEX.matches(clean)) return null
        return clean
    }
}
