package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.isIos
import com.nuvio.app.supportsPosterNavigationMotion
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal sealed class SettingsSearchTarget {
    data class Page(val page: SettingsPage) : SettingsSearchTarget()
    object Downloads : SettingsSearchTarget()
    object Collections : SettingsSearchTarget()
    object SwitchProfile : SettingsSearchTarget()
    object CheckForUpdates : SettingsSearchTarget()
}

internal data class SettingsSearchEntry(
    val key: String,
    val title: String,
    val description: String,
    val page: String,
    val section: String,
    val category: String,
    val icon: ImageVector,
    val target: SettingsSearchTarget,
) {
    val searchableText: String = listOf(title, description, page, section, category)
        .joinToString(separator = " ")
        .lowercase()

    val contextLabel: String = listOf(page, section)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(separator = " - ")
}

@Composable
internal fun settingsSearchEntries(
    pluginsEnabled: Boolean,
    supportersContributorsPageEnabled: Boolean,
    accountDeletionEnabled: Boolean,
    personalMediaAddonCopyEnabled: Boolean,
    liquidGlassNativeTabBarSupported: Boolean,
    switchProfileAvailable: Boolean,
    checkForUpdatesAvailable: Boolean,
): List<SettingsSearchEntry> {
    val accountCategory = stringResource(SettingsCategory.Account.labelRes)
    val generalCategory = stringResource(SettingsCategory.General.labelRes)
    val aboutCategory = stringResource(SettingsCategory.About.labelRes)
    val advancedCategory = stringResource(SettingsCategory.Advanced.labelRes)

    val accountPage = stringResource(Res.string.compose_settings_page_account)
    val trackingPage = stringResource(Res.string.compose_settings_page_tracking)
    val layoutPage = stringResource(Res.string.compose_settings_page_appearance)
    val advancedPage = stringResource(Res.string.compose_settings_page_advanced)
    val contentDiscoveryPage = stringResource(Res.string.compose_settings_page_content_discovery)
    val downloadsPage = stringResource(Res.string.compose_settings_root_downloads_title)
    val playbackPage = stringResource(Res.string.compose_settings_page_playback)
    val streamsPage = stringResource(Res.string.compose_settings_page_streams)
    val integrationsPage = stringResource(Res.string.compose_settings_page_integrations)
    val notificationsPage = stringResource(Res.string.compose_settings_page_notifications)
    val supportersPage = stringResource(Res.string.compose_settings_page_supporters_contributors)
    val licensesPage = stringResource(Res.string.compose_settings_page_licenses_attributions)
    val homeLayoutPage = stringResource(Res.string.compose_settings_page_homescreen)
    val detailPage = stringResource(Res.string.compose_settings_page_meta_screen)
    val continueWatchingPage = stringResource(Res.string.compose_settings_page_continue_watching)
    val posterStylePage = stringResource(Res.string.compose_settings_page_poster_customization)
    val addonsPage = stringResource(Res.string.compose_settings_page_addons)
    val pluginsPage = stringResource(Res.string.compose_settings_page_plugins)
    val collectionsPage = stringResource(Res.string.collections_header)
    val tmdbPage = stringResource(Res.string.compose_settings_page_tmdb_enrichment)
    val mdbListPage = stringResource(Res.string.compose_settings_page_mdblist_ratings)

    val entries = mutableListOf<SettingsSearchEntry>()

    fun add(
        key: String,
        title: String,
        description: String = "",
        page: String = title,
        section: String = "",
        category: String = generalCategory,
        icon: ImageVector,
        target: SettingsSearchTarget,
    ) {
        entries += SettingsSearchEntry(
            key = key,
            title = title,
            description = description,
            page = page,
            section = section,
            category = category,
            icon = icon,
            target = target,
        )
    }

    fun addPage(
        page: SettingsPage,
        key: String,
        title: String,
        description: String,
        category: String = generalCategory,
        icon: ImageVector,
    ) {
        add(
            key = key,
            title = title,
            description = description,
            page = title,
            category = category,
            icon = icon,
            target = SettingsSearchTarget.Page(page),
        )
    }

    fun addRow(
        page: SettingsPage,
        key: String,
        title: String,
        description: String = "",
        pageLabel: String,
        section: String,
        category: String = generalCategory,
        icon: ImageVector,
    ) {
        add(
            key = key,
            title = title,
            description = description,
            page = pageLabel,
            section = section,
            category = category,
            icon = icon,
            target = SettingsSearchTarget.Page(page),
        )
    }

    if (switchProfileAvailable) {
        add(
            key = "switch-profile",
            title = stringResource(Res.string.compose_settings_root_switch_profile_title),
            description = stringResource(Res.string.compose_settings_root_switch_profile_description),
            page = accountPage,
            section = stringResource(Res.string.compose_settings_root_account_section),
            category = accountCategory,
            icon = Icons.Rounded.People,
            target = SettingsSearchTarget.SwitchProfile,
        )
    }
    addPage(
        page = SettingsPage.Account,
        key = "account",
        title = accountPage,
        description = stringResource(Res.string.compose_settings_root_account_description),
        category = accountCategory,
        icon = Icons.Rounded.AccountCircle,
    )
    addPage(
        page = SettingsPage.TraktAuthentication,
        key = "tracking",
        title = trackingPage,
        description = stringResource(Res.string.compose_settings_root_tracking_description),
        category = accountCategory,
        icon = Icons.Default.Sync,
    )
    addPage(
        page = SettingsPage.Appearance,
        key = "layout",
        title = layoutPage,
        description = stringResource(Res.string.compose_settings_root_appearance_description),
        icon = Icons.Rounded.Palette,
    )
    addPage(
        page = SettingsPage.Advanced,
        key = "advanced",
        title = advancedPage,
        description = stringResource(Res.string.compose_settings_root_advanced_description),
        category = advancedCategory,
        icon = Icons.Rounded.Tune,
    )
    addPage(
        page = SettingsPage.ContentDiscovery,
        key = "content-discovery",
        title = contentDiscoveryPage,
        description = stringResource(Res.string.compose_settings_root_content_discovery_description),
        icon = Icons.Rounded.Extension,
    )
    add(
        key = "downloads",
        title = downloadsPage,
        description = stringResource(Res.string.compose_settings_root_downloads_description),
        category = generalCategory,
        icon = Icons.Rounded.CloudDownload,
        target = SettingsSearchTarget.Downloads,
    )
    addPage(
        page = SettingsPage.Playback,
        key = "playback",
        title = playbackPage,
        description = stringResource(Res.string.settings_playback_subtitle),
        icon = Icons.Rounded.PlayArrow,
    )
    addPage(
        page = SettingsPage.Streams,
        key = "streams",
        title = streamsPage,
        description = stringResource(Res.string.compose_settings_root_streams_description),
        icon = Icons.Rounded.Style,
    )
    addPage(
        page = SettingsPage.Integrations,
        key = "integrations",
        title = integrationsPage,
        description = stringResource(Res.string.compose_settings_root_integrations_description),
        icon = Icons.Rounded.Link,
    )
    addPage(
        page = SettingsPage.Notifications,
        key = "notifications",
        title = notificationsPage,
        description = stringResource(Res.string.compose_settings_root_notifications_description),
        icon = Icons.Rounded.Notifications,
    )
    if (supportersContributorsPageEnabled) {
        addPage(
            page = SettingsPage.SupportersContributors,
            key = "supporters",
            title = supportersPage,
            description = stringResource(Res.string.about_supporters_contributors_subtitle),
            category = aboutCategory,
            icon = Icons.Rounded.Favorite,
        )
    }
    addPage(
        page = SettingsPage.LicensesAttributions,
        key = "licenses-attributions",
        title = licensesPage,
        description = stringResource(Res.string.about_licenses_attributions_subtitle),
        category = aboutCategory,
        icon = Icons.Rounded.Info,
    )
    listOf(
        PlaybackSearchRow("nuvio-license", stringResource(Res.string.settings_licenses_attributions_nuvio_title), stringResource(Res.string.settings_licenses_attributions_nuvio_license)),
        PlaybackSearchRow("tmdb-attribution", stringResource(Res.string.settings_licenses_attributions_tmdb_title), stringResource(Res.string.settings_licenses_attributions_tmdb_body)),
        PlaybackSearchRow("trakt-attribution", stringResource(Res.string.settings_licenses_attributions_trakt_title), stringResource(Res.string.settings_licenses_attributions_trakt_body)),
        PlaybackSearchRow("simkl-attribution", stringResource(Res.string.settings_licenses_attributions_simkl_title), stringResource(Res.string.settings_licenses_attributions_simkl_body)),
        PlaybackSearchRow("premiumize-attribution", stringResource(Res.string.settings_licenses_attributions_premiumize_title), stringResource(Res.string.settings_licenses_attributions_premiumize_body)),
        PlaybackSearchRow("torbox-attribution", stringResource(Res.string.settings_licenses_attributions_torbox_title), stringResource(Res.string.settings_licenses_attributions_torbox_body)),
        PlaybackSearchRow("mdblist-attribution", stringResource(Res.string.settings_licenses_attributions_mdblist_title), stringResource(Res.string.settings_licenses_attributions_mdblist_body)),
        PlaybackSearchRow("introdb-attribution", stringResource(Res.string.settings_licenses_attributions_introdb_title), stringResource(Res.string.settings_licenses_attributions_introdb_body)),
        PlaybackSearchRow("imdb-datasets", stringResource(Res.string.settings_licenses_attributions_imdb_title), stringResource(Res.string.settings_licenses_attributions_imdb_body)),
        PlaybackSearchRow(
            if (isIos) "mpvkit-license" else "exoplayer-license",
            if (isIos) {
                stringResource(Res.string.settings_licenses_attributions_mpvkit_title)
            } else {
                stringResource(Res.string.settings_licenses_attributions_exoplayer_title)
            },
            if (isIos) {
                stringResource(Res.string.settings_licenses_attributions_mpvkit_license)
            } else {
                stringResource(Res.string.settings_licenses_attributions_exoplayer_license)
            },
        ),
    ).forEach { row ->
        addRow(
            page = SettingsPage.LicensesAttributions,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = licensesPage,
            section = stringResource(Res.string.compose_settings_root_about_section),
            category = aboutCategory,
            icon = Icons.Rounded.Info,
        )
    }
    if (checkForUpdatesAvailable) {
        add(
            key = "check-updates",
            title = stringResource(Res.string.compose_settings_root_check_updates_title),
            description = stringResource(Res.string.compose_settings_root_check_updates_description),
            page = if (supportersContributorsPageEnabled) supportersPage else licensesPage,
            section = stringResource(Res.string.compose_settings_root_about_section),
            category = aboutCategory,
            icon = Icons.Rounded.CloudDownload,
            target = SettingsSearchTarget.CheckForUpdates,
        )
    }

    addRow(
        page = SettingsPage.Account,
        key = "account-status",
        title = stringResource(Res.string.settings_account_status),
        pageLabel = accountPage,
        section = accountPage,
        category = accountCategory,
        icon = Icons.Rounded.AccountCircle,
    )
    addRow(
        page = SettingsPage.Account,
        key = "account-sign-out",
        title = stringResource(Res.string.settings_account_sign_out),
        pageLabel = accountPage,
        section = accountPage,
        category = accountCategory,
        icon = Icons.Rounded.AccountCircle,
    )
    if (accountDeletionEnabled) {
        addRow(
            page = SettingsPage.Account,
            key = "account-delete",
            title = stringResource(Res.string.settings_account_delete_account),
            description = stringResource(Res.string.settings_account_delete_account_description),
            pageLabel = accountPage,
            section = accountPage,
            category = accountCategory,
            icon = Icons.Rounded.AccountCircle,
        )
    }

    addRow(
        page = SettingsPage.Appearance,
        key = "theme",
        title = stringResource(Res.string.settings_appearance_section_theme),
        pageLabel = layoutPage,
        section = stringResource(Res.string.settings_appearance_section_theme),
        icon = Icons.Rounded.Palette,
    )
    addRow(
        page = SettingsPage.Appearance,
        key = "amoled",
        title = stringResource(Res.string.settings_appearance_amoled_black),
        description = stringResource(Res.string.settings_appearance_amoled_description),
        pageLabel = layoutPage,
        section = stringResource(Res.string.settings_appearance_section_display),
        icon = Icons.Rounded.Palette,
    )
    if (liquidGlassNativeTabBarSupported) {
        addRow(
            page = SettingsPage.Appearance,
            key = "liquid-glass",
            title = stringResource(Res.string.settings_appearance_liquid_glass),
            description = stringResource(Res.string.settings_appearance_liquid_glass_description),
            pageLabel = layoutPage,
            section = stringResource(Res.string.settings_appearance_section_display),
            icon = Icons.Rounded.Palette,
        )
    }
    addRow(
        page = SettingsPage.Appearance,
        key = "app-language",
        title = stringResource(Res.string.settings_appearance_app_language),
        pageLabel = layoutPage,
        section = stringResource(Res.string.settings_appearance_section_display),
        icon = Icons.Rounded.Language,
    )
    addRow(
        page = SettingsPage.Advanced,
        key = "remember-last-profile",
        title = stringResource(Res.string.settings_advanced_remember_last_profile),
        description = stringResource(Res.string.settings_advanced_remember_last_profile_description),
        pageLabel = advancedPage,
        section = stringResource(Res.string.settings_advanced_section_startup),
        category = advancedCategory,
        icon = Icons.Rounded.Tune,
    )
    if (SentrySettingsRepository.isSupported) {
        addRow(
            page = SettingsPage.Advanced,
            key = "sentry-crash-reports",
            title = stringResource(Res.string.settings_advanced_sentry_reports),
            description = stringResource(Res.string.settings_advanced_sentry_reports_subtitle),
            pageLabel = advancedPage,
            section = stringResource(Res.string.settings_advanced_section_diagnostics),
            category = advancedCategory,
            icon = Icons.Rounded.Tune,
        )
    }
    addRow(
        page = SettingsPage.Advanced,
        key = "clear-cw-cache",
        title = stringResource(Res.string.settings_advanced_clear_cw_cache),
        description = stringResource(Res.string.settings_advanced_clear_cw_cache_subtitle),
        pageLabel = advancedPage,
        section = stringResource(Res.string.settings_advanced_section_cache),
        category = advancedCategory,
        icon = Icons.Rounded.Tune,
    )
    addPage(
        page = SettingsPage.ContinueWatching,
        key = "continue-watching",
        title = continueWatchingPage,
        description = stringResource(Res.string.settings_appearance_continue_watching_description),
        icon = Icons.Rounded.Style,
    )
    addPage(
        page = SettingsPage.PosterCustomization,
        key = "poster-card-style",
        title = posterStylePage,
        description = stringResource(Res.string.settings_appearance_poster_customization_description),
        icon = Icons.Rounded.Tune,
    )

    addPage(
        page = SettingsPage.Addons,
        key = "addons",
        title = addonsPage,
        description = stringResource(
            if (personalMediaAddonCopyEnabled) {
                Res.string.settings_content_discovery_addons_description_appstore
            } else {
                Res.string.settings_content_discovery_addons_description
            },
        ),
        icon = Icons.Rounded.Extension,
    )
    if (pluginsEnabled) {
        addPage(
            page = SettingsPage.Plugins,
            key = "plugins",
            title = pluginsPage,
            description = stringResource(Res.string.settings_content_discovery_plugins_description),
            icon = Icons.Rounded.Hub,
        )
    }
    addPage(
        page = SettingsPage.Homescreen,
        key = "home-layout",
        title = homeLayoutPage,
        description = stringResource(Res.string.settings_content_discovery_homescreen_description),
        icon = Icons.Rounded.Home,
    )
    addPage(
        page = SettingsPage.MetaScreen,
        key = "detail-page",
        title = detailPage,
        description = stringResource(Res.string.settings_content_discovery_meta_screen_description),
        icon = Icons.Rounded.Tune,
    )
    add(
        key = "collections",
        title = collectionsPage,
        description = stringResource(Res.string.settings_content_discovery_collections_description),
        page = layoutPage,
        section = stringResource(Res.string.settings_appearance_section_home),
        category = generalCategory,
        icon = Icons.Rounded.CollectionsBookmark,
        target = SettingsSearchTarget.Collections,
    )

    val playbackPlayer = stringResource(Res.string.settings_playback_section_player)
    val playbackSubtitleAudio = stringResource(Res.string.settings_playback_section_subtitle_audio)
    val playbackStreamSelection = stringResource(Res.string.settings_playback_section_stream_selection)
    val playbackStreamAutoPlay = stringResource(Res.string.settings_playback_section_stream_auto_play)
    val playbackDecoder = stringResource(Res.string.settings_playback_section_decoder)
    val playbackSubtitleRendering = stringResource(Res.string.settings_playback_section_subtitle_rendering)
    val playbackSkipSegments = stringResource(Res.string.settings_playback_section_skip_segments)
    val playbackNextEpisode = stringResource(Res.string.settings_playback_section_next_episode)
    addRow(
        page = SettingsPage.Streams,
        key = "stream-addon-logo",
        title = stringResource(Res.string.settings_stream_addon_logo_title),
        description = stringResource(Res.string.settings_stream_addon_logo_description),
        pageLabel = streamsPage,
        section = stringResource(Res.string.settings_stream_display_section),
        icon = Icons.Rounded.Style,
    )
    addRow(
        page = SettingsPage.Streams,
        key = "stream-size-badges",
        title = stringResource(Res.string.settings_stream_size_badges_title),
        description = stringResource(Res.string.settings_stream_size_badges_description),
        pageLabel = streamsPage,
        section = stringResource(Res.string.settings_stream_badges_section),
        icon = Icons.Rounded.Style,
    )
    addRow(
        page = SettingsPage.Streams,
        key = "stream-badge-position",
        title = stringResource(Res.string.settings_stream_badge_position_title),
        description = stringResource(Res.string.settings_stream_badge_position_description),
        pageLabel = streamsPage,
        section = stringResource(Res.string.settings_stream_badges_section),
        icon = Icons.Rounded.Style,
    )
    addRow(
        page = SettingsPage.Streams,
        key = "stream-badge-urls",
        title = stringResource(Res.string.settings_stream_badge_urls_title),
        description = stringResource(Res.string.settings_stream_badge_urls_search_description),
        pageLabel = streamsPage,
        section = stringResource(Res.string.settings_stream_badges_section),
        icon = Icons.Rounded.Style,
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackPlayer,
        icon = Icons.Rounded.PlayArrow,
        rows = listOfNotNull(
            PlaybackSearchRow(
                "loading-overlay",
                stringResource(Res.string.settings_playback_show_loading_overlay),
                stringResource(Res.string.settings_playback_show_loading_overlay_description),
            ),
            PlaybackSearchRow(
                "external-player",
                stringResource(Res.string.settings_playback_external_player),
                stringResource(Res.string.settings_playback_external_player_description_android),
            ),
            if (isIos) PlaybackSearchRow(
                "external-player-app",
                stringResource(Res.string.settings_playback_external_player_app),
            ) else null,
            PlaybackSearchRow(
                "hold-to-speed",
                stringResource(Res.string.settings_playback_hold_to_speed),
                stringResource(Res.string.settings_playback_hold_to_speed_description),
            ),
            PlaybackSearchRow(
                "touch-gestures",
                stringResource(Res.string.settings_playback_touch_gestures),
                stringResource(Res.string.settings_playback_touch_gestures_description),
            ),
            PlaybackSearchRow("hold-speed", stringResource(Res.string.settings_playback_hold_speed)),
        ),
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackSubtitleAudio,
        icon = Icons.Rounded.PlayArrow,
        rows = listOf(
            PlaybackSearchRow("preferred-audio", stringResource(Res.string.settings_playback_preferred_audio_language)),
            PlaybackSearchRow("secondary-audio", stringResource(Res.string.settings_playback_secondary_audio_language)),
            PlaybackSearchRow("preferred-subtitles", stringResource(Res.string.settings_playback_preferred_subtitle_language)),
            PlaybackSearchRow("secondary-subtitles", stringResource(Res.string.settings_playback_secondary_subtitle_language)),
        ),
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackStreamSelection,
        icon = Icons.Rounded.PlayArrow,
        rows = listOf(
            PlaybackSearchRow(
                "reuse-last-link",
                stringResource(Res.string.settings_playback_reuse_last_link),
                stringResource(Res.string.settings_playback_reuse_last_link_description),
            ),
            PlaybackSearchRow("last-link-cache", stringResource(Res.string.settings_playback_last_link_cache_duration)),
        ),
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackStreamAutoPlay,
        icon = Icons.Rounded.PlayArrow,
        rows = buildList {
            add(PlaybackSearchRow("stream-mode", stringResource(Res.string.settings_playback_stream_selection_mode)))
            add(PlaybackSearchRow("regex-pattern", stringResource(Res.string.settings_playback_regex_pattern)))
            add(PlaybackSearchRow("stream-timeout", stringResource(Res.string.settings_playback_stream_timeout), stringResource(Res.string.settings_playback_stream_timeout_description)))
            add(PlaybackSearchRow("source-scope", stringResource(Res.string.settings_playback_source_scope)))
            add(PlaybackSearchRow("allowed-addons", stringResource(Res.string.settings_playback_allowed_addons)))
            if (pluginsEnabled) add(PlaybackSearchRow("allowed-plugins", stringResource(Res.string.settings_playback_allowed_plugins)))
        },
    )
    if (!isIos) {
        addPlaybackRows(
            addRow = ::addRow,
            pageLabel = playbackPage,
            section = playbackDecoder,
            icon = Icons.Rounded.PlayArrow,
            rows = listOf(
                PlaybackSearchRow("decoder-priority", stringResource(Res.string.settings_playback_decoder_priority)),
                PlaybackSearchRow("dv7-hevc", stringResource(Res.string.settings_playback_map_dv7_to_hevc), stringResource(Res.string.settings_playback_map_dv7_to_hevc_description)),
                PlaybackSearchRow("tunneled-playback", stringResource(Res.string.settings_playback_tunneled_playback), stringResource(Res.string.settings_playback_tunneled_playback_description)),
            ),
        )
        addPlaybackRows(
            addRow = ::addRow,
            pageLabel = playbackPage,
            section = playbackSubtitleRendering,
            icon = Icons.Rounded.PlayArrow,
            rows = listOf(
                PlaybackSearchRow("libass", stringResource(Res.string.settings_playback_enable_libass), stringResource(Res.string.settings_playback_enable_libass_description)),
                PlaybackSearchRow("libass-render", stringResource(Res.string.settings_playback_render_type)),
            ),
        )
    }
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackSkipSegments,
        icon = Icons.Rounded.PlayArrow,
        rows = listOf(
            PlaybackSearchRow("skip-intro", stringResource(Res.string.settings_playback_skip_intro_outro_recap), stringResource(Res.string.settings_playback_skip_intro_outro_recap_description)),
            PlaybackSearchRow("anime-skip", stringResource(Res.string.settings_playback_anime_skip), stringResource(Res.string.settings_playback_anime_skip_description)),
            PlaybackSearchRow("anime-skip-client", stringResource(Res.string.settings_playback_anime_skip_client_id), stringResource(Res.string.settings_playback_anime_skip_client_id_description)),
            PlaybackSearchRow("intro-submit", stringResource(Res.string.settings_playback_intro_submit_enabled), stringResource(Res.string.settings_playback_intro_submit_enabled_description)),
            PlaybackSearchRow("introdb-key", stringResource(Res.string.settings_playback_introdb_api_key), stringResource(Res.string.settings_playback_introdb_api_key_description)),
        ),
    )
    addPlaybackRows(
        addRow = ::addRow,
        pageLabel = playbackPage,
        section = playbackNextEpisode,
        icon = Icons.Rounded.PlayArrow,
        rows = listOf(
            PlaybackSearchRow("auto-play-next", stringResource(Res.string.settings_playback_auto_play_next_episode), stringResource(Res.string.settings_playback_auto_play_next_episode_description)),
            PlaybackSearchRow("prefer-binge", stringResource(Res.string.settings_playback_prefer_binge_group), stringResource(Res.string.settings_playback_prefer_binge_group_description)),
            PlaybackSearchRow("threshold-mode", stringResource(Res.string.settings_playback_threshold_mode)),
            PlaybackSearchRow("threshold-percent", stringResource(Res.string.settings_playback_threshold_percentage), stringResource(Res.string.settings_playback_threshold_percentage_description)),
            PlaybackSearchRow("threshold-minutes", stringResource(Res.string.settings_playback_minutes_before_end), stringResource(Res.string.settings_playback_minutes_before_end_description)),
        ),
    )

    addContinueWatchingRows(
        addRow = ::addRow,
        pageLabel = continueWatchingPage,
        section = stringResource(Res.string.settings_continue_watching_section_visibility),
        icon = Icons.Rounded.Style,
        rows = listOf(
            PlaybackSearchRow(
                "show-continue-watching",
                stringResource(Res.string.settings_continue_watching_show_title),
                stringResource(Res.string.settings_continue_watching_show_description),
            ),
        ),
    )
    addContinueWatchingRows(
        addRow = ::addRow,
        pageLabel = continueWatchingPage,
        section = stringResource(Res.string.settings_continue_watching_section_up_next_behavior),
        icon = Icons.Rounded.Style,
        rows = listOf(
            PlaybackSearchRow("episode-thumbnails", stringResource(Res.string.settings_continue_watching_use_episode_thumbnails_title), stringResource(Res.string.settings_continue_watching_use_episode_thumbnails_description)),
            PlaybackSearchRow("up-next", stringResource(Res.string.settings_continue_watching_up_next_title), stringResource(Res.string.settings_continue_watching_up_next_description)),
            PlaybackSearchRow("unaired-next-up", stringResource(Res.string.settings_continue_watching_show_unaired_next_up_title), stringResource(Res.string.settings_continue_watching_show_unaired_next_up_description)),
            PlaybackSearchRow("blur-next-up", stringResource(Res.string.settings_continue_watching_blur_next_up_title), stringResource(Res.string.settings_continue_watching_blur_next_up_description)),
        ),
    )
    addContinueWatchingRows(
        addRow = ::addRow,
        pageLabel = continueWatchingPage,
        section = stringResource(Res.string.settings_continue_watching_section_on_launch),
        icon = Icons.Rounded.Style,
        rows = listOf(
            PlaybackSearchRow("resume-prompt", stringResource(Res.string.settings_continue_watching_resume_prompt_title), stringResource(Res.string.settings_continue_watching_resume_prompt_description)),
        ),
    )

    val posterSection = stringResource(Res.string.settings_poster_card_style)
    listOf(
        PlaybackSearchRow("poster-width", stringResource(Res.string.settings_poster_card_width)),
        PlaybackSearchRow("poster-radius", stringResource(Res.string.settings_poster_card_radius)),
        PlaybackSearchRow("poster-landscape", stringResource(Res.string.settings_poster_landscape_mode)),
        PlaybackSearchRow("poster-hide-labels", stringResource(Res.string.settings_poster_hide_labels)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.PosterCustomization,
            key = "poster-${row.key}",
            title = row.title,
            description = row.description,
            pageLabel = posterStylePage,
            section = posterSection,
            icon = Icons.Rounded.Tune,
        )
    }

    val cardDepthSection = stringResource(Res.string.settings_card_depth_title)
    listOf(
        PlaybackSearchRow("card-depth-effect", cardDepthSection, stringResource(Res.string.settings_card_depth_description)),
        PlaybackSearchRow("card-depth-edge", stringResource(Res.string.settings_card_depth_edge)),
        PlaybackSearchRow("card-depth-sheen", stringResource(Res.string.settings_card_depth_sheen)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.PosterCustomization,
            key = "poster-${row.key}",
            title = row.title,
            description = row.description,
            pageLabel = posterStylePage,
            section = cardDepthSection,
            icon = Icons.Rounded.Tune,
        )
    }

    val homeLayoutSection = stringResource(Res.string.settings_homescreen_section_hero)
    listOf(
        PlaybackSearchRow("home-hero", stringResource(Res.string.settings_homescreen_show_hero), stringResource(Res.string.settings_homescreen_show_hero_description)),
        PlaybackSearchRow("home-catalog-type", stringResource(Res.string.layout_catalog_type), stringResource(Res.string.layout_catalog_type_sub)),
        PlaybackSearchRow("home-hide-unreleased", stringResource(Res.string.layout_hide_unreleased), stringResource(Res.string.layout_hide_unreleased_sub)),
        PlaybackSearchRow("home-hero-sources", stringResource(Res.string.settings_homescreen_section_hero_sources)),
        PlaybackSearchRow("home-catalogs", stringResource(Res.string.settings_homescreen_section_catalogs)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.Homescreen,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = homeLayoutPage,
            section = homeLayoutSection,
            icon = Icons.Rounded.Home,
        )
    }

    val detailAppearanceSection = stringResource(Res.string.settings_meta_section_appearance)
    if (supportsPosterNavigationMotion) {
        addRow(
            page = SettingsPage.MetaScreen,
            key = "meta-poster-transition",
            title = stringResource(Res.string.settings_meta_poster_transition),
            description = stringResource(Res.string.settings_meta_poster_transition_description),
            pageLabel = detailPage,
            section = detailAppearanceSection,
            icon = Icons.Rounded.Tune,
        )
    }
    listOf(
        PlaybackSearchRow("meta-background-mode", stringResource(Res.string.settings_meta_background_mode), stringResource(Res.string.settings_meta_background_mode_description)),
        PlaybackSearchRow("meta-tabs", stringResource(Res.string.settings_meta_tab_layout), stringResource(Res.string.settings_meta_tab_layout_description)),
        PlaybackSearchRow("meta-episode-cards", stringResource(Res.string.settings_meta_episode_cards), stringResource(Res.string.settings_meta_episode_cards_description)),
        PlaybackSearchRow("meta-blur-episodes", stringResource(Res.string.settings_meta_blur_unwatched_episodes), stringResource(Res.string.settings_meta_blur_unwatched_episodes_description)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.MetaScreen,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = detailPage,
            section = detailAppearanceSection,
            icon = Icons.Rounded.Tune,
        )
    }
    val detailSectionsSection = stringResource(Res.string.settings_meta_section_sections)
    listOf(
        PlaybackSearchRow("meta-overview", stringResource(Res.string.settings_meta_overview), stringResource(Res.string.settings_meta_overview_description)),
        PlaybackSearchRow("meta-actions", stringResource(Res.string.settings_meta_actions), stringResource(Res.string.settings_meta_actions_description)),
        PlaybackSearchRow("meta-details", stringResource(Res.string.settings_meta_details), stringResource(Res.string.settings_meta_details_description)),
        PlaybackSearchRow("meta-trailers", stringResource(Res.string.settings_meta_trailers), stringResource(Res.string.settings_meta_trailers_description)),
        PlaybackSearchRow("meta-cast", stringResource(Res.string.settings_meta_cast), stringResource(Res.string.settings_meta_cast_description)),
        PlaybackSearchRow("meta-episodes", stringResource(Res.string.settings_meta_episodes), stringResource(Res.string.settings_meta_episodes_description)),
        PlaybackSearchRow("meta-production", stringResource(Res.string.settings_meta_production), stringResource(Res.string.settings_meta_production_description)),
        PlaybackSearchRow("meta-more-like-this", stringResource(Res.string.settings_meta_more_like_this), stringResource(Res.string.settings_meta_more_like_this_description)),
        PlaybackSearchRow("meta-collection", stringResource(Res.string.settings_meta_collection), stringResource(Res.string.settings_meta_collection_description)),
        PlaybackSearchRow("meta-comments", stringResource(Res.string.settings_meta_comments), stringResource(Res.string.settings_meta_comments_description)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.MetaScreen,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = detailPage,
            section = detailSectionsSection,
            icon = Icons.Rounded.Tune,
        )
    }

    addPage(
        page = SettingsPage.TmdbEnrichment,
        key = "tmdb",
        title = tmdbPage,
        description = stringResource(Res.string.settings_integrations_tmdb_description),
        icon = Icons.Rounded.Link,
    )
    addPage(
        page = SettingsPage.MdbListRatings,
        key = "mdblist",
        title = mdbListPage,
        description = stringResource(Res.string.settings_integrations_mdblist_description),
        icon = Icons.Rounded.Link,
    )
    val tmdbModulesSection = stringResource(Res.string.settings_tmdb_section_modules)
    listOf(
        PlaybackSearchRow("tmdb-enable", stringResource(Res.string.settings_tmdb_enable_enrichment), stringResource(Res.string.settings_tmdb_enable_enrichment_description), stringResource(Res.string.settings_tmdb_section_title)),
        PlaybackSearchRow("tmdb-api-key", stringResource(Res.string.settings_tmdb_personal_api_key), "", stringResource(Res.string.settings_tmdb_section_credentials)),
        PlaybackSearchRow("tmdb-language", stringResource(Res.string.settings_tmdb_preferred_language), stringResource(Res.string.settings_tmdb_preferred_language_description), stringResource(Res.string.settings_tmdb_section_localization)),
        PlaybackSearchRow("tmdb-trailers", stringResource(Res.string.settings_tmdb_module_trailers), stringResource(Res.string.settings_tmdb_module_trailers_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-artwork", stringResource(Res.string.settings_tmdb_module_artwork), stringResource(Res.string.settings_tmdb_module_artwork_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-basic-info", stringResource(Res.string.settings_tmdb_module_basic_info), stringResource(Res.string.settings_tmdb_module_basic_info_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-details", stringResource(Res.string.settings_tmdb_module_details), stringResource(Res.string.settings_tmdb_module_details_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-credits", stringResource(Res.string.settings_tmdb_module_credits), stringResource(Res.string.settings_tmdb_module_credits_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-companies", stringResource(Res.string.settings_tmdb_module_production_companies), stringResource(Res.string.settings_tmdb_module_production_companies_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-networks", stringResource(Res.string.settings_tmdb_module_networks), stringResource(Res.string.settings_tmdb_module_networks_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-episodes", stringResource(Res.string.settings_tmdb_module_episodes), stringResource(Res.string.settings_tmdb_module_episodes_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-season-posters", stringResource(Res.string.settings_tmdb_module_season_posters), stringResource(Res.string.settings_tmdb_module_season_posters_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-more-like-this", stringResource(Res.string.settings_tmdb_module_more_like_this), stringResource(Res.string.settings_tmdb_module_more_like_this_description), tmdbModulesSection),
        PlaybackSearchRow("tmdb-collections", stringResource(Res.string.settings_tmdb_module_collections), stringResource(Res.string.settings_tmdb_module_collections_description), tmdbModulesSection),
    ).forEach { row ->
        addRow(
            page = SettingsPage.TmdbEnrichment,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = tmdbPage,
            section = row.sectionOverride ?: tmdbModulesSection,
            icon = Icons.Rounded.Link,
        )
    }

    listOf(
        PlaybackSearchRow("mdb-enable", stringResource(Res.string.settings_mdb_enable_ratings), stringResource(Res.string.settings_mdb_enable_ratings_description), stringResource(Res.string.settings_mdb_section_title)),
        PlaybackSearchRow("mdb-api-key", stringResource(Res.string.settings_mdb_api_key_title), stringResource(Res.string.settings_mdb_api_key_description), stringResource(Res.string.settings_mdb_section_api_key)),
        PlaybackSearchRow("mdb-imdb", stringResource(Res.string.source_imdb), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-tmdb", stringResource(Res.string.source_tmdb), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-tomatoes", stringResource(Res.string.source_rotten_tomatoes), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-metacritic", stringResource(Res.string.source_metacritic), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-trakt", stringResource(Res.string.source_trakt), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-letterboxd", stringResource(Res.string.source_letterboxd), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
        PlaybackSearchRow("mdb-audience", stringResource(Res.string.source_audience_score), "", stringResource(Res.string.settings_mdb_section_rating_providers)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.MdbListRatings,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = mdbListPage,
            section = row.sectionOverride ?: stringResource(Res.string.settings_mdb_section_title),
            icon = Icons.Rounded.Link,
        )
    }

    val notificationsAlerts = stringResource(Res.string.settings_notifications_section_alerts)
    addRow(
        page = SettingsPage.Notifications,
        key = "episode-release-alerts",
        title = stringResource(Res.string.settings_notifications_episode_release_alerts),
        description = stringResource(Res.string.settings_notifications_episode_release_alerts_description),
        pageLabel = notificationsPage,
        section = notificationsAlerts,
        icon = Icons.Rounded.Notifications,
    )
    addRow(
        page = SettingsPage.Notifications,
        key = "notification-test",
        title = stringResource(Res.string.settings_notifications_test_title),
        pageLabel = notificationsPage,
        section = stringResource(Res.string.settings_notifications_section_test),
        icon = Icons.Rounded.Notifications,
    )

    addRow(
        page = SettingsPage.TraktAuthentication,
        key = "trakt-authentication",
        title = stringResource(Res.string.trakt_library_source_trakt),
        description = stringResource(Res.string.settings_trakt_intro_description),
        pageLabel = trackingPage,
        section = stringResource(Res.string.settings_tracking_services),
        category = accountCategory,
        icon = Icons.Rounded.Link,
    )
    addRow(
        page = SettingsPage.TraktAuthentication,
        key = "simkl-authentication",
        title = stringResource(Res.string.tracking_source_simkl),
        description = stringResource(Res.string.settings_simkl_sign_in_description),
        pageLabel = trackingPage,
        section = stringResource(Res.string.settings_tracking_services),
        category = accountCategory,
        icon = Icons.Rounded.Link,
    )
    addRow(
        page = SettingsPage.TraktAuthentication,
        key = "mdblist-authentication",
        title = stringResource(Res.string.tracking_source_mdblist),
        description = stringResource(Res.string.settings_mdblist_sign_in_description),
        pageLabel = trackingPage,
        section = stringResource(Res.string.settings_tracking_services),
        category = accountCategory,
        icon = Icons.Rounded.Link,
    )
    listOf(
        PlaybackSearchRow("trakt-library-source", stringResource(Res.string.trakt_library_source_title), stringResource(Res.string.trakt_library_source_subtitle)),
        PlaybackSearchRow("trakt-watch-progress", stringResource(Res.string.trakt_watch_progress_title), stringResource(Res.string.trakt_watch_progress_subtitle)),
        PlaybackSearchRow("trakt-continue-watching-window", stringResource(Res.string.trakt_continue_watching_window), stringResource(Res.string.trakt_continue_watching_subtitle)),
        PlaybackSearchRow("trakt-comments", stringResource(Res.string.settings_trakt_comments), stringResource(Res.string.settings_trakt_comments_description)),
        PlaybackSearchRow("trakt-more-like-this-source", stringResource(Res.string.trakt_more_like_this_source_title), stringResource(Res.string.trakt_more_like_this_source_subtitle)),
    ).forEach { row ->
        addRow(
            page = SettingsPage.TraktAuthentication,
            key = row.key,
            title = row.title,
            description = row.description,
            pageLabel = trackingPage,
            section = stringResource(Res.string.settings_tracking_features),
            category = accountCategory,
            icon = Icons.Rounded.Link,
        )
    }

    return entries
}

private data class PlaybackSearchRow(
    val key: String,
    val title: String,
    val description: String = "",
    val sectionOverride: String? = null,
)

private fun addPlaybackRows(
    addRow: (
        page: SettingsPage,
        key: String,
        title: String,
        description: String,
        pageLabel: String,
        section: String,
        category: String,
        icon: ImageVector,
    ) -> Unit,
    pageLabel: String,
    section: String,
    icon: ImageVector,
    rows: List<PlaybackSearchRow>,
) {
    rows.forEach { row ->
        addRow(
            SettingsPage.Playback,
            "playback-${row.key}",
            row.title,
            row.description,
            pageLabel,
            section,
            "",
            icon,
        )
    }
}

private fun addContinueWatchingRows(
    addRow: (
        page: SettingsPage,
        key: String,
        title: String,
        description: String,
        pageLabel: String,
        section: String,
        category: String,
        icon: ImageVector,
    ) -> Unit,
    pageLabel: String,
    section: String,
    icon: ImageVector,
    rows: List<PlaybackSearchRow>,
) {
    rows.forEach { row ->
        addRow(
            SettingsPage.ContinueWatching,
            "continue-watching-${row.key}",
            row.title,
            row.description,
            pageLabel,
            section,
            "",
            icon,
        )
    }
}

internal fun LazyListScope.settingsSearchRootContent(
    query: String,
    entries: @Composable () -> List<SettingsSearchEntry>,
    isTablet: Boolean,
    showSearchField: Boolean,
    animateSearchField: Boolean,
    onQueryChange: (String) -> Unit,
    onTargetClick: (SettingsSearchTarget) -> Unit,
) {
    if (showSearchField || query.isNotBlank()) {
        item(key = "settings-search-field") {
            SettingsSearchRevealItem(animate = animateSearchField) {
                SettingsSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                )
            }
        }
    }

    if (query.isBlank()) return

    item(key = "settings-search-results") {
        val results = settingsSearchResults(
            query = query,
            entries = entries(),
        )
        if (results.isEmpty()) {
            SettingsSearchEmptyState(isTablet = isTablet)
        } else {
            SettingsSection(
                title = stringResource(Res.string.settings_search_results_section),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    results.forEachIndexed { index, entry ->
                        if (index > 0) {
                            SettingsGroupDivider(isTablet = isTablet)
                        }
                        SettingsNavigationRow(
                            title = entry.title,
                            description = entry.resultDescription(),
                            icon = entry.icon,
                            isTablet = isTablet,
                            onClick = { onTargetClick(entry.target) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSearchRevealItem(
    animate: Boolean,
    content: @Composable () -> Unit,
) {
    if (!animate) {
        content()
        return
    }

    val visibleState = remember {
        MutableTransitionState(false).apply {
            targetState = true
        }
    }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = expandVertically(
            animationSpec = tween(durationMillis = NuvioTokens.Motion.normalMillis),
            expandFrom = Alignment.Top,
        ) + fadeIn(
            animationSpec = tween(durationMillis = NuvioTokens.Motion.fastMillis),
        ) + slideInVertically(
            animationSpec = tween(durationMillis = NuvioTokens.Motion.normalMillis),
            initialOffsetY = { -it / 4 },
        ),
    ) {
        content()
    }
}

@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = tokens.shapes.compactCard,
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = tokens.colors.textMuted,
            )
        },
        trailingIcon = if (query.isNotBlank()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.compose_search_clear),
                        tint = tokens.colors.textMuted,
                    )
                }
            }
        } else {
            null
        },
        placeholder = {
            Text(
                text = stringResource(Res.string.settings_search_placeholder),
                color = tokens.colors.textMuted,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = tokens.colors.textPrimary),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = tokens.colors.borderFocus,
            unfocusedBorderColor = tokens.colors.borderDefault,
            focusedContainerColor = tokens.colors.surfaceCard,
            unfocusedContainerColor = tokens.colors.surfaceCard,
            cursorColor = tokens.colors.accent,
        ),
    )
}

@Composable
private fun SettingsSearchEmptyState(isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    SettingsSection(
        title = stringResource(Res.string.settings_search_results_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 18.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_search_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun settingsSearchResults(
    query: String,
    entries: List<SettingsSearchEntry>,
): List<SettingsSearchEntry> {
    val terms = query
        .trim()
        .lowercase()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    if (terms.isEmpty()) return emptyList()

    return entries.filter { entry ->
        terms.all { term -> entry.searchableText.contains(term) }
    }
}

private fun SettingsSearchEntry.resultDescription(): String {
    return description.ifBlank { contextLabel }
}
