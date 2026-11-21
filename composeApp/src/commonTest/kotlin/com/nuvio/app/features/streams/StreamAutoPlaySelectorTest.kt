package com.nuvio.app.features.streams

import com.nuvio.app.features.usenet.BUILT_IN_USENET_GROUP_ID
import com.nuvio.app.features.usenet.BUILT_IN_USENET_GROUP_NAME
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamAutoPlaySelectorTest {

    @Test
    fun `allowed addons can include built-in Usenet like an addon`() {
        val usenet = StreamItem(
            name = "1080p • Usenet • Indexer",
            nzbUrl = "https://indexer.test/getnzb/1.nzb",
            nzbServers = listOf("nntps://news.test:563/20"),
            addonName = BUILT_IN_USENET_GROUP_NAME,
            addonId = BUILT_IN_USENET_GROUP_ID,
        )
        val addon = stream(addonName = "AddonA", url = "https://example.com/a.m3u8", name = "720p", bingeGroup = null)
        fun select(selectedAddons: Set<String>, source: StreamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES) =
            StreamAutoPlaySelector.selectAutoPlayStream(
                streams = listOf(usenet, addon),
                mode = StreamAutoPlayMode.FIRST_STREAM,
                regexPattern = "",
                source = source,
                installedAddonNames = setOf("AddonA", BUILT_IN_USENET_GROUP_NAME),
                selectedAddons = selectedAddons,
                selectedPlugins = setOf("Some plugin"),
            )

        assertEquals(usenet, select(setOf(BUILT_IN_USENET_GROUP_NAME)))
        assertEquals(usenet, select(emptySet(), StreamAutoPlaySource.INSTALLED_ADDONS_ONLY))
        assertEquals(addon, select(setOf("AddonA")))
    }

    @Test
    fun `bingeGroup-first selects matching stream before first stream mode`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "1080p",
            bingeGroup = "other-group",
        )
        val preferred = stream(
            addonName = "AddonB",
            url = "https://example.com/preferred.m3u8",
            name = "720p",
            bingeGroup = "same-group",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, preferred),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
        )

        assertEquals(preferred, selected)
    }

    @Test
    fun `falls back to normal mode when no bingeGroup match exists`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "First",
            bingeGroup = "group-a",
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            name = "Second",
            bingeGroup = "group-b",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "missing-group",
            preferBingeGroupInSelection = true,
        )

        assertEquals(first, selected)
    }

    @Test
    fun `bingeGroup-first respects source and addon plugin filters`() {
        val filteredOutAddonMatch = stream(
            addonName = "AddonFilteredOut",
            url = "https://example.com/addon-match.m3u8",
            bingeGroup = "same-group",
        )
        val allowedPluginMatch = stream(
            addonName = "PluginAllowed",
            url = "https://example.com/plugin-match.m3u8",
            bingeGroup = "same-group",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(filteredOutAddonMatch, allowedPluginMatch),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
            installedAddonNames = setOf("AddonFilteredOut"),
            selectedAddons = emptySet(),
            selectedPlugins = setOf("PluginAllowed"),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
        )

        assertEquals(allowedPluginMatch, selected)
    }

    @Test
    fun `blank preferredBingeGroup behaves as disabled`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            bingeGroup = "group-a",
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            bingeGroup = "group-b",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "   ",
            preferBingeGroupInSelection = true,
        )

        assertEquals(first, selected)
    }

    @Test
    fun `manual mode remains manual even with matching bingeGroup`() {
        val matched = stream(
            addonName = "AddonA",
            url = "https://example.com/match.m3u8",
            bingeGroup = "same-group",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(matched),
            mode = StreamAutoPlayMode.MANUAL,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
        )

        assertNull(selected)
    }

    @Test
    fun `first stream mode can select direct debrid candidate without resolved URL`() {
        val directDebrid = stream(
            addonName = "Torbox Instant",
            url = null,
            name = "TB Instant",
            directDebrid = true,
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(directDebrid),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(directDebrid, selected)
    }

    @Test
    fun `first stream mode does not auto select external url browser link`() {
        val external = stream(
            addonName = "External Addon",
            externalUrl = "https://example.com/watch",
            name = "Watch on site",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(external),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("External Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertNull(selected)
    }

    @Test
    fun `timeout evaluation keeps pending regex debrid candidate open`() {
        val pending = stream(
            addonName = "Torrentio",
            name = "The Show 1080p",
            infoHash = "hash-pending",
            cacheState = StreamDebridCacheState.CHECKING,
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(pending),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            debridEnabled = true,
            activeResolverProviderId = "premiumize",
        )

        assertNull(evaluation.stream)
        assertTrue(evaluation.hasPendingDebridCandidate)
    }

    @Test
    fun `timeout evaluation still selects direct link while debrid candidate is pending`() {
        val pending = stream(
            addonName = "Torrentio",
            name = "The Show 1080p",
            infoHash = "hash-pending",
            cacheState = StreamDebridCacheState.CHECKING,
        )
        val direct = stream(
            addonName = "Direct Addon",
            url = "https://example.com/video.mp4",
            name = "The Show 1080p",
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(pending, direct),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio", "Direct Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            debridEnabled = true,
            activeResolverProviderId = "premiumize",
        )

        assertEquals(direct, evaluation.stream)
        assertFalse(evaluation.hasPendingDebridCandidate)
    }

    @Test
    fun `direct debrid candidate must match active resolver`() {
        val torbox = stream(
            addonName = "Comet",
            name = "TB Instant",
            directDebrid = true,
            directDebridService = "torbox",
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(torbox),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Comet"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            debridEnabled = true,
            activeResolverProviderId = "premiumize",
        )

        assertNull(evaluation.stream)
        assertFalse(evaluation.hasPendingDebridCandidate)
    }

    @Test
    fun `nested exclusions do not crash regex selection`() {
        val stream = stream(
            addonName = "Direct Addon",
            url = "https://example.com/video.mp4",
            name = "Movie 1080p WEB",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(stream),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "^(?!.*\\b(CAM(?!RIP)|TS)\\b).*1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Direct Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(stream, selected)
    }

    private fun stream(
        addonName: String,
        url: String? = null,
        externalUrl: String? = null,
        name: String? = null,
        bingeGroup: String? = null,
        directDebrid: Boolean = false,
        directDebridService: String = "torbox",
        infoHash: String? = null,
        cacheState: StreamDebridCacheState? = null,
    ): StreamItem = StreamItem(
        name = name,
        url = url,
        externalUrl = externalUrl,
        infoHash = infoHash,
        addonName = addonName,
        addonId = "addon:$addonName",
        clientResolve = if (directDebrid) {
            StreamClientResolve(
                type = "debrid",
                service = directDebridService,
                isCached = true,
                infoHash = "hash",
            )
        } else {
            null
        },
        debridCacheStatus = cacheState?.let { state ->
            StreamDebridCacheStatus(
                providerId = "premiumize",
                providerName = "Premiumize",
                state = state,
            )
        },
        behaviorHints = StreamBehaviorHints(
            bingeGroup = bingeGroup,
        ),
    )
}
