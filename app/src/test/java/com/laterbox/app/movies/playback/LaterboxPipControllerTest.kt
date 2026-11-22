package com.laterbox.app.movies.playback

import androidx.media3.common.Player
import com.laterbox.app.ui.components.LaterboxIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class LaterboxPipControllerTest {

    @Test
    fun shouldEnterPipOnBack_alwaysReturnsFalseSoBackTransitionsToMiniPlayer() {
        assertFalse(LaterboxPipSpec.shouldEnterPipOnBack())
    }

    @Test
    fun shouldEnterPipOnUserLeaveHint_onlyWhenPlayingSupportedNotLockedNotFinishingAndNoModalOpen() {
        assertTrue(
            LaterboxPipSpec.shouldEnterPipOnUserLeaveHint(
                isPlaying = true,
                isPipSupported = true,
                isAlreadyInPip = false,
                hasModalOrOverlayOpen = false,
                isLocked = false,
                isFinishing = false
            )
        )
        // Paused or stopped playback does not enter PiP
        assertFalse(
            LaterboxPipSpec.shouldEnterPipOnUserLeaveHint(
                isPlaying = false,
                isPipSupported = true,
                isAlreadyInPip = false,
                hasModalOrOverlayOpen = false,
                isLocked = false,
                isFinishing = false
            )
        )
        // Unsupported or OS-disabled PiP falls back cleanly
        assertFalse(
            LaterboxPipSpec.shouldEnterPipOnUserLeaveHint(
                isPlaying = true,
                isPipSupported = false,
                isAlreadyInPip = false,
                hasModalOrOverlayOpen = false,
                isLocked = false,
                isFinishing = false
            )
        )
        // Already in PiP, modal open, locked screen, or finishing (Back) must not enter PiP
        assertFalse(
            LaterboxPipSpec.shouldEnterPipOnUserLeaveHint(
                isPlaying = true,
                isPipSupported = true,
                isAlreadyInPip = true
            )
        )
        assertFalse(
            LaterboxPipSpec.shouldEnterPipOnUserLeaveHint(
                isPlaying = true,
                isPipSupported = true,
                hasModalOrOverlayOpen = true
            )
        )
        assertFalse(
            LaterboxPipSpec.shouldEnterPipOnUserLeaveHint(
                isPlaying = true,
                isPipSupported = true,
                isLocked = true
            )
        )
        assertFalse(
            LaterboxPipSpec.shouldEnterPipOnUserLeaveHint(
                isPlaying = true,
                isPipSupported = true,
                isFinishing = true
            )
        )
    }

    @Test
    fun buildPipActionsDescriptor_generatesThreeControlsDrivenBySeekPreferenceAndPlayState() {
        val playingActions = LaterboxPipSpec.buildPipActionsDescriptor(
            isPlaying = true,
            doubleTapSeekSeconds = 15
        )
        assertEquals(3, playingActions.size)
        assertEquals(PipActionType.REWIND, playingActions[0].type)
        assertEquals("Rewind 15s", playingActions[0].title)
        assertEquals(LaterboxIcons.Replay10, playingActions[0].iconRes)

        assertEquals(PipActionType.PLAY_PAUSE, playingActions[1].type)
        assertEquals("Pause", playingActions[1].title)
        assertEquals(LaterboxIcons.Pause, playingActions[1].iconRes)

        assertEquals(PipActionType.FORWARD, playingActions[2].type)
        assertEquals("Forward 15s", playingActions[2].title)
        assertEquals(LaterboxIcons.Forward10, playingActions[2].iconRes)

        val pausedActions = LaterboxPipSpec.buildPipActionsDescriptor(
            isPlaying = false,
            doubleTapSeekSeconds = 30
        )
        assertEquals("Rewind 30s", pausedActions[0].title)
        assertEquals("Play", pausedActions[1].title)
        assertEquals(LaterboxIcons.Play, pausedActions[1].iconRes)
        assertEquals("Forward 30s", pausedActions[2].title)
    }

    @Test
    fun computeSeekTargetMs_clampsAtZeroAndDurationAcrossPreferenceIntervals() {
        assertEquals(5_000L, LaterboxPipSpec.resolveSeekDeltaMs(5))
        assertEquals(10_000L, LaterboxPipSpec.resolveSeekDeltaMs(10))
        assertEquals(15_000L, LaterboxPipSpec.resolveSeekDeltaMs(15))
        assertEquals(30_000L, LaterboxPipSpec.resolveSeekDeltaMs(30))

        // Rewind clamps at 0
        assertEquals(0L, LaterboxPipSpec.computeSeekTargetMs(currentPositionMs = 8_000L, durationMs = 120_000L, deltaMs = -15_000L))
        // Forward clamps at duration
        assertEquals(120_000L, LaterboxPipSpec.computeSeekTargetMs(currentPositionMs = 112_000L, durationMs = 120_000L, deltaMs = 15_000L))
        // Middle seek moves accurately
        assertEquals(75_000L, LaterboxPipSpec.computeSeekTargetMs(currentPositionMs = 60_000L, durationMs = 120_000L, deltaMs = 15_000L))
    }

    @Test
    fun playbackBridge_dispatchPipAction_controlsActivePlayerPlayPauseAndSeek() {
        var playing = true
        var playWhenReady = true
        var positionMs = 40_000L
        val durationMs = 100_000L
        var playbackState = Player.STATE_READY

        val proxyPlayer = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, method, args ->
            when (method.name) {
                "isPlaying" -> playing
                "getPlayWhenReady" -> playWhenReady
                "getCurrentPosition" -> positionMs
                "getDuration" -> durationMs
                "getPlaybackState" -> playbackState
                "seekTo" -> {
                    val pos = args?.lastOrNull() as? Long ?: 0L
                    positionMs = pos
                    Unit
                }
                "pause" -> {
                    playing = false
                    playWhenReady = false
                    Unit
                }
                "play" -> {
                    playing = true
                    playWhenReady = true
                    Unit
                }
                "equals" -> false
                "hashCode" -> 42
                "toString" -> "FakePlayer"
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    else -> null
                }
            }
        } as Player

        // Inject activePlayer via reflection-free attach? Actually attachPlayer starts service on Context,
        // so set activePlayer via field helper or expose internal test setter? Let's test via internal helper!
        LaterboxPlaybackBridge.setActivePlayerForTest(proxyPlayer)
        try {
            LaterboxPlaybackBridge.updateDoubleTapSeekSeconds(15)

            // Forward +15s
            assertTrue(LaterboxPlaybackBridge.dispatchPipAction(PipActionType.FORWARD))
            assertEquals(55_000L, positionMs)

            // Rewind -15s twice -> 25_000L
            assertTrue(LaterboxPlaybackBridge.dispatchPipAction(PipActionType.REWIND))
            assertEquals(40_000L, positionMs)

            // Pause
            assertFalse(LaterboxPlaybackBridge.dispatchPipAction(PipActionType.PLAY_PAUSE))
            assertFalse(playing)

            // Play from ended seeks back to 0 and resumes
            playbackState = Player.STATE_ENDED
            positionMs = durationMs
            assertTrue(LaterboxPlaybackBridge.dispatchPipAction(PipActionType.PLAY_PAUSE))
            assertTrue(playing)
            assertEquals(0L, positionMs)
        } finally {
            LaterboxPlaybackBridge.setActivePlayerForTest(null)
        }
    }

    @Test
    fun pipSpec_backAndUserLeaveTransitions_differentiateActiveVsPausedPlayback() {
        // Active playing -> native PiP on Back and Home/Recents
        assertTrue(LaterboxPipSpec.isIntendedActivePlayback(isPlaying = true, playWhenReady = true, playbackState = Player.STATE_READY))
        assertEquals(
            PlayerBackAction.ENTER_NATIVE_PIP,
            LaterboxPipSpec.resolveBackAction(
                isActivePlayback = true,
                isPipSupported = true,
                isAlreadyInPip = false,
                hasModalOrOverlayOpen = false,
                isLocked = false,
                isFinishing = false,
                hasSessionTarget = true
            )
        )
        assertEquals(
            UserLeaveAction.ENTER_NATIVE_PIP,
            LaterboxPipSpec.resolveUserLeaveAction(
                isActivePlayback = true,
                isPipSupported = true,
                isAlreadyInPip = false,
                hasModalOrOverlayOpen = false,
                isLocked = false,
                isFinishing = false
            )
        )

        // Buffering with playWhenReady=true counts as active intended playback
        assertTrue(LaterboxPipSpec.isIntendedActivePlayback(isPlaying = false, playWhenReady = true, playbackState = Player.STATE_BUFFERING))

        // Paused playback -> in-app Mini-Player on Back, STAY_IN_PLAYER on Home
        assertFalse(LaterboxPipSpec.isIntendedActivePlayback(isPlaying = false, playWhenReady = false, playbackState = Player.STATE_READY))
        assertEquals(
            PlayerBackAction.ENTER_MINI_PLAYER,
            LaterboxPipSpec.resolveBackAction(
                isActivePlayback = false,
                isPipSupported = true,
                isAlreadyInPip = false,
                hasModalOrOverlayOpen = false,
                isLocked = false,
                isFinishing = false,
                hasSessionTarget = true
            )
        )
        assertEquals(
            UserLeaveAction.DO_NOTHING,
            LaterboxPipSpec.resolveUserLeaveAction(
                isActivePlayback = false,
                isPipSupported = true,
                isAlreadyInPip = false,
                hasModalOrOverlayOpen = false,
                isLocked = false,
                isFinishing = false
            )
        )

        // Active playback when device doesn't support PiP -> graceful Mini-Player on Back
        assertEquals(
            PlayerBackAction.ENTER_MINI_PLAYER,
            LaterboxPipSpec.resolveBackAction(
                isActivePlayback = true,
                isPipSupported = false,
                isAlreadyInPip = false,
                hasModalOrOverlayOpen = false,
                isLocked = false,
                isFinishing = false,
                hasSessionTarget = true
            )
        )

        // Player error or ended with no session stops and closes
        assertFalse(LaterboxPipSpec.isIntendedActivePlayback(isPlaying = true, playWhenReady = true, playbackState = Player.STATE_READY, hasError = true))
        assertEquals(
            PlayerBackAction.STOP_AND_CLOSE,
            LaterboxPipSpec.resolveBackAction(
                isActivePlayback = false,
                isPipSupported = true,
                isAlreadyInPip = false,
                hasModalOrOverlayOpen = false,
                isLocked = false,
                isFinishing = false,
                hasSessionTarget = false,
                hasPlayerError = true
            )
        )
    }
}
