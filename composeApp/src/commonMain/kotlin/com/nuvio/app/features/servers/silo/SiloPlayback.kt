package com.nuvio.app.features.servers.silo

import kotlinx.atomicfu.atomic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class PlaybackCapabilitiesWire(
    @SerialName("installation_id") val installationId: String? = null,
    val state: String = "",
    val allowed: Boolean = false,
    @SerialName("protocol_versions") val protocolVersions: List<Int> = emptyList(),
    val features: List<String> = emptyList(),
    val deliveries: List<String> = emptyList()
)

@Serializable
internal data class WatchStateWire(
    @SerialName("content_id") val contentId: String = "",
    val versions: List<WatchVersionWire> = emptyList(),
    @SerialName("user_data") val userData: WatchUserDataWire? = null
)

@Serializable
internal data class WatchUserDataWire(@SerialName("last_file_id") val lastFileId: String? = null)

@Serializable
internal data class WatchVersionWire(
    @SerialName("file_id") val fileId: String = "",
    val resolution: String = "",
    @SerialName("codec_video") val codecVideo: String = "",
    @SerialName("codec_audio") val codecAudio: String = "",
    val container: String = "",
    val hdr: Boolean = false,
    @SerialName("file_size") val fileSize: Long? = null,
    /** Kilobits per second. */
    val bitrate: Long? = null,
    @SerialName("duration_seconds") val durationSeconds: Long? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("edition_raw") val editionRaw: String? = null,
    @SerialName("video_tracks") val videoTracks: List<VideoTrackWire> = emptyList(),
    @SerialName("audio_tracks") val audioTracks: List<AudioTrackWire> = emptyList(),
)

@Serializable
internal data class VideoTrackWire(
    val codec: String? = null,
    @SerialName("video_range_type") val videoRangeType: String? = null,
    @SerialName("dv_profile") val dvProfile: Int? = null,
    @SerialName("dv_bl_compat_id") val dvBlCompatId: Int? = null
)

@Serializable
internal data class AudioTrackWire(
    val codec: String? = null,
    val profile: String? = null,
    val layout: String? = null,
    val channels: Int? = null,
    val language: String? = null,
    val title: String? = null,
    val default: Boolean = false
)

@Serializable
internal data class DecisionWire(
    val outcome: String = "",
    @SerialName("session_id") val sessionId: String? = null,
    val terminal: TerminalWire? = null,
    @SerialName("playback_plan") val plan: PlanWire? = null
)

@Serializable
internal data class TerminalWire(
    val reason: String = "",
    val message: String = "",
    val retryable: Boolean = false
)

@Serializable
internal data class PlanWire(
    @SerialName("plan_id") val planId: String = "",
    @SerialName("plan_attempt_key") val planAttemptKey: String = "",
    val delivery: String = "",
    val stream: StreamWire = StreamWire(),
    val timeline: TimelineWire = TimelineWire(),
    @SerialName("selected_tracks") val selectedTracks: SelectedTracksWire = SelectedTracksWire(),
    val subtitle: PlanSubtitleWire = PlanSubtitleWire(),
    @SerialName("degradation_warnings") val degradationWarnings: List<WarningWire> = emptyList(),
    @SerialName("decision_reason") val decisionReason: String? = null,
    @SerialName("effective_media_file_id") val effectiveMediaFileId: String? = null,
    val source: PlanSourceWire = PlanSourceWire()
)

@Serializable
internal data class PlanSourceWire(@SerialName("duration_seconds") val durationSeconds: Double? = null)

@Serializable
internal data class StreamWire(val url: String = "", val protocol: String = "")

@Serializable
internal data class TimelineWire(
    @SerialName("timeline_offset_seconds") val timelineOffsetSeconds: Double = 0.0,
    @SerialName("can_seek_anywhere") val canSeekAnywhere: Boolean = true
)

@Serializable
internal data class SelectedTracksWire(val audio: SelectedTrackWire? = null, val subtitle: SelectedTrackWire? = null)

@Serializable
internal data class SelectedTrackWire(val id: String = "", val index: Int? = null)

@Serializable
internal data class PlanSubtitleWire(val inventory: List<SubtitleEntryWire> = emptyList())

@Serializable
internal data class SubtitleEntryWire(
    @SerialName("track_id") val trackId: String = "",
    @SerialName("combined_index") val combinedIndex: Int = 0,
    val source: String = "",
    val codec: String? = null,
    val language: String? = null,
    val label: String? = null,
    val forced: Boolean = false,
    val delivery: String = "",
    val url: String? = null,
    val unplayable: Boolean = false
) {
    /** Older servers don't send the flag; an embedded track they can't name has no route either. */
    fun unplayable(): Boolean = unplayable || (source == "embedded" && codec.isNullOrBlank())
}

@Serializable
internal data class WarningWire(val code: String = "")

/** What a Silo playback needs to keep reporting to the server after it started. */
internal class SiloPlayState(
    val installationId: String,
    val itemId: String,
    val timelineOffsetSeconds: Double
) {
    private val sequence = atomic(0L)

    fun nextSequence(): Long = sequence.incrementAndGet()
}

/** What the device's player can take straight from the file; the same lists the Jellyfin and Emby profiles use. */
internal class SiloDirectSupport(
    val containers: List<String>,
    val videoCodecs: List<String>,
    val audioCodecs: List<String>,
    val dolbyVisionProfiles: List<Int>,
) {
    companion object {
        /** mpv (iOS, and Android with the mpv engine) plays any file. */
        val ANY = SiloDirectSupport(DIRECT_CONTAINERS, DIRECT_VIDEO_CODECS, DIRECT_AUDIO_CODECS, listOf(4, 5, 7, 8))

        /** ExoPlayer on a phone: what the Jellyfin and Emby profiles direct-play there. */
        val EXOPLAYER = SiloDirectSupport(
            containers = listOf("mp4", "m4v", "mkv", "matroska", "webm", "mov"),
            videoCodecs = listOf("h264", "hevc", "vp8", "vp9", "av1"),
            audioCodecs = listOf("aac", "mp3", "ac3", "eac3", "flac", "opus", "vorbis"),
            dolbyVisionProfiles = listOf(8),
        )
    }
}

/**
 * What the player asks Silo for. Direct playback asks for the original file when [support] covers it, and falls back
 * to a plain HLS conversion when the player can't play it. Silo plays the source on "auto"; servers from before mid
 * October 2026 need [legacyQuality] ("original" and the `metered` flag they still require).
 */
internal fun siloStartBody(
    installationId: String,
    fileId: String,
    profileId: String,
    attemptId: String,
    direct: Boolean,
    audioIndex: Int?,
    subtitleIndex: Int?,
    platform: String,
    appVersion: String,
    support: SiloDirectSupport = SiloDirectSupport.ANY,
    legacyQuality: Boolean = false,
): JsonObject = buildJsonObject {
    put("installation_id", installationId)
    put("protocol_version", PROTOCOL_VERSION)
    put("client_features", strings(listOf("playback_plan_v3", "direct_stream_resume_v1")))
    put("file_id", fileId)
    put("profile_id", profileId)
    put("playback_attempt_id", attemptId)
    put("quality_preference", if (legacyQuality) "original" else "auto")
    put("subtitle_fidelity_preference", if (direct) "preserve" else "compatible")
    if (legacyQuality) put("metered", false)
    put("start_position", 0)
    audioIndex?.let { put("audio_track_index", it) }
    subtitleIndex?.let { put("subtitle_track_index", it) }
    val videoCodecs = if (direct) support.videoCodecs else HLS_VIDEO_CODECS
    val audioCodecs = if (direct) support.audioCodecs else HLS_AUDIO_CODECS
    val dolbyVision = if (direct) support.dolbyVisionProfiles else emptyList()
    val hdr = hdrDetails(direct, dolbyVision)
    put("client_capabilities", buildJsonObject {
        put("video_evidence", "exact")
        put("audio_evidence", "exact")
        put("codecs_video", strings(videoCodecs + if (dolbyVision.isNotEmpty()) listOf("dolby_vision") else emptyList()))
        put("codecs_video_hardware", strings(videoCodecs + if (dolbyVision.isNotEmpty()) listOf("dolby_vision") else emptyList()))
        put("codecs_audio", strings(audioCodecs))
        put("containers", strings(if (direct) support.containers else HLS_CONTAINERS))
        put("max_resolution", "2160p")
        put("hdr", direct)
        put("hdr_details", hdr)
        put("video_decode", JsonArray(videoCodecs.map { codec ->
            buildJsonObject {
                put("codec", codec)
                put("hardware", true)
                put("bit_depths", JsonArray(listOf(8, 10, 12).map(::JsonPrimitive)))
            }
        }))
    })
    put("client_playback_context", buildJsonObject {
        put("protocol_version", PROTOCOL_VERSION)
        put("form_factor", "mobile")
        put("app_version", appVersion.take(64).ifBlank { "1" })
        put("device", buildJsonObject {
            put("platform", platform)
        })
        put("output", buildJsonObject {
            put("hdr_details", hdr)
            put("output_context_id", "1")
        })
        put("deliveries", buildJsonObject {
            if (direct) {
                put("original_http", delivery(support.containers, videoCodecs, audioCodecs, direct = true))
            }
            put("hls", delivery(HLS_CONTAINERS, HLS_VIDEO_CODECS, HLS_AUDIO_CODECS, direct = false))
        })
    })
}

private fun delivery(containers: List<String>, video: List<String>, audio: List<String>, direct: Boolean): JsonObject =
    buildJsonObject {
        put("enabled", true)
        put("supported_on_device", true)
        put("containers", strings(containers))
        put("video_codecs", strings(video))
        put("audio_decode_codecs", strings(audio))
        put("audio_passthrough_codecs", strings(emptyList()))
        put("subtitles", buildJsonObject {
            put("embedded_text", direct)
            put("sidecar_text", true)
            put("ass_styling", direct)
            put("embedded_bitmap", direct)
            put("sidecar_bitmap", false)
            put("font_attachments", false)
        })
        put("features", strings(if (direct) emptyList() else listOf("hls")))
        put("auth_header_refresh", false)
        put(
            "validated_claims",
            strings(if (direct) listOf("client_managed_dynamic_range_v1", "client_selected_audio_track_v1") else emptyList())
        )
        put("transformations", JsonArray(emptyList()))
    }

private fun hdrDetails(supported: Boolean, dolbyVision: List<Int>): JsonObject = buildJsonObject {
    put("hdr10", supported)
    put("hdr10_plus", supported)
    put("hlg", supported)
    put("dolby_vision_profiles", JsonArray(dolbyVision.map(::JsonPrimitive)))
}

private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

/**
 * Asks Silo for another route than [plan], one the player can seek in by itself. A copy remux (the file repackaged
 * from a keyframe) can only be moved by asking Silo again for every seek, which the app's player does not do.
 */
internal fun siloReplanBody(
    start: JsonObject,
    plan: PlanWire,
    attemptedKeys: List<String>,
    replanId: String
): JsonObject = buildJsonObject {
    listOf(
        "installation_id", "protocol_version", "client_features", "playback_attempt_id", "quality_preference", "metered"
    ).forEach { key -> start[key]?.let { put(key, it) } }
    put("operation", "failure_recovery")
    put("replan_request_id", replanId)
    put("failed_plan_id", plan.planId)
    put("plan_attempt_id", replanId)
    put("plan_attempt_key", plan.planAttemptKey)
    put("attempted_plan_keys", strings(attemptedKeys.takeLast(MAX_ATTEMPTED_KEYS)))
    put("attempt_count", attemptedKeys.size.coerceIn(1, MAX_ATTEMPTS))
    put("position_seconds", 0)
    put("selected_tracks", buildJsonObject {
        plan.selectedTracks.audio?.let { put("audio", it.toJson()) }
        plan.selectedTracks.subtitle?.let { put("subtitle", it.toJson()) }
    })
    put("failure", buildJsonObject { put("classification", "player_failure") })
    start["client_capabilities"]?.let { put("client_capabilities", it) }
    start["client_playback_context"]?.let { put("client_playback_context", it) }
}

private fun SelectedTrackWire.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    index?.let { put("index", it) }
}

internal fun siloStopBody(installationId: String, stopId: String, sequence: Long, positionSeconds: Double): JsonObject =
    buildJsonObject {
        put("installation_id", installationId)
        put("stop_id", stopId)
        put("sequence", sequence)
        put("position", positionSeconds)
        put("is_paused", true)
    }

internal fun siloProgressBody(installationId: String, sequence: Long, positionSeconds: Double, paused: Boolean): JsonObject =
    buildJsonObject {
        put("installation_id", installationId)
        put("sequence", sequence)
        put("position", positionSeconds)
        put("is_paused", paused)
    }

/** A short line for a version: codec and the Dolby Vision / HDR kind, as the other servers show it. */
internal fun WatchVersionWire.videoLabel(): String? {
    val track = videoTracks.firstOrNull()
    val codec = when (codecVideo.lowercase()) {
        "hevc", "h265" -> "HEVC"
        "h264", "avc" -> "AVC"
        "" -> null
        else -> codecVideo.uppercase()
    }
    val range = when {
        track?.dvProfile != null -> {
            val compat = track.dvBlCompatId?.takeIf { it > 0 }
            if (compat != null) "DV P${track.dvProfile}.$compat" else "DV P${track.dvProfile}"
        }
        track?.videoRangeType?.contains("HDR10Plus", ignoreCase = true) == true -> "HDR10+"
        track?.videoRangeType.equals("HLG", ignoreCase = true) -> "HLG"
        hdr -> "HDR10"
        else -> null
    }
    return listOfNotNull(codec, range).joinToString(" • ").ifEmpty { null }
}

internal fun AudioTrackWire.label(): String {
    val name = when (codec?.lowercase()) {
        "truehd" -> "TrueHD"
        "eac3" -> "EAC3"
        "ac3" -> "AC3"
        "dts" -> profile?.takeIf { it.contains("MA", ignoreCase = true) }?.let { "DTS-HD MA" } ?: "DTS"
        "aac" -> "AAC"
        "flac" -> "FLAC"
        null, "" -> null
        else -> codec?.uppercase()
    }
    val atmos = listOfNotNull(profile, title).any { it.contains("Atmos", ignoreCase = true) }
    val channels = layout?.substringBefore('(')?.takeIf { it.isNotBlank() && it != "stereo" } ?: if (layout == "stereo") "2.0" else null
    val language = language?.takeIf { it.isNotBlank() && it.lowercase() !in NO_LANGUAGE_CODES }
    return listOfNotNull(language, name?.let { if (atmos) "$it Atmos" else it }, channels).joinToString(" ").ifEmpty { title ?: "Audio" }
}

private val NO_LANGUAGE_CODES = setOf("und", "mul", "zxx", "mis")
private const val PROTOCOL_VERSION = 3
private const val MAX_ATTEMPTED_KEYS = 16
private const val MAX_ATTEMPTS = 8
private val DIRECT_VIDEO_CODECS = listOf("h264", "hevc", "av1", "vp9", "vp8", "mpeg2video", "mpeg4", "vc1")
private val HLS_VIDEO_CODECS = listOf("h264", "hevc")
private val DIRECT_AUDIO_CODECS = listOf(
    "aac", "ac3", "eac3", "truehd", "mlp", "dts", "flac", "alac", "opus", "vorbis", "mp3", "mp2",
    "pcm_s16le", "pcm_s24le", "pcm_s16be", "pcm_s24be", "pcm_bluray", "pcm_dvd"
)
private val HLS_AUDIO_CODECS = listOf("aac", "ac3", "eac3")
private val DIRECT_CONTAINERS = listOf("mkv", "matroska", "mp4", "m4v", "mov", "webm", "avi", "ts", "mpegts", "m2ts", "wmv", "asf", "flv", "ogg")
private val HLS_CONTAINERS = listOf("m3u8", "hls", "ts", "mp4")
