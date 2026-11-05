package com.nuvio.app.features.livetv.calendar

import com.nuvio.app.features.livetv.data.CivilTime
import com.nuvio.app.features.livetv.data.LiveTvTimeZones

enum class CalendarKind { EPISODE, MOVIE }

/** Tags beside an entry. */
enum class CalendarTag(val label: String) {
    NEW("New"),
    SERIES_PREMIERE("Series premiere"),
    SEASON_PREMIERE("Season premiere"),
    SEASON_FINALE("Season finale"),
    SERIES_FINALE("Series finale"),
    IN_THEATERS("In theaters"),
    AT_HOME("At home"),
    ON_DISC("On disc"),
}

/** Why an entry is on your Calendar. */
enum class CalendarReason { LIBRARY, WATCHING }

data class CalendarEntry(
    /** Unique per entry. */
    val key: String,
    val kind: CalendarKind,
    /** Nuvio's id for the show or movie (IMDb "tt…" when known). */
    val contentId: String,
    /** "series" or "movie". */
    val type: String,
    val title: String,
    val poster: String?,
    val background: String?,
    val addonBaseUrl: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** The last episode when several come out together (a whole season at once). */
    val lastEpisode: Int? = null,
    val episodeTitle: String? = null,
    val episodeImage: String? = null,
    /** When it airs for you (null: only the day is known). */
    val airMs: Long? = null,
    /** Midnight (device time) of the day it airs in your time zone. */
    val dayMs: Long,
    /** The network or streaming service. */
    val network: String? = null,
    /** On a streaming service (no air time). */
    val streaming: Boolean = false,
    val tags: List<CalendarTag> = emptyList(),
    /** Movies: the other release ("In theaters Oct 2"). */
    val note: String? = null,
    val reasons: Set<CalendarReason> = emptySet(),
    /** You've watched it. */
    val watched: Boolean = false,
    /** You haven't reached it yet: its title and picture are hidden. */
    val spoiler: Boolean = false,
    val runtimeMin: Int? = null,
    /** What it's about (the episode's summary, the movie's overview). */
    val description: String? = null,
) {
    val episodeCode: String?
        get() = if (season == null || episode == null) null else buildString {
            append("S$season · E$episode")
            if (lastEpisode != null && lastEpisode > episode) append("–E$lastEpisode")
        }

    /** Nuvio's id for this episode ("tt123:2:5"). */
    val videoId: String?
        get() = if (season != null && episode != null && contentId.startsWith("tt")) "$contentId:$season:$episode" else null
}

/** What the Calendar shows. */
data class CalendarState(
    val entries: List<CalendarEntry> = emptyList(),
    /** Still looking up shows. */
    val loading: Boolean = false,
    /** Shows looked up so far / in all (while loading). */
    val progress: Pair<Int, Int>? = null,
    val updatedAt: Long = 0L,
    /** Shows and movies on the Calendar. */
    val showCount: Int = 0,
    val problem: String? = null,
)

/**
 * US networks air prime time at the same clock time on the East and West Coasts (the West gets a
 * delayed feed), but TVmaze gives the East Coast moment. On a phone set to Pacific (or Alaska /
 * Hawaii) time, a show listed for 8 pm Eastern is shown at 8 pm local; on Mountain time at 7 pm
 * ("8/7c"). Central and Eastern are left as they are.
 */
object CalendarAirTimes {
    private const val EASTERN = "America/New_York"
    private const val HOUR = 3_600_000L

    /** Hours to take off the listed clock time (0 = Pacific or further west, 1 = Mountain), or null: no change. */
    fun westShiftHours(now: Long): Int? {
        // The standard (winter) offset of the phone's zone: the smaller of its January and July offsets.
        val t = CivilTime.at(now, LiveTvTimeZones.SYSTEM)
        val jan = LiveTvTimeZones.offsetMs(LiveTvTimeZones.SYSTEM, CivilTime.toEpochMs(t.year, 1, 15, 12, 0, 0, LiveTvTimeZones.UTC))
        val jul = LiveTvTimeZones.offsetMs(LiveTvTimeZones.SYSTEM, CivilTime.toEpochMs(t.year, 7, 15, 12, 0, 0, LiveTvTimeZones.UTC))
        val std = minOf(jan, jul) / HOUR
        return when {
            std in -11L..-8L -> 0
            std == -7L -> 1
            else -> null
        }
    }

    /**
     * The time [listedMs] airs here: the same clock time (less the west shift) when the show is on
     * an East Coast network, otherwise [listedMs] as it is.
     */
    fun local(listedMs: Long?, networkZone: String?, streaming: Boolean): Long? {
        if (listedMs == null || streaming) return listedMs
        val shift = westShiftHours(listedMs) ?: return listedMs
        val zone = networkZone ?: return listedMs
        if (!zone.startsWith("America/") || !LiveTvTimeZones.isKnown(zone)) return listedMs
        // Same offset as New York at that moment: an East Coast network.
        if (LiveTvTimeZones.offsetMs(zone, listedMs) != LiveTvTimeZones.offsetMs(EASTERN, listedMs)) return listedMs
        val east = CivilTime.at(listedMs, EASTERN)
        return CivilTime.toEpochMs(east.year, east.month, east.day, east.hour - shift, east.minute, east.second, LiveTvTimeZones.SYSTEM)
    }
}
