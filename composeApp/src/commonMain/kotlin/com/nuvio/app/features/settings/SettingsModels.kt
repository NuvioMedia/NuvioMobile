package com.nuvio.app.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource

internal enum class SettingsCategory(
    val labelRes: StringResource,
    val icon: ImageVector,
) {
    You(Res.string.settings_group_you, Icons.Default.Person),
    Look(Res.string.settings_group_look, Icons.Default.Palette),
    Watch(Res.string.settings_group_watch, Icons.Rounded.PlayArrow),
    Services(Res.string.settings_group_services, Icons.Default.Link),
    System(Res.string.settings_group_system, Icons.Rounded.Settings),
}

internal enum class SettingsPage(
    val titleRes: StringResource,
    val category: SettingsCategory,
    val parentPage: SettingsPage?,
) {
    Root(
        titleRes = Res.string.compose_settings_page_root,
        category = SettingsCategory.Look,
        parentPage = null,
    ),
    Layout(
        titleRes = Res.string.compose_settings_page_layout,
        category = SettingsCategory.Look,
        parentPage = Root,
    ),
    About(
        titleRes = Res.string.compose_settings_category_about,
        category = SettingsCategory.System,
        parentPage = Root,
    ),
    Account(
        titleRes = Res.string.compose_settings_page_account,
        category = SettingsCategory.You,
        parentPage = Root,
    ),
    SupportersContributors(
        titleRes = Res.string.compose_settings_page_supporters_contributors,
        category = SettingsCategory.System,
        parentPage = About,
    ),
    LicensesAttributions(
        titleRes = Res.string.compose_settings_page_licenses_attributions,
        category = SettingsCategory.System,
        parentPage = About,
    ),
    Playback(
        titleRes = Res.string.compose_settings_page_playback,
        category = SettingsCategory.Watch,
        parentPage = Root,
    ),
    PlaybackPlayer(
        titleRes = Res.string.settings_playback_page_player,
        category = SettingsCategory.Watch,
        parentPage = Playback,
    ),
    PlaybackStreamSelection(
        titleRes = Res.string.settings_playback_page_stream_selection,
        category = SettingsCategory.Watch,
        parentPage = Playback,
    ),
    PlaybackUpNext(
        titleRes = Res.string.settings_playback_page_up_next,
        category = SettingsCategory.Watch,
        parentPage = Playback,
    ),
    PlaybackSkipSegments(
        titleRes = Res.string.settings_playback_page_skip,
        category = SettingsCategory.Watch,
        parentPage = Playback,
    ),
    PlaybackInterface(
        titleRes = Res.string.settings_playback_page_interface,
        category = SettingsCategory.Watch,
        parentPage = Playback,
    ),
    PlaybackAudio(
        titleRes = Res.string.settings_playback_page_audio,
        category = SettingsCategory.Watch,
        parentPage = Playback,
    ),
    PlaybackSubtitles(
        titleRes = Res.string.settings_playback_page_subtitles,
        category = SettingsCategory.Watch,
        parentPage = Playback,
    ),
    PlaybackVideo(
        titleRes = Res.string.settings_playback_page_video,
        category = SettingsCategory.Watch,
        parentPage = Playback,
    ),
    PlaybackBuffer(
        titleRes = Res.string.settings_playback_page_buffer,
        category = SettingsCategory.Watch,
        parentPage = Playback,
    ),
    PlaybackP2p(
        titleRes = Res.string.settings_playback_page_p2p,
        category = SettingsCategory.Watch,
        parentPage = Playback,
    ),
    Appearance(
        titleRes = Res.string.settings_appearance_title,
        category = SettingsCategory.Look,
        parentPage = Root,
    ),
    Streams(
        titleRes = Res.string.compose_settings_page_streams,
        category = SettingsCategory.Look,
        parentPage = Layout,
    ),
    Advanced(
        titleRes = Res.string.compose_settings_page_advanced,
        category = SettingsCategory.System,
        parentPage = Root,
    ),
    Notifications(
        titleRes = Res.string.compose_settings_page_notifications,
        category = SettingsCategory.System,
        parentPage = Root,
    ),
    ContinueWatching(
        titleRes = Res.string.compose_settings_page_continue_watching,
        category = SettingsCategory.Look,
        parentPage = Layout,
    ),
    PosterCustomization(
        titleRes = Res.string.compose_settings_page_poster_customization,
        category = SettingsCategory.Look,
        parentPage = Layout,
    ),
    ContentDiscovery(
        titleRes = Res.string.compose_settings_page_content_discovery,
        category = SettingsCategory.Watch,
        parentPage = Root,
    ),
    Addons(
        titleRes = Res.string.compose_settings_page_addons,
        category = SettingsCategory.Watch,
        parentPage = ContentDiscovery,
    ),
    Plugins(
        titleRes = Res.string.compose_settings_page_plugins,
        category = SettingsCategory.Watch,
        parentPage = ContentDiscovery,
    ),
    MediaServers(
        titleRes = Res.string.compose_settings_page_media_servers,
        category = SettingsCategory.Watch,
        parentPage = ContentDiscovery,
    ),
    MediaServer(
        titleRes = Res.string.compose_settings_page_media_server,
        category = SettingsCategory.Watch,
        parentPage = MediaServers,
    ),
    Homescreen(
        titleRes = Res.string.compose_settings_page_homescreen,
        category = SettingsCategory.Look,
        parentPage = Layout,
    ),
    MetaScreen(
        titleRes = Res.string.compose_settings_page_meta_screen,
        category = SettingsCategory.Look,
        parentPage = Layout,
    ),
    Integrations(
        titleRes = Res.string.compose_settings_page_integrations,
        category = SettingsCategory.Services,
        parentPage = Root,
    ),
    TmdbEnrichment(
        titleRes = Res.string.compose_settings_page_tmdb_enrichment,
        category = SettingsCategory.Services,
        parentPage = Integrations,
    ),
    MdbListRatings(
        titleRes = Res.string.compose_settings_page_mdblist_ratings,
        category = SettingsCategory.Services,
        parentPage = Integrations,
    ),
    Debrid(
        titleRes = Res.string.compose_settings_page_debrid,
        category = SettingsCategory.Services,
        parentPage = Integrations,
    ),
    TraktAuthentication(
        titleRes = Res.string.compose_settings_page_tracking,
        category = SettingsCategory.Services,
        parentPage = Root,
    ),
}

internal val SettingsPage.opensInlineOnTablet: Boolean
    get() = parentPage != null

internal fun SettingsPage.previousPage(): SettingsPage? = parentPage

internal fun settingsCategoryForName(name: String): SettingsCategory = when (name) {
    "Account" -> SettingsCategory.You
    "General" -> SettingsCategory.Look
    "About", "Advanced" -> SettingsCategory.System
    else -> SettingsCategory.entries.firstOrNull { it.name == name } ?: SettingsCategory.Look
}

internal val SettingsPage.icon: ImageVector
    get() = when (this) {
        SettingsPage.Root -> Icons.Rounded.Settings
        SettingsPage.Account -> Icons.Default.Person
        SettingsPage.SupportersContributors -> Icons.Default.Favorite
        SettingsPage.LicensesAttributions -> Icons.Default.Info
        SettingsPage.Playback -> Icons.Rounded.PlayArrow
        SettingsPage.Appearance -> Icons.Default.Palette
        SettingsPage.Layout -> Icons.Default.GridView
        SettingsPage.About -> Icons.Default.Info
        SettingsPage.Streams -> Icons.AutoMirrored.Filled.ViewList
        SettingsPage.Advanced -> Icons.Default.Build
        SettingsPage.Notifications -> Icons.Rounded.Notifications
        SettingsPage.ContinueWatching -> Icons.Default.History
        SettingsPage.PosterCustomization -> Icons.Default.RoundedCorner
        SettingsPage.ContentDiscovery -> Icons.Default.Explore
        SettingsPage.Addons -> Icons.Default.Extension
        SettingsPage.Plugins -> Icons.Default.Power
        SettingsPage.MediaServers -> Icons.Default.Dns
        SettingsPage.MediaServer -> Icons.Default.Dns
        SettingsPage.Homescreen -> Icons.Default.Dashboard
        SettingsPage.MetaScreen -> Icons.Default.Description
        SettingsPage.Integrations -> Icons.Default.Link
        SettingsPage.TmdbEnrichment -> Icons.Default.Link
        SettingsPage.MdbListRatings -> Icons.Default.Link
        SettingsPage.Debrid -> Icons.Default.Link
        SettingsPage.TraktAuthentication -> Icons.Default.Sync
        SettingsPage.PlaybackPlayer -> Icons.Default.SmartDisplay
        SettingsPage.PlaybackStreamSelection -> Icons.AutoMirrored.Filled.PlaylistPlay
        SettingsPage.PlaybackUpNext -> Icons.Default.QueuePlayNext
        SettingsPage.PlaybackSkipSegments -> Icons.Default.FastForward
        SettingsPage.PlaybackInterface -> Icons.Default.Layers
        SettingsPage.PlaybackAudio -> Icons.AutoMirrored.Filled.VolumeUp
        SettingsPage.PlaybackSubtitles -> Icons.Default.Subtitles
        SettingsPage.PlaybackVideo -> Icons.Default.VideoSettings
        SettingsPage.PlaybackBuffer -> Icons.Default.NetworkCheck
        SettingsPage.PlaybackP2p -> Icons.Default.Hub
    }
