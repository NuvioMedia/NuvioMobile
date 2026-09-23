package com.nuvio.app.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute : NavKey {
    val title: String?
        get() = null

    val subtitle: String?
        get() = null

    /** Full-screen destinations such as the video player keep native navigation chrome hidden. */
    val hidesNavigationBar: Boolean
        get() = false

    /** Stable enough to apply Navigation 3 launchSingleTop semantics in SwiftUI. */
    val navigationIdentity: String
        get() = toString()

    /** Lets an explicitly cross-tab route select its native SwiftUI stack. */
    val preferredTabName: String?
        get() = null
}

@Serializable
sealed interface SettingsDestinationRoute : AppRoute {
    override val preferredTabName: String
        get() = "Settings"
}

@Serializable
data object TabsRoute : AppRoute

@Serializable
data class DetailRoute(
    val type: String,
    val id: String,
    override val title: String? = null,
    val initialSeasonNumber: Int? = null,
    val initialEpisodeNumber: Int? = null,
) : AppRoute

@Serializable
data class PersonDetailRoute(
    val personId: Int,
    val personName: String,
    val personPhoto: String? = null,
    val castAvatarTransitionKey: String? = null,
    val preferCrew: Boolean = false,
) : AppRoute {
    override val title: String
        get() = personName
}

@Serializable
data class EntityBrowseRoute(
    val entityKind: String,
    val entityId: Int,
    val entityName: String,
    val sourceType: String = "tv",
) : AppRoute {
    override val title: String
        get() = entityName
}

/** A settings leaf promoted from the former in-screen page state machine. */
@Serializable
data class SettingsPageRoute(
    val pageName: String,
    override val title: String,
) : SettingsDestinationRoute

@Serializable
data class HomescreenSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class MetaScreenSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class ContinueWatchingSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class DownloadsSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class DownloadShowRoute(
    val showId: String,
    override val title: String,
) : AppRoute

@Serializable
data class AddonsSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class PluginsSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class AccountSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class SupportersContributorsSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class LicensesAttributionsSettingsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class CollectionsRoute(override val title: String = "") : SettingsDestinationRoute

@Serializable
data class CollectionEditorRoute(
    val collectionId: String? = null,
    override val title: String = "",
) : AppRoute

@Serializable
data class CollectionEditorPageRoute(
    val collectionId: String? = null,
    val pageName: String,
    override val title: String,
) : AppRoute

@Serializable
data class FolderDetailRoute(
    val collectionId: String,
    val folderId: String,
    override val title: String = "",
) : AppRoute

@Serializable
data class StreamRoute(
    val launchId: Long,
    override val title: String = "",
) : AppRoute

@Serializable
data class CatalogRoute(
    val launchId: Long,
    override val title: String = "",
    override val subtitle: String? = null,
) : AppRoute

@Serializable
data class PlayerRoute(
    val launchId: Long,
    override val title: String = "",
) : AppRoute {
    override val hidesNavigationBar: Boolean
        get() = true
}
