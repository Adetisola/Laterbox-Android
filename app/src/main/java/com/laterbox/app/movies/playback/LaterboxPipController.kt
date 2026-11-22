package com.laterbox.app.movies.playback

import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Process
import android.util.Log
import android.util.Rational
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.compose.runtime.compositionLocalOf
import com.laterbox.app.ui.components.LaterboxIcons

enum class PipActionType(val actionString: String, val requestCode: Int) {
    REWIND("com.laterbox.app.action.PIP_REWIND", 5101),
    PLAY_PAUSE("com.laterbox.app.action.PIP_PLAY_PAUSE", 5102),
    FORWARD("com.laterbox.app.action.PIP_FORWARD", 5103);

    companion object {
        fun fromAction(action: String?): PipActionType? =
            entries.firstOrNull { it.actionString == action }
    }
}

data class PipActionSpec(
    val type: PipActionType,
    val title: String,
    val contentDescription: String,
    @DrawableRes val iconRes: Int
)

enum class PlayerBackAction {
    ENTER_NATIVE_PIP,
    ENTER_MINI_PLAYER,
    STOP_AND_CLOSE
}

enum class UserLeaveAction {
    ENTER_NATIVE_PIP,
    DO_NOTHING
}

enum class PipStopAction {
    NONE,
    STOP_PLAYBACK_AND_FINISH
}

/**
 * Pure Kotlin/JVM lifecycle state machine distinguishing PiP window dismissal
 * (PiP exit followed by onStop without returning to fullscreen onResume) from
 * expanding PiP back to fullscreen, configuration changes, or transitioning to
 * the in-app mini-player.
 */
class LaterboxPipLifecycleTracker {
    var isInPip: Boolean = false
        private set
    var wasInPipSession: Boolean = false
        private set
    var returnedToFullscreenSincePipExit: Boolean = false
        private set
    var isActivityStopped: Boolean = false
        private set

    fun onStarted() {
        isActivityStopped = false
    }

    fun onPipModeChanged(nowInPip: Boolean) {
        isInPip = nowInPip
        if (nowInPip) {
            wasInPipSession = true
            returnedToFullscreenSincePipExit = false
            isActivityStopped = false
        }
    }

    fun onResumed(currentlyInPip: Boolean) {
        isInPip = currentlyInPip
        isActivityStopped = false
        if (!currentlyInPip) {
            wasInPipSession = false
            returnedToFullscreenSincePipExit = true
        }
    }

    fun onTransitionToMiniPlayer() {
        isInPip = false
        wasInPipSession = false
        returnedToFullscreenSincePipExit = false
        isActivityStopped = false
    }

    fun evaluateOnPipModeChanged(
        nowInPip: Boolean,
        isChangingConfigurations: Boolean,
        isRetainedForMiniPlayer: Boolean
    ): PipStopAction {
        onPipModeChanged(nowInPip)
        if (isChangingConfigurations || isRetainedForMiniPlayer) {
            return PipStopAction.NONE
        }
        if (!nowInPip && isActivityStopped && wasInPipSession && !returnedToFullscreenSincePipExit) {
            wasInPipSession = false
            isInPip = false
            return PipStopAction.STOP_PLAYBACK_AND_FINISH
        }
        return PipStopAction.NONE
    }

    fun evaluateOnStop(
        isCurrentlyInPip: Boolean,
        isChangingConfigurations: Boolean,
        isRetainedForMiniPlayer: Boolean
    ): PipStopAction {
        isActivityStopped = true
        if (isChangingConfigurations || isRetainedForMiniPlayer) {
            return PipStopAction.NONE
        }
        if (wasInPipSession && !isCurrentlyInPip && !returnedToFullscreenSincePipExit) {
            wasInPipSession = false
            isInPip = false
            return PipStopAction.STOP_PLAYBACK_AND_FINISH
        }
        return PipStopAction.NONE
    }
}

/**
 * Pure Kotlin/JVM specification helper for Laterbox native Picture-in-Picture behavior
 * so PiP decision logic, seek delta clamping, and RemoteAction metadata can be unit-tested cleanly.
 */
object LaterboxPipSpec {

    private const val STATE_IDLE = 1
    private const val STATE_BUFFERING = 2
    private const val STATE_READY = 3
    private const val STATE_ENDED = 4

    fun normalizeSeekSeconds(doubleTapSeekSeconds: Int): Int =
        doubleTapSeekSeconds.coerceIn(5, 30)

    fun resolveSeekDeltaMs(doubleTapSeekSeconds: Int): Long =
        normalizeSeekSeconds(doubleTapSeekSeconds) * 1_000L

    fun computeSeekTargetMs(
        currentPositionMs: Long,
        durationMs: Long,
        deltaMs: Long
    ): Long {
        val safeCurrent = currentPositionMs.coerceAtLeast(0L)
        val rawTarget = (safeCurrent + deltaMs).coerceAtLeast(0L)
        return if (durationMs > 0L) rawTarget.coerceAtMost(durationMs) else rawTarget
    }

    /**
     * Classifies whether the player currently has active playback intent.
     * Temporarily buffering streams with playWhenReady=true count as active playback;
     * explicitly paused, IDLE, ENDED, or failed playback never count as active playback.
     */
    fun isIntendedActivePlayback(
        isPlaying: Boolean,
        playWhenReady: Boolean,
        playbackState: Int,
        hasError: Boolean = false
    ): Boolean {
        if (hasError || playbackState == STATE_IDLE || playbackState == STATE_ENDED) return false
        if (isPlaying) return true
        return playWhenReady && (playbackState == STATE_BUFFERING || playbackState == STATE_READY)
    }

    fun shouldEnterPipOnBack(
        isActivePlayback: Boolean = false,
        isPipSupported: Boolean = false,
        isAlreadyInPip: Boolean = false,
        hasModalOrOverlayOpen: Boolean = false,
        isLocked: Boolean = false,
        isFinishing: Boolean = false
    ): Boolean = isActivePlayback && isPipSupported && !isAlreadyInPip && !hasModalOrOverlayOpen && !isLocked && !isFinishing

    fun resolveBackAction(
        isActivePlayback: Boolean,
        isPipSupported: Boolean,
        isAlreadyInPip: Boolean = false,
        hasModalOrOverlayOpen: Boolean = false,
        isLocked: Boolean = false,
        isFinishing: Boolean = false,
        hasSessionTarget: Boolean = true,
        hasPlayerError: Boolean = false
    ): PlayerBackAction {
        if (shouldEnterPipOnBack(
                isActivePlayback = isActivePlayback,
                isPipSupported = isPipSupported,
                isAlreadyInPip = isAlreadyInPip,
                hasModalOrOverlayOpen = hasModalOrOverlayOpen,
                isLocked = isLocked,
                isFinishing = isFinishing
            )
        ) {
            return PlayerBackAction.ENTER_NATIVE_PIP
        }
        return if (hasSessionTarget && !hasPlayerError) {
            PlayerBackAction.ENTER_MINI_PLAYER
        } else {
            PlayerBackAction.STOP_AND_CLOSE
        }
    }

    fun shouldEnterPipOnUserLeaveHint(
        isPlaying: Boolean,
        isPipSupported: Boolean,
        isAlreadyInPip: Boolean = false,
        hasModalOrOverlayOpen: Boolean = false,
        isLocked: Boolean = false,
        isFinishing: Boolean = false
    ): Boolean = isPlaying && isPipSupported && !isAlreadyInPip && !hasModalOrOverlayOpen && !isLocked && !isFinishing

    fun resolveUserLeaveAction(
        isActivePlayback: Boolean,
        isPipSupported: Boolean,
        isAlreadyInPip: Boolean = false,
        hasModalOrOverlayOpen: Boolean = false,
        isLocked: Boolean = false,
        isFinishing: Boolean = false
    ): UserLeaveAction =
        if (shouldEnterPipOnUserLeaveHint(
                isPlaying = isActivePlayback,
                isPipSupported = isPipSupported,
                isAlreadyInPip = isAlreadyInPip,
                hasModalOrOverlayOpen = hasModalOrOverlayOpen,
                isLocked = isLocked,
                isFinishing = isFinishing
            )
        ) {
            UserLeaveAction.ENTER_NATIVE_PIP
        } else {
            UserLeaveAction.DO_NOTHING
        }

    fun buildPipActionsDescriptor(
        isPlaying: Boolean,
        doubleTapSeekSeconds: Int
    ): List<PipActionSpec> {
        val seekSec = normalizeSeekSeconds(doubleTapSeekSeconds)
        return listOf(
            PipActionSpec(
                type = PipActionType.REWIND,
                title = "Rewind ${seekSec}s",
                contentDescription = "Rewind $seekSec seconds",
                iconRes = LaterboxIcons.Replay10
            ),
            PipActionSpec(
                type = PipActionType.PLAY_PAUSE,
                title = if (isPlaying) "Pause" else "Play",
                contentDescription = if (isPlaying) "Pause video" else "Play video",
                iconRes = if (isPlaying) LaterboxIcons.Pause else LaterboxIcons.Play
            ),
            PipActionSpec(
                type = PipActionType.FORWARD,
                title = "Forward ${seekSec}s",
                contentDescription = "Fast-forward $seekSec seconds",
                iconRes = LaterboxIcons.Forward10
            )
        )
    }
}

val LocalIsInPictureInPictureMode = compositionLocalOf { false }

object LaterboxPipController {
    private const val TAG = "LaterboxPipController"

    fun isPipSupportedAndAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val hasFeature = runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        }.getOrDefault(false)
        if (!hasFeature) return false

        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return true
        @Suppress("DEPRECATION")
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                    Process.myUid(),
                    context.packageName
                )
            }
        }.getOrDefault(AppOpsManager.MODE_ALLOWED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun buildPictureInPictureParams(
        context: Context,
        isPlaying: Boolean,
        doubleTapSeekSeconds: Int,
        aspectRatio: Rational = Rational(16, 9),
        autoEnterEnabled: Boolean = false
    ): PictureInPictureParams {
        val clampedAspectRatio = clampPipAspectRatio(aspectRatio)
        val specs = LaterboxPipSpec.buildPipActionsDescriptor(isPlaying, doubleTapSeekSeconds)
        val actions = specs.map { spec ->
            val intent = Intent(spec.type.actionString).apply {
                setPackage(context.packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                spec.type.requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            RemoteAction(
                Icon.createWithResource(context, spec.iconRes),
                spec.title,
                spec.contentDescription,
                pendingIntent
            )
        }
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(clampedAspectRatio)
            .setActions(actions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnterEnabled)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    fun tryEnterPictureInPicture(
        activity: Activity?,
        isPlaying: Boolean,
        doubleTapSeekSeconds: Int,
        aspectRatio: Rational = Rational(16, 9)
    ): Boolean {
        val act = activity ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!isPipSupportedAndAllowed(act)) return false
        if (act.isFinishing || act.isDestroyed || act.isInPictureInPictureMode) return false
        return runCatching {
            val params = buildPictureInPictureParams(
                context = act,
                isPlaying = isPlaying,
                doubleTapSeekSeconds = doubleTapSeekSeconds,
                aspectRatio = aspectRatio,
                autoEnterEnabled = isPlaying
            )
            act.enterPictureInPictureMode(params).also { entered ->
                if (entered) {
                    (act.applicationContext as? com.laterbox.app.LaterboxApplication)
                        ?.container
                        ?.analyticsTracker
                        ?.trackFeatureUsed("pip_playback")
                }
            }
        }.onFailure { err ->
            Log.w(TAG, "Unable to enter Picture-in-Picture: ${err.message}")
        }.getOrDefault(false)
    }

    fun updatePictureInPictureParams(
        activity: Activity?,
        isPlaying: Boolean,
        doubleTapSeekSeconds: Int,
        aspectRatio: Rational = Rational(16, 9),
        autoEnterEnabled: Boolean = false
    ) {
        val act = activity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!isPipSupportedAndAllowed(act)) return
        if (act.isFinishing || act.isDestroyed) return
        runCatching {
            val params = buildPictureInPictureParams(
                context = act,
                isPlaying = isPlaying,
                doubleTapSeekSeconds = doubleTapSeekSeconds,
                aspectRatio = aspectRatio,
                autoEnterEnabled = autoEnterEnabled
            )
            act.setPictureInPictureParams(params)
        }.onFailure { err ->
            Log.w(TAG, "Failed updating PiP params: ${err.message}")
        }
    }

    fun disableAutoEnterPip(activity: Activity?) {
        val act = activity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!isPipSupportedAndAllowed(act) || act.isDestroyed) return
        runCatching {
            val params = PictureInPictureParams.Builder()
                .setAutoEnterEnabled(false)
                .build()
            act.setPictureInPictureParams(params)
        }.onFailure { err ->
            Log.w(TAG, "Failed disabling auto-enter PiP: ${err.message}")
        }
    }

    fun buildIntentFilter(): IntentFilter = IntentFilter().apply {
        PipActionType.entries.forEach { addAction(it.actionString) }
    }

    private fun clampPipAspectRatio(rational: Rational): Rational {
        if (rational.denominator <= 0 || rational.numerator <= 0) return Rational(16, 9)
        val value = rational.toFloat()
        return when {
            value < 0.45f -> Rational(9, 19)
            value > 2.35f -> Rational(21, 9)
            else -> rational
        }
    }
}

class LaterboxPipActionReceiver(
    private val activityProvider: () -> Activity?
) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val actionType = PipActionType.fromAction(intent?.action) ?: return
        val nowPlaying = LaterboxPlaybackBridge.dispatchPipAction(actionType)
        val seekSec = LaterboxPlaybackBridge.activeDoubleTapSeekSeconds
        LaterboxPipController.updatePictureInPictureParams(
            activity = activityProvider(),
            isPlaying = nowPlaying,
            doubleTapSeekSeconds = seekSec
        )
    }
}
