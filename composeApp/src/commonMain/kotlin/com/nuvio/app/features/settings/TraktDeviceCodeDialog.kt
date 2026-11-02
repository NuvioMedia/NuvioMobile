package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.features.trakt.TRAKT_ACTIVATION_URL
import com.nuvio.app.features.trakt.TraktAuthUiState
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Shows the Trakt device code while the app polls for approval. Dismissing the dialog only
 * hides it (the card keeps showing the code); Cancel ends the sign-in.
 */
@Composable
internal fun TraktDeviceCodeDialog(
    uiState: TraktAuthUiState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val userCode = uiState.deviceUserCode ?: return
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    val verificationUrl = uiState.deviceVerificationUrl?.takeIf(String::isNotBlank) ?: TRAKT_ACTIVATION_URL
    var copied by remember(userCode) { mutableStateOf(false) }
    var browserError by remember(userCode) { mutableStateOf(false) }

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.trakt_device_dialog_title),
    ) {
        Text(
            text = stringResource(Res.string.trakt_device_instructions),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) {
                    clipboardManager.setText(AnnotatedString(userCode))
                    copied = true
                },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ) {
            Column(
                modifier = Modifier.padding(vertical = 18.dp, horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = userCode,
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        if (copied) Res.string.trakt_device_code_copied else Res.string.trakt_device_tap_to_copy,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = verificationUrl.removePrefix("https://"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        val statusMessage = uiState.errorMessage?.takeIf(String::isNotBlank)
            ?: stringResource(Res.string.settings_trakt_failed_open_browser).takeIf { browserError }
            ?: uiState.statusMessage?.takeIf(String::isNotBlank)
        if (statusMessage != null) {
            val isError = statusMessage == uiState.errorMessage || browserError
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (uiState.isPolling && !isError) {
                    NuvioLoadingIndicator(modifier = Modifier.size(16.dp))
                }
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = {
                    onCancel()
                    onDismiss()
                },
            )
            DialogButton(
                text = stringResource(Res.string.trakt_device_open_activate),
                onClick = {
                    browserError = false
                    runCatching { uriHandler.openUri(verificationUrl) }
                        .onFailure { browserError = true }
                },
                style = DialogButtonStyle.Primary,
            )
        }
    }
}
