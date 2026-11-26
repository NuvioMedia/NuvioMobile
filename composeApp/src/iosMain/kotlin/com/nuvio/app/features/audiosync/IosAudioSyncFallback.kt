package com.nuvio.app.features.audiosync

import co.touchlab.kermit.Logger
import com.nuvio.app.features.autosync.AutoSyncMpvAudioFallback
import com.nuvio.app.features.autosync.IosAutoSyncMpvPlatform
import com.nuvio.app.features.player.NuvioPlayerBridge
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlinx.coroutines.CoroutineScope

private val audioSyncLog = Logger.withTag("NuvioAudioSync")
private val audioSyncUrl = Regex("""https?://[^\s]+""")

@OptIn(ExperimentalNativeApi::class)
private fun logAudioSync(message: String) {
    if (Platform.isDebugBinary) audioSyncLog.i { audioSyncUrl.replace(message, "<url>") }
}

internal fun createIosAudioSyncFallback(
    scope: CoroutineScope,
    bridge: NuvioPlayerBridge,
    sourceUrl: String,
    sourceHeaders: Map<String, String>,
    getSubtitleHeaders: (String) -> Map<String, String>,
): AutoSyncMpvAudioFallback? {
    ensureAudioSyncInitialized()
    if (!AudioSyncSettings.fallbackEnabled.value) return null
    val native = AudioSyncNativeRegistry.bridge ?: return null
    val host = object : MpvSpotAudioSyncHost {
        override fun positionMs(): Long = bridge.getPositionMs()

        override fun durationMs(): Long = bridge.getDurationMs()

        override fun isPlaying(): Boolean = bridge.getIsPlaying()

        override fun selectedAudioLanguage(): String? =
            (0 until bridge.getAudioTrackCount())
                .firstOrNull { bridge.isAudioTrackSelected(it) }
                ?.let { bridge.getAudioTrackLang(it) }
                ?.takeIf { it.isNotBlank() }

        override fun replaceSubtitle(replacedUrl: String, path: String) {
            bridge.replaceExternalSubtitle(replacedUrl, path)
        }

        override fun setSubtitleDelayMs(delayMs: Int) {
            bridge.setSubtitleDelayMs(delayMs)
        }

        override suspend fun writeSubtitleFile(fileName: String, stalePrefix: String, content: String): String? =
            IosAutoSyncMpvPlatform.writeSubtitleFile(fileName, stalePrefix, content)
    }
    return MpvSpotAudioSyncFallback(
        scope = scope,
        host = host,
        reader = IosAudioSyncSpotReader(native),
        recognizers = IosAudioSyncRecognizers(native),
        sourceUrl = sourceUrl,
        sourceHeaders = sourceHeaders,
        getSubtitleHeaders = getSubtitleHeaders,
        log = ::logAudioSync,
    )
}
