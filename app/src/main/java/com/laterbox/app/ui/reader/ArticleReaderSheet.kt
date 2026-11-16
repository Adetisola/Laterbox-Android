package com.laterbox.app.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.laterbox.app.data.reader.ArticleBlock
import com.laterbox.app.data.reader.ExtractedArticle
import com.laterbox.app.domain.model.SavedLink
import com.laterbox.app.ui.components.LaterboxIconButton
import com.laterbox.app.ui.components.LaterboxIcons
import com.laterbox.app.ui.components.LaterboxPrimaryButton
import com.laterbox.app.ui.components.LaterboxSecondaryButton
import com.laterbox.app.ui.components.LaterboxShapeTokens
import com.laterbox.app.ui.theme.LaterboxTheme

sealed class ArticleReaderUiState {
    data object Loading : ArticleReaderUiState()
    data class Loaded(val article: ExtractedArticle) : ArticleReaderUiState()
    data class Error(val message: String) : ArticleReaderUiState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleReaderSheet(
    link: SavedLink,
    uiState: ArticleReaderUiState,
    fontScale: Float,
    sheetState: SheetState,
    onRefresh: () -> Unit,
    onOpenOriginal: () -> Unit,
    onMarkReadAndArchive: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val colors = LaterboxTheme.colors
    val spacing = LaterboxTheme.spacing

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = LaterboxTheme.shapes.Sheet,
        containerColor = colors.backgroundPrimary,
        contentColor = colors.textPrimary,
        tonalElevation = LaterboxTheme.elevations.sheet,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = spacing.sm)
                    .size(width = spacing.dragHandleWidth, height = spacing.dragHandleHeight)
                    .clip(RoundedCornerShape(spacing.radiusFull))
                    .background(colors.borderSubtle)
            )
        }
    ) {
        ArticleReaderContent(
            link = link,
            uiState = uiState,
            fontScale = fontScale,
            onRefresh = onRefresh,
            onOpenOriginal = onOpenOriginal,
            onMarkReadAndArchive = onMarkReadAndArchive,
            onDismissRequest = onDismissRequest,
            showTopBar = true,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .navigationBarsPadding()
                .testTag("article_reader_sheet")
        )
    }
}

@Composable
fun ArticleReaderContent(
    link: SavedLink,
    uiState: ArticleReaderUiState,
    fontScale: Float,
    onRefresh: () -> Unit,
    onOpenOriginal: () -> Unit,
    onMarkReadAndArchive: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    showTopBar: Boolean = true,
    onDismissRequest: () -> Unit = {}
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    val spacing = LaterboxTheme.spacing
    val context = LocalContext.current
    val effectiveScale = fontScale.coerceIn(0.8f, 1.3f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("article_reader_content")
    ) {
        if (showTopBar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.base, vertical = spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = link.domain,
                        style = typography.metaText.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (uiState is ArticleReaderUiState.Loaded) {
                        val cacheLabel = if (uiState.article.fromCache) " · Cached offline" else ""
                        Text(
                            text = "${uiState.article.estimatedReadMinutes} min read$cacheLabel",
                            style = typography.metaText.copy(fontSize = 11.sp),
                            color = colors.accentPrimary
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = LaterboxShapeTokens.Button,
                        color = colors.surfaceSecondary,
                        border = BorderStroke(spacing.hairline, colors.borderSubtle),
                        modifier = Modifier
                            .clip(LaterboxShapeTokens.Button)
                            .clickable(role = Role.Button, onClick = onOpenOriginal)
                    ) {
                        Text(
                            text = "Web ↗",
                            style = typography.uiMedium.copy(fontSize = 12.sp),
                            color = colors.accentPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }

                    LaterboxIconButton(
                        iconRes = LaterboxIcons.Refresh,
                        contentDescription = "Refresh reader view",
                        onClick = onRefresh,
                        modifier = Modifier.testTag("reader_refresh_button")
                    )

                    LaterboxIconButton(
                        iconRes = LaterboxIcons.Close,
                        contentDescription = "Close reader",
                        onClick = onDismissRequest
                    )
                }
            }

            HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.6f))
        }

        when (uiState) {
            is ArticleReaderUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(spacing.xl)
                        .testTag("reader_loading_state"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(spacing.md)
                    ) {
                        CircularProgressIndicator(color = colors.accentPrimary)
                        Text(
                            text = "Preparing clean reader view…",
                            style = typography.bodySecondary,
                            color = colors.textSecondary
                        )
                        LaterboxSecondaryButton(
                            text = "Open Original Instead ↗",
                            onClick = onOpenOriginal
                        )
                    }
                }
            }

            is ArticleReaderUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(spacing.xl)
                        .testTag("reader_error_state"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(spacing.md)
                    ) {
                        Text(
                            text = "Reader mode unavailable",
                            style = typography.editorialTitle,
                            color = colors.textPrimary
                        )
                        Text(
                            text = uiState.message,
                            style = typography.bodySecondary,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(spacing.xs))
                        LaterboxPrimaryButton(
                            text = "Open Original in Browser ↗",
                            onClick = onOpenOriginal,
                            modifier = Modifier.fillMaxWidth()
                        )
                        LaterboxSecondaryButton(
                            text = "Retry Reader Extraction",
                            onClick = onRefresh,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            is ArticleReaderUiState.Loaded -> {
                val article = uiState.article
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = spacing.lg, vertical = spacing.base)
                        .testTag("reader_loaded_scroll")
                ) {
                    if (!showTopBar) {
                        val cacheLabel = if (article.fromCache) " · Cached offline" else ""
                        Text(
                            text = "${article.estimatedReadMinutes} min read$cacheLabel",
                            style = typography.metaText.copy(
                                fontSize = (12f * effectiveScale).sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = colors.accentPrimary
                        )
                        Spacer(modifier = Modifier.height(spacing.xs))
                    }

                    Text(
                        text = article.title,
                        style = typography.editorialTitle.copy(
                            fontSize = (24f * effectiveScale).sp,
                            lineHeight = (31f * effectiveScale).sp
                        ),
                        color = colors.textPrimary
                    )

                    val bylineParts = listOfNotNull(
                        article.byline,
                        article.siteName?.takeIf { !it.equals(article.byline, ignoreCase = true) },
                        "${article.wordCount} words"
                    )
                    if (bylineParts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(spacing.xs))
                        Text(
                            text = bylineParts.joinToString(" · "),
                            style = typography.metaText.copy(fontSize = (12f * effectiveScale).sp),
                            color = colors.textSecondary
                        )
                    }

                    if (!link.personalNote.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(spacing.md))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(LaterboxShapeTokens.Card)
                                .background(colors.accentContainer.copy(alpha = 0.45f))
                                .border(spacing.hairline, colors.accentPrimary.copy(alpha = 0.25f), LaterboxShapeTokens.Card)
                                .padding(spacing.md)
                        ) {
                            Text(
                                text = "Your note: ${link.personalNote}",
                                style = typography.bodySecondary.copy(
                                    fontSize = (14f * effectiveScale).sp,
                                    fontStyle = FontStyle.Italic
                                ),
                                color = colors.textPrimary
                            )
                        }
                    }

                    val heroUrl = article.leadImageUrl ?: link.previewImageUrl
                    if (!heroUrl.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(spacing.md))
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(heroUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(LaterboxShapeTokens.Card)
                                .background(colors.surfaceSecondary)
                        )
                    }

                    Spacer(modifier = Modifier.height(spacing.lg))

                    article.blocks.forEach { block ->
                        when (block) {
                            is ArticleBlock.Heading -> {
                                Spacer(modifier = Modifier.height(spacing.md))
                                val size = if (block.level <= 2) 19f else 17f
                                Text(
                                    text = block.text,
                                    style = typography.cardTitle.copy(
                                        fontSize = (size * effectiveScale).sp,
                                        lineHeight = ((size + 6f) * effectiveScale).sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = colors.textPrimary
                                )
                                Spacer(modifier = Modifier.height(spacing.xs))
                            }

                            is ArticleBlock.Paragraph -> {
                                Text(
                                    text = block.text,
                                    style = typography.bodyPrimary.copy(
                                        fontSize = (16f * effectiveScale).sp,
                                        lineHeight = (26f * effectiveScale).sp
                                    ),
                                    color = colors.textPrimary
                                )
                                Spacer(modifier = Modifier.height(spacing.md))
                            }

                            is ArticleBlock.Quote -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = spacing.xs)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.surfaceSecondary.copy(alpha = 0.7f))
                                        .padding(spacing.md),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .height(22.dp)
                                            .background(colors.accentPrimary)
                                    )
                                    Spacer(modifier = Modifier.width(spacing.sm))
                                    Text(
                                        text = block.text,
                                        style = typography.bodyPrimary.copy(
                                            fontSize = (15.5f * effectiveScale).sp,
                                            lineHeight = (24f * effectiveScale).sp,
                                            fontStyle = FontStyle.Italic
                                        ),
                                        color = colors.textSecondary
                                    )
                                }
                                Spacer(modifier = Modifier.height(spacing.sm))
                            }

                            is ArticleBlock.BulletItem -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = spacing.sm, bottom = spacing.sm),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = (9f * effectiveScale).dp)
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(colors.accentPrimary)
                                    )
                                    Spacer(modifier = Modifier.width(spacing.sm))
                                    Text(
                                        text = block.text,
                                        style = typography.bodyPrimary.copy(
                                            fontSize = (15.5f * effectiveScale).sp,
                                            lineHeight = (24f * effectiveScale).sp
                                        ),
                                        color = colors.textPrimary
                                    )
                                }
                            }

                            is ArticleBlock.CodeBlock -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = spacing.xs)
                                        .clip(LaterboxShapeTokens.Card)
                                        .background(colors.surfaceSecondary)
                                        .border(spacing.hairline, colors.borderSubtle, LaterboxShapeTokens.Card)
                                        .horizontalScroll(rememberScrollState())
                                        .padding(spacing.md)
                                ) {
                                    Text(
                                        text = block.text,
                                        style = typography.codeText.copy(
                                            fontSize = (13f * effectiveScale).sp,
                                            lineHeight = (19f * effectiveScale).sp
                                        ),
                                        color = colors.textPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.height(spacing.sm))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(spacing.xl))
                }

                HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.6f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceElevated)
                        .padding(horizontal = spacing.base, vertical = spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    LaterboxSecondaryButton(
                        text = "Open Web ↗",
                        onClick = onOpenOriginal,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("reader_open_web_button")
                    )
                    LaterboxPrimaryButton(
                        text = if (link.isArchived) "Done" else "Read & Archive",
                        onClick = onMarkReadAndArchive,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("reader_read_archive_button")
                    )
                }
            }
        }
    }
}
