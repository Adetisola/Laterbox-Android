package com.laterbox.app.remoteconfig

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RemoteConfigManager(
    private val repository: RemoteConfigRepository,
    private val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
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

    fun initializeNonBlocking(startupNetworkDelayMs: Long = 1_500L) {
        applicationScope.launch(Dispatchers.IO) {
            runCatching { repository.loadFromCache() }
            if (startupNetworkDelayMs > 0L) {
                delay(startupNetworkDelayMs)
            }
            runCatching { repository.refreshIfNeeded(forceRefresh = false) }
        }
    }

    fun onAppForegrounded() {
        applicationScope.launch(Dispatchers.IO) {
            runCatching { repository.refreshIfNeeded(forceRefresh = false) }
        }
    }

    suspend fun refreshNow(): RemoteConfigState {
        return repository.refreshIfNeeded(forceRefresh = true)
    }

    fun isFeatureEnabled(featureKey: String, defaultValue: Boolean = true): Boolean {
        return currentConfig.isFeatureEnabled(featureKey, defaultValue)
    }

    fun dismissAnnouncement(id: String) {
        applicationScope.launch(Dispatchers.IO) {
            runCatching { repository.dismissAnnouncement(id) }
        }
    }
}
