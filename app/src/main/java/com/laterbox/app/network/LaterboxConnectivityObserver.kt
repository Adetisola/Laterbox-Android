package com.laterbox.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.laterbox.app.ui.components.LaterboxToastController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    toastController: LaterboxToastController
) {
    val tracker = OfflineToastStateTracker(toastController)

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var started = false

    fun start() {
        if (started) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                evaluateAndPublish(cm)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                evaluateAndPublish(cm)
            }

            override fun onLost(network: Network) {
                evaluateAndPublish(cm)
            }

            override fun onUnavailable() {
                tracker.onConnectivityEvaluated(false)
            }
        }
        networkCallback = callback
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            started = true
        }
        evaluateAndPublish(cm)
    }

    fun stop() {
        val cm = connectivityManager
        val cb = networkCallback
        if (cm != null && cb != null) {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        started = false
    }

    private fun evaluateAndPublish(cm: ConnectivityManager) {
        tracker.onConnectivityEvaluated(isNetworkValidatedOnline(cm))
    }

    companion object {
        fun isNetworkValidatedOnline(cm: ConnectivityManager): Boolean {
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
