package com.nuvio.app.features.livetv

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSwitchRow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.livetv_settings_pluto_description
import nuvio.composeapp.generated.resources.livetv_settings_pluto_title
import nuvio.composeapp.generated.resources.livetv_settings_section_playlists
import nuvio.composeapp.generated.resources.livetv_settings_section_xtream
import nuvio.composeapp.generated.resources.livetv_settings_validate
import nuvio.composeapp.generated.resources.livetv_settings_xtream_enabled_description
import nuvio.composeapp.generated.resources.livetv_settings_xtream_enabled_title
import nuvio.composeapp.generated.resources.livetv_settings_xtream_password
import nuvio.composeapp.generated.resources.livetv_settings_xtream_server
import nuvio.composeapp.generated.resources.livetv_settings_xtream_username
import nuvio.composeapp.generated.resources.livetv_settings_xtream_validation_failed
import nuvio.composeapp.generated.resources.livetv_settings_xtream_validation_success
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.liveTvSettingsContent(isTablet: Boolean) {
    item {
        val plutoEnabled by remember {
            LiveTvSettingsRepository.ensureLoaded()
            LiveTvSettingsRepository.plutoEnabled
        }.collectAsStateWithLifecycle()

        SettingsSection(
            title = stringResource(Res.string.livetv_settings_section_playlists),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_settings_pluto_title),
                    description = stringResource(Res.string.livetv_settings_pluto_description),
                    checked = plutoEnabled,
                    isTablet = isTablet,
                    onCheckedChange = LiveTvSettingsRepository::setPlutoEnabled,
                )
            }
        }
    }

    item {
        XtreamSettingsGroup(isTablet = isTablet)
    }
}

@Composable
private fun XtreamSettingsGroup(isTablet: Boolean) {
    val xtreamConfig by remember {
        LiveTvSettingsRepository.ensureLoaded()
        LiveTvSettingsRepository.xtreamConfig
    }.collectAsStateWithLifecycle()
    var serverUrl by remember(xtreamConfig.serverUrl) { mutableStateOf(xtreamConfig.serverUrl) }
    var username by remember(xtreamConfig.username) { mutableStateOf(xtreamConfig.username) }
    var password by remember(xtreamConfig.password) { mutableStateOf(xtreamConfig.password) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val validationSuccessText = stringResource(Res.string.livetv_settings_xtream_validation_success)
    val validationFailedText = stringResource(Res.string.livetv_settings_xtream_validation_failed)

    SettingsSection(
        title = stringResource(Res.string.livetv_settings_section_xtream),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.livetv_settings_xtream_enabled_title),
                description = stringResource(Res.string.livetv_settings_xtream_enabled_description),
                checked = xtreamConfig.enabled,
                isTablet = isTablet,
                onCheckedChange = { enabled ->
                    LiveTvSettingsRepository.updateXtreamConfig(
                        xtreamConfig.copy(enabled = enabled),
                    )
                },
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    validationMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.livetv_settings_xtream_server)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    validationMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.livetv_settings_xtream_username)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    validationMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.livetv_settings_xtream_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            androidx.compose.material3.TextButton(
                onClick = {
                    val draft = XtreamCodesConfig(
                        serverUrl = serverUrl.trim(),
                        username = username.trim(),
                        password = password,
                        enabled = xtreamConfig.enabled,
                    )
                    scope.launch {
                        val valid = XtreamCodesClient.validateConfig(draft)
                        validationMessage = if (valid) validationSuccessText else validationFailedText
                        if (valid) {
                            LiveTvSettingsRepository.updateXtreamConfig(draft)
                        }
                    }
                },
            ) {
                Text(stringResource(Res.string.livetv_settings_validate))
            }
            validationMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
