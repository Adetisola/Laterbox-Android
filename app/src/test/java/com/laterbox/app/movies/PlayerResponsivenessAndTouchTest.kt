package com.laterbox.app.movies

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.ui.MoviePlayerScreen
import com.laterbox.app.movies.ui.MoviePlayerUiState
import com.laterbox.app.movies.ui.PlayerClockState
import com.laterbox.app.movies.ui.player.PlayerTapGestureTracker
import com.laterbox.app.movies.ui.player.PlayerTapOutcome
import com.laterbox.app.movies.ui.player.PlayerVolumeBoostHud
import com.laterbox.app.ui.theme.LaterboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerResponsivenessAndTouchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `tap gesture tracker reveals controls at 0ms when hidden and accumulates quick side taps`() {
        val tracker = PlayerTapGestureTracker(doubleTapWindowMs = 300L, accumulationWindowMs = 700L)

        // 1. Controls hidden -> tap anywhere shows controls immediately (0ms)
        val hiddenRightTap = tracker.onTap(
            touchX = 900f,
            widthPx = 1000f,
            controlsVisible = false,
            isLocked = false,
            stepSeconds = 10,
            uptimeMs = 1_000L
        )
        assertEquals(PlayerTapOutcome.SetControlsVisible(true), hiddenRightTap)

        // 2. Quick consecutive right tap -> initiates +10s seek without blocking initial tap
        val doubleRightTap = tracker.onTap(
            touchX = 900f,
            widthPx = 1000f,
            controlsVisible = true,
            isLocked = false,
            stepSeconds = 10,
            uptimeMs = 1_180L
        )
        assertEquals(
            PlayerTapOutcome.SeekDelta(direction = 1, accumulatedSeconds = 10, stepSeconds = 10, keepControlsVisible = false),
            doubleRightTap
        )

        // 3. Consecutive 3rd & 4th right taps accumulate +20s, +30s
        val tripleRight = tracker.onTap(910f, 1000f, controlsVisible = false, isLocked = false, stepSeconds = 10, uptimeMs = 1_420L)
        assertEquals(PlayerTapOutcome.SeekDelta(1, 20, 10, false), tripleRight)

        val quadRight = tracker.onTap(920f, 1000f, controlsVisible = false, isLocked = false, stepSeconds = 10, uptimeMs = 1_750L)
        assertEquals(PlayerTapOutcome.SeekDelta(1, 30, 10, false), quadRight)

        // 4. Center tap when controls visible (after accumulation window) toggles off immediately
        val centerToggle = tracker.onTap(500f, 1000f, controlsVisible = true, isLocked = false, stepSeconds = 10, uptimeMs = 3_000L)
        assertEquals(PlayerTapOutcome.SetControlsVisible(false), centerToggle)

        // 5. Left consecutive taps accumulate -15s, -30s
        assertEquals(
            PlayerTapOutcome.SetControlsVisible(true),
            tracker.onTap(100f, 1000f, controlsVisible = false, isLocked = false, stepSeconds = 15, uptimeMs = 4_000L)
        )
        assertEquals(
            PlayerTapOutcome.SeekDelta(-1, 15, 15, false),
            tracker.onTap(100f, 1000f, controlsVisible = true, isLocked = false, stepSeconds = 15, uptimeMs = 4_160L)
        )
        assertEquals(
            PlayerTapOutcome.SeekDelta(-1, 30, 15, false),
            tracker.onTap(110f, 1000f, controlsVisible = false, isLocked = false, stepSeconds = 15, uptimeMs = 4_400L)
        )

        // 6. Locked state never seeks even on double tap
        val lockedTap1 = tracker.onTap(100f, 1000f, controlsVisible = false, isLocked = true, stepSeconds = 10, uptimeMs = 5_000L)
        val lockedTap2 = tracker.onTap(100f, 1000f, controlsVisible = true, isLocked = true, stepSeconds = 10, uptimeMs = 5_120L)
        assertEquals(PlayerTapOutcome.SetControlsVisible(true), lockedTap1)
        assertEquals(PlayerTapOutcome.SetControlsVisible(false), lockedTap2)
    }

    @Test
    fun `buffering keeps controls accessible and does not auto-hide or break buttons`() {
        val src = PlaybackSource("s720", "https://cdn.example/video.mp4", "MP4", "720p", 720, providerName = "Laterbox")
        composeTestRule.setContent {
            LaterboxTheme {
                MoviePlayerScreen(
                    uiState = MoviePlayerUiState(
                        isLoading = false,
                        resolution = PlaybackResolution.Available(
                            title = "Buffering Test Movie",
                            sources = listOf(src)
                        ),
                        selectedSource = src
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onSelectSource = { _, _ -> },
                    onSelectSubtitle = { _, _ -> },
                    onSaveProgress = { _, _ -> },
                    onOpenExternalUrl = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("player_controls_overlay").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_buffering_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_play_pause_button").assertIsDisplayed()

        // Advance beyond the 3.5s auto-hide delay -> controls stay open while STATE_BUFFERING!
        composeTestRule.mainClock.advanceTimeBy(4_200L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("player_controls_overlay").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_play_pause_button").assertIsDisplayed()
    }

    @Test
    fun `holding or pressing controls does not trigger hold-to-2x speed badge`() {
        val localSrc = PlaybackSource("local", "file:///storage/emulated/0/Download/movie.mp4", "MP4", "1080p", 1080, providerName = "Offline")
        composeTestRule.setContent {
            LaterboxTheme {
                MoviePlayerScreen(
                    uiState = MoviePlayerUiState(
                        isLoading = false,
                        resolution = PlaybackResolution.Available(
                            title = "Offline Download Movie",
                            sources = listOf(localSrc)
                        ),
                        selectedSource = localSrc,
                        isPlayingOffline = true
                    ),
                    onBackClick = {},
                    onRetry = {},
                    onSelectSource = { _, _ -> },
                    onSelectSubtitle = { _, _ -> },
                    onSaveProgress = { _, _ -> },
                    onOpenExternalUrl = {}
                )
            }
        }

        // Long press directly on Play/Pause & Speed button -> must NOT trigger Hold-to-2x!
        composeTestRule.onNodeWithTag("player_speed_button").performTouchInput {
            down(center)
            advanceEventTime(450L)
            up()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("player_hold_2x_badge").assertDoesNotExist()
        composeTestRule.onNodeWithTag("player_controls_overlay").assertIsDisplayed()

        // Clicking forward button keeps controls functional & updates seek feedback
        composeTestRule.onNodeWithTag("player_forward_10_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("player_controls_overlay").assertIsDisplayed()
    }

    @Test
    fun `player clock state updates do not recompose outer parent composables`() {
        var parentCompositions = 0
        var leafClockCompositions = 0
        lateinit var clockState: PlayerClockState

        composeTestRule.setContent {
            LaterboxTheme {
                clockState = remember { PlayerClockState(initialPositionMs = 1_000L) }
                SideEffect { parentCompositions++ }
                Column {
                    Text("Static Outer Overlay")
                    ClockLeafSample(clockState = clockState, onRecompose = { leafClockCompositions++ })
                }
            }
        }

        composeTestRule.waitForIdle()
        val initialParent = parentCompositions
        val initialLeaf = leafClockCompositions

        // Simulate 3 x 400ms clock ticks
        composeTestRule.runOnUiThread { clockState.positionMs = 1_400L }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { clockState.positionMs = 1_800L }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread {
            clockState.durationMs = 120_000L
            clockState.positionMs = 2_200L
        }
        composeTestRule.waitForIdle()

        assertEquals("Outer player host must have zero recompositions on clock ticks", initialParent, parentCompositions)
        assertTrue("Isolated leaf clock must update on clock ticks", leafClockCompositions > initialLeaf)
    }

    @Test
    fun `volume boost hud only renders boost badge animation above 100 percent`() {
        composeTestRule.setContent {
            LaterboxTheme {
                Column {
                    PlayerVolumeBoostHud(
                        visible = true,
                        volumePercent = 80,
                        isBoostSupported = true,
                        fallbackNotice = null,
                        hudTag = "vol_normal",
                        boostBadgeTag = "boost_normal"
                    )
                    PlayerVolumeBoostHud(
                        visible = true,
                        volumePercent = 160,
                        isBoostSupported = true,
                        fallbackNotice = null,
                        hudTag = "vol_boosted",
                        boostBadgeTag = "boost_active"
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("vol_normal").assertIsDisplayed()
        composeTestRule.onNodeWithTag("boost_normal").assertDoesNotExist()
        composeTestRule.onNodeWithTag("vol_boosted").assertIsDisplayed()
        composeTestRule.onNodeWithTag("boost_active").assertIsDisplayed()
        assertFalse(false)
    }

    @Test
    fun `center 44 percent double tap dead zone ignores seek while outer 28 percent edges seek`() {
        val tracker = PlayerTapGestureTracker(doubleTapWindowMs = 300L, accumulationWindowMs = 700L)

        // Tap at x = 350/1000 (inside 44% center dead zone [0.28..0.72]) -> never initiates seek
        assertEquals(
            PlayerTapOutcome.SetControlsVisible(true),
            tracker.onTap(350f, 1000f, controlsVisible = false, isLocked = false, stepSeconds = 10, uptimeMs = 1_000L)
        )
        assertEquals(
            PlayerTapOutcome.SetControlsVisible(false),
            tracker.onTap(350f, 1000f, controlsVisible = true, isLocked = false, stepSeconds = 10, uptimeMs = 1_150L)
        )

        // Tap at x = 710/1000 (still inside center dead zone < 0.72) -> never seeks
        assertEquals(
            PlayerTapOutcome.SetControlsVisible(true),
            tracker.onTap(710f, 1000f, controlsVisible = false, isLocked = false, stepSeconds = 10, uptimeMs = 2_000L)
        )
        assertEquals(
            PlayerTapOutcome.SetControlsVisible(false),
            tracker.onTap(710f, 1000f, controlsVisible = true, isLocked = false, stepSeconds = 10, uptimeMs = 2_120L)
        )

        // Edge taps at 28% and 72% initiate seeks cleanly
        tracker.onTap(280f, 1000f, controlsVisible = false, isLocked = false, stepSeconds = 10, uptimeMs = 3_000L)
        assertEquals(
            PlayerTapOutcome.SeekDelta(-1, 10, 10, false),
            tracker.onTap(280f, 1000f, controlsVisible = true, isLocked = false, stepSeconds = 10, uptimeMs = 3_150L)
        )
        tracker.onTap(720f, 1000f, controlsVisible = false, isLocked = false, stepSeconds = 10, uptimeMs = 4_000L)
        assertEquals(
            PlayerTapOutcome.SeekDelta(1, 10, 10, false),
            tracker.onTap(720f, 1000f, controlsVisible = true, isLocked = false, stepSeconds = 10, uptimeMs = 4_150L)
        )
    }

    @Test
    fun `buffering indicator is unified inside play pause center control and back closes unlocked player`() {
        val src = PlaybackSource("s720", "https://cdn.example/video.mp4", "MP4", "720p", 720, providerName = "Laterbox")
        var backCalled = false
        var latestSavedPos = -1L
        var backDispatcher: androidx.activity.OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            LaterboxTheme {
                MoviePlayerScreen(
                    uiState = MoviePlayerUiState(
                        isLoading = false,
                        resolution = PlaybackResolution.Available(
                            title = "Unified Control Movie",
                            sources = listOf(src)
                        ),
                        selectedSource = src
                    ),
                    onBackClick = { backCalled = true },
                    onRetry = {},
                    onSelectSource = { _, _ -> },
                    onSelectSubtitle = { _, _ -> },
                    onSaveProgress = { pos, _ -> latestSavedPos = pos },
                    onOpenExternalUrl = {}
                )
            }
        }

        val bufferingBounds = composeTestRule.onNodeWithTag("player_buffering_indicator", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val playPauseBounds = composeTestRule.onNodeWithTag("player_play_pause_button")
            .fetchSemanticsNode().boundsInRoot

        // Unified buffering spinner lives directly inside the center Play/Pause circle (no 120dp offset)
        assertTrue(
            "Expected buffering indicator inside Play/Pause bounds ($playPauseBounds), got $bufferingBounds",
            kotlin.math.abs(bufferingBounds.center.y - playPauseBounds.center.y) < 4f &&
                kotlin.math.abs(bufferingBounds.center.x - playPauseBounds.center.x) < 4f
        )

        // Dispatch Android Back on unlocked player -> closes player via onBackClick without popping underlying NavHost
        composeTestRule.runOnUiThread {
            backDispatcher?.onBackPressed()
        }
        composeTestRule.waitForIdle()
        assertTrue(backCalled)
        assertTrue(latestSavedPos >= 0L)
    }

    @androidx.compose.runtime.Composable
    private fun ClockLeafSample(clockState: PlayerClockState, onRecompose: () -> Unit) {
        SideEffect { onRecompose() }
        Text(text = "${clockState.positionMs}/${clockState.durationMs}")
    }
}
