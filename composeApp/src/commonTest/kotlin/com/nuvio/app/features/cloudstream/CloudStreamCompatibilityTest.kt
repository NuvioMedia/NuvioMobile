package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudStreamCompatibilityTest {
    private fun metadata(repositoryUrl: String = "https://example.com/repo.json"): CloudStreamPluginMetadata {
        val id = CloudStreamPluginId(repositoryUrl, "ExampleProvider")
        return CloudStreamPluginMetadata(
            id = id,
            repositoryManifestUrl = repositoryUrl,
            packageUrl = "https://example.com/ExampleProvider.cs3",
            status = CloudStreamPluginStatus.Ok,
            version = 1,
            name = "Example Provider",
            internalName = "ExampleProvider",
            authors = emptyList(),
            description = null,
            fileSize = null,
            projectUrl = null,
            language = null,
            tvTypes = emptyList(),
            rawTvTypes = emptyList(),
            iconUrl = null,
            apiVersion = 1,
            fileHash = null,
        )
    }

    @Test
    fun standardCs3IsRunnableOnlyWhenAndroidDexRuntimeIsAvailable() {
        val plugin = metadata()

        val fullBuild = CloudStreamCompatibilityResolver.resolve(
            metadata = plugin,
            supportsAndroidDex = true,
        )
        val storeBuild = CloudStreamCompatibilityResolver.resolve(
            metadata = plugin,
            supportsAndroidDex = false,
        )

        assertEquals(CloudStreamRuntimeKind.AndroidDex, fullBuild.runtimeKind)
        assertEquals(CloudStreamPlatformSupport.AndroidOnly, fullBuild.platformSupport)
        assertTrue(fullBuild.isRunnable)
        assertEquals(CloudStreamRuntimeKind.UnsupportedAndroidDex, storeBuild.runtimeKind)
        assertEquals(CloudStreamPlatformSupport.Unsupported, storeBuild.platformSupport)
        assertEquals(false, storeBuild.isRunnable)
    }

    @Test
    fun reviewedCrossPlatformAdapterRemainsAvailableWithoutDexRuntime() {
        val plugin = metadata(
            "https://raw.githubusercontent.com/Kraptor123/cs-kraptor/master/repo.json",
        ).copy(
            id = CloudStreamPluginId(
                "https://raw.githubusercontent.com/Kraptor123/cs-kraptor/master/repo.json",
                "KickTR",
            ),
            internalName = "KickTR",
        )

        val compatibility = CloudStreamCompatibilityResolver.resolve(
            metadata = plugin,
            supportsAndroidDex = false,
        )

        assertEquals(CloudStreamRuntimeKind.PrecompiledCrossPlatformAdapter, compatibility.runtimeKind)
        assertEquals("kick-tr-v1", compatibility.adapterId)
        assertTrue(compatibility.isRunnable)
    }
}
