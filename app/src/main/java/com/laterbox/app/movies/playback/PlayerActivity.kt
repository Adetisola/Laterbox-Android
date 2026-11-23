package com.laterbox.app.movies.playback

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laterbox.app.LaterboxApplication
import com.laterbox.app.MainActivity
import com.laterbox.app.data.preferences.ThemeMode
import com.laterbox.app.data.preferences.UserPreferences
import com.laterbox.app.ui.AppViewModelProvider
import com.laterbox.app.media.domain.model.MediaAsset
import com.laterbox.app.movies.ui.LocalVideoPlayerScreen
import com.laterbox.app.movies.ui.MoviePlayerScreen
import com.laterbox.app.movies.ui.MoviePlayerViewModel
import com.laterbox.app.movies.ui.PlayerPlaybackPreferences
import com.laterbox.app.ui.util.BrowserLauncher
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated full-screen playback and Picture-in-Picture Activity.
 *
 * Isolates Android system PiP solely to active video playback while keeping [MainActivity]
 * out of PiP, and returns cleanly to [MainActivity] with the in-app mini-player on Back.
 */
class PlayerActivity : ComponentActivity() {

    private val isInPictureInPictureModeState = MutableStateFlow(false)
    private val launchTargetState = MutableStateFlow<PlaybackSessionTarget?>(null)
    private val pipActionReceiver = LaterboxPipActionReceiver(activityProvider = { this })
    private var isPipReceiverRegistered = false
    private val pipLifecycleTracker = LaterboxPipLifecycleTracker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode
        isInPictureInPictureModeState.value = initialPip
        LaterboxPlaybackBridge.setInPictureInPicture(initialPip)
        pipLifecycleTracker.onPipModeChanged(initialPip)

        if (!isPipReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                pipActionReceiver,
                LaterboxPipController.buildIntentFilter(),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isPipReceiverRegistered = true
        }

        val initialTarget = resolveTargetFromIntent(intent)
        if (initialTarget == null) {
            finish()
            return
        }
        launchTargetState.value = initialTarget

        enableEdgeToEdge()
        val container = (application as LaterboxApplication).container
        container.analyticsTracker.trackScreenView("player")

        setContent {
            val foregroundEpoch by container.analyticsTracker.foregroundEpoch.collectAsState()
            LaunchedEffect(foregroundEpoch) {
                container.analyticsTracker.trackScreenView("player")
            }
            val preferences by container.userPreferencesRepository.userPreferences.collectAsState(
                initial = UserPreferences()
            )
            val isInPip by isInPictureInPictureModeState.collectAsState()
            val target by launchTargetState.collectAsState()
            val colors = LaterboxTheme.colors

            val darkTheme = when (preferences.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            LaterboxTheme(darkTheme = darkTheme, fontScale = preferences.fontSizeScale.scaleFactor) {
                CompositionLocalProvider(LocalIsInPictureInPictureMode provides isInPip) {
                    when (val activeTarget = target) {
                        is PlaybackSessionTarget.MovieStream -> {
                            val moviePlayerViewModel: MoviePlayerViewModel = viewModel(
                                factory = AppViewModelProvider.Factory
                            )
                            LaunchedEffect(
                                activeTarget.providerId,
                                activeTarget.titleId,
                                activeTarget.seasonNumber,
                                activeTarget.episodeNumber
                            ) {
                                moviePlayerViewModel.load(
                                    providerId = activeTarget.providerId,
                                    titleId = activeTarget.titleId,
                                    seasonNumber = activeTarget.seasonNumber,
                                    episodeNumber = activeTarget.episodeNumber
                                )
                            }
                            val playerState by moviePlayerViewModel.uiState.collectAsState()
                            MoviePlayerScreen(
                                uiState = playerState,
                                onBackClick = { finishToMainWithMiniPlayer() },
                                onTryEnterPipOnBack = { enterNativePipFromPlayer() },
                                onRetry = {
                                    moviePlayerViewModel.load(
                                        providerId = activeTarget.providerId,
                                        titleId = activeTarget.titleId,
                                        seasonNumber = activeTarget.seasonNumber,
                                        episodeNumber = activeTarget.episodeNumber,
                                        forceReload = true
                                    )
                                },
                                onSelectSource = moviePlayerViewModel::selectSource,
                                onSelectSubtitle = moviePlayerViewModel::selectSubtitle,
                                onSaveProgress = moviePlayerViewModel::saveProgress,
                                onSaveProgressForEpisode = moviePlayerViewModel::saveProgressForEpisode,
                                onOpenExternalUrl = { url ->
                                    BrowserLauncher.openUrl(this@PlayerActivity, url, colors)
                                },
                                onDownloadSource = moviePlayerViewModel::downloadSource,
                                onDismissDownloadStatus = moviePlayerViewModel::clearDownloadStatus,
                                onSelectAudioDub = moviePlayerViewModel::selectAudioDub,
                                onSelectInStreamAudio = moviePlayerViewModel::saveStreamAudioPreference,
                                onNextEpisode = moviePlayerViewModel::playNextEpisode,
                                onSubtitleStyleChange = moviePlayerViewModel::updateSubtitleStyle,
                                onOpenRelatedTitle = { relProvider, relId ->
                                    LaterboxPlaybackBridge.stopAndDetach(applicationContext)
                                    val detailIntent = Intent(this@PlayerActivity, MainActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        putExtra(
                                            MainActivity.EXTRA_OPEN_DESTINATION,
                                            "movies/detail/$relProvider/$relId"
                                        )
                                    }
                                    startActivity(detailIntent)
                                    finish()
                                },
                                introSkipEnabled = container.remoteConfigManager.isFeatureEnabled(
                                    com.laterbox.app.ai.RemoteCapabilities.KEY_PLAYER_INTRO_SKIP
                                )
                            )
                        }
                        is PlaybackSessionTarget.LocalVideo -> {
                            var localAsset by remember(activeTarget.assetId) {
                                mutableStateOf<MediaAsset?>(null)
                            }
                            var assetMissing by remember(activeTarget.assetId) {
                                mutableStateOf(false)
                            }
                            LaunchedEffect(activeTarget.assetId) {
                                val loaded = withContext(Dispatchers.IO) {
                                    container.mediaRepository.getAssetById(activeTarget.assetId)
                                        .firstOrNull()
                                }
                                if (loaded == null) {
                                    assetMissing = true
                                } else {
                                    localAsset = loaded
                                }
                            }
                            LaunchedEffect(assetMissing) {
                                if (assetMissing) {
                                    LaterboxPlaybackBridge.stopAndDetach(applicationContext)
                                    finish()
                                }
                            }

                            val asset = localAsset
                            if (asset == null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = LaterboxTheme.colors.accentPrimary)
                                }
                            } else {
                                val localPrefs = remember(preferences) {
                                    PlayerPlaybackPreferences(
                                        doubleTapSeekSeconds = preferences.moviesDoubleTapSeekSeconds,
                                        swipeToSeekEnabled = preferences.moviesSwipeToSeekEnabled,
                                        swipeSeekSensitivity = preferences.moviesSwipeSeekSensitivity,
                                        brightnessGestureEnabled = preferences.moviesBrightnessGestureEnabled,
                                        volumeGestureEnabled = preferences.moviesVolumeGestureEnabled,
                                        autoplayNextEpisode = preferences.moviesAutoplayNextEpisode,
                                        autoplayCountdownSeconds = preferences.moviesAutoplayCountdownSeconds,
                                        defaultQualityHeight = preferences.moviesDefaultQualityHeight,
                                        subtitleStyle = preferences.toSubtitleStyleConfig()
                                    )
                                }
                                LocalVideoPlayerScreen(
                                    asset = asset,
                                    onBackClick = { finishToMainWithMiniPlayer() },
                                    onTryEnterPipOnBack = { enterNativePipFromPlayer() },
                                    playbackPreferences = localPrefs,
                                    onSubtitleStyleChange = { style ->
                                        lifecycleScope.launch {
                                            container.userPreferencesRepository.setSubtitleStyleConfig(style)
                                        }
                                    }
                                )
                            }
                        }
                        null -> Unit
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveTargetFromIntent(intent)?.let { resolved ->
            launchTargetState.value = resolved
        }
    }

    override fun onStart() {
        super.onStart()
        pipLifecycleTracker.onStarted()
    }

    override fun onResume() {
        super.onResume()
        val currentlyInPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode
        isInPictureInPictureModeState.value = currentlyInPip
        LaterboxPlaybackBridge.setInPictureInPicture(currentlyInPip)
        pipLifecycleTracker.onResumed(currentlyInPip)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !isFinishing &&
            !isChangingConfigurations &&
            !isInPictureInPictureMode &&
            LaterboxPlaybackBridge.canEnterPipOnUserLeave(
                context = this,
                isAlreadyInPip = isInPictureInPictureMode,
                isFinishing = isFinishing
            )
        ) {
            enterNativePipFromPlayer()
        }
    }

    private fun enterNativePipFromPlayer(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (isFinishing || isDestroyed || isChangingConfigurations || isInPictureInPictureMode) {
            return false
        }
        val player = LaterboxPlaybackBridge.activePlayer
        val videoSize = runCatching { player?.videoSize }.getOrNull()
        val aspectRatio = if (videoSize != null && videoSize.width > 0 && videoSize.height > 0) {
            Rational(videoSize.width, videoSize.height)
        } else {
            Rational(16, 9)
        }
        return LaterboxPipController.tryEnterPictureInPicture(
            activity = this,
            isPlaying = true,
            doubleTapSeekSeconds = LaterboxPlaybackBridge.activeDoubleTapSeekSeconds,
            aspectRatio = aspectRatio
        )
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPictureInPictureModeState.value = isInPictureInPictureMode
        LaterboxPlaybackBridge.setInPictureInPicture(isInPictureInPictureMode)
        val pipAction = pipLifecycleTracker.evaluateOnPipModeChanged(
            nowInPip = isInPictureInPictureMode,
            isChangingConfigurations = isChangingConfigurations,
            isRetainedForMiniPlayer = LaterboxPlaybackBridge.isRetainedForMiniPlayer
        )
        if (pipAction == PipStopAction.STOP_PLAYBACK_AND_FINISH) {
            stopPlaybackAndFinishFromPipDismissal()
        }
    }

    override fun onStop() {
        super.onStop()
        val currentlyInPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode
        val stopAction = pipLifecycleTracker.evaluateOnStop(
            isCurrentlyInPip = currentlyInPip,
            isChangingConfigurations = isChangingConfigurations,
            isRetainedForMiniPlayer = LaterboxPlaybackBridge.isRetainedForMiniPlayer
        )
        if (stopAction == PipStopAction.STOP_PLAYBACK_AND_FINISH) {
            stopPlaybackAndFinishFromPipDismissal()
        }
    }

    private fun stopPlaybackAndFinishFromPipDismissal() {
        LaterboxPipController.disableAutoEnterPip(this)
        LaterboxPlaybackBridge.setInPictureInPicture(false)
        LaterboxPlaybackBridge.persistCurrentProgress()
        LaterboxPlaybackBridge.stopAndDetach(applicationContext)
        if (!isFinishing) {
            finish()
        }
    }

    override fun onDestroy() {
        if (isPipReceiverRegistered) {
            runCatching { unregisterReceiver(pipActionReceiver) }
            isPipReceiverRegistered = false
        }
        if (!isChangingConfigurations) {
            LaterboxPlaybackBridge.setInPictureInPicture(false)
        }
        if (isFinishing && !isChangingConfigurations && !LaterboxPlaybackBridge.isRetainedForMiniPlayer) {
            LaterboxPlaybackBridge.stopAndDetach(applicationContext)
        }
        super.onDestroy()
    }

    private fun finishToMainWithMiniPlayer() {
        LaterboxPipController.disableAutoEnterPip(this)
        pipLifecycleTracker.onTransitionToMiniPlayer()
        LaterboxPlaybackBridge.setInPictureInPicture(false)
        if (isTaskRoot) {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(mainIntent)
        }
        finish()
    }

    private fun resolveTargetFromIntent(intent: Intent?): PlaybackSessionTarget? {
        if (intent == null) return LaterboxPlaybackBridge.activeTarget
        return when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_REOPEN -> LaterboxPlaybackBridge.activeTarget
            MODE_LOCAL_VIDEO -> {
                val assetId = intent.getStringExtra(EXTRA_LOCAL_ASSET_ID).orEmpty()
                if (assetId.isNotBlank()) PlaybackSessionTarget.LocalVideo(assetId) else null
            }
            MODE_STREAM -> {
                val providerId = intent.getStringExtra(EXTRA_PROVIDER_ID).orEmpty()
                val titleId = intent.getStringExtra(EXTRA_TITLE_ID).orEmpty()
                if (providerId.isBlank() || titleId.isBlank()) return null
                val rawSeason = intent.getIntExtra(EXTRA_SEASON, -1)
                val rawEpisode = intent.getIntExtra(EXTRA_EPISODE, -1)
                PlaybackSessionTarget.MovieStream(
                    providerId = providerId,
                    titleId = titleId,
                    seasonNumber = rawSeason.takeIf { it > 0 },
                    episodeNumber = rawEpisode.takeIf { it > 0 }
                )
            }
            else -> LaterboxPlaybackBridge.activeTarget
        }
    }

    companion object {
        private const val EXTRA_MODE = "com.laterbox.app.player.MODE"
        private const val MODE_STREAM = "stream"
        private const val MODE_LOCAL_VIDEO = "local_video"
        private const val MODE_REOPEN = "reopen"

        private const val EXTRA_PROVIDER_ID = "com.laterbox.app.player.PROVIDER_ID"
        private const val EXTRA_TITLE_ID = "com.laterbox.app.player.TITLE_ID"
        private const val EXTRA_SEASON = "com.laterbox.app.player.SEASON"
        private const val EXTRA_EPISODE = "com.laterbox.app.player.EPISODE"
        private const val EXTRA_LOCAL_ASSET_ID = "com.laterbox.app.player.LOCAL_ASSET_ID"

        fun createStreamIntent(
            context: Context,
            providerId: String,
            titleId: String,
            seasonNumber: Int? = null,
            episodeNumber: Int? = null
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_MODE, MODE_STREAM)
            putExtra(EXTRA_PROVIDER_ID, providerId)
            putExtra(EXTRA_TITLE_ID, titleId)
            putExtra(EXTRA_SEASON, seasonNumber ?: -1)
            putExtra(EXTRA_EPISODE, episodeNumber ?: -1)
        }

        fun createLocalVideoIntent(
            context: Context,
            assetId: String
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_MODE, MODE_LOCAL_VIDEO)
            putExtra(EXTRA_LOCAL_ASSET_ID, assetId)
        }

        fun createReopenIntent(context: Context): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_MODE, MODE_REOPEN)
            }
    }
}
