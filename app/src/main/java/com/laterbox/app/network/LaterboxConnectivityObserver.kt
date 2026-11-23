package com.laterbox.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.annotation.VisibleForTesting
import com.laterbox.app.ui.components.LaterboxToastController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates the global persistent "You're offline" toast based strictly on OS connectivity
 * validation (never on transient API errors or slow networks).
 * - Dismissing manually suppresses the offline toast for the remainder of the current outage.
 * - Reconnecting clears the toast and re-arms it for any future offline episode.
 */
class OfflineToastStateTracker(
    private val toastController: LaterboxToastController
) {
    private val lock = Any()
    private var dismissedForCurrentOutage: Boolean = false
    private val _isValidatedOnline = MutableStateFlow(true)
    val isValidatedOnline: StateFlow<Boolean> = _isValidatedOnline.asStateFlow()

    val isSuppressedForCurrentOutage: Boolean
        get() = synchronized(lock) { dismissedForCurrentOutage }

    fun onConnectivityEvaluated(validatedOnline: Boolean) {
        _isValidatedOnline.value = validatedOnline
        if (validatedOnline) {
            synchronized(lock) {
                dismissedForCurrentOutage = false
            }
            toastController.dismissByDedupeKey(
                dedupeKey = LaterboxToastController.OFFLINE_DEDUPE_KEY,
                invokeDismissCallback = false
            )
        } else {
            val shouldShow = synchronized(lock) { !dismissedForCurrentOutage }
            if (shouldShow) {
                toastController.showOffline(
                    message = "You're offline",
                    onManualDismiss = {
                        synchronized(lock) {
                            dismissedForCurrentOutage = true
                        }
                    }
                )
            }
        }
    }
}

class LaterboxConnectivityObserver(
    private val context: Context,
    toastController: LaterboxToastController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val offlineGracePeriodMs: Long = DEFAULT_OFFLINE_GRACE_PERIOD_MS
) {
    val tracker = OfflineToastStateTracker(toastController)

    private val stateLock = Any()
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var started = false
    private var currentDefaultNetwork: Network? = null
    private var currentCapabilities: NetworkCapabilities? = null
    private var isBlocked = false
    private var pendingOfflineJob: Job? = null

    fun start() {
        if (started) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val callback = createDefaultNetworkCallback(cm)
        networkCallback = callback
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            started = true
        }
        refresh(immediateOfflineWhenDisconnected = true)
    }

    /**
     * Re-evaluates the current active default network (for example when returning to foreground
     * after connectivity or airplane-mode changes happened while backgrounded).
     */
    fun refresh(immediateOfflineWhenDisconnected: Boolean = true) {
        val cm = connectivityManager
            ?: (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?: return
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val onlineNow = synchronized(stateLock) {
            if (active != currentDefaultNetwork) {
                currentDefaultNetwork = active
                isBlocked = false
            }
            currentCapabilities = caps
            evaluateLiveOnlineLocked(cm)
        }
        if (onlineNow) {
            publishOnlineImmediately()
        } else if (immediateOfflineWhenDisconnected && active == null) {
            publishOfflineImmediately()
        } else {
            scheduleOfflineCheck(cm)
        }
    }

    fun stop() {
        val cm = connectivityManager
        val cb = networkCallback
        synchronized(stateLock) {
            pendingOfflineJob?.cancel()
            pendingOfflineJob = null
            currentDefaultNetwork = null
            currentCapabilities = null
            isBlocked = false
        }
        if (cm != null && cb != null) {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        started = false
    }

    @VisibleForTesting
    internal fun createDefaultNetworkCallback(cm: ConnectivityManager): ConnectivityManager.NetworkCallback {
        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val validatedNow = synchronized(stateLock) {
                    currentDefaultNetwork = network
                    isBlocked = false
                    currentCapabilities = cm.getNetworkCapabilities(network)
                    evaluateLiveOnlineLocked(cm)
                }
                if (validatedNow) {
                    publishOnlineImmediately()
                } else {
                    scheduleOfflineCheck(cm)
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val (matchesActiveOrDefault, validatedNow) = synchronized(stateLock) {
                    currentDefaultNetwork = network
                    currentCapabilities = networkCapabilities
                    val active = cm.activeNetwork
                    val relevant = active == null || active == network
                    val validated = if (relevant) {
                        !isBlocked && isCapabilitiesValidatedOnline(networkCapabilities)
                    } else {
                        evaluateLiveOnlineLocked(cm)
                    }
                    relevant to validated
                }
                if (!matchesActiveOrDefault) return
                if (validatedNow) {
                    publishOnlineImmediately()
                } else {
                    scheduleOfflineCheck(cm)
                }
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                val validatedNow = synchronized(stateLock) {
                    if (currentDefaultNetwork == null || currentDefaultNetwork == network) {
                        isBlocked = blocked
                    }
                    evaluateLiveOnlineLocked(cm)
                }
                if (validatedNow) {
                    publishOnlineImmediately()
                } else {
                    scheduleOfflineCheck(cm)
                }
            }

            override fun onLost(network: Network) {
                val validatedNow = synchronized(stateLock) {
                    if (currentDefaultNetwork == network) {
                        currentDefaultNetwork = null
                        currentCapabilities = null
                        isBlocked = false
                    }
                    evaluateLiveOnlineLocked(cm)
                }
                if (validatedNow) {
                    publishOnlineImmediately()
                } else {
                    scheduleOfflineCheck(cm)
                }
            }

            override fun onUnavailable() {
                synchronized(stateLock) {
                    currentDefaultNetwork = null
                    currentCapabilities = null
                    isBlocked = false
                }
                publishOfflineImmediately()
            }
        }
    }

    private fun evaluateLiveOnlineLocked(cm: ConnectivityManager): Boolean {
        val active = cm.activeNetwork ?: return false
        val blocked = active == currentDefaultNetwork && isBlocked
        if (blocked) return false
        val caps = cm.getNetworkCapabilities(active)
            ?: if (active == currentDefaultNetwork) currentCapabilities else null
        return isCapabilitiesValidatedOnline(caps, blocked = blocked)
    }

    private fun publishOnlineImmediately() {
        synchronized(stateLock) {
            pendingOfflineJob?.cancel()
            pendingOfflineJob = null
        }
        tracker.onConnectivityEvaluated(true)
    }

    private fun publishOfflineImmediately() {
        synchronized(stateLock) {
            pendingOfflineJob?.cancel()
            pendingOfflineJob = null
        }
        tracker.onConnectivityEvaluated(false)
    }

    private fun scheduleOfflineCheck(cm: ConnectivityManager) {
        synchronized(stateLock) {
            if (pendingOfflineJob?.isActive == true) {
                return
            }
            pendingOfflineJob = scope.launch {
                if (offlineGracePeriodMs > 0L) {
                    delay(offlineGracePeriodMs)
                }
                val onlineNow = synchronized(stateLock) {
                    pendingOfflineJob = null
                    evaluateLiveOnlineLocked(cm)
                }
                tracker.onConnectivityEvaluated(onlineNow)
            }
        }
    }

    companion object {
        const val DEFAULT_OFFLINE_GRACE_PERIOD_MS = 1_200L

        fun isCapabilitiesValidatedOnline(
            caps: NetworkCapabilities?,
            blocked: Boolean = false
        ): Boolean {
            if (caps == null || blocked) return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        fun isNetworkValidatedOnline(cm: ConnectivityManager): Boolean {
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            return isCapabilitiesValidatedOnline(caps)
        }
    }
}
