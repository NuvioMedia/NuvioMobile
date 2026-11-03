package com.nuvio.app.features.livetv

private val XmlTvRootRegex = Regex("(?i)<tv(?:\\s|>)")

internal enum class LiveTvEpgContentChoice {
    Fresh,
    Cached,
    Failed,
}

internal fun chooseLiveTvEpgContent(hasValidFresh: Boolean, hasValidCache: Boolean): LiveTvEpgContentChoice =
    when {
        hasValidFresh -> LiveTvEpgContentChoice.Fresh
        hasValidCache -> LiveTvEpgContentChoice.Cached
        else -> LiveTvEpgContentChoice.Failed
    }

internal fun isValidXmlTvContent(content: String): Boolean {
    return XmlTvRootRegex.containsMatchIn(content) &&
        (content.contains("<channel", ignoreCase = true) || content.contains("<programme", ignoreCase = true))
}

internal object LiveTvEpgStorageBridge {
    var readCache: (Int, String) -> LiveTvEpgCacheEntry? = { _, _ -> null }
    var writeCache: (Int, LiveTvEpgCacheEntry) -> Unit = { _, _ -> }
}

internal fun LiveTvStorage.readEpgCache(profileId: Int, url: String): LiveTvEpgCacheEntry? =
    LiveTvEpgStorageBridge.readCache(profileId, url)

internal fun LiveTvStorage.writeEpgCache(profileId: Int, entry: LiveTvEpgCacheEntry) =
    LiveTvEpgStorageBridge.writeCache(profileId, entry)
