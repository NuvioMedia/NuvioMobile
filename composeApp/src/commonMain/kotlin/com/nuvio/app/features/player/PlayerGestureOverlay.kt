package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.accentBrush
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.themePalette
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_brightness
import nuvio.composeapp.generated.resources.compose_player_volume
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerGestureOverlay(
    currentFeedback: GestureFeedbackState?,
    renderedFeedback: GestureFeedbackState?,
    useLegacyLayout: Boolean,
    horizontalSafePadding: Dp,
    horizontalPadding: Dp,
) {
    val isSeek = currentFeedback?.icon == GestureFeedbackIcon.SeekForward ||
        currentFeedback?.icon == GestureFeedbackIcon.SeekBackward
    AnimatedVisibility(
        visible = currentFeedback != null && (useLegacyLayout || !isSeek),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (useLegacyLayout) {
                renderedFeedback?.let { feedback ->
                    GestureFeedbackPill(
                        feedback = feedback,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                            .padding(horizontal = horizontalSafePadding)
                            .padding(top = 40.dp),
                    )
                }
            } else {
                (currentFeedback ?: renderedFeedback)?.let { feedback ->
                    PlayerGestureFeedback(
                        feedback = feedback,
                        horizontalSafePadding = horizontalSafePadding,
                        horizontalPadding = horizontalPadding,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerGestureFeedback(
    feedback: GestureFeedbackState,
    horizontalSafePadding: Dp,
    horizontalPadding: Dp,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            when (feedback.icon) {
                GestureFeedbackIcon.Brightness, GestureFeedbackIcon.Volume, GestureFeedbackIcon.VolumeMuted -> {
                    val isBrightness = feedback.icon == GestureFeedbackIcon.Brightness
                    val description = stringResource(
                        if (isBrightness) Res.string.compose_player_brightness else Res.string.compose_player_volume,
                    )
                    val level = feedback.level?.coerceIn(0f, 1f) ?: 0f
                    val trackHeight = minOf(maxHeight / 4, 104.dp)
                    val animatedLevel by animateFloatAsState(level, tween(80), label = "playerGestureLevel")
                    Box(
                        modifier = Modifier
                            .align(if (isBrightness) Alignment.CenterStart else Alignment.CenterEnd)
                            .padding(horizontal = horizontalSafePadding + 8.dp)
                            .semantics {
                                contentDescription = description
                                progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f)
                            }
                            .width(6.dp)
                            .height(trackHeight)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.3f)),
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .fillMaxHeight(animatedLevel)
                                .background(MaterialTheme.themePalette.accentBrush()),
                        )
                    }
                }
                GestureFeedbackIcon.Speed -> {
                    val message = feedback.messageRes?.let { stringResource(it, *feedback.messageArgs.toTypedArray()) }
                        ?: feedback.message.orEmpty()
                    Text(
                        text = message,
                        color = Color.White,
                        style = MaterialTheme.nuvioTypeScale.bodyLg.copy(
                            fontWeight = FontWeight.SemiBold,
                            shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f),
                        ),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                            .padding(horizontal = horizontalSafePadding + horizontalPadding)
                            .padding(top = 40.dp),
                    )
                }
                GestureFeedbackIcon.SeekForward, GestureFeedbackIcon.SeekBackward -> Unit
            }
        }
    }
}
