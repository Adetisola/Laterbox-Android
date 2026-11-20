package com.laterbox.app.movies.data.repository

import com.laterbox.app.BuildConfig
import com.laterbox.app.media.data.db.dao.DownloadedAssetDao
import com.laterbox.app.movies.data.download.MoviesDownloadBridge
import com.laterbox.app.movies.data.fixtures.DevelopmentMoviesFixtureProvider
import com.laterbox.app.movies.data.local.MoviesCacheJsonCodec
import com.laterbox.app.movies.data.local.dao.MoviesDao
import com.laterbox.app.movies.data.local.entity.HiddenTitleEntity
import com.laterbox.app.movies.data.local.entity.MediaCollectionCrossRef
import com.laterbox.app.movies.data.local.entity.MovieCacheEntryEntity
import com.laterbox.app.movies.data.local.entity.MovieRecentlyViewedEntity
import com.laterbox.app.movies.data.local.entity.MovieSearchHistoryEntity
import com.laterbox.app.movies.data.local.entity.MovieSeriesPreferencesEntity
import com.laterbox.app.movies.data.local.entity.MovieViewingReminderEntity
import com.laterbox.app.movies.data.local.entity.MovieWatchHistoryEntity
import com.laterbox.app.movies.data.local.entity.MovieWatchlistEntity
import com.laterbox.app.movies.data.provider.InternetArchiveMoviesProvider
import com.laterbox.app.movies.data.provider.MovieBoxProvider
import com.laterbox.app.movies.data.provider.TmdbOptionalMoviesProvider
import com.laterbox.app.movies.data.provider.TvMazeSeriesProvider
import com.laterbox.app.movies.data.resolver.MediaResolver
import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.CollectionMediaItem
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.EpisodeProgressState
import com.laterbox.app.movies.domain.model.MediaDetails
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.MoviesHomeFeed
import com.laterbox.app.movies.domain.model.OfflineMediaMatch
import com.laterbox.app.movies.domain.model.PersonFilmography
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.ProviderLinkRecord
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SeriesPreferences
import com.laterbox.app.movies.domain.model.SeriesProgressSummary
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.model.ViewingReminderItem
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.movies.domain.repository.MoviesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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
    private val mediaResolver: MediaResolver = MediaResolver(moviesDao, nowMs),
    private val downloadedAssetDao: DownloadedAssetDao? = null,
    private val localMediaUriResolver: ((String) -> String?)? = null,
    private val subtitleDownloadManager: com.laterbox.app.movies.data.download.SubtitleDownloadManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : MoviesRepository {

    @Volatile
    private var hasBackfilledCanonicalState: Boolean = false

    private val registeredCanonicalCache = ConcurrentHashMap<String, String>()
    private val summaryMemoryCache = ConcurrentHashMap<String, MovieTitleSummary>()

    private fun cacheSummaries(items: List<MovieTitleSummary>) {
        for (item in items) {
            if (item.providerId.isNotBlank() && item.id.isNotBlank()) {
                summaryMemoryCache[item.compositeId] = item
                item.canonicalId?.takeIf { it.isNotBlank() }?.let { cid ->
                    summaryMemoryCache["canonical:$cid"] = item
                    registeredCanonicalCache[item.compositeId] = cid
                }
            }
        }
    }

    override fun getCachedSummary(providerId: String, titleId: String): MovieTitleSummary? {
        if (providerId == MediaResolver.CANONICAL_PROVIDER_ID) {
            return summaryMemoryCache["canonical:$titleId"]
        }
        return summaryMemoryCache["$providerId:$titleId"]
    }

    private suspend fun isDebugSampleFixturesEnabled(): Boolean =
        BuildConfig.DEBUG && useSampleFixturesOnlyProvider()

    override suspend fun getHomeFeed(forceRefresh: Boolean): Result<MoviesHomeFeed> = withContext(ioDispatcher) {
        if (isDebugSampleFixturesEnabled()) {
            return@withContext fixtureProvider.getHomeFeed()
        }

        if (!hasBackfilledCanonicalState) {
            mediaResolver.backfillWatchlistAndHistoryIfNeeded()
            hasBackfilledCanonicalState = true
        }

        val currentMs = nowMs()
        val mb = movieBoxProvider
        val mbCacheKey = mb?.let { buildMovieBoxHomeCacheKey(it.activeBaseUrl()) }
        val mbCachedEntry = mbCacheKey?.let { moviesDao.getCacheEntry(it) }

        if (mb != null && mbCacheKey != null) {
            if (!forceRefresh && mbCachedEntry != null && mbCachedEntry.expiresAt > currentMs) {
                val decoded = withContext(defaultDispatcher) {
                    MoviesCacheJsonCodec.decodeHomeFeed(mbCachedEntry.payloadJson, isOfflineCached = false)
                }
                if (decoded != null) {
                    return@withContext Result.success(registerFeedCanonical(decoded))
                }
            }

            val mbResult = mb.getHomeFeed()
            if (mbResult.isSuccess) {
                val enrichedFeed = registerFeedCanonical(mbResult.getOrThrow())
                val encodedJson = withContext(defaultDispatcher) {
                    MoviesCacheJsonCodec.encodeHomeFeed(enrichedFeed)
                }
                moviesDao.upsertCacheEntry(
                    MovieCacheEntryEntity(
                        cacheKey = mbCacheKey,
                        providerId = MovieBoxProvider.PROVIDER_ID,
                        payloadJson = encodedJson,
                        updatedAt = currentMs,
                        expiresAt = currentMs + CACHE_TTL_MS
                    )
                )
                moviesDao.pruneExpiredCache(currentMs - STALE_RETENTION_MS)
                return@withContext Result.success(enrichedFeed)
            }

            // MovieBox failed live: attempt live Internet Archive + TVMaze fallback before stale cache.
            val fallbackFeedResult = getOpenArchiveHomeFeed(
                forceRefresh = true,
                fallbackNotice = MOVIEBOX_FALLBACK_NOTICE
            )
            if (fallbackFeedResult.isSuccess) {
                return@withContext fallbackFeedResult
            }

            // All live providers failed: fall back to stale cache (prefer MovieBox cache first).
            if (mbCachedEntry != null) {
                val decoded = withContext(defaultDispatcher) {
                    MoviesCacheJsonCodec.decodeHomeFeed(mbCachedEntry.payloadJson, isOfflineCached = true)
                }
                if (decoded != null) {
                    cacheSummaries(decoded.featuredBanners)
                    decoded.sections.forEach { cacheSummaries(it.items) }
                    return@withContext Result.success(
                        decoded.copy(
                            activeProviderNotice = "Offline cache: showing previously loaded catalogue"
                        )
                    )
                }
            }
            val openCacheKey = buildHomeCacheKey()
            moviesDao.getCacheEntry(openCacheKey)?.let { openCached ->
                val decoded = withContext(defaultDispatcher) {
                    MoviesCacheJsonCodec.decodeHomeFeed(openCached.payloadJson, isOfflineCached = true)
                }
                if (decoded != null) {
                    cacheSummaries(decoded.featuredBanners)
                    decoded.sections.forEach { cacheSummaries(it.items) }
                    return@withContext Result.success(
                        decoded.copy(
                            activeProviderNotice = "Offline cache: showing previously loaded discovery feed"
                        )
                    )
                }
            }
            return@withContext Result.failure(
                mbResult.exceptionOrNull()
                    ?: fallbackFeedResult.exceptionOrNull()
                    ?: IllegalStateException("Unable to load Movies & TV catalogue")
            )
        }

        getOpenArchiveHomeFeed(forceRefresh = forceRefresh, fallbackNotice = null)
    }

    private suspend fun getOpenArchiveHomeFeed(
        forceRefresh: Boolean,
        fallbackNotice: String?
    ): Result<MoviesHomeFeed> = withContext(ioDispatcher) {
        val currentMs = nowMs()
        val cacheKey = buildHomeCacheKey()
        val cachedEntry = moviesDao.getCacheEntry(cacheKey)
        if (!forceRefresh && cachedEntry != null && cachedEntry.expiresAt > currentMs) {
            val decoded = withContext(defaultDispatcher) {
                MoviesCacheJsonCodec.decodeHomeFeed(cachedEntry.payloadJson, isOfflineCached = false)
            }
            if (decoded != null) {
                return@withContext Result.success(
                    registerFeedCanonical(
                        if (fallbackNotice != null && decoded.activeProviderNotice == null) {
                            decoded.copy(activeProviderNotice = fallbackNotice)
                        } else {
                            decoded
                        }
                    )
                )
            }
        }

        val iaDeferred = async { internetArchiveProvider.getHomeFeed() }
        val tvMazeDeferred = async { tvMazeProvider.getHomeFeed() }

        val iaResult = iaDeferred.await()
        val tvMazeResult = tvMazeDeferred.await()

        val iaFeed = iaResult.getOrNull()
        val tvFeed = tvMazeResult.getOrNull()

        if (iaFeed == null && tvFeed == null) {
            if (!forceRefresh || fallbackNotice == null) {
                if (cachedEntry != null) {
                    val decoded = withContext(defaultDispatcher) {
                        MoviesCacheJsonCodec.decodeHomeFeed(cachedEntry.payloadJson, isOfflineCached = true)
                    }
                    if (decoded != null) {
                        cacheSummaries(decoded.featuredBanners)
                        decoded.sections.forEach { cacheSummaries(it.items) }
                        return@withContext Result.success(
                            decoded.copy(
                                activeProviderNotice = "Offline cache: showing previously loaded discovery feed"
                            )
                        )
                    }
                }
            }
            val firstError = iaResult.exceptionOrNull()
                ?: tvMazeResult.exceptionOrNull()
                ?: IllegalStateException("Unable to load Movies & TV catalogue")
            return@withContext Result.failure(firstError)
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

        val encodedJson = withContext(defaultDispatcher) {
            MoviesCacheJsonCodec.encodeHomeFeed(mergedFeed)
        }
        moviesDao.upsertCacheEntry(
            MovieCacheEntryEntity(
                cacheKey = cacheKey,
                providerId = "composite",
                payloadJson = encodedJson,
                updatedAt = currentMs,
                expiresAt = currentMs + CACHE_TTL_MS
            )
        )
        moviesDao.pruneExpiredCache(currentMs - STALE_RETENTION_MS)

        Result.success(mergedFeed)
    }

    override suspend fun getCatalogue(
        kind: MediaKind?,
        page: Int,
        pageSize: Int,
        genre: String?,
        sort: CatalogueSort,
        country: String?,
        year: String?
    ): Result<CataloguePage> = withContext(ioDispatcher) {
        if (isDebugSampleFixturesEnabled()) {
            return@withContext when (kind) {
                MediaKind.MOVIE, MediaKind.TV_SERIES ->
                    fixtureProvider.getCatalogue(kind, page, pageSize, genre, sort, country, year)
                null -> {
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
                null -> {
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
                            ?: IllegalStateException("Catalogue request failed.")
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
                return@withContext Result.success(pageData.copy(items = registerSummariesCanonical(pageData.items)))
            }
        }

        // Secondary fallback to Internet Archive + TVMaze
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
            return@withContext Result.failure(err)
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

    override suspend fun getGenres(): Result<List<String>> = withContext(ioDispatcher) {
        if (isDebugSampleFixturesEnabled()) {
            return@withContext fixtureProvider.getGenres()
        }
        val mbResult = movieBoxProvider?.getGenres()
        if (mbResult != null && mbResult.isSuccess) {
            return@withContext mbResult
        }
        val iaGenres = internetArchiveProvider.getGenres().getOrDefault(emptyList())
        val tvGenres = tvMazeProvider.getGenres().getOrDefault(emptyList())
        val combined = buildList {
            add("All")
            addAll((iaGenres + tvGenres).filterNot { it.equals("All", ignoreCase = true) }.distinct())
        }
        Result.success(combined)
    }

    override suspend fun search(
        query: String,
        kind: MediaKind?,
        page: Int,
        pageSize: Int
    ): Result<CataloguePage> = withContext(ioDispatcher) {
        val clean = query.trim()
        if (clean.isBlank()) {
            return@withContext Result.success(CataloguePage(emptyList(), page, pageSize, 0, false))
        }
        if (isDebugSampleFixturesEnabled()) {
            return@withContext fixtureProvider.search(clean, kind, page, pageSize)
        }

        val mbResult = movieBoxProvider?.search(clean, kind, page, pageSize)
        if (mbResult != null && mbResult.isSuccess) {
            val pageData = mbResult.getOrThrow()
            return@withContext Result.success(pageData.copy(items = registerSummariesCanonical(pageData.items)))
        }

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
            return@withContext Result.failure(err)
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

    override suspend fun getSuggestions(query: String, limit: Int): Result<List<SearchSuggestion>> = withContext(ioDispatcher) {
        val clean = query.trim()
        if (clean.length < 2) return@withContext Result.success(emptyList())
        if (isDebugSampleFixturesEnabled()) {
            return@withContext fixtureProvider.getSuggestions(clean, limit)
        }

        val mbResult = movieBoxProvider?.getSuggestions(clean, limit)
        if (mbResult != null && mbResult.isSuccess) {
            return@withContext mbResult
        }

        val iaDeferred = async { internetArchiveProvider.getSuggestions(clean, limit) }
        val tvDeferred = async { tvMazeProvider.getSuggestions(clean, limit) }

        val results = listOf(iaDeferred.await(), tvDeferred.await())
        val lists = results.mapNotNull { it.getOrNull() }
        if (lists.isEmpty()) {
            return@withContext Result.failure(
                results.firstNotNullOfOrNull { it.exceptionOrNull() } ?: IllegalStateException("Suggestions failed")
            )
        }
        val merged = interleaveLists(lists).distinctBy { "${it.providerId}:${it.id}" }.take(limit)
        Result.success(merged)
    }

    override suspend fun getDetails(providerId: String, titleId: String, forceRefresh: Boolean): Result<MediaDetails> = withContext(ioDispatcher) {
        val (resolvedProviderId, resolvedTitleId) = mediaResolver.resolveConcreteTarget(
            providerId = providerId,
            titleId = titleId,
            preferPlayable = false
        )
        if (resolvedProviderId == DevelopmentMoviesFixtureProvider.PROVIDER_ID) {
            return@withContext fixtureProvider.getDetails(resolvedTitleId).also { res ->
                res.getOrNull()?.let { d -> cacheSummaries(listOf(d.summary) + d.relatedTitles) }
            }
        }

        val currentMs = nowMs()
        val cacheKey = "details:$resolvedProviderId:$resolvedTitleId"
        val cachedEntry = moviesDao.getCacheEntry(cacheKey)
        if (!forceRefresh && cachedEntry != null && cachedEntry.expiresAt > currentMs) {
            val decoded = withContext(defaultDispatcher) {
                MoviesCacheJsonCodec.decodeDetails(cachedEntry.payloadJson)
            }
            if (decoded != null) {
                val resolvedSummary = mediaResolver.resolveOrRegister(
                    decoded.summary,
                    seasonCount = decoded.seasons.size.takeIf { it > 0 }
                )
                val finalDetails = decoded.copy(summary = resolvedSummary)
                cacheSummaries(listOf(finalDetails.summary) + finalDetails.relatedTitles)
                return@withContext Result.success(finalDetails)
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
            cacheSummaries(listOf(enriched.summary) + enriched.relatedTitles)
            val encoded = withContext(defaultDispatcher) {
                MoviesCacheJsonCodec.encodeDetails(enriched)
            }
            moviesDao.upsertCacheEntry(
                MovieCacheEntryEntity(
                    cacheKey = cacheKey,
                    providerId = resolvedProviderId,
                    payloadJson = encoded,
                    updatedAt = currentMs,
                    expiresAt = currentMs + CACHE_TTL_MS
                )
            )
            return@withContext Result.success(enriched)
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
                val resolvedRelated = registerSummariesCanonical(altDetails.relatedTitles)
                val enrichedAlt = altDetails.copy(
                    summary = resolvedSummary,
                    relatedTitles = resolvedRelated
                )
                cacheSummaries(listOf(enrichedAlt.summary) + enrichedAlt.relatedTitles)
                return@withContext Result.success(enrichedAlt)
            }
        }

        val primaryError = primaryResult.exceptionOrNull()
            ?: IllegalStateException("Unable to load title details.")
        val staleDecoded = cachedEntry?.let { entry ->
            withContext(defaultDispatcher) { MoviesCacheJsonCodec.decodeDetails(entry.payloadJson) }
        }
        staleDecoded?.let {
            cacheSummaries(listOf(it.summary) + it.relatedTitles)
            Result.success(it)
        } ?: Result.failure(primaryError)
    }

    private suspend fun fetchProviderDetails(providerId: String, titleId: String): Result<MediaDetails> =
        when (providerId) {
            MovieBoxProvider.PROVIDER_ID -> movieBoxProvider?.getDetails(titleId)
                ?: Result.failure(IllegalStateException("Streaming service is not configured."))
            InternetArchiveMoviesProvider.PROVIDER_ID -> internetArchiveProvider.getDetails(titleId)
            TvMazeSeriesProvider.PROVIDER_ID -> tvMazeProvider.getDetails(titleId)
            TmdbOptionalMoviesProvider.PROVIDER_ID -> tmdbProvider.getDetails(titleId)
            else -> Result.failure(IllegalArgumentException("Unknown provider '$providerId'"))
        }

    override suspend fun getPersonFilmography(
        providerId: String,
        personId: String,
        personName: String,
        personImageUrl: String?
    ): Result<PersonFilmography> = withContext(ioDispatcher) {
        val result = when (providerId) {
            MovieBoxProvider.PROVIDER_ID -> movieBoxProvider?.getPersonFilmography(personId, personName, personImageUrl)
                ?: Result.failure(IllegalStateException("Streaming service is not configured."))
            TvMazeSeriesProvider.PROVIDER_ID -> tvMazeProvider.getPersonFilmography(personId, personName, personImageUrl)
            else -> Result.failure(UnsupportedOperationException("Person filmography is not supported for $providerId"))
        }
        result.map { filmography ->
            filmography.copy(credits = registerSummariesCanonical(filmography.credits))
        }
    }

    override suspend fun getSeasons(providerId: String, seriesId: String): Result<List<SeasonSummary>> = withContext(ioDispatcher) {
        val (resolvedProviderId, resolvedSeriesId) = mediaResolver.resolveConcreteTarget(
            providerId = providerId,
            titleId = seriesId,
            preferPlayable = false
        )
        val primary = when (resolvedProviderId) {
            MovieBoxProvider.PROVIDER_ID -> movieBoxProvider?.getSeasons(resolvedSeriesId)
                ?: Result.failure(IllegalStateException("Streaming service is not configured."))
            TvMazeSeriesProvider.PROVIDER_ID -> tvMazeProvider.getSeasons(resolvedSeriesId)
            TmdbOptionalMoviesProvider.PROVIDER_ID -> tmdbProvider.getSeasons(resolvedSeriesId)
            DevelopmentMoviesFixtureProvider.PROVIDER_ID -> fixtureProvider.getSeasons(resolvedSeriesId)
            else -> Result.success(emptyList())
        }
        if (primary.isSuccess) return@withContext primary

        val linkedAlternates = mediaResolver.getLinksForTitle(resolvedProviderId, resolvedSeriesId)
            .filterNot { it.providerId == resolvedProviderId }
        for (alt in linkedAlternates) {
            if (alt.providerId == TvMazeSeriesProvider.PROVIDER_ID) {
                val altRes = tvMazeProvider.getSeasons(alt.providerTitleId)
                if (altRes.isSuccess) return@withContext altRes
            }
        }
        primary
    }

    override suspend fun getEpisodes(providerId: String, seriesId: String, seasonNumber: Int): Result<List<EpisodeItem>> = withContext(ioDispatcher) {
        val (resolvedProviderId, resolvedSeriesId) = mediaResolver.resolveConcreteTarget(
            providerId = providerId,
            titleId = seriesId,
            preferPlayable = false
        )
        if (resolvedProviderId == DevelopmentMoviesFixtureProvider.PROVIDER_ID) {
            return@withContext fixtureProvider.getEpisodes(resolvedSeriesId, seasonNumber)
        }
        val currentMs = nowMs()
        val cacheKey = "episodes:$resolvedProviderId:$resolvedSeriesId:$seasonNumber"
        val cachedEntry = moviesDao.getCacheEntry(cacheKey)
        if (cachedEntry != null && cachedEntry.expiresAt > currentMs) {
            val decoded = withContext(defaultDispatcher) {
                MoviesCacheJsonCodec.decodeEpisodes(cachedEntry.payloadJson)
            }
            if (decoded != null) return@withContext Result.success(decoded)
        }

        val result = when (resolvedProviderId) {
            MovieBoxProvider.PROVIDER_ID -> movieBoxProvider?.getEpisodes(resolvedSeriesId, seasonNumber)
                ?: Result.failure(IllegalStateException("Streaming service is not configured."))
            TvMazeSeriesProvider.PROVIDER_ID -> tvMazeProvider.getEpisodes(resolvedSeriesId, seasonNumber)
            TmdbOptionalMoviesProvider.PROVIDER_ID -> tmdbProvider.getEpisodes(resolvedSeriesId, seasonNumber)
            else -> Result.success(emptyList())
        }

        if (result.isSuccess) {
            val episodes = result.getOrThrow()
            val encoded = withContext(defaultDispatcher) {
                MoviesCacheJsonCodec.encodeEpisodes(episodes)
            }
            moviesDao.upsertCacheEntry(
                MovieCacheEntryEntity(
                    cacheKey = cacheKey,
                    providerId = resolvedProviderId,
                    payloadJson = encoded,
                    updatedAt = currentMs,
                    expiresAt = currentMs + CACHE_TTL_MS
                )
            )
            return@withContext Result.success(episodes)
        }

        val linkedAlternates = mediaResolver.getLinksForTitle(resolvedProviderId, resolvedSeriesId)
            .filterNot { it.providerId == resolvedProviderId }
        for (alt in linkedAlternates) {
            if (alt.providerId == TvMazeSeriesProvider.PROVIDER_ID) {
                val altRes = tvMazeProvider.getEpisodes(alt.providerTitleId, seasonNumber)
                if (altRes.isSuccess) return@withContext altRes
            }
        }

        val error = result.exceptionOrNull() ?: IllegalStateException("Unable to load season episodes.")
        val staleDecoded = cachedEntry?.let { entry ->
            withContext(defaultDispatcher) { MoviesCacheJsonCodec.decodeEpisodes(entry.payloadJson) }
        }
        staleDecoded?.let { Result.success(it) } ?: Result.failure(error)
    }

    override suspend fun getSeasonEpisodeSizes(
        providerId: String,
        seriesId: String,
        seasonNumber: Int,
        episodeNumbers: List<Int>
    ): Result<Map<Int, Map<Int, Double>>> = withContext(ioDispatcher) {
        val (resolvedProviderId, resolvedSeriesId) = mediaResolver.resolveConcreteTarget(
            providerId = providerId,
            titleId = seriesId,
            preferPlayable = true
        )
        when (resolvedProviderId) {
            MovieBoxProvider.PROVIDER_ID ->
                movieBoxProvider?.getSeasonEpisodeSizes(resolvedSeriesId, seasonNumber, episodeNumbers)
                    ?: Result.success(emptyMap())
            else -> Result.success(emptyMap())
        }
    }

    override suspend fun resolvePlayback(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Result<PlaybackResolution> = withContext(ioDispatcher) {
        val (resolvedProviderId, resolvedTitleId) = mediaResolver.resolveConcreteTarget(
            providerId = providerId,
            titleId = titleId,
            preferPlayable = true
        )
        val offlineMatchDef = async { findOfflineEpisodeAsset(resolvedProviderId, resolvedTitleId, seasonNumber, episodeNumber) }
        val savedPosDef = async { getSavedPositionMs(resolvedProviderId, resolvedTitleId, seasonNumber, episodeNumber) }
        val networkResDef = async {
            when (resolvedProviderId) {
                MovieBoxProvider.PROVIDER_ID ->
                    movieBoxProvider?.resolvePlaybackSources(resolvedTitleId, seasonNumber, episodeNumber)
                        ?: Result.failure(IllegalStateException("Streaming service is not configured."))
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
        }

        val offlineMatch = offlineMatchDef.await()
        val offlineSource = offlineMatch?.let { match ->
            localMediaUriResolver?.invoke(match.localPath)?.let { fileUri ->
                PlaybackSource(
                    id = "offline_${match.assetId}",
                    url = fileUri,
                    format = "MP4",
                    qualityLabel = "${match.qualityLabel ?: "Downloaded"} (Offline)",
                    qualityHeight = parseHeightFromQuality(match.qualityLabel),
                    sizeMb = match.fileSizeBytes / (1024.0 * 1024.0),
                    providerName = "Offline Library",
                    isOfflineLocal = true
                )
            }
        }
        val offlineSubtitles = if (offlineSource != null && offlineMatch != null) {
            subtitleDownloadManager?.getOfflineSubtitleTracksForTitle(
                providerId = resolvedProviderId,
                titleId = resolvedTitleId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                assetId = offlineMatch.assetId
            ).orEmpty()
        } else {
            emptyList()
        }
        val savedPos = savedPosDef.await()
        val result = networkResDef.await()

        if (result.isFailure && offlineSource != null) {
            return@withContext Result.success(
                PlaybackResolution.Available(
                    title = "Offline Playback",
                    subtitleHeading = if (seasonNumber != null && episodeNumber != null) "S${seasonNumber} · E${episodeNumber}" else null,
                    sources = listOf(offlineSource),
                    subtitles = offlineSubtitles,
                    startPositionMs = savedPos,
                    providerName = "Offline Library",
                    sourceBadge = DataSourceBadge.LIVE_INTERNET_ARCHIVE
                )
            )
        }

        result.map { resolution ->
            when (resolution) {
                is PlaybackResolution.Available -> {
                    val combinedSources = if (offlineSource != null) {
                        listOf(offlineSource) + resolution.sources
                    } else {
                        resolution.sources
                    }
                    val combinedSubtitles = if (offlineSubtitles.isNotEmpty()) {
                        (offlineSubtitles + resolution.subtitles)
                            .distinctBy { "${it.languageCode.lowercase()}|${it.format.lowercase()}" }
                    } else {
                        resolution.subtitles
                    }
                    resolution.copy(
                        sources = combinedSources,
                        subtitles = combinedSubtitles,
                        startPositionMs = savedPos
                    )
                }
                is PlaybackResolution.Unavailable -> {
                    if (offlineSource != null) {
                        PlaybackResolution.Available(
                            title = resolution.title,
                            subtitleHeading = if (seasonNumber != null && episodeNumber != null) "S${seasonNumber} · E${episodeNumber}" else null,
                            sources = listOf(offlineSource),
                            subtitles = offlineSubtitles,
                            startPositionMs = savedPos,
                            providerName = "Offline Library",
                            sourceBadge = resolution.sourceBadge
                        )
                    } else {
                        resolution
                    }
                }
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
        moviesDao.observeWatchlist()
            .map { entities ->
                entities.map { it.toDomain() }.also { cacheSummaries(it) }
            }
            .flowOn(defaultDispatcher)

    override fun observeIsInWatchlist(compositeId: String): Flow<Boolean> =
        moviesDao.observeIsInWatchlist(compositeId).flowOn(ioDispatcher)

    override suspend fun toggleWatchlist(item: MovieTitleSummary): Boolean = withContext(ioDispatcher) {
        val resolved = mediaResolver.resolveOrRegister(item)
        cacheSummaries(listOf(resolved))
        val cid = resolved.canonicalId
        val existing = moviesDao.getWatchlistItem(resolved.compositeId)
            ?: cid?.let { moviesDao.getWatchlistItemByCanonical(it) }
        if (existing != null) {
            moviesDao.deleteWatchlistItem(existing.compositeId)
            cid?.let { moviesDao.deleteWatchlistItemsByCanonical(it) }
            false
        } else {
            moviesDao.insertWatchlistItem(resolved.toWatchlistEntity(nowMs()))
            true
        }
    }

    override fun observeContinueWatching(limit: Int): Flow<List<WatchHistoryItem>> =
        moviesDao.observeContinueWatching(limit)
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(defaultDispatcher)

    override suspend fun saveWatchProgress(
        item: MovieTitleSummary,
        positionMs: Long,
        durationMs: Long,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeLabel: String?
    ) = withContext(ioDispatcher) {
        val resolved = if (item.canonicalId != null) item else mediaResolver.resolveOrRegister(item)
        cacheSummaries(listOf(resolved))
        val key = buildHistoryKey(resolved.providerId, resolved.id, seasonNumber, episodeNumber)
        val existing = moviesDao.getWatchHistoryByKey(key)
            ?: resolved.canonicalId?.let {
                moviesDao.getWatchHistoryByCanonical(it, seasonNumber ?: 0, episodeNumber ?: 0)
            }
        val cleanPos = positionMs.coerceAtLeast(0L)
        val cleanDur = durationMs.coerceAtLeast(0L)
        val now = nowMs()
        val reachedCompletion = cleanDur > 0L && (cleanPos.toFloat() / cleanDur.toFloat()) >= 0.96f

        val wasCompleted = existing?.isCompleted == true
        val prevFraction = if (existing != null && existing.durationMs > 0L) {
            existing.positionMs.toFloat() / existing.durationMs.toFloat()
        } else 0f
        val newRewatchCount = when {
            reachedCompletion && wasCompleted && prevFraction < 0.90f -> existing!!.rewatchCount + 1
            else -> existing?.rewatchCount ?: 0
        }
        val isCompleted = wasCompleted || reachedCompletion
        val completedAt = when {
            reachedCompletion -> now
            else -> existing?.completedAt
        }

        moviesDao.upsertWatchHistory(
            MovieWatchHistoryEntity(
                historyKey = existing?.historyKey ?: key,
                compositeId = existing?.compositeId ?: resolved.compositeId,
                providerId = resolved.providerId,
                titleId = resolved.id,
                kind = resolved.kind.wireValue,
                title = resolved.title,
                episodeLabel = episodeLabel ?: existing?.episodeLabel,
                seasonNumber = seasonNumber ?: existing?.seasonNumber,
                episodeNumber = episodeNumber ?: existing?.episodeNumber,
                posterUrl = resolved.posterUrl ?: existing?.posterUrl,
                backdropUrl = resolved.backdropUrl ?: item.backdropUrl ?: existing?.backdropUrl,
                positionMs = cleanPos,
                durationMs = cleanDur,
                lastWatchedAt = now,
                canonicalId = resolved.canonicalId ?: existing?.canonicalId,
                isTrailer = false,
                isCompleted = isCompleted,
                completedAt = completedAt,
                rewatchCount = newRewatchCount,
                hiddenFromContinueWatching = if (cleanPos > 0L) false else existing?.hiddenFromContinueWatching ?: false,
                isHiddenTitle = existing?.isHiddenTitle ?: false
            )
        )
    }

    override suspend fun getSavedPositionMs(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Long = withContext(ioDispatcher) {
        val (resolvedProviderId, resolvedTitleId) = mediaResolver.resolveConcreteTarget(providerId, titleId, preferPlayable = false)
        val key = buildHistoryKey(resolvedProviderId, resolvedTitleId, seasonNumber, episodeNumber)
        val entry = moviesDao.getWatchHistoryByKey(key) ?: run {
            val cid = mediaResolver.resolveCanonicalIdForProvider(providerId, titleId)
            if (cid != null) {
                moviesDao.getWatchHistoryByCanonical(cid, seasonNumber ?: 0, episodeNumber ?: 0)
            } else null
        } ?: return@withContext 0L
        // Start from beginning if user reached >= 96% completion
        if (entry.durationMs > 0L && entry.positionMs.toFloat() / entry.durationMs.toFloat() >= 0.96f) {
            return@withContext 0L
        }
        entry.positionMs
    }

    override fun observeViewingHistory(limit: Int): Flow<List<WatchHistoryItem>> =
        moviesDao.observeViewingHistory(limit)
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(defaultDispatcher)

    override fun observeMovieWatchHistory(limit: Int): Flow<List<WatchHistoryItem>> =
        moviesDao.observeMovieWatchHistory(limit)
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(defaultDispatcher)

    override fun observeEpisodeWatchHistory(limit: Int): Flow<List<WatchHistoryItem>> =
        moviesDao.observeEpisodeWatchHistory(limit)
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(defaultDispatcher)

    override fun observeTrailerHistory(limit: Int): Flow<List<WatchHistoryItem>> =
        moviesDao.observeTrailerHistory(limit)
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(defaultDispatcher)

    override fun observeHiddenHistory(limit: Int): Flow<List<WatchHistoryItem>> =
        moviesDao.observeHiddenTitles(limit)
            .map { entities -> entities.map { it.toDomainHistoryItem() } }
            .flowOn(defaultDispatcher)

    override fun observeSeriesProgress(
        providerId: String,
        titleId: String,
        title: String
    ): Flow<SeriesProgressSummary> = flow {
        val (resolvedProviderId, resolvedTitleId) = mediaResolver.resolveConcreteTarget(providerId, titleId, preferPlayable = false)
        val cid = mediaResolver.resolveCanonicalIdForProvider(providerId, titleId)
        val compositeId = "$resolvedProviderId:$resolvedTitleId"
        val mediaKey = cid ?: compositeId
        emitAll(
            moviesDao.observeHistoryForTitle(compositeId, cid).map { entries ->
                val epStates = mutableMapOf<Pair<Int, Int>, EpisodeProgressState>()
                var latestEntry: MovieWatchHistoryEntity? = null
                for (entry in entries) {
                    val se = entry.seasonNumber ?: continue
                    val ep = entry.episodeNumber ?: continue
                    val completed = entry.isCompleted || (entry.durationMs > 0L && entry.positionMs.toFloat() / entry.durationMs.toFloat() >= 0.96f)
                    epStates[se to ep] = EpisodeProgressState(
                        seasonNumber = se,
                        episodeNumber = ep,
                        isCompleted = completed,
                        positionMs = entry.positionMs,
                        durationMs = entry.durationMs,
                        rewatchCount = entry.rewatchCount,
                        lastWatchedAt = entry.lastWatchedAt
                    )
                    if (latestEntry == null || entry.lastWatchedAt > latestEntry.lastWatchedAt) {
                        latestEntry = entry
                    }
                }
                SeriesProgressSummary(
                    mediaKey = mediaKey,
                    compositeId = compositeId,
                    providerId = resolvedProviderId,
                    titleId = resolvedTitleId,
                    title = title.ifBlank { latestEntry?.title ?: "" },
                    totalWatchedEpisodes = epStates.values.count { it.isCompleted },
                    lastWatchedSeason = latestEntry?.seasonNumber,
                    lastWatchedEpisode = latestEntry?.episodeNumber,
                    lastWatchedAt = latestEntry?.lastWatchedAt ?: 0L,
                    nextUnwatchedSeason = null,
                    nextUnwatchedEpisode = null,
                    episodeStates = epStates
                )
            }
        )
    }.flowOn(ioDispatcher)

    override suspend fun computeNextUnwatchedEpisode(
        providerId: String,
        titleId: String,
        seasons: List<SeasonSummary>,
        episodesBySeason: Map<Int, List<EpisodeItem>>
    ): Pair<Int, Int>? = withContext(ioDispatcher) {
        val (resolvedProviderId, resolvedTitleId) = mediaResolver.resolveConcreteTarget(providerId, titleId, preferPlayable = false)
        val cid = mediaResolver.resolveCanonicalIdForProvider(providerId, titleId)
        val entries = moviesDao.getHistoryListForTitle("$resolvedProviderId:$resolvedTitleId", cid)
        val completedKeys = entries
            .filter {
                it.isCompleted || (it.durationMs > 0L && it.positionMs.toFloat() / it.durationMs.toFloat() >= 0.96f)
            }
            .mapNotNull { e ->
                val se = e.seasonNumber ?: return@mapNotNull null
                val ep = e.episodeNumber ?: return@mapNotNull null
                se to ep
            }
            .toSet()

        val sortedSeasons = seasons.sortedBy { it.seasonNumber }
        for (season in sortedSeasons) {
            val loadedEpisodes = episodesBySeason[season.seasonNumber]
            if (!loadedEpisodes.isNullOrEmpty()) {
                for (ep in loadedEpisodes.sortedBy { it.episodeNumber }) {
                    if ((season.seasonNumber to ep.episodeNumber) !in completedKeys) {
                        return@withContext season.seasonNumber to ep.episodeNumber
                    }
                }
            } else if (season.availableEpisodes.isNotEmpty()) {
                for (epNum in season.availableEpisodes.sorted()) {
                    if ((season.seasonNumber to epNum) !in completedKeys) {
                        return@withContext season.seasonNumber to epNum
                    }
                }
            } else if (season.episodeCount > 0) {
                for (epNum in 1..season.episodeCount) {
                    if ((season.seasonNumber to epNum) !in completedKeys) {
                        return@withContext season.seasonNumber to epNum
                    }
                }
            }
        }
        null
    }

    override suspend fun markWatched(
        item: MovieTitleSummary,
        durationMs: Long,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeLabel: String?,
        isRewatch: Boolean
    ) = withContext(ioDispatcher) {
        val resolved = if (item.canonicalId != null) item else mediaResolver.resolveOrRegister(item)
        cacheSummaries(listOf(resolved))
        val key = buildHistoryKey(resolved.providerId, resolved.id, seasonNumber, episodeNumber)
        val existing = moviesDao.getWatchHistoryByKey(key)
            ?: resolved.canonicalId?.let {
                moviesDao.getWatchHistoryByCanonical(it, seasonNumber ?: 0, episodeNumber ?: 0)
            }
        val now = nowMs()
        val effectiveDuration = when {
            durationMs > 0L -> durationMs
            existing != null && existing.durationMs > 0L -> existing.durationMs
            resolved.durationMinutes != null && resolved.durationMinutes > 0 -> resolved.durationMinutes * 60_000L
            else -> 1_000L
        }
        val alreadyCompleted = existing?.isCompleted == true
        val newRewatchCount = when {
            isRewatch || alreadyCompleted -> (existing?.rewatchCount ?: 0) + 1
            else -> existing?.rewatchCount ?: 0
        }
        moviesDao.upsertWatchHistory(
            MovieWatchHistoryEntity(
                historyKey = existing?.historyKey ?: key,
                compositeId = existing?.compositeId ?: resolved.compositeId,
                providerId = resolved.providerId,
                titleId = resolved.id,
                kind = resolved.kind.wireValue,
                title = resolved.title,
                episodeLabel = episodeLabel ?: existing?.episodeLabel,
                seasonNumber = seasonNumber ?: existing?.seasonNumber,
                episodeNumber = episodeNumber ?: existing?.episodeNumber,
                posterUrl = resolved.posterUrl ?: existing?.posterUrl,
                backdropUrl = resolved.backdropUrl ?: existing?.backdropUrl,
                positionMs = effectiveDuration,
                durationMs = effectiveDuration,
                lastWatchedAt = now,
                canonicalId = resolved.canonicalId ?: existing?.canonicalId,
                isTrailer = false,
                isCompleted = true,
                completedAt = now,
                rewatchCount = newRewatchCount,
                hiddenFromContinueWatching = false,
                isHiddenTitle = false
            )
        )
    }

    override suspend fun markUnwatched(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ) = withContext(ioDispatcher) {
        val (resolvedProviderId, resolvedTitleId) = mediaResolver.resolveConcreteTarget(providerId, titleId, preferPlayable = false)
        val key = buildHistoryKey(resolvedProviderId, resolvedTitleId, seasonNumber, episodeNumber)
        val existing = moviesDao.getWatchHistoryByKey(key)
            ?: mediaResolver.resolveCanonicalIdForProvider(providerId, titleId)?.let { cid ->
                moviesDao.getWatchHistoryByCanonical(cid, seasonNumber ?: 0, episodeNumber ?: 0)
            }
        if (existing != null) {
            moviesDao.deleteWatchHistoryEntry(existing.historyKey)
        } else {
            moviesDao.deleteWatchHistoryEntry(key)
        }
    }

    override suspend fun restartFromBeginning(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ) = withContext(ioDispatcher) {
        val (resolvedProviderId, resolvedTitleId) = mediaResolver.resolveConcreteTarget(providerId, titleId, preferPlayable = false)
        val key = buildHistoryKey(resolvedProviderId, resolvedTitleId, seasonNumber, episodeNumber)
        val existing = moviesDao.getWatchHistoryByKey(key)
            ?: mediaResolver.resolveCanonicalIdForProvider(providerId, titleId)?.let { cid ->
                moviesDao.getWatchHistoryByCanonical(cid, seasonNumber ?: 0, episodeNumber ?: 0)
            }
        if (existing != null) {
            moviesDao.resetSavedPositionPreservingCompletion(existing.historyKey, nowMs())
        }
    }

    override suspend fun removeFromContinueWatching(providerId: String, titleId: String) = withContext(ioDispatcher) {
        val (resolvedProviderId, resolvedTitleId) = mediaResolver.resolveConcreteTarget(providerId, titleId, preferPlayable = false)
        val cid = mediaResolver.resolveCanonicalIdForProvider(providerId, titleId)
        moviesDao.setHiddenFromContinueWatching("$resolvedProviderId:$resolvedTitleId", cid, hidden = true)
    }

    override suspend fun setTitleHidden(providerId: String, titleId: String, hidden: Boolean) {
        setTitleHiddenInternal(providerId, titleId, hidden, itemHint = null)
    }

    override suspend fun setTitleHidden(item: MovieTitleSummary, hidden: Boolean) {
        cacheSummaries(listOf(item))
        setTitleHiddenInternal(item.providerId, item.id, hidden, itemHint = item)
    }

    private suspend fun setTitleHiddenInternal(
        providerId: String,
        titleId: String,
        hidden: Boolean,
        itemHint: MovieTitleSummary?
    ) = withContext(ioDispatcher) {
        val (resolvedProviderId, resolvedTitleId) = mediaResolver.resolveConcreteTarget(providerId, titleId, preferPlayable = false)
        val resolvedCompositeId = "$resolvedProviderId:$resolvedTitleId"
        val rawCompositeId = "$providerId:$titleId"
        val cid = itemHint?.canonicalId
            ?: mediaResolver.resolveCanonicalIdForProvider(providerId, titleId)
            ?: mediaResolver.resolveCanonicalIdForProvider(resolvedProviderId, resolvedTitleId)

        if (hidden) {
            val cached = itemHint
                ?: getCachedSummary(resolvedProviderId, resolvedTitleId)
                ?: getCachedSummary(providerId, titleId)
            val watchlistEntry = if (cached == null) {
                moviesDao.getWatchlistItem(resolvedCompositeId)
                    ?: moviesDao.getWatchlistItem(rawCompositeId)
                    ?: cid?.let { moviesDao.getWatchlistItemByCanonical(it) }
            } else null
            val recentEntry = if (cached == null && watchlistEntry == null) {
                moviesDao.getRecentlyViewedForTitle(resolvedCompositeId, cid)
                    ?: moviesDao.getRecentlyViewedForTitle(rawCompositeId, cid)
            } else null
            val historyEntry = if (cached == null && watchlistEntry == null && recentEntry == null) {
                moviesDao.getAnyHistoryForTitle(resolvedCompositeId, cid)
                    ?: moviesDao.getAnyHistoryForTitle(rawCompositeId, cid)
            } else null

            val resolvedKind = cached?.kind?.wireValue
                ?: watchlistEntry?.kind
                ?: recentEntry?.kind
                ?: historyEntry?.kind
                ?: MediaKind.MOVIE.wireValue
            val resolvedTitle = cached?.title
                ?: watchlistEntry?.title
                ?: recentEntry?.title
                ?: historyEntry?.title
                ?: resolvedTitleId
            val resolvedPoster = cached?.posterUrl
                ?: watchlistEntry?.posterUrl
                ?: recentEntry?.posterUrl
                ?: historyEntry?.posterUrl
            val mediaKey = cid ?: "$resolvedProviderId:$resolvedTitleId:$resolvedKind"

            moviesDao.upsertHiddenTitle(
                HiddenTitleEntity(
                    mediaKey = mediaKey,
                    compositeId = resolvedCompositeId,
                    providerId = resolvedProviderId,
                    titleId = resolvedTitleId,
                    canonicalId = cid,
                    kind = resolvedKind,
                    title = resolvedTitle,
                    posterUrl = resolvedPoster,
                    hiddenAt = nowMs()
                )
            )
        } else {
            val fallbackKind = itemHint?.kind?.wireValue ?: MediaKind.MOVIE.wireValue
            val mediaKey = cid ?: "$resolvedProviderId:$resolvedTitleId:$fallbackKind"
            moviesDao.deleteHiddenTitle(
                mediaKey = mediaKey,
                compositeId = resolvedCompositeId,
                canonicalId = cid,
                providerId = resolvedProviderId,
                titleId = resolvedTitleId
            )
            if (rawCompositeId != resolvedCompositeId) {
                moviesDao.deleteHiddenTitle(
                    mediaKey = cid ?: "$providerId:$titleId:$fallbackKind",
                    compositeId = rawCompositeId,
                    canonicalId = cid,
                    providerId = providerId,
                    titleId = titleId
                )
            }
            moviesDao.setTitleHidden(resolvedCompositeId, cid, hidden = false)
            moviesDao.setHiddenFromContinueWatching(resolvedCompositeId, cid, hidden = false)
        }
    }

    override suspend fun recordTrailerWatch(
        item: MovieTitleSummary,
        positionMs: Long,
        durationMs: Long
    ) = withContext(ioDispatcher) {
        val resolved = if (item.canonicalId != null) item else mediaResolver.resolveOrRegister(item)
        cacheSummaries(listOf(resolved))
        val key = "trailer:${resolved.providerId}:${resolved.id}"
        moviesDao.upsertWatchHistory(
            MovieWatchHistoryEntity(
                historyKey = key,
                compositeId = resolved.compositeId,
                providerId = resolved.providerId,
                titleId = resolved.id,
                kind = resolved.kind.wireValue,
                title = resolved.title,
                episodeLabel = "Trailer",
                seasonNumber = null,
                episodeNumber = null,
                posterUrl = resolved.posterUrl,
                backdropUrl = resolved.backdropUrl ?: item.backdropUrl,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                lastWatchedAt = nowMs(),
                canonicalId = resolved.canonicalId,
                isTrailer = true
            )
        )
    }

    override suspend fun deleteViewingHistoryEntry(historyKey: String) = withContext(ioDispatcher) {
        moviesDao.deleteWatchHistoryEntry(historyKey)
    }

    override suspend fun clearViewingHistory(trailersOnly: Boolean?) = withContext(ioDispatcher) {
        when (trailersOnly) {
            null -> moviesDao.clearWatchHistory()
            else -> moviesDao.clearWatchHistoryByTrailerFlag(trailersOnly)
        }
    }

    override fun observeRecentlyViewed(limit: Int): Flow<List<MovieTitleSummary>> =
        moviesDao.observeRecentlyViewed(limit)
            .map { list -> list.map { it.toDomain() }.also { cacheSummaries(it) } }
            .flowOn(defaultDispatcher)

    override suspend fun recordRecentlyViewed(item: MovieTitleSummary) = withContext(ioDispatcher) {
        if (item.id.isBlank() || item.providerId.isBlank()) return@withContext
        val resolved = if (item.canonicalId != null) item else mediaResolver.resolveOrRegister(item)
        cacheSummaries(listOf(resolved))
        moviesDao.upsertRecentlyViewed(
            MovieRecentlyViewedEntity(
                mediaKey = resolved.stableMediaKey,
                compositeId = resolved.compositeId,
                providerId = resolved.providerId,
                titleId = resolved.id,
                canonicalId = resolved.canonicalId,
                kind = resolved.kind.wireValue,
                title = resolved.title,
                posterUrl = resolved.posterUrl,
                backdropUrl = resolved.backdropUrl,
                releaseYear = resolved.releaseYear,
                rating = resolved.rating,
                genresCsv = resolved.genres.joinToString(","),
                sourceBadge = resolved.sourceBadge.name,
                isPlayableHint = resolved.isPlayableHint,
                viewedAt = nowMs()
            )
        )
    }

    override suspend fun removeRecentlyViewed(providerId: String, titleId: String) = withContext(ioDispatcher) {
        val cid = mediaResolver.resolveCanonicalIdForProvider(providerId, titleId)
        val concrete = mediaResolver.resolveConcreteTarget(providerId, titleId, preferPlayable = false)
        val compositeId = "$providerId:$titleId"
        moviesDao.deleteRecentlyViewed(
            mediaKey = cid ?: compositeId,
            compositeId = compositeId,
            canonicalId = cid ?: compositeId,
            providerId = providerId,
            titleId = titleId
        )
        if (concrete.first != providerId || concrete.second != titleId) {
            val concreteComposite = "${concrete.first}:${concrete.second}"
            moviesDao.deleteRecentlyViewed(
                mediaKey = cid ?: concreteComposite,
                compositeId = concreteComposite,
                canonicalId = cid ?: concreteComposite,
                providerId = concrete.first,
                titleId = concrete.second
            )
        }
    }

    override suspend fun clearRecentlyViewed() = withContext(ioDispatcher) {
        moviesDao.clearRecentlyViewed()
    }

    override fun observeCollectionIdsForMedia(providerId: String, titleId: String): Flow<List<Long>> = flow {
        val cid = mediaResolver.resolveCanonicalIdForProvider(providerId, titleId)
        val compositeId = "$providerId:$titleId"
        emitAll(moviesDao.observeCollectionIdsForMedia(cid ?: compositeId, compositeId))
    }.flowOn(ioDispatcher)

    override fun observeMediaInCollection(collectionId: Long): Flow<List<CollectionMediaItem>> =
        moviesDao.observeMediaInCollection(collectionId).map { refs ->
            refs.map { it.toDomain() }.also { items -> cacheSummaries(items.map { m -> m.summary }) }
        }.flowOn(defaultDispatcher)

    override suspend fun addMediaToCollection(item: MovieTitleSummary, collectionId: Long) = withContext(ioDispatcher) {
        val resolved = if (item.canonicalId != null) item else mediaResolver.resolveOrRegister(item)
        cacheSummaries(listOf(resolved))
        moviesDao.upsertMediaCollectionCrossRef(
            MediaCollectionCrossRef(
                mediaKey = resolved.stableMediaKey,
                collectionId = collectionId,
                compositeId = resolved.compositeId,
                providerId = resolved.providerId,
                titleId = resolved.id,
                canonicalId = resolved.canonicalId,
                kind = resolved.kind.wireValue,
                title = resolved.title,
                posterUrl = resolved.posterUrl,
                backdropUrl = resolved.backdropUrl,
                releaseYear = resolved.releaseYear,
                rating = resolved.rating,
                genresCsv = resolved.genres.joinToString(","),
                sourceBadge = resolved.sourceBadge.name,
                isPlayableHint = resolved.isPlayableHint,
                addedAt = nowMs()
            )
        )
    }

    override suspend fun removeMediaFromCollection(item: MovieTitleSummary, collectionId: Long) = withContext(ioDispatcher) {
        val cid = item.canonicalId ?: mediaResolver.resolveCanonicalIdForProvider(item.providerId, item.id)
        moviesDao.removeMediaFromCollection(cid ?: item.compositeId, item.compositeId, collectionId)
    }

    override suspend fun getSeriesPreferences(providerId: String, titleId: String): SeriesPreferences? = withContext(ioDispatcher) {
        val cid = mediaResolver.resolveCanonicalIdForProvider(providerId, titleId)
        val key = cid ?: "$providerId:$titleId"
        moviesDao.getSeriesPreferences(key)?.toDomain()
            ?: moviesDao.getSeriesPreferences("$providerId:$titleId")?.toDomain()
    }

    override fun observeSeriesPreferences(providerId: String, titleId: String): Flow<SeriesPreferences?> = flow {
        val cid = mediaResolver.resolveCanonicalIdForProvider(providerId, titleId)
        val key = cid ?: "$providerId:$titleId"
        emitAll(moviesDao.observeSeriesPreferences(key).map { it?.toDomain() })
    }.flowOn(ioDispatcher)

    override suspend fun saveSeriesAudioPreference(
        providerId: String,
        titleId: String,
        language: String?,
        label: String?
    ) = withContext(ioDispatcher) {
        val key = mediaResolver.resolveCanonicalIdForProvider(providerId, titleId) ?: "$providerId:$titleId"
        val current = moviesDao.getSeriesPreferences(key) ?: MovieSeriesPreferencesEntity(
            mediaKey = key,
            updatedAt = nowMs()
        )
        moviesDao.upsertSeriesPreferences(
            current.copy(
                preferredAudioLanguage = language,
                preferredAudioLabel = label,
                updatedAt = nowMs()
            )
        )
    }

    override suspend fun saveSeriesSubtitlePreference(
        providerId: String,
        titleId: String,
        language: String?,
        label: String?,
        disabled: Boolean
    ) = withContext(ioDispatcher) {
        val key = mediaResolver.resolveCanonicalIdForProvider(providerId, titleId) ?: "$providerId:$titleId"
        val current = moviesDao.getSeriesPreferences(key) ?: MovieSeriesPreferencesEntity(
            mediaKey = key,
            updatedAt = nowMs()
        )
        moviesDao.upsertSeriesPreferences(
            current.copy(
                preferredSubtitleLanguage = language,
                preferredSubtitleLabel = label,
                subtitlesDisabled = disabled,
                updatedAt = nowMs()
            )
        )
    }

    override suspend fun setSeriesRemindersMuted(providerId: String, titleId: String, muted: Boolean) = withContext(ioDispatcher) {
        val key = mediaResolver.resolveCanonicalIdForProvider(providerId, titleId) ?: "$providerId:$titleId"
        val current = moviesDao.getSeriesPreferences(key) ?: MovieSeriesPreferencesEntity(
            mediaKey = key,
            updatedAt = nowMs()
        )
        moviesDao.upsertSeriesPreferences(
            current.copy(
                remindersMuted = muted,
                updatedAt = nowMs()
            )
        )
    }

    override fun observeOfflineAvailability(): Flow<List<OfflineMediaMatch>> {
        val dao = downloadedAssetDao ?: return flowOf(emptyList())
        return dao.observeAvailableOfflineMovieAssets().map { assets ->
            assets.mapNotNull { asset ->
                val ref = asset.sourceRef?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val parts = ref.split("|")
                val se = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }
                val ep = parts.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 }
                OfflineMediaMatch(
                    assetId = asset.id,
                    sourceRef = ref,
                    localPath = asset.localPath,
                    qualityLabel = asset.qualityLabel ?: parts.getOrNull(4)?.takeIf { it.isNotBlank() },
                    fileSizeBytes = asset.fileSize,
                    seasonNumber = se,
                    episodeNumber = ep
                )
            }
        }.flowOn(defaultDispatcher)
    }

    override suspend fun findOfflineEpisodeAsset(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): OfflineMediaMatch? = withContext(ioDispatcher) {
        val dao = downloadedAssetDao ?: return@withContext null
        val se = seasonNumber ?: 0
        val ep = episodeNumber ?: 0
        val linked = mediaResolver.getLinksForTitle(providerId, titleId)
        val candidatePairs = buildList {
            add(providerId to titleId)
            linked.forEach { link -> add(link.providerId to link.providerTitleId) }
        }.distinct()

        for ((pId, tId) in candidatePairs) {
            val prefix = "${pId.trim()}|${tId.trim()}|$se|$ep|"
            val matches = dao.findAvailableBySourceRefPrefix(prefix)
            val best = matches.firstOrNull { asset ->
                localMediaUriResolver == null || localMediaUriResolver.invoke(asset.localPath) != null
            }
            if (best != null) {
                val ref = best.sourceRef.orEmpty()
                return@withContext OfflineMediaMatch(
                    assetId = best.id,
                    sourceRef = ref,
                    localPath = best.localPath,
                    qualityLabel = best.qualityLabel,
                    fileSizeBytes = best.fileSize,
                    seasonNumber = se.takeIf { it > 0 },
                    episodeNumber = ep.takeIf { it > 0 }
                )
            }
        }
        null
    }

    override fun observeActiveViewingReminders(): Flow<List<ViewingReminderItem>> =
        moviesDao.observeActiveViewingReminders()
            .map { list -> list.map { it.toDomain() } }
            .flowOn(defaultDispatcher)

    override fun observeViewingReminderForTitle(providerId: String, titleId: String): Flow<ViewingReminderItem?> = flow {
        val cid = mediaResolver.resolveCanonicalIdForProvider(providerId, titleId)
        val compositeId = "$providerId:$titleId"
        emitAll(moviesDao.observeViewingReminderForTitle(cid ?: compositeId, compositeId).map { it?.toDomain() })
    }.flowOn(ioDispatcher)

    override suspend fun scheduleViewingReminder(
        item: MovieTitleSummary,
        triggerAtMillis: Long,
        note: String?,
        seasonNumber: Int?,
        episodeNumber: Int?
    ) = withContext(ioDispatcher) {
        val resolved = if (item.canonicalId != null) item else mediaResolver.resolveOrRegister(item)
        cacheSummaries(listOf(resolved))
        val now = nowMs()
        val key = resolved.stableMediaKey
        val existing = moviesDao.getViewingReminderByMediaKey(key)
        moviesDao.upsertViewingReminder(
            MovieViewingReminderEntity(
                mediaKey = key,
                compositeId = resolved.compositeId,
                providerId = resolved.providerId,
                titleId = resolved.id,
                canonicalId = resolved.canonicalId,
                kind = resolved.kind.wireValue,
                title = resolved.title,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                posterUrl = resolved.posterUrl,
                note = note?.trim()?.takeIf { it.isNotBlank() },
                triggerAtMillis = triggerAtMillis,
                isDismissed = false,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )
    }

    override suspend fun dismissViewingReminder(providerId: String, titleId: String) = withContext(ioDispatcher) {
        val cid = mediaResolver.resolveCanonicalIdForProvider(providerId, titleId)
        val compositeId = "$providerId:$titleId"
        moviesDao.dismissViewingReminder(cid ?: compositeId, compositeId, nowMs())
    }

    override fun observeRecentSearches(limit: Int): Flow<List<String>> =
        moviesDao.observeRecentSearches(limit).flowOn(ioDispatcher)

    override suspend fun recordSearchQuery(query: String) = withContext(ioDispatcher) {
        val clean = query.trim()
        if (clean.length < 2) return@withContext
        moviesDao.upsertSearchQuery(
            MovieSearchHistoryEntity(query = clean, searchedAt = nowMs())
        )
    }

    override suspend fun clearSearchHistory() = withContext(ioDispatcher) {
        moviesDao.clearSearchHistory()
    }

    override suspend fun getProviderLinks(providerId: String, titleId: String): List<ProviderLinkRecord> = withContext(ioDispatcher) {
        mediaResolver.getLinksForTitle(providerId, titleId)
    }

    override suspend fun unlinkProviderTitle(providerId: String, titleId: String): String? = withContext(ioDispatcher) {
        registeredCanonicalCache.remove("$providerId:$titleId")
        mediaResolver.unlinkProviderTitle(providerId, titleId)
    }

    private suspend fun registerSummariesCanonical(items: List<MovieTitleSummary>): List<MovieTitleSummary> {
        if (items.isEmpty()) return items
        cacheSummaries(items)

        val uncached = items.filter { item ->
            item.id.isNotBlank() &&
                item.providerId.isNotBlank() &&
                registeredCanonicalCache[item.compositeId] == null
        }
        if (uncached.isNotEmpty()) {
            val resolvedBatch = mediaResolver.resolveOrRegisterBatch(uncached)
            cacheSummaries(resolvedBatch)
        }

        val finalItems = items.map { item ->
            val cachedCid = registeredCanonicalCache[item.compositeId]
            if (cachedCid != null && cachedCid != item.canonicalId) {
                item.copy(canonicalId = cachedCid)
            } else {
                item
            }
        }
        cacheSummaries(finalItems)
        return finalItems
    }

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

    private fun parseHeightFromQuality(quality: String?): Int {
        if (quality.isNullOrBlank()) return 720
        val match = Regex("(\\d{3,4})").find(quality)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 720
    }

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
        backdropUrl = backdropUrl,
        positionMs = positionMs,
        durationMs = durationMs,
        lastWatchedAt = lastWatchedAt,
        historyKey = historyKey,
        isTrailer = isTrailer,
        canonicalId = canonicalId,
        isCompleted = isCompleted,
        completedAt = completedAt,
        rewatchCount = rewatchCount,
        hiddenFromContinueWatching = hiddenFromContinueWatching,
        isHiddenTitle = isHiddenTitle
    )

    private fun MovieRecentlyViewedEntity.toDomain(): MovieTitleSummary = MovieTitleSummary(
        id = titleId,
        providerId = providerId,
        kind = MediaKind.fromWire(kind),
        title = title,
        releaseYear = releaseYear,
        rating = rating,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        genres = genresCsv.split(",").map { it.trim() }.filter { it.isNotBlank() },
        sourceBadge = DataSourceBadge.fromName(sourceBadge),
        isPlayableHint = isPlayableHint,
        canonicalId = canonicalId
    )

    private fun MediaCollectionCrossRef.toDomain(): CollectionMediaItem = CollectionMediaItem(
        collectionId = collectionId,
        mediaKey = mediaKey,
        summary = MovieTitleSummary(
            id = titleId,
            providerId = providerId,
            kind = MediaKind.fromWire(kind),
            title = title,
            releaseYear = releaseYear,
            rating = rating,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            genres = genresCsv.split(",").map { it.trim() }.filter { it.isNotBlank() },
            sourceBadge = DataSourceBadge.fromName(sourceBadge),
            isPlayableHint = isPlayableHint,
            canonicalId = canonicalId
        ),
        addedAt = addedAt
    )

    private fun MovieSeriesPreferencesEntity.toDomain(): SeriesPreferences = SeriesPreferences(
        mediaKey = mediaKey,
        preferredAudioLanguage = preferredAudioLanguage,
        preferredAudioLabel = preferredAudioLabel,
        preferredSubtitleLanguage = preferredSubtitleLanguage,
        preferredSubtitleLabel = preferredSubtitleLabel,
        subtitlesDisabled = subtitlesDisabled,
        remindersMuted = remindersMuted,
        updatedAt = updatedAt
    )

    private fun MovieViewingReminderEntity.toDomain(): ViewingReminderItem = ViewingReminderItem(
        mediaKey = mediaKey,
        compositeId = compositeId,
        providerId = providerId,
        titleId = titleId,
        canonicalId = canonicalId,
        kind = MediaKind.fromWire(kind),
        title = title,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        posterUrl = posterUrl,
        note = note,
        triggerAtMillis = triggerAtMillis,
        isDismissed = isDismissed
    )

    private fun HiddenTitleEntity.toDomainHistoryItem(): WatchHistoryItem = WatchHistoryItem(
        compositeId = compositeId,
        providerId = providerId,
        titleId = titleId,
        kind = MediaKind.fromWire(kind),
        title = title,
        posterUrl = posterUrl,
        positionMs = 0L,
        durationMs = 0L,
        lastWatchedAt = hiddenAt,
        historyKey = "hidden:$mediaKey",
        canonicalId = canonicalId,
        isHiddenTitle = true
    )

    companion object {
        const val CACHE_TTL_MS = 6L * 60 * 60 * 1000L
        const val STALE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000L
        const val MOVIEBOX_FALLBACK_NOTICE =
            "Main catalogue temporarily unavailable — showing archive & TV guide"
    }
}
