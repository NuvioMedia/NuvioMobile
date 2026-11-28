package com.nuvio.app.features.usenet

/**
 * A built-in release's NZB link as Reuse Last Link keeps it, outside the encrypted sources: each
 * indexer API key in it is replaced by a reference to its indexer, wherever the indexer put the
 * key in the link. [withApiKeys] puts today's key back. The same form as NuvioTV's.
 */
internal object UsenetKeptLinks {
    // Braces are never left bare in a URL, so a reference cannot be mistaken for part of one.
    private const val KEY_REFERENCE = "{indexer-key:"
    private val KEY_REFERENCES = Regex("""\{indexer-key:([^}]+)\}""")

    /** Shorter values could match unrelated parts of a link. */
    private const val MIN_REFERENCED_KEY = 8

    fun withoutApiKeys(nzbUrl: String, config: UsenetSourceConfiguration): String =
        config.indexers.filter { it.apiKey.length >= MIN_REFERENCED_KEY }.sortedByDescending { it.apiKey.length }
            .fold(nzbUrl) { url, indexer -> url.replace(indexer.apiKey, "$KEY_REFERENCE${indexer.id}}") }

    /** Null when a referenced indexer was removed or has no key any more: the link cannot be opened. */
    fun withApiKeys(stored: String, config: UsenetSourceConfiguration): String? {
        var missing = false
        val url = KEY_REFERENCES.replace(stored) { match ->
            val key = config.indexers.find { it.id == match.groupValues[1] }?.apiKey.orEmpty()
            if (key.isEmpty()) missing = true
            key
        }
        return url.takeUnless { missing }
    }

    /** The indexer a kept link names, if it is still set up. */
    fun indexerOf(stored: String, config: UsenetSourceConfiguration): UsenetIndexer? =
        KEY_REFERENCES.find(stored)?.groupValues?.get(1)?.let { id -> config.indexers.find { it.id == id } }
}
