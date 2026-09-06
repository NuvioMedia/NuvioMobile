package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.simkl.SimklAnimeIdPreference
import com.nuvio.app.features.simkl.SimklAuthUiState
import com.nuvio.app.features.simkl.SimklConnectionMode
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.TrackingSettingsUiState
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.tracking.effectiveLibrarySourceMode
import com.nuvio.app.features.tracking.effectiveWatchProgressSource
import com.nuvio.app.features.trakt.MoreLikeThisSourcePreference
import com.nuvio.app.features.trakt.TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL
import com.nuvio.app.features.trakt.TraktAuthUiState
import com.nuvio.app.features.trakt.TraktConnectionMode
import com.nuvio.app.features.trakt.TraktContinueWatchingDaysOptions
import com.nuvio.app.features.trakt.normalizeTraktContinueWatchingDaysCap
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.settings_tracking_connect_first
import nuvio.composeapp.generated.resources.settings_tracking_data_sources
import nuvio.composeapp.generated.resources.settings_tracking_nuvio_library_description
import nuvio.composeapp.generated.resources.settings_tracking_nuvio_progress_description
import nuvio.composeapp.generated.resources.settings_tracking_progress_refresh_failed
import nuvio.composeapp.generated.resources.settings_tracking_services
import nuvio.composeapp.generated.resources.settings_tracking_simkl_library_description
import nuvio.composeapp.generated.resources.settings_tracking_simkl_progress_description
import nuvio.composeapp.generated.resources.settings_tracking_source_fallback
import nuvio.composeapp.generated.resources.settings_tracking_tmdb_recommendations_description
import nuvio.composeapp.generated.resources.settings_tracking_trakt_library_description
import nuvio.composeapp.generated.resources.settings_tracking_trakt_progress_description
import nuvio.composeapp.generated.resources.settings_tracking_trakt_recommendations_description
import nuvio.composeapp.generated.resources.settings_tracking_viewing_discovery
import nuvio.composeapp.generated.resources.settings_tracking_anime_id_dialog_subtitle
import nuvio.composeapp.generated.resources.settings_tracking_anime_id_dialog_title
import nuvio.composeapp.generated.resources.settings_tracking_anime_id_imdb
import nuvio.composeapp.generated.resources.settings_tracking_anime_id_imdb_description
import nuvio.composeapp.generated.resources.settings_tracking_anime_id_kitsu
import nuvio.composeapp.generated.resources.settings_tracking_anime_id_kitsu_description
import nuvio.composeapp.generated.resources.settings_tracking_anime_id_mal
import nuvio.composeapp.generated.resources.settings_tracking_anime_id_mal_description
import nuvio.composeapp.generated.resources.settings_tracking_anime_id_subtitle
import nuvio.composeapp.generated.resources.settings_tracking_anime_id_title
import nuvio.composeapp.generated.resources.settings_tracking_anime_section
import nuvio.composeapp.generated.resources.settings_tracking_simkl_rewatches_description
import nuvio.composeapp.generated.resources.settings_tracking_simkl_rewatches_section
import nuvio.composeapp.generated.resources.settings_tracking_simkl_rewatches_title
import nuvio.composeapp.generated.resources.settings_trakt_comments
import nuvio.composeapp.generated.resources.settings_trakt_comments_description
import nuvio.composeapp.generated.resources.tracking_source_simkl
import nuvio.composeapp.generated.resources.tracking_watch_progress_dialog_subtitle
import nuvio.composeapp.generated.resources.trakt_all_history
import nuvio.composeapp.generated.resources.trakt_continue_watching_subtitle
import nuvio.composeapp.generated.resources.trakt_continue_watching_window
import nuvio.composeapp.generated.resources.trakt_cw_window_subtitle
import nuvio.composeapp.generated.resources.trakt_cw_window_title
import nuvio.composeapp.generated.resources.trakt_days_format
import nuvio.composeapp.generated.resources.trakt_library_source_dialog_subtitle
import nuvio.composeapp.generated.resources.trakt_library_source_dialog_title
import nuvio.composeapp.generated.resources.trakt_library_source_nuvio
import nuvio.composeapp.generated.resources.trakt_library_source_subtitle
import nuvio.composeapp.generated.resources.trakt_library_source_title
import nuvio.composeapp.generated.resources.trakt_library_source_trakt
import nuvio.composeapp.generated.resources.trakt_more_like_this_source_dialog_subtitle
import nuvio.composeapp.generated.resources.trakt_more_like_this_source_dialog_title
import nuvio.composeapp.generated.resources.trakt_more_like_this_source_subtitle
import nuvio.composeapp.generated.resources.trakt_more_like_this_source_title
import nuvio.composeapp.generated.resources.trakt_more_like_this_source_tmdb
import nuvio.composeapp.generated.resources.trakt_more_like_this_source_trakt
import nuvio.composeapp.generated.resources.trakt_watch_progress_dialog_title
import nuvio.composeapp.generated.resources.trakt_watch_progress_source_nuvio
import nuvio.composeapp.generated.resources.trakt_watch_progress_source_trakt
import nuvio.composeapp.generated.resources.trakt_watch_progress_subtitle
import nuvio.composeapp.generated.resources.trakt_watch_progress_title
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.trackingSettingsContent(
    isTablet: Boolean,
    traktUiState: TraktAuthUiState,
    simklUiState: SimklAuthUiState,
    settingsUiState: TrackingSettingsUiState,
    commentsEnabled: Boolean,
    onCommentsEnabledChange: (Boolean) -> Unit,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_tracking_services),
            isTablet = isTablet,
        ) {
            TrackingProviderCards(
                isTablet = isTablet,
                traktUiState = traktUiState,
                simklUiState = simklUiState,
            )
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_tracking_data_sources),
            isTablet = isTablet,
        ) {
            TrackingDataSources(
                isTablet = isTablet,
                settingsUiState = settingsUiState,
                traktConnected = traktUiState.mode == TraktConnectionMode.CONNECTED,
                simklConnected = simklUiState.mode == SimklConnectionMode.CONNECTED,
            )
        }
    }

    if (traktUiState.mode == TraktConnectionMode.CONNECTED) {
        item {
            SettingsSection(
                title = stringResource(Res.string.settings_tracking_viewing_discovery),
                isTablet = isTablet,
            ) {
                TrackingViewingAndDiscovery(
                    isTablet = isTablet,
                    settingsUiState = settingsUiState,
                    traktConnected = true,
                    commentsEnabled = commentsEnabled,
                    onCommentsEnabledChange = onCommentsEnabledChange,
                )
            }
        }
    }

    if (simklUiState.mode == SimklConnectionMode.CONNECTED) {
        item {
            SettingsSection(
                title = stringResource(Res.string.settings_tracking_anime_section),
                isTablet = isTablet,
            ) {
                AnimeIdPreferenceSection(
                    isTablet = isTablet,
                    settingsUiState = settingsUiState,
                )
            }
        }
        item {
            SettingsSection(
                title = stringResource(Res.string.settings_tracking_simkl_rewatches_section),
                isTablet = isTablet,
            ) {
                SimklRewatchesPreferenceSection(
                    isTablet = isTablet,
                    settingsUiState = settingsUiState,
                )
            }
        }
    }
}

@Composable
private fun SimklRewatchesPreferenceSection(
    isTablet: Boolean,
    settingsUiState: TrackingSettingsUiState,
) {
    SettingsGroup(isTablet = isTablet) {
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_tracking_simkl_rewatches_title),
            description = stringResource(Res.string.settings_tracking_simkl_rewatches_description),
            checked = settingsUiState.simklRewatchesEnabled,
            isTablet = isTablet,
            onCheckedChange = TrackingSettingsRepository::setSimklRewatchesEnabled,
        )
    }
}

private enum class TrackingDataPicker {
    LIBRARY,
    WATCH_PROGRESS,
}

@Composable
private fun TrackingDataSources(
    isTablet: Boolean,
    settingsUiState: TrackingSettingsUiState,
    traktConnected: Boolean,
    simklConnected: Boolean,
) {
    var activePickerName by rememberSaveable { mutableStateOf<String?>(null) }
    val activePicker = activePickerName?.let(TrackingDataPicker::valueOf)
    val scope = rememberCoroutineScope()
    val transitionState by remember {
        WatchProgressSourceCoordinator.ensureStarted()
        WatchProgressSourceCoordinator.uiState
    }.collectAsStateWithLifecycle()
    val connectedProviders = buildSet {
        if (traktConnected) add(TrackingProviderId.TRAKT)
        if (simklConnected) add(TrackingProviderId.SIMKL)
    }
    val effectiveLibrarySource = effectiveLibrarySourceMode(settingsUiState.librarySourceMode) { provider ->
        provider in connectedProviders
    }
    val effectiveProgressSource = effectiveWatchProgressSource(settingsUiState.watchProgressSource) { provider ->
        provider in connectedProviders
    }
    val libraryFallback = if (effectiveLibrarySource != settingsUiState.librarySourceMode) {
        stringResource(
            Res.string.settings_tracking_source_fallback,
            librarySourceModeLabel(settingsUiState.librarySourceMode),
            librarySourceModeLabel(effectiveLibrarySource),
        )
    } else {
        null
    }
    val progressFallback = if (effectiveProgressSource != settingsUiState.watchProgressSource) {
        stringResource(
            Res.string.settings_tracking_source_fallback,
            watchProgressSourceLabel(settingsUiState.watchProgressSource),
            watchProgressSourceLabel(effectiveProgressSource),
        )
    } else {
        null
    }

    SettingsGroup(isTablet = isTablet) {
        TrackingPreferenceActionRow(
            title = stringResource(Res.string.trakt_library_source_title),
            description = stringResource(Res.string.trakt_library_source_subtitle),
            value = librarySourceModeLabel(effectiveLibrarySource),
            supportingMessage = libraryFallback,
            isTablet = isTablet,
            onClick = { activePickerName = TrackingDataPicker.LIBRARY.name },
        )
        SettingsGroupDivider(isTablet = isTablet)
        TrackingPreferenceActionRow(
            title = stringResource(Res.string.trakt_watch_progress_title),
            description = stringResource(Res.string.trakt_watch_progress_subtitle),
            value = watchProgressSourceLabel(effectiveProgressSource),
            supportingMessage = progressFallback,
            isLoading = transitionState.isRefreshing,
            isTablet = isTablet,
            onClick = { activePickerName = TrackingDataPicker.WATCH_PROGRESS.name },
        )
        if (transitionState.lastRefreshSucceeded == false && !transitionState.isRefreshing) {
            SettingsGroupDivider(isTablet = isTablet)
            TrackingInlineErrorRow(
                isTablet = isTablet,
                message = stringResource(Res.string.settings_tracking_progress_refresh_failed),
                onRetry = {
                    scope.launch {
                        WatchProgressSourceCoordinator.refreshActiveSource(ProfileRepository.activeProfileId)
                    }
                },
            )
        }
    }

    when (activePicker) {
        TrackingDataPicker.LIBRARY -> TrackingAdaptivePicker(
            isTablet = isTablet,
            title = stringResource(Res.string.trakt_library_source_dialog_title),
            subtitle = stringResource(Res.string.trakt_library_source_dialog_subtitle),
            selectedValue = effectiveLibrarySource,
            options = librarySourceOptions(traktConnected, simklConnected),
            onSelected = TrackingSettingsRepository::setLibrarySourceMode,
            onDismiss = { activePickerName = null },
        )
        TrackingDataPicker.WATCH_PROGRESS -> TrackingAdaptivePicker(
            isTablet = isTablet,
            title = stringResource(Res.string.trakt_watch_progress_dialog_title),
            subtitle = stringResource(Res.string.tracking_watch_progress_dialog_subtitle),
            selectedValue = effectiveProgressSource,
            options = watchProgressSourceOptions(traktConnected, simklConnected),
            onSelected = { source ->
                scope.launch {
                    WatchProgressSourceCoordinator.selectSource(
                        profileId = ProfileRepository.activeProfileId,
                        source = source,
                    )
                }
            },
            onDismiss = { activePickerName = null },
        )
        null -> Unit
    }
}

private enum class TrackingViewingPicker {
    CONTINUE_WATCHING,
    MORE_LIKE_THIS,
}

@Composable
private fun TrackingViewingAndDiscovery(
    isTablet: Boolean,
    settingsUiState: TrackingSettingsUiState,
    traktConnected: Boolean,
    commentsEnabled: Boolean,
    onCommentsEnabledChange: (Boolean) -> Unit,
) {
    var activePickerName by rememberSaveable { mutableStateOf<String?>(null) }
    val activePicker = activePickerName?.let(TrackingViewingPicker::valueOf)
    val effectiveRecommendationsSource = effectiveTrackingRecommendationsSource(
        source = settingsUiState.moreLikeThisSource,
        traktConnected = traktConnected,
    )
    val recommendationsFallback = if (effectiveRecommendationsSource != settingsUiState.moreLikeThisSource) {
        stringResource(
            Res.string.settings_tracking_source_fallback,
            moreLikeThisSourceLabel(settingsUiState.moreLikeThisSource),
            moreLikeThisSourceLabel(effectiveRecommendationsSource),
        )
    } else {
        null
    }
    val connectTraktFirst = stringResource(
        Res.string.settings_tracking_connect_first,
        TrackingBrand.TRAKT.displayName,
    )

    SettingsGroup(isTablet = isTablet) {
        TrackingPreferenceActionRow(
            title = stringResource(Res.string.trakt_continue_watching_window),
            description = stringResource(Res.string.trakt_continue_watching_subtitle),
            value = continueWatchingDaysCapLabel(settingsUiState.continueWatchingDaysCap),
            isTablet = isTablet,
            onClick = { activePickerName = TrackingViewingPicker.CONTINUE_WATCHING.name },
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_trakt_comments),
            description = listOfNotNull(
                stringResource(Res.string.settings_trakt_comments_description),
                connectTraktFirst.takeUnless { traktConnected },
            ).joinToString("\n"),
            checked = commentsEnabled,
            enabled = traktConnected,
            isTablet = isTablet,
            onCheckedChange = onCommentsEnabledChange,
        )
        SettingsGroupDivider(isTablet = isTablet)
        TrackingPreferenceActionRow(
            title = stringResource(Res.string.trakt_more_like_this_source_title),
            description = stringResource(Res.string.trakt_more_like_this_source_subtitle),
            value = moreLikeThisSourceLabel(effectiveRecommendationsSource),
            supportingMessage = recommendationsFallback,
            isTablet = isTablet,
            onClick = { activePickerName = TrackingViewingPicker.MORE_LIKE_THIS.name },
        )
    }

    when (activePicker) {
        TrackingViewingPicker.CONTINUE_WATCHING -> TrackingAdaptivePicker(
            isTablet = isTablet,
            title = stringResource(Res.string.trakt_cw_window_title),
            subtitle = stringResource(Res.string.trakt_cw_window_subtitle),
            selectedValue = normalizeTraktContinueWatchingDaysCap(settingsUiState.continueWatchingDaysCap),
            options = continueWatchingOptions(),
            onSelected = TrackingSettingsRepository::setContinueWatchingDaysCap,
            onDismiss = { activePickerName = null },
        )
        TrackingViewingPicker.MORE_LIKE_THIS -> TrackingAdaptivePicker(
            isTablet = isTablet,
            title = stringResource(Res.string.trakt_more_like_this_source_dialog_title),
            subtitle = stringResource(Res.string.trakt_more_like_this_source_dialog_subtitle),
            selectedValue = effectiveRecommendationsSource,
            options = recommendationsSourceOptions(traktConnected),
            onSelected = TrackingSettingsRepository::setMoreLikeThisSource,
            onDismiss = { activePickerName = null },
        )
        null -> Unit
    }
}

@Composable
private fun TrackingPreferenceActionRow(
    title: String,
    description: String,
    value: String,
    isTablet: Boolean,
    onClick: () -> Unit,
    supportingMessage: String? = null,
    isLoading: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    SettingsNavigationRow(
        title = title,
        description = listOfNotNull(
            description,
            supportingMessage?.takeIf(String::isNotBlank),
        ).joinToString("\n"),
        isTablet = isTablet,
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isLoading) {
                    NuvioLoadingIndicator(
                        color = tokens.colors.accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.accent,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun TrackingInlineErrorRow(
    isTablet: Boolean,
    message: String,
    onRetry: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isTablet) 20.dp else 16.dp,
                vertical = if (isTablet) 14.dp else 12.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.danger,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(Res.string.action_retry))
        }
    }
}

@Composable
private fun librarySourceOptions(
    traktConnected: Boolean,
    simklConnected: Boolean,
): List<TrackingPickerOption<LibrarySourceMode>> {
    val traktAvailable = isTrackingBrandAvailable(TrackingBrand.TRAKT, traktConnected, simklConnected)
    val simklAvailable = isTrackingBrandAvailable(TrackingBrand.SIMKL, traktConnected, simklConnected)
    return listOf(
        TrackingPickerOption(
            value = LibrarySourceMode.LOCAL,
            title = stringResource(Res.string.trakt_library_source_nuvio),
            description = stringResource(Res.string.settings_tracking_nuvio_library_description),
        ),
        TrackingPickerOption(
            value = LibrarySourceMode.TRAKT,
            title = stringResource(Res.string.trakt_library_source_trakt),
            description = stringResource(Res.string.settings_tracking_trakt_library_description),
            enabled = traktAvailable,
            unavailableReason = trackingUnavailableReason(TrackingBrand.TRAKT, traktAvailable),
        ),
        TrackingPickerOption(
            value = LibrarySourceMode.SIMKL,
            title = stringResource(Res.string.tracking_source_simkl),
            description = stringResource(Res.string.settings_tracking_simkl_library_description),
            enabled = simklAvailable,
            unavailableReason = trackingUnavailableReason(TrackingBrand.SIMKL, simklAvailable),
        ),
    )
}

@Composable
private fun watchProgressSourceOptions(
    traktConnected: Boolean,
    simklConnected: Boolean,
): List<TrackingPickerOption<WatchProgressSource>> {
    val traktAvailable = isTrackingBrandAvailable(TrackingBrand.TRAKT, traktConnected, simklConnected)
    val simklAvailable = isTrackingBrandAvailable(TrackingBrand.SIMKL, traktConnected, simklConnected)
    return listOf(
        TrackingPickerOption(
            value = WatchProgressSource.NUVIO_SYNC,
            title = stringResource(Res.string.trakt_watch_progress_source_nuvio),
            description = stringResource(Res.string.settings_tracking_nuvio_progress_description),
        ),
        TrackingPickerOption(
            value = WatchProgressSource.TRAKT,
            title = stringResource(Res.string.trakt_watch_progress_source_trakt),
            description = stringResource(Res.string.settings_tracking_trakt_progress_description),
            enabled = traktAvailable,
            unavailableReason = trackingUnavailableReason(TrackingBrand.TRAKT, traktAvailable),
        ),
        TrackingPickerOption(
            value = WatchProgressSource.SIMKL,
            title = stringResource(Res.string.tracking_source_simkl),
            description = stringResource(Res.string.settings_tracking_simkl_progress_description),
            enabled = simklAvailable,
            unavailableReason = trackingUnavailableReason(TrackingBrand.SIMKL, simklAvailable),
        ),
    )
}

@Composable
private fun continueWatchingOptions(): List<TrackingPickerOption<Int>> =
    TraktContinueWatchingDaysOptions.map { days ->
        val normalizedDays = normalizeTraktContinueWatchingDaysCap(days)
        TrackingPickerOption(
            value = normalizedDays,
            title = continueWatchingDaysCapLabel(normalizedDays),
        )
    }

@Composable
private fun recommendationsSourceOptions(
    traktConnected: Boolean,
): List<TrackingPickerOption<MoreLikeThisSourcePreference>> {
    val traktAvailable = isTrackingBrandAvailable(TrackingBrand.TRAKT, traktConnected, simklConnected = false)
    return listOf(
        TrackingPickerOption(
            value = MoreLikeThisSourcePreference.TMDB,
            title = stringResource(Res.string.trakt_more_like_this_source_tmdb),
            description = stringResource(Res.string.settings_tracking_tmdb_recommendations_description),
        ),
        TrackingPickerOption(
            value = MoreLikeThisSourcePreference.TRAKT,
            title = stringResource(Res.string.trakt_more_like_this_source_trakt),
            description = stringResource(Res.string.settings_tracking_trakt_recommendations_description),
            enabled = traktAvailable,
            unavailableReason = trackingUnavailableReason(TrackingBrand.TRAKT, traktAvailable),
        ),
    )
}

@Composable
private fun trackingUnavailableReason(
    brand: TrackingBrand,
    connected: Boolean,
): String? = if (connected) {
    null
} else {
    stringResource(Res.string.settings_tracking_connect_first, brand.displayName)
}

@Composable
private fun librarySourceModeLabel(source: LibrarySourceMode): String = when (source) {
    LibrarySourceMode.TRAKT -> stringResource(Res.string.trakt_library_source_trakt)
    LibrarySourceMode.LOCAL -> stringResource(Res.string.trakt_library_source_nuvio)
    LibrarySourceMode.SIMKL -> stringResource(Res.string.tracking_source_simkl)
}

@Composable
private fun watchProgressSourceLabel(source: WatchProgressSource): String = when (source) {
    WatchProgressSource.TRAKT -> stringResource(Res.string.trakt_watch_progress_source_trakt)
    WatchProgressSource.NUVIO_SYNC -> stringResource(Res.string.trakt_watch_progress_source_nuvio)
    WatchProgressSource.SIMKL -> stringResource(Res.string.tracking_source_simkl)
}

@Composable
private fun moreLikeThisSourceLabel(source: MoreLikeThisSourcePreference): String = when (source) {
    MoreLikeThisSourcePreference.TRAKT -> stringResource(Res.string.trakt_more_like_this_source_trakt)
    MoreLikeThisSourcePreference.TMDB -> stringResource(Res.string.trakt_more_like_this_source_tmdb)
}

@Composable
private fun continueWatchingDaysCapLabel(daysCap: Int): String {
    val normalized = normalizeTraktContinueWatchingDaysCap(daysCap)
    return if (normalized == TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL) {
        stringResource(Res.string.trakt_all_history)
    } else {
        stringResource(Res.string.trakt_days_format, normalized)
    }
}

internal fun effectiveTrackingRecommendationsSource(
    source: MoreLikeThisSourcePreference,
    traktConnected: Boolean,
): MoreLikeThisSourcePreference =
    if (source == MoreLikeThisSourcePreference.TRAKT && !traktConnected) {
        MoreLikeThisSourcePreference.TMDB
    } else {
        source
    }

@Composable
private fun AnimeIdPreferenceSection(
    isTablet: Boolean,
    settingsUiState: TrackingSettingsUiState,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }

    SettingsGroup(isTablet = isTablet) {
        TrackingPreferenceActionRow(
            title = stringResource(Res.string.settings_tracking_anime_id_title),
            description = stringResource(Res.string.settings_tracking_anime_id_subtitle),
            value = animeIdPreferenceLabel(settingsUiState.simklAnimeIdPreference),
            isTablet = isTablet,
            onClick = { showPicker = true },
        )
    }

    if (showPicker) {
        TrackingAdaptivePicker(
            isTablet = isTablet,
            title = stringResource(Res.string.settings_tracking_anime_id_dialog_title),
            subtitle = stringResource(Res.string.settings_tracking_anime_id_dialog_subtitle),
            selectedValue = settingsUiState.simklAnimeIdPreference,
            options = animeIdPreferenceOptions(),
            onSelected = TrackingSettingsRepository::setSimklAnimeIdPreference,
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun animeIdPreferenceOptions(): List<TrackingPickerOption<SimklAnimeIdPreference>> = listOf(
    TrackingPickerOption(
        value = SimklAnimeIdPreference.IMDB,
        title = stringResource(Res.string.settings_tracking_anime_id_imdb),
        description = stringResource(Res.string.settings_tracking_anime_id_imdb_description),
    ),
    TrackingPickerOption(
        value = SimklAnimeIdPreference.MAL,
        title = stringResource(Res.string.settings_tracking_anime_id_mal),
        description = stringResource(Res.string.settings_tracking_anime_id_mal_description),
    ),
    TrackingPickerOption(
        value = SimklAnimeIdPreference.KITSU,
        title = stringResource(Res.string.settings_tracking_anime_id_kitsu),
        description = stringResource(Res.string.settings_tracking_anime_id_kitsu_description),
    ),
)

@Composable
private fun animeIdPreferenceLabel(preference: SimklAnimeIdPreference): String = when (preference) {
    SimklAnimeIdPreference.IMDB -> stringResource(Res.string.settings_tracking_anime_id_imdb)
    SimklAnimeIdPreference.MAL -> stringResource(Res.string.settings_tracking_anime_id_mal)
    SimklAnimeIdPreference.KITSU -> stringResource(Res.string.settings_tracking_anime_id_kitsu)
}
