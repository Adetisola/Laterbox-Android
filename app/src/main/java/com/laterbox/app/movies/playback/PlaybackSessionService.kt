package com.laterbox.app.movies.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
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

/**
 * Coordinates the currently active video/audio [Player] with [PlaybackSessionService] so
 * background playback, Android media notifications, lock-screen controls, and Bluetooth/headset
 * transport controls stay synchronized without creating duplicate ExoPlayer instances.
 */
object LaterboxPlaybackBridge {
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
    private var attachedService: PlaybackSessionService? = null

    fun attachPlayer(
        context: Context,
        player: Player,
        title: String?,
        subtitle: String? = null
    ) {
        val appContext = context.applicationContext
        val previous = activePlayer
        activePlayer = player
        activeTitle = title?.takeIf { it.isNotBlank() }
        activeSubtitle = subtitle?.takeIf { it.isNotBlank() }

        val service = attachedService
        if (service != null) {
            service.syncWithPlayer(player)
        } else if (previous == null || previous !== player) {
            startServiceSafely(appContext)
        }
    }

    fun updateMetadata(title: String?, subtitle: String? = null) {
        activeTitle = title?.takeIf { it.isNotBlank() }
        activeSubtitle = subtitle?.takeIf { it.isNotBlank() }
    }

    fun releasePlayer(context: Context, player: Player) {
        if (activePlayer === player) {
            stopAndDetach(context)
        }
        runCatching { player.release() }
    }

    fun stopAndDetach(context: Context) {
        val appContext = context.applicationContext
        activePlayer = null
        activeTitle = null
        activeSubtitle = null
        attachedService?.stopPlaybackSession()
        runCatching {
            appContext.stopService(Intent(appContext, PlaybackSessionService::class.java))
        }
    }

    internal fun onServiceBound(service: PlaybackSessionService) {
        attachedService = service
        activePlayer?.let { service.syncWithPlayer(it) }
    }

    internal fun onServiceDestroyed(service: PlaybackSessionService) {
        if (attachedService === service) {
            attachedService = null
        }
    }

    private fun startServiceSafely(appContext: Context) {
        runCatching {
            val intent = Intent(appContext, PlaybackSessionService::class.java)
            ContextCompat.startForegroundService(appContext, intent)
        }
    }
}

@OptIn(UnstableApi::class)
class PlaybackSessionService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var fallbackPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = LaterboxPlaybackBridge.activePlayer
        if (player == null) {
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
            stopPlaybackSession()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
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
        val existing = mediaSession
        if (existing != null) {
            if (existing.player !== player) {
                existing.player = player
            }
            return existing
        }

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            SESSION_ACTIVITY_REQUEST_CODE,
            sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val session = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
        addSession(session)
        mediaSession = session
        return session
    }

    internal fun currentSession(): MediaSession? = mediaSession

    private fun releaseSessionInternal() {
        mediaSession?.let { session ->
            runCatching { removeSession(session) }
            runCatching { session.release() }
        }
        mediaSession = null
        fallbackPlayer?.runCatching { release() }
        fallbackPlayer = null
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
