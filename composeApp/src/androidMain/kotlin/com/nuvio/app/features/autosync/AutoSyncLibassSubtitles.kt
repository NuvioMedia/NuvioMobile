@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.autosync

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.libass.media.AssHandler
import com.nuvio.tv.libass.media.parser.AssFullSubtitleParser
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext

/**
 * AutoSync for ASS/SSA add-on subtitles that libass draws.
 *
 * libass keeps the whole subtitle file in its own track, so AutoSync cannot retime cues the way it
 * does for the sidecar renderer. Instead [AssHandler.replaceTrack] swaps the track registered under
 * the subtitle's track id for one filled with a copy whose Dialogue Start/End times are rewritten.
 * Styles, fonts, positioning and effects are untouched, and the media source is not reloaded.
 *
 * The retimed copy is also remembered per track id, so when Media3 loads that subtitle again (a
 * media reload, or Nuvio switching to the subtitle AutoSync picked) libass gets the synced copy
 * instead of the original. It is forgotten when AutoSync starts again or the subtitle is replaced.
 */
internal object AutoSyncLibassSubtitles {
    private val retimedByHandler =
        Collections.synchronizedMap(WeakHashMap<AssHandler, MutableMap<String, ByteArray>>())

    /** The ids libass registered external ASS/SSA tracks under, as Media3 handed them over. */
    private val trackIdsByHandler =
        Collections.synchronizedMap(WeakHashMap<AssHandler, MutableSet<String>>())

    /** Wraps libass's parser for external ASS/SSA files; every other parser is returned as is. */
    fun wrapParser(assHandler: AssHandler, format: Format, parser: SubtitleParser): SubtitleParser {
        if (parser !is AssFullSubtitleParser) return parser
        val trackId = format.id ?: return parser
        synchronized(trackIdsByHandler) {
            trackIdsByHandler.getOrPut(assHandler) { ConcurrentHashMap.newKeySet() }.add(trackId)
        }
        return RetimedAssSubtitleParser(parser) { retimedBody(assHandler, trackId) }
    }

    /**
     * Shows [retimedText] in place of the subtitle libass registered under [trackId].
     * [AssHandler.replaceTrack] parses the copy into a new track before the renderer switches to
     * it, so the subtitle never disappears while a large file parses, and releases the old track.
     * It runs on AutoSync's background dispatcher, as Media3's loader parses the original.
     */
    suspend fun apply(assHandler: AssHandler, trackId: String, retimedText: String) {
        val (libassTrackId, bytes) = remember(assHandler, trackId, retimedText)
        val format = Format.Builder()
            .setId(libassTrackId)
            .setSampleMimeType(MimeTypes.TEXT_SSA)
            .build()
        withContext(autoSyncCpuDispatcher) { assHandler.replaceTrack(format, bytes) }
    }

    /**
     * Remembers [retimedText] for the subtitle registered under [trackId] without loading it, for
     * a subtitle Media3 is about to load: libass then gets the synced copy from its parser.
     */
    fun remember(assHandler: AssHandler, trackId: String, retimedText: String): Pair<String, ByteArray> {
        val bytes = retimedText.toByteArray(Charsets.UTF_8)
        val libassTrackId = libassTrackId(trackId, trackIdsByHandler[assHandler].orEmpty())
        synchronized(retimedByHandler) {
            retimedByHandler.getOrPut(assHandler) { ConcurrentHashMap() }[libassTrackId] = bytes
        }
        return libassTrackId to bytes
    }

    /**
     * The id libass knows [trackId] by: the subtitle configuration id itself, or that id behind a
     * prefix Media3 added. Falls back to [trackId] before Media3 handed the track to libass.
     */
    internal fun libassTrackId(trackId: String, knownIds: Collection<String>): String =
        knownIds.firstOrNull { it == trackId }
            ?: knownIds.firstOrNull { it.endsWith(":$trackId") }
            ?: trackId

    fun clear(assHandler: AssHandler?) {
        if (assHandler == null) return
        retimedByHandler.remove(assHandler)
    }

    internal fun retimedBody(assHandler: AssHandler, trackId: String): ByteArray? =
        synchronized(retimedByHandler) { retimedByHandler[assHandler]?.get(trackId) }
}

/** Feeds libass the synced copy of a subtitle when Media3 loads it after AutoSync applied it. */
internal class RetimedAssSubtitleParser(
    private val delegate: SubtitleParser,
    private val retimedBody: () -> ByteArray?,
) : SubtitleParser {
    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        val retimed = retimedBody()
        if (retimed == null) {
            delegate.parse(data, offset, length, outputOptions, output)
        } else {
            delegate.parse(retimed, 0, retimed.size, outputOptions, output)
        }
    }

    override fun reset() = delegate.reset()

    override fun getCueReplacementBehavior(): Int = delegate.cueReplacementBehavior
}
