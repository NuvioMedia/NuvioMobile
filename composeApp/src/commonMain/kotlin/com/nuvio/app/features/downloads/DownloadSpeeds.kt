package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember

@Composable
internal fun rememberDownloadSpeeds(items: List<DownloadItem>): Map<String, Long> {
    val tracker = remember { DownloadSpeedTracker() }
    LaunchedEffect(items) {
        tracker.update(items)
    }
    return tracker.speeds
}

private class DownloadSpeedTracker {
    private val samples = mutableMapOf<String, Sample>()
    val speeds = mutableStateMapOf<String, Long>()

    fun update(items: List<DownloadItem>) {
        val downloading = items.filter { it.status == DownloadStatus.Downloading }
        downloading.forEach { item ->
            val previous = samples.put(item.id, Sample(item.downloadedBytes, item.updatedAtEpochMs)) ?: return@forEach
            val elapsedMs = item.updatedAtEpochMs - previous.atEpochMs
            val receivedBytes = item.downloadedBytes - previous.bytes
            if (elapsedMs <= 0L || receivedBytes < 0L) return@forEach
            val bytesPerSecond = receivedBytes * 1_000L / elapsedMs
            val current = speeds[item.id]
            speeds[item.id] = if (current == null) bytesPerSecond else (current * 7L + bytesPerSecond * 3L) / 10L
        }
        val downloadingIds = downloading.mapTo(mutableSetOf()) { it.id }
        samples.keys.retainAll(downloadingIds)
        speeds.keys.filterNot { it in downloadingIds }.forEach(speeds::remove)
    }

    private data class Sample(val bytes: Long, val atEpochMs: Long)
}
