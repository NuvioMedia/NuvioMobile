package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamFormattingEngineTest {
    private fun stream(
        name: String = "Apocalypse.Z.New.World.2026.1080p.WEB-DL.HEVC.DDP5.1.HDR10.2GB.mkv",
        description: String? = null,
        badges: List<StreamBadge> = emptyList(),
        addonId: String = "cloudstream:provider-hash",
        addonName: String = "CineStream",
    ) = StreamItem(
        name = name,
        description = description,
        addonName = addonName,
        addonId = addonId,
        badges = badges,
    )

    @Test
    fun formatsRawCloudStreamTitleWhenScopeIncludesPlugin() {
        val item = stream()
        val result = StreamFormattingEngine.format(
            stream = item,
            preset = StreamFormatPreset.NUVIOEX_MODERN,
            providerId = item.addonId,
            providerFamily = StreamProviderFamily.CLOUDSTREAM_PLUGIN,
            settings = StreamFormattingSettings(),
        )

        assertTrue(result.title.contains("Apocalypse Z New World"))
        assertFalse(result.title.contains("1080p", ignoreCase = true))
        assertTrue(result.summary.orEmpty().contains("1080P"))
        assertTrue(result.summary.orEmpty().contains("WEB-DL"))
    }

    @Test
    fun doesNotTreatAQualityBadgeAloneAsCompleteFormatting() {
        val item = stream(
            name = "Apocalypse.Z.New.World.2026.1080p.WEB-DL.HEVC.mkv",
            badges = listOf(StreamBadge(name = "FHD")),
        )

        assertFalse(StreamFormattingEngine.hasExistingFormatting(item))
        val result = StreamFormattingEngine.format(
            stream = item,
            preset = StreamFormatPreset.NUVIOEX_MODERN,
            providerId = item.addonId,
            providerFamily = StreamProviderFamily.CLOUDSTREAM_PLUGIN,
            settings = StreamFormattingSettings(),
        )
        assertTrue(result.title.contains("Apocalypse Z New World"))
    }

    @Test
    fun preservesProviderFormattingWhenStructuredBadgesExist() {
        val item = stream(
            name = "Allmovieland [English]",
            description = "1080P · Audio: English · Provider: CineStream",
            badges = listOf(StreamBadge(name = "FHD")),
        )

        val result = StreamFormattingEngine.format(
            stream = item,
            preset = StreamFormatPreset.NUVIOEX_MODERN,
            providerId = item.addonId,
            providerFamily = StreamProviderFamily.CLOUDSTREAM_PLUGIN,
            settings = StreamFormattingSettings(),
        )

        assertEquals(item.name, result.title)
        assertEquals(item.description, result.summary)
        assertTrue(result.technicalDetails.isEmpty())
    }

    @Test
    fun preservesProviderFormattingWhenMultipleBadgesAlreadyExist() {
        val item = stream(
            name = "Allmovieland [English]",
            badges = listOf(
                StreamBadge(name = "FHD"),
                StreamBadge(name = "WEB-DL"),
            ),
        )

        assertTrue(StreamFormattingEngine.hasExistingFormatting(item))
        val result = StreamFormattingEngine.format(
            stream = item,
            preset = StreamFormatPreset.NUVIOEX_MODERN,
            providerId = item.addonId,
            providerFamily = StreamProviderFamily.CLOUDSTREAM_PLUGIN,
            settings = StreamFormattingSettings(),
        )
        assertEquals(item.name, result.title)
        assertTrue(result.technicalDetails.isEmpty())
    }

    @Test
    fun preservesProviderFormattingWhenDescriptionAlreadyHasTechnicalSummary() {
        val item = stream(
            name = "Allmovieland [Hindi]",
            description = "1080P · Audio: Hindi · Provider: CineStream",
        )

        assertTrue(StreamFormattingEngine.hasExistingFormatting(item))
    }

    @Test
    fun respectsProviderFamilyAndIndividualProviderScope() {
        val item = stream()
        val excludedScope = StreamFormattingScope(
            includeCloudstreamPlugins = true,
            excludedProviderIds = setOf("CineStream"),
        )
        val excluded = StreamFormattingEngine.format(
            stream = item,
            preset = StreamFormatPreset.NUVIOEX_MODERN,
            providerId = item.addonId,
            providerFamily = StreamProviderFamily.CLOUDSTREAM_PLUGIN,
            settings = StreamFormattingSettings(scope = excludedScope),
        )
        assertEquals(item.name, excluded.title)

        val onlyNuvio = StreamFormattingEngine.format(
            stream = item,
            preset = StreamFormatPreset.NUVIOEX_MODERN,
            providerId = item.addonId,
            providerFamily = StreamProviderFamily.CLOUDSTREAM_PLUGIN,
            settings = StreamFormattingSettings(
                scope = StreamFormattingScope(includeNuvioAddons = true, includeCloudstreamPlugins = false),
            ),
        )
        assertEquals(item.name, onlyNuvio.title)
    }
}
