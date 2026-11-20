package com.laterbox.app.ui.collections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.laterbox.app.data.preferences.InboxViewMode
import com.laterbox.app.domain.model.SavedLink
import com.laterbox.app.movies.domain.model.CollectionMediaItem
import com.laterbox.app.ui.components.EmptyStateView
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.components.SavedLinkCard
import com.laterbox.app.ui.theme.LaterboxTheme

@Composable
fun CollectionDetailScreen(
    uiState: CollectionDetailUiState,
    onBackClick: () -> Unit,
    onLinkClick: (SavedLink) -> Unit,
    onQuickOpenClick: (SavedLink) -> Unit,
    viewMode: InboxViewMode = InboxViewMode.HERO,
    mediaReadyLinkIds: Set<Long> = emptySet(),
    onToggleRead: ((SavedLink) -> Unit)? = null,
    onArchive: ((SavedLink) -> Unit)? = null,
    onOpenMovieDetail: (providerId: String, titleId: String) -> Unit = { _, _ -> },
    onRemoveMediaItem: (CollectionMediaItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with back navigation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.base, vertical = spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button with tactile backdrop
                com.laterbox.app.ui.components.LaterboxIconButton(
                    iconRes = com.laterbox.app.ui.components.LaterboxIcons.ArrowLeft,
                    contentDescription = "Back to collections",
                    onClick = onBackClick
                )

                Spacer(modifier = Modifier.width(spacing.sm))

                Column {
                    Text(
                        text = uiState.collectionName,
                        style = typography.displayLarge.copy(fontSize = 22.sp),
                        color = colors.textPrimary
                    )
                    val subtitleText = buildList {
                        if (uiState.links.isNotEmpty() || uiState.mediaItems.isEmpty()) {
                            add("${uiState.links.size} saved link${if (uiState.links.size != 1) "s" else ""}")
                        }
                        if (uiState.mediaItems.isNotEmpty()) {
                            add("${uiState.mediaItems.size} title${if (uiState.mediaItems.size != 1) "s" else ""}")
                        }
                    }.joinToString(" · ")
                    Text(
                        text = subtitleText,
                        style = typography.metaText,
                        color = colors.textSecondary
                    )
                }
            }

            when {
                uiState.isLoading && uiState.links.isEmpty() && uiState.mediaItems.isEmpty() -> {
                    com.laterbox.app.ui.components.CollectionDetailSkeletonList(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("collection_detail_skeleton")
                    )
                }

                uiState.links.isEmpty() && uiState.mediaItems.isEmpty() -> {
                    EmptyStateView(
                        title = "This collection is empty",
                        subtitle = "You can add links from your Inbox or movies and series from Movies & TV.",
                        actionText = "← Back to Collections",
                        onActionClick = onBackClick,
                        modifier = Modifier.weight(1f)
                    )
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = spacing.base,
                            end = spacing.base,
                            top = spacing.sm,
                            bottom = 112.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        if (uiState.mediaItems.isNotEmpty()) {
                            item(key = "section_media_header") {
                                Text(
                                    text = "Movies & TV",
                                    style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                    color = colors.textPrimary,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                                )
                            }
                            items(
                                items = uiState.mediaItems,
                                key = { "media_${it.mediaKey}" }
                            ) { media ->
                                val summary = media.summary
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = colors.surfacePrimary,
                                    border = BorderStroke(0.75.dp, colors.borderSubtle),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { onOpenMovieDetail(summary.providerId, summary.id) }
                                        .testTag("collection_media_item_${summary.id}")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(54.dp)
                                                .height(78.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(colors.surfaceSecondary),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (!summary.posterUrl.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = summary.posterUrl,
                                                    contentDescription = summary.title,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Icon(
                                                    painter = painterResource(LaterboxIcons.Movies),
                                                    contentDescription = null,
                                                    tint = colors.textTertiary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Text(
                                                text = summary.title,
                                                style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                                color = colors.textPrimary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            val meta = buildList {
                                                add(summary.kind.label)
                                                summary.releaseYear?.let { add(it.toString()) }
                                                summary.genres.firstOrNull()?.let { add(it) }
                                            }.joinToString(" · ")
                                            Text(
                                                text = meta,
                                                style = typography.metaText,
                                                color = colors.textSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(
                                            onClick = { onRemoveMediaItem(media) },
                                            modifier = Modifier.testTag("collection_media_remove_${summary.id}")
                                        ) {
                                            Icon(
                                                painter = painterResource(LaterboxIcons.Close),
                                                contentDescription = "Remove from collection",
                                                tint = colors.textTertiary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (uiState.links.isNotEmpty() && uiState.mediaItems.isNotEmpty()) {
                            item(key = "section_links_header") {
                                Text(
                                    text = "Saved Links",
                                    style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                                    color = colors.textPrimary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                                )
                            }
                        }

                        items(
                            items = uiState.links,
                            key = { it.id }
                        ) { link ->
                            SavedLinkCard(
                                link = link,
                                onClick = { onLinkClick(link) },
                                onQuickOpenClick = { onQuickOpenClick(link) },
                                viewMode = viewMode,
                                isMediaReady = link.id in mediaReadyLinkIds,
                                onToggleRead = onToggleRead?.let { cb -> { cb(link) } },
                                onArchive = onArchive?.let { cb -> { cb(link) } }
                            )
                        }
                    }
                }
            }
        }
    }
}
