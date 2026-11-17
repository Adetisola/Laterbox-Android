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
import java.io.IOException

/**
 * Optional provider integrating the official TMDB v3 API when the user provides their own API key.
 * Ships with zero embedded keys and explicitly marks playback unavailable unless paired with a licensed player.
 */
class TmdbOptionalMoviesProvider(
    private val okHttpClient: OkHttpClient,
    private val apiKeyProvider: suspend () -> String,
    private val baseUrl: String = "https://api.themoviedb.org/3",
    private val imageBaseUrl: String = "https://image.tmdb.org/t/p"
) : MoviesCatalogueProvider,
    MoviesSearchProvider,
    MovieDetailsProvider,
    SeriesEpisodeProvider,
    PlaybackSourceProvider {

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "TMDB Catalogue"
    override val sourceBadge: DataSourceBadge = DataSourceBadge.LIVE_TMDB

    suspend fun isConfigured(): Boolean = apiKeyProvider().isNotBlank()

    override suspend fun getHomeFeed(): Result<MoviesHomeFeed> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("TMDB API key not configured"))
        }
        runCatching {
            coroutineScope {
                val trendingDeferred = async { fetchList("/trending/all/day", key) }
                val popularMoviesDeferred = async { fetchList("/movie/popular", key, MediaKind.MOVIE) }
                val popularTvDeferred = async { fetchList("/tv/popular", key, MediaKind.TV_SERIES) }

                val trending = trendingDeferred.await().getOrDefault(emptyList())
                val popMovies = popularMoviesDeferred.await().getOrDefault(emptyList())
                val popTv = popularTvDeferred.await().getOrDefault(emptyList())

                if (trending.isEmpty() && popMovies.isEmpty() && popTv.isEmpty()) {
                    trendingDeferred.await().getOrThrow()
                }

                MoviesHomeFeed(
                    featuredBanners = trending.take(5),
                    sections = listOfNotNull(
                        trending.takeIf { it.isNotEmpty() }?.let {
                            BrowseSection("tmdb_trending", "TMDB Trending Today", "Global trending film & series", SectionStyle.RAIL, it)
                        },
                        popMovies.takeIf { it.isNotEmpty() }?.let {
                            BrowseSection("tmdb_movies", "Popular Movies (TMDB)", "Top theatrical & streaming releases", SectionStyle.RAIL, it)
                        },
                        popTv.takeIf { it.isNotEmpty() }?.let {
                            BrowseSection("tmdb_tv", "Popular Series (TMDB)", "Trending series metadata & seasons", SectionStyle.RAIL, it)
                        }
                    ),
                    genres = GENRES_BY_ID.values.distinct().sorted(),
                    activeProviderNotice = "TMDB metadata active with your configured API key"
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
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("TMDB API key not configured"))
        }
        runCatching {
            val endpoint = if (kind == MediaKind.MOVIE) "/discover/movie" else "/discover/tv"
            val sortParam = when (sort) {
                CatalogueSort.POPULAR -> "popularity.desc"
                CatalogueSort.TOP_RATED -> "vote_average.desc"
                CatalogueSort.NEWEST -> if (kind == MediaKind.MOVIE) "primary_release_date.desc" else "first_air_date.desc"
            }
            val genreId = GENRES_BY_ID.entries.firstOrNull { it.value.equals(genre, ignoreCase = true) }?.key
            val urlBuilder = "$normalizedBase$endpoint".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", key)
                .addQueryParameter("page", page.coerceAtLeast(1).toString())
                .addQueryParameter("sort_by", sortParam)
            if (genreId != null) {
                urlBuilder.addQueryParameter("with_genres", genreId.toString())
            }
            val root = JSONObject(executeGet(urlBuilder.build().toString()))
            val arr = root.optJSONArray("results") ?: JSONArray()
            val items = parseSummaryArray(arr, kind)
            val totalPages = root.optInt("total_pages", page)
            val totalResults = root.optInt("total_results", items.size)
            CataloguePage(
                items = items,
                page = page,
                pageSize = items.size.coerceAtLeast(20),
                totalCount = totalResults,
                hasMore = page < totalPages
            )
        }
    }

    override suspend fun getGenres(): Result<List<String>> =
        Result.success(listOf("All") + GENRES_BY_ID.values.distinct().sorted())

    override suspend fun search(
        query: String,
        kind: MediaKind?,
        page: Int,
        pageSize: Int
    ): Result<CataloguePage> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("TMDB API key not configured"))
        }
        runCatching {
            val path = when (kind) {
                MediaKind.MOVIE -> "/search/movie"
                MediaKind.TV_SERIES -> "/search/tv"
                null -> "/search/multi"
            }
            val url = "$normalizedBase$path".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", key)
                .addQueryParameter("query", query.trim())
                .addQueryParameter("page", page.coerceAtLeast(1).toString())
                .build()
            val root = JSONObject(executeGet(url.toString()))
            val items = parseSummaryArray(root.optJSONArray("results") ?: JSONArray(), kind)
            val totalPages = root.optInt("total_pages", page)
            CataloguePage(
                items = items,
                page = page,
                pageSize = items.size.coerceAtLeast(20),
                totalCount = root.optInt("total_results", items.size),
                hasMore = page < totalPages
            )
        }
    }

    override suspend fun getSuggestions(query: String, limit: Int): Result<List<SearchSuggestion>> =
        search(query, null, page = 1, pageSize = limit).map { page ->
            page.items.take(limit).map {
                SearchSuggestion(it.id, providerId, it.title, it.kind, it.releaseYear, it.posterUrl)
            }
        }

    override suspend fun getDetails(titleId: String): Result<MediaDetails> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("TMDB API key not configured"))
        }
        val (kindPrefix, rawId) = parseCompositeId(titleId)
        runCatching {
            val kindPath = if (kindPrefix == MediaKind.TV_SERIES) "tv" else "movie"
            val url = "$normalizedBase/$kindPath/$rawId".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", key)
                .addQueryParameter("append_to_response", "credits,recommendations")
                .build()
            val obj = JSONObject(executeGet(url.toString()))
            val summary = parseSingleSummary(obj, kindPrefix)
            val credits = obj.optJSONObject("credits") ?: JSONObject()
            val castArr = credits.optJSONArray("cast") ?: JSONArray()
            val cast = buildList {
                for (i in 0 until minOf(castArr.length(), 15)) {
                    val c = castArr.optJSONObject(i) ?: continue
                    val img = c.optString("profile_path").takeIf { it.isNotBlank() && it != "null" }
                        ?.let { "$imageBaseUrl/w342$it" }
                    add(CastMember(c.optString("name"), c.optString("character").takeIf { it.isNotBlank() }, img))
                }
            }
            val seasonsArr = obj.optJSONArray("seasons") ?: JSONArray()
            val seasons = if (kindPrefix == MediaKind.TV_SERIES) {
                buildList {
                    for (i in 0 until seasonsArr.length()) {
                        val s = seasonsArr.optJSONObject(i) ?: continue
                        val num = s.optInt("season_number", 0)
                        if (num <= 0) continue
                        val img = s.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                            ?.let { "$imageBaseUrl/w342$it" }
                        add(SeasonSummary(num, s.optString("name").ifBlank { "Season $num" }, s.optInt("episode_count", 0), img))
                    }
                }
            } else emptyList()

            val recs = parseSummaryArray(
                obj.optJSONObject("recommendations")?.optJSONArray("results") ?: JSONArray(),
                kindPrefix
            ).take(8)

            MediaDetails(
                summary = summary,
                fullDescription = obj.optString("overview").ifBlank { summary.summary.orEmpty() },
                directorOrCreator = null,
                networkOrStudio = null,
                cast = cast,
                seasons = seasons,
                relatedTitles = recs,
                externalInfoUrl = "https://www.themoviedb.org/$kindPath/$rawId"
            )
        }
    }

    override suspend fun getSeasons(seriesId: String): Result<List<SeasonSummary>> =
        getDetails("tv_$seriesId").map { it.seasons }

    override suspend fun getEpisodes(seriesId: String, seasonNumber: Int): Result<List<EpisodeItem>> =
        withContext(Dispatchers.IO) {
            val key = apiKeyProvider().trim()
            if (key.isBlank()) {
                return@withContext Result.failure(IllegalStateException("TMDB API key not configured"))
            }
            val (_, rawId) = parseCompositeId(seriesId)
            runCatching {
                val url = "$normalizedBase/tv/$rawId/season/$seasonNumber".toHttpUrl().newBuilder()
                    .addQueryParameter("api_key", key)
                    .build()
                val obj = JSONObject(executeGet(url.toString()))
                val arr = obj.optJSONArray("episodes") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val ep = arr.optJSONObject(i) ?: continue
                        val img = ep.optString("still_path").takeIf { it.isNotBlank() && it != "null" }
                            ?.let { "$imageBaseUrl/w500$it" }
                        add(
                            EpisodeItem(
                                id = ep.optLong("id").toString(),
                                seasonNumber = ep.optInt("season_number", seasonNumber),
                                episodeNumber = ep.optInt("episode_number", i + 1),
                                title = ep.optString("name").ifBlank { "Episode ${i + 1}" },
                                overview = ep.optString("overview").takeIf { it.isNotBlank() },
                                runtimeMinutes = if (ep.isNull("runtime")) null else ep.optInt("runtime").takeIf { it > 0 },
                                airDate = ep.optString("air_date").takeIf { it.isNotBlank() && it != "null" },
                                stillUrl = img
                            )
                        )
                    }
                }
            }
        }

    override suspend fun resolvePlaybackSources(
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Result<PlaybackResolution> {
        val (kind, rawId) = parseCompositeId(titleId)
        val kindPath = if (kind == MediaKind.TV_SERIES) "tv" else "movie"
        return Result.success(
            PlaybackResolution.Unavailable(
                title = "TMDB Title #$rawId",
                reason = "TMDB provides official metadata only; feature playback requires a licensed streaming provider.",
                providerName = displayName,
                externalReferenceUrl = "https://www.themoviedb.org/$kindPath/$rawId",
                sourceBadge = sourceBadge
            )
        )
    }

    private fun fetchList(path: String, key: String, forcedKind: MediaKind? = null): Result<List<MovieTitleSummary>> =
        runCatching {
            val url = "$normalizedBase$path".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", key)
                .build()
            val root = JSONObject(executeGet(url.toString()))
            parseSummaryArray(root.optJSONArray("results") ?: JSONArray(), forcedKind)
        }

    private fun parseSummaryArray(arr: JSONArray, forcedKind: MediaKind?): List<MovieTitleSummary> = buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val mediaType = obj.optString("media_type")
            if (forcedKind == null && mediaType == "person") continue
            val kind = forcedKind ?: if (mediaType == "tv") MediaKind.TV_SERIES else MediaKind.MOVIE
            add(parseSingleSummary(obj, kind))
        }
    }

    private fun parseSingleSummary(obj: JSONObject, kind: MediaKind): MovieTitleSummary {
        val rawId = obj.optLong("id").toString()
        val typedId = if (kind == MediaKind.TV_SERIES) "tv_$rawId" else "mv_$rawId"
        val title = obj.optString("title").ifBlank { obj.optString("name") }.ifBlank { "Untitled" }
        val dateStr = obj.optString("release_date").ifBlank { obj.optString("first_air_date") }
        val year = dateStr.take(4).toIntOrNull()
        val rating = if (obj.isNull("vote_average")) null else obj.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
        val posterPath = obj.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
        val backdropPath = obj.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
        val genreIdsArr = obj.optJSONArray("genre_ids")
        val genres = if (genreIdsArr != null) {
            (0 until genreIdsArr.length()).mapNotNull { GENRES_BY_ID[genreIdsArr.optInt(it)] }
        } else {
            val genresArr = obj.optJSONArray("genres") ?: JSONArray()
            (0 until genresArr.length()).mapNotNull { genresArr.optJSONObject(it)?.optString("name") }
        }
        return MovieTitleSummary(
            id = typedId,
            providerId = providerId,
            kind = kind,
            title = title,
            releaseYear = year,
            rating = rating,
            posterUrl = posterPath?.let { "$imageBaseUrl/w500$it" },
            backdropUrl = backdropPath?.let { "$imageBaseUrl/w780$it" },
            genres = genres,
            summary = obj.optString("overview").takeIf { it.isNotBlank() && it != "null" },
            language = obj.optString("original_language").takeIf { it.isNotBlank() },
            sourceBadge = sourceBadge,
            isPlayableHint = false
        )
    }

    private fun parseCompositeId(titleId: String): Pair<MediaKind, String> = when {
        titleId.startsWith("tv_") -> MediaKind.TV_SERIES to titleId.removePrefix("tv_")
        titleId.startsWith("mv_") -> MediaKind.MOVIE to titleId.removePrefix("mv_")
        else -> MediaKind.MOVIE to titleId
    }

    private fun executeGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("TMDB returned HTTP ${response.code}")
            }
            return response.body?.string() ?: throw IOException("Empty response from TMDB")
        }
    }

    private val normalizedBase: String get() = baseUrl.trimEnd('/')

    companion object {
        const val PROVIDER_ID = "tmdb"

        private val GENRES_BY_ID = mapOf(
            28 to "Action",
            12 to "Adventure",
            16 to "Animation",
            35 to "Comedy",
            80 to "Crime",
            99 to "Documentary",
            18 to "Drama",
            10751 to "Family",
            14 to "Fantasy",
            27 to "Horror",
            9648 to "Mystery",
            10749 to "Romance",
            878 to "Science-Fiction",
            53 to "Thriller",
            10752 to "War",
            37 to "Western"
        )
    }
}
