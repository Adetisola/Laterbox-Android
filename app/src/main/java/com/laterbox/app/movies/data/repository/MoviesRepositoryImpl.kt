package com.laterbox.app.movies.data.repository

import com.laterbox.app.movies.data.fixtures.DevelopmentMoviesFixtureProvider
import com.laterbox.app.movies.data.local.MoviesCacheJsonCodec
import com.laterbox.app.movies.data.local.dao.MoviesDao
import com.laterbox.app.movies.data.local.entity.MovieCacheEntryEntity
import com.laterbox.app.movies.data.local.entity.MovieSearchHistoryEntity
import com.laterbox.app.movies.data.local.entity.MovieWatchHistoryEntity
import com.laterbox.app.movies.data.local.entity.MovieWatchlistEntity
import com.laterbox.app.movies.data.provider.InternetArchiveMoviesProvider
import com.laterbox.app.movies.data.provider.TmdbOptionalMoviesProvider
import com.laterbox.app.movies.data.provider.TvMazeSeriesProvider
import com.laterbox.app.movies.domain.model.BrowseSection
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
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MoviesRepositoryImpl(
    private val moviesDao: MoviesDao,
    private val internetArchiveProvider: InternetArchiveMoviesProvider,
    private val tvMazeProvider: TvMazeSeriesProvider,
    private val tmdbProvider: TmdbOptionalMoviesProvider,
    private val fixtureProvider: DevelopmentMoviesFixtureProvider = DevelopmentMoviesFixtureProvider(),
    private val useSampleFixturesOnlyProvider: suspend () -> Boolean = { false },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : MoviesRepository {

    override suspend fun getHomeFeed(forceRefresh: Boolean): Result<MoviesHomeFeed> {
        if (useSampleFixturesOnlyProvider()) {
            return fixtureProvider.getHomeFeed()
        }

        val currentMs = nowMs()
        val cacheKey = buildHomeCacheKey()
        val cachedEntry = moviesDao.getCacheEntry(cacheKey)
        if (!forceRefresh && cachedEntry != null && cachedEntry.expiresAt > currentMs) {
            MoviesCacheJsonCodec.decodeHomeFeed(cachedEntry.payloadJson, isOfflineCached = false)
                ?.let { return Result.success(it) }
        }

        return coroutineScope {
            val tmdbActive = tmdbProvider.isConfigured()
            val iaDeferred = async { internetArchiveProvider.getHomeFeed() }
            val tvMazeDeferred = async { tvMazeProvider.getHomeFeed() }
            val tmdbDeferred = if (tmdbActive) async { tmdbProvider.getHomeFeed() } else null

            val iaResult = iaDeferred.await()
            val tvMazeResult = tvMazeDeferred.await()
            val tmdbResult = tmdbDeferred?.await()

            val iaFeed = iaResult.getOrNull()
            val tvFeed = tvMazeResult.getOrNull()
            val tmdbFeed = tmdbResult?.getOrNull()

            if (iaFeed == null && tvFeed == null && tmdbFeed == null) {
                if (cachedEntry != null) {
                    MoviesCacheJsonCodec.decodeHomeFeed(cachedEntry.payloadJson, isOfflineCached = true)
                        ?.let { cachedFeed ->
                            return@coroutineScope Result.success(
                                cachedFeed.copy(
                                    activeProviderNotice = "Offline cache: showing previously loaded discovery feed"
                                )
                            )
                        }
                }
                val firstError = iaResult.exceptionOrNull()
                    ?: tvMazeResult.exceptionOrNull()
                    ?: tmdbResult?.exceptionOrNull()
                    ?: IllegalStateException("Unable to load Movies & TV catalogue")
                return@coroutineScope Result.failure(firstError)
            }

            val mergedBanners = interleaveLists(
                listOfNotNull(
                    iaFeed?.featuredBanners,
                    tvFeed?.featuredBanners,
                    tmdbFeed?.featuredBanners
                )
            ).distinctBy { it.compositeId }.take(6)

            val mergedSections = buildList {
                iaFeed?.sections?.let { addAll(it) }
                tvFeed?.sections?.let { addAll(it) }
                tmdbFeed?.sections?.let { addAll(it) }
            }

            val mergedGenres = buildList {
                add("All")
                val rawGenres = buildList {
                    iaFeed?.genres?.let { addAll(it) }
                    tvFeed?.genres?.let { addAll(it) }
                    tmdbFeed?.genres?.let { addAll(it) }
                }.filterNot { it.equals("All", ignoreCase = true) }
                addAll(rawGenres.distinct())
            }

            val activeSources = buildList {
                if (iaFeed != null) add("Internet Archive Cinema")
                if (tvFeed != null) add("TVMaze Series")
                if (tmdbFeed != null) add("TMDB")
            }

            val mergedFeed = MoviesHomeFeed(
                featuredBanners = mergedBanners,
                sections = mergedSections,
                genres = mergedGenres,
                activeProviderNotice = "Live sources: ${activeSources.joinToString(" · ")}",
                isOfflineCached = false,
                containsSampleFixtures = false
            )

            moviesDao.upsertCacheEntry(
                MovieCacheEntryEntity(
                    cacheKey = cacheKey,
                    providerId = "composite",
                    payloadJson = MoviesCacheJsonCodec.encodeHomeFeed(mergedFeed),
                    updatedAt = currentMs,
                    expiresAt = currentMs + CACHE_TTL_MS
                )
            )
            moviesDao.pruneExpiredCache(currentMs - STALE_RETENTION_MS)

            Result.success(mergedFeed)
        }
    }

    override suspend fun getCatalogue(
        kind: MediaKind?,
        page: Int,
        pageSize: Int,
        genre: String?,
        sort: CatalogueSort
    ): Result<CataloguePage> {
        if (useSampleFixturesOnlyProvider()) {
            return when (kind) {
                MediaKind.MOVIE, MediaKind.TV_SERIES ->
                    fixtureProvider.getCatalogue(kind, page, pageSize, genre, sort)
                null -> coroutineScope {
                    val m = async { fixtureProvider.getCatalogue(MediaKind.MOVIE, page, pageSize, genre, sort) }.await()
                    val t = async { fixtureProvider.getCatalogue(MediaKind.TV_SERIES, page, pageSize, genre, sort) }.await()
                    val combined = interleaveLists(
                        listOf(m.getOrNull()?.items.orEmpty(), t.getOrNull()?.items.orEmpty())
                    ).distinctBy { it.compositeId }
                    Result.success(
                        CataloguePage(
                            items = combined,
                            page = page,
                            pageSize = pageSize,
                            totalCount = combined.size,
                            hasMore = false
                        )
                    )
                }
            }
        }

        val effectiveGenre = genre?.takeUnless { it.equals("All", ignoreCase = true) }
        return coroutineScope {
            val tmdbActive = tmdbProvider.isConfigured()
            val queryMovies = kind == null || kind == MediaKind.MOVIE
            val querySeries = kind == null || kind == MediaKind.TV_SERIES
            val perProviderSize = if (kind == null) (pageSize / 2).coerceAtLeast(8) else pageSize

            val iaDeferred = if (queryMovies) {
                async { internetArchiveProvider.getCatalogue(MediaKind.MOVIE, page, perProviderSize, effectiveGenre, sort) }
            } else null
            val tvDeferred = if (querySeries) {
                async { tvMazeProvider.getCatalogue(MediaKind.TV_SERIES, page, perProviderSize, effectiveGenre, sort) }
            } else null
            val tmdbMovieDeferred = if (queryMovies && tmdbActive) {
                async { tmdbProvider.getCatalogue(MediaKind.MOVIE, page, perProviderSize, effectiveGenre, sort) }
            } else null
            val tmdbTvDeferred = if (querySeries && tmdbActive) {
                async { tmdbProvider.getCatalogue(MediaKind.TV_SERIES, page, perProviderSize, effectiveGenre, sort) }
            } else null

            val results = listOfNotNull(
                iaDeferred?.await(),
                tvDeferred?.await(),
                tmdbMovieDeferred?.await(),
                tmdbTvDeferred?.await()
            )
            val pages = results.mapNotNull { it.getOrNull() }
            if (pages.isEmpty()) {
                val err = results.firstNotNullOfOrNull { it.exceptionOrNull() }
                    ?: IllegalStateException("Catalogue request failed")
                return@coroutineScope Result.failure(err)
            }

            val items = interleaveLists(pages.map { it.items }).distinctBy { it.compositeId }
            val hasMore = pages.any { it.hasMore }
            val total = pages.sumOf { it.totalCount ?: it.items.size }
            Result.success(
                CataloguePage(
                    items = items,
                    page = page,
                    pageSize = pageSize,
                    totalCount = total,
                    hasMore = hasMore
                )
            )
        }
    }

    override suspend fun getGenres(): Result<List<String>> {
        if (useSampleFixturesOnlyProvider()) {
            return fixtureProvider.getGenres()
        }
        val iaGenres = internetArchiveProvider.getGenres().getOrDefault(emptyList())
        val tvGenres = tvMazeProvider.getGenres().getOrDefault(emptyList())
        val tmdbGenres = if (tmdbProvider.isConfigured()) tmdbProvider.getGenres().getOrDefault(emptyList()) else emptyList()
        val combined = buildList {
            add("All")
            addAll((iaGenres + tvGenres + tmdbGenres).filterNot { it.equals("All", ignoreCase = true) }.distinct())
        }
        return Result.success(combined)
    }

    override suspend fun search(
        query: String,
        kind: MediaKind?,
        page: Int,
        pageSize: Int
    ): Result<CataloguePage> {
        val clean = query.trim()
        if (clean.isBlank()) {
            return Result.success(CataloguePage(emptyList(), page, pageSize, 0, false))
        }
        if (useSampleFixturesOnlyProvider()) {
            return fixtureProvider.search(clean, kind, page, pageSize)
        }
        return coroutineScope {
            val tmdbActive = tmdbProvider.isConfigured()
            val queryMovies = kind == null || kind == MediaKind.MOVIE
            val querySeries = kind == null || kind == MediaKind.TV_SERIES
            val perProviderSize = if (kind == null) (pageSize / 2).coerceAtLeast(8) else pageSize

            val iaDeferred = if (queryMovies) {
                async { internetArchiveProvider.search(clean, MediaKind.MOVIE, page, perProviderSize) }
            } else null
            val tvDeferred = if (querySeries) {
                async { tvMazeProvider.search(clean, MediaKind.TV_SERIES, page, perProviderSize) }
            } else null
            val tmdbDeferred = if (tmdbActive) {
                async { tmdbProvider.search(clean, kind, page, perProviderSize) }
            } else null

            val results = listOfNotNull(
                iaDeferred?.await(),
                tvDeferred?.await(),
                tmdbDeferred?.await()
            )
            val pages = results.mapNotNull { it.getOrNull() }
            if (pages.isEmpty()) {
                val err = results.firstNotNullOfOrNull { it.exceptionOrNull() }
                    ?: IllegalStateException("Search failed")
                return@coroutineScope Result.failure(err)
            }

            val items = interleaveLists(pages.map { it.items }).distinctBy { it.compositeId }
            Result.success(
                CataloguePage(
                    items = items,
                    page = page,
                    pageSize = pageSize,
                    totalCount = pages.sumOf { it.totalCount ?: it.items.size },
                    hasMore = pages.any { it.hasMore }
                )
            )
        }
    }

    override suspend fun getSuggestions(query: String, limit: Int): Result<List<SearchSuggestion>> {
        val clean = query.trim()
        if (clean.length < 2) return Result.success(emptyList())
        if (useSampleFixturesOnlyProvider()) {
            return fixtureProvider.getSuggestions(clean, limit)
        }
        return coroutineScope {
            val iaDeferred = async { internetArchiveProvider.getSuggestions(clean, limit) }
            val tvDeferred = async { tvMazeProvider.getSuggestions(clean, limit) }
            val tmdbDeferred = if (tmdbProvider.isConfigured()) {
                async { tmdbProvider.getSuggestions(clean, limit) }
            } else null

            val results = listOfNotNull(iaDeferred.await(), tvDeferred.await(), tmdbDeferred?.await())
            val lists = results.mapNotNull { it.getOrNull() }
            if (lists.isEmpty()) {
                return@coroutineScope Result.failure(
                    results.firstNotNullOfOrNull { it.exceptionOrNull() } ?: IllegalStateException("Suggestions failed")
                )
            }
            val merged = interleaveLists(lists).distinctBy { "${it.providerId}:${it.id}" }.take(limit)
            Result.success(merged)
        }
    }

    override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean): Result<MediaDetails> {
        if (providerId == DevelopmentMoviesFixtureProvider.PROVIDER_ID) {
            return fixtureProvider.getDetails(titleId)
        }

        val currentMs = nowMs()
        val cacheKey = "details:$providerId:$titleId"
        val cachedEntry = moviesDao.getCacheEntry(cacheKey)
        if (!forceRefresh && cachedEntry != null && cachedEntry.expiresAt > currentMs) {
            MoviesCacheJsonCodec.decodeDetails(cachedEntry.payloadJson)?.let { return Result.success(it) }
        }

        val result = when (providerId) {
            InternetArchiveMoviesProvider.PROVIDER_ID -> internetArchiveProvider.getDetails(titleId)
            TvMazeSeriesProvider.PROVIDER_ID -> tvMazeProvider.getDetails(titleId)
            TmdbOptionalMoviesProvider.PROVIDER_ID -> tmdbProvider.getDetails(titleId)
            else -> Result.failure(IllegalArgumentException("Unknown provider '$providerId'"))
        }

        return result.fold(
            onSuccess = { details ->
                moviesDao.upsertCacheEntry(
                    MovieCacheEntryEntity(
                        cacheKey = cacheKey,
                        providerId = providerId,
                        payloadJson = MoviesCacheJsonCodec.encodeDetails(details),
                        updatedAt = currentMs,
                        expiresAt = currentMs + CACHE_TTL_MS
                    )
                )
                Result.success(details)
            },
            onFailure = { error ->
                cachedEntry?.let { MoviesCacheJsonCodec.decodeDetails(it.payloadJson) }
                    ?.let { Result.success(it) }
                    ?: Result.failure(error)
            }
        )
    }

    override suspend fun getSeasons(providerId: String, seriesId: String): Result<List<SeasonSummary>> =
        when (providerId) {
            TvMazeSeriesProvider.PROVIDER_ID -> tvMazeProvider.getSeasons(seriesId)
            TmdbOptionalMoviesProvider.PROVIDER_ID -> tmdbProvider.getSeasons(seriesId)
            DevelopmentMoviesFixtureProvider.PROVIDER_ID -> fixtureProvider.getSeasons(seriesId)
            else -> Result.success(emptyList())
        }

    override suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int): Result<List<EpisodeItem>> {
        if (providerId == DevelopmentMoviesFixtureProvider.PROVIDER_ID) {
            return fixtureProvider.getEpisodes(seriesId, seasonNumber)
        }
        val currentMs = nowMs()
        val cacheKey = "episodes:$providerId:$seriesId:$seasonNumber"
        val cachedEntry = moviesDao.getCacheEntry(cacheKey)
        if (cachedEntry != null && cachedEntry.expiresAt > currentMs) {
            MoviesCacheJsonCodec.decodeEpisodes(cachedEntry.payloadJson)?.let { return Result.success(it) }
        }

        val result = when (providerId) {
            TvMazeSeriesProvider.PROVIDER_ID -> tvMazeProvider.getEpisodes(seriesId, seasonNumber)
            TmdbOptionalMoviesProvider.PROVIDER_ID -> tmdbProvider.getEpisodes(seriesId, seasonNumber)
            else -> Result.success(emptyList())
        }

        return result.fold(
            onSuccess = { episodes ->
                moviesDao.upsertCacheEntry(
                    MovieCacheEntryEntity(
                        cacheKey = cacheKey,
                        providerId = providerId,
                        payloadJson = MoviesCacheJsonCodec.encodeEpisodes(episodes),
                        updatedAt = currentMs,
                        expiresAt = currentMs + CACHE_TTL_MS
                    )
                )
                Result.success(episodes)
            },
            onFailure = { error ->
                cachedEntry?.let { MoviesCacheJsonCodec.decodeEpisodes(it.payloadJson) }
                    ?.let { Result.success(it) }
                    ?: Result.failure(error)
            }
        )
    }

    override suspend fun resolvePlayback(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Result<PlaybackResolution> {
        val result = when (providerId) {
            InternetArchiveMoviesProvider.PROVIDER_ID ->
                internetArchiveProvider.resolvePlaybackSources(titleId, seasonNumber, episodeNumber)
            TvMazeSeriesProvider.PROVIDER_ID ->
                tvMazeProvider.resolvePlaybackSources(titleId, seasonNumber, episodeNumber)
            TmdbOptionalMoviesProvider.PROVIDER_ID ->
                tmdbProvider.resolvePlaybackSources(titleId, seasonNumber, episodeNumber)
            DevelopmentMoviesFixtureProvider.PROVIDER_ID ->
                fixtureProvider.resolvePlaybackSources(titleId, seasonNumber, episodeNumber)
            else -> Result.failure(IllegalArgumentException("Unknown provider '$providerId'"))
        }

        return result.map { resolution ->
            if (resolution is PlaybackResolution.Available) {
                val savedPos = getSavedPositionMs(providerId, titleId, seasonNumber, episodeNumber)
                resolution.copy(startPositionMs = savedPos)
            } else {
                resolution
            }
        }
    }

    override suspend fun getSubtitles(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Result<List<SubtitleTrack>> = when (providerId) {
        InternetArchiveMoviesProvider.PROVIDER_ID ->
            internetArchiveProvider.getSubtitles(titleId, seasonNumber, episodeNumber)
        DevelopmentMoviesFixtureProvider.PROVIDER_ID ->
            fixtureProvider.getSubtitles(titleId, seasonNumber, episodeNumber)
        else -> Result.success(emptyList())
    }

    override fun observeWatchlist(): Flow<List<MovieTitleSummary>> =
        moviesDao.observeWatchlist().map { entities -> entities.map { it.toDomain() } }

    override fun observeIsInWatchlist(compositeId: String): Flow<Boolean> =
        moviesDao.observeIsInWatchlist(compositeId)

    override suspend fun toggleWatchlist(item: MovieTitleSummary): Boolean {
        val existing = moviesDao.getWatchlistItem(item.compositeId)
        return if (existing != null) {
            moviesDao.deleteWatchlistItem(item.compositeId)
            false
        } else {
            moviesDao.insertWatchlistItem(item.toWatchlistEntity(nowMs()))
            true
        }
    }

    override fun observeContinueWatching(limit: Int): Flow<List<WatchHistoryItem>> =
        moviesDao.observeContinueWatching(limit).map { entities -> entities.map { it.toDomain() } }

    override suspend fun saveWatchProgress(
        item: MovieTitleSummary,
        positionMs: Long,
        durationMs: Long,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeLabel: String?
    ) {
        val key = buildHistoryKey(item.providerId, item.id, seasonNumber, episodeNumber)
        moviesDao.upsertWatchHistory(
            MovieWatchHistoryEntity(
                historyKey = key,
                compositeId = item.compositeId,
                providerId = item.providerId,
                titleId = item.id,
                kind = item.kind.wireValue,
                title = item.title,
                episodeLabel = episodeLabel,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                posterUrl = item.posterUrl,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                lastWatchedAt = nowMs()
            )
        )
    }

    override suspend fun getSavedPositionMs(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Long {
        val key = buildHistoryKey(providerId, titleId, seasonNumber, episodeNumber)
        val entry = moviesDao.getWatchHistoryByKey(key) ?: return 0L
        // Start from beginning if user reached >= 96% completion
        if (entry.durationMs > 0L && entry.positionMs.toFloat() / entry.durationMs.toFloat() >= 0.96f) {
            return 0L
        }
        return entry.positionMs
    }

    override fun observeRecentSearches(limit: Int): Flow<List<String>> =
        moviesDao.observeRecentSearches(limit)

    override suspend fun recordSearchQuery(query: String) {
        val clean = query.trim()
        if (clean.length < 2) return
        moviesDao.upsertSearchQuery(
            MovieSearchHistoryEntity(query = clean, searchedAt = nowMs())
        )
    }

    override suspend fun clearSearchHistory() {
        moviesDao.clearSearchHistory()
    }

    private suspend fun buildHomeCacheKey(): String {
        val tmdbFlag = if (tmdbProvider.isConfigured()) "tmdb1" else "tmdb0"
        return "home_feed:$tmdbFlag"
    }

    private fun buildHistoryKey(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): String = "$providerId:$titleId:${seasonNumber ?: 0}:${episodeNumber ?: 0}"

    private fun <T> interleaveLists(lists: List<List<T>>): List<T> {
        if (lists.isEmpty()) return emptyList()
        val maxLen = lists.maxOfOrNull { it.size } ?: 0
        return buildList {
            for (i in 0 until maxLen) {
                for (list in lists) {
                    if (i < list.size) add(list[i])
                }
            }
        }
    }

    private fun MovieWatchlistEntity.toDomain(): MovieTitleSummary = MovieTitleSummary(
        id = titleId,
        providerId = providerId,
        kind = MediaKind.fromWire(kind),
        title = title,
        releaseYear = releaseYear,
        rating = rating,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        genres = genresCsv.split(",").map { it.trim() }.filter { it.isNotBlank() },
        summary = summary,
        sourceBadge = DataSourceBadge.fromName(sourceBadge),
        isPlayableHint = isPlayableHint
    )

    private fun MovieTitleSummary.toWatchlistEntity(addedAt: Long): MovieWatchlistEntity = MovieWatchlistEntity(
        compositeId = compositeId,
        providerId = providerId,
        titleId = id,
        kind = kind.wireValue,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        releaseYear = releaseYear,
        rating = rating,
        genresCsv = genres.joinToString(","),
        summary = summary,
        sourceBadge = sourceBadge.name,
        isPlayableHint = isPlayableHint,
        addedAt = addedAt
    )

    private fun MovieWatchHistoryEntity.toDomain(): WatchHistoryItem = WatchHistoryItem(
        compositeId = compositeId,
        providerId = providerId,
        titleId = titleId,
        kind = MediaKind.fromWire(kind),
        title = title,
        episodeLabel = episodeLabel,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        posterUrl = posterUrl,
        positionMs = positionMs,
        durationMs = durationMs,
        lastWatchedAt = lastWatchedAt
    )

    companion object {
        const val CACHE_TTL_MS = 6L * 60 * 60 * 1000L
        const val STALE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000L
    }
}
