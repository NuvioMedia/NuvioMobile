package com.nuvio.app.features.debrid

import kotlinx.coroutines.CancellationException
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
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val response = TorboxApiClient.checkCached(apiKey = apiKey, hashes = hashes)
            torboxCachedItems(response)
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

internal fun torboxCachedItems(
    response: DebridApiResponse<TorboxEnvelopeDto<Map<String, TorboxCachedItemDto>>>,
): Map<String, LocalDebridCachedItem>? {
    if (!response.isSuccessful) return null
    val body = response.body ?: return null
    if (body.success != true) return null
    val data = body.data ?: return null

    return data.mapKeys { it.key.lowercase() }.mapValues { (_, value) ->
        LocalDebridCachedItem(
            name = value.name,
            size = value.size,
        )
    }
}
