package com.nuvio.app.features.livetv.sync

import com.nuvio.app.features.livetv.data.LiveTvKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

/** The parts of the Live TV setup that Setup Sync can leave out on one device. */
internal enum class SyncPart(val label: String, val description: String, val defaultOn: Boolean) {
    LOGINS("Playlists and logins", "Your IPTV links, usernames and passwords. Stored encrypted.", true),
    FAVORITES("Favorites and groups", "Favorite channels, hidden channels and group order.", true),
    SPORTS("Sports", "Followed teams and leagues, and Choose leagues.", true),
    CALENDAR("Calendar", "Followed and hidden shows.", true),
    LOOK("Look and layout", "Themes, menu, card styles and other options.", false),
}

/**
 * Which parts of the setup this device syncs. The choice is kept on this device only (it is not part
 * of the synced setup). A part that is off is left out when this device saves, and keeps this
 * device's own values when a saved setup is restored; the saved copy keeps what other devices put
 * in it. With every part on, nothing here changes how sync works.
 */
internal object SyncParts {
    private val store = LiveTvDriveHttp
    private val PROFILE_SUFFIX = Regex("""_p\d+$""")

    private val LOGIN_KEYS = setOf(LiveTvKeys.playlists, LiveTvKeys.epgs, LiveTvKeys.playlistAccounts, LiveTvKeys.epgOverrides)
    private val FAVORITE_KEYS = setOf(
        LiveTvKeys.favorites, LiveTvKeys.hiddenChannels, LiveTvKeys.hiddenGroups, LiveTvKeys.channelNames,
        LiveTvKeys.channelLogos, LiveTvKeys.channelNumbers, LiveTvKeys.groupNames, LiveTvKeys.groupOrder,
        LiveTvKeys.customGroups, LiveTvKeys.channelOrder, LiveTvKeys.channelCopies, LiveTvKeys.channelAudio,
        LiveTvKeys.pictureShapes, LiveTvKeys.lockedGroups, LiveTvKeys.nameRemovals, LiveTvKeys.reminders,
        LiveTvKeys.vodHiddenCategories, LiveTvKeys.vodGroupOrder, LiveTvKeys.vodGroupNames,
    )
    private val SPORTS_KEYS = setOf(LiveTvKeys.sports, LiveTvKeys.sportsMine)
    private val CALENDAR_KEYS = setOf(
        LiveTvKeys.calendarFollows, LiveTvKeys.calendarHiddenShows, LiveTvKeys.calendarChannelPicks,
        LiveTvKeys.calendarPlans, LiveTvKeys.calendarCountry,
    )

    /** Always synced, whatever is switched off: the parental lock and the profile set-up marker. */
    private val ALWAYS_KEYS = setOf(LiveTvKeys.parentalPin, LiveTvKeys.lockAdultContent, "profile_seeded")

    private val _revision = MutableStateFlow(0)

    /** Changes whenever a switch is flipped (for the settings card to redraw). */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Which part a stored key belongs to, or null when it is always synced. Everything not named above is "Look and layout". */
    fun partOf(key: String): SyncPart? {
        val base = key.replace(PROFILE_SUFFIX, "")
        return when {
            base in ALWAYS_KEYS -> null
            base in LOGIN_KEYS -> SyncPart.LOGINS
            base in FAVORITE_KEYS -> SyncPart.FAVORITES
            base in SPORTS_KEYS -> SyncPart.SPORTS
            base in CALENDAR_KEYS -> SyncPart.CALENDAR
            else -> SyncPart.LOOK
        }
    }

    /**
     * A phone never syncs its look and layout: the saved copy is shared with TVs, whose look is
     * different, so [SyncPart.LOOK] is always off here (and not offered in settings).
     */
    fun isOn(part: SyncPart): Boolean = part != SyncPart.LOOK && store.getBoolean(KEY + part.name, part.defaultOn)

    fun isOff(part: SyncPart): Boolean = !isOn(part)

    private fun keyIsOn(key: String): Boolean = partOf(key)?.let { isOn(it) } ?: true

    /**
     * Turns a part on or off on this device. A part just turned on takes the saved copy's values
     * for what the copy has (see [freshen]), so this device doesn't overwrite what other devices
     * changed meanwhile.
     */
    fun set(part: SyncPart, on: Boolean) {
        if (part == SyncPart.LOOK || isOn(part) == on) return
        store.putBoolean(KEY + part.name, on)
        store.putBoolean(FRESH + part.name, on)
        _revision.value++
    }

    /** [data] without the parts that are off. */
    fun strip(data: JsonObject): JsonObject {
        if (SyncPart.entries.all { isOn(it) }) return data
        return JsonObject(data.filterKeys { keyIsOn(it) })
    }

    /** [view] plus the entries of the parts that are off, taken from [from]. */
    fun keep(view: JsonObject, from: JsonObject): JsonObject {
        if (SyncPart.entries.all { isOn(it) }) return view
        val out = view.toMutableMap()
        from.forEach { (k, v) -> if (!keyIsOn(k)) out[k] = v }
        return JsonObject(out)
    }

    /**
     * [local] where the saved copy's value wins for parts switched on since the last sync (the
     * lists of playlists and guides are left alone: they combine source by source anyway).
     */
    fun freshen(local: JsonObject, remote: JsonObject): JsonObject {
        val fresh = SyncPart.entries.filter { isOn(it) && store.getBoolean(FRESH + it.name, false) }
        if (fresh.isEmpty()) return local
        val out = local.toMutableMap()
        local.keys.forEach { k ->
            val base = k.replace(PROFILE_SUFFIX, "")
            if (base == LiveTvKeys.playlists || base == LiveTvKeys.epgs) return@forEach
            if (partOf(k)?.let { it in fresh } == true) remote[k]?.let { out[k] = it }
        }
        return JsonObject(out)
    }

    /** Called after a sync round finished: the parts switched on are now part of the agreed copy. */
    fun clearFresh() {
        SyncPart.entries.forEach { if (store.getBoolean(FRESH + it.name, false)) store.putBoolean(FRESH + it.name, false) }
    }

    private const val KEY = "sync_part_"
    private const val FRESH = "sync_part_fresh_"
}
