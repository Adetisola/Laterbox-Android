package com.laterbox.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.laterbox.app.ui.theme.LaterboxTheme
import kotlin.math.PI
import kotlin.math.cos

/**
 * Shared single-clock controller for skeleton shimmer across the entire composition tree.
 * Only activates an [rememberInfiniteTransition] when at least one skeleton consumer is mounted,
 * and reads the animated [State] exclusively inside the draw phase so animation frames never
 * trigger recomposition or restart across sibling elements.
 */
@Stable
class SkeletonShimmerController {
    private var consumerCount by mutableIntStateOf(0)
    internal var animatedState: State<Float>? by mutableStateOf(null)
        private set

    fun registerConsumer() {
        consumerCount++
    }

    fun unregisterConsumer() {
        consumerCount = (consumerCount - 1).coerceAtLeast(0)
    }

    @Composable
    fun Attach() {
        if (consumerCount > 0) {
            val transition = rememberInfiniteTransition(label = "laterbox_skeleton_shimmer")
            val progressState = transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "laterbox_shimmer_phase"
            )
            SideEffect { animatedState = progressState }
        } else {
            SideEffect { animatedState = null }
        }
    }
}

val LocalSkeletonShimmerController = staticCompositionLocalOf<SkeletonShimmerController?> { null }

/**
 * Draw-layer synchronized skeleton shimmer modifier using low-contrast surface tokens
 * (`surfaceSecondary` <-> `surfaceElevated`). Never triggers recompositions on animation ticks.
 */
fun Modifier.skeletonShimmer(
    shape: Shape? = null,
    baseColor: Color? = null,
    highlightColor: Color? = null
): Modifier = composed {
    val colors = LaterboxTheme.colors
    val resolvedBase = baseColor ?: colors.surfaceSecondary
    val resolvedHighlight = highlightColor ?: colors.surfaceElevated
    val controller = LocalSkeletonShimmerController.current
    val fallbackState: State<Float>? = if (controller == null) {
        rememberInfiniteTransition(label = "skeleton_fallback").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "skeleton_fallback_phase"
        )
    } else {
        DisposableEffect(controller) {
            controller.registerConsumer()
            onDispose { controller.unregisterConsumer() }
        }
        null
    }

    val shapedModifier = if (shape != null) this.clip(shape) else this
    shapedModifier.drawBehind {
        val phase = controller?.animatedState?.value ?: fallbackState?.value ?: 0f
        // Smooth cosine pulse from 0..1..0 combined with a gentle horizontal sweep
        val pulse = 0.5f - 0.5f * cos(phase * 2f * PI.toFloat())
        val midColor = lerp(resolvedBase, resolvedHighlight, 0.25f + 0.45f * pulse)
        val peakColor = lerp(resolvedBase, resolvedHighlight, 0.85f)
        val width = size.width.coerceAtLeast(1f)
        val height = size.height.coerceAtLeast(1f)
        val sweepOffset = (phase * 2.2f - 0.6f) * width
        val brush = Brush.linearGradient(
            colors = listOf(resolvedBase, midColor, peakColor, midColor, resolvedBase),
            start = Offset(x = sweepOffset - width * 0.45f, y = 0f),
            end = Offset(x = sweepOffset + width * 0.45f, y = height * 0.5f)
        )
        drawRect(brush = brush)
    }
}

@Composable
fun rememberSkeletonAlpha(): Float {
    val controller = LocalSkeletonShimmerController.current
    if (controller != null) {
        DisposableEffect(controller) {
            controller.registerConsumer()
            onDispose { controller.unregisterConsumer() }
        }
        val phase = controller.animatedState?.value ?: 0f
        val pulse = 0.5f - 0.5f * cos(phase * 2f * PI.toFloat())
        return 0.45f + 0.35f * pulse
    }
    val transition = rememberInfiniteTransition(label = "skeleton_shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.80f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton_alpha"
    )
    return alpha
}

@Composable
fun SavedLinkCardSkeleton(
    modifier: Modifier = Modifier,
    isHero: Boolean = true
) {
    val colors = LaterboxTheme.colors
    val spacing = LaterboxTheme.spacing

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(spacing.cardRadius),
        colors = CardDefaults.cardColors(containerColor = colors.surfacePrimary),
        border = BorderStroke(spacing.hairline, colors.borderSubtle),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = spacing.m,
                        end = spacing.m,
                        top = spacing.sm,
                        bottom = if (isHero) spacing.xs else spacing.sm
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .skeletonShimmer(shape = CircleShape)
                    )
                    Spacer(modifier = Modifier.width(spacing.s))
                    Box(
                        modifier = Modifier
                            .width(90.dp)
                            .height(12.dp)
                            .skeletonShimmer(shape = RoundedCornerShape(spacing.radiusSmall))
                    )
                    Spacer(modifier = Modifier.width(spacing.s))
                    Box(
                        modifier = Modifier
                            .width(55.dp)
                            .height(14.dp)
                            .skeletonShimmer(shape = RoundedCornerShape(spacing.radiusSmall))
                    )
                }

                Spacer(modifier = Modifier.height(spacing.sm))

                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(18.dp)
                        .skeletonShimmer(shape = RoundedCornerShape(spacing.radiusSmall))
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.65f)
                        .height(18.dp)
                        .skeletonShimmer(shape = RoundedCornerShape(spacing.radiusSmall))
                )

                Spacer(modifier = Modifier.height(spacing.xs))

                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(13.dp)
                        .skeletonShimmer(shape = RoundedCornerShape(spacing.radiusSmall))
                )
            }

            if (isHero) {
                Spacer(modifier = Modifier.height(spacing.xs))
                val imageShape = RoundedCornerShape(
                    bottomStart = spacing.cardRadius,
                    bottomEnd = spacing.cardRadius
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .skeletonShimmer(shape = imageShape)
                        .border(
                            width = spacing.hairline,
                            color = colors.borderSubtle,
                            shape = imageShape
                        )
                )
            }
        }
    }
}

@Composable
fun InboxSkeletonList(
    modifier: Modifier = Modifier,
    isHero: Boolean = true
) {
    val spacing = LaterboxTheme.spacing
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.base,
            end = spacing.base,
            top = spacing.sm,
            bottom = 80.dp
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        userScrollEnabled = false
    ) {
        item { SavedLinkCardSkeleton(isHero = isHero) }
        item { SavedLinkCardSkeleton(isHero = isHero) }
        item { SavedLinkCardSkeleton(isHero = isHero) }
    }
}

@Composable
fun CollectionCardSkeleton(
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val spacing = LaterboxTheme.spacing

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(spacing.radiusMedium))
            .background(colors.surfacePrimary)
            .border(spacing.hairline, colors.borderSubtle, RoundedCornerShape(spacing.radiusMedium))
            .padding(spacing.md)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .skeletonShimmer(shape = RoundedCornerShape(spacing.radiusSmall))
            )

            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(16.dp)
                        .skeletonShimmer(shape = RoundedCornerShape(spacing.radiusSmall))
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Box(
                    modifier = Modifier
                        .width(45.dp)
                        .height(12.dp)
                        .skeletonShimmer(shape = RoundedCornerShape(spacing.radiusSmall))
                )
            }
        }
    }
}

@Composable
fun CollectionsSkeletonGrid(
    modifier: Modifier = Modifier
) {
    val spacing = LaterboxTheme.spacing
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        contentPadding = PaddingValues(
            start = spacing.base,
            end = spacing.base,
            top = spacing.sm,
            bottom = 80.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        modifier = modifier.fillMaxWidth(),
        userScrollEnabled = false
    ) {
        items(4) {
            CollectionCardSkeleton()
        }
    }
}

@Composable
fun CollectionDetailSkeletonList(
    modifier: Modifier = Modifier
) {
    val spacing = LaterboxTheme.spacing
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = spacing.base,
            end = spacing.base,
            top = spacing.sm,
            bottom = 112.dp
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        userScrollEnabled = false
    ) {
        items(4) { index ->
            SavedLinkCardSkeleton(isHero = index == 0)
        }
    }
}

@Composable
fun NotificationHistorySkeletonList(
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val spacing = LaterboxTheme.spacing
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.base,
            end = spacing.base,
            top = spacing.sm,
            bottom = 80.dp
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        userScrollEnabled = false
    ) {
        item {
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .height(12.dp)
                    .skeletonShimmer(shape = RoundedCornerShape(6.dp))
            )
        }
        items(5) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(spacing.cardRadius))
                    .background(colors.surfacePrimary)
                    .border(spacing.hairline, colors.borderSubtle, RoundedCornerShape(spacing.cardRadius))
                    .padding(spacing.md),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .skeletonShimmer(shape = CircleShape)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.65f)
                            .height(14.dp)
                            .skeletonShimmer(shape = RoundedCornerShape(5.dp))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .height(12.dp)
                            .skeletonShimmer(shape = RoundedCornerShape(5.dp))
                    )
                    Box(
                        modifier = Modifier
                            .width(84.dp)
                            .height(10.dp)
                            .skeletonShimmer(shape = RoundedCornerShape(5.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun PosterCardSkeleton(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .skeletonShimmer(shape = RoundedCornerShape(14.dp))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(12.dp)
                .skeletonShimmer(shape = RoundedCornerShape(4.dp))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.45f)
                .height(10.dp)
                .skeletonShimmer(shape = RoundedCornerShape(4.dp))
        )
    }
}

@Composable
fun MediaCatalogueSkeletonGrid(
    modifier: Modifier = Modifier,
    columns: Int = 3,
    rows: Int = 4
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        repeat(rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(columns) {
                    PosterCardSkeleton(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun ViewingHistorySkeletonList(
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = false
    ) {
        items(6) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfacePrimary)
                    .border(0.75.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .aspectRatio(2f / 3f)
                        .skeletonShimmer(shape = RoundedCornerShape(10.dp))
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .height(16.dp)
                            .skeletonShimmer(shape = RoundedCornerShape(6.dp))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(12.dp)
                            .skeletonShimmer(shape = RoundedCornerShape(6.dp))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(4.dp)
                            .skeletonShimmer(shape = RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun EpisodeListSkeleton(
    modifier: Modifier = Modifier,
    count: Int = 4
) {
    val colors = LaterboxTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("detail_episodes_skeleton"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(count) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfacePrimary)
                    .border(0.75.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 40.dp)
                        .skeletonShimmer(shape = RoundedCornerShape(10.dp))
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.68f)
                            .height(14.dp)
                            .skeletonShimmer(shape = RoundedCornerShape(5.dp))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.45f)
                            .height(11.dp)
                            .skeletonShimmer(shape = RoundedCornerShape(5.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun MovieDetailCastRailSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.testTag("detail_cast_skeleton"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .width(72.dp)
                .height(18.dp)
                .skeletonShimmer(shape = RoundedCornerShape(6.dp))
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = false
        ) {
            items(6) {
                Column(
                    modifier = Modifier.width(86.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(66.dp)
                            .skeletonShimmer(shape = CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .width(68.dp)
                            .height(11.dp)
                            .skeletonShimmer(shape = RoundedCornerShape(4.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun MovieDetailPosterRailSkeleton(
    titleWidthDp: Int = 128,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .width(titleWidthDp.dp)
                .height(18.dp)
                .skeletonShimmer(shape = RoundedCornerShape(6.dp))
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = false
        ) {
            items(4) {
                PosterCardSkeleton(modifier = Modifier.width(126.dp))
            }
        }
    }
}

@Composable
fun MovieDetailScreenSkeleton(
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("detail_loading_skeleton"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .heightIn(min = 252.dp, max = 320.dp)
                .skeletonShimmer()
        ) {
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
                        .skeletonShimmer(shape = RoundedCornerShape(12.dp))
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(12.dp))
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(96.dp)
                            .height(16.dp)
                            .skeletonShimmer(shape = RoundedCornerShape(6.dp))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(24.dp)
                            .skeletonShimmer(shape = RoundedCornerShape(6.dp))
                    )
                }
            }
        }

        // Primary action bar placeholder
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .skeletonShimmer(shape = RoundedCornerShape(12.dp))
            )
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .skeletonShimmer(shape = RoundedCornerShape(12.dp))
            )
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .skeletonShimmer(shape = RoundedCornerShape(12.dp))
            )
        }

        // 2-row metadata pills placeholders
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.testTag("detail_metadata_skeleton_row1"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.width(68.dp).height(26.dp).skeletonShimmer(shape = RoundedCornerShape(20.dp)))
                Box(modifier = Modifier.width(74.dp).height(26.dp).skeletonShimmer(shape = RoundedCornerShape(20.dp)))
                Box(modifier = Modifier.width(86.dp).height(26.dp).skeletonShimmer(shape = RoundedCornerShape(20.dp)))
            }
            Row(
                modifier = Modifier.testTag("detail_metadata_skeleton_row2"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.width(104.dp).height(26.dp).skeletonShimmer(shape = RoundedCornerShape(20.dp)))
                Box(modifier = Modifier.width(92.dp).height(26.dp).skeletonShimmer(shape = RoundedCornerShape(20.dp)))
            }
        }

        // Synopsis lines placeholder
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(13.dp).skeletonShimmer(shape = RoundedCornerShape(5.dp)))
            Box(modifier = Modifier.fillMaxWidth(0.92f).height(13.dp).skeletonShimmer(shape = RoundedCornerShape(5.dp)))
            Box(modifier = Modifier.fillMaxWidth(0.68f).height(13.dp).skeletonShimmer(shape = RoundedCornerShape(5.dp)))
        }

        MovieDetailCastRailSkeleton()
    }
}

