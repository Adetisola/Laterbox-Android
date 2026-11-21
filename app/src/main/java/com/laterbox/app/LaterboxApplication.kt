package com.laterbox.app

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.laterbox.app.data.metadata.MetadataSyncWorker
import com.laterbox.app.di.AppContainer
import com.laterbox.app.di.DefaultAppContainer
import com.laterbox.app.reminders.smart.SmartReminderCoordinator
import com.laterbox.app.update.ApkInstallerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LaterboxApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var lastReconcileAtMs: Long = 0L

    private suspend fun reconcileInspectionsIfNeeded(minIntervalMs: Long = 30_000L) {
        val now = System.currentTimeMillis()
        if (now - lastReconcileAtMs < minIntervalMs) return
        lastReconcileAtMs = now
        try {
            container.mediaInspectionManager.reconcileInspections()
        } catch (t: Throwable) {
            Log.w("LaterboxApplication", "Inspection reconciliation warning: ${t.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)

        // Bypass background workers and App Check in Robolectric / unit test environments
        if (android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            return
        }

        // Initialize Firebase App Check: Debug provider in debug builds, Play Integrity in release builds
        try {
            val isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val providerFactory = if (isDebug) {
                com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory.getInstance()
            } else {
                PlayIntegrityAppCheckProviderFactory.getInstance()
            }
            Firebase.appCheck.installAppCheckProviderFactory(providerFactory)
            Log.d("LaterboxApplication", "Installed App Check provider factory")
        } catch (e: Exception) {
            Log.w("LaterboxApplication", "Failed to install App Check provider: ${e.message}")
        }

        // Initialize Remote Config non-blockingly (hydrates local DataStore cache first, fetches after frame settles)
        container.remoteConfigManager.initializeNonBlocking()

        // Fast startup state recovery first; defer heavy yt-dlp / FFmpeg / orphan sweep I/O
        applicationScope.launch {
            try {
                container.userPreferencesRepository.recordFirstLaunchIfNeeded()
                container.database.savedLinkDao().resetStuckFetchingLinks()
                MetadataSyncWorker.enqueue(this@LaterboxApplication)

                val prefs = container.userPreferencesRepository.userPreferencesFlow.first()
                val smartSettings = prefs.toSmartReminderSettings()
                SmartReminderCoordinator.sync(
                    context = this@LaterboxApplication,
                    settings = smartSettings.copy(
                        enabled = smartSettings.enabled &&
                            container.remoteConfigManager.isFeatureEnabled(
                                com.laterbox.app.ai.RemoteCapabilities.KEY_SMART_REMINDERS
                            )
                    )
                )

                // Recover any interrupted media downloads quickly
                container.mediaRepository.recoverInterruptedTasks()

                // Defer heavy binary warmup, file sweep, APK cleanup, and inspection reconciliation
                // until after first frame has settled
                kotlinx.coroutines.delay(2_500L)

                try {
                    container.ytDlpRuntimeManager.ensureYtDlpInitialized()
                    applicationScope.launch {
                        try {
                            container.ytDlpRuntimeManager.ensureFfmpegInitialized()
                        } catch (t: Throwable) {
                            Log.w("LaterboxApplication", "FFmpeg background pre-warm warning: ${t.message}")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("LaterboxApplication", "yt-dlp runtime init warning: ${t.message}")
                }

                container.appStorageManager.runMaintenanceCleanup()
                com.laterbox.app.storage.StorageMaintenanceWorker.enqueuePeriodic(this@LaterboxApplication)

                try {
                    val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
                    ApkInstallerHelper.cleanupOldUpdateApks(this@LaterboxApplication, currentVersion)
                } catch (t: Throwable) {
                    Log.w("LaterboxApplication", "Failed to clean old update APKs: ${t.message}")
                }

                reconcileInspectionsIfNeeded()
                com.laterbox.app.movies.data.cache.MoviesMediaCache.warmUp(this@LaterboxApplication)

                // Check for yt-dlp binary updates if > 24 hours (deferred to prevent startup lock contention)
                applicationScope.launch {
                    kotlinx.coroutines.delay(15_000L)
                    try {
                        if (container.remoteConfigManager.isFeatureEnabled(
                                com.laterbox.app.ai.RemoteCapabilities.KEY_YTDLP_AUTO_UPDATE
                            )
                        ) {
                            container.ytDlpUpdater.updateIfStale()
                        }
                    } catch (t: Throwable) {
                        Log.w("LaterboxApplication", "Deferred yt-dlp update check error: ${t.message}")
                    }
                }
            } catch (_: Exception) {
                // Safeguard against test environments
            }
        }

        // Register foreground transition observer to reconcile media inspections cleanly (debounced)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0

            override fun onActivityStarted(activity: android.app.Activity) {
                if (startedActivities == 0) {
                    container.remoteConfigManager.onAppForegrounded()
                    container.analyticsTracker.onAppForegrounded()
                    applicationScope.launch {
                        reconcileInspectionsIfNeeded()
                    }
                }
                startedActivities++
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                val wasForeground = startedActivities > 0
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (wasForeground && startedActivities == 0) {
                    container.analyticsTracker.onAppBackgrounded()
                }
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }
}
