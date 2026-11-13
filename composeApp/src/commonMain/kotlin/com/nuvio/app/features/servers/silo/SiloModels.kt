package com.nuvio.app.features.servers.silo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A Silo server found at an address, with the ways it lets a device sign in. */
internal class SiloServer(
    val address: String,
    val serverId: String,
    val name: String,
    val passwordSignIn: Boolean,
    val codeSignIn: Boolean,
    internal val cancelsCodes: Boolean
) {
    override fun toString(): String = "SiloServer(serverId=$serverId)"
}

/** A sign-in code, approved on another device at [link]. */
internal class SiloCode(
    internal val deviceCode: String,
    val userCode: String,
    val link: String,
    val linkWithCode: String,
    val expiresAtMs: Long,
    internal val serverExpiresAt: String?,
    val pollEveryMs: Long
) {
    override fun toString(): String = "SiloCode(expiresAtMs=$expiresAtMs)"

    /** Silo's eight digit codes read best in two groups of four. */
    val shownCode: String
        get() = userCode.filter(Char::isDigit).let { digits ->
            if (digits.length == 8) "${digits.take(4)} ${digits.drop(4)}" else userCode
        }
}

internal sealed interface SiloCodeStatus {
    /** [expiresAtMs] moves later while someone has the code open. */
    data class Waiting(val opened: Boolean, val expiresAtMs: Long, val pollAfterMs: Long) : SiloCodeStatus

    class Approved(val tokens: SiloTokens) : SiloCodeStatus {
        override fun toString(): String = "Approved"
    }

    data class Ended(val reason: SiloCodeEnd) : SiloCodeStatus
}

internal enum class SiloCodeEnd { EXPIRED, DENIED, CANCELED, USED }

internal class SiloTokens(
    internal val access: String,
    internal val refresh: String,
    internal val issuedAtMs: Long,
    internal val accessExpiresAtMs: Long,
    val userId: String,
    val userName: String,
    val passwordChangeNeeded: Boolean
) {
    override fun toString(): String = "SiloTokens(userId=$userId)"
}

internal data class SiloProfile(
    val id: String,
    val name: String,
    val hasPin: Boolean,
    val isChild: Boolean,
    val isPrimary: Boolean
)

/**
 * What a Silo connection keeps as its token: the token pair and the chosen profile. [signInId] names the sign-in, so
 * devices sharing it through the Nuvio account can tell it from a newer one; [syncedAtMs] is when it was last uploaded.
 */
@Serializable
internal data class SiloCredential(
    @SerialName("access") val access: String = "",
    @SerialName("refresh") val refresh: String,
    @SerialName("issued_at_ms") val issuedAtMs: Long = 0L,
    @SerialName("expires_at_ms") val accessExpiresAtMs: Long = 0L,
    @SerialName("profile_id") val profileId: String,
    @SerialName("profile_token") val profileToken: String? = null,
    @SerialName("sign_in_id") val signInId: String = "",
    @SerialName("synced_at_ms") val syncedAtMs: Long = 0L
) {
    override fun toString(): String = "SiloCredential(profileId=$profileId)"
}

@Serializable
internal data class SystemInfoWire(@SerialName("api_major") val apiMajor: Int? = null)

@Serializable
internal data class IdentityWire(@SerialName("server_id") val serverId: String = "")

@Serializable
internal data class BrandingWire(@SerialName("server_name") val serverName: String? = null)

@Serializable
internal data class ProvidersWire(
    val items: List<ProviderWire> = emptyList(),
    @SerialName("password_login") val passwordLogin: Boolean = false
)

@Serializable
internal data class ProviderWire(val mode: String = "")

@Serializable
internal data class DeviceCapabilityWire(
    val state: String = "",
    val cancel: Boolean = false
)

@Serializable
internal data class DeviceStartBody(
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_platform") val devicePlatform: String = "android-tv"
)

@Serializable
internal data class DeviceStartWire(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("verification_uri_complete") val verificationUriComplete: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 900,
    val interval: Long = 3
)

@Serializable
internal data class DeviceCodeBody(@SerialName("device_code") val deviceCode: String)

@Serializable
internal data class DevicePollWire(
    val status: String = "",
    @SerialName("poll_after") val pollAfter: Long? = null,
    val opened: Boolean = false,
    @SerialName("expires_at") val expiresAt: String? = null,
    val tokens: TokenPairWire? = null
)

@Serializable
internal data class LoginBody(val username: String, val password: String)

@Serializable
internal data class TokenPairWire(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    val user: AccountWire? = null
)

@Serializable
internal data class AccountWire(
    val id: String = "",
    val username: String = "",
    @SerialName("password_change_required") val passwordChangeRequired: Boolean = false
)

@Serializable
internal data class RefreshBody(@SerialName("refresh_token") val refreshToken: String)

@Serializable
internal data class ProfilesWire(val items: List<ProfileWire> = emptyList())

@Serializable
internal data class ProfileWire(
    val id: String = "",
    val name: String = "",
    @SerialName("has_pin") val hasPin: Boolean = false,
    @SerialName("is_child") val isChild: Boolean = false,
    @SerialName("is_primary") val isPrimary: Boolean = false
)

@Serializable
internal data class PinBody(val pin: String)

@Serializable
internal data class PinWire(
    val valid: Boolean = false,
    @SerialName("profile_token") val profileToken: String? = null
)

@Serializable
internal data class UserLibrariesWire(val items: List<UserLibraryWire> = emptyList())

@Serializable
internal data class UserLibraryWire(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    @SerialName("sort_order") val sortOrder: Long = 0
)

@Serializable
internal data class CatalogPageWire(
    val items: List<CatalogItemWire> = emptyList(),
    val page: PageWire? = null,
    val total: Int? = null,
    @SerialName("total_exact") val totalExact: Boolean = false,
    @SerialName("window_cursor") val windowCursor: String? = null
)

@Serializable
internal data class PageWire(
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null
)

@Serializable
internal data class CatalogItemWire(
    @SerialName("content_id") val contentId: String = "",
    val type: String = "",
    val title: String = "",
    val year: Int? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("last_air_date") val lastAirDate: String? = null,
    @SerialName("show_status") val showStatus: String? = null,
    val runtime: Int? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    val overview: String? = null,
    @SerialName("content_rating") val contentRating: String? = null,
    @SerialName("rating_imdb") val ratingImdb: Double? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("series_poster_url") val seriesPosterUrl: String? = null,
    @SerialName("series_backdrop_url") val seriesBackdropUrl: String? = null,
    val genres: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    @SerialName("user_state") val userState: UserStateWire? = null,
    @SerialName("user_data") val userData: UserDataWire? = null,
    @SerialName("position_seconds") val positionSeconds: Double? = null,
    @SerialName("progress_updated_at") val progressUpdatedAt: String? = null,
    @SerialName("item_source") val itemSource: String? = null,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("series_title") val seriesTitle: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tmdb_id") val tmdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: String? = null,
    val cast: List<PersonWire> = emptyList(),
    val crew: List<PersonWire> = emptyList()
)

@Serializable
internal data class UserStateWire(val played: Boolean = false)

@Serializable
internal data class UserDataWire(
    val played: Boolean = false,
    @SerialName("position_seconds") val positionSeconds: Double? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null
)

@Serializable
internal data class PersonWire(
    val name: String = "",
    val character: String? = null,
    val job: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null
)

@Serializable
internal data class SeasonsWire(val items: List<SeasonWire> = emptyList())

@Serializable
internal data class SeasonWire(@SerialName("season_number") val seasonNumber: Int = 0)

@Serializable
internal data class EpisodesWire(val items: List<EpisodeWire> = emptyList())

@Serializable
internal data class EpisodeWire(
    @SerialName("content_id") val contentId: String = "",
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_number") val episodeNumber: Int = 0,
    val title: String = "",
    val runtime: Int? = null,
    @SerialName("air_date") val airDate: String? = null,
    val overview: String? = null,
    @SerialName("still_url") val stillUrl: String? = null,
    @SerialName("user_data") val userData: UserDataWire? = null
)

@Serializable
internal data class HomeLayoutWire(val sections: List<HomeSectionWire> = emptyList())

@Serializable
internal data class HomeSectionWire(
    val id: String = "",
    @SerialName("section_type") val sectionType: String = "",
    val items: List<CatalogItemWire> = emptyList()
)
