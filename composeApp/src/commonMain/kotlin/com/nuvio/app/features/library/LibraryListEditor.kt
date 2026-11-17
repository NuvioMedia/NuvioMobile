package com.nuvio.app.features.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.tracking.LibraryListPrivacy
import com.nuvio.app.features.tracking.TrackingListManagementCapabilities
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryListEditor(
    state: LibraryListDialogState,
    capabilities: TrackingListManagementCapabilities,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onPrivacyChange: (LibraryListPrivacy) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val inputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = tokens.colors.borderFocus,
        unfocusedBorderColor = tokens.colors.borderDefault,
        focusedContainerColor = tokens.colors.surfaceCard,
        unfocusedContainerColor = tokens.colors.surfaceCard,
    )
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text(stringResource(Res.string.library_list_name)) },
            singleLine = true,
            enabled = !state.isPending,
            modifier = Modifier.fillMaxWidth(),
            shape = tokens.shapes.compactCard,
            colors = inputColors,
            keyboardOptions = KeyboardOptions(imeAction = if (capabilities.supportsDescription) ImeAction.Next else ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!state.isPending && state.name.isNotBlank()) onSubmit() }),
        )
        if (capabilities.supportsDescription) {
            OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                label = { Text(stringResource(Res.string.library_list_description)) },
                enabled = !state.isPending,
                modifier = Modifier.fillMaxWidth(),
                shape = tokens.shapes.compactCard,
                colors = inputColors,
                minLines = 2,
                maxLines = 4,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.library_list_privacy), style = MaterialTheme.typography.labelLarge)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                capabilities.privacyOptions.chunked(2).forEach { options ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { privacy ->
                            val selected = state.privacy == privacy
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = tokens.shapes.compactCard,
                                color = if (selected) tokens.colors.accent.copy(alpha = 0.12f) else tokens.colors.surfaceCard,
                                contentColor = when {
                                    state.isPending -> tokens.colors.textDisabled
                                    selected -> tokens.colors.accent
                                    else -> tokens.colors.textMuted
                                },
                                border = BorderStroke(1.dp, if (selected) tokens.colors.accent.copy(alpha = 0.5f) else tokens.colors.borderSubtle),
                            ) {
                                Row(
                                    modifier = Modifier.selectable(selected, enabled = !state.isPending, role = Role.RadioButton) {
                                        onPrivacyChange(privacy)
                                    }.padding(horizontal = 14.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(privacyIcon(privacy), contentDescription = null, modifier = Modifier.size(20.dp))
                                    Text(privacyLabel(privacy), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
            val description = when (state.privacy) {
                LibraryListPrivacy.PRIVATE -> Res.string.library_list_private_description
                LibraryListPrivacy.PUBLIC -> Res.string.library_list_public_description
                else -> null
            }
            description?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted) }
        }
    }
}

@Composable
internal fun privacyLabel(privacy: LibraryListPrivacy): String = stringResource(when (privacy) {
    LibraryListPrivacy.PRIVATE -> Res.string.library_list_private
    LibraryListPrivacy.PUBLIC -> Res.string.library_list_public
    LibraryListPrivacy.LINK -> Res.string.library_list_link
    LibraryListPrivacy.FRIENDS -> Res.string.library_list_friends
})

internal fun privacyIcon(privacy: LibraryListPrivacy): ImageVector = when (privacy) {
    LibraryListPrivacy.PRIVATE -> Icons.Rounded.Lock
    LibraryListPrivacy.PUBLIC -> Icons.Rounded.Public
    LibraryListPrivacy.LINK -> Icons.Rounded.Link
    LibraryListPrivacy.FRIENDS -> Icons.Rounded.People
}
