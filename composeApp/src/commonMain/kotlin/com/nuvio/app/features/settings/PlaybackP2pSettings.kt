package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.p2p.P2pConsentDialog
import com.nuvio.app.features.p2p.P2pCacheClearResult
import com.nuvio.app.features.p2p.P2pCacheSize
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.p2p.P2pTorrentProfile
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlaybackP2pSettings(
    isTablet: Boolean,
) {
    var showP2pConsentDialog by remember { mutableStateOf(false) }
    var showP2pProfileDialog by remember { mutableStateOf(false) }
    var showP2pCacheSizeDialog by remember { mutableStateOf(false) }
    val p2pSettings by remember {
        P2pSettingsRepository.ensureLoaded()
        P2pSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val p2pCacheState by P2pStreamingEngine.cacheState.collectAsStateWithLifecycle()
    val p2pStreamingState by P2pStreamingEngine.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    var p2pCacheClearResult by remember { mutableStateOf<P2pCacheClearResult?>(null) }
    var p2pCacheClearFailed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 12.dp)) {
        SettingsSection(
            title = stringResource(Res.string.settings_playback_section_p2p),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_p2p_title),
                    description = stringResource(Res.string.settings_p2p_subtitle),
                    checked = p2pSettings.p2pEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        if (enabled && !p2pSettings.p2pEnabled) {
                            showP2pConsentDialog = true
                        } else {
                            P2pSettingsRepository.setP2pEnabled(enabled)
                        }
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_p2p_hide_stats_title),
                    description = stringResource(Res.string.settings_p2p_hide_stats_subtitle),
                    checked = p2pSettings.hideTorrentStats,
                    isTablet = isTablet,
                    onCheckedChange = P2pSettingsRepository::setHideTorrentStats,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_p2p_profile_title),
                    description = p2pProfileLabel(p2pSettings.torrentProfile),
                    isTablet = isTablet,
                    onClick = { showP2pProfileDialog = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_p2p_cache_size_title),
                    description = p2pCacheSizeLabel(p2pSettings.cacheSize),
                    isTablet = isTablet,
                    onClick = { showP2pCacheSizeDialog = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                val cacheClearAvailable = p2pStreamingState !is P2pStreamingState.Connecting &&
                    p2pStreamingState !is P2pStreamingState.Streaming &&
                    !p2pCacheState.isClearing
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_p2p_clear_cache_title),
                    description = when {
                        p2pCacheState.isClearing ->
                            stringResource(Res.string.settings_p2p_clear_cache_clearing)
                        !cacheClearAvailable ->
                            stringResource(Res.string.settings_p2p_clear_cache_playback_active)
                        p2pCacheClearFailed ->
                            stringResource(Res.string.settings_p2p_clear_cache_failed)
                        p2pCacheClearResult != null -> stringResource(
                            Res.string.settings_p2p_clear_cache_done,
                            formatP2pCacheBytes(p2pCacheClearResult!!.reclaimedBytes),
                        )
                        !p2pCacheState.hasMeasurement ->
                            stringResource(Res.string.settings_p2p_clear_cache_usage_pending)
                        else -> stringResource(
                            Res.string.settings_p2p_clear_cache_usage,
                            formatP2pCacheBytes(p2pCacheState.usedBytes),
                        )
                    },
                    enabled = cacheClearAvailable,
                    isTablet = isTablet,
                    onClick = {
                        p2pCacheClearResult = null
                        p2pCacheClearFailed = false
                        coroutineScope.launch {
                            runCatching { P2pStreamingEngine.clearCache() }
                                .onSuccess { p2pCacheClearResult = it }
                                .onFailure { p2pCacheClearFailed = true }
                        }
                    },
                )
            }
        }
    }

    if (showP2pProfileDialog) {
        SettingsChoiceDialog(
            title = stringResource(Res.string.settings_p2p_profile_title),
            options = P2pTorrentProfile.entries,
            selected = p2pSettings.torrentProfile,
            label = { p2pProfileLabel(it) },
            description = { profile ->
                when (profile) {
                    P2pTorrentProfile.SOFT ->
                        stringResource(Res.string.settings_p2p_profile_soft_description)
                    P2pTorrentProfile.BALANCED ->
                        stringResource(Res.string.settings_p2p_profile_balanced_description)
                    P2pTorrentProfile.FAST ->
                        stringResource(Res.string.settings_p2p_profile_fast_description)
                }
            },
            onSelect = { profile ->
                P2pSettingsRepository.setTorrentProfile(profile)
                showP2pProfileDialog = false
            },
            onDismiss = { showP2pProfileDialog = false },
        )
    }

    if (showP2pCacheSizeDialog) {
        SettingsChoiceDialog(
            title = stringResource(Res.string.settings_p2p_cache_size_title),
            options = P2pCacheSize.entries,
            selected = p2pSettings.cacheSize,
            label = { p2pCacheSizeLabel(it) },
            onSelect = { size ->
                P2pSettingsRepository.setCacheSize(size)
                p2pCacheClearResult = null
                showP2pCacheSizeDialog = false
            },
            onDismiss = { showP2pCacheSizeDialog = false },
        )
    }

    if (showP2pConsentDialog) {
        P2pConsentDialog(
            onEnableP2p = {
                P2pSettingsRepository.setP2pEnabled(true)
                showP2pConsentDialog = false
            },
            onDismiss = { showP2pConsentDialog = false },
        )
    }
}

@Composable
private fun p2pProfileLabel(profile: P2pTorrentProfile): String = when (profile) {
    P2pTorrentProfile.SOFT -> stringResource(Res.string.settings_p2p_profile_soft)
    P2pTorrentProfile.BALANCED -> stringResource(Res.string.settings_p2p_profile_balanced)
    P2pTorrentProfile.FAST -> stringResource(Res.string.settings_p2p_profile_fast)
}

@Composable
private fun p2pCacheSizeLabel(size: P2pCacheSize): String = when (size) {
    P2pCacheSize.NONE -> stringResource(Res.string.settings_p2p_cache_none)
    P2pCacheSize.GB_2 -> stringResource(Res.string.settings_p2p_cache_2_gb)
    P2pCacheSize.GB_5 -> stringResource(Res.string.settings_p2p_cache_5_gb)
    P2pCacheSize.GB_10 -> stringResource(Res.string.settings_p2p_cache_10_gb)
}

private fun formatP2pCacheBytes(bytes: Long): String {
    val gibibyte = 1024.0 * 1024.0 * 1024.0
    val mebibyte = 1024.0 * 1024.0
    return if (bytes >= gibibyte) {
        "${kotlin.math.round(bytes / gibibyte * 10.0) / 10.0} GB"
    } else {
        "${kotlin.math.round(bytes / mebibyte * 10.0) / 10.0} MB"
    }
}
