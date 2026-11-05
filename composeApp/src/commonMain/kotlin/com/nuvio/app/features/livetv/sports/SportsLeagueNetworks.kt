package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.sports.SportsCatalog.hasWord

/**
 * A league's (or a sport's) own channel: NFL Network, NBA TV, Golf Channel… [words] are what its
 * name says (lowercase, as [SportsCatalog.norm] writes them); a name with any of [not] is another
 * channel (RedZone isn't NFL Network).
 */
internal class LeagueNetwork(
    val id: String,
    val sport: String,
    val name: String,
    val mark: String,
    val color: Long,
    val words: List<String>,
    val not: List<String> = emptyList()
)

/** A network on the page: the channel it plays on now, and the other copies of it you have. */
internal class NetworkTile(
    val network: LeagueNetwork,
    val lead: LiveChannel?,
    val others: List<LiveChannel>,
    val picked: Boolean
)

internal object LeagueNetworks {
    /** In page order: sports in this order, a sport's networks in this order. */
    val all: List<LeagueNetwork> = listOf(
        LeagueNetwork("nfl", "FOOTBALL", "NFL Network", "NFL", 0xFF1C4F9C, listOf("nfl network"), listOf("redzone", "red zone")),
        LeagueNetwork("redzone", "FOOTBALL", "NFL RedZone", "RZ", 0xFFA8322D, listOf("redzone", "red zone")),
        LeagueNetwork("nbatv", "BASKETBALL", "NBA TV", "NBA", 0xFF8A3B1F, listOf("nba tv"), listOf("canada")),
        LeagueNetwork("nbatvca", "BASKETBALL", "NBA TV Canada", "NBA", 0xFF5A3A8F, listOf("nba tv canada")),
        LeagueNetwork("mlb", "BASEBALL", "MLB Network", "MLB", 0xFF1D6B5A, listOf("mlb network"), listOf("strike zone")),
        LeagueNetwork("strikezone", "BASEBALL", "MLB Strike Zone", "SZ", 0xFF4B5870, listOf("strike zone")),
        LeagueNetwork("nhl", "HOCKEY", "NHL Network", "NHL", 0xFF2F5D8A, listOf("nhl network")),
        LeagueNetwork("golf", "GOLF", "Golf Channel", "GC", 0xFF2E7D4F, listOf("golf channel")),
        LeagueNetwork("tennis", "TENNIS", "Tennis Channel", "TC", 0xFF7A8A22, listOf("tennis channel")),
        LeagueNetwork("laliga", "SOCCER", "LaLiga TV", "LL", 0xFFB5372E, listOf("laliga tv", "la liga tv")),
        LeagueNetwork("ligue1", "SOCCER", "Ligue 1+", "L1", 0xFF2A3F8F, listOf("ligue 1 plus"))
    )

    /** Names that are a replay or an archive of the channel, not the channel. */
    private val NOT_THE_CHANNEL = listOf("replay", "replays", "24 7", "classic", "vault", "encore", "highlights")

    /**
     * Which of [channels] are each network: by the channel's name only (never the guide), so this
     * is cheap and only needs doing again when the channel list changes.
     */
    fun find(channels: List<LiveChannel>): Map<String, List<LiveChannel>> {
        val out = LinkedHashMap<String, MutableList<LiveChannel>>()
        for (ch in channels) {
            if (SportsMatcher.isBanner(ch.name)) continue
            val n = SportsCatalog.norm(ch.name.replace("+", " plus "))
            for (net in all) {
                if (net.words.none { hasWord(n, it) }) continue
                if (net.not.any { hasWord(n, it) } || NOT_THE_CHANNEL.any { hasWord(n, it) }) continue
                out.getOrPut(net.id) { ArrayList() } += ch
            }
        }
        return out
    }

    /**
     * Every network as a tile. The channel you picked leads; otherwise the best of what was found
     * (your region and groups first, then the better picture). "Channels from" narrows the copies
     * when that leaves something.
     */
    fun tiles(found: Map<String, List<LiveChannel>>, settings: SportsSettings, byKey: Map<String, LiveChannel>): List<NetworkTile> {
        val rank = SportsMatcher.preferenceRank(settings)
        return all.map { net ->
            var list = found[net.id].orEmpty()
            if (settings.channelRegion.isNotBlank()) {
                val inRegion = list.filter { ChannelRegions.includes(it, settings.channelRegion) }
                if (inRegion.isNotEmpty()) list = inRegion
            }
            val sorted = list.sortedWith(compareByDescending<LiveChannel> { rank(it) }.thenByDescending { quality(it) })
            val pick = settings.leagueNetworkChannels[net.id]?.let { byKey[it] }
            val lead = pick ?: sorted.firstOrNull()
            NetworkTile(net, lead, sorted.filter { it.key != lead?.key }, pick != null)
        }
    }

    private fun quality(ch: LiveChannel): Int {
        val n = SportsCatalog.norm(ch.name)
        return when {
            hasWord(n, "4k") || hasWord(n, "uhd") -> 4
            hasWord(n, "fhd") || hasWord(n, "1080") || hasWord(n, "1080p") -> 3
            hasWord(n, "sd") || hasWord(n, "480") || hasWord(n, "576") -> 1
            else -> 2
        }
    }
}
