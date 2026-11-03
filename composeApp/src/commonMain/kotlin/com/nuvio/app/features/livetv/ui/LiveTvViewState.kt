package com.nuvio.app.features.livetv.ui

import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.model.ChannelGroup
import com.nuvio.app.features.livetv.model.ChannelSort
import com.nuvio.app.features.livetv.model.CustomGroup
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.model.LiveUserState
import com.nuvio.app.features.livetv.parental.ParentalControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One group in the channel list, with its channels in display order. */
data class LiveGroupSection(
    val group: ChannelGroup,
    val channels: List<LiveChannel>,
)

/** The channel list as the Live TV tab shows it: groups in order, favorites first. */
data class LiveTvListState(
    val sections: List<LiveGroupSection> = emptyList(),
    /** Every channel that isn't hidden, sorted, for search and the guide. */
    val visible: List<LiveChannel> = emptyList(),
    val favoriteKeys: Set<String> = emptySet(),
    val customGroups: List<CustomGroup> = emptyList(),
    val hasSources: Boolean = false,
    /** Groups that need the parental PIN right now (their channels are left out of every list). */
    val lockedGroupIds: Set<String> = emptySet(),
)

/**
 * Builds the grouped channel list from the repository's channels and your preferences, the TV
 * app's rules: hidden channels and groups left out, your own groups first, then the playlist's
 * groups in your order (or by name), channels sorted as the Channel Sort setting says, with
 * your "Reorder channels" order on top.
 */
internal object LiveTvViewState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val list: StateFlow<LiveTvListState> = combine(
        LiveTvRepository.displayChannels,
        LiveTvPreferences.userState,
        LiveTvPreferences.settings,
        LiveTvPreferences.playlists,
        ParentalControls.unlocked,
    ) { channels, user, s, playlists, unlocked ->
        build(channels, user, s, playlists.any { it.liveEnabled }, unlocked)
    }.stateIn(scope, SharingStarted.Eagerly, LiveTvListState())

    fun build(channels: List<LiveChannel>, user: LiveUserState, s: LiveTvSettings, hasSources: Boolean, unlocked: Set<String> = emptySet()): LiveTvListState {
        val shown = channels.filter { it.key !in user.hiddenChannels && it.groupId !in user.hiddenGroups }
        // Parental controls: channels in locked groups stay out of every list until unlocked.
        val parental = ParentalControls.enabled(s)
        fun locked(id: String, name: String): Boolean =
            parental && id !in unlocked && (id in user.lockedGroups || (s.lockAdultContent && ParentalControls.isAdult(name)))
        val lockedIds = HashSet<String>()
        val lockedTitles = LinkedHashMap<String, String>()
        shown.distinctBy { it.groupId }.forEach { c -> if (locked(c.groupId, c.group)) { lockedIds += c.groupId; lockedTitles[c.groupId] = c.group } }
        user.customGroups.forEach { g -> if (g.id !in user.hiddenGroups && locked(g.id, g.name)) { lockedIds += g.id; lockedTitles[g.id] = g.name } }
        val visible = shown.filter { it.groupId !in lockedIds }
        val byKey = visible.associateBy { it.key }
        // What your own groups can show: also channels whose playlist group is hidden (hiding a
        // group only hides that group). Channels hidden one by one, or in a locked group, stay out.
        val customPool = run {
            val hiddenLocked = HashSet<String>()
            channels.filter { it.groupId in user.hiddenGroups }.distinctBy { it.groupId }
                .forEach { c -> if (locked(c.groupId, c.group)) hiddenLocked += c.groupId }
            byKey + channels
                .filter { it.groupId in user.hiddenGroups && it.key !in user.hiddenChannels && it.groupId !in hiddenLocked }
                .associateBy { it.key }
        }
        val favorites = user.favorites.mapNotNull { byKey[it] }
        // Adult channels never show in Recently watched.
        val recent = user.recent.mapNotNull { byKey[it] }.filterNot { ParentalControls.isAdultChannel(it) }

        fun sorted(list: List<LiveChannel>): List<LiveChannel> = when (s.channelSort) {
            ChannelSort.PLAYLIST -> list
            ChannelSort.NUMBER -> list.sortedBy { it.number }
            ChannelSort.NAME -> list.sortedBy { it.name.lowercase() }
        }

        fun ordered(groupId: String, list: List<LiveChannel>): List<LiveChannel> {
            val order = user.channelOrder[groupId] ?: return sorted(list)
            val here = list.associateBy { it.key }
            val placed = order.mapNotNull { here[it] }
            val placedKeys = placed.mapTo(HashSet()) { it.key }
            return placed + sorted(list.filter { it.key !in placedKeys })
        }

        val sections = ArrayList<LiveGroupSection>()
        if (s.showFavoritesGroup && favorites.isNotEmpty()) {
            sections += LiveGroupSection(ChannelGroup(ChannelGroup.FAVORITES, "Favorites", favorites.size, special = true), sorted(favorites))
        }
        if (s.showRecentGroup && recent.isNotEmpty()) {
            sections += LiveGroupSection(ChannelGroup(ChannelGroup.RECENT, "Recently watched", recent.size, special = true), recent)
        }

        val regular = ArrayList<LiveGroupSection>()
        val custom = user.customGroups.filter { it.id !in user.hiddenGroups }
        custom.forEach { g ->
            if (g.id in lockedIds) { regular += LiveGroupSection(ChannelGroup(g.id, g.name, 0, locked = true), emptyList()); return@forEach }
            val list = sorted(g.channelKeys.mapNotNull { customPool[it] })
            regular += LiveGroupSection(ChannelGroup(g.id, g.name, list.size), list)
        }
        // Locked playlist groups keep their place in the list, with nothing inside until unlocked.
        val lockedSource = HashMap<String, String>()
        shown.forEach { c -> if (c.groupId in lockedIds && c.groupId !in lockedSource) lockedSource[c.groupId] = c.sourceId }
        lockedTitles.forEach { (id, title) ->
            if (!id.startsWith(ChannelGroup.CUSTOM_PREFIX)) regular += LiveGroupSection(ChannelGroup(id, user.groupNames[id] ?: title, 0, sourceId = lockedSource[id], locked = true), emptyList())
        }
        val playlistGroups = LinkedHashMap<String, Pair<String, ArrayList<LiveChannel>>>()
        visible.forEach { c ->
            val cur = playlistGroups.getOrPut(c.groupId) { c.group to ArrayList() }
            cur.second += c
        }
        val groupSource = HashMap<String, String>()
        visible.forEach { c -> if (c.groupId !in groupSource) groupSource[c.groupId] = c.sourceId }
        playlistGroups.forEach { (id, v) ->
            val own = v.second
            val ownKeys = own.mapTo(HashSet()) { it.key }
            val copies = user.channelCopies[id].orEmpty().mapNotNull { byKey[it] }.filter { it.key !in ownKeys }
            val list = ordered(id, own + copies).distinctBy { it.key }
            regular += LiveGroupSection(ChannelGroup(id, v.first, list.size, sourceId = groupSource[id]), list)
        }
        val orderIndex = user.groupOrder.withIndex().associate { (i, id) -> id to i }
        val playlistIndex = HashMap<String, Int>().also { m -> visible.forEach { c -> if (c.sourceId !in m) m[c.sourceId] = m.size } }
        val orderedRegular = when (s.groupSort) {
            "playlist" -> regular
            "name" -> regular.sortedWith(
                compareBy<LiveGroupSection>(
                    { g -> if (g.group.sourceId == null) -1 else if (s.groupPlaylistHeadings) playlistIndex[g.group.sourceId] ?: Int.MAX_VALUE else 0 },
                    { g -> g.group.title.lowercase() }
                )
            )
            // Your order (Reorder groups). A group you haven't placed yet (e.g. one just added in
            // the playlist) goes right after the group above it in the playlist.
            else -> if (orderIndex.isEmpty()) regular else {
                val result = regular.filter { it.group.id in orderIndex }.sortedBy { orderIndex[it.group.id] }.toMutableList()
                regular.forEachIndexed { i, g ->
                    if (g.group.id !in orderIndex) {
                        val above = if (i > 0) result.indexOfFirst { it.group.id == regular[i - 1].group.id } else -1
                        result.add(above + 1, g)
                    }
                }
                result
            }
        }
        sections += orderedRegular
        val numbered = if (s.sequentialNumbers) {
            sections.map { sec -> sec.copy(channels = sec.channels.mapIndexed { i, c -> c.copy(number = i + 1) }) }
        } else sections
        return LiveTvListState(
            sections = numbered,
            // Adult channels stay out of search while adult content is locked.
            visible = sorted(if (parental && s.lockAdultContent) visible.filterNot { ParentalControls.isAdultChannel(it) } else visible),
            favoriteKeys = user.favorites.toHashSet(),
            customGroups = custom,
            hasSources = hasSources,
            lockedGroupIds = lockedIds,
        )
    }

    /** Channels whose name or number matches [query], from the visible list. */
    fun search(visible: List<LiveChannel>, query: String): List<LiveChannel> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return visible.filter { it.name.contains(q, ignoreCase = true) || it.number.toString() == q }
    }
}
