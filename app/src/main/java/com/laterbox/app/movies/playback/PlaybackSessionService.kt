package com.laterbox.app.movies.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.laterbox.app.MainActivity
import com.laterbox.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.IdentityHashMap

sealed interface PlaybackSessionTarget {
    val episodeKey: String

    data class MovieStream(
        val providerId: String,
        val titleId: String,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null
    ) : PlaybackSessionTarget {
        override val episodeKey: String = "${titleId}:${seasonNumber ?: 0}:${episodeNumber ?: 0}"
    }

    data class LocalVideo(
        val assetId: String
    ) : PlaybackSessionTarget {
        override val episodeKey: String = "local:$assetId"
    }
}

data class MiniPlayerState(
    val target: PlaybackSessionTarget,
    val episodeKey: String,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val positionMs: Long,
    val durationMs: Long
)

/**
 * Coordinates the single shared video/audio [Player] and [PlaybackSessionService] so
 * full-screen [PlayerActivity], the in-app mini-player, system PiP, Android media
 * notifications, and headset transport controls stay synchronized without duplicate ExoPlayers.
 */
object LaterboxPlaybackBridge {
    private const val TAG = "LaterboxPlaybackBridge"
    private val lock = Any()
    private val releasedPlayers: MutableSet<Player> = Collections.newSetFromMap(IdentityHashMap())

    @Volatile
    var activePlayer: Player? = null
        private set

    @Volatile
    var activeTitle: String? = null
        private set

    @Volatile
    var activeSubtitle: String? = null
        private set

    @Volatile
    var activeArtworkUrl: String? = null
        private set

    @Volatile
    var activeTarget: PlaybackSessionTarget? = null
        private set

    @Volatile
    var activeEpisodeKey: String? = null
        private set

    @Volatile
    var activeDoubleTapSeekSeconds: Int = 10
        private set

    @Volatile
    var isRetainedForMiniPlayer: Boolean = false
        private set

    @Volatile
    var hasPipBlockingModal: Boolean = false

    @Volatile
    var isPlayerLocked: Boolean = false

    @Volatile
    private var sharedVideoPlayer: ExoPlayer? = null

    @Volatile
    private var activePreparedKey: String? = null

    @Volatile
    private var cachedUiStateTargetKey: String? = null

    @Volatile
    private var cachedUiStateValue: Any? = null

    @Volatile
    private var progressSaver: ((Long, Long) -> Unit)? = null

    @Volatile
    private var attachedService: PlaybackSessionService? = null

    @Volatile
    private var serviceStartPending: Boolean = false

    private val _miniPlayerState = MutableStateFlow<MiniPlayerState?>(null)
    val miniPlayerState: StateFlow<MiniPlayerState?> = _miniPlayerState.asStateFlow()

    internal fun setActivePlayerForTest(player: Player?) {
        synchronized(lock) {
            activePlayer = player
        }
    }

    fun updateDoubleTapSeekSeconds(seconds: Int) {
        activeDoubleTapSeekSeconds = LaterboxPipSpec.normalizeSeekSeconds(seconds)
    }

    /**
     * Returns the single shared video [ExoPlayer] instance, creating it lazily on [context].
     */
    @OptIn(UnstableApi::class)
    fun obtainOrCreateSharedVideoPlayer(
        context: Context,
        doubleTapSeekSeconds: Int
    ): ExoPlayer {
        val appContext = context.applicationContext
        val seekSec = LaterboxPipSpec.normalizeSeekSeconds(doubleTapSeekSeconds)
        activeDoubleTapSeekSeconds = seekSec
        synchronized(lock) {
            val existing = sharedVideoPlayer
            if (existing != null && !releasedPlayers.contains(existing)) {
                return existing
            }
            val seekIncrementMs = seekSec * 1_000L
            val built = ExoPlayer.Builder(appContext)
                .setAudioAttributes(PlaybackSessionService.buildConfiguredAudioAttributes(), true)
                .setHandleAudioBecomingNoisy(true)
                .setSeekBackIncrementMs(seekIncrementMs)
                .setSeekForwardIncrementMs(seekIncrementMs)
                .build()
            sharedVideoPlayer = built
            return built
        }
    }

    fun isUsingSharedVideoPlayer(player: Player?): Boolean {
        synchronized(lock) {
            return player != null && sharedVideoPlayer === player && !releasedPlayers.contains(player)
        }
    }

    fun setTargetMetadata(
        target: PlaybackSessionTarget?,
        episodeKey: String?,
        artworkUrl: String?,
        onSaveProgress: ((Long, Long) -> Unit)?
    ) {
        synchronized(lock) {
            activeTarget = target
            activeEpisodeKey = episodeKey
            activeArtworkUrl = artworkUrl?.takeIf { it.isNotBlank() }
            progressSaver = onSaveProgress
        }
    }

    fun markPreparedSource(episodeKey: String, sourceUrl: String, subtitleId: String?) {
        synchronized(lock) {
            activePreparedKey = "$episodeKey|$sourceUrl|${subtitleId.orEmpty()}"
        }
    }

    fun isAlreadyPreparedFor(episodeKey: String, sourceUrl: String, subtitleId: String?): Boolean {
        val expected = "$episodeKey|$sourceUrl|${subtitleId.orEmpty()}"
        synchronized(lock) {
            val player = sharedVideoPlayer ?: return false
            return !releasedPlayers.contains(player) &&
                activePlayer === player &&
                activePreparedKey == expected &&
                player.playbackState != Player.STATE_IDLE
        }
    }

    fun cachePlayerUiState(targetKey: String, uiState: Any) {
        synchronized(lock) {
            cachedUiStateTargetKey = targetKey
            cachedUiStateValue = uiState
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getCachedPlayerUiState(targetKey: String): T? {
        synchronized(lock) {
            if (cachedUiStateTargetKey == targetKey) {
                return cachedUiStateValue as? T
            }
            return null
        }
    }

    fun enterMiniPlayer(context: Context): Boolean {
        val player: Player
        val target: PlaybackSessionTarget
        synchronized(lock) {
            val currentPlayer = activePlayer ?: return false
            val currentTarget = activeTarget ?: return false
            if (releasedPlayers.contains(currentPlayer)) return false
            isRetainedForMiniPlayer = true
            player = currentPlayer
            target = currentTarget
        }
        persistCurrentProgress()
        updateMiniPlayerSnapshot(player, target)
        return true
    }

    fun exitMiniPlayerToFullscreen() {
        synchronized(lock) {
            isRetainedForMiniPlayer = false
        }
        _miniPlayerState.value = null
    }

    fun refreshMiniPlayerProgress(persistProgress: Boolean = false) {
        val player: Player
        val target: PlaybackSessionTarget
        synchronized(lock) {
            if (!isRetainedForMiniPlayer) return
            player = activePlayer ?: return
            target = activeTarget ?: return
            if (releasedPlayers.contains(player)) return
        }
        updateMiniPlayerSnapshot(player, target)
        if (persistProgress) {
            persistCurrentProgress()
        }
    }

    fun toggleMiniPlayerPlayPause() {
        val player = activePlayer ?: return
        val target = activeTarget ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0L)
            }
            player.play()
        }
        persistCurrentProgress()
        updateMiniPlayerSnapshot(player, target)
    }

    fun dismissMiniPlayer(context: Context) {
        persistCurrentProgress()
        stopAndDetach(context)
    }

    fun persistCurrentProgress() {
        val player = activePlayer ?: return
        val saver = progressSaver ?: return
        val pos = runCatching { player.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
        val dur = runCatching { player.duration.coerceAtLeast(0L) }.getOrDefault(0L)
        if (pos > 0L || dur > 0L) {
            runCatching { saver(pos, dur) }
                .onFailure { err -> Log.w(TAG, "Progress persist failed: ${err.message}") }
        }
    }

    fun canEnterPipOnUserLeave(
        context: Context,
        isAlreadyInPip: Boolean,
        isFinishing: Boolean
    ): Boolean {
        val player = activePlayer ?: return false
        val activelyPlaying = runCatching { player.isPlaying }.getOrDefault(false)
        val pipSupported = LaterboxPipController.isPipSupportedAndAllowed(context.applicationContext)
        return LaterboxPipSpec.shouldEnterPipOnUserLeaveHint(
            isPlaying = activelyPlaying,
            isPipSupported = pipSupported,
            isAlreadyInPip = isAlreadyInPip,
            hasModalOrOverlayOpen = hasPipBlockingModal,
            isLocked = isPlayerLocked,
            isFinishing = isFinishing
        )
    }

    private fun updateMiniPlayerSnapshot(player: Player, target: PlaybackSessionTarget) {
        val pos = runCatching { player.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
        val dur = runCatching { player.duration.coerceAtLeast(0L) }.getOrDefault(0L)
        val playing = runCatching { player.isPlaying || player.playWhenReady }.getOrDefault(false) &&
            runCatching { player.playbackState != Player.STATE_ENDED }.getOrDefault(true)
        val buffering = runCatching { player.playbackState == Player.STATE_BUFFERING }.getOrDefault(false)
        _miniPlayerState.value = MiniPlayerState(
            target = target,
            episodeKey = activeEpisodeKey ?: target.episodeKey,
            title = activeTitle ?: "Now Playing",
            subtitle = activeSubtitle,
            artworkUrl = activeArtworkUrl,
            isPlaying = playing,
            isBuffering = buffering,
            positionMs = pos,
            durationMs = dur
        )
    }

    /**
     * Dispatches a Picture-in-Picture remote control action against the active player session.
     * Returns whether the active player is now intended to be playing.
     */
    fun dispatchPipAction(actionType: PipActionType): Boolean {
        val player = activePlayer ?: return false
        val deltaMs = LaterboxPipSpec.resolveSeekDeltaMs(activeDoubleTapSeekSeconds)
        return when (actionType) {
            PipActionType.REWIND -> {
                val target = LaterboxPipSpec.computeSeekTargetMs(
                    currentPositionMs = player.currentPosition,
                    durationMs = player.duration,
                    deltaMs = -deltaMs
                )
                player.seekTo(target)
                player.isPlaying || player.playWhenReady
            }
            PipActionType.FORWARD -> {
                val target = LaterboxPipSpec.computeSeekTargetMs(
                    currentPositionMs = player.currentPosition,
                    durationMs = player.duration,
                    deltaMs = deltaMs
                )
                player.seekTo(target)
                player.isPlaying || player.playWhenReady
            }
            PipActionType.PLAY_PAUSE -> {
                if (player.isPlaying) {
                    player.pause()
                    false
                } else {
                    if (player.playbackState == Player.STATE_ENDED) {
                        player.seekTo(0L)
                    }
                    player.play()
                    true
                }
            }
        }
    }

    fun attachPlayer(
        context: Context,
        player: Player,
        title: String?,
        subtitle: String? = null
    ) {
        val appContext = context.applicationContext
        val serviceToSync: PlaybackSessionService?
        val shouldStartService: Boolean
        synchronized(lock) {
            if (releasedPlayers.contains(player)) {
                Log.w(TAG, "Ignoring attachPlayer for already-released player")
                return
            }
            val previous = activePlayer
            activePlayer = player
            activeTitle = title?.takeIf { it.isNotBlank() }
            activeSubtitle = subtitle?.takeIf { it.isNotBlank() }
            serviceToSync = attachedService
            shouldStartService = serviceToSync == null && (!serviceStartPending || previous !== player)
            if (shouldStartService) {
                serviceStartPending = true
            }
        }

        if (serviceToSync != null) {
            runCatching { serviceToSync.syncWithPlayer(player) }
                .onFailure { err -> Log.w(TAG, "Failed to sync active player with service: ${err.message}") }
        } else if (shouldStartService) {
            startServiceSafely(appContext)
        }
    }

    fun updateMetadata(title: String?, subtitle: String? = null, artworkUrl: String? = activeArtworkUrl) {
        synchronized(lock) {
            activeTitle = title?.takeIf { it.isNotBlank() }
            activeSubtitle = subtitle?.takeIf { it.isNotBlank() }
            activeArtworkUrl = artworkUrl?.takeIf { it.isNotBlank() }
        }
        val player = activePlayer
        val target = activeTarget
        if (isRetainedForMiniPlayer && player != null && target != null) {
            updateMiniPlayerSnapshot(player, target)
        }
    }

    fun releasePlayer(context: Context, player: Player) {
        val appContext = context.applicationContext
        val serviceToStop: PlaybackSessionService?
        val shouldStopServiceIntent: Boolean
        val alreadyReleased: Boolean
        synchronized(lock) {
            if (isRetainedForMiniPlayer && activePlayer === player && sharedVideoPlayer === player) {
                return
            }
            alreadyReleased = !releasedPlayers.add(player)
            if (sharedVideoPlayer === player) {
                sharedVideoPlayer = null
                activePreparedKey = null
            }
            if (activePlayer === player) {
                activePlayer = null
                activeTitle = null
                activeSubtitle = null
                activeArtworkUrl = null
                activeTarget = null
                activeEpisodeKey = null
                cachedUiStateTargetKey = null
                cachedUiStateValue = null
                progressSaver = null
                isRetainedForMiniPlayer = false
                hasPipBlockingModal = false
                isPlayerLocked = false
                _miniPlayerState.value = null
                serviceToStop = attachedService
                shouldStopServiceIntent = serviceToStop == null && serviceStartPending
                serviceStartPending = false
            } else {
                serviceToStop = null
                shouldStopServiceIntent = false
            }
        }

        // Detach MediaSession BEFORE releasing ExoPlayer so MediaSession never holds a released player.
        serviceToStop?.let { service ->
            runCatching { service.stopPlaybackSession() }
                .onFailure { err -> Log.w(TAG, "Failed stopping playback session: ${err.message}") }
        }
        if (shouldStopServiceIntent) {
            runCatching { appContext.stopService(Intent(appContext, PlaybackSessionService::class.java)) }
        }
        if (!alreadyReleased) {
            runCatching { player.release() }
                .onFailure { err -> Log.w(TAG, "Ignoring player.release() error: ${err.message}") }
        }
    }

    fun stopAndDetach(context: Context) {
        val appContext = context.applicationContext
        val serviceToStop: PlaybackSessionService?
        val playerToRelease: ExoPlayer?
        synchronized(lock) {
            playerToRelease = sharedVideoPlayer
            if (playerToRelease != null) {
                releasedPlayers.add(playerToRelease)
            }
            sharedVideoPlayer = null
            activePreparedKey = null
            activePlayer = null
            activeTitle = null
            activeSubtitle = null
            activeArtworkUrl = null
            activeTarget = null
            activeEpisodeKey = null
            cachedUiStateTargetKey = null
            cachedUiStateValue = null
            progressSaver = null
            isRetainedForMiniPlayer = false
            hasPipBlockingModal = false
            isPlayerLocked = false
            _miniPlayerState.value = null
            serviceToStop = attachedService
            serviceStartPending = false
        }
        serviceToStop?.stopPlaybackSession()
        if (playerToRelease != null) {
            runCatching { playerToRelease.release() }
                .onFailure { err -> Log.w(TAG, "Failed releasing shared video player: ${err.message}") }
        }
        runCatching {
            appContext.stopService(Intent(appContext, PlaybackSessionService::class.java))
        }.onFailure { err ->
            Log.w(TAG, "Failed stopping PlaybackSessionService: ${err.message}")
        }
    }

    internal fun onServiceBound(service: PlaybackSessionService) {
        val playerToSync: Player?
        synchronized(lock) {
            attachedService = service
            serviceStartPending = false
            playerToSync = activePlayer
        }
        if (playerToSync != null) {
            runCatching { service.syncWithPlayer(playerToSync) }
                .onFailure { err -> Log.w(TAG, "Failed initial syncWithPlayer: ${err.message}") }
        }
    }

    internal fun onServiceDestroyed(service: PlaybackSessionService) {
        synchronized(lock) {
            if (attachedService === service) {
                attachedService = null
                serviceStartPending = false
            }
        }
    }

    private fun startServiceSafely(appContext: Context) {
        runCatching {
            val intent = Intent(appContext, PlaybackSessionService::class.java)
            ContextCompat.startForegroundService(appContext, intent)
        }.onFailure { err ->
            synchronized(lock) {
                serviceStartPending = false
            }
            Log.w(TAG, "Unable to start PlaybackSessionService: ${err.message}")
        }
    }
}

@OptIn(UnstableApi::class)
class PlaybackSessionService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private var tickerCount = 0
    private val progressTicker = object : Runnable {
        override fun run() {
            if (LaterboxPlaybackBridge.isRetainedForMiniPlayer) {
                tickerCount++
                LaterboxPlaybackBridge.refreshMiniPlayerProgress(persistProgress = tickerCount % 10 == 0)
            }
            progressHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensurePlaybackNotificationChannel()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(MEDIA_NOTIFICATION_ID)
                .setChannelId(MEDIA_CHANNEL_ID)
                .setChannelName(R.string.playback_notification_channel_name)
                .build()
                .apply {
                    setSmallIcon(R.mipmap.ic_launcher)
                }
        )
        LaterboxPlaybackBridge.onServiceBound(this)
        progressHandler.postDelayed(progressTicker, 500L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = LaterboxPlaybackBridge.activePlayer
        if (player == null) {
            if (mediaSession == null) {
                runCatching { startForeground(MEDIA_NOTIFICATION_ID, buildMinimalStoppingNotification()) }
            }
            stopPlaybackSession()
            return START_NOT_STICKY
        }
        syncWithPlayer(player)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val existing = mediaSession
        if (existing != null) return existing
        val player = LaterboxPlaybackBridge.activePlayer ?: return null
        return buildOrUpdateSession(player)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.playbackState == Player.STATE_ENDED) {
            LaterboxPlaybackBridge.persistCurrentProgress()
            stopPlaybackSession()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        progressHandler.removeCallbacks(progressTicker)
        LaterboxPlaybackBridge.onServiceDestroyed(this)
        releaseSessionInternal()
        super.onDestroy()
    }

    internal fun syncWithPlayer(player: Player) {
        buildOrUpdateSession(player)
    }

    internal fun stopPlaybackSession() {
        releaseSessionInternal()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun buildOrUpdateSession(player: Player): MediaSession {
        val pendingIntent = buildSessionPendingIntent()
        val existing = mediaSession
        if (existing != null) {
            if (existing.player !== player) {
                existing.player = player
            }
            runCatching { existing.setSessionActivity(pendingIntent) }
            return existing
        }

        val session = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
        addSession(session)
        mediaSession = session
        return session
    }

    private fun buildSessionPendingIntent(): PendingIntent {
        val hasVideoTarget = LaterboxPlaybackBridge.activeTarget != null
        return if (hasVideoTarget) {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val playerIntent = PlayerActivity.createReopenIntent(this)
            TaskStackBuilder.create(this)
                .addNextIntent(mainIntent)
                .addNextIntent(playerIntent)
                .getPendingIntent(
                    SESSION_ACTIVITY_REQUEST_CODE,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
        } else {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            PendingIntent.getActivity(
                this,
                SESSION_ACTIVITY_REQUEST_CODE,
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    internal fun currentSession(): MediaSession? = mediaSession

    private fun releaseSessionInternal() {
        mediaSession?.let { session ->
            runCatching { removeSession(session) }
            runCatching { session.release() }
        }
        mediaSession = null
    }

    private fun ensurePlaybackNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(MEDIA_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    MEDIA_CHANNEL_ID,
                    getString(R.string.playback_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildMinimalStoppingNotification(): Notification {
        ensurePlaybackNotificationChannel()
        return NotificationCompat.Builder(this, MEDIA_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setOngoing(false)
            .setSilent(true)
            .build()
    }

    companion object {
        const val MEDIA_NOTIFICATION_ID = 4107
        const val MEDIA_CHANNEL_ID = "laterbox_media_playback"
        private const val SESSION_ACTIVITY_REQUEST_CODE = 4108

        fun buildConfiguredAudioAttributes(): AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
    }
}
