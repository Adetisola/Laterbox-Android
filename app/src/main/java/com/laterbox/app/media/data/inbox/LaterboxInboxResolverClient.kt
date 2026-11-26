package com.laterbox.app.media.data.inbox

import com.laterbox.app.BuildConfig
import com.laterbox.app.media.domain.model.AcquisitionSource
import com.laterbox.app.media.domain.model.InspectionResult
import com.laterbox.app.media.domain.model.MatchConfidence
import com.laterbox.app.media.domain.model.MediaCandidate
import com.laterbox.app.media.domain.model.MediaType
import com.laterbox.app.media.domain.model.MusicMatchCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

enum class InboxResolvePlatform(
    val displayName: String,
    val endpointPath: String
) {
    TWITTER("X", "/api/inbox/resolve/twitter"),
    INSTAGRAM("Instagram", "/api/inbox/resolve/instagram"),
    SPOTIFY("Spotify", "/api/inbox/resolve/spotify")
}

object InboxLinkRouter {
    private val twitterHosts = setOf(
        "x.com",
        "www.x.com",
        "mobile.x.com",
        "twitter.com",
        "www.twitter.com",
        "mobile.twitter.com",
        "fxtwitter.com",
        "www.fxtwitter.com",
        "vxtwitter.com",
        "www.vxtwitter.com",
        "fixupx.com",
        "www.fixupx.com"
    )

    private val instagramHosts = setOf(
        "instagram.com",
        "www.instagram.com",
        "m.instagram.com",
        "ddinstagram.com",
        "www.ddinstagram.com",
        "instagr.am",
        "www.instagr.am"
    )

    private val spotifyHosts = setOf(
        "open.spotify.com",
        "spotify.link",
        "play.spotify.com"
    )

    fun routeForUrl(rawUrl: String): InboxResolvePlatform? {
        val host = extractHost(rawUrl) ?: return null
        return when {
            host in twitterHosts -> InboxResolvePlatform.TWITTER
            host in instagramHosts -> InboxResolvePlatform.INSTAGRAM
            host in spotifyHosts -> InboxResolvePlatform.SPOTIFY
            else -> null
        }
    }

    internal fun extractHost(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return try {
            val uri = URI(withScheme)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            uri.host?.lowercase()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }
}

data class InboxVideoVariant(
    val url: String,
    val bitrate: Long?,
    val quality: String
)

data class InboxCarouselItem(
    val mediaType: MediaType,
    val url: String,
    val thumbnailUrl: String?
)

data class InboxSpotifyMeta(
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val durationMs: Long?
)

data class InboxYouTubeAudioMatch(
    val videoId: String,
    val title: String?,
    val durationMs: Long?,
    val audioUrl: String,
    val confidence: String?
)

sealed interface InboxResolveOutcome {
    data class VideoVariants(
        val platform: InboxResolvePlatform,
        val variants: List<InboxVideoVariant>,
        val thumbnailUrl: String?,
        val durationMs: Long?
    ) : InboxResolveOutcome

    data class DirectMedia(
        val platform: InboxResolvePlatform,
        val mediaType: MediaType,
        val url: String,
        val thumbnailUrl: String?,
        val requiresReferer: Boolean
    ) : InboxResolveOutcome

    data class AudioMedia(
        val platform: InboxResolvePlatform,
        val url: String,
        val title: String?,
        val artist: String?,
        val source: String? = null,
        val spotifyMeta: InboxSpotifyMeta? = null,
        val youtubeMatch: InboxYouTubeAudioMatch? = null
    ) : InboxResolveOutcome

    data class Carousel(
        val platform: InboxResolvePlatform,
        val items: List<InboxCarouselItem>,
        val requiresReferer: Boolean
    ) : InboxResolveOutcome

    data class Unsupported(
        val platform: InboxResolvePlatform,
        val reason: String,
        val fallback: String,
        val spotifyMeta: InboxSpotifyMeta? = null
    ) : InboxResolveOutcome

    data class Unavailable(
        val platform: InboxResolvePlatform,
        val httpStatus: Int? = null
    ) : InboxResolveOutcome
}

class LaterboxInboxResolverClient(
    private val okHttpClient: OkHttpClient,
    private val baseUrlProvider: suspend () -> String,
    private val appCheckTokenProvider: suspend (forceRefresh: Boolean) -> String? = { null },
    private val authHeaderProvider: suspend (forceRefresh: Boolean) -> Pair<String, String>? = { null }
) {

    suspend fun resolve(rawUrl: String): InboxResolveOutcome? {
        val platform = InboxLinkRouter.routeForUrl(rawUrl) ?: return null
        return withContext(Dispatchers.IO) {
            val baseUrl = baseUrlProvider().trim().trimEnd('/')
            if (baseUrl.isBlank()) {
                return@withContext InboxResolveOutcome.Unavailable(platform)
            }
            val endpointUrl = baseUrl + platform.endpointPath
            val payload = JSONObject().put("url", rawUrl.trim()).toString()
            executeWithAuthRetry(platform, endpointUrl, payload)
        }
    }

    suspend fun resolveToInspectionResult(rawUrl: String): InspectionResult {
        val platform = InboxLinkRouter.routeForUrl(rawUrl)
            ?: return InspectionResult.Unsupported("Unsupported link for Inbox resolver.")
        val baseUrl = runCatching { baseUrlProvider().trim().trimEnd('/') }.getOrDefault("")
        val proxyHeaders = if (platform == InboxResolvePlatform.INSTAGRAM) {
            buildProxyAuthHeaders()
        } else {
            emptyMap()
        }
        val outcome = resolve(rawUrl) ?: InboxResolveOutcome.Unavailable(platform)
        return outcomeToInspectionResult(outcome, baseUrl, proxyHeaders)
    }

    suspend fun buildProxyAuthHeaders(forceRefresh: Boolean = false): Map<String, String> {
        val headers = linkedMapOf(
            "X-Laterbox-Version-Code" to BuildConfig.VERSION_CODE.toString(),
            "X-Laterbox-Version-Name" to BuildConfig.VERSION_NAME
        )
        val appCheck = runCatching { appCheckTokenProvider(forceRefresh) }.getOrNull()?.takeIf { it.isNotBlank() }
        if (appCheck != null) {
            headers["X-Firebase-AppCheck"] = appCheck
        } else {
            val authPair = runCatching { authHeaderProvider(forceRefresh) }.getOrNull()
            if (authPair != null && authPair.second.isNotBlank()) {
                headers[authPair.first] = authPair.second
            }
        }
        return headers
    }

    private suspend fun executeWithAuthRetry(
        platform: InboxResolvePlatform,
        endpointUrl: String,
        jsonBody: String
    ): InboxResolveOutcome {
        val firstResponse = performPost(endpointUrl, jsonBody, forceRefresh = false)
            ?: return InboxResolveOutcome.Unavailable(platform)
        if (firstResponse.first == 401) {
            val retryResponse = performPost(endpointUrl, jsonBody, forceRefresh = true)
                ?: return InboxResolveOutcome.Unavailable(platform, 401)
            return parseResolveResponse(platform, retryResponse.first, retryResponse.second)
        }
        return parseResolveResponse(platform, firstResponse.first, firstResponse.second)
    }

    private suspend fun performPost(
        endpointUrl: String,
        jsonBody: String,
        forceRefresh: Boolean
    ): Pair<Int, String?>? {
        val requestBuilder = Request.Builder()
            .url(endpointUrl)
            .header("Accept", "application/json")
            .header("X-Laterbox-Version-Code", BuildConfig.VERSION_CODE.toString())
            .header("X-Laterbox-Version-Name", BuildConfig.VERSION_NAME)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))

        val appCheckToken = runCatching { appCheckTokenProvider(forceRefresh) }.getOrNull()?.takeIf { it.isNotBlank() }
        if (appCheckToken != null) {
            requestBuilder.header("X-Firebase-AppCheck", appCheckToken)
        } else {
            val authPair = runCatching { authHeaderProvider(forceRefresh) }.getOrNull()
            if (authPair != null && authPair.second.isNotBlank()) {
                requestBuilder.header(authPair.first, authPair.second)
            }
        }

        return try {
            okHttpClient.newCall(requestBuilder.build()).execute().use { resp ->
                resp.code to resp.body?.string()
            }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun buildInstagramProxyUrl(baseUrl: String, cdnUrl: String): String {
            val cleanBase = baseUrl.trim().trimEnd('/')
            val encoded = URLEncoder.encode(cdnUrl, "UTF-8")
            return "$cleanBase/api/inbox/download/instagram?url=$encoded"
        }

        internal fun parseResolveResponse(
            platform: InboxResolvePlatform,
            statusCode: Int,
            responseBody: String?
        ): InboxResolveOutcome {
            if (statusCode !in 200..299 || responseBody.isNullOrBlank()) {
                return InboxResolveOutcome.Unavailable(platform, statusCode)
            }
            return try {
                val json = JSONObject(responseBody)
                when (json.optString("type").lowercase()) {
                    "video" -> {
                        val variantsArr = json.optJSONArray("variants")
                        if (variantsArr != null && variantsArr.length() > 0) {
                            val variants = parseVariants(variantsArr)
                            if (variants.isEmpty()) {
                                InboxResolveOutcome.Unavailable(platform, 404)
                            } else {
                                InboxResolveOutcome.VideoVariants(
                                    platform = platform,
                                    variants = variants,
                                    thumbnailUrl = json.optString("thumbnail").takeIf { it.isNotBlank() && it != "null" },
                                    durationMs = if (json.has("duration_ms") && !json.isNull("duration_ms")) {
                                        json.optLong("duration_ms").takeIf { it > 0L }
                                    } else null
                                )
                            }
                        } else {
                            val directUrl = json.optString("url").trim()
                            if (directUrl.isEmpty()) {
                                InboxResolveOutcome.Unavailable(platform, 404)
                            } else {
                                InboxResolveOutcome.DirectMedia(
                                    platform = platform,
                                    mediaType = MediaType.VIDEO,
                                    url = directUrl,
                                    thumbnailUrl = json.optString("thumbnail").takeIf { it.isNotBlank() && it != "null" },
                                    requiresReferer = json.optBoolean("requires_referer", false)
                                )
                            }
                        }
                    }

                    "image" -> {
                        val directUrl = json.optString("url").trim()
                        if (directUrl.isEmpty()) {
                            InboxResolveOutcome.Unavailable(platform, 404)
                        } else {
                            InboxResolveOutcome.DirectMedia(
                                platform = platform,
                                mediaType = MediaType.IMAGE,
                                url = directUrl,
                                thumbnailUrl = directUrl,
                                requiresReferer = json.optBoolean("requires_referer", false)
                            )
                        }
                    }

                    "carousel" -> {
                        val itemsArr = json.optJSONArray("items") ?: JSONArray()
                        val items = buildList {
                            for (i in 0 until itemsArr.length()) {
                                val obj = itemsArr.optJSONObject(i) ?: continue
                                val url = obj.optString("url").trim()
                                if (url.isEmpty()) continue
                                val mType = when (obj.optString("type").lowercase()) {
                                    "video" -> MediaType.VIDEO
                                    else -> MediaType.IMAGE
                                }
                                val thumb = obj.optString("thumbnail").takeIf { it.isNotBlank() && it != "null" }
                                    ?: if (mType == MediaType.IMAGE) url else null
                                add(InboxCarouselItem(mediaType = mType, url = url, thumbnailUrl = thumb))
                            }
                        }
                        if (items.isEmpty()) {
                            InboxResolveOutcome.Unavailable(platform, 404)
                        } else {
                            InboxResolveOutcome.Carousel(
                                platform = platform,
                                items = items,
                                requiresReferer = json.optBoolean("requires_referer", false)
                            )
                        }
                    }

                    "audio" -> {
                        val spotifyMeta = json.optJSONObject("spotify")?.let(::parseSpotifyMeta)
                        val youtubeMatch = json.optJSONObject("youtube")?.let(::parseYouTubeAudioMatch)
                        val audioUrl = youtubeMatch?.audioUrl ?: json.optString("url").trim()
                        if (audioUrl.isEmpty()) {
                            if (spotifyMeta != null) {
                                InboxResolveOutcome.Unsupported(
                                    platform = platform,
                                    reason = "no_youtube_match",
                                    fallback = "open_in_spotify",
                                    spotifyMeta = spotifyMeta
                                )
                            } else {
                                InboxResolveOutcome.Unavailable(platform, 404)
                            }
                        } else {
                            InboxResolveOutcome.AudioMedia(
                                platform = platform,
                                url = audioUrl,
                                title = spotifyMeta?.title
                                    ?: json.optString("title").takeIf { it.isNotBlank() && it != "null" },
                                artist = spotifyMeta?.artist
                                    ?: json.optString("artist").takeIf { it.isNotBlank() && it != "null" },
                                source = json.optString("source").takeIf { it.isNotBlank() && it != "null" },
                                spotifyMeta = spotifyMeta,
                                youtubeMatch = youtubeMatch
                            )
                        }
                    }

                    "unsupported" -> InboxResolveOutcome.Unsupported(
                        platform = platform,
                        reason = json.optString("reason", "unsupported"),
                        fallback = json.optString("fallback", "open_in_browser"),
                        spotifyMeta = json.optJSONObject("spotify")?.let(::parseSpotifyMeta)
                    )

                    else -> InboxResolveOutcome.Unavailable(platform, statusCode)
                }
            } catch (_: Throwable) {
                InboxResolveOutcome.Unavailable(platform, statusCode)
            }
        }

        private fun parseSpotifyMeta(obj: JSONObject): InboxSpotifyMeta? {
            val title = obj.optString("title").trim()
            if (title.isEmpty() || title == "null") return null
            val artist = obj.optString("artist").trim().takeIf { it.isNotBlank() && it != "null" } ?: "Unknown Artist"
            val album = obj.optString("album").trim().takeIf { it.isNotBlank() && it != "null" }
            val coverUrl = obj.optString("cover").trim().takeIf { it.startsWith("http") }
            val durationMs = if (obj.has("duration_ms") && !obj.isNull("duration_ms")) {
                obj.optLong("duration_ms").takeIf { it > 0L }
            } else null
            return InboxSpotifyMeta(
                title = title,
                artist = artist,
                album = album,
                coverUrl = coverUrl,
                durationMs = durationMs
            )
        }

        private fun parseYouTubeAudioMatch(obj: JSONObject): InboxYouTubeAudioMatch? {
            val audioUrl = obj.optString("audio_url").trim()
            if (audioUrl.isEmpty() || audioUrl == "null") return null
            val videoId = obj.optString("video_id").trim()
            val title = obj.optString("title").trim().takeIf { it.isNotBlank() && it != "null" }
            val durationMs = if (obj.has("duration_ms") && !obj.isNull("duration_ms")) {
                obj.optLong("duration_ms").takeIf { it > 0L }
            } else null
            val confidence = obj.optString("confidence").trim().takeIf { it.isNotBlank() && it != "null" }
            return InboxYouTubeAudioMatch(
                videoId = videoId,
                title = title,
                durationMs = durationMs,
                audioUrl = audioUrl,
                confidence = confidence
            )
        }

        private fun parseVariants(arr: JSONArray): List<InboxVideoVariant> {
            return buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val vUrl = item.optString("url").trim()
                    if (vUrl.isEmpty()) continue
                    val bitrate = if (item.has("bitrate") && !item.isNull("bitrate")) {
                        item.optLong("bitrate").takeIf { it > 0L }
                    } else null
                    val quality = item.optString("quality").trim().ifBlank { "Video" }
                    add(InboxVideoVariant(url = vUrl, bitrate = bitrate, quality = quality))
                }
            }
        }

        fun outcomeToInspectionResult(
            outcome: InboxResolveOutcome,
            baseUrl: String,
            proxyHeaders: Map<String, String>
        ): InspectionResult {
            return when (outcome) {
                is InboxResolveOutcome.VideoVariants -> {
                    val candidates = outcome.variants.mapIndexed { idx, variant ->
                        MediaCandidate(
                            candidateId = "inbox_tw_${idx}_${variant.quality}",
                            mediaType = MediaType.VIDEO,
                            formatLabel = "${variant.quality} MP4",
                            estimatedBytes = null,
                            durationMs = outcome.durationMs,
                            width = null,
                            height = null,
                            source = AcquisitionSource.DirectHttp(url = variant.url),
                            ext = "mp4",
                            hasVideo = true,
                            hasAudio = true,
                            isBest = idx == 0,
                            qualityLabel = variant.quality,
                            bitrate = variant.bitrate?.toDouble(),
                            extractor = "inbox_backend"
                        )
                    }
                    InspectionResult.Available(
                        platform = outcome.platform.displayName,
                        thumbnailUrl = outcome.thumbnailUrl,
                        candidates = candidates,
                        isComplete = true
                    )
                }

                is InboxResolveOutcome.DirectMedia -> {
                    val useProxy = outcome.requiresReferer &&
                        outcome.platform == InboxResolvePlatform.INSTAGRAM &&
                        baseUrl.isNotBlank()
                    val downloadUrl = if (useProxy) buildInstagramProxyUrl(baseUrl, outcome.url) else outcome.url
                    val headers = if (useProxy) proxyHeaders else emptyMap()
                    val isVideo = outcome.mediaType == MediaType.VIDEO
                    val ext = if (isVideo) "mp4" else "jpg"
                    val label = if (isVideo) "Video MP4" else "Photo JPG"
                    val candidate = MediaCandidate(
                        candidateId = "inbox_ig_direct",
                        mediaType = outcome.mediaType,
                        formatLabel = label,
                        estimatedBytes = null,
                        durationMs = null,
                        width = null,
                        height = null,
                        source = AcquisitionSource.DirectHttp(url = downloadUrl, headers = headers),
                        ext = ext,
                        hasVideo = isVideo,
                        hasAudio = isVideo,
                        isBest = true,
                        extractor = "inbox_backend"
                    )
                    InspectionResult.Available(
                        platform = outcome.platform.displayName,
                        thumbnailUrl = outcome.thumbnailUrl,
                        candidates = listOf(candidate),
                        isComplete = true
                    )
                }

                is InboxResolveOutcome.AudioMedia -> {
                    val resolvedTitle = outcome.spotifyMeta?.title ?: outcome.title
                    val resolvedArtist = outcome.spotifyMeta?.artist ?: outcome.artist
                    val resolvedAlbum = outcome.spotifyMeta?.album
                    val coverUrl = outcome.spotifyMeta?.coverUrl
                    val durationMs = outcome.spotifyMeta?.durationMs ?: outcome.youtubeMatch?.durationMs
                    val label = listOfNotNull(resolvedArtist, resolvedTitle)
                        .joinToString(" — ")
                        .takeIf { it.isNotBlank() }
                        ?: "Audio"
                    val isYouTube = outcome.source.equals("youtube", ignoreCase = true) || outcome.youtubeMatch != null
                    val ext = if (isYouTube || outcome.url.contains(".m4a")) "m4a" else "mp3"
                    val candidate = MediaCandidate(
                        candidateId = "inbox_spotify_audio",
                        mediaType = MediaType.AUDIO,
                        formatLabel = label,
                        estimatedBytes = null,
                        durationMs = durationMs,
                        width = null,
                        height = null,
                        source = AcquisitionSource.DirectHttp(url = outcome.url),
                        ext = ext,
                        hasVideo = false,
                        hasAudio = true,
                        isBest = true,
                        extractor = "inbox_backend"
                    )
                    val matchCandidate = if (outcome.platform == InboxResolvePlatform.SPOTIFY && !resolvedTitle.isNullOrBlank()) {
                        val isHighConfidence = outcome.youtubeMatch?.confidence.equals("high", ignoreCase = true)
                        MusicMatchCandidate(
                            candidateId = candidate.candidateId,
                            title = resolvedTitle,
                            artist = resolvedArtist ?: "Unknown Artist",
                            durationMs = durationMs,
                            score = if (isHighConfidence) 0.95 else 0.78,
                            confidence = if (isHighConfidence) MatchConfidence.STRONG else MatchConfidence.POSSIBLE,
                            downloadCandidate = candidate,
                            sourceDescription = if (isYouTube) "via YouTube" else "Authorized Audio Source"
                        )
                    } else {
                        null
                    }
                    InspectionResult.Available(
                        platform = outcome.platform.displayName,
                        title = resolvedTitle,
                        thumbnailUrl = coverUrl,
                        candidates = listOf(candidate),
                        artist = resolvedArtist,
                        album = resolvedAlbum,
                        musicMatches = listOfNotNull(matchCandidate),
                        isComplete = true
                    )
                }

                is InboxResolveOutcome.Carousel -> {
                    val useProxy = outcome.requiresReferer &&
                        outcome.platform == InboxResolvePlatform.INSTAGRAM &&
                        baseUrl.isNotBlank()
                    val headers = if (useProxy) proxyHeaders else emptyMap()
                    val candidates = outcome.items.mapIndexed { idx, item ->
                        val downloadUrl = if (useProxy) buildInstagramProxyUrl(baseUrl, item.url) else item.url
                        val isVideo = item.mediaType == MediaType.VIDEO
                        val ext = if (isVideo) "mp4" else "jpg"
                        val kind = if (isVideo) "Video" else "Photo"
                        MediaCandidate(
                            candidateId = "inbox_carousel_$idx",
                            mediaType = item.mediaType,
                            formatLabel = "Item ${idx + 1} — $kind",
                            estimatedBytes = null,
                            durationMs = null,
                            width = null,
                            height = null,
                            source = AcquisitionSource.DirectHttp(url = downloadUrl, headers = headers),
                            ext = ext,
                            hasVideo = isVideo,
                            hasAudio = isVideo,
                            isBest = idx == 0,
                            extractor = "inbox_backend"
                        )
                    }
                    InspectionResult.Available(
                        platform = outcome.platform.displayName,
                        thumbnailUrl = outcome.items.firstNotNullOfOrNull { it.thumbnailUrl },
                        candidates = candidates,
                        isComplete = true
                    )
                }

                is InboxResolveOutcome.Unsupported -> InspectionResult.Unsupported(
                    reason = "Open this link in your browser to view or save media.",
                    spotifyTitle = outcome.spotifyMeta?.title,
                    spotifyArtist = outcome.spotifyMeta?.artist,
                    spotifyAlbum = outcome.spotifyMeta?.album,
                    spotifyCoverUrl = outcome.spotifyMeta?.coverUrl
                )

                is InboxResolveOutcome.Unavailable -> InspectionResult.Unsupported(
                    reason = "Open this link in your browser to view or save media."
                )
            }
        }
    }
}
