package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_location_change
import nuvio.composeapp.generated.resources.downloads_location_change_description
import nuvio.composeapp.generated.resources.downloads_location_change_failed
import nuvio.composeapp.generated.resources.downloads_location_label
import nuvio.composeapp.generated.resources.downloads_location_open
import nuvio.composeapp.generated.resources.downloads_open_directory_failed
import nuvio.composeapp.generated.resources.compose_settings_root_downloads_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DownloadLocationSettingsScreen(
    onBack: () -> Unit,
) {
    val locationLabel by DownloadLocationState.locationLabel.collectAsStateWithLifecycle()
    val openFolderFailedText = stringResource(Res.string.downloads_open_directory_failed)
    val changeFolderFailedText = stringResource(Res.string.downloads_location_change_failed)

    LaunchedEffect(Unit) {
        DownloadLocationState.refresh()
    }

    NuvioScreen(modifier = Modifier.fillMaxSize()) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.compose_settings_root_downloads_title),
                onBack = onBack,
            )
        }
        item {
            SettingsSection(
                title = stringResource(Res.string.downloads_location_label),
                isTablet = false,
            ) {
                SettingsGroup(isTablet = false) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.downloads_location_open),
                        description = locationLabel,
                        icon = Icons.Rounded.Folder,
                        isTablet = false,
                        onClick = {
                            if (!DownloadsPlatformDownloader.openDownloadsDirectory()) {
                                NuvioToastController.show(openFolderFailedText)
                            }
                        },
                    )
                    if (!isIos) {
                        SettingsGroupDivider(isTablet = false)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.downloads_location_change),
                            description = stringResource(Res.string.downloads_location_change_description),
                            icon = Icons.Rounded.Edit,
                            isTablet = false,
                            onClick = {
                                if (!DownloadLocationManager.requestFolderPicker()) {
                                    NuvioToastController.show(changeFolderFailedText)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
