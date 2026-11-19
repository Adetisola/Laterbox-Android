package com.laterbox.app.movies.data.provider

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
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SectionStyle
import com.laterbox.app.movies.domain.provider.MovieDetailsProvider
import com.laterbox.app.movies.domain.provider.MoviesCatalogueProvider
import com.laterbox.app.movies.domain.provider.MoviesSearchProvider
import com.laterbox.app.movies.domain.provider.PlaybackSourceProvider
import com.laterbox.app.movies.domain.provider.SeriesEpisodeProvider
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
import java.util.Locale

class TvMazeSeriesProvider(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String = "https://api.tvmaze.com"
) : MoviesCatalogueProvider,
    MoviesSearchProvider,
    MovieDetailsProvider,
    SeriesEpisodeProvider,
    PlaybackSourceProvider {

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "TVMaze Series Guide"
    override val sourceBadge: DataSourceBadge = DataSourceBadge.LIVE_TVMAZE

    override suspend fun getHomeFeed(): Result<MoviesHomeFeed> = withContext(Dispatchers.IO) {
        runCatching {
            coroutineScope {
                val showsDeferred = async { fetchShowIndex(apiPage = 0) }
                val scheduleDeferred = async { fetchTrendingSchedule() }
                val webScheduleDeferred = async { fetchWebStreamingSchedule() }

                val allShows = showsDeferred.await()
                val broadcastToday = scheduleDeferred.await().getOrDefault(emptyList())
                val webToday = webScheduleDeferred.await().getOrDefault(emptyList())
                val trendingToday = (webToday + broadcastToday)
                    .distinctBy { it.id }
                    .sortedByDescending { it.rating ?: 0.0 }
                    .take(18)

                val popularShows = allShows
                    .sortedByDescending { it.weight }
                    .map { it.summary }
                    .take(15)

                val topRatedShows = allShows
                    .filter { (it.summary.rating ?: 0.0) >= 7.8 }
                    .sortedByDescending { it.summary.rating ?: 0.0 }
                    .map { it.summary }
                    .take(15)

                val banners = (trendingToday.take(3) + topRatedShows.take(3))
                    .distinctBy { it.id }
                    .take(5)

                val sections = buildList {
                    if (trendingToday.isNotEmpty()) {
                        add(
                            BrowseSection(
                                id = "tvmaze_trending_tonight",
                                title = "Trending TV Series",
                                subtitle = "Streaming & on air via TVMaze guide",
                                style = SectionStyle.RAIL,
                                items = trendingToday
                            )
                        )
                    }
                    if (topRatedShows.isNotEmpty()) {
                        add(
                            BrowseSection(
                                id = "tvmaze_top_rated",
                                title = "Critically Acclaimed Series",
                                subtitle = "Top-rated television with full episode guides",
                                style = SectionStyle.RAIL,
                                items = topRatedShows
                            )
                        )
                    }
                    if (popularShows.isNotEmpty()) {
                        add(
                            BrowseSection(
                                id = "tvmaze_popular",
                                title = "Popular Series Catalogue",
                                subtitle = "Browse seasons, cast, and episode breakdowns",
                                style = SectionStyle.RAIL,
                                items = popularShows
                            )
                        )
                    }
                }

                MoviesHomeFeed(
                    featuredBanners = banners,
                    sections = sections,
                    genres = SUPPORTED_GENRES,
                    activeProviderNotice = "TVMaze series metadata & episode guides active"
                )
            }
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
        if (kind == MediaKind.MOVIE) {
            return@withContext Result.success(
                CataloguePage(items = emptyList(), page = page, pageSize = pageSize, totalCount = 0, hasMore = false)
            )
        }
        runCatching {
            val safePage = page.coerceAtLeast(1)
            val showsPerUiChunk = pageSize.coerceAtLeast(1)
            // TVMaze returns up to 250 shows per /shows?page=N endpoint
            val apiPage = ((safePage - 1) * showsPerUiChunk) / 250
            val offsetWithinApiPage = ((safePage - 1) * showsPerUiChunk) % 250

            val indexedShows = fetchShowIndex(apiPage = apiPage)
            val filtered = indexedShows.filter { indexed ->
                (genre.isNullOrBlank() ||
                    genre.equals("All", ignoreCase = true) ||
                    indexed.summary.genres.any { g -> g.equals(genre, ignoreCase = true) }) &&
                    (country.isNullOrBlank() || country.equals("All", ignoreCase = true) ||
                        indexed.summary.country.isNullOrBlank() ||
                        indexed.summary.country.equals(country, ignoreCase = true)) &&
                    (year.isNullOrBlank() || year.equals("All", ignoreCase = true) ||
                        indexed.summary.releaseYear?.toString() == year.trim())
            }
            val sorted = when (sort) {
                CatalogueSort.POPULAR -> filtered.sortedByDescending { it.weight }
                CatalogueSort.TOP_RATED -> filtered.sortedByDescending { it.summary.rating ?: 0.0 }
                CatalogueSort.NEWEST -> filtered.sortedByDescending { it.summary.releaseYear ?: 0 }
            }

            val pageItems = if (offsetWithinApiPage < sorted.size) {
                sorted.subList(offsetWithinApiPage, minOf(offsetWithinApiPage + showsPerUiChunk, sorted.size))
                    .map { it.summary }
            } else {
                emptyList()
            }

            CataloguePage(
                items = pageItems,
                page = safePage,
                pageSize = showsPerUiChunk,
                totalCount = sorted.size,
                hasMore = (offsetWithinApiPage + pageItems.size < sorted.size) || indexedShows.size >= 240
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
        if (kind == MediaKind.MOVIE) {
            return@withContext Result.success(
                CataloguePage(items = emptyList(), page = page, pageSize = pageSize, totalCount = 0, hasMore = false)
            )
        }
        val clean = query.trim()
        if (clean.isBlank()) {
            return@withContext Result.success(CataloguePage(emptyList(), page, pageSize, 0, false))
        }
        runCatching {
            val results = fetchSearchShows(clean)
            val fromIndex = ((page.coerceAtLeast(1) - 1) * pageSize).coerceAtLeast(0)
            val slice = if (fromIndex < results.size) {
                results.subList(fromIndex, minOf(fromIndex + pageSize, results.size))
            } else {
                emptyList()
            }
            CataloguePage(
                items = slice,
                page = page,
                pageSize = pageSize,
                totalCount = results.size,
                hasMore = fromIndex + slice.size < results.size
            )
        }
    }

    override suspend fun getSuggestions(query: String, limit: Int): Result<List<SearchSuggestion>> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.length < 2) return@withContext Result.success(emptyList())
            runCatching {
                fetchSearchShows(clean).take(limit).map {
                    SearchSuggestion(
                        id = it.id,
                        providerId = providerId,
                        title = it.title,
                        kind = MediaKind.TV_SERIES,
                        releaseYear = it.releaseYear,
                        posterUrl = it.posterUrl
                    )
                }
            }
        }

    override suspend fun getDetails(titleId: String): Result<MediaDetails> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$normalizedBaseUrl/shows/$titleId?embed[]=cast&embed[]=seasons&embed[]=episodes"
            val raw = executeGet(url)
            val showObj = JSONObject(raw)
            val summary = parseShowSummary(showObj)
            val embedded = showObj.optJSONObject("_embedded") ?: JSONObject()

            val cast = parseCast(embedded.optJSONArray("cast"))
            val episodes = parseAllEpisodes(embedded.optJSONArray("episodes"))
            val seasons = parseSeasons(embedded.optJSONArray("seasons"), episodes)

            val networkObj = showObj.optJSONObject("network") ?: showObj.optJSONObject("webChannel")
            val networkName = networkObj?.optString("name")?.takeIf { it.isNotBlank() }

            val related = runCatching {
                val primaryGenre = summary.genres.firstOrNull()
                fetchShowIndex(0)
                    .map { it.summary }
                    .filter { it.id != summary.id && (primaryGenre == null || primaryGenre in it.genres) }
                    .take(6)
            }.getOrDefault(emptyList())

            MediaDetails(
                summary = summary,
                fullDescription = summary.summary ?: "No synopsis provided.",
                directorOrCreator = null,
                networkOrStudio = networkName,
                cast = cast,
                seasons = seasons,
                audioDubs = emptyList(),
                availableSubtitles = emptyList(),
                relatedTitles = related,
                externalInfoUrl = showObj.optString("url").takeIf { it.isNotBlank() }
            )
        }
    }

    override suspend fun getSeasons(seriesId: String): Result<List<SeasonSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val rawSeasons = executeGet("$normalizedBaseUrl/shows/$seriesId/seasons")
            parseSeasons(JSONArray(rawSeasons), emptyList())
        }
    }

    override suspend fun getEpisodes(seriesId: String, seasonNumber: Int): Result<List<EpisodeItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rawEpisodes = executeGet("$normalizedBaseUrl/shows/$seriesId/episodes")
                parseAllEpisodes(JSONArray(rawEpisodes))
                    .filter { it.seasonNumber == seasonNumber }
                    .sortedBy { it.episodeNumber }
            }
        }

    override suspend fun resolvePlaybackSources(
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Result<PlaybackResolution> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = executeGet("$normalizedBaseUrl/shows/$titleId")
            val showObj = JSONObject(raw)
            val title = showObj.optString("name").ifBlank { "Series #$titleId" }
            val refUrl = showObj.optString("url").takeIf { it.isNotBlank() }
            val episodeSuffix = if (seasonNumber != null && seasonNumber > 0 && episodeNumber != null && episodeNumber > 0) {
                " (S${seasonNumber}E${episodeNumber})"
            } else {
                ""
            }
            PlaybackResolution.Unavailable(
                title = "$title$episodeSuffix",
                reason = "Streaming isn't available directly here. Open the episode guide for broadcast details.",
                providerName = displayName,
                externalReferenceUrl = refUrl,
                sourceBadge = sourceBadge
            )
        }
    }

    suspend fun getPersonFilmography(
        personId: String,
        personName: String,
        personImageUrl: String? = null
    ): Result<PersonFilmography> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanId = personId.trim()
            require(cleanId.isNotBlank()) { "Person ID is required." }
            val rawCredits = executeGet("$normalizedBaseUrl/people/$cleanId/castcredits?embed=show")
            val arr = JSONArray(rawCredits)
            val credits = buildList {
                for (i in 0 until arr.length()) {
                    val entry = arr.optJSONObject(i) ?: continue
                    val show = entry.optJSONObject("_embedded")?.optJSONObject("show")
                        ?: entry.optJSONObject("show")
                        ?: continue
                    add(parseShowSummary(show))
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

    private data class IndexedShow(val summary: MovieTitleSummary, val weight: Int)

    private fun fetchShowIndex(apiPage: Int): List<IndexedShow> {
        val raw = executeGet("$normalizedBaseUrl/shows?page=$apiPage")
        val arr = JSONArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(IndexedShow(summary = parseShowSummary(obj), weight = obj.optInt("weight", 0)))
            }
        }
    }

    private fun fetchTrendingSchedule(): Result<List<MovieTitleSummary>> = runCatching {
        val raw = executeGet("$normalizedBaseUrl/schedule?country=US")
        val arr = JSONArray(raw)
        val seen = mutableSetOf<String>()
        buildList {
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val show = entry.optJSONObject("show") ?: continue
                val summary = parseShowSummary(show)
                if (seen.add(summary.id)) {
                    add(summary)
                }
            }
        }.take(15)
    }

    private fun fetchWebStreamingSchedule(): Result<List<MovieTitleSummary>> = runCatching {
        val raw = executeGet("$normalizedBaseUrl/schedule/web")
        val arr = JSONArray(raw)
        val seen = mutableSetOf<String>()
        buildList {
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val show = entry.optJSONObject("_embedded")?.optJSONObject("show")
                    ?: entry.optJSONObject("show")
                    ?: continue
                val summary = parseShowSummary(show)
                if (seen.add(summary.id)) {
                    add(summary)
                }
            }
        }.take(15)
    }

    private fun fetchSearchShows(query: String): List<MovieTitleSummary> {
        val url = "$normalizedBaseUrl/search/shows".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        val raw = executeGet(url.toString())
        val arr = JSONArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val show = item.optJSONObject("show") ?: continue
                add(parseShowSummary(show))
            }
        }
    }

    private fun parseShowSummary(obj: JSONObject): MovieTitleSummary {
        val id = obj.optLong("id").toString()
        val imageObj = obj.optJSONObject("image")
        val mediumImg = imageObj?.optString("medium")?.takeIf { it.isNotBlank() && it != "null" }
        val originalImg = imageObj?.optString("original")?.takeIf { it.isNotBlank() && it != "null" }
        val ratingObj = obj.optJSONObject("rating")
        val rating = if (ratingObj != null && !ratingObj.isNull("average")) {
            ratingObj.optDouble("average", Double.NaN).takeIf { !it.isNaN() }
        } else {
            null
        }
        val genresArr = obj.optJSONArray("genres") ?: JSONArray()
        val genres = buildList {
            for (i in 0 until genresArr.length()) {
                genresArr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
        val premiered = obj.optString("premiered").takeIf { it.isNotBlank() && it != "null" }
        val year = premiered?.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull()
        val runtime = if (obj.isNull("averageRuntime")) {
            if (obj.isNull("runtime")) null else obj.optInt("runtime").takeIf { it > 0 }
        } else {
            obj.optInt("averageRuntime").takeIf { it > 0 }
        }
        val countryName = obj.optJSONObject("network")?.optJSONObject("country")?.optString("name")
            ?.takeIf { it.isNotBlank() }
        val rawSummary = obj.optString("summary").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        val cleanSummary = if (rawSummary.isNotBlank()) Jsoup.parse(rawSummary).text().trim() else null

        val imdbId = obj.optJSONObject("externals")?.optString("imdb")?.takeIf { it.isNotBlank() && it != "null" }

        return MovieTitleSummary(
            id = id,
            providerId = providerId,
            kind = MediaKind.TV_SERIES,
            title = obj.optString("name").ifBlank { "Untitled Series" },
            releaseYear = year,
            rating = rating,
            durationMinutes = runtime,
            posterUrl = originalImg ?: mediumImg,
            backdropUrl = null,
            genres = genres,
            summary = cleanSummary,
            language = obj.optString("language").takeIf { it.isNotBlank() && it != "null" },
            country = countryName,
            sourceBadge = sourceBadge,
            isPlayableHint = false,
            imdbId = imdbId,
            tvmazeId = id,
            releaseDate = premiered
        )
    }

    private fun parseCast(castArr: JSONArray?): List<CastMember> {
        if (castArr == null) return emptyList()
        return buildList {
            for (i in 0 until castArr.length()) {
                val entry = castArr.optJSONObject(i) ?: continue
                val person = entry.optJSONObject("person") ?: continue
                val character = entry.optJSONObject("character")
                val image = character?.optJSONObject("image")?.optString("medium")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: person.optJSONObject("image")?.optString("medium")?.takeIf { it.isNotBlank() && it != "null" }
                val personId = person.optLong("id", 0L).takeIf { it > 0L }?.toString()
                add(
                    CastMember(
                        name = person.optString("name"),
                        role = character?.optString("name")?.takeIf { it.isNotBlank() },
                        imageUrl = image,
                        personId = personId,
                        providerId = PROVIDER_ID
                    )
                )
            }
        }.distinctBy { it.name }.take(20)
    }

    private fun parseSeasons(seasonsArr: JSONArray?, episodes: List<EpisodeItem>): List<SeasonSummary> {
        val countsBySeason = episodes.groupingBy { it.seasonNumber }.eachCount()
        if (seasonsArr == null) {
            return countsBySeason.entries.sortedBy { it.key }.map { (se, count) ->
                SeasonSummary(seasonNumber = se, title = "Season $se", episodeCount = count)
            }
        }
        return buildList {
            for (i in 0 until seasonsArr.length()) {
                val s = seasonsArr.optJSONObject(i) ?: continue
                val num = s.optInt("number", i + 1)
                val orderCount = if (s.isNull("episodeOrder")) 0 else s.optInt("episodeOrder", 0)
                val actualCount = countsBySeason[num] ?: orderCount
                val title = s.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: "Season $num"
                val img = s.optJSONObject("image")?.optString("medium")?.takeIf { it.isNotBlank() && it != "null" }
                add(SeasonSummary(seasonNumber = num, title = title, episodeCount = actualCount, imageUrl = img))
            }
        }.sortedBy { it.seasonNumber }
    }

    private fun parseAllEpisodes(epArr: JSONArray?): List<EpisodeItem> {
        if (epArr == null) return emptyList()
        return buildList {
            for (i in 0 until epArr.length()) {
                val ep = epArr.optJSONObject(i) ?: continue
                val season = ep.optInt("season", 1)
                val number = if (ep.isNull("number")) i + 1 else ep.optInt("number", i + 1)
                val rawOverview = ep.optString("summary").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                val overview = if (rawOverview.isNotBlank()) Jsoup.parse(rawOverview).text().trim() else null
                val stillUrl = ep.optJSONObject("image")?.optString("medium")?.takeIf { it.isNotBlank() && it != "null" }
                val runtime = if (ep.isNull("runtime")) null else ep.optInt("runtime").takeIf { it > 0 }
                add(
                    EpisodeItem(
                        id = ep.optLong("id").toString(),
                        seasonNumber = season,
                        episodeNumber = number,
                        title = ep.optString("name").ifBlank { "Episode $number" },
                        overview = overview,
                        runtimeMinutes = runtime,
                        airDate = ep.optString("airdate").takeIf { it.isNotBlank() && it != "null" },
                        stillUrl = stillUrl
                    )
                )
            }
        }
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
                throw IOException("TVMaze returned HTTP ${response.code}")
            }
            return response.body?.string() ?: throw IOException("Empty response from TVMaze")
        }
    }

    private val normalizedBaseUrl: String get() = baseUrl.trimEnd('/')

    companion object {
        const val PROVIDER_ID = "tvmaze"

        val SUPPORTED_GENRES = listOf(
            "All",
            "Drama",
            "Comedy",
            "Science-Fiction",
            "Thriller",
            "Crime",
            "Mystery",
            "Action",
            "Adventure",
            "Horror",
            "Anime"
        )
    }
}
