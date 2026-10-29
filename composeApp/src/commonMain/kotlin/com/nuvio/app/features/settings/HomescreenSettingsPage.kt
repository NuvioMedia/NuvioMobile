package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.home.HomeAddonLayoutItem
import com.nuvio.app.features.home.HomeAddonLayoutMode
import com.nuvio.app.features.home.HomeAddonRowOrderPolicy
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeCatalogRowPlacement
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.action_reset
import nuvio.composeapp.generated.resources.layout_hide_unreleased
import nuvio.composeapp.generated.resources.layout_hide_unreleased_sub
import nuvio.composeapp.generated.resources.layout_catalog_type
import nuvio.composeapp.generated.resources.layout_catalog_type_sub
import nuvio.composeapp.generated.resources.settings_homescreen_addon_groups
import nuvio.composeapp.generated.resources.settings_homescreen_addon_groups_description
import nuvio.composeapp.generated.resources.settings_homescreen_group_mode_bottom
import nuvio.composeapp.generated.resources.settings_homescreen_group_mode_locked
import nuvio.composeapp.generated.resources.settings_homescreen_group_mode_normal
import nuvio.composeapp.generated.resources.settings_homescreen_group_mode_top
import nuvio.composeapp.generated.resources.settings_homescreen_group_rows_count
import nuvio.composeapp.generated.resources.settings_homescreen_empty_message
import nuvio.composeapp.generated.resources.settings_homescreen_empty_title
import nuvio.composeapp.generated.resources.settings_homescreen_keep_home_focused
import nuvio.composeapp.generated.resources.settings_homescreen_limit_reached
import nuvio.composeapp.generated.resources.settings_homescreen_load_failed_title
import nuvio.composeapp.generated.resources.settings_homescreen_no_sources_selected
import nuvio.composeapp.generated.resources.settings_homescreen_pin_to_move_toast
import nuvio.composeapp.generated.resources.settings_homescreen_section_catalogs
import nuvio.composeapp.generated.resources.settings_homescreen_section_catalogs_collections
import nuvio.composeapp.generated.resources.settings_homescreen_section_collections
import nuvio.composeapp.generated.resources.settings_homescreen_section_hidden
import nuvio.composeapp.generated.resources.settings_homescreen_hidden_rows_count
import nuvio.composeapp.generated.resources.settings_homescreen_hidden_rows_show
import nuvio.composeapp.generated.resources.settings_homescreen_hidden_rows_hide
import nuvio.composeapp.generated.resources.settings_homescreen_no_visible_rows
import nuvio.composeapp.generated.resources.settings_homescreen_addon_catalogs_toggle
import nuvio.composeapp.generated.resources.settings_homescreen_section_hero
import nuvio.composeapp.generated.resources.settings_homescreen_section_hero_sources
import nuvio.composeapp.generated.resources.settings_homescreen_selected_count
import nuvio.composeapp.generated.resources.settings_homescreen_show_hero
import nuvio.composeapp.generated.resources.settings_homescreen_show_hero_description
import nuvio.composeapp.generated.resources.settings_homescreen_summary
import nuvio.composeapp.generated.resources.settings_homescreen_summary_hint
import nuvio.composeapp.generated.resources.settings_homescreen_addon_row_order
import nuvio.composeapp.generated.resources.settings_homescreen_addon_row_order_description
import nuvio.composeapp.generated.resources.settings_homescreen_addon_order_edit
import nuvio.composeapp.generated.resources.settings_homescreen_addon_order_cancel
import nuvio.composeapp.generated.resources.settings_homescreen_addon_order_save
import nuvio.composeapp.generated.resources.settings_homescreen_addon_order_reorder
import nuvio.composeapp.generated.resources.settings_homescreen_addon_order_policy_title
import nuvio.composeapp.generated.resources.settings_homescreen_addon_order_all_title
import nuvio.composeapp.generated.resources.settings_homescreen_addon_order_all_description
import nuvio.composeapp.generated.resources.settings_homescreen_addon_order_unpinned_title
import nuvio.composeapp.generated.resources.settings_homescreen_addon_order_unpinned_description
import nuvio.composeapp.generated.resources.settings_homescreen_addon_order_upcoming_title
import nuvio.composeapp.generated.resources.settings_homescreen_addon_order_upcoming_description
import nuvio.composeapp.generated.resources.settings_homescreen_addon_order_reset_title
import nuvio.composeapp.generated.resources.settings_homescreen_addon_order_reset_message
import nuvio.composeapp.generated.resources.settings_homescreen_addon_order_reset_confirm
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState


internal fun LazyListScope.homescreenSettingsContent(
    isTablet: Boolean,
    heroEnabled: Boolean,
    showCatalogType: Boolean,
    hideUnreleasedContent: Boolean,
    items: List<HomeCatalogSettingsItem>,
    addonGroups: List<HomeAddonLayoutItem>,
    addonRowOrderPolicy: HomeAddonRowOrderPolicy,
    isCatalogLoading: Boolean,
    catalogErrorMessage: String?,
) {
    val selectedHeroSourceCount = items.count { it.heroSourceEnabled }
    val enabledCatalogCount = items.count { it.isVisible }
    item {
        HomescreenSummaryCard(
            isTablet = isTablet,
            enabledCatalogCount = enabledCatalogCount,
            totalCatalogCount = items.size,
            selectedHeroSourceCount = selectedHeroSourceCount,
        )
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_homescreen_section_hero),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_homescreen_show_hero),
                    description = stringResource(Res.string.settings_homescreen_show_hero_description),
                    checked = heroEnabled,
                    isTablet = isTablet,
                    onCheckedChange = HomeCatalogSettingsRepository::setHeroEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.layout_catalog_type),
                    description = stringResource(Res.string.layout_catalog_type_sub),
                    checked = showCatalogType,
                    isTablet = isTablet,
                    onCheckedChange = HomeCatalogSettingsRepository::setShowCatalogType,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.layout_hide_unreleased),
                    description = stringResource(Res.string.layout_hide_unreleased_sub),
                    checked = hideUnreleasedContent,
                    isTablet = isTablet,
                    onCheckedChange = HomeCatalogSettingsRepository::setHideUnreleasedContent,
                )
            }
        }
    }
    item {
        val catalogOnlyItems = items.filter { !it.isCollection }
        if (heroEnabled && catalogOnlyItems.isNotEmpty()) {
            var heroSourcesExpanded by remember { mutableStateOf(false) }
            SettingsSection(
                title = stringResource(Res.string.settings_homescreen_section_hero_sources),
                isTablet = isTablet,
            ) {
                HeroSourcesDropdown(
                    isTablet = isTablet,
                    items = catalogOnlyItems,
                    selectedHeroSourceCount = selectedHeroSourceCount,
                    expanded = heroSourcesExpanded,
                    onExpandedChange = { heroSourcesExpanded = it },
                )
            }
        }
    }
    item {
        if (addonGroups.isNotEmpty()) {
            AddonRowOrderSection(
                isTablet = isTablet,
                addonGroups = addonGroups,
                savedPolicy = addonRowOrderPolicy,
            )
        }
    }
    item {
        if (isCatalogLoading && items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                NuvioLoadingIndicator(modifier = Modifier.size(28.dp))
            }
        } else if (catalogErrorMessage != null && items.isEmpty()) {
            HomeEmptyStateCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(Res.string.settings_homescreen_load_failed_title),
                message = catalogErrorMessage,
                actionLabel = stringResource(Res.string.action_retry),
                onActionClick = AddonRepository::refreshAll,
            )
        } else if (items.isEmpty()) {
            HomeEmptyStateCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(Res.string.settings_homescreen_empty_title),
                message = stringResource(Res.string.settings_homescreen_empty_message),
            )
        } else {
            val visibleItems = items.filter { it.isVisible }
            val hiddenItems = items.filterNot { it.isVisible }
            val titleItems = visibleItems.ifEmpty { items }
            val catalogCount = titleItems.count { !it.isCollection }
            val collectionCount = titleItems.count { it.isCollection }
            val sectionTitle = when {
                collectionCount > 0 && catalogCount > 0 -> stringResource(Res.string.settings_homescreen_section_catalogs_collections)
                collectionCount > 0 -> stringResource(Res.string.settings_homescreen_section_collections)
                else -> stringResource(Res.string.settings_homescreen_section_catalogs)
            }
            var hiddenExpanded by remember { mutableStateOf(false) }
            SettingsSection(
                title = sectionTitle,
                isTablet = isTablet,
                actions = {
                    NuvioActionLabel(
                        text = stringResource(Res.string.action_reset),
                        onClick = HomeCatalogSettingsRepository::resetToDefaults,
                    )
                },
            ) {
                if (visibleItems.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.settings_homescreen_no_visible_rows),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                } else {
                    val hapticFeedback = LocalHapticFeedback.current
                    val pinToMoveToast = stringResource(Res.string.settings_homescreen_pin_to_move_toast)
                    HomescreenCatalogList(
                        isTablet = isTablet,
                        items = visibleItems,
                        onPinnedDragAttempt = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            NuvioToastController.show(pinToMoveToast)
                        },
                    )
                }
            }
            if (hiddenItems.isNotEmpty()) {
                SettingsSection(
                    title = stringResource(Res.string.settings_homescreen_section_hidden),
                    isTablet = isTablet,
                    actions = {
                        NuvioActionLabel(
                            text = stringResource(
                                if (hiddenExpanded) Res.string.settings_homescreen_hidden_rows_hide
                                else Res.string.settings_homescreen_hidden_rows_show,
                            ),
                            onClick = { hiddenExpanded = !hiddenExpanded },
                        )
                    },
                ) {
                    if (hiddenExpanded) {
                        val hapticFeedback = LocalHapticFeedback.current
                        val pinToMoveToast = stringResource(Res.string.settings_homescreen_pin_to_move_toast)
                        HomescreenCatalogList(
                            isTablet = isTablet,
                            items = hiddenItems,
                            onPinnedDragAttempt = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                NuvioToastController.show(pinToMoveToast)
                            },
                        )
                    } else {
                        SettingsGroup(isTablet = isTablet) {
                            Text(
                                text = stringResource(Res.string.settings_homescreen_hidden_rows_count, hiddenItems.size),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun AddonRowOrderSection(
    isTablet: Boolean,
    addonGroups: List<HomeAddonLayoutItem>,
    savedPolicy: HomeAddonRowOrderPolicy,
) {
    var editing by remember { mutableStateOf(false) }
    var draftAddonIds by remember { mutableStateOf(emptyList<String>()) }
    var draftPolicy by remember(savedPolicy) { mutableStateOf(savedPolicy) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val groupById = addonGroups.associateBy { it.addonId }
    val orderIds = if (editing) {
        draftAddonIds.filter { it in groupById } +
            addonGroups.map { it.addonId }.filterNot { it in draftAddonIds }
    } else {
        addonGroups.map { it.addonId }
    }
    val orderedGroups = orderIds.mapNotNull(groupById::get)
    val commitDraftOrder: () -> Unit = {
        HomeCatalogSettingsRepository.saveAddonRowOrder(
            addonIds = orderedGroups.map { it.addonId },
            policy = draftPolicy,
        )
        editing = false
        draftAddonIds = emptyList()
    }
    // Keep the reorder callback in sync with draft state; rememberReorderableLazyListState
    // may retain its callback while Compose recomposes the list after each drag.
    val latestOrderIds by rememberUpdatedState(orderIds)
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val currentIds = latestOrderIds.toMutableList()
        if (!editing || from.index !in currentIds.indices ||
            to.index !in currentIds.indices || from.index == to.index
        ) {
            return@rememberReorderableLazyListState
        }
        draftAddonIds = currentIds.apply {
            add(to.index, removeAt(from.index))
        }
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    SettingsSection(
        title = stringResource(Res.string.settings_homescreen_addon_row_order),
        isTablet = isTablet,
        actions = {
            NuvioActionLabel(
                text = stringResource(
                    if (editing) Res.string.settings_homescreen_addon_order_cancel
                    else Res.string.settings_homescreen_addon_order_edit,
                ),
                onClick = {
                    if (editing) {
                        editing = false
                        draftAddonIds = emptyList()
                        draftPolicy = savedPolicy
                    } else {
                        draftAddonIds = addonGroups.map { it.addonId }
                        draftPolicy = savedPolicy
                        editing = true
                    }
                },
            )
        },
    ) {
        SettingsGroup(isTablet = isTablet) {
            Text(
                text = stringResource(Res.string.settings_homescreen_addon_row_order_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (isTablet) 760.dp else 560.dp),
                state = listState,
            ) {
                itemsIndexed(orderedGroups, key = { _, group -> group.addonId }) { index, group ->
                    ReorderableItem(
                        reorderState,
                        key = group.addonId,
                        enabled = editing,
                    ) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)
                        Surface(shadowElevation = elevation) {
                            Column {
                                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = if (isTablet) 20.dp else 16.dp,
                                            vertical = 12.dp,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = group.addonName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = stringResource(
                                                Res.string.settings_homescreen_group_rows_count,
                                                group.catalogCount,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = stringResource(Res.string.settings_homescreen_addon_catalogs_toggle),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Switch(
                                            checked = group.catalogsEnabled,
                                            onCheckedChange = { enabled ->
                                                HomeCatalogSettingsRepository.setAddonCatalogsEnabled(group.addonId, enabled)
                                            },
                                        )
                                    }
                                    if (editing) {
                                        IconButton(
                                            modifier = with(this@ReorderableItem) {
                                                Modifier.draggableHandle(
                                                    onDragStarted = {
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    },
                                                    onDragStopped = {
                                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    },
                                                )
                                            },
                                            onClick = {},
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Menu,
                                                contentDescription = stringResource(
                                                    Res.string.settings_homescreen_addon_order_reorder,
                                                ),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (editing) {
                SettingsGroupDivider(isTablet = isTablet)
                Text(
                    text = stringResource(Res.string.settings_homescreen_addon_order_policy_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                AddonRowOrderPolicyOption(
                    isTablet = isTablet,
                    selected = draftPolicy == HomeAddonRowOrderPolicy.MOVE_ALL_ROWS,
                    title = stringResource(Res.string.settings_homescreen_addon_order_all_title),
                    description = stringResource(Res.string.settings_homescreen_addon_order_all_description),
                    onSelected = { draftPolicy = HomeAddonRowOrderPolicy.MOVE_ALL_ROWS },
                )
                AddonRowOrderPolicyOption(
                    isTablet = isTablet,
                    selected = draftPolicy == HomeAddonRowOrderPolicy.MOVE_UNPINNED_ROWS,
                    title = stringResource(Res.string.settings_homescreen_addon_order_unpinned_title),
                    description = stringResource(Res.string.settings_homescreen_addon_order_unpinned_description),
                    onSelected = { draftPolicy = HomeAddonRowOrderPolicy.MOVE_UNPINNED_ROWS },
                )
                AddonRowOrderPolicyOption(
                    isTablet = isTablet,
                    selected = draftPolicy == HomeAddonRowOrderPolicy.UPCOMING_ROWS_ONLY,
                    title = stringResource(Res.string.settings_homescreen_addon_order_upcoming_title),
                    description = stringResource(Res.string.settings_homescreen_addon_order_upcoming_description),
                    onSelected = { draftPolicy = HomeAddonRowOrderPolicy.UPCOMING_ROWS_ONLY },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            editing = false
                            draftAddonIds = emptyList()
                            draftPolicy = savedPolicy
                        },
                    ) {
                        Text(stringResource(Res.string.settings_homescreen_addon_order_cancel))
                    }
                    Button(
                        onClick = {
                            if (draftPolicy == HomeAddonRowOrderPolicy.MOVE_ALL_ROWS) {
                                showResetConfirmation = true
                            } else {
                                commitDraftOrder()
                            }
                        },
                    ) {
                        Text(stringResource(Res.string.settings_homescreen_addon_order_save))
                    }
                }
            }
            if (showResetConfirmation) {
                AlertDialog(
                    onDismissRequest = { showResetConfirmation = false },
                    title = {
                        Text(stringResource(Res.string.settings_homescreen_addon_order_reset_title))
                    },
                    text = {
                        Text(stringResource(Res.string.settings_homescreen_addon_order_reset_message))
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showResetConfirmation = false
                                commitDraftOrder()
                            },
                        ) {
                            Text(stringResource(Res.string.settings_homescreen_addon_order_reset_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetConfirmation = false }) {
                            Text(stringResource(Res.string.settings_homescreen_addon_order_cancel))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AddonRowOrderPolicyOption(
    isTablet: Boolean,
    selected: Boolean,
    title: String,
    description: String,
    onSelected: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelected)
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelected)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeroSourcesDropdown(
    isTablet: Boolean,
    items: List<HomeCatalogSettingsItem>,
    selectedHeroSourceCount: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val noSourcesSelected = stringResource(Res.string.settings_homescreen_no_sources_selected)
    SettingsGroup(isTablet = isTablet) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clickable { onExpandedChange(!expanded) },
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(
                        Res.string.settings_homescreen_selected_count,
                        selectedHeroSourceCount,
                        HomeCatalogSettingsRepository.HERO_SOURCE_SELECTION_LIMIT,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = items.filter { it.heroSourceEnabled }
                        .joinToString(separator = ", ") { it.displayTitle }
                        .ifBlank { noSourcesSelected },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                SettingsGroupDivider(isTablet = isTablet)
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        SettingsGroupDivider(isTablet = isTablet)
                    }
                    SettingsSwitchRow(
                        title = item.displayTitle,
                        description = if (!item.heroSourceEnabled &&
                            selectedHeroSourceCount >= HomeCatalogSettingsRepository.HERO_SOURCE_SELECTION_LIMIT
                        ) {
                            stringResource(
                                Res.string.settings_homescreen_limit_reached,
                                item.addonName,
                                HomeCatalogSettingsRepository.HERO_SOURCE_SELECTION_LIMIT,
                            )
                        } else {
                            item.addonName
                        },
                        checked = item.heroSourceEnabled,
                        enabled = item.heroSourceEnabled ||
                            selectedHeroSourceCount < HomeCatalogSettingsRepository.HERO_SOURCE_SELECTION_LIMIT,
                        isTablet = isTablet,
                        onCheckedChange = { HomeCatalogSettingsRepository.setHeroSourceEnabled(item.key, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomescreenSummaryCard(
    isTablet: Boolean,
    enabledCatalogCount: Int,
    totalCatalogCount: Int,
    selectedHeroSourceCount: Int,
) {
    SettingsGroup(isTablet = isTablet) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(Res.string.settings_homescreen_keep_home_focused),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    Res.string.settings_homescreen_summary,
                    enabledCatalogCount,
                    totalCatalogCount,
                    selectedHeroSourceCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.settings_homescreen_summary_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomescreenCatalogList(
    isTablet: Boolean,
    items: List<HomeCatalogSettingsItem>,
    onPinnedDragAttempt: () -> Unit,
) {
    var expandedKey by remember { mutableStateOf<String?>(null) }
    val hapticFeedback = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    // Reorderable state can retain its callback across recompositions. Resolve every
    // pointer update against the list currently rendered by Compose.
    val latestItems by rememberUpdatedState(items)
    var pinnedDragFeedbackShown by remember { mutableStateOf(false) }
    val reorderableLazyListState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
    ) { from, to ->
        val currentItems = latestItems
        if (from.index == to.index) return@rememberReorderableLazyListState
        val fromItem = currentItems.getOrNull(from.index)
            ?: return@rememberReorderableLazyListState
        val toItem = currentItems.getOrNull(to.index)
            ?: return@rememberReorderableLazyListState
        if (!HomeCatalogSettingsRepository.canMoveByKeys(fromItem.key, toItem.key)) {
            if (!pinnedDragFeedbackShown) {
                pinnedDragFeedbackShown = true
                onPinnedDragAttempt()
            }
            return@rememberReorderableLazyListState
        }

        val rangeStart = minOf(from.index, to.index).coerceAtLeast(0)
        val rangeEnd = maxOf(from.index, to.index).coerceAtMost(currentItems.lastIndex)
        val crossesManagedGroup = rangeStart <= rangeEnd && currentItems
            .subList(rangeStart, rangeEnd + 1)
            .any { it.addonId != null && it.addonLayoutMode != HomeAddonLayoutMode.NORMAL }
        val staysInsideSameManagedGroup = fromItem.addonId != null &&
            fromItem.addonId == toItem.addonId &&
            fromItem.addonLayoutMode != HomeAddonLayoutMode.NORMAL &&
            rangeStart <= rangeEnd && currentItems.subList(rangeStart, rangeEnd + 1).all {
                it.addonId == fromItem.addonId && it.addonLayoutMode != HomeAddonLayoutMode.NORMAL
            }
        // Managed dynamic groups can move internally but cannot leave their configured slot.
        if (crossesManagedGroup && !staysInsideSameManagedGroup && !fromItem.isCollection) {
            if (!pinnedDragFeedbackShown) {
                pinnedDragFeedbackShown = true
                onPinnedDragAttempt()
            }
            return@rememberReorderableLazyListState
        }

        HomeCatalogSettingsRepository.moveByKeys(
            fromKey = fromItem.key,
            toKey = toItem.key,
            commitImmediately = false,
        )
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    SettingsGroup(isTablet = isTablet) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (isTablet) 900.dp else 680.dp),
            state = lazyListState,
        ) {
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                ReorderableItem(
                    reorderableLazyListState,
                    key = item.key,
                    enabled = item.rowPlacement == HomeCatalogRowPlacement.NORMAL ||
                        item.rowPlacement == HomeCatalogRowPlacement.PINNED_TOP ||
                        item.rowPlacement == HomeCatalogRowPlacement.PINNED_BOTTOM,
                ) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)

                    Surface(shadowElevation = elevation) {
                        Column {
                            if (index > 0) {
                                SettingsGroupDivider(isTablet = isTablet)
                            }
                            HomescreenCatalogRow(
                                item = item,
                                isTablet = isTablet,
                                expanded = expandedKey == item.key,
                                onExpandedChange = { shouldExpand ->
                                    expandedKey = if (shouldExpand) item.key else null
                                },
                                onTitleChange = { HomeCatalogSettingsRepository.setCustomTitle(item.key, it) },
                                onEnabledChange = { HomeCatalogSettingsRepository.setEnabled(item.key, it) },
                                onRowPlacementChange = { HomeCatalogSettingsRepository.setRowPlacement(item.key, it) },
                                dragHandleScope = this@ReorderableItem,
                                onPinnedDragAttempt = onPinnedDragAttempt,
                                onReorderFinished = {
                                    HomeCatalogSettingsRepository.commitPendingRowOrder()
                                    pinnedDragFeedbackShown = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
