package com.nuvio.app.features.servers.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A plex.tv sign-in code: a short one is typed at plex.tv/link on another device, a long one is approved in the browser
 * on this device.
 */
class PlexPin(
    val id: Long,
    val code: String,
    val expiresAtMs: Long,
    val strong: Boolean,
) {
    override fun toString(): String = "PlexPin(id=$id)"
}

sealed interface PlexPinStatus {
    data object Waiting : PlexPinStatus
    data object Expired : PlexPinStatus
    class Linked(val accountToken: String) : PlexPinStatus {
        override fun toString(): String = "Linked"
    }
}

/** A signed-in Plex account or Plex Home user, with its own token. */
class PlexAccount(
    val token: String,
    val id: String,
    val uuid: String,
    val name: String
) {
    override fun toString(): String = "PlexAccount(id=$id)"
}

enum class PlexReach { LOCAL, REMOTE, RELAY, OFFLINE }

/** One address of a server as plex.tv lists it. */
data class PlexAddress(
    val uri: String,
    val local: Boolean,
    val relay: Boolean
)

class PlexServer(
    val machineId: String,
    val name: String,
    val owned: Boolean,
    val accessToken: String,
    val addresses: List<PlexAddress>,
    val reach: PlexReach = PlexReach.OFFLINE,
    /** The best address that answered: home network first, then internet, then Plex Relay. */
    val address: String? = null
) {
    fun reached(reach: PlexReach, address: String?) =
        PlexServer(machineId, name, owned, accessToken, addresses, reach, address)

    override fun toString(): String = "PlexServer(machineId=$machineId, reach=$reach)"
}

class PlexHomeUser(
    val id: String,
    val uuid: String,
    val name: String,
    val hasPin: Boolean,
    val admin: Boolean,
    val restricted: Boolean
) {
    override fun toString(): String = "PlexHomeUser(id=$id)"
}

@Serializable
internal data class PinWire(
    val id: Long = 0,
    val code: String = "",
    @SerialName("expiresIn") val expiresIn: Long = 0,
    @SerialName("authToken") val authToken: String? = null
)

@Serializable
internal data class AccountWire(
    val id: Long = 0,
    val uuid: String = "",
    val username: String = "",
    val title: String = "",
    @SerialName("authToken") val authToken: String? = null
)

@Serializable
internal data class ResourceWire(
    val name: String = "",
    val provides: String = "",
    @SerialName("clientIdentifier") val clientIdentifier: String = "",
    val owned: Boolean = false,
    @SerialName("accessToken") val accessToken: String? = null,
    val connections: List<ConnectionWire> = emptyList()
)

@Serializable
internal data class ConnectionWire(
    val address: String = "",
    val port: Int = 0,
    val uri: String = "",
    val local: Boolean = false,
    val relay: Boolean = false,
    @SerialName("IPv6") val ipv6: Boolean = false
)

@Serializable
internal data class HomeUsersWire(val users: List<HomeUserWire> = emptyList())

@Serializable
internal data class HomeUserWire(
    val id: Long = 0,
    val uuid: String = "",
    val title: String = "",
    val username: String? = null,
    val admin: Boolean = false,
    val restricted: Boolean = false,
    val protected: Boolean = false
)

@Serializable
internal data class ContainerReply(@SerialName("MediaContainer") val container: MediaContainerWire = MediaContainerWire())

@Serializable
internal data class MediaContainerWire(
    val totalSize: Int? = null,
    val machineIdentifier: String? = null,
    @SerialName("generalDecisionCode") val generalDecisionCode: Int? = null,
    @SerialName("generalDecisionText") val generalDecisionText: String? = null,
    @SerialName("transcodeDecisionText") val transcodeDecisionText: String? = null,
    @SerialName("directPlayDecisionText") val directPlayDecisionText: String? = null,
    @SerialName("Directory") val directories: List<DirectoryWire> = emptyList(),
    @SerialName("Metadata") val metadata: List<MetadataWire> = emptyList(),
    @SerialName("Hub") val hubs: List<HubWire> = emptyList()
)

@Serializable
internal data class DirectoryWire(
    val key: String = "",
    val title: String = "",
    val type: String = ""
)

@Serializable
internal data class HubWire(@SerialName("Metadata") val metadata: List<MetadataWire> = emptyList())

@Serializable
internal data class MetadataWire(
    @SerialName("ratingKey") val ratingKey: String = "",
    val key: String = "",
    val type: String = "",
    val title: String = "",
    val year: Int? = null,
    @SerialName("editionTitle") val editionTitle: String? = null,
    val summary: String? = null,
    val thumb: String? = null,
    val art: String? = null,
    @SerialName("grandparentThumb") val grandparentThumb: String? = null,
    @SerialName("grandparentArt") val grandparentArt: String? = null,
    @SerialName("grandparentTitle") val grandparentTitle: String? = null,
    @SerialName("grandparentRatingKey") val grandparentRatingKey: String? = null,
    @SerialName("parentIndex") val parentIndex: Int? = null,
    val index: Int? = null,
    val duration: Long? = null,
    @SerialName("viewOffset") val viewOffset: Long? = null,
    @SerialName("viewCount") val viewCount: Int? = null,
    @SerialName("lastViewedAt") val lastViewedAt: Long? = null,
    @SerialName("originallyAvailableAt") val originallyAvailableAt: String? = null,
    @SerialName("contentRating") val contentRating: String? = null,
    @SerialName("audienceRating") val audienceRating: Double? = null,
    val rating: Double? = null,
    @SerialName("librarySectionID") val librarySectionId: Long? = null,
    val guid: String? = null,
    @SerialName("Guid") val guids: List<TagWire> = emptyList(),
    @SerialName("Genre") val genres: List<TagWire> = emptyList(),
    @SerialName("Country") val countries: List<TagWire> = emptyList(),
    @SerialName("Director") val directors: List<TagWire> = emptyList(),
    @SerialName("Writer") val writers: List<TagWire> = emptyList(),
    @SerialName("Role") val roles: List<RoleWire> = emptyList(),
    @SerialName("Image") val images: List<ImageWire> = emptyList(),
    @SerialName("Media") val media: List<MediaWire> = emptyList()
)

@Serializable
internal data class TagWire(val id: String? = null, val tag: String? = null)

@Serializable
internal data class RoleWire(val tag: String = "", val role: String? = null, val thumb: String? = null)

@Serializable
internal data class ImageWire(val type: String = "", val url: String = "")

@Serializable
internal data class MediaWire(
    val id: Long = 0,
    val duration: Long? = null,
    @SerialName("videoResolution") val videoResolution: String? = null,
    @SerialName("videoCodec") val videoCodec: String? = null,
    @SerialName("audioCodec") val audioCodec: String? = null,
    @SerialName("audioChannels") val audioChannels: Int? = null,
    val container: String? = null,
    val title: String? = null,
    @SerialName("Part") val parts: List<PartWire> = emptyList()
)

@Serializable
internal data class PartWire(
    val id: Long = 0,
    val key: String = "",
    val file: String? = null,
    val size: Long? = null,
    @SerialName("Stream") val streams: List<StreamWire> = emptyList()
)

@Serializable
internal data class StreamWire(
    val id: Long = 0,
    @SerialName("streamType") val streamType: Int = 0,
    val codec: String? = null,
    val key: String? = null,
    val language: String? = null,
    @SerialName("languageCode") val languageCode: String? = null,
    @SerialName("displayTitle") val displayTitle: String? = null,
    @SerialName("extendedDisplayTitle") val extendedDisplayTitle: String? = null,
    val title: String? = null,
    val selected: Boolean = false,
    /** In a conversion decision: "copy" when the stream is passed through, "transcode" when it is converted. */
    val decision: String? = null
)

internal const val STREAM_VIDEO = 1
internal const val STREAM_AUDIO = 2
internal const val STREAM_SUBTITLE = 3
