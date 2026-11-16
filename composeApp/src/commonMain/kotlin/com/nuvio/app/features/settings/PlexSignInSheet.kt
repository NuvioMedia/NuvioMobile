package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.message
import com.nuvio.app.features.servers.plex.PlexAccountClient
import com.nuvio.app.features.servers.plex.PlexHomeUser
import com.nuvio.app.features.servers.plex.PlexProvider
import com.nuvio.app.features.servers.plex.PlexReach
import com.nuvio.app.features.servers.plex.PlexServer
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.plex_browser_waiting
import nuvio.composeapp.generated.resources.plex_code_expired
import nuvio.composeapp.generated.resources.plex_code_instructions
import nuvio.composeapp.generated.resources.plex_code_starting
import nuvio.composeapp.generated.resources.plex_code_waiting
import nuvio.composeapp.generated.resources.plex_continue
import nuvio.composeapp.generated.resources.plex_new_code
import nuvio.composeapp.generated.resources.plex_reach_local
import nuvio.composeapp.generated.resources.plex_reach_offline_detail
import nuvio.composeapp.generated.resources.plex_reach_relay_detail
import nuvio.composeapp.generated.resources.plex_reach_remote
import nuvio.composeapp.generated.resources.plex_server_owned
import nuvio.composeapp.generated.resources.plex_server_shared
import nuvio.composeapp.generated.resources.plex_servers_checking
import nuvio.composeapp.generated.resources.plex_servers_finding
import nuvio.composeapp.generated.resources.plex_servers_none
import nuvio.composeapp.generated.resources.plex_servers_subtitle
import nuvio.composeapp.generated.resources.plex_servers_title
import nuvio.composeapp.generated.resources.plex_sign_in_subtitle
import nuvio.composeapp.generated.resources.plex_use_browser
import nuvio.composeapp.generated.resources.plex_use_code
import nuvio.composeapp.generated.resources.plex_user_admin
import nuvio.composeapp.generated.resources.plex_user_managed
import nuvio.composeapp.generated.resources.plex_users_subtitle
import nuvio.composeapp.generated.resources.plex_users_title
import nuvio.composeapp.generated.resources.servers_has_pin
import nuvio.composeapp.generated.resources.servers_sign_in_title
import nuvio.composeapp.generated.resources.servers_signing_in
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlexSignInSheet(
    provider: PlexProvider,
    onConnected: (ServerConnection) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val flow = remember(provider) {
        PlexSignInFlow(provider, scope, onConnected = { connection ->
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
                PlexSignInStep.CODE -> CodeStep(state, flow)
                PlexSignInStep.SERVERS -> ServersStep(state, flow)
                PlexSignInStep.USERS -> UsersStep(state, flow)
                PlexSignInStep.PIN -> PinStep(state, flow)
                PlexSignInStep.SIGNING_IN -> Waiting(
                    title = stringResource(Res.string.servers_sign_in_title, provider.displayName),
                    message = stringResource(Res.string.servers_signing_in),
                )
                PlexSignInStep.FAILED -> FailedStep(state, flow)
            }
        }
    }
}

@Composable
private fun CodeStep(state: PlexSignInState, flow: PlexSignInFlow) {
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    var opened by remember(state.pin) { mutableStateOf(false) }

    SheetHeader(
        title = stringResource(Res.string.servers_sign_in_title, "Plex"),
        subtitle = stringResource(Res.string.plex_sign_in_subtitle),
    )
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
    when {
        state.codePhase == PlexCodePhase.STARTING -> SignInStatusLine(stringResource(Res.string.plex_code_starting), loading = true)
        state.codePhase == PlexCodePhase.EXPIRED -> {
            SignInStatusLine(stringResource(Res.string.plex_code_expired), error = true)
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
            SignInPrimaryButton(stringResource(Res.string.plex_new_code)) { flow.newCode() }
        }
        state.mode == PlexCodeMode.BROWSER -> {
            val page = state.authPage
            SignInPrimaryButton(stringResource(Res.string.plex_continue), enabled = page != null) {
                if (page != null && runCatching { uriHandler.openUri(page) }.isSuccess) opened = true
            }
            if (opened) {
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s12))
                SignInStatusLine(stringResource(Res.string.plex_browser_waiting), loading = true)
            }
        }
        else -> state.pin?.let { pin ->
            Text(
                text = stringResource(Res.string.plex_code_instructions, PlexAccountClient.LINK_PAGE),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s12))
            SignInCodeCard(
                shownCode = pin.code,
                copyText = pin.code,
                waiting = stringResource(Res.string.plex_code_waiting),
                letterSpacing = 6.sp,
            )
        }
    }
    if (state.codePhase != PlexCodePhase.STARTING) {
        val other = if (state.mode == PlexCodeMode.BROWSER) PlexCodeMode.LINK else PlexCodeMode.BROWSER
        SignInTextButton(
            stringResource(if (other == PlexCodeMode.LINK) Res.string.plex_use_code else Res.string.plex_use_browser),
        ) { flow.newCode(other) }
    }
}

@Composable
private fun ServersStep(state: PlexSignInState, flow: PlexSignInFlow) {
    SheetHeader(
        title = stringResource(Res.string.plex_servers_title),
        subtitle = state.accountName?.let { stringResource(Res.string.plex_servers_subtitle, it) }.orEmpty(),
    )
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
    when {
        state.servers.isEmpty() && state.serversLoading ->
            SignInStatusLine(stringResource(Res.string.plex_servers_finding), loading = true)
        state.servers.isEmpty() -> SignInStatusLine(stringResource(Res.string.plex_servers_none), error = true)
        else -> {
            if (state.serversLoading) {
                SignInStatusLine(stringResource(Res.string.plex_servers_checking), loading = true)
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s12))
            }
            SignInPicker(
                items = state.servers,
                icon = Icons.Rounded.Dns,
                title = { it.name },
                description = { it.describe(checking = state.serversLoading) },
                enabled = { !state.serversLoading && !state.busy && it.reach != PlexReach.OFFLINE },
                onPick = flow::pickServer,
            )
        }
    }
    ErrorLine(state.error)
}

@Composable
private fun UsersStep(state: PlexSignInState, flow: PlexSignInFlow) {
    SheetHeader(
        title = stringResource(Res.string.plex_users_title),
        subtitle = state.server?.let { stringResource(Res.string.plex_users_subtitle, it.name) }.orEmpty(),
    )
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
    SignInPicker(
        items = state.users,
        icon = Icons.Rounded.Person,
        title = { it.name },
        description = { it.describe() },
        enabled = { !state.busy },
        onPick = flow::pickUser,
    )
    ErrorLine(state.error)
    SignInTextButton(stringResource(Res.string.action_back), enabled = !state.busy) { flow.back() }
}

@Composable
private fun PinStep(state: PlexSignInState, flow: PlexSignInFlow) {
    val user = state.pinFor ?: return
    SignInPinStep(
        name = user.name,
        subtitle = state.server?.name.orEmpty(),
        key = user.uuid,
        maxLength = MAX_PIN_LENGTH,
        wrong = state.pinWrong,
        busy = state.busy,
        onSubmit = flow::submitPin,
        onBack = flow::back,
        error = { ErrorLine(state.error) },
    )
}

@Composable
private fun FailedStep(state: PlexSignInState, flow: PlexSignInFlow) {
    SheetHeader(
        title = stringResource(Res.string.servers_sign_in_title, "Plex"),
        subtitle = stringResource(Res.string.plex_sign_in_subtitle),
    )
    ErrorLine(state.error ?: ServerFailure.FAILED)
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
    SignInPrimaryButton(stringResource(Res.string.action_retry)) { flow.newCode() }
}

@Composable
private fun Waiting(title: String, message: String) {
    SheetHeader(title = title, subtitle = "")
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
    SignInStatusLine(message, loading = true)
}

@Composable
private fun ErrorLine(failure: ServerFailure?) {
    SignInErrorText(failure?.let { stringResource(it.message()) })
}

@Composable
private fun PlexServer.describe(checking: Boolean): String {
    val owner = stringResource(if (owned) Res.string.plex_server_owned else Res.string.plex_server_shared)
    if (checking) return owner
    val reachText = when (reach) {
        PlexReach.LOCAL -> stringResource(Res.string.plex_reach_local)
        PlexReach.REMOTE -> stringResource(Res.string.plex_reach_remote)
        PlexReach.RELAY -> stringResource(Res.string.plex_reach_relay_detail)
        PlexReach.OFFLINE -> stringResource(Res.string.plex_reach_offline_detail)
    }
    return "$owner · $reachText"
}

@Composable
private fun PlexHomeUser.describe(): String = listOfNotNull(
    stringResource(if (admin) Res.string.plex_user_admin else Res.string.plex_user_managed).takeIf { admin || restricted },
    stringResource(Res.string.servers_has_pin).takeIf { hasPin },
).joinToString(" · ")

private const val MAX_PIN_LENGTH = 8
