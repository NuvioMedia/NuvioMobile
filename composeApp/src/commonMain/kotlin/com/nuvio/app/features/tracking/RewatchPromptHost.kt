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
import nuvio.composeapp.generated.resources.rewatch_prompt_confirm
import nuvio.composeapp.generated.resources.rewatch_prompt_dismiss
import nuvio.composeapp.generated.resources.rewatch_prompt_title
import com.nuvio.app.features.simkl.SimklRewatchConsentRepository
import org.jetbrains.compose.resources.stringResource

/** How long the question stays on screen before it counts as a no. */
private const val REWATCH_PROMPT_TIMEOUT_MS = 8_000L

/**
 * Shows the rewatch question and its answer feedback above the rest of the app. It is deliberately
 * a popup instead of part of a screen: the playback that triggered it can end on any screen, and an
 * unanswered question must never block navigation or playback.
 *
 * The answer is what the scrobble at the end of the playback writes from: a yes may open a run, a
 * no keeps the series quiet, and nothing is sent before the playback ends.
 */
@Composable
fun RewatchPromptHost() {
    RewatchQuestionPopup()
}

@Composable
private fun RewatchQuestionPopup() {
    val question by SimklRewatchConsentRepository.question.collectAsStateWithLifecycle()
    val active = question ?: return
    val promptKey = active.media.stableKey

    LaunchedEffect(promptKey) {
        delay(REWATCH_PROMPT_TIMEOUT_MS)
        SimklRewatchConsentRepository.dismiss()
    }

    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = SimklRewatchConsentRepository::dismiss,
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
                        onClick = SimklRewatchConsentRepository::decline,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(Res.string.rewatch_prompt_dismiss),
                            maxLines = 2,
                        )
                    }
                    TextButton(
                        onClick = SimklRewatchConsentRepository::grant,
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
