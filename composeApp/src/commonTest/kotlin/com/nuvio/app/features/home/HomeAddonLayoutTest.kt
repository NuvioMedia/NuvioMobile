package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeAddonLayoutTest {
    @Test
    fun `default add-on row ordering groups unpinned catalogs by add-on`() {
        val definitions = listOf(
            definition("A", "one"),
            definition("C", "one"),
            definition("B", "one"),
            definition("C", "two"),
            definition("D", "one"),
        )

        assertEquals(
            listOf("A:one", "C:one", "C:two", "B:one", "D:one"),
            orderHomeCatalogDefinitions(definitions, snapshot(definitions)).map(::label),
        )
    }

    @Test
    fun `saved add-on order groups its unpinned rows together`() {
        val definitions = listOf(
            definition("A", "one"),
            definition("C", "one"),
            definition("B", "one"),
            definition("C", "two"),
            definition("D", "one"),
        )
        val state = snapshot(
            definitions = definitions,
            layouts = mapOf("C" to HomeAddonLayoutPreference(
                mode = HomeAddonLayoutMode.LOCKED,
                order = 1,
                anchorOrder = 1,
            )),
        )

        assertEquals(
            listOf("A:one", "C:one", "C:two", "B:one", "D:one"),
            orderHomeCatalogDefinitions(definitions, state).map(::label),
        )
    }

    @Test
    fun `legacy anchors no longer override the new add-on row order`() {
        val definitions = listOf(
            definition("A", "one"),
            definition("B", "one"),
            definition("C", "new"),
            definition("D", "one"),
        )
        val state = snapshot(
            definitions = definitions,
            layouts = mapOf("C" to HomeAddonLayoutPreference(
                mode = HomeAddonLayoutMode.LOCKED,
                order = 4,
                anchorOrder = 2,
            )),
            explicitOrders = mapOf(
                "A:movie:one" to 0,
                "B:movie:one" to 1,
                "C:movie:new" to 5,
                "D:movie:one" to 3,
            ),
        )

        assertEquals(
            listOf("A:one", "B:one", "D:one", "C:new"),
            orderHomeCatalogDefinitions(definitions, state).map(::label),
        )
    }

    @Test
    fun `unpinned rows stay grouped while individually pinned rows keep their placement`() {
        val definitions = listOf(
            definition("A", "one"),
            definition("C", "one"),
            definition("B", "one"),
            definition("C", "two"),
            definition("D", "one"),
            definition("C", "new"),
        )
        val anchor = "B:movie:one"
        val locked = snapshot(
            definitions = definitions,
            layouts = mapOf("C" to HomeAddonLayoutPreference(
                mode = HomeAddonLayoutMode.LOCKED,
                order = 1,
                anchorOrder = 1,
                anchorKey = anchor,
            )),
        )
        assertEquals(
            listOf("A:one", "C:one", "C:two", "C:new", "B:one", "D:one"),
            orderHomeCatalogDefinitions(definitions, locked).map(::label),
        )

        val individuallyPinned = locked.copy(
            preferences = locked.preferences + (
                "C:movie:one" to locked.preferences.getValue("C:movie:one").copy(
                    rowPlacement = HomeCatalogRowPlacement.PINNED_TOP,
                )
            ),
        )
        assertEquals(
            listOf("C:one", "A:one", "C:two", "C:new", "B:one", "D:one"),
            orderHomeCatalogDefinitions(definitions, individuallyPinned).map(::label),
        )
    }

    @Test
    fun `legacy add-on placement modes no longer override individual row pins`() {
        val definitions = listOf(
            definition("A", "one"),
            definition("C", "one"),
            definition("B", "one"),
            definition("C", "two"),
            definition("D", "one"),
        )
        val top = snapshot(
            definitions = definitions,
            layouts = mapOf("C" to HomeAddonLayoutPreference(
                mode = HomeAddonLayoutMode.PINNED_TOP,
                order = 1,
                anchorOrder = 1,
            )),
        )
        val bottom = snapshot(
            definitions = definitions,
            layouts = mapOf("C" to HomeAddonLayoutPreference(
                mode = HomeAddonLayoutMode.PINNED_BOTTOM,
                order = 1,
                anchorOrder = 1,
            )),
        )

        assertEquals(
            listOf("A:one", "C:one", "C:two", "B:one", "D:one"),
            orderHomeCatalogDefinitions(definitions, top).map(::label),
        )
        assertEquals(
            listOf("A:one", "C:one", "C:two", "B:one", "D:one"),
            orderHomeCatalogDefinitions(definitions, bottom).map(::label),
        )
    }

    @Test
    fun `actual home settings order follows saved add-on row order`() {
        val items = listOf(
            settingItem("A", "one", 0),
            settingItem("C", "one", 1),
            settingItem("B", "one", 2),
            settingItem("C", "two", 3),
            settingItem("D", "one", 4),
        )
        val addonIds = items.filterNot { it.isCollection }
            .associate { it.key to it.key.substringBefore(":movie:") }
        val layouts = mapOf(
            "C" to HomeAddonLayoutPreference(
                mode = HomeAddonLayoutMode.PINNED_TOP,
                order = 1,
                anchorOrder = 1,
            ),
        )

        assertEquals(
            listOf("A:one", "C:one", "C:two", "B:one", "D:one"),
            orderHomeCatalogSettingsItems(items, addonIds, layouts).map(::settingLabel),
        )
    }

    @Test
    fun `home settings retain grouped ordering when catalog identifiers change`() {
        val items = listOf(
            settingItem("A", "one", 0),
            settingItem("B", "one", 1),
            settingItem("D", "one", 3),
            settingItem("C", "new", 5),
            settingItem("C", "latest", 6),
        )
        val addonIds = items.filterNot { it.isCollection }
            .associate { it.key to it.key.substringBefore(":movie:") }
        val layouts = mapOf(
            "C" to HomeAddonLayoutPreference(
                mode = HomeAddonLayoutMode.LOCKED,
                order = 2,
                anchorOrder = 2,
            ),
        )

        assertEquals(
            listOf("A:one", "B:one", "C:new", "C:latest", "D:one"),
            orderHomeCatalogSettingsItems(items, addonIds, layouts).map(::settingLabel),
        )
    }

    @Test
    fun `pinned collections retain priority when add-on order changes`() {
        val items = listOf(
            HomeCatalogSettingsItem(
                key = "collection_favorites",
                defaultTitle = "Favorites",
                addonName = "Favorites",
                order = 0,
                isCollection = true,
                isPinnedToTop = true,
            ),
            settingItem("A", "one", 1),
            settingItem("C", "one", 2),
            settingItem("B", "one", 3),
        )
        val addonIds = items.filterNot { it.isCollection }
            .associate { it.key to it.key.substringBefore(":movie:") }
        val layouts = mapOf(
            "C" to HomeAddonLayoutPreference(
                mode = HomeAddonLayoutMode.PINNED_TOP,
                order = 2,
                anchorOrder = 2,
            ),
        )

        assertEquals(
            listOf("collection_favorites", "A:one", "C:one", "B:one"),
            orderHomeCatalogSettingsItems(items, addonIds, layouts).map(::settingLabel),
        )
    }

    @Test
    fun `individual row pins are removed from addon group and ordered in pin sections`() {
        val items = listOf(
            settingItem("A", "one", 0),
            settingItem("A", "two", 1).copy(rowPlacement = HomeCatalogRowPlacement.PINNED_TOP),
            HomeCatalogSettingsItem(
                key = "collection_favorites",
                defaultTitle = "Favorites",
                addonName = "Favorites",
                order = 2,
                isCollection = true,
            ),
            settingItem("B", "one", 3).copy(rowPlacement = HomeCatalogRowPlacement.PINNED_BOTTOM),
        )
        val addonIds = items.filterNot { it.isCollection }
            .associate { it.key to it.key.substringBefore(":movie:") }

        val ordered = orderHomeCatalogSettingsItems(items, addonIds, emptyMap())

        assertEquals(
            listOf("A:two", "A:one", "collection_favorites", "B:one"),
            ordered.map(::settingLabel),
        )
        assertEquals(1, ordered.count { it.key == "A:two" })
        assertEquals(1, ordered.count { it.key == "B:one" })
    }

    @Test
    fun `pin-at-position rows stay independent of add-on ordering`() {
        val items = listOf(
            settingItem("A", "one", 0),
            settingItem("C", "one", 1),
            settingItem("B", "one", 2),
            settingItem("C", "two", 3).copy(rowPlacement = HomeCatalogRowPlacement.PINNED_POSITION),
            settingItem("D", "one", 4),
            settingItem("C", "new", 5),
        )
        val addonIds = items.filterNot { it.isCollection }
            .associate { it.key to it.key.substringBefore(":movie:") }
        val layouts = mapOf("C" to HomeAddonLayoutPreference(
            mode = HomeAddonLayoutMode.PINNED_BOTTOM,
            order = 1,
            anchorOrder = 1,
        ))
        val expected = listOf("A:one", "C:one", "C:new", "B:one", "C:two", "D:one")

        assertEquals(expected, orderHomeCatalogSettingsItems(items, addonIds, layouts).map(::settingLabel))

        val definitions = listOf(
            definition("A", "one"),
            definition("C", "one"),
            definition("B", "one"),
            definition("C", "two"),
            definition("D", "one"),
            definition("C", "new"),
        )
        val baseState = snapshot(definitions, layouts = layouts)
        val state = baseState.copy(
            preferences = baseState.preferences + (
                "C:movie:two" to baseState.preferences.getValue("C:movie:two").copy(
                    rowPlacement = HomeCatalogRowPlacement.PINNED_POSITION,
                )
            ),
        )
        assertEquals(expected, orderHomeCatalogDefinitions(definitions, state).map(::label))
    }

    @Test
    fun `pinned top and bottom rows can each be reordered without changing normal rows`() {
        val items = listOf(
            settingItem("A", "top-one", 0).copy(rowPlacement = HomeCatalogRowPlacement.PINNED_TOP),
            settingItem("B", "normal", 1),
            settingItem("C", "top-two", 2).copy(rowPlacement = HomeCatalogRowPlacement.PINNED_TOP),
            settingItem("D", "bottom-one", 3).copy(rowPlacement = HomeCatalogRowPlacement.PINNED_BOTTOM),
            settingItem("E", "bottom-two", 4).copy(rowPlacement = HomeCatalogRowPlacement.PINNED_BOTTOM),
        )
        val addonIds = items.filterNot { it.isCollection }
            .associate { it.key to it.key.substringBefore(":movie:") }

        val reordered = items.map { item ->
            when (item.key) {
                "C:movie:top-two" -> item.copy(order = 0)
                "A:movie:top-one" -> item.copy(order = 2)
                "E:movie:bottom-two" -> item.copy(order = 3)
                "D:movie:bottom-one" -> item.copy(order = 4)
                else -> item
            }
        }

        assertEquals(
            listOf("C:top-two", "A:top-one", "B:normal", "E:bottom-two", "D:bottom-one"),
            orderHomeCatalogSettingsItems(reordered, addonIds, emptyMap()).map(::settingLabel),
        )
    }


    @Test
    fun `moving all rows groups pinned and unpinned catalogs by add-on order`() {
        val definitions = listOf(
            definition("A", "one"),
            definition("C", "top"),
            definition("B", "one"),
            definition("C", "normal"),
            definition("D", "one"),
            definition("C", "bottom"),
        )
        val base = snapshot(
            definitions = definitions,
            layouts = mapOf(
                "C" to HomeAddonLayoutPreference(HomeAddonLayoutMode.NORMAL, order = 0, anchorOrder = 0),
                "A" to HomeAddonLayoutPreference(HomeAddonLayoutMode.NORMAL, order = 1, anchorOrder = 1),
                "B" to HomeAddonLayoutPreference(HomeAddonLayoutMode.NORMAL, order = 2, anchorOrder = 2),
                "D" to HomeAddonLayoutPreference(HomeAddonLayoutMode.NORMAL, order = 3, anchorOrder = 3),
            ),
        )
        val pinned = base.copy(
            addonRowOrderPolicy = HomeAddonRowOrderPolicy.MOVE_ALL_ROWS,
            preferences = base.preferences +
                ("C:movie:top" to base.preferences.getValue("C:movie:top").copy(
                    rowPlacement = HomeCatalogRowPlacement.PINNED_TOP,
                )) +
                ("C:movie:bottom" to base.preferences.getValue("C:movie:bottom").copy(
                    rowPlacement = HomeCatalogRowPlacement.PINNED_BOTTOM,
                )),
        )

        assertEquals(
            listOf("C:top", "C:normal", "C:bottom", "A:one", "B:one", "D:one"),
            orderHomeCatalogDefinitions(definitions, pinned).map(::label),
        )
    }

    @Test
    fun `unpinned-only add-on ordering leaves individual row pins in their own sections`() {
        val definitions = listOf(
            definition("A", "one"),
            definition("C", "top"),
            definition("B", "one"),
            definition("C", "normal"),
            definition("D", "one"),
            definition("C", "bottom"),
        )
        val base = snapshot(
            definitions = definitions,
            layouts = mapOf(
                "C" to HomeAddonLayoutPreference(HomeAddonLayoutMode.NORMAL, order = 0, anchorOrder = 0),
                "A" to HomeAddonLayoutPreference(HomeAddonLayoutMode.NORMAL, order = 1, anchorOrder = 1),
                "B" to HomeAddonLayoutPreference(HomeAddonLayoutMode.NORMAL, order = 2, anchorOrder = 2),
                "D" to HomeAddonLayoutPreference(HomeAddonLayoutMode.NORMAL, order = 3, anchorOrder = 3),
            ),
        )
        val pinned = base.copy(
            addonRowOrderPolicy = HomeAddonRowOrderPolicy.MOVE_UNPINNED_ROWS,
            preferences = base.preferences +
                ("C:movie:top" to base.preferences.getValue("C:movie:top").copy(
                    rowPlacement = HomeCatalogRowPlacement.PINNED_TOP,
                )) +
                ("C:movie:bottom" to base.preferences.getValue("C:movie:bottom").copy(
                    rowPlacement = HomeCatalogRowPlacement.PINNED_BOTTOM,
                )),
        )

        assertEquals(
            listOf("C:top", "C:normal", "A:one", "B:one", "D:one", "C:bottom"),
            orderHomeCatalogDefinitions(definitions, pinned).map(::label),
        )
    }

    @Test
    fun `upcoming-only order leaves existing rows in place and groups new rows by add-on order`() {
        val definitions = listOf(
            definition("A", "existing"),
            definition("C", "existing"),
            definition("B", "existing"),
            definition("A", "new"),
            definition("C", "new"),
        )
        val base = snapshot(
            definitions = definitions,
            layouts = mapOf(
                "C" to HomeAddonLayoutPreference(HomeAddonLayoutMode.NORMAL, order = 0, anchorOrder = 0),
                "A" to HomeAddonLayoutPreference(HomeAddonLayoutMode.NORMAL, order = 1, anchorOrder = 1),
                "B" to HomeAddonLayoutPreference(HomeAddonLayoutMode.NORMAL, order = 2, anchorOrder = 2),
            ),
            explicitOrders = mapOf(
                "A:movie:existing" to 0,
                "C:movie:existing" to 1,
                "B:movie:existing" to 2,
                "A:movie:new" to 3,
                "C:movie:new" to 4,
            ),
        )
        val futureOnly = base.copy(
            addonRowOrderPolicy = HomeAddonRowOrderPolicy.UPCOMING_ROWS_ONLY,
            upcomingRowKeys = setOf("A:movie:new", "C:movie:new"),
        )

        assertEquals(
            listOf("A:existing", "C:existing", "B:existing", "C:new", "A:new"),
            orderHomeCatalogDefinitions(definitions, futureOnly).map(::label),
        )
    }

    private fun settingItem(addonId: String, catalogId: String, order: Int) =
        HomeCatalogSettingsItem(
            key = "$addonId:movie:$catalogId",
            defaultTitle = "$addonId $catalogId",
            addonName = "Addon $addonId",
            order = order,
            addonId = addonId,
        )

    private fun settingLabel(item: HomeCatalogSettingsItem): String =
        if (item.isCollection) item.key else
            item.key.substringBefore(":movie:") + ":" + item.key.substringAfterLast(":")

    private fun snapshot(
        definitions: List<HomeCatalogDefinition>,
        layouts: Map<String, HomeAddonLayoutPreference> = emptyMap(),
        explicitOrders: Map<String, Int> = emptyMap(),
    ): HomeCatalogSettingsSnapshot = HomeCatalogSettingsSnapshot(
        heroEnabled = true,
        showCatalogType = true,
        hideUnreleasedContent = false,
        preferences = definitions.associate { definition ->
            definition.key to HomeCatalogPreference(
                customTitle = "",
                enabled = true,
                heroSourceEnabled = true,
                order = explicitOrders[definition.key]
                    ?: definitions.indexOf(definition),
            )
        },
        addonLayouts = layouts,
    )

    private fun definition(addonId: String, catalogId: String): HomeCatalogDefinition =
        HomeCatalogDefinition(
            key = "$addonId:movie:$catalogId",
            defaultTitle = "$addonId $catalogId",
            catalogName = catalogId,
            addonName = "Addon $addonId",
            manifestUrl = "https://example.com/$addonId/manifest.json",
            type = "movie",
            catalogId = catalogId,
            supportsPagination = false,
            descriptorSignature = "test",
        )

    private fun label(definition: HomeCatalogDefinition): String =
        definition.key.substringBefore(":movie:") + ":" + definition.catalogId
}
