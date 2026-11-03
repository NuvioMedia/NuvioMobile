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
import com.nuvio.app.features.catalog.CatalogTarget
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
    fun cloudStreamCatalogRowsAreExposedAndKeepTheirHomeVisibilityPreference() {
        val providerId = "https://example.com/repo.json#Provider"
        val section = HomeCatalogSection(
            key = "cloudstream:$providerId:Anime",
            title = "Anime",
            subtitle = "Example Provider",
            addonName = "Example Provider",
            target = CatalogTarget.CloudStream(providerId, "Anime", "series"),
            items = listOf(MetaPreview(id = "show-1", type = "series", name = "Example Show")),
        )

        HomeCatalogSettingsRepository.syncCloudStreamCatalogs(listOf(section))
        assertEquals(section.key, HomeCatalogSettingsRepository.uiState.value.items.single().key)
        assertEquals("Example Provider", HomeCatalogSettingsRepository.uiState.value.items.single().addonName)
        assertFalse(HomeCatalogSettingsRepository.uiState.value.items.single().supportsHeroSource)

        HomeCatalogSettingsRepository.setEnabled(section.key, false)
        HomeCatalogSettingsRepository.syncCloudStreamCatalogs(listOf(section.copy(title = "Anime Updated")))

        val item = HomeCatalogSettingsRepository.uiState.value.items.single()
        assertFalse(item.enabled)
        assertEquals("Anime Updated", item.defaultTitle)
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
    fun legacyPositionPinIsNormalizedWhileTopAndBottomPinsPersist() {
        val initial = listOf(addon(listOf("popular", "latest")))
        HomeCatalogSettingsRepository.syncCatalogs(initial)
        val positionKey = "test:movie:popular"
        val bottomKey = "test:movie:latest"

        HomeCatalogSettingsRepository.setRowPlacement(positionKey, HomeCatalogRowPlacement.PINNED_POSITION)
        HomeCatalogSettingsRepository.setRowPlacement(bottomKey, HomeCatalogRowPlacement.PINNED_BOTTOM)
        assertEquals(
            HomeCatalogRowPlacement.NORMAL,
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == positionKey }.rowPlacement,
        )
        assertEquals(
            HomeCatalogRowPlacement.PINNED_BOTTOM,
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == bottomKey }.rowPlacement,
        )

        val refreshed = listOf(addon(listOf("popular", "latest", "new")))
        HomeCatalogSettingsRepository.syncCatalogs(refreshed)
        assertEquals(
            HomeCatalogRowPlacement.NORMAL,
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == positionKey }.rowPlacement,
        )
        assertEquals(
            HomeCatalogRowPlacement.PINNED_BOTTOM,
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == bottomKey }.rowPlacement,
        )

        HomeCatalogSettingsRepository.clearLocalState()
        HomeCatalogSettingsRepository.syncCatalogs(refreshed)
        assertEquals(
            HomeCatalogRowPlacement.NORMAL,
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == positionKey }.rowPlacement,
        )
        assertEquals(
            HomeCatalogRowPlacement.PINNED_BOTTOM,
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == bottomKey }.rowPlacement,
        )
    }

    @Test
    fun legacyPositionPinNoLongerBlocksManualReordering() {
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

        assertFalse(before == HomeCatalogSettingsRepository.uiState.value.items.map { it.key })
        assertEquals(
            HomeCatalogRowPlacement.NORMAL,
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == pinnedKey }.rowPlacement,
        )
    }


    @Test
    fun normalRowsCannotBeDraggedAcrossPinnedBottomBoundary() {
        HomeCatalogSettingsRepository.syncCatalogs(
            listOf(addon(listOf("popular", "middle", "latest"))),
        )
        HomeCatalogSettingsRepository.setRowPlacement(
            "test:movie:latest",
            HomeCatalogRowPlacement.PINNED_BOTTOM,
        )

        assertFalse(
            HomeCatalogSettingsRepository.canMoveByKeys(
                "test:movie:popular",
                "test:movie:latest",
            ),
        )
        assertTrue(
            HomeCatalogSettingsRepository.canMoveByKeys(
                "test:movie:popular",
                "test:movie:middle",
            ),
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

    @Test
    fun mergedRowsSupportCrudVisibilityAndLocalPersistence() {
        val addons = listOf(addon(listOf("popular", "latest", "trending")))
        HomeCatalogSettingsRepository.syncCatalogs(addons)

        val id = assertNotNull(
            HomeCatalogSettingsRepository.createMergedRow(
                title = "Latest Mix",
                sourceKeys = listOf("test:movie:popular", "test:movie:latest", "test:movie:trending"),
                displayMode = MergedHomeRowDisplayMode.SOURCE_TABS,
                deduplicate = true,
            ),
        )
        val key = mergedHomeRowKey(id)

        val createdItem = assertNotNull(HomeCatalogSettingsRepository.uiState.value.items.singleOrNull { it.key == key })
        assertTrue(createdItem.isMergedRow)
        assertTrue(createdItem.enabled)
        assertEquals(id, createdItem.mergedRowId)
        assertEquals(3, assertNotNull(HomeCatalogSettingsRepository.resolveMergedHomeRow(id)).sources.size)
        val createdSyncPayload = HomeCatalogSettingsRepository.exportToSyncPayload()
        assertEquals(listOf(id), createdSyncPayload.mergedRows.map { it.id })
        assertTrue(createdSyncPayload.items.any { it.key == key })

        HomeCatalogSettingsRepository.setEnabled(key, false)
        assertFalse(HomeCatalogSettingsRepository.uiState.value.items.single { it.key == key }.enabled)

        assertTrue(
            HomeCatalogSettingsRepository.updateMergedRow(
                id = id,
                title = "Updated Mix",
                sourceKeys = listOf("test:movie:popular", "test:movie:latest"),
                displayMode = MergedHomeRowDisplayMode.UNIFIED_CAROUSEL,
                deduplicate = false,
            ),
        )
        val updated = HomeCatalogSettingsRepository.uiState.value.mergedRows.single { it.id == id }
        assertEquals("Updated Mix", updated.title)
        assertEquals(MergedHomeRowDisplayMode.UNIFIED_CAROUSEL, updated.resolvedDisplayMode())
        assertFalse(updated.deduplicate)
        assertFalse(HomeCatalogSettingsRepository.mergedRowEnabled(id))

        val duplicateId = assertNotNull(HomeCatalogSettingsRepository.duplicateMergedRow(id))
        val duplicate = HomeCatalogSettingsRepository.uiState.value.mergedRows.single { it.id == duplicateId }
        assertEquals("Updated Mix (copy)", duplicate.title)
        assertEquals(updated.sourceKeys, duplicate.sourceKeys)
        assertEquals(updated.displayMode, duplicate.displayMode)
        assertFalse(HomeCatalogSettingsRepository.mergedRowEnabled(duplicateId))

        HomeCatalogSettingsRepository.deleteMergedRow(duplicateId)
        assertTrue(HomeCatalogSettingsRepository.uiState.value.mergedRows.none { it.id == duplicateId })

        HomeCatalogSettingsRepository.clearLocalState()
        HomeCatalogSettingsRepository.syncCatalogs(addons)

        val restored = HomeCatalogSettingsRepository.uiState.value.mergedRows.single { it.id == id }
        assertEquals("Updated Mix", restored.title)
        assertEquals(MergedHomeRowDisplayMode.UNIFIED_CAROUSEL, restored.resolvedDisplayMode())
        assertFalse(restored.deduplicate)
        assertFalse(HomeCatalogSettingsRepository.mergedRowEnabled(id))
        assertEquals(2, assertNotNull(HomeCatalogSettingsRepository.resolveMergedHomeRow(id)).sources.size)

        HomeCatalogSettingsRepository.deleteMergedRow(id)
        assertTrue(HomeCatalogSettingsRepository.uiState.value.mergedRows.none { it.id == id })
        assertTrue(HomeCatalogSettingsRepository.uiState.value.items.none { it.key == key })
    }

    @Test
    fun remoteMergedRowPreferencesSurvivePullBeforeAnyAddonManifestLoads() {
        val row = MergedHomeRowDefinition(
            id = "remote-row",
            title = "Remote Mix",
            sourceKeys = listOf("test:movie:popular", "test:movie:latest"),
            displayMode = MergedHomeRowDisplayMode.SOURCE_TABS.storageValue,
        )
        val payload = SyncHomeCatalogPayload(
            items = listOf(
                SyncCatalogItem(
                    addonId = "test",
                    type = "movie",
                    catalogId = "popular",
                    enabled = false,
                    customTitle = "Remote Popular",
                    key = "test:movie:popular",
                ),
            ),
            mergedRows = listOf(row),
        )

        // Keep a local preference for a source that is absent from the incoming payload.
        // It must survive while manifests are unavailable, then bind back to its catalog.
        HomeCatalogSettingsRepository.syncCatalogs(listOf(addon(listOf("popular", "latest"))))
        HomeCatalogSettingsRepository.setCustomTitle("test:movie:latest", "Local Latest")

        // This is the startup ordering where remote settings arrive before manifests.
        HomeCatalogSettingsRepository.syncCatalogs(emptyList())
        HomeCatalogSettingsRepository.syncCollections(listOf(collection()))
        HomeCatalogSettingsRepository.applyFromRemote(payload)
        HomeCatalogSettingsRepository.syncCatalogs(listOf(addon(listOf("popular", "latest"))))

        val source = HomeCatalogSettingsRepository.uiState.value.items.single { it.key == "test:movie:popular" }
        assertFalse(source.enabled)
        assertEquals("Remote Popular", source.customTitle)
        assertEquals(
            "Local Latest",
            HomeCatalogSettingsRepository.uiState.value.items.single { it.key == "test:movie:latest" }.customTitle,
        )
        assertEquals(row.id, HomeCatalogSettingsRepository.uiState.value.mergedRows.single().id)
        assertEquals(2, assertNotNull(HomeCatalogSettingsRepository.resolveMergedHomeRow(row.id)).sources.size)
    }

    @Test
    fun remoteMergedRowPreferencesSurvivePartialAddonManifestLoading() {
        val readyAddon = addon(listOf("popular"), addonId = "ready", addonName = "Ready")
        val pendingAddon = addon(listOf("latest"), addonId = "pending", addonName = "Pending")
            .copy(manifest = null, isRefreshing = true)
        val row = MergedHomeRowDefinition(
            id = "partial-row",
            title = "Partial Mix",
            sourceKeys = listOf("ready:movie:popular", "pending:movie:latest"),
        )
        val payload = SyncHomeCatalogPayload(
            items = listOf(
                SyncCatalogItem(
                    addonId = "pending",
                    type = "movie",
                    catalogId = "latest",
                    enabled = false,
                    customTitle = "Pending Latest",
                    key = "pending:movie:latest",
                ),
            ),
            mergedRows = listOf(row),
        )

        HomeCatalogSettingsRepository.syncCatalogs(listOf(readyAddon, pendingAddon))
        HomeCatalogSettingsRepository.applyFromRemote(payload)
        HomeCatalogSettingsRepository.syncCatalogs(
            listOf(readyAddon, addon(listOf("latest"), addonId = "pending", addonName = "Pending")),
        )

        val source = HomeCatalogSettingsRepository.uiState.value.items.single {
            it.key == "pending:movie:latest"
        }
        assertFalse(source.enabled)
        assertEquals("Pending Latest", source.customTitle)
        assertEquals(2, assertNotNull(HomeCatalogSettingsRepository.resolveMergedHomeRow(row.id)).sources.size)
    }

    @Test
    fun mergedRowDisplayAndBlendOptionsPersistAcrossReload() {
        HomeCatalogSettingsRepository.syncCatalogs(listOf(addon(listOf("popular", "latest"))))
        val id = assertNotNull(
            HomeCatalogSettingsRepository.createMergedRow(
                title = "Smart Mix",
                sourceKeys = listOf("test:movie:popular", "test:movie:latest"),
                displayMode = MergedHomeRowDisplayMode.TYPE_TABS,
                blendMode = MergedHomeRowBlendMode.SMART_FRESHNESS_POPULARITY,
                sortMode = MergedHomeRowSortMode.NEWEST,
                hideIncludedRows = true,
                hideTitlesWithoutPosters = true,
            ),
        )

        val created = HomeCatalogSettingsRepository.snapshot().mergedRows.single { it.id == id }
        assertEquals(MergedHomeRowDisplayMode.TYPE_TABS, created.resolvedDisplayMode())
        assertEquals(MergedHomeRowBlendMode.SMART_FRESHNESS_POPULARITY, created.resolvedBlendMode())
        assertEquals(MergedHomeRowSortMode.NEWEST, created.resolvedSortMode())
        assertTrue(created.hideIncludedRows)
        assertTrue(created.hideTitlesWithoutPosters)

        assertTrue(
            HomeCatalogSettingsRepository.updateMergedRow(
                id = id,
                title = "Updated Smart Mix",
                sourceKeys = created.sourceKeys,
                displayMode = MergedHomeRowDisplayMode.SOURCE_TABS,
                deduplicate = true,
                blendMode = MergedHomeRowBlendMode.INTERLEAVE,
                sortMode = MergedHomeRowSortMode.POPULARITY,
                hideIncludedRows = false,
                hideTitlesWithoutPosters = false,
            ),
        )

        HomeCatalogSettingsRepository.clearLocalState()
        HomeCatalogSettingsRepository.syncCatalogs(listOf(addon(listOf("popular", "latest"))))
        val restored = HomeCatalogSettingsRepository.snapshot().mergedRows.single { it.id == id }
        assertEquals("Updated Smart Mix", restored.title)
        assertEquals(MergedHomeRowDisplayMode.SOURCE_TABS, restored.resolvedDisplayMode())
        assertEquals(MergedHomeRowBlendMode.INTERLEAVE, restored.resolvedBlendMode())
        assertEquals(MergedHomeRowSortMode.POPULARITY, restored.resolvedSortMode())
        assertFalse(restored.hideIncludedRows)
        assertFalse(restored.hideTitlesWithoutPosters)
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
