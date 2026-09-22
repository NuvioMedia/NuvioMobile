package com.nuvio.app.features.mdblist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class MdbListConfiguration(
    val clientId: String,
    val appVersion: String,
    val baseUrl: String = "https://api.mdblist.com"
)

enum class MdbListHttpMethod { GET, POST, PUT, DELETE }

class MdbListHttpRequest(
    val method: MdbListHttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: String = "",
    val form: Map<String, String>? = null,
    val accessToken: String? = null,
    val limitKey: String = "public",
    val retrySafe: Boolean = method == MdbListHttpMethod.GET
) {
    override fun toString(): String = "MdbListHttpRequest(method=$method)"
}

class MdbListHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun errorCode(): String? = runCatching {
        (Json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.matches(Regex("[a-z_]{1,64}")) }

    override fun toString(): String = "MdbListHttpResponse(status=$status)"
}

class MdbListApiException(
    val status: Int? = null,
    val code: String? = null,
    val retryAtEpochMs: Long? = null
) : Exception("MDBList request failed${status?.let { " (HTTP $it)" }.orEmpty()}")

fun interface MdbListHttpEngine {
    suspend fun execute(request: MdbListHttpRequest): MdbListHttpResponse
}
