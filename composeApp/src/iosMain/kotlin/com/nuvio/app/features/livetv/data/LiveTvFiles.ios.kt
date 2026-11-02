package com.nuvio.app.features.livetv.data

/** Live TV isn't built for iOS yet: nothing is stored, downloads fail politely. */
internal actual object LiveTvFiles {
    actual fun exists(dir: String, name: String): Boolean = false
    actual fun lastModified(dir: String, name: String): Long = 0L
    actual fun size(dir: String, name: String): Long = 0L
    actual fun delete(dir: String, name: String) {}
    actual fun list(dir: String): List<String> = emptyList()

    actual suspend fun download(
        url: String,
        dir: String,
        name: String,
        userAgent: String,
        onProgress: ((bytes: Long) -> Unit)?,
    ): Boolean = error("Live TV isn't available on this platform yet")

    actual fun writeText(dir: String, name: String, text: String): Boolean = false
    actual fun <T> readChunks(dir: String, name: String, block: (Sequence<String>) -> T): T? = null
    actual fun readSmall(dir: String, name: String): String? = null
    actual fun writeSmall(dir: String, name: String, text: String) {}
    actual val deviceName: String get() = "iPhone"
}
