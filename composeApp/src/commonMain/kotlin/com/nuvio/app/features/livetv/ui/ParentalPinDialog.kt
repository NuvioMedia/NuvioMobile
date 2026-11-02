package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.layout.fillMaxWidth
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.parental.ParentalControls
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.livetv_parental_is_locked
import nuvio.composeapp.generated.resources.livetv_parental_pin
import nuvio.composeapp.generated.resources.livetv_parental_pin_wrong
import nuvio.composeapp.generated.resources.livetv_parental_unlock
import org.jetbrains.compose.resources.stringResource

/**
 * Asks for the parental PIN. [onPin] says whether it was right (false shows "wrong PIN" and
 * keeps the dialog open); [confirmLabel] is the button ("Unlock", "Remove lock"…).
 */
@Composable
internal fun ParentalPinDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(Res.string.livetv_parental_unlock),
    message: String? = null,
    onPin: (String) -> Boolean,
) {
    val tokens = MaterialTheme.nuvio
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    fun submit() {
        if (onPin(pin)) onDismiss() else { wrong = true; pin = "" }
    }
    DialogSurface(onDismissRequest = onDismiss, title = title, message = message) {
        OutlinedTextField(
            value = pin,
            onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8); wrong = false },
            singleLine = true,
            label = { Text(stringResource(Res.string.livetv_parental_pin)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            isError = wrong,
            supportingText = if (wrong) ({ Text(stringResource(Res.string.livetv_parental_pin_wrong), color = tokens.colors.danger) }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(text = confirmLabel, style = DialogButtonStyle.Primary, enabled = pin.length >= 4, onClick = ::submit)
        }
    }
}

/** The PIN prompt for a locked group: right → the group opens until the app is closed. */
@Composable
internal fun UnlockGroupDialog(groupId: String, groupTitle: String, onDismiss: () -> Unit, onUnlocked: () -> Unit = {}) {
    ParentalPinDialog(
        title = "“$groupTitle” " + stringResource(Res.string.livetv_parental_is_locked),
        onDismiss = onDismiss,
        onPin = { pin -> ParentalControls.unlock(groupId, pin).also { if (it) onUnlocked() } },
    )
}
