@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.audiosync

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.CuesWithTiming
import com.nuvio.app.features.audiosync.asr.AsrModel
import com.nuvio.app.features.autosync.AutoSyncMpvAudioFallback
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import com.nuvio.app.features.autosync.AutoSyncState
import com.nuvio.app.features.autosync.AutoSyncSubtitleCandidate
import com.nuvio.app.features.autosync.AutoSyncSyncedSubtitle
import com.nuvio.app.features.autosync.AutomaticSubtitleSync
import com.nuvio.app.features.player.PlayerPlaybackNetworking
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerSubtitleUtils
import com.nuvio.app.features.player.SidecarSubtitleController
import com.nuvio.app.features.player.parseSidecarTimedCuesRobust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Android libmpv operations that [AudioSyncFallback] uses in spot-only mode. */
internal interface MpvAudioSyncHost {
    fun currentPlaybackSnapshot(): PlayerPlaybackSnapshot
    fun selectedAudioLanguage(): String?
    suspend fun replaceAudioSyncSubtitle(
        replacedUrl: String?,
        path: String,
        title: String?,
        language: String?,
        replaceTrackId: Int?,
    ): Int?

    suspend fun restoreOriginalAudioSyncSubtitle(
        trackId: Int,
        originalUrl: String?,
        title: String?,
        language: String?,
    )
}

/**
 * Audio subtitle sync as AutoSync's fallback, for one stream of one Android player.
 *
 * AutoSync decides first. While it analyses a subtitle the audio is already being listened to
 * ([arm]); when it keeps the subtitle's original timing (no embedded subtitles, no usable
 * reference, or a weak match) the audio takes over ([takeOver]); otherwise nothing more is done
 * ([disarm]). The mapping found is applied by retiming the cues the player's sidecar shows, the
 * same way AutoSync applies its own result, so Nuvio's subtitle rendering is untouched. When the
 * sidecar moves on (another subtitle or track), the fallback stops on its own.
 *
 * With the mpv engine ([player] is null) no audio passes through ExoPlayer, so the film is heard
 * only through spots sampled ahead of playback, and the mapping is shown the way AutoSync shows
 * its own result there: a retimed copy of the subtitle loaded as an external mpv track.
 */
internal class AudioSyncFallback private constructor(
    context: Context,
    private val scope: CoroutineScope,
    /** The ExoPlayer that plays the stream; null when libmpv plays it. */
    private val player: ExoPlayer?,
    private val sidecar: SidecarSubtitleController?,
    private val mpvHost: MpvAudioSyncHost?,
    private val sourceUrl: String,
    sourceHeaders: Map<String, String>,
    private val getSubtitleHeaders: (String) -> Map<String, String>,
    private val onMimeTypeSelected: (String) -> Unit,
    private val onSubtitleDelayChanged: (Int) -> Unit,
    private val getAppliedListener: () -> ((subtitleUrl: String, delayMs: Int) -> Unit)?,
) : AutoSyncMpvAudioFallback {
    private val isMpv = player == null
    private val appContext = context.applicationContext
    private val controller = AudioSubtitleSyncController(
        context = appContext,
        // Subtitles are retimed in place, so the user's delay simply adds on top.
        manualDelayMs = { 0 },
        onStatus = ::onStatus,
        requestSwitch = ::replaceSubtitle,
    )

    /**
     * The subtitle being synced, with its original cues and the sidecar attachment it lives in
     * (ExoPlayer), or the retimed copy mpv shows for it ([mpvTrackId], null while the original shows).
     */
    private class Target(
        val url: String,
        val cues: List<CuesWithTiming>,
        val generation: Long,
        var mpvTrackId: Int? = null,
        var mpvReplacedUrl: String? = url,
    )

    private val target = AtomicReference<Target?>(null)
    private var armed = false
    private var appliedModel: SubtitleSyncModel? = null
    private var ticker: Job? = null
    private var takeOverJob: Job? = null
    private var settingsJob: Job? = null
    private var pendingStatus: AudioSyncStatus? = null
    private var statusRun: AutoSyncSyncedSubtitle.Status? = null
    @Volatile
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()
    @Volatile
    private var contentType: String? = null
    @Volatile
    private var videoId: String? = null
    @Volatile
    private var activeMpvSubtitleUrl: String? = null
    @Volatile
    private var appliedListenerOverride: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null

    /** Playback time the current takeover has gone without any mapping. */
    private var unmatchedPlayedMs = 0L
    private var unmatchedReported = false
    private var lockConfirmed = false

    private val trackListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            controller.onAudioTrackSelected(tracks.selectedAudioFormat())
        }
    }

    init {
        initialize(appContext)
        controller.listensBeforeSession = false
        controller.spotOnly = isMpv
        if (isMpv) controller.spotOnlyAudioLanguage = mpvHost?.selectedAudioLanguage()
        controller.enabled = AudioSyncSettings.fallbackEnabled.value
        controller.samplingMode = AudioSyncSettings.samplingMode.value
        controller.onSourceChanged(sourceUrl)
        controller.setSpotSource(
            forSourceKey = sourceUrl,
            url = sourceUrl,
            dataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(appContext, sourceHeaders),
            extractorsFactory = DefaultExtractorsFactory(),
            localEngine = isLoopback(sourceUrl),
        )
        if (player != null) {
            player.addListener(trackListener)
            controller.onAudioTrackSelected(player.currentTracks.selectedAudioFormat())
            AudioSyncTaps.attach(controller)
        }
        // Turned off in Settings mid-playback: the subtitle goes back to AutoSync's (original) timing.
        settingsJob = scope.launch {
            combine(
                AudioSyncSettings.fallbackEnabled,
                AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart,
            ) { audio, auto -> audio && auto }
                .collect { enabled ->
                    if (!enabled) {
                        stop(restoreOriginalOnMpv = true)
                        controller.enabled = false
                    }
                }
        }
    }

    override fun setContent(contentType: String?, videoId: String?) {
        this.contentType = contentType
        this.videoId = videoId
        if (armed && !contentType.isNullOrBlank() && !videoId.isNullOrBlank()) {
            controller.setContent(contentType, videoId)
        }
    }

    override fun setCandidates(candidates: List<AutoSyncSubtitleCandidate>) {
        this.candidates = candidates
        if (armed) {
            controller.setReferenceSubtitles(referenceCandidates(candidates))
        }
    }

    override fun setAppliedListener(listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?) {
        appliedListenerOverride = listener
    }

    /** AutoSync started analysing a subtitle: listen meanwhile, so a takeover starts with audio. */
    override fun arm() {
        stop(restoreOriginalOnMpv = false)
        if (!AudioSyncSettings.fallbackEnabled.value) return
        controller.enabled = true
        controller.samplingMode = AudioSyncSettings.samplingMode.value
        controller.listensBeforeSession = true
        // Start an already-downloaded recognizer before subtitle discovery, downloads or
        // AutoSync's analysis. Recognition can then begin immediately on a later takeover.
        controller.prepareRecognizer()
        if (!armed) {
            // Defer title lookup until an AutoSync run actually starts, instead of stream setup.
            armed = true
            val type = contentType
            val vid = videoId
            if (!type.isNullOrBlank() && !vid.isNullOrBlank()) controller.setContent(type, vid)
        }
        controller.setReferenceSubtitles(referenceCandidates(candidates))
        startTicker()
    }

    /** Every subtitle the add-ons returned for this title; English ones are speech recognition references. */
    private fun referenceCandidates(
        list: List<AutoSyncSubtitleCandidate>,
    ): List<AudioSubtitleSyncController.ReferenceCandidate> =
        list.distinctBy { it.url }.map { subtitle ->
            AudioSubtitleSyncController.ReferenceCandidate(
                url = subtitle.url,
                language = subtitle.language,
                headers = subtitle.headers.ifEmpty { getSubtitleHeaders(subtitle.url) },
                label = subtitle.name.orEmpty(),
            )
        }

    /** AutoSync applied its own result: nothing is left for the audio to do. */
    override fun disarm() {
        stop(restoreOriginalOnMpv = false)
    }

    /**
     * AutoSync kept [url]'s original timing, shown by the sidecar: sync it to the audio instead.
     * Waits briefly for the sidecar's cues, as AutoSync does before applying its own result.
     * Returns false when the fallback is off, so AutoSync reports the failure itself.
     */
    override fun takeOver(url: String): Boolean {
        if (!AudioSyncSettings.fallbackEnabled.value || !controller.enabled) {
            disarm()
            return false
        }
        // mpv's audio is only heard through sampling; with sampling off there is nothing to sync to.
        if (isMpv && AudioSyncSettings.samplingMode.value == AudioSamplingMode.OFF) {
            SyncLog.i("audio sampling is off; the mpv engine has no other audio to sync to")
            disarm()
            return false
        }
        takeOverJob?.cancel()
        statusRun = AutoSyncSyncedSubtitle.start(url)
        if (isMpv) {
            activeMpvSubtitleUrl = url
            takeOverJob = scope.launch { takeOverMpv(url) }
            return true
        }
        val activeSidecar = sidecar ?: run {
            AutoSyncSyncedSubtitle.fail(statusRun)
            disarm()
            return false
        }
        takeOverJob = scope.launch {
            var waitedMs = 0L
            while (activeSidecar.activeSidecarSubtitleKey == url && activeSidecar.sidecarTimedCues.isEmpty() && waitedMs < CUES_WAIT_MS) {
                delay(CUES_POLL_MS)
                waitedMs += CUES_POLL_MS
            }
            val generation = activeSidecar.currentGenerationFor(url)
            val cues = activeSidecar.sidecarTimedCues
            if (generation == null || cues.isEmpty()) {
                AutoSyncSyncedSubtitle.fail(statusRun)
                return@launch disarm()
            }
            target.set(Target(url, cues, generation))
            appliedModel = null
            unmatchedPlayedMs = 0L
            unmatchedReported = false
            lockConfirmed = false
            startTicker()
            SyncLog.i("AutoSync kept the original timing of $url; syncing it to the audio")
            controller.startSession(url, cues)
        }
        return true
    }

    /** mpv: the subtitle's cues come from its file, as mpv shows them without the sidecar. */
    private suspend fun takeOverMpv(url: String) {
        val candidate = candidates.firstOrNull { it.url == url }
        val headers = candidate?.headers?.ifEmpty { getSubtitleHeaders(url) } ?: getSubtitleHeaders(url)
        val cues = downloadCues(url, headers, candidate?.language)
        if (!isMpvShowing(url)) return disarm()
        if (cues.isEmpty()) {
            AutoSyncSyncedSubtitle.fail(statusRun)
            return disarm()
        }
        val current = Target(url, cues, generation = 0L)
        target.set(current)
        appliedModel = null
        unmatchedPlayedMs = 0L
        unmatchedReported = false
        lockConfirmed = false
        startTicker()
        SyncLog.i("AutoSync kept the original timing of $url on mpv; syncing it to sampled audio")
        controller.startSession(url, cues)
    }

    private suspend fun downloadCues(
        url: String,
        headers: Map<String, String>,
        languageHint: String? = null,
    ): List<CuesWithTiming> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = AutomaticSubtitleSync.downloadSubtitleBody(
                    url = url,
                    headers = headers,
                    languageHint = languageHint,
                )
                parseSidecarTimedCuesRobust(body, url).cues
            }.getOrDefault(emptyList())
        }

    /** mpv still plays this fallback's stream with [url] as the chosen add-on subtitle. */
    private fun isMpvShowing(url: String): Boolean =
        mpvHost != null && activeMpvSubtitleUrl == url

    /** The subtitle [current] was made for is still the one shown. */
    private fun stillShown(current: Target): Boolean =
        if (isMpv) isMpvShowing(current.url) else sidecar?.currentGenerationFor(current.url) == current.generation

    /** The user moved to another subtitle or track, or AutoSync starts over. */
    override fun stop(restoreOriginalOnMpv: Boolean) {
        takeOverJob?.cancel()
        takeOverJob = null
        AutoSyncSyncedSubtitle.end(statusRun)
        statusRun = null
        ticker?.cancel()
        ticker = null
        val previousMpvUrl = activeMpvSubtitleUrl
        if (!restoreOriginalOnMpv) activeMpvSubtitleUrl = null
        target.getAndSet(null)?.let { previous ->
            AutoSyncSyncedSubtitle.clear()
            if (isMpv) {
                showOriginalOnMpv(previous, restoreOriginal = restoreOriginalOnMpv && previousMpvUrl == previous.url)
            } else {
                // Leave the subtitle on its original timing if it is still the one shown.
                sidecar?.commitPreparedSidecarSubtitle(
                    expectedCurrentUrl = previous.url,
                    newUrl = previous.url,
                    cues = previous.cues,
                    expectedGeneration = previous.generation,
                )
            }
        }
        appliedModel = null
        pendingStatus = null
        lockConfirmed = false
        controller.listensBeforeSession = false
        controller.stopSession()
    }

    override fun release() {
        stop(restoreOriginalOnMpv = false)
        settingsJob?.cancel()
        player?.removeListener(trackListener)
        AudioSyncTaps.detach(controller)
        controller.release()
    }

    /** Feeds the playhead and applies each new mapping to the sidecar, on the main thread. */
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val current = target.get()
                // Another subtitle, a track or a stopped sidecar: the fallback steps aside.
                if (current != null && !stillShown(current)) {
                    stop(restoreOriginalOnMpv = false)
                    return@launch
                }
                if (isMpv) controller.spotOnlyAudioLanguage = mpvHost?.selectedAudioLanguage()
                val snapshot = mpvHost?.currentPlaybackSnapshot()
                val positionMs = if (player != null) {
                    player.currentPosition.coerceAtLeast(0L)
                } else {
                    snapshot?.positionMs?.coerceAtLeast(0L) ?: 0L
                }
                val durationMs = if (player != null) {
                    player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
                } else {
                    snapshot?.durationMs?.takeIf { it > 0L } ?: 0L
                }
                val isPlaying = player?.isPlaying ?: (snapshot?.isPlaying == true)
                controller.onPlaybackPosition(positionMs, durationMs)
                val model = controller.currentModel()
                if (current != null && model !== appliedModel) {
                    appliedModel = model
                    apply(current, model)
                }
                val status = pendingStatus
                pendingStatus = null
                if (current != null && target.get() === current && status != null) {
                    when (status) {
                        is AudioSyncStatus.Synced, is AudioSyncStatus.Adjusted -> {
                            if (appliedModel != null && appliedModel === controller.currentModel()) {
                                lockConfirmed = true
                                AutoSyncSyncedSubtitle.mark(current.url)
                            } else if (controller.currentModel() != null) {
                                pendingStatus = status
                            }
                        }
                        AudioSyncStatus.Withdrawn -> {
                            if (controller.currentModel() == null && lockConfirmed) {
                                lockConfirmed = false
                                statusRun = AutoSyncSyncedSubtitle.start(current.url)
                                unmatchedPlayedMs = 0L
                                unmatchedReported = false
                            }
                        }
                        AudioSyncStatus.Failed -> {
                            if (controller.currentModel() == null) {
                                AutoSyncSyncedSubtitle.mark(current.url, AutoSyncState.FAILED)
                            }
                        }
                        else -> Unit
                    }
                }
                // Still nothing after a while: log it once, and keep searching.
                if (current != null && target.get() === current && !unmatchedReported && !controller.isModelConfirmed) {
                    if (isPlaying) unmatchedPlayedMs += TICK_MS
                    if (unmatchedPlayedMs >= UNMATCHED_REPORT_MS) {
                        unmatchedReported = true
                        SyncLog.i("no audio match after ${unmatchedPlayedMs / 1_000}s of playback; still searching")
                        AutoSyncSyncedSubtitle.fail(statusRun)
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    private suspend fun apply(current: Target, model: SubtitleSyncModel?) {
        if (isMpv) return applyOnMpv(current, model)
        val retimed = withContext(Dispatchers.Default) { retime(current.cues, model) }
        if (target.get() !== current) return
        val committed = sidecar?.commitPreparedSidecarSubtitle(
            expectedCurrentUrl = current.url,
            newUrl = current.url,
            cues = retimed,
            expectedGeneration = current.generation,
        ) == true
        if (!committed) stop(restoreOriginalOnMpv = false)
    }

    /**
     * mpv: loads a retimed copy of the subtitle as an external track in place of the previous copy,
     * as AutoSync applies its own result there. Without a mapping the original is shown again.
     */
    private suspend fun applyOnMpv(current: Target, model: SubtitleSyncModel?) {
        if (model == null) {
            showOriginalOnMpv(current, restoreOriginal = true)
            return
        }
        val retimed = withContext(Dispatchers.Default) { retime(current.cues, model) }
        val path = withContext(Dispatchers.IO) { writeMpvCopy(current.url, retimed) }
        if (target.get() !== current || !isMpvShowing(current.url)) return
        val host = mpvHost ?: return stop(restoreOriginalOnMpv = false)
        val candidate = candidates.firstOrNull { it.url == current.url }
        val trackId = path?.let {
            host.replaceAudioSyncSubtitle(
                replacedUrl = current.mpvReplacedUrl,
                path = it,
                title = candidate?.name?.takeIf { name -> name.isNotBlank() },
                language = candidate?.language?.takeIf { lang -> lang.isNotBlank() }?.let(PlayerSubtitleUtils::normalizeLanguageCode),
                replaceTrackId = current.mpvTrackId,
            )
        }
        if (trackId == null) {
            SyncLog.w("mpv did not accept the synced subtitle copy")
            return stop(restoreOriginalOnMpv = false)
        }
        current.mpvTrackId = trackId
        current.mpvReplacedUrl = null
    }

    /**
     * mpv: drops the retimed copy of [previous]. When that copy is what mpv shows and
     * [restoreOriginal] is true, the original file is selected again first.
     */
    private fun showOriginalOnMpv(previous: Target, restoreOriginal: Boolean) {
        val trackId = previous.mpvTrackId ?: return
        previous.mpvTrackId = null
        if (restoreOriginal) previous.mpvReplacedUrl = previous.url
        val host = mpvHost ?: return
        val candidate = candidates.firstOrNull { it.url == previous.url }
        val originalUrl = previous.url.takeIf { restoreOriginal }
        val title = candidate?.name?.takeIf { it.isNotBlank() }
        val language = candidate?.language?.takeIf { it.isNotBlank() }?.let(PlayerSubtitleUtils::normalizeLanguageCode)
        scope.launch {
            host.restoreOriginalAudioSyncSubtitle(
                trackId = trackId,
                originalUrl = originalUrl,
                title = title,
                language = language,
            )
        }
    }

    /** Writes [cues] as SRT next to AutoSync's mpv copies; returns its path, or null on failure. */
    private fun writeMpvCopy(url: String, cues: List<CuesWithTiming>): String? = runCatching {
        val text = toSrt(cues)
        val cacheDir = File(appContext.cacheDir, "subtitles").also { it.mkdirs() }
        val prefix = "mpv_audiosync_${Integer.toHexString(url.hashCode())}_"
        val file = File(cacheDir, "$prefix${Integer.toHexString(text.hashCode())}.srt")
        cacheDir.listFiles { candidate -> candidate.name.startsWith(prefix) && candidate != file }
            ?.forEach { it.delete() }
        file.writeText(text, Charsets.UTF_8)
        file.absolutePath
    }.onFailure { SyncLog.w("could not write synced subtitle copy: ${it.message}") }.getOrNull()

    /** Automatic switch to another subtitle that fits the audio, as AutoSync replaces a subtitle. */
    private fun replaceSubtitle(url: String) {
        scope.launch {
            val current = target.get() ?: return@launch
            val candidate = candidates.firstOrNull { it.url == url } ?: return@launch
            val headers = candidate.headers.ifEmpty { getSubtitleHeaders(url) }
            val cues = downloadCues(url, headers, candidate.language)
            if (cues.isEmpty() || target.get() !== current) return@launch
            val listener = appliedListenerOverride ?: getAppliedListener()
            if (isMpv) {
                if (!isMpvShowing(current.url)) return@launch
                statusRun = AutoSyncSyncedSubtitle.start(url)
                activeMpvSubtitleUrl = url
                // The previous copy is replaced as soon as the handed-over mapping is applied.
                target.set(
                    Target(
                        url = url,
                        cues = cues,
                        generation = 0L,
                        mpvTrackId = current.mpvTrackId,
                        mpvReplacedUrl = current.mpvReplacedUrl,
                    ),
                )
                appliedModel = null
                onSubtitleDelayChanged(0)
                listener?.invoke(url, 0)
                controller.startSession(url, cues)
                return@launch
            }
            val activeSidecar = sidecar ?: return@launch
            val committed = activeSidecar.commitPreparedSidecarSubtitle(
                expectedCurrentUrl = current.url,
                newUrl = url,
                cues = cues,
                expectedGeneration = current.generation,
            )
            val generation = activeSidecar.currentGenerationFor(url)
            if (!committed || generation == null) return@launch
            statusRun = AutoSyncSyncedSubtitle.start(url)
            target.set(Target(url, cues, generation))
            appliedModel = null
            onMimeTypeSelected(PlayerSubtitleUtils.mimeTypeFromUrl(url))
            onSubtitleDelayChanged(0)
            listener?.invoke(url, 0)
            // The new session adopts the mapping the switch was decided with.
            controller.startSession(url, cues)
        }
    }

    /** Only confirmed results mark the subtitle as synced; later refinements change nothing. */
    private fun onStatus(status: AudioSyncStatus) {
        if (status !is AudioSyncStatus.Synced && status !is AudioSyncStatus.Adjusted &&
            status != AudioSyncStatus.Withdrawn && status != AudioSyncStatus.Failed
        ) return
        val current = target.get() ?: return
        scope.launch {
            if (target.get() === current) pendingStatus = status
        }
    }

    companion object {
        private const val TICK_MS = 250L
        private const val CUES_WAIT_MS = 5_000L
        private const val CUES_POLL_MS = 50L
        private const val UNMATCHED_REPORT_MS = 5 * 60_000L
        private val initialized = AtomicBoolean(false)

        /** Settings persistence and the speech model. Safe to call more than once. */
        fun initialize(context: Context) {
            if (!initialized.compareAndSet(false, true)) return
            val appContext = context.applicationContext
            SyncLog.debugEnabled = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val preferences = appContext.getSharedPreferences("nuvio_audio_sync_settings", Context.MODE_PRIVATE)
            AudioSyncSettings.installPersistence(
                load = { key -> if (preferences.contains(key)) preferences.getBoolean(key, false) else null },
                save = { key, value -> preferences.edit().putBoolean(key, value).apply() },
                loadString = { key -> preferences.getString(key, null) },
                saveString = { key, value -> preferences.edit().putString(key, value).apply() },
            )
            AsrModel.initialize(appContext)
        }

        fun forExoPlayer(
            context: Context,
            scope: CoroutineScope,
            player: ExoPlayer,
            sidecar: SidecarSubtitleController,
            sourceUrl: String,
            sourceHeaders: Map<String, String>,
            getSubtitleHeaders: (String) -> Map<String, String>,
            onMimeTypeSelected: (String) -> Unit,
            onSubtitleDelayChanged: (Int) -> Unit,
            getAppliedListener: () -> ((subtitleUrl: String, delayMs: Int) -> Unit)?,
        ): AudioSyncFallback? {
            initialize(context)
            if (!AudioSyncSettings.fallbackEnabled.value) return null
            return AudioSyncFallback(
                context = context,
                scope = scope,
                player = player,
                sidecar = sidecar,
                mpvHost = null,
                sourceUrl = sourceUrl,
                sourceHeaders = sourceHeaders,
                getSubtitleHeaders = getSubtitleHeaders,
                onMimeTypeSelected = onMimeTypeSelected,
                onSubtitleDelayChanged = onSubtitleDelayChanged,
                getAppliedListener = getAppliedListener,
            )
        }

        fun forMpv(
            context: Context,
            scope: CoroutineScope,
            mpvHost: MpvAudioSyncHost,
            sourceUrl: String,
            sourceHeaders: Map<String, String>,
            getSubtitleHeaders: (String) -> Map<String, String>,
            onSubtitleDelayChanged: (Int) -> Unit,
        ): AudioSyncFallback? {
            initialize(context)
            if (!AudioSyncSettings.fallbackEnabled.value) return null
            return AudioSyncFallback(
                context = context,
                scope = scope,
                player = null,
                sidecar = null,
                mpvHost = mpvHost,
                sourceUrl = sourceUrl,
                sourceHeaders = sourceHeaders,
                getSubtitleHeaders = getSubtitleHeaders,
                onMimeTypeSelected = {},
                onSubtitleDelayChanged = onSubtitleDelayChanged,
                getAppliedListener = { null },
            )
        }

        private fun isLoopback(url: String): Boolean = runCatching {
            val host = Uri.parse(url).host.orEmpty().lowercase()
            host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
        }.getOrDefault(false)

        /** [cues] as SubRip text; cues without a start are skipped, open-ended ones last 2 s. */
        internal fun toSrt(cues: List<CuesWithTiming>): String = buildString {
            var index = 1
            for (entry in cues) {
                if (entry.startTimeUs == C.TIME_UNSET) continue
                val text = entry.cues.mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                    .joinToString("\n")
                if (text.isEmpty()) continue
                val endUs = when {
                    entry.durationUs != C.TIME_UNSET -> entry.startTimeUs + entry.durationUs
                    entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs
                    else -> entry.startTimeUs + 2_000_000L
                }
                append(index++).append('\n')
                append(srtTime(entry.startTimeUs)).append(" --> ").append(srtTime(endUs)).append('\n')
                append(text).append("\n\n")
            }
        }

        private fun srtTime(timeUs: Long): String {
            val totalMs = (timeUs / 1_000L).coerceAtLeast(0L)
            // Locale.ROOT: SubRip needs ASCII digits whatever the device language.
            return String.format(
                Locale.ROOT,
                "%02d:%02d:%02d,%03d",
                totalMs / 3_600_000L, totalMs / 60_000L % 60L, totalMs / 1_000L % 60L, totalMs % 1_000L,
            )
        }

        /**
         * [cues] placed on the media timeline by [model] (subtitle time -> media time); the
         * original cues when there is no mapping. Lines of a scene the release cuts are dropped,
         * as a subtitle delay would never show them either.
         */
        internal fun retime(cues: List<CuesWithTiming>, model: SubtitleSyncModel?): List<CuesWithTiming> {
            if (model == null) return cues
            return cues.mapNotNull { entry ->
                if (entry.startTimeUs == C.TIME_UNSET) return@mapNotNull entry
                val startUs = model.mediaTimeUs(entry.startTimeUs) ?: return@mapNotNull null
                val durationUs = when {
                    entry.durationUs != C.TIME_UNSET -> entry.durationUs
                    entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs - entry.startTimeUs
                    else -> C.TIME_UNSET
                }
                val scaledUs = if (durationUs == C.TIME_UNSET) durationUs else (durationUs * model.segmentAt(startUs / 1_000L).scale).toLong()
                CuesWithTiming(entry.cues, startUs, scaledUs)
            }.sortedBy { it.startTimeUs }
        }
    }
}

/**
 * Routes audio the player demuxes or plays to the fallback of the stream being played. The
 * player's extractors and audio output are created before the fallback, so they look it up here.
 */
internal object AudioSyncTaps {
    @Volatile
    private var active: AudioSubtitleSyncController? = null

    fun attach(controller: AudioSubtitleSyncController) {
        active = controller
    }

    fun detach(controller: AudioSubtitleSyncController) {
        if (active === controller) active = null
    }

    /** Copies the audio of [sourceKey]'s stream while it is demuxed (look-ahead). */
    fun wrapExtractors(factory: ExtractorsFactory, sourceKey: String): ExtractorsFactory =
        AudioSyncExtractorsFactory(factory, object : AudioSampleSink {
            private fun current() = active?.takeIf { it.currentSourceKey == sourceKey }

            override fun wantsSamples(format: Format): Boolean = current()?.wantsSamples(format) == true

            override fun onSample(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
                current()?.onSample(format, timeUs, data, offset, size)
            }

            override fun onDiscontinuity() {
                current()?.onDiscontinuity()
            }
        })

    /** Hears the player's own decoded audio where the look-ahead copy can't be decoded. */
    fun wrapAudioSink(sink: AudioSink): AudioSink = PlaybackAudioTap(sink, object : PlaybackPcmListener {
        override fun wantsPlaybackPcm(mediaTimeUs: Long, durationUs: Long): Boolean =
            active?.wantsPlaybackPcm(mediaTimeUs, durationUs) == true

        override fun onPlaybackPcm(mono: FloatArray, frames: Int, sampleRate: Int, mediaTimeUs: Long) {
            active?.onPlaybackPcm(mono, frames, sampleRate, mediaTimeUs)
        }
    })
}
