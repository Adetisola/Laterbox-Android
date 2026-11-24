package com.laterbox.app.movies.data.repository

import android.util.Log
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
import com.laterbox.app.movies.data.provider.MovieBoxApiException
import com.laterbox.app.movies.data.provider.MovieBoxFailureCategory
import com.laterbox.app.movies.data.provider.MovieBoxProvider
import com.laterbox.app.movies.data.provider.TmdbOptionalMoviesProvider
import com.laterbox.app.movies.data.provider.TvMazeSeriesProvider
import com.laterbox.app.movies.data.resolver.MatchCandidate
import com.laterbox.app.movies.data.resolver.MediaMatcher
import com.laterbox.app.movies.data.resolver.MediaResolver
import com.laterbox.app.movies.domain.engine.MovieBoxPriorityDeduplicator
import com.laterbox.app.movies.domain.model.CataloguePage
import com.laterbox.app.movies.domain.model.CatalogueSort
import com.laterbox.app.movies.domain.model.CollectionMediaItem
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.EpisodeProgressState
import com.laterbox.app.movies.domain.model.LibrarySearchSuggestion
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
import com.laterbox.app.movies.domain.repository.MovieDurationCoverageStats
import com.laterbox.app.movies.domain.repository.MoviesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class MoviesRepositoryImpl(
    private val moviesDao: MoviesDao,
    private val internetArchiveProvider: InternetArchiveMoviesProvider,
    private val tvMazeProvider: TvMazeSeriesProvider,
    private val tmdbProvider: TmdbOptionalMoviesProvider,
    private val fixtureProvider: DevelopmentMoviesFixtureProvider = DevelopmentMoviesFixtureProvider(),
    private val movieBoxProvider: MovieBoxProvider? = null,
    private val useSampleFixturesOnlyProvider: suspend () -> Boolean = { false },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val mediaResolver: MediaResolver = MediaResolver(moviesDao, nowMs),
    private val downloadedAssetDao: DownloadedAssetDao? = null,
    private val localMediaUriResolver: ((String) -> String?)? = null,
    private val subtitleDownloadManager: com.laterbox.app.movies.data.download.SubtitleDownloadManager? = null,
    private val downloadPlaybackProgressDao: com.laterbox.app.media.data.db.dao.DownloadPlaybackProgressDao? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val isMovieBoxEnabled: () -> Boolean = { true },
    private val isInternetArchiveEnabled: () -> Boolean = { true },
    private val isTvMazeEnabled: () -> Boolean = { true },
    private val isTmdbEnabled: () -> Boolean = { true },
    private val onAnalyticsPerformance: ((metricName: String, durationMs: Int, screenId: String?) -> Unit)? = null,
    private val onAnalyticsError: ((category: String, screenId: String?) -> Unit)? = null,
) : MoviesRepository {

    @Volatile
    var lastPrimaryFailureCategory: MovieBoxFailureCategory? = null
        private set

    @Volatile
    var lastPrimaryFailureDetail: String? = null
        private set

    private fun activeMovieBox(): MovieBoxProvider? {
        val provider = movieBoxProvider ?: return null
        return if (isMovieBoxEnabled()) {
            clearPrimaryFailure()
            provider
        } else {
            lastPrimaryFailureCategory = MovieBoxFailureCategory.REMOTE_DISABLED
            lastPrimaryFailureDetail = "provider_moviebox_enabled=false"
            null
        }
    }

    private fun clearPrimaryFailure() {
        lastPrimaryFailureCategory = null
        lastPrimaryFailureDetail = null
    }

    private fun classifyPrimaryFailure(error: Throwable?): MovieBoxFailureCategory {
        val apiErr = error as? MovieBoxApiException
        if (apiErr != null) return apiErr.failureCategory
        return when (error) {
            is java.net.SocketTimeoutException -> MovieBoxFailureCategory.TIMEOUT
            is java.io.IOException -> MovieBoxFailureCategory.NETWORK_ERROR
            else -> MovieBoxFailureCategory.UNKNOWN
        }
    }

    private fun recordPrimaryFallback(
        operation: String,
        error: Throwable?,
        screenId: String = "movies",
    ) {
        val category = classifyPrimaryFailure(error)
        val detail = (error as? MovieBoxApiException)?.diagnosticReason
            ?: error?.message?.take(200)
            ?: category.name
        lastPrimaryFailureCategory = category
        lastPrimaryFailureDetail = "$operation:$detail"
        runCatching {
            Log.w(
                "MoviesRepositoryImpl",
                "Primary catalogue fallback [$operation] category=$category detail=$detail"
            )
        }
        val analyticsCategory = when (category) {
            MovieBoxFailureCategory.TIMEOUT,
            MovieBoxFailureCategory.NETWORK_ERROR -> "network_timeout"
            MovieBoxFailureCategory.UPGRADE_REQUIRED -> "unsupported_version_blocked"
            MovieBoxFailureCategory.PARSE_ERROR -> "metadata_fetch_error"
            MovieBoxFailureCategory.REMOTE_DISABLED,
            MovieBoxFailureCategory.LOCAL_AUTH_UNAVAILABLE,
            MovieBoxFailureCategory.BACKEND_AUTH_REJECTED,
            MovieBoxFailureCategory.RATE_LIMITED,
            MovieBoxFailureCategory.UPSTREAM_BOOTSTRAP_FAILED,
            MovieBoxFailureCategory.UPSTREAM_REQUEST_FAILED,
            MovieBoxFailureCategory.UNKNOWN -> "api_http_error"
        }
        onAnalyticsError?.invoke(analyticsCategory, screenId)
    }

    @Volatile
    private var hasBackfilledCanonicalState: Boolean = false

    private data class CachedDurationEntry(
        val minutes: Int?,
        val recordedAtMs: Long
    )

    private val registeredCanonicalCache = ConcurrentHashMap<String, String>()
    private val summaryMemoryCache = ConcurrentHashMap<String, MovieTitleSummary>()
    private val durationLookupCache = ConcurrentHashMap<String, CachedDurationEntry>()
    private val inFlightDurationLookups = ConcurrentHashMap<String, CompletableDeferred<Int?>>()
    private val tvMazeShowIdByMovieBoxSeries = ConcurrentHashMap<String, String>()
    private val tvMazeShowLookupCooldown = ConcurrentHashMap<String, Long>()
    private val tvMazeStillCooldownBySeason = ConcurrentHashMap<String, Long>()
    private val totalMoviesObservedCount = AtomicInteger(0)
    private val initialDurationMoviesCount = AtomicInteger(0)
    private val enrichedMoviesCount = AtomicInteger(0)
    private val failedEnrichmentsCount = AtomicInteger(0)
    private val observedCoverageKeys = ConcurrentHashMap.newKeySet<String>()

    private fun cacheSummaries(items: List<MovieTitleSummary>) {
        val ts = nowMs()
        for (item in items) {
            if (item.providerId.isNotBlank() && item.id.isNotBlank()) {
                val existing = summaryMemoryCache[item.compositeId]
                val bestDuration = item.durationMinutes?.takeIf { it in 1..600 }
                    ?: existing?.durationMinutes?.takeIf { it in 1..600 }
                    ?: durationLookupCache[item.compositeId]?.minutes?.takeIf { it in 1..600 }
                val mergedItem = if (item.kind == MediaKind.MOVIE && bestDuration != item.durationMinutes) {
                    item.copy(durationMinutes = bestDuration)
                } else {
                    item
                }
                summaryMemoryCache[item.compositeId] = mergedItem
                if (item.kind == MediaKind.MOVIE && observedCoverageKeys.add(item.compositeId)) {
                    totalMoviesObservedCount.incrementAndGet()
                    if (bestDuration != null) {
                        initialDurationMoviesCount.incrementAndGet()
                    }
                }
                if (item.kind == MediaKind.MOVIE && bestDuration != null) {
                    durationLookupCache[item.compositeId] = CachedDurationEntry(bestDuration, ts)
                }
                item.canonicalId?.takeIf { it.isNotBlank() }?.let { cid ->
                    summaryMemoryCache["canonical:$cid"] = mergedItem
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

    override fun getDurationCoverageStats(): MovieDurationCoverageStats = MovieDurationCoverageStats(
        totalMoviesObserved = totalMoviesObservedCount.get(),
        moviesWithInitialDuration = initialDurationMoviesCount.get(),
        enrichedMovies = enrichedMoviesCount.get(),
        failedEnrichments = failedEnrichmentsCount.get()
    )

    override suspend fun enrichMissingMovieDurations(
        items: List<MovieTitleSummary>,
        maxItems: Int
    ): Map<String, Int> = withContext(ioDispatcher) {
        val cap = maxItems.coerceIn(1, 20)
        val movieCandidates = items
            .asSequence()
            .filter { it.kind == MediaKind.MOVIE && it.id.isNotBlank() && it.providerId.isNotBlank() }
            .distinctBy { it.compositeId }
            .toList()
        if (movieCandidates.isEmpty()) return@withContext emptyMap()

        val ts = nowMs()
        val resolved = linkedMapOf<String, Int>()
        val toLookup = mutableListOf<MovieTitleSummary>()
        val awaitedInFlight = mutableListOf<Pair<MovieTitleSummary, CompletableDeferred<Int?>>>()

        for (item in movieCandidates) {
            val currentDuration = item.durationMinutes?.takeIf { it in 1..600 }
                ?: summaryMemoryCache[item.compositeId]?.durationMinutes?.takeIf { it in 1..600 }
            if (currentDuration != null) {
                resolved[item.compositeId] = currentDuration
                continue
            }

            val cachedLookup = durationLookupCache[item.compositeId]
            if (cachedLookup != null) {
                val age = ts - cachedLookup.recordedAtMs
                if (cachedLookup.minutes != null && age < CACHE_TTL_MS) {
                    resolved[item.compositeId] = cachedLookup.minutes
                    applyEnrichedDurationToCache(item, cachedLookup.minutes)
                    continue
                }
                if (cachedLookup.minutes == null && age < DURATION_MISS_COOLDOWN_MS) {
                    continue
                }
            }

            if (toLookup.size + awaitedInFlight.size >= cap) continue

            val existingFlight = inFlightDurationLookups[item.compositeId]
            if (existingFlight != null) {
                awaitedInFlight.add(item to existingFlight)
            } else {
                val created = CompletableDeferred<Int?>()
                val racer = inFlightDurationLookups.putIfAbsent(item.compositeId, created)
                if (racer != null) {
                    awaitedInFlight.add(item to racer)
                } else {
                    toLookup.add(item)
                }
            }
        }

        if (toLookup.isNotEmpty()) {
            val newlyResolved = linkedMapOf<String, Int>()
            try {
                // 1. Batch resolve via Laterbox backend when MovieBox items exist
                val mb = activeMovieBox()
                val mbCandidates = toLookup.filter { it.providerId == MovieBoxProvider.PROVIDER_ID }
                if (mb != null && mbCandidates.isNotEmpty()) {
                    val batchMap = mb.fetchMovieRuntimesBatch(mbCandidates).getOrNull().orEmpty()
                    mbCandidates.forEach { cand ->
                        val mins = batchMap[cand.id]?.takeIf { it in 1..600 }
                        if (mins != null) {
                            newlyResolved[cand.compositeId] = mins
                        }
                    }
                }

                // 2. Fallback to strict TMDB / IA details for remaining unresolved movies
                var iaDetailCount = 0
                for (cand in toLookup) {
                    if (cand.compositeId in newlyResolved) continue
                    val fallbackMins: Int? = (
                        if (isTmdbEnabled()) {
                            tmdbProvider.findMovieRuntimeMinutes(
                                imdbId = cand.imdbId,
                                title = cand.title,
                                releaseYear = cand.releaseYear
                            )
                        } else null
                    ) ?: if (cand.providerId == InternetArchiveMoviesProvider.PROVIDER_ID &&
                        isInternetArchiveEnabled() &&
                        iaDetailCount < 4
                    ) {
                        iaDetailCount++
                        internetArchiveProvider.getDetails(cand.id)
                            .getOrNull()
                            ?.summary
                            ?.durationMinutes
                            ?.takeIf { it in 1..600 }
                    } else {
                        null
                    }
                    if (fallbackMins != null) {
                        newlyResolved[cand.compositeId] = fallbackMins
                    }
                }
            } finally {
                val doneTs = nowMs()
                for (cand in toLookup) {
                    val found = newlyResolved[cand.compositeId]
                    if (found != null) {
                        durationLookupCache[cand.compositeId] = CachedDurationEntry(found, doneTs)
                        applyEnrichedDurationToCache(cand, found)
                        enrichedMoviesCount.incrementAndGet()
                        resolved[cand.compositeId] = found
                    } else {
                        durationLookupCache[cand.compositeId] = CachedDurationEntry(null, doneTs)
                        failedEnrichmentsCount.incrementAndGet()
                    }
                    inFlightDurationLookups.remove(cand.compositeId)?.complete(found)
                }
            }
        }

        for ((item, deferred) in awaitedInFlight) {
            val found = runCatching { deferred.await() }.getOrNull()
            if (found != null && found in 1..600) {
                resolved[item.compositeId] = found
            }
        }

        resolved
    }

    private suspend fun applyEnrichedDurationToCache(item: MovieTitleSummary, minutes: Int) {
        if (item.kind != MediaKind.MOVIE || minutes !in 1..600) return
        val existing = summaryMemoryCache[item.compositeId] ?: item
        val updated = existing.copy(durationMinutes = minutes)
        summaryMemoryCache[item.compositeId] = updated
        updated.canonicalId?.takeIf { it.isNotBlank() }?.let { cid ->
            summaryMemoryCache["canonical:$cid"] = updated
        }

        val detailKey = "details:${item.providerId}:${item.id}"
        val cachedEntry = moviesDao.getCacheEntry(detailKey) ?: return
        val decoded = withContext(defaultDispatcher) {
            MoviesCacheJsonCodec.decodeDetails(cachedEntry.payloadJson)
        } ?: return
        if (decoded.summary.kind == MediaKind.MOVIE &&
            (decoded.summary.durationMinutes == null || decoded.summary.durationMinutes <= 0)
        ) {
            val encoded = withContext(defaultDispatcher) {
                MoviesCacheJsonCodec.encodeDetails(decoded.copy(summary = decoded.summary.copy(durationMinutes = minutes)))
            }
            moviesDao.upsertCacheEntry(
                cachedEntry.copy(payloadJson = encoded, updatedAt = nowMs())
            )
        }
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
        val mb = activeMovieBox()
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

            val fetchStartedMs = nowMs()
            val mbResult = mb.getHomeFeed()
            if (mbResult.isSuccess) {
                onAnalyticsPerformance?.invoke(
                    "catalogue_load_ms",
                    (nowMs() - fetchStartedMs).coerceIn(0L, 600_000L).toInt(),
                    "movies",
                )
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
            recordPrimaryFallback("home", mbResult.exceptionOrNull(), screenId = "movies")
            val fallbackFeedResult = getOpenArchiveHomeFeed(
                forceRefresh = true,
                fallbackNotice = buildPrimaryFallbackNotice(mbResult.exceptionOrNull())
            )
            if (fallbackFeedResult.isSuccess) {
                onAnalyticsPerformance?.invoke(
                    "catalogue_load_ms",
                    (nowMs() - fetchStartedMs).coerceIn(0L, 600_000L).toInt(),
                    "movies",
                )
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
            onAnalyticsError?.invoke("metadata_fetch_error", "movies")
            return@withContext Result.failure(
                mbResult.exceptionOrNull()
                    ?: fallbackFeedResult.exceptionOrNull()
                    ?: IllegalStateException("Unable to load Movies & TV catalogue")
            )
        }

        if (!isMovieBoxEnabled() && movieBoxProvider != null) {
            lastPrimaryFailureCategory = MovieBoxFailureCategory.REMOTE_DISABLED
            lastPrimaryFailureDetail = "home:provider_moviebox_enabled=false"
            runCatching {
                Log.i("MoviesRepositoryImpl", "Primary catalogue disabled by remote configuration")
            }
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

        val iaDeferred = if (isInternetArchiveEnabled()) {
            async { internetArchiveProvider.getHomeFeed() }
        } else null
        val tvMazeDeferred = if (isTvMazeEnabled()) {
            async { tvMazeProvider.getHomeFeed() }
        } else null

        val iaResult = iaDeferred?.await()
        val tvMazeResult = tvMazeDeferred?.await()

        val iaFeed = iaResult?.getOrNull()
        val tvFeed = tvMazeResult?.getOrNull()

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
            val firstError = iaResult?.exceptionOrNull()
                ?: tvMazeResult?.exceptionOrNull()
                ?: IllegalStateException("Movies & TV discovery providers are currently paused.")
            return@withContext Result.failure(firstError)
        }

        val mergedBanners = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
            interleaveLists(
                listOfNotNull(
                    iaFeed?.featuredBanners,
                    tvFeed?.featuredBanners
                )
            )
        ).take(6)

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
                MediaKind.MOVIE, MediaKind.TV_SERIES, MediaKind.SHORT_DRAMA ->
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

        var mbFailure: Throwable? = null
        val mb = activeMovieBox()
        if (mb != null) {
            if (kind == MediaKind.SHORT_DRAMA) {
                // Prefer fresh/cached home feed Short Drama rails before forcing a fresh /home network call.
                val mbCacheKey = buildMovieBoxHomeCacheKey(mb.activeBaseUrl())
                val cachedEntry = moviesDao.getCacheEntry(mbCacheKey)
                val cachedFeed = cachedEntry?.let { entry ->
                    withContext(defaultDispatcher) {
                        MoviesCacheJsonCodec.decodeHomeFeed(entry.payloadJson, isOfflineCached = false)
                    }
                }
                val fromCachedHome = cachedFeed?.let { feed ->
                    extractShortDramaCataloguePage(
                        feed = feed,
                        page = page,
                        pageSize = pageSize,
                        genre = effectiveGenre,
                        sort = sort,
                        country = effectiveCountry,
                        year = effectiveYear
                    )
                }
                if (fromCachedHome != null && fromCachedHome.items.isNotEmpty()) {
                    return@withContext Result.success(
                        fromCachedHome.copy(items = registerSummariesCanonical(fromCachedHome.items))
                    )
                }
            }
            val mbResult = when (kind) {
                MediaKind.MOVIE, MediaKind.TV_SERIES, MediaKind.SHORT_DRAMA ->
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
                        val combined = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
                            interleaveLists(pages.map { it.items })
                        )
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
                val dedupedItems = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
                    registerSummariesCanonical(pageData.items)
                )
                return@withContext Result.success(pageData.copy(items = dedupedItems))
            }
            mbFailure = mbResult.exceptionOrNull()
            recordPrimaryFallback("catalogue:${kind?.name?.lowercase(Locale.US) ?: "all"}", mbFailure, screenId = "catalogue")
        }

        if (kind == MediaKind.SHORT_DRAMA) {
            if (mbFailure != null) {
                return@withContext Result.failure(mbFailure)
            }
            return@withContext Result.success(
                CataloguePage(items = emptyList(), page = page, pageSize = pageSize, totalCount = 0, hasMore = false)
            )
        }

        // Secondary fallback to Internet Archive + TVMaze
        val queryMovies = kind == null || kind == MediaKind.MOVIE
        val querySeries = kind == null || kind == MediaKind.TV_SERIES
        val perProviderSize = if (kind == null) (pageSize / 2).coerceAtLeast(8) else pageSize

        val iaDeferred = if (queryMovies && isInternetArchiveEnabled()) {
            async { internetArchiveProvider.getCatalogue(MediaKind.MOVIE, page, perProviderSize, effectiveGenre, sort, effectiveCountry, effectiveYear) }
        } else null
        val tvDeferred = if (querySeries && isTvMazeEnabled()) {
            async { tvMazeProvider.getCatalogue(MediaKind.TV_SERIES, page, perProviderSize, effectiveGenre, sort, effectiveCountry, effectiveYear) }
        } else null

        val results = listOfNotNull(iaDeferred?.await(), tvDeferred?.await())
        val pages = results.mapNotNull { it.getOrNull() }
        if (pages.isEmpty()) {
            val err = mbFailure
                ?: results.firstNotNullOfOrNull { it.exceptionOrNull() }
                ?: IllegalStateException("Catalogue request failed")
            return@withContext Result.failure(err)
        }

        val items = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
            registerSummariesCanonical(
                interleaveLists(pages.map { it.items })
            )
        )
        Result.success(
            CataloguePage(
                items = items,
                page = page,
                pageSize = pageSize,
                totalCount = pages.sumOf { it.totalCount ?: it.items.size },
                hasMore = pages.any { it.hasMore },
                activeProviderNotice = mbFailure?.let(::buildPrimaryFallbackNotice)
            )
        )
    }

    private fun extractShortDramaCataloguePage(
        feed: MoviesHomeFeed,
        page: Int,
        pageSize: Int,
        genre: String?,
        sort: CatalogueSort,
        country: String?,
        year: String?
    ): CataloguePage {
        val safePage = page.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 50)
        val cleanYear = year?.trim()?.takeIf { Regex("^\\d{4}$").matches(it) }
        val filtered = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
            (feed.sections.flatMap { it.items } + feed.featuredBanners)
                .filter { it.kind == MediaKind.SHORT_DRAMA || it.subjectType == 7 }
        )
            .filter { item ->
                (genre == null || item.genres.any { g -> g.equals(genre, ignoreCase = true) }) &&
                    (country == null || item.country.equals(country, ignoreCase = true)) &&
                    (cleanYear == null || item.releaseYear?.toString() == cleanYear)
            }
        val sorted = when (sort) {
            CatalogueSort.POPULAR -> filtered
            CatalogueSort.TOP_RATED -> filtered.sortedByDescending { it.rating ?: 0.0 }
            CatalogueSort.NEWEST -> filtered.sortedByDescending { it.releaseDate ?: it.releaseYear?.toString().orEmpty() }
        }
        val fromIndex = (safePage - 1) * safePageSize
        val pageItems = if (fromIndex < sorted.size) {
            sorted.subList(fromIndex, minOf(fromIndex + safePageSize, sorted.size))
        } else {
            emptyList()
        }
        return CataloguePage(
            items = pageItems,
            page = safePage,
            pageSize = safePageSize,
            totalCount = sorted.size,
            hasMore = fromIndex + pageItems.size < sorted.size
        )
    }

    override suspend fun getGenres(): Result<List<String>> = withContext(ioDispatcher) {
        if (isDebugSampleFixturesEnabled()) {
            return@withContext fixtureProvider.getGenres()
        }
        val mbResult = activeMovieBox()?.getGenres()
        if (mbResult != null && mbResult.isSuccess) {
            return@withContext mbResult
        }
        val iaGenres = if (isInternetArchiveEnabled()) {
            internetArchiveProvider.getGenres().getOrDefault(emptyList())
        } else emptyList()
        val tvGenres = if (isTvMazeEnabled()) {
            tvMazeProvider.getGenres().getOrDefault(emptyList())
        } else emptyList()
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
            return@withContext Result.success(CataloguePage(emptyList(), page, pageSize, 0, false, 0))
        }
        if (isDebugSampleFixturesEnabled()) {
            return@withContext fixtureProvider.search(clean, kind, page, pageSize)
        }

        val queryMovies = kind == null || kind == MediaKind.MOVIE
        val querySeries = kind == null || kind == MediaKind.TV_SERIES
        val mbActive = activeMovieBox()
        val suppPerProviderSize = if (mbActive != null) {
            (pageSize / 2).coerceIn(6, 12)
        } else {
            if (kind == null) (pageSize / 2).coerceAtLeast(8) else pageSize
        }

        val mbDeferred = mbActive?.let { provider ->
            async<Result<CataloguePage>> {
                try {
                    provider.search(clean, kind, page, pageSize)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Result.failure(t)
                }
            }
        }
        val iaDeferred = if (queryMovies && isInternetArchiveEnabled()) {
            async<Result<CataloguePage>> {
                try {
                    internetArchiveProvider.search(clean, MediaKind.MOVIE, page, suppPerProviderSize)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Result.failure(t)
                }
            }
        } else null
        val tvDeferred = if (querySeries && isTvMazeEnabled()) {
            async<Result<CataloguePage>> {
                try {
                    tvMazeProvider.search(clean, MediaKind.TV_SERIES, page, suppPerProviderSize)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Result.failure(t)
                }
            }
        } else null

        val mbResult = mbDeferred?.await()
        val suppResults = listOfNotNull(iaDeferred?.await(), tvDeferred?.await())
        val suppPages = suppResults.mapNotNull { it.getOrNull() }

        if (mbResult != null && mbResult.isSuccess) {
            val mbPage = mbResult.getOrThrow()
            val mbItems = mbPage.items
            val rawSuppItems = interleaveLists(suppPages.map { it.items })
                .distinctBy { it.compositeId }

            val combinedRegistered = registerSummariesCanonical(mbItems + rawSuppItems)
            val registeredPrimary = combinedRegistered.take(mbItems.size)
            val primaryKeys = buildSet {
                registeredPrimary.forEach { item ->
                    add(item.compositeId)
                    add(item.stableMediaKey)
                    item.canonicalId?.let(::add)
                }
            }
            val primaryCandidates = registeredPrimary.map { MatchCandidate.fromSummary(it) }
            val filteredSupplementary = combinedRegistered
                .drop(mbItems.size)
                .filter { item ->
                    val hasKeyOverlap = item.compositeId in primaryKeys ||
                        item.stableMediaKey in primaryKeys ||
                        (item.canonicalId != null && item.canonicalId in primaryKeys)
                    if (hasKeyOverlap) return@filter false
                    val itemCand = MatchCandidate.fromSummary(item)
                    primaryCandidates.none { primaryCand -> MediaMatcher.evaluate(primaryCand, itemCand).isMatch }
                }
            val dedupedSupplementary = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(filteredSupplementary)

            val merged = registeredPrimary + dedupedSupplementary
            return@withContext Result.success(
                CataloguePage(
                    items = merged,
                    page = mbPage.page,
                    pageSize = mbPage.pageSize,
                    totalCount = (mbPage.totalCount ?: mbItems.size) + dedupedSupplementary.size,
                    hasMore = mbPage.hasMore || suppPages.any { it.hasMore },
                    primaryCount = registeredPrimary.size
                )
            )
        }

        if (suppPages.isEmpty()) {
            val err = mbResult?.exceptionOrNull()
                ?: suppResults.firstNotNullOfOrNull { it.exceptionOrNull() }
                ?: IllegalStateException("Search failed")
            return@withContext Result.failure(err)
        }

        val items = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
            registerSummariesCanonical(
                interleaveLists(suppPages.map { it.items }).distinctBy { it.compositeId }
            )
        )
        mbResult?.exceptionOrNull()?.let { err ->
            recordPrimaryFallback("search", err, screenId = "search")
        }
        Result.success(
            CataloguePage(
                items = items,
                page = page,
                pageSize = pageSize,
                totalCount = suppPages.sumOf { it.totalCount ?: it.items.size },
                hasMore = suppPages.any { it.hasMore },
                primaryCount = items.size,
                activeProviderNotice = mbResult?.exceptionOrNull()?.let(::buildPrimaryFallbackNotice)
            )
        )
    }

    override suspend fun getSuggestions(query: String, limit: Int): Result<List<SearchSuggestion>> = withContext(ioDispatcher) {
        val clean = query.trim()
        if (clean.length < 2) return@withContext Result.success(emptyList())
        if (isDebugSampleFixturesEnabled()) {
            return@withContext fixtureProvider.getSuggestions(clean, limit)
        }

        val mbResult = activeMovieBox()?.getSuggestions(clean, limit)
        if (mbResult != null && mbResult.isSuccess) {
            clearPrimaryFailure()
            return@withContext mbResult.map {
                MovieBoxPriorityDeduplicator.deduplicateSuggestionsPreferringMovieBox(it).take(limit)
            }
        }
        if (mbResult != null && mbResult.isFailure) {
            recordPrimaryFallback("suggest", mbResult.exceptionOrNull(), screenId = "search")
        }

        val iaDeferred = if (isInternetArchiveEnabled()) {
            async { internetArchiveProvider.getSuggestions(clean, limit) }
        } else null
        val tvDeferred = if (isTvMazeEnabled()) {
            async { tvMazeProvider.getSuggestions(clean, limit) }
        } else null

        val results = listOfNotNull(iaDeferred?.await(), tvDeferred?.await())
        val lists = results.mapNotNull { it.getOrNull() }
        if (lists.isEmpty()) {
            return@withContext Result.failure(
                results.firstNotNullOfOrNull { it.exceptionOrNull() } ?: IllegalStateException("Suggestions unavailable")
            )
        }
        val merged = MovieBoxPriorityDeduplicator
            .deduplicateSuggestionsPreferringMovieBox(interleaveLists(lists))
            .take(limit)
        Result.success(merged)
    }

    override suspend fun searchLibrarySuggestions(
        query: String,
        limit: Int
    ): List<LibrarySearchSuggestion> = withContext(ioDispatcher) {
        if (limit <= 0) return@withContext emptyList()
        val clean = query.trim()
        val normalizedQuery = clean.lowercase(Locale.US)

        val hiddenEntities = moviesDao.getHiddenTitlesSnapshot(200)
        val hiddenKeys = buildSet {
            hiddenEntities.forEach { hidden ->
                add(hidden.mediaKey)
                add(hidden.compositeId)
                add("${hidden.providerId}:${hidden.titleId}")
                hidden.canonicalId?.let(::add)
            }
        }

        val offlineKeys = buildSet {
            val assets = downloadedAssetDao?.observeAvailableOfflineMovieAssets()?.firstOrNull().orEmpty()
            assets.forEach { asset ->
                val isReachable = localMediaUriResolver == null ||
                    localMediaUriResolver.invoke(asset.localPath) != null
                if (!isReachable) return@forEach
                val parts = asset.sourceRef?.split("|") ?: return@forEach
                val pId = parts.getOrNull(0)?.trim().orEmpty()
                val tId = parts.getOrNull(1)?.trim().orEmpty()
                if (pId.isNotBlank() && tId.isNotBlank()) {
                    add("$pId:$tId")
                }
            }
        }

        data class Candidate(
            val summary: MovieTitleSummary,
            val sourceLabel: String,
            val timestamp: Long,
            val priority: Int
        )

        val candidates = mutableListOf<Candidate>()

        moviesDao.getWatchHistorySnapshot(50)
            .filter { !it.isTrailer && !it.isHiddenTitle }
            .forEach { entry ->
                val isContinueWatching = !entry.isCompleted && !entry.hiddenFromContinueWatching && entry.positionMs > 0L
                val cached = getCachedSummary(entry.providerId, entry.titleId)
                val summary = MovieTitleSummary(
                    id = entry.titleId,
                    providerId = entry.providerId,
                    kind = MediaKind.fromWire(entry.kind),
                    title = entry.title,
                    releaseYear = cached?.releaseYear,
                    rating = cached?.rating,
                    posterUrl = entry.posterUrl ?: cached?.posterUrl,
                    backdropUrl = entry.backdropUrl ?: cached?.backdropUrl,
                    genres = cached?.genres.orEmpty(),
                    sourceBadge = cached?.sourceBadge ?: when (entry.providerId) {
                        MovieBoxProvider.PROVIDER_ID -> DataSourceBadge.LIVE_MOVIEBOX
                        InternetArchiveMoviesProvider.PROVIDER_ID -> DataSourceBadge.LIVE_INTERNET_ARCHIVE
                        TvMazeSeriesProvider.PROVIDER_ID -> DataSourceBadge.LIVE_TVMAZE
                        TmdbOptionalMoviesProvider.PROVIDER_ID -> DataSourceBadge.LIVE_TMDB
                        DevelopmentMoviesFixtureProvider.PROVIDER_ID -> DataSourceBadge.SAMPLE_FIXTURE
                        else -> DataSourceBadge.LIVE_MOVIEBOX
                    },
                    isPlayableHint = true,
                    canonicalId = entry.canonicalId
                )
                candidates.add(
                    Candidate(
                        summary = summary,
                        sourceLabel = if (isContinueWatching) "Continue Watching" else "History",
                        timestamp = entry.lastWatchedAt,
                        priority = if (isContinueWatching) 4 else 2
                    )
                )
            }

        moviesDao.getWatchlistSnapshot().forEach { entry ->
            candidates.add(
                Candidate(
                    summary = entry.toDomain(),
                    sourceLabel = "Watchlist",
                    timestamp = entry.addedAt,
                    priority = 3
                )
            )
        }

        moviesDao.getCollectionMediaSnapshot(50).forEach { entry ->
            candidates.add(
                Candidate(
                    summary = entry.toDomain().summary,
                    sourceLabel = "Collection",
                    timestamp = entry.addedAt,
                    priority = 2
                )
            )
        }

        moviesDao.getRecentlyViewedSnapshot(30).forEach { entry ->
            candidates.add(
                Candidate(
                    summary = entry.toDomain(),
                    sourceLabel = "Recently Viewed",
                    timestamp = entry.viewedAt,
                    priority = 1
                )
            )
        }

        fun isHidden(summary: MovieTitleSummary): Boolean =
            summary.stableMediaKey in hiddenKeys ||
                summary.compositeId in hiddenKeys ||
                (summary.canonicalId != null && summary.canonicalId in hiddenKeys)

        val deduplicated = linkedMapOf<String, Candidate>()
        candidates
            .filterNot { isHidden(it.summary) }
            .sortedWith(compareByDescending<Candidate> { it.priority }.thenByDescending { it.timestamp })
            .forEach { candidate ->
                val key = candidate.summary.stableMediaKey
                val existing = deduplicated[key]
                if (existing == null || candidate.timestamp > existing.timestamp && candidate.priority >= existing.priority) {
                    deduplicated[key] = candidate
                }
            }

        val matching = if (normalizedQuery.isBlank()) {
            deduplicated.values.toList()
        } else {
            val queryTokens = normalizedQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
            deduplicated.values
                .mapNotNull { candidate ->
                    val lowerTitle = candidate.summary.title.lowercase(Locale.US)
                    val score = when {
                        lowerTitle.startsWith(normalizedQuery) -> 3
                        lowerTitle.contains(normalizedQuery) -> 2
                        queryTokens.size > 1 && queryTokens.all { lowerTitle.contains(it) } -> 1
                        else -> 0
                    }
                    if (score > 0) score to candidate else null
                }
                .sortedWith(
                    compareByDescending<Pair<Int, Candidate>> { it.first }
                        .thenByDescending { it.second.priority }
                        .thenByDescending { it.second.timestamp }
                )
                .map { it.second }
        }

        matching
            .take(limit)
            .map { candidate ->
                val summary = candidate.summary
                val isDownloaded = summary.compositeId in offlineKeys
                LibrarySearchSuggestion(
                    summary = summary,
                    sourceLabel = candidate.sourceLabel,
                    isDownloaded = isDownloaded
                )
            }
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
        val cachedSummarySnapshot = getCachedSummary(resolvedProviderId, resolvedTitleId)
        val cacheKey = "details:$resolvedProviderId:$resolvedTitleId"
        val cachedEntry = moviesDao.getCacheEntry(cacheKey)
        if (!forceRefresh && cachedEntry != null && cachedEntry.expiresAt > currentMs) {
            val decoded = withContext(defaultDispatcher) {
                MoviesCacheJsonCodec.decodeDetails(cachedEntry.payloadJson)
            }
            if (decoded != null) {
                val patchedSummary = decoded.summary.let { sum ->
                    var next = sum
                    if (next.trailerUrl.isNullOrBlank() && !cachedSummarySnapshot?.trailerUrl.isNullOrBlank()) {
                        next = next.copy(
                            trailerUrl = cachedSummarySnapshot?.trailerUrl,
                            trailerHeaders = cachedSummarySnapshot?.trailerHeaders.orEmpty()
                        )
                    }
                    if (next.kind == MediaKind.MOVIE && (next.durationMinutes ?: 0) <= 0 &&
                        (cachedSummarySnapshot?.durationMinutes ?: 0) > 0
                    ) {
                        next = next.copy(durationMinutes = cachedSummarySnapshot?.durationMinutes)
                    }
                    next
                }
                val resolvedSummary = mediaResolver.resolveOrRegister(
                    patchedSummary,
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
            val previousDecodedSummary = cachedEntry?.let { entry ->
                withContext(defaultDispatcher) { MoviesCacheJsonCodec.decodeDetails(entry.payloadJson) }
            }?.summary
            val previousDecodedDuration = previousDecodedSummary?.durationMinutes
            val cachedSummaryDuration = cachedSummarySnapshot?.durationMinutes
            val preservedDuration = if (rawDetails.summary.kind == MediaKind.MOVIE) {
                rawDetails.summary.durationMinutes?.takeIf { it > 0 }
                    ?: cachedSummaryDuration?.takeIf { it > 0 }
                    ?: previousDecodedDuration?.takeIf { it > 0 }
            } else {
                null
            }
            val preservedTrailerUrl = rawDetails.summary.trailerUrl?.takeIf { it.isNotBlank() }
                ?: cachedSummarySnapshot?.trailerUrl?.takeIf { it.isNotBlank() }
                ?: previousDecodedSummary?.trailerUrl?.takeIf { it.isNotBlank() }
            val preservedTrailerHeaders = when {
                !rawDetails.summary.trailerUrl.isNullOrBlank() -> rawDetails.summary.trailerHeaders
                !cachedSummarySnapshot?.trailerUrl.isNullOrBlank() -> cachedSummarySnapshot?.trailerHeaders.orEmpty()
                !previousDecodedSummary?.trailerUrl.isNullOrBlank() -> previousDecodedSummary?.trailerHeaders.orEmpty()
                else -> rawDetails.summary.trailerHeaders
            }
            val mergedSummary = rawDetails.summary.copy(
                durationMinutes = preservedDuration,
                trailerUrl = preservedTrailerUrl,
                trailerHeaders = preservedTrailerHeaders
            )
            val resolvedSummary = mediaResolver.resolveOrRegister(
                mergedSummary,
                seasonCount = rawDetails.seasons.size.takeIf { it > 0 }
            )
            val resolvedRelated = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
                registerSummariesCanonical(rawDetails.relatedTitles)
            )
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
                val resolvedRelated = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
                    registerSummariesCanonical(altDetails.relatedTitles)
                )
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
            MovieBoxProvider.PROVIDER_ID -> activeMovieBox()?.getDetails(titleId)
                ?: Result.failure(IllegalStateException("Streaming service is currently paused."))
            InternetArchiveMoviesProvider.PROVIDER_ID ->
                if (isInternetArchiveEnabled()) internetArchiveProvider.getDetails(titleId)
                else Result.failure(IllegalStateException("Internet Archive provider is currently paused."))
            TvMazeSeriesProvider.PROVIDER_ID ->
                if (isTvMazeEnabled()) tvMazeProvider.getDetails(titleId)
                else Result.failure(IllegalStateException("TVMaze provider is currently paused."))
            TmdbOptionalMoviesProvider.PROVIDER_ID ->
                if (isTmdbEnabled()) tmdbProvider.getDetails(titleId)
                else Result.failure(IllegalStateException("TMDB metadata is currently paused."))
            else -> Result.failure(IllegalArgumentException("Unknown provider '$providerId'"))
        }

    override suspend fun getPersonFilmography(
        providerId: String,
        personId: String,
        personName: String,
        personImageUrl: String?
    ): Result<PersonFilmography> = withContext(ioDispatcher) {
        val result = when (providerId) {
            MovieBoxProvider.PROVIDER_ID -> activeMovieBox()?.getPersonFilmography(personId, personName, personImageUrl)
                ?: Result.failure(IllegalStateException("Streaming service is currently paused."))
            TvMazeSeriesProvider.PROVIDER_ID ->
                if (isTvMazeEnabled()) tvMazeProvider.getPersonFilmography(personId, personName, personImageUrl)
                else Result.failure(IllegalStateException("TVMaze provider is currently paused."))
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
            MovieBoxProvider.PROVIDER_ID -> activeMovieBox()?.getSeasons(resolvedSeriesId)
                ?: Result.failure(IllegalStateException("Streaming service is currently paused."))
            TvMazeSeriesProvider.PROVIDER_ID ->
                if (isTvMazeEnabled()) tvMazeProvider.getSeasons(resolvedSeriesId)
                else Result.failure(IllegalStateException("TVMaze provider is currently paused."))
            TmdbOptionalMoviesProvider.PROVIDER_ID ->
                if (isTmdbEnabled()) tmdbProvider.getSeasons(resolvedSeriesId)
                else Result.failure(IllegalStateException("TMDB metadata is currently paused."))
            DevelopmentMoviesFixtureProvider.PROVIDER_ID -> fixtureProvider.getSeasons(resolvedSeriesId)
            else -> Result.success(emptyList())
        }
        if (primary.isSuccess) return@withContext primary
        if (resolvedProviderId == MovieBoxProvider.PROVIDER_ID && isMovieBoxEnabled()) {
            recordPrimaryFallback("seasons", primary.exceptionOrNull(), screenId = "details")
        }

        val linkedAlternates = mediaResolver.getLinksForTitle(resolvedProviderId, resolvedSeriesId)
            .filterNot { it.providerId == resolvedProviderId }
        for (alt in linkedAlternates) {
            if (alt.providerId == TvMazeSeriesProvider.PROVIDER_ID && isTvMazeEnabled()) {
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
            if (decoded != null) {
                if (resolvedProviderId == MovieBoxProvider.PROVIDER_ID &&
                    decoded.isNotEmpty() &&
                    decoded.any { it.stillUrl.isNullOrBlank() } &&
                    isTvMazeEnabled() &&
                    !isTvMazeStillInCooldown(resolvedSeriesId, seasonNumber, currentMs)
                ) {
                    val enriched = enrichEpisodesWithTvMazeStills(resolvedSeriesId, seasonNumber, decoded, currentMs)
                    if (enriched != decoded) {
                        val encoded = withContext(defaultDispatcher) {
                            MoviesCacheJsonCodec.encodeEpisodes(enriched)
                        }
                        moviesDao.upsertCacheEntry(
                            cachedEntry.copy(payloadJson = encoded, updatedAt = currentMs)
                        )
                        return@withContext Result.success(enriched)
                    }
                }
                return@withContext Result.success(decoded)
            }
        }

        val result = when (resolvedProviderId) {
            MovieBoxProvider.PROVIDER_ID -> activeMovieBox()?.getEpisodes(resolvedSeriesId, seasonNumber)
                ?: Result.failure(IllegalStateException("Streaming service is currently paused."))
            TvMazeSeriesProvider.PROVIDER_ID ->
                if (isTvMazeEnabled()) tvMazeProvider.getEpisodes(resolvedSeriesId, seasonNumber)
                else Result.failure(IllegalStateException("TVMaze provider is currently paused."))
            TmdbOptionalMoviesProvider.PROVIDER_ID ->
                if (isTmdbEnabled()) tmdbProvider.getEpisodes(resolvedSeriesId, seasonNumber)
                else Result.failure(IllegalStateException("TMDB metadata is currently paused."))
            else -> Result.success(emptyList())
        }

        if (result.isSuccess) {
            var episodes = result.getOrThrow()
            if (resolvedProviderId == MovieBoxProvider.PROVIDER_ID &&
                episodes.isNotEmpty() &&
                episodes.any { it.stillUrl.isNullOrBlank() }
            ) {
                val cachedEntryDetail = moviesDao.getCacheEntry("details:$resolvedProviderId:$resolvedSeriesId")
                    ?: moviesDao.getCacheEntry("detail:$resolvedProviderId:$resolvedSeriesId")
                val cachedImdb = cachedEntryDetail?.let {
                    withContext(defaultDispatcher) {
                        MoviesCacheJsonCodec.decodeDetails(it.payloadJson)?.summary?.imdbId
                    }
                }
                val enrichment = runCatching {
                    activeMovieBox()?.getSeasonEpisodeMetadata(
                        seriesId = resolvedSeriesId,
                        seasonNumber = seasonNumber,
                        imdbId = cachedImdb
                    ).orEmpty()
                }.getOrDefault(emptyMap())
                if (enrichment.isNotEmpty()) {
                    episodes = episodes.map { ep ->
                        val patch = enrichment[ep.episodeNumber] ?: return@map ep
                        val genericTitle = ep.title == "Episode ${ep.episodeNumber}"
                        ep.copy(
                            title = if (genericTitle && !patch.title.isNullOrBlank()) patch.title else ep.title,
                            overview = ep.overview ?: patch.overview,
                            runtimeMinutes = ep.runtimeMinutes ?: patch.runtimeMinutes,
                            airDate = ep.airDate ?: patch.airDate,
                            stillUrl = ep.stillUrl ?: patch.stillUrl
                        )
                    }
                }
                if (episodes.any { it.stillUrl.isNullOrBlank() } && isTvMazeEnabled()) {
                    episodes = enrichEpisodesWithTvMazeStills(
                        seriesId = resolvedSeriesId,
                        seasonNumber = seasonNumber,
                        episodes = episodes,
                        currentMs = currentMs,
                    )
                }
            }
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

        if (resolvedProviderId == MovieBoxProvider.PROVIDER_ID && isMovieBoxEnabled()) {
            recordPrimaryFallback("episodes", result.exceptionOrNull(), screenId = "details")
        }

        val linkedAlternates = mediaResolver.getLinksForTitle(resolvedProviderId, resolvedSeriesId)
            .filterNot { it.providerId == resolvedProviderId }
        for (alt in linkedAlternates) {
            if (alt.providerId == TvMazeSeriesProvider.PROVIDER_ID && isTvMazeEnabled()) {
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

    private fun isTvMazeStillInCooldown(seriesId: String, seasonNumber: Int, currentMs: Long): Boolean {
        val lastMiss = tvMazeStillCooldownBySeason["$seriesId:$seasonNumber"] ?: return false
        return (currentMs - lastMiss) in 0L until TVMAZE_STILL_ENRICH_COOLDOWN_MS
    }

    private suspend fun enrichEpisodesWithTvMazeStills(
        seriesId: String,
        seasonNumber: Int,
        episodes: List<EpisodeItem>,
        currentMs: Long,
    ): List<EpisodeItem> {
        if (episodes.isEmpty() || episodes.none { it.stillUrl.isNullOrBlank() } || !isTvMazeEnabled()) {
            return episodes
        }
        val cooldownKey = "$seriesId:$seasonNumber"
        if (isTvMazeStillInCooldown(seriesId, seasonNumber, currentMs)) {
            return episodes
        }

        val tvMazeShowId = resolveTvMazeShowIdForMovieBox(seriesId, currentMs)
        if (tvMazeShowId.isNullOrBlank()) {
            tvMazeStillCooldownBySeason[cooldownKey] = currentMs
            return episodes
        }

        val tvEpisodesCacheKey = "episodes:${TvMazeSeriesProvider.PROVIDER_ID}:$tvMazeShowId:$seasonNumber"
        val cachedTvEntry = moviesDao.getCacheEntry(tvEpisodesCacheKey)
        var tvEpisodes: List<EpisodeItem>? = if (cachedTvEntry != null && cachedTvEntry.expiresAt > currentMs) {
            withContext(defaultDispatcher) { MoviesCacheJsonCodec.decodeEpisodes(cachedTvEntry.payloadJson) }
        } else {
            null
        }

        if (tvEpisodes == null) {
            val fetched = runCatching { tvMazeProvider.getEpisodes(tvMazeShowId, seasonNumber) }
                .getOrNull()
                ?.getOrNull()
            if (fetched != null) {
                tvEpisodes = fetched
                val encoded = withContext(defaultDispatcher) { MoviesCacheJsonCodec.encodeEpisodes(fetched) }
                moviesDao.upsertCacheEntry(
                    MovieCacheEntryEntity(
                        cacheKey = tvEpisodesCacheKey,
                        providerId = TvMazeSeriesProvider.PROVIDER_ID,
                        payloadJson = encoded,
                        updatedAt = currentMs,
                        expiresAt = currentMs + CACHE_TTL_MS
                    )
                )
            } else {
                // Offline fallback to stale TVMaze episode cache if present
                tvEpisodes = cachedTvEntry?.let { entry ->
                    withContext(defaultDispatcher) { MoviesCacheJsonCodec.decodeEpisodes(entry.payloadJson) }
                }
            }
        }

        if (tvEpisodes.isNullOrEmpty()) {
            tvMazeStillCooldownBySeason[cooldownKey] = currentMs
            return episodes
        }

        val stillsBySeasonEpisode = HashMap<Pair<Int, Int>, String>(tvEpisodes.size)
        for (tvEp in tvEpisodes) {
            val url = tvEp.stillUrl?.trim()?.takeIf { it.isNotBlank() } ?: continue
            stillsBySeasonEpisode.putIfAbsent(tvEp.seasonNumber to tvEp.episodeNumber, url)
        }
        if (stillsBySeasonEpisode.isEmpty()) {
            tvMazeStillCooldownBySeason[cooldownKey] = currentMs
            return episodes
        }

        var patchedCount = 0
        val patched = episodes.map { ep ->
            if (!ep.stillUrl.isNullOrBlank()) {
                ep
            } else {
                val fillUrl = stillsBySeasonEpisode[ep.seasonNumber to ep.episodeNumber]
                if (!fillUrl.isNullOrBlank()) {
                    patchedCount++
                    ep.copy(stillUrl = fillUrl)
                } else {
                    ep
                }
            }
        }
        if (patchedCount == 0) {
            tvMazeStillCooldownBySeason[cooldownKey] = currentMs
        }
        return patched
    }

    private suspend fun resolveTvMazeShowIdForMovieBox(
        seriesId: String,
        currentMs: Long,
    ): String? {
        tvMazeShowIdByMovieBoxSeries[seriesId]?.let { return it }

        val linked = mediaResolver.getLinksForTitle(MovieBoxProvider.PROVIDER_ID, seriesId)
            .firstOrNull { it.providerId == TvMazeSeriesProvider.PROVIDER_ID }
            ?.providerTitleId
            ?.takeIf { it.isNotBlank() }
        if (linked != null) {
            tvMazeShowIdByMovieBoxSeries[seriesId] = linked
            return linked
        }

        val lastShowMiss = tvMazeShowLookupCooldown[seriesId]
        if (lastShowMiss != null && (currentMs - lastShowMiss) in 0L until TVMAZE_STILL_ENRICH_COOLDOWN_MS) {
            return null
        }

        val detailEntry = moviesDao.getCacheEntry("details:${MovieBoxProvider.PROVIDER_ID}:$seriesId")
            ?: moviesDao.getCacheEntry("detail:${MovieBoxProvider.PROVIDER_ID}:$seriesId")
        val cachedDetails = detailEntry?.let { entry ->
            withContext(defaultDispatcher) { MoviesCacheJsonCodec.decodeDetails(entry.payloadJson) }
        }
        val mbSummary = cachedDetails?.summary
            ?: getCachedSummary(MovieBoxProvider.PROVIDER_ID, seriesId)

        val directTvMazeId = mbSummary?.tvmazeId?.takeIf { it.isNotBlank() }
        if (directTvMazeId != null) {
            tvMazeShowIdByMovieBoxSeries[seriesId] = directTvMazeId
            return directTvMazeId
        }

        val cleanImdb = MediaMatcher.normalizeImdbId(mbSummary?.imdbId)
        if (cleanImdb != null) {
            val lookupRes = runCatching { tvMazeProvider.lookupShowByImdb(cleanImdb) }.getOrNull()?.getOrNull()
            if (lookupRes != null && lookupRes.id.isNotBlank()) {
                val linkedShow = if (mbSummary != null) {
                    mediaResolver.resolveOrRegister(
                        lookupRes.copy(canonicalId = mbSummary.canonicalId),
                        seasonCount = cachedDetails?.seasons?.size?.takeIf { it > 0 }
                    )
                } else {
                    lookupRes
                }
                tvMazeShowIdByMovieBoxSeries[seriesId] = linkedShow.id
                return linkedShow.id
            }
        }

        if (mbSummary == null || mbSummary.title.isBlank()) {
            tvMazeShowLookupCooldown[seriesId] = currentMs
            return null
        }

        val mbSeasonCount = cachedDetails?.seasons?.size?.takeIf { it > 0 }
        val mbCandidate = MatchCandidate.fromSummary(mbSummary, seasonCount = mbSeasonCount)
        val searchItems = runCatching {
            tvMazeProvider.search(mbSummary.title, MediaKind.TV_SERIES, page = 1, pageSize = 8)
        }.getOrNull()?.getOrNull()?.items.orEmpty()

        val normMbTitle = MediaMatcher.normalizeTitle(mbSummary.title)
        val shortlisted = searchItems.filter { cand ->
            MediaMatcher.normalizeTvMazeId(cand.id) != null && (
                (cleanImdb != null && MediaMatcher.normalizeImdbId(cand.imdbId) == cleanImdb) ||
                    MediaMatcher.normalizeTitle(cand.title) == normMbTitle
                )
        }.take(2)

        for (cand in shortlisted) {
            val quickEval = MediaMatcher.evaluate(mbCandidate, MatchCandidate.fromSummary(cand))
            if (quickEval.isMatch) {
                mediaResolver.resolveOrRegister(
                    cand.copy(canonicalId = mbSummary.canonicalId),
                    seasonCount = mbSeasonCount
                )
                tvMazeShowIdByMovieBoxSeries[seriesId] = cand.id
                return cand.id
            }
            val yearGapOk = mbSummary.releaseYear != null &&
                cand.releaseYear != null &&
                kotlin.math.abs(mbSummary.releaseYear - cand.releaseYear) <= 1
            if (yearGapOk && (mbSeasonCount != null || mbSummary.durationMinutes != null)) {
                val candDetails = runCatching { tvMazeProvider.getDetails(cand.id) }
                    .getOrNull()?.getOrNull()
                if (candDetails != null) {
                    val detailedCand = MatchCandidate.fromSummary(
                        candDetails.summary,
                        seasonCount = candDetails.seasons.size.takeIf { it > 0 }
                    )
                    if (MediaMatcher.evaluate(mbCandidate, detailedCand).isMatch) {
                        mediaResolver.resolveOrRegister(
                            candDetails.summary.copy(canonicalId = mbSummary.canonicalId),
                            seasonCount = candDetails.seasons.size.takeIf { it > 0 }
                        )
                        tvMazeShowIdByMovieBoxSeries[seriesId] = candDetails.summary.id
                        return candDetails.summary.id
                    }
                }
            }
        }

        tvMazeShowLookupCooldown[seriesId] = currentMs
        return null
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
                activeMovieBox()?.getSeasonEpisodeSizes(resolvedSeriesId, seasonNumber, episodeNumbers)
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
        val offlineMatches = findOfflineEpisodeAssets(resolvedProviderId, resolvedTitleId, seasonNumber, episodeNumber)
        val offlineSourcesWithMatch = offlineMatches.mapNotNull { match ->
            localMediaUriResolver?.invoke(match.localPath)?.let { fileUri ->
                match to PlaybackSource(
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
        val savedPos = getSavedPositionMs(resolvedProviderId, resolvedTitleId, seasonNumber, episodeNumber)

        if (offlineSourcesWithMatch.isNotEmpty()) {
            val primaryMatch = offlineSourcesWithMatch.first().first
            val offlineSources = offlineSourcesWithMatch.map { it.second }
            val offlineSubtitles = offlineSourcesWithMatch
                .flatMap { (match, _) ->
                    subtitleDownloadManager?.getOfflineSubtitleTracksForTitle(
                        providerId = resolvedProviderId,
                        titleId = resolvedTitleId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        assetId = match.assetId
                    ).orEmpty()
                }
                .distinctBy { "${it.languageCode.lowercase()}|${it.format.lowercase()}" }

            val cachedTitle = getCachedSummary(resolvedProviderId, resolvedTitleId)?.title
                ?: getCachedSummary(providerId, titleId)?.title
                ?: "Offline Playback"
            val heading = if (seasonNumber != null && episodeNumber != null) {
                "S${seasonNumber} · E${episodeNumber}"
            } else {
                null
            }
            return@withContext Result.success(
                PlaybackResolution.Available(
                    title = cachedTitle,
                    subtitleHeading = heading,
                    sources = offlineSources,
                    subtitles = offlineSubtitles,
                    startPositionMs = savedPos,
                    providerName = "Offline Library",
                    sourceBadge = when (resolvedProviderId) {
                        MovieBoxProvider.PROVIDER_ID -> DataSourceBadge.LIVE_MOVIEBOX
                        InternetArchiveMoviesProvider.PROVIDER_ID -> DataSourceBadge.LIVE_INTERNET_ARCHIVE
                        TvMazeSeriesProvider.PROVIDER_ID -> DataSourceBadge.LIVE_TVMAZE
                        else -> DataSourceBadge.LIVE_INTERNET_ARCHIVE
                    }
                )
            )
        }

        val resolveStartedMs = nowMs()
        val networkResult = when (resolvedProviderId) {
            MovieBoxProvider.PROVIDER_ID ->
                activeMovieBox()?.resolvePlaybackSources(resolvedTitleId, seasonNumber, episodeNumber)
                    ?: Result.failure(IllegalStateException("Streaming service is currently paused. Downloaded local files remain playable offline."))
            InternetArchiveMoviesProvider.PROVIDER_ID ->
                if (isInternetArchiveEnabled()) {
                    internetArchiveProvider.resolvePlaybackSources(resolvedTitleId, seasonNumber, episodeNumber)
                } else {
                    Result.failure(IllegalStateException("Internet Archive provider is currently paused."))
                }
            TvMazeSeriesProvider.PROVIDER_ID ->
                if (isTvMazeEnabled()) {
                    tvMazeProvider.resolvePlaybackSources(resolvedTitleId, seasonNumber, episodeNumber)
                } else {
                    Result.failure(IllegalStateException("TVMaze provider is currently paused."))
                }
            TmdbOptionalMoviesProvider.PROVIDER_ID ->
                if (isTmdbEnabled()) {
                    tmdbProvider.resolvePlaybackSources(resolvedTitleId, seasonNumber, episodeNumber)
                } else {
                    Result.failure(IllegalStateException("TMDB metadata is currently paused."))
                }
            DevelopmentMoviesFixtureProvider.PROVIDER_ID ->
                fixtureProvider.resolvePlaybackSources(resolvedTitleId, seasonNumber, episodeNumber)
            else -> Result.failure(IllegalArgumentException("Unknown provider '$resolvedProviderId'"))
        }

        val resolveDurationMs = (nowMs() - resolveStartedMs).coerceIn(0L, 600_000L).toInt()
        networkResult.fold(
            onSuccess = { resolution ->
                when (resolution) {
                    is PlaybackResolution.Available -> {
                        onAnalyticsPerformance?.invoke("stream_resolve_ms", resolveDurationMs, "player")
                        Result.success(resolution.copy(startPositionMs = savedPos))
                    }
                    is PlaybackResolution.Unavailable -> {
                        onAnalyticsError?.invoke("stream_resolution_failed", "player")
                        Result.success(resolution)
                    }
                }
            },
            onFailure = { error ->
                onAnalyticsError?.invoke("stream_resolution_failed", "player")
                Result.failure(error)
            },
        )
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
                activeMovieBox()?.getSubtitles(resolvedTitleId, seasonNumber, episodeNumber)
                    ?: Result.success(emptyList())
            InternetArchiveMoviesProvider.PROVIDER_ID ->
                if (isInternetArchiveEnabled()) {
                    internetArchiveProvider.getSubtitles(resolvedTitleId, seasonNumber, episodeNumber)
                } else {
                    Result.success(emptyList())
                }
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

        val dlProgressDao = downloadPlaybackProgressDao
        if (dlProgressDao != null) {
            val progressKey = com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressKey.forMediaItem(
                providerId = resolved.providerId,
                titleId = resolved.id,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber
            )
            val matchingAsset = findOfflineEpisodeAsset(resolved.providerId, resolved.id, seasonNumber, episodeNumber)
            dlProgressDao.upsert(
                com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressEntity(
                    progressKey = progressKey,
                    assetId = matchingAsset?.assetId ?: resolved.compositeId,
                    sourceRef = matchingAsset?.sourceRef,
                    positionMs = cleanPos,
                    durationMs = cleanDur,
                    updatedAt = now
                )
            )
        }
    }

    override suspend fun getSavedPositionMs(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Long = withContext(ioDispatcher) {
        val (resolvedProviderId, resolvedTitleId) = mediaResolver.resolveConcreteTarget(providerId, titleId, preferPlayable = false)
        val key = buildHistoryKey(resolvedProviderId, resolvedTitleId, seasonNumber, episodeNumber)
        val historyEntry = moviesDao.getWatchHistoryByKey(key) ?: run {
            val cid = mediaResolver.resolveCanonicalIdForProvider(providerId, titleId)
            if (cid != null) {
                moviesDao.getWatchHistoryByCanonical(cid, seasonNumber ?: 0, episodeNumber ?: 0)
            } else null
        }
        val dlEntry = downloadPlaybackProgressDao?.let { dao ->
            val dlKey = com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressKey.forMediaItem(
                providerId = resolvedProviderId,
                titleId = resolvedTitleId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber
            )
            dao.getByKey(dlKey) ?: if (resolvedProviderId != providerId || resolvedTitleId != titleId) {
                dao.getByKey(
                    com.laterbox.app.media.data.db.entity.DownloadPlaybackProgressKey.forMediaItem(
                        providerId = providerId,
                        titleId = titleId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber
                    )
                )
            } else null
        }

        val bestPair = when {
            dlEntry != null && (historyEntry == null || dlEntry.updatedAt > historyEntry.lastWatchedAt) ->
                dlEntry.positionMs to dlEntry.durationMs
            historyEntry != null ->
                historyEntry.positionMs to historyEntry.durationMs
            else -> return@withContext 0L
        }
        val (pos, dur) = bestPair
        // Start from beginning if user reached >= 96% completion
        if (dur > 0L && pos.toFloat() / dur.toFloat() >= 0.96f) {
            return@withContext 0L
        }
        pos
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

    override suspend fun markSeriesWatched(
        item: MovieTitleSummary,
        seasons: List<SeasonSummary>,
        episodesBySeason: Map<Int, List<EpisodeItem>>,
        isRewatch: Boolean
    ): Int = withContext(ioDispatcher) {
        val resolved = if (item.canonicalId != null) item else mediaResolver.resolveOrRegister(item)
        cacheSummaries(listOf(resolved))

        data class EpisodeTarget(
            val seasonNumber: Int,
            val episodeNumber: Int,
            val durationMs: Long
        )

        val targets = mutableListOf<EpisodeTarget>()
        val sortedSeasons = seasons.sortedBy { it.seasonNumber }
        if (sortedSeasons.isNotEmpty()) {
            for (season in sortedSeasons) {
                val loaded = episodesBySeason[season.seasonNumber]?.takeIf { it.isNotEmpty() }
                    ?: runCatching {
                        getEpisodes(resolved.providerId, resolved.id, season.seasonNumber).getOrNull()
                    }.getOrNull()?.takeIf { it.isNotEmpty() }
                if (loaded != null) {
                    loaded.sortedBy { it.episodeNumber }.forEach { ep ->
                        targets.add(
                            EpisodeTarget(
                                seasonNumber = season.seasonNumber,
                                episodeNumber = ep.episodeNumber,
                                durationMs = (ep.runtimeMinutes ?: 45) * 60_000L
                            )
                        )
                    }
                } else {
                    val knownNumbers = when {
                        season.availableEpisodes.isNotEmpty() ->
                            season.availableEpisodes.filter { it > 0 }.distinct().sorted()
                        season.episodeCount > 0 ->
                            (1..season.episodeCount).toList()
                        else -> emptyList()
                    }
                    knownNumbers.forEach { epNum ->
                        targets.add(
                            EpisodeTarget(
                                seasonNumber = season.seasonNumber,
                                episodeNumber = epNum,
                                durationMs = 45 * 60_000L
                            )
                        )
                    }
                }
            }
        } else if (episodesBySeason.isNotEmpty()) {
            for ((seasonNum, eps) in episodesBySeason.toSortedMap()) {
                eps.sortedBy { it.episodeNumber }.forEach { ep ->
                    targets.add(
                        EpisodeTarget(
                            seasonNumber = seasonNum,
                            episodeNumber = ep.episodeNumber,
                            durationMs = (ep.runtimeMinutes ?: 45) * 60_000L
                        )
                    )
                }
            }
        }

        if (targets.isEmpty()) return@withContext 0

        val existingList = moviesDao.getHistoryListForTitle(resolved.compositeId, resolved.canonicalId)
        val existingByEp = existingList.mapNotNull { entry ->
            val se = entry.seasonNumber ?: return@mapNotNull null
            val ep = entry.episodeNumber ?: return@mapNotNull null
            (se to ep) to entry
        }.toMap()
        val now = nowMs()

        targets.forEach { target ->
            val key = buildHistoryKey(resolved.providerId, resolved.id, target.seasonNumber, target.episodeNumber)
            val existing = existingByEp[target.seasonNumber to target.episodeNumber]
                ?: moviesDao.getWatchHistoryByKey(key)
            val effectiveDuration = when {
                target.durationMs > 0L -> target.durationMs
                existing != null && existing.durationMs > 0L -> existing.durationMs
                else -> 45 * 60_000L
            }
            val alreadyCompleted = existing?.isCompleted == true
            val newRewatchCount = when {
                isRewatch -> (existing?.rewatchCount ?: 0) + 1
                else -> existing?.rewatchCount ?: 0
            }
            if (!isRewatch && alreadyCompleted) {
                return@forEach
            }
            moviesDao.upsertWatchHistory(
                MovieWatchHistoryEntity(
                    historyKey = existing?.historyKey ?: key,
                    compositeId = existing?.compositeId ?: resolved.compositeId,
                    providerId = resolved.providerId,
                    titleId = resolved.id,
                    kind = resolved.kind.wireValue,
                    title = resolved.title,
                    episodeLabel = "S${target.seasonNumber} · E${target.episodeNumber}",
                    seasonNumber = target.seasonNumber,
                    episodeNumber = target.episodeNumber,
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
        targets.size
    }

    override suspend fun markUnwatched(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ) = withContext(ioDispatcher) {
        val (resolvedProviderId, resolvedTitleId) = mediaResolver.resolveConcreteTarget(providerId, titleId, preferPlayable = false)
        val cid = mediaResolver.resolveCanonicalIdForProvider(providerId, titleId)
            ?: mediaResolver.resolveCanonicalIdForProvider(resolvedProviderId, resolvedTitleId)
        if (seasonNumber == null && episodeNumber == null) {
            val resolvedComposite = "$resolvedProviderId:$resolvedTitleId"
            val rawComposite = "$providerId:$titleId"
            moviesDao.deleteAllWatchHistoryForTitle(resolvedComposite, cid)
            if (rawComposite != resolvedComposite) {
                moviesDao.deleteAllWatchHistoryForTitle(rawComposite, cid)
            }
            return@withContext
        }
        val key = buildHistoryKey(resolvedProviderId, resolvedTitleId, seasonNumber, episodeNumber)
        val existing = moviesDao.getWatchHistoryByKey(key)
            ?: cid?.let {
                moviesDao.getWatchHistoryByCanonical(it, seasonNumber ?: 0, episodeNumber ?: 0)
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
                val isReachable = localMediaUriResolver == null ||
                    localMediaUriResolver.invoke(asset.localPath) != null
                if (!isReachable) return@mapNotNull null
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
        }.flowOn(ioDispatcher)
    }

    private suspend fun findOfflineEpisodeAssets(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): List<OfflineMediaMatch> = withContext(ioDispatcher) {
        val dao = downloadedAssetDao ?: return@withContext emptyList()
        val se = seasonNumber ?: 0
        val ep = episodeNumber ?: 0
        val linked = mediaResolver.getLinksForTitle(providerId, titleId)
        val candidatePairs = buildList {
            add(providerId to titleId)
            linked.forEach { link -> add(link.providerId to link.providerTitleId) }
        }.distinct()

        val allVerified = mutableListOf<Pair<com.laterbox.app.media.data.db.entity.DownloadedAssetEntity, OfflineMediaMatch>>()
        for ((pId, tId) in candidatePairs) {
            val prefix = "${pId.trim()}|${tId.trim()}|$se|$ep|"
            val matches = dao.findAvailableBySourceRefPrefix(prefix)
            matches.forEach { asset ->
                val isReachable = localMediaUriResolver == null ||
                    localMediaUriResolver.invoke(asset.localPath) != null
                if (!isReachable) return@forEach
                val ref = asset.sourceRef.orEmpty()
                val parts = ref.split("|")
                val quality = asset.qualityLabel ?: parts.getOrNull(4)?.takeIf { it.isNotBlank() }
                allVerified.add(
                    asset to OfflineMediaMatch(
                        assetId = asset.id,
                        sourceRef = ref,
                        localPath = asset.localPath,
                        qualityLabel = quality,
                        fileSizeBytes = asset.fileSize,
                        seasonNumber = se.takeIf { it > 0 },
                        episodeNumber = ep.takeIf { it > 0 }
                    )
                )
            }
        }
        allVerified
            .distinctBy { it.first.id }
            .sortedWith(
                compareByDescending<Pair<com.laterbox.app.media.data.db.entity.DownloadedAssetEntity, OfflineMediaMatch>> {
                    parseHeightFromQuality(it.second.qualityLabel)
                }.thenByDescending { it.first.createdAt }
            )
            .map { it.second }
    }

    override suspend fun findOfflineEpisodeAsset(
        providerId: String,
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): OfflineMediaMatch? = findOfflineEpisodeAssets(providerId, titleId, seasonNumber, episodeNumber).firstOrNull()

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
        moviesDao.observeRecentSearches((limit * 2).coerceAtLeast(limit))
            .map { queries ->
                queries
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase(Locale.US) }
                    .take(limit)
            }
            .flowOn(ioDispatcher)

    override suspend fun recordSearchQuery(query: String) = withContext(ioDispatcher) {
        val clean = query.trim().replace(Regex("\\s+"), " ")
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
        val registeredBanners = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
            registerSummariesCanonical(feed.featuredBanners)
        )
        val registeredSections = feed.sections.map { section ->
            section.copy(
                items = MovieBoxPriorityDeduplicator.deduplicateSummariesPreferringMovieBox(
                    registerSummariesCanonical(section.items)
                )
            )
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

    private fun buildPrimaryFallbackNotice(error: Throwable?): String {
        val apiErr = error as? MovieBoxApiException
        return when {
            apiErr?.failureCategory == MovieBoxFailureCategory.UPGRADE_REQUIRED ->
                "Please update Laterbox to continue using the main catalogue — showing archive & TV guide"
            apiErr?.failureCategory == MovieBoxFailureCategory.LOCAL_AUTH_UNAVAILABLE ||
                apiErr?.failureCategory == MovieBoxFailureCategory.BACKEND_AUTH_REJECTED ||
                apiErr?.isAuthFailure == true ->
                "Access verification is temporarily unavailable — showing archive & TV guide"
            apiErr?.failureCategory == MovieBoxFailureCategory.RATE_LIMITED ||
                apiErr?.isRateLimited == true ->
                "Main catalogue is temporarily busy — showing archive & TV guide"
            else -> MOVIEBOX_FALLBACK_NOTICE
        }
    }

    companion object {
        const val CACHE_TTL_MS = 6L * 60 * 60 * 1000L
        const val STALE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000L
        const val DURATION_MISS_COOLDOWN_MS = 15L * 60 * 1000L
        const val TVMAZE_STILL_ENRICH_COOLDOWN_MS = 10L * 60 * 1000L
        const val MOVIEBOX_FALLBACK_NOTICE =
            "Main catalogue temporarily unavailable — showing archive & TV guide"
    }
}
