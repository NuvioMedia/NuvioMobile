package com.nuvio.app.features.usenet

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/**
 * The decisions of a tuning run, kept apart from the network so they can be tested.
 *
 * The limit check finds how many connections the account really accepts: a connection allowance
 * above it gets refusals, and the engine keeps retrying them during playback. The port and
 * connection rounds then measure download speed with real articles.
 */
internal object TunePlan {
    /** At most this many connections are opened to find the limit, unless the allowance is higher. */
    const val PROBE_CAP = 100
    const val MAX_PROBE = 200
    /** Connections opened together before confirming a refusal with one more sign-in. */
    const val PROBE_BATCH = 8
    /** Connections used to compare ports: few enough to leave the line room, enough to fill most. */
    const val PORT_CONNECTIONS = 8
    /** Readings within this share of the best are as fast. */
    const val GOOD_ENOUGH = 0.95
    /** Another port must be this much faster than the current one to replace it. */
    const val PORT_WIN = 1.10
    /** A step adding less than this over the best so far counts as no gain; two in a row end the round. */
    const val GAIN = 1.03
    private val LADDER = listOf(4, 8, 12, 16, 24, 32, 48, 64, 80, 100, 128, 160, 200)

    fun probeCap(allowance: Int) = maxOf(PROBE_CAP, allowance).coerceAtMost(MAX_PROBE)

    /**
     * Connections that may be used: the limit less a tenth (at least one), so a second device or
     * a slot the server has not yet freed does not cause refusals. Without a limit, what was tried.
     */
    fun usable(limit: Int?, tried: Int): Int = if (limit == null) tried else maxOf(1, limit - maxOf(1, limit / 10))

    /** The counts the connection round measures, up to [usable]. */
    fun ladder(usable: Int): List<Int> = (LADDER.filter { it < usable } + usable).distinct()

    /** TLS ports stay TLS: a plain port would send the login unencrypted. The current port first. */
    fun ports(provider: UsenetProvider): List<Pair<Int, Boolean>> =
        (listOf(provider.port to provider.tls) + if (provider.tls) listOf(563 to true, 443 to true)
            else listOf(119 to false)).distinct()

    fun averages(readings: List<TuneReading>, key: (TuneReading) -> Int): Map<Int, Double> =
        readings.groupBy(key).mapValues { (_, same) -> same.map { it.mbps }.average() }

    /** The fewest connections within [GOOD_ENOUGH] of the best average; null without a reading. */
    fun fastestConnections(readings: List<TuneReading>): Int? {
        val speeds = averages(readings) { it.connections }
        val best = speeds.values.maxOrNull()?.takeIf { it > 0 } ?: return null
        return speeds.filterValues { it >= best * GOOD_ENOUGH }.keys.min()
    }

    /**
     * The allowance to recommend. With a known [limit] it is never above the usable share of it.
     * It is not lowered otherwise: unused connections cost nothing and help seeks and slow periods,
     * and without a refusal there is no evidence of a limit. It is raised when more connections
     * measured clearly faster.
     */
    fun recommend(current: Int, fastest: Int, limit: Int?, tried: Int,
        initial: Map<Int, Double>, repeats: Map<Int, Double>): Int {
        fun fasterThanCurrent(speeds: Map<Int, Double>): Boolean {
            val baseline = speeds[current] ?: return false
            val candidate = speeds[fastest] ?: return false
            return baseline < candidate * GOOD_ENOUGH
        }
        val wanted = if (fastest > current && fasterThanCurrent(initial) && fasterThanCurrent(repeats)) fastest else current
        return if (limit == null) wanted else wanted.coerceAtMost(usable(limit, tried))
    }

    /** Compare working ports at the same number of signed-in connections. */
    fun pickPort(readings: List<TuneReading>, current: Pair<Int, Boolean>,
        remeasure: (TuneReading, Int) -> TuneReading?): Pair<Int, Boolean>? {
        val working = readings.filter { it.mbps > 0 }
        val count = working.minOfOrNull { it.connections } ?: return null
        val comparable = working.mapNotNull { reading ->
            if (reading.connections == count) reading
            else remeasure(reading, count)?.takeIf { it.connections == count }
        }
        fun isCurrent(reading: TuneReading) = reading.port == current.first && reading.tls == current.second
        val now = comparable.find(::isCurrent)
        // Keep a working current port if its equal-count recheck failed.
        if (now == null && working.any(::isCurrent)) return current
        val best = comparable.maxBy { it.mbps }
        return if (now != null && best.mbps < now.mbps * PORT_WIN) current else best.port to best.tls
    }

    /** True when the last two steps of a rising round each added less than [GAIN] over the best before them. */
    fun stopRising(speeds: List<Double>): Boolean {
        if (speeds.size < 3) return false
        fun noGain(i: Int) = speeds[i] < speeds.subList(0, i).max() * GAIN
        return noGain(speeds.lastIndex) && noGain(speeds.lastIndex - 1)
    }

    /** A finished step waits at least this long for the replies still due after QUIT, and at most [MAX_DRAIN_MS]. */
    const val MIN_DRAIN_MS = 3_000L
    const val MAX_DRAIN_MS = 15_000L

    /**
     * How long a finished step waits for the replies still in flight after QUIT: twice the time
     * [pipeline] bodies per connection take at the step's speed, within bounds. Some servers keep
     * counting a connection closed in the middle of a reply for minutes; past the bound it is
     * closed anyway.
     */
    fun drainMs(connections: Int, pipeline: Int, bodyBytes: Double, bytesPerSecond: Double): Long {
        if (bodyBytes <= 0 || bytesPerSecond <= 0) return MIN_DRAIN_MS
        val expectedMs = connections * pipeline * bodyBytes / bytesPerSecond * 1000
        return (2 * expectedMs).toLong().coerceIn(MIN_DRAIN_MS, MAX_DRAIN_MS)
    }

    /** Message ids of an NZB's segments, in order. */
    fun messageIds(nzb: String): List<String> =
        Regex("<segment\\b[^>]*>\\s*<?([^<>\\s]+?)>?\\s*</segment>").findAll(nzb).map { it.groupValues[1] }.toList()
}

/**
 * Measures, with real articles, the connection limit of a provider account and the port and
 * connection allowance that download fastest on this network. Runs on demand from settings; playback
 * only uses what the user then saves.
 */
internal class NntpProviderTuner(
    private val tester: NntpProviderTester,
    private val testArticles: () -> List<String>,
    private val stepMs: Long = STEP_MS,
    private val settleMs: Long = SETTLE_MS,
    private val warmupMs: Long = WARMUP_MS
) {
    constructor(tester: NntpProviderTester) : this(tester, ::downloadTestArticles)

    // A restart may arrive while cancellation is still releasing the previous provider slots.
    private val runMutex = Mutex()

    suspend fun tune(provider: UsenetProvider, allowPrivateNetwork: Boolean, pipeline: Int,
        onProgress: (TuneProgress) -> Unit): TuneOutcome = runMutex.withLock {
        runInterruptible(Dispatchers.IO) {
            Run(provider, allowPrivateNetwork, pipeline.coerceAtLeast(1), onProgress).execute()
        }
    }

    private inner class Run(
        val provider: UsenetProvider,
        val allowPrivate: Boolean,
        val pipeline: Int,
        val onProgress: (TuneProgress) -> Unit
    ) {
        val readings = mutableListOf<TuneReading>()
        val downloaded = AtomicLong()
        lateinit var addresses: List<InetAddress>
        lateinit var articles: List<String>
        val next = AtomicInteger()

        fun execute(): TuneOutcome {
            val (usableAddresses, unusable) = tester.resolveUsable(provider, allowPrivate)
            if (unusable != null) return TuneOutcome.Failed(TuneFailure.CONNECT, unusable)
            addresses = usableAddresses
            // The test articles download while the limit is checked.
            val testData = FutureTask { testArticles() }
            thread(isDaemon = true, name = "usenet-tune-nzb") { testData.run() }

            // 1. The limit: sign in batch after batch, holding every connection, until one is refused.
            val cap = TunePlan.probeCap(provider.connections)
            val held = mutableListOf<NntpSession>()
            var limit: Int? = null
            try {
                while (held.size < cap) {
                    progress(TuneProgress.Phase.LIMIT, held.size, provider.port, provider.tls)
                    val batch = openBatch(provider, minOf(TunePlan.PROBE_BATCH, cap - held.size))
                    batch.mapNotNull { it.session }.let(held::addAll)
                    val failure = batch.firstOrNull { it.session == null }?.result
                    if (failure != null) {
                        // The first connection failing is a setup problem, not a limit.
                        if (held.isEmpty()) return TuneOutcome.Failed(TuneFailure.CONNECT, failure)
                        val retry = openBatch(provider, 1).single()
                        if (retry.session == null) {
                            if (retry.result == UsenetProviderTestResult.REFUSED && batch.any { it.result == UsenetProviderTestResult.REFUSED }) {
                                limit = held.size
                            }
                            break
                        }
                        held += retry.session
                    }
                }
            } finally {
                signOut(held)
            }
            val tried = if (limit == null) held.size else limit + 1
            settle()

            articles = try { testData.get() } catch (_: ExecutionException) { emptyList() }
            if (articles.size < MIN_ARTICLES) return TuneOutcome.Failed(TuneFailure.TEST_DATA)
            next.set((articles.indices).random())
            val usable = TunePlan.usable(limit, tried)

            // 2. The port, at a few connections.
            val ports = TunePlan.ports(provider)
            val portConnections = minOf(TunePlan.PORT_CONNECTIONS, usable)
            for ((port, tls) in ports) {
                val reading = measure(TuneProgress.Phase.PORT, port, tls, portConnections)
                if (reading == null && failure == TuneFailure.MISSING_ARTICLES) return TuneOutcome.Failed(TuneFailure.MISSING_ARTICLES)
            }
            val (port, tls) = TunePlan.pickPort(readings, provider.port to provider.tls) { reading, count ->
                measure(TuneProgress.Phase.PORT, reading.port, reading.tls, count)
            } ?: return TuneOutcome.Failed(failure ?: TuneFailure.TOO_SLOW)

            // 3. The connection count on that port, rising until more stop helping or the limit is reached.
            val rising = mutableListOf<Double>()
            for (count in TunePlan.ladder(usable)) {
                val reading = measure(TuneProgress.Phase.CONNECTIONS, port, tls, count)
                rising += reading?.mbps ?: 0.0
                if (reading == null && failure == TuneFailure.CONNECT) break
                if (TunePlan.stopRising(rising)) break
            }
            val current = provider.connections
            if (current <= usable && readings.none { it.port == port && it.tls == tls && it.connections == current }) {
                measure(TuneProgress.Phase.CONNECTIONS, port, tls, current)
            }
            val round = readings.filter { it.port == port && it.tls == tls }
            val initial = TunePlan.averages(round) { it.connections }
            val candidate = TunePlan.fastestConnections(round) ?: return TuneOutcome.Failed(TuneFailure.TOO_SLOW)
            val countsToRepeat = if (candidate > current && current in initial) listOf(current, candidate)
                else initial.entries.sortedByDescending { it.value }.take(2).map { it.key }
            val repeats = mutableMapOf<Int, Double>()
            for (count in countsToRepeat) {
                val repeat = measure(TuneProgress.Phase.CONNECTIONS, port, tls, count)
                if (repeat?.connections == count) repeats[count] = repeat.mbps
            }
            val final = readings.filter { it.port == port && it.tls == tls }
            val fastest = TunePlan.fastestConnections(final) ?: return TuneOutcome.Failed(TuneFailure.TOO_SLOW)
            val recommended = TunePlan.recommend(current, fastest, limit, tried, initial, repeats)
            val speed = TunePlan.averages(final) { it.connections }
            val mbps = speed[recommended] ?: speed.filterKeys { it <= recommended }.maxByOrNull { it.key }?.value ?: speed.getValue(fastest)
            return TuneOutcome.Success(TuneResult(limit, tried, port, tls, recommended, fastest, mbps, readings.toList(), downloaded.get()))
        }

        var failure: TuneFailure? = null

        fun progress(phase: TuneProgress.Phase, connections: Int, port: Int, tls: Boolean) =
            onProgress(TuneProgress(phase, connections, port, tls, readings.toList()))

        /** Opens [count] connections at once; each result keeps its session when it signed in. */
        fun openBatch(target: UsenetProvider, count: Int): List<OpenResult> {
            val results = AtomicReferenceArray<OpenResult>(count)
            val done = CountDownLatch(count)
            val sockets = List(count) { mutableListOf<Socket>() }
            var cancelled = false
            fun newSocket(i: Int): Socket = synchronized(sockets) {
                if (cancelled) throw IOException("Connection batch cancelled")
                Socket().also(sockets[i]::add)
            }
            repeat(count) { i ->
                thread(isDaemon = true, name = "usenet-tune-open") {
                    try { results.set(i, tester.open(target, allowPrivate, addresses) { newSocket(i) }) }
                    catch (_: Exception) { results.set(i, OpenResult(UsenetProviderTestResult.UNREACHABLE)) }
                    finally { done.countDown() }
                }
            }
            fun all() = List(count) { results.get(it) ?: OpenResult(UsenetProviderTestResult.UNREACHABLE) }
            try { done.await() } catch (e: InterruptedException) {
                // Unblock the sign-ins still in progress; the sessions already open are signed out.
                synchronized(sockets) { cancelled = true }
                sockets.forEachIndexed { i, opened ->
                    if (results.get(i)?.session == null) opened.forEach { runCatching { it.close() } }
                }
                done.await()
                signOut(all().mapNotNull { it.session })
                throw e
            }
            return all()
        }

        /**
         * Downloads with [connections] connections for [stepMs] after a short warm-up; null (and
         * [failure]) when nothing could be measured. Sign-in time is not measured.
         */
        fun measure(phase: TuneProgress.Phase, port: Int, tls: Boolean, connections: Int): TuneReading? {
            failure = null
            progress(phase, connections, port, tls)
            val target = provider.copy(port = port, tls = tls)
            val opened = openBatch(target, connections)
            val sessions = opened.mapNotNull { it.session }
            if (sessions.isEmpty()) {
                failure = TuneFailure.CONNECT
                settle()
                return null
            }
            val received = AtomicLong()
            val bodies = AtomicInteger()
            val answered = AtomicInteger()
            val missing = AtomicInteger()
            val ending = AtomicBoolean()
            val workers = sessions.map { session ->
                thread(isDaemon = true, name = "usenet-tune") { download(session, received, bodies, answered, missing, ending) }
            }
            var bytesPerSecond = 0.0
            var stopped = false
            try {
                Thread.sleep(warmupMs)
                val startBytes = received.get()
                val start = System.nanoTime()
                while (true) {
                    Thread.sleep(POLL_MS)
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    if (elapsed >= stepMs) break
                    if (elapsed >= MIN_STEP_MS && received.get() - startBytes >= MAX_STEP_BYTES) break
                    if (workers.none { it.isAlive }) break
                }
                val seconds = (System.nanoTime() - start) / 1e9
                val bytes = received.get() - startBytes
                bytesPerSecond = bytes / seconds
                if (answered.get() >= MISSING_SAMPLE && missing.get() * 2 > answered.get()) {
                    failure = TuneFailure.MISSING_ARTICLES
                    return null
                }
                if (bytes <= 0) { failure = TuneFailure.TOO_SLOW; return null }
                val reading = TuneReading(port, tls, sessions.size, bytes * 8 / 1e6 / seconds)
                readings += reading
                progress(phase, connections, port, tls)
                return reading
            } catch (e: InterruptedException) {
                stopped = true
                throw e
            } finally {
                val drainMs = if (stopped) TunePlan.MIN_DRAIN_MS
                    else TunePlan.drainMs(sessions.size, pipeline, received.get().toDouble() / bodies.get().coerceAtLeast(1), bytesPerSecond)
                val spent = finish(sessions, workers, ending, drainMs)
                downloaded.addAndGet(received.get())
                // Signed-out slots are freed while the replies drain, so only the rest of the settle
                // time is left. A connection closed in the middle of a reply gets the whole of it.
                Thread.sleep(if (spent == null) settleMs else (settleMs - spent).coerceAtLeast(0))
            }
        }

        /**
         * Ends a step: each worker sends QUIT after its next reply and reads the replies still due
         * up to the goodbye, within [drainMs]. Then every session is closed. Returns the time spent
         * when every worker was done in time, or null when some were closed in the middle of a reply.
         */
        fun finish(sessions: List<NntpSession>, workers: List<Thread>, ending: AtomicBoolean, drainMs: Long): Long? {
            ending.set(true)
            val start = System.nanoTime()
            val deadline = start + drainMs * 1_000_000
            var interrupted = false
            try {
                for (worker in workers) {
                    val left = (deadline - System.nanoTime()) / 1_000_000
                    if (left <= 0) break
                    worker.join(left)
                }
            } catch (_: InterruptedException) {
                // Stopped while draining: close at once.
                interrupted = true
            }
            val done = workers.none { it.isAlive }
            sessions.forEach { it.close() }
            workers.forEach { it.join(1_000) }
            if (interrupted) throw InterruptedException()
            return if (done) (System.nanoTime() - start) / 1_000_000 else null
        }

        /**
         * BODY requests, [pipeline] in flight. Once [ending] is set: QUIT after the next reply, then
         * the replies still due up to the goodbye. Until then, or until the session is closed.
         */
        fun download(session: NntpSession, received: AtomicLong, bodies: AtomicInteger, answered: AtomicInteger,
            missing: AtomicInteger, ending: AtomicBoolean) {
            fun nextId() = articles[Math.floorMod(next.getAndIncrement(), articles.size)]
            var quitting = false
            try {
                session.timeoutMs = READ_TIMEOUT_MS
                session.commands(List(pipeline) { "BODY <${nextId()}>" })
                while (true) {
                    val status = session.readLine() ?: return
                    answered.incrementAndGet()
                    when (status.take(3)) {
                        "222" -> { session.skipBody { received.addAndGet(it.toLong()) }; bodies.incrementAndGet() }
                        "430", "423" -> missing.incrementAndGet()
                        // The goodbye after QUIT, or an error.
                        else -> return
                    }
                    if (quitting) continue
                    if (ending.get()) {
                        quitting = true
                        session.command("QUIT")
                    } else {
                        session.command("BODY <${nextId()}>")
                    }
                }
            } catch (_: IOException) {
                // Closed at the end of the step, or the connection failed: either way this worker is done.
            }
        }

        /** Gives the server time to free the slots of closed connections. */
        fun settle() = Thread.sleep(settleMs)
    }

    internal companion object {
        /** A public test NZB kept current by SABnzbd: about 1 GB in 1561 articles posted for speed tests. */
        const val TEST_NZB_URL = "https://sabnzbd.org/tests/test_download_1GB.nzb"
        const val STEP_MS = 8_000L
        const val SETTLE_MS = 2_500L
        const val WARMUP_MS = 1_500L
        const val MIN_STEP_MS = 3_000L
        const val POLL_MS = 100L
        /** A step on a fast line ends early once this much was measured, to spare block accounts. */
        const val MAX_STEP_BYTES = 250L * 1024 * 1024
        const val READ_TIMEOUT_MS = 15_000
        const val MIN_ARTICLES = 50
        /** Missing articles are judged once this many replies arrived. */
        const val MISSING_SAMPLE = 10

        /** How long idle sessions being signed out wait for their goodbyes, all together. */
        const val SIGN_OUT_MS = 2_000

        /**
         * Signs idle sessions out and waits briefly for each goodbye before closing. Some servers
         * keep counting a connection closed without QUIT for minutes, and a rerun would hit it.
         */
        fun signOut(sessions: List<NntpSession>) {
            sessions.forEach { session -> runCatching { session.command("QUIT") } }
            val deadline = System.nanoTime() + SIGN_OUT_MS * 1_000_000L
            sessions.forEach { session ->
                val left = ((deadline - System.nanoTime()) / 1_000_000).toInt()
                if (left > 0) runCatching { session.timeoutMs = left; session.readLine() }
                session.close()
            }
        }

        private val http by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        }

        fun downloadTestArticles(): List<String> {
            val request = Request.Builder().url(TEST_NZB_URL).header(USER_AGENT, NUVIO_USER_AGENT).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Test NZB: HTTP ${response.code}")
                val source = (response.body ?: throw IOException("Test NZB: empty")).source()
                val buffer = Buffer()
                while (buffer.size <= MAX_NZB_BYTES && source.read(buffer, 64 * 1024L) != -1L) Unit
                if (buffer.size > MAX_NZB_BYTES) throw IOException("Test NZB too large")
                return TunePlan.messageIds(buffer.readUtf8())
            }
        }

        private const val MAX_NZB_BYTES = 4 * 1024 * 1024
    }
}
