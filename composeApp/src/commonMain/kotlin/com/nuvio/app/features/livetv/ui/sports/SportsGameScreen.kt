package com.nuvio.app.features.livetv.ui.sports

import com.nuvio.app.features.livetv.ui.liveTvSideInsets
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.sports.ChannelMatch
import com.nuvio.app.features.livetv.sports.ChannelRegions
import com.nuvio.app.features.livetv.sports.GameExtras
import com.nuvio.app.features.livetv.sports.GameState
import com.nuvio.app.features.livetv.sports.LeagueKind
import com.nuvio.app.features.livetv.sports.ScoringPlay
import com.nuvio.app.features.livetv.sports.Side
import com.nuvio.app.features.livetv.sports.Sport
import com.nuvio.app.features.livetv.sports.SportsCatalog
import com.nuvio.app.features.livetv.sports.SportsEvent
import com.nuvio.app.features.livetv.sports.SportsMatcher
import com.nuvio.app.features.livetv.sports.SportsRecordings
import com.nuvio.app.features.livetv.sports.SportsRepository
import com.nuvio.app.features.livetv.sports.Standings
import com.nuvio.app.features.livetv.sports.TeamInfo
import com.nuvio.app.features.livetv.ui.ChannelLogo
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.livetv_sports_fix_channel
import nuvio.composeapp.generated.resources.livetv_sports_game_gone
import nuvio.composeapp.generated.resources.livetv_sports_latest_scoring
import nuvio.composeapp.generated.resources.livetv_sports_maybe
import nuvio.composeapp.generated.resources.livetv_sports_no_channels
import nuvio.composeapp.generated.resources.livetv_sports_not_this_channel
import nuvio.composeapp.generated.resources.livetv_sports_pick_channel
import nuvio.composeapp.generated.resources.livetv_sports_record
import nuvio.composeapp.generated.resources.livetv_sports_remind
import nuvio.composeapp.generated.resources.livetv_sports_reminded
import nuvio.composeapp.generated.resources.livetv_sports_showing_it
import nuvio.composeapp.generated.resources.livetv_sports_watch_on
import nuvio.composeapp.generated.resources.livetv_sports_your_pick
import nuvio.composeapp.generated.resources.livetv_watch_from_start
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * A game's page: who's playing and the score, the channels showing it (tap to play, long-press
 * to fix), record and remind, then the latest scoring and what else the feed has (stats,
 * leaders, form, the table, the venue, chances).
 */
@Composable
fun SportsGameScreen(
    eventId: String,
    onBack: () -> Unit,
    actions: SportsActions,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val repo = SportsRepository
    LaunchedEffect(Unit) { repo.start(); repo.setViewing(true) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { repo.setViewing(false) } }
    val known by repo.known.collectAsStateWithLifecycle()
    val events by repo.events.collectAsStateWithLifecycle()
    val ev = remember(events, known, eventId) { events.firstOrNull { it.id == eventId } ?: known.firstOrNull { it.id == eventId } }
    Column(modifier = modifier.fillMaxSize().background(tokens.colors.background).statusBarsPadding().liveTvSideInsets()) {
        NuvioScreenHeader(
            title = ev?.let { SportsCatalog.league(it.leagueId)?.name } ?: "Sports",
            includeStatusBarPadding = false,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s8),
        )
        if (ev == null) {
            EmptyNote(stringResource(Res.string.livetv_sports_game_gone))
        } else {
            GameBody(ev, actions)
        }
    }
    GameAlertsOffer()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameBody(ev: SportsEvent, actions: SportsActions) {
    val tokens = MaterialTheme.nuvio
    val repo = SportsRepository
    val settings by repo.settings.collectAsStateWithLifecycle()
    val follows by repo.follows.collectAsStateWithLifecycle()
    val allMatches by repo.matches.collectAsStateWithLifecycle()
    val matching by repo.matching.collectAsStateWithLifecycle()
    val schedules by SportsRecordings.scheduled.collectAsStateWithLifecycle()
    val hasChannels = LiveTvRepository.channels.collectAsStateWithLifecycle().value.isNotEmpty()
    val use24h = use24h()
    val now by produceState(LiveTvClock.nowMs()) { while (true) { value = LiveTvClock.nowMs(); delay(15_000) } }
    val haptics = LocalHapticFeedback.current
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    val hide = settings.hideScores
    val showScore = ev.hasScore && !hide
    val matches = allMatches[ev.id].orEmpty()
    val reminded = follows.reminders.any { it.eventId == ev.id }
    val recording = remember(schedules, ev.id) { SportsRecordings.forEvent(ev, schedules) }
    var askRecord by remember(ev.id) { mutableStateOf(false) }
    var fixing by remember(ev.id) { mutableStateOf(false) }
    var regionPick by remember { mutableStateOf(false) }
    var channelMenu by remember { mutableStateOf<ChannelMatch?>(null) }
    var teamSheet by remember { mutableStateOf<TeamInfo?>(null) }
    val plays = rememberScoringPlays(ev, hide)
    val extras = rememberGameExtras(ev)
    val tables by repo.standings.collectAsStateWithLifecycle()
    LaunchedEffect(ev.leagueId, ev.state) {
        if (ev.state == GameState.PRE && !hide && league != null && repo.mayHaveTable(league)) runCatching { repo.standings(ev.leagueId) }
    }
    val grouped = remember(matches) { SportsMatcher.groupCopies(matches) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = NuvioTokens.Space.s16, end = NuvioTokens.Space.s16, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s24)),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
    ) {
        // ---- the score strip
        item(key = "head") {
            Column(
                modifier = Modifier.fillMaxWidth().clip(tokens.shapes.card).background(tokens.colors.surface).padding(NuvioTokens.Space.s12),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                    LeagueMark(league, 20.dp)
                    Text(
                        listOfNotNull(league?.name, ev.subtitle).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    StateBadge(ev, use24h)
                }
                if (sides.size == 2) {
                    val table = tables[ev.leagueId]
                    sides.forEach { s ->
                        TeamBand(
                            side = s, showScore = showScore, winner = ev.state == GameState.POST && s.winner,
                            followed = follows.followsTeam(ev.leagueId, s.team.id),
                            pre = if (ev.state == GameState.PRE && !hide) preGameLine(s, table, extras) else null,
                            onClick = { teamSheet = s.team },
                        )
                    }
                } else {
                    Text(eventHeadline(ev, league), style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold)
                    ev.about?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                }
                if (ev.state == GameState.LIVE) NuvioProgressBar(progress = progressOf(ev, now), height = NuvioTokens.Space.s3)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                    Text(
                        text = when (ev.state) {
                            GameState.LIVE -> cornerStatus(ev, use24h, hide)
                            GameState.PRE -> if (ev.hasTime) dayWord(ev.startMs, now) + " " + sportsClock(ev.startMs) + " · " + countdownLabel(ev.startMs, now) else (ev.note ?: startLabel(ev, use24h))
                            GameState.POST -> (ev.status ?: "Final") + " · " + dayWord(ev.startMs, now)
                            GameState.OFF -> ev.status ?: "Postponed"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ev.state == GameState.LIVE) SportsLiveRed else tokens.colors.textSecondary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (ev.broadcasts.isNotEmpty()) Text(ev.broadcasts.take(3).joinToString(", "), style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1)
                }
                ev.situation?.takeIf { !hide }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary) }
            }
        }
        // ---- actions
        item(key = "actions") {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
                if (ev.state != GameState.POST && ev.hasTime) {
                    Chip(label = stringResource(if (reminded) Res.string.livetv_sports_reminded else Res.string.livetv_sports_remind), selected = reminded, onClick = { repo.toggleReminder(ev) })
                }
                if (ev.state != GameState.POST && ev.hasTime && hasChannels) {
                    Chip(
                        label = if (recording != null) "● Recording set" else stringResource(Res.string.livetv_sports_record),
                        selected = recording != null,
                        onClick = { if (recording != null) SportsRecordings.cancel(ev) else askRecord = true },
                    )
                }
                if (hasChannels) Chip(label = stringResource(Res.string.livetv_sports_fix_channel), selected = false, onClick = { fixing = true })
                if (league != null && repo.mayHaveTable(league)) Chip(label = "Table", selected = false, onClick = { actions.onOpenLeague(league) })
                if (matches.size > 1) {
                    Chip(label = "🌐 " + ChannelRegions.shortName(settings.channelRegion), selected = settings.channelRegion.isNotBlank(), onClick = { regionPick = true })
                }
            }
        }
        // ---- channels
        item(key = "channels-h") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.livetv_sports_watch_on), style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (matching) NuvioLoadingIndicator(size = NuvioTokens.Space.s16)
            }
        }
        if (grouped.isEmpty()) {
            item(key = "channels-none") {
                Text(
                    if (!hasChannels) "Add a playlist in Live TV settings to watch games." else if (matching) "Looking through your channels…" else stringResource(Res.string.livetv_sports_no_channels),
                    style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted,
                )
            }
        }
        items(grouped, key = { "c:" + it.first().channel.key }) { copies ->
            val m = copies.first()
            SportsChannelRow(
                m = m, copies = copies.size, ev = ev, use24h = use24h,
                onPlay = { c -> actions.onPlay(c.channel, ev.title) },
                onLongPress = { c -> haptics.performHapticFeedback(HapticFeedbackType.LongPress); channelMenu = c },
            )
        }
        // ---- latest scoring
        if (plays.isNotEmpty()) {
            item(key = "plays-h") { Text(stringResource(Res.string.livetv_sports_latest_scoring), style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = NuvioTokens.Space.s8)) }
            items(plays.asReversed().take(12)) { p -> PlayRow(p) }
        }
        if (sides.size == 2 && showScore && sides.any { it.periods.isNotEmpty() }) {
            item(key = "periods") { PeriodTable(sides, sport) }
        }
        if (ev.standings.isNotEmpty() && !hide) {
            item(key = "standings-h") {
                Text(
                    when (sport) { Sport.TENNIS -> "Matches"; Sport.FIGHTING -> "Fight card"; Sport.GOLF -> "Leaderboard"; else -> "Results" },
                    style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = NuvioTokens.Space.s8),
                )
            }
            items(ev.standings) { st ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s2), verticalAlignment = Alignment.CenterVertically) {
                    Text(st.pos, style = MaterialTheme.typography.labelMedium, color = if (st.pos == "LIVE") SportsLiveRed else tokens.colors.textMuted, modifier = Modifier.width(44.dp))
                    Text(st.name, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    st.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary) }
                }
            }
        }
        // ---- more about the game
        extras?.let { x -> gameExtrasItems(ev, x, sides, hide) }
    }

    if (askRecord) {
        RecordDialog(ev, matches, use24h, onRecord = { m, minutes -> askRecord = false; NuvioToastController.show(SportsRecordings.record(ev, m, minutes)) }, onDismiss = { askRecord = false })
    }
    if (fixing) FixChannelDialog(ev, matches, onDismiss = { fixing = false })
    if (regionPick) {
        RegionDialog(matches.map { SportsMatcher.channelRegionCode(it.channel) }, settings.channelRegion, onPick = { repo.updateSettings { s -> s.copy(channelRegion = it) }; regionPick = false }, onDismiss = { regionPick = false })
    }
    channelMenu?.let { m -> ChannelMenuSheet(ev, m, actions, onDismiss = { channelMenu = null }) }
    teamSheet?.let { t -> TeamSheet(t, actions, onDismiss = { teamSheet = null }) }
}

@Composable
private fun TeamBand(side: Side, showScore: Boolean, winner: Boolean, followed: Boolean, pre: String?, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val c = bandColor(bandBase(side.team))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .clickable(onClick = onClick)
            .background(
                Brush.horizontalGradient(
                    0f to c.copy(alpha = 0.92f),
                    0.7f to c.copy(alpha = 0.28f),
                    1f to c.copy(alpha = 0.05f),
                ),
            )
            .padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
    ) {
        TeamLogo(side.team, 36.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(side.team.name + (if (followed) " ★" else ""), style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = if (winner) FontWeight.Black else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(side.record, pre).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showScore) Text(side.score ?: "–", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Black)
    }
}

/** Before a game: "3rd · WWDLW · 62% win" for one side, from the table and the game's extras. */
private fun preGameLine(s: Side, table: Standings?, x: GameExtras?): String? {
    val row = table?.find(s.team.id)?.second
    val form = x?.form?.firstOrNull { it.teamId == s.team.id || it.team.equals(s.team.name, true) }?.results?.joinToString("") { it.take(1) } ?: row?.form
    val win = if (s.home) x?.homeWin else x?.awayWin
    return listOfNotNull(row?.pos?.let { Standings.ordinal(it) }, form?.takeLast(5)?.uppercase()?.takeIf { it.isNotEmpty() }, win?.let { "${it.roundToInt()}% win" })
        .joinToString(" · ").ifBlank { null }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SportsChannelRow(m: ChannelMatch, copies: Int, ev: SportsEvent, use24h: Boolean, onPlay: (ChannelMatch) -> Unit, onLongPress: (ChannelMatch) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().clip(tokens.shapes.card).background(tokens.colors.surface)
            .combinedClickable(onClick = { onPlay(m) }, onLongClick = { onLongPress(m) })
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        ChannelLogo(m.channel, size = 40.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                Text(m.channel.name, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                m.quality?.let { SportsBadge(it, tokens.colors.surfaceElevated, tokens.colors.textSecondary) }
                if (copies > 1) Text("+${copies - 1}", style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted)
            }
            Text(
                text = listOfNotNull(m.channel.sourceName.takeIf { it.isNotBlank() }, m.program?.let { p -> "${sportsClock(p.startMs)} ${p.title}" } ?: m.how).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            m.score >= 150 -> SportsBadge(stringResource(Res.string.livetv_sports_your_pick), tokens.colors.accent, tokens.colors.onAccent)
            m.sure -> SportsBadge(stringResource(Res.string.livetv_sports_showing_it), Color(0xFF2E9E5B))
            else -> SportsBadge(stringResource(Res.string.livetv_sports_maybe), tokens.colors.surfaceElevated, tokens.colors.textSecondary)
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = tokens.colors.textPrimary)
    }
}

@Composable
private fun PlayRow(p: ScoringPlay) {
    val tokens = MaterialTheme.nuvio
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s2), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
        Text(p.clock, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, modifier = Modifier.width(64.dp))
        p.teamAbbr?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textSecondary, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp)) }
        Text(p.text, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (p.awayScore != null && p.homeScore != null) Text("${p.awayScore}–${p.homeScore}", style = MaterialTheme.typography.labelMedium, color = tokens.colors.textSecondary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PeriodTable(sides: List<Side>, sport: Sport) {
    val tokens = MaterialTheme.nuvio
    val n = sides.maxOf { it.periods.size }
    val heading = when (sport) { Sport.BASEBALL -> "Innings"; Sport.HOCKEY -> "Periods"; Sport.FOOTBALL, Sport.RUGBY -> "Halves"; else -> "Quarters" }
    Column(modifier = Modifier.fillMaxWidth().clip(tokens.shapes.card).background(tokens.colors.surface).padding(NuvioTokens.Space.s10), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
        Row {
            Text(heading, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, modifier = Modifier.weight(1f))
            (1..n).forEach { Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center) }
            Text("T", style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, modifier = Modifier.width(32.dp), textAlign = TextAlign.Center)
        }
        sides.forEach { s ->
            Row {
                Text(s.team.abbr.ifBlank { s.team.name }, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                (0 until n).forEach { i -> Text(s.periods.getOrNull(i) ?: "-", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center) }
                Text(s.score ?: "-", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Black, modifier = Modifier.width(32.dp), textAlign = TextAlign.Center)
            }
        }
    }
}

/** A game's scoring plays, kept up to date while it's on. */
@Composable
private fun rememberScoringPlays(ev: SportsEvent, hideScores: Boolean): List<ScoringPlay> {
    var plays by remember(ev.id) { mutableStateOf<List<ScoringPlay>>(emptyList()) }
    val latest = rememberUpdatedState(ev)
    LaunchedEffect(ev.id, ev.state, ev.away?.score, ev.home?.score, hideScores) {
        if (hideScores) return@LaunchedEffect
        while (true) {
            val e = latest.value
            if (e.state != GameState.PRE) plays = runCatching { SportsRepository.scoringPlays(e) }.getOrDefault(emptyList())
            if (e.state != GameState.LIVE) break
            delay(30_000)
        }
    }
    return plays
}

/** The game's extras, asked again every minute while it's on (once otherwise). */
@Composable
private fun rememberGameExtras(ev: SportsEvent): GameExtras? {
    var extras by remember(ev.id) { mutableStateOf<GameExtras?>(null) }
    val latest = rememberUpdatedState(ev)
    LaunchedEffect(ev.id, ev.state) {
        while (true) {
            runCatching { SportsRepository.gameExtras(latest.value) }.getOrNull()?.let { extras = it }
            if (latest.value.state != GameState.LIVE) break
            delay(60_000)
        }
    }
    return extras
}

// ------------------------------------------------------------------ dialogs and sheets

@Composable
private fun RecordDialog(ev: SportsEvent, matches: List<ChannelMatch>, use24h: Boolean, onRecord: (ChannelMatch, Int) -> Unit, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val channels = remember(matches) { matches.take(6) }
    var pick by remember(ev.id) { mutableIntStateOf(0) }
    var minutes by remember(ev.id) { mutableIntStateOf(SportsRecordings.defaultLength(ev)) }
    DialogSurface(onDismissRequest = onDismiss, title = stringResource(Res.string.livetv_sports_record), message = ev.title) {
        if (channels.isEmpty()) Text("No channel found for this game yet. Use Fix channel to pick one.", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
            channels.forEachIndexed { i, m ->
                DialogOption(text = m.channel.name, description = listOfNotNull(m.channel.sourceName.takeIf { it.isNotBlank() }, if (m.sure) "Showing it" else "Maybe").joinToString(" · "), selected = i == pick, onClick = { pick = i })
            }
        }
        Text("Record for", style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
            SportsRecordings.LENGTHS.forEach { len -> Chip(label = SportsRecordings.label(len), selected = len == minutes, onClick = { minutes = len }) }
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(text = stringResource(Res.string.livetv_sports_record), style = DialogButtonStyle.Primary, enabled = channels.isNotEmpty(), onClick = { channels.getOrNull(pick)?.let { onRecord(it, minutes) } })
        }
    }
}

@Composable
private fun FixChannelDialog(ev: SportsEvent, matches: List<ChannelMatch>, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val repo = SportsRepository
    var picking by remember { mutableStateOf(matches.isEmpty()) }
    var chosen by remember { mutableStateOf<LiveChannel?>(null) }
    val pick = chosen
    if (pick != null) {
        val networks = ev.broadcasts
        val league = SportsCatalog.league(ev.leagueId)
        val teams = ev.ordered(league?.sport ?: Sport.OTHER).map { it.team }
        if (networks.isEmpty() && teams.isEmpty() && league?.kind != LeagueKind.EVENT) {
            LaunchedEffect(pick.key) {
                repo.pickChannel(ev, pick.key, forNetworks = false)
                NuvioToastController.show("Saved: ${ev.title} on ${pick.name}")
                onDismiss()
            }
            return
        }
        DialogSurface(onDismissRequest = onDismiss, title = "Use ${pick.name} for more games?", message = "Pick a network or a team, and their games from now on use ${pick.name} too.") {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
                networks.firstOrNull()?.let { n ->
                    DialogOption(text = "Yes, every $n game", selected = false, onClick = { repo.pickChannel(ev, pick.key, forNetworks = true); NuvioToastController.show("Saved: $n games use ${pick.name}"); onDismiss() })
                }
                if (league?.kind == LeagueKind.EVENT) {
                    DialogOption(text = "Yes, every ${league.name} event", selected = false, onClick = { repo.pickChannel(ev, pick.key, forNetworks = false, forLeague = true); NuvioToastController.show("Saved: ${league.name} events use ${pick.name}"); onDismiss() })
                }
                teams.forEach { t ->
                    DialogOption(text = "Yes, every ${t.name} game", selected = false, onClick = { repo.pickChannel(ev, pick.key, forNetworks = false, forTeam = t); NuvioToastController.show("Saved: ${t.name} games use ${pick.name}"); onDismiss() })
                }
                DialogOption(text = "Just this game", selected = false, onClick = { repo.pickChannel(ev, pick.key, forNetworks = false); NuvioToastController.show("Saved: ${ev.title} on ${pick.name}"); onDismiss() })
            }
            DialogButtons { DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss) }
        }
        return
    }
    if (picking) {
        ChannelPickerDialog(title = "Pick the channel for ${ev.title}", onPick = { chosen = it }, onDismiss = onDismiss)
        return
    }
    DialogSurface(onDismissRequest = onDismiss, title = stringResource(Res.string.livetv_sports_fix_channel), message = "Take wrong channels off the list, or pick the right one.") {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
            items(matches, key = { it.channel.key }) { m ->
                DialogOption(text = m.channel.name, description = stringResource(Res.string.livetv_sports_not_this_channel), selected = false, onClick = {
                    repo.blockChannel(ev, m.channel.key)
                    NuvioToastController.show("${m.channel.name} won't be used for this game")
                })
            }
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(text = stringResource(Res.string.livetv_sports_pick_channel), style = DialogButtonStyle.Primary, onClick = { picking = true })
        }
    }
}

/** Any channel, by name. */
@Composable
internal fun ChannelPickerDialog(title: String, onPick: (LiveChannel) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember { SportsRepository.channelsForPicker() }
    val shown = remember(query, all) {
        val q = query.trim()
        if (q.isEmpty()) all.take(60) else all.filter { it.name.contains(q, true) }.take(120)
    }
    DialogSurface(onDismissRequest = onDismiss, title = title) {
        NuvioInputField(value = query, onValueChange = { query = it }, placeholder = "Channel name")
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
            items(shown, key = { it.key }) { ch ->
                DialogOption(text = ch.name, description = ch.group + " · " + ch.sourceName, selected = false, onClick = { onPick(ch) })
            }
        }
        DialogButtons { DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss) }
    }
}

@Composable
private fun RegionDialog(codes: Collection<String?>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val choices = remember(codes, current) { ChannelRegions.choicesFor(codes, current) }
    fun count(n: Int) = if (n == 1) "1 channel" else "$n channels"
    DialogSurface(onDismissRequest = onDismiss, title = "Channels from", message = "Shows that region's channels first for every game (handy for commentary in your language).") {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
            DialogOption(text = "All regions", description = count(codes.size), selected = current.isBlank(), onClick = { onPick("") })
            choices.forEach { (code, n) ->
                DialogOption(text = ChannelRegions.name(code), description = if (n == 0) "None for this game" else count(n), selected = code == current, onClick = { onPick(code) })
            }
        }
        DialogButtons { DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss) }
    }
}

/** Long-press on a channel: play, from the start, not this channel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelMenuSheet(ev: SportsEvent, m: ChannelMatch, actions: SportsActions, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
            ChannelLogo(m.channel, size = 40.dp)
            Column {
                Text(m.channel.name, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Text(m.how, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            }
        }
        NuvioBottomSheetActionRow(title = "Play", icon = Icons.Filled.PlayArrow, onClick = { onDismiss(); actions.onPlay(m.channel, ev.title) })
        if (m.catchup) {
            NuvioBottomSheetActionRow(title = stringResource(Res.string.livetv_watch_from_start), icon = Icons.Filled.Replay, onClick = {
                onDismiss()
                val league = SportsCatalog.league(ev.leagueId)
                val program = m.program?.takeIf { it.startMs <= ev.startMs + 30 * 60_000L }
                    ?: EpgProgram(startMs = ev.startMs, stopMs = ev.endMs ?: (ev.startMs + (league?.minutes ?: 180) * 60_000L), title = ev.title)
                actions.onPlayCatchup(m.channel, program)
            })
        }
        NuvioBottomSheetActionRow(title = "This is the right channel", icon = Icons.Filled.NotificationsActive, onClick = {
            onDismiss(); SportsRepository.pickChannel(ev, m.channel.key, forNetworks = false); NuvioToastController.show("Saved: ${ev.title} on ${m.channel.name}")
        })
        NuvioBottomSheetActionRow(title = stringResource(Res.string.livetv_sports_not_this_channel), icon = Icons.Filled.FiberManualRecord, onClick = {
            onDismiss(); SportsRepository.blockChannel(ev, m.channel.key); NuvioToastController.show("${m.channel.name} won't be used for this game")
        })
        Spacer(Modifier.height(NuvioTokens.Space.s12))
    }
}

/** A team's card: follow, and its games (results and what's next). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamSheet(team: TeamInfo, actions: SportsActions, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val repo = SportsRepository
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val follows by repo.follows.collectAsStateWithLifecycle()
    val settings by repo.settings.collectAsStateWithLifecycle()
    val use24h = use24h()
    val now = LiveTvClock.nowMs()
    var games by remember(team.key) { mutableStateOf<List<SportsEvent>?>(null) }
    LaunchedEffect(team.key) { games = runCatching { repo.teamGames(team) }.getOrDefault(emptyList()) }
    val followed = follows.followedTeam(team) != null
    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, fullHeight = true) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8)
                .clip(tokens.shapes.compactCard)
                .background(teamBandBrush(team))
                .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s10),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            TeamLogo(team, 44.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(team.fullName, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(SportsCatalog.league(team.leagueId)?.name, team.record).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            }
            Chip(label = if (followed) "Following" else "Follow", selected = followed, onClick = { repo.toggleTeam(team) })
        }
        val list = games
        if (list == null) {
            NuvioLoadingIndicator(modifier = Modifier.padding(NuvioTokens.Space.s24).align(Alignment.CenterHorizontally))
        } else {
            val startIndex = list.indexOfFirst { it.state != GameState.POST }.let { if (it < 0) (list.size - 3).coerceAtLeast(0) else (it - 1).coerceAtLeast(0) }
            LazyColumn(
                state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = startIndex),
                modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
                contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
            ) {
                if (list.isEmpty()) item { Text("No games known yet.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted) }
                items(list, key = { it.id }) { ev ->
                    GameCard(ev, follows, now, use24h, settings.hideScores, channels = null) { onDismiss(); actions.onOpenGame(ev) }
                }
            }
        }
    }
}
