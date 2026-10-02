package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleLanguageMatching
import com.nuvio.app.features.addons.RawHttpResponse
import android.os.SystemClock
import androidx.media3.common.C
import com.nuvio.app.features.player.SubtitleSyncCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong

/**
 * Android-only AutoSync V2.
 *
 * V2 first performs a cheap fixed-scale delay-only check. If one constant offset
 * is strong and stable across the movie, it validates that hypothesis structurally
 * and applies one uniform shift. Otherwise it discovers the whole-film affine
 * transform and runs cue/group DP retiming.
 */
internal object AutomaticSubtitleSync {
    private const val MIN_SELECTED_CUES = 1
    private const val MAX_PARALLEL_ALTERNATIVE_DOWNLOADS = 6
    private const val MAX_PARALLEL_ALTERNATIVE_PARSES = 2
    private const val MAX_PARALLEL_ALTERNATIVE_MATCHES = 2
    private const val EXCEPTIONAL_MATCH_QUALITY = 0.95
    private const val EXCEPTIONAL_MATCH_TARGET_COVERAGE = 0.99
    private const val EXCEPTIONAL_MATCH_REFERENCE_COVERAGE = 0.97
    private const val EXCEPTIONAL_MATCH_SIMPLE_RATIO = 0.98
    private const val REFERENCE_SEARCH_CHECKPOINT = 2
    private const val STRONG_CHECKPOINT_QUALITY = 0.92
    private const val STRONG_CHECKPOINT_TARGET_COVERAGE = 0.98
    private const val STRONG_CHECKPOINT_REFERENCE_COVERAGE = 0.90
    private const val PASSIVE_CHECKPOINT_QUALITY = 0.88
    private const val PASSIVE_CHECKPOINT_TARGET_COVERAGE = 0.96
    private const val PASSIVE_CHECKPOINT_REFERENCE_COVERAGE = 0.85
    private const val ASYMMETRIC_CHECKPOINT_QUALITY = 0.94
    private const val ASYMMETRIC_CHECKPOINT_TARGET_COVERAGE = 0.995
    private const val ASYMMETRIC_CHECKPOINT_REFERENCE_COVERAGE = 0.84
    private const val ASYMMETRIC_CHECKPOINT_SIMPLE_RATIO = 0.97
    private const val ASYMMETRIC_CHECKPOINT_MAX_GROUP_COST = 0.20
    private const val ASYMMETRIC_CHECKPOINT_MIN_REFERENCE_RATIO = 1.15
    private const val ASYMMETRIC_CHECKPOINT_MAX_TARGET_SKIP_RUN = 2

    // Scheduling-only reference pre-ranker. It never accepts/rejects a match.
    private const val CHEAP_REFERENCE_SAMPLE_CUES = 24
    private const val CHEAP_TARGET_SAMPLE_CUES = 32
    private const val CHEAP_REFERENCE_OFFSET_CANDIDATES = 4
    private const val CHEAP_REFERENCE_MATCH_TOLERANCE_MS = 1_800L

    private const val FALLBACK_CANDIDATE_POLL_MS = 250L
    private const val FALLBACK_CANDIDATE_WAIT_MS = 10_000L

    private const val MIN_FULL_DIALOGUE_CUES = 8
    private const val MIN_FULL_DIALOGUE_CLASSIFICATION_SPAN_MS = 30_000L
    private const val MIN_FULL_DIALOGUE_DENSITY_PER_MINUTE = 2.0
    private const val MIN_FULL_DIALOGUE_TEXT_RATIO = 0.45
    private const val MIN_INDEXED_REFERENCE_SPAN_MS = 45_000L

    private const val LIVE_REFERENCE_WAIT_MS = 12_000L
    private const val SPARSE_LIVE_REFERENCE_WAIT_MS = 60_000L
    private const val MIN_SPARSE_LIVE_REFERENCE_CUES = 8
    private const val MIN_SPARSE_LIVE_REFERENCE_SPAN_MS = 30_000L
    private const val LIVE_REFERENCE_POLL_MS = 500L
    private const val MIN_LIVE_REFERENCE_CUES = 20
    private const val MIN_LIVE_REFERENCE_SPAN_RATIO = 0.80

    private const val HTTP_429_RETRY_DELAY_MS = 900L
    private const val SUBTITLE_DOWNLOAD_TIMEOUT_MS = 15_000L
    private const val MAX_SUBTITLE_RESPONSE_BYTES = 4 * 1024 * 1024
    private const val MAX_PARSED_SUBTITLE_CACHE_ENTRIES = 8

    private val parsedSubtitleCacheLock = Any()
    private val parsedSubtitleCache =
        object : LinkedHashMap<ParsedSubtitleCacheKey, CachedParsedSubtitle>(
            MAX_PARSED_SUBTITLE_CACHE_ENTRIES,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<ParsedSubtitleCacheKey, CachedParsedSubtitle>?,
            ): Boolean = size > MAX_PARSED_SUBTITLE_CACHE_ENTRIES
        }

    suspend fun findTimelineRetime(
        sourceKey: String,
        selectedSubtitleUrl: String,
        selectedSubtitleHeaders: Map<String, String>,
        selectedSubtitleBodyDeferred: Deferred<String?>? = null,
        preferredLanguage: String?,
        alternativeSubtitles: List<AutoSyncSubtitleCandidate> = emptyList(),
        alternativeSubtitlesProvider: (() -> List<AutoSyncSubtitleCandidate>)? = null,
        onReferenceReady: () -> Unit = {},
        onNoSubtitleTracks: () -> Unit = {},
        onAnalysisOutcome: ((AutoSyncAnalysisOutcome) -> Unit)? = null,
        sourceHeaders: Map<String, String> = emptyMap(),
    ): AutoSyncResolvedTimeline? {
        Unit
        val aggressiveMode = AutoSyncPreferencesRepository.aggressiveMode.value

        val result = supervisorScope {
            val indexedTimelineDeferred = async {
                EmbeddedSubtitleTimelineLoader.load(
                    sourceUrl = sourceKey,
                    sourceHeaders = sourceHeaders,
                )
            }
            val selectedSubtitleDeferred = async {
                val sharedBody = if (selectedSubtitleBodyDeferred != null) {
                    try {
                        selectedSubtitleBodyDeferred.await()
                    } catch (cancel: CancellationException) {
                        // If only the shared acquisition was cancelled, treat it as unavailable.
                        // If this matcher coroutine itself is cancelled, keep cancellation prompt.
                        currentCoroutineContext().ensureActive()
                        null
                    }
                } else {
                    null
                }

                if (selectedSubtitleBodyDeferred != null) {
                    sharedBody?.let { body ->
                        loadSelectedSubtitle(
                            url = selectedSubtitleUrl,
                            headers = selectedSubtitleHeaders,
                            rawBodyOverride = body,
                        )
                    }
                } else {
                    loadSelectedSubtitle(
                        url = selectedSubtitleUrl,
                        headers = selectedSubtitleHeaders,
                    )
                }
            }

            // Overlap same-language candidate downloads with embedded timeline indexing.
            // Scoring remains entirely V2.
            val alternativeDownloadSemaphore = Semaphore(MAX_PARALLEL_ALTERNATIVE_DOWNLOADS)
            val alternativeParseSemaphore = Semaphore(MAX_PARALLEL_ALTERNATIVE_PARSES)
            val prefetchedAlternativeLoads =
                linkedMapOf<String, Deferred<LoadedSubtitle?>>()

            fun currentPrefetchCandidates(): List<AutoSyncSubtitleCandidate> {
                val snapshot =
                    (alternativeSubtitlesProvider?.invoke() ?: alternativeSubtitles)
                        .distinctBy { it.url }
                val language =
                    snapshot.firstOrNull { it.url == selectedSubtitleUrl }
                        ?.language
                        ?.takeIf { it.isNotBlank() }
                        ?: preferredLanguage?.takeIf { it.isNotBlank() }

                return snapshot
                    .asSequence()
                    .filter { it.url.isNotBlank() && it.url != selectedSubtitleUrl }
                    .filter { candidate ->
                        language.isNullOrBlank() ||
                            SubtitleLanguageMatching.matchesLanguageCode(
                                candidate.language,
                                language,
                            )
                    }
                    .distinctBy { it.url }
                    .toList()
            }

            fun fillPrefetchSlots() {
                if (indexedTimelineDeferred.isCompleted) return

                var activeCount =
                    prefetchedAlternativeLoads.values.count { !it.isCompleted }
                val candidates = currentPrefetchCandidates()

                for (candidate in candidates) {
                    if (
                        indexedTimelineDeferred.isCompleted ||
                        activeCount >= MAX_PARALLEL_ALTERNATIVE_DOWNLOADS
                    ) {
                        break
                    }
                    if (candidate.url in prefetchedAlternativeLoads) continue

                    prefetchedAlternativeLoads[candidate.url] = async {
                        loadSelectedSubtitle(
                            url = candidate.url,
                            headers = emptyMap(),
                            downloadSemaphore = alternativeDownloadSemaphore,
                            parseSemaphore = alternativeParseSemaphore,
                        )
                    }
                    activeCount++
                }
            }

            while (!indexedTimelineDeferred.isCompleted) {
                fillPrefetchSlots()
                if (indexedTimelineDeferred.isCompleted) break

                val activePrefetches =
                    prefetchedAlternativeLoads.values.filterNot { it.isCompleted }

                if (activePrefetches.size < MAX_PARALLEL_ALTERNATIVE_DOWNLOADS) {
                    // Candidate providers can populate after AutoSync starts. Poll only while
                    // there is spare capacity; a full prefetch set already has useful work.
                    withTimeoutOrNull(FALLBACK_CANDIDATE_POLL_MS) {
                        select<Unit> {
                            indexedTimelineDeferred.onAwait { }
                            activePrefetches.forEach { job ->
                                job.onAwait { }
                            }
                        }
                    }
                } else {
                    select<Unit> {
                        indexedTimelineDeferred.onAwait { }
                        activePrefetches.forEach { job ->
                            job.onAwait { }
                        }
                    }
                }
            }

            val indexedTimeline = indexedTimelineDeferred.await()
            var selectedResolved = selectedSubtitleDeferred.isCompleted
            var selected =
                if (selectedResolved) {
                    selectedSubtitleDeferred.await()
                } else {
                    null
                }
            suspend fun consumeSelectedIfCompleted(): Boolean {
                if (selectedResolved) {
                    return true
                }
                if (!selectedSubtitleDeferred.isCompleted) return false

                selected = selectedSubtitleDeferred.await()
                selectedResolved = true
                return true
            }

            fun currentExternalCandidates(): List<AutoSyncSubtitleCandidate> =
                (alternativeSubtitlesProvider?.invoke() ?: alternativeSubtitles)
                    .distinctBy { it.url }

            fun selectedLanguage(
                candidates: List<AutoSyncSubtitleCandidate>,
            ): String? =
                candidates.firstOrNull { it.url == selectedSubtitleUrl }
                    ?.language
                    ?.takeIf { it.isNotBlank() }
                    ?: preferredLanguage?.takeIf { it.isNotBlank() }

            fun sameLanguageAlternatives(
                candidates: List<AutoSyncSubtitleCandidate>,
                language: String?,
            ): List<AutoSyncSubtitleCandidate> =
                candidates
                    .asSequence()
                    .filter { it.url.isNotBlank() && it.url != selectedSubtitleUrl }
                    .filter { candidate ->
                        language.isNullOrBlank() ||
                            SubtitleLanguageMatching.matchesLanguageCode(
                                candidate.language,
                                language,
                            )
                    }
                    .distinctBy { it.url }
                    .toList()

            var availableCandidates = currentExternalCandidates()
            var language = selectedLanguage(availableCandidates)
            var alternatives = sameLanguageAlternatives(availableCandidates, language)

            suspend fun awaitSameLanguageAlternatives() {
                if (
                    alternativeSubtitlesProvider == null ||
                    alternatives.isNotEmpty() ||
                    selected != null
                ) {
                    return
                }

                val waitStartedMs = SystemClock.elapsedRealtime()
                while (alternatives.isEmpty() && selected == null) {
                    availableCandidates = currentExternalCandidates()
                    language = selectedLanguage(availableCandidates)
                    alternatives = sameLanguageAlternatives(availableCandidates, language)
                    if (alternatives.isNotEmpty()) break
                    if (consumeSelectedIfCompleted() && selected != null) break

                    val elapsedMs = SystemClock.elapsedRealtime() - waitStartedMs
                    val remainingMs = FALLBACK_CANDIDATE_WAIT_MS - elapsedMs
                    if (remainingMs <= 0L) break
                    val waitSliceMs = minOf(FALLBACK_CANDIDATE_POLL_MS, remainingMs)

                    if (!selectedResolved) {
                        // Polling the provider must not hide a selected request that becomes
                        // usable in the meantime. Timeout only cancels this waiter, not the
                        // selected deferred itself.
                        withTimeoutOrNull(waitSliceMs) {
                            selectedSubtitleDeferred.await()
                        }
                        consumeSelectedIfCompleted()
                    } else {
                        delay(waitSliceMs)
                    }
                }

            }

            if (selected == null) {
                awaitSameLanguageAlternatives()
            }

            var seedTarget = selected?.cues

            if (seedTarget.isNullOrEmpty()) {
                val pendingSeedLoads = linkedMapOf<String, Deferred<LoadedSubtitle?>>()

                if (!selectedResolved) {
                    pendingSeedLoads[selectedSubtitleUrl] = selectedSubtitleDeferred
                }
                alternatives.forEach { candidate ->
                    prefetchedAlternativeLoads[candidate.url]?.let { job ->
                        pendingSeedLoads.putIfAbsent(candidate.url, job)
                    }
                }

                if (
                    pendingSeedLoads.keys.none { it != selectedSubtitleUrl } &&
                    alternatives.isNotEmpty()
                ) {
                    val candidate = alternatives
                        .firstOrNull { it.url !in prefetchedAlternativeLoads }
                    if (candidate != null) {
                        val job = async {
                            loadSelectedSubtitle(
                                url = candidate.url,
                                headers = emptyMap(),
                                downloadSemaphore = alternativeDownloadSemaphore,
                                parseSemaphore = alternativeParseSemaphore,
                            )
                        }
                        prefetchedAlternativeLoads[candidate.url] = job
                        pendingSeedLoads[candidate.url] = job
                    }
                }

                while (seedTarget.isNullOrEmpty() && pendingSeedLoads.isNotEmpty()) {
                    val completed = select<Pair<String, LoadedSubtitle?>> {
                        pendingSeedLoads.forEach { (url, job) ->
                            job.onAwait { url to it }
                        }
                    }
                    pendingSeedLoads.remove(completed.first)

                    if (completed.first == selectedSubtitleUrl) {
                        selectedResolved = true
                        selected = completed.second
                    }

                    completed.second?.let { loaded ->
                        seedTarget = loaded.cues
                    }
                }
            }

            // Preserve the original selected-subtitle fallback when every speculative seed
            // failed, but always consume an already-completed result instead of skipping it.
            if (seedTarget.isNullOrEmpty() && !selectedResolved) {
                selected = selectedSubtitleDeferred.await()
                selectedResolved = true
                seedTarget = selected?.cues
            }

            if (seedTarget.isNullOrEmpty()) {
                prefetchedAlternativeLoads.values.forEach { it.cancel() }
                selectedSubtitleDeferred.cancel()
                onAnalysisOutcome?.invoke(AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE)
                return@supervisorScope null
            }

            var referenceTracks: List<ReferenceTrack> = emptyList()
            var forcedFallbackTracks: List<ReferenceTrack> = emptyList()

            if (indexedTimeline != null) {
                val profiles = indexedTimeline.tracks.map(::buildReferenceProfile)
                val eligibleProfiles = profiles.filter { profile ->
                    profile.fullDialogueCandidate &&
                        profile.cueCount >= MIN_FULL_DIALOGUE_CUES &&
                        profile.spanMs >= MIN_INDEXED_REFERENCE_SPAN_MS
                }
                val preferredProfiles =
                    eligibleProfiles.filter { profile -> profile.fullDialogue }
                val forcedProfiles =
                    eligibleProfiles.filter { profile -> isForcedReferenceTrack(profile.track) }
                if (preferredProfiles.isNotEmpty()) {
                    referenceTracks = orderReferenceProfiles(preferredProfiles)
                        .map { it.track.copy(cues = it.track.cues.toList()) }
                    forcedFallbackTracks = orderReferenceProfiles(forcedProfiles)
                        .map { it.track.copy(cues = it.track.cues.toList()) }
                } else {
                    referenceTracks = orderReferenceProfiles(forcedProfiles)
                        .map { it.track.copy(cues = it.track.cues.toList()) }
                }
            }

            if (referenceTracks.isEmpty()) {
                val useSparseLiveReference =
                    indexedTimeline?.source == "matroska-cues-no-subtitle-entries"
                val liveSelection = awaitNearCompleteLiveReferences(
                    sourceKey = sourceKey,
                    preferredLanguage = preferredLanguage,
                    target = seedTarget,
                    waitMs = when {
                        useSparseLiveReference -> SPARSE_LIVE_REFERENCE_WAIT_MS
                        indexedTimeline?.skipLiveFallbackWait == true -> 0L
                        else -> LIVE_REFERENCE_WAIT_MS
                    },
                    allowSparseLiveReference = useSparseLiveReference,
                )
                referenceTracks = liveSelection.primary
                forcedFallbackTracks = liveSelection.forcedFallback
            }

            if (referenceTracks.isEmpty()) {
                val noSubtitleTracks = indexedTimeline?.noSubtitleTracks == true
                if (noSubtitleTracks) {
                    onNoSubtitleTracks()
                    onAnalysisOutcome?.invoke(AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS)
                } else {
                    onAnalysisOutcome?.invoke(AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE)
                }
                prefetchedAlternativeLoads.values.forEach { it.cancel() }
                selectedSubtitleDeferred.cancel()
                return@supervisorScope null
            }

            onReferenceReady()

            val referenceActivityCache =
                mutableMapOf<String, AutoSyncTimelineRetimer.PreparedActivity?>()

            if (referenceTracks.size >= 3) {
                val consistencyContext = currentCoroutineContext()
                val outliers = withContext(Dispatchers.Default) {
                    AutoSyncReferenceConsistency.findOutliers(
                        references = referenceTracks.mapNotNull { track ->
                            preparedReferenceActivity(track, referenceActivityCache)?.let {
                                AutoSyncReferenceConsistency.Reference(
                                    key = track.key,
                                    activity = it,
                                    cueCount = track.cues.size,
                                )
                            }
                        },
                        cancellationCheck = { consistencyContext.ensureActive() },
                    )
                }
                if (outliers.isNotEmpty()) {
                    referenceTracks = referenceTracks.filterNot { it.key in outliers }
                }
            }

            // The Nuvio-selected subtitle is usually automatic, so treat it as one timing
            // candidate rather than giving it expensive V2 priority. Consume any completion
            // that occurred while reference preparation was running before refreshing providers.
            consumeSelectedIfCompleted()
            awaitSameLanguageAlternatives()

            val selectedCandidateMetadata =
                availableCandidates.firstOrNull { it.url == selectedSubtitleUrl }
                    ?: AutoSyncSubtitleCandidate(
                        url = selectedSubtitleUrl,
                        language = language ?: preferredLanguage.orEmpty(),
                        name = "Selected",
                    )

            // Put the selected subtitle into the same pool. It remains eligible, but is no
            // longer privileged merely because Nuvio auto-selected it.
            alternatives = (alternatives + selectedCandidateMetadata)
                .distinctBy { it.url }

            val pipelineContext = currentCoroutineContext()

            val candidateByUrl = linkedMapOf<String, AutoSyncSubtitleCandidate>()
            val candidateOrder = linkedMapOf<String, Int>()
            var nextCandidateIndex = 0

            fun registerCandidate(candidate: AutoSyncSubtitleCandidate) {
                if (candidate.url.isBlank() || candidate.url in candidateByUrl) return
                candidateByUrl[candidate.url] = candidate
                candidateOrder[candidate.url] = nextCandidateIndex++
            }

            alternatives.forEach(::registerCandidate)

            fun refreshCandidatePool() {
                if (alternativeSubtitlesProvider == null) return
                availableCandidates = currentExternalCandidates()
                language = selectedLanguage(availableCandidates)
                val refreshed =
                    sameLanguageAlternatives(availableCandidates, language) +
                        selectedCandidateMetadata
                refreshed.distinctBy { it.url }.forEach(::registerCandidate)
            }

            val loadedByUrl = mutableMapOf<String, LoadedSubtitle>()
            val scheduledUrls = hashSetOf<String>()
            val activeLoads = linkedMapOf<String, Deferred<LoadedSubtitle?>>()

            // Do not starve an already-parsed selected subtitle behind speculative prefetches.
            // This changes only readiness order; matching and scoring remain identical.
            if (selected != null) {
                activeLoads[selectedSubtitleUrl] = selectedSubtitleDeferred
                scheduledUrls += selectedSubtitleUrl
            }

            prefetchedAlternativeLoads.forEach { (url, job) ->
                if (url in candidateByUrl) {
                    activeLoads[url] = job
                    scheduledUrls += url
                } else {
                    job.cancel()
                }
            }

            fun headersForCandidate(url: String): Map<String, String> =
                if (url == selectedSubtitleUrl) selectedSubtitleHeaders else emptyMap()

            fun scheduleMoreLoads() {
                refreshCandidatePool()
                val candidatesToSchedule = candidateByUrl.values.filter { candidate ->
                    candidate.url !in scheduledUrls &&
                        candidate.url !in loadedByUrl
                }

                for (candidate in candidatesToSchedule) {
                    if (activeLoads.size >= MAX_PARALLEL_ALTERNATIVE_DOWNLOADS) break

                    scheduledUrls += candidate.url
                    activeLoads[candidate.url] = async {
                        if (candidate.url == selectedSubtitleUrl) {
                            selected ?: selectedSubtitleDeferred.await()
                        } else {
                            loadSelectedSubtitle(
                                url = candidate.url,
                                headers = emptyMap(),
                                downloadSemaphore = alternativeDownloadSemaphore,
                                parseSemaphore = alternativeParseSemaphore,
                            )
                        }
                    }
                }
            }

            val timingFamilies = mutableListOf<CandidateTimingFamilyState>()
            val queuedPairs = mutableListOf<PairHypothesis>()
            val activePairJobs =
                linkedMapOf<Int, Deferred<CompletedPairEvaluation>>()
            val activePairPriorities = mutableMapOf<Int, Double>()
            var nextPairJobId = 0
            var evaluatedPairs = 0
            var peakPairWorkers = 0
            var bestFamily: CandidateTimingFamilyState? = null
            var bestMatch: TimelineRetimeMatch? = null

            val pairComparator =
                compareBy<PairHypothesis> {
                    // A family with exactly one usable attempt needs one more usable reference
                    // before the existing nonexceptional checkpoint can authorize stopping.
                    // Prioritize only that bounded continuation; never drop or cap other work.
                    if (
                        it.family.completedUsableAttempts in
                            1 until REFERENCE_SEARCH_CHECKPOINT
                    ) {
                        1
                    } else {
                        0
                    }
                }
                    .thenBy { it.schedulingScore }
                    .thenBy { it.rankedReference.cheapAffinity }
                    .thenBy { it.rankedReference.suitability }
                    .thenBy { if (it.preflightChampion) 1 else 0 }
                    .thenBy { -it.family.representative.index }
                    .thenBy { it.rankedReference.track.key }

            suspend fun admitLoadedCandidate(
                candidate: AutoSyncSubtitleCandidate,
                loaded: LoadedSubtitle,
            ) {
                loadedByUrl[candidate.url] = loaded
                val index = candidateOrder[candidate.url] ?: return
                val alternative = LoadedAlternative(
                    index = index,
                    candidate = candidate,
                    loaded = loaded,
                )

                val equivalent = timingFamilies.firstOrNull { family ->
                    constantTimelineShiftMs(
                        family.representative.loaded.cues,
                        loaded.cues,
                    ) != null
                }
                if (equivalent != null) {
                    equivalent.members += alternative
                    return
                }

                val targetActivity = withContext(Dispatchers.Default) {
                    AutoSyncTimelineRetimer.prepareUnitActivity(loaded.cues)
                }

                val preflightResult = if (targetActivity != null) {
                    withContext(Dispatchers.Default) {
                        AutoSyncDelayPreflight.evaluate(
                            referenceTracks = referenceTracks,
                            target = loaded.cues,
                            referenceActivityCache = referenceActivityCache,
                            preparedTargetActivity = targetActivity,
                            cancellationCheck = { pipelineContext.ensureActive() },
                        )
                    }
                } else {
                    AutoSyncDelayPreflight.Result(
                        best = null,
                        evidenceByReferenceKey = emptyMap(),
                    )
                }
                val preflight = preflightResult.best

                val rankedReferences = withContext(Dispatchers.Default) {
                    rankReferenceCandidates(
                        target = loaded.cues,
                        referenceTracks = referenceTracks,
                        preferredReferenceKey = preflight?.referenceKey,
                    )
                }

                val family = CandidateTimingFamilyState(
                    representative = alternative,
                    members = mutableListOf(alternative),
                    targetActivity = targetActivity,
                    preflight = preflight,
                    preflightEvidenceByReferenceKey =
                        preflightResult.evidenceByReferenceKey,
                    rankedReferences = rankedReferences,
                )
                timingFamilies += family

                rankedReferences.forEach { ranked ->
                    val isPreflightChampion =
                        preflight?.referenceKey == ranked.track.key
                    val evidence =
                        preflightResult.evidenceByReferenceKey[ranked.track.key]
                    val championHint =
                        preflight?.takeIf { isPreflightChampion }
                    queuedPairs += PairHypothesis(
                        family = family,
                        rankedReference = ranked,
                        preflightHint = evidence?.validatedAlignment?.let { alignment ->
                            AutoSyncDelayPreflight.Match(
                                referenceKey = ranked.track.key,
                                alignment = alignment,
                            )
                        },
                        preflightEvidence = evidence?.search,
                        preflightChampion = isPreflightChampion,
                        schedulingScore = maxOf(
                            ranked.cheapAffinity,
                            championHint?.score ?: Double.NEGATIVE_INFINITY,
                        ),
                    )
                }

            }

            fun startPairJobs() {
                while (
                    activePairJobs.size < MAX_PARALLEL_ALTERNATIVE_MATCHES &&
                    queuedPairs.isNotEmpty()
                ) {
                    val hypothesis = queuedPairs.maxWithOrNull(pairComparator) ?: break
                    queuedPairs.remove(hypothesis)

                    val referenceKey = hypothesis.rankedReference.track.key
                    if (!hypothesis.family.startedReferenceKeys.add(referenceKey)) continue

                    val jobId = nextPairJobId++
                    activePairPriorities[jobId] = hypothesis.schedulingScore
                    activePairJobs[jobId] = async(Dispatchers.Default) {
                        val representative = hypothesis.family.representative
                        val evaluation = evaluatePair(
                            label =
                                "PAIR[${representative.index}/$referenceKey]",
                            url = representative.candidate.url,
                            target = representative.loaded.cues,
                            rankedReference = hypothesis.rankedReference,
                            referenceActivityCache = referenceActivityCache,
                            preflightHint = hypothesis.preflightHint,
                            preflightEvidence = hypothesis.preflightEvidence,
                            allowPrecomputedDelayFastPath = hypothesis.preflightChampion,
                            preparedTargetActivity = hypothesis.family.targetActivity,
                        )
                        CompletedPairEvaluation(
                            hypothesis = hypothesis,
                            evaluation = evaluation,
                        )
                    }
                }
                peakPairWorkers = maxOf(peakPairWorkers, activePairJobs.size)
            }

            fun isBetterMatch(
                candidate: TimelineRetimeMatch,
                current: TimelineRetimeMatch?,
                sameTarget: Boolean,
            ): Boolean {
                if (current == null) return true
                if (candidate.timeline.confident != current.timeline.confident) {
                    return candidate.timeline.confident
                }
                if (sameTarget) {
                    preferTighterFitOnDisagreement(candidate.timeline, current.timeline)
                        ?.let { preferCandidate ->
                            return preferCandidate
                        }
                }
                val candidateQuality = directTimelineQualityScore(candidate)
                val currentQuality = directTimelineQualityScore(current)
                if (candidateQuality != currentQuality) {
                    return candidateQuality > currentQuality
                }
                return candidate.track.key < current.track.key
            }

            suspend fun evaluateForcedReferenceFallback():
                Pair<CandidateTimingFamilyState, TimelineRetimeMatch>? {
                if (forcedFallbackTracks.isEmpty() || timingFamilies.isEmpty()) return null

                var fallbackBestFamily: CandidateTimingFamilyState? = null
                var fallbackBestMatch: TimelineRetimeMatch? = null
                var fallbackPairs = 0

                for (family in timingFamilies) {
                    pipelineContext.ensureActive()
                    val target = family.representative.loaded.cues
                    val preflightResult =
                        if (family.targetActivity != null) {
                            withContext(Dispatchers.Default) {
                                AutoSyncDelayPreflight.evaluate(
                                    referenceTracks = forcedFallbackTracks,
                                    target = target,
                                    referenceActivityCache = referenceActivityCache,
                                    preparedTargetActivity = family.targetActivity,
                                    cancellationCheck = { pipelineContext.ensureActive() },
                                )
                            }
                        } else {
                            AutoSyncDelayPreflight.Result(
                                best = null,
                                evidenceByReferenceKey = emptyMap(),
                            )
                        }
                    val preflight = preflightResult.best
                    val rankedReferences = withContext(Dispatchers.Default) {
                        rankReferenceCandidates(
                            target = target,
                            referenceTracks = forcedFallbackTracks,
                            preferredReferenceKey = preflight?.referenceKey,
                        )
                    }

                    for (ranked in rankedReferences) {
                        pipelineContext.ensureActive()
                        val referenceKey = ranked.track.key
                        val evidence = preflightResult.evidenceByReferenceKey[referenceKey]
                        val isPreflightChampion = preflight?.referenceKey == referenceKey
                        val evaluation = evaluatePair(
                            label =
                                "FORCED PAIR[${family.representative.index}/$referenceKey]",
                            url = family.representative.candidate.url,
                            target = target,
                            rankedReference = ranked,
                            referenceActivityCache = referenceActivityCache,
                            preflightHint = evidence?.validatedAlignment?.let { alignment ->
                                AutoSyncDelayPreflight.Match(
                                    referenceKey = referenceKey,
                                    alignment = alignment,
                                )
                            },
                            preflightEvidence = evidence?.search,
                            allowPrecomputedDelayFastPath = isPreflightChampion,
                            preparedTargetActivity = family.targetActivity,
                        )
                        fallbackPairs++

                        val match = evaluation.match
                        if (
                            match != null &&
                            match.timeline.confident &&
                            isBetterMatch(
                                match,
                                fallbackBestMatch,
                                sameTarget = family === fallbackBestFamily,
                            )
                        ) {
                            fallbackBestMatch = match
                            fallbackBestFamily = family
                        }
                    }
                }

                val family = fallbackBestFamily ?: return null
                val match = fallbackBestMatch ?: return null
                return family to match
            }

            // Early-stop rule only. Stopping here, or running out of work, both apply the best
            // match that passed the retimer's own confidence gates (bestMatch below). The
            // thorough (aggressive) checkpoint thresholds therefore make the search continue
            // longer before settling; they never reject a match passive mode would apply.
            fun canStopForFamily(
                family: CandidateTimingFamilyState,
                match: TimelineRetimeMatch,
            ): Boolean {
                if (isExceptionalMatch(match)) return true
                val requiredAttempts =
                    minOf(REFERENCE_SEARCH_CHECKPOINT, family.rankedReferences.size)
                if (family.completedUsableAttempts < requiredAttempts) {
                    return false
                }
                return isStrongCheckpointMatch(match, aggressiveMode) ||
                    isAsymmetricReferenceCheckpointMatch(
                        match,
                        targetCueCount = family.representative.loaded.cues.size,
                    )
            }

            fun hasEqualOrHigherPriorityOutstanding(
                family: CandidateTimingFamilyState,
            ): Boolean {
                val threshold = family.bestSchedulingScore
                if (!threshold.isFinite()) return true
                return queuedPairs.any { it.schedulingScore >= threshold } ||
                    activePairPriorities.values.any { it >= threshold }
            }

            fun recordCompletedPair(
                completed: CompletedPairEvaluation,
            ) {
                evaluatedPairs++

                val family = completed.hypothesis.family
                val pairMatch = completed.evaluation.match
                if (pairMatch != null) {
                    family.completedUsableAttempts++
                    if (isBetterMatch(pairMatch, family.best, sameTarget = true)) {
                        family.best = pairMatch
                        family.bestSchedulingScore = completed.hypothesis.schedulingScore
                    }
                }

                val familyBest = family.best
                if (
                    familyBest != null &&
                    familyBest.timeline.confident &&
                    isBetterMatch(familyBest, bestMatch, sameTarget = family === bestFamily)
                ) {
                    bestMatch = familyBest
                    bestFamily = family
                }

                if (
                    !family.abandoned &&
                    familyBest?.timeline?.confident != true &&
                    AutoSyncNoFitTracker.isNoFit(pairMatch?.timeline)
                ) {
                    val referenceActivity = preparedReferenceActivity(
                        completed.hypothesis.rankedReference.track,
                        referenceActivityCache,
                    )
                    if (referenceActivity != null && family.noFit.recordNoFit(referenceActivity)) {
                        family.abandoned = true
                        queuedPairs.removeAll { it.family === family }
                    }
                }
            }

            suspend fun drainCompletedPairs() {
                val completedIds = activePairJobs
                    .filterValues { it.isCompleted }
                    .keys
                    .toList()

                for (jobId in completedIds) {
                    val job = activePairJobs.remove(jobId) ?: continue
                    activePairPriorities.remove(jobId)
                    recordCompletedPair(job.await())
                }
            }

            fun findStopFamily(): CandidateTimingFamilyState? {
                val exceptional = timingFamilies.firstOrNull { family ->
                    family.best?.let(::isExceptionalMatch) == true
                }
                if (exceptional != null) return exceptional

                return timingFamilies
                    .asSequence()
                    .filter { family ->
                        val match = family.best ?: return@filter false
                        match.timeline.confident &&
                            canStopForFamily(family, match) &&
                            !hasEqualOrHigherPriorityOutstanding(family)
                    }
                    .maxByOrNull { it.bestSchedulingScore }
            }

            scheduleMoreLoads()
            startPairJobs()

            fun authoritativeStopFamily(): CandidateTimingFamilyState? =
                findStopFamily()

            var strongStop = false
            try {
                while (!strongStop) {
                    // Pair workers may finish while candidate preparation is running. Always
                    // consume those results before launching or admitting more speculative work.
                    drainCompletedPairs()
                    if (authoritativeStopFamily() != null) strongStop = true
                    if (strongStop) break

                    startPairJobs()
                    scheduleMoreLoads()

                    // Starting/refilling is cheap, but a worker may already have completed.
                    drainCompletedPairs()
                    if (authoritativeStopFamily() != null) strongStop = true
                    if (strongStop) break

                    if (
                        activeLoads.isEmpty() &&
                        activePairJobs.isEmpty() &&
                        queuedPairs.isEmpty()
                    ) {
                        refreshCandidatePool()
                        scheduleMoreLoads()
                        if (activeLoads.isEmpty()) break
                    }

                    val event = select<SchedulerEvent> {
                        activeLoads.forEach { (url, job) ->
                            job.onAwait { loaded ->
                                SchedulerEvent.CandidateLoaded(url, loaded)
                            }
                        }
                        activePairJobs.forEach { (jobId, job) ->
                            job.onAwait { completed ->
                                SchedulerEvent.PairEvaluated(jobId, completed)
                            }
                        }
                    }

                    when (event) {
                        is SchedulerEvent.CandidateLoaded -> {
                            activeLoads.remove(event.url)
                            val candidate = candidateByUrl[event.url]
                            scheduleMoreLoads()

                            // Do not let synchronous activity/preflight/ranking preparation hide
                            // a matcher result that already completed.
                            drainCompletedPairs()
                            if (authoritativeStopFamily() != null) strongStop = true
                            if (strongStop) continue

                            if (candidate != null && event.loaded != null) {
                                admitLoadedCandidate(candidate, event.loaded)
                            }

                            drainCompletedPairs()
                        }

                        is SchedulerEvent.PairEvaluated -> {
                            activePairJobs.remove(event.jobId)
                            activePairPriorities.remove(event.jobId)
                            recordCompletedPair(event.completed)
                            drainCompletedPairs()
                        }
                    }

                    if (authoritativeStopFamily() != null) strongStop = true
                }
            } finally {
                val pendingLoads =
                    activeLoads.filterValues { !it.isCompleted }
                val pendingPairs =
                    activePairJobs.values.filterNot { it.isCompleted }

                pendingLoads.values.forEach { it.cancel() }
                pendingPairs.forEach { it.cancel() }
            }

            var winningFamily = bestFamily
            var winningMatch = bestMatch
            if (
                (winningFamily == null || winningMatch == null || !winningMatch.timeline.confident) &&
                forcedFallbackTracks.isNotEmpty()
            ) {
                evaluateForcedReferenceFallback()?.let { fallback ->
                    winningFamily = fallback.first
                    winningMatch = fallback.second
                }
            }
            if (winningFamily == null || winningMatch == null || !winningMatch.timeline.confident) {
                if (!selectedResolved) {
                    selectedSubtitleDeferred.cancel()
                }
                return@supervisorScope null
            }

            val resolvedWinningFamily = winningFamily
            val resolvedWinningMatch = winningMatch
            val winningMember =
                resolvedWinningFamily.members.minByOrNull { it.index }
                    ?: resolvedWinningFamily.representative
            val memberMatch =
                if (winningMember === resolvedWinningFamily.representative) {
                    resolvedWinningMatch
                } else {
                    reuseShiftEquivalentMatch(
                        match = resolvedWinningMatch,
                        representativeTarget =
                            resolvedWinningFamily.representative.loaded.cues,
                        target = winningMember.loaded.cues,
                    ) ?: resolvedWinningMatch
                }

            if (!selectedResolved) {
                selectedSubtitleDeferred.cancel()
            }

            return@supervisorScope AutoSyncResolvedTimeline(
                subtitleUrl = winningMember.candidate.url,
                subtitleHeaders = headersForCandidate(winningMember.candidate.url),
                subtitleBody = winningMember.loaded.rawBody,
                timeline = memberMatch.timeline,
            )
        }
        return result
    }

    private suspend fun evaluatePair(
        label: String,
        url: String,
        target: List<SubtitleSyncCue>,
        rankedReference: RankedReferenceCandidate,
        referenceActivityCache: MutableMap<String, AutoSyncTimelineRetimer.PreparedActivity?>,
        preflightHint: AutoSyncDelayPreflight.Match? = null,
        preflightEvidence: AutoSyncTimelineRetimer.DelayOnlySearchEvidence? = null,
        allowPrecomputedDelayFastPath: Boolean = false,
        preparedTargetActivity: AutoSyncTimelineRetimer.PreparedActivity? = null,
    ): PairEvaluation = withContext(Dispatchers.Default) {
        val evaluationContext = currentCoroutineContext()
        val targetActivity =
            preparedTargetActivity ?: AutoSyncTimelineRetimer.prepareUnitActivity(target)
        val track = rankedReference.track

        preflightHint?.let { hint ->
        }

        val preparedReference = if (track.sampled) {
            null
        } else {
            preparedReferenceActivity(track, referenceActivityCache)
        }

        val timeline = buildTimelineRetimeResult(
            track = track,
            target = target,
            preparedReferenceActivity = preparedReference,
            preparedTargetActivity = targetActivity,
            delayOnlyHint =
                preflightHint
                    ?.takeIf { it.referenceKey == track.key }
                    ?.alignment,
            delayOnlyEvidence = preflightEvidence,
            allowPrecomputedDelayFastPath = allowPrecomputedDelayFastPath,
            cancellationCheck = { evaluationContext.ensureActive() },
        )
        if (timeline == null) {
            return@withContext PairEvaluation(match = null)
        }

        val match = TimelineRetimeMatch(track, timeline)

        PairEvaluation(match = match)
    }

    /** Unit-scale activity for [track], prepared once per run and shared across workers. */
    internal fun preparedReferenceActivity(
        track: ReferenceTrack,
        cache: MutableMap<String, AutoSyncTimelineRetimer.PreparedActivity?>,
    ): AutoSyncTimelineRetimer.PreparedActivity? = synchronized(cache) {
        if (cache.containsKey(track.key)) {
            cache[track.key]
        } else {
            AutoSyncTimelineRetimer.prepareUnitActivity(track.cues).also { cache[track.key] = it }
        }
    }

    private fun constantTimelineShiftMs(
        representative: List<SubtitleSyncCue>,
        candidate: List<SubtitleSyncCue>,
    ): Long? {
        if (representative.size < 4 || representative.size != candidate.size) return null

        val shiftMs = candidate.first().startTimeMs - representative.first().startTimeMs
        for (index in representative.indices) {
            val left = representative[index]
            val right = candidate[index]
            if (
                right.startTimeMs - left.startTimeMs != shiftMs ||
                right.endTimeMs - left.endTimeMs != shiftMs
            ) {
                return null
            }
        }
        return shiftMs
    }

    private fun reuseShiftEquivalentMatch(
        match: TimelineRetimeMatch,
        representativeTarget: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): TimelineRetimeMatch? {
        val shiftMs = constantTimelineShiftMs(representativeTarget, target)
            ?: return null
        val timeline = match.timeline
        if (timeline.cues.size != target.size) return null

        return match.copy(
            timeline = timeline.copy(
                cues = timeline.cues.mapIndexed { index, cue ->
                    val targetCue = target[index]
                    cue.copy(
                        originalStartTimeMs = targetCue.startTimeMs,
                        originalEndTimeMs = targetCue.endTimeMs,
                    )
                },
                alignmentInterceptMs =
                    timeline.alignmentInterceptMs -
                        timeline.alignmentScale * shiftMs.toDouble(),
            ),
        )
    }

    private fun rankReferenceCandidates(
        target: List<SubtitleSyncCue>,
        referenceTracks: List<ReferenceTrack>,
        preferredReferenceKey: String? = null,
    ): List<RankedReferenceCandidate> =
        groupEquivalentReferenceTimelines(referenceTracks)
            .mapNotNull { group ->
                group.members.minWithOrNull(
                    compareBy<ReferenceTrack> { isSdhReferenceTrack(it) }
                        .thenBy { it.key },
                )
            }
            .map { track ->
                RankedReferenceCandidate(
                    track = track,
                    cheapAffinity = cheapReferenceAffinity(track.cues, target),
                    suitability = referenceSuitabilityScore(track, target),
                )
            }
            .sortedWith(
                compareByDescending<RankedReferenceCandidate> {
                    if (it.track.key == preferredReferenceKey) 1 else 0
                }.thenByDescending { it.cheapAffinity }
                    .thenByDescending { it.suitability }
                    .thenBy { isSdhReferenceTrack(it.track) }
                    .thenBy { it.track.key },
            )

    private fun referenceCueRatio(
        track: ReferenceTrack,
        target: List<SubtitleSyncCue>,
    ): Double {
        if (track.cues.isEmpty() || target.isEmpty()) return 0.0
        val smaller = minOf(track.cues.size, target.size).toDouble()
        val larger = maxOf(track.cues.size, target.size).toDouble()
        return if (larger <= 0.0) 0.0 else smaller / larger
    }

    private fun referenceSuitabilityScore(
        track: ReferenceTrack,
        target: List<SubtitleSyncCue>,
    ): Double {
        if (track.cues.isEmpty() || target.isEmpty()) return 0.0
        val cueRatio = referenceCueRatio(track, target)
        val referenceSpan = referenceSpanMs(track.cues).coerceAtLeast(1L)
        val targetSpan = referenceSpanMs(target).coerceAtLeast(1L)
        val spanRatio =
            minOf(referenceSpan, targetSpan).toDouble() /
                maxOf(referenceSpan, targetSpan).toDouble()
        val nonSdhBonus = if (isSdhReferenceTrack(track)) 0.0 else 0.08
        return cueRatio * 0.60 + spanRatio * 0.32 + nonSdhBonus
    }

    private fun cheapReferenceAffinity(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): Double {
        if (reference.size < 4 || target.size < 4) return 0.0

        val referenceSample = evenlySampleCues(reference, CHEAP_REFERENCE_SAMPLE_CUES)
        val targetSample = evenlySampleCues(target, CHEAP_TARGET_SAMPLE_CUES)
        if (referenceSample.isEmpty() || targetSample.isEmpty()) return 0.0

        val scales = mutableListOf(
            1.0,
            25.0 / 23.976,
            23.976 / 25.0,
            25.0 / 24.0,
            24.0 / 25.0,
            24.0 / 23.976,
            23.976 / 24.0,
        )

        val referenceSpan = reference.last().startTimeMs - reference.first().startTimeMs
        val targetSpan = target.last().startTimeMs - target.first().startTimeMs
        if (referenceSpan > 0L && targetSpan > 0L) {
            val observedScale = referenceSpan.toDouble() / targetSpan.toDouble()
            if (
                observedScale.isFinite() &&
                observedScale in 0.94..1.06 &&
                scales.none { abs(it - observedScale) < 0.00035 }
            ) {
                scales += observedScale
            }
        }

        var bestScore = 0.0
        for (scale in scales) {
            val offsets = LinkedHashSet<Long>()

            for (anchor in 0 until CHEAP_REFERENCE_OFFSET_CANDIDATES) {
                val referenceIndex =
                    (anchor.toLong() * referenceSample.lastIndex /
                        (CHEAP_REFERENCE_OFFSET_CANDIDATES - 1)).toInt()
                val targetIndex =
                    (anchor.toLong() * targetSample.lastIndex /
                        (CHEAP_REFERENCE_OFFSET_CANDIDATES - 1)).toInt()

                offsets += referenceSample[referenceIndex].startTimeMs -
                    (targetSample[targetIndex].startTimeMs.toDouble() * scale).roundToLong()
            }

            for (offsetMs in offsets) {
                var hits = 0
                var residualTotal = 0L

                for (cue in targetSample) {
                    val shiftedStart =
                        (cue.startTimeMs.toDouble() * scale).roundToLong() + offsetMs
                    val insertion = lowerBoundCueStart(reference, shiftedStart)

                    var nearest = Long.MAX_VALUE
                    if (insertion < reference.size) {
                        nearest = abs(reference[insertion].startTimeMs - shiftedStart)
                    }
                    if (insertion > 0) {
                        nearest = minOf(
                            nearest,
                            abs(reference[insertion - 1].startTimeMs - shiftedStart),
                        )
                    }

                    if (nearest <= CHEAP_REFERENCE_MATCH_TOLERANCE_MS) {
                        hits++
                        residualTotal += nearest
                    }
                }

                if (hits == 0) continue
                val participation = hits.toDouble() / targetSample.size.toDouble()
                val meanResidual = residualTotal.toDouble() / hits.toDouble()
                val residualScore = exp(-meanResidual / 900.0)
                val score = participation * 0.80 + residualScore * 0.20
                if (score > bestScore) bestScore = score
            }
        }

        return bestScore.coerceIn(0.0, 1.0)
    }

    private fun evenlySampleCues(
        cues: List<SubtitleSyncCue>,
        maxSamples: Int,
    ): List<SubtitleSyncCue> {
        if (cues.size <= maxSamples) return cues
        if (maxSamples <= 1) return listOf(cues.first())

        val lastIndex = cues.lastIndex
        return (0 until maxSamples)
            .map { sampleIndex ->
                cues[(sampleIndex.toLong() * lastIndex / (maxSamples - 1)).toInt()]
            }
            .distinctBy { it.startTimeMs }
    }

    private fun lowerBoundCueStart(
        cues: List<SubtitleSyncCue>,
        timeMs: Long,
    ): Int {
        var low = 0
        var high = cues.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cues[middle].startTimeMs < timeMs) low = middle + 1 else high = middle
        }
        return low
    }

    private suspend fun loadSelectedSubtitle(
        url: String,
        headers: Map<String, String>,
        rawBodyOverride: String? = null,
        downloadSemaphore: Semaphore? = null,
        parseSemaphore: Semaphore? = null,
    ): LoadedSubtitle? {
        val cacheKey = ParsedSubtitleCacheKey(url, stableHeaderIdentity(headers))
        if (rawBodyOverride == null) {
            synchronized(parsedSubtitleCacheLock) {
                parsedSubtitleCache[cacheKey]
            }?.let { cached ->
                return LoadedSubtitle(
                    cues = cached.cues,
                    rawBody = cached.rawBody,
                )
            }
        }

        val text = if (rawBodyOverride != null) {
            rawBodyOverride
        } else {
            try {
                if (downloadSemaphore != null) {
                    downloadSemaphore.withPermit {
                        downloadSubtitleTextWithSingle429Retry(url, headers)
                    }
                } else {
                    downloadSubtitleTextWithSingle429Retry(url, headers)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                return null
            }
        }
        val cues = try {
            val parseAndNormalize: suspend () -> List<SubtitleSyncCue> = {
                withContext(Dispatchers.Default) {
                    val parseContext = currentCoroutineContext()
                    val parsed = AutoSyncSubtitleCueParser.parse(
                        text = text,
                        sourceUrl = url,
                        cancellationCheck = { parseContext.ensureActive() },
                    )
                    parseContext.ensureActive()
                    AutoSyncTimelineRetimer.normalizeExternalTimeline(parsed)
                }
            }
            if (parseSemaphore != null) {
                parseSemaphore.withPermit {
                    parseAndNormalize()
                }
            } else {
                parseAndNormalize()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            return null
        }

        if (cues.size < MIN_SELECTED_CUES) {
            return null
        }

        val immutable = cues.toList()
        // A body supplied by the active sidecar is selection-owned and exact. Do not let a
        // URL/header-only cache substitute bytes from a mutable same-URL resource later.
        if (rawBodyOverride == null) {
            synchronized(parsedSubtitleCacheLock) {
                parsedSubtitleCache[cacheKey] = CachedParsedSubtitle(
                    cues = immutable,
                    rawBody = text,
                )
            }
        }
        return LoadedSubtitle(
            cues = immutable,
            rawBody = text,
        )
    }

    internal suspend fun downloadSubtitleBody(
        url: String,
        headers: Map<String, String>,
    ): String =
        downloadSubtitleTextWithSingle429Retry(
            url = url,
            headers = headers,
        )

    private suspend fun downloadSubtitleTextWithSingle429Retry(
        url: String,
        headers: Map<String, String>,
    ): String {
        val first = requestSubtitle(url, headers)
        if (first.status == 429) {
            delay(HTTP_429_RETRY_DELAY_MS)
            return validatedSubtitleBody(requestSubtitle(url, headers))
        }
        return validatedSubtitleBody(first)
    }

    private suspend fun requestSubtitle(
        url: String,
        headers: Map<String, String>,
    ): RawHttpResponse =
        withTimeoutOrNull(SUBTITLE_DOWNLOAD_TIMEOUT_MS) {
            AutoSyncSubtitleHttp.get(
                url = url,
                headers = mapOf("Accept" to "*/*") + headers,
                maxResponseBodyBytes = MAX_SUBTITLE_RESPONSE_BYTES,
            )
        } ?: error("subtitle request timed out after ${SUBTITLE_DOWNLOAD_TIMEOUT_MS}ms")

    private fun validatedSubtitleBody(
        response: RawHttpResponse,
    ): String {
        if (response.status !in 200..299) error("subtitle HTTP ${response.status}")
        if (response.body.endsWith("\n...[truncated]")) {
            error("subtitle response exceeded $MAX_SUBTITLE_RESPONSE_BYTES bytes")
        }
        if (response.body.isBlank()) error("empty subtitle response")
        return response.body
    }

    private suspend fun awaitNearCompleteLiveReferences(
        sourceKey: String,
        preferredLanguage: String?,
        target: List<SubtitleSyncCue>,
        waitMs: Long = LIVE_REFERENCE_WAIT_MS,
        allowSparseLiveReference: Boolean = false,
    ): ReferenceSelection {
        val targetSpan = referenceSpanMs(target).coerceAtLeast(1L)
        val started = SystemClock.elapsedRealtime()
        var lastSignature = ""
        var forcedFallback: List<ReferenceProfile> = emptyList()

        while (true) {
            currentCoroutineContext().ensureActive()
            val prepared = EmbeddedSubtitleCueStore
                .candidateTracks(sourceKey, preferredLanguage)
                .map { track -> track.copy(cues = deduplicateReferenceCues(track.cues)) }

            val profiles = prepared.map(::buildReferenceProfile)
            val eligibleProfiles = profiles.filter { profile ->
                profile.fullDialogueCandidate &&
                    profile.cueCount >= MIN_LIVE_REFERENCE_CUES &&
                    profile.spanMs.toDouble() / targetSpan.toDouble() >= MIN_LIVE_REFERENCE_SPAN_RATIO
            }
            val ready = orderReferenceProfiles(
                eligibleProfiles.filter { profile -> profile.fullDialogue },
            )
            val sparseReady = if (allowSparseLiveReference) {
                orderReferenceProfiles(
                    prepared.asSequence()
                        .filter { track ->
                            track.cues.size >= MIN_SPARSE_LIVE_REFERENCE_CUES &&
                                referenceSpanMs(track.cues) >= MIN_SPARSE_LIVE_REFERENCE_SPAN_MS
                        }
                        .map { track -> buildReferenceProfile(track.copy(sampled = true)) }
                        .filter { profile -> profile.fullDialogue }
                        .toList(),
                )
            } else {
                emptyList()
            }
            forcedFallback = orderReferenceProfiles(
                eligibleProfiles.filter { profile -> isForcedReferenceTrack(profile.track) },
            )

            val signature = prepared.joinToString("|") { track ->
                "${track.key}:g${track.generation}:${track.cues.size}:" +
                    "${track.cues.lastOrNull()?.startTimeMs ?: -1L}"
            }
            if (signature != lastSignature) {
                lastSignature = signature
            }

            if (ready.isNotEmpty()) {
                return ReferenceSelection(
                    primary = ready.map { it.track.copy(cues = it.track.cues.toList()) },
                    forcedFallback = forcedFallback.map {
                        it.track.copy(cues = it.track.cues.toList())
                    },
                )
            }

            if (sparseReady.isNotEmpty()) {
                return ReferenceSelection(
                    primary = sparseReady.map { it.track.copy(cues = it.track.cues.toList()) },
                )
            }

            val elapsedMs = SystemClock.elapsedRealtime() - started
            if (elapsedMs >= waitMs) break
            delay(minOf(LIVE_REFERENCE_POLL_MS, waitMs - elapsedMs))
        }

        if (forcedFallback.isNotEmpty()) {
            return ReferenceSelection(
                primary = forcedFallback.map { it.track.copy(cues = it.track.cues.toList()) },
            )
        }

        return ReferenceSelection()
    }

    private fun groupEquivalentReferenceTimelines(
        referenceTracks: List<ReferenceTrack>,
    ): List<ReferenceTimingGroup> {
        val buckets = linkedMapOf<ReferenceTimingFingerprint, MutableList<ReferenceTimingGroup>>()
        referenceTracks.forEach { track ->
            val fingerprint = referenceTimingFingerprint(track.cues)
            val bucket = buckets.getOrPut(fingerprint) { mutableListOf() }
            val exactGroup = bucket.firstOrNull { group ->
                sameReferenceTiming(group.members.first().cues, track.cues)
            }
            if (exactGroup != null) exactGroup.members += track
            else bucket += ReferenceTimingGroup(mutableListOf(track))
        }
        return buckets.values.flatten()
    }

    private fun referenceTimingFingerprint(cues: List<SubtitleSyncCue>): ReferenceTimingFingerprint {
        var timingHash = 1_125_899_906_842_597L
        for (cue in cues) {
            timingHash = timingHash * 31L + cue.startTimeMs
            timingHash = timingHash * 31L + cue.endTimeMs
        }
        return ReferenceTimingFingerprint(
            cueCount = cues.size,
            firstStartMs = cues.firstOrNull()?.startTimeMs ?: -1L,
            lastStartMs = cues.lastOrNull()?.startTimeMs ?: -1L,
            timingHash = timingHash,
        )
    }

    private fun sameReferenceTiming(
        left: List<SubtitleSyncCue>,
        right: List<SubtitleSyncCue>,
    ): Boolean {
        if (left.size != right.size) return false
        return left.indices.all { index ->
            left[index].startTimeMs == right[index].startTimeMs &&
                left[index].endTimeMs == right[index].endTimeMs
        }
    }

    private fun buildReferenceProfile(track: ReferenceTrack): ReferenceProfile {
        val cueCount = track.cues.size
        val spanMs = referenceSpanMs(track.cues)
        val density = if (spanMs <= 0L) 0.0 else cueCount * 60_000.0 / spanMs

        var textCueCount = 0
        var dialogueCueCount = 0
        if (track.generation >= 0L) {
            for (cue in track.cues) {
                if (cue.text.isBlank()) continue
                textCueCount++
                if (isDialogueLikeReferenceCue(cue)) dialogueCueCount++
            }
        }
        val dialogueRatio =
            if (textCueCount == 0) 0.5 else dialogueCueCount.toDouble() / textCueCount

        val fullDialogueCandidate =
            cueCount >= MIN_FULL_DIALOGUE_CUES &&
                spanMs >= MIN_FULL_DIALOGUE_CLASSIFICATION_SPAN_MS &&
                !isCommentaryReferenceTrack(track) &&
                !isDescriptiveReferenceTrack(track) &&
                (track.sampled || density >= MIN_FULL_DIALOGUE_DENSITY_PER_MINUTE) &&
                (textCueCount < 4 || dialogueRatio >= MIN_FULL_DIALOGUE_TEXT_RATIO)
        val fullDialogue =
            fullDialogueCandidate && !isForcedReferenceTrack(track)

        val dialogueRoleBonus =
            if ((track.roleFlags and C.ROLE_FLAG_TRANSCRIBES_DIALOG) != 0) 8.0 else 0.0
        val subtitleRoleBonus =
            if ((track.roleFlags and C.ROLE_FLAG_SUBTITLE) != 0) 4.0 else 0.0
        val sdhPenalty = if (isSdhReferenceTrack(track)) 5.0 else 0.0
        val rankingScore =
            cueCount * 2.0 +
                density.coerceAtMost(20.0) * 1.5 +
                dialogueRatio * 20.0 +
                dialogueRoleBonus +
                subtitleRoleBonus -
                sdhPenalty

        return ReferenceProfile(
            track = track,
            cueCount = cueCount,
            spanMs = spanMs,
            densityPerMinute = density,
            fullDialogueCandidate = fullDialogueCandidate,
            fullDialogue = fullDialogue,
            rankingScore = rankingScore,
        )
    }

    private fun orderReferenceProfiles(profiles: List<ReferenceProfile>): List<ReferenceProfile> =
        profiles.sortedWith(
            compareByDescending<ReferenceProfile> { it.fullDialogue }
                .thenByDescending { it.rankingScore }
                .thenBy { isSdhReferenceTrack(it.track) }
                .thenBy { it.track.key },
        )

    private fun isForcedReferenceTrack(track: ReferenceTrack): Boolean {
        if ((track.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) return true
        val label = track.label.orEmpty().lowercase()
        return label.contains("forced") ||
            label.contains("foreign only") ||
            label.contains("signs only") ||
            label.contains("songs only")
    }

    private fun isCommentaryReferenceTrack(track: ReferenceTrack): Boolean {
        if ((track.roleFlags and C.ROLE_FLAG_COMMENTARY) != 0) return true
        return track.label.orEmpty().contains("commentary", ignoreCase = true)
    }

    private fun isDescriptiveReferenceTrack(track: ReferenceTrack): Boolean {
        if ((track.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) return true
        val label = track.label.orEmpty().lowercase()
        return label.contains("audio description") || label.contains("descriptive subtitle")
    }

    internal fun isSdhReferenceTrack(track: ReferenceTrack): Boolean {
        if ((track.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0) return true
        val label = track.label.orEmpty().lowercase()
        return label.contains("sdh") ||
            label.contains("shd") ||
            label.contains("hoh") ||
            label.contains("hearing impaired") ||
            label.contains("hearing-impaired") ||
            label.contains("closed caption")
    }

    private fun isDialogueLikeReferenceCue(cue: SubtitleSyncCue): Boolean {
        val text = normalizedCueText(cue.text)
        if (text.isBlank()) return false
        val parentheticalOnly =
            (text.startsWith("(") && text.endsWith(")")) ||
                (text.startsWith("[") && text.endsWith("]"))
        return !parentheticalOnly && text.any { it.isLetterOrDigit() }
    }

    private fun deduplicateReferenceCues(cues: List<SubtitleSyncCue>): List<SubtitleSyncCue> {
        if (cues.size < 2) return cues
        val sorted = cues.sortedBy { it.startTimeMs }
        val out = ArrayList<SubtitleSyncCue>(sorted.size)
        for (cue in sorted) {
            val previous = out.lastOrNull()
            if (
                previous != null &&
                abs(previous.startTimeMs - cue.startTimeMs) <= 125L &&
                (
                    previous.text.isBlank() ||
                        cue.text.isBlank() ||
                        normalizedCueText(previous.text) == normalizedCueText(cue.text)
                    )
            ) {
                if (previous.text.isBlank() && cue.text.isNotBlank()) out[out.lastIndex] = cue
            } else {
                out += cue
            }
        }
        return out
    }

    private fun normalizedCueText(text: String): String =
        text.replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()

    private fun referenceSpanMs(cues: List<SubtitleSyncCue>): Long =
        if (cues.size < 2) 0L else cues.last().endTimeMs - cues.first().startTimeMs

    private fun directTimelineQualityScore(match: TimelineRetimeMatch): Double {
        val result = match.timeline
        val costScore = 1.0 / (1.0 + result.averageGroupCost.coerceAtLeast(0.0))
        val skipPenalty = result.longestTargetSkipRun.coerceAtMost(20) * 0.004
        val sdhPenalty = if (isSdhReferenceTrack(match.track)) 0.010 else 0.0
        return result.targetCoverage * 0.42 +
            result.referenceCoverage * 0.15 +
            costScore * 0.23 +
            result.simpleGroupRatio * 0.20 -
            skipPenalty -
            sdhPenalty
    }

    private fun isExceptionalMatch(match: TimelineRetimeMatch): Boolean {
        val result = match.timeline
        return result.confident &&
            directTimelineQualityScore(match) >= EXCEPTIONAL_MATCH_QUALITY &&
            result.targetCoverage >= EXCEPTIONAL_MATCH_TARGET_COVERAGE &&
            result.referenceCoverage >= EXCEPTIONAL_MATCH_REFERENCE_COVERAGE &&
            result.simpleGroupRatio >= EXCEPTIONAL_MATCH_SIMPLE_RATIO
    }

    private fun isStrongCheckpointMatch(
        match: TimelineRetimeMatch,
        aggressiveMode: Boolean,
    ): Boolean {
        val result = match.timeline
        val qualityThreshold =
            if (aggressiveMode) STRONG_CHECKPOINT_QUALITY else PASSIVE_CHECKPOINT_QUALITY
        val targetCoverageThreshold =
            if (aggressiveMode) {
                STRONG_CHECKPOINT_TARGET_COVERAGE
            } else {
                PASSIVE_CHECKPOINT_TARGET_COVERAGE
            }
        val referenceCoverageThreshold =
            if (aggressiveMode) {
                STRONG_CHECKPOINT_REFERENCE_COVERAGE
            } else {
                PASSIVE_CHECKPOINT_REFERENCE_COVERAGE
            }

        return result.confident &&
            directTimelineQualityScore(match) >= qualityThreshold &&
            result.targetCoverage >= targetCoverageThreshold &&
            result.referenceCoverage >= referenceCoverageThreshold
    }

    private fun isAsymmetricReferenceCheckpointMatch(
        match: TimelineRetimeMatch,
        targetCueCount: Int,
    ): Boolean {
        if (targetCueCount <= 0) return false
        val result = match.timeline
        val referenceRatio = match.track.cues.size.toDouble() / targetCueCount.toDouble()

        return result.confident &&
            referenceRatio >= ASYMMETRIC_CHECKPOINT_MIN_REFERENCE_RATIO &&
            directTimelineQualityScore(match) >= ASYMMETRIC_CHECKPOINT_QUALITY &&
            result.targetCoverage >= ASYMMETRIC_CHECKPOINT_TARGET_COVERAGE &&
            result.referenceCoverage >= ASYMMETRIC_CHECKPOINT_REFERENCE_COVERAGE &&
            result.averageGroupCost <= ASYMMETRIC_CHECKPOINT_MAX_GROUP_COST &&
            result.simpleGroupRatio >= ASYMMETRIC_CHECKPOINT_SIMPLE_RATIO &&
            result.longestTargetSkipRun <= ASYMMETRIC_CHECKPOINT_MAX_TARGET_SKIP_RUN
    }

    private fun buildTimelineRetimeResult(
        track: ReferenceTrack,
        target: List<SubtitleSyncCue>,
        preparedReferenceActivity: AutoSyncTimelineRetimer.PreparedActivity?,
        preparedTargetActivity: AutoSyncTimelineRetimer.PreparedActivity?,
        delayOnlyHint: AutoSyncDelayOnlyAlignment? = null,
        delayOnlyEvidence: AutoSyncTimelineRetimer.DelayOnlySearchEvidence? = null,
        allowPrecomputedDelayFastPath: Boolean = true,
        cancellationCheck: (() -> Unit)? = null,
    ): AutoSyncTimelineRetimeResult? {
        if (track.sampled) {
            val alignment = AutoSyncSampledReferenceAligner.findDelay(
                reference = track.cues,
                target = target,
            ) ?: return null
            return AutoSyncTimelineRetimer.buildDelayOnlyTimeline(target, alignment).copy(
                alignmentSource = "sampled-delay",
                referenceCoverage = alignment.score,
            )
        }

        val relaxDelayMargin = AutoSyncTimelineRetimer.shouldRelaxDelayOnlyMargin(
            sdhReference = isSdhReferenceTrack(track),
            referenceSize = track.cues.size,
            targetSize = target.size,
        )

        return AutoSyncTimelineRetimer.retime(
            reference = track.cues,
            target = target,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
            allowAmbiguousDelayOnlyMargin = relaxDelayMargin,
            referenceEstimatedEndStartsMs = track.estimatedEndStartsMs,
            preparedReferenceActivity = preparedReferenceActivity,
            preparedTargetActivity = preparedTargetActivity,
            precomputedDelayOnly = delayOnlyHint,
            precomputedDelayOnlyEvidence = delayOnlyEvidence,
            allowPrecomputedDelayFastPath = allowPrecomputedDelayFastPath,
            cancellationCheck = cancellationCheck,
        )
    }

    private fun stableHeaderIdentity(
        headers: Map<String, String>,
    ): List<Pair<String, String>> =
        headers.entries
            .sortedBy { it.key.lowercase() }
            .map { (key, value) -> key.lowercase() to value }

    private fun fmt(value: Double): String = "%.4f".format(value)

    private data class ParsedSubtitleCacheKey(
        val url: String,
        val headers: List<Pair<String, String>>,
    )
    private data class LoadedSubtitle(
        val cues: List<SubtitleSyncCue>,
        val rawBody: String?,
    )
    private data class ReferenceTimingFingerprint(
        val cueCount: Int,
        val firstStartMs: Long,
        val lastStartMs: Long,
        val timingHash: Long,
    )
    private data class ReferenceTimingGroup(val members: MutableList<ReferenceTrack>)
    private data class ReferenceSelection(
        val primary: List<ReferenceTrack> = emptyList(),
        val forcedFallback: List<ReferenceTrack> = emptyList(),
    )
    private data class ReferenceProfile(
        val track: ReferenceTrack,
        val cueCount: Int,
        val spanMs: Long,
        val densityPerMinute: Double,
        val fullDialogueCandidate: Boolean,
        val fullDialogue: Boolean,
        val rankingScore: Double,
    )
    private data class RankedReferenceCandidate(
        val track: ReferenceTrack,
        val cheapAffinity: Double,
        val suitability: Double,
    )
    private data class PairEvaluation(
        val match: TimelineRetimeMatch?,
    )
    private data class CachedParsedSubtitle(
        val cues: List<SubtitleSyncCue>,
        val rawBody: String,
    )
    private data class LoadedAlternative(
        val index: Int,
        val candidate: AutoSyncSubtitleCandidate,
        val loaded: LoadedSubtitle,
    )
    private class CandidateTimingFamilyState(
        val representative: LoadedAlternative,
        val members: MutableList<LoadedAlternative>,
        val targetActivity: AutoSyncTimelineRetimer.PreparedActivity?,
        val preflight: AutoSyncDelayPreflight.Match?,
        val preflightEvidenceByReferenceKey: Map<String, AutoSyncDelayPreflight.Evidence>,
        val rankedReferences: List<RankedReferenceCandidate>,
        val startedReferenceKeys: MutableSet<String> = hashSetOf(),
        var completedUsableAttempts: Int = 0,
        var best: TimelineRetimeMatch? = null,
        var bestSchedulingScore: Double = Double.NEGATIVE_INFINITY,
        val noFit: AutoSyncNoFitTracker = AutoSyncNoFitTracker(),
        var abandoned: Boolean = false,
    )
    private data class PairHypothesis(
        val family: CandidateTimingFamilyState,
        val rankedReference: RankedReferenceCandidate,
        val preflightHint: AutoSyncDelayPreflight.Match?,
        val preflightEvidence: AutoSyncTimelineRetimer.DelayOnlySearchEvidence?,
        val preflightChampion: Boolean,
        val schedulingScore: Double,
    )
    private data class CompletedPairEvaluation(
        val hypothesis: PairHypothesis,
        val evaluation: PairEvaluation,
    )
    private sealed class SchedulerEvent {
        data class CandidateLoaded(
            val url: String,
            val loaded: LoadedSubtitle?,
        ) : SchedulerEvent()

        data class PairEvaluated(
            val jobId: Int,
            val completed: CompletedPairEvaluation,
        ) : SchedulerEvent()
    }

    private data class TimelineRetimeMatch(
        val track: ReferenceTrack,
        val timeline: AutoSyncTimelineRetimeResult,
    )
}

internal enum class AutoSyncAnalysisOutcome {
    SUBTITLE_UNAVAILABLE,
    NO_SUBTITLE_TRACKS,
    NO_USABLE_REFERENCE,
}

internal data class AutoSyncResolvedTimeline(
    val subtitleUrl: String,
    val subtitleHeaders: Map<String, String>,
    val subtitleBody: String?,
    val timeline: AutoSyncTimelineRetimeResult,
)

internal data class ReferenceTrack(
    val key: String,
    val language: String?,
    val cues: List<SubtitleSyncCue>,
    val label: String? = null,
    val selectionFlags: Int = 0,
    val roleFlags: Int = 0,
    val generation: Long = 0L,
    val estimatedEndStartsMs: Set<Long> = emptySet(),
    val sampled: Boolean = false,
)

/** Thread-safe accumulation of the embedded text timing already passing through Media3. */
internal object EmbeddedSubtitleCueStore {
    private const val SEEK_DEDUP_WINDOW_MS = 1_500L
    private const val SEEK_TARGET_TOLERANCE_MS = 1_000L
    private const val MAX_RETAINED_GENERATIONS = 6

    private data class Track(
        var language: String?,
        var label: String?,
        var selectionFlags: Int,
        var roleFlags: Int,
        val cues: LinkedHashMap<String, SubtitleSyncCue> = linkedMapOf(),
    )

    private data class RetainedGeneration(
        val generation: Long,
        val tracks: MutableMap<String, Track>,
    )

    private val lock = Any()
    private val sources = mutableMapOf<String, MutableMap<String, Track>>()
    private val retainedGenerations = mutableMapOf<String, MutableList<RetainedGeneration>>()
    private val generations = mutableMapOf<String, Long>()
    private val lastSeekTargetMs = mutableMapOf<String, Long?>()
    private val lastSeekWallMs = mutableMapOf<String, Long>()

    fun reset(sourceKey: String) {
        if (sourceKey.isBlank()) return

        var generation = 0L
        synchronized(lock) {
            generation = (generations[sourceKey] ?: 0L) + 1L
            sources[sourceKey] = linkedMapOf()
            retainedGenerations.remove(sourceKey)
            generations[sourceKey] = generation
            lastSeekTargetMs.remove(sourceKey)
            lastSeekWallMs.remove(sourceKey)
        }

    }

    fun beginNewGeneration(sourceKey: String, targetTimeMs: Long?) {
        if (sourceKey.isBlank()) return

        val now = SystemClock.elapsedRealtime()
        var generation: Long? = null
        synchronized(lock) {
            val previousTarget = lastSeekTargetMs[sourceKey]
            val previousWall = lastSeekWallMs[sourceKey]
            val duplicateSeek =
                previousWall != null &&
                    now - previousWall <= SEEK_DEDUP_WINDOW_MS &&
                    when {
                        previousTarget == null && targetTimeMs == null -> true
                        previousTarget != null && targetTimeMs != null ->
                            abs(previousTarget - targetTimeMs) <= SEEK_TARGET_TOLERANCE_MS
                        else -> false
                    }

            if (!duplicateSeek) {
                val currentGeneration = generations[sourceKey] ?: 0L
                val currentTracks = sources[sourceKey]
                if (currentTracks != null && currentTracks.isNotEmpty()) {
                    val retained = retainedGenerations.getOrPut(sourceKey) { mutableListOf() }
                    retained += RetainedGeneration(currentGeneration, currentTracks)
                    while (retained.size > MAX_RETAINED_GENERATIONS) {
                        retained.removeAt(0)
                    }
                }

                generation = currentGeneration + 1L
                generations[sourceKey] = generation!!
                sources[sourceKey] = linkedMapOf()
            }
            lastSeekTargetMs[sourceKey] = targetTimeMs
            lastSeekWallMs[sourceKey] = now
        }
    }

    fun record(
        sourceKey: String,
        trackKey: String,
        language: String?,
        label: String?,
        selectionFlags: Int,
        roleFlags: Int,
        cue: SubtitleSyncCue,
    ) {
        if (sourceKey.isBlank() || trackKey.isBlank()) return

        synchronized(lock) {
            val track = sources
                .getOrPut(sourceKey) { linkedMapOf() }
                .getOrPut(trackKey) {
                    Track(
                        language = language,
                        label = label,
                        selectionFlags = selectionFlags,
                        roleFlags = roleFlags,
                    )
                }

            track.language = track.language ?: language
            track.label = track.label ?: label
            track.selectionFlags = track.selectionFlags or selectionFlags
            track.roleFlags = track.roleFlags or roleFlags

            val cueKey = "${cue.startTimeMs}:${cue.endTimeMs}:${cue.text}"
            track.cues[cueKey] = cue
        }

    }

    fun candidateTracks(
        sourceKey: String,
        preferredLanguage: String?,
    ): List<ReferenceTrack> = synchronized(lock) {
        val preferred = preferredLanguage?.trim()?.lowercase().orEmpty()
        val currentGeneration = generations[sourceKey] ?: 0L

        val snapshots = buildList {
            retainedGenerations[sourceKey].orEmpty().forEach { retained ->
                add(retained.generation to retained.tracks)
            }
            sources[sourceKey]?.takeIf { it.isNotEmpty() }?.let { currentTracks ->
                add(currentGeneration to currentTracks)
            }
        }

        snapshots
            .flatMap { (generation, tracks) ->
                tracks.map { (key, track) ->
                    ReferenceTrack(
                        key = key,
                        language = track.language,
                        cues = track.cues.values.sortedBy { it.startTimeMs },
                        label = track.label,
                        selectionFlags = track.selectionFlags,
                        roleFlags = track.roleFlags,
                        generation = generation,
                    )
                }
            }
            .filter { it.cues.size >= 3 }
            .sortedWith(
                compareByDescending<ReferenceTrack> {
                    languageRank(it.language, preferred)
                }.thenByDescending {
                    it.cues.size
                }.thenByDescending {
                    it.generation
                },
            )
    }

    private fun languageRank(language: String?, preferred: String): Int {
        val normalized = language?.trim()?.lowercase().orEmpty()
        val preferredBase = preferred.substringBefore('-')
        val normalizedBase = normalized.substringBefore('-')

        return when {
            preferred.isNotBlank() && normalized == preferred -> 4
            preferredBase.isNotBlank() && normalizedBase == preferredBase -> 3
            normalized == "en" || normalized.startsWith("en-") -> 2
            normalized.isNotBlank() -> 1
            else -> 0
        }
    }
}
