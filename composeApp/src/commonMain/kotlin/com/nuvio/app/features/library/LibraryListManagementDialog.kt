package com.nuvio.app.features.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.rememberModalBottomSheetState
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.tracking.LibraryListPrivacy
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingListManagementCapabilities
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryListManagementDialog(
    state: LibraryListDialogState,
    tabs: List<TrackingLibraryTab>,
    capabilities: TrackingListManagementCapabilities,
    onCreate: () -> Unit,
    onEdit: (TrackingLibraryTab) -> Unit,
    onDelete: (TrackingLibraryTab) -> Unit,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onPrivacyChange: (LibraryListPrivacy) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    errorMessage: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    val personal = tabs.filter { it.kind == TrackingLibraryTabKind.PERSONAL }
    val editing = personal.firstOrNull { it.key == state.key }
    val creating = state.mode == LibraryListDialogMode.EDIT && state.key == null
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val keyboard = LocalSoftwareKeyboardController.current
        val scope = rememberCoroutineScope()
        val submit = { keyboard?.hide(); onSubmit() }
        val back = { keyboard?.hide(); onBack() }
        val dismiss: () -> Unit = {
            keyboard?.hide()
            scope.launch { dismissNuvioBottomSheet(sheetState, onDismiss) }
        }
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding().navigationBarsPadding()
                .padding(horizontal = tokens.spacing.sheetPadding)
                .padding(bottom = tokens.spacing.sheetPadding),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (state.key != null) {
                    IconButton(onClick = back, enabled = !state.isPending) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.action_back))
                    }
                }
                Text(
                    text = stringResource(when (state.mode) {
                        LibraryListDialogMode.MANAGE -> Res.string.library_manage_lists
                        LibraryListDialogMode.DELETE -> Res.string.library_list_delete
                        LibraryListDialogMode.EDIT -> if (creating) Res.string.library_create_list else Res.string.library_edit_list
                    }),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = dismiss, enabled = !state.isPending) {
                    Icon(Icons.Rounded.Close, stringResource(Res.string.action_close), tint = tokens.colors.textMuted)
                }
            }
            when (state.mode) {
                LibraryListDialogMode.MANAGE -> LibraryListRows(
                    tabs = personal,
                    onEdit = onEdit,
                    modifier = Modifier.weight(1f, fill = false),
                )
                LibraryListDialogMode.EDIT -> LibraryListEditor(
                    state = state,
                    capabilities = capabilities,
                    onNameChange = onNameChange,
                    onDescriptionChange = onDescriptionChange,
                    onPrivacyChange = onPrivacyChange,
                    onSubmit = submit,
                    modifier = Modifier.weight(1f, fill = false),
                )
                LibraryListDialogMode.DELETE -> Text(
                    text = stringResource(Res.string.library_list_delete_message, state.name),
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textMuted,
                )
            }
            state.error?.let {
                Text(
                    text = errorMessage ?: stringResource(if (state.mode == LibraryListDialogMode.DELETE)
                        Res.string.library_list_delete_failed else Res.string.library_list_save_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.danger,
                )
            }
            when (state.mode) {
                LibraryListDialogMode.MANAGE -> NuvioPrimaryButton(
                    text = stringResource(Res.string.library_create_list),
                    onClick = onCreate,
                )
                LibraryListDialogMode.EDIT -> {
                    NuvioPrimaryButton(
                        text = stringResource(when {
                            state.isPending -> Res.string.action_saving
                            creating -> Res.string.library_create_list_action
                            else -> Res.string.action_save
                        }),
                        enabled = !state.isPending && state.name.isNotBlank(),
                        onClick = submit,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        if (creating) {
                            TextButton(onClick = back, enabled = !state.isPending) {
                                Text(stringResource(Res.string.library_manage_lists))
                            }
                        } else if (editing != null) {
                            TextButton(onClick = { keyboard?.hide(); onDelete(editing) }, enabled = !state.isPending) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = tokens.colors.danger)
                                Text(stringResource(Res.string.library_list_delete_action), color = tokens.colors.danger,
                                    modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
                LibraryListDialogMode.DELETE -> Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                ) {
                    TextButton(onClick = back, enabled = !state.isPending, modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                    Button(
                        onClick = submit,
                        enabled = !state.isPending,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = tokens.shapes.button,
                        colors = ButtonDefaults.buttonColors(containerColor = tokens.colors.danger, contentColor = tokens.colors.onAccent),
                    ) { Text(stringResource(Res.string.library_list_delete_action)) }
                }
            }
        }
    }
}
