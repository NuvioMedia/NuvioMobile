package com.nuvio.app.features.addons

internal fun addonTransportBaseUrl(manifestUrl: String): String =
    manifestUrl.substringBefore("?").removeSuffix("/manifest.json")

internal fun externalAddonType(type: String, season: Int?, episode: Int?): String {
    val normalized = type.trim().lowercase()
    return if (normalized == "tv" && season != null && episode != null) "series" else normalized
}

internal fun buildAddonResourceUrl(
    manifestUrl: String,
    resource: String,
    type: String,
    id: String,
    extraPathSegment: String? = null,
): String {
    val encodedId = id.encodeAddonPathSegment()
    val externalType = type.trim().lowercase()
    val baseUrl = addonTransportBaseUrl(manifestUrl)
    val query = manifestUrl.substringAfter("?", "").let { query ->
        if (query.isBlank()) "" else "?$query"
    }
    val resourceUrl = if (extraPathSegment.isNullOrEmpty()) {
        "$baseUrl/$resource/$externalType/$encodedId.json"
    } else {
        "$baseUrl/$resource/$externalType/$encodedId/$extraPathSegment.json"
    }
    return resourceUrl + query
}


internal fun String.encodeAddonPathSegment(): String =
    buildString {
        encodeToByteArray().forEach { byte ->
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (
                char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '_' ||
                char == '.' ||
                char == '~'
            ) {
                append(char)
            } else {
                append('%')
                append(ADDON_URL_HEX[value shr 4])
                append(ADDON_URL_HEX[value and 0x0F])
            }
        }
    }

private const val ADDON_URL_HEX = "0123456789ABCDEF"
