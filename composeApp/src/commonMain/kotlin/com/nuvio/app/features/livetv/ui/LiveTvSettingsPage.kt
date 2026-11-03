package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.format.formatLocalDateTime
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.data.BackupFileException
import com.nuvio.app.features.livetv.data.BackupFileInfo
import com.nuvio.app.features.livetv.data.exportBackupFileText
import com.nuvio.app.features.livetv.data.importBackupFile
import com.nuvio.app.features.livetv.data.parseBackupFile
import com.nuvio.app.features.livetv.model.EpgSource
import com.nuvio.app.features.livetv.model.PlaylistAccount
import com.nuvio.app.features.livetv.model.PlaylistSource
import com.nuvio.app.features.livetv.ondemand.OnDemandRepository
import com.nuvio.app.features.livetv.ondemand.VodSort
import com.nuvio.app.features.livetv.record.LiveTvRecorder
import com.nuvio.app.features.livetv.record.RecordingPlatform
import com.nuvio.app.features.livetv.record.RecordingRules
import com.nuvio.app.features.livetv.sports.SportsRepository
import com.nuvio.app.features.livetv.parental.ParentalControls
import com.nuvio.app.features.livetv.ondemand.VodCategory
import com.nuvio.app.features.livetv.ondemand.VodKind
import com.nuvio.app.features.livetv.model.ChannelGroup
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationPlatform
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nuvio.app.features.livetv.sync.LiveTvDriveSync
import com.nuvio.app.features.livetv.sync.SignInPrompt
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSwitchRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_delete
import nuvio.composeapp.generated.resources.action_edit
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.livetv_add_epg
import nuvio.composeapp.generated.resources.livetv_add_epg_description
import nuvio.composeapp.generated.resources.livetv_add_m3u
import nuvio.composeapp.generated.resources.livetv_add_m3u_description
import nuvio.composeapp.generated.resources.livetv_add_xtream
import nuvio.composeapp.generated.resources.livetv_add_xtream_description
import nuvio.composeapp.generated.resources.livetv_backup_now
import nuvio.composeapp.generated.resources.livetv_backup_now_description
import nuvio.composeapp.generated.resources.livetv_catchup_prefer_hls
import nuvio.composeapp.generated.resources.livetv_catchup_prefer_hls_description
import nuvio.composeapp.generated.resources.livetv_catchup_shift
import nuvio.composeapp.generated.resources.livetv_catchup_shift_description
import nuvio.composeapp.generated.resources.livetv_catchup_shift_none
import nuvio.composeapp.generated.resources.livetv_section_catchup
import nuvio.composeapp.generated.resources.livetv_days_ahead
import nuvio.composeapp.generated.resources.livetv_days_ahead_description
import nuvio.composeapp.generated.resources.livetv_hours_back
import nuvio.composeapp.generated.resources.livetv_hours_back_description
import nuvio.composeapp.generated.resources.livetv_delete_source_message
import nuvio.composeapp.generated.resources.livetv_disable
import nuvio.composeapp.generated.resources.livetv_drive_account
import nuvio.composeapp.generated.resources.livetv_drive_auto_sync
import nuvio.composeapp.generated.resources.livetv_drive_auto_sync_description
import nuvio.composeapp.generated.resources.livetv_drive_body
import nuvio.composeapp.generated.resources.livetv_drive_code_instructions
import nuvio.composeapp.generated.resources.livetv_drive_code_waiting
import nuvio.composeapp.generated.resources.livetv_drive_sign_in
import nuvio.composeapp.generated.resources.livetv_drive_sign_in_description
import nuvio.composeapp.generated.resources.livetv_drive_sign_out
import nuvio.composeapp.generated.resources.livetv_drive_sign_out_description
import nuvio.composeapp.generated.resources.livetv_drive_unavailable
import nuvio.composeapp.generated.resources.livetv_enable
import nuvio.composeapp.generated.resources.livetv_epg_refresh
import nuvio.composeapp.generated.resources.livetv_epg_refresh_description
import nuvio.composeapp.generated.resources.livetv_field_name
import nuvio.composeapp.generated.resources.livetv_field_password
import nuvio.composeapp.generated.resources.livetv_field_server
import nuvio.composeapp.generated.resources.livetv_field_url
import nuvio.composeapp.generated.resources.livetv_field_user_agent
import nuvio.composeapp.generated.resources.livetv_field_username
import nuvio.composeapp.generated.resources.livetv_field_xmltv_url
import nuvio.composeapp.generated.resources.livetv_load_embedded_epg
import nuvio.composeapp.generated.resources.livetv_never_updated
import nuvio.composeapp.generated.resources.livetv_playlist_refresh
import nuvio.composeapp.generated.resources.livetv_playlist_refresh_description
import nuvio.composeapp.generated.resources.livetv_remember_audio
import nuvio.composeapp.generated.resources.livetv_remember_audio_description
import nuvio.composeapp.generated.resources.livetv_restore
import nuvio.composeapp.generated.resources.livetv_restore_description
import nuvio.composeapp.generated.resources.livetv_restore_message
import nuvio.composeapp.generated.resources.livetv_section_backup
import nuvio.composeapp.generated.resources.livetv_backup_file_body
import nuvio.composeapp.generated.resources.livetv_import_file
import nuvio.composeapp.generated.resources.livetv_import_file_description
import nuvio.composeapp.generated.resources.livetv_export_file
import nuvio.composeapp.generated.resources.livetv_export_file_description
import nuvio.composeapp.generated.resources.livetv_import_confirm_title
import nuvio.composeapp.generated.resources.livetv_import_confirm_message
import nuvio.composeapp.generated.resources.livetv_import_confirm_action
import nuvio.composeapp.generated.resources.livetv_import_unknown_device
import nuvio.composeapp.generated.resources.livetv_import_unknown_date
import nuvio.composeapp.generated.resources.livetv_import_done
import nuvio.composeapp.generated.resources.livetv_export_done
import nuvio.composeapp.generated.resources.livetv_file_error_not_backup
import nuvio.composeapp.generated.resources.livetv_file_error_newer
import nuvio.composeapp.generated.resources.livetv_file_error_no_sources
import nuvio.composeapp.generated.resources.livetv_file_error_unavailable
import nuvio.composeapp.generated.resources.livetv_file_error_read
import nuvio.composeapp.generated.resources.livetv_file_error_write
import nuvio.composeapp.generated.resources.livetv_file_error_import
import nuvio.composeapp.generated.resources.livetv_drive_subsection
import nuvio.composeapp.generated.resources.livetv_section_general
import nuvio.composeapp.generated.resources.livetv_section_on_demand
import nuvio.composeapp.generated.resources.livetv_section_recordings
import nuvio.composeapp.generated.resources.livetv_rec_exact_alarms
import nuvio.composeapp.generated.resources.livetv_rec_exact_alarms_description
import nuvio.composeapp.generated.resources.livetv_rec_keep_all
import nuvio.composeapp.generated.resources.livetv_rec_keep_last
import nuvio.composeapp.generated.resources.livetv_rec_notifications
import nuvio.composeapp.generated.resources.livetv_rec_notifications_description
import nuvio.composeapp.generated.resources.livetv_rec_padding_post
import nuvio.composeapp.generated.resources.livetv_rec_padding_post_description
import nuvio.composeapp.generated.resources.livetv_rec_padding_pre
import nuvio.composeapp.generated.resources.livetv_rec_padding_pre_description
import nuvio.composeapp.generated.resources.livetv_rec_series_keep
import nuvio.composeapp.generated.resources.livetv_rec_series_keep_description
import nuvio.composeapp.generated.resources.livetv_rec_space_rule
import nuvio.composeapp.generated.resources.livetv_rec_space_rule_delete
import nuvio.composeapp.generated.resources.livetv_rec_space_rule_description
import nuvio.composeapp.generated.resources.livetv_rec_space_rule_stop
import nuvio.composeapp.generated.resources.livetv_rec_storage
import nuvio.composeapp.generated.resources.livetv_rec_storage_description
import nuvio.composeapp.generated.resources.livetv_rec_this_device
import nuvio.composeapp.generated.resources.livetv_vod_default_sort
import nuvio.composeapp.generated.resources.livetv_vod_default_sort_description
import nuvio.composeapp.generated.resources.livetv_vod_import
import nuvio.composeapp.generated.resources.livetv_vod_import_description
import nuvio.composeapp.generated.resources.livetv_vod_merge_duplicates
import nuvio.composeapp.generated.resources.livetv_vod_merge_duplicates_description
import nuvio.composeapp.generated.resources.livetv_vod_update_now
import nuvio.composeapp.generated.resources.livetv_vod_update_now_description
import nuvio.composeapp.generated.resources.livetv_section_guides
import nuvio.composeapp.generated.resources.livetv_section_playlists
import nuvio.composeapp.generated.resources.livetv_section_watching
import nuvio.composeapp.generated.resources.livetv_section_channel_display
import nuvio.composeapp.generated.resources.livetv_phone_logos
import nuvio.composeapp.generated.resources.livetv_phone_logos_description
import nuvio.composeapp.generated.resources.livetv_phone_names
import nuvio.composeapp.generated.resources.livetv_phone_names_description
import nuvio.composeapp.generated.resources.livetv_phone_numbers
import nuvio.composeapp.generated.resources.livetv_phone_numbers_description
import nuvio.composeapp.generated.resources.livetv_show_tab
import nuvio.composeapp.generated.resources.livetv_sports
import nuvio.composeapp.generated.resources.livetv_parental
import nuvio.composeapp.generated.resources.livetv_section_reminders
import nuvio.composeapp.generated.resources.livetv_reminders_list
import nuvio.composeapp.generated.resources.livetv_reminders_list_description
import nuvio.composeapp.generated.resources.livetv_reminders_none
import nuvio.composeapp.generated.resources.livetv_reminder_lead
import nuvio.composeapp.generated.resources.livetv_reminder_lead_description
import nuvio.composeapp.generated.resources.livetv_reminder_lead_at_start
import nuvio.composeapp.generated.resources.livetv_reminder_lead_minutes
import nuvio.composeapp.generated.resources.livetv_continue_watching
import nuvio.composeapp.generated.resources.livetv_continue_watching_description
import com.nuvio.app.features.livetv.reminders.LiveTvReminders
import androidx.compose.material.icons.filled.Notifications
import nuvio.composeapp.generated.resources.livetv_parental_pin
import nuvio.composeapp.generated.resources.livetv_parental_pin_set
import nuvio.composeapp.generated.resources.livetv_parental_pin_not_set
import nuvio.composeapp.generated.resources.livetv_parental_pin_description
import nuvio.composeapp.generated.resources.livetv_parental_set_pin
import nuvio.composeapp.generated.resources.livetv_parental_change_pin
import nuvio.composeapp.generated.resources.livetv_parental_remove_pin
import nuvio.composeapp.generated.resources.livetv_parental_new_pin
import nuvio.composeapp.generated.resources.livetv_parental_current_pin
import nuvio.composeapp.generated.resources.livetv_parental_lock_adult
import nuvio.composeapp.generated.resources.livetv_parental_lock_adult_description
import nuvio.composeapp.generated.resources.livetv_parental_locked_groups
import nuvio.composeapp.generated.resources.livetv_parental_locked_groups_description
import nuvio.composeapp.generated.resources.livetv_parental_locked_count
import nuvio.composeapp.generated.resources.livetv_parental_channel_groups
import nuvio.composeapp.generated.resources.livetv_parental_vod_groups
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.livetv_sports_show
import nuvio.composeapp.generated.resources.livetv_sports_show_description
import nuvio.composeapp.generated.resources.livetv_sports_hide_scores
import nuvio.composeapp.generated.resources.livetv_sports_hide_scores_description
import nuvio.composeapp.generated.resources.livetv_sports_scoring_alerts
import nuvio.composeapp.generated.resources.livetv_sports_scoring_alerts_description
import nuvio.composeapp.generated.resources.livetv_sports_score_chip
import nuvio.composeapp.generated.resources.livetv_sports_score_chip_description
import nuvio.composeapp.generated.resources.livetv_show_tab_description
import nuvio.composeapp.generated.resources.livetv_stream_format
import nuvio.composeapp.generated.resources.livetv_stream_format_description
import nuvio.composeapp.generated.resources.livetv_switch_backup
import nuvio.composeapp.generated.resources.livetv_switch_backup_description
import nuvio.composeapp.generated.resources.livetv_update_all
import nuvio.composeapp.generated.resources.livetv_update_all_description
import nuvio.composeapp.generated.resources.livetv_update_now
import nuvio.composeapp.generated.resources.livetv_updated_at
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload

private val REFRESH_HOURS = listOf(3, 6, 12, 24, 48, 72, 168)
private val FUTURE_DAYS = listOf(1, 2, 3, 5, 7, 10, 14)
private val PAST_HOURS = listOf(1, 3, 6, 12, 24, 48, 72, 168)

private fun pastHoursLabel(h: Int): String = when {
    h == 1 -> "1 hour"
    h < 24 -> "$h hours"
    h == 24 -> "1 day"
    else -> "${h / 24} days"
}
/** Time shifts in minutes, half an hour apart, a day either way (the TV app's choices). */
private val EPG_OFFSETS = (-24..24).map { it * 30 }
private val PADDING_MINUTES = listOf(0, 1, 2, 3, 5, 10, 15, 30)
private val KEEP_COUNTS = listOf(0, 1, 2, 3, 5, 10, 20)
private val REMINDER_LEADS = listOf(0, 1, 2, 5, 10, 15, 30)

/** "+1 h", "−1 h 30 min" or [none] for 0. */
internal fun offsetLabel(min: Int, none: String): String {
    if (min == 0) return none
    val sign = if (min > 0) "+" else "−"
    val a = kotlin.math.abs(min)
    return if (a % 60 == 0) "$sign${a / 60} h" else "$sign${a / 60} h ${a % 60} min"
}

internal fun hoursLabel(h: Int): String = when {
    h >= 24 && h % 24 == 0 -> if (h == 24) "1 day" else "${h / 24} days"
    else -> "$h hours"
}

private sealed interface LiveDialog {
    data object AddM3u : LiveDialog
    data object AddXtream : LiveDialog
    data object AddEpg : LiveDialog
    data class PlaylistActions(val source: PlaylistSource) : LiveDialog
    data class EditPlaylist(val source: PlaylistSource) : LiveDialog
    data class DeletePlaylist(val source: PlaylistSource) : LiveDialog
    data class EpgActions(val source: EpgSource) : LiveDialog
    data class EditEpg(val source: EpgSource) : LiveDialog
    data class DeleteEpg(val source: EpgSource) : LiveDialog
    data object PlaylistRefresh : LiveDialog
    data object EpgRefresh : LiveDialog
    data object FutureDays : LiveDialog
    data object PastHours : LiveDialog
    data object StreamFormat : LiveDialog
    data object CatchupShift : LiveDialog
    data object VodDefaultSort : LiveDialog
    data object RecordPre : LiveDialog
    data object RecordPost : LiveDialog
    data object RecordKeep : LiveDialog
    data object RecordSpaceRule : LiveDialog
    data object ConfirmRestore : LiveDialog
    /** A backup file was picked and read: confirm before it replaces this device's setup. */
    data class ConfirmImportFile(val text: String, val info: BackupFileInfo) : LiveDialog
    data object ConfirmSignOut : LiveDialog
    data object ParentalMenu : LiveDialog
    data object ParentalSetPin : LiveDialog
    /** Asks the current PIN, then [then]. */
    data class ParentalAskPin(val then: LiveDialog?) : LiveDialog
    data object ParentalRemovePin : LiveDialog
    data object ParentalGroups : LiveDialog
    data object Reminders : LiveDialog
    data object ReminderLead : LiveDialog
}

/** The Live TV page in Settings: playlists, guides, watching options, Google Drive backup. */
internal fun LazyListScope.liveTvSettingsContent(isTablet: Boolean) {
    item(key = "livetv_settings") {
        LiveTvSettingsBody(isTablet)
    }
}

@Composable
private fun LiveTvSettingsBody(isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    val playlists by LiveTvPreferences.playlists.collectAsStateWithLifecycle()
    val epgs by LiveTvPreferences.epgSources.collectAsStateWithLifecycle()
    val settings by LiveTvPreferences.settings.collectAsStateWithLifecycle()
    val accounts by LiveTvPreferences.playlistAccounts.collectAsStateWithLifecycle()
    val status by remember { LiveTvRepository.ensureLoaded(); LiveTvRepository.status }.collectAsStateWithLifecycle()
    val drive by remember { LiveTvDriveSync.start(); LiveTvDriveSync.state }.collectAsStateWithLifecycle()
    val vodStatus by remember { OnDemandRepository.start(); OnDemandRepository.status }.collectAsStateWithLifecycle()
    val sports by SportsRepository.settings.collectAsStateWithLifecycle()
    val userState by LiveTvPreferences.userState.collectAsStateWithLifecycle()
    val reminderLead by LiveTvPreferences.reminderLeadMinutes.collectAsStateWithLifecycle()
    val showInContinueWatching by LiveTvPreferences.showInContinueWatching.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<LiveDialog?>(null) }
    val scope = rememberCoroutineScope()
    var exactAlarms by remember { mutableStateOf(RecordingPlatform.canScheduleExact()) }
    var notificationsOn by remember { mutableStateOf(RecordingPlatform.notificationsAllowed()) }
    fun update(transform: (com.nuvio.app.features.livetv.model.LiveTvSettings) -> com.nuvio.app.features.livetv.model.LiveTvSettings) =
        LiveTvPreferences.updateSettings(transform)

    // Backup files: what the last import or export said, and whether one is under way.
    var fileMessage by remember { mutableStateOf<String?>(null) }
    var fileBusy by remember { mutableStateOf(false) }
    val pickImportFile = rememberLiveTvBackupImporter { result ->
        result.fold(
            onSuccess = { text ->
                if (text != null) parseBackupFile(text).fold(
                    onSuccess = { info -> fileMessage = null; dialog = LiveDialog.ConfirmImportFile(text, info) },
                    onFailure = { e -> scope.launch { fileMessage = backupFileErrorText(e, reading = true) } },
                )
            },
            onFailure = { e -> scope.launch { fileMessage = backupFileErrorText(e, reading = true) } },
        )
    }
    val pickExportFile = rememberLiveTvBackupExporter(text = { exportBackupFileText() }) { result ->
        scope.launch {
            fileMessage = result.fold(
                onSuccess = { written -> if (written) getString(Res.string.livetv_export_done) else fileMessage },
                onFailure = { e -> backupFileErrorText(e, reading = false) },
            )
        }
    }
    fun importFile(text: String) {
        fileBusy = true
        fileMessage = null
        scope.launch {
            val result = withContext(Dispatchers.Default) { importBackupFile(text) }
            fileMessage = result.fold(
                onSuccess = { summary ->
                    LiveTvDriveSync.backUpAfterImport()
                    getString(Res.string.livetv_import_done, summary.playlistCount, summary.epgCount)
                },
                onFailure = { e -> backupFileErrorText(e, reading = true) },
            )
            fileBusy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap)) {
        SettingsSection(title = stringResource(Res.string.livetv_section_playlists), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                playlists.forEachIndexed { i, pl ->
                    if (i > 0) SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = pl.name,
                        description = playlistStatus(pl, accounts[pl.id]),
                        icon = if (pl.isXtream) Icons.Filled.Login else Icons.Filled.ListAlt,
                        isTablet = isTablet,
                        onClick = { dialog = LiveDialog.PlaylistActions(pl) },
                    )
                }
                if (playlists.isNotEmpty()) SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_add_m3u),
                    description = stringResource(Res.string.livetv_add_m3u_description),
                    icon = Icons.Filled.Add,
                    isTablet = isTablet,
                    onClick = { dialog = LiveDialog.AddM3u },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_add_xtream),
                    description = stringResource(Res.string.livetv_add_xtream_description),
                    icon = Icons.Filled.Add,
                    isTablet = isTablet,
                    onClick = { dialog = LiveDialog.AddXtream },
                )
            }
        }

        SettingsSection(title = stringResource(Res.string.livetv_section_guides), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                epgs.forEachIndexed { i, src ->
                    if (i > 0) SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = src.name,
                        description = epgStatus(src),
                        icon = Icons.Filled.Schedule,
                        isTablet = isTablet,
                        onClick = { dialog = LiveDialog.EpgActions(src) },
                    )
                }
                if (epgs.isNotEmpty()) SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_add_epg),
                    description = stringResource(Res.string.livetv_add_epg_description),
                    icon = Icons.Filled.Add,
                    isTablet = isTablet,
                    onClick = { dialog = LiveDialog.AddEpg },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_days_ahead),
                    description = stringResource(Res.string.livetv_days_ahead_description),
                    isTablet = isTablet,
                    trailingContent = { ValueLabel(if (settings.epgFutureDays == 1) "1 day" else "${settings.epgFutureDays} days") },
                    onClick = { dialog = LiveDialog.FutureDays },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_hours_back),
                    description = stringResource(Res.string.livetv_hours_back_description),
                    isTablet = isTablet,
                    trailingContent = { ValueLabel(pastHoursLabel(settings.epgPastHours)) },
                    onClick = { dialog = LiveDialog.PastHours },
                )
            }
        }

        SettingsSection(title = stringResource(Res.string.livetv_section_general), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_update_all),
                    description = if (status.loading) status.message else stringResource(Res.string.livetv_update_all_description),
                    isTablet = isTablet,
                    enabled = !status.loading,
                    trailingContent = { if (status.loading) NuvioLoadingIndicator(size = NuvioTokens.Space.s20) },
                    onClick = { LiveTvRepository.refreshAll(force = true) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_playlist_refresh),
                    description = stringResource(Res.string.livetv_playlist_refresh_description),
                    isTablet = isTablet,
                    trailingContent = { ValueLabel(hoursLabel(settings.playlistRefreshHours)) },
                    onClick = { dialog = LiveDialog.PlaylistRefresh },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_epg_refresh),
                    description = stringResource(Res.string.livetv_epg_refresh_description),
                    isTablet = isTablet,
                    trailingContent = { ValueLabel(hoursLabel(settings.epgRefreshHours)) },
                    onClick = { dialog = LiveDialog.EpgRefresh },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_show_tab),
                    description = stringResource(Res.string.livetv_show_tab_description),
                    checked = settings.showInSidebar,
                    isTablet = isTablet,
                    onCheckedChange = { on -> update { it.copy(showInSidebar = on) } },
                )
            }
        }

        SettingsSection(title = stringResource(Res.string.livetv_section_channel_display), isTablet = isTablet) {
            // Turning one off is ignored when it's the last one on, so a channel always shows something.
            val onCount = listOf(settings.phoneChannelLogos, settings.phoneChannelNames, settings.phoneChannelNumbers).count { it }
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_phone_logos),
                    description = stringResource(Res.string.livetv_phone_logos_description),
                    checked = settings.phoneChannelLogos,
                    enabled = !(settings.phoneChannelLogos && onCount == 1),
                    isTablet = isTablet,
                    onCheckedChange = { on -> update { if (!on && !it.phoneChannelNames && !it.phoneChannelNumbers) it else it.copy(phoneChannelLogos = on) } },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_phone_names),
                    description = stringResource(Res.string.livetv_phone_names_description),
                    checked = settings.phoneChannelNames,
                    enabled = !(settings.phoneChannelNames && onCount == 1),
                    isTablet = isTablet,
                    onCheckedChange = { on -> update { if (!on && !it.phoneChannelLogos && !it.phoneChannelNumbers) it else it.copy(phoneChannelNames = on) } },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_phone_numbers),
                    description = stringResource(Res.string.livetv_phone_numbers_description),
                    checked = settings.phoneChannelNumbers,
                    enabled = !(settings.phoneChannelNumbers && onCount == 1),
                    isTablet = isTablet,
                    onCheckedChange = { on -> update { if (!on && !it.phoneChannelLogos && !it.phoneChannelNames) it else it.copy(phoneChannelNumbers = on) } },
                )
            }
        }

        SettingsSection(title = stringResource(Res.string.livetv_section_watching), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_stream_format),
                    description = stringResource(Res.string.livetv_stream_format_description),
                    isTablet = isTablet,
                    trailingContent = { ValueLabel(streamFormatLabel(playlists.firstOrNull { it.isXtream }?.streamFormat ?: "auto")) },
                    onClick = { dialog = LiveDialog.StreamFormat },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_switch_backup),
                    description = stringResource(Res.string.livetv_switch_backup_description),
                    checked = settings.autoBackupChannel,
                    isTablet = isTablet,
                    onCheckedChange = { on -> update { it.copy(autoBackupChannel = on) } },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_continue_watching),
                    description = stringResource(Res.string.livetv_continue_watching_description),
                    checked = showInContinueWatching,
                    isTablet = isTablet,
                    onCheckedChange = { on -> LiveTvPreferences.setShowInContinueWatching(on) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_remember_audio),
                    description = stringResource(Res.string.livetv_remember_audio_description),
                    checked = true,
                    enabled = false,
                    isTablet = isTablet,
                    onCheckedChange = {},
                )
            }
        }

        SettingsSection(title = stringResource(Res.string.livetv_section_catchup), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_catchup_prefer_hls),
                    description = stringResource(Res.string.livetv_catchup_prefer_hls_description),
                    checked = settings.catchupPreferHls,
                    isTablet = isTablet,
                    onCheckedChange = { on -> update { it.copy(catchupPreferHls = on) } },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_catchup_shift),
                    description = stringResource(Res.string.livetv_catchup_shift_description),
                    isTablet = isTablet,
                    trailingContent = { ValueLabel(offsetLabel(settings.catchupShiftMinutes, stringResource(Res.string.livetv_catchup_shift_none))) },
                    onClick = { dialog = LiveDialog.CatchupShift },
                )
            }
        }

        SettingsSection(title = stringResource(Res.string.livetv_section_on_demand), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                playlists.forEachIndexed { i, pl ->
                    if (i > 0) SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.livetv_vod_import) + " · " + pl.name,
                        description = stringResource(Res.string.livetv_vod_import_description),
                        checked = pl.importVod,
                        enabled = pl.enabled,
                        isTablet = isTablet,
                        onCheckedChange = { on -> LiveTvPreferences.updatePlaylists { l -> l.map { if (it.id == pl.id) it.copy(importVod = on) else it } } },
                    )
                }
                if (playlists.isNotEmpty()) SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_vod_update_now),
                    description = vodStatus.message ?: stringResource(Res.string.livetv_vod_update_now_description),
                    isTablet = isTablet,
                    enabled = !vodStatus.loading && playlists.any { it.enabled && it.importVod },
                    trailingContent = { if (vodStatus.loading) NuvioLoadingIndicator(size = NuvioTokens.Space.s20) },
                    onClick = { OnDemandRepository.refreshNow() },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_vod_default_sort),
                    description = stringResource(Res.string.livetv_vod_default_sort_description),
                    isTablet = isTablet,
                    trailingContent = { ValueLabel((VodSort.parse(settings.vodDefaultSort) ?: VodSort.DEFAULT).label) },
                    onClick = { dialog = LiveDialog.VodDefaultSort },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_vod_merge_duplicates),
                    description = stringResource(Res.string.livetv_vod_merge_duplicates_description),
                    checked = settings.vodMergeDuplicates,
                    isTablet = isTablet,
                    onCheckedChange = { on -> update { it.copy(vodMergeDuplicates = on) } },
                )
            }
        }

        SettingsSection(title = stringResource(Res.string.livetv_sports), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_sports_show),
                    description = stringResource(Res.string.livetv_sports_show_description),
                    checked = sports.showInSidebar,
                    isTablet = isTablet,
                    onCheckedChange = { on -> SportsRepository.updateSettings { it.copy(showInSidebar = on) } },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_sports_hide_scores),
                    description = stringResource(Res.string.livetv_sports_hide_scores_description),
                    checked = sports.hideScores,
                    isTablet = isTablet,
                    onCheckedChange = { on -> SportsRepository.updateSettings { it.copy(hideScores = on) } },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_sports_score_chip),
                    description = stringResource(Res.string.livetv_sports_score_chip_description),
                    checked = sports.scoreOnInfoBar,
                    isTablet = isTablet,
                    onCheckedChange = { on -> SportsRepository.updateSettings { it.copy(scoreOnInfoBar = on) } },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_sports_scoring_alerts),
                    description = stringResource(Res.string.livetv_sports_scoring_alerts_description),
                    checked = sports.scoringAlerts,
                    isTablet = isTablet,
                    onCheckedChange = { on -> SportsRepository.updateSettings { it.copy(scoringAlerts = on) } },
                )
            }
        }

        SettingsSection(title = stringResource(Res.string.livetv_parental), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                val pinSet = ParentalControls.enabled(settings)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_parental_pin),
                    description = stringResource(Res.string.livetv_parental_pin_description),
                    icon = Icons.Filled.Lock,
                    isTablet = isTablet,
                    trailingContent = { ValueLabel(stringResource(if (pinSet) Res.string.livetv_parental_pin_set else Res.string.livetv_parental_pin_not_set)) },
                    onClick = { dialog = if (pinSet) LiveDialog.ParentalMenu else LiveDialog.ParentalSetPin },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.livetv_parental_lock_adult),
                    description = stringResource(Res.string.livetv_parental_lock_adult_description),
                    checked = settings.lockAdultContent,
                    enabled = pinSet,
                    isTablet = isTablet,
                    onCheckedChange = { on -> update { it.copy(lockAdultContent = on) }; ParentalControls.relock() },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_parental_locked_groups),
                    description = stringResource(Res.string.livetv_parental_locked_groups_description),
                    isTablet = isTablet,
                    enabled = pinSet,
                    trailingContent = { ValueLabel(stringResource(Res.string.livetv_parental_locked_count, userState.lockedGroups.size)) },
                    onClick = { dialog = LiveDialog.ParentalAskPin(LiveDialog.ParentalGroups) },
                )
            }
        }

        SettingsSection(title = stringResource(Res.string.livetv_section_reminders), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_reminders_list),
                    description = stringResource(Res.string.livetv_reminders_list_description),
                    icon = Icons.Filled.Notifications,
                    isTablet = isTablet,
                    trailingContent = { ValueLabel(userState.reminders.size.toString()) },
                    onClick = { dialog = LiveDialog.Reminders },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_reminder_lead),
                    description = stringResource(Res.string.livetv_reminder_lead_description),
                    isTablet = isTablet,
                    trailingContent = { ValueLabel(leadLabel(reminderLead)) },
                    onClick = { dialog = LiveDialog.ReminderLead },
                )
            }
        }

        SettingsSection(title = stringResource(Res.string.livetv_section_recordings), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_rec_padding_pre),
                    description = stringResource(Res.string.livetv_rec_padding_pre_description),
                    isTablet = isTablet,
                    trailingContent = { ValueLabel(minutesLabel(settings.recordPreMinutes)) },
                    onClick = { dialog = LiveDialog.RecordPre },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_rec_padding_post),
                    description = stringResource(Res.string.livetv_rec_padding_post_description),
                    isTablet = isTablet,
                    trailingContent = { ValueLabel(minutesLabel(settings.recordPostMinutes)) },
                    onClick = { dialog = LiveDialog.RecordPost },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_rec_series_keep),
                    description = stringResource(Res.string.livetv_rec_series_keep_description),
                    isTablet = isTablet,
                    trailingContent = { ValueLabel(if (settings.recordSeriesKeep > 0) stringResource(Res.string.livetv_rec_keep_last, settings.recordSeriesKeep) else stringResource(Res.string.livetv_rec_keep_all)) },
                    onClick = { dialog = LiveDialog.RecordKeep },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_rec_storage),
                    description = stringResource(Res.string.livetv_rec_storage_description),
                    isTablet = isTablet,
                    trailingContent = { ValueLabel(stringResource(Res.string.livetv_rec_this_device) + " · " + RecordingRules.sizeLabel(LiveTvRecorder.freeBytes())) },
                    onClick = {},
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_rec_space_rule),
                    description = stringResource(Res.string.livetv_rec_space_rule_description),
                    isTablet = isTablet,
                    trailingContent = { ValueLabel(stringResource(if (settings.recordingSpaceRule == "DELETE_OLDEST") Res.string.livetv_rec_space_rule_delete else Res.string.livetv_rec_space_rule_stop)) },
                    onClick = { dialog = LiveDialog.RecordSpaceRule },
                )
                if (!exactAlarms) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.livetv_rec_exact_alarms),
                        description = stringResource(Res.string.livetv_rec_exact_alarms_description),
                        isTablet = isTablet,
                        onClick = { RecordingPlatform.requestExactAlarms(); exactAlarms = RecordingPlatform.canScheduleExact() },
                    )
                }
                if (!notificationsOn) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.livetv_rec_notifications),
                        description = stringResource(Res.string.livetv_rec_notifications_description),
                        isTablet = isTablet,
                        onClick = { scope.launch { notificationsOn = EpisodeReleaseNotificationPlatform.requestAuthorization() } },
                    )
                }
            }
        }

        SettingsSection(title = stringResource(Res.string.livetv_section_backup), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                Text(
                    text = stringResource(Res.string.livetv_backup_file_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                    modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_import_file),
                    description = stringResource(Res.string.livetv_import_file_description),
                    icon = Icons.Filled.FileDownload,
                    isTablet = isTablet,
                    enabled = !fileBusy && !drive.busy,
                    trailingContent = { if (fileBusy) NuvioLoadingIndicator(size = NuvioTokens.Space.s20) },
                    onClick = { pickImportFile() },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.livetv_export_file),
                    description = stringResource(Res.string.livetv_export_file_description),
                    icon = Icons.Filled.FileUpload,
                    isTablet = isTablet,
                    enabled = !fileBusy,
                    onClick = { pickExportFile() },
                )
                fileMessage?.let {
                    SettingsGroupDivider(isTablet = isTablet)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, modifier = Modifier.padding(NuvioTokens.Space.s16))
                }
            }
            NuvioSectionLabel(
                text = stringResource(Res.string.livetv_drive_subsection),
                modifier = Modifier.padding(horizontal = NuvioTokens.Radius.card).padding(top = NuvioTokens.Space.s16, bottom = NuvioTokens.Space.s10),
            )
            SettingsGroup(isTablet = isTablet) {
                when {
                    !drive.available -> Text(
                        text = stringResource(Res.string.livetv_drive_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                        modifier = Modifier.padding(NuvioTokens.Space.s16),
                    )
                    !drive.connected -> {
                        Text(
                            text = stringResource(Res.string.livetv_drive_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textMuted,
                            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.livetv_drive_sign_in),
                            description = stringResource(Res.string.livetv_drive_sign_in_description),
                            icon = Icons.Filled.Backup,
                            isTablet = isTablet,
                            enabled = !drive.busy,
                            onClick = { LiveTvDriveSync.beginSignIn() },
                        )
                    }
                    else -> {
                        SettingsNavigationRow(
                            title = stringResource(Res.string.livetv_drive_account),
                            description = (drive.email ?: "Connected") + (drive.lastSyncMs.takeIf { it > 0 }?.let { " · " + formatLocalDateTime(it) } ?: ""),
                            icon = Icons.Filled.Backup,
                            isTablet = isTablet,
                            onClick = {},
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(Res.string.livetv_drive_auto_sync),
                            description = stringResource(Res.string.livetv_drive_auto_sync_description),
                            checked = drive.autoSync,
                            isTablet = isTablet,
                            onCheckedChange = LiveTvDriveSync::setAutoSync,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.livetv_backup_now),
                            description = stringResource(Res.string.livetv_backup_now_description),
                            isTablet = isTablet,
                            enabled = !drive.busy,
                            trailingContent = { if (drive.busy) NuvioLoadingIndicator(size = NuvioTokens.Space.s20) },
                            onClick = { LiveTvDriveSync.backUpNow() },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.livetv_restore),
                            description = stringResource(Res.string.livetv_restore_description),
                            isTablet = isTablet,
                            enabled = !drive.busy,
                            onClick = { dialog = LiveDialog.ConfirmRestore },
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = stringResource(Res.string.livetv_drive_sign_out),
                            description = stringResource(Res.string.livetv_drive_sign_out_description),
                            isTablet = isTablet,
                            onClick = { dialog = LiveDialog.ConfirmSignOut },
                        )
                    }
                }
                drive.message?.let {
                    SettingsGroupDivider(isTablet = isTablet)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary, modifier = Modifier.padding(NuvioTokens.Space.s16))
                }
            }
            LiveTvCloudSyncSection(isTablet = isTablet)
        }
    }

    drive.signIn?.let { prompt -> DriveSignInDialog(prompt, onCancel = { LiveTvDriveSync.cancelSignIn() }) }

    val close = { dialog = null }
    when (val d = dialog) {
        null -> Unit
        LiveDialog.AddM3u -> M3uForm(null, onDismiss = close)
        LiveDialog.AddXtream -> XtreamForm(null, onDismiss = close)
        LiveDialog.AddEpg -> EpgForm(null, onDismiss = close)
        is LiveDialog.EditPlaylist -> if (d.source.isXtream) XtreamForm(d.source, onDismiss = close) else M3uForm(d.source, onDismiss = close)
        is LiveDialog.EditEpg -> EpgForm(d.source, onDismiss = close)
        is LiveDialog.PlaylistActions -> ActionsDialog(
            title = d.source.name,
            subtitle = playlistStatus(d.source, accounts[d.source.id]),
            onDismiss = close,
            actions = listOf(
                stringResource(Res.string.action_edit) to { dialog = LiveDialog.EditPlaylist(d.source) },
                stringResource(Res.string.livetv_update_now) to { LiveTvRepository.updatePlaylistNow(d.source.id); close() },
                stringResource(if (d.source.enabled) Res.string.livetv_disable else Res.string.livetv_enable) to {
                    LiveTvRepository.setPlaylistEnabled(d.source.id, !d.source.enabled); close()
                },
                stringResource(Res.string.action_delete) to { dialog = LiveDialog.DeletePlaylist(d.source) },
            ),
        )
        is LiveDialog.EpgActions -> ActionsDialog(
            title = d.source.name,
            subtitle = epgStatus(d.source),
            onDismiss = close,
            actions = listOf(
                stringResource(Res.string.action_edit) to { dialog = LiveDialog.EditEpg(d.source) },
                stringResource(Res.string.livetv_update_now) to { LiveTvRepository.updateEpg(d.source); close() },
                stringResource(if (d.source.enabled) Res.string.livetv_disable else Res.string.livetv_enable) to {
                    LiveTvRepository.setEpgEnabled(d.source.id, !d.source.enabled); close()
                },
                stringResource(Res.string.action_delete) to { dialog = LiveDialog.DeleteEpg(d.source) },
            ),
        )
        is LiveDialog.DeletePlaylist -> ConfirmDialog(
            title = stringResource(Res.string.action_delete) + " \"${d.source.name}\"?",
            message = stringResource(Res.string.livetv_delete_source_message),
            confirm = stringResource(Res.string.action_delete),
            destructive = true,
            onDismiss = close,
        ) { LiveTvRepository.removePlaylist(d.source.id) }
        is LiveDialog.DeleteEpg -> ConfirmDialog(
            title = stringResource(Res.string.action_delete) + " \"${d.source.name}\"?",
            message = stringResource(Res.string.livetv_delete_source_message),
            confirm = stringResource(Res.string.action_delete),
            destructive = true,
            onDismiss = close,
        ) { LiveTvRepository.removeEpg(d.source.id) }
        LiveDialog.PlaylistRefresh -> ChoiceDialog(
            title = stringResource(Res.string.livetv_playlist_refresh),
            options = REFRESH_HOURS.map { it to hoursLabel(it) },
            selected = settings.playlistRefreshHours,
            onDismiss = close,
        ) { v -> update { it.copy(playlistRefreshHours = v) } }
        LiveDialog.EpgRefresh -> ChoiceDialog(
            title = stringResource(Res.string.livetv_epg_refresh),
            options = REFRESH_HOURS.map { it to hoursLabel(it) },
            selected = settings.epgRefreshHours,
            onDismiss = close,
        ) { v -> update { it.copy(epgRefreshHours = v) } }
        LiveDialog.FutureDays -> ChoiceDialog(
            title = stringResource(Res.string.livetv_days_ahead),
            options = FUTURE_DAYS.map { it to if (it == 1) "1 day" else "$it days" },
            selected = settings.epgFutureDays,
            onDismiss = close,
        ) { v -> update { it.copy(epgFutureDays = v) }; LiveTvRepository.refreshEpgOnly() }
        LiveDialog.PastHours -> ChoiceDialog(
            title = stringResource(Res.string.livetv_hours_back),
            options = (PAST_HOURS + settings.epgPastHours).distinct().sorted().map { it to pastHoursLabel(it) },
            selected = settings.epgPastHours,
            onDismiss = close,
        ) { v -> update { it.copy(epgPastHours = v) }; LiveTvRepository.refreshEpgOnly() }
        LiveDialog.StreamFormat -> ChoiceDialog(
            title = stringResource(Res.string.livetv_stream_format),
            options = listOf("auto" to "Auto", "ts" to "MPEG-TS", "m3u8" to "HLS (m3u8)"),
            selected = playlists.firstOrNull { it.isXtream }?.streamFormat ?: "auto",
            onDismiss = close,
        ) { v ->
            playlists.filter { it.isXtream && it.streamFormat != v }.forEach { LiveTvRepository.updatePlaylist(it.copy(streamFormat = v)) }
        }
        LiveDialog.CatchupShift -> {
            val none = stringResource(Res.string.livetv_catchup_shift_none)
            ChoiceDialog(
                title = stringResource(Res.string.livetv_catchup_shift),
                options = EPG_OFFSETS.map { it to offsetLabel(it, none) },
                selected = settings.catchupShiftMinutes,
                onDismiss = close,
            ) { v -> update { it.copy(catchupShiftMinutes = v) } }
        }
        LiveDialog.VodDefaultSort -> ChoiceDialog(
            title = stringResource(Res.string.livetv_vod_default_sort),
            options = VodSort.entries.map { it to it.label },
            selected = VodSort.parse(settings.vodDefaultSort) ?: VodSort.DEFAULT,
            onDismiss = close,
        ) { v -> update { it.copy(vodDefaultSort = v.name) } }
        LiveDialog.RecordPre -> ChoiceDialog(
            title = stringResource(Res.string.livetv_rec_padding_pre),
            options = PADDING_MINUTES.map { it to minutesLabel(it) },
            selected = settings.recordPreMinutes,
            onDismiss = close,
        ) { v -> update { it.copy(recordPreMinutes = v) } }
        LiveDialog.RecordPost -> ChoiceDialog(
            title = stringResource(Res.string.livetv_rec_padding_post),
            options = PADDING_MINUTES.map { it to minutesLabel(it) },
            selected = settings.recordPostMinutes,
            onDismiss = close,
        ) { v -> update { it.copy(recordPostMinutes = v) } }
        LiveDialog.RecordKeep -> {
            val all = stringResource(Res.string.livetv_rec_keep_all)
            ChoiceDialog(
                title = stringResource(Res.string.livetv_rec_series_keep),
                options = KEEP_COUNTS.map { it to if (it == 0) all else it.toString() },
                selected = settings.recordSeriesKeep,
                onDismiss = close,
            ) { v -> update { it.copy(recordSeriesKeep = v) } }
        }
        LiveDialog.RecordSpaceRule -> ChoiceDialog(
            title = stringResource(Res.string.livetv_rec_space_rule),
            options = listOf("STOP" to stringResource(Res.string.livetv_rec_space_rule_stop), "DELETE_OLDEST" to stringResource(Res.string.livetv_rec_space_rule_delete)),
            selected = settings.recordingSpaceRule,
            onDismiss = close,
        ) { v -> update { it.copy(recordingSpaceRule = v) } }
        LiveDialog.ConfirmRestore -> ConfirmDialog(
            title = stringResource(Res.string.livetv_restore) + "?",
            message = stringResource(Res.string.livetv_restore_message),
            confirm = stringResource(Res.string.livetv_restore),
            onDismiss = close,
        ) { LiveTvDriveSync.restoreNow() }
        is LiveDialog.ConfirmImportFile -> ConfirmDialog(
            title = stringResource(Res.string.livetv_import_confirm_title),
            message = stringResource(
                Res.string.livetv_import_confirm_message,
                d.info.device.ifBlank { stringResource(Res.string.livetv_import_unknown_device) },
                if (d.info.savedAtMs > 0) formatLocalDateTime(d.info.savedAtMs) else stringResource(Res.string.livetv_import_unknown_date),
                d.info.playlistCount,
                d.info.epgCount,
            ),
            confirm = stringResource(Res.string.livetv_import_confirm_action),
            onDismiss = close,
        ) { importFile(d.text) }
        LiveDialog.ConfirmSignOut -> ConfirmDialog(
            title = stringResource(Res.string.livetv_drive_sign_out) + "?",
            message = stringResource(Res.string.livetv_drive_sign_out_description),
            confirm = stringResource(Res.string.livetv_drive_sign_out),
            onDismiss = close,
        ) { LiveTvDriveSync.disconnect() }
        LiveDialog.ParentalMenu -> ActionsDialog(
            title = stringResource(Res.string.livetv_parental_pin),
            subtitle = stringResource(Res.string.livetv_parental_pin_set),
            onDismiss = close,
            actions = listOf(
                stringResource(Res.string.livetv_parental_change_pin) to { dialog = LiveDialog.ParentalAskPin(LiveDialog.ParentalSetPin) },
                stringResource(Res.string.livetv_parental_remove_pin) to { dialog = LiveDialog.ParentalAskPin(LiveDialog.ParentalRemovePin) },
            ),
        )
        is LiveDialog.ParentalAskPin -> ParentalPinDialog(
            title = stringResource(Res.string.livetv_parental_current_pin),
            // A right PIN moves on to the next step; only a cancel closes everything.
            onDismiss = { if (dialog is LiveDialog.ParentalAskPin) dialog = null },
            confirmLabel = stringResource(Res.string.action_save),
            onPin = { pin -> ParentalControls.checkPin(pin, settings).also { ok -> if (ok) dialog = d.then } },
        )
        LiveDialog.ParentalSetPin -> ParentalPinDialog(
            title = stringResource(Res.string.livetv_parental_new_pin),
            message = stringResource(Res.string.livetv_parental_pin_description),
            onDismiss = close,
            confirmLabel = stringResource(Res.string.action_save),
            onPin = { pin -> ParentalControls.setPin(pin); true },
        )
        LiveDialog.ParentalRemovePin -> LaunchedEffect(Unit) { ParentalControls.setPin(""); close() }
        LiveDialog.ParentalGroups -> LockedGroupsDialog(onDismiss = close)
        LiveDialog.Reminders -> DialogSurface(onDismissRequest = close, title = stringResource(Res.string.livetv_reminders_list), message = stringResource(Res.string.livetv_reminders_list_description)) {
            if (userState.reminders.isEmpty()) {
                Text(stringResource(Res.string.livetv_reminders_none), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
                items(userState.reminders, key = { it.channelKey + "|" + it.startMs }) { r ->
                    DialogOption(
                        text = r.title,
                        description = r.channelName + " · " + formatLocalDateTime(r.startMs),
                        selected = true,
                        onClick = { LiveTvPreferences.removeReminder(r.channelKey, r.startMs) },
                    )
                }
            }
            DialogButtons { DialogButton(text = stringResource(Res.string.action_close), style = DialogButtonStyle.Primary, onClick = close) }
        }
        LiveDialog.ReminderLead -> ChoiceDialog(
            title = stringResource(Res.string.livetv_reminder_lead),
            options = REMINDER_LEADS.map { it to leadLabel(it) },
            selected = reminderLead,
            onDismiss = close,
        ) { v -> LiveTvPreferences.setReminderLeadMinutes(v) }
    }
}

@Composable
private fun ValueLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.accent)
}

private fun minutesLabel(m: Int) = if (m == 0) "None" else if (m == 1) "1 minute" else "$m minutes"

@Composable
private fun leadLabel(m: Int): String = if (m == 0) stringResource(Res.string.livetv_reminder_lead_at_start) else stringResource(Res.string.livetv_reminder_lead_minutes, m)

private fun streamFormatLabel(f: String) = when (f) { "ts" -> "MPEG-TS"; "m3u8" -> "HLS (m3u8)"; else -> "Auto" }

@Composable
private fun playlistStatus(pl: PlaylistSource, account: PlaylistAccount?): String {
    val parts = ArrayList<String>()
    if (!pl.enabled) parts += "Off"
    if (pl.lastError != null) parts += pl.lastError
    else if (pl.lastUpdatedMs > 0) parts += stringResource(Res.string.livetv_updated_at, formatLocalDateTime(pl.lastUpdatedMs))
    else parts += stringResource(Res.string.livetv_never_updated)
    if (pl.channelCount > 0) parts += "${pl.channelCount} channels"
    account?.let { a ->
        a.daysLeftLabel()?.let { parts += if (it == "expired") "Expired" else "Expires in $it" }
        if (a.status.isNotBlank() && !a.status.equals("Active", true)) parts += a.status
        if (a.maxConnections > 0) parts += "${a.activeConnections}/${a.maxConnections} connections"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun epgStatus(src: EpgSource): String {
    val parts = ArrayList<String>()
    if (!src.enabled) parts += "Off"
    if (src.lastError != null) parts += src.lastError
    else if (src.lastUpdatedMs > 0) parts += stringResource(Res.string.livetv_updated_at, formatLocalDateTime(src.lastUpdatedMs))
    else parts += stringResource(Res.string.livetv_never_updated)
    return parts.joinToString(" · ")
}

// ---------------------------------------------------------------- dialogs

@Composable
private fun ActionsDialog(title: String, subtitle: String?, actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    DialogSurface(onDismissRequest = onDismiss, title = title, message = subtitle) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
            actions.forEach { (label, act) -> DialogOption(text = label, selected = false, onClick = act) }
        }
        DialogButtons { DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss) }
    }
}

@Composable
private fun ConfirmDialog(title: String, message: String, confirm: String, destructive: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    DialogSurface(onDismissRequest = onDismiss, title = title, message = message) {
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(text = confirm, style = if (destructive) DialogButtonStyle.Destructive else DialogButtonStyle.Primary, onClick = { onConfirm(); onDismiss() })
        }
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    DialogSurface(onDismissRequest = onDismiss, title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
            options.forEach { (v, label) -> DialogOption(text = label, selected = v == selected, onClick = { onPick(v); onDismiss() }) }
        }
        DialogButtons { DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss) }
    }
}

@Composable
private fun M3uForm(existing: PlaylistSource?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var ua by remember { mutableStateOf(existing?.userAgent.orEmpty()) }
    var embedded by remember { mutableStateOf(existing?.useEmbeddedEpg ?: true) }
    DialogSurface(onDismissRequest = onDismiss, title = stringResource(if (existing == null) Res.string.livetv_add_m3u else Res.string.action_edit)) {
        NuvioInputField(value = name, onValueChange = { name = it }, placeholder = stringResource(Res.string.livetv_field_name))
        NuvioInputField(value = url, onValueChange = { url = it }, placeholder = stringResource(Res.string.livetv_field_url))
        NuvioInputField(value = ua, onValueChange = { ua = it }, placeholder = stringResource(Res.string.livetv_field_user_agent))
        SettingsSwitchRow(title = stringResource(Res.string.livetv_load_embedded_epg), checked = embedded, isTablet = false, horizontalPadding = 0.dp, onCheckedChange = { embedded = it })
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.action_save),
                style = DialogButtonStyle.Primary,
                enabled = url.isNotBlank(),
                onClick = {
                    if (existing == null) LiveTvRepository.addPlaylist(name, url, ua)
                    else LiveTvRepository.updatePlaylist(existing.copy(name = name.ifBlank { "Playlist" }, url = url.trim(), userAgent = ua.trim(), useEmbeddedEpg = embedded))
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun XtreamForm(existing: PlaylistSource?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var server by remember { mutableStateOf(existing?.xtreamServer.orEmpty()) }
    var user by remember { mutableStateOf(existing?.xtreamUsername.orEmpty()) }
    var pass by remember { mutableStateOf(existing?.xtreamPassword.orEmpty()) }
    var ua by remember { mutableStateOf(existing?.userAgent.orEmpty()) }
    var embedded by remember { mutableStateOf(existing?.useEmbeddedEpg ?: true) }
    DialogSurface(onDismissRequest = onDismiss, title = stringResource(if (existing == null) Res.string.livetv_add_xtream else Res.string.action_edit)) {
        NuvioInputField(value = name, onValueChange = { name = it }, placeholder = stringResource(Res.string.livetv_field_name))
        NuvioInputField(value = server, onValueChange = { server = it }, placeholder = stringResource(Res.string.livetv_field_server))
        NuvioInputField(value = user, onValueChange = { user = it }, placeholder = stringResource(Res.string.livetv_field_username))
        NuvioInputField(value = pass, onValueChange = { pass = it }, placeholder = stringResource(Res.string.livetv_field_password))
        NuvioInputField(value = ua, onValueChange = { ua = it }, placeholder = stringResource(Res.string.livetv_field_user_agent))
        SettingsSwitchRow(title = stringResource(Res.string.livetv_load_embedded_epg), checked = embedded, isTablet = false, horizontalPadding = 0.dp, onCheckedChange = { embedded = it })
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.action_save),
                style = DialogButtonStyle.Primary,
                enabled = server.isNotBlank() && (PlaylistSource.credentialsFromLink(server) != null || (user.isNotBlank() && pass.isNotBlank())),
                onClick = {
                    if (existing == null) LiveTvRepository.addXtream(name, server, user, pass)
                    else LiveTvRepository.updatePlaylist(
                        existing.copy(name = name.ifBlank { "Xtream" }, xtreamServer = server, xtreamUsername = user, xtreamPassword = pass, userAgent = ua.trim(), useEmbeddedEpg = embedded)
                    )
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun EpgForm(existing: EpgSource?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    DialogSurface(onDismissRequest = onDismiss, title = stringResource(if (existing == null) Res.string.livetv_add_epg else Res.string.action_edit)) {
        NuvioInputField(value = name, onValueChange = { name = it }, placeholder = stringResource(Res.string.livetv_field_name))
        NuvioInputField(value = url, onValueChange = { url = it }, placeholder = stringResource(Res.string.livetv_field_xmltv_url))
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.action_save),
                style = DialogButtonStyle.Primary,
                enabled = url.isNotBlank(),
                onClick = {
                    if (existing == null) LiveTvRepository.addEpg(name, url)
                    else LiveTvRepository.updateEpg(existing.copy(name = name.ifBlank { "EPG" }, url = url.trim()))
                    onDismiss()
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DriveSignInDialog(prompt: SignInPrompt, onCancel: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val shortUrl = prompt.url.removePrefix("https://").removePrefix("http://")
    DialogSurface(onDismissRequest = onCancel, title = stringResource(Res.string.livetv_drive_sign_in)) {
        Text(
            text = stringResource(Res.string.livetv_drive_code_instructions, shortUrl),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = prompt.userCode,
            style = MaterialTheme.typography.displaySmall,
            color = tokens.colors.accent,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8, Alignment.CenterHorizontally)) {
            Chip(label = shortUrl, selected = true, onClick = { runCatching { uriHandler.openUri(prompt.qrUrl) } })
        }
        Text(
            text = stringResource(Res.string.livetv_drive_code_waiting),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        DialogButtons { DialogButton(text = stringResource(Res.string.action_cancel), onClick = onCancel) }
    }
}

/** Which channel groups and On Demand categories need the PIN. */
@Composable
private fun LockedGroupsDialog(onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val state by LiveTvViewState.list.collectAsStateWithLifecycle()
    val user by LiveTvPreferences.userState.collectAsStateWithLifecycle()
    val movies by produceState(emptyList<VodCategory>()) { value = runCatching { OnDemandRepository.categories(VodKind.MOVIE) }.getOrDefault(emptyList()) }
    val series by produceState(emptyList<VodCategory>()) { value = runCatching { OnDemandRepository.categories(VodKind.SERIES) }.getOrDefault(emptyList()) }
    val groups = remember(state) { state.sections.map { it.group }.filter { !it.special && it.id != ChannelGroup.ALL } }
    DialogSurface(onDismissRequest = onDismiss, title = stringResource(Res.string.livetv_parental_locked_groups), message = stringResource(Res.string.livetv_parental_locked_groups_description)) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
            item { Text(stringResource(Res.string.livetv_parental_channel_groups), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted) }
            items(groups, key = { "g:" + it.id }) { g ->
                val locked = g.id in user.lockedGroups
                DialogOption(text = g.title, selected = locked, onClick = { LiveTvPreferences.setGroupLocked(g.id, !locked); if (locked) ParentalControls.relock() })
            }
            val vod = movies + series
            if (vod.isNotEmpty()) item { Text(stringResource(Res.string.livetv_parental_vod_groups), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted, modifier = Modifier.padding(top = NuvioTokens.Space.s8)) }
            items(vod, key = { "v:" + it.uid }) { c ->
                val id = "vod:" + c.uid
                val locked = id in user.lockedGroups
                DialogOption(text = c.name, description = if (c.kind == VodKind.MOVIE) "Movies" else "Series", selected = locked, onClick = { LiveTvPreferences.setGroupLocked(id, !locked); if (locked) ParentalControls.relock() })
            }
        }
        DialogButtons { DialogButton(text = stringResource(Res.string.action_close), style = DialogButtonStyle.Primary, onClick = onDismiss) }
    }
}

/** A backup-file failure in plain words. [reading]: the file was being read or imported, not written. */
private suspend fun backupFileErrorText(e: Throwable, reading: Boolean): String = when {
    e is BackupFilePickerUnavailable -> getString(Res.string.livetv_file_error_unavailable)
    e is BackupFileException -> when (e.reason) {
        BackupFileException.Reason.NOT_JSON, BackupFileException.Reason.NOT_BACKUP -> getString(Res.string.livetv_file_error_not_backup)
        BackupFileException.Reason.NEWER_FORMAT -> getString(Res.string.livetv_file_error_newer)
        BackupFileException.Reason.NO_SOURCES -> getString(Res.string.livetv_file_error_no_sources)
    }
    !reading -> getString(Res.string.livetv_file_error_write)
    e is IllegalArgumentException || e is IllegalStateException -> e.message?.takeIf { it.isNotBlank() }
        ?.let { getString(Res.string.livetv_file_error_import, it) } ?: getString(Res.string.livetv_file_error_read)
    else -> getString(Res.string.livetv_file_error_read)
}
