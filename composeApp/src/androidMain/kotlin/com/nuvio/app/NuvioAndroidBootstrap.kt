package com.nuvio.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nuvio.app.core.auth.AuthStorage
import com.nuvio.app.core.deeplink.handleAppUrl
import com.nuvio.app.core.diagnostics.SentryInitializer
import com.nuvio.app.core.network.ServerConfigurationStorage
import com.nuvio.app.core.poster.CustomPosterUrlStorage
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import com.nuvio.app.core.sync.SyncClientIdentityStorage
import com.nuvio.app.core.ui.CardDepthStyleStorage
import com.nuvio.app.core.ui.PosterCardStyleStorage
import com.nuvio.app.features.addons.AddonHttpClientProvider
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.autosync.AutoSyncPreferencesAndroid
import com.nuvio.app.features.collection.CollectionMobileSettingsStorage
import com.nuvio.app.features.collection.CollectionStorage
import com.nuvio.app.features.debrid.DebridSettingsStorage
import com.nuvio.app.features.details.MetaScreenSettingsStorage
import com.nuvio.app.features.details.SeasonViewModeStorage
import com.nuvio.app.features.downloads.DownloadsLiveStatusPlatform
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.home.HomeCatalogSettingsStorage
import com.nuvio.app.features.library.LibraryDisplaySettingsStorage
import com.nuvio.app.features.library.LibraryStorage
import com.nuvio.app.features.mdblist.MdbListSettingsStorage
import com.nuvio.app.features.mdblist.PlatformMdbListAuthPersistence
import com.nuvio.app.features.mdblist.PlatformMdbListSyncStorage
import com.nuvio.app.features.membership.MemberAssetStorage
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationPlatform
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsStorage
import com.nuvio.app.features.p2p.P2pSettingsStorage
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.player.ExternalPlayerPlatform
import com.nuvio.app.features.player.PipRemoteActionReceiver
import com.nuvio.app.features.player.PlayerPictureInPictureManager
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.PlayerTrackPreferenceStorage
import com.nuvio.app.features.player.SubtitleFileCache
import com.nuvio.app.features.plugins.PluginStorage
import com.nuvio.app.features.profiles.AvatarStorage
import com.nuvio.app.features.profiles.ProfilePinCacheStorage
import com.nuvio.app.features.profiles.ProfileStorage
import com.nuvio.app.features.search.DiscoverSelectionStorage
import com.nuvio.app.features.search.SearchHistoryStorage
import com.nuvio.app.features.servers.ServerStorage
import com.nuvio.app.features.settings.AppIconPlatform
import com.nuvio.app.features.settings.SentrySettingsStorage
import com.nuvio.app.features.settings.ThemeSettingsStorage
import com.nuvio.app.features.simkl.SimklAuthStorage
import com.nuvio.app.features.simkl.SimklSyncStorage
import com.nuvio.app.features.streams.BingeGroupCacheStorage
import com.nuvio.app.features.streams.StreamBadgeSettingsStorage
import com.nuvio.app.features.streams.StreamLinkCacheStorage
import com.nuvio.app.features.tmdb.TmdbSettingsStorage
import com.nuvio.app.features.trakt.TraktAuthStorage
import com.nuvio.app.features.trakt.TraktCommentsStorage
import com.nuvio.app.features.trakt.TraktLibraryStorage
import com.nuvio.app.features.trakt.TraktSettingsStorage
import com.nuvio.app.features.updater.AndroidAppUpdaterPlatform
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentStorage
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesStorage
import com.nuvio.app.features.watchprogress.ResumePromptStorage
import com.nuvio.app.features.watchprogress.WatchProgressStorage

/**
 * Process-wide Nuvio setup that MainActivity used to do inline in onCreate, shared with the Android Auto
 * screen (which runs Nuvio without an activity). Safe to call more than once.
 */
object NuvioAndroidBootstrap {
    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        ThemeSettingsStorage.initialize(appContext)
        AppIconPlatform.initialize(appContext)
        SentrySettingsStorage.initialize(appContext)
        SentryInitializer.start(appContext as Application)
        AutoSyncPreferencesAndroid.initialize(appContext) // AutoSync hook
        SyncClientIdentityStorage.initialize(appContext)
        AddonHttpClientProvider.initialize(appContext)
        AddonStorage.initialize(appContext)
        AuthStorage.initialize(appContext)
        ServerConfigurationStorage.initialize(appContext)
        LibraryStorage.initialize(appContext)
        WatchedStorage.initialize(appContext)
        MetaScreenSettingsStorage.initialize(appContext)
        com.nuvio.app.features.shuffle.EpisodeShuffleStorage.initialize(appContext)
        HomeCatalogSettingsStorage.initialize(appContext)
        PlayerSettingsStorage.initialize(appContext)
        PlayerTrackPreferenceStorage.initialize(appContext)
        P2pSettingsStorage.initialize(appContext)
        P2pStreamingEngine.initialize(appContext)
        ExternalPlayerPlatform.initialize(appContext)
        SubtitleFileCache.initialize(appContext)
        ProfileStorage.initialize(appContext)
        AvatarStorage.initialize(appContext)
        ProfilePinCacheStorage.initialize(appContext)
        MemberAssetStorage.initialize(appContext)
        DiscoverSelectionStorage.initialize(appContext)
        SearchHistoryStorage.initialize(appContext)
        SeasonViewModeStorage.initialize(appContext)
        PosterCardStyleStorage.initialize(appContext)
        CustomPosterUrlStorage.initialize(appContext)
        CardDepthStyleStorage.initialize(appContext)
        DebridSettingsStorage.initialize(appContext)
        TmdbSettingsStorage.initialize(appContext)
        MdbListSettingsStorage.initialize(appContext)
        TraktAuthStorage.initialize(appContext)
        TraktCommentsStorage.initialize(appContext)
        TraktLibraryStorage.initialize(appContext)
        TraktSettingsStorage.initialize(appContext)
        PlatformMdbListAuthPersistence.initialize(appContext)
        ServerStorage.initialize(appContext)
        PlatformMdbListSyncStorage.initialize(appContext)
        SimklAuthStorage.initialize(appContext)
        SimklSyncStorage.initialize(appContext)
        LibraryDisplaySettingsStorage.initialize(appContext)
        ContinueWatchingPreferencesStorage.initialize(appContext)
        ResumePromptStorage.initialize(appContext)
        ContinueWatchingEnrichmentStorage.initialize(appContext)
        EpisodeReleaseNotificationsStorage.initialize(appContext)
        WatchProgressStorage.initialize(appContext)
        StreamLinkCacheStorage.initialize(appContext)
        StreamBadgeSettingsStorage.initialize(appContext)
        BingeGroupCacheStorage.initialize(appContext)
        PluginStorage.initialize(appContext)
        CollectionMobileSettingsStorage.initialize(appContext)
        CollectionStorage.initialize(appContext)
        DownloadsStorage.initialize(appContext)
        DownloadsPlatformDownloader.initialize(appContext)
        DownloadsLiveStatusPlatform.initialize(appContext)
        AndroidAppUpdaterPlatform.initialize(appContext)
        PlatformLocalAccountDataCleaner.initialize(appContext)
        EpisodeReleaseNotificationPlatform.initialize(appContext)
        initialized = true
    }
}
