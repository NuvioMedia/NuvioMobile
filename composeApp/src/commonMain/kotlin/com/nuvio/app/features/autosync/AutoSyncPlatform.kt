package com.nuvio.app.features.autosync

import kotlin.time.TimeSource

/*
 * The few platform services AutoSync needs. Everything else in the AutoSync engine is shared,
 * so Android, iOS and desktop run the same analysis.
 */

private val autoSyncClockOrigin = TimeSource.Monotonic.markNow()

/** Monotonic milliseconds, the shared stand-in for Android's SystemClock.elapsedRealtime(). */
internal fun autoSyncElapsedRealtimeMs(): Long = autoSyncClockOrigin.elapsedNow().inWholeMilliseconds

/** Monotonic nanoseconds, the shared stand-in for System.nanoTime(). */
internal fun autoSyncNanoTime(): Long = autoSyncClockOrigin.elapsedNow().inWholeNanoseconds

/**
 * Track selection and role flags, with the same values as Media3's `C` constants so flags read
 * from Media3 formats and from container indexes compare equally.
 */
internal object AutoSyncTrackFlags {
    const val SELECTION_FLAG_DEFAULT = 1
    const val SELECTION_FLAG_FORCED = 2
    const val ROLE_FLAG_COMMENTARY = 8
    const val ROLE_FLAG_SUBTITLE = 128
    const val ROLE_FLAG_DESCRIBES_VIDEO = 512
    const val ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND = 1024
    const val ROLE_FLAG_TRANSCRIBES_DIALOG = 4096
}

/** Small access-ordered LRU map. Not thread-safe: callers hold their own lock. */
internal class AutoSyncLruCache<K, V>(private val maxEntries: Int) {
    private val map = LinkedHashMap<K, V>()

    operator fun get(key: K): V? {
        val value = map.remove(key) ?: return null
        map[key] = value
        return value
    }

    operator fun set(key: K, value: V) {
        map.remove(key)
        map[key] = value
        while (map.size > maxEntries) {
            map.remove(map.keys.first())
        }
    }

    fun remove(key: K): V? = map.remove(key)
}

/** `MutableMap.putIfAbsent` for common code: returns the previous value, or null after inserting. */
internal fun <K, V> MutableMap<K, V>.autoSyncPutIfAbsent(key: K, value: V): V? {
    val existing = this[key]
    if (existing == null) this[key] = value
    return existing
}

/** Which HTTP client a request uses; Android keeps a dedicated client for container indexes. */
internal enum class AutoSyncHttpClientKind {
    /** Range requests into the video container: short timeouts, follows redirects. */
    CONTAINER_INDEX,

    /** Subtitle file downloads: Nuvio's shared addon client. */
    SUBTITLE,
}

/** Streaming response body. [read] returns the number of bytes read, or -1 at the end. */
internal interface AutoSyncHttpBody {
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

internal class AutoSyncHttpResponse(
    val status: Int,
    val statusText: String,
    val finalUrl: String,
    /** Header names in lower case; repeated headers joined with ",". */
    val headers: Map<String, String>,
    val body: AutoSyncHttpBody,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * Sends a GET with exactly [headers] and runs [block] while the response is open. The response is
 * closed when [block] returns, and cancelling the caller cancels the network call.
 * [timeoutMs] bounds the whole call, body included; null keeps the client's own timeouts.
 */
internal expect suspend fun <T> autoSyncHttpGet(
    url: String,
    headers: Map<String, String>,
    kind: AutoSyncHttpClientKind,
    timeoutMs: Long?,
    block: suspend (AutoSyncHttpResponse) -> T,
): T

/** True when this platform can decode text in the charset with IANA name [charsetName]. */
internal expect fun autoSyncIsCharsetSupported(charsetName: String): Boolean

/**
 * Decodes [length] bytes from [offset] in [charsetName]. With [strict], malformed or unmappable
 * input returns null; otherwise it is replaced. Returns null for an unsupported charset.
 */
internal expect fun autoSyncDecodeBytes(
    bytes: ByteArray,
    offset: Int,
    length: Int,
    charsetName: String,
    strict: Boolean,
): String?
