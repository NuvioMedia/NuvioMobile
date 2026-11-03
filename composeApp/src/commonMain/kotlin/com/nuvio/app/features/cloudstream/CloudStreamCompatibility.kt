package com.nuvio.app.features.cloudstream

/**
 * Standard CS3 packages contain Android DEX. They can run only in Android Full,
 * where the embedded CloudStream-compatible runtime is present. iOS and store
 * distributions must never advertise arbitrary DEX packages as runnable.
 */
object CloudStreamCompatibilityResolver {
    private val crossPlatformAdapters = mapOf(
        "https://raw.githubusercontent.com/Kraptor123/cs-kraptor/master/repo.json#KickTR" to "kick-tr-v1",
    )

    fun resolve(
        metadata: CloudStreamPluginMetadata,
        supportsAndroidDex: Boolean = false,
    ): CloudStreamCompatibility {
        val adapterId = crossPlatformAdapters[metadata.id.value]
        return when {
            adapterId != null -> CloudStreamCompatibility(
                runtimeKind = CloudStreamRuntimeKind.PrecompiledCrossPlatformAdapter,
                platformSupport = CloudStreamPlatformSupport.AndroidAndIos,
                adapterId = adapterId,
                reason = "This provider has a reviewed cross-platform adapter compiled into NuvioEx.",
            )
            supportsAndroidDex -> CloudStreamCompatibility(
                runtimeKind = CloudStreamRuntimeKind.AndroidDex,
                platformSupport = CloudStreamPlatformSupport.AndroidOnly,
                reason = "This standard .cs3 package can run using the embedded CloudStream-compatible runtime in Android Full builds.",
            )
            else -> CloudStreamCompatibility(
                runtimeKind = CloudStreamRuntimeKind.UnsupportedAndroidDex,
                platformSupport = CloudStreamPlatformSupport.Unsupported,
                reason = "This standard .cs3 package contains Android DEX and requires the Android Full runtime.",
            )
        }
    }
}

fun sortCloudStreamEpisodes(episodes: List<CloudStreamEpisode>): List<CloudStreamEpisode> =
    episodes.sortedWith(
        compareBy<CloudStreamEpisode>(
            { it.season ?: Int.MAX_VALUE },
            { it.episode ?: Int.MAX_VALUE },
            { it.name.lowercase() },
        ),
    )
