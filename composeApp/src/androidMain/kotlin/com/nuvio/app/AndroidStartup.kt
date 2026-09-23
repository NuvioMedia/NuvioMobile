package com.nuvio.app

import android.content.Context
import com.nuvio.app.core.auth.AuthStorage
import com.nuvio.app.core.diagnostics.SentryInitializer
import com.nuvio.app.core.network.ServerConfigurationStorage
import com.nuvio.app.core.poster.CustomPosterUrlStorage
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import com.nuvio.app.core.sync.SyncClientIdentityStorage
import com.nuvio.app.core.ui.CardDepthStyleStorage
import com.nuvio.app.core.ui.PosterCardStyleStorage
import com.nuvio.app.features.addons.AddonHttpClientProvider
import com.nuvio.app.features.addons.AddonStorage
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
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.PlayerTrackPreferenceStorage
import com.nuvio.app.features.player.SubtitleFileCache
import com.nuvio.app.features.plugins.PluginStorage
import com.nuvio.app.features.profiles.AvatarStorage
import com.nuvio.app.features.profiles.ProfilePinCacheStorage
import com.nuvio.app.features.profiles.ProfileStorage
import com.nuvio.app.features.search.DiscoverSelectionStorage
import com.nuvio.app.features.search.SearchHistoryStorage
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
 * Cold-start storage wiring.
 *
 * [initializeForColdStart] is everything the first Compose frame may read.
 * [initializeDeferred] is work that used to block [android.app.Activity.onCreate]
 * after the splash was already up: Sentry, OkHttp disk cache, etc.
 */
internal object AndroidStartup {
    @Volatile
    private var coldStartDone = false

    @Volatile
    private var deferredDone = false

    fun initializeForColdStart(context: Context) {
        if (coldStartDone) return
        coldStartDone = true
        val appContext = context.applicationContext

        ThemeSettingsStorage.initialize(appContext)
        AppIconPlatform.initialize(appContext)
        SyncClientIdentityStorage.initialize(appContext)
        AddonStorage.initialize(appContext)
        AuthStorage.initialize(appContext)
        ServerConfigurationStorage.initialize(appContext)
        LibraryStorage.initialize(appContext)
        WatchedStorage.initialize(appContext)
        MetaScreenSettingsStorage.initialize(appContext)
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
    }

    fun initializeDeferred(context: Context) {
        if (deferredDone) return
        deferredDone = true
        val appContext = context.applicationContext
        SentrySettingsStorage.initialize(appContext)
        AddonHttpClientProvider.initialize(appContext)
        val application = appContext as? android.app.Application
        if (application != null) {
            SentryInitializer.start(application)
        }
    }
}
