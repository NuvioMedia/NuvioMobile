package com.nuvio.app.features.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.nuvio.app.core.diagnostics.SentryNetworkBreadcrumbInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI

/**
 * Downloads subtitle files from remote URLs to local cache and provides
 * content:// URIs via FileProvider so external players can read them.
 */
object SubtitleFileCache {
    private const val TAG = "SubtitleFileCache"
    private const val SUBTITLE_CACHE_DIR = "subtitles"

    private var appContext: Context? = null
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(SentryNetworkBreadcrumbInterceptor())
            .build()
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private val cacheDir: File?
        get() = appContext?.let { File(it.cacheDir, SUBTITLE_CACHE_DIR).also { dir -> dir.mkdirs() } }

    /**
     * Downloads subtitle files and returns updated [SubtitleInput] list with
     * content:// URIs instead of HTTP URLs.
     *
     * Subtitles that fail to download are silently skipped (not included in result).
     * Returns null if no subtitles could be downloaded.
     */
    suspend fun cacheSubtitles(subtitles: List<SubtitleInput>): List<SubtitleInput>? {
        if (subtitles.isEmpty()) return null
        val context = appContext ?: return null

        // Clean old cached subtitles before downloading new ones
        clearCache()

        val cached = subtitles.mapNotNull { input ->
            try {
                val localUri = downloadToCache(context, input)
                if (localUri != null) {
                    input.copy(url = localUri.toString())
                } else {
                    Log.w(TAG, "Failed to download subtitle: ${input.name}")
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error caching subtitle: ${input.name}", e)
                null
            }
        }

        return cached.ifEmpty { null }
    }

    /**
     * Downloads a single subtitle file and returns a content:// URI.
     */
    private suspend fun downloadToCache(context: Context, input: SubtitleInput): Uri? =
        withContext(Dispatchers.IO) {
            val dir = cacheDir ?: return@withContext null
            val extension = guessExtension(input.url)
            val filename = sanitizeFilename("${input.lang}_${input.name}.$extension")
            val file = File(dir, filename)

            try {
                if (input.url.startsWith("file:")) {
                    File(URI(input.url)).copyTo(file, overwrite = true)
                    return@withContext FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }
                if (input.url.startsWith("content:")) {
                    context.contentResolver.openInputStream(Uri.parse(input.url))?.use { inputStream ->
                        file.outputStream().use { output -> inputStream.copyTo(output) }
                    } ?: return@withContext null
                    return@withContext FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }
                val request = Request.Builder().url(input.url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "HTTP ${response.code} downloading subtitle: ${input.url}")
                        return@withContext null
                    }

                    val body = response.body ?: return@withContext null
                    file.outputStream().use { output ->
                        body.byteStream().copyTo(output)
                    }
                }

                // Return content:// URI via FileProvider
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download subtitle file: ${input.url}", e)
                file.delete()
                null
            }
        }

    /**
     * Removes all cached subtitle files.
     */
    fun clearCache() {
        try {
            cacheDir?.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear subtitle cache", e)
        }
    }

    private fun guessExtension(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        return when {
            path.endsWith(".vtt", ignoreCase = true) -> "vtt"
            path.endsWith(".ass", ignoreCase = true) -> "ass"
            path.endsWith(".ssa", ignoreCase = true) -> "ssa"
            path.endsWith(".ttml", ignoreCase = true) -> "ttml"
            path.endsWith(".dfxp", ignoreCase = true) -> "dfxp"
            else -> "srt"
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            .take(100) // Limit filename length
    }
}
