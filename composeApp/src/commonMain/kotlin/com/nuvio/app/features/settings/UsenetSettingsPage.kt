package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.languageLabelResForCode
import com.nuvio.app.features.streams.epochMs
import com.nuvio.app.features.usenet.StreamRankingPreset
import com.nuvio.app.features.usenet.StreamRankingPresets
import com.nuvio.app.features.usenet.UsenetConfiguration
import com.nuvio.app.features.usenet.UsenetIndexer
import com.nuvio.app.features.usenet.UsenetIndexerProblem
import com.nuvio.app.features.usenet.UsenetIndexerStatus
import com.nuvio.app.features.usenet.UsenetNoticeDialog
import com.nuvio.app.features.usenet.UsenetPlatform
import com.nuvio.app.features.usenet.UsenetProvider
import com.nuvio.app.features.usenet.UsenetPlayback
import com.nuvio.app.features.usenet.UsenetRepository
import com.nuvio.app.features.usenet.UsenetStartupReport
import com.nuvio.app.features.usenet.UsenetSort
import com.nuvio.app.features.usenet.UsenetSourceConfiguration
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.usenet_add_indexer
import nuvio.composeapp.generated.resources.usenet_add_provider
import nuvio.composeapp.generated.resources.usenet_automatic
import nuvio.composeapp.generated.resources.usenet_available_first
import nuvio.composeapp.generated.resources.usenet_available_first_description
import nuvio.composeapp.generated.resources.usenet_balanced
import nuvio.composeapp.generated.resources.usenet_builtin_description
import nuvio.composeapp.generated.resources.usenet_builtin_enabled
import nuvio.composeapp.generated.resources.usenet_cache_nzb
import nuvio.composeapp.generated.resources.usenet_cache_nzb_description
import nuvio.composeapp.generated.resources.usenet_connections
import nuvio.composeapp.generated.resources.usenet_connections_description
import nuvio.composeapp.generated.resources.usenet_days
import nuvio.composeapp.generated.resources.usenet_exclude_full_disc
import nuvio.composeapp.generated.resources.usenet_exclude_full_disc_description
import nuvio.composeapp.generated.resources.usenet_exclude_low_quality
import nuvio.composeapp.generated.resources.usenet_fast_mkv
import nuvio.composeapp.generated.resources.usenet_fast_mkv_description
import nuvio.composeapp.generated.resources.usenet_fast_nzb
import nuvio.composeapp.generated.resources.usenet_fast_nzb_description
import nuvio.composeapp.generated.resources.usenet_gb
import nuvio.composeapp.generated.resources.usenet_hide_duplicates
import nuvio.composeapp.generated.resources.usenet_hide_duplicates_description
import nuvio.composeapp.generated.resources.usenet_hole_filling
import nuvio.composeapp.generated.resources.usenet_hole_filling_description
import nuvio.composeapp.generated.resources.usenet_indexer_fallback
import nuvio.composeapp.generated.resources.usenet_indexer_fallback_description
import nuvio.composeapp.generated.resources.usenet_indexer_hits_left
import nuvio.composeapp.generated.resources.usenet_indexer_left
import nuvio.composeapp.generated.resources.usenet_indexer_paused
import nuvio.composeapp.generated.resources.usenet_indexer_problem_auth
import nuvio.composeapp.generated.resources.usenet_indexer_problem_response
import nuvio.composeapp.generated.resources.usenet_indexer_problem_unreachable
import nuvio.composeapp.generated.resources.usenet_indexer_results
import nuvio.composeapp.generated.resources.usenet_indexer_results_description
import nuvio.composeapp.generated.resources.usenet_low_memory
import nuvio.composeapp.generated.resources.usenet_max_age
import nuvio.composeapp.generated.resources.usenet_max_size
import nuvio.composeapp.generated.resources.usenet_min_resolution
import nuvio.composeapp.generated.resources.usenet_no_limit
import nuvio.composeapp.generated.resources.usenet_priority_value
import nuvio.composeapp.generated.resources.usenet_profile
import nuvio.composeapp.generated.resources.usenet_profile_description
import nuvio.composeapp.generated.resources.usenet_progressive_results
import nuvio.composeapp.generated.resources.usenet_progressive_results_description
import nuvio.composeapp.generated.resources.usenet_provider_summary
import nuvio.composeapp.generated.resources.usenet_ranking_any_language
import nuvio.composeapp.generated.resources.usenet_ranking_balanced
import nuvio.composeapp.generated.resources.usenet_ranking_balanced_desc
import nuvio.composeapp.generated.resources.usenet_ranking_best
import nuvio.composeapp.generated.resources.usenet_ranking_best_desc
import nuvio.composeapp.generated.resources.usenet_ranking_dual_audio
import nuvio.composeapp.generated.resources.usenet_ranking_hd_sdr
import nuvio.composeapp.generated.resources.usenet_ranking_hd_sdr_desc
import nuvio.composeapp.generated.resources.usenet_ranking_language
import nuvio.composeapp.generated.resources.usenet_ranking_language_hint
import nuvio.composeapp.generated.resources.usenet_ranking_multi_audio
import nuvio.composeapp.generated.resources.usenet_ranking_original
import nuvio.composeapp.generated.resources.usenet_ranking_original_desc
import nuvio.composeapp.generated.resources.usenet_ranking_smaller
import nuvio.composeapp.generated.resources.usenet_ranking_smaller_desc
import nuvio.composeapp.generated.resources.usenet_ranking_title
import nuvio.composeapp.generated.resources.usenet_result_limit
import nuvio.composeapp.generated.resources.usenet_result_limit_description
import nuvio.composeapp.generated.resources.usenet_section_indexers
import nuvio.composeapp.generated.resources.usenet_section_none
import nuvio.composeapp.generated.resources.usenet_section_playback
import nuvio.composeapp.generated.resources.usenet_section_providers
import nuvio.composeapp.generated.resources.usenet_section_search
import nuvio.composeapp.generated.resources.usenet_self_hosted
import nuvio.composeapp.generated.resources.usenet_self_hosted_description
import nuvio.composeapp.generated.resources.usenet_show_release_names
import nuvio.composeapp.generated.resources.usenet_show_release_names_description
import nuvio.composeapp.generated.resources.usenet_sort_indexer
import nuvio.composeapp.generated.resources.usenet_sort_largest
import nuvio.composeapp.generated.resources.usenet_sort_newest
import nuvio.composeapp.generated.resources.usenet_sort_quality
import nuvio.composeapp.generated.resources.usenet_sort_results
import nuvio.composeapp.generated.resources.usenet_sort_smallest
import nuvio.composeapp.generated.resources.usenet_source_disabled
import nuvio.composeapp.generated.resources.usenet_sources_needed
import nuvio.composeapp.generated.resources.usenet_sources_save_error
import nuvio.composeapp.generated.resources.usenet_sources_title
import nuvio.composeapp.generated.resources.usenet_throughput
import nuvio.composeapp.generated.resources.usenet_stats_title
import nuvio.composeapp.generated.resources.usenet_stats_row_desc
import nuvio.composeapp.generated.resources.usenet_section_diagnostics
import nuvio.composeapp.generated.resources.usenet_last_startup
import nuvio.composeapp.generated.resources.usenet_last_startup_summary
import nuvio.composeapp.generated.resources.usenet_last_startup_none
import nuvio.composeapp.generated.resources.usenet_ranking_custom
import nuvio.composeapp.generated.resources.usenet_ranking_custom_desc
import nuvio.composeapp.generated.resources.usenet_ranking_customize
import nuvio.composeapp.generated.resources.usenet_ranking_customize_desc
import nuvio.composeapp.generated.resources.usenet_fallback
import nuvio.composeapp.generated.resources.usenet_fallback_description
import nuvio.composeapp.generated.resources.usenet_fallback_max_attempts
import nuvio.composeapp.generated.resources.usenet_fallback_max_attempts_description
import nuvio.composeapp.generated.resources.usenet_download_user_agent
import nuvio.composeapp.generated.resources.usenet_max_consecutive_missing
import nuvio.composeapp.generated.resources.usenet_max_consecutive_missing_description
import nuvio.composeapp.generated.resources.usenet_max_missing_articles
import nuvio.composeapp.generated.resources.usenet_max_missing_articles_description
import nuvio.composeapp.generated.resources.usenet_prefetch_results
import nuvio.composeapp.generated.resources.usenet_prefetch_results_description
import nuvio.composeapp.generated.resources.usenet_prewarm
import nuvio.composeapp.generated.resources.usenet_prewarm_description
import nuvio.composeapp.generated.resources.usenet_read_ahead
import nuvio.composeapp.generated.resources.usenet_read_ahead_description
import nuvio.composeapp.generated.resources.usenet_search_user_agent
import nuvio.composeapp.generated.resources.usenet_user_agent_default
import org.jetbrains.compose.resources.stringResource

private val MIN_RESOLUTIONS = listOf(0, 480, 720, 1080, 2160)
private val MAX_SIZES_GB = listOf(0, 2, 5, 10, 20, 40, 80)
private val MAX_AGES_DAYS = listOf(0, 30, 90, 365, 1000, 3000)
private val RESULT_LIMITS = listOf(25, 50, 100, 200, 500, UsenetSourceConfiguration.UNLIMITED_RESULTS)
private val FETCH_LIMITS = listOf(UsenetSourceConfiguration.AUTO_INDEXER_RESULTS) + UsenetSourceConfiguration.INDEXER_RESULT_CHOICES
private val MAX_CONNECTIONS = listOf(0, 10, 20, 30, 50, 100)
private val READ_AHEAD_SEGMENTS = listOf(0, 2, 4, 8, 16, 24, 32, 48, 64, 96, 128, 256, 512)
private val RANKING_PRESETS = StreamRankingPreset.entries.filter { it != StreamRankingPreset.CUSTOM }

/** The presets to choose from: Nuvio's, and the custom profile once one was saved. */
private fun rankingPresets(sources: UsenetSourceConfiguration): List<StreamRankingPreset> =
    if (sources.ranking.custom != null) RANKING_PRESETS + StreamRankingPreset.CUSTOM else RANKING_PRESETS

/** One of the page's choice dialogs. */
private enum class UsenetChoice {
    RANKING, LANGUAGE, SORT, MIN_RESOLUTION, MAX_SIZE, MAX_AGE, RESULTS, FETCH, PROFILE, CONNECTIONS,
    READ_AHEAD, MISSING_ARTICLES, CONSECUTIVE_MISSING, SEARCH_AGENT, DOWNLOAD_AGENT, FALLBACK_ATTEMPTS,
}

internal fun LazyListScope.usenetSettingsContent(
    isTablet: Boolean,
    onSortingClick: () -> Unit = {},
    onDiagnosticsClick: () -> Unit = {},
    onStatsClick: () -> Unit = {},
) {
    item {
        val uiState by remember {
            UsenetRepository.ensureLoaded()
            UsenetRepository.uiState
        }.collectAsStateWithLifecycle()
        val statusRevision by UsenetPlatform.indexerStatusChanges.collectAsStateWithLifecycle()
        var editor by remember { mutableStateOf<UsenetSourceEditor?>(null) }
        var showNotice by remember { mutableStateOf(false) }
        var choice by remember { mutableStateOf<UsenetChoice?>(null) }
        val sources = uiState.sources
        val engine = uiState.engine

        fun updateSources(change: (UsenetSourceConfiguration) -> UsenetSourceConfiguration) {
            UsenetRepository.updateSources(change)
        }

        // The switch, providers and indexers in one group, as NuvioTV shows them.
        SettingsSection(title = stringResource(Res.string.usenet_sources_title), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.usenet_builtin_enabled),
                    description = when {
                        uiState.error -> stringResource(Res.string.usenet_sources_save_error)
                        sources.enabled && !sources.ready -> stringResource(Res.string.usenet_sources_needed)
                        else -> stringResource(Res.string.usenet_builtin_description)
                    },
                    checked = sources.enabled,
                    enabled = uiState.loaded,
                    isTablet = isTablet,
                    onCheckedChange = { enabled ->
                        if (enabled && !sources.firstUseAccepted) {
                            showNotice = true
                        } else {
                            updateSources { it.copy(enabled = enabled) }
                        }
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SourcesLabel(stringResource(Res.string.usenet_section_providers), isTablet)
                if (sources.providers.isEmpty()) EmptyLine(isTablet)
                sources.providers.forEach { provider ->
                    SettingsNavigationRow(
                        title = provider.name,
                        description = providerDescription(provider),
                        icon = Icons.Rounded.Dns,
                        enabled = uiState.loaded,
                        isTablet = isTablet,
                        onClick = { editor = UsenetSourceEditor.Provider(provider) },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                }
                SettingsNavigationRow(
                    title = stringResource(Res.string.usenet_add_provider),
                    description = null,
                    icon = Icons.Rounded.Add,
                    enabled = uiState.loaded,
                    isTablet = isTablet,
                    onClick = { editor = UsenetSourceEditor.Provider(null) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SourcesLabel(stringResource(Res.string.usenet_section_indexers), isTablet)
                if (sources.indexers.isEmpty()) EmptyLine(isTablet)
                sources.indexers.forEach { indexer ->
                    // Searches update the statuses while this page is open.
                    val status = remember(indexer, statusRevision) { UsenetPlatform.indexerStatus(indexer) }
                    SettingsNavigationRow(
                        title = indexer.name,
                        description = indexerDescription(indexer, status),
                        icon = Icons.Rounded.TravelExplore,
                        enabled = uiState.loaded,
                        isTablet = isTablet,
                        onClick = { editor = UsenetSourceEditor.Indexer(indexer) },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                }
                SettingsNavigationRow(
                    title = stringResource(Res.string.usenet_add_indexer),
                    description = null,
                    icon = Icons.Rounded.Add,
                    enabled = uiState.loaded,
                    isTablet = isTablet,
                    onClick = { editor = UsenetSourceEditor.Indexer(null) },
                )
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        SettingsSection(title = stringResource(Res.string.usenet_section_search), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                val preset = sources.ranking.preset.takeIf { it in rankingPresets(sources) } ?: StreamRankingPreset.ORIGINAL
                ChoiceRow(
                    stringResource(Res.string.usenet_ranking_title),
                    customRankingLabel(preset, sources),
                    isTablet,
                ) { choice = UsenetChoice.RANKING }
                when (preset) {
                    StreamRankingPreset.ORIGINAL ->
                        ChoiceRow(stringResource(Res.string.usenet_sort_results), sortLabel(sources.sort), isTablet) { choice = UsenetChoice.SORT }
                    // A custom profile keeps its own language priorities.
                    StreamRankingPreset.CUSTOM -> Unit
                    else -> ChoiceRow(stringResource(Res.string.usenet_ranking_language), languageLabel(sources.ranking.preferredLanguage), isTablet) {
                        choice = UsenetChoice.LANGUAGE
                    }
                }
                ChoiceRow(stringResource(Res.string.usenet_ranking_customize), stringResource(Res.string.usenet_ranking_customize_desc), isTablet,
                    onClick = onSortingClick)
                ChoiceRow(stringResource(Res.string.usenet_min_resolution), resolutionLabel(sources.minResolution), isTablet) {
                    choice = UsenetChoice.MIN_RESOLUTION
                }
                ChoiceRow(stringResource(Res.string.usenet_max_size), sizeLabel(sources.maxSizeGb), isTablet) { choice = UsenetChoice.MAX_SIZE }
                ChoiceRow(stringResource(Res.string.usenet_max_age), ageLabel(sources.maxAgeDays), isTablet) { choice = UsenetChoice.MAX_AGE }
                ChoiceRow(stringResource(Res.string.usenet_result_limit), resultsLabel(sources.maxResults), isTablet) { choice = UsenetChoice.RESULTS }
                ChoiceRow(stringResource(Res.string.usenet_indexer_results), fetchLabel(sources.indexerResultsLimit), isTablet) {
                    choice = UsenetChoice.FETCH
                }
                Switch(stringResource(Res.string.usenet_exclude_low_quality), null, sources.excludeLowQuality, isTablet) { value ->
                    updateSources { it.copy(excludeLowQuality = value) }
                }
                Switch(stringResource(Res.string.usenet_exclude_full_disc), stringResource(Res.string.usenet_exclude_full_disc_description),
                    sources.excludeFullDisc, isTablet) { value -> updateSources { it.copy(excludeFullDisc = value) } }
                Switch(stringResource(Res.string.usenet_indexer_fallback), stringResource(Res.string.usenet_indexer_fallback_description),
                    sources.indexerFallback, isTablet) { value -> updateSources { it.copy(indexerFallback = value) } }
                Switch(stringResource(Res.string.usenet_hide_duplicates), stringResource(Res.string.usenet_hide_duplicates_description),
                    sources.hideDuplicates, isTablet) { value -> updateSources { it.copy(hideDuplicates = value) } }
                Switch(stringResource(Res.string.usenet_show_release_names), stringResource(Res.string.usenet_show_release_names_description),
                    sources.showReleaseNames, isTablet) { value -> updateSources { it.copy(showReleaseNames = value) } }
                Switch(stringResource(Res.string.usenet_available_first), stringResource(Res.string.usenet_available_first_description),
                    sources.availableFirst, isTablet) { value -> updateSources { it.copy(availableFirst = value) } }
                Switch(stringResource(Res.string.usenet_progressive_results), stringResource(Res.string.usenet_progressive_results_description),
                    sources.progressiveResults, isTablet, last = true) { value -> updateSources { it.copy(progressiveResults = value) } }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        SettingsSection(title = stringResource(Res.string.usenet_section_playback), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                Switch(stringResource(Res.string.usenet_fallback), stringResource(Res.string.usenet_fallback_description),
                    engine.fallbackEnabled, isTablet) { value -> UsenetRepository.updateEngineSettings { it.copy(fallbackEnabled = value) } }
                if (engine.fallbackEnabled) {
                    ChoiceRow(stringResource(Res.string.usenet_fallback_max_attempts), engine.fallbackMaxAttempts.toString(), isTablet) {
                        choice = UsenetChoice.FALLBACK_ATTEMPTS
                    }
                }
                ChoiceRow(stringResource(Res.string.usenet_profile), profileLabel(engine.profile), isTablet) { choice = UsenetChoice.PROFILE }
                ChoiceRow(stringResource(Res.string.usenet_connections), connectionsLabel(engine.maxConnections), isTablet) {
                    choice = UsenetChoice.CONNECTIONS
                }
                ChoiceRow(stringResource(Res.string.usenet_read_ahead), connectionsLabel(engine.readAhead), isTablet) {
                    choice = UsenetChoice.READ_AHEAD
                }
                Switch(stringResource(Res.string.usenet_fast_mkv), stringResource(Res.string.usenet_fast_mkv_description),
                    engine.fastMkvStartup, isTablet) { value -> UsenetRepository.updateEngineSettings { it.copy(fastMkvStartup = value) } }
                Switch(stringResource(Res.string.usenet_fast_nzb), stringResource(Res.string.usenet_fast_nzb_description),
                    engine.fastNzbFetch, isTablet) { value -> UsenetRepository.updateEngineSettings { it.copy(fastNzbFetch = value) } }
                Switch(stringResource(Res.string.usenet_cache_nzb), stringResource(Res.string.usenet_cache_nzb_description),
                    engine.cacheNzb, isTablet) { value -> UsenetRepository.updateEngineSettings { it.copy(cacheNzb = value) } }
                Switch(stringResource(Res.string.usenet_prefetch_results), stringResource(Res.string.usenet_prefetch_results_description),
                    engine.prefetchResults, isTablet) { value -> UsenetRepository.updateEngineSettings { it.copy(prefetchResults = value) } }
                Switch(stringResource(Res.string.usenet_prewarm), stringResource(Res.string.usenet_prewarm_description),
                    engine.prewarmOnLaunch, isTablet) { value -> UsenetRepository.updateEngineSettings { it.copy(prewarmOnLaunch = value) } }
                Switch(stringResource(Res.string.usenet_self_hosted), stringResource(Res.string.usenet_self_hosted_description),
                    engine.allowPrivateNetwork, isTablet) { value -> UsenetRepository.updateEngineSettings { it.copy(allowPrivateNetwork = value) } }
                Switch(stringResource(Res.string.usenet_hole_filling), stringResource(Res.string.usenet_hole_filling_description),
                    engine.holeFilling, isTablet) { value -> UsenetRepository.updateEngineSettings { it.copy(holeFilling = value) } }
                if (engine.holeFilling) {
                    ChoiceRow(stringResource(Res.string.usenet_max_missing_articles), engine.maxMissingArticles.toString(), isTablet) {
                        choice = UsenetChoice.MISSING_ARTICLES
                    }
                    ChoiceRow(stringResource(Res.string.usenet_max_consecutive_missing), engine.maxConsecutiveMissing.toString(), isTablet) {
                        choice = UsenetChoice.CONSECUTIVE_MISSING
                    }
                }
                ChoiceRow(stringResource(Res.string.usenet_search_user_agent), userAgentLabel(engine.searchUserAgent, engine.searchAgent), isTablet) {
                    choice = UsenetChoice.SEARCH_AGENT
                }
                SettingsNavigationRow(
                    title = stringResource(Res.string.usenet_download_user_agent),
                    description = userAgentLabel(engine.downloadUserAgent, engine.downloadAgent),
                    isTablet = isTablet,
                    onClick = { choice = UsenetChoice.DOWNLOAD_AGENT },
                )
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        SettingsSection(title = stringResource(Res.string.usenet_section_diagnostics), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                val latestStartup by UsenetPlayback.lastStartupReport.collectAsStateWithLifecycle()
                val startup = remember(latestStartup) { UsenetStartupReport.parse(latestStartup) }
                SettingsNavigationRow(
                    title = stringResource(Res.string.usenet_last_startup),
                    description = if (startup?.recordedAtMs != null) {
                        stringResource(Res.string.usenet_last_startup_summary, clockTime(startup.recordedAtMs), ms(startup.totalMs))
                    } else {
                        stringResource(Res.string.usenet_last_startup_none)
                    },
                    isTablet = isTablet,
                    onClick = onDiagnosticsClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.usenet_stats_title),
                    description = stringResource(Res.string.usenet_stats_row_desc),
                    isTablet = isTablet,
                    onClick = onStatsClick,
                )
            }
        }

        when (val current = editor) {
            is UsenetSourceEditor.Provider -> UsenetProviderSheet(
                existing = current.existing,
                allowPrivateNetwork = engine.allowPrivateNetwork,
                onDismiss = { editor = null },
            )
            is UsenetSourceEditor.Indexer -> UsenetIndexerSheet(existing = current.existing, onDismiss = { editor = null })
            null -> Unit
        }

        if (showNotice) {
            UsenetNoticeDialog(
                onTurnOn = {
                    showNotice = false
                    updateSources { it.copy(enabled = true, firstUseAccepted = true) }
                },
                onDismiss = { showNotice = false },
            )
        }

        UsenetChoiceDialog(choice, sources, engine, onDismiss = { choice = null }, onSources = ::updateSources)
    }
}

@Composable
private fun UsenetChoiceDialog(
    choice: UsenetChoice?,
    sources: UsenetSourceConfiguration,
    engine: UsenetConfiguration,
    onDismiss: () -> Unit,
    onSources: ((UsenetSourceConfiguration) -> UsenetSourceConfiguration) -> Unit,
) {
    fun <T> pick(change: (T) -> Unit): (T) -> Unit = { value -> change(value); onDismiss() }
    when (choice) {
        UsenetChoice.RANKING -> SettingsChoiceDialog(
            title = stringResource(Res.string.usenet_ranking_title),
            options = rankingPresets(sources),
            selected = sources.ranking.preset,
            label = { customRankingLabel(it, sources) },
            description = { rankingDescription(it) },
            onSelect = pick { preset -> onSources { it.copy(ranking = it.ranking.copy(preset = preset)) } },
            onDismiss = onDismiss,
        )
        UsenetChoice.LANGUAGE -> SettingsChoiceDialog(
            title = stringResource(Res.string.usenet_ranking_language),
            options = listOf("") + StreamRankingPresets.languages,
            selected = sources.ranking.preferredLanguage,
            label = { languageLabel(it) },
            description = { if (it.isEmpty()) stringResource(Res.string.usenet_ranking_language_hint) else null },
            onSelect = pick { language -> onSources { it.copy(ranking = it.ranking.copy(preferredLanguage = language)) } },
            onDismiss = onDismiss,
        )
        UsenetChoice.SORT -> SettingsChoiceDialog(
            title = stringResource(Res.string.usenet_sort_results),
            options = UsenetSort.entries,
            selected = sources.sort,
            label = { sortLabel(it) },
            onSelect = pick { sort -> onSources { it.copy(sort = sort) } },
            onDismiss = onDismiss,
        )
        UsenetChoice.MIN_RESOLUTION -> SettingsChoiceDialog(
            title = stringResource(Res.string.usenet_min_resolution),
            options = MIN_RESOLUTIONS,
            selected = sources.minResolution,
            label = { resolutionLabel(it) },
            onSelect = pick { value -> onSources { it.copy(minResolution = value) } },
            onDismiss = onDismiss,
        )
        UsenetChoice.MAX_SIZE -> SettingsChoiceDialog(
            title = stringResource(Res.string.usenet_max_size),
            options = MAX_SIZES_GB,
            selected = sources.maxSizeGb,
            label = { sizeLabel(it) },
            onSelect = pick { value -> onSources { it.copy(maxSizeGb = value) } },
            onDismiss = onDismiss,
        )
        UsenetChoice.MAX_AGE -> SettingsChoiceDialog(
            title = stringResource(Res.string.usenet_max_age),
            options = MAX_AGES_DAYS,
            selected = sources.maxAgeDays,
            label = { ageLabel(it) },
            onSelect = pick { value -> onSources { it.copy(maxAgeDays = value) } },
            onDismiss = onDismiss,
        )
        UsenetChoice.RESULTS -> SettingsChoiceDialog(
            title = stringResource(Res.string.usenet_result_limit),
            options = (RESULT_LIMITS + sources.maxResults).distinct(),
            selected = sources.maxResults,
            label = { resultsLabel(it) },
            description = { if (it == sources.maxResults) stringResource(Res.string.usenet_result_limit_description) else null },
            onSelect = pick { value -> onSources { it.copy(maxResults = value) } },
            onDismiss = onDismiss,
        )
        UsenetChoice.FETCH -> SettingsChoiceDialog(
            title = stringResource(Res.string.usenet_indexer_results),
            options = FETCH_LIMITS,
            selected = sources.indexerResultsLimit,
            label = { fetchLabel(it) },
            description = { if (it == sources.indexerResultsLimit) stringResource(Res.string.usenet_indexer_results_description) else null },
            onSelect = pick { value -> onSources { it.copy(indexerResultsLimit = value) } },
            onDismiss = onDismiss,
        )
        UsenetChoice.PROFILE -> SettingsChoiceDialog(
            title = stringResource(Res.string.usenet_profile),
            options = UsenetConfiguration.PROFILES,
            selected = engine.profile,
            label = { profileLabel(it) },
            description = { if (it == engine.profile) stringResource(Res.string.usenet_profile_description) else null },
            onSelect = pick { value -> UsenetRepository.updateEngineSettings { it.copy(profile = value) } },
            onDismiss = onDismiss,
        )
        UsenetChoice.CONNECTIONS -> SettingsChoiceDialog(
            title = stringResource(Res.string.usenet_connections),
            options = (MAX_CONNECTIONS + engine.maxConnections).distinct().sorted(),
            selected = engine.maxConnections,
            label = { connectionsLabel(it) },
            description = { if (it == 0) stringResource(Res.string.usenet_connections_description) else null },
            onSelect = pick { value -> UsenetRepository.updateEngineSettings { it.copy(maxConnections = value) } },
            onDismiss = onDismiss,
        )
        UsenetChoice.READ_AHEAD -> SettingsChoiceDialog(
            title = stringResource(Res.string.usenet_read_ahead),
            options = (READ_AHEAD_SEGMENTS + engine.readAhead).distinct().sorted(),
            selected = engine.readAhead,
            label = { connectionsLabel(it) },
            description = { if (it == engine.readAhead) stringResource(Res.string.usenet_read_ahead_description) else null },
            onSelect = pick { value -> UsenetRepository.updateEngineSettings { it.copy(readAhead = value) } },
            onDismiss = onDismiss,
        )
        UsenetChoice.MISSING_ARTICLES -> SettingsChoiceDialog(
            title = stringResource(Res.string.usenet_max_missing_articles),
            options = (1..50).toList(),
            selected = engine.maxMissingArticles,
            label = { it.toString() },
            description = { if (it == engine.maxMissingArticles) stringResource(Res.string.usenet_max_missing_articles_description) else null },
            onSelect = pick { value ->
                UsenetRepository.updateEngineSettings {
                    it.copy(maxMissingArticles = value, maxConsecutiveMissing = minOf(it.maxConsecutiveMissing, value))
                }
            },
            onDismiss = onDismiss,
        )
        UsenetChoice.CONSECUTIVE_MISSING -> SettingsChoiceDialog(
            title = stringResource(Res.string.usenet_max_consecutive_missing),
            options = (1..minOf(10, engine.maxMissingArticles)).toList(),
            selected = engine.maxConsecutiveMissing,
            label = { it.toString() },
            description = {
                if (it == engine.maxConsecutiveMissing) stringResource(Res.string.usenet_max_consecutive_missing_description) else null
            },
            onSelect = pick { value -> UsenetRepository.updateEngineSettings { it.copy(maxConsecutiveMissing = value) } },
            onDismiss = onDismiss,
        )
        UsenetChoice.FALLBACK_ATTEMPTS -> SettingsChoiceDialog(
            title = stringResource(Res.string.usenet_fallback_max_attempts),
            options = (1..50).toList(),
            selected = engine.fallbackMaxAttempts,
            label = { it.toString() },
            description = {
                if (it == engine.fallbackMaxAttempts) stringResource(Res.string.usenet_fallback_max_attempts_description) else null
            },
            onSelect = pick { value -> UsenetRepository.updateEngineSettings { it.copy(fallbackMaxAttempts = value) } },
            onDismiss = onDismiss,
        )
        UsenetChoice.SEARCH_AGENT -> UsenetUserAgentDialog(
            title = stringResource(Res.string.usenet_search_user_agent),
            agent = engine.searchAgent,
            search = true,
            onSave = { agent -> UsenetRepository.updateEngineSettings { it.copy(searchUserAgent = agent) } },
            onDismiss = onDismiss,
        )
        UsenetChoice.DOWNLOAD_AGENT -> UsenetUserAgentDialog(
            title = stringResource(Res.string.usenet_download_user_agent),
            agent = engine.downloadAgent,
            search = false,
            onSave = { agent -> UsenetRepository.updateEngineSettings { it.copy(downloadUserAgent = agent) } },
            onDismiss = onDismiss,
        )
        null -> Unit
    }
}

/** Heads the providers or the indexers inside the sources group. */
@Composable
private fun SourcesLabel(text: String, isTablet: Boolean) {
    NuvioSectionLabel(
        text = text,
        modifier = Modifier.padding(
            start = if (isTablet) 20.dp else 16.dp,
            end = if (isTablet) 20.dp else 16.dp,
            top = 14.dp,
            bottom = 2.dp,
        ),
    )
}

@Composable
private fun EmptyLine(isTablet: Boolean) {
    Text(
        text = stringResource(Res.string.usenet_section_none),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.nuvio.colors.textMuted,
        modifier = Modifier.padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 14.dp),
    )
    SettingsGroupDivider(isTablet = isTablet)
}

@Composable
private fun ChoiceRow(title: String, value: String, isTablet: Boolean, onClick: () -> Unit) {
    SettingsNavigationRow(title = title, description = value, isTablet = isTablet, onClick = onClick)
    SettingsGroupDivider(isTablet = isTablet)
}

@Composable
private fun Switch(
    title: String,
    description: String?,
    checked: Boolean,
    isTablet: Boolean,
    last: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsSwitchRow(title = title, description = description, checked = checked, isTablet = isTablet, onCheckedChange = onCheckedChange)
    if (!last) SettingsGroupDivider(isTablet = isTablet)
}

@Composable
private fun providerDescription(provider: UsenetProvider): String = listOfNotNull(
    stringResource(Res.string.usenet_provider_summary, "${provider.host}:${provider.port}", provider.connections),
    stringResource(Res.string.usenet_priority_value, provider.priority),
    stringResource(Res.string.usenet_source_disabled).takeIf { !provider.enabled },
).joinToString(" · ")

@Composable
private fun indexerDescription(indexer: UsenetIndexer, status: UsenetIndexerStatus): String {
    val host = indexer.apiUrl.substringAfter("://").substringBefore('/').substringBefore('?')
    val state = indexerStatusText(status)
    return listOfNotNull(
        host.takeIf { it.isNotBlank() },
        stringResource(Res.string.usenet_priority_value, indexer.priority),
        stringResource(Res.string.usenet_source_disabled).takeIf { !indexer.enabled },
        state,
    ).joinToString(" · ")
}

/** A pause, the last failure, or what the indexer reported left today; raw errors are never shown. */
@Composable
private fun indexerStatusText(status: UsenetIndexerStatus): String? {
    val now = epochMs()
    if (status.pausedUntil > now) return stringResource(Res.string.usenet_indexer_paused, clockTime(status.pausedUntil))
    when (status.problem) {
        UsenetIndexerProblem.AUTH -> return stringResource(Res.string.usenet_indexer_problem_auth)
        UsenetIndexerProblem.UNREACHABLE -> return stringResource(Res.string.usenet_indexer_problem_unreachable)
        UsenetIndexerProblem.RESPONSE -> return stringResource(Res.string.usenet_indexer_problem_response)
        null -> Unit
    }
    val hits = status.apiHitsLeft ?: return null
    val at = clockTime(status.leftAt)
    val grabs = status.grabsLeft
    return if (grabs != null) stringResource(Res.string.usenet_indexer_left, hits, grabs, at)
    else stringResource(Res.string.usenet_indexer_hits_left, hits, at)
}

private fun clockTime(epochMs: Long): String = UsenetPlatform.clockTime(epochMs)

@Composable
private fun rankingLabel(preset: StreamRankingPreset): String = when (preset) {
    StreamRankingPreset.BALANCED -> stringResource(Res.string.usenet_ranking_balanced)
    StreamRankingPreset.BEST_QUALITY -> stringResource(Res.string.usenet_ranking_best)
    StreamRankingPreset.SMALLER_FILES -> stringResource(Res.string.usenet_ranking_smaller)
    StreamRankingPreset.HD_SDR -> stringResource(Res.string.usenet_ranking_hd_sdr)
    StreamRankingPreset.CUSTOM -> stringResource(Res.string.usenet_ranking_custom)
    else -> stringResource(Res.string.usenet_ranking_original)
}

/** A preset's name; the custom profile reads as "Custom profile · its name". */
@Composable
private fun customRankingLabel(preset: StreamRankingPreset, sources: UsenetSourceConfiguration): String {
    val name = sources.ranking.custom?.name
    return if (preset == StreamRankingPreset.CUSTOM && !name.isNullOrBlank()) "${rankingLabel(preset)} · $name" else rankingLabel(preset)
}

@Composable
private fun rankingDescription(preset: StreamRankingPreset): String = when (preset) {
    StreamRankingPreset.BALANCED -> stringResource(Res.string.usenet_ranking_balanced_desc)
    StreamRankingPreset.BEST_QUALITY -> stringResource(Res.string.usenet_ranking_best_desc)
    StreamRankingPreset.SMALLER_FILES -> stringResource(Res.string.usenet_ranking_smaller_desc)
    StreamRankingPreset.HD_SDR -> stringResource(Res.string.usenet_ranking_hd_sdr_desc)
    StreamRankingPreset.CUSTOM -> stringResource(Res.string.usenet_ranking_custom_desc)
    else -> stringResource(Res.string.usenet_ranking_original_desc)
}

@Composable
private fun languageLabel(code: String): String = when (code) {
    "" -> stringResource(Res.string.usenet_ranking_any_language)
    "multi" -> stringResource(Res.string.usenet_ranking_multi_audio)
    "dual" -> stringResource(Res.string.usenet_ranking_dual_audio)
    else -> languageLabelResForCode(code)?.let { stringResource(it) } ?: code
}

@Composable
private fun sortLabel(sort: UsenetSort): String = when (sort) {
    UsenetSort.QUALITY -> stringResource(Res.string.usenet_sort_quality)
    UsenetSort.LARGEST -> stringResource(Res.string.usenet_sort_largest)
    UsenetSort.SMALLEST -> stringResource(Res.string.usenet_sort_smallest)
    UsenetSort.NEWEST -> stringResource(Res.string.usenet_sort_newest)
    UsenetSort.INDEXER -> stringResource(Res.string.usenet_sort_indexer)
}

@Composable
private fun resolutionLabel(value: Int): String = when (value) {
    0 -> stringResource(Res.string.usenet_no_limit)
    2160 -> "4K"
    else -> "${value}p"
}

@Composable
private fun sizeLabel(value: Int): String =
    if (value == 0) stringResource(Res.string.usenet_no_limit) else stringResource(Res.string.usenet_gb, value)

@Composable
private fun ageLabel(value: Int): String =
    if (value == 0) stringResource(Res.string.usenet_no_limit) else stringResource(Res.string.usenet_days, value)

@Composable
private fun resultsLabel(value: Int): String =
    if (value == UsenetSourceConfiguration.UNLIMITED_RESULTS) stringResource(Res.string.usenet_no_limit) else value.toString()

@Composable
private fun fetchLabel(value: Int): String =
    if (value == UsenetSourceConfiguration.AUTO_INDEXER_RESULTS) stringResource(Res.string.usenet_automatic) else value.toString()

@Composable
private fun profileLabel(profile: String): String = when (profile) {
    UsenetConfiguration.PROFILE_LOW_MEMORY -> stringResource(Res.string.usenet_low_memory)
    UsenetConfiguration.PROFILE_THROUGHPUT -> stringResource(Res.string.usenet_throughput)
    else -> stringResource(Res.string.usenet_balanced)
}

@Composable
private fun connectionsLabel(value: Int): String =
    if (value == 0) stringResource(Res.string.usenet_automatic) else value.toString()

/** The User-Agent being sent; Nuvio's own is marked as the default. */
@Composable
private fun userAgentLabel(chosen: String, sent: String): String =
    if (chosen.isBlank()) stringResource(Res.string.usenet_user_agent_default, sent) else sent
