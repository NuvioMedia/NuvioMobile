package com.nuvio.app.features.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvatarAccessTest {
    @Test
    fun supporterAvatarsAreOnlyPublishedWithAccess() {
        val standard = AvatarCatalogItem(id = "standard", storagePath = "standard.png")
        val supporter = AvatarCatalogItem(
            id = "supporter-gold",
            storagePath = "gold.png",
            localImageUrl = "file:///cache/supporter-gold.png",
            memberOnly = true,
        )

        assertEquals(
            listOf(standard),
            availableAvatarCatalog(listOf(standard), listOf(supporter), hasMemberAccess = false),
        )
        assertEquals(
            listOf(standard, supporter),
            availableAvatarCatalog(listOf(standard), listOf(supporter), hasMemberAccess = true),
        )
    }

    @Test
    fun supporterAvatarUsesAuthenticatedLocalAsset() {
        val supporter = AvatarCatalogItem(
            id = "supporter-gold",
            storagePath = "private/gold.png",
            localImageUrl = "file:///cache/supporter-gold.png",
            memberOnly = true,
        )

        assertEquals("file:///cache/supporter-gold.png", avatarImageUrl(supporter))
        assertNull(avatarImageUrl(supporter.copy(localImageUrl = null)))
    }

    @Test
    fun supporterAvatarNormalizesLegacySingleSlashFileUri() {
        val supporter = AvatarCatalogItem(
            id = "supporter-gold",
            storagePath = "private/gold.png",
            localImageUrl = "file:/C:/cache/supporter-gold.png",
            memberOnly = true,
        )

        assertEquals("file:///C:/cache/supporter-gold.png", avatarImageUrl(supporter))
    }
}
