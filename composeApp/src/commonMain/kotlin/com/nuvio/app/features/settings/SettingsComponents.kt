package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioConsumePointerEvents
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_homescreen_collection_with_addon
import nuvio.composeapp.generated.resources.settings_homescreen_display_name
import nuvio.composeapp.generated.resources.settings_homescreen_hero_source
import nuvio.composeapp.generated.resources.settings_homescreen_hidden
import nuvio.composeapp.generated.resources.settings_homescreen_not_in_hero
import nuvio.composeapp.generated.resources.settings_homescreen_pinned
import nuvio.composeapp.generated.resources.settings_homescreen_pinned_to_top
import nuvio.composeapp.generated.resources.settings_homescreen_reorder
import nuvio.composeapp.generated.resources.settings_homescreen_visible
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableCollectionItemScope

@Composable
private fun SettingsCard(
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = tokens.colors.surface,
        shape = if (isTablet) RoundedCornerShape(NuvioTokens.Radius.xl) else tokens.shapes.compactCard,
        border = BorderStroke(
            tokens.borders.hairline,
            tokens.colors.borderSubtle,
        ),
    ) {
        Column(content = content)
    }
}

@Composable
internal fun SettingsGroup(
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsCard(
        isTablet = isTablet,
        modifier = modifier,
    ) {
        Column(content = content)
    }
}

@Composable
internal fun SettingsGroupDivider(isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    HorizontalDivider(
        modifier = Modifier.padding(start = if (isTablet) NuvioTokens.Space.s80 - NuvioTokens.Space.s2 else NuvioTokens.Space.s64 + NuvioTokens.Space.s2),
        thickness = tokens.borders.hairline,
        color = tokens.colors.borderSubtle,
    )
}

@Composable
internal fun TabletPageHeader(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .nuvioConsumePointerEvents(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
        ) {
            if (showBack) {
                NuvioBackButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(36.dp),
                    shape = tokens.shapes.compactCard,
                    containerColor = tokens.colors.surface,
                    contentColor = tokens.colors.textPrimary,
                    buttonSize = NuvioTokens.Space.s36,
                    iconSize = tokens.icons.md,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun SettingsSidebarItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val primary = tokens.colors.accent
    val background = if (selected) primary.copy(alpha = tokens.opacity.hover) else Color.Transparent
    val iconChip = if (selected) primary.copy(alpha = tokens.opacity.selected) else Color.Transparent
    val contentColor = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.listGap, vertical = NuvioTokens.Space.s2)
            .background(background, RoundedCornerShape(NuvioTokens.Space.s10))
            .clickable(onClick = onClick)
            .padding(horizontal = tokens.spacing.screenHorizontal, vertical = tokens.spacing.listGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(tokens.icons.xl),
            color = iconChip,
            shape = RoundedCornerShape(NuvioTokens.Radius.md),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) primary else contentColor,
                )
            }
        }
        Spacer(modifier = Modifier.width(tokens.spacing.listGap))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
internal fun SettingsSection(
    title: String,
    isTablet: Boolean,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioSectionLabel(text = title)
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
        Spacer(modifier = Modifier.height(if (isTablet) tokens.spacing.listGap else NuvioTokens.Space.s10))
        content()
    }
}

@Composable
internal fun SettingsNavigationRow(
    title: String,
    description: String?,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    enabled: Boolean = true,
    isTablet: Boolean,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val iconSize = if (isTablet) 42.dp else 36.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    val horizontalPadding = if (isTablet) 20.dp else 16.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
            .alpha(if (enabled) NuvioTokens.Opacity.visible else tokens.opacity.medium),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
                .widthIn(max = if (isTablet) 560.dp else Dp.Unspecified),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null || iconPainter != null) {
                Surface(
                    modifier = Modifier.size(iconSize),
                    color = tokens.colors.accent.copy(alpha = tokens.opacity.pressed),
                    shape = tokens.shapes.compactCard,
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (iconPainter != null) {
                            androidx.compose.foundation.Image(
                                painter = iconPainter,
                                contentDescription = null,
                                modifier = Modifier.size(if (isTablet) 28.dp else 24.dp),
                                contentScale = ContentScale.Fit,
                            )
                        } else if (icon != null) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = tokens.colors.accent,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(if (isTablet) 16.dp else 14.dp))
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                )
                if (!description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                        modifier = Modifier.alpha(0.92f),
                    )
                }
            }
        }
        trailingContent?.invoke(this)
    }
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    isTablet: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    val horizontalPadding = if (isTablet) 20.dp else 16.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
                .widthIn(max = if (isTablet) 560.dp else Dp.Unspecified)
                .alpha(if (enabled) NuvioTokens.Opacity.visible else tokens.opacity.medium),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.padding(start = 4.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = tokens.colors.onAccent,
                checkedTrackColor = tokens.colors.accent,
                uncheckedThumbColor = tokens.colors.textMuted,
                uncheckedTrackColor = tokens.colors.borderDefault,
            ),
        )
    }
}

@Composable
internal fun HomescreenCatalogRow(
    item: HomeCatalogSettingsItem,
    isTablet: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onTitleChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    dragHandleScope: ReorderableCollectionItemScope,
    onPinnedDragAttempt: () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 18.dp else 16.dp
    val hapticFeedback = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpandedChange(!expanded) }
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
                    .then(if (isTablet) Modifier.widthIn(max = 560.dp) else Modifier),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (item.isCollection) {
                        stringResource(Res.string.settings_homescreen_collection_with_addon, item.addonName)
                    } else {
                        item.addonName
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
                Text(
                    text = buildString {
                        append(
                            if (item.enabled) {
                                stringResource(Res.string.settings_homescreen_visible)
                            } else {
                                stringResource(Res.string.settings_homescreen_hidden)
                            },
                        )
                        if (item.isCollection) {
                            if (item.isPinnedToTop) {
                                append(" • ")
                                append(stringResource(Res.string.settings_homescreen_pinned_to_top))
                            }
                        } else {
                            append(" • ")
                            append(
                                if (item.heroSourceEnabled) {
                                    stringResource(Res.string.settings_homescreen_hero_source)
                                } else {
                                    stringResource(Res.string.settings_homescreen_not_in_hero)
                                },
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(
                    checked = item.enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = tokens.colors.onAccent,
                        checkedTrackColor = tokens.colors.accent,
                        uncheckedThumbColor = tokens.colors.textMuted,
                        uncheckedTrackColor = tokens.colors.borderDefault,
                    ),
                )
                if (item.isPinnedToTop) {
                    IconButton(
                        onClick = onPinnedDragAttempt,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = stringResource(Res.string.settings_homescreen_pinned),
                            tint = tokens.colors.textMuted.copy(alpha = tokens.opacity.medium),
                        )
                    }
                } else {
                    IconButton(
                        modifier = with(dragHandleScope) {
                            Modifier.draggableHandle(
                                onDragStarted = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragStopped = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                            )
                        },
                        onClick = {},
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Menu,
                            contentDescription = stringResource(Res.string.settings_homescreen_reorder),
                            tint = tokens.colors.textMuted,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = expanded && !item.isCollection) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = item.customTitle,
                    onValueChange = onTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(Res.string.settings_homescreen_display_name)) },
                    placeholder = { Text(item.defaultTitle) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = tokens.colors.borderFocus.copy(alpha = tokens.opacity.strong),
                        unfocusedBorderColor = tokens.colors.borderDefault.copy(alpha = tokens.opacity.medium),
                        focusedContainerColor = tokens.colors.surface,
                        unfocusedContainerColor = tokens.colors.surface,
                        disabledContainerColor = tokens.colors.surface,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun SettingsDialogSurface(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = tokens.shapes.dialog,
        color = tokens.colors.surfaceDialog,
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.dialogPadding),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            content()
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s2))
        }
    }
}
