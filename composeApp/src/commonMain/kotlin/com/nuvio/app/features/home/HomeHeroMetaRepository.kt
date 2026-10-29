package com.nuvio.app.features.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.mdblist.MdbListMetadataService
import com.nuvio.app.features.mdblist.MdbListSettings
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class HomeHeroState(
    val items: List<MetaPreview>,
    val showImdbRating: Boolean,
    val showMdbListRatings: Boolean,
)

internal data class HomeHeroMeta(
    val details: MetaDetails? = null,
    val ratings: List<MetaExternalRating> = emptyList(),
)

internal object HomeHeroMetaRepository {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val _metas = MutableStateFlow<Map<String, HomeHeroMeta>>(emptyMap())
    val metas: StateFlow<Map<String, HomeHeroMeta>> = _metas.asStateFlow()

    suspend fun load(items: List<MetaPreview>, ratingSettings: MdbListSettings?) {
        val keys = items.mapTo(mutableSetOf(), MetaPreview::stableKey)
        _metas.update { metas -> metas.filterKeys(keys::contains) }
        val permits = Semaphore(DETAILS_FETCH_CONCURRENCY)
        withContext(dispatcher) {
            items.forEach { item ->
                launch { loadItem(item, ratingSettings, permits) }
            }
        }
    }

    private suspend fun loadItem(
        item: MetaPreview,
        ratingSettings: MdbListSettings?,
        permits: Semaphore,
    ) {
        val key = item.stableKey()
        coroutineScope {
            val details = async { permits.withPermit { fetchDetails(item) } }
            launch {
                val ratings = ratingSettings
                    ?.let { settings -> fetchRatings(item, settings) { details.await() } }
                    .orEmpty()
                update(key) { meta -> meta.copy(ratings = ratings) }
            }
            val resolved = details.await() ?: return@coroutineScope
            update(key) { meta -> meta.copy(details = resolved) }
        }
    }

    private fun update(key: String, transform: (HomeHeroMeta) -> HomeHeroMeta) {
        _metas.update { metas -> metas + (key to transform(metas[key] ?: HomeHeroMeta())) }
    }

    private suspend fun fetchDetails(item: MetaPreview): MetaDetails? =
        try {
            MetaDetailsRepository.fetch(type = item.type, id = item.id)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            null
        }

    private suspend fun fetchRatings(
        item: MetaPreview,
        settings: MdbListSettings,
        details: suspend () -> MetaDetails?,
    ): List<MetaExternalRating> {
        val preview = MetaDetails(id = item.id, type = item.type, name = item.name)
        val meta = if (MdbListMetadataService.shouldFetchForMeta(preview, item.id, settings)) {
            preview
        } else {
            details() ?: return emptyList()
        }
        return try {
            withTimeoutOrNull(RATINGS_TIMEOUT_MS) {
                MdbListMetadataService.enrichMeta(
                    meta = meta,
                    fallbackItemId = item.id,
                    settings = settings,
                ).externalRatings
            }.orEmpty()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            emptyList()
        }
    }
}

internal fun MetaPreview.withHeroMeta(meta: HomeHeroMeta): MetaPreview {
    val details = meta.details
    return copy(
        genres = details?.genres?.takeIf { it.isNotEmpty() } ?: genres,
        imdbRating = details?.imdbRating?.takeIf { it.isNotBlank() } ?: imdbRating,
        runtime = details?.runtime?.takeIf { it.isNotBlank() } ?: runtime,
        status = details?.status?.takeIf { it.isNotBlank() } ?: status,
        ageRating = details?.ageRating?.takeIf { it.isNotBlank() } ?: ageRating,
        language = details?.language?.takeIf { it.isNotBlank() } ?: language,
        externalRatings = meta.ratings,
    )
}

@Composable
internal fun rememberHomeHeroState(items: List<MetaPreview>): HomeHeroState {
    val mdbListSettings by remember {
        MdbListSettingsRepository.ensureLoaded()
        MdbListSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val metaScreenSettings by remember {
        MetaScreenSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val tmdbSettings by remember {
        TmdbSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val metas by HomeHeroMetaRepository.metas.collectAsStateWithLifecycle()
    val showMdbListRatings = mdbListSettings.isActive && mdbListSettings.showOnHero

    LaunchedEffect(items, mdbListSettings, tmdbSettings) {
        delay(LOAD_DEBOUNCE_MS)
        HomeHeroMetaRepository.load(items, mdbListSettings.takeIf { showMdbListRatings })
    }

    val heroItems = remember(items, metas) {
        items.map { item -> metas[item.stableKey()]?.let(item::withHeroMeta) ?: item }
    }
    return HomeHeroState(
        items = heroItems,
        showImdbRating = metaScreenSettings.showOverallRatings,
        showMdbListRatings = showMdbListRatings,
    )
}

private const val DETAILS_FETCH_CONCURRENCY = 4
private const val RATINGS_TIMEOUT_MS = 5_000L
private const val LOAD_DEBOUNCE_MS = 400L
