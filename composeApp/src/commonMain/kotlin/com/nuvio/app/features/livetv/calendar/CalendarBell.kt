package com.nuvio.app.features.livetv.calendar

import kotlinx.coroutines.flow.MutableStateFlow

/** The show or movie whose page is open, so its page can show the Calendar bell. */
internal data class BellTarget(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val imdb: String?,
)

internal object CalendarBellHost {
    val target = MutableStateFlow<BellTarget?>(null)

    fun forMeta(id: String, type: String, name: String, poster: String?, background: String?, imdb: String?): BellTarget? {
        val t = type.lowercase()
        val kind = when {
            t == "movie" -> "movie"
            t == "series" || t == "tv" -> "series"
            else -> return null
        }
        return BellTarget(id, kind, name, poster, background, imdb)
    }
}
