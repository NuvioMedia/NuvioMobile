package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.membershipTitle
import com.nuvio.app.features.tracking.trackingMembershipDestinations
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.compose_tracking_list_picker_loading
import nuvio.composeapp.generated.resources.compose_tracking_list_picker_subtitle
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingListPickerDialog(
    visible: Boolean,
    title: String,
    tabs: List<TrackingLibraryTab>,
    membership: Map<String, Boolean>,
    isPending: Boolean,
    errorMessage: String?,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val tokens = MaterialTheme.nuvio
    val destinations = trackingMembershipDestinations(tabs)

    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
    ) {
        Text(
            text = stringResource(Res.string.compose_tracking_list_picker_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.danger,
            )
        }

        if (isPending && destinations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NuvioTokens.Space.s80 + NuvioTokens.Space.s80 + NuvioTokens.Space.s80 + NuvioTokens.Space.s40),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
                ) {
                    NuvioLoadingIndicator(
                        modifier = Modifier.size(tokens.icons.lg),
                    )
                    Text(
                        text = stringResource(Res.string.compose_tracking_list_picker_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NuvioTokens.Space.s80 + NuvioTokens.Space.s80 + NuvioTokens.Space.s80 + NuvioTokens.Space.s40),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            ) {
                items(items = destinations, key = { it.key }) { tab ->
                    DialogOption(
                        text = tab.membershipTitle(),
                        selected = membership[tab.key] == true,
                        enabled = !isPending,
                        role = Role.Checkbox,
                        onClick = { onToggle(tab.key) },
                    )
                }
            }
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
                enabled = !isPending,
            )
            DialogButton(
                text = stringResource(Res.string.action_save),
                onClick = onSave,
                style = DialogButtonStyle.Primary,
                loading = isPending,
            )
        }
    }
}
