package com.nuvio.app.features.usenet

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** NuvioTV's tests of the same rules. */
class BuiltInUsenetOrderTest {
    private val urls = listOf("https://a.example", "https://b.example", "https://c.example")

    @Test
    fun neverPlacedItFollowsEveryAddon() {
        assertEquals(3, BuiltInUsenetOrder.index(urls, null))
    }

    @Test
    fun placedFirstItLeadsTheAddons() {
        assertEquals(0, BuiltInUsenetOrder.index(urls, emptyList()))
    }

    @Test
    fun itFollowsTheLowestAddonPlacedAboveIt() {
        assertEquals(2, BuiltInUsenetOrder.index(urls, listOf("https://a.example", "https://b.example")))
        // Reordered on another device: b went below c, so it now follows b at the end.
        assertEquals(
            3,
            BuiltInUsenetOrder.index(
                listOf("https://a.example", "https://c.example", "https://b.example"),
                listOf("https://a.example", "https://b.example"),
            ),
        )
    }

    @Test
    fun aRemovedAddonLeavesItAfterTheOneAboveThat() {
        assertEquals(1, BuiltInUsenetOrder.index(listOf("https://a.example", "https://c.example"), listOf("https://a.example", "https://b.example")))
        assertEquals(0, BuiltInUsenetOrder.index(listOf("https://c.example"), listOf("https://a.example")))
    }

    @Test
    fun addonUrlsMatchWithoutCaseOrATrailingSlash() {
        assertEquals(1, BuiltInUsenetOrder.index(urls, listOf("HTTPS://A.example/")))
    }

    @Test
    fun movingBuiltInUsenetChangesOnlyItsPlace() {
        val up = BuiltInUsenetOrder.move(urls, usenetIndex = 3, url = null, direction = -1)!!
        assertEquals(urls, up.addonUrls)
        assertEquals(listOf("https://a.example", "https://b.example"), up.addonsAbove)

        val down = BuiltInUsenetOrder.move(urls, usenetIndex = 0, url = null, direction = 1)!!
        assertEquals(urls, down.addonUrls)
        assertEquals(listOf("https://a.example"), down.addonsAbove)
    }

    @Test
    fun movingAnAddonPastBuiltInUsenetChangesOnlyItsPlace() {
        val move = BuiltInUsenetOrder.move(urls, usenetIndex = 1, url = "https://b.example", direction = -1)!!
        assertEquals(urls, move.addonUrls)
        assertEquals(listOf("https://a.example", "https://b.example"), move.addonsAbove)
    }

    @Test
    fun movingAnAddonPastAnotherReordersTheAddons() {
        val move = BuiltInUsenetOrder.move(urls, usenetIndex = 3, url = "https://c.example", direction = -1)!!
        assertEquals(listOf("https://a.example", "https://c.example", "https://b.example"), move.addonUrls)
    }

    @Test
    fun nothingMovesPastEitherEnd() {
        assertNull(BuiltInUsenetOrder.move(urls, usenetIndex = 0, url = null, direction = -1))
        assertNull(BuiltInUsenetOrder.move(urls, usenetIndex = 0, url = "https://c.example", direction = 1))
    }

    @Test
    fun streamGroupsFollowBuiltInUsenetsPlace() {
        val names = listOf("Addon 0", "Addon 1", "Addon 2")
        val groups = (names + BUILT_IN_USENET_GROUP_NAME + "A plugin").map {
            AddonStreamGroup(addonName = it, addonId = it, streams = emptyList())
        }
        val order = BuiltInUsenetOrder.sourceOrder(names, urls, BUILT_IN_USENET_GROUP_NAME, listOf("https://a.example"))
        assertEquals(
            listOf("Addon 0", BUILT_IN_USENET_GROUP_NAME, "Addon 1", "Addon 2", "A plugin"),
            StreamAutoPlaySelector.orderAddonStreams(groups, order).map { it.addonName },
        )
        // Never placed, or with no search, it follows the addons as before.
        assertEquals(names + BUILT_IN_USENET_GROUP_NAME, BuiltInUsenetOrder.sourceOrder(names, urls, BUILT_IN_USENET_GROUP_NAME, null))
        assertEquals(names, BuiltInUsenetOrder.sourceOrder(names, urls, null, emptyList()))
    }
}
