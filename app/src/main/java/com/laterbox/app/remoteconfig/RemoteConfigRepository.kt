package com.laterbox.app.remoteconfig

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RemoteConfigRepository(
    private val cache: RemoteConfigCache,
    private val client: RemoteConfigClient,
    private val clientVersionCode: Int,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val versionGate: LaterboxVersionGate? = null,
) {
    private val refreshMutex = Mutex()
    private val _state = MutableStateFlow(RemoteConfigState())
    val state: StateFlow<RemoteConfigState> = _state.asStateFlow()

    val dismissedAnnouncementIdsFlow: Flow<Set<String>> = cache.dismissedAnnouncementIdsFlow

    suspend fun loadFromCache(): RemoteConfigSnapshot? {
        val now = clockMs()
        val cached = runCatching { cache.readCachedConfig(clientVersionCode, now) }.getOrNull()
        val lastAttempt = runCatching { cache.readLastAttemptEpochMs() }.getOrDefault(0L)
        if (cached != null) {
            _state.value = _state.value.copy(
                activeConfig = cached,
                lastFetchAttemptEpochMs = maxOf(_state.value.lastFetchAttemptEpochMs, lastAttempt),
                lastSuccessfulFetchEpochMs = maxOf(_state.value.lastSuccessfulFetchEpochMs, cached.fetchedAtEpochMs),
                lastError = null,
            )
        } else {
            // Preserve current default snapshot if cache is missing/corrupt/expired
            val current = _state.value.activeConfig
            if (current.source == RemoteConfigSource.CACHE && (current.isStale(now) || current.isExpired(now))) {
                _state.value = _state.value.copy(activeConfig = RemoteConfigSnapshot.default(now))
            }
        }
        return cached
    }

    suspend fun refreshIfNeeded(
        forceRefresh: Boolean = false,
        bypassThrottle: Boolean = false,
    ): RemoteConfigState {
        return refreshMutex.withLock {
            val now = clockMs()
            val currentState = _state.value
            val currentConfig = currentState.activeConfig

            // Ensure cache is checked at least once before deciding throttle/ETag
            val effectiveConfig = if (currentConfig.source == RemoteConfigSource.DEFAULT && currentConfig.fetchedAtEpochMs == 0L) {
                loadFromCache() ?: currentConfig
            } else {
                currentConfig
            }
            val latestState = _state.value

            if (!forceRefresh) {
                // Respect failure backoff window (10s -> 30s -> 60s) without blocking 900s on transient errors
                if (latestState.consecutiveFailures > 0) {
                    if (now < latestState.nextRetryAtEpochMs) {
                        return@withLock latestState
                    }
                } else {
                    val lastSuccessMs = latestState.lastSuccessfulFetchEpochMs
                    val effectiveMinIntervalMs = if (bypassThrottle) {
                        MIN_BYPASS_DEDUP_MS
                    } else {
                        effectiveConfig.general.minRefreshIntervalSeconds.coerceIn(60, 86400) * 1000L
                    }
                    if (lastSuccessMs > 0L && (now - lastSuccessMs) < effectiveMinIntervalMs) {
                        return@withLock latestState
                    }
                }
            }

            _state.value = latestState.copy(isRefreshing = true)
            runCatching { cache.recordFetchAttempt(now) }

            val cachedEtag = runCatching { cache.readEtag() }.getOrNull() ?: effectiveConfig.etag
            val nextState = when (val outcome = client.fetchPublishedConfig(cachedEtag, forceRefresh = forceRefresh)) {
                is RemoteConfigFetchOutcome.Updated -> {
                    val fetchedMs = outcome.snapshot.fetchedAtEpochMs.takeIf { it > 0L } ?: now
                    runCatching {
                        cache.saveCachedConfig(
                            rawJson = outcome.rawJson,
                            etag = outcome.etag,
                            fetchedAtEpochMs = fetchedMs,
                        )
                    }
                    versionGate?.syncWithPublishedConfig(outcome.snapshot)
                    _state.value.copy(
                        activeConfig = outcome.snapshot,
                        isRefreshing = false,
                        lastFetchAttemptEpochMs = now,
                        lastSuccessfulFetchEpochMs = now,
                        lastError = null,
                        consecutiveFailures = 0,
                        nextRetryAtEpochMs = 0L,
                    )
                }

                is RemoteConfigFetchOutcome.NotModified -> {
                    val refreshedSnapshot = if (effectiveConfig.source == RemoteConfigSource.DEFAULT) {
                        RemoteConfigSnapshot.default(now)
                    } else {
                        effectiveConfig.copy(
                            etag = outcome.etag ?: effectiveConfig.etag,
                            fetchedAtEpochMs = now,
                            source = RemoteConfigSource.NETWORK,
                        )
                    }
                    runCatching { cache.updateFetchTimestamp(now, outcome.etag) }
                    versionGate?.syncWithPublishedConfig(refreshedSnapshot)
                    _state.value.copy(
                        activeConfig = refreshedSnapshot,
                        isRefreshing = false,
                        lastFetchAttemptEpochMs = now,
                        lastSuccessfulFetchEpochMs = now,
                        lastError = null,
                        consecutiveFailures = 0,
                        nextRetryAtEpochMs = 0L,
                    )
                }

                is RemoteConfigFetchOutcome.Failed -> {
                    // Fail safely: retain cached valid snapshot unless it has become expired/stale
                    val retained = if (effectiveConfig.source != RemoteConfigSource.DEFAULT &&
                        (effectiveConfig.isExpired(now) || effectiveConfig.isStale(now))
                    ) {
                        RemoteConfigSnapshot.default(now)
                    } else {
                        effectiveConfig
                    }
                    val failures = latestState.consecutiveFailures + 1
                    val backoffMs = FAILURE_BACKOFF_MS.getOrElse(failures - 1) { FAILURE_BACKOFF_MS.last() }
                    _state.value.copy(
                        activeConfig = retained,
                        isRefreshing = false,
                        lastFetchAttemptEpochMs = now,
                        lastError = outcome.message,
                        consecutiveFailures = failures,
                        nextRetryAtEpochMs = now + backoffMs,
                    )
                }
            }
            _state.value = nextState
            nextState
        }
    }

    suspend fun dismissAnnouncement(id: String) {
        cache.dismissAnnouncement(id)
    }

    companion object {
        private const val MIN_BYPASS_DEDUP_MS = 5_000L
        private val FAILURE_BACKOFF_MS = longArrayOf(10_000L, 30_000L, 60_000L)
    }
}
