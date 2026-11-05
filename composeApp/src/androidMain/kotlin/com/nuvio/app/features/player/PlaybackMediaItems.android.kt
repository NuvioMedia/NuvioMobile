package com.nuvio.app.features.player

import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal fun playbackMediaItemFromUrl(
    url: String,
    responseHeaders: Map<String, String> = emptyMap(),
    streamType: String? = null,
): MediaItem {
    val builder = MediaItem.Builder().setUri(url)
    inferPlaybackMimeType(
        url = url,
        responseHeaders = responseHeaders,
        streamType = streamType,
    )?.let(builder::setMimeType)
    return builder.build()
}

private fun inferPlaybackMimeType(
    url: String,
    responseHeaders: Map<String, String>,
    streamType: String?,
): String? =
    inferMimeTypeFromStreamType(streamType)
        ?: inferMimeTypeFromResponseHeaders(responseHeaders)
        ?: inferMimeTypeFromLocalContent(url)
        ?: inferMimeTypeFromPath(url)

/**
 * Local files can carry a misleading extension (downloads keep the extension derived from the
 * source URL), so the container is detected from the actual payload instead of the file name.
 * Falls back to the extension based derivation whenever the content cannot be inspected.
 */
private fun inferMimeTypeFromLocalContent(url: String): String? {
    if (!isLocalUri(url)) return null
    localMimeTypeCache[url]?.let { return it }
    val detected = runOnBackgroundDispatcherIfNeeded { sniffLocalMimeType(url) } ?: return null
    if (localMimeTypeCache.size >= LOCAL_MIME_TYPE_CACHE_LIMIT) localMimeTypeCache.clear()
    localMimeTypeCache[url] = detected
    return detected
}

private fun isLocalUri(url: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase(Locale.US)
    return scheme == "file" || scheme == "content"
}

private fun sniffLocalMimeType(url: String): String? {
    val header = readLocalHeaderBytes(url) ?: return null
    return mimeTypeFromHeader(header)
}

private fun readLocalHeaderBytes(url: String): ByteArray? = runCatching {
    val uri = Uri.parse(url)
    when (uri.scheme?.lowercase(Locale.US)) {
        "file" -> uri.path?.let { path ->
            FileInputStream(File(path)).use { it.readHeaderBytes() }
        }
        "content" -> {
            val context = PlayerSettingsStorage.applicationContext() ?: return@runCatching null
            context.contentResolver.openInputStream(uri)?.use { it.readHeaderBytes() }
        }
        else -> null
    }
}.getOrNull()

private fun InputStream.readHeaderBytes(): ByteArray {
    val buffer = ByteArray(HEADER_SNIFF_BYTES)
    var offset = 0
    while (offset < buffer.size) {
        val read = read(buffer, offset, buffer.size - offset)
        if (read <= 0) break
        offset += read
    }
    return buffer.copyOf(offset)
}

private fun mimeTypeFromHeader(header: ByteArray): String? = when {
    header.startsWithBytes(EBML_HEADER) -> MimeTypes.VIDEO_MATROSKA
    header.matchesBytesAt(4, MP4_FTYP_HEADER) -> MimeTypes.VIDEO_MP4
    header.size > MPEG_TS_PACKET_OFFSET &&
        header[0] == MPEG_TS_SYNC_BYTE &&
        header[MPEG_TS_PACKET_OFFSET] == MPEG_TS_SYNC_BYTE -> MimeTypes.VIDEO_MP2T
    header.size >= 12 &&
        header.startsWithBytes(RIFF_HEADER) &&
        header.matchesBytesAt(8, AVI_HEADER) -> MimeTypes.VIDEO_AVI
    header.startsWithBytes(FLV_HEADER) -> MimeTypes.VIDEO_FLV
    header.startsWithBytes(OGG_HEADER) -> MIME_APPLICATION_OGG
    else -> null
}

private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean =
    size >= prefix.size && matchesBytesAt(0, prefix)

private fun ByteArray.matchesBytesAt(offset: Int, expected: ByteArray): Boolean {
    if (size < offset + expected.size) return false
    return expected.indices.all { this[offset + it] == expected[it] }
}

/**
 * File reads must not happen on the main thread, but [playbackMediaItemFromUrl] is synchronous.
 * The actual IO therefore runs on a background dispatcher and the caller waits for the result.
 */
private fun <T> runOnBackgroundDispatcherIfNeeded(block: () -> T): T =
    if (Looper.myLooper() == Looper.getMainLooper()) {
        runBlocking(Dispatchers.IO) { block() }
    } else {
        block()
    }

private fun inferMimeTypeFromStreamType(streamType: String?): String? {
    val normalized = streamType
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return when (normalized) {
        "hls", "m3u8" -> MimeTypes.APPLICATION_M3U8
        "dash", "mpd" -> MimeTypes.APPLICATION_MPD
        "smoothstreaming", "ss" -> MimeTypes.APPLICATION_SS
        else -> null
    }
}

private fun inferMimeTypeFromResponseHeaders(headers: Map<String, String>): String? {
    if (headers.isEmpty()) return null

    headers.entries
        .firstOrNull { (key, _) -> key.equals("Content-Type", ignoreCase = true) }
        ?.value
        ?.let(::normalizeMimeType)
        ?.let { return it }

    val contentDisposition = headers.entries
        .firstOrNull { (key, _) -> key.equals("Content-Disposition", ignoreCase = true) }
        ?.value
        ?: return null

    val filename = contentDisposition
        .substringAfter("filename*=", missingDelimiterValue = "")
        .substringAfterLast("''", missingDelimiterValue = "")
        .ifBlank {
            contentDisposition.substringAfter("filename=", missingDelimiterValue = "")
        }
        .trim()
        .trim('"', '\'')
        .takeIf { it.isNotBlank() }

    return inferMimeTypeFromPath(filename)
}

internal fun normalizeMimeType(contentType: String?): String? {
    val normalized = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.US)
        ?: return null

    return when (normalized) {
        "application/vnd.apple.mpegurl",
        "application/mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
        "application/m3u8" -> MimeTypes.APPLICATION_M3U8

        "application/dash+xml",
        "video/vnd.mpeg.dash.mpd" -> MimeTypes.APPLICATION_MPD

        "application/vnd.ms-sstr+xml" -> MimeTypes.APPLICATION_SS

        "video/mp4",
        "application/mp4",
        "video/x-m4v" -> MimeTypes.VIDEO_MP4

        "video/webm",
        "audio/webm" -> MimeTypes.VIDEO_WEBM

        "video/x-matroska",
        "audio/x-matroska",
        "video/mkv",
        "audio/mkv" -> MimeTypes.VIDEO_MATROSKA

        else -> null
    }
}

private fun inferMimeTypeFromPath(path: String?): String? {
    val normalized = path
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val pathWithoutFragment = normalized.substringBefore('#')
    val pathPart = pathWithoutFragment.substringBefore('?')
    val queryPart = pathWithoutFragment.substringAfter('?', missingDelimiterValue = "")
    val fileName = pathPart.substringAfterLast('/')
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")

    return when {
        extension == "m3u8" -> MimeTypes.APPLICATION_M3U8
        extension == "mpd" -> MimeTypes.APPLICATION_MPD
        extension == "ism" || extension == "isml" -> MimeTypes.APPLICATION_SS
        extension == "mkv" -> MimeTypes.VIDEO_MATROSKA
        extension == "webm" -> MimeTypes.VIDEO_WEBM
        extension == "mp4" || extension == "m4v" -> MimeTypes.VIDEO_MP4
        extension == "ts" || extension == "mts" || extension == "m2ts" -> MimeTypes.VIDEO_MP2T
        extension == "mov" -> MIME_VIDEO_QUICK_TIME
        extension == "avi" -> MimeTypes.VIDEO_AVI
        extension == "mpeg" || extension == "mpg" -> MimeTypes.VIDEO_MPEG
        else -> inferMimeTypeFromQuery(queryPart)
            ?: inferMimeTypeFromDelimitedToken(pathPart)
            ?: inferMimeTypeFromDelimitedToken(queryPart)
    }
}

private fun inferMimeTypeFromQuery(query: String): String? {
    if (query.isBlank()) return null

    query.split('&').forEach { parameter ->
        val key = parameter.substringBefore('=', missingDelimiterValue = "").trim()
        val value = parameter.substringAfter('=', missingDelimiterValue = "").trim()
        if (key.isBlank() || value.isBlank()) return@forEach

        when (key) {
            "format",
            "mime",
            "mime_type",
            "contenttype",
            "content_type",
            "type",
            "ext",
            "extension",
            "output" -> when (value.substringAfterLast('/').substringAfterLast('.')) {
                "m3u8" -> return MimeTypes.APPLICATION_M3U8
                "mpd" -> return MimeTypes.APPLICATION_MPD
                "ism", "isml" -> return MimeTypes.APPLICATION_SS
                "mkv" -> return MimeTypes.VIDEO_MATROSKA
                "webm" -> return MimeTypes.VIDEO_WEBM
                "mp4", "m4v" -> return MimeTypes.VIDEO_MP4
                "ts", "mts", "m2ts" -> return MimeTypes.VIDEO_MP2T
                "mov" -> return MIME_VIDEO_QUICK_TIME
                "avi" -> return MimeTypes.VIDEO_AVI
                "mpeg", "mpg" -> return MimeTypes.VIDEO_MPEG
            }
        }

        when (value) {
            "application/vnd.apple.mpegurl",
            "application/mpegurl",
            "application/x-mpegurl",
            "audio/mpegurl",
            "audio/x-mpegurl",
            "application/m3u8",
            "hls" -> return MimeTypes.APPLICATION_M3U8
            "application/dash+xml",
            "video/vnd.mpeg.dash.mpd",
            "dash" -> return MimeTypes.APPLICATION_MPD
            "application/vnd.ms-sstr+xml",
            "smoothstreaming",
            "ss" -> return MimeTypes.APPLICATION_SS
        }
    }

    return null
}

private fun inferMimeTypeFromDelimitedToken(value: String): String? =
    when {
        DELIMITED_M3U8_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_M3U8
        DELIMITED_HLS_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_M3U8
        DELIMITED_MPD_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_MPD
        DELIMITED_SS_PATTERN.containsMatchIn(value) -> MimeTypes.APPLICATION_SS
        else -> null
    }

internal fun probeMimeType(url: String, headers: Map<String, String>): String? {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return null
    val methods = listOf("HEAD", "GET")
    methods.forEach { method ->
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 3_000
                readTimeout = 3_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Accept", "*/*")
                headers.forEach { (key, value) ->
                    setRequestProperty(key, value)
                }
            }
            try {
                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    val contentType = connection.contentType
                    normalizeMimeType(contentType)
                } else null
            } finally {
                connection.disconnect()
            }
        }.getOrNull()?.let { return it }
    }
    return null
}

private const val MIME_VIDEO_QUICK_TIME = "video/quicktime"
private const val MIME_APPLICATION_OGG = "application/ogg"
private const val HEADER_SNIFF_BYTES = 4096
private const val MPEG_TS_PACKET_OFFSET = 188
private const val MPEG_TS_SYNC_BYTE: Byte = 0x47
private const val LOCAL_MIME_TYPE_CACHE_LIMIT = 64
private val EBML_HEADER = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
private val MP4_FTYP_HEADER = "ftyp".toByteArray(Charsets.US_ASCII)
private val RIFF_HEADER = "RIFF".toByteArray(Charsets.US_ASCII)
private val AVI_HEADER = "AVI ".toByteArray(Charsets.US_ASCII)
private val FLV_HEADER = "FLV".toByteArray(Charsets.US_ASCII)
private val OGG_HEADER = "OggS".toByteArray(Charsets.US_ASCII)
private val localMimeTypeCache = ConcurrentHashMap<String, String>()
private val DELIMITED_M3U8_PATTERN = Regex("(^|[=/_.?&%-])m3u8($|[=/_.?&%-])")
private val DELIMITED_HLS_PATTERN = Regex("(^|[=/_.?&%-])hls($|[=/_.?&%-])")
private val DELIMITED_MPD_PATTERN = Regex("(^|[=/_.?&%-])mpd($|[=/_.?&%-])")
private val DELIMITED_SS_PATTERN = Regex("(^|[=/_.?&%-])(ism|isml)($|[=/_.?&%-])")
