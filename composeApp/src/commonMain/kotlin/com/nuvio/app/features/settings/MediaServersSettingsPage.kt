package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioTokens
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
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.mediaServersSettingsContent(
    isTablet: Boolean,
    onServerClick: () -> Unit,
) {
    item {
        val uiState by remember {
            ServerRepository.ensureLoaded()
            ServerRepository.uiState
        }.collectAsStateWithLifecycle()
        var signIn by remember { mutableStateOf<ServerSignInRequest?>(null) }
        fun open(connection: ServerConnection) {
            MediaServerSelection.connectionId = connection.id
            onServerClick()
        }

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
                        onClick = { open(connection) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
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

        signIn?.let { request ->
            ServerSignInSheet(
                request = request,
                onConnected = { connection ->
                    signIn = null
                    open(connection)
                },
                onDismiss = { signIn = null },
            )
        }
    }
}

@Composable
private fun ServerConnection.statusText(failure: ServerFailure?): String =
    listOfNotNull(identity(), status(failure)?.first).joinToString(" · ")
