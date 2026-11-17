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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.laterbox.app.movies.domain.model.EpisodeItem
import com.laterbox.app.movies.domain.model.MediaKind
import com.laterbox.app.movies.domain.model.PlaybackResolution
import com.laterbox.app.movies.domain.model.PlaybackSource
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.theme.LaterboxTheme
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
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
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    var showDownloadDialog by remember { mutableStateOf(false) }

    val availableRes = uiState.playbackResolution as? PlaybackResolution.Available
    if (showDownloadDialog && availableRes != null && uiState.details != null) {
        DownloadResolutionDialog(
            title = uiState.details.summary.title,
            sources = availableRes.sources,
            isEnqueuing = uiState.isEnqueuingDownload,
            onSelectSource = onDownloadSource,
            onDismiss = { showDownloadDialog = false }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
            .navigationBarsPadding()
            .testTag("movie_detail_screen")
    ) {
        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accentPrimary)
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

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("movie_detail_list"),
                    contentPadding = PaddingValues(bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Top Hero Backdrop & Poster Header
                    item(key = "detail_hero") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(290.dp)
                                .background(colors.surfaceSecondary)
                        ) {
                            val backdrop = summary.backdropUrl ?: summary.posterUrl
                            if (!backdrop.isNullOrBlank()) {
                                AsyncImage(
                                    model = backdrop,
                                    contentDescription = summary.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.Black.copy(alpha = 0.35f),
                                                colors.backgroundPrimary.copy(alpha = 0.55f),
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
                                    DataSourceBadgeChip(badge = summary.sourceBadge)
                                    Text(
                                        text = summary.title,
                                        style = typography.editorialTitle.copy(fontSize = 22.sp, lineHeight = 26.sp),
                                        color = colors.textPrimary,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val metaParts = buildList {
                                        add(summary.kind.label)
                                        summary.releaseYear?.let { add(it.toString()) }
                                        summary.durationMinutes?.let { add("${it}m") }
                                        summary.rating?.let { add(String.format(Locale.US, "★ %.1f", it)) }
                                    }
                                    Text(
                                        text = metaParts.joinToString(" · "),
                                        style = typography.metaText,
                                        color = colors.textSecondary
                                    )
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
                                                text = "Checking authorised stream availability...",
                                                style = typography.bodySecondary,
                                                color = colors.textSecondary
                                            )
                                        }
                                    }
                                }
                                resolution is PlaybackResolution.Available -> {
                                    val isResume = uiState.savedPositionMs >= 5_000L
                                    val hasAuthorisedDownloads = resolution.sources.any { it.isAuthorised }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = { onPlayClick(null, null) },
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
                                            val maxQual = resolution.sources.firstOrNull()?.qualityLabel ?: "Stream"
                                            val ctaLabel = if (isResume) "Resume (${maxQual})" else "Watch Now (${maxQual})"
                                            Text(text = ctaLabel, style = typography.buttonLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                                text = "Playback Unavailable from ${resolution.providerName}",
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
                                                    Text("Open Official Guide", color = colors.textPrimary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Synopsis & Metadata Chips
                    item(key = "detail_synopsis") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = details.fullDescription.ifBlank { "No description available." },
                                style = typography.bodyPrimary,
                                color = colors.textPrimary
                            )

                            val metaLines = buildList {
                                details.directorOrCreator?.let { add("Director / Creator: $it") }
                                details.networkOrStudio?.let { add("Studio / Network: $it") }
                                summary.language?.let { add("Language: $it") }
                                if (details.availableSubtitles.isNotEmpty()) {
                                    add("Subtitles: ${details.availableSubtitles.joinToString { it.language }}")
                                }
                            }
                            if (metaLines.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    metaLines.forEach { line ->
                                        Text(
                                            text = line,
                                            style = typography.metaText,
                                            color = colors.textSecondary
                                        )
                                    }
                                }
                            }

                            if (summary.genres.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    summary.genres.forEach { genre ->
                                        Surface(
                                            shape = RoundedCornerShape(20.dp),
                                            color = colors.surfacePrimary,
                                            border = BorderStroke(0.75.dp, colors.borderSubtle)
                                        ) {
                                            Text(
                                                text = genre,
                                                style = typography.caption,
                                                color = colors.textSecondary,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
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
                                    items(details.cast, key = { it.name + (it.role ?: "") }) { member ->
                                        Column(
                                            modifier = Modifier.width(86.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(66.dp)
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
                    }

                    // TV Series Seasons & Episodes
                    if (summary.kind == MediaKind.TV_SERIES && details.seasons.isNotEmpty()) {
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
                                    items(details.seasons, key = { it.seasonNumber }) { season ->
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

                        if (uiState.isLoadingEpisodes) {
                            item(key = "episodes_loading") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = colors.accentPrimary
                                    )
                                }
                            }
                        } else if (uiState.episodes.isNotEmpty()) {
                            items(uiState.episodes, key = { "ep_${it.id}_${it.seasonNumber}_${it.episodeNumber}" }) { ep ->
                                EpisodeRowCard(
                                    episode = ep,
                                    onClick = { onPlayClick(ep.seasonNumber, ep.episodeNumber) },
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }

                    // Related Titles Rail
                    if (details.relatedTitles.isNotEmpty()) {
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
                                    items(details.relatedTitles, key = { "rel_${it.compositeId}" }) { rel ->
                                        MoviePosterCard(
                                            item = rel,
                                            onClick = { onOpenRelated(rel.providerId, rel.id) }
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

@Composable
private fun EpisodeRowCard(
    episode: EpisodeItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("episode_row_${episode.seasonNumber}_${episode.episodeNumber}"),
        shape = RoundedCornerShape(14.dp),
        color = colors.surfacePrimary,
        border = BorderStroke(0.75.dp, colors.borderSubtle)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(98.dp)
                    .height(62.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceSecondary),
                contentAlignment = Alignment.Center
            ) {
                if (!episode.stillUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = episode.stillUrl,
                        contentDescription = episode.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = "E${episode.episodeNumber}",
                        style = typography.titleLarge,
                        color = colors.textTertiary
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
                }.joinToString(" · ")
                if (epMeta.isNotBlank()) {
                    Text(
                        text = epMeta,
                        style = typography.caption,
                        color = colors.textSecondary
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
        }
    }
}
