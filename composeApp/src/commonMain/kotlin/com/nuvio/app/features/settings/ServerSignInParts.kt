package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.servers_code_copied
import nuvio.composeapp.generated.resources.servers_connect
import nuvio.composeapp.generated.resources.servers_pin_hint
import nuvio.composeapp.generated.resources.servers_pin_title
import nuvio.composeapp.generated.resources.servers_pin_wrong
import org.jetbrains.compose.resources.stringResource

/** The sign-in code in a card that copies [copyText] when tapped, then the waiting line (or "Code copied"). */
@Composable
internal fun SignInCodeCard(shownCode: String, copyText: String, waiting: String, letterSpacing: TextUnit = 4.sp) {
    val tokens = MaterialTheme.nuvio
    val clipboard = LocalClipboardManager.current
    var copied by remember(copyText) { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                clipboard.setText(AnnotatedString(copyText))
                copied = true
            },
        shape = tokens.shapes.card,
        color = tokens.colors.surfaceCard,
    ) {
        Text(
            text = shownCode,
            modifier = Modifier.padding(vertical = NuvioTokens.Space.s20),
            style = MaterialTheme.typography.headlineMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = letterSpacing,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s12))
    SignInStatusLine(if (copied) stringResource(Res.string.servers_code_copied) else waiting, loading = !copied)
}

/** A list to pick one entry from: servers, users or profiles. */
@Composable
internal fun <T> SignInPicker(
    items: List<T>,
    icon: ImageVector,
    title: (T) -> String,
    description: @Composable (T) -> String,
    enabled: (T) -> Boolean,
    onPick: (T) -> Unit,
) {
    SettingsGroup(isTablet = false) {
        items.forEachIndexed { index, item ->
            if (index > 0) SettingsGroupDivider(isTablet = false)
            SettingsNavigationRow(
                title = title(item),
                description = description(item),
                icon = icon,
                enabled = enabled(item),
                isTablet = false,
                onClick = { onPick(item) },
            )
        }
    }
}

/** The PIN step for a user or profile; [onBack] is left out when there is nothing to go back to. */
@Composable
internal fun SignInPinStep(
    name: String,
    subtitle: String,
    key: Any?,
    maxLength: Int,
    wrong: Boolean,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onBack: (() -> Unit)?,
    error: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var pin by remember(key) { mutableStateOf("") }
    SheetHeader(title = stringResource(Res.string.servers_pin_title, name), subtitle = subtitle)
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
    SettingsSecretTextField(
        value = pin,
        onValueChange = { value -> pin = value.filter(Char::isDigit).take(maxLength) },
        label = stringResource(Res.string.servers_pin_hint),
        modifier = Modifier.fillMaxWidth(),
        isError = wrong,
        enabled = !busy,
        shape = tokens.shapes.button,
        colors = sheetFieldColors(),
        imeAction = ImeAction.Done,
        keyboardActions = KeyboardActions(onDone = { onSubmit(pin) }),
    )
    if (wrong) SignInErrorText(stringResource(Res.string.servers_pin_wrong))
    error()
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
    SignInPrimaryButton(
        text = stringResource(Res.string.servers_connect),
        enabled = pin.isNotBlank() && !busy,
        loading = busy,
    ) { onSubmit(pin) }
    if (onBack != null) SignInTextButton(stringResource(Res.string.action_back), enabled = !busy, onClick = onBack)
}

@Composable
internal fun SignInStatusLine(text: String, loading: Boolean = false, error: Boolean = false) {
    val tokens = MaterialTheme.nuvio
    Row(
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) NuvioLoadingIndicator(modifier = Modifier.size(tokens.icons.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) tokens.colors.danger else tokens.colors.textSecondary,
        )
    }
}

@Composable
internal fun SignInErrorText(text: String?) {
    text ?: return
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s10))
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.danger)
}

@Composable
internal fun SignInPrimaryButton(text: String, enabled: Boolean = true, loading: Boolean = false, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(NuvioTokens.Space.s56),
        enabled = enabled,
        shape = tokens.shapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = tokens.colors.accent,
            contentColor = tokens.colors.onAccent,
        ),
    ) {
        if (loading) {
            NuvioLoadingIndicator(modifier = Modifier.size(tokens.icons.sm), color = tokens.colors.onAccent)
        } else {
            Text(text)
        }
    }
}

@Composable
internal fun SignInTextButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s8))
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(text = text, color = MaterialTheme.nuvio.colors.accent)
    }
}
