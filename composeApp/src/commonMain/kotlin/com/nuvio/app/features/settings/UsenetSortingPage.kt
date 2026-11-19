package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.usenet.RankingPreview
import com.nuvio.app.features.usenet.StreamRankingAction
import com.nuvio.app.features.usenet.StreamRankingCondition
import com.nuvio.app.features.usenet.StreamRankingEditing
import com.nuvio.app.features.usenet.StreamRankingEditing.moved
import com.nuvio.app.features.usenet.StreamRankingEditing.replaced
import com.nuvio.app.features.usenet.StreamRankingEditing.without
import com.nuvio.app.features.usenet.StreamRankingField
import com.nuvio.app.features.usenet.StreamRankingKey
import com.nuvio.app.features.usenet.StreamRankingOperator
import com.nuvio.app.features.usenet.StreamRankingPreset
import com.nuvio.app.features.usenet.StreamRankingProfile
import com.nuvio.app.features.usenet.StreamRankingRule
import com.nuvio.app.features.usenet.StreamRankingTier
import com.nuvio.app.features.usenet.StreamReleaseQuality
import com.nuvio.app.features.usenet.UsenetPlatform
import com.nuvio.app.features.usenet.UsenetRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.usenet_ranking_action
import nuvio.composeapp.generated.resources.usenet_ranking_add_condition
import nuvio.composeapp.generated.resources.usenet_ranking_add_priority
import nuvio.composeapp.generated.resources.usenet_ranking_add_rule
import nuvio.composeapp.generated.resources.usenet_ranking_add_tier
import nuvio.composeapp.generated.resources.usenet_ranking_all
import nuvio.composeapp.generated.resources.usenet_ranking_any
import nuvio.composeapp.generated.resources.usenet_ranking_ascending
import nuvio.composeapp.generated.resources.usenet_ranking_at_least
import nuvio.composeapp.generated.resources.usenet_ranking_at_most
import nuvio.composeapp.generated.resources.usenet_ranking_boost
import nuvio.composeapp.generated.resources.usenet_ranking_bucket
import nuvio.composeapp.generated.resources.usenet_ranking_bucket_hint
import nuvio.composeapp.generated.resources.usenet_ranking_clipboard_empty
import nuvio.composeapp.generated.resources.usenet_ranking_condition
import nuvio.composeapp.generated.resources.usenet_ranking_condition_field
import nuvio.composeapp.generated.resources.usenet_ranking_contains
import nuvio.composeapp.generated.resources.usenet_ranking_copied
import nuvio.composeapp.generated.resources.usenet_ranking_descending
import nuvio.composeapp.generated.resources.usenet_ranking_disabled
import nuvio.composeapp.generated.resources.usenet_ranking_discard
import nuvio.composeapp.generated.resources.usenet_ranking_down
import nuvio.composeapp.generated.resources.usenet_ranking_draft
import nuvio.composeapp.generated.resources.usenet_ranking_enabled
import nuvio.composeapp.generated.resources.usenet_ranking_episode_size
import nuvio.composeapp.generated.resources.usenet_ranking_episodes
import nuvio.composeapp.generated.resources.usenet_ranking_exclude
import nuvio.composeapp.generated.resources.usenet_ranking_excluded
import nuvio.composeapp.generated.resources.usenet_ranking_export
import nuvio.composeapp.generated.resources.usenet_ranking_field_age
import nuvio.composeapp.generated.resources.usenet_ranking_field_audio
import nuvio.composeapp.generated.resources.usenet_ranking_field_codec
import nuvio.composeapp.generated.resources.usenet_ranking_field_group
import nuvio.composeapp.generated.resources.usenet_ranking_field_indexer
import nuvio.composeapp.generated.resources.usenet_ranking_field_language
import nuvio.composeapp.generated.resources.usenet_ranking_field_media
import nuvio.composeapp.generated.resources.usenet_ranking_field_title
import nuvio.composeapp.generated.resources.usenet_ranking_field_visual
import nuvio.composeapp.generated.resources.usenet_ranking_import
import nuvio.composeapp.generated.resources.usenet_ranking_import_export
import nuvio.composeapp.generated.resources.usenet_ranking_import_hint
import nuvio.composeapp.generated.resources.usenet_ranking_imported
import nuvio.composeapp.generated.resources.usenet_ranking_intro
import nuvio.composeapp.generated.resources.usenet_ranking_invalid
import nuvio.composeapp.generated.resources.usenet_ranking_is
import nuvio.composeapp.generated.resources.usenet_ranking_is_not
import nuvio.composeapp.generated.resources.usenet_ranking_key_age
import nuvio.composeapp.generated.resources.usenet_ranking_key_indexer
import nuvio.composeapp.generated.resources.usenet_ranking_key_language
import nuvio.composeapp.generated.resources.usenet_ranking_key_quality
import nuvio.composeapp.generated.resources.usenet_ranking_key_resolution
import nuvio.composeapp.generated.resources.usenet_ranking_key_score
import nuvio.composeapp.generated.resources.usenet_ranking_key_size
import nuvio.composeapp.generated.resources.usenet_ranking_key_size_preference
import nuvio.composeapp.generated.resources.usenet_ranking_key_tier
import nuvio.composeapp.generated.resources.usenet_ranking_languages
import nuvio.composeapp.generated.resources.usenet_ranking_languages_hint
import nuvio.composeapp.generated.resources.usenet_ranking_matches
import nuvio.composeapp.generated.resources.usenet_ranking_minimum
import nuvio.composeapp.generated.resources.usenet_ranking_minimum_hint
import nuvio.composeapp.generated.resources.usenet_ranking_more
import nuvio.composeapp.generated.resources.usenet_ranking_movie_size
import nuvio.composeapp.generated.resources.usenet_ranking_movies
import nuvio.composeapp.generated.resources.usenet_ranking_name
import nuvio.composeapp.generated.resources.usenet_ranking_preview
import nuvio.composeapp.generated.resources.usenet_ranking_preview_count
import nuvio.composeapp.generated.resources.usenet_ranking_preview_hint
import nuvio.composeapp.generated.resources.usenet_ranking_priorities
import nuvio.composeapp.generated.resources.usenet_ranking_priorities_hint
import nuvio.composeapp.generated.resources.usenet_ranking_qualities
import nuvio.composeapp.generated.resources.usenet_ranking_remove
import nuvio.composeapp.generated.resources.usenet_ranking_require
import nuvio.composeapp.generated.resources.usenet_ranking_resolutions
import nuvio.composeapp.generated.resources.usenet_ranking_rule_name
import nuvio.composeapp.generated.resources.usenet_ranking_rules
import nuvio.composeapp.generated.resources.usenet_ranking_rules_hint
import nuvio.composeapp.generated.resources.usenet_ranking_samples
import nuvio.composeapp.generated.resources.usenet_ranking_saved
import nuvio.composeapp.generated.resources.usenet_ranking_score
import nuvio.composeapp.generated.resources.usenet_ranking_score_value
import nuvio.composeapp.generated.resources.usenet_ranking_section_profile
import nuvio.composeapp.generated.resources.usenet_ranking_size_hint
import nuvio.composeapp.generated.resources.usenet_ranking_tier_name
import nuvio.composeapp.generated.resources.usenet_ranking_tiers
import nuvio.composeapp.generated.resources.usenet_ranking_tiers_hint
import nuvio.composeapp.generated.resources.usenet_ranking_unmatched
import nuvio.composeapp.generated.resources.usenet_ranking_up
import nuvio.composeapp.generated.resources.usenet_ranking_value
import nuvio.composeapp.generated.resources.usenet_ranking_values_hint
import nuvio.composeapp.generated.resources.usenet_save
import org.jetbrains.compose.resources.stringResource

/** A choice dialog of the sorting editor. */
private sealed interface SortingChoice {
    data class PriorityKey(val index: Int) : SortingChoice
    data object AddPriority : SortingChoice
    data object Bucket : SortingChoice
    data class ConditionField(val rule: Int, val condition: Int) : SortingChoice
}

/**
 * The custom sorting editor: NuvioTV's phone editor as a settings page. Edits stay a draft until
 * Save, which validates the profile and makes it the active sorting of this profile's results.
 */
internal fun LazyListScope.usenetSortingContent(isTablet: Boolean) {
    item {
        val uiState by remember {
            UsenetRepository.ensureLoaded()
            UsenetRepository.uiState
        }.collectAsStateWithLifecycle()
        val sources = uiState.sources
        var draft by remember(uiState.loaded) { mutableStateOf(StreamRankingEditing.startingDraft(sources.ranking, sources.sort)) }
        var dirty by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var expandedRule by remember { mutableStateOf<Int?>(null) }
        var choice by remember { mutableStateOf<SortingChoice?>(null) }
        var mediaType by remember { mutableStateOf("movie") }
        var samples by remember { mutableStateOf(StreamRankingEditing.defaultSamples) }
        var preview by remember { mutableStateOf<RankingPreview?>(null) }
        var previewError by remember { mutableStateOf<String?>(null) }
        val clipboard = LocalClipboardManager.current
        val savedText = stringResource(Res.string.usenet_ranking_saved)
        val copiedText = stringResource(Res.string.usenet_ranking_copied)
        val importedText = stringResource(Res.string.usenet_ranking_imported)
        val clipboardEmptyText = stringResource(Res.string.usenet_ranking_clipboard_empty)
        val invalidText = stringResource(Res.string.usenet_ranking_invalid)
        val newTierName = stringResource(Res.string.usenet_ranking_tier_name)
        val newRuleName = stringResource(Res.string.usenet_ranking_rule_name)

        fun edit(change: (StreamRankingProfile) -> StreamRankingProfile) {
            draft = change(draft)
            dirty = true
            error = null
            preview = null
        }

        fun editRule(index: Int, change: (StreamRankingRule) -> StreamRankingRule) = edit { profile ->
            profile.rules.getOrNull(index)?.let { profile.copy(rules = profile.rules.replaced(index, change(it))) } ?: profile
        }

        fun editTier(index: Int, change: (StreamRankingTier) -> StreamRankingTier) = edit { profile ->
            profile.tiers.getOrNull(index)?.let { profile.copy(tiers = profile.tiers.replaced(index, change(it))) } ?: profile
        }

        fun save() {
            val profile = draft
            runCatching {
                profile.validate()
                UsenetRepository.updateSources { it.copy(ranking = it.ranking.copy(preset = StreamRankingPreset.CUSTOM, custom = profile)) }
                    .getOrThrow()
            }.onSuccess {
                dirty = false
                error = null
                NuvioToastController.show(savedText)
            }.onFailure { error = it.message ?: invalidText }
        }

        SettingsSection(title = stringResource(Res.string.usenet_ranking_section_profile), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                EditorColumn(isTablet) {
                    Hint(stringResource(Res.string.usenet_ranking_intro))
                    when {
                        error != null -> Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.danger)
                        dirty -> Text(
                            stringResource(Res.string.usenet_ranking_draft),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.nuvio.colors.accent,
                        )
                    }
                    NuvioPrimaryButton(text = stringResource(Res.string.usenet_save), enabled = uiState.loaded, onClick = ::save)
                    if (dirty) {
                        TextButton(onClick = {
                            draft = StreamRankingEditing.startingDraft(sources.ranking, sources.sort)
                            dirty = false
                            error = null
                            preview = null
                        }) { Text(stringResource(Res.string.usenet_ranking_discard)) }
                    }
                    EditorField(draft.name, { value -> edit { it.copy(name = value) } }, stringResource(Res.string.usenet_ranking_name),
                        capitalization = KeyboardCapitalization.Sentences)
                    ParsedField(
                        value = draft.preferredLanguages,
                        label = stringResource(Res.string.usenet_ranking_languages),
                        format = { it.joinToString(", ") },
                        parse = StreamRankingEditing::parseLanguages,
                        onValue = { value -> edit { it.copy(preferredLanguages = value) } },
                    )
                    Hint(stringResource(Res.string.usenet_ranking_languages_hint))
                    ParsedField(
                        value = draft.preferredMovieSizeGb,
                        label = stringResource(Res.string.usenet_ranking_movie_size),
                        format = ::formatNumber,
                        parse = ::parseNumber,
                        keyboardType = KeyboardType.Decimal,
                        onValue = { value -> edit { it.copy(preferredMovieSizeGb = value) } },
                    )
                    ParsedField(
                        value = draft.preferredEpisodeSizeGb,
                        label = stringResource(Res.string.usenet_ranking_episode_size),
                        format = ::formatNumber,
                        parse = ::parseNumber,
                        keyboardType = KeyboardType.Decimal,
                        onValue = { value -> edit { it.copy(preferredEpisodeSizeGb = value) } },
                    )
                    Hint(stringResource(Res.string.usenet_ranking_size_hint))
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.usenet_ranking_bucket),
                    description = draft.topPerBucket.toString() + " · " + stringResource(Res.string.usenet_ranking_bucket_hint),
                    isTablet = isTablet,
                    onClick = { choice = SortingChoice.Bucket },
                )
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        SettingsSection(title = stringResource(Res.string.usenet_ranking_priorities), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                EditorColumn(isTablet) { Hint(stringResource(Res.string.usenet_ranking_priorities_hint)) }
                draft.sort.forEachIndexed { index, sort ->
                    SettingsGroupDivider(isTablet = isTablet)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { choice = SortingChoice.PriorityKey(index) }
                            .padding(start = if (isTablet) 20.dp else 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${index + 1}. ${keyLabel(sort.key)}", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.nuvio.colors.textPrimary, fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(if (sort.descending) Res.string.usenet_ranking_descending else Res.string.usenet_ranking_ascending),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.nuvio.colors.textMuted,
                            )
                        }
                        ActionIcon(Icons.Rounded.SwapVert, stringResource(Res.string.usenet_ranking_ascending)) {
                            edit { it.copy(sort = it.sort.replaced(index, sort.copy(descending = !sort.descending))) }
                        }
                        MoveIcons(index, draft.sort.size) { offset -> edit { it.copy(sort = it.sort.moved(index, offset)) } }
                        ActionIcon(Icons.Rounded.Close, stringResource(Res.string.usenet_ranking_remove)) {
                            edit { it.copy(sort = it.sort.without(index)) }
                        }
                    }
                }
                if (StreamRankingEditing.unusedKeys(draft).isNotEmpty()) {
                    SettingsGroupDivider(isTablet = isTablet)
                    AddButton(stringResource(Res.string.usenet_ranking_add_priority), isTablet) { choice = SortingChoice.AddPriority }
                }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        SettingsSection(title = stringResource(Res.string.usenet_ranking_tiers), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                EditorColumn(isTablet) { Hint(stringResource(Res.string.usenet_ranking_tiers_hint)) }
                draft.tiers.forEachIndexed { index, tier ->
                    SettingsGroupDivider(isTablet = isTablet)
                    EditorColumn(isTablet) {
                        EditorField(tier.name, { value -> editTier(index) { it.copy(name = value) } },
                            "${index + 1}. " + stringResource(Res.string.usenet_ranking_tier_name), capitalization = KeyboardCapitalization.Sentences)
                        Label(stringResource(Res.string.usenet_ranking_resolutions))
                        ChipFlow {
                            StreamRankingEditing.resolutions.forEach { resolution ->
                                UsenetChip(resolutionName(resolution), resolution in tier.resolutions) {
                                    editTier(index) { it.copy(resolutions = it.resolutions.toggled(resolution)) }
                                }
                            }
                        }
                        Label(stringResource(Res.string.usenet_ranking_qualities))
                        ChipFlow {
                            StreamRankingEditing.qualities.forEach { quality ->
                                UsenetChip(qualityName(quality), quality in tier.qualities) {
                                    editTier(index) { it.copy(qualities = it.qualities.toggled(quality)) }
                                }
                            }
                        }
                        ItemActions(index, draft.tiers.size,
                            onMove = { offset -> edit { it.copy(tiers = it.tiers.moved(index, offset)) } },
                            onRemove = { edit { it.copy(tiers = it.tiers.without(index)) } })
                    }
                }
                if (draft.tiers.size < StreamRankingEditing.MAX_TIERS) {
                    SettingsGroupDivider(isTablet = isTablet)
                    AddButton(stringResource(Res.string.usenet_ranking_add_tier), isTablet) { edit { StreamRankingEditing.addTier(it, newTierName) } }
                }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        SettingsSection(title = stringResource(Res.string.usenet_ranking_rules), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                EditorColumn(isTablet) {
                    Hint(stringResource(Res.string.usenet_ranking_rules_hint))
                    Hint(stringResource(Res.string.usenet_ranking_values_hint))
                }
                draft.rules.forEachIndexed { index, rule ->
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = rule.name,
                        description = ruleSummary(rule),
                        isTablet = isTablet,
                        onClick = { expandedRule = if (expandedRule == index) null else index },
                    )
                    if (expandedRule == index) {
                        RuleEditor(
                            rule = rule,
                            index = index,
                            count = draft.rules.size,
                            isTablet = isTablet,
                            onRule = { change -> editRule(index, change) },
                            onAction = { action -> edit { StreamRankingEditing.setRuleAction(it, index, action) } },
                            onField = { condition -> choice = SortingChoice.ConditionField(index, condition) },
                            onMove = { offset ->
                                edit { it.copy(rules = it.rules.moved(index, offset)) }
                                expandedRule = (index + offset).coerceIn(0, draft.rules.lastIndex)
                            },
                            onRemove = {
                                edit { it.copy(rules = it.rules.without(index)) }
                                expandedRule = null
                            },
                        )
                    }
                }
                if (draft.rules.size < StreamRankingEditing.MAX_RULES) {
                    SettingsGroupDivider(isTablet = isTablet)
                    AddButton(stringResource(Res.string.usenet_ranking_add_rule), isTablet) {
                        edit { StreamRankingEditing.addRule(it, newRuleName) }
                        expandedRule = draft.rules.lastIndex
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        SettingsSection(title = stringResource(Res.string.usenet_ranking_preview), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                EditorColumn(isTablet) {
                    Hint(stringResource(Res.string.usenet_ranking_preview_hint))
                    Label(stringResource(Res.string.usenet_ranking_field_media))
                    ChipFlow {
                        UsenetChip(stringResource(Res.string.usenet_ranking_movies), mediaType == "movie") { mediaType = "movie"; preview = null }
                        UsenetChip(stringResource(Res.string.usenet_ranking_episodes), mediaType == "series") { mediaType = "series"; preview = null }
                    }
                    OutlinedTextField(
                        value = samples,
                        onValueChange = { samples = it; preview = null; previewError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(Res.string.usenet_ranking_samples)) },
                        minLines = 6,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        shape = MaterialTheme.nuvio.shapes.button,
                        colors = sheetFieldColors(),
                    )
                    NuvioPrimaryButton(text = stringResource(Res.string.usenet_ranking_preview), onClick = {
                        runCatching { UsenetPlatform.previewRanking(draft, StreamRankingEditing.parseSamples(samples), mediaType) }
                            .onSuccess { preview = it; previewError = null }
                            .onFailure { preview = null; previewError = it.message ?: invalidText }
                    })
                    previewError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.danger) }
                    preview?.let { PreviewResults(it) }
                }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        SettingsSection(title = stringResource(Res.string.usenet_ranking_import_export), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                EditorColumn(isTablet) {
                    Hint(stringResource(Res.string.usenet_ranking_import_hint))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(StreamRankingEditing.exportJson(draft)))
                            NuvioToastController.show(copiedText)
                        }) { Text(stringResource(Res.string.usenet_ranking_export)) }
                        TextButton(onClick = {
                            val text = clipboard.getText()?.text
                            if (text.isNullOrBlank()) {
                                error = clipboardEmptyText
                            } else {
                                runCatching { StreamRankingEditing.importJson(text) }
                                    .onSuccess { imported ->
                                        edit { imported }
                                        expandedRule = null
                                        NuvioToastController.show(importedText)
                                    }
                                    .onFailure { error = it.message ?: invalidText }
                            }
                        }) { Text(stringResource(Res.string.usenet_ranking_import)) }
                    }
                }
            }
        }

        when (val current = choice) {
            is SortingChoice.PriorityKey -> {
                val sort = draft.sort.getOrNull(current.index)
                if (sort == null) {
                    choice = null
                } else {
                    val used = draft.sort.map { it.key }.toSet() - sort.key
                    SettingsChoiceDialog(
                        title = stringResource(Res.string.usenet_ranking_priorities),
                        options = StreamRankingKey.entries.filter { it !in used },
                        selected = sort.key,
                        label = { keyLabel(it) },
                        onSelect = { key ->
                            edit { it.copy(sort = it.sort.replaced(current.index, sort.copy(key = key))) }
                            choice = null
                        },
                        onDismiss = { choice = null },
                    )
                }
            }
            SortingChoice.AddPriority -> {
                val unused = StreamRankingEditing.unusedKeys(draft)
                if (unused.isEmpty()) {
                    choice = null
                } else {
                    SettingsChoiceDialog(
                        title = stringResource(Res.string.usenet_ranking_add_priority),
                        options = unused,
                        selected = unused.first(),
                        label = { keyLabel(it) },
                        onSelect = { key ->
                            edit { StreamRankingEditing.addPriority(it, key) }
                            choice = null
                        },
                        onDismiss = { choice = null },
                    )
                }
            }
            SortingChoice.Bucket -> SettingsChoiceDialog(
                title = stringResource(Res.string.usenet_ranking_bucket),
                options = (0..20).toList(),
                selected = draft.topPerBucket,
                label = { it.toString() },
                description = { if (it == draft.topPerBucket) stringResource(Res.string.usenet_ranking_bucket_hint) else null },
                onSelect = { value ->
                    edit { it.copy(topPerBucket = value) }
                    choice = null
                },
                onDismiss = { choice = null },
            )
            is SortingChoice.ConditionField -> {
                val condition = draft.rules.getOrNull(current.rule)?.conditions?.getOrNull(current.condition)
                if (condition == null) {
                    choice = null
                } else {
                    SettingsChoiceDialog(
                        title = stringResource(Res.string.usenet_ranking_condition_field),
                        options = StreamRankingField.entries,
                        selected = condition.field,
                        label = { fieldLabel(it) },
                        onSelect = { field ->
                            editRule(current.rule) { rule ->
                                rule.copy(conditions = rule.conditions.replaced(current.condition, StreamRankingEditing.withField(condition, field)))
                            }
                            choice = null
                        },
                        onDismiss = { choice = null },
                    )
                }
            }
            null -> Unit
        }
    }
}

@Composable
private fun RuleEditor(
    rule: StreamRankingRule,
    index: Int,
    count: Int,
    isTablet: Boolean,
    onRule: ((StreamRankingRule) -> StreamRankingRule) -> Unit,
    onAction: (StreamRankingAction) -> Unit,
    onField: (Int) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    EditorColumn(isTablet) {
        EditorField(rule.name, { value -> onRule { it.copy(name = value) } }, stringResource(Res.string.usenet_ranking_rule_name),
            capitalization = KeyboardCapitalization.Sentences)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.usenet_ranking_enabled), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.nuvio.colors.textPrimary)
            Switch(checked = rule.enabled, onCheckedChange = { value -> onRule { it.copy(enabled = value) } })
        }
        Label(stringResource(Res.string.usenet_ranking_action))
        ChipFlow {
            StreamRankingAction.entries.forEach { action ->
                UsenetChip(actionLabel(action), rule.action == action) { onAction(action) }
            }
        }
        if (rule.action == StreamRankingAction.BOOST) {
            ParsedField(
                value = rule.score,
                label = stringResource(Res.string.usenet_ranking_score),
                format = Int::toString,
                parse = { text -> if (text.isBlank()) 0 else text.trim().toIntOrNull() },
                keyboardType = KeyboardType.Number,
                onValue = { value -> onRule { it.copy(score = value) } },
            )
        } else {
            ParsedField(
                value = rule.minimumRemaining,
                label = stringResource(Res.string.usenet_ranking_minimum),
                format = Int::toString,
                parse = { text -> if (text.isBlank()) 0 else text.trim().toIntOrNull()?.takeIf { it >= 0 } },
                keyboardType = KeyboardType.Number,
                onValue = { value -> onRule { it.copy(minimumRemaining = value) } },
            )
            Hint(stringResource(Res.string.usenet_ranking_minimum_hint))
        }
        ChipFlow {
            UsenetChip(stringResource(Res.string.usenet_ranking_all), !rule.matchAny) { onRule { it.copy(matchAny = false) } }
            UsenetChip(stringResource(Res.string.usenet_ranking_any), rule.matchAny) { onRule { it.copy(matchAny = true) } }
        }
        rule.conditions.forEachIndexed { position, condition ->
            ConditionEditor(
                condition = condition,
                position = position,
                onField = { onField(position) },
                onCondition = { change -> onRule { it.copy(conditions = it.conditions.replaced(position, change(condition))) } },
                onRemove = { onRule { it.copy(conditions = it.conditions.without(position)) } },
            )
        }
        if (rule.conditions.size < StreamRankingEditing.MAX_CONDITIONS) {
            TextButton(onClick = { onRule(StreamRankingEditing::addCondition) }) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(Res.string.usenet_ranking_add_condition))
            }
        }
        ItemActions(index, count, onMove = onMove, onRemove = onRemove)
    }
}

@Composable
private fun ConditionEditor(
    condition: StreamRankingCondition,
    position: Int,
    onField: () -> Unit,
    onCondition: ((StreamRankingCondition) -> StreamRankingCondition) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.usenet_ranking_condition, (position + 1).toString()) + " · " + fieldLabel(condition.field),
                modifier = Modifier.weight(1f).clickable(onClick = onField).padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.nuvio.colors.accent,
                fontWeight = FontWeight.Medium,
            )
            ActionIcon(Icons.Rounded.Close, stringResource(Res.string.usenet_ranking_remove), onClick = onRemove)
        }
        ChipFlow {
            StreamRankingEditing.operatorsFor(condition.field).forEach { operator ->
                UsenetChip(operatorLabel(operator), condition.operator == operator) { onCondition { it.copy(operator = operator) } }
            }
        }
        EditorField(condition.value, { value -> onCondition { it.copy(value = value) } }, stringResource(Res.string.usenet_ranking_value),
            keyboardType = if (condition.field in NUMERIC_FIELDS) KeyboardType.Decimal else KeyboardType.Text)
    }
}

@Composable
private fun PreviewResults(preview: RankingPreview) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Hint(stringResource(Res.string.usenet_ranking_preview_count, preview.results.size.toString()))
        preview.results.forEachIndexed { index, row ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${index + 1}. ${row.title}", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull(
                        row.tier ?: stringResource(Res.string.usenet_ranking_unmatched),
                        stringResource(Res.string.usenet_ranking_score_value, row.score.toString()),
                        stringResource(Res.string.usenet_ranking_more).takeIf { row.overflow },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
                if (row.matches.isNotEmpty()) {
                    Text(row.matches.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
                }
            }
        }
        if (preview.excluded.isNotEmpty()) {
            Hint(stringResource(Res.string.usenet_ranking_excluded, preview.excluded.size.toString()))
            preview.excluded.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted) }
        }
    }
}

@Composable
private fun EditorColumn(isTablet: Boolean, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textMuted)
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.nuvio.colors.textSecondary)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(capitalization = capitalization, autoCorrectEnabled = false, keyboardType = keyboardType),
        shape = MaterialTheme.nuvio.shapes.button,
        colors = sheetFieldColors(),
    )
}

/**
 * A text field for a value that is not text. It keeps what is typed, such as "1." or "en, ", and
 * passes the value on whenever the text parses; an outside change, such as an import, replaces it.
 */
@Composable
private fun <T> ParsedField(
    value: T,
    label: String,
    format: (T) -> String,
    parse: (String) -> T?,
    onValue: (T) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var text by remember { mutableStateOf(format(value)) }
    LaunchedEffect(value) {
        if (parse(text) != value) text = format(value)
    }
    EditorField(
        value = text,
        onValueChange = { typed ->
            text = typed
            parse(typed)?.let { parsed -> if (parsed != value) onValue(parsed) }
        },
        label = label,
        keyboardType = keyboardType,
    )
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun MoveIcons(index: Int, count: Int, onMove: (Int) -> Unit) {
    ActionIcon(Icons.Rounded.KeyboardArrowUp, stringResource(Res.string.usenet_ranking_up), enabled = index > 0) { onMove(-1) }
    ActionIcon(Icons.Rounded.KeyboardArrowDown, stringResource(Res.string.usenet_ranking_down), enabled = index < count - 1) { onMove(1) }
}

@Composable
private fun ItemActions(index: Int, count: Int, onMove: (Int) -> Unit, onRemove: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onMove(-1) }, enabled = index > 0) { Text(stringResource(Res.string.usenet_ranking_up)) }
        TextButton(onClick = { onMove(1) }, enabled = index < count - 1) { Text(stringResource(Res.string.usenet_ranking_down)) }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onRemove) {
            Text(stringResource(Res.string.usenet_ranking_remove), color = MaterialTheme.nuvio.colors.danger)
        }
    }
}

@Composable
private fun AddButton(text: String, isTablet: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(horizontal = if (isTablet) 12.dp else 8.dp, vertical = 4.dp)) {
        Icon(Icons.Rounded.Add, contentDescription = null)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text)
    }
}

private val NUMERIC_FIELDS = setOf(StreamRankingField.RESOLUTION, StreamRankingField.SIZE_GB, StreamRankingField.AGE_DAYS)

private fun <T> List<T>.toggled(value: T): List<T> = if (value in this) this - value else this + value

private fun formatNumber(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

private fun parseNumber(text: String): Double? =
    if (text.isBlank()) 0.0 else text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

private fun resolutionName(resolution: Int): String = if (resolution == 2160) "4K" else "${resolution}p"

private fun qualityName(quality: StreamReleaseQuality): String = when (quality) {
    StreamReleaseQuality.REMUX -> "Remux"
    StreamReleaseQuality.BLURAY -> "Blu-ray"
    StreamReleaseQuality.WEB_DL -> "WEB-DL"
    StreamReleaseQuality.WEBRIP -> "WEBRip"
    StreamReleaseQuality.HDTV -> "HDTV"
    StreamReleaseQuality.DVD -> "DVD"
    StreamReleaseQuality.CAM -> "CAM"
    StreamReleaseQuality.UNKNOWN -> "Unknown"
}

@Composable
private fun ruleSummary(rule: StreamRankingRule): String = listOfNotNull(
    when (rule.action) {
        StreamRankingAction.BOOST -> stringResource(Res.string.usenet_ranking_score_value, rule.score.toString())
        StreamRankingAction.EXCLUDE -> stringResource(Res.string.usenet_ranking_exclude)
        StreamRankingAction.REQUIRE -> stringResource(Res.string.usenet_ranking_require)
    },
    stringResource(Res.string.usenet_ranking_disabled).takeIf { !rule.enabled },
).joinToString(" · ")

@Composable
private fun keyLabel(key: StreamRankingKey): String = stringResource(
    when (key) {
        StreamRankingKey.TIER -> Res.string.usenet_ranking_key_tier
        StreamRankingKey.SCORE -> Res.string.usenet_ranking_key_score
        StreamRankingKey.LANGUAGE -> Res.string.usenet_ranking_key_language
        StreamRankingKey.RESOLUTION -> Res.string.usenet_ranking_key_resolution
        StreamRankingKey.QUALITY -> Res.string.usenet_ranking_key_quality
        StreamRankingKey.SIZE -> Res.string.usenet_ranking_key_size
        StreamRankingKey.AGE -> Res.string.usenet_ranking_key_age
        StreamRankingKey.INDEXER -> Res.string.usenet_ranking_key_indexer
        StreamRankingKey.SIZE_PREFERENCE -> Res.string.usenet_ranking_key_size_preference
    },
)

@Composable
private fun fieldLabel(field: StreamRankingField): String = stringResource(
    when (field) {
        StreamRankingField.TITLE -> Res.string.usenet_ranking_field_title
        StreamRankingField.GROUP -> Res.string.usenet_ranking_field_group
        StreamRankingField.QUALITY -> Res.string.usenet_ranking_key_quality
        StreamRankingField.RESOLUTION -> Res.string.usenet_ranking_key_resolution
        StreamRankingField.CODEC -> Res.string.usenet_ranking_field_codec
        StreamRankingField.VISUAL -> Res.string.usenet_ranking_field_visual
        StreamRankingField.AUDIO -> Res.string.usenet_ranking_field_audio
        StreamRankingField.LANGUAGE -> Res.string.usenet_ranking_field_language
        StreamRankingField.INDEXER -> Res.string.usenet_ranking_field_indexer
        StreamRankingField.SIZE_GB -> Res.string.usenet_ranking_key_size
        StreamRankingField.AGE_DAYS -> Res.string.usenet_ranking_field_age
        StreamRankingField.MEDIA_TYPE -> Res.string.usenet_ranking_field_media
    },
)

@Composable
private fun operatorLabel(operator: StreamRankingOperator): String = stringResource(
    when (operator) {
        StreamRankingOperator.IS -> Res.string.usenet_ranking_is
        StreamRankingOperator.IS_NOT -> Res.string.usenet_ranking_is_not
        StreamRankingOperator.CONTAINS -> Res.string.usenet_ranking_contains
        StreamRankingOperator.MATCHES -> Res.string.usenet_ranking_matches
        StreamRankingOperator.AT_LEAST -> Res.string.usenet_ranking_at_least
        StreamRankingOperator.AT_MOST -> Res.string.usenet_ranking_at_most
    },
)

@Composable
private fun actionLabel(action: StreamRankingAction): String = stringResource(
    when (action) {
        StreamRankingAction.BOOST -> Res.string.usenet_ranking_boost
        StreamRankingAction.EXCLUDE -> Res.string.usenet_ranking_exclude
        StreamRankingAction.REQUIRE -> Res.string.usenet_ranking_require
    },
)
