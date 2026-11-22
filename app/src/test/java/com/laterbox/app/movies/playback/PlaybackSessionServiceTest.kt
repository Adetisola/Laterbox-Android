package com.laterbox.app.movies.playback

import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test
    fun sharedVideoPlayer_reusesSingleInstanceAcrossMiniPlayerBackAndReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = LaterboxPlaybackBridge.obtainOrCreateSharedVideoPlayer(context, 10)
        val second = LaterboxPlaybackBridge.obtainOrCreateSharedVideoPlayer(context, 15)
        assertSame(first, second)

        val target = PlaybackSessionTarget.MovieStream("moviebox", "dune_2")
        var savedCount = 0
        LaterboxPlaybackBridge.setTargetMetadata(
            target = target,
            episodeKey = target.episodeKey,
            artworkUrl = "https://cdn.example/dune.jpg",
            onSaveProgress = { _, _ -> savedCount++ }
        )
        LaterboxPlaybackBridge.attachPlayer(context, first, title = "Dune: Part Two", subtitle = "HD")

        // Pressing Back retains shared ExoPlayer for mini-player instead of releasing it
        val entered = LaterboxPlaybackBridge.enterMiniPlayer(context)
        assertEquals(true, entered)
        assertEquals(true, LaterboxPlaybackBridge.isRetainedForMiniPlayer)
        assertNotNull(LaterboxPlaybackBridge.miniPlayerState.value)
        assertEquals("Dune: Part Two", LaterboxPlaybackBridge.miniPlayerState.value?.title)

        // Simulated PlayerActivity dispose must NOT release the retained shared player
        LaterboxPlaybackBridge.releasePlayer(context, first)
        assertSame(first, LaterboxPlaybackBridge.activePlayer)
        assertSame(first, LaterboxPlaybackBridge.obtainOrCreateSharedVideoPlayer(context, 10))

        // Reopening full-screen clears mini-player bar while keeping exact same ExoPlayer
        LaterboxPlaybackBridge.exitMiniPlayerToFullscreen()
        assertEquals(false, LaterboxPlaybackBridge.isRetainedForMiniPlayer)
        assertNull(LaterboxPlaybackBridge.miniPlayerState.value)
        assertSame(first, LaterboxPlaybackBridge.activePlayer)

        // Dismissing mini-player releases shared player cleanly
        LaterboxPlaybackBridge.enterMiniPlayer(context)
        LaterboxPlaybackBridge.dismissMiniPlayer(context)
        assertNull(LaterboxPlaybackBridge.activePlayer)
        assertNull(LaterboxPlaybackBridge.miniPlayerState.value)
    }

    @Test
    fun manifestAndActivityOwnership_mainActivityNeverSupportsPipAndPlayerActivityDoes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_ACTIVITIES
        )
        val activities = pkgInfo.activities.orEmpty().associateBy { it.name }
        val mainInfo = activities["com.laterbox.app.MainActivity"]
        val playerInfo = activities["com.laterbox.app.movies.playback.PlayerActivity"]

        assertNotNull(mainInfo)
        assertNotNull(playerInfo)
        val flagSupportsPip = runCatching {
            android.content.pm.ActivityInfo::class.java
                .getField("FLAG_SUPPORTS_PICTURE_IN_PICTURE")
                .getInt(null)
        }.getOrDefault(0x00400000)
        assertEquals(0, (mainInfo!!.flags and flagSupportsPip))
        assertEquals(flagSupportsPip, (playerInfo!!.flags and flagSupportsPip))
    }

    @Test
    fun bridgeResolveBackTransition_honorsLivePlayState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var isPlaying = true
        var playWhenReady = true
        var playbackState = androidx.media3.common.Player.STATE_READY
        val proxyPlayer = java.lang.reflect.Proxy.newProxyInstance(
            androidx.media3.common.Player::class.java.classLoader,
            arrayOf(androidx.media3.common.Player::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "isPlaying" -> isPlaying
                "getPlayWhenReady" -> playWhenReady
                "getPlaybackState" -> playbackState
                "getCurrentPosition" -> 12_000L
                "getDuration" -> 60_000L
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        } as androidx.media3.common.Player

        LaterboxPlaybackBridge.setActivePlayerForTest(proxyPlayer)
        LaterboxPlaybackBridge.hasPipBlockingModal = false
        LaterboxPlaybackBridge.isPlayerLocked = false
        LaterboxPlaybackBridge.setTargetMetadata(
            target = PlaybackSessionTarget.MovieStream("moviebox", "tt-pip"),
            episodeKey = "moviebox|tt-pip|0|0",
            artworkUrl = null,
            onSaveProgress = { _, _ -> }
        )
        try {
            assertTrue(LaterboxPlaybackBridge.isActivePlaybackNow(hasError = false))

            // Buffering with playWhenReady=true remains active playback
            isPlaying = false
            playbackState = androidx.media3.common.Player.STATE_BUFFERING
            assertTrue(LaterboxPlaybackBridge.isActivePlaybackNow(hasError = false))

            // Paused -> not active playback, Back returns Mini-Player
            playWhenReady = false
            playbackState = androidx.media3.common.Player.STATE_READY
            assertFalse(LaterboxPlaybackBridge.isActivePlaybackNow(hasError = false))
            assertEquals(
                PlayerBackAction.ENTER_MINI_PLAYER,
                LaterboxPlaybackBridge.resolveBackTransition(
                    context = context,
                    isAlreadyInPip = false,
                    isFinishing = false,
                    hasError = false
                )
            )
            assertFalse(
                LaterboxPlaybackBridge.canEnterPipOnUserLeave(
                    context = context,
                    isAlreadyInPip = false,
                    isFinishing = false,
                    hasError = false
                )
            )

            // Playback error -> STOP_AND_CLOSE
            assertEquals(
                PlayerBackAction.STOP_AND_CLOSE,
                LaterboxPlaybackBridge.resolveBackTransition(
                    context = context,
                    isAlreadyInPip = false,
                    isFinishing = false,
                    hasError = true
                )
            )
        } finally {
            LaterboxPlaybackBridge.stopAndDetach(context)
        }
    }

    @Test
    fun stopAndDetach_stopsActivePlayerAndClearsPipAndMiniPlayerState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var stopCount = 0
        val proxyPlayer = java.lang.reflect.Proxy.newProxyInstance(
            androidx.media3.common.Player::class.java.classLoader,
            arrayOf(androidx.media3.common.Player::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "stop" -> {
                    stopCount++
                    Unit
                }
                "isPlaying" -> true
                "getPlayWhenReady" -> true
                "getPlaybackState" -> androidx.media3.common.Player.STATE_READY
                "getCurrentPosition" -> 12_000L
                "getDuration" -> 60_000L
                else -> null
            }
        } as androidx.media3.common.Player

        LaterboxPlaybackBridge.setActivePlayerForTest(proxyPlayer)
        LaterboxPlaybackBridge.setInPictureInPicture(true)
        LaterboxPlaybackBridge.setTargetMetadata(
            target = PlaybackSessionTarget.MovieStream("moviebox", "tt-stop"),
            episodeKey = "moviebox|tt-stop|0|0",
            artworkUrl = null,
            onSaveProgress = { _, _ -> }
        )

        LaterboxPlaybackBridge.stopAndDetach(context)

        assertTrue(stopCount >= 1)
        assertFalse(LaterboxPlaybackBridge.isInPictureInPicture)
        assertFalse(LaterboxPlaybackBridge.isRetainedForMiniPlayer)
        assertEquals(null, LaterboxPlaybackBridge.activePlayer)
        assertEquals(null, LaterboxPlaybackBridge.activeTarget)
        assertEquals(null, LaterboxPlaybackBridge.miniPlayerState.value)
    }

    @Test
    fun artworkHelper_populatesMediaMetadataForStreamsAndLocalThumbnails_withBrandedFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LaterboxPlaybackArtworkHelper.clearCacheForTest()

        // 1. Online movie / episode artwork URI populates MediaMetadata
        val streamMeta = LaterboxPlaybackArtworkHelper.buildPlaybackMediaMetadata(
            title = "Severance",
            subtitle = "S1 · E1",
            artworkUrl = "https://cdn.example.com/posters/severance-s1e1.jpg",
        )
        assertEquals("Severance", streamMeta.title?.toString())
        assertEquals("S1 · E1", streamMeta.subtitle?.toString())
        assertEquals("https://cdn.example.com/posters/severance-s1e1.jpg", streamMeta.artworkUri?.toString())

        // 2. Local asset relative thumbnailPath resolves cleanly when file exists
        val thumbDir = java.io.File(context.filesDir, "media_thumbnails").apply { mkdirs() }
        val thumbFile = java.io.File(thumbDir, "local-asset-1.jpg")
        val pngBytes = LaterboxPlaybackArtworkHelper.renderBrandedFallbackArtworkPng(context)
        thumbFile.writeBytes(pngBytes)
        try {
            val resolvedUri = LaterboxPlaybackArtworkHelper.resolveLocalThumbnailUri(
                context = context,
                artworkUrl = null,
                thumbnailPath = "media_thumbnails/local-asset-1.jpg",
            )
            assertNotNull(resolvedUri)
            assertTrue(resolvedUri!!.startsWith("file://"))

            val localMeta = LaterboxPlaybackArtworkHelper.buildPlaybackMediaMetadata(
                title = "Offline Clip",
                artworkUrl = resolvedUri,
            )
            val bitmapLoader = LaterboxPlaybackBitmapLoader(context)
            val loadedBitmap = bitmapLoader.loadBitmapFromMetadata(localMeta).get()
            assertNotNull(loadedBitmap)
            assertTrue(loadedBitmap.width > 0 && loadedBitmap.height > 0)

            // 3. Missing / unparseable artwork still yields a branded fallback bitmap rather than failing
            val emptyMeta = LaterboxPlaybackArtworkHelper.buildPlaybackMediaMetadata(
                title = "No Art Clip",
                artworkUrl = null,
            )
            assertNull(emptyMeta.artworkUri)
            val fallbackBitmap = bitmapLoader.loadBitmapFromMetadata(emptyMeta).get()
            assertNotNull(fallbackBitmap)
            assertTrue(fallbackBitmap.width > 0 && fallbackBitmap.height > 0)

            val missingFileMeta = LaterboxPlaybackArtworkHelper.buildPlaybackMediaMetadata(
                title = "Broken File Clip",
                artworkUrl = "file://${context.filesDir.absolutePath}/does-not-exist-art.jpg",
            )
            val brokenFallbackBitmap = bitmapLoader.loadBitmapFromMetadata(missingFileMeta).get()
            assertNotNull(brokenFallbackBitmap)
            assertTrue(brokenFallbackBitmap.width > 0 && brokenFallbackBitmap.height > 0)
        } finally {
            thumbFile.delete()
        }
    }
}
