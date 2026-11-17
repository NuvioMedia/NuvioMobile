package com.nuvio.app.features.details

import android.app.Application
import com.nuvio.app.core.poster.CustomPosterUrlRepository
import com.nuvio.app.core.poster.CustomPosterUrlStorage
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.mdblist.MdbListSettings
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.trakt.MoreLikeThisSourcePreference
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MetaDetailsPosterNavigationTest {
    private val recommendation = MetaPreview(
        id = "tt1000002",
        type = "movie",
        name = "Recommended title",
        poster = "https://original.example/tt1000002.jpg",
    )
    private val parent = MetaDetails(
        id = "tt1000001",
        type = "movie",
        name = "Parent title",
        moreLikeThis = listOf(recommendation),
        moreLikeThisSource = MoreLikeThisSource.TMDB,
    )
    private val child = MetaDetails(
        id = recommendation.id,
        type = recommendation.type,
        name = recommendation.name,
    )
    private val customPoster = "https://posters.example/tt1000002.jpg"

    @BeforeTest
    fun initialize() {
        MetaDetailsRepository.clear()
        CustomPosterUrlStorage.initialize(RuntimeEnvironment.getApplication())
        CustomPosterUrlStorage.savePattern("https://posters.example/{imdb_id}.jpg")
        CustomPosterUrlStorage.saveEnabledScreens(null)
        CustomPosterUrlRepository.onProfileChanged()
        TmdbSettingsRepository.setEnabled(true)
        TrackingSettingsRepository.setMoreLikeThisSource(MoreLikeThisSourcePreference.TMDB)
        MdbListSettingsRepository.setEnabled(false)
        HomeCatalogSettingsRepository.setHideUnreleasedContent(false)
    }

    @AfterTest
    fun clearState() {
        MetaDetailsRepository.clear()
        CustomPosterUrlStorage.savePattern(null)
        CustomPosterUrlRepository.clearLocalState()
        TmdbSettingsRepository.onProfileChanged()
        TrackingSettingsRepository.clearLocalState()
        MdbListSettingsRepository.onProfileChanged()
        HomeCatalogSettingsRepository.onProfileChanged()
    }

    @Test
    fun activeDetailsKeepCustomRecommendationPosters() {
        cache(parent)
        MetaDetailsRepository.load(parent.type, parent.id)

        assertCustomPoster(assertNotNull(MetaDetailsRepository.peek(parent.type, parent.id)))
    }

    @Test
    fun cachedBaseDetailsKeepCustomPostersAfterOpeningRecommendation() {
        assertPostersAfterOpeningRecommendation(enriched = false)
    }

    @Test
    fun cachedEnrichedDetailsKeepCustomPostersAfterOpeningRecommendation() {
        assertPostersAfterOpeningRecommendation(enriched = true)
    }

    @Test
    fun reloadingParentDetailsKeepsCustomRecommendationPosters() {
        openParentThenRecommendation(enriched = false)
        MetaDetailsRepository.load(parent.type, parent.id)

        assertCustomPoster(assertNotNull(MetaDetailsRepository.peek(parent.type, parent.id)))
    }

    private fun assertPostersAfterOpeningRecommendation(enriched: Boolean) {
        openParentThenRecommendation(enriched)

        assertCustomPoster(assertNotNull(MetaDetailsRepository.peek(parent.type, parent.id)))
    }

    private fun openParentThenRecommendation(enriched: Boolean) {
        cache(parent, enriched)
        cache(child)
        MetaDetailsRepository.load(parent.type, parent.id)
        assertCustomPoster(assertNotNull(MetaDetailsRepository.uiState.value.meta))

        MetaDetailsRepository.load(child.type, child.id)
        assertEquals(child.id, MetaDetailsRepository.uiState.value.meta?.id)
    }

    private fun assertCustomPoster(meta: MetaDetails) {
        val poster = meta.moreLikeThis.single()
        assertEquals(customPoster, poster.poster)
        assertEquals(recommendation.poster, poster.rawPosterUrl)
    }

    @Suppress("UNCHECKED_CAST")
    private fun cache(meta: MetaDetails, enriched: Boolean = false) {
        val repositoryClass = MetaDetailsRepository::class.java
        val fingerprint = repositoryClass.getDeclaredMethod(
            "buildMetaScreenSettingsFingerprint",
            MdbListSettings::class.java,
        ).apply { isAccessible = true }
            .invoke(MetaDetailsRepository, MdbListSettingsRepository.snapshot()) as String
        val entryClass = repositoryClass.declaredClasses.single { it.simpleName == "CachedMetaEntry" }
        val entry = entryClass.getDeclaredConstructor(
            MetaDetails::class.java,
            MetaDetails::class.java,
            String::class.java,
        ).apply { isAccessible = true }
            .newInstance(meta, meta.takeIf { enriched }, fingerprint.takeIf { enriched })
        val cache = repositoryClass.getDeclaredField("cachedMetaByRequestKey")
            .apply { isAccessible = true }
            .get(MetaDetailsRepository) as MutableMap<String, Any>
        cache["${meta.type}:${meta.id}"] = entry
    }
}
