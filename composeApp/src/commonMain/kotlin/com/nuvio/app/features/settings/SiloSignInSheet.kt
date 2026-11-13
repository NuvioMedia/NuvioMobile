package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.message
import com.nuvio.app.features.servers.silo.SiloProfile
import com.nuvio.app.features.servers.silo.SiloProvider
import com.nuvio.app.features.servers.silo.SiloSignInProblem
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.action_continue
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.servers_address
import nuvio.composeapp.generated.resources.servers_connect
import nuvio.composeapp.generated.resources.servers_password
import nuvio.composeapp.generated.resources.servers_sign_in_title
import nuvio.composeapp.generated.resources.servers_username
import nuvio.composeapp.generated.resources.silo_address_hint
import nuvio.composeapp.generated.resources.silo_address_subtitle
import nuvio.composeapp.generated.resources.silo_approve_in_browser
import nuvio.composeapp.generated.resources.silo_code_copied
import nuvio.composeapp.generated.resources.silo_code_denied
import nuvio.composeapp.generated.resources.silo_code_expired
import nuvio.composeapp.generated.resources.silo_code_instructions
import nuvio.composeapp.generated.resources.silo_code_starting
import nuvio.composeapp.generated.resources.silo_code_subtitle
import nuvio.composeapp.generated.resources.silo_code_unavailable
import nuvio.composeapp.generated.resources.silo_code_waiting
import nuvio.composeapp.generated.resources.silo_error_not_silo
import nuvio.composeapp.generated.resources.silo_error_password_off
import nuvio.composeapp.generated.resources.silo_error_temporary_password
import nuvio.composeapp.generated.resources.silo_error_too_many_tries
import nuvio.composeapp.generated.resources.silo_finding
import nuvio.composeapp.generated.resources.silo_new_code
import nuvio.composeapp.generated.resources.silo_password_subtitle
import nuvio.composeapp.generated.resources.silo_pin_hint
import nuvio.composeapp.generated.resources.silo_pin_title
import nuvio.composeapp.generated.resources.silo_pin_wrong
import nuvio.composeapp.generated.resources.silo_profile_kids
import nuvio.composeapp.generated.resources.silo_profile_pin
import nuvio.composeapp.generated.resources.silo_profiles_title
import nuvio.composeapp.generated.resources.silo_signing_in
import nuvio.composeapp.generated.resources.silo_use_code
import nuvio.composeapp.generated.resources.silo_use_password
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SiloSignInSheet(
    provider: SiloProvider,
    address: String,
    onConnected: (ServerConnection) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val flow = remember(provider) {
        SiloSignInFlow(provider, scope, onConnected = { connection ->
            scope.launch { dismissNuvioBottomSheet(sheetState) { onConnected(connection) } }
        })
    }
    val state by flow.state.collectAsStateWithLifecycle()

    LaunchedEffect(flow) { flow.start() }
    DisposableEffect(flow) { onDispose { flow.close() } }

    NuvioModalBottomSheet(
        onDismissRequest = { if (!state.busy) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = tokens.spacing.sheetPadding,
                    end = tokens.spacing.sheetPadding,
                    bottom = tokens.spacing.sheetPadding,
                ),
        ) {
            when (state.step) {
                SiloSignInStep.ADDRESS, SiloSignInStep.FINDING -> AddressStep(state, flow, address)
                SiloSignInStep.CODE -> CodeStep(state, flow)
                SiloSignInStep.PASSWORD -> PasswordStep(state, flow)
                SiloSignInStep.PROFILES -> ProfilesStep(state, flow)
                SiloSignInStep.PIN -> PinStep(state, flow)
                SiloSignInStep.SIGNING_IN -> {
                    SheetHeader(title = stringResource(Res.string.servers_sign_in_title, serverName(state)), subtitle = "")
                    Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
                    StatusLine(stringResource(Res.string.silo_signing_in), loading = true)
                }
                SiloSignInStep.FAILED -> {
                    SheetHeader(title = stringResource(Res.string.servers_sign_in_title, serverName(state)), subtitle = "")
                    ErrorLine(state)
                    Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
                    PrimaryButton(stringResource(Res.string.action_retry)) { flow.backToAddress() }
                }
            }
        }
    }
}

@Composable
private fun serverName(state: SiloSignInState): String = state.server?.name ?: "Silo"

@Composable
private fun AddressStep(state: SiloSignInState, flow: SiloSignInFlow, initial: String) {
    var address by rememberSaveable { mutableStateOf(initial) }
    val finding = state.step == SiloSignInStep.FINDING
    SheetHeader(
        title = stringResource(Res.string.servers_sign_in_title, "Silo"),
        subtitle = stringResource(Res.string.silo_address_subtitle),
    )
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        modifier = Modifier.fillMaxWidth(),
        enabled = !finding,
        singleLine = true,
        label = { Text(stringResource(Res.string.servers_address)) },
        placeholder = { Text(stringResource(Res.string.silo_address_hint)) },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { flow.find(address) }),
        shape = MaterialTheme.nuvio.shapes.button,
        colors = sheetFieldColors(),
    )
    ErrorLine(state)
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
    PrimaryButton(
        text = stringResource(if (finding) Res.string.silo_finding else Res.string.action_continue),
        enabled = address.isNotBlank() && !finding,
        loading = finding,
    ) { flow.find(address) }
}

@Composable
private fun CodeStep(state: SiloSignInState, flow: SiloSignInFlow) {
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(state.code) { mutableStateOf(false) }
    val server = state.server ?: return
    SheetHeader(
        title = stringResource(Res.string.servers_sign_in_title, server.name),
        subtitle = stringResource(Res.string.silo_code_subtitle, server.name),
    )
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
    val code = state.code
    when {
        state.codePhase == SiloCodePhase.STARTING -> StatusLine(stringResource(Res.string.silo_code_starting), loading = true)
        state.codePhase == SiloCodePhase.UNAVAILABLE -> StatusLine(stringResource(Res.string.silo_code_unavailable), error = true)
        state.codePhase == SiloCodePhase.EXPIRED || state.codePhase == SiloCodePhase.DENIED -> {
            StatusLine(
                stringResource(
                    if (state.codePhase == SiloCodePhase.DENIED) Res.string.silo_code_denied else Res.string.silo_code_expired,
                ),
                error = true,
            )
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
            PrimaryButton(stringResource(Res.string.silo_new_code)) { flow.newCode() }
        }
        code != null -> {
            PrimaryButton(stringResource(Res.string.silo_approve_in_browser)) {
                runCatching { uriHandler.openUri(code.linkWithCode) }
            }
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
            Text(
                text = stringResource(Res.string.silo_code_instructions, code.link),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s12))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        clipboard.setText(AnnotatedString(code.userCode))
                        copied = true
                    },
                shape = tokens.shapes.card,
                color = tokens.colors.surfaceCard,
            ) {
                Text(
                    text = code.shownCode,
                    modifier = Modifier.padding(vertical = NuvioTokens.Space.s20),
                    style = MaterialTheme.typography.headlineMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 4.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s12))
            StatusLine(
                stringResource(if (copied) Res.string.silo_code_copied else Res.string.silo_code_waiting),
                loading = !copied,
            )
        }
    }
    ErrorLine(state)
    if (server.passwordSignIn) {
        SecondaryButton(stringResource(Res.string.silo_use_password), enabled = !state.busy) { flow.usePassword() }
    }
    SecondaryButton(stringResource(Res.string.action_back), enabled = !state.busy) { flow.backToAddress() }
}

@Composable
private fun PasswordStep(state: SiloSignInState, flow: SiloSignInFlow) {
    val tokens = MaterialTheme.nuvio
    val server = state.server ?: return
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    fun submit() = flow.signInWithPassword(username.trim(), password)
    SheetHeader(
        title = stringResource(Res.string.servers_sign_in_title, server.name),
        subtitle = stringResource(Res.string.silo_password_subtitle, server.name),
    )
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
            singleLine = true,
            label = { Text(stringResource(Res.string.servers_username)) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            shape = tokens.shapes.button,
            colors = sheetFieldColors(),
        )
        SettingsSecretTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(Res.string.servers_password),
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
            shape = tokens.shapes.button,
            colors = sheetFieldColors(),
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
    }
    ErrorLine(state)
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
    PrimaryButton(
        text = stringResource(Res.string.servers_connect),
        enabled = username.isNotBlank() && password.isNotEmpty() && !state.busy,
        loading = state.busy,
    ) {
        submit()
        password = ""
    }
    if (server.codeSignIn) {
        SecondaryButton(stringResource(Res.string.silo_use_code), enabled = !state.busy) { flow.useCode() }
    }
    SecondaryButton(stringResource(Res.string.action_back), enabled = !state.busy) { flow.backToAddress() }
}

@Composable
private fun ProfilesStep(state: SiloSignInState, flow: SiloSignInFlow) {
    SheetHeader(title = stringResource(Res.string.silo_profiles_title), subtitle = serverName(state))
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
    SettingsGroup(isTablet = false) {
        state.profiles.forEachIndexed { index, profile ->
            if (index > 0) SettingsGroupDivider(isTablet = false)
            SettingsNavigationRow(
                title = profile.name,
                description = profile.describe(),
                icon = Icons.Rounded.Person,
                enabled = !state.busy,
                isTablet = false,
                onClick = { flow.pickProfile(profile) },
            )
        }
    }
    ErrorLine(state)
}

@Composable
private fun PinStep(state: SiloSignInState, flow: SiloSignInFlow) {
    val tokens = MaterialTheme.nuvio
    val profile = state.pinFor ?: return
    var pin by remember(profile.id) { mutableStateOf("") }
    SheetHeader(title = stringResource(Res.string.silo_pin_title, profile.name), subtitle = serverName(state))
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
    SettingsSecretTextField(
        value = pin,
        onValueChange = { value -> pin = value.filter(Char::isDigit).take(MAX_PIN_LENGTH) },
        label = stringResource(Res.string.silo_pin_hint),
        modifier = Modifier.fillMaxWidth(),
        isError = state.pinWrong,
        enabled = !state.busy,
        shape = tokens.shapes.button,
        colors = sheetFieldColors(),
        imeAction = ImeAction.Done,
        keyboardActions = KeyboardActions(onDone = { flow.submitPin(pin) }),
    )
    if (state.pinWrong) {
        Spacer(modifier = Modifier.height(NuvioTokens.Space.s10))
        Text(
            text = stringResource(Res.string.silo_pin_wrong),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.danger,
        )
    }
    ErrorLine(state)
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
    PrimaryButton(
        text = stringResource(Res.string.servers_connect),
        enabled = pin.isNotBlank() && !state.busy,
        loading = state.busy,
    ) { flow.submitPin(pin) }
    if (state.profiles.size > 1) {
        SecondaryButton(stringResource(Res.string.action_back), enabled = !state.busy) { flow.backToProfiles() }
    }
}

@Composable
private fun StatusLine(text: String, loading: Boolean = false, error: Boolean = false) {
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
private fun ErrorLine(state: SiloSignInState) {
    val text = when (state.problem) {
        SiloSignInProblem.NOT_SILO -> stringResource(Res.string.silo_error_not_silo)
        SiloSignInProblem.TEMPORARY_PASSWORD -> stringResource(Res.string.silo_error_temporary_password)
        SiloSignInProblem.PASSWORD_SIGN_IN_OFF -> stringResource(Res.string.silo_error_password_off)
        SiloSignInProblem.TOO_MANY_TRIES -> stringResource(Res.string.silo_error_too_many_tries)
        null -> state.error?.let { stringResource(it.message()) }
    } ?: return
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s10))
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.danger)
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, loading: Boolean = false, onClick: () -> Unit) {
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
private fun SecondaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s8))
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(text = text, color = MaterialTheme.nuvio.colors.accent)
    }
}

@Composable
private fun SiloProfile.describe(): String = listOfNotNull(
    stringResource(Res.string.silo_profile_kids).takeIf { isChild },
    stringResource(Res.string.silo_profile_pin).takeIf { hasPin },
).joinToString(" · ")

private const val MAX_PIN_LENGTH = 8
