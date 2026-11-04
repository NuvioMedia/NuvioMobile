package com.nuvio.app.features.livetv.ui.sports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.record.RecordingPlatform
import com.nuvio.app.features.livetv.sports.GameAlerts
import com.nuvio.app.features.livetv.sports.SportsRepository
import com.nuvio.app.features.livetv.sports.SportsSettings
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSwitchRow
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.livetv_game_alerts
import nuvio.composeapp.generated.resources.livetv_game_alerts_blocked
import nuvio.composeapp.generated.resources.livetv_game_alerts_description
import nuvio.composeapp.generated.resources.livetv_game_alerts_exact
import nuvio.composeapp.generated.resources.livetv_game_alerts_exact_description
import nuvio.composeapp.generated.resources.livetv_game_alerts_how_early
import nuvio.composeapp.generated.resources.livetv_game_alerts_not_now
import nuvio.composeapp.generated.resources.livetv_game_alerts_offer_body
import nuvio.composeapp.generated.resources.livetv_game_alerts_offer_title
import nuvio.composeapp.generated.resources.livetv_game_alerts_off
import nuvio.composeapp.generated.resources.livetv_game_alerts_scores
import nuvio.composeapp.generated.resources.livetv_game_alerts_scores_description
import nuvio.composeapp.generated.resources.livetv_game_alerts_scores_hidden
import nuvio.composeapp.generated.resources.livetv_game_alerts_starting
import nuvio.composeapp.generated.resources.livetv_game_alerts_starting_description
import nuvio.composeapp.generated.resources.livetv_game_alerts_turn_on
import nuvio.composeapp.generated.resources.livetv_sports_switch_auto
import nuvio.composeapp.generated.resources.livetv_sports_switch_auto_description
import org.jetbrains.compose.resources.stringResource

private val ALERT_MINUTES = listOf(0, 5, 10, 15, 30, 60)

private fun leadText(minutes: Int) = if (minutes == 0) "At the start" else "$minutes minutes before"

/** Whether notifications are on, checked again every couple of seconds (they can be changed in the phone's settings). */
@Composable
private fun notificationsOnState(): Boolean {
    var on by remember { mutableStateOf(GameAlerts.notificationsAllowed()) }
    LaunchedEffect(Unit) {
        while (true) {
            on = GameAlerts.notificationsAllowed()
            delay(2_000)
        }
    }
    return on
}

/** "Game starting · Scores", or "Off" (for the Game Alerts row). */
@Composable
private fun alertsSummary(s: SportsSettings, notificationsOn: Boolean): String {
    val off = stringResource(Res.string.livetv_game_alerts_off)
    val starting = stringResource(Res.string.livetv_game_alerts_starting)
    if (!notificationsOn) return off
    val parts = buildList {
        if (s.remindMyTeams) add(starting)
        if (s.scoringAlerts && !s.hideScores) add("Scores")
    }
    return if (parts.isEmpty()) off else parts.joinToString(" · ")
}

/** The Game Alerts row (Settings > Live TV > Sports, and the Sports page's settings). */
@Composable
internal fun GameAlertsRow(isTablet: Boolean, onClick: () -> Unit) {
    val settings by SportsRepository.settings.collectAsStateWithLifecycle()
    val notificationsOn = notificationsOnState()
    SettingsNavigationRow(
        title = stringResource(Res.string.livetv_game_alerts),
        description = stringResource(Res.string.livetv_game_alerts_description),
        icon = Icons.Filled.Notifications,
        isTablet = isTablet,
        trailingContent = {
            Text(alertsSummary(settings, notificationsOn), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textSecondary)
        },
        onClick = onClick,
    )
}

/** Game Alerts: game starting (and how early), scores for my teams, opening the game from the alert. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameAlertsSheet(onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val repo = SportsRepository
    val settings by repo.settings.collectAsStateWithLifecycle()
    val notificationsOn = notificationsOnState()
    var exactAlarms by remember { mutableStateOf(RecordingPlatform.canScheduleExact()) }
    var pickLead by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun update(t: (SportsSettings) -> SportsSettings) = repo.updateSettings(t)
    /** Turning an alert on: ask for notifications when they're off. */
    fun turnedOn(on: Boolean) { if (on) GameAlerts.askForNotifications() }

    LaunchedEffect(Unit) {
        while (true) {
            exactAlarms = RecordingPlatform.canScheduleExact()
            delay(2_000)
        }
    }

    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            stringResource(Res.string.livetv_game_alerts),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
        )
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = NuvioTokens.Space.s16),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            if (!notificationsOn) {
                SettingsGroup(isTablet = false) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
                    ) {
                        Text(
                            stringResource(Res.string.livetv_game_alerts_blocked),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        DialogButton(
                            text = stringResource(Res.string.livetv_game_alerts_turn_on),
                            style = DialogButtonStyle.Primary,
                            onClick = { GameAlerts.turnOnNotifications() },
                        )
                    }
                }
            }
            SettingsGroup(isTablet = false) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_game_alerts_starting),
                    description = stringResource(Res.string.livetv_game_alerts_starting_description),
                    checked = settings.remindMyTeams,
                    isTablet = false,
                    onCheckedChange = { on -> update { it.copy(remindMyTeams = on) }; turnedOn(on) },
                )
                SettingsGroupDivider(isTablet = false)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_game_alerts_how_early),
                    description = leadText(settings.remindMinutes),
                    isTablet = false,
                    onClick = { pickLead = true },
                )
                SettingsGroupDivider(isTablet = false)
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_sports_switch_auto),
                    description = stringResource(Res.string.livetv_sports_switch_auto_description),
                    checked = settings.autoSwitch,
                    isTablet = false,
                    onCheckedChange = { on -> update { it.copy(autoSwitch = on) } },
                )
                SettingsGroupDivider(isTablet = false)
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_game_alerts_scores),
                    description = stringResource(
                        if (settings.hideScores) Res.string.livetv_game_alerts_scores_hidden else Res.string.livetv_game_alerts_scores_description,
                    ),
                    checked = settings.scoringAlerts && !settings.hideScores,
                    enabled = !settings.hideScores,
                    isTablet = false,
                    onCheckedChange = { on -> update { it.copy(scoringAlerts = on) }; turnedOn(on) },
                )
                if (!exactAlarms) {
                    SettingsGroupDivider(isTablet = false)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.livetv_game_alerts_exact),
                        description = stringResource(Res.string.livetv_game_alerts_exact_description),
                        isTablet = false,
                        onClick = { RecordingPlatform.requestExactAlarms() },
                    )
                }
            }
        }
    }
    if (pickLead) {
        DialogSurface(onDismissRequest = { pickLead = false }, title = stringResource(Res.string.livetv_game_alerts_how_early)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                ALERT_MINUTES.forEach { m ->
                    DialogOption(text = leadText(m), selected = settings.remindMinutes == m, onClick = { update { it.copy(remindMinutes = m) }; pickLead = false })
                }
            }
            DialogButtons { DialogButton(text = stringResource(Res.string.action_cancel), onClick = { pickLead = false }) }
        }
    }
}

/** "Get alerts for Arsenal?" after you follow your first team (shown once on this phone). */
@Composable
internal fun GameAlertsOffer() {
    val name by GameAlerts.offer.collectAsStateWithLifecycle()
    val team = name ?: return
    DialogSurface(
        onDismissRequest = { GameAlerts.declineOffer() },
        title = stringResource(Res.string.livetv_game_alerts_offer_title, team),
        message = stringResource(Res.string.livetv_game_alerts_offer_body),
    ) {
        DialogButtons {
            DialogButton(text = stringResource(Res.string.livetv_game_alerts_not_now), onClick = { GameAlerts.declineOffer() })
            DialogButton(text = stringResource(Res.string.livetv_game_alerts_turn_on), style = DialogButtonStyle.Primary, onClick = { GameAlerts.acceptOffer() })
        }
    }
}
