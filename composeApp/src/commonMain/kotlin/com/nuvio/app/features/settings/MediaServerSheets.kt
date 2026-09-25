package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.servers_address
import nuvio.composeapp.generated.resources.servers_address_hint
import nuvio.composeapp.generated.resources.servers_connect
import nuvio.composeapp.generated.resources.servers_connecting
import nuvio.composeapp.generated.resources.servers_enabled
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
import nuvio.composeapp.generated.resources.servers_sign_in_title
import nuvio.composeapp.generated.resources.servers_username
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal data class ServerSignInRequest(
    val address: String = "",
    val username: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerSignInDialog(
    request: ServerSignInRequest,
    onConnected: (ServerConnection) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(request.address) }
    var username by rememberSaveable { mutableStateOf(request.username) }
    var password by remember { mutableStateOf("") }
    var connecting by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<ServerFailure?>(null) }

    BasicAlertDialog(onDismissRequest = { if (!connecting) onDismiss() }) {
        SettingsDialogSurface(title = stringResource(Res.string.servers_sign_in_title)) {
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(Res.string.servers_address)) },
                placeholder = { Text(stringResource(Res.string.servers_address_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(Res.string.servers_username)) },
            )
            SettingsSecretTextField(
                value = password,
                onValueChange = {
                    password = it
                    error = null
                },
                label = stringResource(Res.string.servers_password),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { failure ->
                Text(
                    text = stringResource(failure.signInMessage()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss, enabled = !connecting) {
                    Text(stringResource(Res.string.action_cancel))
                }
                Button(
                    enabled = !connecting && address.isNotBlank() && username.isNotBlank(),
                    onClick = {
                        connecting = true
                        error = null
                        scope.launch {
                            try {
                                val connection = ServerRepository.connectJellyfin(address, username.trim(), password)
                                password = ""
                                onConnected(connection)
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
                    },
                ) {
                    Text(
                        stringResource(if (connecting) Res.string.servers_connecting else Res.string.servers_connect),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerConnectionDialog(
    connection: ServerConnection,
    failure: ServerFailure?,
    onSignInAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refreshing by rememberSaveable(connection.id) { mutableStateOf(false) }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        SettingsDialogSurface(title = ServerRepository.sourceLabel(connection)) {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.servers_enabled),
                    checked = connection.enabled,
                    isTablet = false,
                    onCheckedChange = { ServerRepository.setEnabled(connection.id, it) },
                )
                Text(
                    text = stringResource(Res.string.servers_libraries),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        if (connection.libraries.isEmpty()) Res.string.servers_no_libraries else Res.string.servers_libraries_description,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                connection.libraries.forEach { library ->
                    SettingsSwitchRow(
                        title = library.name,
                        checked = library.selected,
                        enabled = connection.enabled,
                        isTablet = false,
                        onCheckedChange = { ServerRepository.setLibrarySelected(connection.id, library.id, it) },
                    )
                }
            }
            failure?.let {
                Text(
                    text = stringResource(it.signInMessage()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                TextButton(
                    enabled = !refreshing && connection.enabled,
                    onClick = {
                        refreshing = true
                        scope.launch {
                            runCatching { ServerRepository.refreshLibraries(connection.id) }
                            refreshing = false
                        }
                    },
                ) {
                    Text(stringResource(Res.string.servers_refresh_libraries))
                }
                TextButton(onClick = onSignInAgain) {
                    Text(stringResource(Res.string.servers_sign_in_again))
                }
                TextButton(
                    onClick = {
                        ServerRepository.remove(connection.id)
                        onDismiss()
                    },
                ) {
                    Text(stringResource(Res.string.servers_remove), color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = onDismiss) {
                    Text(stringResource(Res.string.action_done))
                }
            }
        }
    }
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
