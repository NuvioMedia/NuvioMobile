package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.loadAddonSubtitles
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object DownloadSubtitles {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepare(item: DownloadItem, localVideoUri: String): Unit = withContext(Dispatchers.Default) {
        val storage = DownloadSubtitleStorage(localVideoUri)
        val manifest = readManifest(storage)
        val saved = manifest.tracks.filter { storage.localFileUri(it.fileName) != null }.toMutableList()
        if (manifest.complete && saved.size == manifest.tracks.size) return@withContext
        var nextFileIndex = (saved.maxOfOrNull { it.fileName.substringBefore('.').toIntOrNull() ?: -1 } ?: -1) + 1
        val addonSubtitles = loadAddonSubtitles(item.subtitleRequests).map {
            StreamSubtitle(url = it.url, language = it.language, name = it.display)
        }
        val pending = (item.sourceSubtitles + addonSubtitles)
            .distinctBy { it.url }
            .filter { subtitle ->
                (subtitle.url.startsWith("https://", true) || subtitle.url.startsWith("http://", true)) &&
                    saved.none { it.sourceUrl == subtitle.url }
            }
        for (batch in pending.chunked(4)) {
            val fetched = coroutineScope {
                batch.map { subtitle ->
                    async {
                        val body = try {
                            withTimeoutOrNull(15_000L) {
                                httpGetTextWithHeaders(subtitle.url, subtitle.headers.orEmpty())
                            }?.takeIf { it.isNotBlank() && PlayerSubtitleCueParser.parse(it, subtitle.url).isNotEmpty() }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            null
                        }
                        subtitle to body
                    }
                }.awaitAll()
            }
            for ((subtitle, body) in fetched) {
                currentCoroutineContext().ensureActive()
                if (body == null) continue
                val extension = PlayerSubtitleCueParser.fileExtension(body, subtitle.url)
                val fileName = "${nextFileIndex++}.$extension"
                try {
                    storage.write(fileName, body)
                    saved += DownloadedSubtitle(subtitle.url, fileName, subtitle.language, subtitle.name)
                    storage.write("manifest.json", json.encodeToString(SubtitleManifest(tracks = saved.toList())))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (saved.isEmpty()) return@withContext
        runCatching {
            storage.write("manifest.json", json.encodeToString(SubtitleManifest(complete = true, tracks = saved)))
        }
        Unit
    }

    fun localSubtitles(localVideoUri: String): List<StreamSubtitle> {
        if (!localVideoUri.isDownloadedVideoUri()) return emptyList()
        val storage = DownloadSubtitleStorage(localVideoUri)
        return readManifest(storage).tracks.mapNotNull { track ->
            val uri = storage.localFileUri(track.fileName) ?: return@mapNotNull null
            StreamSubtitle(url = uri, language = track.language, name = track.name)
        }
    }

    suspend fun migrate(legacyStoredFileUri: String, newStoredFileUri: String): Unit = withContext(Dispatchers.Default) {
        if (legacyStoredFileUri == newStoredFileUri) return@withContext
        val source = DownloadSubtitleStorage(legacyStoredFileUri)
        val destination = DownloadSubtitleStorage(newStoredFileUri)
        val manifest = readManifest(source)
        if (manifest.tracks.isEmpty()) {
            source.removeQuietly()
            return@withContext
        }

        val migrated = manifest.tracks.filter { track ->
            val body = source.readTrackOrNull(track.fileName) ?: return@filter false
            destination.writeTrackOrNull(track.fileName, body)
        }
        if (migrated.size != manifest.tracks.size) return@withContext

        runCatching {
            destination.write(
                "manifest.json",
                json.encodeToString(SubtitleManifest(complete = manifest.complete, tracks = migrated)),
            )
            source.remove()
        }
    }

    private fun readManifest(storage: DownloadSubtitleStorage): SubtitleManifest =
        runCatching {
            storage.read("manifest.json")?.let { json.decodeFromString<SubtitleManifest>(it) }
        }.getOrNull() ?: SubtitleManifest()
}

private fun DownloadSubtitleStorage.readTrackOrNull(fileName: String): String? =
    try {
        read(fileName)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

private fun DownloadSubtitleStorage.writeTrackOrNull(fileName: String, text: String): Boolean =
    try {
        write(fileName, text)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

private fun DownloadSubtitleStorage.removeQuietly() {
    try {
        remove()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
    }
}

private fun String.isDownloadedVideoUri(): Boolean =
    startsWith("file:") || startsWith("content:")

@Serializable
private data class SubtitleManifest(
    val complete: Boolean = false,
    val tracks: List<DownloadedSubtitle> = emptyList(),
)

@Serializable
private data class DownloadedSubtitle(
    val sourceUrl: String,
    val fileName: String,
    val language: String,
    val name: String? = null,
)

internal expect class DownloadSubtitleStorage(localVideoUri: String) {
    fun read(fileName: String): String?
    fun write(fileName: String, text: String)
    fun localFileUri(fileName: String): String?
    fun remove()
}
