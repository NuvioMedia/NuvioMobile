package com.nuvio.app.features.usenet

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class NewznabProtocolTest {
    private val indexer = UsenetIndexer(id = "one", name = "Indexer", apiUrl = "https://indexer.test/api?custom=value", apiKey = "secret &+key")

    @Test fun `season packs are told from episodes`() {
        assertEquals(listOf(1), NewznabProtocol.packSeasons("Slow.Horses.S01.1080p.ATVP.WEB-DL-NTb"))
        assertEquals(listOf(2), NewznabProtocol.packSeasons("Show Season 2 Complete 2160p"))
        assertEquals(listOf(1, 2, 3), NewznabProtocol.packSeasons("Show.S01-S03.1080p"))
        assertEquals(listOf(1, 2, 3), NewznabProtocol.packSeasons("Show.S01-03.1080p"))
        assertEquals(listOf(1, 2, 3), NewznabProtocol.packSeasons("Show Season 1-3 1080p"))
        assertEquals(listOf(1, 3), NewznabProtocol.packSeasons("Show.S01.S03.1080p"))
        assertEquals(listOf(2), NewznabProtocol.packSeasons("Show.S02-720p"))
        assertTrue(NewznabProtocol.packSeasons("Slow.Horses.S01E03.1080p").isEmpty())
        assertTrue(NewznabProtocol.packSeasons("Show.1x03.720p").isEmpty())
        assertTrue(NewznabProtocol.packSeasons("Movie.2020.2160p").isEmpty())
    }

    @Test fun `a single-season pack is filtered and sorted by its size per episode`() {
        val gb = 1L shl 30
        val pack = UsenetRelease("Show.S01.1080p-GRP", "https://a/pack", size = 9 * gb, indexerId = "one")
        val episode = UsenetRelease("Show.S01E02.1080p-GRP", "https://a/ep", size = 2 * gb, indexerId = "one")
        val config = UsenetSourceConfiguration(indexers = listOf(indexer), maxSizeGb = 5, sort = UsenetSort.LARGEST)
        // Without an episode count the pack is its whole size: over the limit.
        assertEquals(listOf(episode), NewznabProtocol.arrange(listOf(pack, episode), config))
        // Nine episodes: 1 GB each, under the limit and smaller than the 2 GB episode.
        assertEquals(listOf(episode, pack), NewznabProtocol.arrange(listOf(pack, episode), config, seasonEpisodes = mapOf(1 to 9)))
        assertEquals(listOf(pack, episode),
            NewznabProtocol.arrange(listOf(pack, episode), config.copy(sort = UsenetSort.SMALLEST), seasonEpisodes = mapOf(1 to 9)))
    }

    @Test fun `a pack of several seasons is found for each of them and sized by all their episodes`() {
        val gb = 1L shl 30
        val pack = UsenetRelease("Show.S01-S03.1080p-GRP", "https://a/pack", size = 34 * gb, indexerId = "one")
        assertEquals(gb, pack.episodeSize(mapOf(1 to 10, 2 to 12, 3 to 12)))
        // A season without a count leaves the whole size.
        assertEquals(34 * gb, pack.episodeSize(mapOf(1 to 10, 2 to 12)))
        assertEquals(34 * gb, pack.episodeSize(null))

        fun holds(release: String, season: Int) = NewznabProtocol.matches(UsenetRelease(release, "url"),
            UsenetSearchRequest(imdbId = "tt1", series = true, season = season, episode = 1), idSearch = true, requireNumbering = true)
        assertTrue(holds(pack.title, 2))
        assertFalse(holds(pack.title, 4))
        assertTrue(holds("Show.S01-10.1080p", 9))
        assertTrue(holds("Show Season 1-10 1080p", 9))
        // Listed seasons are no range, and a resolution after a hyphen is no season.
        assertTrue(holds("Show.S01.S03.1080p", 3))
        assertFalse(holds("Show.S01.S03.1080p", 2))
        assertTrue(holds("Show.S02-720p", 2))
        assertFalse(holds("Show.S02-720p", 720))
    }

    @Test fun `the result limit never cuts a release ranked first`() {
        val releases = (1..5).map { UsenetRelease("Movie.2020.2160p-G$it", "https://a/$it", size = it.toLong(), indexerId = "one") } +
            UsenetRelease("Movie.2020.720p-OK", "https://a/ok", indexerId = "one")
        val config = UsenetSourceConfiguration(indexers = listOf(indexer), maxResults = 3)
        // Best resolution first would cut the 720p release; ranked first, it stays and leads.
        val kept = NewznabProtocol.arrange(releases, config) { if (it.title.endsWith("OK")) 0 else 1 }
        assertEquals(listOf("Movie.2020.720p-OK", "Movie.2020.2160p-G5", "Movie.2020.2160p-G4"), kept.map { it.title })
    }

    @Test fun `the result limit lists up to 500 or every result, read from more than are listed`() {
        val unlimited = UsenetSourceConfiguration(indexers = listOf(indexer), maxResults = UsenetSourceConfiguration.UNLIMITED_RESULTS,
            indexerResultsLimit = UsenetSourceConfiguration.AUTO_INDEXER_RESULTS)
        assertEquals(200, unlimited.copy(maxResults = 50).indexerResults)
        assertEquals(500, unlimited.copy(maxResults = 500).indexerResults)
        assertEquals(1000, unlimited.indexerResults)
        val releases = (1..600).map { UsenetRelease("Movie.2020.1080p-G$it", "https://a/$it", size = it.toLong(), indexerId = "one") }
        assertEquals(600, NewznabProtocol.arrange(releases, unlimited).size)
        assertEquals(500, NewznabProtocol.arrange(releases, unlimited.copy(maxResults = 500)).size)
        unlimited.validate()
        unlimited.copy(maxResults = 500).validate()
        assertThrows(IllegalArgumentException::class.java) { unlimited.copy(maxResults = 501).validate() }
    }

    @Test fun `provider credentials are encoded once and IPv6 is preserved`() {
        val provider = UsenetProvider(name = "News", host = "2001:db8::1", username = "a@b", password = "a:+ %/", connections = 30)
        provider.validate()
        val uri = URI(provider.serverUrl())
        assertEquals("nntps", uri.scheme)
        assertEquals("a@b:a:+ %/", uri.userInfo)
        assertEquals(563, uri.port)
        assertEquals("/30", uri.path)
        assertEquals("priority=1", uri.query)
        assertFalse(provider.toString().contains(provider.password))
    }

    @Test fun `hierarchy orders sources by priority and keeps user order within a priority`() {
        val providers = listOf(3, 1, 2, 1).mapIndexed { i, p -> UsenetProvider(id = "p$i", name = "P", host = "h", priority = p) }
        val indexers = listOf(2, 1).mapIndexed { i, p -> indexer.copy(id = "i$i", priority = p) }
        val normalized = UsenetSourceConfiguration(providers = providers, indexers = indexers).normalized()
        assertEquals(listOf("p1", "p3", "p2", "p0"), normalized.providers.map { it.id })
        assertEquals(listOf("i1", "i0"), normalized.indexers.map { it.id })
        assertThrows(IllegalArgumentException::class.java) { providers[0].copy(priority = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { indexer.copy(priority = 6).validate() }
    }

    @Test fun `episode id search keeps season zero and encoded keys`() {
        val url = NewznabProtocol.searchUrl(indexer, UsenetSearchRequest(imdbId = "tt123", series = true, season = 0, episode = 2), NewznabCapabilities())!!
        assertEquals("123", url.queryParameter("imdbid"))
        assertEquals("0", url.queryParameter("season"))
        assertEquals("2", url.queryParameter("ep"))
        assertEquals("tvsearch", url.queryParameter("t"))
        assertEquals("secret &+key", url.queryParameter("apikey"))
        assertEquals("value", url.queryParameter("custom"))
        assertNull(NewznabProtocol.searchUrl(indexer, UsenetSearchRequest(imdbId = "tt123", series = true), NewznabCapabilities()))
    }

    @Test fun `tv searches use tvdb when imdb is unsupported and season searches drop only the episode`() {
        val caps = NewznabCapabilities(tvParams = setOf("q", "tvdbid", "season", "ep"))
        val episode = UsenetSearchRequest(imdbId = "tt1", tvdbId = "121361", series = true, season = 1, episode = 2)
        val url = NewznabProtocol.searchUrl(indexer, episode, caps)!!
        assertEquals("121361", url.queryParameter("tvdbid"))
        assertNull(url.queryParameter("imdbid"))
        val season = NewznabProtocol.searchUrl(indexer, episode, caps, wholeSeason = true)!!
        assertEquals("121361", season.queryParameter("tvdbid"))
        assertEquals("1", season.queryParameter("season"))
        assertNull(season.queryParameter("ep"))
        // Season-wide text searches and movies are never shared.
        assertNull(NewznabProtocol.searchUrl(indexer, episode.copy(tvdbId = null, title = "A Show"), caps, wholeSeason = true))
        assertNull(NewznabProtocol.searchUrl(indexer, UsenetSearchRequest(imdbId = "tt1"), NewznabCapabilities(), wholeSeason = true))
        assertNull(NewznabProtocol.searchUrl(indexer, UsenetSearchRequest(tvdbId = "1"), NewznabCapabilities(movieParams = setOf("tvdbid"))))
        val obfuscated = UsenetRelease("a8f3k2b9", "url")
        assertTrue(NewznabProtocol.matches(obfuscated, episode, idSearch = true))
        assertFalse(NewznabProtocol.matches(obfuscated, episode, idSearch = true, requireNumbering = true))
    }

    @Test fun `episode names that tell the time number no other episode`() {
        fun names(release: String, episode: Int) = NewznabProtocol.matches(UsenetRelease(release, "url"),
            UsenetSearchRequest(imdbId = "tt1", series = true, season = 1, episode = episode), idSearch = true)
        val e02 = "The.Pitt.2025-S01E02-8-00.A.M.WEB-DL-2160p.DV.HDR10.EAC3.Atmos5.1.EN+FR.x265.-FRESH"
        val e08 = "The.Pitt.2025-S01E08-2-00.P.M.WEB-DL-2160p.DV.HDR10.EAC3.Atmos5.1.EN+FR.x265.-FRESH"
        assertTrue(names(e02, 2))
        assertFalse(names(e02, 5)) // Not E02 to E08.
        assertTrue(names(e08, 8))
        assertFalse(names(e08, 2)) // Not E08 to E02.
        val e04 = "The.Pitt.2025-S01E04-10-00.A.M.WEB-DL-2160p.DV.HDR10.EAC3.Atmos5.1.EN+FR.x265.-FRESH"
        assertTrue(names(e04, 4))
        assertFalse(names(e04, 5)) // Not E04 to E10: ten o'clock.
        assertFalse(names(e04, 10))
        // Ranges and lists still span and list their episodes.
        assertTrue(names("Show.S01E01-03.1080p", 2))
        assertTrue(names("Show.S01E01-E03.1080p", 2))
        assertTrue(names("Show.S01E9-10.1080p", 10))
        assertTrue(names("Show.S01E01E02.1080p", 2))
        assertFalse(names("Show.S01E01E03.1080p", 2))
        // These times are an episode's name: its upload's other episodes still share its key.
        assertEquals(NewznabProtocol.episodeTemplate(e02.lowercase())?.first,
            NewznabProtocol.episodeTemplate(e08.lowercase())?.first)
        assertEquals(8, NewznabProtocol.episodeTemplate(e08)?.second)
    }

    @Test fun `capabilities select tmdb searches and respect disabled search types`() {
        val caps = NewznabProtocol.capabilities("""<caps><limits max="50"/><searching><movie-search available="yes" supportedParams="q,tmdbid,year"/><tv-search available="no"/><search available="yes"/></searching></caps>""")
        val url = NewznabProtocol.searchUrl(indexer, UsenetSearchRequest(imdbId = "tt1", tmdbId = "20"), caps)!!
        assertEquals("20", url.queryParameter("tmdbid"))
        assertEquals("50", url.queryParameter("limit"))
        assertNull(NewznabProtocol.searchUrl(indexer, UsenetSearchRequest(imdbId = "tt1", series = true, season = 1, episode = 1), caps))
        val fallback = NewznabProtocol.searchUrl(indexer, UsenetSearchRequest(title = "A Show", series = true, season = 2, episode = 3), caps)!!
        assertEquals("search", fallback.queryParameter("t"))
        assertEquals("A Show S02E03", fallback.queryParameter("q"))
        val episodeFallback = NewznabProtocol.searchUrl(indexer,
            UsenetSearchRequest(imdbId = "tt1", title = "A Show", series = true, season = 2, episode = 3),
            caps.copy(tvParams = setOf("imdbid", "q")))!!
        assertNull(episodeFallback.queryParameter("imdbid"))
        assertEquals("A Show S02E03", episodeFallback.queryParameter("q"))
    }

    @Test fun `namespaced rss yields playable releases and rejects non nzb enclosures`() {
        val page = NewznabProtocol.releases("""
            <rss xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/"><channel>
            <newznab:response offset="0" total="8"/>
            <item><title>Movie.2020.2160p</title><pubDate>Fri, 09 Oct 2026 12:00:00 +0000</pubDate>
            <enclosure type="application/x-nzb" url="/get?id=1&amp;apikey=secret" length="1000"/>
            <newznab:attr name="size" value="2000"/><newznab:attr name="password" value="1"/></item>
            </channel></rss>
        """.trimIndent(), indexer)
        val release = page.releases.single()
        assertEquals(8, page.total)
        assertEquals(2000L, release.size)
        assertEquals(2160, release.resolution)
        assertTrue(release.passworded)
        assertTrue(release.publishedAt > 0)
        assertEquals("https://indexer.test/get?id=1&apikey=secret", release.nzbUrl)
    }

    @Test fun `repeated releases keep the highest priority copy that passes the filters`() {
        val low = indexer.copy(id = "low", priority = 2)
        val config = UsenetSourceConfiguration(indexers = listOf(indexer, low))
        val preferred = UsenetRelease("Show.S01E01.1080p-GRP", "https://a/1", indexerId = indexer.id)
        val repeat = UsenetRelease("show s01e01 1080p grp", "https://b/1", indexerId = low.id)
        val other = UsenetRelease("Show.S01E01.720p-GRP", "https://b/2", indexerId = low.id)
        assertEquals(listOf(preferred, other), NewznabProtocol.withoutDuplicates(listOf(repeat, preferred, other), config))
        // A passworded preferred copy is filtered out later; the repeat must survive instead.
        assertEquals(listOf(repeat, other),
            NewznabProtocol.withoutDuplicates(listOf(preferred.copy(passworded = true), repeat, other), config))
    }

    @Test fun `sort filters duplicates and is stable regardless of response order`() {
        val now = 1800000000000L
        val config = UsenetSourceConfiguration(indexers = listOf(indexer), maxResults = 2, minResolution = 720, maxSizeGb = 10, maxAgeDays = 30)
        val releases = listOf(
            UsenetRelease("Good.1080p", "https://test/1", 4000, now, "one"),
            UsenetRelease("Good.2160p", "https://test/2", 5000, now, "one"),
            UsenetRelease("Bad.HDCAM.1080p", "https://test/3", 6000, now),
            UsenetRelease("Old.2160p", "https://test/4", 4000, now - 40L * 86400000),
            UsenetRelease("Huge.2160p", "https://test/5", 11L * 1024 * 1024 * 1024, now),
            UsenetRelease("Locked.2160p", "https://test/6", 6000, now, passworded = true)
        )
        val sorted = NewznabProtocol.arrange(releases + releases.first(), config, now)
        assertEquals(listOf("Good.2160p", "Good.1080p"), sorted.map { it.title })
        assertEquals(sorted, NewznabProtocol.arrange(releases.reversed(), config, now))
        assertEquals("Good.1080p", NewznabProtocol.arrange(releases, config.copy(sort = UsenetSort.SMALLEST), now).first().title)
    }

    @Test fun `id search results whose name gives another year are another film`() {
        val stone = UsenetSearchRequest(imdbId = "tt0241527", title = "Harry Potter and the Sorcerer's Stone", year = 2001)
        fun byId(release: String, request: UsenetSearchRequest = stone) =
            NewznabProtocol.matches(UsenetRelease(release, "url"), request, idSearch = true)
        // An indexer filed Deathly Hallows Part 2 under the 2001 film.
        assertFalse(byId("Harry.Potter.und.die.Heiligtuemer.des.Todes.Teil.2.2011.German.DL.2160p.HDR.BluRay.AV1-Bi0hazard"))
        assertTrue(byId("Harry.Potter.and.the.Sorcerers.Stone.2001.2160p.UHD.BluRay.x265"))
        assertTrue(byId("Harry.Potter.und.der.Stein.der.Weisen.2002.German.1080p")) // Off by one.
        assertTrue(byId("d-pate-1080p")) // No year: the ID is trusted.
        assertTrue(byId("Blade.Runner.2049.2017.2160p", UsenetSearchRequest(imdbId = "tt1856101", title = "Blade Runner 2049", year = 2017)))
        assertTrue(byId("Blade.Runner.2049.1080p", UsenetSearchRequest(imdbId = "tt1856101", title = "Blade Runner 2049", year = 2017)))
        // Series carry season years; only movies are checked.
        assertTrue(NewznabProtocol.matches(UsenetRelease("Show.S01E01.1999.1080p", "url"),
            UsenetSearchRequest(imdbId = "tt1", series = true, season = 1, episode = 1, year = 2010), idSearch = true))
    }

    @Test fun `id search results named after another film of the same year are another film`() {
        // Some of the names TMDB lists for the film, in other languages among them.
        val towers = UsenetSearchRequest(imdbId = "tt0167261", title = "The Lord of the Rings: The Two Towers", year = 2002,
            otherTitles = listOf("Der Herr der Ringe - Die zwei Türme", "Il Signore degli Anelli - Le due torri",
                "LOTR2 - The Two Towers", "In de ban van de ring: De twee torens", "ロード・オブ・ザ・リング／二つの塔", "Властелин колец 2"))
        fun kept(movie: UsenetSearchRequest, vararg releases: String) =
            NewznabProtocol.idResultsOf(movie, releases.map { UsenetRelease(it, it) }).map { it.title }
        // An indexer filed The Ring (2002) under the film.
        assertEquals(emptyList<String>(), kept(towers, "The.Ring.2002.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.DTS-HD.MA.5.1-TRiToN",
            "The Ring 2002 1080p PCOK WEB-DL DDP 5 1 H 264-PiRaTeS", "The.Ring.2002.MULTi.COMPLETE.BLURAY-OLDHAM"))
        val named = arrayOf(
            "The.Lord.of.the.Rings.The.Two.Towers.2002.Extended.2160p.UHD.BluRay.x265",
            "The Lord of the Rings-The Two Towers 2002 2160p 35mm HDR v1.1",
            "Lord.of.the.Rings.Two.Towers.2002.1080p.BluRay.x264", // Articles left out.
            "LOTR The Two Towers 2002 1080p BluRay", // Shortened.
            "The Lord of thee Rings The Two Towers 2002 720p", // Misspelled.
            "Der.Herr.der.Ringe.Die.zwei.Tuerme.2002.German.DL.1080p", // Translated.
            "Der Herr der Ringe II Die zwei Tuerme 2002 German 1080p",
            "Il.Signore.degli.Anelli.Le.Due.Torri.2002.iTALiAN.1080p",
            "{ Der.Herr.der.Ringe.die.zwei.Tuerme.2002.German.AC3.DVDRiP.XViD } }ghost-of-usenet.org{",
            "a8f3c1e9d2b7", "d-pate-1080p") // No year, as obfuscated names: the ID is trusted.
        assertEquals(named.toList(), kept(towers, *named))
        // The year still judges a name of the film's.
        assertEquals(emptyList<String>(), kept(towers, "The.Lord.of.the.Rings.The.Two.Towers.2012.1080p"))
        // Unknown names leave the title unjudged.
        assertEquals(listOf("The.Ring.2002.1080p"), kept(towers.copy(otherTitles = null), "The.Ring.2002.1080p"))

        // Posts that lost a title's accented letters, a subtitle after the title, year-like titles.
        val amelie = UsenetSearchRequest(title = "Amélie", originalTitle = "Le Fabuleux Destin d'Amélie Poulain", year = 2001, otherTitles = emptyList())
        assertEquals(listOf("Amlie.2001.DVDRiP.XviD", "Le.Fabuleux.Destin.D.Amelie.Poulain.2001.1080p"),
            kept(amelie, "Amlie.2001.DVDRiP.XviD", "Le.Fabuleux.Destin.D.Amelie.Poulain.2001.1080p", "Soul.Assassin.2001.WEBRip.x264"))
        val starWars = UsenetSearchRequest(title = "Star Wars", year = 1977, otherTitles = emptyList())
        assertEquals(listOf("Star.Wars.Episode.IV.A.New.Hope.1977.1080p"),
            kept(starWars, "Star.Wars.Episode.IV.A.New.Hope.1977.1080p", "Scum.1977.1080p.BluRay.x264-GHOULS"))
        assertEquals(listOf("Blade.Runner.2049.2017.2160p", "1917.2019.1080p"), kept(
            UsenetSearchRequest(title = "Blade Runner 2049", year = 2017, otherTitles = emptyList()), "Blade.Runner.2049.2017.2160p") +
            kept(UsenetSearchRequest(title = "1917", year = 2019, otherTitles = emptyList()), "1917.2019.1080p"))
    }

    @Test fun `full disc releases are recognised and hidden unless allowed`() {
        listOf("The.Drama.2026.COMPLETE.UHD.BLURAY-GRP", "Movie.2020.Complete.Bluray-GRP", "Movie 2020 BD50",
            "Movie.2020.1080p.BDMV.AVC.DTS-HD.MA", "Movie.2020.DVD9-GRP", "Movie.2020.ISO", "[Movie 2020 BD25]",
            "Show.S01.COMPLETE.BLURAY.AVC.DTS-HD.MA.5.1-GRP"
        ).forEach { assertTrue(it, UsenetRelease(it, "url").fullDisc) }
        listOf("The.Drama.2026.2160p.UHD.Remux.HEVC.DoVi.TrueHD.Atmos.7.1-playBD",
            "Movie.2020.COMPLETE.BLURAY.REMUX-GRP", "Show.S01.COMPLETE.BluRay.1080p.x264-GRP", "Movie.2020.BDRip.x265",
            "Movie.2020.Complete.BDRip", "Movie.2020.1080p.BluRay.DTS-HD.MA", "Isolation.2020.1080p", "Show.S01.Complete.DVDRip",
            // DVD5 here is the encode's target size, not a disc.
            "Oppenheimer.2023.BluRay.2160p.HDR.MULTi.5.1.AV1.Opus.DVD5-CAV1aR",
            "Interstellar.2014.Complete.BluRay.1080p.Re-Encode.AVC.DTS-HD.MA.5.1-GRP"
        ).forEach { assertFalse(it, UsenetRelease(it, "url").fullDisc) }
        val releases = listOf(UsenetRelease("Disc.COMPLETE.UHD.BLURAY", "https://test/1"),
            UsenetRelease("Remux.2160p.REMUX", "https://test/2"))
        val config = UsenetSourceConfiguration(indexers = listOf(indexer))
        // Discs show by default; the filter hides them.
        assertEquals(2, NewznabProtocol.arrange(releases, config).size)
        assertEquals(listOf("Remux.2160p.REMUX"),
            NewznabProtocol.arrange(releases, config.copy(excludeFullDisc = true)).map { it.title })
    }

    @Test fun `a whole Blu-ray is labelled by its kind, and nothing else is`() {
        mapOf(
            "Movie.2010.COMPLETE.BLURAY-GRP" to "BLU-RAY",
            "Movie 2010 BD50" to "BLU-RAY",
            "Movie.2019.COMPLETE.UHD.BLURAY-GRP" to "UHD BLU-RAY",
            "Movie.2019.2160p.BDMV.HEVC" to "UHD BLU-RAY",
            "Reservoir.Dogs.1992.1080p.BluRay.DDP5.1.iso" to "BLU-RAY ISO",
            "Die.Hard.1.1988.4K.UHD.Bluray.Disc.ISO.File-Sixty" to "UHD BLU-RAY ISO",
        ).forEach { (title, label) -> assertEquals(title, label, UsenetRelease(title, "url").blurayLabel) }
        // DVDs play no better as discs; encodes and remuxes are single videos.
        listOf("Movie.2005.COMPLETE.DVD-GRP", "Movie.2005.DVD9-GRP", "Movie.2005.DVD9.ISO",
            "Movie.2019.2160p.UHD.BluRay.REMUX.HEVC-GRP", "Movie.2019.1080p.BluRay.x264-GRP"
        ).forEach { assertNull(it, UsenetRelease(it, "url").blurayLabel) }
    }

    @Test fun `entity declarations are rejected without accessing external resources`() {
        assertThrows(IllegalArgumentException::class.java) {
            NewznabProtocol.releases("""<!DOCTYPE rss [<!ENTITY secret SYSTEM "file:///secret">]><rss/>""", indexer)
        }
        val error = assertThrows(IllegalStateException::class.java) {
            NewznabProtocol.capabilities("""<error code="100" description="API key secret &amp; URL"/>""")
        }
        assertEquals("Indexer authentication failed", error.message)
    }

    @Test fun `sort modes distinguish resolution size date and user indexer priority`() {
        val preferred = indexer.copy(id = "preferred")
        val config = UsenetSourceConfiguration(indexers = listOf(preferred, indexer))
        val releases = listOf(
            UsenetRelease("Sharp.2160p", "https://test/sharp", 4000, 1000, indexer.id),
            UsenetRelease("Large.1080p", "https://test/large", 8000, 2000, indexer.id),
            UsenetRelease("Recent.720p", "https://test/recent", 1000, 3000, indexer.id),
            UsenetRelease("Preferred.1080p", "https://test/preferred", 2000, 1500, preferred.id)
        )
        fun first(sort: UsenetSort) = NewznabProtocol.arrange(releases, config.copy(sort = sort)).first().title
        assertEquals("Sharp.2160p", first(UsenetSort.QUALITY))
        assertEquals("Large.1080p", first(UsenetSort.LARGEST))
        assertEquals("Recent.720p", first(UsenetSort.SMALLEST))
        assertEquals("Recent.720p", first(UsenetSort.NEWEST))
        assertEquals("Preferred.1080p", first(UsenetSort.INDEXER))
        assertEquals("Sharp.2160p", NewznabProtocol.arrange(releases,
            config.copy(sort = UsenetSort.INDEXER, indexers = config.indexers.reversed())).first().title)
    }

    @Test fun `titles match release spellings without accents, apostrophes or ampersands`() {
        fun matches(title: String, release: String, year: Int? = null) =
            NewznabProtocol.matches(UsenetRelease(release, "url"), UsenetSearchRequest(title = title, year = year), false)
        assertTrue(matches("Amélie", "Amelie.2001.1080p", 2001))
        assertTrue(matches("Der König der Löwen", "Der.Koenig.der.Loewen.2019.German.1080p", 2019))
        assertTrue(matches("Der König der Löwen", "Der.Konig.der.Lowen.2019.1080p", 2019))
        assertTrue(matches("Marvel's The Avengers", "Marvels.The.Avengers.2012.1080p", 2012))
        assertTrue(matches("Marvel’s The Avengers", "Marvel's.The.Avengers.2012.1080p", 2012))
        assertTrue(matches("Fast & Furious", "Fast.and.Furious.2009.1080p", 2009))
        assertTrue(matches("Fast and Furious", "Fast.&.Furious.2009.1080p", 2009))
        assertTrue(matches("Straße", "Strasse.2020.1080p"))
        assertFalse(matches("Amélie", "Amelia.2001.1080p", 2001))

        val caps = NewznabCapabilities(movieParams = setOf("q"))
        fun query(title: String) = NewznabProtocol.searchUrl(indexer, UsenetSearchRequest(title = title), caps)!!.queryParameter("q")
        assertEquals("Amelie", query("Amélie"))
        assertEquals("Der Koenig der Loewen", query("Der König der Löwen"))
        assertEquals("Marvels The Avengers", query("Marvel's The Avengers"))
        assertEquals("Mission Impossible Fallout", query("Mission: Impossible – Fallout"))
        // Marks belong to the letter outside the Latin script.
        assertEquals("ガンダム", query("ガンダム"))
    }

    @Test fun `titles inside other titles are rejected and original titles are accepted`() {
        fun movie(title: String, release: String, year: Int, original: String? = null) = NewznabProtocol.matches(
            UsenetRelease(release, "url"), UsenetSearchRequest(title = title, originalTitle = original, year = year), false)
        fun episode(title: String, release: String) = NewznabProtocol.matches(UsenetRelease(release, "url"),
            UsenetSearchRequest(title = title, series = true, season = 1, episode = 1), false)

        // Another title that contains the requested one, before or after it.
        assertFalse(movie("Interstellar", "The.Science.of.Interstellar.2014.1080p", 2014))
        assertTrue(movie("Interstellar", "Interstellar.2014.IMAX.2160p", 2014))
        assertTrue(movie("Interstellar", "Interstellar.German.DL.1080p.BluRay", 2014))
        assertFalse(episode("The Rookie", "The.Rookie.Feds.S01E01.1080p"))
        assertTrue(episode("The Rookie", "The.Rookie.S01E01.1080p"))
        // Only the year judges what comes between a movie title and its year.
        assertTrue(movie("Star Wars", "Star.Wars.Episode.IV.A.New.Hope.1977.1080p", 1977))
        assertFalse(movie("Cars", "Cars.2.2011.1080p", 2006))
        assertFalse(movie("Cars", "Cars.2.1080p", 2006))
        // Series names keep prefixes and country tags; an article still makes a one-word title another show.
        assertTrue(episode("Daredevil", "Marvels.Daredevil.S01E01.1080p"))
        assertTrue(episode("The Office", "The.Office.US.S01E01.720p"))
        assertTrue(episode("Doctor Who", "Doctor.Who.2005.S01E01.720p"))
        assertFalse(episode("Batman", "The.Batman.S01E01.720p"))

        assertTrue(movie("Money Heist", "La.Casa.de.Papel.2017.1080p", 2017, original = "La casa de papel"))
        assertFalse(movie("Money Heist", "La.Casa.de.Papel.2017.1080p", 2017))
    }

    @Test fun `text fallback rejects wrong title year and episode but allows season packs`() {
        val movie = UsenetSearchRequest(title = "The Movie", year = 2020)
        assertTrue(NewznabProtocol.matches(UsenetRelease("The.Movie.2020.1080p", "url"), movie, false))
        assertTrue(NewznabProtocol.matches(UsenetRelease("The.Movie.2021.1080p", "url"), movie, false))
        assertFalse(NewznabProtocol.matches(UsenetRelease("The.Movie.2022.1080p", "url"), movie, false))
        assertFalse(NewznabProtocol.matches(UsenetRelease("Another.Movie.2020.1080p", "url"), movie, false))
        assertTrue(NewznabProtocol.matches(UsenetRelease("The.Movie.(2020).1080p", "url"), movie, false))
        // Year-like words in the title are not the release year.
        val sequel = UsenetSearchRequest(title = "Blade Runner 2049", year = 2017)
        assertTrue(NewznabProtocol.matches(UsenetRelease("Blade.Runner.2049.2017.2160p", "url"), sequel, false))
        assertTrue(NewznabProtocol.matches(UsenetRelease("Blade.Runner.2049.1080p", "url"), sequel, false))
        assertFalse(NewznabProtocol.matches(UsenetRelease("Blade.Runner.2049.2019.1080p", "url"), sequel, false))
        assertTrue(NewznabProtocol.matches(UsenetRelease("1917.2019.1080p", "url"), UsenetSearchRequest(title = "1917", year = 2019), false))
        val series = UsenetSearchRequest(title = "A Show", series = true, season = 1, episode = 5)
        assertFalse(NewznabProtocol.matches(UsenetRelease("A.Show.S01E04.1080p", "url"), series, true))
        assertFalse(NewznabProtocol.matches(UsenetRelease("A.Show.1x04.1080p", "url"), series, true))
        assertTrue(NewznabProtocol.matches(UsenetRelease("A.Show.S01E01-E10", "url"), series, false))
        // A hyphen spans a range; episodes written one after another are a list of exactly those.
        fun episode(title: String, number: Int) =
            NewznabProtocol.matches(UsenetRelease(title, "url"), series.copy(episode = number), true)
        assertTrue(episode("A.Show.S01E01-05.1080p", 3))
        assertTrue(episode("A.Show.S01E04E05E06.1080p", 6))
        assertTrue(episode("A.Show.S01E04E05E06.1080p", 5))
        assertFalse(episode("A.Show.S01E04E06.1080p", 5))
        assertTrue(episode("A.Show.S01E04E06.1080p", 6))
        assertTrue(episode("A.Show.S01E01E02-E04.1080p", 3))
        assertTrue(episode("A.Show.1x04-06.1080p", 5))
        assertFalse(episode("A.Show.S01E04-1080p", 5))
        assertTrue(NewznabProtocol.matches(UsenetRelease("A.Show.S01.Complete", "url"), series, false))
        assertFalse(NewznabProtocol.matches(UsenetRelease("A.Show.S02.Complete", "url"), series, true))
    }
}
