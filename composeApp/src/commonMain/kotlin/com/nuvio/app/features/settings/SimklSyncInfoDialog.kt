package com.nuvio.app.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.simkl.SIMKL_AUTOMATIC_REFRESH_INTERVAL_MINUTES
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.settings_simkl_sync_info_activity
import nuvio.composeapp.generated.resources.settings_simkl_sync_info_description
import nuvio.composeapp.generated.resources.settings_simkl_sync_info_docs
import nuvio.composeapp.generated.resources.settings_simkl_sync_info_library_statuses
import nuvio.composeapp.generated.resources.settings_simkl_sync_info_manual
import nuvio.composeapp.generated.resources.settings_simkl_sync_info_title
import nuvio.composeapp.generated.resources.settings_trakt_failed_open_browser
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SimklSyncInfoDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val tokens = MaterialTheme.nuvio
    var browserError by rememberSaveable { mutableStateOf(false) }

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_simkl_sync_info_title),
    ) {
        listOf(
            stringResource(Res.string.settings_simkl_sync_info_description, SIMKL_AUTOMATIC_REFRESH_INTERVAL_MINUTES),
            stringResource(Res.string.settings_simkl_sync_info_activity),
            stringResource(Res.string.settings_simkl_sync_info_manual),
            stringResource(Res.string.settings_simkl_sync_info_library_statuses),
        ).forEach { paragraph ->
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
        }
        if (browserError) {
            Text(
                text = stringResource(Res.string.settings_trakt_failed_open_browser),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.danger,
            )
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.settings_simkl_sync_info_docs),
                onClick = {
                    browserError = false
                    runCatching { uriHandler.openUri(SIMKL_SYNC_GUIDE_URL) }
                        .onFailure { browserError = true }
                },
            )
            DialogButton(
                text = stringResource(Res.string.action_close),
                onClick = onDismiss,
                style = DialogButtonStyle.Primary,
            )
        }
    }
}

private const val SIMKL_SYNC_GUIDE_URL = "https://api.simkl.org/guides/sync"
