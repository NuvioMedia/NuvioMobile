package com.nuvio.app.features.debrid

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.DefaultRawHttpResponseMaxBytes
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class DebridApiResponse<T>(
    val status: Int,
    val body: T?,
    val rawBody: String,
) {
    val isSuccessful: Boolean
        get() = status in 200..299
}

internal object DebridApiJson {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}

internal object TorboxApiClient {
    private const val BASE_URL = "https://api.torbox.app"
    // Keep each page bounded; season packs can contain large file arrays.
    private const val cloudListResponseMaxBytes = 16 * 1024 * 1024

    suspend fun startDeviceAuthorization(
        appName: String,
    ): DebridApiResponse<TorboxEnvelopeDto<TorboxDeviceAuthorizationDto>> =
        requestWithoutAuth(
            method = "GET",
            url = "$BASE_URL/v1/api/user/auth/device/start?${
                queryString("app" to appName)
            }",
        )

    suspend fun redeemDeviceAuthorization(
        deviceCode: String,
    ): DebridApiResponse<TorboxEnvelopeDto<TorboxDeviceTokenDto>> =
        requestWithoutAuth(
            method = "POST",
            url = "$BASE_URL/v1/api/user/auth/device/token",
            body = DebridApiJson.json.encodeToString(TorboxDeviceTokenRequestDto(deviceCode = deviceCode)),
            contentType = "application/json",
        )

    suspend fun validateApiKey(apiKey: String): Boolean =
        getUser(apiKey.trim()).status in 200..299

    private suspend fun getUser(apiKey: String): RawHttpResponse =
        httpRequestRaw(
            method = "GET",
            url = "$BASE_URL/v1/api/user/me",
            headers = authHeaders(apiKey),
            body = "",
        )

    suspend fun checkCached(
        apiKey: String,
        hashes: List<String>,
    ): DebridApiResponse<TorboxEnvelopeDto<Map<String, TorboxCachedItemDto>>> {
        val normalizedHashes = hashes
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedHashes.isEmpty()) {
            return DebridApiResponse(
                status = 200,
                body = TorboxEnvelopeDto(success = true, data = emptyMap()),
                rawBody = "",
            )
        }
        val body = DebridApiJson.json.encodeToString(
            TorboxCheckCachedRequestDto(hashes = normalizedHashes),
        )
        return request(
            method = "POST",
            url = "$BASE_URL/v1/api/torrents/checkcached?format=object",
            apiKey = apiKey,
            body = body,
            contentType = "application/json",
        )
    }

    suspend fun createTorrent(apiKey: String, magnet: String): DebridApiResponse<TorboxEnvelopeDto<TorboxCreateTorrentDataDto>> {
        val boundary = "NuvioDebrid${magnet.hashCode().toUInt()}"
        val body = multipartFormBody(
            boundary = boundary,
            "magnet" to magnet,
            "add_only_if_cached" to "true",
            "allow_zip" to "false",
        )
        return request(
            method = "POST",
            url = "$BASE_URL/v1/api/torrents/createtorrent",
            apiKey = apiKey,
            body = body,
            contentType = "multipart/form-data; boundary=$boundary",
        )
    }

    suspend fun getTorrent(apiKey: String, id: Int): DebridApiResponse<TorboxEnvelopeDto<TorboxTorrentDataDto>> =
        request(
            method = "GET",
            url = "$BASE_URL/v1/api/torrents/mylist?${
                queryString(
                    "id" to id.toString(),
                    "bypass_cache" to "true",
                )
            }",
            apiKey = apiKey,
        )

    suspend fun listCloudTorrents(apiKey: String): DebridApiResponse<TorboxEnvelopeDto<List<TorboxCloudItemDto>>> =
        listCloudItems(apiKey, "torrents")

    suspend fun listCloudUsenet(apiKey: String): DebridApiResponse<TorboxEnvelopeDto<List<TorboxCloudItemDto>>> =
        listCloudItems(apiKey, "usenet")

    suspend fun listCloudWebDownloads(apiKey: String): DebridApiResponse<TorboxEnvelopeDto<List<TorboxCloudItemDto>>> =
        listCloudItems(apiKey, "webdl")

    private suspend fun listCloudItems(
        apiKey: String,
        kind: String,
    ): DebridApiResponse<TorboxEnvelopeDto<List<TorboxCloudItemDto>>> =
        fetchTorboxCloudPages { offset, limit ->
            request(
                method = "GET",
                url = "$BASE_URL/v1/api/$kind/mylist?offset=$offset&limit=$limit",
                apiKey = apiKey,
                maxResponseBodyBytes = cloudListResponseMaxBytes,
            )
        }

    suspend fun requestDownloadLink(
        apiKey: String,
        torrentId: Int,
        fileId: Int?,
    ): DebridApiResponse<TorboxEnvelopeDto<String>> =
        request(
            method = "GET",
            url = "$BASE_URL/v1/api/torrents/requestdl?${
                queryString(
                    "token" to apiKey,
                    "torrent_id" to torrentId.toString(),
                    "file_id" to fileId?.toString(),
                    "zip_link" to "false",
                    "redirect" to "false",
                    "append_name" to "false",
                )
            }",
            apiKey = apiKey,
        )

    suspend fun requestCloudTorrentDownloadLink(
        apiKey: String,
        torrentId: String,
        fileId: String?,
    ): DebridApiResponse<TorboxEnvelopeDto<String>> =
        request(
            method = "GET",
            url = "$BASE_URL/v1/api/torrents/requestdl?${
                queryString(
                    "token" to apiKey,
                    "torrent_id" to torrentId,
                    "file_id" to fileId,
                    "zip_link" to "false",
                    "redirect" to "false",
                    "append_name" to "false",
                )
            }",
            apiKey = apiKey,
        )

    suspend fun requestCloudUsenetDownloadLink(
        apiKey: String,
        usenetId: String,
        fileId: String?,
    ): DebridApiResponse<TorboxEnvelopeDto<String>> =
        request(
            method = "GET",
            url = "$BASE_URL/v1/api/usenet/requestdl?${
                queryString(
                    "token" to apiKey,
                    "usenet_id" to usenetId,
                    "file_id" to fileId,
                    "zip_link" to "false",
                    "redirect" to "false",
                    "append_name" to "false",
                )
            }",
            apiKey = apiKey,
        )

    suspend fun requestCloudWebDownloadLink(
        apiKey: String,
        webId: String,
        fileId: String?,
    ): DebridApiResponse<TorboxEnvelopeDto<String>> =
        request(
            method = "GET",
            url = "$BASE_URL/v1/api/webdl/requestdl?${
                queryString(
                    "token" to apiKey,
                    "web_id" to webId,
                    "file_id" to fileId,
                    "zip_link" to "false",
                    "redirect" to "false",
                    "append_name" to "false",
                )
            }",
            apiKey = apiKey,
        )

    private suspend inline fun <reified T> request(
        method: String,
        url: String,
        apiKey: String,
        body: String = "",
        contentType: String? = null,
        maxResponseBodyBytes: Int = DefaultRawHttpResponseMaxBytes,
    ): DebridApiResponse<T> {
        val headers = authHeaders(apiKey) + listOfNotNull(
            contentType?.let { "Content-Type" to it },
            "Accept" to "application/json",
        )
        val response = httpRequestRaw(
            method = method,
            url = url,
            headers = headers,
            body = body,
            maxResponseBodyBytes = maxResponseBodyBytes,
        )
        return DebridApiResponse(
            status = response.status,
            body = response.decodeBody<T>(),
            rawBody = response.body,
        )
    }

    private suspend inline fun <reified T> requestWithoutAuth(
        method: String,
        url: String,
        body: String = "",
        contentType: String? = null,
    ): DebridApiResponse<T> {
        val headers = listOfNotNull(
            contentType?.let { "Content-Type" to it },
            "Accept" to "application/json",
        ).toMap()
        val response = httpRequestRaw(
            method = method,
            url = url,
            headers = headers,
            body = body,
        )
        return DebridApiResponse(
            status = response.status,
            body = response.decodeBody<T>(),
            rawBody = response.body,
        )
    }

    private fun authHeaders(apiKey: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $apiKey")
}

internal object RealDebridApiClient {
    private const val BASE_URL = "https://api.real-debrid.com/rest/1.0"

    suspend fun validateApiKey(apiKey: String): Boolean =
        httpRequestRaw(
            method = "GET",
            url = "$BASE_URL/user",
            headers = authHeaders(apiKey.trim()),
            body = "",
        ).status in 200..299

    suspend fun addMagnet(apiKey: String, magnet: String): DebridApiResponse<RealDebridAddTorrentDto> =
        formRequest(
            method = "POST",
            url = "$BASE_URL/torrents/addMagnet",
            apiKey = apiKey,
            fields = listOf("magnet" to magnet),
        )

    suspend fun getTorrentInfo(apiKey: String, id: String): DebridApiResponse<RealDebridTorrentInfoDto> =
        request(
            method = "GET",
            url = "$BASE_URL/torrents/info/${encodePathSegment(id)}",
            apiKey = apiKey,
        )

    suspend fun selectFiles(apiKey: String, id: String, files: String): DebridApiResponse<Unit> =
        formRequest(
            method = "POST",
            url = "$BASE_URL/torrents/selectFiles/${encodePathSegment(id)}",
            apiKey = apiKey,
            fields = listOf("files" to files),
        )

    suspend fun unrestrictLink(apiKey: String, link: String): DebridApiResponse<RealDebridUnrestrictLinkDto> =
        formRequest(
            method = "POST",
            url = "$BASE_URL/unrestrict/link",
            apiKey = apiKey,
            fields = listOf("link" to link),
        )

    suspend fun deleteTorrent(apiKey: String, id: String): DebridApiResponse<Unit> =
        request(
            method = "DELETE",
            url = "$BASE_URL/torrents/delete/${encodePathSegment(id)}",
            apiKey = apiKey,
        )

    private suspend inline fun <reified T> formRequest(
        method: String,
        url: String,
        apiKey: String,
        fields: List<Pair<String, String>>,
    ): DebridApiResponse<T> {
        val body = fields.joinToString("&") { (key, value) ->
            "${encodeFormValue(key)}=${encodeFormValue(value)}"
        }
        return request(
            method = method,
            url = url,
            apiKey = apiKey,
            body = body,
            contentType = "application/x-www-form-urlencoded",
        )
    }

    private suspend inline fun <reified T> request(
        method: String,
        url: String,
        apiKey: String,
        body: String = "",
        contentType: String? = null,
    ): DebridApiResponse<T> {
        val headers = authHeaders(apiKey) + listOfNotNull(
            contentType?.let { "Content-Type" to it },
            "Accept" to "application/json",
        )
        val response = httpRequestRaw(
            method = method,
            url = url,
            headers = headers,
            body = body,
        )
        return DebridApiResponse(
            status = response.status,
            body = response.decodeBody<T>(),
            rawBody = response.body,
        )
    }

    private fun authHeaders(apiKey: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $apiKey")
}

internal object PremiumizeApiClient {
    private const val BASE_URL = "https://www.premiumize.me"

    suspend fun validateApiKey(apiKey: String): Boolean {
        val response = accountInfo(apiKey.trim())
        return response.isSuccessful && response.body?.isSuccess == true
    }

    suspend fun startDeviceAuthorization(
        clientId: String,
    ): DebridApiResponse<PremiumizeDeviceAuthorizationDto> =
        formRequestWithoutAuth(
            method = "POST",
            url = "$BASE_URL/token",
            fields = listOf(
                "response_type" to "device_code",
                "client_id" to clientId,
            ),
        )

    suspend fun redeemDeviceAuthorization(
        clientId: String,
        deviceCode: String,
    ): DebridApiResponse<PremiumizeDeviceTokenDto> =
        formRequestWithoutAuth(
            method = "POST",
            url = "$BASE_URL/token",
            fields = listOf(
                "grant_type" to "device_code",
                "code" to deviceCode,
                "client_id" to clientId,
            ),
        )

    suspend fun accountInfo(apiKey: String): DebridApiResponse<PremiumizeAccountInfoDto> =
        request(
            method = "GET",
            url = "$BASE_URL/api/account/info",
            apiKey = apiKey,
        )

    suspend fun listAllItems(apiKey: String): DebridApiResponse<PremiumizeItemListAllDto> =
        request(
            method = "GET",
            url = "$BASE_URL/api/item/listall",
            apiKey = apiKey,
        )

    suspend fun itemDetails(
        apiKey: String,
        itemId: String,
    ): DebridApiResponse<PremiumizeItemDetailsDto> =
        request(
            method = "GET",
            url = "$BASE_URL/api/item/details?${queryString("id" to itemId)}",
            apiKey = apiKey,
        )

    suspend fun directDownload(
        apiKey: String,
        source: String,
    ): DebridApiResponse<PremiumizeDirectDownloadDto> =
        formRequest(
            method = "POST",
            url = "$BASE_URL/api/transfer/directdl",
            apiKey = apiKey,
            fields = listOf("src" to source),
        )

    suspend fun checkCache(
        apiKey: String,
        items: List<String>,
    ): DebridApiResponse<PremiumizeCacheCheckDto> {
        val normalizedItems = items.map { it.trim() }.filter { it.isNotBlank() }
        if (normalizedItems.isEmpty()) {
            return DebridApiResponse(
                status = 200,
                body = PremiumizeCacheCheckDto(status = "success", response = emptyList()),
                rawBody = "",
            )
        }
        return formRequest(
            method = "POST",
            url = "$BASE_URL/api/cache/check",
            apiKey = apiKey,
            fields = normalizedItems.map { "items[]" to it },
        )
    }

    private suspend inline fun <reified T> formRequestWithoutAuth(
        method: String,
        url: String,
        fields: List<Pair<String, String>>,
    ): DebridApiResponse<T> =
        requestWithoutAuth(
            method = method,
            url = url,
            body = formBody(fields),
            contentType = "application/x-www-form-urlencoded",
        )

    private suspend inline fun <reified T> formRequest(
        method: String,
        url: String,
        apiKey: String,
        fields: List<Pair<String, String>>,
    ): DebridApiResponse<T> =
        request(
            method = method,
            url = url,
            apiKey = apiKey,
            body = formBody(fields),
            contentType = "application/x-www-form-urlencoded",
        )

    private suspend inline fun <reified T> requestWithoutAuth(
        method: String,
        url: String,
        body: String = "",
        contentType: String? = null,
    ): DebridApiResponse<T> {
        val headers = listOfNotNull(
            contentType?.let { "Content-Type" to it },
            "Accept" to "application/json",
        ).toMap()
        val response = httpRequestRaw(
            method = method,
            url = url,
            headers = headers,
            body = body,
        )
        return DebridApiResponse(
            status = response.status,
            body = response.decodeBody<T>(),
            rawBody = response.body,
        )
    }

    private suspend inline fun <reified T> request(
        method: String,
        url: String,
        apiKey: String,
        body: String = "",
        contentType: String? = null,
    ): DebridApiResponse<T> {
        val headers = authHeaders(apiKey) + listOfNotNull(
            contentType?.let { "Content-Type" to it },
            "Accept" to "application/json",
        )
        val response = httpRequestRaw(
            method = method,
            url = url,
            headers = headers,
            body = body,
        )
        return DebridApiResponse(
            status = response.status,
            body = response.decodeBody<T>(),
            rawBody = response.body,
        )
    }

    private fun formBody(fields: List<Pair<String, String>>): String =
        fields.joinToString("&") { (key, value) ->
            "${encodeFormValue(key)}=${encodeFormValue(value)}"
        }

    private fun authHeaders(apiKey: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $apiKey")

    private val PremiumizeAccountInfoDto.isSuccess: Boolean
        get() = status.equals("success", ignoreCase = true)
}

object DebridCredentialValidator {
    suspend fun validateProvider(providerId: String, apiKey: String): Boolean {
        val normalized = apiKey.trim()
        if (normalized.isBlank()) return false
        return DebridProviderApis.apiFor(providerId)?.validateApiKey(normalized) == true
    }
}

internal suspend fun fetchTorboxCloudPages(
    fetchPage: suspend (offset: Int, limit: Int) -> DebridApiResponse<TorboxEnvelopeDto<List<TorboxCloudItemDto>>>,
): DebridApiResponse<TorboxEnvelopeDto<List<TorboxCloudItemDto>>> {
    val items = mutableListOf<TorboxCloudItemDto>()
    var offset = 0
    var limit = 100
    while (true) {
        val response = fetchPage(offset, limit)
        // Retry only a locally truncated response, never an API or decoding error.
        if (response.isSuccessful && response.rawBody.endsWith("\n...[truncated]")) {
            check(limit > 1) { "Torbox cloud item exceeds the response size limit." }
            limit = (limit / 2).coerceAtLeast(1)
            continue
        }
        val envelope = response.body
        if (!response.isSuccessful || envelope == null || envelope.success == false) return response
        val page = envelope.data.orEmpty()
        items.addAll(page)
        if (page.size < limit) {
            return response.copy(body = envelope.copy(data = items), rawBody = "")
        }
        offset += page.size
    }
}

private inline fun <reified T> RawHttpResponse.decodeBody(): T? {
    if (body.isBlank() || T::class == Unit::class) return null
    return try {
        DebridApiJson.json.decodeFromString<T>(body)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun multipartFormBody(boundary: String, vararg fields: Pair<String, String>): String =
    buildString {
        fields.forEach { (name, value) ->
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
            append(value).append("\r\n")
        }
        append("--").append(boundary).append("--\r\n")
    }
