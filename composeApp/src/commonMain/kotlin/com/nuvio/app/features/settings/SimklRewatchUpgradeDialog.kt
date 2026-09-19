package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_tracking_rewatch_pro_description
import nuvio.composeapp.generated.resources.settings_tracking_rewatch_pro_not_now
import nuvio.composeapp.generated.resources.settings_tracking_rewatch_pro_title
import nuvio.composeapp.generated.resources.settings_tracking_rewatch_pro_upgrade
import nuvio.composeapp.generated.resources.settings_trakt_failed_open_browser
import org.jetbrains.compose.resources.stringResource

/**
 * Shown when a free Simkl account tries to turn rewatch recording on. Simkl only stores rewatch
 * sessions for Pro and VIP plans, so the picker stays on its previous value until the plan allows it.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SimklRewatchUpgradeDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var browserError by rememberSaveable { mutableStateOf(false) }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_tracking_rewatch_pro_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.settings_tracking_rewatch_pro_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (browserError) {
                    Text(
                        text = stringResource(Res.string.settings_trakt_failed_open_browser),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.settings_tracking_rewatch_pro_not_now))
                    }
                    TextButton(
                        onClick = {
                            browserError = false
                            runCatching { uriHandler.openUri(SIMKL_VIP_URL) }
                                .onFailure { browserError = true }
                        },
                    ) {
                        Text(stringResource(Res.string.settings_tracking_rewatch_pro_upgrade))
                    }
                }
            }
        }
    }
}

private const val SIMKL_VIP_URL = "https://simkl.com/vip/"
