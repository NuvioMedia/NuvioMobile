package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.tmdb.TmdbSettings
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.normalizeLanguage
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.settings_tmdb_api_key_label
import nuvio.composeapp.generated.resources.settings_tmdb_api_key_override_description
import nuvio.composeapp.generated.resources.settings_tmdb_enable_enrichment
import nuvio.composeapp.generated.resources.settings_tmdb_enable_enrichment_description
import nuvio.composeapp.generated.resources.settings_tmdb_language_code_label
import nuvio.composeapp.generated.resources.settings_tmdb_module_artwork
import nuvio.composeapp.generated.resources.settings_tmdb_module_artwork_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_basic_info
import nuvio.composeapp.generated.resources.settings_tmdb_module_basic_info_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_collections
import nuvio.composeapp.generated.resources.settings_tmdb_module_collections_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_credits
import nuvio.composeapp.generated.resources.settings_tmdb_module_credits_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_details
import nuvio.composeapp.generated.resources.settings_tmdb_module_details_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_episodes
import nuvio.composeapp.generated.resources.settings_tmdb_module_episodes_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_release_dates
import nuvio.composeapp.generated.resources.settings_tmdb_module_release_dates_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_more_like_this
import nuvio.composeapp.generated.resources.settings_tmdb_module_more_like_this_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_networks
import nuvio.composeapp.generated.resources.settings_tmdb_module_networks_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_production_companies
import nuvio.composeapp.generated.resources.settings_tmdb_module_production_companies_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_season_posters
import nuvio.composeapp.generated.resources.settings_tmdb_module_season_posters_description
import nuvio.composeapp.generated.resources.settings_tmdb_module_trailers
import nuvio.composeapp.generated.resources.settings_tmdb_module_trailers_description
import nuvio.composeapp.generated.resources.settings_tmdb_personal_api_key
import nuvio.composeapp.generated.resources.settings_tmdb_preferred_language
import nuvio.composeapp.generated.resources.settings_tmdb_preferred_language_description
import nuvio.composeapp.generated.resources.settings_tmdb_section_localization
import nuvio.composeapp.generated.resources.settings_tmdb_section_modules
import nuvio.composeapp.generated.resources.settings_tmdb_section_title
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.tmdbSettingsContent(
    isTablet: Boolean,
    settings: TmdbSettings,
) {
    val enrichmentControlsEnabled = settings.enabled

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_tmdb_section_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_tmdb_enable_enrichment),
                    description = stringResource(Res.string.settings_tmdb_enable_enrichment_description),
                    checked = settings.enabled,
                    isTablet = isTablet,
                    onCheckedChange = TmdbSettingsRepository::setEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbApiKeyRow(
                    isTablet = isTablet,
                    value = settings.apiKey,
                    onApiKeyCommitted = TmdbSettingsRepository::setApiKey,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_tmdb_section_localization),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                TmdbLanguageRow(
                    isTablet = isTablet,
                    value = settings.language,
                    enabled = true,
                    onLanguageCommitted = TmdbSettingsRepository::setLanguage,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_tmdb_section_modules),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_trailers),
                    description = stringResource(Res.string.settings_tmdb_module_trailers_description),
                    checked = settings.useTrailers,
                    enabled = enrichmentControlsEnabled,
                    onCheckedChange = TmdbSettingsRepository::setUseTrailers,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_artwork),
                    description = stringResource(Res.string.settings_tmdb_module_artwork_description),
                    checked = settings.useArtwork,
                    enabled = enrichmentControlsEnabled,
                    onCheckedChange = TmdbSettingsRepository::setUseArtwork,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_basic_info),
                    description = stringResource(Res.string.settings_tmdb_module_basic_info_description),
                    checked = settings.useBasicInfo,
                    enabled = enrichmentControlsEnabled,
                    onCheckedChange = TmdbSettingsRepository::setUseBasicInfo,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_details),
                    description = stringResource(Res.string.settings_tmdb_module_details_description),
                    checked = settings.useDetails,
                    enabled = enrichmentControlsEnabled,
                    onCheckedChange = TmdbSettingsRepository::setUseDetails,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_release_dates),
                    description = stringResource(Res.string.settings_tmdb_module_release_dates_description),
                    checked = settings.useReleaseDates,
                    enabled = enrichmentControlsEnabled,
                    onCheckedChange = TmdbSettingsRepository::setUseReleaseDates,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_credits),
                    description = stringResource(Res.string.settings_tmdb_module_credits_description),
                    checked = settings.useCredits,
                    enabled = enrichmentControlsEnabled,
                    onCheckedChange = TmdbSettingsRepository::setUseCredits,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_production_companies),
                    description = stringResource(Res.string.settings_tmdb_module_production_companies_description),
                    checked = settings.useProductions,
                    enabled = enrichmentControlsEnabled,
                    onCheckedChange = TmdbSettingsRepository::setUseProductions,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_networks),
                    description = stringResource(Res.string.settings_tmdb_module_networks_description),
                    checked = settings.useNetworks,
                    enabled = enrichmentControlsEnabled,
                    onCheckedChange = TmdbSettingsRepository::setUseNetworks,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_episodes),
                    description = stringResource(Res.string.settings_tmdb_module_episodes_description),
                    checked = settings.useEpisodes,
                    enabled = enrichmentControlsEnabled,
                    onCheckedChange = TmdbSettingsRepository::setUseEpisodes,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_season_posters),
                    description = stringResource(Res.string.settings_tmdb_module_season_posters_description),
                    checked = settings.useSeasonPosters,
                    enabled = enrichmentControlsEnabled,
                    onCheckedChange = TmdbSettingsRepository::setUseSeasonPosters,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_more_like_this),
                    description = stringResource(Res.string.settings_tmdb_module_more_like_this_description),
                    checked = settings.useMoreLikeThis,
                    enabled = enrichmentControlsEnabled,
                    onCheckedChange = TmdbSettingsRepository::setUseMoreLikeThis,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = stringResource(Res.string.settings_tmdb_module_collections),
                    description = stringResource(Res.string.settings_tmdb_module_collections_description),
                    checked = settings.useCollections,
                    enabled = enrichmentControlsEnabled,
                    onCheckedChange = TmdbSettingsRepository::setUseCollections,
                )
            }
        }
    }
}

@Composable
private fun TmdbApiKeyRow(
    isTablet: Boolean,
    value: String,
    onApiKeyCommitted: (String) -> Unit,
) {
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val normalizedDraft = draft.trim()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = if (isTablet) 16.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.settings_tmdb_personal_api_key),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(Res.string.settings_tmdb_api_key_override_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsSecretTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(Res.string.settings_tmdb_api_key_label),
        )
        Button(
            onClick = {
                draft = normalizedDraft
                onApiKeyCommitted(normalizedDraft)
            },
            enabled = normalizedDraft != value,
        ) {
            Text(stringResource(Res.string.action_save))
        }
    }
}

@Composable
private fun TmdbLanguageRow(
    isTablet: Boolean,
    value: String,
    enabled: Boolean,
    onLanguageCommitted: (String) -> Unit,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val normalizedDraft = normalizeLanguage(draft)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.settings_tmdb_preferred_language),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(Res.string.settings_tmdb_preferred_language_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(Res.string.settings_tmdb_language_code_label)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    draft = normalizedDraft
                    onLanguageCommitted(normalizedDraft)
                },
                enabled = enabled && normalizedDraft != value,
            ) {
                Text(stringResource(Res.string.action_save))
            }
        }
    }
}

@Composable
private fun TmdbToggleRow(
    isTablet: Boolean,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsSwitchRow(
        title = title,
        description = description,
        checked = checked,
        enabled = enabled,
        isTablet = isTablet,
        onCheckedChange = onCheckedChange,
    )
}
