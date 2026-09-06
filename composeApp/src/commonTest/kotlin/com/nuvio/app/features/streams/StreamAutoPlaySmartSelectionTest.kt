package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamAutoPlaySmartSelectionTest {
    @Test
    fun `first stream autoplay uses smart ranking`() {
        fun stream(name: String, resolution: String) = StreamItem(
            name = name,
            url = "https://cdn.example.com/$name.mkv",
            addonName = "Test",
            addonId = "addon.test",
            clientResolve = StreamClientResolve(
                stream = StreamClientResolveStream(
                    raw = StreamClientResolveRaw(
                        parsed = StreamClientResolveParsed(resolution = resolution)
                    )
                )
            )
        )
        val low = stream("720p", "720p")
        val high = stream("1080p", "1080p")

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(low, high),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(high, selected)
    }

    @Test
    fun `regex ordered alternatives support more than two preferences`() {
        fun stream(name: String, resolution: String) = StreamItem(
            name = name,
            url = "https://cdn.example.com/$name.mkv",
            addonName = "Test",
            addonId = "addon.test",
            clientResolve = StreamClientResolve(
                stream = StreamClientResolveStream(
                    raw = StreamClientResolveRaw(
                        parsed = StreamClientResolveParsed(resolution = resolution)
                    )
                )
            )
        )
        val atmos = stream("ATMOS 2160p", "2160p")
        val hdr = stream("HDR 1080p", "1080p")
        val dv = stream("DV 720p", "720p")

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(atmos, hdr, dv),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "(DV|HDR|ATMOS)",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(dv, selected)
    }

    @Test
    fun `manual mode remains unselected`() {
        val stream = StreamItem(
            name = "1080p",
            url = "https://cdn.example.com/1080p.mkv",
            addonName = "Test",
            addonId = "addon.test",
        )
        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(stream),
            mode = StreamAutoPlayMode.MANUAL,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )
        assertEquals(null, evaluation.stream)
        assertEquals(emptyList(), evaluation.readyStreams)
    }
}
