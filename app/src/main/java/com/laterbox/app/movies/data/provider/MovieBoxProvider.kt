package com.laterbox.app.movies.data.provider

import android.util.Log
import com.laterbox.app.BuildConfig
import com.laterbox.app.data.preferences.MovieBoxEndpointConfig
import com.laterbox.app.movies.domain.model.AudioDubInfo
import com.laterbox.app.movies.domain.model.BrowseSection
import com.laterbox.app.movies.domain.model.CastMember
import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.PersonFilmography
import com.laterbox.app.movies.domain.model.PlaybackMarkerWindow
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SectionStyle
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.provider.MovieDetailsProvider
import com.laterbox.app.movies.domain.provider.MoviesCatalogueProvider
import com.laterbox.app.movies.domain.provider.MoviesSearchProvider
import com.laterbox.app.movies.domain.provider.PlaybackSourceProvider
import com.laterbox.app.movies.domain.provider.SubtitleProvider
import com.laterbox.app.movies.domain.provider.SeriesEpisodeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

class MovieBoxApiException(
    val httpCode: Int?,
    message: String,
    val isAuthFailure: Boolean = (httpCode == 401 || httpCode == 403),
    cause: Throwable? = null
) : IOException(message, cause)

class MovieBoxProvider(
    private val okHttpClient: OkHttpClient,
    private val baseUrlProvider: suspend () -> String,
    private val retryDelayMs: Long = 250L,
    private val appCheckTokenProvider: suspend (forceRefresh: Boolean) -> String? = { null }
) : MoviesCatalogueProvider,
    MoviesSearchProvider,
    MovieDetailsProvider,
    SeriesEpisodeProvider,
    PlaybackSourceProvider,
    SubtitleProvider {

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Streaming Catalogue"
    override val sourceBadge: DataSourceBadge = DataSourceBadge.LIVE_MOVIEBOX

    private val detailPathCache = ConcurrentHashMap<String, String>()
    private val titleCache = ConcurrentHashMap<String, String>()
    private val trailerCache = ConcurrentHashMap<String, String>()
    private val trailerHeadersCache = ConcurrentHashMap<String, Map<String, String>>()
    private val episodeSizesCache = ConcurrentHashMap<String, ConcurrentHashMap<Int, Map<Int, Double>>>()
    private val markerWindowCache = ConcurrentHashMap<String, Pair<PlaybackMarkerWindow?, PlaybackMarkerWindow?>>()

    @Volatile
    private var cachedGenres: List<String> = emptyList()

    suspend fun activeBaseUrl(): String = baseUrlProvider().trim().trimEnd('/')

    override suspend fun getHomeFeed(): Result<MoviesHomeFeed> = withContext(Dispatchers.IO) {
        runCatching {
            val url = requireBaseHttpUrl().newBuilder()
                .addPathSegment("home")
                .build()
            val body = executeGet(url)
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: root
            val operatingList = data.optJSONArray("operatingList")
                ?: throw IOException(
                    root.optCleanString("message")
                        ?: "Discovery sections are not available right now."
                )

            val banners = mutableListOf<MovieTitleSummary>()
            val sections = mutableListOf<BrowseSection>()
            val extractedGenres = mutableListOf<String>()
            val seenSectionIds = mutableSetOf<String>()

            for (i in 0 until operatingList.length()) {
                val op = operatingList.optJSONObject(i) ?: continue
                val opType = op.optCleanString("type").orEmpty()
                val opTitle = op.optCleanString("title")

                when {
                    opType.equals("BANNER", ignoreCase = true) ||
                        (op.has("banner") && !op.isNull("banner")) -> {
                        val bannerObj = op.optJSONObject("banner")
                        val bannerItems = bannerObj?.optJSONArray("items") ?: JSONArray()
                        for (j in 0 until bannerItems.length()) {
                            val entry = bannerItems.optJSONObject(j) ?: continue
                            val externalUrl = entry.optCleanString("url")
                            if (externalUrl != null) continue
                            val bannerImage = entry.optJSONObject("image")?.optCleanString("url")
                            val sub = entry.optJSONObject("subject")
                            val trailerUrl = (sub ?: entry)
                                .optJSONObject("trailer")
                                ?.optJSONObject("videoAddress")
                                ?.optCleanString("url")
                                ?: entry.optJSONObject("trailer")
                                    ?.optJSONObject("videoAddress")
                                    ?.optCleanString("url")

                            if (sub != null) {
                                parseSubjectObject(
                                    obj = sub,
                                    backdropOverride = bannerImage,
                                    trailerOverride = trailerUrl
                                )?.let { banners.add(it) }
                            } else {
                                val id = (entry.optCleanString("subjectId") ?: entry.optCleanString("id"))
                                    ?.takeUnless { it == "0" }
                                val subjectType = entry.optIntOrNull("subjectType")
                                if (subjectType != null && subjectType !in VALID_SUBJECT_TYPES) continue
                                val title = entry.optCleanString("title") ?: opTitle
                                if (id != null && title != null) {
                                    val dp = entry.optCleanString("detailPath")
                                    cacheMetadata(id, title, dp, trailerUrl)
                                    val cover = entry.optJSONObject("cover")?.optCleanString("url") ?: bannerImage
                                    val releaseDateStr = entry.optCleanString("releaseDate") ?: entry.optCleanString("release_date")
                                    banners.add(
                                        MovieTitleSummary(
                                            id = id,
                                            providerId = PROVIDER_ID,
                                            kind = parseMediaKind(
                                                subjectType,
                                                entry.optCleanString("type")
                                            ),
                                            title = title,
                                            releaseYear = parseYear(releaseDateStr, entry.optCleanString("year")),
                                            durationMinutes = parseDurationMinutes(entry.optLongOrNull("duration")),
                                            posterUrl = cover,
                                            backdropUrl = bannerImage,
                                            language = entry.optCleanString("language"),
                                            country = entry.optCleanString("countryName") ?: entry.optCleanString("country"),
                                            detailPath = dp,
                                            trailerUrl = trailerUrl,
                                            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                                            isPlayableHint = true,
                                            releaseDate = releaseDateStr
                                        )
                                    )
                                }
                            }
                        }
                    }

                    opType.equals("SPORT_LIVE", ignoreCase = true) -> Unit

                    opType.equals("FILTER", ignoreCase = true) || (op.has("filters") && !op.has("subjects")) -> {
                        val filters = op.optJSONArray("filters") ?: JSONArray()
                        for (j in 0 until filters.length()) {
                            filters.optJSONObject(j)?.optCleanString("title")?.let { filterTitle ->
                                val normalized = normalizeVerifiedGenre(filterTitle) ?: filterTitle
                                extractedGenres.add(normalized)
                            }
                        }
                    }

                    else -> {
                        val subjects = op.optJSONArray("subjects") ?: continue
                        val sectionItems = buildList {
                            for (j in 0 until subjects.length()) {
                                val sub = subjects.optJSONObject(j) ?: continue
                                parseSubjectObject(sub)?.let { add(it) }
                            }
                        }.distinctBy { it.id }

                        if (sectionItems.isNotEmpty()) {
                            val baseSlug = op.optCleanString("opId")
                                ?: opTitle?.lowercase(Locale.US)
                                    ?.replace(Regex("[^a-z0-9]+"), "_")
                                    ?.trim('_')
                                ?: "section_$i"
                            val uniqueId = if (seenSectionIds.add(baseSlug)) baseSlug else "${baseSlug}_$i"
                            val style = if (opType.equals("APPOINTMENT_LIST", ignoreCase = true)) {
                                SectionStyle.COLLECTION
                            } else {
                                SectionStyle.RAIL
                            }
                            sections.add(
                                BrowseSection(
                                    id = uniqueId,
                                    title = opTitle ?: "Featured",
                                    subtitle = op.optCleanString("subTitle") ?: op.optCleanString("description"),
                                    style = style,
                                    items = sectionItems
                                )
                            )
                        }
                    }
                }
            }

            val finalBanners = (banners.ifEmpty {
                sections.firstOrNull()?.items?.take(5).orEmpty()
            }).distinctBy { it.id }

            val apiGenres = buildList {
                val arr = data.optJSONArray("genres") ?: JSONArray()
                for (gIdx in 0 until arr.length()) {
                    arr.optString(gIdx).trim()
                        .takeIf { it.isNotBlank() }
                        ?.let { raw ->
                            add(normalizeVerifiedGenre(raw) ?: raw)
                        }
                }
            }

            val genreList = buildList {
                add("All")
                val discovered = when {
                    apiGenres.isNotEmpty() -> apiGenres
                    extractedGenres.isNotEmpty() -> extractedGenres
                    else -> VERIFIED_GENRES
                }.filterNot { it.equals("All", ignoreCase = true) }
                    .distinct()
                addAll(discovered)
            }
            if (genreList.size > 1) {
                cachedGenres = genreList
            }

            MoviesHomeFeed(
                featuredBanners = finalBanners,
                sections = sections,
                genres = genreList,
                activeProviderNotice = null,
                isOfflineCached = false,
                containsSampleFixtures = false
            )
        }
    }

    override suspend fun getCatalogue(
        kind: MediaKind,
        page: Int,
        pageSize: Int,
        genre: String?,
        sort: CatalogueSort,
        country: String?,
        year: String?
    ): Result<CataloguePage> = withContext(Dispatchers.IO) {
        runCatching {
            val safePage = page.coerceAtLeast(1)
            val safePageSize = pageSize.coerceIn(1, 50)
            val endpoint = if (kind == MediaKind.TV_SERIES) "tv-series" else "movies"
            val normalizedGenre = normalizeVerifiedGenre(genre)
            val normalizedCountry = normalizeVerifiedCountry(country)
            val normalizedYear = year?.trim()?.takeIf { YEAR_REGEX.matches(it) }
            val rawApiSort = when (sort) {
                CatalogueSort.POPULAR -> "Hottest"
                CatalogueSort.TOP_RATED -> "Rating"
                CatalogueSort.NEWEST -> "Latest"
            }
            val apiSort = if (normalizedGenre == "Western" && rawApiSort == "Hottest") {
                "ForYou"
            } else {
                rawApiSort
            }

            val urlBuilder = requireBaseHttpUrl().newBuilder()
                .addPathSegment(endpoint)
                .addQueryParameter("page", safePage.toString())
                .addQueryParameter("per_page", safePageSize.toString())
                .addQueryParameter("sort", apiSort)
            if (normalizedGenre != null) {
                urlBuilder.addQueryParameter("genre", normalizedGenre)
            }
            if (normalizedCountry != null) {
                urlBuilder.addQueryParameter("country", normalizedCountry)
            }
            if (normalizedYear != null) {
                urlBuilder.addQueryParameter("year", normalizedYear)
            }

            val body = executeGet(urlBuilder.build())
            val root = JSONObject(body)
            val total = root.optIntOrNull("total")
            val rawItems = root.optJSONArray("items") ?: JSONArray()

            val parsed = buildList {
                for (i in 0 until rawItems.length()) {
                    val obj = rawItems.optJSONObject(i) ?: continue
                    val id = obj.optCleanString("subject_id")?.takeUnless { it == "0" } ?: continue
                    val subjectType = obj.optIntOrNull("subject_type")
                    if (subjectType != null && subjectType !in VALID_SUBJECT_TYPES) continue
                    val title = obj.optCleanString("title") ?: continue
                    val dp = obj.optCleanString("detail_path")
                    cacheMetadata(id, title, dp)
                    val cover = obj.optCleanString("cover")
                    val stillsUrl = obj.optJSONObject("stills")?.optCleanString("url")
                    val itemKind = subjectType?.let { parseMediaKind(it, null) } ?: kind
                    val releaseDateStr = obj.optCleanString("release_date")
                    add(
                        MovieTitleSummary(
                            id = id,
                            providerId = PROVIDER_ID,
                            kind = itemKind,
                            title = title,
                            releaseYear = parseYear(releaseDateStr, obj.optCleanString("year")),
                            rating = obj.optRating("rating", "imdb_rating"),
                            durationMinutes = parseDurationMinutes(obj.optLongOrNull("duration")),
                            posterUrl = cover,
                            backdropUrl = stillsUrl,
                            genres = parseGenres(obj.optCleanString("genre")),
                            language = obj.optCleanString("language"),
                            country = obj.optCleanString("country"),
                            detailPath = dp,
                            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                            isPlayableHint = true,
                            releaseDate = releaseDateStr
                        )
                    )
                }
            }.distinctBy { it.id }

            val hasMore = parsed.isNotEmpty() &&
                parsed.size >= safePageSize &&
                (total == null || safePage * parsed.size < total)

            CataloguePage(
                items = parsed,
                page = safePage,
                pageSize = safePageSize,
                totalCount = total ?: parsed.size,
                hasMore = hasMore
            )
        }
    }

    override suspend fun getGenres(): Result<List<String>> {
        val current = cachedGenres
        if (current.isNotEmpty()) return Result.success(current)
        return Result.success(DEFAULT_GENRES)
    }

    override suspend fun search(
        query: String,
        kind: MediaKind?,
        page: Int,
        pageSize: Int
    ): Result<CataloguePage> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = query.trim()
            if (clean.isBlank()) {
                return@runCatching CataloguePage(emptyList(), page, pageSize, 0, false)
            }
            val safePage = page.coerceAtLeast(1)
            val safePageSize = pageSize.coerceIn(1, 50)

            val url = requireBaseHttpUrl().newBuilder()
                .addPathSegment("search")
                .addQueryParameter("q", clean)
                .addQueryParameter("page", safePage.toString())
                .addQueryParameter("per_page", safePageSize.toString())
                .build()

            val body = executeGet(url)
            val root = JSONObject(body)
            val rawResults = root.optJSONArray("results") ?: JSONArray()

            val parsed = buildList {
                for (i in 0 until rawResults.length()) {
                    val obj = rawResults.optJSONObject(i) ?: continue
                    val id = obj.optCleanString("subject_id")?.takeUnless { it == "0" } ?: continue
                    val subjectType = obj.optIntOrNull("subject_type")
                    if (subjectType != null && subjectType !in VALID_SUBJECT_TYPES) continue
                    val title = obj.optCleanString("title") ?: continue
                    val dp = obj.optCleanString("detail_path")
                    cacheMetadata(id, title, dp)
                    val cover = obj.optCleanString("cover")
                    val stillsUrl = obj.optJSONObject("stills")?.optCleanString("url")
                    val itemKind = parseMediaKind(
                        subjectType,
                        obj.optCleanString("type")
                    )
                    val releaseDateStr = obj.optCleanString("release_date")
                    add(
                        MovieTitleSummary(
                            id = id,
                            providerId = PROVIDER_ID,
                            kind = itemKind,
                            title = title,
                            releaseYear = parseYear(releaseDateStr),
                            rating = obj.optRating("imdb_rating", "rating"),
                            durationMinutes = parseDurationMinutes(obj.optLongOrNull("duration")),
                            posterUrl = cover,
                            backdropUrl = stillsUrl,
                            genres = parseGenres(obj.optCleanString("genre")),
                            language = obj.optCleanString("language"),
                            country = obj.optCleanString("country"),
                            detailPath = dp,
                            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                            isPlayableHint = true,
                            releaseDate = releaseDateStr
                        )
                    )
                }
            }.distinctBy { it.id }

            val filtered = if (kind != null) {
                parsed.filter { it.kind == kind }
            } else {
                parsed
            }

            CataloguePage(
                items = filtered,
                page = safePage,
                pageSize = safePageSize,
                totalCount = filtered.size,
                hasMore = rawResults.length() >= safePageSize
            )
        }
    }

    override suspend fun getSuggestions(
        query: String,
        limit: Int
    ): Result<List<SearchSuggestion>> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = query.trim()
            if (clean.isBlank()) return@runCatching emptyList()

            val url = requireBaseHttpUrl().newBuilder()
                .addPathSegment("search")
                .addPathSegment("suggest")
                .addQueryParameter("q", clean)
                .build()

            val body = executeGet(url)
            val root = JSONObject(body)
            val raw = root.optJSONArray("suggestions") ?: JSONArray()

            buildList {
                for (i in 0 until raw.length()) {
                    val obj = raw.optJSONObject(i) ?: continue
                    val title = obj.optCleanString("title") ?: continue
                    val rawId = obj.optCleanString("subject_id")
                        ?.takeUnless { it == "0" }
                        .orEmpty()
                    val slug = obj.optCleanString("slug")
                    if (rawId.isNotBlank() && slug != null) {
                        cacheMetadata(rawId, title, slug)
                    } else if (rawId.isNotBlank()) {
                        titleCache[rawId] = title
                    }
                    add(
                        SearchSuggestion(
                            id = rawId,
                            providerId = PROVIDER_ID,
                            title = title,
                            kind = MediaKind.MOVIE
                        )
                    )
                }
            }.distinctBy { suggestion ->
                if (suggestion.hasNavigableId) {
                    "id:${suggestion.id}"
                } else {
                    "kw:${suggestion.title.lowercase(Locale.US)}"
                }
            }.take(limit)
        }
    }

    override suspend fun getDetails(titleId: String): Result<MediaDetails> = withContext(Dispatchers.IO) {
        runCatching {
            val url = requireBaseHttpUrl().newBuilder()
                .addPathSegment("detail")
                .addPathSegment(titleId)
                .build()

            val body = executeGet(url)
            val root = JSONObject(body)
            val id = root.optCleanString("subject_id") ?: titleId
            val title = root.optCleanString("title") ?: titleCache[id] ?: "Title $id"
            val dp = root.optCleanString("detail_path")
            val trailerObj = root.optJSONObject("trailer")
            val trailerUrl = trailerObj?.optCleanString("url")
                ?: trailerObj?.optJSONObject("videoAddress")?.optCleanString("url")
                ?: root.optCleanString("trailer_url")
                ?: trailerCache[id]
            val trailerHeaders = parseHeadersObject(trailerObj?.optJSONObject("headers"))
                .ifEmpty { trailerHeadersCache[id].orEmpty() }
            cacheMetadata(id, title, dp, trailerUrl, trailerHeaders)

            val kind = parseMediaKind(
                root.optIntOrNull("subject_type"),
                root.optCleanString("type")
            )
            val cover = root.optCleanString("cover")
            val stillsUrl = root.optJSONObject("stills")?.optCleanString("url")
            val description = root.optCleanString("description").orEmpty()

            val dubs = buildList {
                val arr = root.optJSONArray("dubs") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val d = arr.optJSONObject(i) ?: continue
                    val langName = d.optCleanString("lang_name")
                    val language = d.optCleanString("language")
                    val label = langName ?: language ?: continue
                    add(
                        AudioDubInfo(
                            language = language ?: label,
                            label = label,
                            referenceId = d.optCleanString("subject_id")
                        )
                    )
                }
            }

            var directorOrCreator: String? = null
            var directorPersonId: String? = null
            val cast = buildList {
                val starsArr = root.optJSONArray("stars") ?: JSONArray()
                for (i in 0 until starsArr.length()) {
                    val star = starsArr.optJSONObject(i) ?: continue
                    val name = star.optCleanString("name") ?: continue
                    val staffType = star.optIntOrNull("staff_type")
                    val staffId = star.optCleanString("staff_id")
                    if (staffType == 2 && directorOrCreator == null) {
                        directorOrCreator = name
                        directorPersonId = staffId
                    }
                    val role = star.optCleanString("character")
                        ?: if (staffType == 2) "Director" else null
                    add(
                        CastMember(
                            name = name,
                            role = role,
                            imageUrl = star.optCleanString("avatar_url"),
                            personId = staffId,
                            providerId = PROVIDER_ID
                        )
                    )
                }
            }

            val detailSeasons = parseSeasonsArray(root.optJSONArray("seasons"))
            val seasons = if (kind == MediaKind.TV_SERIES) {
                detailSeasons.ifEmpty { getSeasons(id).getOrDefault(emptyList()) }
            } else {
                emptyList()
            }

            val relatedTitles = buildList {
                val relArr = root.optJSONArray("related") ?: JSONArray()
                for (i in 0 until relArr.length()) {
                    val relObj = relArr.optJSONObject(i) ?: continue
                    parseFlatItemSummary(relObj)?.let { add(it) }
                }
            }.distinctBy { it.id }

            val detailIntro = parseMarkerWindow(root, "intro")
            val detailRecap = parseMarkerWindow(root, "recap")
            if (detailIntro != null || detailRecap != null) {
                markerWindowCache[id] = detailIntro to detailRecap
            }

            val releaseDateStr = root.optCleanString("release_date")
            val summary = MovieTitleSummary(
                id = id,
                providerId = PROVIDER_ID,
                kind = kind,
                title = title,
                releaseYear = parseYear(releaseDateStr),
                rating = root.optRating("imdb_rating", "rating"),
                durationMinutes = parseDurationMinutes(root.optLongOrNull("duration")),
                posterUrl = cover,
                backdropUrl = stillsUrl,
                genres = parseGenres(root.optCleanString("genre")),
                summary = description.takeIf { it.isNotBlank() },
                language = root.optCleanString("language"),
                country = root.optCleanString("country"),
                detailPath = dp,
                trailerUrl = trailerUrl,
                trailerHeaders = trailerHeaders,
                sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                isPlayableHint = true,
                imdbId = root.optCleanString("imdbId") ?: root.optCleanString("imdb_id"),
                releaseDate = releaseDateStr
            )

            MediaDetails(
                summary = summary,
                fullDescription = description,
                directorOrCreator = directorOrCreator,
                directorPersonId = directorPersonId,
                cast = cast,
                seasons = seasons,
                audioDubs = dubs,
                relatedTitles = relatedTitles
            )
        }
    }

    override suspend fun getSeasons(seriesId: String): Result<List<SeasonSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = requireBaseHttpUrl().newBuilder()
                .addPathSegment("episodes")
                .addPathSegment(seriesId)
                .build()
            val body = executeGet(url)
            val root = JSONObject(body)
            parseSeasonsArray(root.optJSONArray("seasons"))
        }
    }

    override suspend fun getEpisodes(
        seriesId: String,
        seasonNumber: Int
    ): Result<List<EpisodeItem>> = withContext(Dispatchers.IO) {
        getSeasons(seriesId).map { seasons ->
            val target = seasons.firstOrNull { it.seasonNumber == seasonNumber }
            val epNumbers = when {
                target == null -> emptyList()
                target.availableEpisodes.isNotEmpty() -> target.availableEpisodes
                target.episodeCount > 0 -> (1..target.episodeCount).toList()
                else -> emptyList()
            }
            val cachedSizesForSeason = episodeSizesCache["$seriesId:$seasonNumber"].orEmpty()
            epNumbers.map { ep ->
                EpisodeItem(
                    id = "${seriesId}_s${seasonNumber}_e$ep",
                    seasonNumber = seasonNumber,
                    episodeNumber = ep,
                    title = "Episode $ep",
                    sizesMbByQuality = cachedSizesForSeason[ep].orEmpty()
                )
            }
        }
    }

    suspend fun getSeasonEpisodeSizes(
        seriesId: String,
        seasonNumber: Int,
        episodeNumbers: List<Int> = emptyList()
    ): Result<Map<Int, Map<Int, Double>>> = withContext(Dispatchers.IO) {
        runCatching {
            val cacheKey = "$seriesId:$seasonNumber"
            val seasonMap = episodeSizesCache.getOrPut(cacheKey) { ConcurrentHashMap() }
            if (episodeNumbers.isNotEmpty() && episodeNumbers.all { seasonMap[it]?.isNotEmpty() == true }) {
                return@runCatching seasonMap.toMap()
            }

            val builder = requireBaseHttpUrl().newBuilder()
                .addPathSegment("episodes")
                .addPathSegment(seriesId)
                .addPathSegment("sizes")
                .addQueryParameter("se", seasonNumber.toString())
            if (episodeNumbers.isNotEmpty()) {
                builder.addQueryParameter("eps", episodeNumbers.take(30).joinToString(","))
            }
            detailPathCache[seriesId]?.takeIf { it.isNotBlank() }?.let { dp ->
                builder.addQueryParameter("detail_path", dp)
            }

            try {
                val body = executeGet(builder.build())
                val root = JSONObject(body)
                val epsArr = root.optJSONArray("episodes") ?: JSONArray()
                for (i in 0 until epsArr.length()) {
                    val epObj = epsArr.optJSONObject(i) ?: continue
                    val epNum = epObj.optInt("episode", 0).takeIf { it > 0 } ?: continue
                    val parsedSizes = linkedMapOf<Int, Double>()
                    val sizesObj = epObj.optJSONObject("sizes_mb")
                    if (sizesObj != null) {
                        val keys = sizesObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val q = k.toIntOrNull()?.takeIf { it > 0 } ?: continue
                            val sizeVal = sizesObj.optDouble(k, 0.0)
                            if (sizeVal > 0.0) parsedSizes[q] = sizeVal
                        }
                    }
                    if (parsedSizes.isEmpty()) {
                        val qualitiesArr = epObj.optJSONArray("qualities") ?: JSONArray()
                        for (j in 0 until qualitiesArr.length()) {
                            val qObj = qualitiesArr.optJSONObject(j) ?: continue
                            val q = qObj.optInt("quality_value", 0).takeIf { it > 0 } ?: continue
                            val sizeVal = qObj.optDouble("size_mb", 0.0)
                            if (sizeVal > 0.0) parsedSizes[q] = sizeVal
                        }
                    }
                    if (parsedSizes.isNotEmpty()) {
                        seasonMap[epNum] = parsedSizes
                    }
                }
                seasonMap.toMap()
            } catch (apiErr: MovieBoxApiException) {
                if (apiErr.httpCode == 404 && episodeNumbers.isNotEmpty()) {
                    val semaphore = Semaphore(3)
                    val missing = episodeNumbers.filter { seasonMap[it].isNullOrEmpty() }.take(12)
                    coroutineScope {
                        missing.map { epNum ->
                            async {
                                semaphore.withPermit {
                                    resolvePlaybackSources(seriesId, seasonNumber, epNum)
                                }
                            }
                        }.awaitAll()
                    }
                    seasonMap.toMap()
                } else {
                    throw apiErr
                }
            }
        }
    }

    suspend fun getPersonFilmography(
        staffId: String,
        personName: String,
        personImageUrl: String? = null
    ): Result<PersonFilmography> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanId = staffId.trim()
            require(cleanId.isNotBlank()) { "Staff ID is required." }
            val url = requireBaseHttpUrl().newBuilder()
                .addPathSegment("staff")
                .addPathSegment(cleanId)
                .addPathSegment("titles")
                .addQueryParameter("page", "1")
                .addQueryParameter("per_page", "24")
                .build()
            val body = executeGet(url)
            val root = JSONObject(body)
            val rawItems = root.optJSONArray("items") ?: JSONArray()
            val credits = buildList {
                for (i in 0 until rawItems.length()) {
                    val obj = rawItems.optJSONObject(i) ?: continue
                    parseFlatItemSummary(obj)?.let { add(it) }
                }
            }.distinctBy { it.id }

            PersonFilmography(
                personId = cleanId,
                providerId = PROVIDER_ID,
                name = personName.ifBlank { "Cast Member" },
                imageUrl = personImageUrl,
                credits = credits
            )
        }
    }

    private fun parseSeasonsArray(seasonsArr: JSONArray?): List<SeasonSummary> {
        if (seasonsArr == null) return emptyList()
        return buildList {
            for (i in 0 until seasonsArr.length()) {
                val s = seasonsArr.optJSONObject(i) ?: continue
                val seasonNumber = s.optInt("season", 0).takeIf { it > 0 } ?: continue
                val episodeCount = s.optInt("episode_count", 0).coerceAtLeast(0)
                val allEpArr = s.optJSONArray("all_ep")
                val availableEpisodes = buildList {
                    if (allEpArr != null) {
                        for (j in 0 until allEpArr.length()) {
                            val epNum = allEpArr.optInt(j, 0)
                            if (epNum > 0) add(epNum)
                        }
                    }
                }.distinct()
                val resArr = s.optJSONArray("resolutions")
                val availableResolutions = buildList {
                    if (resArr != null) {
                        for (j in 0 until resArr.length()) {
                            val resObj = resArr.optJSONObject(j)
                            val resVal = resObj?.optInt("resolution", 0)
                                ?: resArr.optInt(j, 0)
                            if (resVal > 0) add(resVal)
                        }
                    }
                }.distinct().sortedDescending()
                add(
                    SeasonSummary(
                        seasonNumber = seasonNumber,
                        title = "Season $seasonNumber",
                        episodeCount = if (episodeCount > 0) episodeCount else availableEpisodes.size,
                        availableEpisodes = availableEpisodes,
                        availableResolutions = availableResolutions
                    )
                )
            }
        }.sortedBy { it.seasonNumber }
    }

    private fun parseFlatItemSummary(obj: JSONObject): MovieTitleSummary? {
        val id = obj.optCleanString("subject_id")?.takeUnless { it == "0" } ?: return null
        val subjectType = obj.optIntOrNull("subject_type")
        if (subjectType != null && subjectType !in VALID_SUBJECT_TYPES) return null
        val title = obj.optCleanString("title") ?: return null
        val dp = obj.optCleanString("detail_path")
        cacheMetadata(id, title, dp)
        val cover = obj.optCleanString("cover")
        val stillsUrl = obj.optJSONObject("stills")?.optCleanString("url")
        val kind = parseMediaKind(
            subjectType,
            obj.optCleanString("type")
        )
        val releaseDateStr = obj.optCleanString("release_date") ?: obj.optCleanString("releaseDate")
        return MovieTitleSummary(
            id = id,
            providerId = PROVIDER_ID,
            kind = kind,
            title = title,
            releaseYear = parseYear(releaseDateStr, obj.optCleanString("year")),
            rating = obj.optRating("imdb_rating", "rating"),
            durationMinutes = parseDurationMinutes(obj.optLongOrNull("duration")),
            posterUrl = cover,
            backdropUrl = stillsUrl,
            genres = parseGenres(obj.optCleanString("genre")),
            language = obj.optCleanString("language"),
            country = obj.optCleanString("country"),
            detailPath = dp,
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = true,
            releaseDate = releaseDateStr
        )
    }

    override suspend fun resolvePlaybackSources(
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Result<PlaybackResolution> = withContext(Dispatchers.IO) {
        runCatching {
            val se = seasonNumber?.takeIf { it > 0 } ?: 0
            val ep = episodeNumber?.takeIf { it > 0 } ?: 0
            val builder = requireBaseHttpUrl().newBuilder()
                .addPathSegment("api")
                .addPathSegment("stream")
                .addPathSegment(titleId)
                .addQueryParameter("se", se.toString())
                .addQueryParameter("ep", ep.toString())

            detailPathCache[titleId]?.takeIf { it.isNotBlank() }?.let { dp ->
                builder.addQueryParameter("detail_path", dp)
            }

            val body = executeGet(builder.build())
            val root = JSONObject(body)
            val streamsArray = root.optJSONArray("streams") ?: JSONArray()

            val sources = buildList {
                for (i in 0 until streamsArray.length()) {
                    val obj = streamsArray.optJSONObject(i) ?: continue
                    val streamUrl = obj.optCleanString("url") ?: continue
                    val format = (obj.optCleanString("format") ?: inferFormatFromUrl(streamUrl))
                        .uppercase(Locale.US)
                    val qualityLabel = obj.optCleanString("quality")
                        ?: obj.optCleanString("resolution_tag")
                        ?: "Stream"
                    val qualityHeight = obj.optIntOrNull("quality_value")?.takeIf { it > 0 }
                        ?: parseQualityHeight(qualityLabel)

                    val headersMap = linkedMapOf<String, String>()
                    val headersObj = obj.optJSONObject("headers")
                    if (headersObj != null) {
                        val keys = headersObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            headersObj.optCleanString(key)?.let { value ->
                                headersMap[key] = value
                            }
                        }
                    }
                    val topCookie = obj.optCleanString("cookie")
                    if (topCookie != null && headersMap.keys.none { it.equals("Cookie", ignoreCase = true) }) {
                        headersMap["Cookie"] = topCookie
                    }

                    add(
                        PlaybackSource(
                            id = "mb_${i}_${qualityHeight}_${format.lowercase(Locale.US)}",
                            url = streamUrl,
                            format = format,
                            qualityLabel = qualityLabel,
                            qualityHeight = qualityHeight,
                            sizeMb = obj.optDoubleOrNull("size_mb")?.takeIf { it > 0.0 },
                            codec = obj.optCleanString("codec"),
                            durationSeconds = obj.optLongOrNull("duration_sec")?.takeIf { it > 0L },
                            headers = headersMap,
                            isAuthorised = true,
                            providerName = obj.optCleanString("source") ?: displayName
                        )
                    )
                }
            }.sortedWith(
                compareByDescending<PlaybackSource> { it.qualityHeight }
                    .thenBy { it.format == "DASH" || it.format == "HLS" }
            )

            if (se > 0 && ep > 0) {
                val epSizes = sources
                    .filter { it.format != "DASH" && it.format != "HLS" && it.qualityHeight > 0 && (it.sizeMb ?: 0.0) > 0.0 }
                    .associate { it.qualityHeight to (it.sizeMb ?: 0.0) }
                if (epSizes.isNotEmpty()) {
                    episodeSizesCache.getOrPut("$titleId:$se") { ConcurrentHashMap() }[ep] = epSizes
                }
            }

            val resolvedTitle = titleCache[titleId]
                ?: root.optCleanString("title")
                ?: "Video"
            if (sources.isEmpty()) {
                PlaybackResolution.Unavailable(
                    title = resolvedTitle,
                    reason = "This title is not currently available to play.",
                    providerName = displayName,
                    sourceBadge = DataSourceBadge.LIVE_MOVIEBOX
                )
            } else {
                val subtitles = getSubtitles(titleId, seasonNumber, episodeNumber)
                    .getOrDefault(emptyList())
                val heading = if (se > 0 && ep > 0) "Season $se · Episode $ep" else null
                val cachedDetailMarkers = markerWindowCache[titleId]
                val introWindow = parseMarkerWindow(root, "intro") ?: cachedDetailMarkers?.first
                val recapWindow = parseMarkerWindow(root, "recap") ?: cachedDetailMarkers?.second
                PlaybackResolution.Available(
                    title = resolvedTitle,
                    subtitleHeading = heading,
                    sources = sources,
                    subtitles = subtitles,
                    providerName = displayName,
                    sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
                    introWindow = introWindow,
                    recapWindow = recapWindow
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
            val se = seasonNumber?.takeIf { it > 0 } ?: 0
            val ep = episodeNumber?.takeIf { it > 0 } ?: 0
            val url = requireBaseHttpUrl().newBuilder()
                .addPathSegment("api")
                .addPathSegment("stream")
                .addPathSegment(titleId)
                .addPathSegment("captions")
                .addQueryParameter("se", se.toString())
                .addQueryParameter("ep", ep.toString())
                .build()

            val body = executeGet(url)
            val root = JSONObject(body)
            val captions = root.optJSONArray("captions") ?: JSONArray()

            buildList {
                for (i in 0 until captions.length()) {
                    val obj = captions.optJSONObject(i) ?: continue
                    val subUrl = obj.optCleanString("url") ?: continue
                    val language = obj.optCleanString("language") ?: "English"
                    val code = obj.optCleanString("code")
                        ?: language.take(2).lowercase(Locale.US)
                    val id = obj.optCleanString("id") ?: "mb_sub_$i"
                    val format = if (subUrl.contains(".vtt", ignoreCase = true)) "vtt" else "srt"
                    add(
                        SubtitleTrack(
                            id = id,
                            language = language,
                            languageCode = code,
                            label = language,
                            url = subUrl,
                            format = format
                        )
                    )
                }
            }
        }
    }

    private fun parseMarkerWindow(obj: JSONObject, prefix: String): PlaybackMarkerWindow? {
        val startMs = obj.optLongOrNull("${prefix}_start_ms")
            ?: obj.optLongOrNull("${prefix}StartMs")
        val endMs = obj.optLongOrNull("${prefix}_end_ms")
            ?: obj.optLongOrNull("${prefix}EndMs")
        if (startMs != null && endMs != null && startMs >= 0L && endMs > startMs) {
            return PlaybackMarkerWindow(startMs = startMs, endMs = endMs)
        }
        return null
    }

    private fun parseSubjectObject(
        obj: JSONObject,
        backdropOverride: String? = null,
        trailerOverride: String? = null
    ): MovieTitleSummary? {
        val id = (obj.optCleanString("subjectId") ?: obj.optCleanString("subject_id"))
            ?.takeUnless { it == "0" } ?: return null
        val subjectType = obj.optIntOrNull("subjectType") ?: obj.optIntOrNull("subject_type")
        if (subjectType != null && subjectType !in VALID_SUBJECT_TYPES) return null
        val title = obj.optCleanString("title") ?: return null
        val dp = obj.optCleanString("detailPath") ?: obj.optCleanString("detail_path")

        val coverUrl = obj.optJSONObject("cover")?.optCleanString("url")
            ?: obj.optCleanString("cover")
            ?: obj.optJSONObject("image")?.optCleanString("url")
        val stillsUrl = obj.optJSONObject("stills")?.optCleanString("url")
        val trailerObj = obj.optJSONObject("trailer")
        val trailerUrl = trailerOverride
            ?: trailerObj?.optCleanString("url")
            ?: trailerObj?.optJSONObject("videoAddress")?.optCleanString("url")
        val trailerHeaders = parseHeadersObject(trailerObj?.optJSONObject("headers"))
        cacheMetadata(id, title, dp, trailerUrl, trailerHeaders)
        val hasResource = if (obj.has("hasResource")) obj.optBoolean("hasResource", true) else true
        val releaseDateStr = obj.optCleanString("releaseDate") ?: obj.optCleanString("release_date")

        return MovieTitleSummary(
            id = id,
            providerId = PROVIDER_ID,
            kind = parseMediaKind(subjectType, obj.optCleanString("type")),
            title = title,
            releaseYear = parseYear(releaseDateStr),
            rating = obj.optRating("imdbRatingValue", "imdb_rating", "rating"),
            durationMinutes = parseDurationMinutes(obj.optLongOrNull("duration")),
            posterUrl = coverUrl,
            backdropUrl = backdropOverride ?: stillsUrl,
            genres = parseGenres(obj.optCleanString("genre")),
            summary = obj.optCleanString("description"),
            language = obj.optCleanString("language"),
            country = obj.optCleanString("countryName") ?: obj.optCleanString("country"),
            detailPath = dp,
            trailerUrl = trailerUrl,
            trailerHeaders = trailerHeaders,
            sourceBadge = DataSourceBadge.LIVE_MOVIEBOX,
            isPlayableHint = hasResource,
            imdbId = obj.optCleanString("imdbId") ?: obj.optCleanString("imdb_id"),
            releaseDate = releaseDateStr
        )
    }

    private fun parseHeadersObject(headersObj: JSONObject?): Map<String, String> {
        if (headersObj == null) return emptyMap()
        return buildMap {
            val keys = headersObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                headersObj.optCleanString(key)?.let { value ->
                    put(key, value)
                }
            }
        }
    }

    private fun cacheMetadata(
        subjectId: String,
        title: String?,
        detailPath: String?,
        trailerUrl: String? = null,
        trailerHeaders: Map<String, String> = emptyMap()
    ) {
        if (!title.isNullOrBlank()) {
            titleCache[subjectId] = title
        }
        if (!detailPath.isNullOrBlank()) {
            detailPathCache[subjectId] = detailPath
        }
        if (!trailerUrl.isNullOrBlank()) {
            trailerCache[subjectId] = trailerUrl
        }
        if (trailerHeaders.isNotEmpty()) {
            trailerHeadersCache[subjectId] = trailerHeaders
        }
    }

    private suspend fun requireBaseHttpUrl(): HttpUrl {
        val raw = activeBaseUrl()
        if (raw.isBlank()) {
            throw IllegalStateException(
                "Catalogue service address is not configured."
            )
        }
        if (!MovieBoxEndpointConfig.isValidBaseUrl(raw, allowDebugCleartextLoopback = BuildConfig.DEBUG)) {
            throw IllegalStateException(
                "Invalid service address. HTTPS is required in release builds."
            )
        }
        return runCatching { raw.toHttpUrl() }.getOrElse { err ->
            throw IllegalStateException("Invalid service address '${raw.substringBefore("?")}': ${err.message}", err)
        }
    }

    private suspend fun executeGet(url: HttpUrl): String {
        val redactedPath = url.encodedPath
        val maxTransientAttempts = 2
        var transientAttempt = 1
        var authRefreshAvailable = true
        var pendingRefreshedToken: String? = null
        var lastError: IOException? = null

        while (transientAttempt <= maxTransientAttempts) {
            val sentAppCheckToken = pendingRefreshedToken?.also { pendingRefreshedToken = null }
                ?: runCatching { appCheckTokenProvider(false) }.getOrNull()?.trim().orEmpty()

            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()

            if (sentAppCheckToken.isNotBlank()) {
                requestBuilder.header("X-Firebase-AppCheck", sentAppCheckToken)
            }

            val startNs = System.nanoTime()
            try {
                var shouldRetryAuthRefresh = false
                var shouldRetryTransient = false

                okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val detail = runCatching { JSONObject(body).optCleanString("detail") }.getOrNull()
                        safeLogWarn("GET $redactedPath -> HTTP ${response.code} (${elapsedMs}ms)")

                        if (response.code in setOf(401, 403) && authRefreshAvailable) {
                            authRefreshAvailable = false
                            val refreshed = runCatching { appCheckTokenProvider(true) }
                                .getOrNull()
                                ?.trim()
                                .orEmpty()
                            if (refreshed.isNotBlank() && refreshed != sentAppCheckToken) {
                                pendingRefreshedToken = refreshed
                                shouldRetryAuthRefresh = true
                                return@use
                            }
                        }

                        val isRetryableStatus = response.code in setOf(502, 503, 504)
                        if (isRetryableStatus && transientAttempt < maxTransientAttempts) {
                            shouldRetryTransient = true
                            return@use
                        }

                        throw MovieBoxApiException(
                            httpCode = response.code,
                            message = buildHttpErrorMessage(
                                code = response.code,
                                detail = detail,
                                sentAppCheckToken = sentAppCheckToken.isNotBlank()
                            )
                        )
                    }

                    if (body.isBlank()) {
                        safeLogWarn("GET $redactedPath -> HTTP ${response.code} empty body (${elapsedMs}ms)")
                        throw MovieBoxApiException(
                            httpCode = response.code,
                            message = "The catalogue service returned an empty response."
                        )
                    }

                    safeLogInfo("GET $redactedPath -> HTTP ${response.code} (${elapsedMs}ms)")
                    return body
                }

                if (shouldRetryAuthRefresh) {
                    continue
                }
                if (shouldRetryTransient) {
                    transientAttempt++
                    if (retryDelayMs > 0L) delay(retryDelayMs)
                    continue
                }
            } catch (e: MovieBoxApiException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                val isNonRetryableNetwork = e is UnknownHostException || e is SSLException
                safeLogWarn("GET $redactedPath -> network error (${e.javaClass.simpleName})")
                if (!isNonRetryableNetwork && transientAttempt < maxTransientAttempts) {
                    transientAttempt++
                    if (retryDelayMs > 0L) delay(retryDelayMs)
                    continue
                }
                throw MovieBoxApiException(
                    httpCode = null,
                    message = buildNetworkErrorMessage(e),
                    cause = e
                )
            }
        }

        throw lastError ?: MovieBoxApiException(
            httpCode = null,
            message = "Unable to reach the catalogue service. Please check your connection and retry."
        )
    }

    private fun buildHttpErrorMessage(
        code: Int,
        detail: String?,
        sentAppCheckToken: Boolean = false
    ): String {
        val safeDetail = sanitizeDetailForUser(detail)
        return when (code) {
            401, 403 -> buildString {
                append("Access could not be verified right now. ")
                if (sentAppCheckToken) {
                    append("Please check your connection or update the app and try again.")
                } else {
                    append("Verification is temporarily unavailable on this device.")
                }
                if (safeDetail != null) {
                    append(" ").append(safeDetail)
                }
            }
            404 -> safeDetail ?: "This title is not currently available."
            429 -> "Too many requests right now. Please try again in a moment."
            502, 503, 504 -> safeDetail ?: "The streaming service is temporarily unavailable. Please retry shortly."
            else -> safeDetail ?: "Could not load catalogue data right now."
        }
    }

    private fun sanitizeDetailForUser(detail: String?): String? {
        val clean = detail?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val blockedTerms = listOf(
            "moviebox",
            "tvmaze",
            "tmdb",
            "x-user",
            "app check",
            "appcheck",
            "bootstrap",
            "token",
            "http ",
            "upstream"
        )
        if (blockedTerms.any { clean.contains(it, ignoreCase = true) }) {
            return null
        }
        return clean
    }

    private fun buildNetworkErrorMessage(error: IOException): String = when (error) {
        is SocketTimeoutException ->
            "Request timed out. Please retry in a moment."
        is UnknownHostException ->
            "Cannot reach the catalogue service. Check your internet connection and retry."
        is SSLException ->
            "Secure connection to the catalogue service could not be established."
        else ->
            "Connection problem while reaching the catalogue service. Please try again."
    }

    private fun safeLogInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun safeLogWarn(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private fun parseMediaKind(subjectType: Int?, typeLabel: String?): MediaKind = when {
        subjectType == 1 -> MediaKind.MOVIE
        subjectType == 2 || subjectType == 7 -> MediaKind.TV_SERIES
        typeLabel != null && (typeLabel.contains("series", ignoreCase = true) ||
            typeLabel.contains("tv", ignoreCase = true)) -> MediaKind.TV_SERIES
        else -> MediaKind.MOVIE
    }

    private fun parseYear(releaseDate: String?, yearStr: String? = null): Int? =
        yearStr?.trim()?.take(4)?.toIntOrNull()
            ?: releaseDate?.trim()?.take(4)?.toIntOrNull()

    private fun parseGenres(raw: String?): List<String> =
        raw?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun parseDurationMinutes(durationSeconds: Long?): Int? {
        val sec = durationSeconds?.takeIf { it > 0L } ?: return null
        val mins = (sec / 60L).toInt()
        return if (mins > 0) mins else 1
    }

    private fun parseQualityHeight(label: String): Int {
        for (q in listOf(2160, 1440, 1080, 720, 480, 360, 240)) {
            if (label.contains(q.toString())) return q
        }
        return if (label.contains("adaptive", ignoreCase = true)) 999 else 480
    }

    private fun inferFormatFromUrl(url: String): String = when {
        url.contains(".mpd", ignoreCase = true) -> "DASH"
        url.contains(".m3u8", ignoreCase = true) -> "HLS"
        url.contains(".webm", ignoreCase = true) -> "WEBM"
        url.contains(".mkv", ignoreCase = true) -> "MKV"
        else -> "MP4"
    }

    private fun JSONObject.optCleanString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val raw = opt(key)?.toString()?.trim() ?: return null
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true) || raw.equals("None", ignoreCase = true)) {
            return null
        }
        return raw
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        optCleanString(key)?.toIntOrNull()

    private fun JSONObject.optLongOrNull(key: String): Long? =
        optCleanString(key)?.toLongOrNull()

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        optCleanString(key)?.toDoubleOrNull()

    private fun JSONObject.optRating(vararg keys: String): Double? {
        for (k in keys) {
            val v = optDoubleOrNull(k)?.takeIf { it > 0.0 }
            if (v != null) return v
        }
        return null
    }

    companion object {
        private const val TAG = "MovieBoxProvider"
        const val PROVIDER_ID = "moviebox"
        private val VALID_SUBJECT_TYPES = setOf(1, 2, 7)
        private val YEAR_REGEX = Regex("^\\d{4}$")

        val VERIFIED_GENRES = listOf(
            "Anime", "Action", "Action & Adventure", "Adventure", "Animation", "Biography", "Comedy",
            "Crime", "Documentary", "Drama", "Family", "History",
            "Horror", "Kids", "Music", "Musical", "Mystery", "Romance",
            "Sci-Fi", "Sci-Fi & Fantasy", "Sport", "Thriller", "War", "Western"
        )

        val VERIFIED_COUNTRIES = listOf(
            "United States", "India", "Korea", "United Kingdom", "Japan", "Nigeria"
        )

        val DEFAULT_GENRES = listOf("All") + VERIFIED_GENRES

        private val GENRE_MAP = VERIFIED_GENRES.associateBy { it.lowercase(Locale.US) } + mapOf(
            "fantasy" to "Sci-Fi & Fantasy"
        )
        private val COUNTRY_MAP = VERIFIED_COUNTRIES.associateBy { it.lowercase(Locale.US) } +
            mapOf("south korea" to "Korea")

        fun normalizeVerifiedGenre(raw: String?): String? {
            val clean = raw?.trim()?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) } ?: return null
            return GENRE_MAP[clean.lowercase(Locale.US)]
        }

        fun normalizeVerifiedCountry(raw: String?): String? {
            val clean = raw?.trim()?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) } ?: return null
            return COUNTRY_MAP[clean.lowercase(Locale.US)]
        }
    }
}
