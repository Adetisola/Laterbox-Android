package com.laterbox.app.movies.data.provider

import com.laterbox.app.movies.domain.model.AudioDubInfo
import com.laterbox.app.movies.domain.model.BrowseSection
import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SectionStyle
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.provider.MovieDetailsProvider
import com.laterbox.app.movies.domain.provider.MoviesCatalogueProvider
import com.laterbox.app.movies.domain.provider.MoviesSearchProvider
import com.laterbox.app.movies.domain.provider.PlaybackSourceProvider
import com.laterbox.app.movies.domain.provider.SubtitleProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

class InternetArchiveMoviesProvider(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String = "https://archive.org"
) : MoviesCatalogueProvider,
    MoviesSearchProvider,
    MovieDetailsProvider,
    PlaybackSourceProvider,
    SubtitleProvider {

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Internet Archive Cinema"
    override val sourceBadge: DataSourceBadge = DataSourceBadge.LIVE_INTERNET_ARCHIVE

    override suspend fun getHomeFeed(): Result<MoviesHomeFeed> = withContext(Dispatchers.IO) {
        runCatching {
            coroutineScope {
                val featuredDeferred = async {
                    queryAdvancedSearch(
                        solrQuery = "$CINEMA_BASE_QUERY AND format:SubRip",
                        sort = "downloads desc",
                        page = 1,
                        rows = 8
                    )
                }
                val noirDeferred = async {
                    queryAdvancedSearch(
                        solrQuery = "collection:film_noir AND mediatype:movies AND format:h.264",
                        sort = "downloads desc",
                        page = 1,
                        rows = 12
                    )
                }
                val sciFiDeferred = async {
                    queryAdvancedSearch(
                        solrQuery = "collection:sci_fi_horror AND mediatype:movies AND format:h.264",
                        sort = "downloads desc",
                        page = 1,
                        rows = 12
                    )
                }
                val comedyDeferred = async {
                    queryAdvancedSearch(
                        solrQuery = "collection:comedy_films AND mediatype:movies AND format:h.264",
                        sort = "downloads desc",
                        page = 1,
                        rows = 12
                    )
                }
                val animationDeferred = async {
                    queryAdvancedSearch(
                        solrQuery = "collection:animationandcartoons AND mediatype:movies AND format:h.264",
                        sort = "downloads desc",
                        page = 1,
                        rows = 12
                    )
                }

                val featured = featuredDeferred.await().getOrDefault(emptyList())
                val noir = noirDeferred.await().getOrDefault(emptyList())
                val sciFi = sciFiDeferred.await().getOrDefault(emptyList())
                val comedy = comedyDeferred.await().getOrDefault(emptyList())
                val animation = animationDeferred.await().getOrDefault(emptyList())

                val totalItems = featured.size + noir.size + sciFi.size + comedy.size + animation.size
                if (totalItems == 0) {
                    // Propagate concrete failure if all initial requests failed
                    featuredDeferred.await().getOrThrow()
                }

                val banners = (featured.take(4) + noir.take(2)).distinctBy { it.id }.take(5)
                val sections = buildList {
                    if (featured.isNotEmpty()) {
                        add(
                            BrowseSection(
                                id = "ia_subtitled_cinema",
                                title = "Playable Classic Cinema",
                                subtitle = "Public domain features with MP4 & subtitles",
                                style = SectionStyle.RAIL,
                                items = featured
                            )
                        )
                    }
                    if (noir.isNotEmpty()) {
                        add(
                            BrowseSection(
                                id = "ia_film_noir",
                                title = "Film Noir Collection",
                                subtitle = "Hard-boiled detective & noir classics",
                                style = SectionStyle.COLLECTION,
                                items = noir
                            )
                        )
                    }
                    if (sciFi.isNotEmpty()) {
                        add(
                            BrowseSection(
                                id = "ia_scifi_horror",
                                title = "Sci-Fi & Midnight Features",
                                subtitle = "Retro sci-fi and cult horror from the Archive",
                                style = SectionStyle.RAIL,
                                items = sciFi
                            )
                        )
                    }
                    if (comedy.isNotEmpty()) {
                        add(
                            BrowseSection(
                                id = "ia_comedy",
                                title = "Golden Age Comedy",
                                subtitle = "Screwball comedies and slapstick greats",
                                style = SectionStyle.RAIL,
                                items = comedy
                            )
                        )
                    }
                    if (animation.isNotEmpty()) {
                        add(
                            BrowseSection(
                                id = "ia_animation",
                                title = "Classic Animation",
                                subtitle = "Restored animated shorts & features",
                                style = SectionStyle.COLLECTION,
                                items = animation
                            )
                        )
                    }
                }

                MoviesHomeFeed(
                    featuredBanners = banners,
                    sections = sections,
                    genres = SUPPORTED_GENRES,
                    activeProviderNotice = "Streaming open-license & public domain cinema via Internet Archive"
                )
            }
        }
    }

    override suspend fun getCatalogue(
        kind: MediaKind,
        page: Int,
        pageSize: Int,
        genre: String?,
        sort: CatalogueSort
    ): Result<CataloguePage> = withContext(Dispatchers.IO) {
        if (kind == MediaKind.TV_SERIES) {
            return@withContext Result.success(
                CataloguePage(items = emptyList(), page = page, pageSize = pageSize, totalCount = 0, hasMore = false)
            )
        }
        runCatching {
            val collectionExpr = when (genre?.trim()?.lowercase(Locale.US)) {
                "film noir", "noir", "crime", "mystery" -> "collection:film_noir AND mediatype:movies AND format:h.264"
                "sci-fi", "science-fiction", "horror", "thriller" -> "collection:sci_fi_horror AND mediatype:movies AND format:h.264"
                "comedy" -> "collection:comedy_films AND mediatype:movies AND format:h.264"
                "animation", "family" -> "collection:animationandcartoons AND mediatype:movies AND format:h.264"
                "drama", "romance", "action", "adventure" ->
                    "$CINEMA_BASE_QUERY AND subject:\"${sanitizeQueryTerm(genre)}\""
                null, "", "all" -> CINEMA_BASE_QUERY
                else -> "$CINEMA_BASE_QUERY AND subject:\"${sanitizeQueryTerm(genre)}\""
            }
            val sortParam = when (sort) {
                CatalogueSort.POPULAR -> "downloads desc"
                CatalogueSort.TOP_RATED -> "avg_rating desc"
                CatalogueSort.NEWEST -> "year desc"
            }
            val parsed = executeAdvancedSearch(collectionExpr, sortParam, page, pageSize)
            CataloguePage(
                items = parsed.items,
                page = page,
                pageSize = pageSize,
                totalCount = parsed.numFound,
                hasMore = page * pageSize < parsed.numFound && parsed.items.isNotEmpty()
            )
        }
    }

    override suspend fun getGenres(): Result<List<String>> = Result.success(SUPPORTED_GENRES)

    override suspend fun search(
        query: String,
        kind: MediaKind?,
        page: Int,
        pageSize: Int
    ): Result<CataloguePage> = withContext(Dispatchers.IO) {
        if (kind == MediaKind.TV_SERIES) {
            return@withContext Result.success(
                CataloguePage(items = emptyList(), page = page, pageSize = pageSize, totalCount = 0, hasMore = false)
            )
        }
        val clean = sanitizeQueryTerm(query)
        if (clean.isBlank()) {
            return@withContext Result.success(CataloguePage(emptyList(), page, pageSize, 0, false))
        }
        runCatching {
            val solrQuery = "$CINEMA_BASE_QUERY AND ($clean)"
            val parsed = executeAdvancedSearch(solrQuery, "downloads desc", page, pageSize)
            CataloguePage(
                items = parsed.items,
                page = page,
                pageSize = pageSize,
                totalCount = parsed.numFound,
                hasMore = page * pageSize < parsed.numFound && parsed.items.isNotEmpty()
            )
        }
    }

    override suspend fun getSuggestions(query: String, limit: Int): Result<List<SearchSuggestion>> =
        withContext(Dispatchers.IO) {
            val clean = sanitizeQueryTerm(query)
            if (clean.length < 2) return@withContext Result.success(emptyList())
            runCatching {
                val parsed = executeAdvancedSearch(
                    solrQuery = "$CINEMA_BASE_QUERY AND title:($clean*)",
                    sort = "downloads desc",
                    page = 1,
                    rows = limit
                )
                val items = parsed.items.ifEmpty {
                    executeAdvancedSearch("$CINEMA_BASE_QUERY AND ($clean)", "downloads desc", 1, limit).items
                }
                items.map {
                    SearchSuggestion(
                        id = it.id,
                        providerId = providerId,
                        title = it.title,
                        kind = MediaKind.MOVIE,
                        releaseYear = it.releaseYear,
                        posterUrl = it.posterUrl
                    )
                }
            }
        }

    override suspend fun getDetails(titleId: String): Result<MediaDetails> = withContext(Dispatchers.IO) {
        runCatching {
            val metadataJson = fetchMetadataJson(titleId)
            val meta = metadataJson.optJSONObject("metadata") ?: JSONObject()
            val filesArr = metadataJson.optJSONArray("files") ?: JSONArray()

            val title = extractStringOrFirst(meta, "title")?.ifBlank { null } ?: titleId
            val rawDesc = extractStringOrJoin(meta, "description")
            val cleanDesc = cleanHtmlText(rawDesc).ifBlank { "Public domain feature archived on Internet Archive." }
            val year = meta.opt("year")?.toString()?.take(4)?.toIntOrNull()
                ?: meta.opt("date")?.toString()?.take(4)?.toIntOrNull()
            val director = extractStringOrJoin(meta, "director").ifBlank { extractStringOrJoin(meta, "creator") }
                .takeIf { it.isNotBlank() }
            val language = extractStringOrFirst(meta, "language")?.takeIf { it.isNotBlank() } ?: "English"
            val genres = extractGenres(meta)
            val durationMinutes = parseRuntimeMinutes(extractStringOrFirst(meta, "runtime"))
                ?: inferDurationFromFiles(filesArr)

            val subtitles = extractSubtitleTracks(titleId, filesArr)
            val streams = extractPlaybackSources(titleId, filesArr)

            val summary = MovieTitleSummary(
                id = titleId,
                providerId = providerId,
                kind = MediaKind.MOVIE,
                title = title,
                releaseYear = year,
                rating = null,
                durationMinutes = durationMinutes,
                posterUrl = "$normalizedBaseUrl/services/img/$titleId",
                backdropUrl = "$normalizedBaseUrl/services/img/$titleId",
                genres = genres,
                summary = cleanDesc.take(260),
                language = language,
                country = extractStringOrFirst(meta, "country"),
                sourceBadge = sourceBadge,
                isPlayableHint = streams.isNotEmpty()
            )

            val collections = extractStringList(meta, "collection")
            val relatedQuery = collections.firstOrNull { it in KNOWN_CINEMA_COLLECTIONS }
                ?.let { "collection:$it AND mediatype:movies AND format:h.264" }
                ?: CINEMA_BASE_QUERY
            val related = queryAdvancedSearch(relatedQuery, "downloads desc", page = 1, rows = 8)
                .getOrDefault(emptyList())
                .filterNot { it.id == titleId }
                .take(6)

            MediaDetails(
                summary = summary,
                fullDescription = cleanDesc,
                directorOrCreator = director,
                networkOrStudio = extractStringOrFirst(meta, "publisher") ?: "Internet Archive",
                cast = emptyList(),
                seasons = emptyList(),
                audioDubs = listOf(AudioDubInfo(language = language, label = "$language (Original Audio)")),
                availableSubtitles = subtitles,
                relatedTitles = related,
                externalInfoUrl = "$normalizedBaseUrl/details/$titleId"
            )
        }
    }

    override suspend fun resolvePlaybackSources(
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Result<PlaybackResolution> = withContext(Dispatchers.IO) {
        runCatching {
            val metadataJson = fetchMetadataJson(titleId)
            val meta = metadataJson.optJSONObject("metadata") ?: JSONObject()
            val filesArr = metadataJson.optJSONArray("files") ?: JSONArray()
            val title = extractStringOrFirst(meta, "title")?.ifBlank { null } ?: titleId
            val sources = extractPlaybackSources(titleId, filesArr)
            val subtitles = extractSubtitleTracks(titleId, filesArr)

            if (sources.isEmpty()) {
                PlaybackResolution.Unavailable(
                    title = title,
                    reason = "No compatible MP4/H.264 or WebM video stream was found for this Internet Archive item.",
                    providerName = displayName,
                    externalReferenceUrl = "$normalizedBaseUrl/details/$titleId",
                    sourceBadge = sourceBadge
                )
            } else {
                PlaybackResolution.Available(
                    title = title,
                    subtitleHeading = extractStringOrFirst(meta, "year")?.let { "Public Domain Film · $it" },
                    sources = sources,
                    subtitles = subtitles,
                    providerName = displayName,
                    sourceBadge = sourceBadge
                )
            }
        }
    }

    override suspend fun getSubtitles(
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Result<List<SubtitleTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val metadataJson = fetchMetadataJson(titleId)
            val filesArr = metadataJson.optJSONArray("files") ?: JSONArray()
            extractSubtitleTracks(titleId, filesArr)
        }
    }

    private fun fetchMetadataJson(titleId: String): JSONObject {
        val url = "$normalizedBaseUrl/metadata/$titleId"
        val body = executeGet(url)
        val obj = JSONObject(body)
        if (!obj.has("metadata") && !obj.has("files")) {
            throw IOException("Internet Archive item '$titleId' was not found.")
        }
        return obj
    }

    private fun queryAdvancedSearch(
        solrQuery: String,
        sort: String,
        page: Int,
        rows: Int
    ): Result<List<MovieTitleSummary>> = runCatching {
        executeAdvancedSearch(solrQuery, sort, page, rows).items
    }

    private data class SearchResultPage(val numFound: Int, val items: List<MovieTitleSummary>)

    private fun executeAdvancedSearch(
        solrQuery: String,
        sort: String,
        page: Int,
        rows: Int
    ): SearchResultPage {
        val httpUrl = "$normalizedBaseUrl/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", solrQuery)
            .addQueryParameter("fl[]", "identifier")
            .addQueryParameter("fl[]", "title")
            .addQueryParameter("fl[]", "year")
            .addQueryParameter("fl[]", "description")
            .addQueryParameter("fl[]", "downloads")
            .addQueryParameter("fl[]", "avg_rating")
            .addQueryParameter("fl[]", "subject")
            .addQueryParameter("sort[]", sort)
            .addQueryParameter("rows", rows.toString())
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("output", "json")
            .build()

        val raw = executeGet(httpUrl.toString())
        val root = JSONObject(raw)
        val response = root.optJSONObject("response") ?: throw IOException("Malformed Internet Archive response")
        val numFound = response.optInt("numFound", 0)
        val docs = response.optJSONArray("docs") ?: JSONArray()

        val items = buildList {
            for (i in 0 until docs.length()) {
                val doc = docs.optJSONObject(i) ?: continue
                val id = doc.optString("identifier").takeIf { it.isNotBlank() } ?: continue
                val rawTitle = extractStringOrFirst(doc, "title") ?: continue
                val year = doc.opt("year")?.toString()?.take(4)?.toIntOrNull()
                val ratingRaw = if (doc.isNull("avg_rating")) null else doc.optDouble("avg_rating", Double.NaN)
                val rating = ratingRaw?.takeIf { !it.isNaN() && it > 0.0 }?.let {
                    // Convert Archive 5-star scale to standard 10-point display if <= 5.0
                    if (it <= 5.0) ((it * 2.0) * 10.0).toInt() / 10.0 else it
                }
                val genres = parseSubjects(doc.opt("subject"))
                val cleanDesc = cleanHtmlText(extractStringOrJoin(doc, "description")).take(240).takeIf { it.isNotBlank() }

                add(
                    MovieTitleSummary(
                        id = id,
                        providerId = providerId,
                        kind = MediaKind.MOVIE,
                        title = rawTitle,
                        releaseYear = year,
                        rating = rating,
                        durationMinutes = null,
                        posterUrl = "$normalizedBaseUrl/services/img/$id",
                        backdropUrl = "$normalizedBaseUrl/services/img/$id",
                        genres = genres,
                        summary = cleanDesc,
                        language = "English",
                        sourceBadge = sourceBadge,
                        isPlayableHint = true
                    )
                )
            }
        }
        return SearchResultPage(numFound = numFound, items = items)
    }

    private fun extractPlaybackSources(titleId: String, filesArr: JSONArray): List<PlaybackSource> {
        val sources = mutableListOf<PlaybackSource>()
        val seenUrls = mutableSetOf<String>()

        for (i in 0 until filesArr.length()) {
            val file = filesArr.optJSONObject(i) ?: continue
            val name = file.optString("name").takeIf { it.isNotBlank() } ?: continue
            val lower = name.lowercase(Locale.US)
            if (!lower.endsWith(".mp4") && !lower.endsWith(".webm") && !lower.endsWith(".m3u8")) continue

            val encodedName = encodePathSegments(name)
            val streamUrl = "$normalizedBaseUrl/download/$titleId/$encodedName"
            if (!seenUrls.add(streamUrl)) continue

            val height = file.opt("height")?.toString()?.toIntOrNull()
                ?: inferHeightFromFilename(name, file.optString("format"))
            val format = when {
                lower.endsWith(".m3u8") -> "HLS"
                lower.endsWith(".webm") -> "WebM"
                else -> "MP4"
            }
            val sizeBytes = file.opt("size")?.toString()?.toLongOrNull() ?: 0L
            val sizeMb = if (sizeBytes > 0L) Math.round((sizeBytes / (1024.0 * 1024.0)) * 10.0) / 10.0 else null
            val durationSec = file.opt("length")?.toString()?.toDoubleOrNull()?.toLong()
            val qualityLabel = when {
                height >= 1080 -> "1080p"
                height >= 720 -> "720p"
                height >= 480 -> "480p"
                height >= 360 -> "360p"
                height >= 240 -> "240p"
                else -> file.optString("format").ifBlank { format }
            }

            sources.add(
                PlaybackSource(
                    id = "$titleId:$name",
                    url = streamUrl,
                    format = format,
                    qualityLabel = qualityLabel,
                    qualityHeight = height,
                    sizeMb = sizeMb,
                    codec = file.optString("format").takeIf { it.isNotBlank() },
                    durationSeconds = durationSec,
                    isAuthorised = true,
                    providerName = displayName
                )
            )
        }

        return sources.sortedWith(
            compareByDescending<PlaybackSource> { it.format == "MP4" }
                .thenByDescending { it.qualityHeight }
                .thenByDescending { it.sizeMb ?: 0.0 }
        )
    }

    private fun extractSubtitleTracks(titleId: String, filesArr: JSONArray): List<SubtitleTrack> {
        val tracks = mutableListOf<SubtitleTrack>()
        val seen = mutableSetOf<String>()
        for (i in 0 until filesArr.length()) {
            val file = filesArr.optJSONObject(i) ?: continue
            val name = file.optString("name").takeIf { it.isNotBlank() } ?: continue
            val lower = name.lowercase(Locale.US)
            val ext = when {
                lower.endsWith(".vtt") -> "vtt"
                lower.endsWith(".srt") -> "srt"
                else -> continue
            }
            val url = "$normalizedBaseUrl/download/$titleId/${encodePathSegments(name)}"
            if (!seen.add(url)) continue

            val (code, lang) = inferSubtitleLanguage(name)
            val label = if (lower.contains(".asr.")) "$lang (Auto · ${ext.uppercase(Locale.US)})" else "$lang (${ext.uppercase(Locale.US)})"
            tracks.add(
                SubtitleTrack(
                    id = "$titleId:$name",
                    language = lang,
                    languageCode = code,
                    label = label,
                    url = url,
                    format = ext
                )
            )
        }
        return tracks
    }

    private fun executeGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "LaterboxAndroid/1.0 (+https://github.com/Adetisola/Laterbox)")
            .header("Accept", "application/json")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Internet Archive returned HTTP ${response.code}")
            }
            return response.body?.string() ?: throw IOException("Empty response from Internet Archive")
        }
    }

    private val normalizedBaseUrl: String get() = baseUrl.trimEnd('/')

    private fun cleanHtmlText(raw: String): String {
        if (raw.isBlank()) return ""
        return Jsoup.parse(raw).text().replace(Regex("\\s+"), " ").trim()
    }

    private fun extractStringOrFirst(obj: JSONObject, key: String): String? {
        val raw = obj.opt(key) ?: return null
        return when (raw) {
            is JSONArray -> (0 until raw.length()).firstNotNullOfOrNull { idx ->
                raw.optString(idx).takeIf { it.isNotBlank() }
            }
            JSONObject.NULL -> null
            else -> raw.toString().takeIf { it.isNotBlank() && it != "null" }
        }
    }

    private fun extractStringOrJoin(obj: JSONObject, key: String): String {
        val raw = obj.opt(key) ?: return ""
        return when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { idx ->
                raw.optString(idx).takeIf { it.isNotBlank() }
            }.joinToString(", ")
            JSONObject.NULL -> ""
            else -> raw.toString().takeIf { it != "null" }.orEmpty()
        }
    }

    private fun extractStringList(obj: JSONObject, key: String): List<String> {
        val raw = obj.opt(key) ?: return emptyList()
        return when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { idx ->
                raw.optString(idx).takeIf { it.isNotBlank() }
            }
            JSONObject.NULL -> emptyList()
            else -> raw.toString().split(";", ",").map { it.trim() }.filter { it.isNotBlank() }
        }
    }

    private fun extractGenres(meta: JSONObject): List<String> {
        val subjects = parseSubjects(meta.opt("subject"))
        if (subjects.isNotEmpty()) return subjects
        val collections = extractStringList(meta, "collection")
        return collections.mapNotNull { col ->
            when (col) {
                "film_noir" -> "Film Noir"
                "sci_fi_horror" -> "Sci-Fi & Horror"
                "comedy_films" -> "Comedy"
                "animationandcartoons" -> "Animation"
                "feature_films" -> "Classic Cinema"
                else -> null
            }
        }.distinct().ifEmpty { listOf("Classic Cinema") }
    }

    private fun parseSubjects(raw: Any?): List<String> {
        val parts = when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).takeIf { s -> s.isNotBlank() } }
            is String -> raw.split(";", ",")
            else -> emptyList()
        }
        return parts.map { token ->
            token.trim().replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString() }
        }.filter { it.length in 2..28 }.distinct().take(4)
    }

    private fun parseRuntimeMinutes(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val hmsMatch = Regex("(\\d+):(\\d{2}):(\\d{2})").find(raw)
        if (hmsMatch != null) {
            val h = hmsMatch.groupValues[1].toIntOrNull() ?: 0
            val m = hmsMatch.groupValues[2].toIntOrNull() ?: 0
            return h * 60 + m
        }
        val minMatch = Regex("(\\d+)").find(raw)
        return minMatch?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..600 }
    }

    private fun inferDurationFromFiles(filesArr: JSONArray): Int? {
        for (i in 0 until filesArr.length()) {
            val f = filesArr.optJSONObject(i) ?: continue
            if (f.optString("name").endsWith(".mp4", ignoreCase = true)) {
                val secs = f.opt("length")?.toString()?.toDoubleOrNull() ?: continue
                val mins = (secs / 60.0).toInt()
                if (mins > 0) return mins
            }
        }
        return null
    }

    private fun inferHeightFromFilename(name: String, format: String): Int {
        val lower = name.lowercase(Locale.US)
        return when {
            "2160" in lower || "4k" in lower -> 2160
            "1080" in lower -> 1080
            "720" in lower -> 720
            "512kb" in lower || "512kb" in format.lowercase(Locale.US) -> 240
            "480" in lower -> 480
            "360" in lower -> 360
            else -> 480
        }
    }

    private fun inferSubtitleLanguage(fileName: String): Pair<String, String> {
        val lower = fileName.lowercase(Locale.US)
        return when {
            ".es." in lower || "spanish" in lower -> "es" to "Spanish"
            ".fr." in lower || "french" in lower -> "fr" to "French"
            ".de." in lower || "german" in lower -> "de" to "German"
            ".pt." in lower || "portuguese" in lower -> "pt" to "Portuguese"
            ".ar." in lower || "arabic" in lower -> "ar" to "Arabic"
            else -> "en" to "English"
        }
    }

    private fun encodePathSegments(path: String): String =
        path.split("/").joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }

    private fun sanitizeQueryTerm(raw: String): String =
        raw.replace(Regex("[\"\\\\()]"), " ").trim()

    companion object {
        const val PROVIDER_ID = "ia"

        private val KNOWN_CINEMA_COLLECTIONS = setOf(
            "feature_films",
            "film_noir",
            "sci_fi_horror",
            "comedy_films",
            "animationandcartoons"
        )

        private const val CINEMA_BASE_QUERY =
            "(collection:feature_films OR collection:film_noir OR collection:sci_fi_horror OR collection:comedy_films OR collection:animationandcartoons) AND mediatype:movies AND format:h.264"

        val SUPPORTED_GENRES = listOf(
            "All",
            "Drama",
            "Comedy",
            "Film Noir",
            "Sci-Fi",
            "Horror",
            "Animation",
            "Action",
            "Romance",
            "Mystery"
        )
    }
}
