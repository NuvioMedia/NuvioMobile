package com.nuvio.app.features.livetv.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.sports.GameExtras
import com.nuvio.app.features.livetv.sports.GameState
import com.nuvio.app.features.livetv.sports.Side
import com.nuvio.app.features.livetv.sports.SportsDates
import com.nuvio.app.features.livetv.sports.SportsEvent
import kotlin.math.roundToInt

/*
 * The bottom of a game's page: form, earlier meetings, match info and the table before it
 * starts; team stats, top performers (or line-ups) and win probability once it's on. A panel only
 * shows when the feed has something for it.
 */

internal fun LazyListScope.gameExtrasItems(ev: SportsEvent, x: GameExtras, sides: List<Side>, hideScores: Boolean) {
    val pre = ev.state == GameState.PRE
    val away = sides.firstOrNull { !it.home }
    val home = sides.firstOrNull { it.home }
    if (!pre && x.stats.isNotEmpty() && !hideScores) item(key = "x:stats") { Panel("Team stats") { StatsPanel(x, away, home) } }
    if (!pre && x.performers.isNotEmpty() && !hideScores) item(key = "x:lead") { Panel("Top performers") { LeadersPanel(x, away, home) } }
    if (x.lineups.isNotEmpty()) item(key = "x:lineups") { Panel("Line-ups") { LineupsPanel(x) } }
    if (x.form.isNotEmpty()) item(key = "x:form") { Panel("Form") { FormPanel(x) } }
    if (x.meetings.isNotEmpty()) item(key = "x:meet") { Panel("Earlier meetings") { MeetingsPanel(x) } }
    if (x.hasOdds && !hideScores) item(key = "x:odds") { Panel(if (pre) "Chances" else "Win probability") { ChancePanel(x, away, home) } }
    if (x.table.isNotEmpty()) item(key = "x:table") { Panel(x.tableTitle ?: "Table") { TablePanel(x) } }
    if (x.hasMatchInfo) item(key = "x:info") { Panel("Match info") { InfoPanel(x) } }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxWidth().clip(tokens.shapes.card).background(tokens.colors.surface).padding(NuvioTokens.Space.s12),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, fontWeight = FontWeight.Black)
        content()
    }
}

@Composable
private fun StatsPanel(x: GameExtras, away: Side?, home: Side?) {
    val tokens = MaterialTheme.nuvio
    val ac = teamColor(away?.team)
    val hc = teamColor(home?.team)
    x.stats.forEach { st ->
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
            Row {
                Text(st.away, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                Text(st.label, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(st.home, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
            }
            val a = st.awayValue ?: 0.0
            val h = st.homeValue ?: 0.0
            val total = (a + h).takeIf { it > 0 } ?: 1.0
            Row(modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))) {
                Box(Modifier.weight((a / total).toFloat().coerceAtLeast(0.02f)).fillMaxHeight().background(ac))
                Box(Modifier.weight((h / total).toFloat().coerceAtLeast(0.02f)).fillMaxHeight().background(hc))
            }
        }
    }
}

@Composable
private fun LeadersPanel(x: GameExtras, away: Side?, home: Side?) {
    val tokens = MaterialTheme.nuvio
    x.performers.forEach { p ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
            val c = when (p.teamId) { away?.team?.id -> teamColor(away?.team); home?.team?.id -> teamColor(home?.team); else -> tokens.colors.textMuted }
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
            Text(p.category, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, modifier = Modifier.width(72.dp), maxLines = 1)
            Column(modifier = Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.line, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LineupsPanel(x: GameExtras) {
    val tokens = MaterialTheme.nuvio
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
        x.lineups.forEach { l ->
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
                Text(l.team + (l.formation?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                l.players.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
internal fun FormSquare(r: Char, size: androidx.compose.ui.unit.Dp = 14.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(3.dp)).background(
            when (r) { 'W' -> Color(0xFF2E9E5B); 'L' -> Color(0xFFC0392B); else -> Color(0xFF5A5F6E) }
        ),
        contentAlignment = Alignment.Center,
    ) {
        // Letter sized to the square, so small squares show a whole, readable W / L / D.
        val letter = (size.value * 0.68f).sp
        Text(
            r.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = letter, lineHeight = letter, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun FormPanel(x: GameExtras) {
    val tokens = MaterialTheme.nuvio
    x.form.forEach { f ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
            Text(f.team, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { f.results.forEach { FormSquare(it.firstOrNull() ?: '-') } }
        }
    }
}

@Composable
private fun MeetingsPanel(x: GameExtras) {
    val tokens = MaterialTheme.nuvio
    x.meetings.forEach { m ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
            Text(m.dateMs?.let { SportsDates.longDateLabel(it) } ?: "", style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, modifier = Modifier.width(88.dp))
            Text(m.text, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChancePanel(x: GameExtras, away: Side?, home: Side?) {
    val tokens = MaterialTheme.nuvio
    val a = x.awayWin ?: 0.0
    val h = x.homeWin ?: 0.0
    val d = x.draw ?: 0.0
    val total = (a + h + d).takeIf { it > 0 } ?: 100.0
    Row {
        Text("${away?.team?.abbr ?: "Away"} ${a.roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (d > 0.5) Text("Draw ${d.roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Text("${home?.team?.abbr ?: "Home"} ${h.roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
    Row(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
        Box(Modifier.weight((a / total).toFloat().coerceAtLeast(0.02f)).fillMaxHeight().background(teamColor(away?.team)))
        if (d > 0.5) Box(Modifier.weight((d / total).toFloat().coerceAtLeast(0.02f)).fillMaxHeight().background(tokens.colors.neutral))
        Box(Modifier.weight((h / total).toFloat().coerceAtLeast(0.02f)).fillMaxHeight().background(teamColor(home?.team)))
    }
    if (x.winHistory.size > 2) {
        // The home team's chance through the game, as thin bars oldest to newest.
        Row(modifier = Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            x.winHistory.forEach { p ->
                Box(Modifier.weight(1f).fillMaxHeight(p.coerceIn(0.05f, 1f)).background(teamColor(home?.team).copy(alpha = 0.7f)))
            }
        }
    }
}

@Composable
private fun TablePanel(x: GameExtras) {
    val tokens = MaterialTheme.nuvio
    Row {
        Text("", modifier = Modifier.width(28.dp))
        Text("", modifier = Modifier.weight(1f))
        x.tableHead.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, modifier = Modifier.width(44.dp), textAlign = TextAlign.End) }
    }
    x.table.forEach { r ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.pos, style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted, modifier = Modifier.width(28.dp))
            Text(r.team, style = MaterialTheme.typography.bodyMedium, color = if (r.mine) tokens.colors.accent else tokens.colors.textPrimary, fontWeight = if (r.mine) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            listOf(r.a, r.b, r.c).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, modifier = Modifier.width(44.dp), textAlign = TextAlign.End) }
        }
    }
}

@Composable
private fun InfoPanel(x: GameExtras) {
    val tokens = MaterialTheme.nuvio
    listOfNotNull(
        x.venue?.let { "Venue" to listOfNotNull(it, x.city).joinToString(", ") },
        x.tempF?.let { "Weather" to "$it°F" + (x.conditions?.let { c -> " · $c" } ?: "") },
        x.referee?.let { "Referee" to it },
        x.attendance?.let { "Attendance" to it.toString() },
    ).forEach { (k, v) ->
        Row {
            Text(k, style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted, modifier = Modifier.width(96.dp))
            Text(v, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
