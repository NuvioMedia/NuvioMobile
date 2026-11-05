package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.livetv.calendar.CalendarEntry
import com.nuvio.app.features.livetv.calendar.CalendarKind
import com.nuvio.app.features.livetv.calendar.CalendarRepository
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.sports.SportsDates
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.livetv_calendar
import nuvio.composeapp.generated.resources.livetv_calendar_all
import nuvio.composeapp.generated.resources.livetv_calendar_empty
import nuvio.composeapp.generated.resources.livetv_calendar_looking
import nuvio.composeapp.generated.resources.livetv_calendar_movies
import nuvio.composeapp.generated.resources.livetv_calendar_shows
import nuvio.composeapp.generated.resources.livetv_calendar_streaming
import nuvio.composeapp.generated.resources.livetv_calendar_today
import nuvio.composeapp.generated.resources.livetv_calendar_tomorrow
import nuvio.composeapp.generated.resources.livetv_calendar_watched
import org.jetbrains.compose.resources.stringResource

private enum class CalendarFilter { ALL, SHOWS, MOVIES }

/**
 * The Calendar: upcoming episodes of the shows in your Library and watch history, and movie
 * release dates, by day. Tap one to open its page.
 */
@Composable
internal fun CalendarScreen(
    onBack: () -> Unit,
    onOpenTitle: (type: String, id: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val state by CalendarRepository.state.collectAsStateWithLifecycle()
    val settings by LiveTvPreferences.settings.collectAsStateWithLifecycle()
    var filterName by rememberSaveable { mutableStateOf(CalendarFilter.ALL.name) }
    val filter = CalendarFilter.valueOf(filterName)
    LaunchedEffect(Unit) { CalendarRepository.refresh() }

    val shown = remember(state.entries, filter, settings.calendarHideWatched) {
        state.entries.filter { e ->
            when (filter) {
                CalendarFilter.ALL -> true
                CalendarFilter.SHOWS -> e.kind == CalendarKind.EPISODE
                CalendarFilter.MOVIES -> e.kind == CalendarKind.MOVIE
            } && !(settings.calendarHideWatched && e.watched)
        }
    }
    val days = remember(shown) { shown.groupBy { it.dayMs }.toList().sortedBy { it.first } }
    val today = remember(state.entries) { SportsDates.dayStart(0) }
    val listState = rememberLazyListState()
    // Open on today (or the next day with something on).
    LaunchedEffect(days.size) {
        if (days.isEmpty()) return@LaunchedEffect
        var index = 0
        val at = days.indexOfFirst { it.first >= today }.let { if (it < 0) days.size else it }
        for (i in 0 until at) index += 1 + days[i].second.size
        listState.scrollToItem(index.coerceAtMost((days.sumOf { 1 + it.second.size } - 1).coerceAtLeast(0)))
    }

    Column(modifier = modifier.fillMaxSize().background(tokens.colors.background).statusBarsPadding().liveTvSideInsets()) {
        NuvioScreenHeader(
            title = stringResource(Res.string.livetv_calendar),
            includeStatusBarPadding = false,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s8),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            modifier = Modifier.padding(bottom = NuvioTokens.Space.s8),
        ) {
            item { Chip(stringResource(Res.string.livetv_calendar_all), filter == CalendarFilter.ALL, { filterName = CalendarFilter.ALL.name }) }
            item { Chip(stringResource(Res.string.livetv_calendar_shows), filter == CalendarFilter.SHOWS, { filterName = CalendarFilter.SHOWS.name }) }
            item { Chip(stringResource(Res.string.livetv_calendar_movies), filter == CalendarFilter.MOVIES, { filterName = CalendarFilter.MOVIES.name }) }
        }
        if (state.loading) {
            val p = state.progress
            Text(
                text = stringResource(Res.string.livetv_calendar_looking) + (p?.let { " ${it.first}/${it.second}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = NuvioTokens.Space.s16, end = NuvioTokens.Space.s16, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            if (days.isEmpty() && !state.loading) {
                item {
                    Text(
                        stringResource(Res.string.livetv_calendar_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                        modifier = Modifier.padding(vertical = NuvioTokens.Space.s24),
                    )
                }
            }
            days.forEach { (day, entries) ->
                item(key = "d$day") { DayHeader(day, today) }
                items(entries.size, key = { "e" + entries[it].key }) { i ->
                    CalendarRow(entries[i], onClick = { e -> onOpenTitle(e.type, e.contentId, e.title) })
                }
            }
        }
    }
}

@Composable
private fun DayHeader(dayMs: Long, today: Long) {
    val tokens = MaterialTheme.nuvio
    val label = when (dayMs) {
        today -> stringResource(Res.string.livetv_calendar_today)
        SportsDates.dayStart(1) -> stringResource(Res.string.livetv_calendar_tomorrow)
        else -> SportsDates.dayLabel(dayMs)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (dayMs == today) tokens.colors.accent else tokens.colors.textPrimary,
        modifier = Modifier.padding(top = NuvioTokens.Space.s12, bottom = NuvioTokens.Space.s2),
    )
}

@Composable
private fun CalendarRow(e: CalendarEntry, onClick: (CalendarEntry) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val image = (if (e.spoiler) null else e.episodeImage) ?: e.background ?: e.poster
    val timeText = e.airMs?.let { formatClock(it) }
    val streamingLabel = stringResource(Res.string.livetv_calendar_streaming)
    val watchedLabel = stringResource(Res.string.livetv_calendar_watched)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(tokens.colors.surfaceCard)
            .clickable { onClick(e) }
            .alpha(if (e.watched) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(112.dp).height(68.dp).background(tokens.colors.background)) {
            if (image != null) {
                AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s8)) {
            Text(
                text = e.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val line = buildString {
                e.episodeCode?.let { append(it) }
                if (!e.spoiler) e.episodeTitle?.let { if (isNotEmpty()) append(" · "); append(it) }
                e.note?.let { if (isNotEmpty()) append(" · "); append(it) }
            }
            if (line.isNotBlank()) {
                Text(line, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val whenLine = buildString {
                if (timeText != null) append(timeText)
                val where = e.network ?: if (e.streaming && e.kind == CalendarKind.EPISODE) streamingLabel else null
                where?.let { if (isNotEmpty()) append(" · "); append(it) }
                if (e.watched) { if (isNotEmpty()) append(" · "); append(watchedLabel) }
            }
            val tags = e.tags.joinToString(" · ") { it.label }
            val bottom = listOf(whenLine, tags).filter { it.isNotBlank() }.joinToString("  ·  ")
            if (bottom.isNotBlank()) {
                Text(bottom, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
