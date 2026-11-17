package com.nuvio.app.features.mdblist

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpPostJson
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal sealed interface MdbListRatingsCredential {
    data class ApiKey(val value: String) : MdbListRatingsCredential {
        override fun toString(): String = "ApiKey()"
    }

    data class Account(val scope: MdbListAuthScope) : MdbListRatingsCredential
}

internal class MdbListRatingsClient(
    private val accountApi: MdbListApiClient,
    private val authStore: MdbListAuthStore,
    private val getText: suspend (String) -> String = { httpGetText(it) },
    private val postJson: suspend (String, String) -> String = { url, body -> httpPostJson(url, body) },
) {
    fun checkCredential(credential: MdbListRatingsCredential) {
        if (credential is MdbListRatingsCredential.Account) authStore.checkScope(credential.scope)
    }

    suspend fun getMedia(
        mediaType: String,
        imdbId: String,
        credential: MdbListRatingsCredential,
    ): String = when (credential) {
        is MdbListRatingsCredential.ApiKey -> getText(
            "https://api.mdblist.com/imdb/$mediaType/$imdbId/?apikey=${credential.value.encodeURLParameter()}&append_to_response=keyword"
        )
        is MdbListRatingsCredential.Account -> accountApi.get(
            "/imdb/$mediaType/$imdbId/",
            query = mapOf("append_to_response" to "keyword"),
            scope = credential.scope,
        ).body
    }

    suspend fun getMediaBatch(
        mediaType: String,
        imdbIds: List<String>,
        credential: MdbListRatingsCredential,
    ): String {
        val body = buildJsonObject {
            put("ids", JsonArray(imdbIds.map(::JsonPrimitive)))
            put("append_to_response", buildJsonArray { add("keyword") })
        }.toString()
        return when (credential) {
            is MdbListRatingsCredential.ApiKey -> postJson(
                "https://api.mdblist.com/imdb/$mediaType/?apikey=${credential.value.encodeURLParameter()}",
                body,
            )
            is MdbListRatingsCredential.Account -> accountApi.post(
                "/imdb/$mediaType/",
                body = body,
                scope = credential.scope,
            ).body
        }
    }
}
