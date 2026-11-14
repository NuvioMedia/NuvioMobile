package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.servers.ServerConnection
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
import nuvio.composeapp.generated.resources.servers_has_pin
import nuvio.composeapp.generated.resources.servers_password
import nuvio.composeapp.generated.resources.servers_sign_in_title
import nuvio.composeapp.generated.resources.servers_signing_in
import nuvio.composeapp.generated.resources.servers_username
import nuvio.composeapp.generated.resources.silo_address_hint
import nuvio.composeapp.generated.resources.silo_address_subtitle
import nuvio.composeapp.generated.resources.silo_approve_in_browser
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
import nuvio.composeapp.generated.resources.silo_profile_kids
import nuvio.composeapp.generated.resources.silo_profiles_title
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
    DisposableEffect(flow) { onDispose { flow.stop() } }

    NuvioModalBottomSheet(
        onDismissRequest = {
            if (!state.busy) {
                flow.close()
                onDismiss()
            }
        },
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
                    SignInStatusLine(stringResource(Res.string.servers_signing_in), loading = true)
                }
                SiloSignInStep.FAILED -> {
                    SheetHeader(title = stringResource(Res.string.servers_sign_in_title, serverName(state)), subtitle = "")
                    ErrorLine(state)
                    Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
                    SignInPrimaryButton(stringResource(Res.string.action_retry)) { flow.backToAddress() }
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
    SignInPrimaryButton(
        text = stringResource(if (finding) Res.string.silo_finding else Res.string.action_continue),
        enabled = address.isNotBlank() && !finding,
        loading = finding,
    ) { flow.find(address) }
}

@Composable
private fun CodeStep(state: SiloSignInState, flow: SiloSignInFlow) {
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    val server = state.server ?: return
    SheetHeader(
        title = stringResource(Res.string.servers_sign_in_title, server.name),
        subtitle = stringResource(Res.string.silo_code_subtitle, server.name),
    )
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
    val code = state.code
    when {
        state.codePhase == SiloCodePhase.STARTING -> SignInStatusLine(stringResource(Res.string.silo_code_starting), loading = true)
        state.codePhase == SiloCodePhase.UNAVAILABLE -> SignInStatusLine(stringResource(Res.string.silo_code_unavailable), error = true)
        state.codePhase == SiloCodePhase.EXPIRED || state.codePhase == SiloCodePhase.DENIED -> {
            SignInStatusLine(
                stringResource(
                    if (state.codePhase == SiloCodePhase.DENIED) Res.string.silo_code_denied else Res.string.silo_code_expired,
                ),
                error = true,
            )
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
            SignInPrimaryButton(stringResource(Res.string.silo_new_code)) { flow.newCode() }
        }
        code != null -> {
            SignInPrimaryButton(stringResource(Res.string.silo_approve_in_browser)) {
                runCatching { uriHandler.openUri(code.linkWithCode) }
            }
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
            Text(
                text = stringResource(Res.string.silo_code_instructions, code.link),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s12))
            SignInCodeCard(
                shownCode = code.shownCode,
                copyText = code.userCode,
                waiting = stringResource(Res.string.silo_code_waiting),
            )
        }
    }
    ErrorLine(state)
    if (server.passwordSignIn) {
        SignInTextButton(stringResource(Res.string.silo_use_password), enabled = !state.busy) { flow.usePassword() }
    }
    SignInTextButton(stringResource(Res.string.action_back), enabled = !state.busy) { flow.backToAddress() }
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
    SignInPrimaryButton(
        text = stringResource(Res.string.servers_connect),
        enabled = username.isNotBlank() && password.isNotEmpty() && !state.busy,
        loading = state.busy,
    ) {
        submit()
        password = ""
    }
    if (server.codeSignIn) {
        SignInTextButton(stringResource(Res.string.silo_use_code), enabled = !state.busy) { flow.useCode() }
    }
    SignInTextButton(stringResource(Res.string.action_back), enabled = !state.busy) { flow.backToAddress() }
}

@Composable
private fun ProfilesStep(state: SiloSignInState, flow: SiloSignInFlow) {
    SheetHeader(title = stringResource(Res.string.silo_profiles_title), subtitle = serverName(state))
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
    SignInPicker(
        items = state.profiles,
        icon = Icons.Rounded.Person,
        title = { it.name },
        description = { it.describe() },
        enabled = { !state.busy },
        onPick = flow::pickProfile,
    )
    ErrorLine(state)
}

@Composable
private fun PinStep(state: SiloSignInState, flow: SiloSignInFlow) {
    val profile = state.pinFor ?: return
    SignInPinStep(
        name = profile.name,
        subtitle = serverName(state),
        key = profile.id,
        maxLength = MAX_PIN_LENGTH,
        wrong = state.pinWrong,
        busy = state.busy,
        onSubmit = flow::submitPin,
        onBack = if (state.profiles.size > 1) flow::backToProfiles else null,
        error = { ErrorLine(state) },
    )
}

@Composable
private fun ErrorLine(state: SiloSignInState) {
    SignInErrorText(
        when (state.problem) {
            SiloSignInProblem.NOT_SILO -> stringResource(Res.string.silo_error_not_silo)
            SiloSignInProblem.TEMPORARY_PASSWORD -> stringResource(Res.string.silo_error_temporary_password)
            SiloSignInProblem.PASSWORD_SIGN_IN_OFF -> stringResource(Res.string.silo_error_password_off)
            SiloSignInProblem.TOO_MANY_TRIES -> stringResource(Res.string.silo_error_too_many_tries)
            null -> state.error?.let { stringResource(it.message()) }
        },
    )
}

@Composable
private fun SiloProfile.describe(): String = listOfNotNull(
    stringResource(Res.string.silo_profile_kids).takeIf { isChild },
    stringResource(Res.string.servers_has_pin).takeIf { hasPin },
).joinToString(" · ")

private const val MAX_PIN_LENGTH = 8
