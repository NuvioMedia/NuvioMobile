package com.nuvio.app.features.livetv.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.sports.GameState
import com.nuvio.app.features.livetv.sports.SportsCatalog
import com.nuvio.app.features.livetv.sports.SportsDates
import com.nuvio.app.features.livetv.sports.SportsRepository
import com.nuvio.app.features.livetv.sports.Standings
import com.nuvio.app.features.livetv.sports.StandingsKind
import com.nuvio.app.features.livetv.sports.StandingsRow
import com.nuvio.app.features.livetv.sports.StandingsView
import com.nuvio.app.features.livetv.sports.TeamInfo
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.livetv_sports_games
import nuvio.composeapp.generated.resources.livetv_sports_no_table
import nuvio.composeapp.generated.resources.livetv_sports_table
import org.jetbrains.compose.resources.stringResource

/** A league's page: its table (divisions, conferences or the whole league) and its games this week. */
@Composable
fun SportsLeagueScreen(
    leagueId: String,
    onBack: () -> Unit,
    actions: SportsActions,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val repo = SportsRepository
    val league = remember(leagueId) { SportsCatalog.league(leagueId) }
    LaunchedEffect(Unit) { repo.start(); repo.setViewing(true) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { repo.setViewing(false) } }
    val follows by repo.follows.collectAsStateWithLifecycle()
    val settings by repo.settings.collectAsStateWithLifecycle()
    val known by repo.known.collectAsStateWithLifecycle()
    val tables by repo.standings.collectAsStateWithLifecycle()
    val use24h = use24h()
    val now by produceState(LiveTvClock.nowMs()) { while (true) { value = LiveTvClock.nowMs(); delay(30_000) } }
    var loading by remember(leagueId) { mutableStateOf(true) }
    var mode by rememberSaveable { mutableStateOf("table") }
    var view by rememberSaveable { mutableStateOf(StandingsView.DIVISION.name) }
    var conference by rememberSaveable { mutableStateOf<String?>(null) }
    var teamSheet by remember { mutableStateOf<TeamInfo?>(null) }
    LaunchedEffect(leagueId) {
        loading = true
        if (league != null && repo.mayHaveTable(league)) runCatching { repo.standings(leagueId) }
        loading = false
    }
    val table = tables[leagueId]
    val games = remember(known, leagueId, now / 60_000L) {
        known.filter { it.leagueId == leagueId && it.startMs > now - 3 * 86_400_000L }.sortedBy { it.startMs }.take(80)
    }
    val followed = leagueId in follows.pins || leagueId in follows.leagues

    Column(modifier = modifier.fillMaxSize().background(tokens.colors.background).statusBarsPadding()) {
        NuvioScreenHeader(
            title = league?.name ?: leagueId,
            includeStatusBarPadding = false,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s8),
            actions = {
                Chip(label = if (followed) "Following" else "Follow", selected = followed, onClick = { repo.togglePin(leagueId) })
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s6),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            if (league != null && repo.mayHaveTable(league)) Chip(stringResource(Res.string.livetv_sports_table), selected = mode == "table", onClick = { mode = "table" })
            Chip(stringResource(Res.string.livetv_sports_games), selected = mode == "games", onClick = { mode = "games" })
            if (mode == "table" && table?.hasDivisions == true) {
                StandingsView.entries.forEach { v ->
                    Chip(v.name.lowercase().replaceFirstChar { it.uppercase() }, selected = view == v.name, onClick = { view = v.name })
                }
                if (view != StandingsView.LEAGUE.name) table.conferences.forEach { c ->
                    Chip(Standings.shortGroupName(c), selected = (conference ?: table.conferences.first()) == c, onClick = { conference = c })
                }
            }
        }
        if (mode == "games" || (league != null && !repo.mayHaveTable(league))) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = NuvioTokens.Space.s16, end = NuvioTokens.Space.s16, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            ) {
                if (games.isEmpty()) item { Text("No games known for the days ahead.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, modifier = Modifier.padding(vertical = NuvioTokens.Space.s16)) }
                items(games, key = { it.id }) { ev -> GameCard(ev, follows, now, use24h, settings.hideScores, channels = null) { actions.onOpenGame(ev) } }
            }
        } else {
            when {
                table == null && loading -> NuvioLoadingIndicator(modifier = Modifier.padding(NuvioTokens.Space.s24).align(Alignment.CenterHorizontally))
                table == null -> Text(stringResource(Res.string.livetv_sports_no_table), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, modifier = Modifier.padding(NuvioTokens.Space.s16))
                else -> TableView(table, StandingsView.entries.first { it.name == view }, conference, follows.teams.map { it.teamId }.toSet(), onTeam = { teamSheet = repo.teamFor(leagueId, it) })
            }
        }
    }
    teamSheet?.let { t -> TeamSheet(t, actions, onDismiss = { teamSheet = null }) }
}

@Composable
private fun TableView(table: Standings, view: StandingsView, conference: String?, mine: Set<String>, onTeam: (StandingsRow) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val groups = table.view(view, conference)
    val lines = table.playoffLines(view)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = NuvioTokens.Space.s12, end = NuvioTokens.Space.s12, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
    ) {
        item(key = "meta") {
            Text(
                listOfNotNull(table.season, table.source, "updated " + SportsDates.shortDayLabel(table.fetchedAt)).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, modifier = Modifier.padding(vertical = NuvioTokens.Space.s4),
            )
        }
        groups.forEach { g ->
            item(key = "g:" + g.name) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = NuvioTokens.Space.s10, bottom = NuvioTokens.Space.s4), verticalAlignment = Alignment.CenterVertically) {
                    Text(g.name, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    table.columns.forEach { c -> Text(c.label, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, modifier = Modifier.width(cellWidth(c.key)), textAlign = TextAlign.End) }
                }
            }
            g.rows.forEachIndexed { i, r ->
                item(key = "r:" + g.name + ":" + r.id) {
                    StandingsLine(table, r, r.id in mine, onClick = { onTeam(r) })
                    lines.firstOrNull { it.first == r.pos }?.let { (_, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = NuvioTokens.Space.s2)) {
                            Box(Modifier.weight(1f).height(1.dp).background(tokens.colors.accent))
                            Text(label, style = MaterialTheme.typography.labelSmall, color = tokens.colors.accent, modifier = Modifier.padding(horizontal = NuvioTokens.Space.s6))
                        }
                    }
                }
            }
        }
        val zones = groups.flatMap { it.rows }.mapNotNull { it.zone }.distinctBy { it.label }
        if (zones.isNotEmpty()) {
            item(key = "zones") {
                Column(modifier = Modifier.padding(top = NuvioTokens.Space.s12), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
                    zones.forEach { z ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Color(z.color)))
                            Text(z.label, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted)
                        }
                    }
                }
            }
        }
    }
}

private fun cellWidth(key: String) = when (key) {
    "streak", "gamesBehind", "winPercent", "behind" -> 46.dp
    "pointDifferential" -> 40.dp
    else -> 32.dp
}

@Composable
private fun StandingsLine(table: Standings, r: StandingsRow, mine: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().clip(tokens.shapes.compactCard).background(if (mine) tokens.colors.overlaySelected else tokens.colors.surface).clickable(onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s6),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
    ) {
        Box(Modifier.width(3.dp).height(24.dp).clip(RoundedCornerShape(2.dp)).background(r.zone?.let { Color(it.color) } ?: Color.Transparent))
        Text(r.pos.toString(), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted, modifier = Modifier.width(22.dp), textAlign = TextAlign.End)
        r.change?.let { Text(if (it > 0) "▲" else "▼", style = MaterialTheme.typography.labelSmall, color = if (it > 0) Color(0xFF2E9E5B) else Color(0xFFC0392B)) }
        if (!r.athlete) TeamLogo(TeamInfo(table.leagueId, r.id, r.shortName, r.name, r.abbr, null, r.color, r.altColor, r.logo), 22.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                (r.clincher?.let { "$it - " } ?: "") + r.shortName,
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, fontWeight = if (mine) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            r.form?.takeIf { table.kind != StandingsKind.RACING }?.let { f ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { f.forEach { FormSquare(it, 10.dp) } }
            }
        }
        table.columns.forEach { c ->
            Text(r.values[c.key] ?: "", style = MaterialTheme.typography.bodySmall, color = if (c.key == "points" || c.key == "winPercent") tokens.colors.textPrimary else tokens.colors.textSecondary, fontWeight = if (c.key == "points") FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(cellWidth(c.key)), textAlign = TextAlign.End, maxLines = 1)
        }
    }
}
