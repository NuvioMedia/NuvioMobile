package com.nuvio.app.features.details.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingRatingState
import com.nuvio.app.features.tracking.TrackingRatings
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_remove
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.rating_failed
import nuvio.composeapp.generated.resources.rating_removed
import nuvio.composeapp.generated.resources.rating_saved
import nuvio.composeapp.generated.resources.rating_syncs_to
import nuvio.composeapp.generated.resources.rating_your_rating
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingSheet(
    media: TrackingMediaReference,
    title: String,
    state: TrackingRatingState,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val tokens = MaterialTheme.nuvio
    val dismiss: () -> Unit = {
        scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }

    NuvioModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = nuvioSafeBottomPadding(16.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.rating_your_rating),
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            RatingPanel(
                media = media,
                state = state,
                onFinished = dismiss,
            )
        }
    }
}

@Composable
fun RatingPanel(
    media: TrackingMediaReference,
    state: TrackingRatingState,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val tokens = MaterialTheme.nuvio
    val current = state.rating
    var value by remember { mutableIntStateOf(current?.roundToInt()?.coerceIn(0, TrackingRatings.MAX) ?: 0) }
    var saving by remember { mutableStateOf(false) }
    val provider = state.provider?.displayName.orEmpty()

    fun submit(rating: Int?) {
        saving = true
        scope.launch {
            val saved = try {
                TrackingRatings.rate(media, rating)
            } finally {
                saving = false
            }
            if (saved) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                NuvioToastController.show(getString(if (rating == null) Res.string.rating_removed else Res.string.rating_saved))
            } else {
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                NuvioToastController.show(getString(Res.string.rating_failed, provider))
            }
            onFinished()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RatingScore(value = value)
        Spacer(modifier = Modifier.height(16.dp))
        RatingStars(
            value = value,
            onValueChange = { value = it },
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.rating_syncs_to, provider),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (current != null) {
                DialogButton(
                    text = stringResource(Res.string.action_remove),
                    onClick = { submit(null) },
                    modifier = Modifier.weight(1f),
                    enabled = !saving,
                )
            }
            DialogButton(
                text = stringResource(Res.string.action_save),
                onClick = { submit(value) },
                modifier = Modifier.weight(1f),
                style = DialogButtonStyle.Primary,
                enabled = value > 0 && value.toDouble() != current,
                loading = saving,
            )
        }
    }
}

@Composable
private fun RatingScore(value: Int) {
    val tokens = MaterialTheme.nuvio
    val color by animateColorAsState(
        targetValue = if (value > 0) tokens.colors.textPrimary else tokens.colors.textMuted,
        animationSpec = tween(durationMillis = 200),
        label = "rating_score_color",
    )
    Row(verticalAlignment = Alignment.Bottom) {
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                val rising = targetState > initialState
                val motion = spring<IntOffset>(
                    dampingRatio = 0.8f,
                    stiffness = Spring.StiffnessMediumLow,
                )
                (slideInVertically(motion) { if (rising) it / 2 else -it / 2 } + fadeIn(tween(140))) togetherWith
                    (slideOutVertically(motion) { if (rising) -it / 2 else it / 2 } + fadeOut(tween(100))) using
                    SizeTransform(clip = false)
            },
            label = "rating_score",
        ) { score ->
            Text(
                text = if (score > 0) score.toString() else "–",
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 56.sp, lineHeight = 60.sp),
                fontWeight = if (score > 0) FontWeight.Bold else FontWeight.Light,
                color = color,
            )
        }
        Text(
            text = "/${TrackingRatings.MAX}",
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textMuted,
        )
    }
}

@Composable
fun rememberRatingState(media: TrackingMediaReference): State<TrackingRatingState> {
    val flow = remember(media) { TrackingRatings.observe(media) }
    val initial = remember(media) { TrackingRatings.current(media) }
    return flow.collectAsStateWithLifecycle(initial)
}

internal fun MetaDetails.ratingReference(episode: MetaVideo? = null): TrackingMediaReference {
    val reference = buildTrackingMediaReference(
        contentType = type,
        parentMetaId = id,
        videoId = episode?.id,
        title = name,
        releaseInfo = releaseInfo,
        seasonNumber = episode?.season,
        episodeNumber = episode?.episode,
        episodeTitle = episode?.title,
    )
    return reference.copy(ids = reference.ids.mergeMissing(TrackingExternalIds(imdb = imdbId)))
}
