package com.laterbox.app.movies.data.repository

import com.laterbox.app.BuildConfig
import com.laterbox.app.movies.data.fixtures.DevelopmentMoviesFixtureProvider
import com.laterbox.app.movies.data.local.MoviesCacheJsonCodec
import com.laterbox.app.movies.data.local.dao.MoviesDao
import com.laterbox.app.movies.data.local.entity.MovieCacheEntryEntity
import com.laterbox.app.movies.data.local.entity.MovieSearchHistoryEntity
import com.laterbox.app.movies.data.local.entity.MovieWatchHistoryEntity
import com.laterbox.app.movies.data.local.entity.MovieWatchlistEntity
import com.laterbox.app.movies.data.provider.InternetArchiveMoviesProvider
import com.laterbox.app.movies.data.provider.MovieBoxProvider
import com.laterbox.app.movies.data.provider.TmdbOptionalMoviesProvider
import com.laterbox.app.movies.data.provider.TvMazeSeriesProvider
import com.laterbox.app.movies.data.resolver.MediaResolver
import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.ProviderLinkRecord
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
    @Suppress("UNUSED_PARAMETER")
    private val tmdbProvider: TmdbOptionalMoviesProvider,
    private val fixtureProvider: DevelopmentMoviesFixtureProvider = DevelopmentMoviesFixtureProvider(),
    private val movieBoxProvider: MovieBoxProvider? = null,
    private val useSampleFixturesOnlyProvider: suspend () -> Boolean = { false },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val mediaResolver: MediaResolver = MediaResolver(moviesDao, nowMs)
) : MoviesRepository {

    private suspend fun isDebugSampleFixturesEnabled(): Boolean =
        BuildConfig.DEBUG && useSampleFixturesOnlyProvider()

    override suspend fun getHomeFeed(forceRefresh: Boolean): Result<MoviesHomeFeed> {
        if (isDebugSampleFixturesEnabled()) {
            return fixtureProvider.getHomeFeed()
        }

        mediaResolver.backfillWatchlistAndHistoryIfNeeded()

        val currentMs = nowMs()
        val mb = movieBoxProvider
        val mbCacheKey = mb?.let { buildMovieBoxHomeCacheKey(it.activeBaseUrl()) }
        val mbCachedEntry = mbCacheKey?.let { moviesDao.getCacheEntry(it) }

        if (mb != null && mbCacheKey != null) {
            if (!forceRefresh && mbCachedEntry != null && mbCachedEntry.expiresAt > currentMs) {
                MoviesCacheJsonCodec.decodeHomeFeed(mbCachedEntry.payloadJson, isOfflineCached = false)
                    ?.let { return Result.success(registerFeedCanonical(it)) }
            }

            val mbResult = mb.getHomeFeed()
            if (mbResult.isSuccess) {
                val enrichedFeed = registerFeedCanonical(mbResult.getOrThrow())
                moviesDao.upsertCacheEntry(
                    MovieCacheEntryEntity(
                        cacheKey = mbCacheKey,
                        providerId = MovieBoxProvider.PROVIDER_ID,
                        payloadJson = MoviesCacheJsonCodec.encodeHomeFeed(enrichedFeed),
                        updatedAt = currentMs,
                        expiresAt = currentMs + CACHE_TTL_MS
                    )
                )
                moviesDao.pruneExpiredCache(currentMs - STALE_RETENTION_MS)
                return Result.success(enrichedFeed)
            }

            // MovieBox failed live: attempt live Internet Archive + TVMaze fallback before stale cache.
            val fallbackFeedResult = getOpenArchiveHomeFeed(
                forceRefresh = true,
                fallbackNotice = MOVIEBOX_FALLBACK_NOTICE
            )
            if (fallbackFeedResult.isSuccess) {
                return fallbackFeedResult
            }

            // All live providers failed: fall back to stale cache (prefer MovieBox cache first).
            if (mbCachedEntry != null) {
                MoviesCacheJsonCodec.decodeHomeFeed(mbCachedEntry.payloadJson, isOfflineCached = true)
                    ?.let { cachedFeed ->
                        return Result.success(
                            cachedFeed.copy(
                                activeProviderNotice = "Offline cache: showing previously loaded MovieBox feed"
                            )
                        )
                    }
            }
            val openCacheKey = buildHomeCacheKey()
            moviesDao.getCacheEntry(openCacheKey)?.let { openCached ->
                MoviesCacheJsonCodec.decodeHomeFeed(openCached.payloadJson, isOfflineCached = true)
                    ?.let { cachedFeed ->
                        return Result.success(
                            cachedFeed.copy(
                                activeProviderNotice = "Offline cache: showing previously loaded discovery feed"
                            )
                        )
                    }
            }
            return Result.failure(
                mbResult.exceptionOrNull()
                    ?: fallbackFeedResult.exceptionOrNull()
                    ?: IllegalStateException("Unable to load Movies & TV catalogue")
            )
        }

        return getOpenArchiveHomeFeed(forceRefresh = forceRefresh, fallbackNotice = null)
    }

    private suspend fun getOpenArchiveHomeFeed(
        forceRefresh: Boolean,
        fallbackNotice: String?
    ): Result<MoviesHomeFeed> {
        val currentMs = nowMs()
        val cacheKey = buildHomeCacheKey()
        val cachedEntry = moviesDao.getCacheEntry(cacheKey)
        if (!forceRefresh && cachedEntry != null && cachedEntry.expiresAt > currentMs) {
            MoviesCacheJsonCodec.decodeHomeFeed(cachedEntry.payloadJson, isOfflineCached = false)
                ?.let { feed ->
                    return Result.success(
                        registerFeedCanonical(
                            if (fallbackNotice != null && feed.activeProviderNotice == null) {
                                feed.copy(activeProviderNotice = fallbackNotice)
                            } else {
                                feed
                            }
                        )
                    )
                }
        }

        return coroutineScope {
            val iaDeferred = async { internetArchiveProvider.getHomeFeed() }
            val tvMazeDeferred = async { tvMazeProvider.getHomeFeed() }

            val iaResult = iaDeferred.await()
            val tvMazeResult = tvMazeDeferred.await()

            val iaFeed = iaResult.getOrNull()
            val tvFeed = tvMazeResult.getOrNull()

            if (iaFeed == null && tvFeed == null) {
                if (!forceRefresh || fallbackNotice == null) {
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
                }
                val firstError = iaResult.exceptionOrNull()
                    ?: tvMazeResult.exceptionOrNull()
                    ?: IllegalStateException("Unable to load Movies & TV catalogue")
                return@coroutineScope Result.failure(firstError)
            }

            val mergedBanners = interleaveLists(
                listOfNotNull(
                    iaFeed?.featuredBanners,
                    tvFeed?.featuredBanners
                )
            ).distinctBy { it.compositeId }.take(6)

            val mergedSections = buildList {
                iaFeed?.sections?.let { addAll(it) }
                tvFeed?.sections?.let { addAll(it) }
            }

            val mergedGenres = buildList {
                add("All")
                val rawGenres = buildList {
                    iaFeed?.genres?.let { addAll(it) }
                    tvFeed?.genres?.let { addAll(it) }
                }.filterNot { it.equals("All", ignoreCase = true) }
                addAll(rawGenres.distinct())
            }

            val mergedFeed = registerFeedCanonical(
                MoviesHomeFeed(
                    featuredBanners = mergedBanners,
                    sections = mergedSections,
                    genres = mergedGenres,
                    activeProviderNotice = fallbackNotice,
                    isOfflineCached = false,
                    containsSampleFixtures = false
                )
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
        sort: CatalogueSort,
        country: String?,
        year: String?
    ): Result<CataloguePage> {
        if (isDebugSampleFixturesEnabled()) {
            return when (kind) {
                MediaKind.MOVIE, MediaKind.TV_SERIES ->
                    fixtureProvider.getCatalogue(kind, page, pageSize, genre, sort, country, year)
                null -> coroutineScope {
                    val m = async { fixtureProvider.getCatalogue(MediaKind.MOVIE, page, pageSize, genre, sort, country, year) }.await()
                    val t = async { fixtureProvider.getCatalogue(MediaKind.TV_SERIES, page, pageSize, genre, sort, country, year) }.await()
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
        val effectiveCountry = country?.takeUnless { it.equals("All", ignoreCase = true) }
        val effectiveYear = year?.takeUnless { it.equals("All", ignoreCase = true) }

        val mb = movieBoxProvider
        if (mb != null) {
            val mbResult = when (kind) {
                MediaKind.MOVIE, MediaKind.TV_SERIES ->
                    mb.getCatalogue(kind, page, pageSize, effectiveGenre, sort, effectiveCountry, effectiveYear)
                null -> coroutineScope {
                    val halfSize = (pageSize / 2).coerceAtLeast(10)
                    val moviesDef = async {
                        mb.getCatalogue(MediaKind.MOVIE, page, halfSize, effectiveGenre, sort, effectiveCountry, effectiveYear)
                    }
                    val seriesDef = async {
                        mb.getCatalogue(MediaKind.TV_SERIES, page, halfSize, effectiveGenre, sort, effectiveCountry, effectiveYear)
                    }
                    val moviesRes = moviesDef.await()
                    val seriesRes = seriesDef.await()
                    val pages = listOfNotNull(moviesRes.getOrNull(), seriesRes.getOrNull())
                    if (pages.isEmpty()) {
                        val err = moviesRes.exceptionOrNull()
                            ?: seriesRes.exceptionOrNull()
                            ?: IllegalStateException("MovieBox catalogue request failed.")
                        Result.failure(err)
                    } else {
                        val combined = interleaveLists(pages.map { it.items }).distinctBy { it.compositeId }
                        Result.success(
                            CataloguePage(
                                items = combined,
                                page = page,
                                pageSize = pageSize,
                                totalCount = pages.sumOf { it.totalCount ?: it.items.size },
                                hasMore = pages.any { it.hasMore }
                            )
                        )
                    }
                }
            }
            if (mbResult.isSuccess) {
                val pageData = mbResult.getOrThrow()
                return Result.success(pageData.copy(items = registerSummariesCanonical(pageData.items)))
            }
        }

        // Secondary fallback to Internet Archive + TVMaze
        return coroutineScope {
            val queryMovies = kind == null || kind == MediaKind.MOVIE
            val querySeries = kind == null || kind == MediaKind.TV_SERIES
            val perProviderSize = if (kind == null) (pageSize / 2).coerceAtLeast(8) else pageSize

            val iaDeferred = if (queryMovies) {
                async { internetArchiveProvider.getCatalogue(MediaKind.MOVIE, page, perProviderSize, effectiveGenre, sort, effectiveCountry, effectiveYear) }
            } else null
            val tvDeferred = if (querySeries) {
                async { tvMazeProvider.getCatalogue(MediaKind.TV_SERIES, page, perProviderSize, effectiveGenre, sort, effectiveCountry, effectiveYear) }
            } else null

            val results = listOfNotNull(iaDeferred?.await(), tvDeferred?.await())
            val pages = results.mapNotNull { it.getOrNull() }
            if (pages.isEmpty()) {
                val err = results.firstNotNullOfOrNull { it.exceptionOrNull() }
                    ?: IllegalStateException("Catalogue request failed")
                return@coroutineScope Result.failure(err)
            }

            val items = registerSummariesCanonical(
                interleaveLists(pages.map { it.items }).distinctBy { it.compositeId }
            )
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

    override suspend fun getGenres(): Result<List<String>> {
        if (isDebugSampleFixturesEnabled()) {
            return fixtureProvider.getGenres()
        }
        val mbResult = movieBoxProvider?.getGenres()
        if (mbResult != null && mbResult.isSuccess) {
            return mbResult
        }
        val iaGenres = internetArchiveProvider.getGenres().getOrDefault(emptyList())
        val tvGenres = tvMazeProvider.getGenres().getOrDefault(emptyList())
        val combined = buildList {
            add("All")
            addAll((iaGenres + tvGenres).filterNot { it.equals("All", ignoreCase = true) }.distinct())
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
        if (isDebugSampleFixturesEnabled()) {
            return fixtureProvider.search(clean, kind, page, pageSize)
        }

        val mbResult = movieBoxProvider?.search(clean, kind, page, pageSize)
        if (mbResult != null && mbResult.isSuccess) {
            val pageData = mbResult.getOrThrow()
            return Result.success(pageData.copy(items = registerSummariesCanonical(pageData.items)))
        }

        return coroutineScope {
            val queryMovies = kind == null || kind == MediaKind.MOVIE
            val querySeries = kind == null || kind == MediaKind.TV_SERIES
            val perProviderSize = if (kind == null) (pageSize / 2).coerceAtLeast(8) else pageSize

            val iaDeferred = if (queryMovies) {
                async { internetArchiveProvider.search(clean, MediaKind.MOVIE, page, perProviderSize) }
            } else null
            val tvDeferred = if (querySeries) {
                async { tvMazeProvider.search(clean, MediaKind.TV_SERIES, page, perProviderSize) }
            } else null

            val results = listOfNotNull(iaDeferred?.await(), tvDeferred?.await())
            val pages = results.mapNotNull { it.getOrNull() }
            if (pages.isEmpty()) {
                val err = results.firstNotNullOfOrNull { it.exceptionOrNull() }
                    ?: IllegalStateException("Search failed")
                return@coroutineScope Result.failure(err)
            }

            val items = registerSummariesCanonical(
                interleaveLists(pages.map { it.items }).distinctBy { it.compositeId }
            )
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
        if (isDebugSampleFixturesEnabled()) {
            return fixtureProvider.getSuggestions(clean, limit)
        }

        val mbResult = movieBoxProvider?.getSuggestions(clean, limit)
        if (mbResult != null && mbResult.isSuccess) {
            return mbResult
        }

        return coroutineScope {
            val iaDeferred = async { internetArchiveProvider.getSuggestions(clean, limit) }
            val tvDeferred = async { tvMazeProvider.getSuggestions(clean, limit) }

            val results = listOf(iaDeferred.await(), tvDeferred.await())
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
        val (resolvedProviderId, resolvedTitleId) = mediaResolver.resolveConcreteTarget(
            providerId = providerId,
            titleId = titleId,
            preferPlayable = false
        )
        if (resolvedProviderId == DevelopmentMoviesFixtureProvider.PROVIDER_ID) {
            return fixtureProvider.getDetails(resolvedTitleId)
        }

        val currentMs = nowMs()
        val cacheKey = "details:$resolvedProviderId:$resolvedTitleId"
        val cachedEntry = moviesDao.getCacheEntry(cacheKey)
        if (!forceRefresh && cachedEntry != null && cachedEntry.expiresAt > currentMs) {
            MoviesCacheJsonCodec.decodeDetails(cachedEntry.payloadJson)?.let { details ->
                val resolvedSummary = mediaResolver.resolveOrRegister(
                    details.summary,
                    seasonCount = details.seasons.size.takeIf { it > 0 }
                )
                return Result.success(details.copy(summary = resolvedSummary))
            }
        }

        val primaryResult = fetchProviderDetails(resolvedProviderId, resolvedTitleId)
        if (primaryResult.isSuccess) {
            val rawDetails = primaryResult.getOrThrow()
            val resolvedSummary = mediaResolver.resolveOrRegister(
                rawDetails.summary,
                seasonCount = rawDetails.seasons.size.takeIf { it > 0 }
            )
            val resolvedRelated = registerSummariesCanonical(rawDetails.relatedTitles)
            val enriched = rawDetails.copy(
                summary = resolvedSummary,
                relatedTitles = resolvedRelated
            )
            moviesDao.upsertCacheEntry(
                MovieCacheEntryEntity(
                    cacheKey = cacheKey,
                    providerId = resolvedProviderId,
                    payloadJson = MoviesCacheJsonCodec.encodeDetails(enriched),
                    updatedAt = currentMs,
                    expiresAt = currentMs + CACHE_TTL_MS
                )
            )
            return Result.success(enriched)
        }

        // If primary failed, try linked alternate providers for the same canonical identity
        val linkedAlternates = mediaResolver.getLinksForTitle(resolvedProviderId, resolvedTitleId)
            .filterNot { it.providerId == resolvedProviderId && it.providerTitleId == resolvedTitleId }
        for (alt in linkedAlternates) {
            val altRes = fetchProviderDetails(alt.providerId, alt.providerTitleId)
            if (altRes.isSuccess) {
                val altDetails = altRes.getOrThrow()
                val resolvedSummary = mediaResolver.resolveOrRegister(
                    altDetails.summary,
                    seasonCount = altDetails.seasons.size.takeIf { it > 0 }
                )
                return Result.success(altDetails.copy(summary = resolvedSummary))
            }
        }

        val primaryError = primaryResult.exceptionOrNull()
            ?: IllegalStateException("Unable to load title details.")
        return cachedEntry?.let { MoviesCacheJsonCodec.decodeDetails(it.payloadJson) }
            ?.let { Result.success(it) }
            ?: Result.failure(primaryError)
    }

    private suspend fun fetchProviderDetails(providerId: String, titleId: String): Result<MediaDetails> =
        when (providerId) {
            MovieBoxProvider.PROVIDER_ID -> movieBoxProvider?.getDetails(titleId)
                ?: Result.failure(IllegalStateException("MovieBox provider is not configured."))
            InternetArchiveMoviesProvider.PROVIDER_ID -> internetArchiveProvider.getDetails(titleId)
            TvMazeSeriesProvider.PROVIDER_ID -> tvMazeProvider.getDetails(titleId)
            TmdbOptionalMoviesProvider.PROVIDER_ID -> tmdbProvider.getDetails(titleId)
            else -> Result.failure(IllegalArgumentException("Unknown provider '$providerId'"))
        }

    override suspend fun getSeasons(providerId: String, seriesId: String): Result<List<SeasonSummary>> {
        val (resolvedProviderId, resolvedSeriesId) = mediaResolver.resolveConcreteTarget(
            providerId = providerId,
            titleId = seriesId,
            preferPlayable = false
        )
        val primary = when (resolvedProviderId) {
            MovieBoxProvider.PROVIDER_ID -> movieBoxProvider?.getSeasons(resolvedSeriesId)
                ?: Result.failure(IllegalStateException("MovieBox provider is not configured."))
            TvMazeSeriesProvider.PROVIDER_ID -> tvMazeProvider.getSeasons(resolvedSeriesId)
            TmdbOptionalMoviesProvider.PROVIDER_ID -> tmdbProvider.getSeasons(resolvedSeriesId)
            DevelopmentMoviesFixtureProvider.PROVIDER_ID -> fixtureProvider.getSeasons(resolvedSeriesId)
            else -> Result.success(emptyList())
        }
        if (primary.isSuccess) return primary

        val linkedAlternates = mediaResolver.getLinksForTitle(resolvedProviderId, resolvedSeriesId)
            .filterNot { it.providerId == resolvedProviderId }
        for (alt in linkedAlternates) {
            if (alt.providerId == TvMazeSeriesProvider.PROVIDER_ID) {
                val altRes = tvMazeProvider.getSeasons(alt.providerTitleId)
                if (altRes.isSuccess) return altRes
            }
        }
        return primary
    }

    override suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int): Result<List<EpisodeItem>> {
        val (resolvedProviderId, resolvedSeriesId) = mediaResolver.resolveConcreteTarget(
            providerId = providerId,
            titleId = seriesId,
            preferPlayable = false
        )
        if (resolvedProviderId == DevelopmentMoviesFixtureProvider.PROVIDER_ID) {
            return fixtureProvider.getEpisodes(resolvedSeriesId, seasonNumber)
        }
        val currentMs = nowMs()
        val cacheKey = "episodes:$resolvedProviderId:$resolvedSeriesId:$seasonNumber"
        val cachedEntry = moviesDao.getCacheEntry(cacheKey)
        if (cachedEntry != null && cachedEntry.expiresAt > currentMs) {
            MoviesCacheJsonCodec.decodeEpisodes(cachedEntry.payloadJson)?.let { return Result.success(it) }
        }

        val result = when (resolvedProviderId) {
            MovieBoxProvider.PROVIDER_ID -> movieBoxProvider?.getEpisodes(resolvedSeriesId, seasonNumber)
                ?: Result.failure(IllegalStateException("MovieBox provider is not configured."))
            TvMazeSeriesProvider.PROVIDER_ID -> tvMazeProvider.getEpisodes(resolvedSeriesId, seasonNumber)
            TmdbOptionalMoviesProvider.PROVIDER_ID -> tmdbProvider.getEpisodes(resolvedSeriesId, seasonNumber)
            else -> Result.success(emptyList())
        }

        if (result.isSuccess) {
            val episodes = result.getOrThrow()
            moviesDao.upsertCacheEntry(
                MovieCacheEntryEntity(
                    cacheKey = cacheKey,
                    providerId = resolvedProviderId,
                    payloadJson = MoviesCacheJsonCodec.encodeEpisodes(episodes),
                    updatedAt = currentMs,
                    expiresAt = currentMs + CACHE_TTL_MS
                )
            )
            return Result.success(episodes)
        }

        val linkedAlternates = mediaResolver.getLinksForTitle(resolvedProviderId, resolvedSeriesId)
            .filterNot { it.providerId == resolvedProviderId }
        for (alt in linkedAlternates) {
            if (alt.providerId == TvMazeSeriesProvider.PROVIDER_ID) {
                val altRes = tvMazeProvider.getEpisodes(alt.providerTitleId, seasonNumber)
                if (altRes.isSuccess) return altRes
            }
        }

        val error = result.exceptionOrNull() ?: IllegalStateException("Unable to load season episodes.")
        return cachedEntry?.let { MoviesCacheJsonCodec.decodeEpisodes(it.payloadJson) }
            ?.let { Result.success(it) }
            ?: Result.failure(error)
    }

    override suspend fun resolvePlayback(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Result<PlaybackResolution> {
        val (resolvedProviderId, resolvedTitleId) = mediaResolver.resolveConcreteTarget(
            providerId = providerId,
            titleId = titleId,
            preferPlayable = true
        )
        val result = when (resolvedProviderId) {
            MovieBoxProvider.PROVIDER_ID ->
                movieBoxProvider?.resolvePlaybackSources(resolvedTitleId, seasonNumber, episodeNumber)
                    ?: Result.failure(IllegalStateException("MovieBox provider is not configured."))
            InternetArchiveMoviesProvider.PROVIDER_ID ->
                internetArchiveProvider.resolvePlaybackSources(resolvedTitleId, seasonNumber, episodeNumber)
            TvMazeSeriesProvider.PROVIDER_ID ->
                tvMazeProvider.resolvePlaybackSources(resolvedTitleId, seasonNumber, episodeNumber)
            TmdbOptionalMoviesProvider.PROVIDER_ID ->
                tmdbProvider.resolvePlaybackSources(resolvedTitleId, seasonNumber, episodeNumber)
            DevelopmentMoviesFixtureProvider.PROVIDER_ID ->
                fixtureProvider.resolvePlaybackSources(resolvedTitleId, seasonNumber, episodeNumber)
            else -> Result.failure(IllegalArgumentException("Unknown provider '$resolvedProviderId'"))
        }

        return result.map { resolution ->
            if (resolution is PlaybackResolution.Available) {
                val savedPos = getSavedPositionMs(resolvedProviderId, resolvedTitleId, seasonNumber, episodeNumber)
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
    ): Result<List<SubtitleTrack>> {
        val (resolvedProviderId, resolvedTitleId) = mediaResolver.resolveConcreteTarget(
            providerId = providerId,
            titleId = titleId,
            preferPlayable = true
        )
        return when (resolvedProviderId) {
            MovieBoxProvider.PROVIDER_ID ->
                movieBoxProvider?.getSubtitles(resolvedTitleId, seasonNumber, episodeNumber)
                    ?: Result.success(emptyList())
            InternetArchiveMoviesProvider.PROVIDER_ID ->
                internetArchiveProvider.getSubtitles(resolvedTitleId, seasonNumber, episodeNumber)
            DevelopmentMoviesFixtureProvider.PROVIDER_ID ->
                fixtureProvider.getSubtitles(resolvedTitleId, seasonNumber, episodeNumber)
            else -> Result.success(emptyList())
        }
    }

    override fun observeWatchlist(): Flow<List<MovieTitleSummary>> =
        moviesDao.observeWatchlist().map { entities -> entities.map { it.toDomain() } }

    override fun observeIsInWatchlist(compositeId: String): Flow<Boolean> =
        moviesDao.observeIsInWatchlist(compositeId)

    override suspend fun toggleWatchlist(item: MovieTitleSummary): Boolean {
        val resolved = mediaResolver.resolveOrRegister(item)
        val cid = resolved.canonicalId
        val existing = moviesDao.getWatchlistItem(resolved.compositeId)
            ?: cid?.let { moviesDao.getWatchlistItemByCanonical(it) }
        return if (existing != null) {
            moviesDao.deleteWatchlistItem(existing.compositeId)
            cid?.let { moviesDao.deleteWatchlistItemsByCanonical(it) }
            false
        } else {
            moviesDao.insertWatchlistItem(resolved.toWatchlistEntity(nowMs()))
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
        val resolved = if (item.canonicalId != null) item else mediaResolver.resolveOrRegister(item)
        val key = buildHistoryKey(resolved.providerId, resolved.id, seasonNumber, episodeNumber)
        moviesDao.upsertWatchHistory(
            MovieWatchHistoryEntity(
                historyKey = key,
                compositeId = resolved.compositeId,
                providerId = resolved.providerId,
                titleId = resolved.id,
                kind = resolved.kind.wireValue,
                title = resolved.title,
                episodeLabel = episodeLabel,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                posterUrl = resolved.posterUrl,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                lastWatchedAt = nowMs(),
                canonicalId = resolved.canonicalId
            )
        )
    }

    override suspend fun getSavedPositionMs(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Long {
        val (resolvedProviderId, resolvedTitleId) = mediaResolver.resolveConcreteTarget(providerId, titleId, preferPlayable = false)
        val key = buildHistoryKey(resolvedProviderId, resolvedTitleId, seasonNumber, episodeNumber)
        val entry = moviesDao.getWatchHistoryByKey(key) ?: run {
            val cid = mediaResolver.resolveCanonicalIdForProvider(providerId, titleId)
            if (cid != null) {
                moviesDao.getWatchHistoryByCanonical(cid, seasonNumber ?: 0, episodeNumber ?: 0)
            } else null
        } ?: return 0L
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

    override suspend fun getProviderLinks(providerId: String, titleId: String): List<ProviderLinkRecord> =
        mediaResolver.getLinksForTitle(providerId, titleId)

    override suspend fun unlinkProviderTitle(providerId: String, titleId: String): String? =
        mediaResolver.unlinkProviderTitle(providerId, titleId)

    private suspend fun registerSummariesCanonical(items: List<MovieTitleSummary>): List<MovieTitleSummary> =
        items.map { mediaResolver.resolveOrRegister(it) }

    private suspend fun registerFeedCanonical(feed: MoviesHomeFeed): MoviesHomeFeed {
        val registeredBanners = registerSummariesCanonical(feed.featuredBanners)
        val registeredSections = feed.sections.map { section ->
            section.copy(items = registerSummariesCanonical(section.items))
        }
        return feed.copy(
            featuredBanners = registeredBanners,
            sections = registeredSections
        )
    }

    private fun buildHomeCacheKey(): String = "home_feed:composite"

    private fun buildMovieBoxHomeCacheKey(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/').lowercase()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "home_feed:moviebox:$digest"
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
        isPlayableHint = isPlayableHint,
        canonicalId = canonicalId
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
        addedAt = addedAt,
        canonicalId = canonicalId
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
        const val MOVIEBOX_FALLBACK_NOTICE =
            "MovieBox unavailable — showing Internet Archive & TVMaze catalogue"
    }
}
