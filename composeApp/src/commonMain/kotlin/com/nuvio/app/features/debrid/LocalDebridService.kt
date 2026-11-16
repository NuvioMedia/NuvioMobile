package com.nuvio.app.features.debrid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class LocalDebridCachedItem(
    val name: String?,
    val size: Long?,
)

internal object LocalDebridService {
    suspend fun checkCached(
        account: DebridServiceCredential,
        hashes: List<String>,
    ): Map<String, LocalDebridCachedItem>? =
        when (account.provider.id) {
            DebridProviders.TORBOX_ID -> checkTorboxCached(account.apiKey, hashes)
            DebridProviders.PREMIUMIZE_ID -> checkPremiumizeCached(account.apiKey, hashes)
            else -> null
        }

    suspend fun isCached(account: DebridServiceCredential, hash: String): Boolean? {
        val normalizedHash = hash.trim().lowercase().takeIf { it.isNotBlank() } ?: return null
        return checkCached(account, listOf(normalizedHash))?.containsKey(normalizedHash)
    }

    private suspend fun checkTorboxCached(
        apiKey: String,
        hashes: List<String>,
    ): Map<String, LocalDebridCachedItem>? {
        val normalizedHashes = hashes
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedHashes.isEmpty()) return emptyMap()
        val cached = requestTorboxCached(apiKey, normalizedHashes) ?: return null
        return resolveTorboxAliasMatches(cached, normalizedHashes) { hash ->
            requestTorboxCached(apiKey, listOf(hash))
        }
    }

    private suspend fun requestTorboxCached(
        apiKey: String,
        hashes: List<String>,
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val response = TorboxApiClient.checkCached(apiKey = apiKey, hashes = hashes)
            if (!response.isSuccessful || response.body?.success == false) {
                null
            } else {
                response.body?.data.orEmpty().mapKeys { it.key.lowercase() }.mapValues { (_, value) ->
                    LocalDebridCachedItem(
                        name = value.name,
                        size = value.size,
                    )
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    private suspend fun checkPremiumizeCached(
        apiKey: String,
        hashes: List<String>,
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val normalizedHashes = hashes
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            if (normalizedHashes.isEmpty()) return emptyMap()
            val sources = normalizedHashes.map { hash -> "magnet:?xt=urn:btih:$hash" }
            val response = PremiumizeApiClient.checkCache(apiKey = apiKey, items = sources)
            val body = response.body
            if (!response.isSuccessful || body?.status.equals("error", ignoreCase = true)) {
                null
            } else {
                normalizedHashes.mapIndexedNotNull { index, hash ->
                    if (body?.response?.getOrNull(index) != true) return@mapIndexedNotNull null
                    hash to LocalDebridCachedItem(
                        name = body.filename?.getOrNull(index),
                        size = body.filesize?.getOrNull(index)?.asLongOrNull(),
                    )
                }.toMap()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
}

private fun kotlinx.serialization.json.JsonElement?.asLongOrNull(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.content.toLongOrNull()
}

/**
 * TorBox matches hashes through its alternative-hash index and answers with the
 * canonical hash as key, so an alias hit would otherwise read as a cache miss.
 */
internal suspend fun resolveTorboxAliasMatches(
    cached: Map<String, LocalDebridCachedItem>,
    requestedHashes: List<String>,
    fetchCached: suspend (String) -> Map<String, LocalDebridCachedItem>? = { null },
): Map<String, LocalDebridCachedItem> {
    if (cached.keys.all { it in requestedHashes }) return cached
    // One requested hash: every returned entry can only be its match.
    if (requestedHashes.size == 1) {
        val item = cached.values.firstOrNull() ?: return cached
        return mapOf(requestedHashes.single() to item)
    }
    val resolved = cached.toMutableMap()
    val matches = coroutineScope {
        requestedHashes
            .filter { it !in resolved }
            .map { hash ->
                async {
                    val item = try {
                        fetchCached(hash)?.values?.firstOrNull()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                    hash to item
                }
            }
            .awaitAll()
    }
    matches.forEach { (hash, item) ->
        if (item != null) resolved[hash] = item
    }
    return resolved
}
