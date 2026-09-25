package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.servers_add_jellyfin
import nuvio.composeapp.generated.resources.servers_add_jellyfin_description
import nuvio.composeapp.generated.resources.servers_empty
import nuvio.composeapp.generated.resources.servers_section_add
import nuvio.composeapp.generated.resources.servers_section_connected
import nuvio.composeapp.generated.resources.servers_signed_in_as
import nuvio.composeapp.generated.resources.servers_status_auth
import nuvio.composeapp.generated.resources.servers_status_disabled
import nuvio.composeapp.generated.resources.servers_status_unreachable
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.mediaServersSettingsContent(isTablet: Boolean) {
    item {
        val uiState by remember {
            ServerRepository.ensureLoaded()
            ServerRepository.uiState
        }.collectAsStateWithLifecycle()
        var managedConnectionId by rememberSaveable { mutableStateOf<String?>(null) }
        var signIn by remember { mutableStateOf<ServerSignInRequest?>(null) }

        SettingsSection(
            title = stringResource(Res.string.servers_section_connected),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                if (uiState.connections.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.servers_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.nuvio.colors.textMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
                uiState.connections.forEachIndexed { index, connection ->
                    if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = connection.name,
                        description = connection.statusText(uiState.failures[connection.id]),
                        icon = Icons.Rounded.Dns,
                        isTablet = isTablet,
                        onClick = { managedConnectionId = connection.id },
                    )
                }
            }
        }

        SettingsSection(
            title = stringResource(Res.string.servers_section_add),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.servers_add_jellyfin),
                    description = stringResource(Res.string.servers_add_jellyfin_description),
                    icon = Icons.Rounded.Add,
                    isTablet = isTablet,
                    onClick = { signIn = ServerSignInRequest() },
                )
            }
        }

        uiState.connections.firstOrNull { it.id == managedConnectionId }?.let { connection ->
            ServerConnectionDialog(
                connection = connection,
                failure = uiState.failures[connection.id],
                onSignInAgain = {
                    managedConnectionId = null
                    signIn = ServerSignInRequest(address = connection.address, username = connection.userName)
                },
                onDismiss = { managedConnectionId = null },
            )
        }
        signIn?.let { request ->
            ServerSignInDialog(
                request = request,
                onConnected = { connection ->
                    signIn = null
                    managedConnectionId = connection.id
                },
                onDismiss = { signIn = null },
            )
        }
    }
}

@Composable
private fun ServerConnection.statusText(failure: ServerFailure?): String {
    val status = when {
        !enabled -> stringResource(Res.string.servers_status_disabled)
        failure == ServerFailure.AUTH_REQUIRED -> stringResource(Res.string.servers_status_auth)
        failure == ServerFailure.UNREACHABLE -> stringResource(Res.string.servers_status_unreachable)
        else -> null
    }
    val identity = stringResource(
        Res.string.servers_signed_in_as,
        ServerRepository.provider(this)?.displayName ?: providerId,
        userName,
    )
    return listOfNotNull(identity, status).joinToString(" · ")
}
