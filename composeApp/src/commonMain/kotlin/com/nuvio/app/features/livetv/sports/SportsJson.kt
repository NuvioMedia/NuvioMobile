package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.livetv.data.liveTvJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * A small mutable JSON tree with org.json's reading and writing habits (opt* reads that coerce
 * strings to numbers and give "" or a fallback for anything missing, put() that chains), so the
 * TV app's feed parsers port line for line. Built on kotlinx.serialization for the actual text.
 */

/** org.json's explicit null value (a key that is present, but null). */
internal object JsonNullValue {
    override fun toString(): String = "null"
}

internal class JSONObject {
    private val map = LinkedHashMap<String, Any?>()

    constructor()
    constructor(text: String) {
        val e = liveTvJson.parseToJsonElement(text)
        val o = e as? JsonObject ?: throw IllegalArgumentException("not a JSON object")
        o.forEach { (k, v) -> map[k] = fromElement(v) }
    }
    constructor(from: Map<*, *>) {
        from.forEach { (k, v) -> if (k != null) map[k.toString()] = wrap(v) }
    }

    fun put(key: String, value: Any?): JSONObject {
        if (value == null) map.remove(key) else map[key] = wrap(value)
        return this
    }

    fun opt(key: String): Any? = map[key]
    fun has(key: String): Boolean = map.containsKey(key)
    fun isNull(key: String): Boolean = map[key].let { it == null || it === JsonNullValue }
    fun keys(): Iterator<String> = map.keys.toList().iterator()
    val keySet: Set<String> get() = map.keys
    fun length(): Int = map.size
    fun remove(key: String): Any? = map.remove(key)

    fun optString(key: String, fallback: String = ""): String = stringOf(map[key], fallback)
    fun optInt(key: String, fallback: Int = 0): Int = numberOf(map[key])?.toInt() ?: fallback
    fun optLong(key: String, fallback: Long = 0L): Long = numberOf(map[key])?.toLong() ?: fallback
    fun optDouble(key: String, fallback: Double = Double.NaN): Double = numberOf(map[key])?.toDouble() ?: fallback
    fun optBoolean(key: String, fallback: Boolean = false): Boolean = booleanOf(map[key]) ?: fallback
    fun optJSONObject(key: String): JSONObject? = map[key] as? JSONObject
    fun optJSONArray(key: String): JSONArray? = map[key] as? JSONArray

    fun getString(key: String): String = map[key]?.let { stringOf(it, "") } ?: throw NoSuchElementException(key)
    fun getInt(key: String): Int = numberOf(map[key])?.toInt() ?: throw NoSuchElementException(key)
    fun getJSONObject(key: String): JSONObject = optJSONObject(key) ?: throw NoSuchElementException(key)
    fun getJSONArray(key: String): JSONArray = optJSONArray(key) ?: throw NoSuchElementException(key)

    fun toElement(): JsonObject = JsonObject(map.mapValues { (_, v) -> toElement(v) })
    override fun toString(): String = toElement().toString()

    companion object {
        val NULL: Any = JsonNullValue
    }
}

internal class JSONArray {
    private val list = ArrayList<Any?>()

    constructor()
    constructor(text: String) {
        val e = liveTvJson.parseToJsonElement(text)
        val a = e as? JsonArray ?: throw IllegalArgumentException("not a JSON array")
        a.forEach { list += fromElement(it) }
    }
    constructor(items: Collection<*>) {
        items.forEach { list += wrap(it) }
    }

    fun put(value: Any?): JSONArray {
        list += wrap(value)
        return this
    }

    fun length(): Int = list.size
    fun opt(i: Int): Any? = list.getOrNull(i)
    fun optJSONObject(i: Int): JSONObject? = list.getOrNull(i) as? JSONObject
    fun optJSONArray(i: Int): JSONArray? = list.getOrNull(i) as? JSONArray
    fun optString(i: Int, fallback: String = ""): String = stringOf(list.getOrNull(i), fallback)
    fun optInt(i: Int, fallback: Int = 0): Int = numberOf(list.getOrNull(i))?.toInt() ?: fallback
    fun optLong(i: Int, fallback: Long = 0L): Long = numberOf(list.getOrNull(i))?.toLong() ?: fallback
    fun getJSONObject(i: Int): JSONObject = optJSONObject(i) ?: throw NoSuchElementException("$i")
    fun getString(i: Int): String = list.getOrNull(i)?.let { stringOf(it, "") } ?: throw NoSuchElementException("$i")

    fun toElement(): JsonArray = JsonArray(list.map { toElement(it) })
    override fun toString(): String = toElement().toString()
}

private fun wrap(v: Any?): Any? = when (v) {
    null -> null
    is JSONObject, is JSONArray, is String, is Boolean, is Number -> v
    JsonNullValue -> v
    is Map<*, *> -> JSONObject(v)
    is Collection<*> -> JSONArray(v)
    is JsonElement -> fromElement(v)
    else -> v.toString()
}

private fun fromElement(e: JsonElement): Any? = when (e) {
    is JsonNull -> JsonNullValue
    is JsonObject -> JSONObject().also { o -> e.forEach { (k, v) -> o.put(k, fromElement(v) ?: JsonNullValue) } }
    is JsonArray -> JSONArray().also { a -> e.forEach { a.put(fromElement(it) ?: JsonNullValue) } }
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.content == "true" -> true
        e.content == "false" -> false
        else -> e.content.toLongOrNull()?.let { l -> if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l }
            ?: e.content.toDoubleOrNull() ?: e.content
    }
}

private fun toElement(v: Any?): JsonElement = when (v) {
    null, JsonNullValue -> JsonNull
    is JSONObject -> v.toElement()
    is JSONArray -> v.toElement()
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Int -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    is Double -> if (v.isNaN() || v.isInfinite()) JsonNull else JsonPrimitive(v)
    is Float -> JsonPrimitive(v.toDouble())
    is Number -> JsonPrimitive(v.toDouble())
    else -> JsonPrimitive(v.toString())
}

private fun stringOf(v: Any?, fallback: String): String = when (v) {
    null -> fallback
    JsonNullValue -> "null"
    is String -> v
    is Double -> if (v == v.toLong().toDouble() && !v.isInfinite()) v.toLong().toString() + ".0" else v.toString()
    else -> v.toString()
}

private fun numberOf(v: Any?): Number? = when (v) {
    is Number -> v
    is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()
    is Boolean -> if (v) 1 else 0
    else -> null
}

private fun booleanOf(v: Any?): Boolean? = when (v) {
    is Boolean -> v
    is String -> when (v.lowercase()) { "true" -> true; "false" -> false; else -> null }
    else -> null
}

/** org.json's JSONArray.optString for every element, blanks left out (the TV app's helper). */
internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }

internal fun JSONObject?.stringMap(): Map<String, String> =
    if (this == null) emptyMap() else keySet.associateWith { optString(it) }

/** Every element that is an object. */
internal fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
