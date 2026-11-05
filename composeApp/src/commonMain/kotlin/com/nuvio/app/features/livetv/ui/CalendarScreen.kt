package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.livetv.calendar.CalendarEntry
import com.nuvio.app.features.livetv.calendar.CalendarFollow
import com.nuvio.app.features.livetv.calendar.CalendarGames
import com.nuvio.app.features.livetv.calendar.CalendarGuide
import com.nuvio.app.features.livetv.calendar.CalendarGuideHit
import com.nuvio.app.features.livetv.calendar.CalendarKind
import com.nuvio.app.features.livetv.calendar.CalendarRepository
import com.nuvio.app.features.livetv.data.CivilTime
import com.nuvio.app.features.livetv.LiveTvDeepLinks
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.data.LiveTvTimeZones
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.sports.SportsDates
import com.nuvio.app.features.livetv.sports.SportsRepository
import com.nuvio.app.features.livetv.ui.sports.GameCard
import com.nuvio.app.features.livetv.ui.sports.use24h
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.livetv_calendar
import nuvio.composeapp.generated.resources.livetv_calendar_airing_empty
import nuvio.composeapp.generated.resources.livetv_calendar_airing_tab
import nuvio.composeapp.generated.resources.livetv_calendar_all
import nuvio.composeapp.generated.resources.livetv_calendar_empty
import nuvio.composeapp.generated.resources.livetv_calendar_follow
import nuvio.composeapp.generated.resources.livetv_calendar_following_empty
import nuvio.composeapp.generated.resources.livetv_calendar_following_tab
import nuvio.composeapp.generated.resources.livetv_calendar_hide
import nuvio.composeapp.generated.resources.livetv_calendar_last
import nuvio.composeapp.generated.resources.livetv_calendar_list
import nuvio.composeapp.generated.resources.livetv_calendar_looking
import nuvio.composeapp.generated.resources.livetv_calendar_month
import nuvio.composeapp.generated.resources.livetv_calendar_movies
import nuvio.composeapp.generated.resources.livetv_calendar_news_talk
import nuvio.composeapp.generated.resources.livetv_calendar_next
import nuvio.composeapp.generated.resources.livetv_calendar_nothing_scheduled
import nuvio.composeapp.generated.resources.livetv_calendar_open
import nuvio.composeapp.generated.resources.livetv_calendar_on_tv
import nuvio.composeapp.generated.resources.livetv_calendar_sports
import nuvio.composeapp.generated.resources.livetv_calendar_watch_live
import nuvio.composeapp.generated.resources.livetv_calendar_record
import nuvio.composeapp.generated.resources.livetv_calendar_cancel_recording
import nuvio.composeapp.generated.resources.livetv_calendar_remind
import nuvio.composeapp.generated.resources.livetv_calendar_cancel_reminder
import nuvio.composeapp.generated.resources.livetv_calendar_choose_channel
import nuvio.composeapp.generated.resources.livetv_calendar_pick_channel
import nuvio.composeapp.generated.resources.livetv_calendar_rec_badge
import nuvio.composeapp.generated.resources.livetv_calendar_reminder_badge
import nuvio.composeapp.generated.resources.livetv_calendar_recent_empty
import nuvio.composeapp.generated.resources.livetv_calendar_recent_tab
import nuvio.composeapp.generated.resources.livetv_calendar_shows
import nuvio.composeapp.generated.resources.livetv_calendar_streaming
import nuvio.composeapp.generated.resources.livetv_calendar_today
import nuvio.composeapp.generated.resources.livetv_calendar_tomorrow
import nuvio.composeapp.generated.resources.livetv_calendar_unfollow
import nuvio.composeapp.generated.resources.livetv_calendar_unwatched_only
import nuvio.composeapp.generated.resources.livetv_calendar_upcoming_tab
import nuvio.composeapp.generated.resources.livetv_calendar_watched
import nuvio.composeapp.generated.resources.livetv_calendar_week
import org.jetbrains.compose.resources.stringResource

private enum class CalTab { UPCOMING, AIRING, RECENT, FOLLOWING }
private enum class CalFilter { ALL, SHOWS, MOVIES, SPORTS, ON_TV }
private enum class CalView { LIST, WEEK, MONTH }

private const val DAY_MS = 24 * 3_600_000L
private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * The Calendar: upcoming episodes of the shows in your Library and watch history, and movie
 * release dates (List, Week or Month), what's airing today, what aired recently, and the shows
 * you follow. Tap a row to open its page; hold it to follow it or take it off the Calendar.
 */
@Composable
internal fun CalendarScreen(
    onBack: () -> Unit,
    onOpenTitle: (type: String, id: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val state by CalendarRepository.state.collectAsStateWithLifecycle()
    val airing by CalendarRepository.airing.collectAsStateWithLifecycle()
    val follows by CalendarRepository.follows.collectAsStateWithLifecycle()
    val settings by LiveTvPreferences.settings.collectAsStateWithLifecycle()
    var tabName by rememberSaveable { mutableStateOf(CalTab.UPCOMING.name) }
    var filterName by rememberSaveable { mutableStateOf(CalFilter.ALL.name) }
    var viewName by rememberSaveable { mutableStateOf(settings.calendarView.uppercase().takeIf { v -> CalView.entries.any { it.name == v } } ?: CalView.LIST.name) }
    var selectedDay by rememberSaveable { mutableStateOf(SportsDates.dayStart(0)) }
    var weekOffset by rememberSaveable { mutableStateOf(0) }
    var monthOffset by rememberSaveable { mutableStateOf(0) }
    var menuFor by remember { mutableStateOf<CalendarEntry?>(null) }
    val tab = CalTab.valueOf(tabName)
    val filter = CalFilter.valueOf(filterName)
    val view = CalView.valueOf(viewName)
    val known by SportsRepository.known.collectAsStateWithLifecycle()
    val sportsFollows by SportsRepository.follows.collectAsStateWithLifecycle()
    val sportsSettings by SportsRepository.settings.collectAsStateWithLifecycle()
    val matches by SportsRepository.matches.collectAsStateWithLifecycle()
    val guideHits by CalendarGuide.hits.collectAsStateWithLifecycle()
    val channels by LiveTvRepository.channels.collectAsStateWithLifecycle()
    val games = remember(known, sportsFollows, settings.calendarSports, sportsSettings.showInSidebar) {
        if (settings.calendarSports && sportsSettings.showInSidebar) CalendarGames.entries(known, sportsFollows, 0L) else emptyList()
    }
    val everything = remember(state.entries, games) {
        (state.entries + games).sortedWith(compareBy<CalendarEntry> { it.dayMs }.thenBy { it.airMs ?: Long.MAX_VALUE }.thenBy { it.title })
    }
    LaunchedEffect(Unit) { CalendarRepository.refresh() }
    val picks by CalendarRepository.channelPicks.collectAsStateWithLifecycle()
    LaunchedEffect(state.entries, channels.size, picks) { CalendarGuide.match(state.entries) }
    LaunchedEffect(tab) { if (tab == CalTab.AIRING) CalendarRepository.loadAiring() }

    val today = SportsDates.dayStart(0)
    val now = LiveTvClock.nowMs()
    val pool = remember(everything, filter, settings.calendarHideWatched, guideHits, matches) {
        everything.filter { e ->
            when (filter) {
                CalFilter.ALL -> true
                CalFilter.SHOWS -> e.kind == CalendarKind.EPISODE
                CalFilter.MOVIES -> e.kind == CalendarKind.MOVIE
                CalFilter.SPORTS -> e.kind == CalendarKind.GAME
                CalFilter.ON_TV -> guideHits[e.key].orEmpty().isNotEmpty() || (e.kind == CalendarKind.GAME && matches[e.eventId].orEmpty().isNotEmpty())
            } && !(settings.calendarHideWatched && e.watched)
        }
    }
    val open: (CalendarEntry) -> Unit = { e ->
        if (e.kind == CalendarKind.GAME) e.eventId?.let { LiveTvDeepLinks.handle(LiveTvDeepLinks.sportsGameUrl(it)) }
        else onOpenTitle(e.type, e.contentId, e.title)
    }
    val hold: (CalendarEntry) -> Unit = { e -> menuFor = e }

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
            modifier = Modifier.padding(bottom = NuvioTokens.Space.s4),
        ) {
            item { Chip(stringResource(Res.string.livetv_calendar_upcoming_tab), tab == CalTab.UPCOMING, { tabName = CalTab.UPCOMING.name }) }
            item { Chip(stringResource(Res.string.livetv_calendar_airing_tab), tab == CalTab.AIRING, { tabName = CalTab.AIRING.name }) }
            item { Chip(stringResource(Res.string.livetv_calendar_recent_tab), tab == CalTab.RECENT, { tabName = CalTab.RECENT.name }) }
            item { Chip(stringResource(Res.string.livetv_calendar_following_tab), tab == CalTab.FOLLOWING, { tabName = CalTab.FOLLOWING.name }) }
        }
        if (tab == CalTab.UPCOMING || tab == CalTab.RECENT) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                modifier = Modifier.padding(bottom = NuvioTokens.Space.s8),
            ) {
                item { SmallChip(stringResource(Res.string.livetv_calendar_all), filter == CalFilter.ALL) { filterName = CalFilter.ALL.name } }
                item { SmallChip(stringResource(Res.string.livetv_calendar_shows), filter == CalFilter.SHOWS) { filterName = CalFilter.SHOWS.name } }
                item { SmallChip(stringResource(Res.string.livetv_calendar_movies), filter == CalFilter.MOVIES) { filterName = CalFilter.MOVIES.name } }
                if (games.isNotEmpty()) item { SmallChip(stringResource(Res.string.livetv_calendar_sports), filter == CalFilter.SPORTS) { filterName = CalFilter.SPORTS.name } }
                if (channels.isNotEmpty()) item { SmallChip(stringResource(Res.string.livetv_calendar_on_tv), filter == CalFilter.ON_TV) { filterName = CalFilter.ON_TV.name } }
            }
        }
        if (tab == CalTab.UPCOMING) {
            Row(
                modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16).padding(bottom = NuvioTokens.Space.s8).fillMaxWidth()
                    .clip(RoundedCornerShape(NuvioTokens.Radius.md)).background(tokens.colors.surfaceCard),
            ) {
                listOf(
                    CalView.LIST to stringResource(Res.string.livetv_calendar_list),
                    CalView.WEEK to stringResource(Res.string.livetv_calendar_week),
                    CalView.MONTH to stringResource(Res.string.livetv_calendar_month),
                ).forEach { (v, label) ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        color = if (v == view) tokens.colors.textPrimary else tokens.colors.textMuted,
                        modifier = Modifier.weight(1f).padding(NuvioTokens.Space.s2).clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                            .background(if (v == view) tokens.colors.background else Color.Transparent)
                            .clickable { viewName = v.name; LiveTvPreferences.updateSettings { it.copy(calendarView = v.name) } }
                            .padding(vertical = NuvioTokens.Space.s8),
                    )
                }
            }
        }
        if (state.loading && tab != CalTab.AIRING && tab != CalTab.FOLLOWING) {
            val p = state.progress
            Text(
                text = stringResource(Res.string.livetv_calendar_looking) + (p?.let { " ${it.first}/${it.second}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
            )
        }
        when (tab) {
            CalTab.UPCOMING -> when (view) {
                CalView.LIST -> UpcomingList(pool, today, now, state.loading, open, hold)
                CalView.WEEK -> {
                    val mondayIndex = WEEKDAYS.indexOf(SportsDates.weekdayLabel(today))
                    val days = List(7) { SportsDates.dayStart(-mondayIndex + weekOffset * 7 + it) }
                    GridHeader(
                        label = SportsDates.shortDayLabel(days.first()) + " " + SportsDates.monthLabel(days.first()) + " – " +
                            SportsDates.shortDayLabel(days.last()) + " " + SportsDates.monthLabel(days.last()),
                        onPrev = { weekOffset -= 1 }, onNext = { weekOffset += 1 },
                    )
                    DayStrip(days, pool, today, selectedDay) { selectedDay = it }
                    DayAgenda(pool.filter { it.dayMs == selectedDay }, selectedDay, today, now, open, hold)
                }
                CalView.MONTH -> {
                    val t = CivilTime.at(today, LiveTvTimeZones.SYSTEM)
                    val index = t.year * 12 + (t.month - 1) + monthOffset
                    val y = index / 12
                    val m = index % 12 + 1
                    val first = SportsDates.localDate(y, m, 1)
                    val nextFirst = SportsDates.localDate(if (m == 12) y + 1 else y, if (m == 12) 1 else m + 1, 1)
                    val count = ((nextFirst - first + DAY_MS / 2) / DAY_MS).toInt()
                    val lead = WEEKDAYS.indexOf(SportsDates.weekdayLabel(first))
                    GridHeader(
                        label = SportsDates.monthLabel(first) + " " + y,
                        onPrev = { monthOffset -= 1 }, onNext = { monthOffset += 1 },
                    )
                    MonthGrid(lead, count, first, pool, today, selectedDay) { selectedDay = it }
                    DayAgenda(pool.filter { it.dayMs == selectedDay }, selectedDay, today, now, open, hold)
                }
            }
            CalTab.AIRING -> AiringTab(airing.entries, airing.loading, airing.problem, settings.calendarSkipNewsTalk, open, hold) { skip ->
                LiveTvPreferences.updateSettings { it.copy(calendarSkipNewsTalk = skip) }
                CalendarRepository.loadAiring(force = true)
            }
            CalTab.RECENT -> RecentTab(
                entries = pool.filter { (it.airMs ?: it.dayMs) <= now && it.dayMs >= today - 14 * DAY_MS }
                    .sortedWith(compareByDescending<CalendarEntry> { it.dayMs }.thenByDescending { it.airMs ?: 0L }),
                today = today,
                hideWatched = settings.calendarHideWatched,
                onHideWatched = { on -> LiveTvPreferences.updateSettings { it.copy(calendarHideWatched = on) } },
                open = open, hold = hold,
            )
            CalTab.FOLLOWING -> FollowingTab(follows, state.entries, now, open = { f -> onOpenTitle(f.type, f.id, f.name) }, onHold = { f ->
                menuFor = followAsEntry(f)
            })
        }
    }
    menuFor?.let { e ->
        EntrySheet(e, followed = CalendarRepository.isFollowed(e.contentId), hits = guideHits[e.key].orEmpty(), onDismiss = { menuFor = null }, onOpen = { open(e) })
    }
}

private fun followAsEntry(f: CalendarFollow) = CalendarEntry(
    key = "follow|${f.id}", kind = if (f.type == "movie") CalendarKind.MOVIE else CalendarKind.EPISODE, contentId = f.id, type = f.type,
    title = f.name, poster = f.poster, background = f.background, addonBaseUrl = f.addonBaseUrl, dayMs = 0L,
)

// ---- pieces

@Composable
private fun SmallChip(label: String, selected: Boolean, onClick: () -> Unit) = Chip(label, selected, onClick)

@Composable
private fun UpcomingList(
    entries: List<CalendarEntry>, today: Long, now: Long, loading: Boolean,
    open: (CalendarEntry) -> Unit, hold: (CalendarEntry) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shown = entries.filter { it.dayMs > today || (it.dayMs == today) }
    val days = remember(shown) { shown.groupBy { it.dayMs }.toList().sortedBy { it.first } }
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = NuvioTokens.Space.s16, end = NuvioTokens.Space.s16, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        if (days.isEmpty() && !loading) {
            item { EmptyNote(stringResource(Res.string.livetv_calendar_empty)) }
        }
        days.forEach { (day, list) ->
            item(key = "d$day") { DayHeader(day, today) }
            items(list.size, key = { "e" + list[it].key }) { i -> CalendarRow(list[i], open, hold) }
        }
    }
}

@Composable
private fun DayAgenda(
    list: List<CalendarEntry>, day: Long, today: Long, now: Long,
    open: (CalendarEntry) -> Unit, hold: (CalendarEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = NuvioTokens.Space.s16, end = NuvioTokens.Space.s16, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        item { DayHeader(day, today) }
        if (list.isEmpty()) item { EmptyNote(stringResource(Res.string.livetv_calendar_empty)) }
        items(list.size, key = { "a" + list[it].key }) { i -> CalendarRow(list[i], open, hold) }
    }
}

@Composable
private fun GridHeader(label: String, onPrev: () -> Unit, onNext: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary, modifier = Modifier.clickable { onPrev() }.padding(horizontal = NuvioTokens.Space.s16))
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary)
        Text("›", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary, modifier = Modifier.clickable { onNext() }.padding(horizontal = NuvioTokens.Space.s16))
    }
}

@Composable
private fun DayStrip(days: List<Long>, entries: List<CalendarEntry>, today: Long, selected: Long, onPick: (Long) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s4),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        days.forEach { d ->
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    SportsDates.weekdayLabel(d), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.nuvio.colors.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                DayCell(d, entries.filter { it.dayMs == d }, today, selected, onPick)
            }
        }
    }
}

@Composable
private fun MonthGrid(lead: Int, count: Int, first: Long, entries: List<CalendarEntry>, today: Long, selected: Long, onPick: (Long) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val byDay = remember(entries) { entries.groupBy { it.dayMs } }
    val cells = lead + count
    val rows = (cells + 6) / 7
    val ty = CivilTime.at(first, LiveTvTimeZones.SYSTEM)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s12), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            WEEKDAYS.forEach { Text(it.take(1), style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        for (r in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
                for (c in 0 until 7) {
                    val n = r * 7 + c - lead + 1
                    Box(modifier = Modifier.weight(1f)) {
                        if (n in 1..count) {
                            val d = SportsDates.localDate(ty.year, ty.month, n)
                            DayCell(d, byDay[d].orEmpty(), today, selected, onPick, label = n.toString())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: Long, list: List<CalendarEntry>, today: Long, selected: Long, onPick: (Long) -> Unit, label: String? = null) {
    val tokens = MaterialTheme.nuvio
    val t = CivilTime.at(day, LiveTvTimeZones.SYSTEM)
    Column(
        modifier = Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(NuvioTokens.Radius.sm))
            .background(tokens.colors.surfaceCard)
            .then(if (day == selected) Modifier.border(1.5.dp, tokens.colors.accent, RoundedCornerShape(NuvioTokens.Radius.sm)) else Modifier)
            .clickable { onPick(day) }.padding(top = NuvioTokens.Space.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label ?: t.day.toString(), style = MaterialTheme.typography.labelMedium,
            fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
            color = if (day == today) tokens.colors.accent else tokens.colors.textPrimary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 3.dp)) {
            if (list.any { it.kind == CalendarKind.EPISODE }) Dot(Color(0xFF6F9BE0))
            if (list.any { it.kind == CalendarKind.MOVIE }) Dot(Color(0xFFE5B84B))
        }
    }
}

@Composable
private fun Dot(color: Color) = Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(color))

@Composable
private fun AiringTab(
    entries: List<CalendarEntry>, loading: Boolean, problem: String?, skipNewsTalk: Boolean,
    open: (CalendarEntry) -> Unit, hold: (CalendarEntry) -> Unit, onSkipNewsTalk: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val today = SportsDates.dayStart(0)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = NuvioTokens.Space.s16, end = NuvioTokens.Space.s16, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(SportsDates.dayLabel(today), style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.livetv_calendar_news_talk), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
                    Switch(checked = !skipNewsTalk, onCheckedChange = { onSkipNewsTalk(!it) }, modifier = Modifier.padding(start = NuvioTokens.Space.s8))
                }
            }
        }
        if (loading && entries.isEmpty()) item { EmptyNote(stringResource(Res.string.livetv_calendar_looking)) }
        else if (entries.isEmpty()) item { EmptyNote(problem ?: stringResource(Res.string.livetv_calendar_airing_empty)) }
        items((entries.size + 2) / 3, key = { "ar$it" }) { r ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                for (c in 0 until 3) {
                    val e = entries.getOrNull(r * 3 + c)
                    if (e == null) Spacer(Modifier.weight(1f)) else PosterTile(e, Modifier.weight(1f), open, hold)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterTile(e: CalendarEntry, modifier: Modifier, open: (CalendarEntry) -> Unit, hold: (CalendarEntry) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val timeText = e.airMs?.let { formatClock(it) }
    val streaming = stringResource(Res.string.livetv_calendar_streaming)
    Column(modifier = modifier.combinedClickable(onClick = { open(e) }, onLongClick = { hold(e) })) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(NuvioTokens.Radius.md)).background(tokens.colors.surfaceCard)) {
            (e.poster ?: e.background)?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(e.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = NuvioTokens.Space.s4))
        Text(timeText ?: streaming, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1)
    }
}

@Composable
private fun RecentTab(
    entries: List<CalendarEntry>, today: Long, hideWatched: Boolean, onHideWatched: (Boolean) -> Unit,
    open: (CalendarEntry) -> Unit, hold: (CalendarEntry) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = NuvioTokens.Space.s16, end = NuvioTokens.Space.s16, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                Text(stringResource(Res.string.livetv_calendar_unwatched_only), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
                Switch(checked = hideWatched, onCheckedChange = onHideWatched, modifier = Modifier.padding(start = NuvioTokens.Space.s8))
            }
        }
        if (entries.isEmpty()) item { EmptyNote(stringResource(Res.string.livetv_calendar_recent_empty)) }
        var lastDay = -1L
        entries.forEachIndexed { i, e ->
            if (e.dayMs != lastDay) {
                lastDay = e.dayMs
                item(key = "rd${e.dayMs}") { DayHeader(e.dayMs, today) }
            }
            item(key = "re${e.key}") { CalendarRow(e, open, hold) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FollowingTab(
    follows: List<CalendarFollow>, entries: List<CalendarEntry>, now: Long,
    open: (CalendarFollow) -> Unit, onHold: (CalendarFollow) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = follows.firstOrNull { it.id == selectedId } ?: follows.firstOrNull()
    Column(modifier = Modifier.fillMaxSize()) {
        if (follows.isEmpty()) {
            EmptyNote(stringResource(Res.string.livetv_calendar_following_empty), Modifier.padding(horizontal = NuvioTokens.Space.s16))
            return@Column
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            items((follows.size + 2) / 3, key = { "fr$it" }) { r ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                    for (c in 0 until 3) {
                        val f = follows.getOrNull(r * 3 + c)
                        if (f == null) Spacer(Modifier.weight(1f)) else {
                            Box(
                                modifier = Modifier.weight(1f).aspectRatio(2f / 3f).clip(RoundedCornerShape(NuvioTokens.Radius.md))
                                    .background(tokens.colors.surfaceCard)
                                    .then(if (f.id == selected?.id) Modifier.border(2.dp, tokens.colors.accent, RoundedCornerShape(NuvioTokens.Radius.md)) else Modifier)
                                    .combinedClickable(onClick = { selectedId = f.id }, onLongClick = { onHold(f) }),
                            ) {
                                (f.poster ?: f.background)?.let { AsyncImage(model = it, contentDescription = f.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            }
                        }
                    }
                }
            }
        }
        selected?.let { f ->
            val ids = setOf(f.id, f.imdbId ?: f.id)
            val mine = entries.filter { it.contentId in ids }
            val next = mine.firstOrNull { (it.airMs ?: it.dayMs) >= now }
            val last = mine.lastOrNull { (it.airMs ?: it.dayMs) < now }
            val line = when {
                next != null -> stringResource(Res.string.livetv_calendar_next) + " " + listOfNotNull(next.episodeCode, SportsDates.dayLabel(next.dayMs), next.airMs?.let { formatClock(it) }, next.network).joinToString(" · ")
                last != null -> stringResource(Res.string.livetv_calendar_last) + " " + listOfNotNull(last.episodeCode, SportsDates.dayLabel(last.dayMs)).joinToString(" · ")
                else -> stringResource(Res.string.livetv_calendar_nothing_scheduled)
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(NuvioTokens.Space.s16).nuvioSafeBottomInset()
                    .clip(RoundedCornerShape(NuvioTokens.Radius.md)).background(tokens.colors.surfaceCard).padding(NuvioTokens.Space.s12),
            ) {
                Text(f.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary)
                Text(line, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, modifier = Modifier.padding(vertical = NuvioTokens.Space.s4))
                next?.description?.takeIf { !next.spoiler }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                Text(
                    stringResource(Res.string.livetv_calendar_open), style = MaterialTheme.typography.labelLarge, color = tokens.colors.accent,
                    modifier = Modifier.padding(top = NuvioTokens.Space.s8).clickable { open(f) },
                )
            }
        }
    }
}

@Composable
private fun Modifier.nuvioSafeBottomInset(): Modifier = this.padding(bottom = nuvioSafeBottomPadding(0.dp))

@Composable
private fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textMuted, modifier = modifier.padding(vertical = NuvioTokens.Space.s24))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarRow(e: CalendarEntry, onClick: (CalendarEntry) -> Unit, onHold: (CalendarEntry) -> Unit) {
    val tokens = MaterialTheme.nuvio
    if (e.kind == CalendarKind.GAME) {
        GameRow(e, onClick)
        return
    }
    val allHits by CalendarGuide.hits.collectAsStateWithLifecycle()
    val hit = allHits[e.key]?.firstOrNull()
    val recBadge = stringResource(Res.string.livetv_calendar_rec_badge)
    val remindBadge = stringResource(Res.string.livetv_calendar_reminder_badge)
    val image = (if (e.spoiler) null else e.episodeImage) ?: e.background ?: e.poster
    val timeText = e.airMs?.let { formatClock(it) }
    val streamingLabel = stringResource(Res.string.livetv_calendar_streaming)
    val watchedLabel = stringResource(Res.string.livetv_calendar_watched)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(tokens.colors.surfaceCard)
            .combinedClickable(onClick = { onClick(e) }, onLongClick = { onHold(e) })
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
            if (hit != null) {
                val onTv = buildString {
                    append("▶ ").append(hit.channelName).append(" · ").append(formatClock(hit.startMs))
                    if (CalendarGuide.isRecording(hit)) append(" · ").append(recBadge)
                    if (CalendarGuide.isReminder(hit)) append(" · ").append(remindBadge)
                }
                Text(onTv, style = MaterialTheme.typography.labelSmall, color = tokens.colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val tags = e.tags.joinToString(" · ") { it.label }
            val bottom = listOf(whenLine, tags).filter { it.isNotBlank() }.joinToString("  ·  ")
            if (bottom.isNotBlank()) {
                Text(bottom, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GameRow(e: CalendarEntry, onClick: (CalendarEntry) -> Unit) {
    val ev = e.eventId?.let { SportsRepository.eventById(it) } ?: return
    val follows by SportsRepository.follows.collectAsStateWithLifecycle()
    val settings by SportsRepository.settings.collectAsStateWithLifecycle()
    val matches by SportsRepository.matches.collectAsStateWithLifecycle()
    GameCard(
        ev = ev, follows = follows, now = LiveTvClock.nowMs(), use24h = use24h(), hideScores = settings.hideScores,
        channels = matches[ev.id]?.size, onClick = { onClick(e) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheet(e: CalendarEntry, followed: Boolean, hits: List<CalendarGuideHit>, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    fun close(then: () -> Unit = {}) { scope.launch { dismissNuvioBottomSheet(sheetState) { onDismiss(); then() } } }
    val now = LiveTvClock.nowMs()
    val tokens = MaterialTheme.nuvio
    // Re-read the recording / reminder state after each change.
    val tick = notice
    val onNow = hits.firstOrNull { now in it.startMs until it.stopMs }
    val upcoming = hits.firstOrNull { it.stopMs > now }
    val isGame = e.kind == CalendarKind.GAME
    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            e.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
        )
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.accent, modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s2))
        }
        NuvioBottomSheetDivider()
        if (!isGame) {
            if (onNow != null) {
                NuvioBottomSheetActionRow(
                    title = stringResource(Res.string.livetv_calendar_watch_live) + " · " + onNow.channelName, icon = Icons.Filled.PlayArrow,
                    onClick = { close { LiveTvDeepLinks.handle(LiveTvDeepLinks.channelUrl(onNow.channelKey)) } },
                )
            }
            if (upcoming != null) {
                val recording = remember(tick, upcoming) { CalendarGuide.isRecording(upcoming) }
                NuvioBottomSheetActionRow(
                    title = stringResource(if (recording) Res.string.livetv_calendar_cancel_recording else Res.string.livetv_calendar_record) + " · " + upcoming.channelName,
                    icon = Icons.Filled.FiberManualRecord,
                    onClick = { notice = CalendarGuide.record(e, upcoming) },
                )
                if (upcoming.startMs > now) {
                    val reminded = remember(tick, upcoming) { CalendarGuide.isReminder(upcoming) }
                    NuvioBottomSheetActionRow(
                        title = stringResource(if (reminded) Res.string.livetv_calendar_cancel_reminder else Res.string.livetv_calendar_remind),
                        icon = Icons.Filled.Notifications,
                        onClick = { notice = CalendarGuide.remind(e, upcoming) },
                    )
                }
            }
            if (hits.size > 1) {
                NuvioBottomSheetActionRow(
                    title = stringResource(Res.string.livetv_calendar_choose_channel) + " (" + hits.size + ")",
                    icon = Icons.Filled.Info, onClick = { choosing = !choosing },
                )
                if (choosing) {
                    Text(
                        stringResource(Res.string.livetv_calendar_pick_channel), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted,
                        modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
                    )
                    hits.filter { it.stopMs > now }.forEach { h ->
                        val live = now in h.startMs until h.stopMs
                        NuvioBottomSheetActionRow(
                            title = h.channelName + " · " + formatClock(h.startMs),
                            icon = if (live) Icons.Filled.PlayArrow else Icons.Filled.FiberManualRecord,
                            selected = CalendarGuide.isRecording(h),
                            onClick = {
                                if (live) close { LiveTvDeepLinks.handle(LiveTvDeepLinks.channelUrl(h.channelKey)) }
                                else notice = CalendarGuide.record(e, h)
                            },
                        )
                    }
                }
            }
            val saved = remember(tick, hits) { CalendarRepository.savedChoice(e) }
            if (saved != null) {
                Text(saved.first, style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted,
                    modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4))
                NuvioBottomSheetActionRow(
                    title = "Forget the saved channel", icon = Icons.Filled.VisibilityOff,
                    onClick = { CalendarRepository.forgetSaved(saved.second); notice = "Saved channel forgotten" },
                )
            }
            if (choosing) {
                Text("Always use one of these for ${e.title}", style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted,
                    modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4))
                hits.forEach { h ->
                    NuvioBottomSheetActionRow(
                        title = "Always use " + h.channelName, icon = Icons.Filled.Notifications, selected = h.mine,
                        onClick = { CalendarRepository.setAlways(e, h); notice = "Always using ${h.channelName} for ${e.title}" },
                    )
                }
            }
            NuvioBottomSheetDivider()
        }
        NuvioBottomSheetActionRow(title = stringResource(Res.string.livetv_calendar_open), icon = Icons.Filled.Info, onClick = { close(onOpen) })
        if (!isGame) {
            NuvioBottomSheetActionRow(
                title = stringResource(if (followed) Res.string.livetv_calendar_unfollow else Res.string.livetv_calendar_follow),
                icon = Icons.Filled.Notifications,
                onClick = { CalendarRepository.toggleFollow(e); close() },
            )
            NuvioBottomSheetActionRow(
                title = stringResource(Res.string.livetv_calendar_hide), icon = Icons.Filled.VisibilityOff,
                onClick = { CalendarRepository.hide(e); close() },
            )
        }
    }
}
