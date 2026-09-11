package com.nuvio.app.features.addons

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Disk TTL for persisted addon manifests before a background revalidation is due.
 * Mirrors NuvioTV's `AddonRepositoryImpl` `MANIFEST_CACHE_TTL_MS` (6 hours) so a cold
 * start never depends on the addon server being reachable.
 */
internal const val MANIFEST_CACHE_TTL_MS = 6L * 60 * 60 * 1000

@Serializable
internal data class CachedManifestEntry(
    val payload: String,
    val fetchedAtMillis: Long,
)

@Serializable
internal data class ManifestCacheBlob(
    val entries: Map<String, CachedManifestEntry> = emptyMap(),
)

/** Serializes the raw manifest.json payloads cached per addon URL. */
internal object AddonManifestCacheCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(entries: Map<String, CachedManifestEntry>): String =
        json.encodeToString(ManifestCacheBlob.serializer(), ManifestCacheBlob(entries))

    fun decode(blob: String?): Map<String, CachedManifestEntry> {
        if (blob.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString(ManifestCacheBlob.serializer(), blob).entries }
            .getOrDefault(emptyMap())
    }
}

internal expect object AddonManifestClock {
    fun nowEpochMs(): Long
}

internal fun CachedManifestEntry.isStale(nowMillis: Long): Boolean =
    nowMillis - fetchedAtMillis >= MANIFEST_CACHE_TTL_MS
