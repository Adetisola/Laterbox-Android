package com.laterbox.app.movies.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.laterbox.app.BuildConfig
import com.laterbox.app.movies.data.cache.MoviesMediaCache
import com.laterbox.app.movies.data.provider.MovieBoxProvider
import com.laterbox.app.movies.domain.model.CastMember
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.OfflineMediaMatch
import com.laterbox.app.ui.components.EpisodeListSkeleton
import com.laterbox.app.ui.components.MovieDetailCastRailSkeleton
import com.laterbox.app.ui.components.MovieDetailPosterRailSkeleton
import com.laterbox.app.ui.components.MovieDetailScreenSkeleton
import com.laterbox.app.ui.components.skeletonShimmer
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.ui.player.LaterboxVideoSurface
import com.laterbox.app.movies.ui.player.PlayerAudioBoostController
import com.laterbox.app.movies.ui.player.PlayerBrightnessHelper
import com.laterbox.app.movies.ui.player.PlayerBrightnessHud
import com.laterbox.app.movies.ui.player.PlayerGesturesMath
import com.laterbox.app.movies.ui.player.PlayerHold2xSpeedBadge
import com.laterbox.app.movies.ui.player.PlayerHorizontalScrubHud
import com.laterbox.app.movies.ui.player.PlayerSeekFeedbackBadge
import com.laterbox.app.movies.ui.player.PlayerVolumeBoostHud
import com.laterbox.app.movies.ui.player.SeekSensitivityTier
import com.laterbox.app.movies.ui.player.playerDragGestures
import com.laterbox.app.ui.components.CustomReminderPickerDialog
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@kotlin.OptIn(ExperimentalLayoutApi::class)
@OptIn(UnstableApi::class)
@Composable
fun MovieDetailScreen(
    uiState: MovieDetailUiState,
    onBackClick: () -> Unit,
    onRetry: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onPlayClick: (seasonNumber: Int?, episodeNumber: Int?) -> Unit,
    onSelectSeason: (Int) -> Unit,
    onOpenRelated: (providerId: String, titleId: String) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onDownloadSource: (PlaybackSource) -> Unit = {},
    onDismissDownloadStatus: () -> Unit = {},
    onRetryPlaybackCheck: () -> Unit = onRetry,
    onUnlinkProviderLink: (providerId: String, titleId: String) -> Unit = { _, _ -> },
    onCastMemberClick: (CastMember) -> Unit = {},
    onDismissPersonSheet: () -> Unit = {},
    onDownloadEpisodesBatch: (seasonNumber: Int, episodeNumbers: List<Int>, preferredQualityHeight: Int?) -> Unit = { _, _, _ -> },
    onPlayTrailerClick: () -> Unit = {},
    onStopTrailer: () -> Unit = {},
    onToggleTrailerMute: () -> Unit = {},
    onSetTrailerFullscreen: (fullscreen: Boolean, currentPositionMs: Long) -> Unit = { _, _ -> },
    onRecordTrailerProgress: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    onTrailerPlaybackError: (String?) -> Unit = {},
    onMarkWatched: (isRewatch: Boolean) -> Unit = {},
    onMarkUnwatched: () -> Unit = {},
    onRestartFromBeginning: () -> Unit = {},
    onRemoveFromContinueWatching: () -> Unit = {},
    onSetTitleHidden: (Boolean) -> Unit = {},
    onToggleEpisodeWatched: (EpisodeItem) -> Unit = {},
    onToggleCollectionMembership: (Long) -> Unit = {},
    onCreateAndAddToCollection: (String) -> Unit = {},
    onScheduleViewingReminder: (triggerAtMillis: Long, note: String?) -> Unit = { _, _ -> },
    onDismissViewingReminder: () -> Unit = {},
    onToggleMuteSeriesReminders: () -> Unit = {},
    onClearActionFeedback: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val context = LocalContext.current
    val activity = remember(context) { context.findDetailActivity() }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showPosterViewer by remember { mutableStateOf(false) }
    var showMoreActionsMenu by remember { mutableStateOf(false) }
    var showCollectionDialog by remember { mutableStateOf(false) }
    var showReminderPicker by remember { mutableStateOf(false) }
    var reminderNoteDraft by remember { mutableStateOf("") }
    var newCollectionNameDraft by remember { mutableStateOf("") }

    val availableRes = uiState.playbackResolution as? PlaybackResolution.Available
    val canShowDownloadSheet = uiState.details != null && (
        availableRes != null ||
            (uiState.details.summary.kind.isEpisodic && uiState.details.seasons.isNotEmpty())
        )
    if (showDownloadDialog && canShowDownloadSheet) {
        val currentDetails = uiState.details!!
        DownloadOptionsBottomSheet(
            title = currentDetails.summary.title,
            kind = currentDetails.summary.kind,
            sources = availableRes?.sources.orEmpty(),
            subtitles = availableRes?.subtitles.orEmpty().ifEmpty { uiState.details.availableSubtitles },
            seasons = uiState.details.seasons,
            selectedSeasonNumber = uiState.selectedSeasonNumber,
            currentEpisodes = uiState.episodes,
            isEnqueuing = uiState.isEnqueuingDownload,
            isLoadingEpisodeSizes = uiState.isLoadingEpisodeSizes,
            onSelectSeason = onSelectSeason,
            onSelectSource = onDownloadSource,
            onDownloadBatch = onDownloadEpisodesBatch,
            onDismiss = { showDownloadDialog = false }
        )
    }

    val posterForViewer = uiState.details?.summary?.posterUrl
    if (showPosterViewer && !posterForViewer.isNullOrBlank()) {
        PosterViewerDialog(
            imageUrl = posterForViewer,
            title = uiState.details.summary.title,
            onDismiss = { showPosterViewer = false }
        )
    }

    uiState.activePersonMember?.let { activeMember ->
        PersonFilmographyBottomSheet(
            member = activeMember,
            filmography = uiState.selectedPerson,
            isLoading = uiState.isLoadingPerson,
            errorMessage = uiState.personError,
            onOpenCredit = onOpenRelated,
            onDismiss = onDismissPersonSheet
        )
    }

    if (showCollectionDialog && uiState.details != null) {
        AlertDialog(
            onDismissRequest = { showCollectionDialog = false },
            containerColor = colors.surfaceElevated,
            modifier = Modifier.testTag("detail_collection_dialog"),
            title = {
                Text("Save to Collection", style = typography.sheetTitle, color = colors.textPrimary)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (uiState.collections.isEmpty()) {
                        Text(
                            text = "No collections yet. Create one below.",
                            style = typography.bodySecondary,
                            color = colors.textSecondary
                        )
                    } else {
                        uiState.collections.forEach { col ->
                            val isMember = col.id in uiState.memberCollectionIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onToggleCollectionMembership(col.id) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isMember,
                                    onCheckedChange = { onToggleCollectionMembership(col.id) }
                                )
                                Text(
                                    text = col.name,
                                    style = typography.uiLabel,
                                    color = colors.textPrimary
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = newCollectionNameDraft,
                        onValueChange = { newCollectionNameDraft = it },
                        label = { Text("New collection name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val clean = newCollectionNameDraft.trim()
                            if (clean.isNotEmpty()) {
                                onCreateAndAddToCollection(clean)
                                newCollectionNameDraft = ""
                            }
                        },
                        enabled = newCollectionNameDraft.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create & Add")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCollectionDialog = false }) {
                    Text("Done", color = colors.accentPrimary)
                }
            }
        )
    }

    if (showReminderPicker && uiState.details != null) {
        CustomReminderPickerDialog(
            initialTimestamp = uiState.viewingReminder?.triggerAtMillis,
            onDismissRequest = { showReminderPicker = false },
            onConfirm = { triggerAt ->
                onScheduleViewingReminder(triggerAt, reminderNoteDraft.trim().takeIf { it.isNotBlank() })
                reminderNoteDraft = ""
                showReminderPicker = false
            }
        )
    }

    val summaryForTrailer = uiState.details?.summary
    val validTrailerUrl = summaryForTrailer?.trailerUrl?.trim()?.takeIf {
        it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
    }

    // Single shared ExoPlayer instance for the active trailer across inline and landscape fullscreen
    val appContext = remember(context) { context.applicationContext }
    val trailerClock = remember(summaryForTrailer?.id, validTrailerUrl) {
        TrailerPlaybackClockState(initialPositionMs = uiState.trailerPositionMs)
    }
    var attachedTrailerView by remember(summaryForTrailer?.id, validTrailerUrl) {
        mutableStateOf<PlayerView?>(null)
    }

    val trailerPlayer: ExoPlayer? = if (uiState.isTrailerPlaying && validTrailerUrl != null) {
        val headers = remember(summaryForTrailer?.trailerHeaders) {
            summaryForTrailer?.trailerHeaders.orEmpty()
        }
        val player = remember(summaryForTrailer?.id, validTrailerUrl) {
            val mediaSourceFactory = MoviesMediaCache.createMediaSourceFactory(
                context = appContext,
                headers = headers,
                useDiskCache = true
            )
            ExoPlayer.Builder(appContext)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
        }
        val latestOnRecordProgress by rememberUpdatedState(onRecordTrailerProgress)
        val latestOnTrailerError by rememberUpdatedState(onTrailerPlaybackError)
        val latestOnStopTrailer by rememberUpdatedState(onStopTrailer)

        DisposableEffect(validTrailerUrl) {
            MoviesMediaCache.markStreamActive(validTrailerUrl)
            onDispose {
                MoviesMediaCache.markStreamInactive(validTrailerUrl)
            }
        }

        LaunchedEffect(player, uiState.trailerMuted) {
            player.volume = if (uiState.trailerMuted) 0f else 1f
        }

        LaunchedEffect(player, validTrailerUrl) {
            runCatching {
                player.setMediaItem(MediaItem.fromUri(Uri.parse(validTrailerUrl)))
                if (uiState.trailerPositionMs > 0L) {
                    player.seekTo(uiState.trailerPositionMs)
                }
                player.volume = if (uiState.trailerMuted) 0f else 1f
                player.prepare()
                player.playWhenReady = true
            }.onFailure { err ->
                latestOnTrailerError(err.message ?: "Unable to play trailer.")
            }
        }

        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    trailerClock.isPlaying = playing
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        latestOnRecordProgress(player.duration.coerceAtLeast(0L), player.duration.coerceAtLeast(0L))
                        latestOnStopTrailer()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    latestOnTrailerError(error.message ?: "Unable to play trailer.")
                }
            }
            player.addListener(listener)
            onDispose {
                val pos = runCatching { player.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
                val dur = runCatching { player.duration.coerceAtLeast(0L) }.getOrDefault(0L)
                latestOnRecordProgress(pos, dur)
                player.removeListener(listener)
                attachedTrailerView?.player = null
                attachedTrailerView = null
                runCatching { player.release() }
            }
        }

        TrailerProgressPoller(
            player = player,
            clockState = trailerClock,
            onRecordProgress = latestOnRecordProgress
        )

        if (uiState.isTrailerFullscreen) {
            BackHandler {
                onSetTrailerFullscreen(false, player.currentPosition.coerceAtLeast(0L))
            }
            DisposableEffect(activity) {
                activity?.let { act ->
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                }
                onDispose {
                    activity?.let { act ->
                        if (!act.isChangingConfigurations) {
                            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
                            controller.show(WindowInsetsCompat.Type.systemBars())
                        }
                    }
                }
            }
        }

        player
    } else {
        null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
            .testTag("movie_detail_screen")
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    MovieDetailScreenSkeleton()
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.50f),
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .align(Alignment.TopStart)
                    ) {
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier.testTag("detail_back_button")
                        ) {
                            Icon(
                                painter = painterResource(LaterboxIcons.ArrowLeft),
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
            uiState.details == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = uiState.errorMessage ?: "Unable to load details.",
                            style = typography.bodySecondary,
                            color = colors.textSecondary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onBackClick) {
                                Text("Go Back", color = colors.textPrimary)
                            }
                            Button(
                                onClick = onRetry,
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary)
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
            else -> {
                val details = uiState.details
                val summary = details.summary
                val resolution = uiState.playbackResolution
                var visibleEpisodeCount by remember(summary.id, uiState.selectedSeasonNumber) {
                    mutableIntStateOf(10)
                }
                val offlineLookup = remember(uiState.offlineMatches) {
                    OfflineMediaLookup.from(uiState.offlineMatches)
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("movie_detail_list"),
                    contentPadding = PaddingValues(bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Top Hero Backdrop & Poster Header (with inline Trailer Player crossfade)
                    item(key = "detail_hero") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .heightIn(min = 252.dp, max = 320.dp)
                                .background(colors.surfaceSecondary)
                        ) {
                            val showInlineTrailer = uiState.isTrailerPlaying && trailerPlayer != null
                            Crossfade(
                                targetState = showInlineTrailer,
                                label = "detail_hero_trailer_crossfade"
                            ) { playingTrailer ->
                                if (playingTrailer && trailerPlayer != null) {
                                    if (!uiState.isTrailerFullscreen) {
                                        InlineTrailerPlayerSurface(
                                            exoPlayer = trailerPlayer,
                                            title = summary.title,
                                            clockState = trailerClock,
                                            isMuted = uiState.trailerMuted,
                                            isFullscreen = false,
                                            playbackPreferences = uiState.playbackPreferences,
                                            attachVideoSurface = true,
                                            onPlayerViewAttached = { attachedTrailerView = it },
                                            onTogglePlayPause = {
                                                if (trailerPlayer.isPlaying) trailerPlayer.pause() else trailerPlayer.play()
                                            },
                                            onSeekTo = { target ->
                                                trailerPlayer.seekTo(target)
                                                trailerClock.positionMs = target
                                            },
                                            onToggleMute = onToggleTrailerMute,
                                            onToggleFullscreen = {
                                                onSetTrailerFullscreen(true, trailerPlayer.currentPosition.coerceAtLeast(0L))
                                            },
                                            onClose = onStopTrailer,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black)
                                        )
                                    }
                                } else {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        BackdropArtworkBox(
                                            backdropUrl = summary.backdropUrl,
                                            posterUrl = summary.posterUrl,
                                            contentDescription = summary.title,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.verticalGradient(
                                                        listOf(
                                                            Color.Black.copy(alpha = 0.35f),
                                                            colors.backgroundPrimary.copy(alpha = 0.58f),
                                                            colors.backgroundPrimary
                                                        )
                                                    )
                                                )
                                        )

                                        // Top navigation bar
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                                .align(Alignment.TopCenter),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.50f)) {
                                                IconButton(
                                                    onClick = onBackClick,
                                                    modifier = Modifier.testTag("detail_back_button")
                                                ) {
                                                    Icon(
                                                        painter = painterResource(LaterboxIcons.ArrowLeft),
                                                        contentDescription = "Back",
                                                        tint = Color.White
                                                    )
                                                }
                                            }
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.50f)) {
                                                    IconButton(
                                                        onClick = onToggleWatchlist,
                                                        modifier = Modifier.testTag("detail_watchlist_button")
                                                    ) {
                                                        Icon(
                                                            painter = painterResource(
                                                                if (uiState.isInWatchlist) LaterboxIcons.Check else LaterboxIcons.Bookmark
                                                            ),
                                                            contentDescription = if (uiState.isInWatchlist) "Saved to Watchlist" else "Add to Watchlist",
                                                            tint = if (uiState.isInWatchlist) colors.accentPrimary else Color.White
                                                        )
                                                    }
                                                }
                                                Box {
                                                    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.50f)) {
                                                        IconButton(
                                                            onClick = { showMoreActionsMenu = true },
                                                            modifier = Modifier.testTag("detail_more_actions_button")
                                                        ) {
                                                            Icon(
                                                                painter = painterResource(LaterboxIcons.MoreVertical),
                                                                contentDescription = "More actions",
                                                                tint = Color.White
                                                            )
                                                        }
                                                    }
                                                    DropdownMenu(
                                                        expanded = showMoreActionsMenu,
                                                        onDismissRequest = { showMoreActionsMenu = false },
                                                        containerColor = colors.surfaceElevated
                                                    ) {
                                                        val detailContext = androidx.compose.ui.platform.LocalContext.current
                                                        DropdownMenuItem(
                                                            text = { Text("Share", color = colors.textPrimary) },
                                                            leadingIcon = {
                                                                Icon(
                                                                    painter = painterResource(LaterboxIcons.Share),
                                                                    contentDescription = null,
                                                                    tint = colors.textSecondary,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            },
                                                            onClick = {
                                                                showMoreActionsMenu = false
                                                                com.laterbox.app.media.share.LaterboxShareHelper.shareMovieDetails(
                                                                    context = detailContext,
                                                                    providerId = summary.providerId,
                                                                    titleId = summary.id,
                                                                    title = summary.title,
                                                                    kind = summary.kind
                                                                )
                                                            },
                                                            modifier = Modifier.testTag("detail_action_share")
                                                        )
                                                        DropdownMenuItem(
                                                            text = { Text("Save to Collection", color = colors.textPrimary) },
                                                            leadingIcon = {
                                                                Icon(
                                                                    painter = painterResource(LaterboxIcons.FolderAdd),
                                                                    contentDescription = null,
                                                                    tint = colors.textSecondary,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            },
                                                            onClick = {
                                                                showMoreActionsMenu = false
                                                                showCollectionDialog = true
                                                            },
                                                            modifier = Modifier.testTag("detail_action_collection")
                                                        )
                                                        DropdownMenuItem(
                                                            text = {
                                                                Text(
                                                                    if (uiState.viewingReminder != null) "Update Viewing Reminder" else "Remind Me to Watch",
                                                                    color = colors.textPrimary
                                                                )
                                                            },
                                                            leadingIcon = {
                                                                Icon(
                                                                    painter = painterResource(LaterboxIcons.Bell),
                                                                    contentDescription = null,
                                                                    tint = colors.textSecondary,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            },
                                                            onClick = {
                                                                showMoreActionsMenu = false
                                                                showReminderPicker = true
                                                            },
                                                            modifier = Modifier.testTag("detail_action_remind")
                                                        )
                                                        if (!uiState.isTitleCompleted) {
                                                            DropdownMenuItem(
                                                                text = { Text("Mark as Watched", color = colors.textPrimary) },
                                                                leadingIcon = {
                                                                    Icon(
                                                                        painter = painterResource(LaterboxIcons.Check),
                                                                        contentDescription = null,
                                                                        tint = colors.textSecondary,
                                                                        modifier = Modifier.size(18.dp)
                                                                    )
                                                                },
                                                                onClick = {
                                                                    showMoreActionsMenu = false
                                                                    onMarkWatched(false)
                                                                },
                                                                modifier = Modifier.testTag("detail_action_mark_watched")
                                                            )
                                                        } else {
                                                            DropdownMenuItem(
                                                                text = { Text("Log Rewatch", color = colors.textPrimary) },
                                                                leadingIcon = {
                                                                    Icon(
                                                                        painter = painterResource(LaterboxIcons.Refresh),
                                                                        contentDescription = null,
                                                                        tint = colors.textSecondary,
                                                                        modifier = Modifier.size(18.dp)
                                                                    )
                                                                },
                                                                onClick = {
                                                                    showMoreActionsMenu = false
                                                                    onMarkWatched(true)
                                                                },
                                                                modifier = Modifier.testTag("detail_action_rewatch")
                                                            )
                                                            DropdownMenuItem(
                                                                text = { Text("Mark Unwatched", color = colors.textPrimary) },
                                                                leadingIcon = {
                                                                    Icon(
                                                                        painter = painterResource(LaterboxIcons.Close),
                                                                        contentDescription = null,
                                                                        tint = colors.textSecondary,
                                                                        modifier = Modifier.size(18.dp)
                                                                    )
                                                                },
                                                                onClick = {
                                                                    showMoreActionsMenu = false
                                                                    onMarkUnwatched()
                                                                },
                                                                modifier = Modifier.testTag("detail_action_mark_unwatched")
                                                            )
                                                        }
                                                        if (uiState.savedPositionMs > 0L) {
                                                            DropdownMenuItem(
                                                                text = { Text("Restart from Beginning", color = colors.textPrimary) },
                                                                leadingIcon = {
                                                                    Icon(
                                                                        painter = painterResource(LaterboxIcons.Refresh),
                                                                        contentDescription = null,
                                                                        tint = colors.textSecondary,
                                                                        modifier = Modifier.size(18.dp)
                                                                    )
                                                                },
                                                                onClick = {
                                                                    showMoreActionsMenu = false
                                                                    onRestartFromBeginning()
                                                                },
                                                                modifier = Modifier.testTag("detail_action_restart")
                                                            )
                                                            DropdownMenuItem(
                                                                text = { Text("Remove from Continue Watching", color = colors.statusDanger) },
                                                                leadingIcon = {
                                                                    Icon(
                                                                        painter = painterResource(LaterboxIcons.Delete),
                                                                        contentDescription = null,
                                                                        tint = colors.statusDanger,
                                                                        modifier = Modifier.size(18.dp)
                                                                    )
                                                                },
                                                                onClick = {
                                                                    showMoreActionsMenu = false
                                                                    onRemoveFromContinueWatching()
                                                                },
                                                                modifier = Modifier.testTag("detail_action_remove_cw")
                                                            )
                                                        }
                                                        if (summary.kind.isEpisodic) {
                                                            val isMuted = uiState.seriesPreferences?.remindersMuted == true
                                                            DropdownMenuItem(
                                                                text = {
                                                                    Text(
                                                                        if (isMuted) "Unmute Series Alerts" else "Mute Series Alerts",
                                                                        color = colors.textPrimary
                                                                    )
                                                                },
                                                                leadingIcon = {
                                                                    Icon(
                                                                        painter = painterResource(LaterboxIcons.Bell),
                                                                        contentDescription = null,
                                                                        tint = colors.textSecondary,
                                                                        modifier = Modifier.size(18.dp)
                                                                    )
                                                                },
                                                                onClick = {
                                                                    showMoreActionsMenu = false
                                                                    onToggleMuteSeriesReminders()
                                                                },
                                                                modifier = Modifier.testTag("detail_action_mute_series")
                                                            )
                                                        }
                                                        DropdownMenuItem(
                                                            text = { Text("Hide Title", color = colors.statusDanger) },
                                                            leadingIcon = {
                                                                Icon(
                                                                    painter = painterResource(LaterboxIcons.Close),
                                                                    contentDescription = null,
                                                                    tint = colors.statusDanger,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            },
                                                            onClick = {
                                                                showMoreActionsMenu = false
                                                                onSetTitleHidden(true)
                                                            },
                                                            modifier = Modifier.testTag("detail_action_hide")
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // Poster + Main Metadata at Bottom
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp)
                                                .align(Alignment.BottomStart),
                                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                                            verticalAlignment = Alignment.Bottom
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .width(104.dp)
                                                    .aspectRatio(2f / 3f)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(colors.surfacePrimary)
                                                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(12.dp))
                                                    .clickable(enabled = !summary.posterUrl.isNullOrBlank()) {
                                                        showPosterViewer = true
                                                    }
                                                    .testTag("detail_poster_image")
                                            ) {
                                                if (!summary.posterUrl.isNullOrBlank()) {
                                                    AsyncImage(
                                                        model = summary.posterUrl,
                                                        contentDescription = summary.title,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                }
                                            }

                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(bottom = 4.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                val movieDuration = if (summary.kind == MediaKind.MOVIE) {
                                                    com.laterbox.app.movies.domain.util.DurationFormatter
                                                        .formatMinutes(summary.durationMinutes)
                                                } else {
                                                    null
                                                }
                                                @kotlin.OptIn(ExperimentalLayoutApi::class)
                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text(
                                                        text = summary.title,
                                                        style = typography.editorialTitle.copy(fontSize = 22.sp, lineHeight = 26.sp),
                                                        color = colors.textPrimary,
                                                        maxLines = 3,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.align(Alignment.CenterVertically)
                                                    )
                                                    if (movieDuration != null) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                            modifier = Modifier.align(Alignment.CenterVertically)
                                                        ) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .width(1.dp)
                                                                    .height(15.dp)
                                                                    .background(colors.textSecondary.copy(alpha = 0.35f))
                                                            )
                                                            Text(
                                                                text = movieDuration,
                                                                style = typography.metaText.copy(fontSize = 14.sp, lineHeight = 20.sp),
                                                                color = colors.textSecondary,
                                                                maxLines = 1,
                                                                softWrap = false
                                                            )
                                                        }
                                                    }
                                                }
                                                val metaParts = buildList {
                                                    add(summary.kind.label)
                                                    summary.releaseYear?.let { add(it.toString()) }
                                                    summary.rating?.takeIf { it > 0.0 && !it.isNaN() }
                                                        ?.let { add(String.format(Locale.US, "★ %.1f", it)) }
                                                }
                                                Text(
                                                    text = metaParts.joinToString(" · "),
                                                    style = typography.metaText,
                                                    color = colors.textSecondary
                                                )
                                                if (validTrailerUrl != null) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Surface(
                                                            shape = RoundedCornerShape(10.dp),
                                                            color = colors.surfaceElevated.copy(alpha = 0.88f),
                                                            border = BorderStroke(0.75.dp, colors.accentPrimary.copy(alpha = 0.45f)),
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(10.dp))
                                                                .clickable(onClick = onPlayTrailerClick)
                                                                .testTag("detail_watch_trailer_button")
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                                                            ) {
                                                                Icon(
                                                                    painter = painterResource(LaterboxIcons.Play),
                                                                    contentDescription = null,
                                                                    tint = colors.accentPrimary,
                                                                    modifier = Modifier.size(12.dp)
                                                                )
                                                                Text(
                                                                    text = "Watch Trailer",
                                                                    style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                                                    color = colors.textPrimary
                                                                )
                                                            }
                                                        }
                                                        if (uiState.trailerError != null) {
                                                            Text(
                                                                text = "Trailer unavailable",
                                                                style = typography.caption,
                                                                color = colors.textTertiary,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                modifier = Modifier.testTag("detail_trailer_error_note")
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Primary Playback CTA or Honest Unavailable Card
                    item(key = "detail_playback_section") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            when {
                                uiState.isCheckingPlayback -> {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = colors.surfacePrimary,
                                        border = BorderStroke(0.75.dp, colors.borderSubtle),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = colors.accentPrimary
                                            )
                                            Text(
                                                text = "Checking playback availability...",
                                                style = typography.bodySecondary,
                                                color = colors.textSecondary
                                            )
                                        }
                                    }
                                }
                                resolution is PlaybackResolution.Available -> {
                                    val isResume = uiState.savedPositionMs >= 5_000L
                                    val hasAuthorisedDownloads = resolution.sources.any { it.isAuthorised } ||
                                        (summary.kind.isEpisodic && details.seasons.isNotEmpty())
                                    val playSeason = if (summary.kind.isEpisodic) {
                                        uiState.nextUnwatchedTarget?.first ?: uiState.selectedSeasonNumber ?: 1
                                    } else null
                                    val playEpisode = if (summary.kind.isEpisodic) {
                                        uiState.nextUnwatchedTarget?.second ?: uiState.episodes.firstOrNull()?.episodeNumber ?: 1
                                    } else null
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = { onPlayClick(playSeason, playEpisode) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = colors.accentPrimary,
                                                contentColor = colors.onAccentPrimary
                                            ),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("detail_watch_button")
                                        ) {
                                            Icon(
                                                painter = painterResource(LaterboxIcons.Play),
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            val watchedCount = uiState.seriesProgress?.totalWatchedEpisodes ?: 0
                                            val ctaLabel = when {
                                                playSeason != null && playEpisode != null && isResume ->
                                                    "Resume S${playSeason}·E${playEpisode}"
                                                playSeason != null && playEpisode != null && uiState.nextUnwatchedTarget != null && watchedCount > 0 ->
                                                    "Continue S${playSeason}·E${playEpisode}"
                                                playSeason != null && playEpisode != null ->
                                                    "Watch S${playSeason}·E${playEpisode}"
                                                isResume ->
                                                    "Resume"
                                                uiState.isTitleCompleted ->
                                                    "Rewatch"
                                                else ->
                                                    "Watch now"
                                            }
                                            Text(
                                                text = ctaLabel,
                                                style = typography.buttonLabel,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        if (hasAuthorisedDownloads) {
                                            OutlinedButton(
                                                onClick = { showDownloadDialog = true },
                                                enabled = !uiState.isEnqueuingDownload,
                                                border = BorderStroke(1.dp, colors.borderSubtle),
                                                shape = RoundedCornerShape(14.dp),
                                                contentPadding = PaddingValues(horizontal = 14.dp),
                                                modifier = Modifier
                                                    .height(48.dp)
                                                    .testTag("detail_download_button")
                                            ) {
                                                if (uiState.isEnqueuingDownload) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(16.dp),
                                                        strokeWidth = 2.dp,
                                                        color = colors.accentPrimary
                                                    )
                                                } else {
                                                    Icon(
                                                        painter = painterResource(LaterboxIcons.Download),
                                                        contentDescription = "Download",
                                                        tint = colors.accentPrimary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "Download",
                                                    style = typography.buttonLabel,
                                                    color = colors.textPrimary
                                                )
                                            }
                                        }
                                    }

                                    uiState.downloadStatusMessage?.let { statusMsg ->
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = colors.accentContainer.copy(alpha = 0.7f),
                                            border = BorderStroke(0.75.dp, colors.accentPrimary.copy(alpha = 0.3f)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("detail_download_status")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = statusMsg,
                                                    style = typography.metaText,
                                                    color = colors.textPrimary,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                TextButton(
                                                    onClick = onDismissDownloadStatus,
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                                ) {
                                                    Text("OK", style = typography.caption, color = colors.accentPrimary)
                                                }
                                            }
                                        }
                                    }
                                }
                                resolution is PlaybackResolution.Unavailable -> {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = colors.surfaceSecondary,
                                        border = BorderStroke(0.75.dp, colors.borderSubtle),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("detail_playback_unavailable")
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Playback Unavailable",
                                                style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                                color = colors.textPrimary
                                            )
                                            Text(
                                                text = resolution.reason,
                                                style = typography.bodySecondary,
                                                color = colors.textSecondary
                                            )
                                            val extUrl = resolution.externalReferenceUrl ?: details.externalInfoUrl
                                            if (!extUrl.isNullOrBlank()) {
                                                OutlinedButton(
                                                    onClick = { onOpenExternalUrl(extUrl) },
                                                    border = BorderStroke(1.dp, colors.borderSubtle),
                                                    shape = RoundedCornerShape(10.dp)
                                                ) {
                                                    Icon(
                                                        painter = painterResource(LaterboxIcons.ArrowUpRight),
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp),
                                                        tint = colors.accentPrimary
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("View Episode Guide", color = colors.textPrimary)
                                                }
                                            }
                                        }
                                    }
                                }
                                uiState.playbackError != null -> {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = colors.surfaceSecondary,
                                        border = BorderStroke(0.75.dp, colors.borderSubtle),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("detail_playback_error")
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Playback unavailable",
                                                style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                                color = colors.textPrimary
                                            )
                                            Text(
                                                text = uiState.playbackError,
                                                style = typography.bodySecondary,
                                                color = colors.textSecondary
                                            )
                                            OutlinedButton(
                                                onClick = onRetryPlaybackCheck,
                                                border = BorderStroke(1.dp, colors.borderSubtle),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text("Try Again", color = colors.textPrimary)
                                            }
                                        }
                                    }
                                }
                            }

                            // Personal Library Quick Actions & Status Pills
                            val isTitleDownloaded = remember(offlineLookup, summary.providerId, summary.id) {
                                offlineLookup.hasTitle(summary.providerId, summary.id)
                            }
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (uiState.memberCollectionIds.isNotEmpty()) colors.accentContainer else colors.surfacePrimary,
                                    border = BorderStroke(
                                        0.75.dp,
                                        if (uiState.memberCollectionIds.isNotEmpty()) colors.accentPrimary else colors.borderSubtle
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .clickable { showCollectionDialog = true }
                                        .testTag("detail_quick_collection_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(LaterboxIcons.FolderAdd),
                                            contentDescription = null,
                                            tint = if (uiState.memberCollectionIds.isNotEmpty()) colors.accentPrimary else colors.textSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = if (uiState.memberCollectionIds.isNotEmpty()) {
                                                "In ${uiState.memberCollectionIds.size} Collection${if (uiState.memberCollectionIds.size == 1) "" else "s"}"
                                            } else "Add to Collection",
                                            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                            color = colors.textPrimary
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (uiState.viewingReminder != null) colors.accentContainer else colors.surfacePrimary,
                                    border = BorderStroke(
                                        0.75.dp,
                                        if (uiState.viewingReminder != null) colors.accentPrimary else colors.borderSubtle
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .clickable { showReminderPicker = true }
                                        .testTag("detail_quick_remind_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(LaterboxIcons.Bell),
                                            contentDescription = null,
                                            tint = if (uiState.viewingReminder != null) colors.accentPrimary else colors.textSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = if (uiState.viewingReminder != null) "Reminder Set" else "Remind Me",
                                            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                            color = colors.textPrimary
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (uiState.isTitleCompleted) colors.accentContainer else colors.surfacePrimary,
                                    border = BorderStroke(
                                        0.75.dp,
                                        if (uiState.isTitleCompleted) colors.accentPrimary else colors.borderSubtle
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .clickable {
                                            if (uiState.isTitleCompleted) {
                                                onMarkUnwatched()
                                            } else {
                                                onMarkWatched(false)
                                            }
                                        }
                                        .testTag("detail_quick_watched_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(LaterboxIcons.Check),
                                            contentDescription = null,
                                            tint = if (uiState.isTitleCompleted) colors.accentPrimary else colors.textSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = if (uiState.isTitleCompleted) "Mark Unwatched" else "Mark Watched",
                                            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                            color = colors.textPrimary
                                        )
                                    }
                                }

                                if (isTitleDownloaded) {
                                    Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = Color(0xFF1B5E20).copy(alpha = 0.18f),
                                        border = BorderStroke(0.75.dp, Color(0xFF4CAF50).copy(alpha = 0.5f)),
                                        modifier = Modifier.testTag("detail_offline_ready_badge")
                                    ) {
                                        Text(
                                            text = "Offline Ready",
                                            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                            color = Color(0xFF4CAF50),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }

                            uiState.viewingReminder?.let { rem ->
                                val df = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.surfacePrimary,
                                    border = BorderStroke(0.75.dp, colors.accentPrimary.copy(alpha = 0.35f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("detail_active_reminder_banner")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Reminder: ${df.format(Date(rem.triggerAtMillis))}",
                                            style = typography.metaText,
                                            color = colors.textPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = onDismissViewingReminder,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text("Cancel", style = typography.caption, color = colors.statusDanger)
                                        }
                                    }
                                }
                            }

                            uiState.actionFeedbackMessage?.let { feedbackMsg ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colors.accentContainer.copy(alpha = 0.7f),
                                    border = BorderStroke(0.75.dp, colors.accentPrimary.copy(alpha = 0.3f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("detail_action_feedback")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = feedbackMsg,
                                            style = typography.metaText,
                                            color = colors.textPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = onClearActionFeedback,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text("OK", style = typography.caption, color = colors.accentPrimary)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Synopsis & Unified Metadata Pills
                    item(key = "detail_synopsis") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (details.fullDescription.isBlank() && uiState.isRefreshingDetails) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("detail_synopsis_skeleton"),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(13.dp)
                                            .skeletonShimmer(shape = RoundedCornerShape(5.dp))
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.78f)
                                            .height(13.dp)
                                            .skeletonShimmer(shape = RoundedCornerShape(5.dp))
                                    )
                                }
                            } else {
                                Text(
                                    text = details.fullDescription.ifBlank { "No description available." },
                                    style = typography.bodyPrimary,
                                    color = colors.textPrimary
                                )
                            }

                            if (details.directorOrCreator != null || details.networkOrStudio != null || details.availableSubtitles.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    details.directorOrCreator?.let { director ->
                                        val isDirectorClickable = !details.directorPersonId.isNullOrBlank()
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "Director / Creator:",
                                                style = typography.metaText,
                                                color = colors.textSecondary
                                            )
                                            Text(
                                                text = director,
                                                style = typography.metaText.copy(
                                                    fontWeight = if (isDirectorClickable) FontWeight.SemiBold else FontWeight.Normal,
                                                    textDecoration = if (isDirectorClickable) TextDecoration.Underline else null
                                                ),
                                                color = if (isDirectorClickable) colors.accentPrimary else colors.textSecondary,
                                                modifier = Modifier
                                                    .clickable(enabled = isDirectorClickable) {
                                                        onCastMemberClick(
                                                            CastMember(
                                                                name = director,
                                                                role = "Director",
                                                                personId = details.directorPersonId,
                                                                providerId = summary.providerId
                                                            )
                                                        )
                                                    }
                                                    .testTag("detail_director_link")
                                            )
                                        }
                                    }
                                    details.networkOrStudio?.let { studio ->
                                        Text(
                                            text = "Studio / Network: $studio",
                                            style = typography.metaText,
                                            color = colors.textSecondary
                                        )
                                    }
                                    if (details.availableSubtitles.isNotEmpty()) {
                                        Text(
                                            text = "Subtitles: ${details.availableSubtitles.joinToString { it.language }}",
                                            style = typography.metaText,
                                            color = colors.textSecondary
                                        )
                                    }
                                }
                            }

                            // 2-Row Responsive Metadata Pills:
                            // Row 1: Descriptive Metadata (Country, Non-Dub Language, Genres)
                            // Row 2: Audio Availability (Original Audio + Switchable Dubs)
                            val countryPills = remember(summary.country) {
                                summary.country
                                    ?.split(",")
                                    ?.map { it.trim() }
                                    ?.filter { it.isNotBlank() }
                                    .orEmpty()
                                    .distinct()
                            }
                            val dubLabelsLower = remember(details.audioDubs) {
                                details.audioDubs.map { it.label.lowercase(Locale.US) }.toSet()
                            }
                            val languagePills = remember(summary.language, dubLabelsLower) {
                                summary.language
                                    ?.split(",")
                                    ?.map { it.trim() }
                                    ?.filter { it.isNotBlank() && it.lowercase(Locale.US) !in dubLabelsLower }
                                    .orEmpty()
                                    .distinct()
                            }
                            val genrePills = remember(summary.genres) {
                                summary.genres.filter { it.isNotBlank() }.distinct()
                            }
                            val hasDescriptivePills = countryPills.isNotEmpty() ||
                                languagePills.isNotEmpty() ||
                                genrePills.isNotEmpty()
                            val hasAudioRow = details.audioDubs.isNotEmpty() || uiState.isRefreshingDetails

                            if (hasDescriptivePills || hasAudioRow) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("detail_metadata_pills_row"),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (hasDescriptivePills) {
                                        FlowRow(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("detail_metadata_descriptive_row"),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            countryPills.forEach { country ->
                                                MetadataPillChip(
                                                    label = country,
                                                    modifier = Modifier.testTag("detail_country_chip_$country")
                                                )
                                            }
                                            languagePills.forEach { lang ->
                                                MetadataPillChip(
                                                    label = lang,
                                                    modifier = Modifier.testTag("detail_language_chip_$lang")
                                                )
                                            }
                                            genrePills.forEach { genre ->
                                                MetadataPillChip(
                                                    label = genre,
                                                    modifier = Modifier.testTag("detail_genre_chip_$genre")
                                                )
                                            }
                                        }
                                    }

                                    if (details.audioDubs.isNotEmpty()) {
                                        FlowRow(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("detail_metadata_audio_row"),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            details.audioDubs.forEach { dub ->
                                                val refId = dub.referenceId
                                                val isActiveDub = refId == null || refId == summary.id
                                                val isSwitchableDub = !refId.isNullOrBlank() && refId != summary.id
                                                Surface(
                                                    shape = RoundedCornerShape(20.dp),
                                                    color = if (isActiveDub && details.audioDubs.size > 1) {
                                                        colors.accentContainer
                                                    } else {
                                                        colors.surfacePrimary
                                                    },
                                                    border = BorderStroke(
                                                        0.75.dp,
                                                        if (isActiveDub && details.audioDubs.size > 1) {
                                                            colors.accentPrimary
                                                        } else {
                                                            colors.borderSubtle
                                                        }
                                                    ),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(20.dp))
                                                        .clickable(enabled = isSwitchableDub) {
                                                            if (!refId.isNullOrBlank()) {
                                                                onOpenRelated(summary.providerId, refId)
                                                            }
                                                        }
                                                        .testTag("detail_dub_chip_${refId ?: dub.language}")
                                                ) {
                                                    Text(
                                                        text = dub.label,
                                                        style = typography.caption.copy(
                                                            fontWeight = if (isActiveDub && details.audioDubs.size > 1) {
                                                                FontWeight.SemiBold
                                                            } else {
                                                                FontWeight.Normal
                                                            }
                                                        ),
                                                        color = if (isActiveDub && details.audioDubs.size > 1) {
                                                            colors.accentPrimary
                                                        } else {
                                                            colors.textSecondary
                                                        },
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                                    )
                                                }
                                            }
                                        }
                                    } else if (uiState.isRefreshingDetails) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("detail_metadata_audio_skeleton"),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .width(98.dp)
                                                    .height(26.dp)
                                                    .skeletonShimmer(shape = RoundedCornerShape(20.dp))
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .width(88.dp)
                                                    .height(26.dp)
                                                    .skeletonShimmer(shape = RoundedCornerShape(20.dp))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Cast Rail
                    if (details.cast.isNotEmpty()) {
                        item(key = "detail_cast") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "Cast",
                                    style = typography.titleLarge,
                                    color = colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    itemsIndexed(
                                        items = details.cast,
                                        key = { index, it -> "${index}_${it.personId ?: it.name}_${it.role ?: ""}" }
                                    ) { index, member ->
                                        Column(
                                            modifier = Modifier
                                                .width(86.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable { onCastMemberClick(member) }
                                                .padding(vertical = 4.dp)
                                                .testTag("detail_cast_$index"),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(66.dp)
                                                    .aspectRatio(1f)
                                                    .clip(CircleShape)
                                                    .background(colors.surfaceSecondary)
                                                    .border(0.75.dp, colors.borderSubtle, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (!member.imageUrl.isNullOrBlank()) {
                                                    AsyncImage(
                                                        model = member.imageUrl,
                                                        contentDescription = member.name,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                } else {
                                                    Icon(
                                                        painter = painterResource(LaterboxIcons.User),
                                                        contentDescription = null,
                                                        tint = colors.textTertiary,
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = member.name,
                                                style = typography.uiLabel.copy(fontSize = 11.sp),
                                                color = colors.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            member.role?.let { role ->
                                                Text(
                                                    text = role,
                                                    style = typography.caption.copy(fontSize = 10.sp),
                                                    color = colors.textSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (uiState.isRefreshingDetails) {
                        item(key = "detail_cast_skeleton") {
                            MovieDetailCastRailSkeleton()
                        }
                    }

                    // Related Titles Rail (above Seasons & Episodes)
                    val displayRelated = (uiState.rankedRelatedTitles.ifEmpty { details.relatedTitles })
                        .filter { it.stableMediaKey !in uiState.hiddenMediaKeys }
                    if (displayRelated.isNotEmpty()) {
                        item(key = "detail_related") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "More Like This",
                                    style = typography.titleLarge,
                                    color = colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    itemsIndexed(
                                        items = displayRelated,
                                        key = { index, rel -> "rel_${index}_${rel.compositeId}" }
                                    ) { _, rel ->
                                        val isRelOffline = offlineLookup.hasTitle(rel.providerId, rel.id)
                                        val relContext = androidx.compose.ui.platform.LocalContext.current
                                        MoviePosterCard(
                                            item = rel,
                                            isOfflineDownloaded = isRelOffline,
                                            onShareTitle = {
                                                com.laterbox.app.media.share.LaterboxShareHelper.shareTitle(relContext, it)
                                            },
                                            onClick = { onOpenRelated(rel.providerId, rel.id) }
                                        )
                                    }
                                }
                            }
                        }
                    } else if (uiState.isRefreshingDetails) {
                        item(key = "detail_related_skeleton") {
                            MovieDetailPosterRailSkeleton(
                                modifier = Modifier.testTag("detail_related_skeleton")
                            )
                        }
                    }

                    // TV Series Seasons & Episodes
                    if (summary.kind.isEpisodic) {
                        if (details.seasons.isNotEmpty()) {
                            item(key = "detail_seasons") {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        text = "Seasons & Episodes",
                                        style = typography.titleLarge,
                                        color = colors.textPrimary,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        itemsIndexed(
                                            items = details.seasons,
                                            key = { index, s -> "season_${index}_${s.seasonNumber}" }
                                        ) { _, season ->
                                            val selected = uiState.selectedSeasonNumber == season.seasonNumber
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = if (selected) colors.accentPrimary else colors.surfacePrimary,
                                                border = BorderStroke(
                                                    0.75.dp,
                                                    if (selected) colors.accentPrimary else colors.borderSubtle
                                                ),
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable { onSelectSeason(season.seasonNumber) }
                                                    .testTag("season_chip_${season.seasonNumber}")
                                            ) {
                                                Text(
                                                    text = if (season.episodeCount > 0) {
                                                        "${season.title} (${season.episodeCount})"
                                                    } else season.title,
                                                    style = typography.uiLabel.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                                                    color = if (selected) colors.onAccentPrimary else colors.textPrimary,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            when {
                                uiState.isLoadingEpisodes -> {
                                    item(key = "episodes_loading") {
                                        EpisodeListSkeleton()
                                    }
                                }
                                uiState.episodesError != null -> {
                                    item(key = "episodes_error") {
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = colors.surfaceSecondary,
                                            border = BorderStroke(0.75.dp, colors.borderSubtle),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp)
                                                .testTag("detail_episodes_error")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = uiState.episodesError,
                                                    style = typography.bodySecondary,
                                                    color = colors.textSecondary,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                TextButton(
                                                    onClick = {
                                                        onSelectSeason(uiState.selectedSeasonNumber ?: 1)
                                                    }
                                                ) {
                                                    Text("Retry", color = colors.accentPrimary)
                                                }
                                            }
                                        }
                                    }
                                }
                                uiState.episodes.isNotEmpty() -> {
                                    val visibleEpisodes = uiState.episodes.take(visibleEpisodeCount)
                                    itemsIndexed(
                                        items = visibleEpisodes,
                                        key = { index, ep -> "ep_${index}_${ep.id}_${ep.seasonNumber}_${ep.episodeNumber}" }
                                    ) { _, ep ->
                                        val epProgress = uiState.seriesProgress?.episodeStates
                                            ?.get(ep.seasonNumber to ep.episodeNumber)
                                        val isWatched = epProgress?.isCompleted == true
                                        val isEpDownloaded = offlineLookup.hasEpisode(summary.providerId, summary.id, ep.seasonNumber, ep.episodeNumber)
                                        EpisodeRowCard(
                                            episode = ep,
                                            seriesPosterUrl = summary.posterUrl ?: summary.backdropUrl,
                                            episodeProgress = epProgress,
                                            isWatched = isWatched,
                                            isDownloaded = isEpDownloaded,
                                            onToggleWatched = { onToggleEpisodeWatched(ep) },
                                            onClick = { onPlayClick(ep.seasonNumber, ep.episodeNumber) },
                                            modifier = Modifier.padding(horizontal = 16.dp)
                                        )
                                    }
                                    if (uiState.episodes.size > visibleEpisodeCount) {
                                        item(key = "detail_episodes_load_more") {
                                            OutlinedButton(
                                                onClick = { visibleEpisodeCount += 10 },
                                                shape = RoundedCornerShape(12.dp),
                                                border = BorderStroke(1.dp, colors.borderSubtle),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp)
                                                    .testTag("detail_episodes_load_more")
                                            ) {
                                                val remaining = uiState.episodes.size - visibleEpisodeCount
                                                Text(
                                                    text = if (remaining > 10) "Load more (10 of $remaining remaining)" else "Load more ($remaining remaining)",
                                                    style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                                    color = colors.textPrimary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (uiState.episodes.isNotEmpty()) {
                            val visibleEpisodes = uiState.episodes.take(visibleEpisodeCount)
                            itemsIndexed(
                                items = visibleEpisodes,
                                key = { index, ep -> "ep_pre_${index}_${ep.id}_${ep.seasonNumber}_${ep.episodeNumber}" }
                            ) { _, ep ->
                                val epProgress = uiState.seriesProgress?.episodeStates
                                    ?.get(ep.seasonNumber to ep.episodeNumber)
                                val isWatched = epProgress?.isCompleted == true
                                val isEpDownloaded = offlineLookup.hasEpisode(summary.providerId, summary.id, ep.seasonNumber, ep.episodeNumber)
                                EpisodeRowCard(
                                    episode = ep,
                                    seriesPosterUrl = summary.posterUrl ?: summary.backdropUrl,
                                    episodeProgress = epProgress,
                                    isWatched = isWatched,
                                    isDownloaded = isEpDownloaded,
                                    onToggleWatched = { onToggleEpisodeWatched(ep) },
                                    onClick = { onPlayClick(ep.seasonNumber, ep.episodeNumber) },
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        } else if (uiState.isRefreshingDetails || uiState.isLoadingEpisodes) {
                            item(key = "episodes_loading_initial") {
                                EpisodeListSkeleton()
                            }
                        } else {
                            item(key = "episodes_unavailable") {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = colors.surfaceSecondary,
                                    border = BorderStroke(0.75.dp, colors.borderSubtle),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                ) {
                                    Text(
                                        text = "No season breakdown was returned for this series.",
                                        style = typography.bodySecondary,
                                        color = colors.textSecondary,
                                        modifier = Modifier.padding(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Debug-only Canonical Provider Links & Unlink
                    if (BuildConfig.DEBUG && uiState.providerLinks.isNotEmpty()) {
                        item(key = "detail_debug_provider_links") {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = colors.surfaceSecondary,
                                border = BorderStroke(0.75.dp, colors.borderSubtle),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .testTag("detail_debug_provider_links")
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Canonical Provider Links (Debug)",
                                        style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.textPrimary
                                    )
                                    uiState.providerLinks.forEach { link ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "${link.providerId}:${link.providerTitleId}",
                                                    style = typography.metaText.copy(fontWeight = FontWeight.Medium),
                                                    color = colors.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "Confidence: ${link.confidence}",
                                                    style = typography.caption,
                                                    color = colors.textSecondary
                                                )
                                            }
                                            if (uiState.providerLinks.size > 1) {
                                                TextButton(
                                                    onClick = {
                                                        onUnlinkProviderLink(link.providerId, link.providerTitleId)
                                                    },
                                                    modifier = Modifier.testTag(
                                                        "debug_unlink_${link.providerId}_${link.providerTitleId}"
                                                    )
                                                ) {
                                                    Text("Unlink", color = colors.statusDanger)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        if (uiState.isTrailerFullscreen && trailerPlayer != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent()
                            }
                        }
                    }
                    .testTag("detail_trailer_fullscreen_dialog")
            ) {
                InlineTrailerPlayerSurface(
                    exoPlayer = trailerPlayer,
                    title = summaryForTrailer?.title ?: "Trailer",
                    clockState = trailerClock,
                    isMuted = uiState.trailerMuted,
                    isFullscreen = true,
                    playbackPreferences = uiState.playbackPreferences,
                    attachVideoSurface = true,
                    onPlayerViewAttached = { attachedTrailerView = it },
                    onTogglePlayPause = {
                        if (trailerPlayer.isPlaying) trailerPlayer.pause() else trailerPlayer.play()
                    },
                    onSeekTo = { target ->
                        trailerPlayer.seekTo(target)
                        trailerClock.positionMs = target
                    },
                    onToggleMute = onToggleTrailerMute,
                    onToggleFullscreen = {
                        onSetTrailerFullscreen(false, trailerPlayer.currentPosition.coerceAtLeast(0L))
                    },
                    onClose = onStopTrailer,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun MetadataPillChip(
    label: String,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = colors.surfacePrimary,
        border = BorderStroke(0.75.dp, colors.borderSubtle)
    ) {
        Text(
            text = label,
            style = typography.caption,
            color = colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

internal fun computeEpisodePartialProgressFraction(
    episodeProgress: com.laterbox.app.movies.domain.model.EpisodeProgressState?,
    isWatched: Boolean
): Float? {
    if (isWatched || episodeProgress == null || episodeProgress.isCompleted) return null
    if (episodeProgress.positionMs <= 0L || episodeProgress.durationMs <= 0L) return null
    val raw = episodeProgress.positionMs.toFloat() / episodeProgress.durationMs.toFloat()
    if (raw >= 0.98f) return null
    return raw.coerceIn(0.02f, 0.98f)
}

@Composable
private fun EpisodeRowCard(
    episode: EpisodeItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    seriesPosterUrl: String? = null,
    episodeProgress: com.laterbox.app.movies.domain.model.EpisodeProgressState? = null,
    isWatched: Boolean = false,
    isDownloaded: Boolean = false,
    onToggleWatched: () -> Unit = {}
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    var stillLoadFailed by remember(episode.stillUrl) { mutableStateOf(false) }
    var fallbackLoadFailed by remember(seriesPosterUrl) { mutableStateOf(false) }
    val partialProgress = computeEpisodePartialProgressFraction(
        episodeProgress = episodeProgress,
        isWatched = isWatched
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("episode_row_${episode.seasonNumber}_${episode.episodeNumber}"),
        shape = RoundedCornerShape(14.dp),
        color = colors.surfacePrimary,
        border = BorderStroke(
            0.75.dp,
            if (isWatched) colors.accentPrimary.copy(alpha = 0.45f) else colors.borderSubtle
        )
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(112.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceSecondary),
                contentAlignment = Alignment.Center
            ) {
                val hasEpisodeStill = !episode.stillUrl.isNullOrBlank() && !stillLoadFailed
                val hasSeriesFallback = !seriesPosterUrl.isNullOrBlank() && !fallbackLoadFailed
                when {
                    hasEpisodeStill -> {
                        AsyncImage(
                            model = episode.stillUrl,
                            contentDescription = episode.title,
                            contentScale = ContentScale.Crop,
                            onError = { stillLoadFailed = true },
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("episode_still_${episode.seasonNumber}_${episode.episodeNumber}")
                        )
                    }
                    hasSeriesFallback -> {
                        AsyncImage(
                            model = seriesPosterUrl,
                            contentDescription = episode.title,
                            contentScale = ContentScale.Crop,
                            onError = { fallbackLoadFailed = true },
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(10.dp)
                                .testTag("episode_blurred_fallback_${episode.seasonNumber}_${episode.episodeNumber}")
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.28f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "E${episode.episodeNumber}",
                                style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White.copy(alpha = 0.92f)
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = "E${episode.episodeNumber}",
                            style = typography.titleLarge,
                            color = colors.textTertiary
                        )
                    }
                }
                if (isDownloaded) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF1B5E20).copy(alpha = 0.88f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .testTag("episode_offline_badge_${episode.seasonNumber}_${episode.episodeNumber}")
                    ) {
                        Text(
                            text = "OFFLINE",
                            style = typography.caption.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
                if (partialProgress != null) {
                    LinearProgressIndicator(
                        progress = { partialProgress },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp)
                            .testTag("episode_progress_${episode.seasonNumber}_${episode.episodeNumber}"),
                        color = colors.accentPrimary,
                        trackColor = Color.Black.copy(alpha = 0.45f)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "${episode.episodeNumber}. ${episode.title}",
                    style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val epMeta = buildList {
                    episode.runtimeMinutes?.let { add("${it}m") }
                    episode.airDate?.let { add(it) }
                    if (isWatched) add("Watched")
                }.joinToString(" · ")
                if (epMeta.isNotBlank()) {
                    Text(
                        text = epMeta,
                        style = typography.caption,
                        color = if (isWatched) colors.accentPrimary else colors.textSecondary
                    )
                }
                episode.overview?.let { overview ->
                    Text(
                        text = overview,
                        style = typography.bodySecondary.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(
                onClick = onToggleWatched,
                modifier = Modifier
                    .size(34.dp)
                    .testTag("episode_watched_toggle_${episode.seasonNumber}_${episode.episodeNumber}")
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isWatched) colors.accentPrimary else colors.surfaceSecondary,
                    border = BorderStroke(
                        0.75.dp,
                        if (isWatched) colors.accentPrimary else colors.borderSubtle
                    ),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(LaterboxIcons.Check),
                            contentDescription = if (isWatched) "Mark episode unwatched" else "Mark episode watched",
                            tint = if (isWatched) colors.onAccentPrimary else colors.textTertiary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            Surface(
                shape = CircleShape,
                color = colors.accentContainer,
                border = BorderStroke(0.75.dp, colors.accentPrimary.copy(alpha = 0.35f)),
                modifier = Modifier
                    .size(34.dp)
                    .testTag("episode_play_button_${episode.seasonNumber}_${episode.episodeNumber}")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(LaterboxIcons.Play),
                        contentDescription = "Play episode ${episode.episodeNumber}",
                        tint = colors.accentPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

internal fun OfflineMediaMatch.matchesTitleComposite(providerId: String, titleId: String): Boolean {
    val parts = sourceRef.split("|")
    return parts.size >= 2 &&
        parts[0].equals(providerId, ignoreCase = true) &&
        parts[1].equals(titleId, ignoreCase = true)
}

internal fun OfflineMediaMatch.matchesTitleEpisode(
    providerId: String,
    titleId: String,
    seasonNum: Int,
    episodeNum: Int
): Boolean {
    if (!matchesTitleComposite(providerId, titleId)) return false
    return seasonNumber == seasonNum && episodeNumber == episodeNum
}

internal class OfflineMediaLookup(
    private val titleKeys: Set<String>,
    private val episodeKeys: Set<String>
) {
    fun hasTitle(providerId: String, titleId: String): Boolean =
        titleKeys.contains("${providerId.lowercase(java.util.Locale.US)}|${titleId.lowercase(java.util.Locale.US)}")

    fun hasEpisode(providerId: String, titleId: String, seasonNum: Int, episodeNum: Int): Boolean =
        episodeKeys.contains("${providerId.lowercase(java.util.Locale.US)}|${titleId.lowercase(java.util.Locale.US)}|$seasonNum|$episodeNum")

    companion object {
        fun from(matches: List<OfflineMediaMatch>): OfflineMediaLookup {
            if (matches.isEmpty()) return OfflineMediaLookup(emptySet(), emptySet())
            val titles = HashSet<String>(matches.size * 2)
            val episodes = HashSet<String>(matches.size * 2)
            for (match in matches) {
                val parts = match.sourceRef.split("|")
                if (parts.size >= 2) {
                    val baseKey = "${parts[0].lowercase(java.util.Locale.US)}|${parts[1].lowercase(java.util.Locale.US)}"
                    titles.add(baseKey)
                    val s = match.seasonNumber
                    val e = match.episodeNumber
                    if (s != null && e != null) {
                        episodes.add("$baseKey|$s|$e")
                    }
                }
            }
            return OfflineMediaLookup(titles, episodes)
        }
    }
}

@Stable
private class TrailerPlaybackClockState(initialPositionMs: Long = 0L) {
    var positionMs by mutableLongStateOf(initialPositionMs)
    var durationMs by mutableLongStateOf(0L)
    var isPlaying by mutableStateOf(true)
}

@Composable
private fun TrailerProgressPoller(
    player: ExoPlayer,
    clockState: TrailerPlaybackClockState,
    onRecordProgress: (Long, Long) -> Unit
) {
    val latestOnRecord by rememberUpdatedState(onRecordProgress)
    LaunchedEffect(player, clockState) {
        var tick = 0
        while (true) {
            val cur = player.currentPosition.coerceAtLeast(0L)
            val dur = player.duration.coerceAtLeast(0L)
            clockState.positionMs = cur
            clockState.durationMs = dur
            clockState.isPlaying = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
            tick++
            if (tick % 8 == 0 && player.isPlaying) {
                latestOnRecord(cur, dur)
            }
            delay(350L)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun InlineTrailerPlayerSurface(
    exoPlayer: ExoPlayer,
    title: String,
    clockState: TrailerPlaybackClockState,
    isMuted: Boolean,
    isFullscreen: Boolean,
    playbackPreferences: PlayerPlaybackPreferences = PlayerPlaybackPreferences(),
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    attachVideoSurface: Boolean = true,
    onPlayerViewAttached: ((PlayerView?) -> Unit)? = null
) {
    val context = LocalContext.current
    val dialogView = LocalView.current
    val typography = LaterboxTheme.typography
    val colors = LaterboxTheme.colors
    var controlsVisible by remember { mutableStateOf(true) }
    var isScrubbing by remember { mutableStateOf(false) }

    val baseSensitivityTier = remember(playbackPreferences.swipeSeekSensitivity) {
        SeekSensitivityTier.fromPreference(playbackPreferences.swipeSeekSensitivity)
    }
    val seekJumpSeconds = playbackPreferences.doubleTapSeekSeconds.coerceIn(5, 60)
    val seekJumpMs = seekJumpSeconds * 1000L

    val latestIsMuted by rememberUpdatedState(isMuted)
    val latestOnSeekTo by rememberUpdatedState(onSeekTo)
    val latestOnToggleMute by rememberUpdatedState(onToggleMute)
    val latestBaseSensitivity by rememberUpdatedState(baseSensitivityTier)

    val targetWindow = if (isFullscreen) {
        (dialogView.parent as? DialogWindowProvider)?.window ?: context.findDetailActivity()?.window
    } else {
        null
    }
    val brightnessHelper = remember(isFullscreen, targetWindow) {
        if (isFullscreen) PlayerBrightnessHelper(targetWindow) else null
    }
    var brightnessFraction by remember(isFullscreen) {
        mutableFloatStateOf(brightnessHelper?.currentNormalizedBrightness ?: 0.5f)
    }
    var showBrightnessHud by remember(isFullscreen) { mutableStateOf(false) }
    var isBrightnessDragging by remember(isFullscreen) { mutableStateOf(false) }

    val audioBoostController = remember(isFullscreen, context) {
        if (isFullscreen) PlayerAudioBoostController(context) else null
    }
    var currentVolumePercent by remember(isFullscreen) {
        mutableIntStateOf(
            if (isFullscreen && isMuted) 0 else audioBoostController?.currentVolumePercent ?: 100
        )
    }
    var volumeFloatPercent by remember(isFullscreen) {
        mutableFloatStateOf(currentVolumePercent.toFloat())
    }
    var showVolumeHud by remember(isFullscreen) { mutableStateOf(false) }
    var isVolumeDragging by remember(isFullscreen) { mutableStateOf(false) }
    var unmutedDuringDrag by remember(isFullscreen) { mutableStateOf(false) }

    var isHorizontalScrubbing by remember(isFullscreen) { mutableStateOf(false) }
    var scrubTargetMs by remember(isFullscreen) { mutableLongStateOf(0L) }
    var scrubDeltaMs by remember(isFullscreen) { mutableLongStateOf(0L) }
    var scrubSensitivity by remember(isFullscreen, baseSensitivityTier) { mutableStateOf(baseSensitivityTier) }

    var isHolding2x by remember(isFullscreen) { mutableStateOf(false) }
    var ignoreNextTap by remember(isFullscreen) { mutableStateOf(false) }
    var seekFeedbackText by remember(isFullscreen) { mutableStateOf<String?>(null) }

    LaunchedEffect(seekFeedbackText) {
        if (seekFeedbackText != null) {
            delay(700L)
            seekFeedbackText = null
        }
    }

    if (isFullscreen && brightnessHelper != null) {
        DisposableEffect(brightnessHelper) {
            onDispose {
                brightnessHelper.restoreOriginalBrightness()
            }
        }
    }

    if (isFullscreen && audioBoostController != null) {
        LaunchedEffect(audioBoostController, exoPlayer) {
            audioBoostController.bindToPlayer(exoPlayer)
            exoPlayer.volume = if (latestIsMuted) {
                0f
            } else {
                PlayerAudioBoostController.computeExoPlayerVolume(currentVolumePercent)
            }
        }
        LaunchedEffect(isMuted, audioBoostController, exoPlayer) {
            exoPlayer.volume = if (isMuted) {
                0f
            } else {
                PlayerAudioBoostController.computeExoPlayerVolume(currentVolumePercent)
            }
            if (isMuted && currentVolumePercent > 100) {
                audioBoostController.resetBoost(exoPlayer)
            }
        }
        DisposableEffect(audioBoostController) {
            onDispose {
                audioBoostController.release()
                exoPlayer.volume = if (latestIsMuted) 0f else 1f
            }
        }
    }

    LaunchedEffect(showBrightnessHud, isBrightnessDragging) {
        if (showBrightnessHud && !isBrightnessDragging) {
            delay(1000L)
            showBrightnessHud = false
        }
    }
    LaunchedEffect(showVolumeHud, isVolumeDragging) {
        if (showVolumeHud && !isVolumeDragging) {
            delay(1000L)
            showVolumeHud = false
        }
    }

    val isCurrentlyPlaying = clockState.isPlaying
    LaunchedEffect(controlsVisible, isCurrentlyPlaying, isScrubbing, isHorizontalScrubbing) {
        if (controlsVisible && isCurrentlyPlaying && !isScrubbing && !isHorizontalScrubbing) {
            delay(3500L)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .playerDragGestures(
                enabled = isFullscreen,
                swipeToSeekEnabled = isFullscreen && playbackPreferences.swipeToSeekEnabled,
                brightnessGestureEnabled = isFullscreen && playbackPreferences.brightnessGestureEnabled,
                volumeGestureEnabled = isFullscreen && playbackPreferences.volumeGestureEnabled,
                baseSensitivity = baseSensitivityTier,
                currentPositionProvider = { exoPlayer.currentPosition.coerceAtLeast(clockState.positionMs) },
                durationProvider = { exoPlayer.duration.coerceAtLeast(clockState.durationMs) },
                onHorizontalScrubStart = { startPos ->
                    isHorizontalScrubbing = true
                    scrubTargetMs = startPos
                    scrubDeltaMs = 0L
                    scrubSensitivity = latestBaseSensitivity
                },
                onHorizontalScrubUpdate = { targetMs, deltaMs, sensitivity ->
                    scrubTargetMs = targetMs
                    scrubDeltaMs = deltaMs
                    scrubSensitivity = sensitivity
                },
                onHorizontalScrubEnd = { targetMs ->
                    if (isHorizontalScrubbing) {
                        latestOnSeekTo(targetMs)
                    }
                    isHorizontalScrubbing = false
                    scrubDeltaMs = 0L
                },
                onHorizontalScrubCancel = {
                    isHorizontalScrubbing = false
                    scrubDeltaMs = 0L
                },
                onBrightnessDelta = { deltaFraction ->
                    isBrightnessDragging = true
                    val next = PlayerGesturesMath.applyBrightnessDelta(brightnessFraction, deltaFraction)
                    brightnessFraction = next
                    brightnessHelper?.setBrightness(next)
                    showBrightnessHud = true
                },
                onBrightnessGestureEnd = {
                    isBrightnessDragging = false
                },
                onVolumeDelta = { deltaPercent ->
                    isVolumeDragging = true
                    val boostSupported = audioBoostController?.isBoostSupported == true
                    val maxPct = if (boostSupported) PlayerAudioBoostController.MAX_PERCENT else PlayerAudioBoostController.NORMAL_MAX_PERCENT
                    val basePct = if (latestIsMuted && !unmutedDuringDrag && volumeFloatPercent <= 0f) {
                        0f
                    } else {
                        volumeFloatPercent
                    }
                    val nextFloat = PlayerGesturesMath.applyVolumeDelta(basePct, deltaPercent, maxPct)
                    volumeFloatPercent = nextFloat
                    val appliedPct = audioBoostController?.applyVolumePercent(nextFloat.roundToInt(), exoPlayer)
                        ?: nextFloat.roundToInt()
                    currentVolumePercent = appliedPct
                    if (appliedPct > 0 && latestIsMuted && !unmutedDuringDrag) {
                        unmutedDuringDrag = true
                        latestOnToggleMute()
                    }
                    if (latestIsMuted && !unmutedDuringDrag) {
                        exoPlayer.volume = 0f
                    }
                    showVolumeHud = true
                },
                onVolumeGestureEnd = {
                    isVolumeDragging = false
                    unmutedDuringDrag = false
                }
            )
            .pointerInput(isFullscreen, exoPlayer) {
                if (!isFullscreen) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    ignoreNextTap = false
                    val downPos = down.position
                    val slop = viewConfiguration.touchSlop
                    var activatedHold = false
                    var releasedOrMovedOut = false

                    try {
                        val deadline = System.currentTimeMillis() + 320L
                        while (!releasedOrMovedOut && !activatedHold) {
                            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                            val event = withTimeoutOrNull(remaining) {
                                awaitPointerEvent(PointerEventPass.Initial)
                            }
                            if (event == null) {
                                activatedHold = true
                                isHolding2x = true
                                ignoreNextTap = true
                                exoPlayer.setPlaybackSpeed(2.0f)
                            } else {
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed ||
                                    abs(change.position.x - downPos.x) > slop ||
                                    abs(change.position.y - downPos.y) > slop
                                ) {
                                    releasedOrMovedOut = true
                                }
                            }
                        }

                        while (activatedHold) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                break
                            }
                        }
                    } finally {
                        if (activatedHold) {
                            isHolding2x = false
                            exoPlayer.setPlaybackSpeed(1.0f)
                        }
                    }
                }
            }
            .pointerInput(isFullscreen, exoPlayer, seekJumpMs, seekJumpSeconds) {
                detectTapGestures(
                    onTap = {
                        if (ignoreNextTap) {
                            ignoreNextTap = false
                        } else {
                            controlsVisible = !controlsVisible
                        }
                    },
                    onDoubleTap = { offset ->
                        ignoreNextTap = false
                        if (!isFullscreen) return@detectTapGestures
                        val widthPx = size.width.toFloat().coerceAtLeast(1f)
                        val cur = exoPlayer.currentPosition.coerceAtLeast(clockState.positionMs)
                        val dur = exoPlayer.duration.coerceAtLeast(clockState.durationMs)
                        if (offset.x < widthPx / 2f) {
                            val target = (cur - seekJumpMs).coerceAtLeast(0L)
                            latestOnSeekTo(target)
                            seekFeedbackText = "-${seekJumpSeconds}s"
                        } else {
                            val target = if (dur > 0L) {
                                (cur + seekJumpMs).coerceAtMost(dur)
                            } else {
                                cur + seekJumpMs
                            }
                            latestOnSeekTo(target)
                            seekFeedbackText = "+${seekJumpSeconds}s"
                        }
                    }
                )
            }
            .testTag(if (isFullscreen) "detail_fullscreen_trailer_player" else "detail_inline_trailer_player")
    ) {
        if (attachVideoSurface) {
            LaterboxVideoSurface(
                player = exoPlayer,
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                keepScreenOn = clockState.isPlaying,
                useTextureView = true,
                onPlayerViewAttached = onPlayerViewAttached,
                modifier = Modifier.fillMaxSize()
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && !isHorizontalScrubbing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.60f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.72f)
                            )
                        )
                    )
                    .then(if (isFullscreen) Modifier.displayCutoutPadding() else Modifier)
                    .padding(horizontal = if (isFullscreen) 18.dp else 10.dp, vertical = if (isFullscreen) 12.dp else 8.dp)
            ) {
                // Top compact bar: Close + Title (fullscreen) + Mute/Sound pill
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.55f)
                        ) {
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("detail_trailer_close_button")
                            ) {
                                Icon(
                                    painter = painterResource(LaterboxIcons.Close),
                                    contentDescription = "Close trailer",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        if (isFullscreen) {
                            Text(
                                text = "$title · Trailer",
                                style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Black.copy(alpha = 0.55f)
                            ) {
                                Text(
                                    text = "Trailer",
                                    style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.62f),
                        border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(onClick = onToggleMute)
                            .testTag("detail_trailer_mute_button")
                    ) {
                        Text(
                            text = if (isMuted) "Muted" else "Sound",
                            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = if (isMuted) Color.White.copy(alpha = 0.78f) else colors.accentPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                // Center Play/Pause control
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.58f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("detail_trailer_play_pause_button")
                    ) {
                        Icon(
                            painter = painterResource(
                                if (clockState.isPlaying) LaterboxIcons.Pause else LaterboxIcons.Play
                            ),
                            contentDescription = if (clockState.isPlaying) "Pause trailer" else "Play trailer",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Isolated scrubber row so 350ms position ticks only recompose this row
                TrailerScrubberRow(
                    clockState = clockState,
                    isFullscreen = isFullscreen,
                    onScrubbingChanged = { isScrubbing = it },
                    onSeekTo = onSeekTo,
                    onToggleFullscreen = onToggleFullscreen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                )
            }
        }

        if (isFullscreen) {
            PlayerHold2xSpeedBadge(
                visible = isHolding2x,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 18.dp)
            )

            PlayerSeekFeedbackBadge(
                visible = seekFeedbackText != null && !isHorizontalScrubbing,
                text = seekFeedbackText.orEmpty(),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = if (controlsVisible) 88.dp else 0.dp)
            )

            PlayerBrightnessHud(
                visible = showBrightnessHud,
                brightnessFraction = brightnessFraction,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 28.dp)
            )

            PlayerVolumeBoostHud(
                visible = showVolumeHud,
                volumePercent = currentVolumePercent,
                isBoostSupported = audioBoostController?.isBoostSupported ?: true,
                fallbackNotice = audioBoostController?.lastFallbackReason,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 28.dp)
            )

            PlayerHorizontalScrubHud(
                visible = isHorizontalScrubbing,
                scrubTargetMs = scrubTargetMs,
                scrubDeltaMs = scrubDeltaMs,
                durationMs = exoPlayer.duration.coerceAtLeast(clockState.durationMs),
                sensitivityTier = scrubSensitivity,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun TrailerScrubberRow(
    clockState: TrailerPlaybackClockState,
    isFullscreen: Boolean,
    onScrubbingChanged: (Boolean) -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    val currentPositionMs = clockState.positionMs
    val durationMs = clockState.durationMs
    val safeDuration = durationMs.coerceAtLeast(1L)
    val displayPos = if (isScrubbing) {
        (scrubFraction * safeDuration).toLong()
    } else {
        currentPositionMs.coerceIn(0L, safeDuration)
    }
    val sliderVal = if (isScrubbing) {
        scrubFraction
    } else if (durationMs > 0L) {
        (currentPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = formatTrailerClock(displayPos),
            style = typography.caption.copy(fontSize = 11.sp),
            color = Color.White
        )
        Slider(
            value = sliderVal,
            onValueChange = { frac ->
                isScrubbing = true
                onScrubbingChanged(true)
                scrubFraction = frac.coerceIn(0f, 1f)
            },
            onValueChangeFinished = {
                if (durationMs > 0L) {
                    onSeekTo((scrubFraction * durationMs).toLong().coerceIn(0L, durationMs))
                }
                isScrubbing = false
                onScrubbingChanged(false)
            },
            colors = SliderDefaults.colors(
                thumbColor = colors.accentPrimary,
                activeTrackColor = colors.accentPrimary,
                inactiveTrackColor = Color.White.copy(alpha = 0.30f)
            ),
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .testTag("detail_trailer_seek_slider")
        )
        Text(
            text = formatTrailerClock(durationMs),
            style = typography.caption.copy(fontSize = 11.sp),
            color = Color.White.copy(alpha = 0.8f)
        )

        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.55f)
        ) {
            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier
                    .size(34.dp)
                    .testTag("detail_trailer_expand_button")
            ) {
                Icon(
                    painter = painterResource(
                        if (isFullscreen) LaterboxIcons.FullscreenExit else LaterboxIcons.Fullscreen
                    ),
                    contentDescription = if (isFullscreen) "Exit fullscreen" else "Expand trailer",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun formatTrailerClock(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
    } else {
        String.format(Locale.US, "%d:%02d", mins, secs)
    }
}

private tailrec fun Context.findDetailActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findDetailActivity()
    else -> null
}


