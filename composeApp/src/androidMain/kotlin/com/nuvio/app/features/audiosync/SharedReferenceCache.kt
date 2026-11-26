package com.nuvio.app.features.audiosync

/**
 * Reference subtitles already downloaded and parsed, kept for the whole app rather than one player:
 * the player is rebuilt when a stream is prepared again or the next episode starts, and its
 * references would otherwise be downloaded again while sync waits for them. Only successful
 * downloads are kept, so a failure is always retried. The least recently used go first.
 */
internal object SharedReferenceCache {
    /**
     * Two or three titles' worth (four references each): enough for a restarted player and the
     * next episode, under 2 MB in all on a low-memory TV.
     */
    private const val MAX_ENTRIES = 8

    private val entries = object : LinkedHashMap<String, List<Triple<Long, Long, String>>>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Triple<Long, Long, String>>>?) =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(url: String): List<Triple<Long, Long, String>>? = entries[url]

    @Synchronized
    fun put(url: String, dialogue: List<Triple<Long, Long, String>>) {
        if (dialogue.isNotEmpty()) entries[url] = dialogue
    }

    @Synchronized
    internal fun clearForTest() = entries.clear()
}
