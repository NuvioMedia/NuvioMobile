package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals

class SmartStreamSelectorTest {
    @Test
    fun `ranks higher quality`() {
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
        assertEquals(listOf(high, low), SmartStreamSelector.rank(listOf(low, high)))
    }

    @Test
    fun `recognizes common resolution variants`() {
        fun stream(name: String) = StreamItem(
            name = name,
            url = "https://cdn.example.com/$name.mkv",
            addonName = "Test",
            addonId = "addon.test",
        )
        val qhd = stream("QHD")
        val uhd = stream("4K UHD")
        val eightK = stream("8K")
        assertEquals(
            listOf(eightK, uhd, qhd),
            SmartStreamSelector.rank(listOf(qhd, eightK, uhd)),
        )
    }

    @Test
    fun `uses bandwidth to avoid an oversized stream`() {
        fun stream(name: String, resolution: String, size: Long) = StreamItem(
            name = name,
            url = "https://cdn.example.com/$name.mkv",
            addonName = "Test",
            addonId = "addon.test",
            behaviorHints = StreamBehaviorHints(videoSize = size),
            clientResolve = StreamClientResolve(
                stream = StreamClientResolveStream(
                    raw = StreamClientResolveRaw(
                        parsed = StreamClientResolveParsed(resolution = resolution, duration = 600)
                    )
                )
            )
        )
        val low = stream("720p", "720p", 40_000_000)
        val high = stream("1080p", "1080p", 100_000_000)
        assertEquals(
            low,
            SmartStreamSelector.rank(
                listOf(high, low),
                SmartStreamSelector.Context(estimatedBandwidthKbps = 1_000)
            ).first()
        )
    }

    @Test
    fun `keeps a stream at the bandwidth limit eligible`() {
        fun stream(name: String, resolution: String, size: Long) = StreamItem(
            name = name,
            url = "https://cdn.example.com/$name.mkv",
            addonName = "Test",
            addonId = "addon.test",
            behaviorHints = StreamBehaviorHints(videoSize = size),
            clientResolve = StreamClientResolve(
                stream = StreamClientResolveStream(
                    raw = StreamClientResolveRaw(
                        parsed = StreamClientResolveParsed(resolution = resolution, duration = 600)
                    )
                )
            )
        )
        val higherQuality = stream("2160p", "2160p", 75_000_000)
        val lowerQuality = stream("1080p", "1080p", 67_500_000)
        assertEquals(
            higherQuality,
            SmartStreamSelector.rank(
                listOf(lowerQuality, higherQuality),
                SmartStreamSelector.Context(estimatedBandwidthKbps = 1_000)
            ).first()
        )
    }

    @Test
    fun `does not prefer hdr when device lacks hdr support`() {
        fun stream(name: String, hdr: List<String>) = StreamItem(
            name = name,
            url = "https://cdn.example.com/$name.mkv",
            addonName = "Test",
            addonId = "addon.test",
            clientResolve = StreamClientResolve(
                stream = StreamClientResolveStream(
                    raw = StreamClientResolveRaw(
                        parsed = StreamClientResolveParsed(resolution = "1080p", hdr = hdr)
                    )
                )
            )
        )
        val sdr = stream("sdr", emptyList())
        val hdr = stream("hdr", listOf("HDR10"))
        assertEquals(sdr, SmartStreamSelector.rank(listOf(hdr, sdr)).first())
    }

    @Test
    fun `explicit stream preference outranks quality score`() {
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
        val preferred = stream("DV", "1080p")
        val higherQuality = stream("HDR", "2160p")
        assertEquals(
            preferred,
            SmartStreamSelector.rank(
                listOf(higherQuality, preferred),
                SmartStreamSelector.Context(preferredStreamTerms = listOf("DV", "HDR"))
            ).first()
        )
    }

    @Test
    fun `keeps equal-score ordering deterministic`() {
        fun stream(name: String) = StreamItem(
            name = name,
            url = "https://cdn.example.com/$name.mkv",
            addonName = "Test",
            addonId = "addon.test",
        )
        val first = stream("first")
        val second = stream("second")
        assertEquals(listOf(first, second), SmartStreamSelector.rank(listOf(first, second)))
    }
}
