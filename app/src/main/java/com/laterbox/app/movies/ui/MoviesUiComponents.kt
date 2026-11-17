package com.laterbox.app.movies.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.laterbox.app.BuildConfig
import com.laterbox.app.data.preferences.MovieBoxEndpointConfig
import com.laterbox.app.movies.domain.model.CastMember
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
import com.laterbox.app.movies.domain.model.PersonFilmography
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.movies.domain.model.SearchSuggestion
import com.laterbox.app.movies.domain.model.SeasonSummary
import com.laterbox.app.movies.domain.model.SubtitleTrack
import com.laterbox.app.movies.domain.model.WatchHistoryItem
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme
import java.util.Locale

@Composable
fun DataSourceBadgeChip(
    badge: DataSourceBadge,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val container = if (badge.isSampleFixture) {
        colors.statusWarning.copy(alpha = 0.16f)
    } else {
        colors.surfaceSecondary
    }
    val textColor = if (badge.isSampleFixture) {
        colors.statusWarning
    } else {
        colors.textSecondary
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = container,
        border = BorderStroke(0.5.dp, textColor.copy(alpha = 0.25f))
    ) {
        Text(
            text = badge.displayLabel.uppercase(Locale.US),
            style = LaterboxTheme.typography.badgeText,
            color = textColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun MoviePosterCard(
    item: MovieTitleSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 132.dp,
    testTag: String = "movie_card_${item.providerId}_${item.id}"
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    Column(
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surfaceSecondary)
                .border(0.75.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
        ) {
            if (!item.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(LaterboxIcons.Movies),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = colors.textTertiary
                    )
                }
            }

            // Subtle bottom gradient for badge legibility
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                        )
                    )
            )

            // Rating pill (top-left)
            item.rating?.let { rating ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    Text(
                        text = String.format(Locale.US, "★ %.1f", rating),
                        style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFFFFD54F),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            // Playable indicator (bottom-right)
            if (item.isPlayableHint) {
                Surface(
                    shape = CircleShape,
                    color = colors.accentPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(LaterboxIcons.Play),
                            contentDescription = "Stream available",
                            modifier = Modifier.size(12.dp),
                            tint = colors.onAccentPrimary
                        )
                    }
                }
            }

            // Kind / fixture micro-badge (bottom-left)
            val badgeText = when {
                item.sourceBadge.isSampleFixture -> "SAMPLE"
                item.kind == MediaKind.TV_SERIES -> "SERIES"
                else -> "FILM"
            }
            Text(
                text = badgeText,
                style = typography.caption.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = 0.92f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 7.dp, vertical = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = item.title,
            style = typography.uiLabel.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp
        )

        val metaParts = buildList {
            item.releaseYear?.let { add(it.toString()) }
            item.genres.firstOrNull()?.let { add(it) }
        }
        if (metaParts.isNotEmpty()) {
            Text(
                text = metaParts.joinToString(" · "),
                style = typography.metaText.copy(fontSize = 10.sp),
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ContinueWatchingCard(
    item: WatchHistoryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    Surface(
        modifier = modifier
            .width(196.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("continue_watching_${item.compositeId}"),
        shape = RoundedCornerShape(14.dp),
        color = colors.surfacePrimary,
        border = BorderStroke(0.75.dp, colors.borderSubtle)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .background(colors.surfaceSecondary)
            ) {
                if (!item.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.30f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = colors.accentPrimary.copy(alpha = 0.94f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(LaterboxIcons.Play),
                                contentDescription = "Resume",
                                modifier = Modifier.size(16.dp),
                                tint = colors.onAccentPrimary
                            )
                        }
                    }
                }
                LinearProgressIndicator(
                    progress = { item.progressFraction.coerceAtLeast(0.04f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter),
                    color = colors.accentPrimary,
                    trackColor = Color.White.copy(alpha = 0.25f)
                )
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = item.title,
                    style = typography.uiLabel.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subText = item.episodeLabel ?: formatResumeTime(item.positionMs, item.durationMs)
                Text(
                    text = subText,
                    style = typography.metaText.copy(fontSize = 10.sp),
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun MoviesHeroCarousel(
    banners: List<MovieTitleSummary>,
    watchlistIds: Set<String>,
    onBannerClick: (MovieTitleSummary) -> Unit,
    onPlayClick: (MovieTitleSummary) -> Unit,
    onToggleWatchlist: (MovieTitleSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    if (banners.isEmpty()) return
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val pagerState = rememberPagerState(pageCount = { banners.size })

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(256.dp)
                .testTag("movies_hero_pager")
        ) { page ->
            val item = banners[page]
            val isSaved = item.compositeId in watchlistIds

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = colors.surfaceSecondary,
                border = BorderStroke(0.75.dp, colors.borderSubtle),
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onBannerClick(item) }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val heroImage = item.backdropUrl ?: item.posterUrl
                    if (!heroImage.isNullOrBlank()) {
                        AsyncImage(
                            model = heroImage,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.20f),
                                        Color.Black.copy(alpha = 0.55f),
                                        Color.Black.copy(alpha = 0.90f)
                                    )
                                )
                            )
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .align(Alignment.TopCenter),
                        horizontalArrangement = if (item.sourceBadge.isSampleFixture) {
                            Arrangement.SpaceBetween
                        } else {
                            Arrangement.End
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (item.sourceBadge.isSampleFixture) {
                            DataSourceBadgeChip(badge = item.sourceBadge)
                        }
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.52f)
                        ) {
                            IconButton(
                                onClick = { onToggleWatchlist(item) },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("hero_bookmark_${item.id}")
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (isSaved) LaterboxIcons.Check else LaterboxIcons.Bookmark
                                    ),
                                    contentDescription = if (isSaved) "In Watchlist" else "Add to Watchlist",
                                    tint = if (isSaved) colors.accentPrimary else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val meta = buildList {
                            add(item.kind.label)
                            item.releaseYear?.let { add(it.toString()) }
                            item.rating?.let { add(String.format(Locale.US, "★ %.1f", it)) }
                            item.genres.firstOrNull()?.let { add(it) }
                        }.joinToString(" · ")
                        Text(
                            text = meta,
                            style = typography.caption.copy(fontWeight = FontWeight.Medium),
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = item.title,
                            style = typography.editorialTitle.copy(
                                fontSize = 22.sp,
                                lineHeight = 26.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (item.isPlayableHint) onPlayClick(item) else onBannerClick(item)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accentPrimary,
                                    contentColor = colors.onAccentPrimary
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (item.isPlayableHint) LaterboxIcons.Play else LaterboxIcons.Movies
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (item.isPlayableHint) "Watch Now" else "View Guide",
                                    style = typography.buttonLabel.copy(fontSize = 12.sp)
                                )
                            }
                            OutlinedButton(
                                onClick = { onBannerClick(item) },
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(
                                    text = "Details",
                                    style = typography.buttonLabel.copy(fontSize = 12.sp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (banners.size > 1) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(banners.size) { index ->
                    val selected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = if (selected) 16.dp else 6.dp, height = 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) colors.accentPrimary else colors.borderSubtle
                            )
                    )
                }
            }
        }
    }
}

@Composable
fun MoviesHomeSkeleton(modifier: Modifier = Modifier) {
    val colors = LaterboxTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("movies_loading_skeleton"),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceSecondary)
        )
        repeat(2) {
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceSecondary)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .width(128.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surfaceSecondary)
                    )
                }
            }
        }
    }
}

@Composable
fun MoviesProviderSourceDialog(
    useSampleFixturesOnly: Boolean,
    onToggleSampleFixtures: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    currentMovieBoxBaseUrl: String = "",
    effectiveMovieBoxBaseUrl: String = "",
    currentMovieBoxApiKey: String = "",
    onSaveMovieBoxConfig: (baseUrl: String, apiKey: String) -> Unit = { _, _ -> }
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    var movieBoxUrlInput by remember(currentMovieBoxBaseUrl) { mutableStateOf(currentMovieBoxBaseUrl) }
    var movieBoxApiKeyInput by remember(currentMovieBoxApiKey) { mutableStateOf(currentMovieBoxApiKey) }
    var showMovieBoxApiKey by remember { mutableStateOf(false) }
    val isUrlValid = MovieBoxEndpointConfig.isValidBaseUrl(
        rawUrl = movieBoxUrlInput,
        allowDebugCleartextLoopback = BuildConfig.DEBUG
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceElevated,
        title = {
            Text(
                text = "Movies & TV Data Sources",
                style = typography.sheetTitle,
                color = colors.textPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "MovieBox streams and metadata are unified with Internet Archive and TVMaze automatic fallback.",
                    style = typography.bodySecondary,
                    color = colors.textSecondary
                )

                // MovieBox Server Configuration
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceSecondary)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "MovieBox API Server",
                            style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary
                        )
                        if (movieBoxUrlInput.isNotBlank()) {
                            TextButton(
                                onClick = { movieBoxUrlInput = "" },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text("Reset Default", style = typography.caption, color = colors.accentPrimary)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = movieBoxUrlInput,
                        onValueChange = { movieBoxUrlInput = it },
                        label = { Text("Server Base URL") },
                        placeholder = {
                            Text(
                                effectiveMovieBoxBaseUrl.ifBlank { MovieBoxEndpointConfig.DEFAULT_PRODUCTION_BASE_URL }
                            )
                        },
                        isError = !isUrlValid,
                        supportingText = {
                            if (!isUrlValid) {
                                Text(
                                    text = if (BuildConfig.DEBUG) {
                                        "Use https:// (or http://10.0.2.2 / localhost in debug)."
                                    } else {
                                        "Use an https:// MovieBox API URL."
                                    },
                                    color = colors.statusDanger
                                )
                            } else if (effectiveMovieBoxBaseUrl.isNotBlank() && movieBoxUrlInput.isBlank()) {
                                Text("Active default: $effectiveMovieBoxBaseUrl")
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("moviebox_base_url_input")
                    )
                    OutlinedTextField(
                        value = movieBoxApiKeyInput,
                        onValueChange = { movieBoxApiKeyInput = it },
                        label = { Text("MovieBox API Key") },
                        placeholder = { Text("X-API-Key (stored locally only)") },
                        visualTransformation = if (showMovieBoxApiKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            TextButton(
                                onClick = { showMovieBoxApiKey = !showMovieBoxApiKey },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.testTag("moviebox_api_key_toggle")
                            ) {
                                Text(
                                    text = if (showMovieBoxApiKey) "Hide" else "Show",
                                    style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                    color = colors.accentPrimary
                                )
                            }
                        },
                        supportingText = {
                            val keyStatus = when {
                                movieBoxApiKeyInput.isNotBlank() -> "Using custom API key stored locally on this device."
                                BuildConfig.DEBUG && BuildConfig.MOVIEBOX_API_KEY.isNotBlank() ->
                                    "Using debug build-config key (never bundled in release APKs)."
                                else -> "Leave blank when using verified App Check, or paste fallback X-API-Key."
                            }
                            Text(keyStatus, color = colors.textSecondary)
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("moviebox_api_key_input")
                    )
                }

                if (BuildConfig.DEBUG) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceSecondary)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Offline Sample Fixture Mode (Debug)",
                                style = typography.uiLabel,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Use local Creative Commons sample titles only",
                                style = typography.metaText,
                                color = colors.textSecondary
                            )
                        }
                        Switch(
                            checked = useSampleFixturesOnly,
                            onCheckedChange = onToggleSampleFixtures,
                            modifier = Modifier.testTag("toggle_sample_fixtures_switch")
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveMovieBoxConfig(movieBoxUrlInput, movieBoxApiKeyInput)
                    onDismiss()
                },
                enabled = isUrlValid,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary),
                modifier = Modifier.testTag("sources_dialog_save_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = colors.textSecondary)
            }
        }
    )
}

@Composable
fun DownloadResolutionDialog(
    title: String,
    sources: List<PlaybackSource>,
    isEnqueuing: Boolean = false,
    onSelectSource: (PlaybackSource) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val authorisedSources = remember(sources) { sources.filter { it.isAuthorised } }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceElevated,
        modifier = Modifier.testTag("download_resolution_dialog"),
        title = {
            Text(
                text = "Download Offline Copy",
                style = typography.sheetTitle,
                color = colors.textPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Choose an authorised stream resolution for \"$title\". Downloads are saved to your Laterbox Downloads tab.",
                    style = typography.bodySecondary,
                    color = colors.textSecondary
                )
                if (authorisedSources.isEmpty()) {
                    Text(
                        text = "No downloadable authorised video streams are available for this title.",
                        style = typography.metaText,
                        color = colors.textSecondary
                    )
                } else {
                    authorisedSources.forEach { source ->
                        val isAdaptive = source.format.equals("DASH", ignoreCase = true) ||
                            source.format.equals("HLS", ignoreCase = true) ||
                            source.url.contains(".mpd", ignoreCase = true) ||
                            source.url.contains(".m3u8", ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surfaceSecondary,
                            border = BorderStroke(0.75.dp, colors.borderSubtle),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !isEnqueuing && !isAdaptive) {
                                    onSelectSource(source)
                                    onDismiss()
                                }
                                .testTag("download_quality_option_${source.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${source.qualityLabel} · ${source.format}",
                                        style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (isAdaptive) colors.textSecondary else colors.textPrimary
                                    )
                                    val metaParts = buildList {
                                        if (isAdaptive) add("Stream only (Adaptive manifest)")
                                        source.codec?.takeIf { it.isNotBlank() }?.let { add(it) }
                                        source.sizeMb?.takeIf { it > 0.0 }?.let { mb ->
                                            add(String.format(Locale.US, "%.0f MB", mb))
                                        }
                                        add(source.providerName)
                                    }
                                    Text(
                                        text = metaParts.joinToString(" · "),
                                        style = typography.caption,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (!isAdaptive) {
                                    Icon(
                                        painter = painterResource(LaterboxIcons.Download),
                                        contentDescription = "Download ${source.qualityLabel}",
                                        tint = colors.accentPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = colors.textSecondary)
            }
        }
    )
}

@Composable
fun PosterViewerDialog(
    imageUrl: String,
    title: String,
    onDismiss: () -> Unit
) {
    val colors = LaterboxTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable(onClick = onDismiss)
                .testTag("detail_poster_viewer_dialog"),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(16.dp))
            )
            Surface(
                shape = CircleShape,
                color = colors.surfaceElevated.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("detail_poster_viewer_close")
                ) {
                    Icon(
                        painter = painterResource(LaterboxIcons.Close),
                        contentDescription = "Close poster",
                        tint = colors.textPrimary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonFilmographyBottomSheet(
    member: CastMember,
    filmography: PersonFilmography?,
    isLoading: Boolean,
    errorMessage: String?,
    onOpenCredit: (providerId: String, titleId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceElevated,
        modifier = Modifier.testTag("person_filmography_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceSecondary)
                        .border(0.75.dp, colors.borderSubtle, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    val img = filmography?.imageUrl ?: member.imageUrl
                    if (!img.isNullOrBlank()) {
                        AsyncImage(
                            model = img,
                            contentDescription = member.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            painter = painterResource(LaterboxIcons.User),
                            contentDescription = null,
                            tint = colors.textTertiary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = filmography?.name ?: member.name,
                        style = typography.sheetTitle,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    member.role?.takeIf { it.isNotBlank() }?.let { role ->
                        Text(
                            text = "as $role",
                            style = typography.bodySecondary,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            when {
                isLoading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = colors.accentPrimary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Loading credits...",
                            style = typography.bodySecondary,
                            color = colors.textSecondary
                        )
                    }
                }
                !errorMessage.isNullOrBlank() -> {
                    Text(
                        text = errorMessage,
                        style = typography.bodySecondary,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                filmography == null || filmography.credits.isEmpty() -> {
                    Text(
                        text = "No additional catalogue credits found for ${member.name}.",
                        style = typography.bodySecondary,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                else -> {
                    Text(
                        text = "Known For (${filmography.credits.size})",
                        style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filmography.credits, key = { it.compositeId }) { credit ->
                            MoviePosterCard(
                                item = credit,
                                width = 112.dp,
                                onClick = {
                                    onDismiss()
                                    onOpenCredit(credit.providerId, credit.id)
                                },
                                testTag = "person_credit_${credit.id}"
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadOptionsBottomSheet(
    title: String,
    kind: MediaKind,
    sources: List<PlaybackSource>,
    subtitles: List<SubtitleTrack>,
    seasons: List<SeasonSummary>,
    selectedSeasonNumber: Int?,
    currentEpisodes: List<EpisodeItem>,
    isEnqueuing: Boolean = false,
    onSelectSource: (PlaybackSource) -> Unit,
    onDownloadBatch: (seasonNumber: Int, episodeNumbers: List<Int>, preferredQualityHeight: Int?) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val authorisedSources = remember(sources) { sources.filter { it.isAuthorised } }
    val progressiveSources = remember(authorisedSources) {
        authorisedSources.filter { src ->
            !src.format.equals("DASH", ignoreCase = true) &&
                !src.format.equals("HLS", ignoreCase = true) &&
                !src.url.contains(".mpd", ignoreCase = true) &&
                !src.url.contains(".m3u8", ignoreCase = true)
        }
    }

    var batchSeason by remember(selectedSeasonNumber, seasons) {
        mutableIntStateOf(selectedSeasonNumber ?: seasons.firstOrNull()?.seasonNumber ?: 1)
    }
    var batchPreferredQualityHeight by remember(progressiveSources) {
        mutableStateOf(progressiveSources.firstOrNull()?.qualityHeight)
    }
    val seasonEpisodeNumbers = remember(batchSeason, seasons, currentEpisodes, selectedSeasonNumber) {
        val seasonObj = seasons.firstOrNull { it.seasonNumber == batchSeason }
        when {
            seasonObj != null && seasonObj.availableEpisodes.isNotEmpty() -> seasonObj.availableEpisodes
            batchSeason == selectedSeasonNumber && currentEpisodes.isNotEmpty() ->
                currentEpisodes.map { it.episodeNumber }.distinct().sorted()
            seasonObj != null && seasonObj.episodeCount > 0 -> (1..seasonObj.episodeCount).toList()
            else -> emptyList()
        }
    }
    var selectedEpisodes by remember(batchSeason, seasonEpisodeNumbers) {
        mutableStateOf(emptySet<Int>())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceElevated,
        modifier = Modifier.testTag("download_resolution_dialog")
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 16.dp)
                .testTag("download_options_list"),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "dl_header") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Download Offline Copy",
                        style = typography.sheetTitle,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Choose an authorised stream resolution for \"$title\". Downloads save to Laterbox Downloads.",
                        style = typography.bodySecondary,
                        color = colors.textSecondary
                    )
                }
            }

            if (subtitles.isNotEmpty()) {
                item(key = "dl_subtitles") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Available Subtitles",
                            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textSecondary
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            subtitles.distinctBy { it.language }.take(6).forEach { track ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = colors.surfaceSecondary,
                                    border = BorderStroke(0.5.dp, colors.borderSubtle)
                                ) {
                                    Text(
                                        text = "${track.language} (${track.format.uppercase(Locale.US)})",
                                        style = typography.caption,
                                        color = colors.textSecondary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (authorisedSources.isEmpty()) {
                item(key = "dl_no_sources") {
                    Text(
                        text = "No downloadable authorised video streams are available for this title.",
                        style = typography.metaText,
                        color = colors.textSecondary
                    )
                }
            } else {
                items(authorisedSources, key = { "dl_src_${it.id}" }) { source ->
                    val isAdaptive = source.format.equals("DASH", ignoreCase = true) ||
                        source.format.equals("HLS", ignoreCase = true) ||
                        source.url.contains(".mpd", ignoreCase = true) ||
                        source.url.contains(".m3u8", ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surfaceSecondary,
                        border = BorderStroke(0.75.dp, colors.borderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !isEnqueuing && !isAdaptive) {
                                onSelectSource(source)
                                onDismiss()
                            }
                            .testTag("download_quality_option_${source.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${source.qualityLabel} · ${source.format}",
                                    style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isAdaptive) colors.textSecondary else colors.textPrimary
                                )
                                val metaParts = buildList {
                                    if (isAdaptive) add("Stream only (Adaptive manifest)")
                                    source.codec?.takeIf { it.isNotBlank() }?.let { add(it) }
                                    source.sizeMb?.takeIf { it > 0.0 }?.let { mb ->
                                        add(String.format(Locale.US, "%.0f MB", mb))
                                    }
                                    add(source.providerName)
                                }
                                Text(
                                    text = metaParts.joinToString(" · "),
                                    style = typography.caption,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!isAdaptive) {
                                Icon(
                                    painter = painterResource(LaterboxIcons.Download),
                                    contentDescription = "Download ${source.qualityLabel}",
                                    tint = colors.accentPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (kind == MediaKind.TV_SERIES && seasons.isNotEmpty() && progressiveSources.isNotEmpty()) {
                item(key = "dl_batch_header") {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HorizontalDivider(thickness = 0.75.dp, color = colors.borderSubtle)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Batch Download Episodes",
                                style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.textPrimary
                            )
                            if (seasonEpisodeNumbers.isNotEmpty()) {
                                val allSelected = selectedEpisodes.size == seasonEpisodeNumbers.size
                                TextButton(
                                    onClick = {
                                        selectedEpisodes = if (allSelected) {
                                            emptySet()
                                        } else {
                                            seasonEpisodeNumbers.toSet()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.testTag("batch_select_all_button")
                                ) {
                                    Text(
                                        text = if (allSelected) "Clear All" else "Select All (${seasonEpisodeNumbers.size})",
                                        style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.accentPrimary
                                    )
                                }
                            }
                        }

                        if (seasons.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(seasons, key = { "batch_s_${it.seasonNumber}" }) { season ->
                                    val selected = batchSeason == season.seasonNumber
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) colors.accentPrimary else colors.surfaceSecondary,
                                        border = BorderStroke(
                                            0.75.dp,
                                            if (selected) colors.accentPrimary else colors.borderSubtle
                                        ),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { batchSeason = season.seasonNumber }
                                            .testTag("batch_season_chip_${season.seasonNumber}")
                                    ) {
                                        Text(
                                            text = "Season ${season.seasonNumber}",
                                            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                            color = if (selected) colors.onAccentPrimary else colors.textPrimary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (progressiveSources.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(progressiveSources.distinctBy { it.qualityHeight }, key = { "bq_${it.qualityHeight}" }) { src ->
                                    val selected = batchPreferredQualityHeight == src.qualityHeight
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) colors.accentContainer else colors.surfaceSecondary,
                                        border = BorderStroke(
                                            0.75.dp,
                                            if (selected) colors.accentPrimary else colors.borderSubtle
                                        ),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { batchPreferredQualityHeight = src.qualityHeight }
                                            .testTag("batch_quality_chip_${src.qualityHeight}")
                                    ) {
                                        Text(
                                            text = src.qualityLabel,
                                            style = typography.caption.copy(fontWeight = FontWeight.Medium),
                                            color = if (selected) colors.accentPrimary else colors.textSecondary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                items(seasonEpisodeNumbers, key = { "batch_ep_${batchSeason}_$it" }) { epNum ->
                    val checked = epNum in selectedEpisodes
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.surfaceSecondary)
                            .clickable {
                                selectedEpisodes = if (checked) {
                                    selectedEpisodes - epNum
                                } else {
                                    selectedEpisodes + epNum
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("batch_episode_row_$epNum"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Season $batchSeason · Episode $epNum",
                            style = typography.uiLabel,
                            color = colors.textPrimary
                        )
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { nowChecked ->
                                selectedEpisodes = if (nowChecked) {
                                    selectedEpisodes + epNum
                                } else {
                                    selectedEpisodes - epNum
                                }
                            },
                            modifier = Modifier.testTag("batch_episode_checkbox_$epNum")
                        )
                    }
                }

                item(key = "dl_batch_confirm") {
                    Button(
                        onClick = {
                            onDownloadBatch(batchSeason, selectedEpisodes.toList(), batchPreferredQualityHeight)
                            onDismiss()
                        },
                        enabled = !isEnqueuing && selectedEpisodes.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accentPrimary,
                            contentColor = colors.onAccentPrimary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .testTag("batch_download_confirm_button")
                    ) {
                        Text(
                            text = "Download Selected (${selectedEpisodes.size})",
                            style = typography.buttonLabel
                        )
                    }
                }
            }
        }
    }
}

private fun formatResumeTime(positionMs: Long, durationMs: Long): String {
    val posMin = (positionMs / 60_000L).coerceAtLeast(0L)
    return if (durationMs > 0L) {
        val totalMin = (durationMs / 60_000L).coerceAtLeast(1L)
        "${posMin}m / ${totalMin}m"
    } else {
        "At ${posMin}m"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoviesSearchSuggestionsDropdown(
    query: String,
    suggestions: List<SearchSuggestion>,
    recentSearches: List<String>,
    isLoading: Boolean,
    errorMessage: String?,
    containerTag: String,
    suggestionTagPrefix: String,
    onSuggestionClick: (SearchSuggestion) -> Unit,
    onSubmitQuery: (String) -> Unit,
    onClearRecent: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val trimmedQuery = query.trim()
    val hasQuery = trimmedQuery.length >= 2

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surfaceElevated,
        border = BorderStroke(1.dp, colors.borderSubtle),
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .testTag(containerTag)
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp)
        ) {
            when {
                hasQuery && isLoading && suggestions.isEmpty() -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("${containerTag}_loading"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.accentPrimary
                        )
                        Text(
                            text = "Searching suggestions...",
                            style = typography.bodySecondary,
                            color = colors.textSecondary
                        )
                    }
                }
                hasQuery && !errorMessage.isNullOrBlank() && suggestions.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .testTag("${containerTag}_error"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = errorMessage,
                            style = typography.metaText,
                            color = colors.textSecondary
                        )
                        Text(
                            text = "Search all titles for \"$trimmedQuery\"",
                            style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.accentPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSubmitQuery(trimmedQuery) }
                                .padding(vertical = 4.dp)
                        )
                    }
                }
                suggestions.isNotEmpty() -> {
                    suggestions.forEachIndexed { idx, suggestion ->
                        val suggestionTagSuffix = if (suggestion.hasNavigableId) suggestion.id else "idx_$idx"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSuggestionClick(suggestion) }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .testTag("$suggestionTagPrefix$suggestionTagSuffix"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = suggestion.title,
                                    style = typography.uiLabel,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subMeta = if (!suggestion.hasNavigableId) {
                                    "Search catalogue"
                                } else {
                                    listOfNotNull(
                                        suggestion.kind.label,
                                        suggestion.releaseYear?.toString()
                                    ).joinToString(" · ")
                                }
                                Text(
                                    text = subMeta,
                                    style = typography.caption,
                                    color = colors.textSecondary
                                )
                            }
                            Icon(
                                painter = painterResource(LaterboxIcons.ArrowUpRight),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = colors.textTertiary
                            )
                        }
                        if (idx < suggestions.lastIndex) {
                            HorizontalDivider(thickness = 0.5.dp, color = colors.borderSubtle)
                        }
                    }
                }
                hasQuery -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSubmitQuery(trimmedQuery) }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("${containerTag}_empty"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "No instant matches for \"$trimmedQuery\"",
                                style = typography.bodySecondary,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Tap to run full search",
                                style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.accentPrimary
                            )
                        }
                        Icon(
                            painter = painterResource(LaterboxIcons.Search),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = colors.accentPrimary
                        )
                    }
                }
            }

            if (!hasQuery && recentSearches.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent searches",
                        style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textSecondary
                    )
                    TextButton(
                        onClick = onClearRecent,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text("Clear", style = typography.caption, color = colors.accentPrimary)
                    }
                }
                FlowRow(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    recentSearches.take(8).forEach { recent ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = colors.surfaceSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onSubmitQuery(recent) }
                        ) {
                            Text(
                                text = recent,
                                style = typography.caption,
                                color = colors.textPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
