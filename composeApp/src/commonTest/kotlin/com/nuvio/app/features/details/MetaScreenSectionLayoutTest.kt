package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetaScreenSectionLayoutTest {

    private val groupedEpisodes = MetaScreenSectionItem(
        key = MetaScreenSectionKey.EPISODES,
        title = "Episodes",
        description = "Episode list",
        enabled = true,
        order = 0,
        tabGroup = 3,
    )

    @Test
    fun `list episodes render outside a multi-section tab group`() {
        assertNull(groupedEpisodes.tabGroupForRendering(MetaEpisodeCardStyle.List))
    }

    @Test
    fun `horizontal episodes retain their configured tab group`() {
        assertEquals(3, groupedEpisodes.tabGroupForRendering(MetaEpisodeCardStyle.Horizontal))
    }

    @Test
    fun `other list sections retain their configured tab group`() {
        val groupedCast = groupedEpisodes.copy(key = MetaScreenSectionKey.CAST)

        assertEquals(3, groupedCast.tabGroupForRendering(MetaEpisodeCardStyle.List))
    }
}
