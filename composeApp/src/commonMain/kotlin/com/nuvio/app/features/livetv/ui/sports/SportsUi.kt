package com.nuvio.app.features.livetv.ui.sports

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.sports.EventSource
import com.nuvio.app.features.livetv.sports.GameState
import com.nuvio.app.features.livetv.sports.League
import com.nuvio.app.features.livetv.sports.LeagueKind
import com.nuvio.app.features.livetv.sports.Side
import com.nuvio.app.features.livetv.sports.Sport
import com.nuvio.app.features.livetv.sports.SportsCatalog
import com.nuvio.app.features.livetv.sports.SportsDates
import com.nuvio.app.features.livetv.sports.SportsEvent
import com.nuvio.app.features.livetv.sports.SportsFeeds
import com.nuvio.app.features.livetv.sports.SportsFollows
import com.nuvio.app.features.livetv.sports.SportsRecordings
import com.nuvio.app.features.livetv.sports.TeamInfo
import com.nuvio.app.features.livetv.sports.clockText
import com.nuvio.app.features.livetv.sports.wrestlingHeadline
import com.nuvio.app.features.livetv.ui.formatClock

// ------------------------------------------------------------------ colours

/** A team's color that reads on a dark screen: the alternate color when the main one is near white. */
internal fun teamColor(t: TeamInfo?): Color {
    val main = t?.color?.let { Color(it) }
    val alt = t?.altColor?.let { Color(it) }
    val pick = when {
        main == null -> alt
        main.luminance() > 0.62f && alt != null && alt.luminance() < 0.62f -> alt
        else -> main
    }
    return pick ?: Color(0xFF3A4150)
}

/** A team colour made dark enough for white text on top of it (the TV app's rule). */
internal fun bandColor(c: Color): Color =
    lerp(c, Color(0xFF0B0E14), if (c.luminance() > 0.5f) 0.5f else if (c.luminance() > 0.25f) 0.25f else 0.08f)

internal fun leagueColor(l: League?): Color = Color(l?.color ?: 0xFF2A2F3A)

internal val SportsLiveRed = Color(0xFFE53935)

// ------------------------------------------------------------------ words for times and states

internal fun startLabel(ev: SportsEvent, use24h: Boolean): String = when {
    ev.hasTime -> clockText(ev.startMs, use24h)
    ev.endMs != null && ev.endMs - ev.startMs > 20 * 3_600_000L -> "Starts"
    else -> "All day"
}

/** What the corner of a card says: the game clock, "Started 1:00", "Final", or the start time. */
internal fun cornerStatus(ev: SportsEvent, use24h: Boolean, hideScores: Boolean): String = when (ev.state) {
    GameState.LIVE -> if (!hideScores && ev.status != null && ev.source != EventSource.GUIDE && ev.hasScore) ev.status
    else "Started " + clockText(ev.startMs, use24h)
    GameState.POST -> if (ev.source == EventSource.GUIDE) "Ended" else (ev.status ?: "Final")
    GameState.OFF -> ev.status ?: "Postponed"
    GameState.PRE -> startLabel(ev, use24h)
}

/** Time bar: how far through, from the game clock when known, otherwise from the start time and usual length. */
internal fun progressOf(ev: SportsEvent, now: Long): Float {
    if (ev.state == GameState.POST) return 1f
    if (ev.state != GameState.LIVE) return 0f
    ev.progress?.let { return it }
    val league = SportsCatalog.league(ev.leagueId)
    val end = ev.endMs ?: (ev.startMs + (league?.minutes ?: 120) * 60_000L)
    return ((now - ev.startMs).toFloat() / (end - ev.startMs).coerceAtLeast(1L)).coerceIn(0.02f, 0.98f)
}

/** A followed team plays, a league you follow, a name you follow, or a reminder you set. */
internal fun isMine(ev: SportsEvent, f: SportsFollows): Boolean =
    ev.leagueId in f.leagues || ev.word != null ||
        (ev.leagueId in f.pins && SportsCatalog.league(ev.leagueId)?.kind == LeagueKind.EVENT) ||
        ev.sides.any { f.followsTeam(ev.leagueId, it.team.id) } ||
        f.reminders.any { it.eventId == ev.id }

/** "Today", "Tomorrow", "Yesterday", or "Sun 2 Nov". */
internal fun dayWord(ms: Long, now: Long): String {
    val today = SportsDates.dayOf(now)
    val day = SportsDates.dayOf(ms)
    val diff = ((day - today) / 86_400_000L).toInt()
    return when (diff) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> SportsDates.dayLabel(ms)
    }
}

/** "in 1 day 16 h", "in 5 h 20", "in 12 min". */
internal fun countdownLabel(startMs: Long, now: Long): String {
    val m = ((startMs - now) / 60_000L).toInt()
    return when {
        m <= 0 -> "Starting"
        m < 60 -> "in $m min"
        m < 24 * 60 -> "in ${m / 60} h ${m % 60}"
        else -> { val d = m / (24 * 60); val h = (m % (24 * 60)) / 60; "in $d day${if (d == 1) "" else "s"}" + (if (h > 0) " $h h" else "") }
    }
}

/** When a game is, in a row card: "Today 7:30 PM", "Final", "2nd · 4:12". */
internal fun whenLabel(ev: SportsEvent, now: Long, use24h: Boolean, hideScores: Boolean): String = when (ev.state) {
    GameState.LIVE -> cornerStatus(ev, use24h, hideScores)
    GameState.POST -> (if (ev.source == EventSource.GUIDE) "Ended" else (ev.status ?: "Final")) + " · " + dayWord(ev.startMs, now)
    GameState.OFF -> ev.status ?: "Postponed"
    GameState.PRE -> dayWord(ev.startMs, now) + (if (ev.hasTime) " " + clockText(ev.startMs, use24h) else "")
}

/** The one-line headline of an event: a wrestling show without its promotion, otherwise the title. */
internal fun eventHeadline(ev: SportsEvent, league: League?): String =
    if (league?.sport == Sport.WRESTLING) wrestlingHeadline(ev.title) else ev.title

@Composable
internal fun use24h(): Boolean {
    val settings by LiveTvPreferences.settings.collectAsStateWithLifecycle()
    return settings.use24HourClock
}

// ------------------------------------------------------------------ small pieces

@Composable
internal fun TeamLogo(team: TeamInfo?, size: Dp) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        val logo = team?.logo?.takeIf { it.isNotBlank() }
        if (logo != null) {
            AsyncImage(model = logo, contentDescription = null, modifier = Modifier.fillMaxSize().padding(size / 8), contentScale = ContentScale.Fit)
        } else {
            Text(
                text = team?.abbr?.take(3)?.ifBlank { null } ?: team?.name?.take(2)?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textInverse,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** A league's logo (from ESPN or the nuvio-data files), or its short name on its colour. */
@Composable
internal fun LeagueMark(league: League?, size: Dp) {
    val logo = league?.let { SportsFeeds.leagueLogos[it.id] }
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(NuvioTokens.Radius.sm)).background(leagueColor(league)),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            AsyncImage(model = logo, contentDescription = null, modifier = Modifier.fillMaxSize().padding(size / 8), contentScale = ContentScale.Fit)
        } else {
            Text(
                text = league?.short?.take(5) ?: "?",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun SportsBadge(text: String, background: Color, color: Color = Color.White, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Black,
        maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(NuvioTokens.Radius.xs)).background(background).padding(horizontal = NuvioTokens.Space.s6, vertical = NuvioTokens.Space.s2),
    )
}

/** "LIVE", "FINAL", "7:30 PM" in the corner of a card. */
@Composable
internal fun StateBadge(ev: SportsEvent, use24h: Boolean) {
    val tokens = MaterialTheme.nuvio
    when (ev.state) {
        GameState.LIVE -> SportsBadge("LIVE", SportsLiveRed)
        GameState.POST -> SportsBadge(if (ev.source == EventSource.GUIDE) "ENDED" else "FINAL", tokens.colors.surfaceElevated, tokens.colors.textSecondary)
        GameState.OFF -> SportsBadge("OFF", tokens.colors.surfaceElevated, tokens.colors.textMuted)
        GameState.PRE -> SportsBadge(startLabel(ev, use24h), tokens.colors.surfaceElevated, tokens.colors.textPrimary)
    }
}

/** One team on its own band in its colour: logo, name, record, and the score (or nothing before the game). */
@Composable
private fun TeamLine(side: Side, showScore: Boolean, followed: Boolean, dim: Boolean) {
    val c = bandColor(teamColor(side.team))
    val text = if (dim) Color.White.copy(alpha = 0.75f) else Color.White
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    0f to c.copy(alpha = 0.92f),
                    0.7f to c.copy(alpha = 0.28f),
                    1f to c.copy(alpha = 0.05f),
                ),
            )
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s6),
    ) {
        TeamLogo(side.team, 28.dp)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = side.team.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = text,
                    fontWeight = if (side.winner) FontWeight.Black else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (followed) Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFF5C400), modifier = Modifier.padding(start = NuvioTokens.Space.s4).size(NuvioTokens.Icon.xs))
            }
            side.record?.takeIf { !showScore && it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
            }
        }
        if (showScore) {
            Text(
                text = side.score ?: "–",
                style = MaterialTheme.typography.titleMedium,
                color = text,
                fontWeight = FontWeight.Black,
                modifier = Modifier.widthIn(min = 28.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

/**
 * A game (two teams) or an event (a race, a fight card) as a card: who's playing, the score when
 * there is one, the state in the corner, the league under it. Tap opens the game's page.
 */
@Composable
internal fun GameCard(
    ev: SportsEvent,
    follows: SportsFollows,
    now: Long,
    use24h: Boolean,
    hideScores: Boolean,
    /** How many of your channels show it (null: not looked yet). */
    channels: Int?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    val showScore = ev.hasScore && !hideScores
    val recording = ev.state != GameState.POST && ev.hasTime && SportsRecordings.forEvent(ev) != null
    val reminded = follows.reminders.any { it.eventId == ev.id }
    val edge = NuvioTokens.Space.s12
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(tokens.shapes.card)
            .background(tokens.colors.surface)
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            modifier = Modifier.padding(start = edge, end = edge, top = NuvioTokens.Space.s10, bottom = NuvioTokens.Space.s6),
        ) {
            LeagueMark(league, 18.dp)
            Text(
                text = listOfNotNull(league?.name, ev.subtitle?.takeIf { sides.size == 2 }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (recording) Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = SportsLiveRed, modifier = Modifier.size(NuvioTokens.Icon.xs))
            if (reminded) Icon(Icons.Filled.Notifications, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(NuvioTokens.Icon.xs))
            StateBadge(ev, use24h)
        }
        if (sides.size == 2) {
            val loser = if (ev.state == GameState.POST && sides.any { it.winner }) sides.firstOrNull { !it.winner } else null
            sides.forEach { s -> TeamLine(s, showScore, follows.followsTeam(ev.leagueId, s.team.id), dim = loser === s) }
        } else Column(
            modifier = Modifier.padding(horizontal = edge),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            Text(
                text = eventHeadline(ev, league),
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ev.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (ev.standings.isNotEmpty() && !hideScores) {
                Text(
                    text = ev.standings.take(3).joinToString("   ") { listOf(it.pos, it.name).filter { p -> p.isNotBlank() }.joinToString(" ") },
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (ev.state == GameState.LIVE) {
            NuvioProgressBar(progress = progressOf(ev, now), height = NuvioTokens.Space.s3, modifier = Modifier.padding(horizontal = edge, vertical = NuvioTokens.Space.s6))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            modifier = Modifier.padding(start = edge, end = edge, top = NuvioTokens.Space.s6, bottom = NuvioTokens.Space.s10),
        ) {
            Text(
                text = when (ev.state) {
                    GameState.LIVE -> if (showScore) cornerStatus(ev, use24h, hideScores) else "On now"
                    GameState.PRE -> if (ev.hasTime) countdownLabel(ev.startMs, now) else ev.note ?: ""
                    else -> ev.note ?: ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (ev.state == GameState.LIVE) SportsLiveRed else tokens.colors.textMuted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ev.situation?.takeIf { !hideScores }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textSecondary, maxLines = 1) }
            when {
                channels == null -> Unit
                channels == 0 -> Text("Not on your channels", style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted)
                else -> Text(if (channels == 1) "1 channel" else "$channels channels", style = MaterialTheme.typography.labelSmall, color = tokens.colors.accent)
            }
        }
    }
}

/** A compact card for a horizontal row (My Sports): the two teams stacked, the state on the right. */
@Composable
internal fun RowCard(
    ev: SportsEvent,
    follows: SportsFollows,
    now: Long,
    use24h: Boolean,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    val showScore = ev.hasScore && !hideScores
    Column(
        modifier = modifier
            .width(220.dp)
            .clip(tokens.shapes.card)
            .background(if (ev.state == GameState.LIVE) tokens.colors.surfaceElevated else tokens.colors.surface)
            .clickable(onClick = onClick)
            .padding(NuvioTokens.Space.s10),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
            LeagueMark(league, 14.dp)
            Text(league?.short ?: "", style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
            Text(
                text = whenLabel(ev, now, use24h, hideScores),
                style = MaterialTheme.typography.labelSmall,
                color = if (ev.state == GameState.LIVE) SportsLiveRed else tokens.colors.textMuted,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        if (sides.size == 2) {
            sides.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                    TeamLogo(s.team, 20.dp)
                    Text(
                        text = s.team.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textPrimary,
                        fontWeight = if (s.winner) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (showScore) Text(s.score ?: "–", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.Black)
                }
            }
        } else {
            Text(eventHeadline(ev, league), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(NuvioTokens.Space.s2))
            Text(ev.subtitle ?: ev.note ?: "", style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "7:30 PM" following the Live TV 24-hour setting (composable; see [clockText] off the main thread). */
@Composable
internal fun sportsClock(ms: Long): String = formatClock(ms)
