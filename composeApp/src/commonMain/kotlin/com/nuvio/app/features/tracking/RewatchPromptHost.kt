package com.nuvio.app.features.tracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.rewatch_notice_declined
import nuvio.composeapp.generated.resources.rewatch_notice_failed
import nuvio.composeapp.generated.resources.rewatch_notice_recorded
import nuvio.composeapp.generated.resources.rewatch_prompt_confirm
import nuvio.composeapp.generated.resources.rewatch_prompt_dismiss
import nuvio.composeapp.generated.resources.rewatch_prompt_title
import org.jetbrains.compose.resources.stringResource

/** How long the question stays on screen before it counts as a no. */
private const val REWATCH_PROMPT_TIMEOUT_MS = 8_000L

/** How long the feedback of an answer stays on screen. */
private const val REWATCH_NOTICE_TIMEOUT_MS = 2_600L

/**
 * Shows the rewatch question and its answer feedback above the rest of the app. It is deliberately
 * a popup instead of part of a screen: the playback that triggered it can end on any screen, and an
 * unanswered question must never block navigation or playback.
 *
 * The second answer keeps the series in Continue Watching, which is how a rewatch run started at
 * S01E01 offers S01E02 next instead of pointing back at the canonical watch position.
 */
@Composable
fun RewatchPromptHost() {
    RewatchQuestionPopup()
    RewatchNoticePopup()
}

@Composable
private fun RewatchQuestionPopup() {
    val prompt by RewatchPromptRepository.prompt.collectAsStateWithLifecycle()
    val active = prompt ?: return
    val promptKey = "${active.media.stableKey}:${active.watchedAtEpochMs}"

    LaunchedEffect(promptKey) {
        delay(REWATCH_PROMPT_TIMEOUT_MS)
        RewatchPromptRepository.dismiss()
    }

    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = RewatchPromptRepository::dismiss,
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .widthIn(max = 460.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(Res.string.rewatch_prompt_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = RewatchPromptRepository::decline,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(Res.string.rewatch_prompt_dismiss),
                            maxLines = 2,
                        )
                    }
                    TextButton(
                        onClick = RewatchPromptRepository::confirm,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(Res.string.rewatch_prompt_confirm),
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

/** The small pill that says what the last answer did, so a tap is never silent. */
@Composable
private fun RewatchNoticePopup() {
    val notice by RewatchPromptRepository.notice.collectAsStateWithLifecycle()
    val active = notice ?: return

    LaunchedEffect(active) {
        delay(REWATCH_NOTICE_TIMEOUT_MS)
        RewatchPromptRepository.dismissNotice()
    }

    Popup(alignment = Alignment.BottomCenter) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .widthIn(max = 360.dp),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 4.dp,
            shadowElevation = 6.dp,
        ) {
            Text(
                text = active.kind.message(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun RewatchNoticeKind.message(): String = stringResource(
    when (this) {
        RewatchNoticeKind.RECORDED -> Res.string.rewatch_notice_recorded
        RewatchNoticeKind.NOT_RECORDED -> Res.string.rewatch_notice_declined
        RewatchNoticeKind.FAILED -> Res.string.rewatch_notice_failed
    },
)
