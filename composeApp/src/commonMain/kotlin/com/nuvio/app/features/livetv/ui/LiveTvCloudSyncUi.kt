package com.nuvio.app.features.livetv.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.format.formatLocalDateTime
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.sync.CloudChoice
import com.nuvio.app.features.livetv.sync.CloudPrompt
import com.nuvio.app.features.livetv.sync.LiveTvCloudSync
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow

/**
 * The two questions Nuvio account sync asks, shown over whatever screen is open: back this
 * device's Live TV setup up to the account, or choose between the saved setup and this one.
 */
@Composable
internal fun LiveTvCloudSyncPrompts() {
    val state by remember { LiveTvCloudSync.start(); LiveTvCloudSync.state }.collectAsStateWithLifecycle()
    when (state.prompt) {
        CloudPrompt.BackUp -> DialogSurface(
            onDismissRequest = { LiveTvCloudSync.answerBackUp(false) },
            title = "Save your Live TV setup?",
            message = "Save your playlists, guides and settings to your Nuvio account, so they follow you to your other devices. " +
                "Your IPTV logins are included, kept encrypted. You can turn this off or delete the saved copy any time in Live TV settings.",
        ) {
            DialogButtons {
                DialogButton(text = "Not Now", onClick = { LiveTvCloudSync.answerBackUp(false) })
                DialogButton(text = "Save My Setup", style = DialogButtonStyle.Primary, onClick = { LiveTvCloudSync.answerBackUp(true) })
            }
        }
        CloudPrompt.Choose -> DialogSurface(
            onDismissRequest = { LiveTvCloudSync.answerChoose(CloudChoice.Later) },
            title = "Your account has a saved Live TV setup",
            message = "This device has a different setup. Combine keeps playlists and guides from both. " +
                "Use the Saved Setup replaces this device's setup with the one saved in your account.",
        ) {
            DialogButtons {
                DialogButton(text = "Not Now", onClick = { LiveTvCloudSync.answerChoose(CloudChoice.Later) })
                DialogButton(text = "Use the Saved Setup", onClick = { LiveTvCloudSync.answerChoose(CloudChoice.UseSaved) })
                DialogButton(text = "Combine Both", style = DialogButtonStyle.Primary, onClick = { LiveTvCloudSync.answerChoose(CloudChoice.Combine) })
            }
        }
        null -> Unit
    }
}

private enum class CloudConfirm { Off, Delete, Earlier }

/** Live TV settings: the Nuvio account sync card (turn on or off, sync now, earlier copy, delete). */
@Composable
internal fun LiveTvCloudSyncSection(isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    val state by remember { LiveTvCloudSync.start(); LiveTvCloudSync.state }.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<CloudConfirm?>(null) }

    NuvioSectionLabel(
        text = "Nuvio account sync",
        modifier = Modifier.padding(horizontal = NuvioTokens.Radius.card).padding(top = NuvioTokens.Space.s16, bottom = NuvioTokens.Space.s10),
    )
    SettingsGroup(isTablet = isTablet) {
        when {
            !state.available -> Text(
                text = "Nuvio account sync works with the official Nuvio server.",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                modifier = Modifier.padding(NuvioTokens.Space.s16),
            )
            !state.signedIn -> Text(
                text = "Sign in to your Nuvio account to keep your Live TV setup the same on all your devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                modifier = Modifier.padding(NuvioTokens.Space.s16),
            )
            !state.enabled -> {
                Text(
                    text = "Save your playlists, guides and settings to your Nuvio account, and get them back on any device you sign in to.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                    modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Turn On",
                    description = state.email,
                    icon = Icons.Filled.Backup,
                    isTablet = isTablet,
                    enabled = !state.busy,
                    trailingContent = { if (state.busy) NuvioLoadingIndicator(size = NuvioTokens.Space.s20) },
                    onClick = { LiveTvCloudSync.turnOn() },
                )
            }
            else -> {
                SettingsNavigationRow(
                    title = "On",
                    description = (state.email ?: "") + (state.lastSyncMs.takeIf { it > 0 }?.let { " · last synced " + formatLocalDateTime(it) } ?: ""),
                    icon = Icons.Filled.Backup,
                    isTablet = isTablet,
                    onClick = {},
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Sync Now",
                    description = "Check for changes from your other devices",
                    isTablet = isTablet,
                    enabled = !state.busy,
                    trailingContent = { if (state.busy) NuvioLoadingIndicator(size = NuvioTokens.Space.s20) },
                    onClick = { LiveTvCloudSync.syncNow() },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Restore Earlier Copy",
                    description = "Go back to the copy saved before the last change",
                    isTablet = isTablet,
                    enabled = !state.busy,
                    onClick = { confirm = CloudConfirm.Earlier },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Turn Off",
                    description = "Stop syncing here. Your saved copy stays in your account",
                    isTablet = isTablet,
                    onClick = { confirm = CloudConfirm.Off },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Delete Saved Setup",
                    description = "Remove the saved copy from your account. This device keeps its setup",
                    isTablet = isTablet,
                    onClick = { confirm = CloudConfirm.Delete },
                )
            }
        }
        state.message?.let {
            SettingsGroupDivider(isTablet = isTablet)
            Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, modifier = Modifier.padding(NuvioTokens.Space.s16))
        }
    }

    confirm?.let { which ->
        val dismiss = { confirm = null }
        val (title, message, action) = when (which) {
            CloudConfirm.Off -> Triple("Turn off sync?", "This device stops syncing. Your saved copy stays in your Nuvio account.", { LiveTvCloudSync.turnOff() })
            CloudConfirm.Delete -> Triple("Delete the saved setup?", "The copy in your Nuvio account is removed and sync turns off here. This device keeps its own setup.", { LiveTvCloudSync.deleteCloudCopy() })
            CloudConfirm.Earlier -> Triple("Restore the earlier copy?", "This replaces the setup on this device and in your account with the copy saved before the last change.", { LiveTvCloudSync.restorePrevious() })
        }
        DialogSurface(onDismissRequest = dismiss, title = title, message = message) {
            DialogButtons {
                DialogButton(text = "Cancel", onClick = dismiss)
                DialogButton(
                    text = "Continue",
                    style = if (which == CloudConfirm.Delete) DialogButtonStyle.Destructive else DialogButtonStyle.Primary,
                    onClick = { action(); dismiss() },
                )
            }
        }
    }
}
