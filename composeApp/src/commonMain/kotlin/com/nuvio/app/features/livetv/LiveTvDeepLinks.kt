package com.nuvio.app.features.livetv

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a Live TV notification (a reminder, a game alert) takes you when tapped. */
internal sealed interface LiveTvLink {
    /** Play this channel. */
    data class Channel(val key: String) : LiveTvLink
    /** Open this game's page in Sports. */
    data class SportsGame(val eventId: String) : LiveTvLink
    /** Open the Sports hub. */
    data object Sports : LiveTvLink
}

/**
 * Live TV's own links ("nuvio://livetv/channel?key=…", "nuvio://livetv/sports?event=…"), used by
 * reminder and game notifications. The app's URL handler hands matching links here; the main
 * screen watches [pending] and acts on it.
 */
internal object LiveTvDeepLinks {
    private const val HOST = "livetv"

    private val _pending = MutableStateFlow<LiveTvLink?>(null)
    val pending: StateFlow<LiveTvLink?> = _pending.asStateFlow()

    fun channelUrl(key: String): String = "nuvio://$HOST/channel?key=" + key.encodeURLParameter()
    fun sportsGameUrl(eventId: String): String = "nuvio://$HOST/sports?event=" + eventId.encodeURLParameter()
    fun sportsUrl(): String = "nuvio://$HOST/sports"

    /** True when [url] is one of ours (and it's now pending). */
    fun handle(url: String): Boolean {
        val link = parse(url) ?: return false
        _pending.value = link
        return true
    }

    fun parse(url: String): LiveTvLink? {
        val u = runCatching { Url(url.trim()) }.getOrNull() ?: return null
        if (u.protocol.name.lowercase() != "nuvio" || u.host.lowercase() != HOST) return null
        val segments = u.pathSegments.map { it.trim() }.filter { it.isNotBlank() }
        return when (segments.firstOrNull()) {
            "channel" -> u.parameters["key"]?.takeIf { it.isNotBlank() }?.let { LiveTvLink.Channel(it) }
            "sports" -> u.parameters["event"]?.takeIf { it.isNotBlank() }?.let { LiveTvLink.SportsGame(it) } ?: LiveTvLink.Sports
            else -> null
        }
    }

    fun consume(link: LiveTvLink) {
        if (_pending.value == link) _pending.value = null
    }
}
