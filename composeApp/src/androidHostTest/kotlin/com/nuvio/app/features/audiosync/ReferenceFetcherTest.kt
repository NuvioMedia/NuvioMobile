package com.nuvio.app.features.audiosync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class ReferenceFetcherTest {
    /** Runs queued work only when asked, like a pool whose threads are all busy. */
    private class ManualExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            queue += command
        }
        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private fun fetcher(
        normal: Executor,
        urgent: Executor,
        parsed: ConcurrentHashMap<String, List<String>> = ConcurrentHashMap(),
        download: (String) -> List<String>,
    ) = ReferenceFetcher(normal, urgent, parsed, download)

    @Test
    fun urgentReferenceDoesNotWaitBehindQueuedAlternatives() {
        val busy = ManualExecutor()
        val urgent = ManualExecutor()
        val fetcher = fetcher(busy, urgent) { listOf("words of $it") }
        repeat(12) { index -> fetcher.fetch("alt$index", onReady = {}) }
        val received = mutableListOf<List<String>>()
        fetcher.fetch("english", urgent = true) { received += it }

        urgent.runAll()

        assertEquals(listOf(listOf("words of english")), received)
        assertEquals(12, busy.queue.size)
    }

    @Test
    fun aFileIsDownloadedOnceForEveryoneWaiting() {
        val executor = ManualExecutor()
        var downloads = 0
        val fetcher = fetcher(executor, executor) { downloads++; listOf("cue") }
        val received = mutableListOf<String>()
        fetcher.fetch("a", onReady = { received += "first" })
        fetcher.fetch("a", onReady = { received += "second" })
        fetcher.fetch("a", onReady = null)

        executor.runAll()
        fetcher.fetch("a") { received += "cached" }

        assertEquals(1, downloads)
        assertEquals(listOf("first", "second", "cached"), received)
    }

    @Test
    fun urgentRequestOvertakesTheSameFileQueuedBehindAlternatives() {
        val busy = ManualExecutor()
        val urgent = ManualExecutor()
        val fetcher = fetcher(busy, urgent) { listOf("cue") }
        val received = mutableListOf<String>()
        fetcher.fetch("english", onReady = { received += "pool" })
        fetcher.fetch("english", urgent = true) { received += "recognition" }

        urgent.runAll()
        assertEquals(listOf("pool", "recognition"), received)

        // The queued duplicate finds the file parsed and serves nobody twice.
        busy.runAll()
        assertEquals(listOf("pool", "recognition"), received)
    }

    @Test
    fun aFailedCopyNeverHidesAnotherThatSucceeds() {
        val busy = ManualExecutor()
        val urgent = ManualExecutor()
        val parsed = ConcurrentHashMap<String, List<String>>()
        var attempt = 0
        val fetcher = fetcher(busy, urgent, parsed) { if (attempt++ == 0) emptyList() else listOf("cue") }
        val received = mutableListOf<List<String>>()
        fetcher.fetch("english", onReady = { received += it })
        fetcher.fetch("english", urgent = true) { received += it }

        busy.runAll() // fails, while the urgent copy is still pending
        assertTrue(received.isEmpty())
        urgent.runAll()

        assertEquals(listOf(listOf("cue"), listOf("cue")), received)
        assertEquals(listOf("cue"), parsed["english"])
    }

    @Test
    fun failuresAndThrowingHandlersNeverEscape() {
        val executor = ManualExecutor()
        val fetcher = fetcher(executor, executor) { url ->
            if (url == "broken") throw IllegalStateException("HTTP 500") else listOf("cue")
        }
        val received = mutableListOf<String>()
        fetcher.fetch("broken", onReady = { received += "broken" })
        fetcher.fetch("good", onReady = { throw IllegalStateException("handler bug") })
        fetcher.fetch("good", onReady = { received += "good" })

        executor.runAll()

        assertEquals(listOf("good"), received)
    }

    @Test
    fun aShutDownExecutorDoesNotThrow() {
        val rejecting = Executor { throw RejectedExecutionException("shut down") }
        val fetcher = fetcher(rejecting, rejecting) { listOf("cue") }
        fetcher.fetch("a", urgent = true) { throw AssertionError("must not be delivered") }
        fetcher.fetch("a", onReady = null)
    }
}
