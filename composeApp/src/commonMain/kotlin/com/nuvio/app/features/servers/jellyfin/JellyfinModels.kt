package com.nuvio.app.features.servers.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class JellyfinPublicInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("Id") val id: String? = null,
)

@Serializable
internal data class JellyfinAuthRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
)

@Serializable
internal data class JellyfinAuthResult(
    @SerialName("User") val user: JellyfinUser? = null,
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
)

@Serializable
internal data class JellyfinUser(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
)

@Serializable
internal data class JellyfinItemsResult(
    @SerialName("Items") val items: List<JellyfinItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
)

@Serializable
internal data class JellyfinItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("EndDate") val endDate: String? = null,
    @SerialName("Status") val status: String? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("Studios") val studios: List<JellyfinNamedItem> = emptyList(),
    @SerialName("People") val people: List<JellyfinPerson> = emptyList(),
    @SerialName("ProviderIds") val providerIds: Map<String, String?> = emptyMap(),
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String> = emptyList(),
    @SerialName("ParentLogoItemId") val parentLogoItemId: String? = null,
    @SerialName("ParentLogoImageTag") val parentLogoImageTag: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryImageTag: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("IndexNumberEnd") val indexNumberEnd: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("LocationType") val locationType: String? = null,
    @SerialName("UserData") val userData: JellyfinUserData? = null,
    @SerialName("MediaSources") val mediaSources: List<JellyfinMediaSource> = emptyList(),
) {
    val isMissing: Boolean
        get() = locationType.equals("Virtual", ignoreCase = true)
}

@Serializable
internal data class JellyfinNamedItem(
    @SerialName("Name") val name: String? = null,
)

@Serializable
internal data class JellyfinPerson(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
)

@Serializable
internal data class JellyfinUserData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
)

@Serializable
internal data class JellyfinMediaSource(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("ETag") val eTag: String? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<JellyfinMediaStream> = emptyList(),
)

@Serializable
internal data class JellyfinMediaStream(
    @SerialName("Type") val type: String? = null,
    @SerialName("Index") val index: Int? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("DeliveryMethod") val deliveryMethod: String? = null,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
)

@Serializable
internal data class JellyfinPlaybackInfoRequest(
    @SerialName("UserId") val userId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String?,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Long,
    @SerialName("EnableDirectPlay") val enableDirectPlay: Boolean = true,
    @SerialName("EnableDirectStream") val enableDirectStream: Boolean = true,
    @SerialName("EnableTranscoding") val enableTranscoding: Boolean = true,
    @SerialName("AllowVideoStreamCopy") val allowVideoStreamCopy: Boolean = true,
    @SerialName("AllowAudioStreamCopy") val allowAudioStreamCopy: Boolean = true,
    @SerialName("AutoOpenLiveStream") val autoOpenLiveStream: Boolean = false,
    @SerialName("DeviceProfile") val deviceProfile: JellyfinDeviceProfile,
)

@Serializable
internal data class JellyfinDeviceProfile(
    @SerialName("Name") val name: String,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Long,
    @SerialName("DirectPlayProfiles") val directPlayProfiles: List<JellyfinDirectPlayProfile>,
    @SerialName("TranscodingProfiles") val transcodingProfiles: List<JellyfinTranscodingProfile>,
    @SerialName("SubtitleProfiles") val subtitleProfiles: List<JellyfinSubtitleProfile>,
)

@Serializable
internal data class JellyfinDirectPlayProfile(
    @SerialName("Type") val type: String = "Video",
    @SerialName("Container") val container: String? = null,
    @SerialName("VideoCodec") val videoCodec: String? = null,
    @SerialName("AudioCodec") val audioCodec: String? = null,
)

@Serializable
internal data class JellyfinTranscodingProfile(
    @SerialName("Type") val type: String = "Video",
    @SerialName("Container") val container: String,
    @SerialName("VideoCodec") val videoCodec: String,
    @SerialName("AudioCodec") val audioCodec: String,
    @SerialName("Protocol") val protocol: String,
    @SerialName("Context") val context: String = "Streaming",
    @SerialName("MaxAudioChannels") val maxAudioChannels: String = "6",
    @SerialName("MinSegments") val minSegments: Int = 1,
    @SerialName("BreakOnNonKeyFrames") val breakOnNonKeyFrames: Boolean = true,
)

@Serializable
internal data class JellyfinSubtitleProfile(
    @SerialName("Format") val format: String,
    @SerialName("Method") val method: String,
)

@Serializable
internal data class JellyfinPlaybackInfoResult(
    @SerialName("MediaSources") val mediaSources: List<JellyfinMediaSource> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
)

@Serializable
internal data class JellyfinPlaybackReport(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String,
    @SerialName("PlaySessionId") val playSessionId: String?,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("IsPaused") val isPaused: Boolean,
    @SerialName("PlayMethod") val playMethod: String,
    @SerialName("CanSeek") val canSeek: Boolean = true,
    @SerialName("EventName") val eventName: String? = null,
)
