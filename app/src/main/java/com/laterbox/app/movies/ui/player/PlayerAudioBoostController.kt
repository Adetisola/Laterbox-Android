package com.laterbox.app.movies.ui.player

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.roundToInt

/**
 * Coordinates 0–100% device media stream volume and 101–200% ExoPlayer-scoped audio boost
 * using Android's [LoudnessEnhancer] bound cleanly to ExoPlayer's audio session ID.
 *
 * ExoPlayer.setVolume is strictly clamped in 0f..1f and is never passed values above 1.0f.
 * Boost is session-scoped, capped at [MAX_BOOST_GAIN_MB] (+900 mB) to avoid harsh clipping,
 * and gracefully falls back if a device or codec does not permit LoudnessEnhancer attachment.
 */
class PlayerAudioBoostController(
    context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var attachedSessionId: Int = 0
    private var attachedPlayer: ExoPlayer? = null
    private var sessionListener: Player.Listener? = null

    var isBoostSupported: Boolean = true
        private set

    var lastFallbackReason: String? = null
        private set

    var currentVolumePercent: Int = readInitialDeviceVolumePercent()
        private set

    fun readInitialDeviceVolumePercent(): Int {
        val mgr = audioManager ?: return 100
        val max = mgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = mgr.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        return ((current.toFloat() / max.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
    }

    @OptIn(UnstableApi::class)
    fun bindToPlayer(exoPlayer: ExoPlayer) {
        if (attachedPlayer === exoPlayer) {
            attachEnhancerToSession(exoPlayer.audioSessionId)
            applyVolumePercent(currentVolumePercent, exoPlayer)
            return
        }

        detachFromPlayer()
        attachedPlayer = exoPlayer

        val listener = object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachEnhancerToSession(audioSessionId)
                applyBoostForPercent(currentVolumePercent)
            }
        }
        sessionListener = listener
        exoPlayer.addListener(listener)

        attachEnhancerToSession(exoPlayer.audioSessionId)
        applyVolumePercent(currentVolumePercent, exoPlayer)
    }

    fun applyVolumePercent(rawPercent: Int, player: ExoPlayer? = attachedPlayer): Int {
        val targetPercent = clampEffectivePercent(rawPercent, isBoostSupported)
        currentVolumePercent = targetPercent

        // Keep ExoPlayer volume strictly in 0f..1f (0f only at 0%, 1f otherwise).
        player?.volume = computeExoPlayerVolume(targetPercent)

        val mgr = audioManager
        if (mgr != null) {
            val max = mgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val streamPercent = targetPercent.coerceIn(0, 100)
            val desiredIndex = computeStreamVolumeIndex(streamPercent, max)
            val existingIndex = mgr.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (existingIndex != desiredIndex) {
                runCatching {
                    mgr.setStreamVolume(AudioManager.STREAM_MUSIC, desiredIndex, 0)
                }
            }
        }

        applyBoostForPercent(targetPercent)
        return targetPercent
    }

    fun resetBoost(player: ExoPlayer? = attachedPlayer) {
        if (currentVolumePercent > 100) {
            currentVolumePercent = 100
        }
        player?.volume = computeExoPlayerVolume(currentVolumePercent)
        applyBoostForPercent(currentVolumePercent)
    }

    fun release() {
        resetBoost(attachedPlayer)
        detachFromPlayer()
        releaseEnhancerOnly()
    }

    private fun detachFromPlayer() {
        val player = attachedPlayer
        val listener = sessionListener
        if (player != null && listener != null) {
            runCatching { player.removeListener(listener) }
        }
        attachedPlayer = null
        sessionListener = null
    }

    private fun attachEnhancerToSession(sessionId: Int) {
        if (sessionId <= 0) return
        if (attachedSessionId == sessionId && loudnessEnhancer != null) return

        releaseEnhancerOnly()
        try {
            val enhancer = LoudnessEnhancer(sessionId)
            enhancer.setTargetGain(computeGainMillibels(currentVolumePercent))
            enhancer.enabled = isBoostActive(currentVolumePercent)
            loudnessEnhancer = enhancer
            attachedSessionId = sessionId
            isBoostSupported = true
            lastFallbackReason = null
        } catch (err: Throwable) {
            Log.w(TAG, "LoudnessEnhancer unavailable for session=$sessionId: ${err.message}")
            isBoostSupported = false
            lastFallbackReason = "Boost unavailable on this audio output"
            if (currentVolumePercent > 100) {
                currentVolumePercent = 100
            }
        }
    }

    private fun applyBoostForPercent(percent: Int) {
        val enhancer = loudnessEnhancer ?: return
        val gainMb = computeGainMillibels(percent)
        val shouldEnable = isBoostActive(percent) && isBoostSupported
        runCatching {
            enhancer.setTargetGain(if (shouldEnable) gainMb else 0)
            enhancer.enabled = shouldEnable
        }.onFailure { err ->
            Log.w(TAG, "Failed updating LoudnessEnhancer gain: ${err.message}")
            isBoostSupported = false
            lastFallbackReason = "Boost unavailable for this audio format"
        }
    }

    private fun releaseEnhancerOnly() {
        loudnessEnhancer?.let { enhancer ->
            runCatching {
                enhancer.setTargetGain(0)
                enhancer.enabled = false
                enhancer.release()
            }
        }
        loudnessEnhancer = null
        attachedSessionId = 0
    }

    companion object {
        private const val TAG = "PlayerAudioBoost"
        const val MAX_PERCENT = 200
        const val NORMAL_MAX_PERCENT = 100
        const val MAX_BOOST_GAIN_MB = 900

        fun computeGainMillibels(percent: Int): Int {
            if (percent <= NORMAL_MAX_PERCENT) return 0
            val clamped = percent.coerceIn(NORMAL_MAX_PERCENT, MAX_PERCENT)
            val ratio = (clamped - NORMAL_MAX_PERCENT) / 100f
            return (ratio * MAX_BOOST_GAIN_MB).roundToInt().coerceIn(0, MAX_BOOST_GAIN_MB)
        }

        fun isBoostActive(percent: Int): Boolean = percent > NORMAL_MAX_PERCENT

        fun computeExoPlayerVolume(percent: Int): Float =
            if (percent <= 0) 0f else 1.0f

        fun computeStreamVolumeIndex(percent: Int, maxIndex: Int): Int {
            val safeMax = maxIndex.coerceAtLeast(1)
            val normalized = percent.coerceIn(0, NORMAL_MAX_PERCENT) / 100f
            return (normalized * safeMax).roundToInt().coerceIn(0, safeMax)
        }

        fun clampEffectivePercent(percent: Int, boostSupported: Boolean): Int {
            val maxAllowed = if (boostSupported) MAX_PERCENT else NORMAL_MAX_PERCENT
            return percent.coerceIn(0, maxAllowed)
        }
    }
}
