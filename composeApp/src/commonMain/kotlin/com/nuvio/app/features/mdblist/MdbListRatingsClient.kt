package com.nuvio.app.features.mdblist

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpPostJson
import io.ktor.http.encodeURLParameter

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

    suspend fun getRating(
        mediaType: String,
        ratingType: String,
        credential: MdbListRatingsCredential,
        body: String,
    ): String = when (credential) {
        is MdbListRatingsCredential.ApiKey -> postJson(
            "https://api.mdblist.com/rating/$mediaType/$ratingType?apikey=${credential.value.encodeURLParameter()}",
            body,
        )
        is MdbListRatingsCredential.Account -> accountApi.post(
            "/rating/$mediaType/$ratingType",
            body = body,
            scope = credential.scope,
        ).body
    }
}
