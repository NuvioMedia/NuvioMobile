package com.nuvio.app.features.usenet

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamSubtitle
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes the APK-installed PIE engine from nativeLibraryDir. All media remains in the child.
 * Provider secrets only cross an authenticated loopback control request.
 */
internal class UsenetSidecar(
    private val context: Context,
    private val settings: () -> UsenetConfiguration,
    private val unplayable: UnplayableReleases,
) {
    private val mutex = Mutex()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()
    private var process: Process? = null
    private var processProfile: String? = null
    private var endpoint: String? = null
    private var token: String? = null
    private var sessionId: String? = null
    @Volatile private var playbackUrl: String? = null
    private var idleStop: Job? = null
    private val retiredSessions = mutableSetOf<String>() // guarded by mutex
    private var retirementJob: Job? = null
    private val preparationGuard = Any()
    private var preparationJob: Job? = null
    private var preparationOwner: Any? = null
    private var preparationKey: PreparationKey? = null
    private var preparationGeneration: Any? = null
    // A prefetched session waiting for its Play. Guarded by mutex; the session itself holds the
    // parsed NZB and cached articles, so nothing is kept twice.
    private var preparedStream: StreamItem? = null
    private var preparedKey: PreparationKey? = null
    private var preparedGeneration: Any? = null
    private var preparedExpiry: Job? = null

    /** What makes a prepared session the one a later Play asks for. */
    private data class PreparationKey(
        val nzb: String?, val servers: List<String>, val headers: Map<String, String>,
        val fileIdx: Int?, val selector: String?, val season: Int?, val episode: Int?,
        val configuration: UsenetConfiguration, val profileId: Int?,
    )

    private fun preparationKey(stream: StreamItem, season: Int?, episode: Int?, profileId: Int?): PreparationKey {
        val configuration = settings()
        return PreparationKey(stream.nzbUrl, stream.nzbServers, nzbHeaders(stream, configuration), stream.fileIdx,
            stream.fileMustInclude, season, episode, configuration, profileId)
    }

    /** The release each recent engine URL plays, so playback outcomes reach its entry. */
    private val playing = object : LinkedHashMap<String, StreamItem>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamItem>) = size > 8
    }

    /** Open timings by engine URL, logged once the first frame shows. */
    private val traces = object : LinkedHashMap<String, Trace>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Trace>) = size > 4
    }

    private class Trace {
        val start = SystemClock.elapsedRealtime()
        val marks = JSONObject()
        var engine: JSONObject = JSONObject()
        @Synchronized fun mark(name: String) {
            if (!marks.has(name)) marks.put(name, SystemClock.elapsedRealtime() - start)
        }
    }

    private val startupReader = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "usenet-sidecar-io").apply { isDaemon = true }
    }

    /** Shares the session mutex so launch and Play cannot start duplicate children. */
    fun prewarm(): Job = cleanup.launch {
        mutex.withLock {
            if (sessionId != null) return@withLock
            if (!appForeground || !settings().prewarmOnLaunch) {
                stopLocked()
                return@withLock
            }
            try {
                start()
                // Visibility can change while the blocking bootstrap runs.
                if (!appForeground) stopLocked()
            } catch (_: Exception) {
                // Optional startup must not crash browsing; Play retries and reports the error.
                stopLocked()
            }
        }
    }

    /** Results have made Usenet relevant. Warm only Go and its trust roots, for two idle minutes. */
    fun prewarmForSources(): Job = cleanup.launch {
        mutex.withLock {
            if (!appForeground || sessionId != null) return@withLock
            try {
                start()
                if (!appForeground) { stopLocked(); return@withLock }
                if (!settings().prewarmOnLaunch) {
                    idleStop = cleanup.launch {
                        delay(120_000)
                        mutex.withLock { if (sessionId == null) stopLocked() }
                    }
                }
            } catch (_: Exception) { stopLocked() }
        }
    }

    /**
     * Opens [stream] now and keeps it ready for two minutes, so its Play starts from an open
     * session. Only one candidate may own a prepared session; [owner] is the screen asking.
     */
    fun prefetch(owner: Any, stream: StreamItem, season: Int?, episode: Int?, profileId: Int?): Job {
        val key = preparationKey(stream, season, episode, profileId)
        if (!key.configuration.prefetchResults) return Job().apply { complete() }
        return synchronized(preparationGuard) {
            if (preparationOwner === owner && preparationKey == key) return@synchronized preparationJob!!
            preparationJob?.cancel()
            preparationOwner = owner
            preparationKey = key
            val generation = Any()
            preparationGeneration = generation
            cleanup.launch {
                mutex.withLock {
                    // A session that plays is never replaced by a guess.
                    if (!appForeground || !settings().prefetchResults || (sessionId != null && preparedStream == null)) {
                        return@withLock
                    }
                    if (preparedStream != null) releaseSessionLocked()
                    try {
                        val resolved = openLocked(stream, season, episode, profileId)
                        preparedStream = resolved
                        preparedKey = key
                        preparedGeneration = generation
                        preparedExpiry = cleanup.launch {
                            delay(PREPARED_LIFETIME_MS)
                            mutex.withLock {
                                if (preparedGeneration === generation) {
                                    preparedExpiry = null
                                    releaseSessionLocked()
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Optional preparation is silent; Play retries it and reports the error.
                    }
                }
            }.also { preparationJob = it }
        }
    }

    /** Drops the prepared session of [owner], or of anyone when null; a session that plays stays. */
    fun cancelPrefetch(owner: Any? = null): Job {
        val generation = synchronized(preparationGuard) {
            if (owner != null && preparationOwner !== owner) return Job().apply { complete() }
            preparationJob?.cancel()
            preparationJob = null; preparationOwner = null; preparationKey = null
            preparationGeneration.also { preparationGeneration = null }
        }
        return cleanup.launch {
            mutex.withLock { if (generation != null && preparedGeneration === generation) releaseSessionLocked() }
        }
    }

    suspend fun resolve(stream: StreamItem, season: Int?, episode: Int?, profileId: Int?): StreamItem {
        var resolved: StreamItem? = null
        try {
            return withContext(Dispatchers.IO) {
                resolveLocked(stream, season, episode, profileId).also { resolved = it }
            }
        } catch (cancelled: CancellationException) {
            // withContext can discard a successful result when its caller is cancelled during
            // dispatcher handoff. No screen can own it then.
            release(resolved?.url)
            throw cancelled
        }
    }

    private suspend fun resolveLocked(stream: StreamItem, season: Int?, episode: Int?, profileId: Int?): StreamItem {
        val key = preparationKey(stream, season, episode, profileId)
        val pending = synchronized(preparationGuard) {
            if (preparationKey != key) preparationJob?.cancel()
            preparationJob
        }
        // Joined outside the session mutex, so Play shares an open that is already running.
        pending?.join()
        return mutex.withLock {
            synchronized(preparationGuard) {
                if (preparationJob === pending) {
                    preparationJob = null; preparationOwner = null; preparationKey = null; preparationGeneration = null
                }
            }
            val cached = preparedStream
            if (cached != null && preparedKey == key && process?.let(::isRunning) == true) {
                clearPreparedLocked()
                cached.url?.let { url -> synchronized(traces) { traces[url] }?.mark("prepared_session_reused") }
                return@withLock cached
            }
            if (preparedStream != null) releaseSessionLocked()
            openLocked(stream, season, episode, profileId)
        }
    }

    /**
     * The engine downloads the NZB with these. An addon's User-Agent takes precedence over the
     * download setting; searches have their own independent setting.
     */
    private fun nzbHeaders(stream: StreamItem, configuration: UsenetConfiguration): Map<String, String> {
        val headers = stream.behaviorHints.proxyHeaders?.request.orEmpty()
        return if (headers.keys.any { it.equals(USER_AGENT, ignoreCase = true) }) headers
        else headers + (USER_AGENT to configuration.downloadAgent)
    }

    private suspend fun openLocked(stream: StreamItem, season: Int?, episode: Int?, profileId: Int?): StreamItem {
        val configuration = settings()
        val trace = Trace()
        var previousId = sessionId
        var openedId: String? = null
        return try {
            trace.mark("resolve_lock_acquired")
            start(trace)
            previousId = sessionId
            trace.mark("engine_ready")
            val request = JSONObject().apply {
                put("keepSessionId", previousId ?: "")
                // The NZB URL carries the indexer key, so profiles of one account share its cache
                // entry and a profile switch does not download it again.
                put("cacheScope", "unscoped")
                put("legacyCacheScope", profileId?.toString() ?: "unscoped")
                put("nzbUrl", stream.nzbUrl)
                put("servers", JSONArray(stream.nzbServers))
                stream.fileIdx?.let { put("fileIdx", it) }
                stream.fileMustInclude?.let { put("fileMustInclude", it) }
                season?.let { put("season", it) }
                episode?.let { put("episode", it) }
                put("headers", JSONObject(nzbHeaders(stream, configuration)))
                put("config", JSONObject().apply {
                    put("profile", configuration.profile)
                    put("readAhead", configuration.readAhead)
                    put("maxConnections", configuration.maxConnections)
                    put("fastMkvStartup", configuration.fastMkvStartup)
                    put("fastNzbFetch", configuration.fastNzbFetch)
                    put("cacheNzb", configuration.cacheNzb)
                    put("allowPrivateNetwork", configuration.allowPrivateNetwork)
                    put("holeFilling", configuration.holeFilling)
                    put("maxMissingArticles", configuration.maxMissingArticles)
                    put("maxConsecutiveMissing", configuration.maxConsecutiveMissing)
                })
            }
            trace.mark("session_request")
            val result = JSONObject(control("/sessions", request))
            trace.mark("session_response")
            val newId = result.getString("id")
            val newPath = result.getString("path")
            require(newId.matches(Regex("[0-9a-f]{48}")) && newPath.startsWith("/stream/$newId/"))
            openedId = newId
            // The engine reconciled abandoned sessions before this open.
            retiredSessions.clear()
            val engineUrl = requireNotNull(endpoint)
            val url = UsenetEngineUrls.stream(engineUrl, newId, newPath)
            val nativeSubtitles = result.optJSONArray("subtitles")?.let { items ->
                (0 until items.length()).map { index ->
                    val item = items.getJSONObject(index)
                    val subtitleId = item.getString("id")
                    require(subtitleId.toIntOrNull() != null)
                    StreamSubtitle(
                        url = "$engineUrl/subtitle/$newId/$subtitleId/${Uri.encode(item.getString("filename"))}",
                        language = item.optString("lang", "und"),
                        name = item.optString("filename").takeIf { it.isNotBlank() },
                    )
                }
            }.orEmpty()
            trace.mark("resolved")
            // The NZB stays as the stream's identity; with a URL it is no longer prepared again.
            // The servers carry credentials and are not needed any more.
            val resolved = stream.copy(
                url = url,
                nzbServers = emptyList(),
                externalUrl = null,
                infoHash = null,
                externalSubtitles = stream.externalSubtitles + nativeSubtitles,
                behaviorHints = stream.behaviorHints.copy(
                    proxyHeaders = null,
                    filename = result.getString("filename"),
                    videoSize = result.getLong("size"),
                ),
            )
            // Validate the entire response before replacing a working session.
            coroutineContext.ensureActive()
            sessionId = newId
            playbackUrl = url
            synchronized(playing) { playing[url] = stream }
            trace.engine = result.optJSONObject("startup") ?: JSONObject()
            synchronized(traces) { traces[url] = trace }
            previousId?.let(::retireSessionLocked)
            resolved
        } catch (e: Exception) {
            (e as? UsenetPreparationException)?.failure?.let { unplayable.record(stream, it) }
            if (e !is CancellationException) runCatching {
                Log.i("UsenetPrepare", "open failed source=\"${stream.name}\" release=\"${stream.title?.lineSequence()?.firstOrNull()}\" " +
                    "kind=${(e as? UsenetPreparationException)?.failure ?: "-"} error=${e.javaClass.simpleName}: " +
                    redacted(e.message.orEmpty()))
            }
            if (previousId != null && sessionId == previousId && process?.let(::isRunning) == true) {
                openedId?.let(::retireSessionLocked)
            } else {
                stopLocked()
            }
            throw e
        }
    }

    /**
     * Retry bounded cleanup without delaying playback. A later open also reconciles these IDs,
     * including lost open responses.
     */
    private fun retireSessionLocked(id: String) {
        retiredSessions += id
        if (retirementJob?.isActive == true) return
        retirementJob = cleanup.launch {
            repeat(3) { attempt ->
                if (attempt > 0) delay(1_000L * attempt)
                mutex.withLock {
                    for (retired in retiredSessions.toList()) {
                        val answer = runCatching {
                            withTimeoutOrNull(2_000L) { control("/sessions/$retired", null, delete = true) }
                        }.getOrNull()
                        if (answer != null) retiredSessions.remove(retired)
                    }
                }
            }
        }
    }

    /** The release behind [url] showed its first frame: remember it, and log how the open went. */
    fun firstFrame(url: String) {
        synchronized(playing) { playing[url] }?.let(unplayable::recordPlayed)
        val trace = synchronized(traces) { traces.remove(url) } ?: return
        trace.mark("first_frame")
        cleanup.launch {
            val engine = mutex.withLock {
                val id = sessionId?.takeIf { url == playbackUrl } ?: return@withLock null
                runCatching {
                    val request = Request.Builder().url("$endpoint/sessions/$id/diagnostics")
                        .header("Authorization", "Bearer $token").get().build()
                    // Diagnostics are optional and this holds the session lock: never wait long.
                    http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                        if (response.isSuccessful) JSONObject(response.body!!.string()) else null
                    }
                }.getOrNull()
            } ?: trace.engine
            runCatching {
                Log.i("UsenetStartup", JSONObject().put("marksMs", trace.marks)
                    .put("engineMarksMs", engine.optJSONObject("marksMs")).toString())
            }
        }
    }

    /** Playback of [url] failed with the engine's [failure] kind; repeatable kinds are remembered. */
    fun playbackFailed(url: String, failure: String?) {
        synchronized(playing) { playing[url] }?.let { unplayable.record(it, failure) }
    }

    /**
     * Only release the session owned by the exiting player. An older screen must not tear down a
     * newer playback session during navigation.
     */
    fun release(url: String?) {
        cleanup.launch { mutex.withLock { if (url != null && url == playbackUrl) releaseSessionLocked() } }
    }

    private suspend fun releaseSessionLocked() {
        clearPreparedLocked()
        val id = sessionId
        sessionId = null; playbackUrl = null
        try {
            if (id != null) {
                val answer = withTimeoutOrNull(2_000L) { control("/sessions/$id", null, delete = true) }
                if (answer == null) { stopLocked(); return }
            }
        } catch (_: Exception) { stopLocked(); return }
        if (!appForeground) { stopLocked(); return }
        idleStop?.cancel()
        if (settings().prewarmOnLaunch) { idleStop = null; return }
        // Keep the initialized Go runtime and trust roots across episode changes, while dropping
        // all provider sockets, credentials and article buffers.
        idleStop = cleanup.launch { delay(30_000); mutex.withLock { if (sessionId == null) stopLocked() } }
    }

    fun onAppBackgrounded() {
        cancelPrefetch()
        cleanup.launch { mutex.withLock { if (sessionId == null) stopLocked() } }
    }

    private fun clearPreparedLocked() {
        preparedExpiry?.cancel(); preparedExpiry = null
        preparedStream = null; preparedKey = null
        preparedGeneration = null
    }

    fun settingsChanged(): Job {
        // An unused prepared session was opened with the old settings; playback keeps its own.
        val invalidation = cancelPrefetch()
        return cleanup.launch {
            invalidation.join()
            mutex.withLock {
                if (!settings().cacheNzb) File(context.cacheDir, NZB_CACHE_DIR).listFiles()?.forEach { it.delete() }
            }
            prewarm().join()
        }
    }

    private fun start(trace: Trace? = null) {
        idleStop?.cancel(); idleStop = null
        val cfg = settings()
        // A settings change must not restart the engine underneath the playing source while its
        // replacement is still being prepared. The new memory target applies once no session owns it.
        if (process?.let(::isRunning) == true && endpoint != null &&
            (processProfile == cfg.profile || sessionId != null)) {
            trace?.mark("sidecar_reused")
            return
        }
        trace?.mark("sidecar_starting")
        stopLocked()
        val binary = File(context.applicationInfo.nativeLibraryDir, "libnuvio_usenet.so")
        if (!binary.canExecute()) {
            throw UsenetPreparationException("The Usenet engine is missing from this build.", UsenetPreparationException.Scope.ENGINE)
        }
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        // Go defaults to MADV_FREE on Android, so memory the engine returns after a session stays
        // in its RSS until the kernel is under pressure. GODEBUG is read before main, hence set here.
        val child = ProcessBuilder(binary.absolutePath)
            .apply { environment()["GODEBUG"] = "madvdontneed=1" }
            .start()
        trace?.mark("sidecar_spawned")
        process = child
        processProfile = cfg.profile
        startupReader.execute { drainQuietly(child.errorStream) }
        val bootstrap = JSONObject().apply {
            put("token", secret)
            put("nzbCacheDir", File(context.cacheDir, NZB_CACHE_DIR).absolutePath)
            put("memoryMiB", when (cfg.profile) {
                UsenetConfiguration.PROFILE_LOW_MEMORY -> 96
                UsenetConfiguration.PROFILE_THROUGHPUT -> 224
                else -> 144
            })
            // Go cannot consistently locate Android's APEX trust store across OS versions.
            // Export the system trust manager's public CA certificates.
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val certs = (tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager).acceptedIssuers
            put("certificates", JSONArray(certs.map { cert ->
                "-----BEGIN CERTIFICATE-----\n" + Base64.encodeToString(cert.encoded, Base64.NO_WRAP) + "\n-----END CERTIFICATE-----\n"
            }))
        }
        // Keep stdin open until stopLocked. Parent death closes the pipe and causes the Go process
        // to terminate even if nothing else ran.
        child.outputStream.write((bootstrap.toString() + "\n").toByteArray())
        child.outputStream.flush()
        trace?.mark("sidecar_bootstrap_sent")
        val ready = startupReader.submit<String> {
            child.inputStream.bufferedReader().readLine() ?: throw IOException("Usenet engine exited before startup")
        }
        try {
            val record = JSONObject(ready.get(15, TimeUnit.SECONDS))
            require(record.getInt("protocol") == 1)
            val port = record.getInt("port"); require(port in 1..65535)
            endpoint = "http://127.0.0.1:$port"; token = secret
            UsenetEngineUrls.remember(port)
            trace?.mark("sidecar_ready")
        } catch (_: Exception) {
            ready.cancel(true)
            stopLocked()
            throw UsenetPreparationException("The Usenet engine could not start.", UsenetPreparationException.Scope.ENGINE)
        }
    }

    private suspend fun control(path: String, payload: JSONObject?, delete: Boolean = false): String {
        val request = Request.Builder().url("$endpoint$path").header("Authorization", "Bearer $token")
        if (delete) request.delete() else request.post(payload.toString().toRequestBody("application/json".toMediaType()))
        val call = http.newCall(request.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        UsenetPreparationException("Could not reach the Usenet engine.", UsenetPreparationException.Scope.ENGINE),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val text = it.body?.string().orEmpty()
                            if (!continuation.isActive) return
                            if (it.isSuccessful) {
                                continuation.resume(text)
                            } else {
                                val failure = it.header("X-Usenet-Failure")
                                val scope = when (failure) {
                                    "configuration" -> UsenetPreparationException.Scope.ENGINE
                                    "provider" -> UsenetPreparationException.Scope.PROVIDER
                                    // Busy can be the previous cancelled open still unwinding.
                                    else -> UsenetPreparationException.Scope.SOURCE
                                }
                                continuation.resumeWithException(UsenetPreparationException(
                                    text.take(240).ifBlank { "The Usenet engine answered HTTP ${it.code}." }, scope, failure,
                                    providers = it.header("X-Usenet-Providers"),
                                ))
                            }
                        }
                    } catch (_: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("The Usenet engine stopped answering."))
                    }
                }
            })
        }
    }

    private fun stopLocked() {
        clearPreparedLocked()
        retirementJob?.cancel(); retirementJob = null
        retiredSessions.clear()
        idleStop?.cancel(); idleStop = null
        val child = process
        process = null; processProfile = null; endpoint = null; token = null; sessionId = null; playbackUrl = null
        if (child != null) {
            runCatching { child.outputStream.close() }
            // Process.isAlive and timed waitFor require API 26; Android destroy sends SIGKILL.
            runCatching {
                val deadline = SystemClock.elapsedRealtime() + 2000
                while (isRunning(child) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
                if (isRunning(child)) child.destroy()
            }
            runCatching { child.inputStream.close() }; runCatching { child.errorStream.close() }
        }
    }

    companion object {
        const val NZB_CACHE_DIR = "usenet-nzb"
        private const val PREPARED_LIFETIME_MS = 120_000L

        /** [message] with each URL cut to its scheme and host: no path, query, key or login reaches the log. */
        internal fun redacted(message: String): String = redactUrls(message)

        @Volatile var appForeground = true

        private fun isRunning(child: Process): Boolean =
            try { child.exitValue(); false } catch (_: IllegalThreadStateException) { true }

        /**
         * Reads the engine's stderr until it ends, keeping none of it: no credentials in Android logs.
         * Stopping the engine closes the stream under this read, which then throws.
         */
        internal fun drainQuietly(input: InputStream) {
            try {
                input.use { val bytes = ByteArray(4096); while (it.read(bytes) >= 0) Unit }
            } catch (_: IOException) {
            }
        }
    }
}
