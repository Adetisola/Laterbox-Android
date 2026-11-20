package com.laterbox.app.movies.playback

import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackSessionServiceTest {

    private val createdPlayers = mutableListOf<ExoPlayer>()

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LaterboxPlaybackBridge.stopAndDetach(context)
        createdPlayers.forEach { runCatching { it.release() } }
        createdPlayers.clear()
    }

    @Test
    fun playbackSessionService_exposesSharedPlayerSessionAndStopsCleanlyWhenReleased() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val player = ExoPlayer.Builder(context)
            .setAudioAttributes(PlaybackSessionService.buildConfiguredAudioAttributes(), true)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
            .also { createdPlayers += it }

        LaterboxPlaybackBridge.attachPlayer(
            context = context,
            player = player,
            title = "Severance",
            subtitle = "S1 · E1"
        )
        assertSame(player, LaterboxPlaybackBridge.activePlayer)
        assertEquals("Severance", LaterboxPlaybackBridge.activeTitle)
        assertEquals("S1 · E1", LaterboxPlaybackBridge.activeSubtitle)

        val controller = Robolectric.buildService(
            PlaybackSessionService::class.java,
            Intent(context, PlaybackSessionService::class.java)
        )
        val service = controller.create().get()
        controller.startCommand(0, 0)

        val session = service.currentSession()
        assertNotNull(session)
        assertSame(player, session?.player)
        assertEquals(1, service.sessions.size)

        // Detaching / releasing stops the active session cleanly
        LaterboxPlaybackBridge.releasePlayer(context, player)
        assertNull(LaterboxPlaybackBridge.activePlayer)
        assertNull(LaterboxPlaybackBridge.activeTitle)
        assertNull(service.currentSession())
        assertEquals(0, service.sessions.size)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        controller.destroy()
    }

    @Test
    fun releasingReplacedPlayer_doesNotDetachActiveReplacementPlayer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val player1 = ExoPlayer.Builder(context).build().also { createdPlayers += it }
        val player2 = ExoPlayer.Builder(context).build().also { createdPlayers += it }

        LaterboxPlaybackBridge.attachPlayer(context, player1, title = "Movie A")
        val controller = Robolectric.buildService(
            PlaybackSessionService::class.java,
            Intent(context, PlaybackSessionService::class.java)
        )
        val service = controller.create().get()
        controller.startCommand(0, 0)

        // Replace player1 with player2 before player1 is released
        LaterboxPlaybackBridge.attachPlayer(context, player2, title = "Movie B")
        assertSame(player2, service.currentSession()?.player)

        // Releasing player1 must NOT detach or stop player2's session
        LaterboxPlaybackBridge.releasePlayer(context, player1)
        assertSame(player2, LaterboxPlaybackBridge.activePlayer)
        assertEquals("Movie B", LaterboxPlaybackBridge.activeTitle)
        assertSame(player2, service.currentSession()?.player)

        // Duplicate release of player1 is safe/idempotent
        LaterboxPlaybackBridge.releasePlayer(context, player1)
        assertSame(player2, LaterboxPlaybackBridge.activePlayer)

        LaterboxPlaybackBridge.releasePlayer(context, player2)
        assertNull(LaterboxPlaybackBridge.activePlayer)
        assertNull(service.currentSession())
        controller.destroy()
    }

    @Test
    @Config(sdk = [35])
    fun onStartCommand_whenPlayerAlreadyDetached_stopsCleanlyWithoutCrashing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LaterboxPlaybackBridge.stopAndDetach(context)

        val controller = Robolectric.buildService(
            PlaybackSessionService::class.java,
            Intent(context, PlaybackSessionService::class.java)
        )
        val service = controller.create().get()
        controller.startCommand(0, 0)

        assertNull(LaterboxPlaybackBridge.activePlayer)
        assertNull(service.currentSession())
        controller.destroy()
    }

    @Test
    fun rapidOpenCloseReopen_keepsSingleAuthoritativePlayer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repeat(5) { idx ->
            val p = ExoPlayer.Builder(context).build().also { createdPlayers += it }
            LaterboxPlaybackBridge.attachPlayer(context, p, title = "Title $idx")
            assertSame(p, LaterboxPlaybackBridge.activePlayer)
            LaterboxPlaybackBridge.releasePlayer(context, p)
            assertNull(LaterboxPlaybackBridge.activePlayer)
        }
    }
}
