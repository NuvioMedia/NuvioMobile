package com.nuvio.app.features.p2p

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
import nuvio.composeapp.generated.resources.p2p_consent_body
import nuvio.composeapp.generated.resources.p2p_consent_cancel
import nuvio.composeapp.generated.resources.p2p_consent_enable
import nuvio.composeapp.generated.resources.p2p_consent_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun P2pConsentDialog(
    onEnableP2p: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.p2p_consent_title),
    ) {
        Text(
            text = stringResource(Res.string.p2p_consent_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textSecondary,
            modifier = Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
        )
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.p2p_consent_cancel),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(Res.string.p2p_consent_enable),
                onClick = onEnableP2p,
                style = DialogButtonStyle.Primary,
            )
        }
    }
}
