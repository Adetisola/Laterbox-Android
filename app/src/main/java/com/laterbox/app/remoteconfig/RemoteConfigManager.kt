package com.laterbox.app.remoteconfig

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RemoteConfigManager(
    private val repository: RemoteConfigRepository,
    private val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val foregroundPollIntervalMs: Long = DEFAULT_FOREGROUND_POLL_INTERVAL_MS,
) {
    val state: StateFlow<RemoteConfigState> = repository.state

    val currentConfig: RemoteConfigSnapshot
        get() = state.value.activeConfig

    val dismissedAnnouncementIds: StateFlow<Set<String>> = repository.dismissedAnnouncementIdsFlow
        .stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = emptySet(),
        )

    @Volatile
    private var isForegrounded: Boolean = false
    private val pollLock = Any()
    private var foregroundPollingJob: Job? = null

    fun initializeNonBlocking(startupNetworkDelayMs: Long = DEFAULT_STARTUP_NETWORK_DELAY_MS) {
        applicationScope.launch(Dispatchers.IO) {
            runCatching { repository.loadFromCache() }
            if (startupNetworkDelayMs > 0L) {
                delay(startupNetworkDelayMs)
            }
            runCatching { repository.refreshIfNeeded(forceRefresh = false, bypassThrottle = true) }
        }
    }

    fun onAppForegrounded() {
        isForegrounded = true
        ensureForegroundPollingLoop()
        applicationScope.launch(Dispatchers.IO) {
            runCatching { repository.refreshIfNeeded(forceRefresh = false, bypassThrottle = true) }
        }
    }

    fun onAppBackgrounded() {
        isForegrounded = false
        synchronized(pollLock) {
            foregroundPollingJob?.cancel()
            foregroundPollingJob = null
        }
    }

    private fun ensureForegroundPollingLoop() {
        synchronized(pollLock) {
            if (foregroundPollingJob?.isActive == true) {
                return
            }
            val pollIntervalMs = foregroundPollIntervalMs.coerceAtLeast(10L)
            foregroundPollingJob = applicationScope.launch(Dispatchers.IO) {
                while (isActive && isForegrounded) {
                    delay(pollIntervalMs)
                    if (!isActive || !isForegrounded) break
                    runCatching { repository.refreshIfNeeded(forceRefresh = false, bypassThrottle = true) }
                }
            }
        }
    }

    suspend fun refreshNow(): RemoteConfigState {
        return repository.refreshIfNeeded(forceRefresh = true, bypassThrottle = true)
    }

    fun isFeatureEnabled(featureKey: String, defaultValue: Boolean = true): Boolean {
        return currentConfig.isFeatureEnabled(featureKey, defaultValue)
    }

    fun dismissAnnouncement(id: String) {
        applicationScope.launch(Dispatchers.IO) {
            runCatching { repository.dismissAnnouncement(id) }
        }
    }

    companion object {
        const val DEFAULT_STARTUP_NETWORK_DELAY_MS = 150L
        const val DEFAULT_FOREGROUND_POLL_INTERVAL_MS = 60_000L
    }
}
