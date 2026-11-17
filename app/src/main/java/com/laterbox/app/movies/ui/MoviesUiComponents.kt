package com.laterbox.app.movies.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.laterbox.app.movies.domain.model.DataSourceBadge
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.MovieTitleSummary
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
    width: Dp = 132.dp
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    Column(
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("movie_card_${item.providerId}_${item.id}")
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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DataSourceBadgeChip(badge = item.sourceBadge)
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
    currentTmdbKey: String,
    onToggleSampleFixtures: (Boolean) -> Unit,
    onSaveTmdbKey: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    var tmdbInput by remember(currentTmdbKey) { mutableStateOf(currentTmdbKey) }

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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Laterbox Movies connects exclusively to open, authorised providers:\n" +
                        "• Internet Archive Cinema (open-license streams & captions)\n" +
                        "• TVMaze Series Guide (metadata, cast & episodes)\n" +
                        "• Optional TMDB API (user-supplied key)",
                    style = typography.bodySecondary,
                    color = colors.textSecondary
                )

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
                            text = "Offline Sample Fixture Mode",
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

                OutlinedTextField(
                    value = tmdbInput,
                    onValueChange = { tmdbInput = it },
                    label = { Text("Optional TMDB v3 API Key") },
                    placeholder = { Text("Leave blank to disable TMDB") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveTmdbKey(tmdbInput)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentPrimary)
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

private fun formatResumeTime(positionMs: Long, durationMs: Long): String {
    val posMin = (positionMs / 60_000L).coerceAtLeast(0L)
    return if (durationMs > 0L) {
        val totalMin = (durationMs / 60_000L).coerceAtLeast(1L)
        "${posMin}m / ${totalMin}m"
    } else {
        "At ${posMin}m"
    }
}
