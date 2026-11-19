package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.usenet.IndexerStatsUi
import com.nuvio.app.features.usenet.ProviderStatsUi
import com.nuvio.app.features.usenet.StatsBar
import com.nuvio.app.features.usenet.StatsBarSource
import com.nuvio.app.features.usenet.StatsContent
import com.nuvio.app.features.usenet.StatsPeriod
import com.nuvio.app.features.usenet.UsenetPlatform
import com.nuvio.app.features.usenet.UsenetRepository
import com.nuvio.app.features.usenet.UsenetStatsSnapshot
import com.nuvio.app.features.usenet.averageFirstByteMs
import com.nuvio.app.features.usenet.averageResponseMs
import com.nuvio.app.features.usenet.completion
import com.nuvio.app.features.usenet.searchYield
import com.nuvio.app.features.usenet.statsBars
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.usenet_cancel
import nuvio.composeapp.generated.resources.usenet_stats_articles
import nuvio.composeapp.generated.resources.usenet_stats_completion
import nuvio.composeapp.generated.resources.usenet_stats_completion_value
import nuvio.composeapp.generated.resources.usenet_stats_content
import nuvio.composeapp.generated.resources.usenet_stats_content_all
import nuvio.composeapp.generated.resources.usenet_stats_content_movies
import nuvio.composeapp.generated.resources.usenet_stats_content_shows
import nuvio.composeapp.generated.resources.usenet_stats_downloaded
import nuvio.composeapp.generated.resources.usenet_stats_errors
import nuvio.composeapp.generated.resources.usenet_stats_failed
import nuvio.composeapp.generated.resources.usenet_stats_failed_requests
import nuvio.composeapp.generated.resources.usenet_stats_filters
import nuvio.composeapp.generated.resources.usenet_stats_first_byte
import nuvio.composeapp.generated.resources.usenet_stats_gib
import nuvio.composeapp.generated.resources.usenet_stats_grabs
import nuvio.composeapp.generated.resources.usenet_stats_limited
import nuvio.composeapp.generated.resources.usenet_stats_ms
import nuvio.composeapp.generated.resources.usenet_stats_no_data
import nuvio.composeapp.generated.resources.usenet_stats_no_sources
import nuvio.composeapp.generated.resources.usenet_stats_outcomes
import nuvio.composeapp.generated.resources.usenet_stats_paused
import nuvio.composeapp.generated.resources.usenet_stats_period
import nuvio.composeapp.generated.resources.usenet_stats_period_30
import nuvio.composeapp.generated.resources.usenet_stats_period_7
import nuvio.composeapp.generated.resources.usenet_stats_period_all
import nuvio.composeapp.generated.resources.usenet_stats_played_chart
import nuvio.composeapp.generated.resources.usenet_stats_releases_average
import nuvio.composeapp.generated.resources.usenet_stats_requests
import nuvio.composeapp.generated.resources.usenet_stats_reset
import nuvio.composeapp.generated.resources.usenet_stats_reset_action
import nuvio.composeapp.generated.resources.usenet_stats_reset_confirm
import nuvio.composeapp.generated.resources.usenet_stats_reset_desc
import nuvio.composeapp.generated.resources.usenet_stats_response
import nuvio.composeapp.generated.resources.usenet_stats_searches
import nuvio.composeapp.generated.resources.usenet_stats_sessions
import nuvio.composeapp.generated.resources.usenet_stats_sessions_failed
import nuvio.composeapp.generated.resources.usenet_stats_since
import nuvio.composeapp.generated.resources.usenet_stats_with_results
import nuvio.composeapp.generated.resources.usenet_stats_with_today
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** One of the statistics page's choice dialogs. */
private enum class StatsChoice { PERIOD, CONTENT }

/**
 * Source statistics, as NuvioTV shows them: the two filters, bars that compare the sources, a card
 * per indexer and per provider, and a reset. Counts stay on this device and name no release.
 */
internal fun LazyListScope.usenetStatsContent(isTablet: Boolean) {
    item {
        val uiState by remember {
            UsenetRepository.ensureLoaded()
            UsenetRepository.uiState
        }.collectAsStateWithLifecycle()
        val revision by UsenetPlatform.statsRevision.collectAsStateWithLifecycle()
        val indexers = uiState.sources.indexers
        val providers = uiState.sources.providers
        var snapshot by remember { mutableStateOf(UsenetStatsSnapshot()) }
        var choice by remember { mutableStateOf<StatsChoice?>(null) }
        var confirmReset by remember { mutableStateOf(false) }
        LaunchedEffect(revision, indexers, providers) {
            snapshot = withContext(Dispatchers.Default) { UsenetPlatform.stats(indexers, providers) }
        }

        SettingsSection(title = stringResource(Res.string.usenet_stats_filters), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.usenet_stats_period),
                    description = periodLabel(snapshot.period),
                    isTablet = isTablet,
                    onClick = { choice = StatsChoice.PERIOD },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.usenet_stats_content),
                    description = contentLabel(snapshot.content),
                    isTablet = isTablet,
                    onClick = { choice = StatsChoice.CONTENT },
                )
            }
        }

        if (indexers.isEmpty() && providers.isEmpty()) {
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
            SettingsGroup(isTablet = isTablet) { StatsColumn(isTablet) { Note(stringResource(Res.string.usenet_stats_no_sources)) } }
            return@item
        }

        fun indexerBars(value: (IndexerStatsUi) -> Long) = statsBars(indexers.map {
            StatsBarSource(it.id, it.name, snapshot.indexers[it.id]?.let(value) ?: 0)
        })
        val played = indexerBars { it.counts?.played?.toLong() ?: 0 }
        val grabs = indexerBars { it.counts?.grabs?.toLong() ?: 0 }
        val served = statsBars(providers.map { StatsBarSource(it.id, it.name, snapshot.providers[it.id]?.counts?.articles ?: 0) })
        listOf(
            Res.string.usenet_stats_played_chart to played,
            Res.string.usenet_stats_grabs to grabs,
            Res.string.usenet_stats_articles to served,
        ).filter { it.second.isNotEmpty() }.forEach { (title, bars) ->
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
            SettingsSection(title = stringResource(title), isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) { StatsColumn(isTablet) { bars.forEach { BarRow(it) } } }
            }
        }

        indexers.forEach { indexer ->
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
            SettingsSection(title = indexer.name, isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    StatsColumn(isTablet) { IndexerRows(snapshot.indexers[indexer.id] ?: IndexerStatsUi(), snapshot.period) }
                }
            }
        }
        providers.forEach { provider ->
            Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
            SettingsSection(title = provider.name.ifBlank { provider.host }, isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    StatsColumn(isTablet) { ProviderRows(snapshot.providers[provider.id] ?: ProviderStatsUi(), snapshot.period) }
                }
            }
        }

        Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.usenet_stats_reset),
                description = stringResource(Res.string.usenet_stats_reset_desc),
                isTablet = isTablet,
                onClick = { confirmReset = true },
            )
        }

        when (choice) {
            StatsChoice.PERIOD -> SettingsChoiceDialog(
                title = stringResource(Res.string.usenet_stats_period),
                options = StatsPeriod.entries,
                selected = snapshot.period,
                label = { periodLabel(it) },
                onSelect = { UsenetPlatform.setStatsPeriod(it); choice = null },
                onDismiss = { choice = null },
            )
            StatsChoice.CONTENT -> SettingsChoiceDialog(
                title = stringResource(Res.string.usenet_stats_content),
                options = StatsContent.entries,
                selected = snapshot.content,
                label = { contentLabel(it) },
                onSelect = { UsenetPlatform.setStatsContent(it); choice = null },
                onDismiss = { choice = null },
            )
            null -> Unit
        }
        if (confirmReset) {
            DialogSurface(
                onDismissRequest = { confirmReset = false },
                title = stringResource(Res.string.usenet_stats_reset),
                message = stringResource(Res.string.usenet_stats_reset_confirm),
            ) {
                DialogButtons {
                    DialogButton(text = stringResource(Res.string.usenet_cancel), onClick = { confirmReset = false })
                    DialogButton(
                        text = stringResource(Res.string.usenet_stats_reset_action),
                        onClick = {
                            UsenetPlatform.resetStats()
                            confirmReset = false
                        },
                        style = DialogButtonStyle.Primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun IndexerRows(stats: IndexerStatsUi, period: StatsPeriod) {
    val counts = stats.counts ?: return Note(stringResource(Res.string.usenet_stats_no_data))
    val today = stats.today
    val searchParts = listOfNotNull(
        counts.failedSearches.takeIf { it > 0 }?.let { stringResource(Res.string.usenet_stats_failed, it) },
        counts.pausedSearches.takeIf { it > 0 }?.let { stringResource(Res.string.usenet_stats_paused, it) },
    )
    StatRow(
        stringResource(Res.string.usenet_stats_searches),
        if (searchParts.isEmpty()) "${counts.searches}" else "${counts.searches} (${searchParts.joinToString(", ")})",
    )
    counts.searchYield()?.let { yield ->
        StatRow(
            stringResource(Res.string.usenet_stats_with_results),
            pluralStringResource(Res.plurals.usenet_stats_releases_average, yield.averageReleases, "${yield.percent}%", yield.averageReleases),
        )
    }
    StatRow(
        stringResource(Res.string.usenet_stats_requests),
        today?.requests?.takeIf { it > 0 }?.let { stringResource(Res.string.usenet_stats_with_today, counts.requests, it) }
            ?: "${counts.requests}",
    )
    counts.averageResponseMs()?.let {
        StatRow(stringResource(Res.string.usenet_stats_response), stringResource(Res.string.usenet_stats_ms, it.toInt()))
    }
    StatRow(stringResource(Res.string.usenet_stats_limited), "${counts.limitedRequests}")
    StatRow(stringResource(Res.string.usenet_stats_failed_requests), "${counts.failedRequests}")
    StatRow(
        stringResource(Res.string.usenet_stats_grabs),
        today?.grabs?.takeIf { it > 0 }?.let { stringResource(Res.string.usenet_stats_with_today, counts.grabs, it) }
            ?: "${counts.grabs}",
    )
    StatRow(stringResource(Res.string.usenet_stats_outcomes), "${counts.played} / ${counts.unavailable}")
    if (period == StatsPeriod.ALL_TIME) stats.since?.let { StatRow(stringResource(Res.string.usenet_stats_since), UsenetPlatform.dateTime(it)) }
}

@Composable
private fun ProviderRows(stats: ProviderStatsUi, period: StatsPeriod) {
    val counts = stats.counts ?: return Note(stringResource(Res.string.usenet_stats_no_data))
    StatRow(stringResource(Res.string.usenet_stats_articles), "${counts.articles}")
    counts.completion()?.let { percent ->
        StatRow(
            stringResource(Res.string.usenet_stats_completion),
            stringResource(Res.string.usenet_stats_completion_value, "${decimal(percent, 1)}%", counts.missing.toInt()),
        )
    }
    StatRow(stringResource(Res.string.usenet_stats_errors), "${counts.errors}")
    StatRow(
        stringResource(Res.string.usenet_stats_downloaded),
        stringResource(Res.string.usenet_stats_gib, decimal(counts.bytes / (1024.0 * 1024 * 1024), 2)),
    )
    counts.averageFirstByteMs()?.let {
        StatRow(stringResource(Res.string.usenet_stats_first_byte), stringResource(Res.string.usenet_stats_ms, it.toInt()))
    }
    StatRow(
        stringResource(Res.string.usenet_stats_sessions),
        if (counts.failedOpens > 0) stringResource(Res.string.usenet_stats_sessions_failed, counts.sessions, counts.failedOpens)
        else "${counts.sessions}",
    )
    if (period == StatsPeriod.ALL_TIME) stats.since?.let { StatRow(stringResource(Res.string.usenet_stats_since), UsenetPlatform.dateTime(it)) }
}

/** [value] with [places] decimals, without a locale's grouping. */
internal fun decimal(value: Double, places: Int): String {
    var factor = 1L
    repeat(places) { factor *= 10 }
    val scaled = (value * factor).roundToLong()
    if (places == 0) return scaled.toString()
    val whole = scaled / factor
    val fraction = (kotlin.math.abs(scaled % factor)).toString().padStart(places, '0')
    return "$whole.$fraction"
}

@Composable
private fun periodLabel(period: StatsPeriod): String = stringResource(
    when (period) {
        StatsPeriod.LAST_7_DAYS -> Res.string.usenet_stats_period_7
        StatsPeriod.LAST_30_DAYS -> Res.string.usenet_stats_period_30
        StatsPeriod.ALL_TIME -> Res.string.usenet_stats_period_all
    },
)

@Composable
private fun contentLabel(content: StatsContent): String = stringResource(
    when (content) {
        StatsContent.ALL -> Res.string.usenet_stats_content_all
        StatsContent.MOVIES -> Res.string.usenet_stats_content_movies
        StatsContent.SHOWS -> Res.string.usenet_stats_content_shows
    },
)

@Composable
private fun StatsColumn(isTablet: Boolean, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textMuted)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textPrimary,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun BarRow(bar: StatsBar) {
    val colors = MaterialTheme.nuvio.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            bar.name,
            modifier = Modifier.width(120.dp),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(colors.textMuted.copy(alpha = 0.25f)),
        ) {
            if (bar.fraction > 0f) Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(bar.fraction).background(colors.accent))
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            "${bar.value}",
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textPrimary,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textMuted)
}
