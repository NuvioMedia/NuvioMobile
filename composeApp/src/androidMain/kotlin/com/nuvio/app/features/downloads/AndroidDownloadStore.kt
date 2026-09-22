package com.nuvio.app.features.downloads

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class AndroidDownloadTransfer(
    val item: DownloadItem,
    val jobId: Int,
    val generation: String,
    val validator: String? = null,
    val retryCount: Int = 0,
)

internal class AndroidDownloadStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val state = MutableStateFlow(load())
    val transfers = state.asStateFlow()

    fun get(fileName: String): AndroidDownloadTransfer? = state.value[fileName]

    fun begin(item: DownloadItem): AndroidDownloadTransfer = synchronized(lock) {
        val previous = get(item.fileName)?.takeIf { it.item.id == item.id }
        if (previous?.item?.status == DownloadStatus.Downloading) return@synchronized previous
        val transfer = AndroidDownloadTransfer(
            item = item.copy(status = DownloadStatus.Downloading, errorMessage = null, localFileUri = null),
            jobId = previous?.jobId ?: ((state.value.values.maxOfOrNull { it.jobId } ?: 0) + 1),
            generation = UUID.randomUUID().toString(),
            validator = previous?.validator,
        )
        save(transfer)
        transfer
    }

    fun update(
        fileName: String,
        generation: String,
        transform: (AndroidDownloadTransfer) -> AndroidDownloadTransfer,
    ): AndroidDownloadTransfer? = synchronized(lock) {
        val current = get(fileName)?.takeIf { it.generation == generation } ?: return@synchronized null
        transform(current).also(::save)
    }

    fun remove(fileName: String) = synchronized(lock) {
        AtomicFile(recordFile(fileName)).delete()
        state.value = state.value - fileName
    }

    private fun save(transfer: AndroidDownloadTransfer) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create download state directory" }
        val file = AtomicFile(recordFile(transfer.item.fileName))
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(transfer).toByteArray())
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
        state.value = state.value + (transfer.item.fileName to transfer)
    }

    private fun load(): Map<String, AndroidDownloadTransfer> = directory.listFiles().orEmpty()
        .filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
        .map { File(it.path.removeSuffix(".bak")) }
        .distinct()
        .mapNotNull { file ->
            runCatching {
                json.decodeFromString<AndroidDownloadTransfer>(AtomicFile(file).readFully().decodeToString())
            }.getOrNull()
        }.associateBy { it.item.fileName }

    private fun recordFile(fileName: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(fileName.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.json")
    }
}
