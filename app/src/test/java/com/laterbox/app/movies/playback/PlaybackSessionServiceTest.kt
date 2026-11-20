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
}
