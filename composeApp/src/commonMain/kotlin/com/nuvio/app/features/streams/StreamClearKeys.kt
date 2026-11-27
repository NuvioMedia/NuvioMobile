package com.nuvio.app.features.streams

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Normalizes `behaviorHints.clearKeys` to `kid:key,kid:key` in lowercase hex. Accepts a `{kid: key}`
 * object, a `[{kid, k}]` array or a `kid:key,kid:key` string; each side is hex (UUID dashes allowed)
 * or base64/base64url and must be 16 bytes. Invalid pairs are dropped, null when none is left.
 * The keys are secret: never log them.
 */
fun JsonElement?.toStreamClearKeys(): String? {
    val pairs: List<Pair<String?, String?>> = when (this) {
        is JsonPrimitive -> contentOrNull.orEmpty().split(',', ';').map {
            it.substringBefore(':', "") to it.substringAfter(':', "")
        }
        is JsonObject -> entries.map { (kid, key) -> kid to (key as? JsonPrimitive)?.contentOrNull }
        is JsonArray -> mapNotNull { entry ->
            (entry as? JsonObject)?.let {
                (it["kid"] as? JsonPrimitive)?.contentOrNull to (it["k"] as? JsonPrimitive)?.contentOrNull
            }
        }
        else -> return null
    }
    return pairs
        .mapNotNull { (kid, key) -> clearKeyHex(kid)?.let { k -> clearKeyHex(key)?.let { v -> "$k:$v" } } }
        .distinct()
        .joinToString(",")
        .ifEmpty { null }
}

private val clearKeyBase64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

private fun clearKeyHex(value: String?): String? {
    val text = value?.trim().orEmpty()
    val hex = text.replace("-", "")
    if (hex.length == 32 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return hex.lowercase()
    val bytes = runCatching { clearKeyBase64.decode(text.replace('+', '-').replace('/', '_')) }.getOrNull()
    return bytes?.takeIf { it.size == 16 }?.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
