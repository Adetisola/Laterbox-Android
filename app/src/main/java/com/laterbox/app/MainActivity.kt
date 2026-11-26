package com.laterbox.app

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.laterbox.app.data.preferences.ThemeMode
import com.laterbox.app.data.preferences.UserPreferences
import com.laterbox.app.movies.playback.LocalIsInPictureInPictureMode
import com.laterbox.app.ui.navigation.LaterboxApp
import com.laterbox.app.ui.navigation.LaterboxDeepLinks
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val openLinkIdState = MutableStateFlow<Long?>(null)
    private val openDestinationState = MutableStateFlow<Pair<String, String?>?>(null)

    companion object {
        const val EXTRA_OPEN_LINK_ID = "com.laterbox.app.extra.OPEN_LINK_ID"
        const val EXTRA_OPEN_DESTINATION = "com.laterbox.app.extra.OPEN_DESTINATION"
        const val EXTRA_CHAT_INITIAL_PROMPT = "com.laterbox.app.extra.CHAT_INITIAL_PROMPT"
        private var hasShownColdSplash = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        handleIntent(intent)

        val prefsLoaded = java.util.concurrent.atomic.AtomicBoolean(false)
        val prefsFirstEmissionElapsedMs = java.util.concurrent.atomic.AtomicLong(-1L)

        if (savedInstanceState == null && !hasShownColdSplash) {
            val splashLogged = java.util.concurrent.atomic.AtomicBoolean(false)
            val splashStartElapsedMs = com.laterbox.app.diagnostics.SplashTimingLogger.nowElapsedMs()

            splashScreen.setKeepOnScreenCondition {
                val nowElapsed = com.laterbox.app.diagnostics.SplashTimingLogger.nowElapsedMs()
                val loaded = prefsLoaded.get()
                val keep = com.laterbox.app.diagnostics.SplashTimingLogger.shouldKeepSplashOnScreen(
                    prefsLoaded = loaded,
                    splashStartElapsedMs = splashStartElapsedMs,
                    nowElapsedMs = nowElapsed
                )
                if (!keep) {
                    hasShownColdSplash = true
                    if (splashLogged.compareAndSet(false, true)) {
                        val firstEmission = prefsFirstEmissionElapsedMs.get().takeIf { it >= 0L }
                        com.laterbox.app.diagnostics.SplashTimingLogger.recordRelease(
                            splashStartElapsedMs = splashStartElapsedMs,
                            prefsFirstEmissionElapsedMs = firstEmission,
                            prefsLoadedBeforeCeiling = loaded,
                            releasedAtElapsedMs = nowElapsed
                        )
                    }
                }
                keep
            }
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        val container = (application as LaterboxApplication).container

        setContent {
            val preferencesState = androidx.compose.runtime.remember(container.userPreferencesRepository) {
                androidx.compose.runtime.mutableStateOf<UserPreferences?>(null)
            }
            androidx.compose.runtime.LaunchedEffect(container.userPreferencesRepository) {
                container.userPreferencesRepository.userPreferences.collect { loadedPrefs ->
                    if (prefsLoaded.compareAndSet(false, true)) {
                        prefsFirstEmissionElapsedMs.compareAndSet(
                            -1L,
                            com.laterbox.app.diagnostics.SplashTimingLogger.nowElapsedMs()
                        )
                    }
                    preferencesState.value = loadedPrefs
                }
            }
            // C7: if DataStore misses the 800ms safety ceiling, render default UserPreferences() briefly until emission arrives.
            val preferences = preferencesState.value ?: UserPreferences()
            androidx.activity.compose.ReportDrawnWhen { preferencesState.value != null }
            val openLinkId by openLinkIdState.collectAsState()
            val openDestination by openDestinationState.collectAsState()

            val darkTheme = when (preferences.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            val fontScale = preferences.fontSizeScale.scaleFactor

            val jankSession = androidx.compose.runtime.remember {
                com.laterbox.app.diagnostics.JankStatsPlatformHook.attach(this@MainActivity)
            }
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            androidx.compose.runtime.DisposableEffect(jankSession, lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    when (event) {
                        androidx.lifecycle.Lifecycle.Event.ON_RESUME -> jankSession?.resume()
                        androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> jankSession?.pause()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    jankSession?.dispose()
                }
            }

            LaterboxTheme(darkTheme = darkTheme, fontScale = fontScale) {
                CompositionLocalProvider(LocalIsInPictureInPictureMode provides false) {
                    LaterboxApp(
                        initialOpenLinkId = openLinkId,
                        onClearOpenLinkId = { openLinkIdState.value = null },
                        initialOpenDestination = openDestination,
                        onClearOpenDestination = { openDestinationState.value = null },
                        onRouteChanged = { route -> jankSession?.setRoute(route) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val linkId = intent?.getLongExtra(EXTRA_OPEN_LINK_ID, -1L) ?: -1L
        if (linkId > 0) {
            openLinkIdState.value = linkId
            intent?.removeExtra(EXTRA_OPEN_LINK_ID)
        }
        val dest = intent?.getStringExtra(EXTRA_OPEN_DESTINATION)
        if (!dest.isNullOrBlank()) {
            val prompt = intent.getStringExtra(EXTRA_CHAT_INITIAL_PROMPT)
            openDestinationState.value = dest to prompt
            intent.removeExtra(EXTRA_OPEN_DESTINATION)
            intent.removeExtra(EXTRA_CHAT_INITIAL_PROMPT)
        } else {
            val deepLinkRoute = LaterboxDeepLinks.parseIncomingIntent(intent)
            if (!deepLinkRoute.isNullOrBlank()) {
                openDestinationState.value = deepLinkRoute to null
                (applicationContext as? LaterboxApplication)
                    ?.container
                    ?.analyticsTracker
                    ?.trackFeatureUsed("deep_link_open")
                intent?.data = null
            }
        }
    }
}
