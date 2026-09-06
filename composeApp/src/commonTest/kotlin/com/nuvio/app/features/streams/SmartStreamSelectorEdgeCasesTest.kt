package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmartStreamSelectorEdgeCasesTest {

    private fun stream(
        name: String,
        resolution: String? = null,
        size: Long? = null,
        duration: Int? = null,
        codec: String? = null,
        hdr: List<String> = emptyList(),
        languages: List<String> = emptyList(),
        directDebrid: Boolean = false,
        cached: Boolean = false,
    ) = StreamItem(
        name = name,
        url = "https://cdn.example.com/$name.mkv",
        addonName = "Test",
        addonId = "addon.test",
        behaviorHints = StreamBehaviorHints(videoSize = size),
        isDirectDebridStream = directDebrid,
        clientResolve = StreamClientResolve(
            isCached = cached,
            stream = StreamClientResolveStream(
                raw = StreamClientResolveRaw(
                    size = size,
                    parsed = StreamClientResolveParsed(
                        resolution = resolution,
                        duration = duration,
                        codec = codec,
                        hdr = hdr,
                        languages = languages,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `parses common resolution variants`() {
        val qhd = stream("qhd", "QHD")
        val dci4k = stream("dci", "DCI 4K")
        val fhd = stream("fhd", "FHD 1080")
        val ranked = SmartStreamSelector.rank(listOf(qhd, fhd, dci4k))

        assertEquals(dci4k, ranked[0])
        assertEquals(qhd, ranked[1])
        assertEquals(fhd, ranked[2])
    }

    @Test
    fun `resolution in description is considered`() {
        val noResolution = stream("unknown")
        val described = stream("described", resolution = null).copy(description = "1080p HDR")

        val ranked = SmartStreamSelector.rank(listOf(noResolution, described))
        assertEquals(described, ranked[0])
    }

    @Test
    fun `unknown resolution does not outrank known resolution`() {
        val unknown = stream("unknown")
        val known = stream("720p", "720p")

        assertEquals(known, SmartStreamSelector.rank(listOf(unknown, known))[0])
    }

    @Test
    fun `zero and null bandwidth fall back to quality ranking`() {
        val low = stream("720p", "720p", size = 40_000_000, duration = 600)
        val high = stream("1080p", "1080p", size = 100_000_000, duration = 600)

        assertEquals(
            high,
            SmartStreamSelector.rank(
                listOf(low, high),
                SmartStreamSelector.Context(estimatedBandwidthKbps = 0),
            )[0],
        )
        assertEquals(
            high,
            SmartStreamSelector.rank(
                listOf(low, high),
                SmartStreamSelector.Context(estimatedBandwidthKbps = null),
            )[0],
        )
    }

    @Test
    fun `missing bitrate metadata does not crash or change deterministic ordering`() {
        val first = stream("first", "1080p")
        val second = stream("second", "1080p", size = 100_000_000, duration = 600)
        val ranked = SmartStreamSelector.rank(
            listOf(first, second),
            SmartStreamSelector.Context(estimatedBandwidthKbps = 5_000),
        )

        assertEquals(listOf(first, second), ranked)
    }

    @Test
    fun `slightly oversized high resolution stream can still win on quality`() {
        val highResolution = stream("2160p", "2160p", size = 90_000_000, duration = 600)
        val lowerResolution = stream("1080p", "1080p", size = 75_000_000, duration = 600)
        val ranked = SmartStreamSelector.rank(
            listOf(highResolution, lowerResolution),
            SmartStreamSelector.Context(estimatedBandwidthKbps = 1_000),
        )

        assertEquals(highResolution, ranked[0])
    }

    @Test
    fun `zero display height falls back to general quality ranking`() {
        val low = stream("720p", "720p")
        val high = stream("1080p", "1080p")
        val ranked = SmartStreamSelector.rank(
            listOf(low, high),
            SmartStreamSelector.Context(displayHeight = 0),
        )

        assertEquals(high, ranked[0])
    }

    @Test
    fun `small display prefers a resolution closer to the display`() {
        val low = stream("720p", "720p")
        val high = stream("1080p", "1080p")
        val ranked = SmartStreamSelector.rank(
            listOf(low, high),
            SmartStreamSelector.Context(displayHeight = 480),
        )

        assertEquals(low, ranked[0])
    }

    @Test
    fun `preferred codec is normalized across common HEVC names`() {
        val h264 = stream("h264", "1080p", codec = "h264")
        val x265 = stream("x265", "1080p", codec = "x265")
        val ranked = SmartStreamSelector.rank(
            listOf(h264, x265),
            SmartStreamSelector.Context(preferredVideoCodec = "hevc"),
        )

        assertEquals(x265, ranked[0])
    }

    @Test
    fun `specific HDR support favors a matching HDR stream`() {
        val dv = stream("dv", "1080p", hdr = listOf("dolbyvision"))
        val hdr10 = stream("hdr10", "1080p", hdr = listOf("hdr10"))
        val sdr = stream("sdr", "1080p")
        val ranked = SmartStreamSelector.rank(
            listOf(sdr, hdr10, dv),
            SmartStreamSelector.Context(supportedHdrTypes = setOf("dolbyvision")),
        )

        assertEquals(dv, ranked[0])
    }

    @Test
    fun `direct debrid bonus can overcome a modest resolution difference`() {
        val regular = stream("regular", "1080p")
        val debrid = stream("debrid", "720p", directDebrid = true)
        val ranked = SmartStreamSelector.rank(listOf(regular, debrid))

        assertEquals(debrid, ranked[0])
    }

    @Test
    fun `preference order outranks general quality`() {
        val primary = stream("1st-choice", "480p")
        val secondary = stream("2nd-choice", "1080p")
        val tertiary = stream("3rd-choice", "4k")
        val ranked = SmartStreamSelector.rank(
            listOf(tertiary, secondary, primary),
            SmartStreamSelector.Context(
                preferredStreamTerms = listOf("1st-choice", "2nd-choice", "3rd-choice"),
            ),
        )

        assertEquals(listOf(primary, secondary, tertiary), ranked)
    }

    @Test
    fun `ranking is stable for equal scores`() {
        val first = stream("first", "1080p")
        val second = stream("second", "1080p")
        val third = stream("third", "1080p")
        val ranked = SmartStreamSelector.rank(listOf(first, second, third))

        assertEquals(listOf(first, second, third), ranked)
    }

    @Test
    fun `platform context provider is used when no explicit context is supplied`() {
        val high = stream("1080p", "1080p")
        val low = stream("720p", "720p")
        SmartStreamSelector.setPlatformContextProvider {
            SmartStreamSelector.Context(displayHeight = 720)
        }

        try {
            val ranked = SmartStreamSelector.rank(listOf(high, low))
            assertTrue(ranked.isNotEmpty())
            assertEquals(low, ranked[0])
        } finally {
            SmartStreamSelector.setPlatformContextProvider(null)
        }
    }
}
