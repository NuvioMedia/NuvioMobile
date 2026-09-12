package com.nuvio.app.features.details

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.AddonsUiState
import com.nuvio.app.features.addons.ManagedAddon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetaDetailsCandidatesTest {

    private fun createAddon(
        id: String,
        types: List<String>,
        idPrefixes: List<String> = emptyList(),
        resourceTypes: List<String> = types,
        resourceIdPrefixes: List<String> = idPrefixes,
    ): ManagedAddon {
        val manifest = AddonManifest(
            id = id,
            name = id,
            description = "Test addon $id",
            version = "1.0.0",
            resources = listOf(
                AddonResource(
                    name = "meta",
                    types = resourceTypes,
                    idPrefixes = resourceIdPrefixes,
                )
            ),
            types = types,
            idPrefixes = idPrefixes,
            transportUrl = "https://$id.example/manifest.json",
        )
        return ManagedAddon(
            manifestUrl = manifest.transportUrl,
            manifest = manifest,
            enabled = true,
        )
    }

    @Test
    fun `explicit id prefix match is prioritized before generic empty-prefix addon`() {
        // Generic addon is installed first in list
        val genericAddon = createAddon(
            id = "generic-community-addon",
            types = listOf("movie", "series"),
            idPrefixes = emptyList(),
        )
        // Cinemeta is installed second in list, but explicitly specifies "tt" prefix
        val cinemeta = createAddon(
            id = "cinemeta",
            types = listOf("movie", "series"),
            idPrefixes = listOf("tt"),
        )

        val state = AddonsUiState(addons = listOf(genericAddon, cinemeta))
        val candidates = MetaDetailsRepository.findMetaCandidates(
            state = state,
            type = "movie",
            id = "tt3915174", // Puss in Boots: The Last Wish
        )

        assertTrue(candidates.isNotEmpty())
        assertEquals("cinemeta", candidates.first().manifest.id)
        assertEquals("movie", candidates.first().candidateType)
    }

    @Test
    fun `tv request infers series and pairs with series candidate type`() {
        val cinemeta = createAddon(
            id = "cinemeta",
            types = listOf("movie", "series"),
            idPrefixes = listOf("tt"),
        )

        val state = AddonsUiState(addons = listOf(cinemeta))
        val candidates = MetaDetailsRepository.findMetaCandidates(
            state = state,
            type = "tv",
            id = "tt0944947",
        )

        assertEquals(1, candidates.size)
        assertEquals("cinemeta", candidates.first().manifest.id)
        assertEquals("series", candidates.first().candidateType)
    }

    @Test
    fun `fallback to generic empty-prefix addon when no explicit prefix match exists`() {
        val cinemeta = createAddon(
            id = "cinemeta",
            types = listOf("movie", "series"),
            idPrefixes = listOf("tt"),
        )
        val genericAddon = createAddon(
            id = "generic-addon",
            types = listOf("movie", "series"),
            idPrefixes = emptyList(),
        )

        val state = AddonsUiState(addons = listOf(cinemeta, genericAddon))
        val candidates = MetaDetailsRepository.findMetaCandidates(
            state = state,
            type = "movie",
            id = "kitsu:12345",
        )

        assertEquals(1, candidates.size)
        assertEquals("generic-addon", candidates.first().manifest.id)
    }
}
