package com.nuvio.app.features.usenet

import android.app.Application
import android.content.Context
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.streams.StreamLinkCacheStorage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UsenetReuseTest {
    private val indexer = UsenetIndexer(id = "idx-a", name = "Indexer", apiUrl = "https://indexer.test", apiKey = "secretkey1234")
    private val provider = UsenetProvider(name = "News", host = "news.test", username = "me", password = "pw")
    private val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
    private val key = StreamLinkCacheRepository.contentKey(type = "series", videoId = "tt1:1:2", parentMetaId = "tt1", season = 1, episode = 2)

    @Before
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_stream_link_cache", Context.MODE_PRIVATE).edit().clear().commit()
        StreamLinkCacheStorage.initialize(context)
    }

    @Test
    fun aKeptNzbIsAValidCacheEntryWithoutAUrl() {
        StreamLinkCacheRepository.save(
            contentKey = key,
            url = "",
            streamName = "1080p • Usenet • Indexer",
            addonName = BUILT_IN_USENET_GROUP_NAME,
            addonId = BUILT_IN_USENET_GROUP_ID,
            videoSize = 1_500_000_000,
            nzbUrl = "https://indexer.test/getnzb/1.nzb?r={indexer-key:idx-a}",
            releaseTitle = "Show.S01E02.1080p.WEB-DL-GRP",
            publishedAt = 1_700_000_000_000,
        )
        val cached = assertNotNull(StreamLinkCacheRepository.getValid(key, ONE_HOUR_MS))
        assertEquals("", cached.url)
        assertEquals("https://indexer.test/getnzb/1.nzb?r={indexer-key:idx-a}", cached.nzbUrl)
        assertEquals("Show.S01E02.1080p.WEB-DL-GRP", cached.releaseTitle)
        assertEquals(1_700_000_000_000, cached.publishedAt)
    }

    @Test
    fun aKeptReleaseOpensWithTodaysProvidersAndKey() {
        val stored = UsenetKeptLinks.withoutApiKeys("https://indexer.test/getnzb/1.nzb?r=secretkey1234", config)
        val restored = assertNotNull(
            BuiltInUsenetService.restoredStream(stored, "1080p • Usenet • Indexer", "Show.S01E02.1080p.WEB-DL-GRP",
                1_500_000_000, 1_700_000_000_000, config, series = true),
        )
        assertEquals("https://indexer.test/getnzb/1.nzb?r=secretkey1234", restored.nzbUrl)
        assertEquals(config.servers(), restored.nzbServers)
        assertEquals(BUILT_IN_USENET_GROUP_ID, restored.addonId)
        assertEquals(1_500_000_000, restored.behaviorHints.videoSize)
        assertTrue(restored.needsUsenetPreparation)
        // The next episode of the upload shares its binge group.
        assertEquals(BuiltInUsenetService.bingeGroup("Show.S01E03.1080p.WEB-DL-GRP"), restored.behaviorHints.bingeGroup)
    }

    @Test
    fun aKeptReleaseDoesNotOpenWhenItsSourcesAreGone() {
        val stored = "https://indexer.test/getnzb/1.nzb?r={indexer-key:idx-a}"
        assertNull(BuiltInUsenetService.restoredStream(stored, "n", "t", null, null, config.copy(indexers = emptyList()), true))
        assertNull(BuiltInUsenetService.restoredStream(stored, "n", "t", null, null, config.copy(enabled = false), true))
        assertNull(BuiltInUsenetService.restoredStream(stored, "n", "t", null, null,
            config.copy(providers = listOf(provider.copy(enabled = false))), true))
    }

    private companion object {
        const val ONE_HOUR_MS = 60L * 60L * 1000L
    }
}
