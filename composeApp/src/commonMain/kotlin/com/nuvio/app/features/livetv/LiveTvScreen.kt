package com.nuvio.app.features.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioKeyboardFocusIndicator
import com.nuvio.app.features.settings.NuvioEnhancedBackupFileBridge
import com.nuvio.app.features.livetv.LiveTvIncomingSourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.live_tv_add_source
import nuvio.composeapp.generated.resources.live_tv_all_channels
import nuvio.composeapp.generated.resources.live_tv_channel_count
import nuvio.composeapp.generated.resources.live_tv_channels
import nuvio.composeapp.generated.resources.live_tv_disconnect
import nuvio.composeapp.generated.resources.live_tv_empty_description
import nuvio.composeapp.generated.resources.live_tv_empty_title
import nuvio.composeapp.generated.resources.live_tv_favorite
import nuvio.composeapp.generated.resources.live_tv_favorites
import nuvio.composeapp.generated.resources.live_tv_guide_title
import nuvio.composeapp.generated.resources.live_tv_load
import nuvio.composeapp.generated.resources.live_tv_load_file
import nuvio.composeapp.generated.resources.live_tv_recent_channel_cta
import nuvio.composeapp.generated.resources.live_tv_recent_channel_title
import nuvio.composeapp.generated.resources.live_tv_refresh
import nuvio.composeapp.generated.resources.live_tv_search
import nuvio.composeapp.generated.resources.live_tv_choose_category
import nuvio.composeapp.generated.resources.live_tv_provider_settings_title
import nuvio.composeapp.generated.resources.live_tv_source_hint
import nuvio.composeapp.generated.resources.live_tv_source_title
import nuvio.composeapp.generated.resources.live_tv_stalker_mac_hint
import nuvio.composeapp.generated.resources.live_tv_stalker_password_hint
import nuvio.composeapp.generated.resources.live_tv_stalker_portal_hint
import nuvio.composeapp.generated.resources.live_tv_stalker_settings_description
import nuvio.composeapp.generated.resources.live_tv_stalker_settings_title
import nuvio.composeapp.generated.resources.live_tv_stalker_username_hint
import nuvio.composeapp.generated.resources.live_tv_source_m3u
import nuvio.composeapp.generated.resources.live_tv_source_stalker
import nuvio.composeapp.generated.resources.live_tv_source_xtream
import nuvio.composeapp.generated.resources.live_tv_settings
import nuvio.composeapp.generated.resources.live_tv_title
import nuvio.composeapp.generated.resources.live_tv_xtream_password_hint
import nuvio.composeapp.generated.resources.live_tv_xtream_server_hint
import nuvio.composeapp.generated.resources.live_tv_xtream_settings_description
import nuvio.composeapp.generated.resources.live_tv_xtream_username_hint
import org.jetbrains.compose.resources.stringResource

@Composable
fun LiveTvScreen(
    modifier: Modifier = Modifier,
    onChannelClick: (LiveTvChannel) -> Unit = {},
) {
    val uiState by remember {
        LiveTvRepository.ensureLoaded()
        LiveTvRepository.uiState
    }.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var sourceUrl by rememberSaveable { mutableStateOf(uiState.sourceUrl) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedGroup by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var showingGuide by rememberSaveable { mutableStateOf(false) }
    var editingSource by rememberSaveable { mutableStateOf(uiState.sourceUrl.isBlank()) }
    var showingAdvancedSettings by rememberSaveable { mutableStateOf(false) }
    var settingsSourceTypeName by rememberSaveable { mutableStateOf(uiState.sourceType.name) }
    var stalkerPortalUrl by rememberSaveable { mutableStateOf(uiState.stalkerSettings.portalUrl) }
    var stalkerMacAddress by rememberSaveable { mutableStateOf(uiState.stalkerSettings.macAddress) }
    var stalkerUsername by rememberSaveable { mutableStateOf(uiState.stalkerSettings.username) }
    var stalkerPassword by rememberSaveable { mutableStateOf(uiState.stalkerSettings.password) }
    var xtreamServerUrl by rememberSaveable { mutableStateOf(uiState.xtreamSettings.serverUrl) }
    var xtreamUsername by rememberSaveable { mutableStateOf(uiState.xtreamSettings.username) }
    var xtreamPassword by rememberSaveable { mutableStateOf(uiState.xtreamSettings.password) }
    var fileImportError by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        LiveTvRepository.setEpgLoadingActive(true)
        onDispose { LiveTvRepository.setEpgLoadingActive(false) }
    }

    LaunchedEffect(uiState.sourceUrl) {
        if (sourceUrl.isBlank()) sourceUrl = uiState.sourceUrl
    }
    LaunchedEffect(uiState.stalkerSettings) {
        if (stalkerPortalUrl.isBlank()) stalkerPortalUrl = uiState.stalkerSettings.portalUrl
        if (stalkerMacAddress.isBlank()) stalkerMacAddress = uiState.stalkerSettings.macAddress
        if (stalkerUsername.isBlank()) stalkerUsername = uiState.stalkerSettings.username
        if (stalkerPassword.isBlank()) stalkerPassword = uiState.stalkerSettings.password
    }
    LaunchedEffect(uiState.xtreamSettings) {
        if (xtreamServerUrl.isBlank()) xtreamServerUrl = uiState.xtreamSettings.serverUrl
        if (xtreamUsername.isBlank()) xtreamUsername = uiState.xtreamSettings.username
        if (xtreamPassword.isBlank()) xtreamPassword = uiState.xtreamSettings.password
    }
    LaunchedEffect(Unit) {
        if (uiState.channels.isEmpty() && !uiState.isLoading) {
            when {
                uiState.sourceType == LiveTvSourceType.Xtream && uiState.xtreamSettings.isConfigured ->
                    LiveTvRepository.loadXtream(uiState.xtreamSettings)
                uiState.sourceType == LiveTvSourceType.Stalker && uiState.stalkerSettings.isConfigured ->
                    LiveTvRepository.loadStalker(uiState.stalkerSettings)
                LiveTvRepository.hasStoredLocalPlaylist() ->
                    LiveTvRepository.loadStoredLocalPlaylist()
                uiState.sourceUrl.isNotBlank() ->
                    LiveTvRepository.load(uiState.sourceUrl)
            }
        }
    }

    val groups = remember(uiState.channels) {
        uiState.channels
            .filterNot { isLikelyCategoryHeading(it.name) }
            .map { it.group }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }
    val recentChannel = remember(uiState.recentChannel, uiState.channels) {
        uiState.recentChannel?.let { recent ->
            val currentMatch = uiState.channels.firstOrNull { it.streamUrl == recent.streamUrl }
            if (currentMatch != null) {
                recent.copy(
                    name = currentMatch.name,
                    logoUrl = currentMatch.logoUrl ?: recent.logoUrl,
                    group = currentMatch.group,
                    tvgId = currentMatch.tvgId ?: recent.tvgId,
                )
            } else {
                recent
            }
        }
    }
    val visibleChannels = remember(uiState.channels, uiState.favoriteUrls, query, selectedGroup, favoritesOnly) {
        uiState.channels.filterNot { isLikelyCategoryHeading(it.name) }.filter { channel ->
            (selectedGroup.isBlank() || channel.group == selectedGroup) &&
                (!favoritesOnly || channel.streamUrl in uiState.favoriteUrls) &&
                (query.isBlank() || channel.name.contains(query, ignoreCase = true))
        }
    }
    val loadSource: () -> Unit = {
        scope.launch {
            fileImportError = null
            if (sourceUrl.trim().startsWith("magnet:", ignoreCase = true)) {
                LiveTvIncomingSourceRepository.submitText(sourceUrl)
                editingSource = false
            } else {
                val isUnchangedSavedLocalPlaylist =
                    uiState.sourceType == LiveTvSourceType.M3u &&
                        sourceUrl.trim() == uiState.sourceUrl &&
                        LiveTvStorage.loadLocalPlaylistData().orEmpty().isNotBlank()
                val result = if (isUnchangedSavedLocalPlaylist) {
                    LiveTvRepository.loadStoredLocalPlaylist()
                } else {
                    LiveTvRepository.load(sourceUrl)
                }
                if (result.isSuccess) {
                    editingSource = false
                    showingAdvancedSettings = false
                    selectedGroup = ""
                    favoritesOnly = false
                }
            }
        }
        Unit
    }
    val chooseLocalPlaylistFile: () -> Unit = {
        fileImportError = null
        NuvioEnhancedBackupFileBridge.importBackup { result ->
            result
                .onSuccess { payload ->
                    scope.launch {
                        if (LiveTvRepository.loadLocalPlaylist("Selected M3U file", payload).isSuccess) {
                            editingSource = false
                            showingAdvancedSettings = false
                            sourceUrl = LiveTvRepository.uiState.value.sourceUrl
                            selectedGroup = ""
                            favoritesOnly = false
                        }
                    }
                }
                .onFailure { error ->
                    fileImportError = error.message ?: "M3U file could not be read."
                }
        }
        Unit
    }
    val loadStalkerSource: () -> Unit = {
        scope.launch {
            val settings = LiveTvStalkerSettings(
                portalUrl = stalkerPortalUrl,
                macAddress = stalkerMacAddress,
                username = stalkerUsername,
                password = stalkerPassword,
            )
            if (LiveTvRepository.loadStalker(settings).isSuccess) {
                showingAdvancedSettings = false
                editingSource = false
                selectedGroup = ""
                favoritesOnly = false
            }
        }
        Unit
    }
    val loadXtreamSource: () -> Unit = {
        scope.launch {
            val settings = LiveTvXtreamSettings(
                serverUrl = xtreamServerUrl,
                username = xtreamUsername,
                password = xtreamPassword,
            )
            if (LiveTvRepository.loadXtream(settings).isSuccess) {
                showingAdvancedSettings = false
                editingSource = false
                selectedGroup = ""
                favoritesOnly = false
            }
        }
        Unit
    }
    val playChannel: (LiveTvChannel) -> Unit = { channel ->
        scope.launch {
            try {
                onChannelClick(LiveTvRepository.prepareForPlayback(channel))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LiveTvRepository.reportPlaybackError(error)
            }
        }
    }

    if (showingGuide) {
        LiveTvFavoritesGuide(
            channels = uiState.channels,
            favoriteUrls = uiState.favoriteUrls,
            programmesByChannel = uiState.programmesByChannel,
            isEpgLoading = uiState.isEpgLoading,
            onChannelClick = playChannel,
            onBack = { showingGuide = false },
            modifier = modifier,
        )
        return
    }

    NuvioScreen(
        modifier = modifier,
        horizontalPadding = 16.dp,
    ) {
        if (showingAdvancedSettings) {
            item {
                NuvioScreenHeader(
                    title = stringResource(Res.string.live_tv_provider_settings_title),
                    includeStatusBarPadding = false,
                    onBack = { showingAdvancedSettings = false },
                )
            }
            item {
                LiveTvSourceTypeSelector(
                    selectedSourceType = LiveTvSourceType.entries.firstOrNull {
                        it.name == settingsSourceTypeName
                    } ?: LiveTvSourceType.M3u,
                    onSelected = { sourceType ->
                        if (
                            sourceType == LiveTvSourceType.M3u &&
                            uiState.sourceType != LiveTvSourceType.M3u &&
                            sourceUrl == uiState.sourceUrl
                        ) {
                            sourceUrl = ""
                        }
                        settingsSourceTypeName = sourceType.name
                    },
                )
            }
            when (LiveTvSourceType.entries.firstOrNull { it.name == settingsSourceTypeName }) {
                LiveTvSourceType.M3u, null -> item {
                    LiveTvSourceCard(
                        sourceUrl = sourceUrl,
                        isLoading = uiState.isLoading,
                        errorMessage = uiState.errorMessage,
                        fileImportError = fileImportError,
                        hasConnectedSource = uiState.sourceType == LiveTvSourceType.M3u && uiState.channels.isNotEmpty(),
                        onSourceUrlChange = { sourceUrl = it },
                        onLoad = loadSource,
                        onChooseFile = chooseLocalPlaylistFile,
                        onDisconnect = {
                            LiveTvRepository.disconnect()
                            fileImportError = null
                            sourceUrl = ""
                            editingSource = true
                            showingAdvancedSettings = false
                            favoritesOnly = false
                            selectedGroup = ""
                        },
                    )
                }

                LiveTvSourceType.Stalker -> item {
                    LiveTvStalkerSettingsCard(
                        portalUrl = stalkerPortalUrl,
                        macAddress = stalkerMacAddress,
                        username = stalkerUsername,
                        password = stalkerPassword,
                        isLoading = uiState.isLoading,
                        errorMessage = uiState.errorMessage,
                        hasConnectedSource = uiState.sourceType == LiveTvSourceType.Stalker && uiState.channels.isNotEmpty(),
                        onPortalUrlChange = { stalkerPortalUrl = it },
                        onMacAddressChange = { stalkerMacAddress = it },
                        onUsernameChange = { stalkerUsername = it },
                        onPasswordChange = { stalkerPassword = it },
                        onLoad = loadStalkerSource,
                        onDisconnect = {
                            LiveTvRepository.disconnect()
                            editingSource = true
                            showingAdvancedSettings = false
                            favoritesOnly = false
                            selectedGroup = ""
                        },
                    )
                }

                LiveTvSourceType.Xtream -> item {
                    LiveTvXtreamSettingsCard(
                        serverUrl = xtreamServerUrl,
                        username = xtreamUsername,
                        password = xtreamPassword,
                        isLoading = uiState.isLoading,
                        errorMessage = uiState.errorMessage,
                        hasConnectedSource = uiState.sourceType == LiveTvSourceType.Xtream && uiState.channels.isNotEmpty(),
                        onServerUrlChange = { xtreamServerUrl = it },
                        onUsernameChange = { xtreamUsername = it },
                        onPasswordChange = { xtreamPassword = it },
                        onLoad = loadXtreamSource,
                        onDisconnect = {
                            LiveTvRepository.disconnect()
                            editingSource = true
                            showingAdvancedSettings = false
                            favoritesOnly = false
                            selectedGroup = ""
                        },
                    )
                }
            }
            return@NuvioScreen
        }

        item {
            NuvioScreenHeader(
                title = stringResource(Res.string.live_tv_title),
                includeStatusBarPadding = false,
                actions = {
                    NuvioIconActionButton(
                        icon = Icons.Rounded.Settings,
                        contentDescription = stringResource(Res.string.live_tv_settings),
                        onClick = {
                            settingsSourceTypeName = uiState.sourceType.name
                            showingAdvancedSettings = true
                        },
                    )
                    if (uiState.channels.isNotEmpty()) {
                        NuvioIconActionButton(
                            icon = Icons.Rounded.Tv,
                            contentDescription = stringResource(Res.string.live_tv_guide_title),
                            onClick = { showingGuide = true },
                        )
                        NuvioIconActionButton(
                            icon = Icons.Rounded.Refresh,
                            contentDescription = stringResource(Res.string.live_tv_refresh),
                            onClick = {
                                scope.launch {
                                    when {
                                        uiState.sourceType == LiveTvSourceType.Xtream ->
                                            LiveTvRepository.loadXtream(uiState.xtreamSettings)
                                        uiState.sourceType == LiveTvSourceType.Stalker ->
                                            LiveTvRepository.loadStalker(uiState.stalkerSettings)
                                        LiveTvStorage.loadLocalPlaylistData().orEmpty().isNotBlank() ->
                                            LiveTvRepository.loadStoredLocalPlaylist()
                                        else ->
                                            LiveTvRepository.load(uiState.sourceUrl)
                                    }
                                }
                            },
                        )
                        NuvioIconActionButton(
                            icon = Icons.Rounded.AddLink,
                            contentDescription = stringResource(Res.string.live_tv_add_source),
                            onClick = { editingSource = !editingSource },
                        )
                    }
                },
            )
        }

        if (
            !editingSource &&
            uiState.channels.isNotEmpty() &&
            !uiState.errorMessage.isNullOrBlank()
        ) {
            item {
                Text(
                    text = uiState.errorMessage.orEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.nuvio.colors.danger,
                )
            }
        }

        if (editingSource || uiState.sourceUrl.isBlank()) {
            item {
                LiveTvSourceCard(
                    sourceUrl = sourceUrl,
                    isLoading = uiState.isLoading,
                    errorMessage = uiState.errorMessage,
                    fileImportError = fileImportError,
                    hasConnectedSource = uiState.channels.isNotEmpty(),
                    onSourceUrlChange = { sourceUrl = it },
                    onLoad = loadSource,
                    onChooseFile = chooseLocalPlaylistFile,
                    onDisconnect = {
                        LiveTvRepository.disconnect()
                        fileImportError = null
                        sourceUrl = ""
                        editingSource = true
                        favoritesOnly = false
                        selectedGroup = ""
                    },
                )
            }
        }

        if (recentChannel != null) {
            item {
                LiveTvRecentChannelCard(
                    channel = recentChannel,
                    onClick = {
                        playChannel(
                            uiState.channels.firstOrNull { it.streamUrl == recentChannel.streamUrl }
                                ?: LiveTvChannel(
                                    id = recentChannel.streamUrl,
                                    name = recentChannel.name,
                                    streamUrl = recentChannel.streamUrl,
                                    tvgId = recentChannel.tvgId,
                                    logoUrl = recentChannel.logoUrl,
                                    group = recentChannel.group,
                                ),
                        )
                    },
                )
            }
        }

        if (uiState.channels.isNotEmpty()) {
            item {
                NuvioInputField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(Res.string.live_tv_search),
                    trailingContent = {
                        if (query.isBlank()) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = MaterialTheme.nuvio.colors.textMuted,
                            )
                        } else {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = null,
                                    tint = MaterialTheme.nuvio.colors.textMuted,
                                )
                            }
                        }
                    },
                )
            }

            item {
                LiveTvFilterRow(
                    groups = groups,
                    selectedGroup = selectedGroup,
                    favoritesOnly = favoritesOnly,
                    allLabel = stringResource(Res.string.live_tv_all_channels),
                    favoritesLabel = stringResource(Res.string.live_tv_favorites),
                    categoryLabel = stringResource(Res.string.live_tv_choose_category),
                    onAllSelected = {
                        favoritesOnly = false
                        selectedGroup = ""
                    },
                    onFavoritesSelected = {
                        favoritesOnly = true
                        selectedGroup = ""
                    },
                    onGroupSelected = {
                        favoritesOnly = false
                        selectedGroup = it
                    },
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NuvioSectionLabel(text = stringResource(Res.string.live_tv_channels))
                    Text(
                        text = stringResource(Res.string.live_tv_channel_count, visibleChannels.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.nuvio.colors.textMuted,
                    )
                }
            }

            items(
                count = visibleChannels.size,
                key = { index -> visibleChannels[index].id },
            ) { index ->
                LiveTvChannelRow(
                    channel = visibleChannels[index],
                    programme = visibleChannels[index].tvgId?.let(uiState.currentProgrammes::get),
                    isFavorite = visibleChannels[index].streamUrl in uiState.favoriteUrls,
                    onFavoriteClick = { LiveTvRepository.toggleFavorite(visibleChannels[index]) },
                    onClick = { playChannel(visibleChannels[index]) },
                )
            }
        } else if (!uiState.isLoading && !editingSource) {
            item {
                LiveTvEmptyState(onAddSource = { editingSource = true })
            }
        }
    }
}

@Composable
private fun LiveTvRecentChannelCard(
    channel: LiveTvRecentChannel,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .nuvioKeyboardFocusIndicator(tokens.shapes.card),
        onClick = onClick,
        color = tokens.colors.surface,
        shape = tokens.shapes.card,
        border = BorderStroke(NuvioTokens.Border.thin, tokens.colors.borderSubtle),
    ) {
        Row(
            modifier = Modifier.padding(tokens.spacing.cardPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(tokens.colors.overlaySelected)
                    .border(
                        width = NuvioTokens.Border.thin,
                        color = tokens.colors.borderSubtle,
                        shape = RoundedCornerShape(16.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (!channel.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = channel.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(7.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Tv,
                        contentDescription = null,
                        tint = tokens.colors.accent,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = stringResource(Res.string.live_tv_recent_channel_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                )
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel.group.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(tokens.colors.accent),
                        )
                        Text(
                            text = channel.group,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

            }

            Surface(
                color = tokens.colors.overlaySelected,
                shape = tokens.shapes.chip,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = tokens.colors.accent,
                    )
                    Text(
                        text = stringResource(Res.string.live_tv_recent_channel_cta),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTvSourceCard(
    sourceUrl: String,
    isLoading: Boolean,
    errorMessage: String?,
    fileImportError: String?,
    hasConnectedSource: Boolean,
    onSourceUrlChange: (String) -> Unit,
    onLoad: () -> Unit,
    onChooseFile: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tokens.colors.surface,
        shape = tokens.shapes.card,
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            ) {
                Box(
                    modifier = Modifier
                        .size(NuvioTokens.Space.s48)
                        .clip(tokens.shapes.compactCard)
                        .background(tokens.colors.overlaySelected),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AddLink,
                        contentDescription = null,
                        tint = tokens.colors.accent,
                    )
                }
                Column {
                    Text(
                        text = stringResource(Res.string.live_tv_source_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(Res.string.live_tv_empty_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }
            NuvioInputField(
                value = sourceUrl,
                onValueChange = onSourceUrlChange,
                placeholder = stringResource(Res.string.live_tv_source_hint),
            )
            (errorMessage ?: fileImportError)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
            }
            NuvioPrimaryButton(
                text = stringResource(Res.string.live_tv_load),
                enabled = sourceUrl.isNotBlank() && !isLoading,
                onClick = onLoad,
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                onClick = onChooseFile,
            ) {
                Text(text = stringResource(Res.string.live_tv_load_file))
            }
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(NuvioTokens.Icon.md)
                        .align(Alignment.CenterHorizontally),
                    color = tokens.colors.accent,
                    strokeWidth = 2.dp,
                )
            }
            if (hasConnectedSource) {
                Text(
                    text = stringResource(Res.string.live_tv_disconnect),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable(onClick = onDisconnect)
                        .padding(8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.colors.danger,
                )
            }
        }
    }
}

@Composable
private fun LiveTvStalkerSettingsCard(
    portalUrl: String,
    macAddress: String,
    username: String,
    password: String,
    isLoading: Boolean,
    errorMessage: String?,
    hasConnectedSource: Boolean,
    onPortalUrlChange: (String) -> Unit,
    onMacAddressChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLoad: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tokens.colors.surface,
        shape = tokens.shapes.card,
        border = BorderStroke(NuvioTokens.Border.thin, tokens.colors.borderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            ) {
                Box(
                    modifier = Modifier
                        .size(NuvioTokens.Space.s48)
                        .clip(tokens.shapes.compactCard)
                        .background(tokens.colors.overlaySelected),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = null,
                        tint = tokens.colors.accent,
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.live_tv_source_stalker),
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(Res.string.live_tv_stalker_settings_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }

            NuvioInputField(
                value = portalUrl,
                onValueChange = onPortalUrlChange,
                placeholder = stringResource(Res.string.live_tv_stalker_portal_hint),
            )
            NuvioInputField(
                value = macAddress,
                onValueChange = onMacAddressChange,
                placeholder = stringResource(Res.string.live_tv_stalker_mac_hint),
            )
            NuvioInputField(
                value = username,
                onValueChange = onUsernameChange,
                placeholder = stringResource(Res.string.live_tv_stalker_username_hint),
            )
            NuvioInputField(
                value = password,
                onValueChange = onPasswordChange,
                placeholder = stringResource(Res.string.live_tv_stalker_password_hint),
            )

            errorMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
            }
            NuvioPrimaryButton(
                text = stringResource(Res.string.live_tv_load),
                enabled = portalUrl.isNotBlank() && macAddress.isNotBlank() && !isLoading,
                onClick = onLoad,
            )
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(NuvioTokens.Icon.md)
                        .align(Alignment.CenterHorizontally),
                    color = tokens.colors.accent,
                    strokeWidth = 2.dp,
                )
            }
            if (hasConnectedSource) {
                Text(
                    text = stringResource(Res.string.live_tv_disconnect),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable(onClick = onDisconnect)
                        .padding(8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.colors.danger,
                )
            }
        }
    }
}

@Composable
private fun LiveTvXtreamSettingsCard(
    serverUrl: String,
    username: String,
    password: String,
    isLoading: Boolean,
    errorMessage: String?,
    hasConnectedSource: Boolean,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLoad: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tokens.colors.surface,
        shape = tokens.shapes.card,
        border = BorderStroke(NuvioTokens.Border.thin, tokens.colors.borderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            ) {
                Box(
                    modifier = Modifier
                        .size(NuvioTokens.Space.s48)
                        .clip(tokens.shapes.compactCard)
                        .background(tokens.colors.overlaySelected),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tv,
                        contentDescription = null,
                        tint = tokens.colors.accent,
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.live_tv_source_xtream),
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(Res.string.live_tv_xtream_settings_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }

            NuvioInputField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                placeholder = stringResource(Res.string.live_tv_xtream_server_hint),
            )
            NuvioInputField(
                value = username,
                onValueChange = onUsernameChange,
                placeholder = stringResource(Res.string.live_tv_xtream_username_hint),
            )
            NuvioInputField(
                value = password,
                onValueChange = onPasswordChange,
                placeholder = stringResource(Res.string.live_tv_xtream_password_hint),
            )

            errorMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
            }
            NuvioPrimaryButton(
                text = stringResource(Res.string.live_tv_load),
                enabled = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !isLoading,
                onClick = onLoad,
            )
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(NuvioTokens.Icon.md)
                        .align(Alignment.CenterHorizontally),
                    color = tokens.colors.accent,
                    strokeWidth = 2.dp,
                )
            }
            if (hasConnectedSource) {
                Text(
                    text = stringResource(Res.string.live_tv_disconnect),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable(onClick = onDisconnect)
                        .padding(8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.colors.danger,
                )
            }
        }
    }
}

@Composable
private fun LiveTvSourceTypeSelector(
    selectedSourceType: LiveTvSourceType,
    onSelected: (LiveTvSourceType) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val sourceTypes = listOf(
            LiveTvSourceType.M3u to stringResource(Res.string.live_tv_source_m3u),
            LiveTvSourceType.Stalker to stringResource(Res.string.live_tv_source_stalker),
            LiveTvSourceType.Xtream to stringResource(Res.string.live_tv_source_xtream),
        )
        sourceTypes.forEach { (sourceType, label) ->
            val selected = sourceType == selectedSourceType
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .nuvioKeyboardFocusIndicator(tokens.shapes.chip),
                onClick = { onSelected(sourceType) },
                color = if (selected) tokens.colors.overlaySelected else tokens.colors.surfaceCard,
                contentColor = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted,
                shape = tokens.shapes.chip,
                border = BorderStroke(
                    NuvioTokens.Border.thin,
                    if (selected) tokens.colors.accent.copy(alpha = 0.52f) else tokens.colors.borderSubtle,
                ),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LiveTvChannelRow(
    channel: LiveTvChannel,
    programme: LiveTvProgramme?,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = tokens.colors.surface,
        shape = tokens.shapes.compactCard,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tokens.colors.surfaceCard),
                contentAlignment = Alignment.Center,
            ) {
                if (!channel.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = channel.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Tv,
                        contentDescription = null,
                        tint = tokens.colors.textMuted,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel.group.isNotBlank()) {
                    Text(
                        text = channel.group,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (programme != null) {
                    Text(
                        text = buildString {
                            append(programme.title)
                            append("  •  ")
                            append(LiveTvClock.formatLocalTime(programme.startEpochMs))
                            append(" - ")
                            append(LiveTvClock.formatLocalTime(programme.stopEpochMs))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = onFavoriteClick,
                modifier = Modifier.nuvioKeyboardFocusIndicator(tokens.shapes.avatar),
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = stringResource(Res.string.live_tv_favorite),
                    tint = if (isFavorite) tokens.colors.warning else tokens.colors.textMuted,
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(tokens.shapes.avatar)
                    .background(tokens.colors.overlaySelected),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                )
            }
        }
    }
}

@Composable
private fun LiveTvEmptyState(onAddSource: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Tv,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = tokens.colors.textMuted,
        )
        Text(
            text = stringResource(Res.string.live_tv_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = tokens.colors.textPrimary,
        )
        Text(
            text = stringResource(Res.string.live_tv_empty_description),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
        Spacer(modifier = Modifier.height(4.dp))
        NuvioPrimaryButton(
            text = stringResource(Res.string.live_tv_add_source),
            modifier = Modifier.fillMaxWidth(0.72f),
            onClick = onAddSource,
        )
    }
}
