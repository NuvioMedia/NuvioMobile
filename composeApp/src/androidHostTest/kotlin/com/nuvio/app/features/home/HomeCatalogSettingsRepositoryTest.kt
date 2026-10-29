package com.nuvio.app.features.home

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionFolder
import com.nuvio.app.features.collection.CollectionMobileSettingsRepository
import com.nuvio.app.features.collection.CollectionMobileSettingsStorage
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.CollectionSource
import com.nuvio.app.features.collection.CollectionStorage
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeCatalogSettingsRepositoryTest {
    private lateinit var preferences: RecordingPreferences
    private lateinit var originalLocale: Locale

    @BeforeTest
    fun initialize() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        val context = RuntimeEnvironment.getApplication()
        val stored = context.getSharedPreferences("nuvio_home_catalog_settings", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
        preferences = RecordingPreferences(stored)
        HomeCatalogSettingsStorage.initialize(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        })
        CollectionRepository.clearLocalState()
        HomeRepository.clear()
        HomeCatalogSettingsRepository.clearLocalState()
        HomeCatalogSettingsRepository.setHeroEnabled(false)
    }

    @AfterTest
    fun clearState() {
        Locale.setDefault(originalLocale)
        HomeCatalogSettingsRepository.clearLocalState()
        CollectionRepository.clearLocalState()
        HomeRepository.clear()
    }

    @Test
    fun unchangedCatalogsDoNotRewriteSettings() {
        val addons = listOf(addon())
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        val editCount = preferences.editCount

        HomeCatalogSettingsRepository.syncCatalogs(addons.map { it.copy() })

        assertEquals(editCount, preferences.editCount)
        assertEquals("test:movie:popular", HomeCatalogSettingsRepository.uiState.value.items.single().key)
    }

    @Test
    fun rowPlacementSurvivesCatalogRefreshAndLocalReload() {
        val initial = listOf(addon(listOf("popular", "latest")))
        HomeCatalogSettingsRepository.syncCatalogs(initial)
        val positionKey = "test:movie:popular"
        val bottomKey = "test:movie:latest"

        HomeCatalogSettingsRepository.setRowPlacement(positionKey, HomeCatalogRowPlacement.PINNED_POSITION)
        HomeCatalogSettingsRepository.setRowPlacement(bottomKey, HomeCatalogRowPlacement.PINNED_BOTTOM)
        assertEquals(
            HomeCatalogRowPlacement.PINNED_POSITION,
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == positionKey }.rowPlacement,
        )
        assertEquals(
            HomeCatalogRowPlacement.PINNED_BOTTOM,
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == bottomKey }.rowPlacement,
        )

        val refreshed = listOf(addon(listOf("popular", "latest", "new")))
        HomeCatalogSettingsRepository.syncCatalogs(refreshed)
        assertEquals(
            HomeCatalogRowPlacement.PINNED_POSITION,
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == positionKey }.rowPlacement,
        )
        assertEquals(
            HomeCatalogRowPlacement.PINNED_BOTTOM,
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == bottomKey }.rowPlacement,
        )

        HomeCatalogSettingsRepository.clearLocalState()
        HomeCatalogSettingsRepository.syncCatalogs(refreshed)
        assertEquals(
            HomeCatalogRowPlacement.PINNED_POSITION,
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == positionKey }.rowPlacement,
        )
        assertEquals(
            HomeCatalogRowPlacement.PINNED_BOTTOM,
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == bottomKey }.rowPlacement,
        )
    }

    @Test
    fun movingAnotherRowAcrossPositionPinnedRowDoesNotDisplaceTheAnchor() {
        HomeCatalogSettingsRepository.syncCatalogs(listOf(addon(listOf("popular", "middle", "latest"))))
        val pinnedKey = "test:movie:middle"
        HomeCatalogSettingsRepository.setRowPlacement(
            pinnedKey,
            HomeCatalogRowPlacement.PINNED_POSITION,
        )

        val before = HomeCatalogSettingsRepository.uiState.value.items.map { it.key }
        val pinnedIndex = before.indexOf(pinnedKey)
        assertTrue(pinnedIndex > 0)

        HomeCatalogSettingsRepository.moveByIndex(fromIndex = 0, toIndex = pinnedIndex)

        assertEquals(before, HomeCatalogSettingsRepository.uiState.value.items.map { it.key })
        assertEquals(
            HomeCatalogRowPlacement.PINNED_POSITION,
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == pinnedKey }.rowPlacement,
        )
    }

    @Test
    fun addonGroupLockSurvivesCatalogIdentifierChangesAndLocalReload() {
        HomeCatalogSettingsRepository.syncCatalogs(listOf(addon(listOf("old", "older"))))
        HomeCatalogSettingsRepository.setAddonLayoutMode("test", HomeAddonLayoutMode.LOCKED)
        val initialGroup = HomeCatalogSettingsRepository.uiState.value.addonGroups.single()
        assertEquals(HomeAddonLayoutMode.LOCKED, initialGroup.mode)

        HomeCatalogSettingsRepository.syncCatalogs(listOf(addon(listOf("new", "latest"))))
        val refreshedGroup = HomeCatalogSettingsRepository.uiState.value.addonGroups.single()
        assertEquals("test", refreshedGroup.addonId)
        assertEquals(HomeAddonLayoutMode.LOCKED, refreshedGroup.mode)
        assertEquals(initialGroup.anchorOrder, refreshedGroup.anchorOrder)

        HomeCatalogSettingsRepository.clearLocalState()
        HomeCatalogSettingsRepository.syncCatalogs(listOf(addon(listOf("new", "latest"))))
        val reloadedGroup = HomeCatalogSettingsRepository.uiState.value.addonGroups.single()
        assertEquals(HomeAddonLayoutMode.LOCKED, reloadedGroup.mode)
        assertEquals(initialGroup.anchorOrder, reloadedGroup.anchorOrder)
    }

    @Test
    fun unchangedCollectionsDoNotRewriteSettings() {
        val collections = listOf(collection())
        HomeCatalogSettingsRepository.syncCollections(collections)
        val editCount = preferences.editCount

        HomeCatalogSettingsRepository.syncCollections(collections.map { it.copy() })

        assertEquals(editCount, preferences.editCount)
        assertEquals("Favorites", HomeCatalogSettingsRepository.uiState.value.items.single().defaultTitle)
    }

    @Test
    fun collectionSyncReappliesInputsAfterCatalogSyncReplacedCollections() {
        val collections = listOf(collection())
        HomeCatalogSettingsRepository.syncCollections(collections)
        HomeCatalogSettingsRepository.syncCatalogs(listOf(addon()))
        assertTrue(HomeCatalogSettingsRepository.uiState.value.items.none { it.isCollection })

        HomeCatalogSettingsRepository.syncCollections(collections)

        assertEquals(
            listOf("Favorites"),
            HomeCatalogSettingsRepository.uiState.value.items.filter { it.isCollection }.map { it.defaultTitle },
        )
    }

    @Test
    fun catalogSyncReappliesInputsAfterCollectionSyncReplacedCollections() {
        val addons = listOf(addon())
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        HomeCatalogSettingsRepository.syncCollections(listOf(collection()))
        assertTrue(HomeCatalogSettingsRepository.uiState.value.items.any { it.isCollection })

        HomeCatalogSettingsRepository.syncCatalogs(addons)

        assertTrue(HomeCatalogSettingsRepository.uiState.value.items.none { it.isCollection })
        assertEquals("test:movie:popular", HomeCatalogSettingsRepository.uiState.value.items.single().key)
    }

    @Test
    fun catalogSyncPreservesPendingCollectionHeroSourceChanges(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        listOf("nuvio_addons", "nuvio_collections", "nuvio_collection_mobile_settings").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        AddonStorage.initialize(context)
        CollectionStorage.initialize(context)
        CollectionMobileSettingsStorage.initialize(context)
        AddonRepository.clearLocalState()
        CollectionMobileSettingsRepository.clearLocalState()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"id":"test","name":"Catalogs","description":"","version":"1","resources":["catalog"],
                 "types":["movie"],"catalogs":[{"type":"movie","id":"old"},{"type":"movie","id":"new"}]}
            """.trimIndent()))
            server.enqueue(MockResponse().setBody("""{"metas":[{"id":"old","type":"movie","name":"Old"}]}"""))
            server.enqueue(MockResponse().setBody("""{"metas":[{"id":"new","type":"movie","name":"New"}]}"""))
            try {
                AddonStorage.saveInstalledAddonUrls(ProfileRepository.activeProfileId, listOf(server.url("/manifest.json").toString()))
                AddonRepository.initialize()
                withTimeout(5_000) { AddonRepository.awaitManifestsLoaded() }
                val addons = AddonRepository.uiState.value.addons
                assertNotNull(addons.single().manifest)
                HomeCatalogSettingsRepository.setHeroEnabled(true)
                val initialCollection = collection().copy(
                    folders = listOf(CollectionFolder(
                        id = "movies",
                        title = "Movies",
                        sources = listOf(CollectionSource(addonId = "test", type = "movie", catalogId = "old")),
                    )),
                )
                CollectionRepository.setCollections(listOf(initialCollection))
                HomeCatalogSettingsRepository.syncCatalogs(addons)
                HomeCatalogSettingsRepository.syncCollections(CollectionRepository.collections.value)
                withTimeout(5_000) { HomeRepository.uiState.first { it.heroItems.singleOrNull()?.id == "old" } }
                val settings = HomeCatalogSettingsRepository.uiState.value
                CollectionRepository.updateCollection(initialCollection.copy(
                    folders = initialCollection.folders.map { folder ->
                        folder.copy(sources = listOf(CollectionSource(addonId = "test", type = "movie", catalogId = "new")))
                    },
                ))

                HomeCatalogSettingsRepository.syncCatalogs(addons)
                val editCount = preferences.editCount
                assertEquals(settings, HomeCatalogSettingsRepository.uiState.value)
                assertEquals("old", HomeRepository.uiState.value.heroItems.single().id)
                HomeCatalogSettingsRepository.syncCollections(CollectionRepository.collections.value)

                withTimeout(5_000) { HomeRepository.uiState.first { it.heroItems.singleOrNull()?.id == "new" } }
                assertEquals(editCount + 1, preferences.editCount)
                HomeCatalogSettingsRepository.syncCatalogs(addons)
                HomeCatalogSettingsRepository.syncCollections(CollectionRepository.collections.value)
                assertEquals(editCount + 1, preferences.editCount)
                assertEquals(3, server.requestCount)
            } finally {
                HomeRepository.clear()
                AddonRepository.clearLocalState()
                CollectionMobileSettingsRepository.clearLocalState()
            }
        }
    }

    @Test
    fun collectionSourceChangesAreAppliedWithTheSameTitleAndFolderCount() {
        val collection = collection()
        HomeCatalogSettingsRepository.syncCollections(listOf(collection))
        val editCount = preferences.editCount
        val settings = HomeCatalogSettingsRepository.uiState.value
        val changed = collection.copy(
            folders = collection.folders.map { folder ->
                folder.copy(sources = listOf(CollectionSource(addonId = "other", type = "movie", catalogId = "new")))
            },
        )

        HomeCatalogSettingsRepository.syncCollections(listOf(changed))

        assertTrue(preferences.editCount > editCount)
        assertEquals(settings, HomeCatalogSettingsRepository.uiState.value)
    }

    @Test
    fun localeChangesRebuildCatalogAndCollectionLabels() {
        val addons = listOf(addon())
        val collections = listOf(collection())
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        HomeCatalogSettingsRepository.syncCollections(collections)
        val editCount = preferences.editCount

        Locale.setDefault(Locale.FRENCH)
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        HomeCatalogSettingsRepository.syncCollections(collections)

        assertEquals(editCount + 2, preferences.editCount)
    }

    @Test
    fun profileChangesRebuildTheSameCatalogAndCollectionInputs() {
        val addons = listOf(addon())
        val collections = listOf(collection())
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        HomeCatalogSettingsRepository.syncCollections(collections)
        val settings = HomeCatalogSettingsRepository.uiState.value

        HomeCatalogSettingsRepository.onProfileChanged()
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        HomeCatalogSettingsRepository.syncCollections(collections)

        assertEquals(settings, HomeCatalogSettingsRepository.uiState.value)
    }


    @Test
    fun allRowsOptionClearsCatalogPinsButKeepsCollectionPinsAndDoesNotPersistPolicy() {
        val addons = listOf(addon(listOf("popular", "latest")))
        val favorites = collection()
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        HomeCatalogSettingsRepository.syncCollections(listOf(favorites))

        HomeCatalogSettingsRepository.setRowPlacement(
            "test:movie:popular",
            HomeCatalogRowPlacement.PINNED_POSITION,
        )
        HomeCatalogSettingsRepository.setRowPlacement(
            "test:movie:latest",
            HomeCatalogRowPlacement.PINNED_BOTTOM,
        )
        HomeCatalogSettingsRepository.setRowPlacement(
            "collection_favorites",
            HomeCatalogRowPlacement.PINNED_BOTTOM,
        )

        HomeCatalogSettingsRepository.saveAddonRowOrder(
            addonIds = listOf("test"),
            policy = HomeAddonRowOrderPolicy.MOVE_ALL_ROWS,
        )

        val items = HomeCatalogSettingsRepository.uiState.value.items
        assertEquals(
            HomeCatalogRowPlacement.NORMAL,
            items.single { it.key == "test:movie:popular" }.rowPlacement,
        )
        assertEquals(
            HomeCatalogRowPlacement.NORMAL,
            items.single { it.key == "test:movie:latest" }.rowPlacement,
        )
        assertEquals(
            HomeCatalogRowPlacement.PINNED_BOTTOM,
            items.single { it.key == "collection_favorites" }.rowPlacement,
        )
        assertEquals(
            HomeAddonRowOrderPolicy.MOVE_UNPINNED_ROWS,
            HomeCatalogSettingsRepository.uiState.value.addonRowOrderPolicy,
        )

        HomeCatalogSettingsRepository.clearLocalState()
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        HomeCatalogSettingsRepository.syncCollections(listOf(favorites))
        assertEquals(
            HomeAddonRowOrderPolicy.MOVE_UNPINNED_ROWS,
            HomeCatalogSettingsRepository.uiState.value.addonRowOrderPolicy,
        )
        assertEquals(
            HomeCatalogRowPlacement.PINNED_BOTTOM,
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == "collection_favorites" }.rowPlacement,
        )
    }

    @Test
    fun unpinnedRowsOptionPreservesRowPinsAndAppliesOrderWithoutPersistingPolicy() {
        val addons = listOf(
            addon(listOf("popular", "latest"), addonId = "a", addonName = "A"),
            addon(listOf("popular"), addonId = "b", addonName = "B"),
        )
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        HomeCatalogSettingsRepository.setRowPlacement(
            "a:movie:popular",
            HomeCatalogRowPlacement.PINNED_TOP,
        )

        HomeCatalogSettingsRepository.saveAddonRowOrder(
            addonIds = listOf("b", "a"),
            policy = HomeAddonRowOrderPolicy.MOVE_UNPINNED_ROWS,
        )

        assertEquals(
            HomeCatalogRowPlacement.PINNED_TOP,
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == "a:movie:popular" }.rowPlacement,
        )
        assertEquals(
            HomeAddonRowOrderPolicy.MOVE_UNPINNED_ROWS,
            HomeCatalogSettingsRepository.uiState.value.addonRowOrderPolicy,
        )
        assertEquals(
            listOf("b", "a"),
            HomeCatalogSettingsRepository.uiState.value.addonGroups.map { it.addonId },
        )
    }

    @Test
    fun upcomingRowsOptionKeepsCurrentOrderAndUsesNewAddonOrderForFutureCatalogs() {
        val initialAddons = listOf(
            addon(listOf("popular"), addonId = "a", addonName = "A"),
            addon(listOf("popular"), addonId = "b", addonName = "B"),
        )
        HomeCatalogSettingsRepository.syncCatalogs(initialAddons)
        val before = HomeCatalogSettingsRepository.uiState.value.items.map { it.key }
        assertEquals(listOf("a:movie:popular", "b:movie:popular"), before)

        HomeCatalogSettingsRepository.saveAddonRowOrder(
            addonIds = listOf("b", "a"),
            policy = HomeAddonRowOrderPolicy.UPCOMING_ROWS_ONLY,
        )
        assertEquals(before, HomeCatalogSettingsRepository.uiState.value.items.map { it.key })
        assertEquals(
            HomeAddonRowOrderPolicy.MOVE_UNPINNED_ROWS,
            HomeCatalogSettingsRepository.uiState.value.addonRowOrderPolicy,
        )

        val refreshedAddons = listOf(
            addon(listOf("popular", "new"), addonId = "a", addonName = "A"),
            addon(listOf("popular", "new"), addonId = "b", addonName = "B"),
        )
        HomeCatalogSettingsRepository.syncCatalogs(refreshedAddons)

        assertEquals(
            listOf("a:movie:popular", "b:movie:popular", "b:movie:new", "a:movie:new"),
            HomeCatalogSettingsRepository.uiState.value.items.map { it.key },
        )
        assertEquals(
            HomeAddonRowOrderPolicy.MOVE_UNPINNED_ROWS,
            HomeCatalogSettingsRepository.uiState.value.addonRowOrderPolicy,
        )

        HomeCatalogSettingsRepository.clearLocalState()
        HomeCatalogSettingsRepository.syncCatalogs(refreshedAddons)
        assertEquals(
            listOf("a:movie:popular", "b:movie:popular", "b:movie:new", "a:movie:new"),
            HomeCatalogSettingsRepository.uiState.value.items.map { it.key },
        )
    }

    @Test
    fun addonCatalogVisibilityIsIndependentAndPersistsAcrossReloadAndSync() {
        val managedAddon = addon(listOf("popular", "latest"))
        HomeCatalogSettingsRepository.syncCatalogs(listOf(managedAddon))

        HomeCatalogSettingsRepository.setAddonCatalogsEnabled("test", false)

        val state = HomeCatalogSettingsRepository.uiState.value
        assertFalse(state.addonGroups.single { it.addonId == "test" }.catalogsEnabled)
        assertTrue(state.items.filterNot { it.isCollection }.all { it.enabled })
        assertTrue(state.items.filterNot { it.isCollection }.none { it.isVisible })
        assertTrue(
            orderHomeCatalogDefinitions(
                definitions = buildHomeCatalogDefinitions(listOf(managedAddon)),
                snapshot = HomeCatalogSettingsRepository.snapshot(),
            ).none { it.key.startsWith("test:") },
        )
        assertFalse(
            HomeCatalogSettingsRepository.exportToSyncPayload()
                .addonLayouts
                ?.single { it.addonId == "test" }
                ?.catalogsEnabled ?: true,
        )

        HomeCatalogSettingsRepository.clearLocalState()
        HomeCatalogSettingsRepository.syncCatalogs(listOf(managedAddon))
        assertFalse(HomeCatalogSettingsRepository.uiState.value.addonGroups.single().catalogsEnabled)
        assertTrue(HomeCatalogSettingsRepository.uiState.value.items.filterNot { it.isCollection }.all { it.enabled })

        HomeCatalogSettingsRepository.setAddonCatalogsEnabled("test", true)
        assertTrue(HomeCatalogSettingsRepository.uiState.value.items.filterNot { it.isCollection }.all { it.isVisible })
        assertTrue(managedAddon.enabled)
    }

    @Test
    fun collectionCannotMoveAcrossPinnedCatalogWithoutExplicitPinChange() {
        val managedAddon = addon(listOf("popular", "latest"))
        HomeCatalogSettingsRepository.syncCatalogs(listOf(managedAddon))
        HomeCatalogSettingsRepository.syncCollections(listOf(collection()))
        HomeCatalogSettingsRepository.setRowPlacement(
            "test:movie:popular",
            HomeCatalogRowPlacement.PINNED_TOP,
        )
        val before = HomeCatalogSettingsRepository.uiState.value.items.map { it.key }

        assertFalse(
            HomeCatalogSettingsRepository.canMoveByKeys(
                "collection_favorites",
                "test:movie:popular",
            ),
        )
        HomeCatalogSettingsRepository.moveByKeys(
            fromKey = "collection_favorites",
            toKey = "test:movie:popular",
        )

        assertEquals(before, HomeCatalogSettingsRepository.uiState.value.items.map { it.key })
        assertEquals(
            HomeCatalogRowPlacement.NORMAL,
            HomeCatalogSettingsRepository.uiState.value.items.single {
                it.key == "collection_favorites"
            }.rowPlacement,
        )
        assertEquals(
            HomeCatalogRowPlacement.PINNED_TOP,
            HomeCatalogSettingsRepository.uiState.value.items.single {
                it.key == "test:movie:popular"
            }.rowPlacement,
        )
    }

    @Test
    fun keyBasedReorderUsesOnlyVisibleRowsWhenHiddenRowsAreSeparated() {
        val managedAddon = addon(listOf("first", "second"))
        HomeCatalogSettingsRepository.syncCatalogs(listOf(managedAddon))
        HomeCatalogSettingsRepository.syncCollections(listOf(collection()))
        HomeCatalogSettingsRepository.setEnabled("test:movie:first", false)

        val beforeVisible = HomeCatalogSettingsRepository.uiState.value.items
            .filter { it.isVisible }
            .map { it.key }
        assertEquals(listOf("test:movie:second", "collection_favorites"), beforeVisible)

        HomeCatalogSettingsRepository.moveByKeys(
            fromKey = "collection_favorites",
            toKey = "test:movie:second",
        )

        assertEquals(
            listOf("collection_favorites", "test:movie:second"),
            HomeCatalogSettingsRepository.uiState.value.items.filter { it.isVisible }.map { it.key },
        )
        assertFalse(HomeCatalogSettingsRepository.uiState.value.items.single { it.key == "test:movie:first" }.isVisible)
    }

    @Test
    fun dragReorderDoesNotChangeAddonPriorityAndPersistsOnCommit() {
        val addons = listOf(
            addon(listOf("popular"), addonId = "a", addonName = "A"),
            addon(listOf("popular"), addonId = "b", addonName = "B"),
        )
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        assertEquals(
            listOf("a:movie:popular", "b:movie:popular"),
            HomeCatalogSettingsRepository.uiState.value.items.map { it.key },
        )

        HomeCatalogSettingsRepository.moveByKeys(
            fromKey = "a:movie:popular",
            toKey = "b:movie:popular",
            commitImmediately = false,
        )
        assertEquals(
            listOf("b:movie:popular", "a:movie:popular"),
            HomeCatalogSettingsRepository.uiState.value.items.map { it.key },
        )
        assertEquals(
            listOf("a", "b"),
            HomeCatalogSettingsRepository.uiState.value.addonGroups.map { it.addonId },
        )

        HomeCatalogSettingsRepository.commitPendingRowOrder()
        HomeCatalogSettingsRepository.clearLocalState()
        HomeCatalogSettingsRepository.syncCatalogs(addons)
        assertEquals(
            listOf("b:movie:popular", "a:movie:popular"),
            HomeCatalogSettingsRepository.uiState.value.items.map { it.key },
        )
        assertEquals(
            listOf("a", "b"),
            HomeCatalogSettingsRepository.uiState.value.addonGroups.map { it.addonId },
        )
    }

    @Test
    fun pinnedCollectionCanReorderWithinPinnedTopSectionWithoutLosingPin() {
        val managedAddon = addon(listOf("popular", "latest"))
        HomeCatalogSettingsRepository.syncCatalogs(listOf(managedAddon))
        HomeCatalogSettingsRepository.syncCollections(listOf(collection()))
        HomeCatalogSettingsRepository.setRowPlacement(
            "test:movie:popular",
            HomeCatalogRowPlacement.PINNED_TOP,
        )
        HomeCatalogSettingsRepository.setRowPlacement(
            "collection_favorites",
            HomeCatalogRowPlacement.PINNED_TOP,
        )

        assertTrue(
            HomeCatalogSettingsRepository.canMoveByKeys(
                "collection_favorites",
                "test:movie:popular",
            ),
        )
        HomeCatalogSettingsRepository.moveByKeys(
            fromKey = "collection_favorites",
            toKey = "test:movie:popular",
        )

        val items = HomeCatalogSettingsRepository.uiState.value.items
        assertEquals("collection_favorites", items.first().key)
        assertEquals(
            HomeCatalogRowPlacement.PINNED_TOP,
            items.single { it.key == "collection_favorites" }.rowPlacement,
        )
        assertEquals(
            HomeCatalogRowPlacement.PINNED_TOP,
            items.single { it.key == "test:movie:popular" }.rowPlacement,
        )
    }

    private fun addon(
        catalogIds: List<String> = listOf("popular"),
        addonId: String = "test",
        addonName: String = "Catalogs",
    ) = ManagedAddon(
        manifestUrl = "https://example.com/manifest.json",
        manifest = AddonManifest(
            id = addonId,
            name = addonName,
            description = "",
            version = "1",
            resources = emptyList(),
            types = listOf("movie"),
            catalogs = catalogIds.map { catalogId ->
                AddonCatalog(type = "movie", id = catalogId, name = catalogId)
            },
            transportUrl = "https://example.com",
        ),
    )

    private fun collection() = Collection(
        id = "favorites",
        title = "Favorites",
        folders = listOf(CollectionFolder(id = "movies", title = "Movies")),
    )

    private class RecordingPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        var editCount = 0

        override fun edit(): SharedPreferences.Editor {
            editCount += 1
            return delegate.edit()
        }
    }
}
