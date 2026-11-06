package com.nuvio.app.features.autosync

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.CFStringConvertIANACharSetNameToEncoding
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSStringEncoding
import platform.Foundation.create

/** Container index range requests: short timeouts, like Android's dedicated client. */
private val containerIndexHttpClient: HttpClient by lazy {
    HttpClient(Darwin) {
        followRedirects = true
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            socketTimeoutMillis = 4_000
            requestTimeoutMillis = 5_000
        }
    }
}

/** Subtitle downloads. */
private val subtitleHttpClient: HttpClient by lazy {
    HttpClient(Darwin) {
        followRedirects = true
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = 30_000
        }
    }
}

internal actual suspend fun <T> autoSyncHttpGet(
    url: String,
    headers: Map<String, String>,
    kind: AutoSyncHttpClientKind,
    timeoutMs: Long?,
    block: suspend (AutoSyncHttpResponse) -> T,
): T {
    val client = when (kind) {
        AutoSyncHttpClientKind.CONTAINER_INDEX -> containerIndexHttpClient
        AutoSyncHttpClientKind.SUBTITLE -> subtitleHttpClient
    }
    return client.prepareGet(url) {
        headers.forEach { (name, value) ->
            // Ktor refuses headers it manages itself (Content-Length, Transfer-Encoding, ...).
            if (!HttpHeaders.isUnsafe(name)) header(name, value)
        }
        if (timeoutMs != null) {
            timeout { requestTimeoutMillis = timeoutMs }
        }
    }.execute { response ->
        val channel = response.bodyAsChannel()
        block(
            AutoSyncHttpResponse(
                status = response.status.value,
                statusText = response.status.description,
                finalUrl = response.call.request.url.toString(),
                headers = response.headers.entries().associate { (name, values) ->
                    name.lowercase() to values.joinToString(",")
                },
                body = object : AutoSyncHttpBody {
                    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        channel.readAvailable(buffer, offset, length)
                },
            ),
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nsStringEncodingFor(charsetName: String): NSStringEncoding? {
    val cfName = CFStringCreateWithCString(null, charsetName, kCFStringEncodingUTF8) ?: return null
    try {
        val cfEncoding = CFStringConvertIANACharSetNameToEncoding(cfName)
        // kCFStringEncodingInvalidId
        if (cfEncoding == 0xFFFFFFFFu) return null
        return CFStringConvertEncodingToNSStringEncoding(cfEncoding)
    } finally {
        CFRelease(cfName)
    }
}

internal actual fun autoSyncIsCharsetSupported(charsetName: String): Boolean =
    nsStringEncodingFor(charsetName) != null

internal actual fun autoSyncDecodeBytes(
    bytes: ByteArray,
    offset: Int,
    length: Int,
    charsetName: String,
    strict: Boolean,
): String? {
    val encoding = nsStringEncodingFor(charsetName) ?: return null
    if (length <= 0) return ""
    decodeStrict(bytes, offset, length, encoding)?.let { return it }
    if (strict) return null

    // Lenient: keep every decodable character and replace the rest with U+FFFD,
    // like String(bytes, charset) on the JVM.
    val result = StringBuilder(length)
    var position = offset
    val end = offset + length
    while (position < end) {
        var decoded: String? = null
        var consumed = 0
        for (size in 1..minOf(4, end - position)) {
            decoded = decodeStrict(bytes, position, size, encoding)
            if (decoded != null) {
                consumed = size
                break
            }
        }
        if (decoded != null) {
            result.append(decoded)
            position += consumed
        } else {
            result.append('�')
            position++
        }
    }
    return result.toString()
}

/** Decodes the bytes in [encoding], or returns null when they are not valid in it. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun decodeStrict(bytes: ByteArray, offset: Int, length: Int, encoding: NSStringEncoding): String? {
    val data = bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(offset), length = length.toULong())
    }
    return NSString.create(data = data, encoding = encoding) as String?
}
