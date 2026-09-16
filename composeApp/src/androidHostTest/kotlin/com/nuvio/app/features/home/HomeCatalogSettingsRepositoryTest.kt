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

    private fun addon() = ManagedAddon(
        manifestUrl = "https://example.com/manifest.json",
        manifest = AddonManifest(
            id = "test",
            name = "Catalogs",
            description = "",
            version = "1",
            resources = emptyList(),
            types = listOf("movie"),
            catalogs = listOf(AddonCatalog(type = "movie", id = "popular", name = "Popular")),
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
