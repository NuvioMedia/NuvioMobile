package com.nuvio.app.features.livetv.ui.sports

import com.nuvio.app.features.livetv.ui.liveTvSideInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.sports.ChannelMatch
import com.nuvio.app.features.livetv.sports.GameState
import com.nuvio.app.features.livetv.sports.League
import com.nuvio.app.features.livetv.sports.Sport
import com.nuvio.app.features.livetv.sports.SportsCatalog
import com.nuvio.app.features.livetv.sports.SportsDates
import com.nuvio.app.features.livetv.sports.SportsEvent
import com.nuvio.app.features.livetv.sports.SportsFollows
import com.nuvio.app.features.livetv.sports.SportsMatcher
import com.nuvio.app.features.livetv.sports.SportsReminders
import com.nuvio.app.features.livetv.sports.SportsRepository
import com.nuvio.app.features.livetv.sports.SportsRows
import com.nuvio.app.features.livetv.sports.SportsSettings
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.livetv_sports
import nuvio.composeapp.generated.resources.livetv_sports_all_events
import nuvio.composeapp.generated.resources.livetv_sports_finished
import nuvio.composeapp.generated.resources.livetv_sports_following
import nuvio.composeapp.generated.resources.livetv_sports_live_now
import nuvio.composeapp.generated.resources.livetv_sports_my_sports
import nuvio.composeapp.generated.resources.livetv_sports_nothing_followed
import nuvio.composeapp.generated.resources.livetv_sports_nothing_today
import nuvio.composeapp.generated.resources.livetv_sports_pick_teams
import nuvio.composeapp.generated.resources.livetv_sports_search
import nuvio.composeapp.generated.resources.livetv_sports_this_week
import nuvio.composeapp.generated.resources.livetv_sports_upcoming
import org.jetbrains.compose.resources.stringResource

/** What the Sports hub does outside itself: plays channels, opens pages. */
data class SportsActions(
    val onPlay: (LiveChannel, nowTitle: String?) -> Unit,
    val onPlayCatchup: (LiveChannel, EpgProgram) -> Unit,
    val onOpenGame: (SportsEvent) -> Unit,
    val onOpenLeague: (League) -> Unit,
)

private enum class SportsTab { MINE, ALL, SEARCH, FOLLOWING }

/**
 * The Sports hub: My Sports (a row per team, league and name you follow), All Events (every game
 * by day), Search (teams, leagues, names) and Following (manage what you follow). Tap a game for
 * its page: the channels showing it, record, remind, scores.
 */
@Composable
fun SportsScreen(
    onBack: () -> Unit,
    actions: SportsActions,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val repo = SportsRepository
    LaunchedEffect(Unit) { SportsReminders.start(); LiveTvRepository.ensureLoaded() }
    DisposableEffect(Unit) {
        repo.setViewing(true)
        onDispose { repo.setViewing(false) }
    }

    val settings by repo.settings.collectAsStateWithLifecycle()
    val follows by repo.follows.collectAsStateWithLifecycle()
    val events by repo.events.collectAsStateWithLifecycle()
    val known by repo.known.collectAsStateWithLifecycle()
    val matches by repo.matches.collectAsStateWithLifecycle()
    val loading by repo.loading.collectAsStateWithLifecycle()
    val matching by repo.matching.collectAsStateWithLifecycle()
    val problem by repo.problem.collectAsStateWithLifecycle()
    val use24h = use24h()
    val now by produceState(LiveTvClock.nowMs()) {
        while (true) { value = LiveTvClock.nowMs(); delay(15_000) }
    }
    var tabIndex by rememberSaveable { mutableIntStateOf(if (follows.isEmpty) SportsTab.ALL.ordinal else SportsTab.MINE.ordinal) }
    val tab = SportsTab.entries[tabIndex]
    var showSettings by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(tokens.colors.background).statusBarsPadding().liveTvSideInsets()) {
        NuvioScreenHeader(
            title = stringResource(Res.string.livetv_sports),
            includeStatusBarPadding = false,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s8),
            actions = {
                if (loading || matching) NuvioLoadingIndicator(size = NuvioTokens.Space.s16)
                IconButton(onClick = { repo.refreshNow() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = tokens.colors.textPrimary)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Tune, contentDescription = null, tint = tokens.colors.textPrimary)
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s6),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            Chip(stringResource(Res.string.livetv_sports_my_sports), selected = tab == SportsTab.MINE, onClick = { tabIndex = SportsTab.MINE.ordinal })
            Chip(stringResource(Res.string.livetv_sports_all_events), selected = tab == SportsTab.ALL, onClick = { tabIndex = SportsTab.ALL.ordinal })
            Chip(stringResource(Res.string.livetv_sports_search), selected = tab == SportsTab.SEARCH, onClick = { tabIndex = SportsTab.SEARCH.ordinal })
            Chip(stringResource(Res.string.livetv_sports_following), selected = tab == SportsTab.FOLLOWING, onClick = { tabIndex = SportsTab.FOLLOWING.ordinal })
        }
        problem?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.danger, modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when (tab) {
            SportsTab.MINE -> MySportsTab(known, follows, settings, matches, now, use24h, actions, onPick = { tabIndex = SportsTab.FOLLOWING.ordinal })
            SportsTab.ALL -> AllEventsTab(events, follows, settings, matches, now, use24h, loading, actions)
            SportsTab.SEARCH -> SportsSearchTab(known, follows, actions)
            SportsTab.FOLLOWING -> SportsFollowingTab(follows, settings, actions)
        }
    }

    if (showSettings) SportsSettingsSheet(settings, onDismiss = { showSettings = false })
}

// ------------------------------------------------------------------ My Sports

@Composable
private fun MySportsTab(
    known: List<SportsEvent>,
    follows: SportsFollows,
    settings: SportsSettings,
    matches: Map<String, List<ChannelMatch>>,
    now: Long,
    use24h: Boolean,
    actions: SportsActions,
    onPick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val repo = SportsRepository
    val tables by repo.standings.collectAsStateWithLifecycle()
    LaunchedEffect(follows.teams) { runCatching { repo.loadTeamTables() } }
    val page = remember(known, follows, now / 60_000L) { SportsRows.build(known, follows, now) { isMine(it, follows) } }
    if (follows.isEmpty) {
        EmptyNote(stringResource(Res.string.livetv_sports_nothing_followed), stringResource(Res.string.livetv_sports_pick_teams), onPick)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        if (page.rows.isEmpty()) {
            item { Text(stringResource(Res.string.livetv_sports_nothing_today), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, modifier = Modifier.padding(NuvioTokens.Space.s16)) }
        }
        items(page.rows, key = { it.key }) { row ->
            val place = row.team?.let { t -> tables[t.leagueId]?.placeText(t.teamId) }
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                val headerTeam = row.team?.toTeamInfo()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NuvioTokens.Space.s16)
                        .let { m ->
                            if (headerTeam != null) m.clip(RoundedCornerShape(NuvioTokens.Radius.lg)).background(teamBandBrush(headerTeam)).padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s6)
                            else m
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                ) {
                    row.team?.let { t -> TeamLogo(t.toTeamInfo(), 22.dp) }
                    row.league?.let { l -> LeagueMark(l, 22.dp) }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.title, style = MaterialTheme.typography.titleSmall, color = if (headerTeam != null) Color.White else tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        place?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (headerTeam != null) Color.White.copy(alpha = 0.7f) else tokens.colors.textMuted) }
                    }
                    row.league?.takeIf { repo.mayHaveTable(it) }?.let { l ->
                        Chip(label = "Table", selected = false, onClick = { actions.onOpenLeague(l) })
                    }
                }
                val state = rememberLazyListState(initialFirstVisibleItemIndex = row.startIndex.coerceIn(0, (row.events.size - 1).coerceAtLeast(0)))
                LazyRow(
                    state = state,
                    contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                ) {
                    items(row.events, key = { it.id }) { ev ->
                        RowCard(ev, follows, now, use24h, settings.hideScores) { actions.onOpenGame(ev) }
                    }
                }
            }
        }
    }
}

private fun com.nuvio.app.features.livetv.sports.FollowedTeam.toTeamInfo() =
    com.nuvio.app.features.livetv.sports.TeamInfo(leagueId, teamId, name, name, abbr, null, color, null, logo)

// ------------------------------------------------------------------ All Events

private class DaySections(
    val weekLong: List<SportsEvent>,
    val live: List<SportsEvent>,
    val upcoming: List<SportsEvent>,
    val finished: List<SportsEvent>,
)

@Composable
private fun AllEventsTab(
    events: List<SportsEvent>,
    follows: SportsFollows,
    settings: SportsSettings,
    matches: Map<String, List<ChannelMatch>>,
    now: Long,
    use24h: Boolean,
    loading: Boolean,
    actions: SportsActions,
) {
    val tokens = MaterialTheme.nuvio
    val repo = SportsRepository
    var day by rememberSaveable { mutableIntStateOf(0) }
    var sportFilter by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(day) { if (day < -1) repo.wantPast() }
    val dayStart = repo.dayStart(day)
    val dayEnd = repo.dayStart(day + 1)
    val active = remember(settings, follows) { repo.activeLeagues(settings, follows).map { it.id }.toSet() + SportsMatcher.OTHER_ID }
    val hasChannels = LiveTvRepository.channels.collectAsStateWithLifecycle().value.isNotEmpty()
    val filterToChannels = settings.onlyOnMyChannels && hasChannels && matches.isNotEmpty()
    val dayEvents = remember(events, dayStart, dayEnd, active, filterToChannels, matches, follows, now / 60_000L) {
        events.filter { ev ->
            val onDay = when {
                ev.state == GameState.POST -> {
                    val finished = if (ev.hasTime) ev.startMs else minOf(ev.endMs ?: ev.startMs, now)
                    finished in dayStart until dayEnd
                }
                ev.hasTime -> ev.startMs in dayStart until dayEnd
                ev.state == GameState.PRE -> ev.startMs in dayStart until dayEnd
                else -> dayEnd > now && ev.startMs < dayEnd && (ev.endMs ?: (ev.startMs + 1)) >= dayStart
            }
            ev.leagueId in active && onDay &&
                (!filterToChannels || ev.state == GameState.POST || isMine(ev, follows) || matches[ev.id]?.isNotEmpty() == true)
        }
    }
    val sports = remember(dayEvents, settings) {
        val order = SportsCatalog.sportsOrder(settings)
        dayEvents.mapNotNull { SportsCatalog.league(it.leagueId)?.sport }.distinct().sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
    }
    val shown = remember(dayEvents, sportFilter) {
        if (sportFilter == null) dayEvents else dayEvents.filter { SportsCatalog.league(it.leagueId)?.sport?.name == sportFilter }
    }
    val leagueRank = remember(settings) { SportsCatalog.ordered(settings).mapIndexed { i, l -> l.id to i }.toMap() }
    val sections = remember(shown, matches, follows, now / 60_000L, leagueRank) {
        fun rank(ev: SportsEvent) = leagueRank[ev.leagueId] ?: 999
        fun airingNow(ev: SportsEvent): Boolean = matches[ev.id]?.any { m -> m.program?.let { now >= it.startMs && now < it.stopMs } == true } == true
        val weekLong = shown.filter { !it.hasTime && it.state == GameState.LIVE && !airingNow(it) }
        val weekIds = weekLong.mapTo(HashSet()) { it.id }
        val live = shown.filter { it.state == GameState.LIVE && it.id !in weekIds }
            .sortedWith(compareByDescending<SportsEvent> { isMine(it, follows) }.thenByDescending { matches[it.id]?.isNotEmpty() == true }.thenBy { rank(it) }.thenBy { it.startMs })
        val upcoming = shown.filter { it.state == GameState.PRE || it.state == GameState.OFF }
            .sortedWith(compareBy<SportsEvent> { if (it.hasTime) it.startMs else Long.MIN_VALUE }.thenByDescending { isMine(it, follows) }.thenBy { rank(it) })
        val finished = shown.filter { it.state == GameState.POST }
            .sortedWith(compareByDescending<SportsEvent> { it.startMs }.thenBy { rank(it) })
        DaySections(weekLong, live, upcoming, finished)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            state = rememberLazyListState(initialFirstVisibleItemIndex = SportsRepository.DAYS_BACK - 1),
            contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s12),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            items((-SportsRepository.DAYS_BACK..SportsRepository.DAYS_AHEAD).toList(), key = { it }) { offset ->
                val label = when (offset) {
                    -1 -> "Yesterday"
                    0 -> "Today"
                    1 -> "Tomorrow"
                    else -> SportsDates.shortDayLabel(repo.dayStart(offset) + 12 * 3_600_000L)
                }
                Chip(label, selected = day == offset, onClick = { day = offset })
            }
        }
        if (sports.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s6),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
            ) {
                item { Chip("All", selected = sportFilter == null, onClick = { sportFilter = null }) }
                items(sports, key = { it.name }) { s -> Chip(s.icon + " " + s.label, selected = sportFilter == s.name, onClick = { sportFilter = s.name }) }
            }
        }
        val empty = sections.live.isEmpty() && sections.upcoming.isEmpty() && sections.finished.isEmpty() && sections.weekLong.isEmpty()
        val liveTitle = stringResource(Res.string.livetv_sports_live_now)
        val weekTitle = stringResource(Res.string.livetv_sports_this_week)
        val upcomingTitle = stringResource(Res.string.livetv_sports_upcoming)
        val finishedTitle = stringResource(Res.string.livetv_sports_finished)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = NuvioTokens.Space.s16, end = NuvioTokens.Space.s16, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            if (empty) {
                item {
                    Text(
                        if (loading) "Getting today's games…" else stringResource(Res.string.livetv_sports_nothing_today),
                        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, modifier = Modifier.padding(vertical = NuvioTokens.Space.s24),
                    )
                }
            }
            section(liveTitle, sections.live, follows, settings, matches, now, use24h, actions, live = true)
            section(weekTitle, sections.weekLong, follows, settings, matches, now, use24h, actions)
            section(upcomingTitle, sections.upcoming, follows, settings, matches, now, use24h, actions)
            section(finishedTitle, sections.finished, follows, settings, matches, now, use24h, actions)
        }
    }
}

private fun LazyListScope.section(
    title: String,
    list: List<SportsEvent>,
    follows: SportsFollows,
    settings: SportsSettings,
    matches: Map<String, List<ChannelMatch>>,
    now: Long,
    use24h: Boolean,
    actions: SportsActions,
    live: Boolean = false,
) {
    if (list.isEmpty()) return
    item(key = "h:$title") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = NuvioTokens.Space.s8)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (live) SportsLiveRed else MaterialTheme.nuvio.colors.textPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(NuvioTokens.Space.s8))
            Text(list.size.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textMuted)
        }
    }
    items(list, key = { "$title/" + it.id }) { ev ->
        val found = matches[ev.id]
        GameCard(
            ev, follows, now, use24h, settings.hideScores,
            channels = if (matches.isEmpty() || ev.state == GameState.POST) null else found?.size ?: 0,
        ) { actions.onOpenGame(ev) }
    }
}

@Composable
internal fun EmptyNote(text: String, button: String? = null, onClick: () -> Unit = {}) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxWidth().padding(NuvioTokens.Space.s24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
        if (button != null) NuvioPrimaryButton(text = button, onClick = onClick)
    }
}
