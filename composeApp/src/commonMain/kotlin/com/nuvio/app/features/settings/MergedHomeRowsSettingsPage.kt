package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListScope
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsUiState
import com.nuvio.app.features.home.MergedHomeRowDefinition
import com.nuvio.app.features.home.MergedHomeRowContentType
import com.nuvio.app.features.home.MergedHomeRowDisplayMode
import com.nuvio.app.features.home.MergedHomeRowBlendMode
import com.nuvio.app.features.home.MergedHomeRowSortMode
import com.nuvio.app.features.home.mergedHomeRowKey
import com.nuvio.app.features.home.cleanMergedSourceTabTitle
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_merged_rows_add
import nuvio.composeapp.generated.resources.settings_merged_rows_cancel
import nuvio.composeapp.generated.resources.settings_merged_rows_create_title
import nuvio.composeapp.generated.resources.settings_merged_rows_delete
import nuvio.composeapp.generated.resources.settings_merged_rows_delete_message
import nuvio.composeapp.generated.resources.settings_merged_rows_delete_title
import nuvio.composeapp.generated.resources.settings_merged_rows_deduplicate
import nuvio.composeapp.generated.resources.settings_merged_rows_description
import nuvio.composeapp.generated.resources.settings_merged_rows_duplicate
import nuvio.composeapp.generated.resources.settings_merged_rows_display_mode
import nuvio.composeapp.generated.resources.settings_merged_rows_edit
import nuvio.composeapp.generated.resources.settings_merged_rows_edit_title
import nuvio.composeapp.generated.resources.settings_merged_rows_empty
import nuvio.composeapp.generated.resources.settings_merged_rows_mode_tabs
import nuvio.composeapp.generated.resources.settings_merged_rows_mode_type_tabs
import nuvio.composeapp.generated.resources.settings_merged_rows_mode_type_tabs_description
import nuvio.composeapp.generated.resources.settings_merged_rows_mode_unified
import nuvio.composeapp.generated.resources.settings_merged_rows_name
import nuvio.composeapp.generated.resources.settings_merged_rows_save
import nuvio.composeapp.generated.resources.settings_merged_rows_select_sources
import nuvio.composeapp.generated.resources.settings_merged_rows_source_limit
import nuvio.composeapp.generated.resources.settings_merged_rows_title
import nuvio.composeapp.generated.resources.settings_merged_rows_type
import nuvio.composeapp.generated.resources.settings_merged_rows_type_all
import nuvio.composeapp.generated.resources.settings_merged_rows_type_movies
import nuvio.composeapp.generated.resources.settings_merged_rows_type_series
import nuvio.composeapp.generated.resources.settings_merged_rows_unavailable_source
import nuvio.composeapp.generated.resources.settings_merged_rows_blend
import nuvio.composeapp.generated.resources.settings_merged_rows_blend_interleave
import nuvio.composeapp.generated.resources.settings_merged_rows_blend_smart
import nuvio.composeapp.generated.resources.settings_merged_rows_blend_interleave_description
import nuvio.composeapp.generated.resources.settings_merged_rows_blend_smart_description
import nuvio.composeapp.generated.resources.settings_merged_rows_hide_included
import nuvio.composeapp.generated.resources.settings_merged_rows_hide_without_posters
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.mergedHomeRowsSettingsContent(
    isTablet: Boolean,
    uiState: HomeCatalogSettingsUiState,
) {
    item(key = "merged_home_rows_settings") {
        MergedHomeRowsEditor(
            isTablet = isTablet,
            uiState = uiState,
        )
    }
}

@Composable
private fun MergedHomeRowsEditor(
    isTablet: Boolean,
    uiState: HomeCatalogSettingsUiState,
) {
    val availableSources = remember(uiState.items) {
        uiState.items
            .filter { it.addonId != null && !it.isCollection && !it.isMergedRow }
            .sortedWith(compareBy<HomeCatalogSettingsItem> { it.addonName.lowercase() }
                .thenBy { it.displayTitle.lowercase() })
    }
    val availableKeys = remember(availableSources) { availableSources.mapTo(mutableSetOf()) { it.key } }
    val orderedRows = uiState.mergedRows.sortedBy { it.order }
    val rowItems = remember(uiState.items) { uiState.items.associateBy { it.key } }

    var editorOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var draftTitle by remember { mutableStateOf("") }
    var draftSourceKeys by remember { mutableStateOf(setOf<String>()) }
    var draftMode by remember { mutableStateOf(MergedHomeRowDisplayMode.SOURCE_TABS) }
    var draftBlendMode by remember { mutableStateOf(MergedHomeRowBlendMode.SMART_FRESHNESS_POPULARITY) }
    var draftContentType by remember { mutableStateOf(MergedHomeRowContentType.ALL) }
    var draftHideIncludedRows by remember { mutableStateOf(false) }
    var draftDeduplicate by remember { mutableStateOf(true) }
    var deleteTarget by remember { mutableStateOf<MergedHomeRowDefinition?>(null) }

    fun startCreate() {
        editingId = null
        draftTitle = ""
        draftSourceKeys = emptySet()
        draftMode = MergedHomeRowDisplayMode.SOURCE_TABS
        draftContentType = MergedHomeRowContentType.ALL
        draftBlendMode = MergedHomeRowBlendMode.SMART_FRESHNESS_POPULARITY
        draftHideIncludedRows = false
        draftDeduplicate = true
        editorOpen = true
    }

    fun startEdit(row: MergedHomeRowDefinition) {
        editingId = row.id
        draftTitle = row.title
        draftSourceKeys = row.sourceKeys.toSet()
        draftMode = row.resolvedDisplayMode()
        draftContentType = if (row.resolvedDisplayMode() == MergedHomeRowDisplayMode.TYPE_TABS) {
            MergedHomeRowContentType.ALL
        } else {
            row.resolvedContentType()
        }
        draftBlendMode = row.resolvedBlendMode()
        draftHideIncludedRows = row.hideIncludedRows
        draftDeduplicate = row.deduplicate
        editorOpen = true
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 24.dp else 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_merged_rows_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(onClick = ::startCreate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.settings_merged_rows_add))
        }

        if (orderedRows.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = stringResource(Res.string.settings_merged_rows_empty),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            orderedRows.forEachIndexed { index, row ->
                val homeItem = rowItems[mergedHomeRowKey(row.id)]
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.title, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "${row.sourceKeys.size} catalogs · ${stringResource(when (row.resolvedDisplayMode()) {
                                        MergedHomeRowDisplayMode.SOURCE_TABS -> Res.string.settings_merged_rows_mode_tabs
                                        MergedHomeRowDisplayMode.TYPE_TABS -> Res.string.settings_merged_rows_mode_type_tabs
                                        MergedHomeRowDisplayMode.UNIFIED_CAROUSEL -> Res.string.settings_merged_rows_mode_unified
                                    })}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = homeItem?.enabled != false,
                                onCheckedChange = { enabled ->
                                    HomeCatalogSettingsRepository.setEnabled(mergedHomeRowKey(row.id), enabled)
                                },
                            )
                        }
                        Text(
                            text = row.sourceKeys.joinToString(" · ") { key ->
                                availableSources.firstOrNull { it.key == key }?.displayTitle?.let(::cleanMergedSourceTabTitle) ?: key
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { startEdit(row) }) {
                                Text(stringResource(Res.string.settings_merged_rows_edit))
                            }
                            TextButton(onClick = {
                                HomeCatalogSettingsRepository.duplicateMergedRow(row.id)
                            }) {
                                Text(stringResource(Res.string.settings_merged_rows_duplicate))
                            }
                            TextButton(onClick = { deleteTarget = row }) {
                                Text(stringResource(Res.string.settings_merged_rows_delete))
                            }
                        }
                    }
                }
            }
        }
    }

    if (editorOpen) {
        val validSelectedCount = draftSourceKeys.count { it in availableKeys }
        val missingSourceKeys = draftSourceKeys.filterNot { it in availableKeys }
        AlertDialog(
            onDismissRequest = { editorOpen = false },
            title = {
                Text(
                    stringResource(
                        if (editingId == null) Res.string.settings_merged_rows_create_title
                        else Res.string.settings_merged_rows_edit_title,
                    ),
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = draftTitle,
                        onValueChange = { draftTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(Res.string.settings_merged_rows_name)) },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draftDeduplicate,
                            onCheckedChange = { draftDeduplicate = it },
                        )
                        Text(
                            stringResource(Res.string.settings_merged_rows_deduplicate),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(stringResource(Res.string.settings_merged_rows_display_mode), style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = draftMode == MergedHomeRowDisplayMode.UNIFIED_CAROUSEL,
                            onClick = { draftMode = MergedHomeRowDisplayMode.UNIFIED_CAROUSEL },
                        )
                        Text(
                            stringResource(Res.string.settings_merged_rows_mode_unified),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = draftMode == MergedHomeRowDisplayMode.SOURCE_TABS,
                            onClick = { draftMode = MergedHomeRowDisplayMode.SOURCE_TABS },
                        )
                        Text(
                            stringResource(Res.string.settings_merged_rows_mode_tabs),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = draftMode == MergedHomeRowDisplayMode.TYPE_TABS,
                            onClick = {
                                draftMode = MergedHomeRowDisplayMode.TYPE_TABS
                                draftContentType = MergedHomeRowContentType.ALL
                            },
                        )
                        Text(
                            stringResource(Res.string.settings_merged_rows_mode_type_tabs),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (draftMode == MergedHomeRowDisplayMode.TYPE_TABS) {
                        Text(
                            text = stringResource(Res.string.settings_merged_rows_mode_type_tabs_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (draftMode != MergedHomeRowDisplayMode.TYPE_TABS) {
                        Text(stringResource(Res.string.settings_merged_rows_type), style = MaterialTheme.typography.titleSmall)
                        listOf(
                            MergedHomeRowContentType.ALL to Res.string.settings_merged_rows_type_all,
                            MergedHomeRowContentType.MOVIES to Res.string.settings_merged_rows_type_movies,
                            MergedHomeRowContentType.SERIES to Res.string.settings_merged_rows_type_series,
                        ).forEach { (type, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = draftContentType == type,
                                    onClick = { draftContentType = type },
                                )
                                Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    Text("Blending method", style = MaterialTheme.typography.titleSmall)
                    listOf(
                        MergedHomeRowBlendMode.INTERLEAVE to "Interleave",
                        MergedHomeRowBlendMode.SMART_FRESHNESS_POPULARITY to "Smart Blend",
                        MergedHomeRowBlendMode.FRESHNESS_WEIGHTED to "Freshness Weighted",
                        MergedHomeRowBlendMode.POPULARITY_WEIGHTED to "Popularity Weighted",
                        MergedHomeRowBlendMode.RATING_WEIGHTED to "Rating Weighted",
                        MergedHomeRowBlendMode.PROVIDER_PRIORITY to "Provider Priority",
                        MergedHomeRowBlendMode.RANDOM to "Random Blend",
                    ).forEach { (mode, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = draftBlendMode == mode, onClick = { draftBlendMode = mode })
                            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(
                        text = when (draftBlendMode) {
                            MergedHomeRowBlendMode.INTERLEAVE -> "Take turns between providers in configured order."
                            MergedHomeRowBlendMode.SMART_FRESHNESS_POPULARITY -> "Balance freshness, popularity, ratings and provider diversity."
                            MergedHomeRowBlendMode.FRESHNESS_WEIGHTED -> "Give newer releases more influence without strict chronological sorting."
                            MergedHomeRowBlendMode.POPULARITY_WEIGHTED -> "Give popular titles more influence while retaining provider diversity."
                            MergedHomeRowBlendMode.RATING_WEIGHTED -> "Give highly rated titles more influence while retaining provider diversity."
                            MergedHomeRowBlendMode.PROVIDER_PRIORITY -> "Respect provider order and take turns between providers."
                            MergedHomeRowBlendMode.RANDOM -> "Shuffle each provider queue, then interleave the results."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = draftHideIncludedRows, onCheckedChange = { draftHideIncludedRows = it })
                        Text(stringResource(Res.string.settings_merged_rows_hide_included), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(stringResource(Res.string.settings_merged_rows_select_sources), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(Res.string.settings_merged_rows_source_limit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    if (missingSourceKeys.isNotEmpty()) {
                        Text(
                            text = stringResource(Res.string.settings_merged_rows_unavailable_source),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        missingSourceKeys.forEach { key ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(key, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { draftSourceKeys = draftSourceKeys - key }) {
                                    Text(stringResource(Res.string.settings_merged_rows_delete))
                                }
                            }
                        }
                    }

                    availableSources.forEach { source ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = source.key in draftSourceKeys,
                                enabled = source.key in draftSourceKeys || draftSourceKeys.size < 8,
                                onCheckedChange = { checked ->
                                    draftSourceKeys = if (checked) draftSourceKeys + source.key else draftSourceKeys - source.key
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(cleanMergedSourceTabTitle(source.displayTitle), style = MaterialTheme.typography.bodyMedium)
                                Text(source.addonName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                }
            },
            confirmButton = {
                TextButton(
                    enabled = draftTitle.isNotBlank() && validSelectedCount >= 2 && validSelectedCount <= 8,
                    onClick = {
                        val saved = editingId?.let { id ->
                            HomeCatalogSettingsRepository.updateMergedRow(
                                id = id,
                                title = draftTitle,
                                sourceKeys = draftSourceKeys.toList(),
                                displayMode = draftMode,
                                deduplicate = draftDeduplicate,
                                contentType = draftContentType,
                                blendMode = draftBlendMode,
                                hideIncludedRows = draftHideIncludedRows,
                            )
                        } ?: (HomeCatalogSettingsRepository.createMergedRow(
                            title = draftTitle,
                            sourceKeys = draftSourceKeys.toList(),
                            displayMode = draftMode,
                            deduplicate = draftDeduplicate,
                            contentType = draftContentType,
                            blendMode = draftBlendMode,
                            hideIncludedRows = draftHideIncludedRows,
                        ) != null)
                        if (saved) editorOpen = false
                    },
                ) { Text(stringResource(Res.string.settings_merged_rows_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editorOpen = false }) {
                    Text(stringResource(Res.string.settings_merged_rows_cancel))
                }
            },
        )
    }

    deleteTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(Res.string.settings_merged_rows_delete_title)) },
            text = { Text(stringResource(Res.string.settings_merged_rows_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    HomeCatalogSettingsRepository.deleteMergedRow(row.id)
                    deleteTarget = null
                }) { Text(stringResource(Res.string.settings_merged_rows_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(Res.string.settings_merged_rows_cancel))
                }
            },
        )
    }
}
