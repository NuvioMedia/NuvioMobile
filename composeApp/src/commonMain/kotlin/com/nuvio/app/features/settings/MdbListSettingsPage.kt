package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.mdblist.MdbListMetadataService
import com.nuvio.app.features.mdblist.MdbListSettings
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.settings_mdb_ratings_credentials_required
import nuvio.composeapp.generated.resources.settings_mdb_ratings_key_override_description
import nuvio.composeapp.generated.resources.settings_mdb_ratings_connected_account
import nuvio.composeapp.generated.resources.settings_mdb_api_key_label
import nuvio.composeapp.generated.resources.settings_mdb_api_key_title
import nuvio.composeapp.generated.resources.settings_mdb_enable_ratings
import nuvio.composeapp.generated.resources.settings_mdb_enable_ratings_description
import nuvio.composeapp.generated.resources.settings_mdb_section_api_key
import nuvio.composeapp.generated.resources.settings_mdb_section_rating_providers
import nuvio.composeapp.generated.resources.settings_mdb_section_title
import nuvio.composeapp.generated.resources.source_audience_score
import nuvio.composeapp.generated.resources.source_imdb
import nuvio.composeapp.generated.resources.source_letterboxd
import nuvio.composeapp.generated.resources.source_mal
import nuvio.composeapp.generated.resources.source_metacritic
import nuvio.composeapp.generated.resources.source_rotten_tomatoes
import nuvio.composeapp.generated.resources.source_tmdb
import nuvio.composeapp.generated.resources.source_trakt
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.mdbListSettingsContent(
    isTablet: Boolean,
    settings: MdbListSettings,
) {
    val providerControlsEnabled = settings.enabled

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_mdb_section_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_mdb_enable_ratings),
                    description = stringResource(Res.string.settings_mdb_enable_ratings_description),
                    checked = settings.enabled,
                    isTablet = isTablet,
                    onCheckedChange = MdbListSettingsRepository::setEnabled,
                )
                if (!settings.hasCredentials) {
                    SettingsGroupDivider(isTablet = isTablet)
                    MdbListInfoRow(
                        isTablet = isTablet,
                        text = stringResource(Res.string.settings_mdb_ratings_credentials_required),
                    )
                }
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_mdb_section_api_key),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                MdbListApiKeyRow(
                    isTablet = isTablet,
                    value = settings.apiKey,
                    usingConnectedAccount = settings.accountScope != null && !settings.hasApiKey,
                    onApiKeyCommitted = MdbListSettingsRepository::setApiKey,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_mdb_section_rating_providers),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                ProviderRows(
                    isTablet = isTablet,
                    settings = settings,
                    controlsEnabled = providerControlsEnabled,
                )
            }
        }
    }
}

@Composable
private fun ProviderRows(
    isTablet: Boolean,
    settings: MdbListSettings,
    controlsEnabled: Boolean,
) {
    val providers = listOf(
        MdbListMetadataService.PROVIDER_IMDB to Res.string.source_imdb,
        MdbListMetadataService.PROVIDER_TMDB to Res.string.source_tmdb,
        MdbListMetadataService.PROVIDER_TOMATOES to Res.string.source_rotten_tomatoes,
        MdbListMetadataService.PROVIDER_METACRITIC to Res.string.source_metacritic,
        MdbListMetadataService.PROVIDER_TRAKT to Res.string.source_trakt,
        MdbListMetadataService.PROVIDER_LETTERBOXD to Res.string.source_letterboxd,
        MdbListMetadataService.PROVIDER_AUDIENCE to Res.string.source_audience_score,
        MdbListMetadataService.PROVIDER_MAL to Res.string.source_mal,
    )

    providers.forEachIndexed { index, (providerId, providerLabelRes) ->
        SettingsSwitchRow(
            title = stringResource(providerLabelRes),
            checked = settings.isProviderEnabled(providerId),
            enabled = controlsEnabled,
            isTablet = isTablet,
            onCheckedChange = { checked ->
                MdbListSettingsRepository.setProviderEnabled(providerId, checked)
            },
        )
        if (index < providers.lastIndex) {
            SettingsGroupDivider(isTablet = isTablet)
        }
    }
}

@Composable
private fun MdbListApiKeyRow(
    isTablet: Boolean,
    value: String,
    usingConnectedAccount: Boolean,
    onApiKeyCommitted: (String) -> Unit,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val normalizedDraft = draft.trim()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.settings_mdb_api_key_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(Res.string.settings_mdb_ratings_key_override_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (usingConnectedAccount) {
            Text(
                text = stringResource(Res.string.settings_mdb_ratings_connected_account),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        SettingsSecretTextField(
            value = draft,
            onValueChange = {
                draft = it
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(Res.string.settings_mdb_api_key_label),
        )

        Row(modifier = Modifier.fillMaxWidth()) {
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
}

@Composable
private fun MdbListInfoRow(
    isTablet: Boolean,
    text: String,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 14.dp else 12.dp

    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
