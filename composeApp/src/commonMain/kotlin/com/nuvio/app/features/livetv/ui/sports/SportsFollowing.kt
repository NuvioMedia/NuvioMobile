package com.nuvio.app.features.livetv.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.livetv.sports.ChannelRegions
import com.nuvio.app.features.livetv.sports.League
import com.nuvio.app.features.livetv.sports.LeagueKind
import com.nuvio.app.features.livetv.sports.Sport
import com.nuvio.app.features.livetv.sports.SportsCatalog
import com.nuvio.app.features.livetv.sports.SportsEvent
import com.nuvio.app.features.livetv.sports.SportsFollows
import com.nuvio.app.features.livetv.sports.SportsRepository
import com.nuvio.app.features.livetv.sports.SportsRows
import com.nuvio.app.features.livetv.sports.SportsSettings
import com.nuvio.app.features.livetv.sports.TeamInfo
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSwitchRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.livetv_sports_add_name
import nuvio.composeapp.generated.resources.livetv_sports_add_name_hint
import nuvio.composeapp.generated.resources.livetv_sports_browse
import nuvio.composeapp.generated.resources.livetv_sports_follow
import nuvio.composeapp.generated.resources.livetv_sports_following
import nuvio.composeapp.generated.resources.livetv_sports_hide_scores
import nuvio.composeapp.generated.resources.livetv_sports_hide_scores_description
import nuvio.composeapp.generated.resources.livetv_sports_leagues
import nuvio.composeapp.generated.resources.livetv_sports_names
import nuvio.composeapp.generated.resources.livetv_sports_only_my_channels
import nuvio.composeapp.generated.resources.livetv_sports_only_my_channels_description
import nuvio.composeapp.generated.resources.livetv_sports_region
import nuvio.composeapp.generated.resources.livetv_sports_remind_lead
import nuvio.composeapp.generated.resources.livetv_sports_remind_my_teams
import nuvio.composeapp.generated.resources.livetv_sports_remind_my_teams_description
import nuvio.composeapp.generated.resources.livetv_sports_scoring_alerts
import nuvio.composeapp.generated.resources.livetv_sports_scoring_alerts_description
import nuvio.composeapp.generated.resources.livetv_sports_search_hint
import nuvio.composeapp.generated.resources.livetv_sports_settings
import nuvio.composeapp.generated.resources.livetv_sports_switch_auto
import nuvio.composeapp.generated.resources.livetv_sports_switch_auto_description
import nuvio.composeapp.generated.resources.livetv_sports_teams
import nuvio.composeapp.generated.resources.livetv_sports_unfollow
import nuvio.composeapp.generated.resources.livetv_sports_channels_from
import nuvio.composeapp.generated.resources.livetv_sports_clear_fixes
import nuvio.composeapp.generated.resources.livetv_sports_clear_fixes_description
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// ------------------------------------------------------------------ Search

/** Search: leagues by name, teams from the games we know and the leagues' team lists, names to follow. */
@Composable
internal fun SportsSearchTab(known: List<SportsEvent>, follows: SportsFollows, actions: SportsActions) {
    val tokens = MaterialTheme.nuvio
    val repo = SportsRepository
    var query by rememberSaveable { mutableStateOf("") }
    var pickingLeague by remember { mutableStateOf<League?>(null) }
    val q = query.trim()
    val leagues = remember(q) {
        if (q.length < 2) emptyList() else SportsCatalog.all.filter { l ->
            l.name.contains(q, true) || l.short.contains(q, true) || l.guideWords.any { it.contains(q, true) }
        }.take(20)
    }
    val teams = remember(q, known) {
        if (q.length < 2) emptyList() else known.asSequence().flatMap { it.sides.asSequence() }.map { it.team }
            .filter { it.fullName.contains(q, true) || it.name.contains(q, true) || it.abbr.equals(q, true) }
            .distinctBy { SportsCatalog.teamFamily(it.leagueId) + "|" + it.id }
            .take(30).toList()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        NuvioInputField(
            value = query, onValueChange = { query = it },
            placeholder = stringResource(Res.string.livetv_sports_search_hint),
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s6),
        )
        val leaguesTitle = stringResource(Res.string.livetv_sports_leagues)
        val teamsTitle = stringResource(Res.string.livetv_sports_teams)
        val nameTitle = stringResource(Res.string.livetv_sports_add_name)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = NuvioTokens.Space.s16, end = NuvioTokens.Space.s16, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            if (q.length >= 3 && follows.words.none { it.equals(q, true) }) {
                item(key = "word") {
                    FollowRow(
                        title = "“$q”",
                        subtitle = nameTitle,
                        leading = { Icon(Icons.Filled.Add, contentDescription = null, tint = tokens.colors.accent) },
                        followed = false,
                        onToggle = { repo.addWord(q) },
                        onClick = { repo.addWord(q) },
                    )
                }
            }
            if (leagues.isNotEmpty()) item(key = "h:l") { SectionTitle(leaguesTitle) }
            items(leagues, key = { "l:" + it.id }) { l ->
                val followed = l.id in follows.pins || l.id in follows.leagues
                FollowRow(
                    title = l.name,
                    subtitle = l.sport.label + (if (l.kind == LeagueKind.TEAMS) " · tap to browse teams" else ""),
                    leading = { LeagueMark(l, 28.dp) },
                    followed = followed,
                    onToggle = { repo.togglePin(l.id) },
                    onClick = { if (l.kind == LeagueKind.TEAMS && !l.guideOnly) pickingLeague = l else actions.onOpenLeague(l) },
                )
            }
            if (teams.isNotEmpty()) item(key = "h:t") { SectionTitle(teamsTitle) }
            items(teams, key = { "t:" + it.key }) { t ->
                FollowRow(
                    title = t.fullName,
                    subtitle = SportsCatalog.league(t.leagueId)?.name ?: t.leagueId,
                    leading = { TeamLogo(t, 28.dp) },
                    followed = follows.followedTeam(t) != null,
                    onToggle = { repo.toggleTeam(t) },
                    onClick = { repo.toggleTeam(t) },
                    band = t,
                )
            }
            if (q.length < 2) {
                item(key = "browse") {
                    Text(stringResource(Res.string.livetv_sports_browse), style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = NuvioTokens.Space.s8))
                }
                items(Sport.entries.toList(), key = { "s:" + it.name }) { sport ->
                    var open by remember { mutableStateOf(false) }
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(tokens.shapes.compactCard).clickable { open = !open }.padding(vertical = NuvioTokens.Space.s8),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(sport.icon + "  " + sport.label, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f))
                            Text(SportsCatalog.all.count { it.sport == sport }.toString(), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
                        }
                        if (open) {
                            SportsCatalog.all.filter { it.sport == sport }.forEach { l ->
                                val followed = l.id in follows.pins || l.id in follows.leagues
                                FollowRow(
                                    title = l.name,
                                    subtitle = l.regions.takeIf { it.isNotEmpty() }?.joinToString(", ") { SportsCatalog.regionName(it) } ?: "",
                                    leading = { LeagueMark(l, 28.dp) },
                                    followed = followed,
                                    onToggle = { repo.togglePin(l.id) },
                                    onClick = { if (l.kind == LeagueKind.TEAMS && !l.guideOnly) pickingLeague = l else actions.onOpenLeague(l) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    pickingLeague?.let { l -> TeamPickerSheet(l, follows, onOpenLeague = { actions.onOpenLeague(l) }, onDismiss = { pickingLeague = null }) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.nuvio.colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = NuvioTokens.Space.s8))
}

/** One thing you can follow: a star on the right. */
@Composable
internal fun FollowRow(
    title: String,
    subtitle: String,
    leading: @Composable () -> Unit,
    followed: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    /** A team's row takes its colours, like the TV app. */
    band: TeamInfo? = null,
) {
    val tokens = MaterialTheme.nuvio
    val titleColor = if (band != null) Color.White else tokens.colors.textPrimary
    val subColor = if (band != null) Color.White.copy(alpha = 0.7f) else tokens.colors.textMuted
    Row(
        modifier = Modifier.fillMaxWidth().clip(tokens.shapes.card).background(tokens.colors.surface)
            .let { if (band != null) it.background(teamBandBrush(band)) else it }
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        leading()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
        IconButton(onClick = onToggle) {
            Icon(
                if (followed) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = stringResource(if (followed) Res.string.livetv_sports_unfollow else Res.string.livetv_sports_follow),
                tint = if (followed) tokens.colors.accent else if (band != null) Color.White.copy(alpha = 0.7f) else tokens.colors.textMuted,
            )
        }
    }
}

/** A league's teams, to follow (loaded from the feed; countries for international competitions). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamPickerSheet(league: League, follows: SportsFollows, onOpenLeague: () -> Unit, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val repo = SportsRepository
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var teams by remember(league.id) { mutableStateOf<List<TeamInfo>?>(null) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(league.id) { teams = runCatching { repo.teams(league) }.getOrDefault(emptyList()) }
    val followedLeague = league.id in follows.pins || league.id in follows.leagues
    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, fullHeight = true) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            LeagueMark(league, 32.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(league.name, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Text(league.sport.label, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            }
            Chip(label = if (followedLeague) "Following" else "Follow league", selected = followedLeague, onClick = { repo.togglePin(league.id) })
            if (repo.mayHaveTable(league)) Chip(label = "Table", selected = false, onClick = { onDismiss(); onOpenLeague() })
        }
        NuvioInputField(value = filter, onValueChange = { filter = it }, placeholder = "Filter teams", modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16))
        val list = teams
        if (list == null) {
            NuvioLoadingIndicator(modifier = Modifier.padding(NuvioTokens.Space.s24).align(Alignment.CenterHorizontally))
        } else {
            val shown = list.filter { filter.isBlank() || it.fullName.contains(filter, true) || it.abbr.contains(filter, true) }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8, ),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
            ) {
                if (shown.isEmpty()) item { Text("No teams to show", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted) }
                items(shown, key = { it.key }) { t ->
                    val followed = follows.followedTeam(t) != null
                    FollowRow(
                        title = t.fullName,
                        subtitle = t.record ?: "",
                        leading = { TeamLogo(t, 28.dp) },
                        followed = followed,
                        onToggle = { repo.toggleTeam(t) },
                        onClick = { repo.toggleTeam(t) },
                        band = t,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Following

/** Everything you follow, in My Sports' order (drag to arrange), with unfollow, plus a name to add. */
@Composable
internal fun SportsFollowingTab(follows: SportsFollows, settings: SportsSettings, actions: SportsActions) {
    val tokens = MaterialTheme.nuvio
    val repo = SportsRepository
    val haptics = LocalHapticFeedback.current
    val keys = remember(follows) { SportsRows.rowKeys(follows) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState = listState) { from, to ->
        val i = keys.indexOf(from.key as? String)
        val j = keys.indexOf(to.key as? String)
        if (i >= 0 && j >= 0) {
            repo.moveRow(i, j)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    var addingName by remember { mutableStateOf(false) }
    var pickingLeague by remember { mutableStateOf<League?>(null) }
    LaunchedEffect(Unit) { if (!follows.setUp) repo.markSetUp() }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = NuvioTokens.Space.s16, end = NuvioTokens.Space.s16, bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s16)),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
    ) {
        item(key = "add") {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8), modifier = Modifier.padding(vertical = NuvioTokens.Space.s4)) {
                Chip(label = stringResource(Res.string.livetv_sports_add_name), selected = false, onClick = { addingName = true })
            }
        }
        if (keys.isEmpty()) {
            item(key = "empty") { Text("Follow teams, leagues and names from Search; their games then show under My Sports.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted) }
        }
        items(keys, key = { it }) { key ->
            ReorderableItem(reorderState, key = key) {
                val team = follows.teams.firstOrNull { "t:${it.key}" == key }
                val league = key.takeIf { it.startsWith("l:") }?.let { SportsCatalog.league(it.removePrefix("l:")) }
                val word = key.takeIf { it.startsWith("w:") }?.removePrefix("w:")
                val teamInfo = team?.let { TeamInfo(it.leagueId, it.teamId, it.name, it.name, it.abbr, null, it.color, null, it.logo) }
                FollowRow(
                    band = teamInfo,
                    title = team?.name ?: league?.name ?: word?.let { "“$it”" } ?: key,
                    subtitle = when {
                        team != null -> SportsCatalog.league(team.leagueId)?.name ?: team.leagueId
                        league != null -> league.sport.label
                        else -> stringResource(Res.string.livetv_sports_names)
                    },
                    leading = {
                        when {
                            team != null -> TeamLogo(TeamInfo(team.leagueId, team.teamId, team.name, team.name, team.abbr, null, team.color, null, team.logo), 28.dp)
                            league != null -> LeagueMark(league, 28.dp)
                            else -> Icon(Icons.Filled.Check, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(28.dp))
                        }
                    },
                    followed = true,
                    onToggle = {
                        when {
                            team != null -> repo.toggleTeam(TeamInfo(team.leagueId, team.teamId, team.name, team.name, team.abbr, null, team.color, null, team.logo))
                            league != null -> { if (league.id in follows.pins) repo.togglePin(league.id); if (league.id in follows.leagues) repo.toggleLeague(league.id) }
                            word != null -> repo.removeWord(word)
                        }
                    },
                    onClick = { if (league != null) actions.onOpenLeague(league) else if (team != null) pickingLeague = SportsCatalog.league(team.leagueId) },
                    trailing = {
                        Icon(
                            Icons.Filled.DragHandle, contentDescription = null, tint = tokens.colors.textMuted,
                            modifier = Modifier.draggableHandle(
                                onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            ),
                        )
                    },
                )
            }
        }
    }
    if (addingName) {
        var name by remember { mutableStateOf("") }
        DialogSurface(onDismissRequest = { addingName = false }, title = stringResource(Res.string.livetv_sports_add_name), message = stringResource(Res.string.livetv_sports_add_name_hint)) {
            NuvioInputField(value = name, onValueChange = { name = it }, placeholder = "Luke Littler")
            DialogButtons {
                DialogButton(text = stringResource(Res.string.action_cancel), onClick = { addingName = false })
                DialogButton(text = stringResource(Res.string.action_save), style = DialogButtonStyle.Primary, enabled = name.trim().length >= 3, onClick = { repo.addWord(name); addingName = false })
            }
        }
    }
    pickingLeague?.let { l -> TeamPickerSheet(l, follows, onOpenLeague = { actions.onOpenLeague(l) }, onDismiss = { pickingLeague = null }) }
}

// ------------------------------------------------------------------ settings sheet

private val REMIND_MINUTES = listOf(0, 5, 10, 15, 30, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SportsSettingsSheet(settings: SportsSettings, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val repo = SportsRepository
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pickRegion by remember { mutableStateOf(false) }
    var pickChannels by remember { mutableStateOf(false) }
    var pickLead by remember { mutableStateOf(false) }
    fun update(t: (SportsSettings) -> SportsSettings) = repo.updateSettings(t)
    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(stringResource(Res.string.livetv_sports_settings), style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8))
        SettingsGroup(isTablet = false) {
            SettingsSwitchRow(title = stringResource(Res.string.livetv_sports_hide_scores), description = stringResource(Res.string.livetv_sports_hide_scores_description), checked = settings.hideScores, isTablet = false, onCheckedChange = { on -> update { it.copy(hideScores = on) } })
            SettingsGroupDivider(isTablet = false)
            SettingsSwitchRow(title = stringResource(Res.string.livetv_sports_only_my_channels), description = stringResource(Res.string.livetv_sports_only_my_channels_description), checked = settings.onlyOnMyChannels, isTablet = false, onCheckedChange = { on -> update { it.copy(onlyOnMyChannels = on) } })
            SettingsGroupDivider(isTablet = false)
            SettingsNavigationRow(title = stringResource(Res.string.livetv_sports_region), description = SportsCatalog.regionName(SportsCatalog.regionOf(settings)), isTablet = false, onClick = { pickRegion = true })
            SettingsGroupDivider(isTablet = false)
            SettingsNavigationRow(title = stringResource(Res.string.livetv_sports_channels_from), description = ChannelRegions.name(settings.channelRegion), isTablet = false, onClick = { pickChannels = true })
            SettingsGroupDivider(isTablet = false)
            SettingsSwitchRow(title = stringResource(Res.string.livetv_sports_remind_my_teams), description = stringResource(Res.string.livetv_sports_remind_my_teams_description), checked = settings.remindMyTeams, isTablet = false, onCheckedChange = { on -> update { it.copy(remindMyTeams = on) } })
            SettingsGroupDivider(isTablet = false)
            SettingsNavigationRow(title = stringResource(Res.string.livetv_sports_remind_lead), description = if (settings.remindMinutes == 0) "At the start" else "${settings.remindMinutes} minutes before", isTablet = false, onClick = { pickLead = true })
            SettingsGroupDivider(isTablet = false)
            SettingsSwitchRow(title = stringResource(Res.string.livetv_sports_switch_auto), description = stringResource(Res.string.livetv_sports_switch_auto_description), checked = settings.autoSwitch, isTablet = false, onCheckedChange = { on -> update { it.copy(autoSwitch = on) } })
            SettingsGroupDivider(isTablet = false)
            SettingsSwitchRow(title = stringResource(Res.string.livetv_sports_scoring_alerts), description = stringResource(Res.string.livetv_sports_scoring_alerts_description), checked = settings.scoringAlerts, isTablet = false, onCheckedChange = { on -> update { it.copy(scoringAlerts = on) } })
            SettingsGroupDivider(isTablet = false)
            SettingsNavigationRow(title = stringResource(Res.string.livetv_sports_clear_fixes), description = stringResource(Res.string.livetv_sports_clear_fixes_description), isTablet = false, onClick = { repo.clearFixes(); onDismiss() })
        }
    }
    if (pickRegion) {
        DialogSurface(onDismissRequest = { pickRegion = false }, title = stringResource(Res.string.livetv_sports_region)) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                DialogOption(text = "Automatic (" + SportsCatalog.regionName(SportsCatalog.deviceRegion()) + ")", selected = settings.region.isBlank(), onClick = { update { it.copy(region = "") }; pickRegion = false })
                SportsCatalog.regions.forEach { (code, name) ->
                    DialogOption(text = name, selected = settings.region == code, onClick = { update { it.copy(region = code) }; pickRegion = false })
                }
            }
            DialogButtons { DialogButton(text = stringResource(Res.string.action_cancel), onClick = { pickRegion = false }) }
        }
    }
    if (pickChannels) {
        DialogSurface(onDismissRequest = { pickChannels = false }, title = stringResource(Res.string.livetv_sports_channels_from)) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                ChannelRegions.all.forEach { (code, name) ->
                    DialogOption(text = name, selected = settings.channelRegion == code, onClick = { update { it.copy(channelRegion = code) }; pickChannels = false })
                }
            }
            DialogButtons { DialogButton(text = stringResource(Res.string.action_cancel), onClick = { pickChannels = false }) }
        }
    }
    if (pickLead) {
        DialogSurface(onDismissRequest = { pickLead = false }, title = stringResource(Res.string.livetv_sports_remind_lead)) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                REMIND_MINUTES.forEach { m ->
                    DialogOption(text = if (m == 0) "At the start" else "$m minutes before", selected = settings.remindMinutes == m, onClick = { update { it.copy(remindMinutes = m) }; pickLead = false })
                }
            }
            DialogButtons { DialogButton(text = stringResource(Res.string.action_cancel), onClick = { pickLead = false }) }
        }
    }
}
