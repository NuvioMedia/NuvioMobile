package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.sports.SportsMatcher
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Change logo: the logos worth offering for a channel, closest first. The same channel in your
 * other playlists, your guides, the two logo libraries and, for 24/7 channels or a search,
 * TMDB's show and movie logos.
 */
internal object LogoFinder {
    private val TWENTY_FOUR_SEVEN = Regex("""(?i)\b24\s*[/\\|\-x]?\s*7\b""")
    private val SPACES = Regex("""\s+""")

    /** The sources that are turned off in Change logo (saved with the settings). */
    fun sourcesOff(raw: String): Set<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * [query]: what was typed in Search, instead of the channel's own name. [withLibrary]: also
     * the libraries and TMDB (their lists are fetched the first time), so your own playlists and
     * guides can be shown straight away without waiting for them.
     */
    suspend fun choices(channel: LiveChannel, query: String, withLibrary: Boolean): List<LogoChoice> = withContext(Dispatchers.Default) {
        // Names without tags ("US: ESPN ᴿᴬᵂ" is ESPN), so they match the libraries' names.
        val clean: (String) -> String = { ChannelNameCleaner.clean(it) }
        val norm: (String) -> String = { LiveTvRepository.normalize(clean(it)) }
        val off = sourcesOff(LiveTvPreferences.settings.value.logoSourcesOff)
        val allChannels = LiveTvRepository.channels.value
        val original = allChannels.firstOrNull { it.key == channel.key }?.name
        val targets = listOfNotNull(
            norm(channel.name),
            LiveTvRepository.normalize(channel.name),
            channel.tvgName?.let { norm(it) },
            channel.tvgId?.let { LiveTvRepository.idName(it) },
            original?.let { norm(it) },
        ).filter { it.length >= 2 }.distinct()
        val words = clean(query).lowercase().split(SPACES).map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }

        fun score(key: String): Int {
            if (key.isEmpty()) return 0
            if (words.isNotEmpty()) return if (words.all { key.contains(it) }) 50 + (if (key in targets) 50 else 0) - (key.length / 8) else 0
            var best = 0
            for (t in targets) {
                val sc = when {
                    key == t -> 100
                    (key.startsWith(t) || t.startsWith(key)) && minOf(key.length, t.length) >= 3 ->
                        70 - abs(key.length - t.length).coerceAtMost(30)
                    t.length >= 4 && key.contains(t) -> 35
                    else -> 0
                }
                if (sc > best) best = sc
            }
            return best
        }

        val out = ArrayList<LogoChoice>()
        val seen = HashSet<String>()
        fun add(url: String?, name: String, from: String, group: Int, sc: Int) {
            val u = url?.trim()
            if (u.isNullOrEmpty() || sc <= 0 || !seen.add(u)) return
            out += LogoChoice(u, name, from, group, sc)
        }

        // Your other playlists (and other copies of this channel in the same one).
        if ("playlists" !in off) allChannels.asSequence()
            .filter { it.key != channel.key && !it.logo.isNullOrBlank() }
            .map { it to maxOf(score(norm(it.name)), it.tvgName?.let { n -> score(norm(n)) } ?: 0) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(40)
            .forEach { (c, sc) -> add(c.logo, c.name, c.sourceName, LogoChoice.PLAYLISTS, sc) }

        // Your guides.
        if ("guides" !in off) LiveTvRepository.epgSources.value.forEach { src ->
            src.channels.asSequence()
                .filter { !it.icon.isNullOrBlank() }
                .map { e -> e to maxOf(score(LiveTvRepository.idName(e.id)), e.names.maxOfOrNull { score(norm(it)) } ?: 0) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(25)
                .forEach { (e, sc) -> add(e.icon, e.displayName, src.name, LogoChoice.GUIDES, sc) }
        }

        // A 24/7 channel (or a search): the show's or film's own title logos from TMDB, first.
        val is247 = SportsMatcher.is247(channel)
        if (withLibrary && "tmdb" !in off && (is247 || words.isNotEmpty())) {
            val name = clean(query).ifBlank { clean(channel.name).replace(TWENTY_FOUR_SEVEN, " ").replace(SPACES, " ").trim() }
            runCatching { TmdbLogos.find(name) }.getOrDefault(emptyList())
                .forEach { add(it.url, it.name, it.from, it.group, it.score) }
        }

        // The libraries.
        if (withLibrary && !("tv-logos" in off && "iptv-org" in off)) {
            LogoLibrary.entries(skip = off).asSequence()
                .map { it to score(it.key) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<LogoLibrary.Entry, Int>> { it.second }.thenBy { if (it.first.from == "tv-logos") 0 else 1 })
                .take(80)
                .forEach { (e, sc) -> add(e.url, e.label, e.from, LogoChoice.LIBRARY, sc) }
        }

        // The logo libraries first (clean, consistent logos), TMDB's title logos leading for 24/7
        // channels; the logos your own playlists and guides already use always at the bottom.
        val order = if (is247) listOf(LogoChoice.TMDB, LogoChoice.LIBRARY, LogoChoice.PLAYLISTS, LogoChoice.GUIDES)
        else listOf(LogoChoice.LIBRARY, LogoChoice.TMDB, LogoChoice.PLAYLISTS, LogoChoice.GUIDES)
        out.sortedWith(compareBy<LogoChoice>({ order.indexOf(it.group) }, { -it.score }))
    }
}
