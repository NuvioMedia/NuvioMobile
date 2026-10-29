package com.nuvio.app.features.party

import com.nuvio.app.features.streams.StreamBadge
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PartySource(
    @SerialName("info_hash") val infoHash: String? = null,
    @SerialName("file_idx") val fileIdx: Int? = null,
    val filename: String? = null,
    @SerialName("video_size") val videoSize: Long? = null,
    @SerialName("binge_group") val bingeGroup: String? = null,
    @SerialName("addon_id") val addonId: String? = null,
    val name: String? = null,
)

@Serializable
data class PartyMedia(
    val type: String,
    @SerialName("meta_id") val metaId: String,
    @SerialName("meta_type") val metaType: String,
    @SerialName("video_id") val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String = "",
    @SerialName("episode_title") val episodeTitle: String? = null,
    val description: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val source: PartySource? = null,
) {
    val key: String
        get() = partyMediaKey(metaType, metaId, season, episode)
}

@Serializable
data class PartyStream(
    @SerialName("media_key") val mediaKey: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    @SerialName("response_headers") val responseHeaders: Map<String, String> = emptyMap(),
    val name: String? = null,
    val description: String? = null,
    @SerialName("addon_name") val addonName: String = "",
    @SerialName("addon_id") val addonId: String = "",
    @SerialName("addon_logo") val addonLogo: String? = null,
    val filename: String? = null,
    @SerialName("video_size") val videoSize: Long? = null,
    @SerialName("binge_group") val bingeGroup: String? = null,
    @SerialName("stream_type") val streamType: String? = null,
    val badges: List<StreamBadge> = emptyList(),
) {
    fun toStreamItem(): StreamItem = StreamItem(
        name = name,
        description = description,
        url = url,
        addonName = addonName,
        addonId = addonId,
        addonLogo = addonLogo,
        streamType = streamType,
        behaviorHints = StreamBehaviorHints(
            bingeGroup = bingeGroup,
            videoSize = videoSize,
            filename = filename,
            proxyHeaders = StreamProxyHeaders(request = headers, response = responseHeaders),
        ),
        badges = badges,
    )
}

internal fun isShareableStreamUrl(url: String): Boolean = SHAREABLE_URL.containsMatchIn(url)

private val SHAREABLE_URL = Regex("^https?://(?!localhost|127\\.|0\\.0\\.0\\.0|\\[::1])[^\\s/]+", RegexOption.IGNORE_CASE)

internal fun partyMediaKey(metaType: String, metaId: String, season: Int?, episode: Int?): String =
    listOf(metaType.lowercase(), metaId, season?.toString().orEmpty(), episode?.toString().orEmpty())
        .joinToString("|")

@Serializable
data class PartyPlayback(
    val seq: Long,
    @SerialName("member_id") val memberId: String? = null,
    val paused: Boolean,
    @SerialName("position_ms") val positionMs: Long,
    val rate: Float = 1f,
    val at: Long,
    @SerialName("media_key") val mediaKey: String? = null,
    val waiting: Boolean = false,
)

@Serializable
data class PartyMember(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("avatar_color") val avatarColor: String? = null,
)

@Serializable
data class PartyPreview(
    val media: PartyMedia? = null,
    val host: PartyMember? = null,
    val members: Int = 0,
    @SerialName("max_members") val maxMembers: Int = 0,
    val error: String? = null,
) {
    val isFull: Boolean
        get() = maxMembers in 1..members
}

@Serializable
enum class PartyMemberStatus {
    @SerialName("browsing") Browsing,
    @SerialName("loading") Loading,
    @SerialName("buffering") Buffering,
    @SerialName("ready") Ready,
    @SerialName("behind") Behind,
}

@Serializable
data class PartyPresence(
    @SerialName("member_id") val memberId: String,
    val status: PartyMemberStatus = PartyMemberStatus.Browsing,
    @SerialName("media_key") val mediaKey: String? = null,
    @SerialName("behind_ms") val behindMs: Long? = null,
)

@Serializable
internal data class PartyInfo(
    val id: String,
    val code: String,
    @SerialName("host_member_id") val hostMemberId: String? = null,
    @SerialName("host_only") val hostOnly: Boolean = false,
    val media: PartyMedia,
    val playback: PartyPlayback? = null,
    @SerialName("max_members") val maxMembers: Int = 3,
)

@Serializable
internal data class PartySnapshot(
    val party: PartyInfo? = null,
    val members: List<PartyMember> = emptyList(),
    @SerialName("member_id") val memberId: String? = null,
    @SerialName("server_time_ms") val serverTimeMs: Long? = null,
    val error: String? = null,
    val ok: Boolean? = null,
)

@Serializable
internal data class PartyChatPayload(
    val id: Long,
    @SerialName("member_id") val memberId: String,
    val name: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("avatar_color") val avatarColor: String? = null,
    val body: String,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("sent_at") val sentAt: Long = 0L,
    val mine: Boolean = false,
)

@Serializable
internal data class PartyChatSendResult(
    val message: PartyChatPayload? = null,
    val error: String? = null,
)

@Serializable
internal data class PartyChatHistory(
    val messages: List<PartyChatPayload> = emptyList(),
    val error: String? = null,
)

enum class PartyError {
    NotFound,
    Full,
    Removed,
    Ended,
    RateLimited,
    SignInRequired,
    Unavailable,
    Network,
}

internal fun partyErrorOf(code: String?): PartyError? = when (code) {
    null -> null
    "party_not_found" -> PartyError.NotFound
    "party_full" -> PartyError.Full
    "removed", "not_member" -> PartyError.Removed
    "party_ended" -> PartyError.Ended
    "rate_limited" -> PartyError.RateLimited
    "unavailable" -> PartyError.Unavailable
    else -> PartyError.Network
}

data class ActiveParty(
    val id: String,
    val code: String,
    val memberId: String,
    val hostMemberId: String?,
    val members: List<PartyMember>,
    val presence: Map<String, PartyPresence>,
    val media: PartyMedia,
    val maxMembers: Int,
    val connected: Boolean,
    val hostOnly: Boolean = false,
    val sharing: Boolean = false,
) {
    val isHost: Boolean
        get() = memberId == hostMemberId

    val canControl: Boolean
        get() = !hostOnly || isHost

    fun member(id: String?): PartyMember? = members.firstOrNull { it.id == id }
}

data class PartyUiState(
    val party: ActiveParty? = null,
    val busy: Boolean = false,
)

sealed interface PartyEvent {
    data class MemberJoined(val name: String) : PartyEvent
    data class MemberLeft(val name: String) : PartyEvent
    data object Removed : PartyEvent
    data object Ended : PartyEvent
    data class MediaChanged(val media: PartyMedia) : PartyEvent
    data class ControlChanged(val hostOnly: Boolean) : PartyEvent
}

enum class PartyAction {
    Paused,
    Resumed,
    Seeked,
    Speed,
}

data class PartyNotice(
    val name: String,
    val action: PartyAction,
    val positionMs: Long,
    val rate: Float,
)
