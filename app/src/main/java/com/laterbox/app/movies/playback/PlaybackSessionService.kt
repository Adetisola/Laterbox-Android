package com.laterbox.app.movies.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.laterbox.app.MainActivity
import com.laterbox.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

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
    var isInPictureInPicture: Boolean = false
        private set

    fun setInPictureInPicture(inPip: Boolean) {
        synchronized(lock) {
            isInPictureInPicture = inPip
        }
    }

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

    fun isActivePlaybackNow(hasError: Boolean = false): Boolean {
        val player = activePlayer ?: return false
        return runCatching {
            LaterboxPipSpec.isIntendedActivePlayback(
                isPlaying = player.isPlaying,
                playWhenReady = player.playWhenReady,
                playbackState = player.playbackState,
                hasError = hasError
            )
        }.getOrDefault(false)
    }

    fun resolveBackTransition(
        context: Context,
        isAlreadyInPip: Boolean,
        isFinishing: Boolean,
        hasError: Boolean = false
    ): PlayerBackAction {
        val pipSupported = LaterboxPipController.isPipSupportedAndAllowed(context.applicationContext)
        return LaterboxPipSpec.resolveBackAction(
            isActivePlayback = isActivePlaybackNow(hasError = hasError),
            isPipSupported = pipSupported,
            isAlreadyInPip = isAlreadyInPip,
            hasModalOrOverlayOpen = hasPipBlockingModal,
            isLocked = isPlayerLocked,
            isFinishing = isFinishing,
            hasSessionTarget = activePlayer != null && activeTarget != null,
            hasPlayerError = hasError
        )
    }

    fun canEnterPipOnUserLeave(
        context: Context,
        isAlreadyInPip: Boolean,
        isFinishing: Boolean,
        hasError: Boolean = false
    ): Boolean {
        val pipSupported = LaterboxPipController.isPipSupportedAndAllowed(context.applicationContext)
        return LaterboxPipSpec.resolveUserLeaveAction(
            isActivePlayback = isActivePlaybackNow(hasError = hasError),
            isPipSupported = pipSupported,
            isAlreadyInPip = isAlreadyInPip,
            hasModalOrOverlayOpen = hasPipBlockingModal,
            isLocked = isPlayerLocked,
            isFinishing = isFinishing
        ) == UserLeaveAction.ENTER_NATIVE_PIP
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
        val secondaryActivePlayer: Player?
        synchronized(lock) {
            playerToRelease = sharedVideoPlayer
            secondaryActivePlayer = activePlayer?.takeIf { it !== playerToRelease }
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
            isInPictureInPicture = false
            hasPipBlockingModal = false
            isPlayerLocked = false
            _miniPlayerState.value = null
            serviceToStop = attachedService
            serviceStartPending = false
        }
        serviceToStop?.stopPlaybackSession()
        if (secondaryActivePlayer != null) {
            runCatching { secondaryActivePlayer.stop() }
                .onFailure { err -> Log.w(TAG, "Failed stopping active player: ${err.message}") }
        }
        if (playerToRelease != null) {
            runCatching {
                playerToRelease.stop()
                playerToRelease.clearMediaItems()
            }.onFailure { err -> Log.w(TAG, "Failed stopping shared video player: ${err.message}") }
            runCatching { playerToRelease.release() }
                .onFailure { err -> Log.w(TAG, "Failed releasing shared video player: ${err.message}") }
        }
        runCatching {
            appContext.getSystemService(NotificationManager::class.java)
                ?.cancel(PlaybackSessionService.MEDIA_NOTIFICATION_ID)
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
                    setSmallIcon(R.drawable.ic_stat_laterbox)
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
        if (!LaterboxPlaybackBridge.isInPictureInPicture &&
            (player == null || !player.playWhenReady || player.playbackState == Player.STATE_ENDED)
        ) {
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
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(MEDIA_NOTIFICATION_ID)
        }
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
            .setBitmapLoader(LaterboxPlaybackBitmapLoader(this))
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
            .setSmallIcon(R.drawable.ic_stat_laterbox)
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

object LaterboxPlaybackArtworkHelper {
    private const val TAG = "LaterboxArtworkHelper"
    internal val FALLBACK_ARTWORK_URI: Uri = Uri.parse("laterbox://playback-artwork/fallback")
    private const val MAX_ARTWORK_BYTES = 6 * 1024 * 1024
    private const val CACHE_CAPACITY = 24

    private val artworkClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val byteCache = object : LinkedHashMap<String, ByteArray>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            return size > CACHE_CAPACITY
        }
    }

    @Volatile
    private var cachedFallbackPng: ByteArray? = null

    fun resolveLocalThumbnailUri(
        context: Context,
        artworkUrl: String?,
        thumbnailPath: String?,
    ): String? {
        val localFileUri = thumbnailPath
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                val file = File(raw)
                val target = if (file.isAbsolute) file else File(context.filesDir, raw)
                if (target.exists() && target.length() > 0L) {
                    Uri.fromFile(target).toString()
                } else {
                    null
                }
            }
        val cleanRemote = artworkUrl?.takeIf { it.isNotBlank() }
        if (cleanRemote != null && !cleanRemote.startsWith("http", ignoreCase = true)) {
            return cleanRemote
        }
        return localFileUri ?: cleanRemote
    }

    fun buildPlaybackMediaMetadata(
        title: String?,
        subtitle: String? = null,
        artist: String? = null,
        album: String? = null,
        artworkUrl: String? = null,
    ): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setIsPlayable(true)
        title?.takeIf { it.isNotBlank() }?.let {
            builder.setTitle(it)
            builder.setDisplayTitle(it)
        }
        subtitle?.takeIf { it.isNotBlank() }?.let { builder.setSubtitle(it) }
        artist?.takeIf { it.isNotBlank() }?.let { builder.setArtist(it) }
        album?.takeIf { it.isNotBlank() }?.let { builder.setAlbumTitle(it) }
        val parsedUri = parseSafeArtworkUri(artworkUrl)
        if (parsedUri != null) {
            builder.setArtworkUri(parsedUri)
        }
        return builder.build()
    }

    internal fun parseSafeArtworkUri(raw: String?): Uri? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase()
        return when (scheme) {
            "http", "https", "file", "content", "android.resource" -> parsed
            null -> Uri.fromFile(File(trimmed))
            else -> null
        }
    }

    fun loadArtworkOrFallbackPng(context: Context, uri: Uri): ByteArray {
        val key = uri.toString()
        if (uri == FALLBACK_ARTWORK_URI || key.isBlank()) {
            return renderBrandedFallbackArtworkPng(context)
        }
        synchronized(byteCache) {
            byteCache[key]?.let { return it }
        }
        val fetched = runCatching { fetchRawArtworkBytes(context, uri) }.getOrNull()
        val valid = fetched?.takeIf { isDecodableImage(it) }
        if (valid != null) {
            synchronized(byteCache) {
                byteCache[key] = valid
            }
            return valid
        }
        return renderBrandedFallbackArtworkPng(context)
    }

    private fun fetchRawArtworkBytes(context: Context, uri: Uri): ByteArray? {
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val req = Request.Builder()
                    .url(uri.toString())
                    .header("User-Agent", "Laterbox-Android/1.0")
                    .build()
                artworkClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val body = resp.body ?: return null
                    val len = body.contentLength()
                    if (len > MAX_ARTWORK_BYTES) return null
                    val bytes = body.bytes()
                    if (bytes.size > MAX_ARTWORK_BYTES) null else bytes
                }
            }
            "file" -> {
                val path = uri.path ?: return null
                val file = File(path)
                if (file.exists() && file.length() in 1L..MAX_ARTWORK_BYTES.toLong()) {
                    file.readBytes()
                } else {
                    null
                }
            }
            "content", "android.resource" -> {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = stream.read(chunk)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_ARTWORK_BYTES) return null
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toByteArray()
                }
            }
            else -> null
        }
    }

    private fun isDecodableImage(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth > 0 && opts.outHeight > 0
    }

    fun renderBrandedFallbackArtworkPng(context: Context): ByteArray {
        cachedFallbackPng?.let { return it }
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFF141519.toInt())

            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF1E2028.toInt()
            }
            canvas.drawRoundRect(RectF(20f, 20f, size - 20f, size - 20f), 36f, 36f, cardPaint)

            val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE5484D.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 6f
            }
            canvas.drawRoundRect(RectF(20f, 20f, size - 20f, size - 20f), 36f, 36f, accentPaint)

            val drawable = runCatching {
                ContextCompat.getDrawable(context, R.drawable.ic_stat_laterbox)
            }.getOrNull()
            if (drawable != null) {
                val wrapped = DrawableCompat.wrap(drawable.mutate())
                DrawableCompat.setTint(wrapped, 0xFFFFFFFF.toInt())
                wrapped.setBounds(68, 68, size - 68, size - 68)
                wrapped.draw(canvas)
            } else {
                val fallbackBadge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt()
                }
                canvas.drawRoundRect(RectF(88f, 88f, size - 88f, size - 88f), 18f, 18f, fallbackBadge)
            }

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            val png = out.toByteArray()
            cachedFallbackPng = png
            return png
        } finally {
            bitmap.recycle()
        }
    }

    internal fun clearCacheForTest() {
        synchronized(byteCache) {
            byteCache.clear()
        }
        cachedFallbackPng = null
    }
}

@OptIn(UnstableApi::class)
internal class LaterboxPlaybackBitmapLoader(
    context: Context,
) : BitmapLoader {
    private val appContext = context.applicationContext
    private val delegate = CacheBitmapLoader(
        DataSourceBitmapLoader(
            DataSourceBitmapLoader.DEFAULT_EXECUTOR_SERVICE.get(),
            DataSource.Factory {
                ByteArrayDataSource { uri ->
                    LaterboxPlaybackArtworkHelper.loadArtworkOrFallbackPng(appContext, uri)
                }
            }
        )
    )

    override fun supportsMimeType(mimeType: String): Boolean =
        delegate.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        delegate.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        delegate.loadBitmap(uri)

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap> {
        val rawData = metadata.artworkData
        if (rawData != null && rawData.isNotEmpty()) {
            return decodeBitmap(rawData)
        }
        val uri = metadata.artworkUri
            ?: LaterboxPlaybackArtworkHelper.parseSafeArtworkUri(LaterboxPlaybackBridge.activeArtworkUrl)
            ?: LaterboxPlaybackArtworkHelper.FALLBACK_ARTWORK_URI
        return loadBitmap(uri)
    }
}
