@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.ui.SubtitleView
import com.nuvio.app.R
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.autosync.AutoSyncSyncedSubtitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

private const val SIDECAR_TAG = "NuvioSidecar"
private val sidecarParserFactory = DefaultSubtitleParserFactory()
private val mainHandler = Handler(Looper.getMainLooper())
private const val SIDECAR_RENDER_INTERVAL_MS = 100L
private const val EMPTY_CUE_SIGNATURE = 0x4E5556494FL // "NUVIO"

internal class SidecarSubtitleController(
    private val scope: CoroutineScope,
    private val getPlayer: () -> Player?,
    private val getSubtitleDelayMs: () -> Int = { 0 },
) {
    private var sidecarSubtitleJob: Job? = null
    private var sidecarGenerationCounter = 0L
    private var activeSidecarGeneration = 0L
    var activeSidecarSubtitleKey: String? = null
        private set
    var sidecarTimedCues: List<CuesWithTiming> = emptyList()
        private set
    private var lastSidecarCueSignature: Long? = null
    private var exoSubtitleViewRef: WeakReference<SubtitleView>? = null

    fun isSidecarActive(): Boolean = activeSidecarSubtitleKey != null

    /** Identifies one attachment of [url]; changes whenever the sidecar restarts or stops. */
    internal fun currentGenerationFor(url: String): Long? =
        activeSidecarGeneration.takeIf { activeSidecarSubtitleKey == url && it != 0L }

    fun canAttachAddonSubtitleViaSidecar(url: String, useLibass: Boolean): Boolean {
        val mime = PlayerSubtitleUtils.mimeTypeFromUrl(url)
        if (mime == MimeTypes.TEXT_SSA && useLibass) {
            return false
        }
        val format = Format.Builder().setSampleMimeType(mime).build()
        return sidecarParserFactory.supportsFormat(format)
    }

    fun bindSubtitleView(subtitleView: SubtitleView?) {
        exoSubtitleViewRef = subtitleView?.let { WeakReference(it) }
        if (subtitleView == null && activeSidecarSubtitleKey != null) {
            return
        }
        if (activeSidecarSubtitleKey != null && sidecarTimedCues.isNotEmpty()) {
            renderSidecarCuesAtCurrentPosition()
        }
    }

    fun stopSidecarAddonSubtitle(clearView: Boolean = true) {
        sidecarSubtitleJob?.cancel()
        sidecarSubtitleJob = null
        activeSidecarSubtitleKey = null
        activeSidecarGeneration = 0L
        AutoSyncSyncedSubtitle.clear() // AutoSync hook
        sidecarTimedCues = emptyList()
        lastSidecarCueSignature = null
        if (clearView) {
            postToSubtitleView { view ->
                view.setTag(R.id.player_view_sidecar_generation_tag, null)
                view.setCues(emptyList())
            }
        }
    }

    fun startSidecarAddonSubtitle(
        url: String,
        headers: Map<String, String> = emptyMap(),
        useLibass: Boolean = false,
        rawBodyLoader: (suspend () -> String)? = null,
    ): Boolean {
        if (!canAttachAddonSubtitleViaSidecar(url, useLibass)) return false

        val subtitleKey = url
        val urlMimeHint = PlayerSubtitleUtils.mimeTypeFromUrl(url)

        sidecarSubtitleJob?.cancel()
        val generation = ++sidecarGenerationCounter
        activeSidecarGeneration = generation
        AutoSyncSyncedSubtitle.clear() // AutoSync hook
        activeSidecarSubtitleKey = subtitleKey
        lastSidecarCueSignature = null
        sidecarTimedCues = emptyList()
        postToSubtitleView { view ->
            view.setTag(R.id.player_view_sidecar_generation_tag, generation)
            view.setCues(emptyList())
        }
        fun isCurrent() =
            activeSidecarSubtitleKey == subtitleKey && activeSidecarGeneration == generation

        sidecarSubtitleJob = scope.launch {
            try {
                val rawBody = rawBodyLoader?.invoke() ?: withContext(Dispatchers.IO) {
                    httpGetTextWithHeaders(url = url, headers = headers)
                }
                if (!isCurrent()) return@launch

                val resolvedMime = PlayerSubtitleUtils.sniffSubtitleMimeType(rawBody, url)
                val parseResult = withContext(Dispatchers.Default) {
                    parseSidecarTimedCuesRobust(rawBody, url)
                }
                if (!isCurrent()) return@launch

                if (parseResult.cues.isEmpty()) {
                    Log.w(
                        SIDECAR_TAG,
                        "Sidecar subtitle parse empty for url=$url urlMime=$urlMimeHint sniffed=$resolvedMime (buffer preserved; no media reload)"
                    )
                    activeSidecarSubtitleKey = null
                    activeSidecarGeneration = 0L
                    sidecarTimedCues = emptyList()
                    postToSubtitleView { view ->
                        view.setTag(R.id.player_view_sidecar_generation_tag, null)
                        view.setCues(emptyList())
                    }
                    return@launch
                }

                sidecarTimedCues = parseResult.cues
                Log.d(
                    SIDECAR_TAG,
                    "Sidecar subtitle ready url=$url cues=${parseResult.cues.size} mime=${parseResult.effectiveMime} source=${parseResult.source} (buffer preserved)"
                )

                while (isActive && isCurrent()) {
                    renderSidecarCuesAtCurrentPosition()
                    delay(SIDECAR_RENDER_INTERVAL_MS)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrent()) return@launch
                Log.w(
                    SIDECAR_TAG,
                    "Sidecar subtitle failed url=$url: ${e.message} (buffer preserved; no media reload)"
                )
                activeSidecarSubtitleKey = null
                activeSidecarGeneration = 0L
                sidecarTimedCues = emptyList()
                postToSubtitleView { view ->
                    view.setTag(R.id.player_view_sidecar_generation_tag, null)
                    view.setCues(emptyList())
                }
            }
        }
        return true
    }

    fun renderSidecarCuesAtCurrentPosition() {
        val cues = sidecarTimedCues
        if (cues.isEmpty() || activeSidecarSubtitleKey == null) return
        val player = getPlayer() ?: return
        val delayUs = getSubtitleDelayMs().toLong() * 1_000L
        val positionUs = (player.currentPosition.coerceAtLeast(0L) * 1_000L - delayUs).coerceAtLeast(0L)
        val active = collectActiveSidecarCues(cues, positionUs)
        val signature = activeCueSignature(active)
        if (signature == lastSidecarCueSignature) return
        lastSidecarCueSignature = signature
        val currentKey = activeSidecarSubtitleKey ?: return
        val currentGeneration = activeSidecarGeneration.takeIf { it != 0L } ?: return
        postToSubtitleView { view ->
            if (
                activeSidecarSubtitleKey == currentKey &&
                activeSidecarGeneration == currentGeneration &&
                view.getTag(R.id.player_view_sidecar_generation_tag) == currentGeneration
            ) {
                view.setCues(active)
            }
        }
    }

    /**
     * Replaces the cues of the attached subtitle with already-parsed [cues], switching the
     * attachment to [newUrl], only if [expectedCurrentUrl] (and [expectedGeneration]) is still
     * the active attachment. Keeps the buffer and avoids re-downloading or re-parsing.
     */
    internal fun commitPreparedSidecarSubtitle(
        expectedCurrentUrl: String,
        newUrl: String,
        cues: List<CuesWithTiming>,
        expectedGeneration: Long? = null,
    ): Boolean {
        if (cues.isEmpty() || activeSidecarSubtitleKey != expectedCurrentUrl) return false
        if (expectedGeneration != null && activeSidecarGeneration != expectedGeneration) return false

        val urlChanged = newUrl != expectedCurrentUrl
        if (urlChanged) {
            sidecarSubtitleJob?.cancel()
            activeSidecarSubtitleKey = newUrl
            activeSidecarGeneration = ++sidecarGenerationCounter
        }

        val committedGeneration = activeSidecarGeneration
        sidecarTimedCues = cues
        lastSidecarCueSignature = null
        postToSubtitleView { view ->
            view.setTag(R.id.player_view_sidecar_generation_tag, committedGeneration)
        }
        renderSidecarCuesAtCurrentPosition()

        if (urlChanged) {
            sidecarSubtitleJob = scope.launch {
                while (
                    isActive &&
                    activeSidecarSubtitleKey == newUrl &&
                    activeSidecarGeneration == committedGeneration
                ) {
                    renderSidecarCuesAtCurrentPosition()
                    delay(SIDECAR_RENDER_INTERVAL_MS)
                }
            }
        }
        return true
    }

    private fun postToSubtitleView(block: (SubtitleView) -> Unit) {
        val view = exoSubtitleViewRef?.get() ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block(view)
        } else {
            mainHandler.post {
                exoSubtitleViewRef?.get()?.let(block)
            }
        }
    }
}

internal data class SidecarParseResult(
    val cues: List<CuesWithTiming>,
    val effectiveMime: String,
    val source: String
)

internal fun parseSidecarTimedCuesRobust(rawText: String, sourceUrl: String): SidecarParseResult {
    val cleaned = rawText.replace("\uFEFF", "")
    val candidates = PlayerSubtitleUtils.sidecarMimeCandidates(cleaned, sourceUrl)
    for (mime in candidates) {
        val parsed = parseSidecarTimedCuesWithMime(cleaned, mime)
        if (parsed.isNotEmpty()) {
            val fixed = PlayerSubtitleRtlFix.fixTimedCues(parsed, isBuiltInSubtitle = false)
            val normalized = if (mime == MimeTypes.TEXT_VTT) normalizeTimedCuePositions(fixed) else fixed
            return SidecarParseResult(
                normalized,
                mime,
                source = "media3"
            )
        }
    }

    val sniffedMime = PlayerSubtitleUtils.sniffSubtitleMimeType(cleaned, sourceUrl)
    val lenient = if (sniffedMime == MimeTypes.TEXT_SSA || sniffedMime == MimeTypes.APPLICATION_TTML) {
        emptyList()
    } else {
        parseSidecarTimedCuesLenient(cleaned, sourceUrl)
    }
    if (lenient.isNotEmpty()) {
        val fixed = PlayerSubtitleRtlFix.fixTimedCues(lenient, isBuiltInSubtitle = false)
        val normalized = if (sniffedMime == MimeTypes.TEXT_VTT) normalizeTimedCuePositions(fixed) else fixed
        return SidecarParseResult(
            normalized,
            sniffedMime,
            source = "lenient"
        )
    }

    return SidecarParseResult(
        cues = emptyList(),
        effectiveMime = candidates.firstOrNull() ?: MimeTypes.APPLICATION_SUBRIP,
        source = "none"
    )
}

private fun normalizeTimedCuePositions(cues: List<CuesWithTiming>): List<CuesWithTiming> {
    return cues.map { entry ->
        val normalized = entry.cues.map { normalizeSidecarCuePosition(it) }
        val durationUs = when {
            entry.durationUs != C.TIME_UNSET -> entry.durationUs
            entry.endTimeUs != C.TIME_UNSET && entry.startTimeUs != C.TIME_UNSET ->
                (entry.endTimeUs - entry.startTimeUs).coerceAtLeast(1L)
            else -> C.TIME_UNSET
        }
        CuesWithTiming(normalized, entry.startTimeUs, durationUs)
    }
}

private fun parseSidecarTimedCuesWithMime(rawText: String, mimeType: String): List<CuesWithTiming> {
    val format = Format.Builder().setSampleMimeType(mimeType).build()
    if (!sidecarParserFactory.supportsFormat(format)) return emptyList()
    return try {
        val parser = sidecarParserFactory.create(format)
        val data = rawText.toByteArray(Charsets.UTF_8)
        val out = ArrayList<CuesWithTiming>(256)
        parser.parse(data, SubtitleParser.OutputOptions.allCues()) { cueGroup ->
            if (cueGroup.startTimeUs != C.TIME_UNSET) {
                out.add(cueGroup)
            }
        }
        out.sortBy { it.startTimeUs }
        out
    } catch (e: Exception) {
        Log.d(
            SIDECAR_TAG,
            "Sidecar Media3 parse failed mime=$mimeType: ${e.message}"
        )
        emptyList()
    }
}

internal fun parseSidecarTimedCuesLenient(rawText: String, sourceUrl: String): List<CuesWithTiming> {
    val syncCues = try {
        PlayerSubtitleCueParser.parseFromText(rawText, sourceUrl)
            .filter { it.text.isNotBlank() && it.startTimeMs >= 0L }
    } catch (_: Exception) {
        emptyList()
    }
    if (syncCues.isEmpty()) return emptyList()

    val out = ArrayList<CuesWithTiming>(syncCues.size)
    for (i in syncCues.indices) {
        val startUs = syncCues[i].startTimeMs * 1_000L
        val endUs = (syncCues[i].endTimeMs * 1_000L).coerceAtLeast(startUs + 1L)
        val durationUs = (endUs - startUs).coerceAtLeast(1L)
        val cue = Cue.Builder().setText(syncCues[i].text).build()
        out.add(CuesWithTiming(listOf(cue), startUs, durationUs))
    }
    return out
}

private fun collectActiveSidecarCues(
    cues: List<CuesWithTiming>,
    positionUs: Long
): List<Cue> {
    if (cues.isEmpty()) return emptyList()
    val active = ArrayList<Cue>(4)
    for (entry in cues) {
        if (entry.startTimeUs > positionUs) break
        val end = when {
            entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs
            entry.durationUs != C.TIME_UNSET -> entry.startTimeUs + entry.durationUs
            else -> Long.MAX_VALUE
        }
        if (positionUs < end) {
            active.addAll(entry.cues)
        }
    }
    return active
}

private fun activeCueSignature(cues: List<Cue>): Long {
    if (cues.isEmpty()) return EMPTY_CUE_SIGNATURE
    var hash = cues.size.toLong()
    for (cue in cues) {
        hash = 31L * hash + (cue.text?.hashCode()?.toLong() ?: 0L)
        hash = 31L * hash + cue.line.toBits().toLong()
        hash = 31L * hash + cue.position.toBits().toLong()
        hash = 31L * hash + cue.lineAnchor.toLong()
        hash = 31L * hash + cue.positionAnchor.toLong()
        hash = 31L * hash + cue.size.toBits().toLong()
    }
    return hash
}

private fun normalizeSidecarCuePosition(cue: Cue): Cue {
    if (cue.bitmap != null || cue.verticalType != Cue.TYPE_UNSET || cue.line == Cue.DIMEN_UNSET) {
        return cue
    }
    return cue.buildUpon()
        .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
        .setLineAnchor(Cue.TYPE_UNSET)
        .build()
}
