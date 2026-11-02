package com.nuvio.app.features.livetv.data

import android.content.Context
import android.os.Build
import com.nuvio.app.features.livetv.model.PlaylistSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal actual object LiveTvFiles {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            // A provider that trickles data forever would otherwise hold up every other update.
            .callTimeout(10, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun dir(dir: String): File {
        val base = appContext?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        return File(File(base, "livetv"), dir).also { it.mkdirs() }
    }

    private fun file(dir: String, name: String) = File(dir(dir), name)

    actual fun exists(dir: String, name: String): Boolean = file(dir, name).isFile

    actual fun lastModified(dir: String, name: String): Long = file(dir, name).takeIf { it.isFile }?.lastModified() ?: 0L

    actual fun size(dir: String, name: String): Long = file(dir, name).takeIf { it.isFile }?.length() ?: 0L

    actual fun delete(dir: String, name: String) {
        file(dir, name).delete()
    }

    actual fun list(dir: String): List<String> = dir(dir).listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()

    actual suspend fun download(
        url: String,
        dir: String,
        name: String,
        userAgent: String,
        onProgress: ((bytes: Long) -> Unit)?,
    ): Boolean = withContext(Dispatchers.IO) {
        val target = file(dir, name)
        val tmp = File(target.parentFile, "$name.tmp")
        try {
            val request = Request.Builder()
                .url(PlaylistSource.tidyLink(url))
                .header("User-Agent", userAgent.ifBlank { XtreamClient.DEFAULT_UA })
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error(XtreamClient.httpError(response.code))
                val body = response.body ?: error("Empty response")
                val raw: InputStream = body.byteStream()
                // Whatever came down (gzip from the server, a .gz file, or plain text) is stored
                // compressed, so reading it back is always the same.
                val input = BufferedInputStream(raw, 64 * 1024)
                val plain = unzipIfNeeded(input)
                var total = 0L
                GZIPOutputStream(tmp.outputStream().buffered(64 * 1024), 64 * 1024).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = plain.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        out.write(buf, 0, n)
                        total += n
                        onProgress?.invoke(total)
                    }
                }
                if (total == 0L) error("Empty response")
            }
            val changed = !target.isFile || !sameDecoded(target, tmp)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            // Touch it so "last updated" is now even when the content didn't change.
            target.setLastModified(System.currentTimeMillis())
            changed
        } catch (e: Exception) {
            tmp.delete()
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw IllegalStateException(describe(e), e)
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is UnknownHostException -> "Couldn't find the provider's server. Check the address, your internet, or try again later."
        is SocketTimeoutException -> "The provider took too long to answer (timeout). It may be busy; try again later."
        is java.net.ConnectException -> "Couldn't connect to the provider's server."
        is javax.net.ssl.SSLException -> "Secure connection to the provider failed."
        is IllegalArgumentException -> "That isn't a valid web address."
        else -> e.message ?: e.javaClass.simpleName
    }

    /** A gzip stream is read through; anything else is handed back as is. */
    private fun unzipIfNeeded(input: BufferedInputStream): InputStream {
        input.mark(2)
        val b1 = input.read()
        val b2 = input.read()
        input.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(input, 64 * 1024) else input
    }

    private fun sameDecoded(a: File, b: File): Boolean = runCatching {
        fun digest(f: File): ByteArray {
            val md = MessageDigest.getInstance("SHA-1")
            open(f).use { s ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest()
        }
        digest(a).contentEquals(digest(b))
    }.getOrDefault(false)

    private fun open(f: File): InputStream {
        val input = BufferedInputStream(f.inputStream(), 64 * 1024)
        return unzipIfNeeded(input)
    }

    actual fun writeText(dir: String, name: String, text: String): Boolean {
        val target = file(dir, name)
        val tmp = File(target.parentFile, "$name.tmp")
        GZIPOutputStream(tmp.outputStream().buffered(64 * 1024), 64 * 1024).use { it.write(text.toByteArray()) }
        val changed = !target.isFile || !sameDecoded(target, tmp)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        target.setLastModified(System.currentTimeMillis())
        return changed
    }

    actual fun <T> readChunks(dir: String, name: String, block: (Sequence<String>) -> T): T? {
        val f = file(dir, name)
        if (!f.isFile) return null
        return InputStreamReader(open(f), Charsets.UTF_8).use { reader ->
            val buf = CharArray(256 * 1024)
            val seq = generateSequence {
                val n = reader.read(buf)
                if (n < 0) null else String(buf, 0, n)
            }
            block(seq)
        }
    }

    actual fun readSmall(dir: String, name: String): String? =
        file(dir, name).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    actual fun writeSmall(dir: String, name: String, text: String) {
        runCatching { file(dir, name).writeText(text) }
    }

    actual val deviceName: String
        get() = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android phone"
}
