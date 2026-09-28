package com.nuvio.app.features.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioInfoBadge
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioSurfaceCard
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun AddonsScreen(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
) {
    NuvioScreen(modifier = modifier) {
        stickyHeader {
            NuvioScreenHeader(
                title = title ?: stringResource(Res.string.addon_title),
                onBack = onBack,
            ) {
            }
        }
        item {
            AddonsSettingsPageContent()
        }
    }
}

@Composable
internal fun AddonsSettingsPageContent(
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        AddonRepository.initialize()
    }

    val uiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    var addonUrl by rememberSaveable { mutableStateOf("") }
    var formMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var installModalState by remember { mutableStateOf<AddonInstallModalState?>(null) }
    var addonPendingDeletionUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val enterAddonUrlMessage = stringResource(Res.string.addons_error_enter_url)
    val usePersonalMediaCopy = AppFeaturePolicy.personalMediaAddonCopyEnabled

    val overview = remember(uiState.addons) { uiState.addons.toOverview() }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(stringResource(Res.string.addons_section_overview))
        OverviewCard(overview = overview)

        SectionHeader(stringResource(Res.string.addons_section_add_addon))
        AddAddonCard(
            addonUrl = addonUrl,
            formMessage = formMessage,
            usePersonalMediaCopy = usePersonalMediaCopy,
            onAddonUrlChange = {
                addonUrl = it
                formMessage = null
            },
            onAddClick = {
                val requestedUrl = addonUrl.trim()
                if (requestedUrl.isBlank()) {
                    formMessage = enterAddonUrlMessage
                    return@AddAddonCard
                }

                formMessage = null
                installModalState = AddonInstallModalState.Checking
                coroutineScope.launch {
                    val result = AddonRepository.addAddon(requestedUrl)
                    installModalState = when (result) {
                        is AddAddonResult.Success -> {
                            addonUrl = ""
                            AddonInstallModalState.Success(result.manifest.name)
                        }

                        is AddAddonResult.Error -> {
                            AddonInstallModalState.Error(result.message)
                        }
                    }
                }
            },
        )

        SectionHeader(stringResource(Res.string.addons_section_installed))
        if (uiState.addons.isEmpty()) {
            EmptyStateCard(usePersonalMediaCopy = usePersonalMediaCopy)
        } else {
            val lastIndex = uiState.addons.lastIndex
            uiState.addons.forEachIndexed { index, addon ->
                val manifest = addon.manifest
                val behaviorHints = manifest?.behaviorHints
                val showConfigureAction = behaviorHints?.configurable == true || behaviorHints?.configurationRequired == true
                val configureUrl = addon.manifestUrl.toConfigureUrl()
                InstalledAddonCard(
                    addon = addon,
                    onMoveUpClick = if (index > 0) {
                        { AddonRepository.moveAddon(index, index - 1) }
                    } else {
                        null
                    },
                    onMoveDownClick = if (index < lastIndex) {
                        { AddonRepository.moveAddon(index, index + 1) }
                    } else {
                        null
                    },
                    onRefreshClick = {
                        AddonRepository.refreshAddon(
                            manifestUrl = addon.manifestUrl,
                            forceRefresh = true,
                        )
                    },
                    onEnabledChange = { enabled ->
                        AddonRepository.setAddonEnabled(addon.manifestUrl, enabled)
                    },
                    onConfigureClick = if (showConfigureAction && !configureUrl.isNullOrBlank()) {
                        {
                            runCatching {
                                uriHandler.openUri(configureUrl)
                            }
                        }
                    } else {
                        null
                    },
                    onDeleteClick = { addonPendingDeletionUrl = addon.manifestUrl },
                )
            }
        }
    }

    val pendingDeletionUrl = addonPendingDeletionUrl
    if (pendingDeletionUrl != null) {
        NuvioStatusModal(
            title = stringResource(Res.string.addons_delete_confirm_title),
            message = stringResource(Res.string.action_delete_confirm_message),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                AddonRepository.removeAddon(pendingDeletionUrl)
                addonPendingDeletionUrl = null
            },
            onDismiss = { addonPendingDeletionUrl = null },
        )
    }

    val modalState = installModalState
    if (modalState != null) {
        val modalTitle = when (modalState) {
            AddonInstallModalState.Checking -> stringResource(Res.string.addons_modal_checking_title)
            is AddonInstallModalState.Success -> stringResource(Res.string.addons_modal_success_title)
            is AddonInstallModalState.Error -> stringResource(Res.string.addons_modal_failure_title)
        }
        val modalMessage = when (modalState) {
            AddonInstallModalState.Checking -> stringResource(Res.string.addons_modal_checking_message)
            is AddonInstallModalState.Success -> stringResource(
                Res.string.addons_modal_success_message,
                modalState.addonName,
            )
            is AddonInstallModalState.Error -> modalState.reason
        }
        val modalConfirmText = when (modalState) {
            AddonInstallModalState.Checking -> stringResource(Res.string.addon_installing)
            is AddonInstallModalState.Success -> stringResource(Res.string.action_done)
            is AddonInstallModalState.Error -> stringResource(Res.string.action_close)
        }
        NuvioStatusModal(
            title = modalTitle,
            message = modalMessage,
            isVisible = true,
            isBusy = modalState.isBusy,
            confirmText = modalConfirmText,
            onConfirm = {
                if (!modalState.isBusy) {
                    installModalState = null
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    NuvioSectionLabel(text = text)
}

@Composable
private fun OverviewCard(overview: AddonOverview) {
    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverviewStat(
                value = overview.totalAddons.toString(),
                label = stringResource(Res.string.addons_overview_addons),
                modifier = Modifier.weight(1f),
            )
            VerticalSeparator()
            OverviewStat(
                value = overview.activeAddons.toString(),
                label = stringResource(Res.string.addons_overview_active),
                modifier = Modifier.weight(1f),
            )
            VerticalSeparator()
            OverviewStat(
                value = overview.totalCatalogs.toString(),
                label = stringResource(Res.string.addons_overview_catalogs),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OverviewStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VerticalSeparator() {
    Box(
        modifier = Modifier
            .padding(horizontal = 10.dp)
            .width(1.dp)
            .height(64.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

@Composable
private fun AddAddonCard(
    addonUrl: String,
    formMessage: String?,
    usePersonalMediaCopy: Boolean,
    onAddonUrlChange: (String) -> Unit,
    onAddClick: () -> Unit,
) {
    NuvioSurfaceCard {
        NuvioInputField(
            value = addonUrl,
            onValueChange = onAddonUrlChange,
            placeholder = stringResource(
                if (usePersonalMediaCopy) {
                    Res.string.addons_appstore_input_placeholder
                } else {
                    Res.string.addons_input_placeholder
                },
            ),
        )
        Spacer(modifier = Modifier.height(18.dp))
        NuvioPrimaryButton(
            text = stringResource(
                if (usePersonalMediaCopy) {
                    Res.string.addons_appstore_install_button
                } else {
                    Res.string.addons_install_button
                },
            ),
            enabled = addonUrl.isNotBlank(),
            onClick = onAddClick,
        )
        if (usePersonalMediaCopy) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(Res.string.addons_appstore_add_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        formMessage?.let { message ->
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private sealed interface AddonInstallModalState {
    val isBusy: Boolean

    data object Checking : AddonInstallModalState {
        override val isBusy: Boolean = true
    }

    data class Success(
        val addonName: String,
    ) : AddonInstallModalState {
        override val isBusy: Boolean = false
    }

    data class Error(
        val reason: String,
    ) : AddonInstallModalState {
        override val isBusy: Boolean = false
    }
}

@Composable
private fun EmptyStateCard(
    usePersonalMediaCopy: Boolean,
) {
    NuvioSurfaceCard {
        Text(
            text = stringResource(
                if (usePersonalMediaCopy) {
                    Res.string.addons_appstore_empty_title
                } else {
                    Res.string.addons_empty_title
                },
            ),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (usePersonalMediaCopy) {
                    Res.string.addons_appstore_empty_subtitle
                } else {
                    Res.string.addons_empty_subtitle
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InstalledAddonCard(
    addon: ManagedAddon,
    onMoveUpClick: (() -> Unit)?,
    onMoveDownClick: (() -> Unit)?,
    onRefreshClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onConfigureClick: (() -> Unit)?,
    onDeleteClick: () -> Unit,
) {
    val manifest = addon.manifest

    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            AddonIconBadge(
                imageUrl = manifest?.logoUrl,
                icon = Icons.Rounded.Extension,
                tint = if (manifest != null) Color(0xFF71BDE8) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = addon.displayTitle,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                manifest?.version?.let { version ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.addons_version_format, version),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = addon.enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onMoveUpClick?.let { onMoveUp ->
                NuvioIconActionButton(
                    icon = Icons.Rounded.ArrowUpward,
                    contentDescription = stringResource(Res.string.addons_move_up),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onMoveUp,
                )
            }
            onMoveDownClick?.let { onMoveDown ->
                NuvioIconActionButton(
                    icon = Icons.Rounded.ArrowDownward,
                    contentDescription = stringResource(Res.string.addons_move_down),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onMoveDown,
                )
            }
            NuvioIconActionButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = stringResource(Res.string.addons_refresh),
                tint = MaterialTheme.colorScheme.primary,
                onClick = onRefreshClick,
            )
            onConfigureClick?.let { onConfigure ->
                NuvioIconActionButton(
                    icon = Icons.Rounded.Settings,
                    contentDescription = stringResource(Res.string.addons_configure),
                    tint = MaterialTheme.colorScheme.tertiary,
                    onClick = onConfigure,
                )
            }
            NuvioIconActionButton(
                icon = Icons.Rounded.Delete,
                contentDescription = stringResource(Res.string.addons_delete),
                tint = MaterialTheme.colorScheme.error,
                onClick = onDeleteClick,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(18.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NuvioInfoBadge(
                text = when {
                    !addon.enabled -> stringResource(Res.string.addons_badge_disabled)
                    addon.isRefreshing -> stringResource(Res.string.addons_badge_refreshing)
                    manifest != null -> stringResource(Res.string.addons_badge_active)
                    else -> stringResource(Res.string.addons_badge_unavailable)
                },
            )
            manifest?.let {
                NuvioInfoBadge(text = stringResource(Res.string.addons_badge_resources, it.resources.size))
                NuvioInfoBadge(text = stringResource(Res.string.addons_badge_catalogs, it.catalogs.size))
                if (it.behaviorHints.configurable) {
                    NuvioInfoBadge(text = stringResource(Res.string.addons_badge_configurable))
                }
            }
        }

        when {
            addon.isRefreshing -> {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(Res.string.addons_loading_manifest_details),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            addon.errorMessage != null && manifest == null -> {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = addon.errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            manifest != null -> {
                Spacer(modifier = Modifier.height(16.dp))
                manifest.description.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                }

                Text(
                    text = manifestSummary(manifest),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                addon.errorMessage?.let { staleError ->
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = staleError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddonIconBadge(
    imageUrl: String?,
    icon: ImageVector,
    tint: Color,
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@Composable
private fun manifestSummary(manifest: AddonManifest): String {
    val resources = manifest.resources.joinToString(separator = ", ") { it.name }
    val types = manifest.types.joinToString(separator = " / ") { it.replaceFirstChar(Char::uppercase) }
    return buildString {
        append(types)
        append(" • ")
        append(resources)
        if (manifest.idPrefixes.isNotEmpty()) {
            append(" • ")
            append(stringResource(Res.string.addons_summary_id_rules, manifest.idPrefixes.size))
        }
        if (manifest.behaviorHints.p2p) {
            append(" • P2P")
        }
    }
}

private fun String.toConfigureUrl(): String {
    val base = substringBefore("?").trimEnd('/')
    return if (base.endsWith("/manifest.json")) {
        base.removeSuffix("/manifest.json") + "/configure"
    } else {
        "$base/configure"
    }
}
