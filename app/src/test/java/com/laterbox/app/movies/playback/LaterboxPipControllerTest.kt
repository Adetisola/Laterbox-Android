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
    fun shouldEnterPipOnBack_onlyWhenPlayingSupportedNotLockedAndNoModalOpen() {
        assertTrue(
            LaterboxPipSpec.shouldEnterPipOnBack(
                isPlaying = true,
                isPipSupported = true,
                isAlreadyInPip = false,
                hasModalOrOverlayOpen = false,
                isLocked = false
            )
        )
        // Paused or stopped playback exits normally instead of entering PiP
        assertFalse(
            LaterboxPipSpec.shouldEnterPipOnBack(
                isPlaying = false,
                isPipSupported = true,
                isAlreadyInPip = false,
                hasModalOrOverlayOpen = false,
                isLocked = false
            )
        )
        // Unsupported or OS-disabled PiP falls back cleanly to normal exit
        assertFalse(
            LaterboxPipSpec.shouldEnterPipOnBack(
                isPlaying = true,
                isPipSupported = false,
                isAlreadyInPip = false,
                hasModalOrOverlayOpen = false,
                isLocked = false
            )
        )
        // Already in PiP or modal open or locked screen should not re-enter PiP
        assertFalse(
            LaterboxPipSpec.shouldEnterPipOnBack(
                isPlaying = true,
                isPipSupported = true,
                isAlreadyInPip = true
            )
        )
        assertFalse(
            LaterboxPipSpec.shouldEnterPipOnBack(
                isPlaying = true,
                isPipSupported = true,
                hasModalOrOverlayOpen = true
            )
        )
        assertFalse(
            LaterboxPipSpec.shouldEnterPipOnBack(
                isPlaying = true,
                isPipSupported = true,
                isLocked = true
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
}
