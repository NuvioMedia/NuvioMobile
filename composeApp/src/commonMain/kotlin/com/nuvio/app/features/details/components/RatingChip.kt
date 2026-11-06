package com.nuvio.app.features.details.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingRatingState
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.rating_rate
import nuvio.composeapp.generated.resources.rating_value
import nuvio.composeapp.generated.resources.rating_your_rating
import org.jetbrains.compose.resources.stringResource

@Composable
fun DetailRating(
    media: TrackingMediaReference,
    title: String,
    state: TrackingRatingState,
    modifier: Modifier = Modifier,
) {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    RatingChip(
        rating = state.rating,
        onClick = { showSheet = true },
        modifier = modifier,
    )
    if (showSheet) {
        RatingSheet(
            media = media,
            title = title,
            state = state,
            onDismiss = { showSheet = false },
        )
    }
}

@Composable
fun RatingChip(
    rating: Double?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val rated = rating != null
    val twirl = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(rating) }
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val container by animateColorAsState(
        targetValue = if (rated) tokens.colors.accent.copy(alpha = 0.16f) else tokens.colors.textPrimary.copy(alpha = 0.08f),
        animationSpec = tween(durationMillis = 240),
        label = "rating_chip_container",
    )
    val label = rating?.let { stringResource(Res.string.rating_value, formatRating(it)) }
        ?: stringResource(Res.string.rating_rate)
    val description = "${stringResource(Res.string.rating_your_rating)}, $label"

    LaunchedEffect(rating) {
        if (rating == shown) return@LaunchedEffect
        shown = rating
        if (rating == null) return@LaunchedEffect
        twirl.snapTo(0f)
        twirl.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
    }

    Row(
        modifier = modifier
            .clip(MetaBadgeShape)
            .background(container)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(MetaBadgePadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (rated) Icons.Rounded.Star else Icons.Rounded.StarOutline,
            contentDescription = null,
            tint = if (rated) tokens.colors.accent else contentColor,
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer {
                    val progress = twirl.value
                    val pop = 1f + 0.3f * sin(PI * progress.coerceIn(0f, 1f)).toFloat()
                    rotationZ = 144f * progress
                    scaleX = pop
                    scaleY = pop
                },
        )
        AnimatedContent(
            targetState = rating,
            transitionSpec = {
                (slideInVertically { it / 2 } + fadeIn(tween(160))) togetherWith
                    (slideOutVertically { -it / 2 } + fadeOut(tween(120))) using
                    SizeTransform(clip = false)
            },
            label = "rating_chip_value",
        ) { value ->
            Text(
                text = value?.let(::formatRating) ?: stringResource(Res.string.rating_rate),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (value != null) FontWeight.Bold else null,
                color = if (value != null) MaterialTheme.colorScheme.onBackground else contentColor,
                maxLines = 1,
            )
        }
    }
}

internal fun formatRating(value: Double): String =
    if (value == floor(value)) value.toInt().toString() else formatOneDecimal(value)
