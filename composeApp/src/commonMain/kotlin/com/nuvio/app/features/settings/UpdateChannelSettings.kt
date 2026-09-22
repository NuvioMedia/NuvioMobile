package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.updater.UpdateChannel
import com.nuvio.app.features.updater.UpdatePreferences
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateChannelSettingsRow(isTablet: Boolean) {
    val channel by UpdatePreferences.shared.channel.collectAsState()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val tokens = MaterialTheme.nuvio
    SettingsNavigationRow(
        title = stringResource(Res.string.updates_channel_title),
        description = stringResource(Res.string.updates_channel_description),
        icon = Icons.Rounded.SystemUpdate,
        isTablet = isTablet,
        trailingContent = {
            Text(
                text = updateChannelLabel(channel),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
        },
        onClick = { showDialog = true },
    )
    if (showDialog) {
        BasicAlertDialog(onDismissRequest = { showDialog = false }) {
            SettingsDialogSurface(title = stringResource(Res.string.updates_channel_title)) {
                Column(
                    modifier = Modifier.selectableGroup().verticalScroll(rememberScrollState()),
                ) {
                    UpdateChannel.entries.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = option == channel,
                                role = Role.RadioButton,
                                onClick = {
                                    UpdatePreferences.shared.setChannel(option)
                                    showDialog = false
                                },
                            ).padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                        ) {
                            RadioButton(selected = option == channel, onClick = null)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = updateChannelLabel(option),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = tokens.colors.textPrimary,
                                )
                                Text(
                                    text = stringResource(
                                        when (option) {
                                            UpdateChannel.STABLE -> Res.string.updates_channel_stable_description
                                            UpdateChannel.BETA -> Res.string.updates_channel_beta_description
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = tokens.colors.textSecondary,
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showDialog = false }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun updateChannelLabel(channel: UpdateChannel): String = stringResource(
    when (channel) {
        UpdateChannel.STABLE -> Res.string.updates_channel_stable
        UpdateChannel.BETA -> Res.string.updates_channel_beta
    },
)
