package com.laterbox.app.movies.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laterbox.app.ui.theme.LaterboxTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val HudPillBackground = Color.Black.copy(alpha = 0.64f)
private val HudBoostAmber = Color(0xFFFFB300)

@Composable
fun PlayerBrightnessHud(
    visible: Boolean,
    brightnessFraction: Float,
    modifier: Modifier = Modifier,
    hudTag: String = "player_brightness_hud"
) {
    val typography = LaterboxTheme.typography
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HudPillBackground,
            modifier = Modifier.testTag(hudTag)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "BRIGHT",
                    style = typography.caption.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                    color = Color.White.copy(alpha = 0.85f)
                )
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(104.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(brightnessFraction.coerceIn(0.01f, 1.0f))
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFFD54F))
                    )
                }
                Text(
                    text = "${(brightnessFraction * 100f).roundToInt()}%",
                    style = typography.caption.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun PlayerVolumeBoostHud(
    visible: Boolean,
    volumePercent: Int,
    isBoostSupported: Boolean,
    fallbackNotice: String?,
    modifier: Modifier = Modifier,
    hudTag: String = "player_volume_hud",
    boostBadgeTag: String = "player_volume_boost_badge"
) {
    val typography = LaterboxTheme.typography
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val isBoosted = PlayerAudioBoostController.isBoostActive(volumePercent)
        val pulseScale = if (isBoosted) {
            val pulseTransition = rememberInfiniteTransition(label = "boostPulse")
            val animatedScale by pulseTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.06f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 650),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "boostScale"
            )
            animatedScale
        } else {
            1f
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HudPillBackground,
            border = if (isBoosted) BorderStroke(1.25.dp, HudBoostAmber.copy(alpha = 0.8f)) else null,
            modifier = Modifier.testTag(hudTag)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isBoosted) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = HudBoostAmber.copy(alpha = 0.24f),
                        modifier = Modifier
                            .scale(pulseScale)
                            .testTag(boostBadgeTag)
                    ) {
                        Text(
                            text = "BOOST · ${volumePercent}%",
                            style = typography.caption.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                            color = HudBoostAmber,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Text(
                        text = if (volumePercent == 0) "MUTE" else "VOL",
                        style = typography.caption.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }

                // Segmented 2-zone bar: top = 101-200% boost range, bottom = 0-100% normal range
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val boostFill = ((volumePercent - 100).coerceIn(0, 100) / 100f)
                    val normalFill = (volumePercent.coerceIn(0, 100) / 100f)

                    Box(
                        modifier = Modifier
                            .width(10.dp)
                            .height(50.dp)
                            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                            .background(
                                if (isBoostSupported) {
                                    HudBoostAmber.copy(alpha = 0.20f)
                                } else {
                                    Color.White.copy(alpha = 0.10f)
                                }
                            ),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        if (boostFill > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(boostFill)
                                    .background(HudBoostAmber)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(16.dp)
                            .height(2.dp)
                            .background(if (isBoosted) HudBoostAmber else Color.White.copy(alpha = 0.7f))
                    )

                    Box(
                        modifier = Modifier
                            .width(10.dp)
                            .height(50.dp)
                            .clip(RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
                            .background(Color.White.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        if (normalFill > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(normalFill)
                                    .background(Color.White)
                            )
                        }
                    }
                }

                Text(
                    text = "${volumePercent}%",
                    style = typography.caption.copy(fontWeight = FontWeight.Bold),
                    color = if (isBoosted) HudBoostAmber else Color.White
                )

                if (!isBoostSupported && fallbackNotice != null) {
                    Text(
                        text = "Max 100%",
                        style = typography.caption.copy(fontSize = 9.sp),
                        color = Color.White.copy(alpha = 0.65f)
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerHorizontalScrubHud(
    visible: Boolean,
    scrubTargetMs: Long,
    scrubDeltaMs: Long,
    durationMs: Long,
    sensitivityTier: SeekSensitivityTier,
    modifier: Modifier = Modifier,
    hudTag: String = "player_horizontal_scrub_hud"
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val deltaSeconds = scrubDeltaMs / 1_000L
        val deltaPrefix = if (deltaSeconds >= 0L) "+" else "-"
        val absSec = abs(deltaSeconds)
        val deltaClock = String.format(Locale.US, "%s%02d:%02d", deltaPrefix, absSec / 60L, absSec % 60L)

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HudPillBackground,
            modifier = Modifier.testTag(hudTag)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${formatHudClock(scrubTargetMs)} / ${formatHudClock(durationMs)}",
                    style = typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = deltaClock,
                    style = typography.uiLabel.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.accentPrimary
                )
                Text(
                    text = "Speed: ${sensitivityTier.displayLabel}",
                    style = typography.caption.copy(fontWeight = FontWeight.Medium),
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun PlayerHold2xSpeedBadge(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LaterboxTheme.colors
    val typography = LaterboxTheme.typography
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = colors.accentPrimary.copy(alpha = 0.92f),
            modifier = Modifier.testTag("player_hold_2x_badge")
        ) {
            Text(
                text = "2× speed",
                style = typography.uiLabel.copy(fontWeight = FontWeight.Bold),
                color = colors.onAccentPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun PlayerSeekFeedbackBadge(
    visible: Boolean,
    text: String,
    modifier: Modifier = Modifier
) {
    val typography = LaterboxTheme.typography
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HudPillBackground,
            modifier = Modifier.testTag("player_seek_feedback_badge")
        ) {
            Text(
                text = text,
                style = typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

internal fun formatHudClock(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000L
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
