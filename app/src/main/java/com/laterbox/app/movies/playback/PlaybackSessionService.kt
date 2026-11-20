package com.laterbox.app.movies.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Coordinates the currently active video/audio [Player] with [PlaybackSessionService] so
 * background playback, Android media notifications, lock-screen controls, and Bluetooth/headset
 * transport controls stay synchronized without creating duplicate ExoPlayer instances.
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
    private var attachedService: PlaybackSessionService? = null

    @Volatile
    private var serviceStartPending: Boolean = false

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

    fun updateMetadata(title: String?, subtitle: String? = null) {
        synchronized(lock) {
            activeTitle = title?.takeIf { it.isNotBlank() }
            activeSubtitle = subtitle?.takeIf { it.isNotBlank() }
        }
    }

    fun releasePlayer(context: Context, player: Player) {
        val appContext = context.applicationContext
        val serviceToStop: PlaybackSessionService?
        val shouldStopServiceIntent: Boolean
        val alreadyReleased: Boolean
        synchronized(lock) {
            alreadyReleased = !releasedPlayers.add(player)
            if (activePlayer === player) {
                activePlayer = null
                activeTitle = null
                activeSubtitle = null
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
        synchronized(lock) {
            activePlayer = null
            activeTitle = null
            activeSubtitle = null
            serviceToStop = attachedService
            serviceStartPending = false
        }
        serviceToStop?.stopPlaybackSession()
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
    private var fallbackPlayer: ExoPlayer? = null

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = LaterboxPlaybackBridge.activePlayer
        if (player == null) {
            // Satisfy Android 8+/12+ startForegroundService() contract before stopSelf()
            // in case the player was closed while the service was still starting up.
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
