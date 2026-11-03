package com.nuvio.app.features.cloudstream

internal object CloudStreamProviderRegistry {
    private val providers: List<CloudStreamProvider> = listOf(
        KickTrCloudStreamProvider,
    )

    fun find(providerId: String): CloudStreamProvider? =
        providers.firstOrNull { it.id == providerId }
}
