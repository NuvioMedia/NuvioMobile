package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.nuvio.app.core.build.AppVersionConfig
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_about_made_with
import nuvio.composeapp.generated.resources.compose_about_version_format
import nuvio.composeapp.generated.resources.compose_settings_page_account
import nuvio.composeapp.generated.resources.compose_settings_page_advanced
import nuvio.composeapp.generated.resources.compose_settings_page_appearance
import nuvio.composeapp.generated.resources.compose_settings_page_integrations
import nuvio.composeapp.generated.resources.compose_settings_page_licenses_attributions
import nuvio.composeapp.generated.resources.compose_settings_page_notifications
import nuvio.composeapp.generated.resources.compose_settings_page_playback
import nuvio.composeapp.generated.resources.compose_settings_page_privacy_policy
import nuvio.composeapp.generated.resources.compose_settings_page_supporters_contributors
import nuvio.composeapp.generated.resources.compose_settings_root_account_description
import nuvio.composeapp.generated.resources.compose_settings_root_appearance_description
import nuvio.composeapp.generated.resources.compose_settings_root_check_updates_description
import nuvio.composeapp.generated.resources.compose_settings_root_check_updates_title
import nuvio.composeapp.generated.resources.compose_settings_root_content_discovery_description
import nuvio.composeapp.generated.resources.compose_settings_root_downloads_description
import nuvio.composeapp.generated.resources.compose_settings_root_downloads_title
import nuvio.composeapp.generated.resources.compose_settings_root_general_section
import nuvio.composeapp.generated.resources.compose_settings_root_integrations_description
import nuvio.composeapp.generated.resources.compose_settings_root_notifications_description
import nuvio.composeapp.generated.resources.compose_settings_root_privacy_policy_description
import nuvio.composeapp.generated.resources.compose_settings_root_switch_profile_description
import nuvio.composeapp.generated.resources.compose_settings_root_switch_profile_title
import nuvio.composeapp.generated.resources.compose_settings_root_tracking_description
import nuvio.composeapp.generated.resources.compose_settings_root_about_section
import nuvio.composeapp.generated.resources.compose_settings_root_account_section
import nuvio.composeapp.generated.resources.compose_settings_root_advanced_description
import nuvio.composeapp.generated.resources.compose_settings_root_advanced_section
import nuvio.composeapp.generated.resources.compose_settings_page_content_discovery
import nuvio.composeapp.generated.resources.compose_settings_page_tracking
import nuvio.composeapp.generated.resources.settings_playback_subtitle
import nuvio.composeapp.generated.resources.updates_debug_test_description
import nuvio.composeapp.generated.resources.updates_debug_test_title
import nuvio.composeapp.generated.resources.about_supporters_contributors_subtitle
import nuvio.composeapp.generated.resources.about_licenses_attributions_subtitle
import org.jetbrains.compose.resources.stringResource

private const val PRIVACY_POLICY_URL = "https://nuvio.tv/privacy-policy"

internal fun LazyListScope.settingsRootContent(
    isTablet: Boolean,
    onPlaybackClick: () -> Unit,
    onAppearanceClick: () -> Unit,
    onAdvancedClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onContentDiscoveryClick: () -> Unit,
    onIntegrationsClick: () -> Unit,
    onTrackingClick: () -> Unit,
    onSupportersContributorsClick: () -> Unit,
    onLicensesAttributionsClick: () -> Unit,
    onCheckForUpdatesClick: (() -> Unit)? = null,
    onTestUpdateBannerClick: (() -> Unit)? = null,
    onDownloadsClick: () -> Unit,
    onAccountClick: () -> Unit,
    onSwitchProfileClick: (() -> Unit)? = null,
    showAccountSection: Boolean = true,
    showGeneralSection: Boolean = true,
    showAboutSection: Boolean = true,
    showAdvancedSection: Boolean = true,
    showSupportersContributorsPage: Boolean = true,
) {
    if (showAccountSection) {
        item {
            SettingsSection(
                title = stringResource(Res.string.compose_settings_root_account_section),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    if (onSwitchProfileClick != null) {
                        SettingsNavigationRow(
                            title = stringResource(Res.string.compose_settings_root_switch_profile_title),
                            description = stringResource(Res.string.compose_settings_root_switch_profile_description),
                            icon = Icons.Rounded.People,
                            isTablet = isTablet,
                            onClick = onSwitchProfileClick,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                    }
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_account),
                        description = stringResource(Res.string.compose_settings_root_account_description),
                        icon = Icons.Rounded.AccountCircle,
                        isTablet = isTablet,
                        onClick = onAccountClick,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_tracking),
                        description = stringResource(Res.string.compose_settings_root_tracking_description),
                        icon = Icons.Default.Sync,
                        isTablet = isTablet,
                        onClick = onTrackingClick,
                    )
                }
            }
        }
    }
    if (showGeneralSection) {
        item {
            SettingsSection(
                title = stringResource(Res.string.compose_settings_root_general_section),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_appearance),
                        description = stringResource(Res.string.compose_settings_root_appearance_description),
                        icon = Icons.Rounded.Palette,
                        isTablet = isTablet,
                        onClick = onAppearanceClick,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_content_discovery),
                        description = stringResource(Res.string.compose_settings_root_content_discovery_description),
                        icon = Icons.Rounded.Extension,
                        isTablet = isTablet,
                        onClick = onContentDiscoveryClick,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_root_downloads_title),
                        description = stringResource(Res.string.compose_settings_root_downloads_description),
                        icon = Icons.Rounded.CloudDownload,
                        isTablet = isTablet,
                        onClick = onDownloadsClick,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_playback),
                        description = stringResource(Res.string.settings_playback_subtitle),
                        icon = Icons.Rounded.PlayArrow,
                        isTablet = isTablet,
                        onClick = onPlaybackClick,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_integrations),
                        description = stringResource(Res.string.compose_settings_root_integrations_description),
                        icon = Icons.Rounded.Link,
                        isTablet = isTablet,
                        onClick = onIntegrationsClick,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_notifications),
                        description = stringResource(Res.string.compose_settings_root_notifications_description),
                        icon = Icons.Rounded.Notifications,
                        isTablet = isTablet,
                        onClick = onNotificationsClick,
                    )
                }
            }
        }
    }
    if (showAboutSection) {
        item {
            val uriHandler = LocalUriHandler.current
            SettingsSection(
                title = stringResource(Res.string.compose_settings_root_about_section),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    if (showSupportersContributorsPage) {
                        SettingsNavigationRow(
                            title = stringResource(Res.string.compose_settings_page_supporters_contributors),
                            description = stringResource(Res.string.about_supporters_contributors_subtitle),
                            icon = Icons.Rounded.Favorite,
                            isTablet = isTablet,
                            onClick = onSupportersContributorsClick,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                    }
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_privacy_policy),
                        description = stringResource(Res.string.compose_settings_root_privacy_policy_description),
                        icon = Icons.Rounded.Policy,
                        isTablet = isTablet,
                        onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_licenses_attributions),
                        description = stringResource(Res.string.about_licenses_attributions_subtitle),
                        icon = Icons.Rounded.Info,
                        isTablet = isTablet,
                        onClick = onLicensesAttributionsClick,
                    )
                    if (onCheckForUpdatesClick != null) {
                        SettingsGroupDivider(isTablet = isTablet)
                        UpdateChannelSettingsRow(isTablet = isTablet)
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.compose_settings_root_check_updates_title),
                            description = stringResource(Res.string.compose_settings_root_check_updates_description),
                            icon = Icons.Rounded.CloudDownload,
                            isTablet = isTablet,
                            onClick = onCheckForUpdatesClick,
                        )
                    }
                    if (onTestUpdateBannerClick != null) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.updates_debug_test_title),
                            description = stringResource(Res.string.updates_debug_test_description),
                            icon = Icons.Rounded.BugReport,
                            isTablet = isTablet,
                            onClick = onTestUpdateBannerClick,
                        )
                    }
                }
            }
        }
    }
    if (showAdvancedSection) {
        item {
            SettingsSection(
                title = stringResource(Res.string.compose_settings_root_advanced_section),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_advanced),
                        description = stringResource(Res.string.compose_settings_root_advanced_description),
                        icon = Icons.Rounded.Tune,
                        isTablet = isTablet,
                        onClick = onAdvancedClick,
                    )
                }
            }
        }
    }
    item {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = if (isTablet) 20.dp else 16.dp),
        ) {
            if (showAboutSection) {
                MemberBrandWordmark(
                    height = if (isTablet) 30.dp else 26.dp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                androidx.compose.foundation.layout.Spacer(
                    modifier = Modifier.height(if (isTablet) 10.dp else 8.dp),
                )
            }
            Text(
                text = stringResource(Res.string.compose_about_made_with),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    Res.string.compose_about_version_format,
                    AppVersionConfig.VERSION_NAME,
                    AppVersionConfig.VERSION_CODE,
                ),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
