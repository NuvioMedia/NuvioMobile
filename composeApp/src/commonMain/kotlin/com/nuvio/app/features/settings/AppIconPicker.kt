package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.SurfaceEdge
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_ok
import nuvio.composeapp.generated.resources.settings_appearance_app_icon_android_confirmation_message
import nuvio.composeapp.generated.resources.settings_appearance_app_icon_android_confirmation_title
import nuvio.composeapp.generated.resources.settings_appearance_app_icon_change_failed
import nuvio.composeapp.generated.resources.settings_appearance_app_icon_sheet_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AppIconPicker(
    isTablet: Boolean,
    state: AppIconSettingsState,
    onSelected: (AppIconOption) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmationIcon by remember { mutableStateOf<AppIconOption?>(null) }
    val requestSelection: (AppIconOption) -> Unit = { icon ->
        if (icon != state.selected) {
            if (AppIconPlatform.requiresCloseConfirmation) {
                confirmationIcon = icon
            } else {
                onSelected(icon)
            }
        }
    }

    if (isTablet) {
        AppIconPickerDialog(
            state = state,
            onSelected = requestSelection,
            onDismiss = onDismiss,
        )
    } else {
        AppIconPickerBottomSheet(
            state = state,
            onSelected = requestSelection,
            onDismiss = onDismiss,
        )
    }

    confirmationIcon?.let { icon ->
        NuvioStatusModal(
            title = stringResource(Res.string.settings_appearance_app_icon_android_confirmation_title),
            message = stringResource(
                Res.string.settings_appearance_app_icon_android_confirmation_message,
                stringResource(icon.labelResource),
            ),
            isVisible = true,
            confirmText = stringResource(Res.string.action_ok),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                confirmationIcon = null
                onSelected(icon)
            },
            onDismiss = { confirmationIcon = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppIconPickerBottomSheet(
    state: AppIconSettingsState,
    onSelected: (AppIconOption) -> Unit,
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
        AppIconPickerContent(
            state = state,
            columns = 2,
            onSelected = onSelected,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppIconPickerDialog(
    state: AppIconSettingsState,
    onSelected: (AppIconOption) -> Unit,
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
            AppIconPickerContent(
                state = state,
                columns = 3,
                onSelected = onSelected,
            )
        }
    }
}

@Composable
private fun AppIconPickerContent(
    state: AppIconSettingsState,
    columns: Int,
    onSelected: (AppIconOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val horizontalPadding = if (columns == 4) tokens.spacing.dialogPadding else tokens.spacing.screenHorizontal
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 720.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = horizontalPadding, top = 10.dp, end = horizontalPadding, bottom = 20.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_appearance_app_icon_sheet_title),
            style = MaterialTheme.typography.headlineSmall,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )

        if (state.changeFailed) {
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = tokens.shapes.compactCard,
                color = tokens.colors.danger.copy(alpha = 0.12f),
            ) {
                Text(
                    text = stringResource(Res.string.settings_appearance_app_icon_change_failed),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.danger,
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIconOption.entries.chunked(columns).forEach { rowIcons ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowIcons.forEach { icon ->
                        AppIconChoice(
                            icon = icon,
                            selected = state.selected == icon,
                            pending = state.pending == icon,
                            enabled = state.pending == null,
                            onClick = { onSelected(icon) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowIcons.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIconChoice(
    icon: AppIconOption,
    selected: Boolean,
    pending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val label = stringResource(icon.labelResource)
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        enabled = enabled,
        shape = tokens.shapes.compactCard,
        color = if (selected) tokens.colors.overlaySelected else tokens.colors.surfaceCard,
        border = BorderStroke(
            width = if (selected) tokens.borders.medium else tokens.borders.hairline,
            color = if (selected) tokens.colors.borderSelected else tokens.colors.borderSubtle,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                AppIconThumbnail(
                    icon = icon,
                    modifier = Modifier.size(78.dp),
                )
                if (selected || pending) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(24.dp),
                        shape = CircleShape,
                        color = tokens.colors.accent,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (pending) {
                                NuvioLoadingIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = tokens.colors.onAccent,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = tokens.colors.onAccent,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(9.dp))
            Text(
                text = label,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) tokens.colors.textPrimary else tokens.colors.textSecondary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
internal fun AppIconThumbnail(
    icon: AppIconOption,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    cornerRadius: Dp = 18.dp,
) {
    val tokens = MaterialTheme.nuvio
    Image(
        painter = painterResource(icon.previewResource),
        contentDescription = contentDescription,
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(cornerRadius))
            .border(
                width = tokens.borders.hairline,
                color = tokens.colors.borderSubtle,
                shape = RoundedCornerShape(cornerRadius),
            ),
        contentScale = ContentScale.Fit,
    )
}
