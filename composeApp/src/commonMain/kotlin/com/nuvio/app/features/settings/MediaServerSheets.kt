package com.nuvio.app.features.settings

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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.servers_address
import nuvio.composeapp.generated.resources.servers_address_hint
import nuvio.composeapp.generated.resources.servers_connect
import nuvio.composeapp.generated.resources.servers_connecting
import nuvio.composeapp.generated.resources.servers_enabled
import nuvio.composeapp.generated.resources.servers_enabled_description
import nuvio.composeapp.generated.resources.servers_error_address
import nuvio.composeapp.generated.resources.servers_error_auth
import nuvio.composeapp.generated.resources.servers_error_failed
import nuvio.composeapp.generated.resources.servers_error_forbidden
import nuvio.composeapp.generated.resources.servers_error_unreachable
import nuvio.composeapp.generated.resources.servers_error_unsupported
import nuvio.composeapp.generated.resources.servers_libraries
import nuvio.composeapp.generated.resources.servers_libraries_description
import nuvio.composeapp.generated.resources.servers_no_libraries
import nuvio.composeapp.generated.resources.servers_password
import nuvio.composeapp.generated.resources.servers_refresh_libraries
import nuvio.composeapp.generated.resources.servers_remove
import nuvio.composeapp.generated.resources.servers_sign_in_again
import nuvio.composeapp.generated.resources.servers_sign_in_subtitle
import nuvio.composeapp.generated.resources.servers_sign_in_title
import nuvio.composeapp.generated.resources.servers_status_auth
import nuvio.composeapp.generated.resources.servers_status_disabled
import nuvio.composeapp.generated.resources.servers_status_unreachable
import nuvio.composeapp.generated.resources.servers_username
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal data class ServerSignInRequest(
    val address: String = "",
    val username: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerSignInSheet(
    request: ServerSignInRequest,
    onConnected: (ServerConnection) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var address by rememberSaveable { mutableStateOf(request.address) }
    var username by rememberSaveable { mutableStateOf(request.username) }
    var password by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ServerFailure?>(null) }
    val canConnect = !connecting && address.isNotBlank() && username.isNotBlank()

    fun connect() {
        if (!canConnect) return
        connecting = true
        error = null
        scope.launch {
            try {
                val connection = ServerRepository.connectJellyfin(address, username.trim(), password)
                password = ""
                dismissNuvioBottomSheet(sheetState) { onConnected(connection) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ServerException) {
                error = failure.failure
            } catch (_: Exception) {
                error = ServerFailure.FAILED
            } finally {
                connecting = false
            }
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = { if (!connecting) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    start = tokens.spacing.sheetPadding,
                    end = tokens.spacing.sheetPadding,
                    bottom = tokens.spacing.sheetPadding,
                ),
        ) {
            SheetHeader(
                title = stringResource(Res.string.servers_sign_in_title),
                subtitle = stringResource(Res.string.servers_sign_in_subtitle),
            )
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !connecting,
                    singleLine = true,
                    label = { Text(stringResource(Res.string.servers_address)) },
                    placeholder = { Text(stringResource(Res.string.servers_address_hint)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    shape = tokens.shapes.button,
                    colors = sheetFieldColors(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !connecting,
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
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = stringResource(Res.string.servers_password),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !connecting,
                    shape = tokens.shapes.button,
                    colors = sheetFieldColors(),
                    imeAction = ImeAction.Done,
                    keyboardActions = KeyboardActions(onDone = { connect() }),
                )
            }
            error?.let { failure ->
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s10))
                Text(
                    text = stringResource(failure.signInMessage()),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
            }
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
            Button(
                onClick = ::connect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NuvioTokens.Space.s56),
                enabled = canConnect,
                shape = tokens.shapes.button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = tokens.colors.accent,
                    contentColor = tokens.colors.onAccent,
                ),
            ) {
                if (connecting) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NuvioLoadingIndicator(
                            modifier = Modifier.size(tokens.icons.sm),
                            color = tokens.colors.onAccent,
                        )
                        Text(stringResource(Res.string.servers_connecting))
                    }
                } else {
                    Text(stringResource(Res.string.servers_connect))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerManageSheet(
    connection: ServerConnection,
    failure: ServerFailure?,
    onSignInAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var refreshing by remember(connection.id) { mutableStateOf(false) }
    fun dismissThen(action: () -> Unit) {
        scope.launch { dismissNuvioBottomSheet(sheetState) { action() } }
    }

    NuvioModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = NuvioTokens.Space.s8),
        ) {
            Column(modifier = Modifier.padding(horizontal = tokens.spacing.sheetPadding)) {
                SheetHeader(
                    title = connection.name,
                    subtitle = "${ServerRepository.provider(connection)?.displayName ?: connection.providerId} · ${connection.userName}",
                )
                Text(
                    text = connection.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = NuvioTokens.Space.s4),
                )
                connection.status(failure)?.let { (text, color) ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = NuvioTokens.Space.s8),
                    )
                }
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
                SettingsGroup(isTablet = false) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.servers_enabled),
                        description = stringResource(Res.string.servers_enabled_description),
                        checked = connection.enabled,
                        isTablet = false,
                        onCheckedChange = { ServerRepository.setEnabled(connection.id, it) },
                    )
                }
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s20))
                SettingsSection(
                    title = stringResource(Res.string.servers_libraries),
                    isTablet = false,
                ) {
                    Text(
                        text = stringResource(
                            if (connection.libraries.isEmpty()) {
                                Res.string.servers_no_libraries
                            } else {
                                Res.string.servers_libraries_description
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                        modifier = Modifier.padding(bottom = NuvioTokens.Space.s10),
                    )
                    if (connection.libraries.isNotEmpty()) {
                        SettingsGroup(isTablet = false) {
                            connection.libraries.forEachIndexed { index, library ->
                                if (index > 0) {
                                    NuvioBottomSheetDivider(modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16))
                                }
                                SettingsSwitchRow(
                                    title = library.name,
                                    checked = library.selected,
                                    enabled = connection.enabled,
                                    isTablet = false,
                                    onCheckedChange = { ServerRepository.setLibrarySelected(connection.id, library.id, it) },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s12))
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                title = stringResource(Res.string.servers_refresh_libraries),
                icon = Icons.Rounded.Sync,
                enabled = connection.enabled && !refreshing,
                onClick = {
                    refreshing = true
                    scope.launch {
                        runCatching { ServerRepository.refreshLibraries(connection.id) }
                            .onFailure { if (it is CancellationException) throw it }
                        refreshing = false
                    }
                },
                trailingContent = if (refreshing) {
                    { NuvioLoadingIndicator(modifier = Modifier.size(tokens.icons.sm)) }
                } else {
                    null
                },
            )
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                title = stringResource(Res.string.servers_sign_in_again),
                icon = Icons.Rounded.Lock,
                onClick = { dismissThen(onSignInAgain) },
            )
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                title = stringResource(Res.string.servers_remove),
                icon = Icons.Rounded.Delete,
                color = tokens.colors.danger,
                onClick = {
                    dismissThen {
                        ServerRepository.remove(connection.id)
                        onDismiss()
                    }
                },
            )
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = tokens.colors.textPrimary,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(modifier = Modifier.height(NuvioTokens.Space.s4))
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.textSecondary,
    )
}

@Composable
private fun ServerConnection.status(failure: ServerFailure?): Pair<String, Color>? {
    val colors = MaterialTheme.nuvio.colors
    return when {
        !enabled -> stringResource(Res.string.servers_status_disabled) to colors.textMuted
        failure == ServerFailure.AUTH_REQUIRED -> stringResource(Res.string.servers_status_auth) to colors.danger
        failure == ServerFailure.UNREACHABLE -> stringResource(Res.string.servers_status_unreachable) to colors.warning
        else -> null
    }
}

@Composable
private fun sheetFieldColors(): TextFieldColors {
    val colors = MaterialTheme.nuvio.colors
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.borderFocus,
        unfocusedBorderColor = colors.borderDefault,
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        focusedLabelColor = colors.textSecondary,
        unfocusedLabelColor = colors.textMuted,
        cursorColor = colors.accent,
    )
}

private fun ServerFailure.signInMessage(): StringResource = when (this) {
    ServerFailure.AUTH_REQUIRED -> Res.string.servers_error_auth
    ServerFailure.NOT_FOUND -> Res.string.servers_error_address
    ServerFailure.UNREACHABLE -> Res.string.servers_error_unreachable
    ServerFailure.UNSUPPORTED -> Res.string.servers_error_unsupported
    ServerFailure.FORBIDDEN -> Res.string.servers_error_forbidden
    ServerFailure.INCOMPLETE,
    ServerFailure.FAILED -> Res.string.servers_error_failed
}
