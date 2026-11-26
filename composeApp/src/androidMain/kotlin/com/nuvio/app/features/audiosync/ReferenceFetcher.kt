package com.nuvio.app.features.audiosync

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Downloads each reference subtitle once, however many ask for it, into [parsed] (an empty list
 * marks a failed file). Urgent requests, the references recognition waits for, run on [urgent]
 * workers, also when the same file is still queued on [normal]; whichever download finishes first
 * serves everyone waiting. A failure only counts once no other download of that file is running.
 */
internal class ReferenceFetcher<T : Any>(
    private val normal: Executor,
    private val urgent: Executor,
    private val parsed: ConcurrentHashMap<String, List<T>>,
    /** Blocking download and parse; returns an empty list on failure. */
    private val download: (url: String) -> List<T>,
    private val log: (String) -> Unit = {},
) {
    private class Pending<T> {
        val waiters = ArrayList<(List<T>) -> Unit>()
        var urgent = false
        var running = 0
    }

    private val pending = ConcurrentHashMap<String, Pending<T>>()

    fun fetch(url: String, urgent: Boolean = false, onReady: ((List<T>) -> Unit)?) {
        var cached: List<T>? = null
        var executor: Executor? = null
        pending.compute(url) { _, existing ->
            val done = parsed[url]
            if (existing == null && done != null) {
                cached = done
                return@compute null
            }
            (existing ?: Pending<T>()).also { entry ->
                onReady?.let(entry.waiters::add)
                if (existing == null || (urgent && !entry.urgent)) {
                    executor = if (urgent) this.urgent else normal
                    entry.urgent = entry.urgent || urgent
                    entry.running++
                }
            }
        }
        val ready = cached
        if (ready != null) {
            if (ready.isNotEmpty() && onReady != null) deliver(url, ready, onReady)
            return
        }
        val start = executor ?: return
        try {
            start.execute { run(url) }
        } catch (_: Exception) {
            // Shut down: the controller is being released.
            finish(url, emptyList())
        }
    }

    private fun run(url: String) {
        // Another download of the same file may have finished first.
        val result = parsed[url]?.takeIf { it.isNotEmpty() } ?: try {
            download(url)
        } catch (error: Exception) {
            log("reference download failed for $url: ${error.message}")
            emptyList()
        }
        finish(url, result)
    }

    private fun finish(url: String, result: List<T>) {
        var waiters: List<(List<T>) -> Unit> = emptyList()
        pending.compute(url) { _, entry ->
            if (entry != null) entry.running--
            if (result.isEmpty() && entry != null && entry.running > 0) return@compute entry
            // A failure never replaces a file already parsed.
            if (result.isNotEmpty()) parsed[url] = result else parsed.putIfAbsent(url, result)
            waiters = entry?.waiters?.toList().orEmpty()
            null
        }
        if (result.isEmpty()) return
        waiters.forEach { deliver(url, result, it) }
    }

    /** A failing handler must not take down the download worker, and with it the app. */
    private fun deliver(url: String, result: List<T>, onReady: (List<T>) -> Unit) {
        try {
            onReady(result)
        } catch (error: Exception) {
            log("reference handler failed for $url: ${error.message}")
        }
    }
}
