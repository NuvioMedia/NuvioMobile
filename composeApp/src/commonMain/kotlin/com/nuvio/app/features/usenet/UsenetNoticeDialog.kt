package com.nuvio.app.features.usenet

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.usenet_cancel
import nuvio.composeapp.generated.resources.usenet_notice_body
import nuvio.composeapp.generated.resources.usenet_notice_title
import nuvio.composeapp.generated.resources.usenet_notice_turn_on
import org.jetbrains.compose.resources.stringResource

/**
 * Shown before built-in Usenet is first switched on in a profile: searches and downloads go
 * directly from this device, with the user's own accounts.
 */
@Composable
fun UsenetNoticeDialog(
    onTurnOn: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.usenet_notice_title),
    ) {
        Text(
            text = stringResource(Res.string.usenet_notice_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textSecondary,
            modifier = Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
        )
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.usenet_cancel),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(Res.string.usenet_notice_turn_on),
                onClick = onTurnOn,
                style = DialogButtonStyle.Primary,
            )
        }
    }
}
