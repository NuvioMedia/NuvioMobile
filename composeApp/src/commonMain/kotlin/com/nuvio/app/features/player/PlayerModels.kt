package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_ios_hardware_decoder_off
import nuvio.composeapp.generated.resources.player_ios_preset_compatibility_desc
import nuvio.composeapp.generated.resources.player_ios_preset_compatibility_label
import nuvio.composeapp.generated.resources.player_ios_preset_custom_desc
import nuvio.composeapp.generated.resources.player_ios_preset_custom_label
import nuvio.composeapp.generated.resources.player_ios_preset_native_edr_desc
import nuvio.composeapp.generated.resources.player_ios_preset_native_edr_label
import nuvio.composeapp.generated.resources.player_ios_preset_sdr_tone_mapped_desc
import nuvio.composeapp.generated.resources.player_ios_preset_sdr_tone_mapped_label
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

data class PlayerLaunch(
    val profileId: Int,
    val title: String,
    val sourceUrl: String,
    val sourceAudioUrl: String? = null,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val sourceResponseHeaders: Map<String, String> = emptyMap(),
    val externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList(),
    val streamType: String? = null,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val streamTitle: String,
    val streamSubtitle: String? = null,
    val bingeGroup: String? = null,
    val pauseDescription: String? = null,
    val providerName: String,
    val providerAddonId: String? = null,
    val contentType: String? = null,
    val videoId: String? = null,
    val parentMetaId: String,
    val parentMetaType: String,
    val torrentInfoHash: String? = null,
    val torrentFileIdx: Int? = null,
    val torrentFilename: String? = null,
    val torrentTrackers: List<String> = emptyList(),
    val initialPositionMs: Long = 0L,
    val initialProgressFraction: Float? = null,
    val contentLanguage: String? = null,
    val sourceFilename: String? = null,
    val stream: com.nuvio.app.features.streams.StreamItem? = null,
)

object PlayerLaunchStore {
    /**
     * Hard cap on the launches kept in memory. A launch carries the whole playback payload
     * (headers, subtitles, stream metadata), so only the most recent ones are retained.
     */
    private const val MAX_LAUNCHES = 8

    /**
     * How long a launch stays readable after the player screen went away. A configuration
     * change disposes and recreates the composition and the player re-reads its launch from
     * the store, so the payload has to outlive the disposal by a small margin.
     */
    private const val EXITED_LAUNCH_GRACE_MS = 30_000L

    private val exitClock = TimeSource.Monotonic

    private var nextLaunchId = 1L
    private val launches = mutableMapOf<Long, LaunchEntry>()

    private data class LaunchEntry(
        val launch: PlayerLaunch,
        /** Set once the player screen for this launch is gone. */
        val exitedAt: TimeMark? = null,
    )

    fun put(launch: PlayerLaunch): Long {
        val launchId = nextLaunchId++
        launches[launchId] = LaunchEntry(launch)
        prune()
        return launchId
    }

    fun get(launchId: Long): PlayerLaunch? {
        prune()
        val entry = launches[launchId] ?: return null
        if (entry.exitedAt != null) {
            // The player came back (configuration change): reclaim the launch.
            launches[launchId] = entry.copy(exitedAt = null)
        }
        return entry.launch
    }

    fun update(launchId: Long, transform: (PlayerLaunch) -> PlayerLaunch) {
        val entry = launches[launchId] ?: return
        launches[launchId] = entry.copy(launch = transform(entry.launch))
    }

    fun remove(launchId: Long) {
        launches.remove(launchId)
    }

    fun clear() {
        nextLaunchId = 1L
        launches.clear()
    }

    /**
     * Called when the player screen goes away: the launch is dropped as soon as the restore
     * window expires, so leaving the player does not keep the payload alive for the session.
     */
    fun markExited(launchId: Long) {
        val entry = launches[launchId] ?: return
        launches[launchId] = entry.copy(exitedAt = exitClock.markNow())
    }

    /** Number of launches currently held by the store. */
    internal val size: Int get() = launches.size

    private fun prune() {
        val expired = launches.entries
            .filter { (_, entry) ->
                val exitedAt = entry.exitedAt
                exitedAt != null && exitedAt.elapsedNow() >= EXITED_LAUNCH_GRACE_MS.milliseconds
            }
            .map { it.key }
        expired.forEach { launches.remove(it) }

        while (launches.size > MAX_LAUNCHES) {
            // Prefer dropping launches whose player already exited, oldest first.
            val victim = launches.entries
                .filter { it.value.exitedAt != null }
                .minByOrNull { it.key }
                ?: launches.entries.minByOrNull { it.key }
                ?: break
            launches.remove(victim.key)
        }
    }
}

enum class PlayerResizeMode {
    Fit,
    Fill,
    Zoom,
}

enum class AndroidPlaybackEngine(
    val label: String,
) {
    Auto("Auto"),
    ExoPlayer("ExoPlayer"),
    Libmpv("libmpv"),
}

enum class AndroidLibmpvVideoOutput(
    val mpvValue: String,
    val label: String,
    val description: String,
) {
    GpuNext(
        mpvValue = "gpu-next",
        label = "GPU next",
        description = "Modern libmpv renderer with higher quality processing.",
    ),
    Gpu(
        mpvValue = "gpu",
        label = "GPU",
        description = "Compatibility renderer for devices that have issues with GPU next.",
    ),
}

enum class IosVideoOutputPreset(
    val label: String,
    val description: String,
) {
    NativeEdr(
        label = "Native EDR",
        description = "Best for HDR-capable iPhones and iPads.",
    ),
    SdrToneMapped(
        label = "SDR tone mapped",
        description = "More predictable whites and blacks on SDR-style output.",
    ),
    Compatibility(
        label = "Compatibility",
        description = "Closest to the older iOS MPV behavior.",
    ),
    Custom(
        label = "Custom",
        description = "Use your advanced values below.",
    ),
}

enum class IosToneMappingMode(
    val mpvValue: String,
    val label: String,
) {
    Auto("auto", "Auto"),
    Bt2390("bt.2390", "BT.2390"),
    Mobius("mobius", "Mobius"),
    Reinhard("reinhard", "Reinhard"),
    Hable("hable", "Hable"),
    Gamma("gamma", "Gamma"),
    Clip("clip", "Clip"),
}

enum class IosTargetPrimaries(
    val mpvValue: String,
    val label: String,
) {
    Auto("auto", "Auto"),
    Bt709("bt.709", "BT.709"),
    DisplayP3("display-p3", "Display P3"),
    Bt2020("bt.2020", "BT.2020"),
}

enum class IosTargetTransfer(
    val mpvValue: String,
    val label: String,
) {
    Auto("auto", "Auto"),
    Srgb("srgb", "sRGB"),
    Bt1886("bt.1886", "BT.1886"),
    Gamma22("gamma2.2", "Gamma 2.2"),
    Gamma24("gamma2.4", "Gamma 2.4"),
    Pq("pq", "PQ"),
    Hlg("hlg", "HLG"),
}

enum class IosHardwareDecoderMode(
    val mpvValue: String,
    val label: String,
) {
    Auto("auto", "Auto"),
    VideoToolbox("videotoolbox", "VideoToolbox"),
    Off("no", "Off"),
}

enum class IosAudioOutputMode(
    val mpvValue: String,
    val label: String,
) {
    Auto("audiounit", "Auto"),
    AvFoundation("avfoundation", "AVFoundation"),
    AudioUnit("audiounit", "AudioUnit");

    companion object {
        val selectableEntries: List<IosAudioOutputMode> = listOf(Auto, AudioUnit)

        fun fromStoredName(name: String?): IosAudioOutputMode =
            name
                ?.let { runCatching { valueOf(it) }.getOrNull() }
                ?.takeUnless { it == AvFoundation }
                ?: Auto
    }
}

@Composable
fun IosVideoOutputPreset.localizedLabel(): String = when (this) {
    IosVideoOutputPreset.NativeEdr -> stringResource(Res.string.player_ios_preset_native_edr_label)
    IosVideoOutputPreset.SdrToneMapped -> stringResource(Res.string.player_ios_preset_sdr_tone_mapped_label)
    IosVideoOutputPreset.Compatibility -> stringResource(Res.string.player_ios_preset_compatibility_label)
    IosVideoOutputPreset.Custom -> stringResource(Res.string.player_ios_preset_custom_label)
}

@Composable
fun IosVideoOutputPreset.localizedDescription(): String = when (this) {
    IosVideoOutputPreset.NativeEdr -> stringResource(Res.string.player_ios_preset_native_edr_desc)
    IosVideoOutputPreset.SdrToneMapped -> stringResource(Res.string.player_ios_preset_sdr_tone_mapped_desc)
    IosVideoOutputPreset.Compatibility -> stringResource(Res.string.player_ios_preset_compatibility_desc)
    IosVideoOutputPreset.Custom -> stringResource(Res.string.player_ios_preset_custom_desc)
}

@Composable
fun IosHardwareDecoderMode.localizedLabel(): String = when (this) {
    IosHardwareDecoderMode.Off -> stringResource(Res.string.player_ios_hardware_decoder_off)
    else -> label
}

internal data class PlaybackKey(
    val sourceIdentity: String,
    val videoId: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val playbackEngine: AndroidPlaybackEngine? = null,
)

data class PlayerPlaybackSnapshot(
    val isLoading: Boolean = true,
    val isPlaying: Boolean = false,
    val isEnded: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)

data class PlayerNowPlayingInfo(
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
)
