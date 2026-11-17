package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.SurfaceEdge
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.cd_selected
import org.jetbrains.compose.resources.stringResource

internal data class TrackingPickerOption<T>(
    val value: T,
    val title: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val unavailableReason: String? = null,
)

@Composable
internal fun <T> TrackingAdaptivePicker(
    isTablet: Boolean,
    title: String,
    subtitle: String,
    selectedValue: T,
    options: List<TrackingPickerOption<T>>,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    if (isTablet) {
        TrackingPickerDialog(
            title = title,
            subtitle = subtitle,
            selectedValue = selectedValue,
            options = options,
            onSelected = onSelected,
            onDismiss = onDismiss,
        )
    } else {
        TrackingPickerBottomSheet(
            title = title,
            subtitle = subtitle,
            selectedValue = selectedValue,
            options = options,
            onSelected = onSelected,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> TrackingPickerBottomSheet(
    title: String,
    subtitle: String,
    selectedValue: T,
    options: List<TrackingPickerOption<T>>,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun dismiss() {
        scope.launch {
            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = sheetState,
    ) {
        TrackingPickerContent(
            title = title,
            subtitle = subtitle,
            selectedValue = selectedValue,
            options = options,
            isTablet = false,
            onSelected = { value ->
                onSelected(value)
                dismiss()
            },
            onDismiss = ::dismiss,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> TrackingPickerDialog(
    title: String,
    subtitle: String,
    selectedValue: T,
    options: List<TrackingPickerOption<T>>,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = tokens.components.dialogMaxWidth),
            shape = tokens.shapes.dialog,
            color = tokens.colors.surfaceDialog,
            border = BorderStroke(tokens.borders.thin, SurfaceEdge),
        ) {
            TrackingPickerContent(
                title = title,
                subtitle = subtitle,
                selectedValue = selectedValue,
                options = options,
                isTablet = true,
                onSelected = { value ->
                    onSelected(value)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun <T> TrackingPickerContent(
    title: String,
    subtitle: String,
    selectedValue: T,
    options: List<TrackingPickerOption<T>>,
    isTablet: Boolean,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val horizontalPadding = if (isTablet) tokens.spacing.dialogPadding else tokens.spacing.screenHorizontal
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = if (isTablet) tokens.spacing.dialogPadding else 12.dp,
                    bottom = 12.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            options.forEachIndexed { index, option ->
                if (index > 0) {
                    NuvioBottomSheetDivider()
                }
                TrackingPickerOptionRow(
                    option = option,
                    selected = option.value == selectedValue,
                    isTablet = isTablet,
                    onClick = { onSelected(option.value) },
                )
            }
        }

        if (isTablet) {
            DialogButtons(
                modifier = Modifier.padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    bottom = horizontalPadding,
                ),
            ) {
                DialogButton(
                    text = stringResource(Res.string.action_close),
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun <T> TrackingPickerOptionRow(
    option: TrackingPickerOption<T>,
    selected: Boolean,
    isTablet: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val description = listOfNotNull(
        option.description?.takeIf(String::isNotBlank),
        option.unavailableReason?.takeIf(String::isNotBlank),
    ).joinToString("\n").takeIf(String::isNotBlank)
    SettingsNavigationRow(
        title = option.title,
        description = description,
        enabled = option.enabled,
        isTablet = isTablet,
        trailingContent = {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(Res.string.cd_selected),
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        onClick = onClick,
    )
}
