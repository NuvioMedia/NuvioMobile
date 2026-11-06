package com.nuvio.app.features.tracking

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

interface TrackingRatingProvider {
    val providerId: TrackingProviderId
    val changes: Flow<Any?>

    fun supports(media: TrackingMediaReference): Boolean

    fun rating(media: TrackingMediaReference): Double?

    suspend fun rate(profileId: Int, media: TrackingMediaReference, rating: Int?): TrackingMutationResult

    fun onEnabled() = Unit
}

data class TrackingRatingState(
    val provider: TrackingProviderId? = null,
    val rating: Double? = null,
) {
    val isAvailable: Boolean
        get() = provider != null
}

object TrackingRatings {
    const val MAX = 10

    private val recent = MutableStateFlow<Map<String, Double?>>(emptyMap())

    fun observe(media: TrackingMediaReference): Flow<TrackingRatingState> =
        combine(
            listOf(TrackingSettingsRepository.uiState, TrackingProviderRegistry.connectedProviderIds, recent) +
                TrackingProviderRegistry.ratingProviders().map(TrackingRatingProvider::changes),
        ) { current(media) }.distinctUntilChanged()

    fun current(media: TrackingMediaReference): TrackingRatingState {
        val provider = provider(media) ?: return TrackingRatingState()
        val key = media.ratingKey(provider.providerId)
        val stored = provider.rating(media)
        val overrides = recent.value
        val rating = if (key in overrides) {
            if (overrides[key] == stored) recent.update { it - key }
            overrides[key]
        } else {
            stored
        }
        return TrackingRatingState(provider.providerId, rating)
    }

    suspend fun rate(media: TrackingMediaReference, rating: Int?): Boolean = withContext(NonCancellable) {
        require(rating == null || rating in 1..MAX)
        val provider = provider(media) ?: return@withContext false
        val saved = try {
            provider.rate(ProfileRepository.activeProfileId, media, rating).isComplete
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (saved) recent.update { it + (media.ratingKey(provider.providerId) to rating?.toDouble()) }
        saved
    }

    fun setSource(source: TrackingProviderId?) {
        TrackingSettingsRepository.setRatingSource(source)
        source?.let(TrackingProviderRegistry::ratingProvider)?.onEnabled()
    }

    private fun provider(media: TrackingMediaReference): TrackingRatingProvider? {
        val source = TrackingSettingsRepository.uiState.value.ratingSource ?: return null
        if (source !in TrackingProviderRegistry.connectedProviderIds.value) return null
        return TrackingProviderRegistry.ratingProvider(source)?.takeIf { it.supports(media) }
    }

    private fun TrackingMediaReference.ratingKey(provider: TrackingProviderId): String = buildString {
        append(ProfileRepository.activeProfileId)
        append(':')
        append(provider.storageId)
        append(':')
        append(stableKey)
        episode?.let {
            append(':')
            append(it.season ?: -1)
            append(':')
            append(it.number)
        }
    }
}
