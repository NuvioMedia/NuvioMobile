package com.nuvio.app.features.collection

import kotlin.test.Test
import kotlin.test.assertEquals

class FolderCatalogTabLabelTest {
    // home_catalog_default_title in English.
    private val withType = { name: String, type: String -> "$name - $type" }

    @Test
    fun `type follows a dash only when show catalog type is on`() {
        assertEquals("Popular - Movies", folderCatalogTabLabel("Popular", "Movies", null, showCatalogType = true, withType))
        assertEquals("Popular", folderCatalogTabLabel("Popular", "Movies", null, showCatalogType = false, withType))
    }

    @Test
    fun `genre stays with the name, before the type`() {
        assertEquals("Popular · Drama - Series", folderCatalogTabLabel("Popular", "Series", "Drama", showCatalogType = true, withType))
        assertEquals("Popular · Drama", folderCatalogTabLabel("Popular", "Series", "Drama", showCatalogType = false, withType))
    }

    @Test
    fun `blank type adds nothing`() {
        assertEquals("Popular", folderCatalogTabLabel("Popular", "", null, showCatalogType = true, withType))
    }
}
