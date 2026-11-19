package com.nuvio.app.features.usenet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnplayableReleasesTest {
    private class MemoryStorage : UnplayableReleaseStorage {
        var value: String? = null
        override var generation = 0
        override fun load() = value
        override fun save(value: String) { this.value = value }
        fun clear() { value = null; generation += 1 }
    }

    private val provider = listOf("nntps://user:secret@news.example.test:563/20?priority=0")
    private var clock = 1_000L
    private val storage = MemoryStorage()
    private val releases = UnplayableReleases(storage) { clock }

    private fun record(url: String = "https://a.test/getnzb/1.nzb&r=key", title: String = "Movie.2026.2160p-GRP",
        size: Long = 30_000_000_000, kind: String = "missing-article") = releases.record(url, title, size, provider, kind)

    @Test fun `a release missing articles is remembered without storing secrets`() {
        record()
        assertTrue(releases.contains("https://a.test/getnzb/1.nzb&r=key", "Movie.2026.2160p-GRP", 30_000_000_000, provider))
        assertFalse(storage.value!!.contains("secret"))
        assertFalse(storage.value!!.contains("key"))
        assertFalse(storage.value!!.contains("Movie"))
    }

    @Test fun `the same upload from another indexer counts, another size does not`() {
        record(size = 25_991_001_440)
        // One indexer rounds sizes to thousands of bytes, another gives them exactly.
        assertTrue(releases.contains("https://b.test/api?t=get&id=9", "movie.2026.2160p-grp", 25_991_001_000, provider))
        assertTrue(releases.contains("https://b.test/api?t=get&id=9", "Movie.2026.2160p-GRP", 25_991_999_999, provider))
        assertFalse(releases.contains("https://b.test/api?t=get&id=9", "Movie.2026.2160p-GRP", 22_087_105_000, provider))
    }

    @Test fun `failures that other input or time can fix are not remembered`() {
        for (kind in listOf("provider-authentication", "provider-quota", "temporary-read-error", "")) record(kind = kind)
        assertFalse(releases.contains("https://a.test/getnzb/1.nzb&r=key", "Movie.2026.2160p-GRP", 30_000_000_000, provider))
    }

    @Test fun `other providers may still have the articles`() {
        record()
        val moreProviders = provider + "nntps://u:p@backup.example.test:563/8"
        assertFalse(releases.contains("https://a.test/getnzb/1.nzb&r=key", null, null, moreProviders))
        // Credentials, connections and priority do not change what a provider holds.
        val sameAddress = listOf("nntps://other:pass@news.example.test:563/50?priority=2")
        assertTrue(releases.contains("https://a.test/getnzb/1.nzb&r=key", null, null, sameAddress))
    }

    @Test fun `a blank release is unplayable with any provider`() {
        record(kind = "blank-content")
        val otherProviders = listOf("nntps://u:p@backup.example.test:563/8")
        assertTrue(releases.contains("https://a.test/getnzb/1.nzb&r=key", null, null, otherProviders))
    }

    @Test fun `the revision it was given counts each remembered outcome`() {
        val revision = kotlinx.coroutines.flow.MutableStateFlow(0)
        val watched = UnplayableReleases(MemoryStorage(), revision) { clock }
        watched.record("https://a.test/1", "Movie.2020.1080p-GRP", 5_000_000_000, provider, "missing-article")
        assertEquals(1, revision.value)
        watched.recordPlayed("https://a.test/2", "Movie.2020.720p-GRP", 2_000_000_000, provider)
        assertEquals(2, revision.value)
        // Failures the same release would not repeat change nothing.
        watched.record("https://a.test/3", "Movie.2020.480p-GRP", 1_000_000_000, provider, "provider-authentication")
        assertEquals(2, revision.value)
    }

    @Test fun `entries expire after 30 days`() {
        record()
        clock += 31L * 24 * 3600 * 1000
        assertFalse(releases.contains("https://a.test/getnzb/1.nzb&r=key", null, null, provider))
    }

    @Test fun `an account reset drops what was already loaded`() {
        record()
        storage.clear()
        assertFalse(releases.contains("https://a.test/getnzb/1.nzb&r=key", null, null, provider))
    }

    @Test fun `listed results put unplayable releases last with the label`() {
        fun stream(title: String) = com.nuvio.app.features.streams.StreamItem(name = "Usenet • A", title = title,
            addonName = BUILT_IN_USENET_GROUP_NAME, addonId = BUILT_IN_USENET_GROUP_ID, nzbUrl = "https://a.test/$title", nzbServers = provider)
        record(url = "https://a.test/dead")
        val group = com.nuvio.app.features.streams.AddonStreamGroup(BUILT_IN_USENET_GROUP_NAME, BUILT_IN_USENET_GROUP_ID, listOf(stream("dead"), stream("good")))
        val arranged = BuiltInUsenetService.arrangeOutcomes(listOf(group), BUILT_IN_USENET_GROUP_ID, releases::contains).single().streams
        assertEquals(listOf("good", "dead"), arranged.map { it.title })
        assertEquals(listOf(USENET_BADGE_UNAVAILABLE), arranged.last().badges.map { it.name })
        // Arranging again changes nothing.
        assertEquals(arranged, BuiltInUsenetService.arrangeOutcomes(listOf(group.copy(streams = arranged)), BUILT_IN_USENET_GROUP_ID, releases::contains).single().streams)
    }

    @Test fun `a release that played is available until it fails or two weeks pass`() {
        val url = "https://a.test/getnzb/1.nzb&r=key"
        releases.recordPlayed(url, "Show.S01.1080p-GRP", 30_000_000_000, provider)
        assertTrue(releases.played(url, null, null, provider))
        assertFalse(releases.contains(url, null, null, provider))
        // One lookup tells both, also by title for another indexer's copy.
        assertEquals(ReleaseOutcome(played = true), releases.outcome("https://b.test/9", "Show.S01.1080p-GRP", 29_000_000_000, provider))
        // A later failure wins over the earlier success.
        clock += 1000
        releases.record(url, "Show.S01.1080p-GRP", 30_000_000_000, provider, "missing-article")
        assertTrue(releases.contains(url, null, null, provider))
        assertFalse(releases.played(url, null, null, provider))
        assertEquals(ReleaseOutcome(failed = true), releases.outcome(url, null, null, provider))
        // A newer success wins again, and expires after two weeks.
        clock += 1000
        releases.recordPlayed(url, "Show.S01.1080p-GRP", 30_000_000_000, provider)
        assertTrue(releases.played(url, null, null, provider))
        clock += 15L * 24 * 3600 * 1000
        assertFalse(releases.played(url, null, null, provider))
    }

    @Test fun `playing again within a day writes nothing`() {
        releases.recordPlayed("https://a.test/1", "Movie.2026.2160p-GRP", 30_000_000_000, provider)
        val saved = storage.value
        clock += 3600_000
        storage.value = null
        releases.recordPlayed("https://a.test/1", "Movie.2026.2160p-GRP", 30_000_000_000, provider)
        assertEquals(null, storage.value)
        assertTrue(saved != null)
    }

    @Test fun `available releases go first unless turned off, unplayable ones last`() {
        fun stream(title: String) = com.nuvio.app.features.streams.StreamItem(name = "Usenet • A", title = title,
            addonName = BUILT_IN_USENET_GROUP_NAME, addonId = BUILT_IN_USENET_GROUP_ID, nzbUrl = "https://a.test/$title", nzbServers = provider)
        record(url = "https://a.test/dead")
        releases.recordPlayed("https://a.test/good", null, null, provider)
        val group = com.nuvio.app.features.streams.AddonStreamGroup(BUILT_IN_USENET_GROUP_NAME, BUILT_IN_USENET_GROUP_ID, listOf(stream("dead"), stream("untried"), stream("good")))
        fun arranged(availableFirst: Boolean) = BuiltInUsenetService.arrangeOutcomes(listOf(group), BUILT_IN_USENET_GROUP_ID, releases::contains,
            releases::played, availableFirst).single().streams
        assertEquals(listOf("good", "untried", "dead"), arranged(true).map { it.title })
        assertEquals(listOf("untried", "good", "dead"), arranged(false).map { it.title })
        assertEquals(listOf(USENET_BADGE_AVAILABLE), arranged(true).first().badges.map { it.name })
        assertEquals(listOf(USENET_BADGE_UNAVAILABLE), arranged(true).last().badges.map { it.name })
    }

    @Test fun `entries saved before keep matching`() {
        fun hex(value: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        val url = "https://a.test/getnzb/1.nzb&r=key"
        storage.value = """{"u:${hex(url)}":{"kind":"missing-article","at":$clock,"providers":"${hex("nntps://news.example.test:563")}"}}"""
        storage.generation += 1
        assertTrue(releases.contains(url, null, null, provider))
    }

    @Test fun `a season pack is matched by its title whatever size it lists`() {
        record(url = "https://a.test/pack", title = "Show.S01.1080p-GRP", size = 9_000_000_000)
        // Another indexer's copy, listed with an episode's share of the size.
        assertTrue(releases.contains("https://b.test/pack", "Show.S01.1080p-GRP", 1_500_000_000, provider))
        assertFalse(releases.contains("https://b.test/pack", "Show.S02.1080p-GRP", 1_500_000_000, provider))
    }

    private val day = 24L * 3600 * 1000
    private val posted = 1_600_000_000_000L

    @Test fun `another episode of the same upload tells how it went`() {
        releases.record("https://a.test/e02", "Show.S01E02.2160p.WEB-DL-GRP", 4_000_000_000, provider, "missing-article", posted)
        assertEquals(SiblingOutcome(2, failed = true), releases.sibling("Show.S01E03.2160p.WEB-DL-GRP", provider, posted + 7 * day))
        // The episode that failed says so itself; another upload, season or provider tells nothing.
        assertEquals(null, releases.sibling("Show.S01E02.2160p.WEB-DL-GRP", provider, posted))
        assertEquals(null, releases.sibling("Show.S01E03.1080p.WEB-DL-GRP", provider, posted))
        assertEquals(null, releases.sibling("Show.S02E03.2160p.WEB-DL-GRP", provider, posted))
        assertEquals(null, releases.sibling("Show.S01E03.2160p.WEB-DL-GRP", listOf("nntps://u:p@backup.example.test:563/8"), posted))
        // Packs and multi-episode releases are not one episode of an upload.
        assertEquals(null, releases.sibling("Show.S01.2160p.WEB-DL-GRP", provider, posted))
        assertEquals(null, releases.sibling("Show.S01E03E04.2160p.WEB-DL-GRP", provider, posted))
        // The newest outcome among the episodes counts.
        releases.recordPlayed("https://a.test/e04", "Show.S01E04.2160p.WEB-DL-GRP", 4_000_000_000, provider, posted + 14 * day)
        assertEquals(SiblingOutcome(4, failed = false), releases.sibling("Show.S01E05.2160p.WEB-DL-GRP", provider, posted + 21 * day))
        clock += 15 * day
        assertEquals(null, releases.sibling("Show.S01E05.2160p.WEB-DL-GRP", provider, posted + 21 * day))
    }

    @Test fun `a repost of the same title is another upload`() {
        val title = "Slow.Horses.S02E02.Last.Stop.2160p.ATVP.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX"
        releases.record("https://a.test/old", title, 10_450_000_000, provider, "missing-article", posted)
        // Posted 204 days later under the same title and size, from another indexer.
        assertTrue(releases.contains("https://b.test/old", title, 10_450_000_000, provider, posted + day / 2))
        assertFalse(releases.contains("https://b.test/new", title, 10_450_000_000, provider, posted + 204 * day))
        val next = "Slow.Horses.S02E03.Drinking.Games.2160p.ATVP.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX"
        assertEquals(SiblingOutcome(2, failed = true), releases.sibling(next, provider, posted + 6 * day))
        assertEquals(null, releases.sibling(next, provider, posted + 204 * day))
        // Without a time either side, nothing tells an upload's episodes from a repost's.
        assertEquals(null, releases.sibling(next, provider, null))
    }

    @Test fun `episode names in titles do not separate an upload's episodes`() {
        releases.record("https://a.test/e04", "Slow.Horses.2022.S01E04.Visiting.Hours.2160p.ATV.WEB-DL.MULTi.DDP5.1.Atmos.H.265-FUZEER",
            12_000_000_000, provider, "missing-article", posted)
        assertEquals(SiblingOutcome(4, failed = true),
            releases.sibling("Slow.Horses.2022.S01E06.Follies.2160p.ATV.WEB-DL.MULTi.DDP5.1.Atmos.H.265-FUZEER", provider, posted))
        releases.recordPlayed("https://a.test/crfw", "Slow Horses S01E04 Visiting Hours 2160p ATVP WEB-DL DDP5 1 Atmos DV HDR10 H 265-CRFW",
            12_000_000_000, provider, posted)
        assertEquals(SiblingOutcome(4, failed = false),
            releases.sibling("Slow Horses S01E02 Work Drinks 2160p ATVP WEB-DL DDP5 1 Atmos DV HDR10 H 265-CRFW", provider, posted))
        assertEquals(null, releases.sibling("Slow Horses S01E02 Work Drinks 1080p ATVP WEB-DL DDP5 1 H 264-CRFW", provider, posted))
        // Played again within a day, an episode still becomes its upload's newest outcome.
        releases.recordPlayed("https://a.test/crfw2", "Slow Horses S01E02 Work Drinks 2160p ATVP WEB-DL DDP5 1 Atmos DV HDR10 H 265-CRFW",
            12_000_000_000, provider, posted)
        releases.recordPlayed("https://a.test/crfw", "Slow Horses S01E04 Visiting Hours 2160p ATVP WEB-DL DDP5 1 Atmos DV HDR10 H 265-CRFW",
            12_000_000_000, provider, posted)
        assertEquals(SiblingOutcome(4, failed = false),
            releases.sibling("Slow Horses S01E03 Hard Tack 2160p ATVP WEB-DL DDP5 1 Atmos DV HDR10 H 265-CRFW", provider, posted))
    }

    @Test fun `another blank episode counts with any provider and goes last`() {
        releases.record("https://a.test/e02", "Show.S01E02.2160p-FAKE", 30_000_000_000, provider, "blank-content", posted)
        val others = listOf("nntps://u:p@backup.example.test:563/8")
        assertEquals(SiblingOutcome(2, failed = true, blank = true), releases.sibling("Show.S01E03.2160p-FAKE", others, posted))
        fun stream(title: String) = com.nuvio.app.features.streams.StreamItem(name = "Usenet • A", title = title,
            addonName = BUILT_IN_USENET_GROUP_NAME, addonId = BUILT_IN_USENET_GROUP_ID, nzbUrl = "https://a.test/$title", nzbServers = provider,
            publishedAt = posted)
        val group = com.nuvio.app.features.streams.AddonStreamGroup(BUILT_IN_USENET_GROUP_NAME, BUILT_IN_USENET_GROUP_ID, listOf(stream("Show.S01E03.2160p-FAKE"), stream("Show.S01E03.2160p-GRP")))
        val arranged = BuiltInUsenetService.arrangeOutcomes(listOf(group), BUILT_IN_USENET_GROUP_ID, releases::contains, releases::played,
            sibling = releases::sibling).single().streams
        assertEquals(listOf("Show.S01E03.2160p-GRP", "Show.S01E03.2160p-FAKE"), arranged.map { it.title })
        assertEquals(listOf("E02 FAILED"), arranged.last().badges.map { it.name })
        // Once tried, the release's own outcome replaces the other episode's.
        releases.record(arranged.last(), "blank-content")
        val again = BuiltInUsenetService.arrangeOutcomes(listOf(group.copy(streams = arranged)), BUILT_IN_USENET_GROUP_ID, releases::contains,
            releases::played, sibling = releases::sibling).single().streams
        assertEquals(listOf(USENET_BADGE_UNAVAILABLE), again.last().badges.map { it.name })
    }
}
