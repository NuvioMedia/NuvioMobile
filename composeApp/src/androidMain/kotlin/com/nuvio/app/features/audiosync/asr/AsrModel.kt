package com.nuvio.app.features.audiosync.asr

import android.content.Context
import android.net.ConnectivityManager
import com.nuvio.app.features.audiosync.SpeechModelActions
import com.nuvio.app.features.audiosync.SpeechModelState
import com.nuvio.app.features.audiosync.SubtitleSyncStatus
import com.nuvio.app.features.audiosync.SyncLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The English recognition model (about 74 MB), kept in app storage. It is only ever downloaded
 * when the user selects Download in Settings; playback never fetches it.
 * Progress and errors are published to [SubtitleSyncStatus.speechModel].
 *
 * A pre-optimized ORT conversion, which loads faster, comes from Nuvio's mirror at [ORT_DOWNLOAD].
 * When it cannot be fetched, the original ONNX files come from the upstream k2-fsa repository,
 * pinned to a commit. Every file of either format is pinned by size and SHA-256 in
 * [AsrModelFiles], so the host does not have to be trusted.
 */
object AsrModel {
    const val TOKENS = "tokens.txt"
    const val DOWNLOAD_MB = 74

    private const val ONNX_VERSION = "gigaspeech-2023-12-12"
    private const val ORT_VERSION = "$ONNX_VERSION-ort-v1"

    /**
     * Where the ORT conversion of the upstream files below is hosted, or null to use the upstream
     * ONNX files only. Should only ever point at a Nuvio-controlled release; the files are pinned
     * in [AsrModelFiles.optimized] either way.
     */
    private val ORT_DOWNLOAD: String? =
        "https://github.com/NuvioMedia/speech-models/releases/download/$ORT_VERSION/"

    /** The upstream model repository, pinned to a commit so its files cannot change under us. */
    private const val ONNX_DOWNLOAD =
        "https://huggingface.co/k2-fsa/sherpa-onnx-zipformer-gigaspeech-2023-12-12/resolve/f7515a672ffb69232794aaaa44a0a360f7f25cd1/"

    /** Upstream names of [AsrModelFiles.legacy], by local name. */
    private val ONNX_UPSTREAM_NAMES = mapOf(
        "encoder.int8.onnx" to "encoder-epoch-30-avg-1.int8.onnx",
        "decoder.int8.onnx" to "decoder-epoch-30-avg-1.int8.onnx",
        "joiner.int8.onnx" to "joiner-epoch-30-avg-1.int8.onnx",
        TOKENS to TOKENS,
    )

    private val downloading = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    /** Registers the Settings actions and publishes the current state. Call once at app start. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        SubtitleSyncStatus.modelActions = object : SpeechModelActions {
            override fun download() {
                val ctx = appContext ?: return
                Thread({ download(ctx) }, "NuvioAsrModelDownload").apply { isDaemon = true }.start()
            }

            override fun delete() {
                val ctx = appContext ?: return
                if (downloading.get()) return
                ortDirectory(ctx).deleteRecursively()
                onnxDirectory(ctx).deleteRecursively()
                publish(ctx)
            }
        }
        publish(context)
    }

    private fun ortDirectory(context: Context): File = File(context.filesDir, "audiosync/asr/$ORT_VERSION")

    private fun onnxDirectory(context: Context): File = File(context.filesDir, "audiosync/asr/$ONNX_VERSION")

    /** The complete model to load: the ORT one when present, otherwise the ONNX one. */
    fun directory(context: Context): File {
        val ort = ortDirectory(context)
        if (AsrModelFiles.isComplete(ort, AsrModelFiles.optimized)) return ort
        return onnxDirectory(context).takeIf { AsrModelFiles.isComplete(it, AsrModelFiles.legacy) } ?: ort
    }

    fun isReady(context: Context): Boolean =
        AsrModelFiles.isComplete(ortDirectory(context), AsrModelFiles.optimized) ||
            AsrModelFiles.isComplete(onnxDirectory(context), AsrModelFiles.legacy)

    fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return !manager.isActiveNetworkMetered
    }

    private fun publish(context: Context, progress: Float = 0f, error: String? = null) {
        SubtitleSyncStatus.publishSpeechModel(
            SpeechModelState(
                supported = true,
                sizeMb = DOWNLOAD_MB,
                downloaded = isReady(context),
                downloading = downloading.get(),
                progress = progress,
                error = error,
            ),
        )
    }

    /**
     * Downloads missing files. Blocking; call from a background thread. Returns true when the model
     * is ready afterwards.
     */
    @Synchronized
    fun download(context: Context): Boolean {
        if (isReady(context)) {
            publish(context)
            return true
        }
        if (!downloading.compareAndSet(false, true)) return false
        var error: String? = null
        try {
            publish(context)
            val ortHost = ORT_DOWNLOAD
            val ortFailure = if (ortHost != null) {
                downloadAll(context, ortDirectory(context), AsrModelFiles.optimized) { ortHost + it }
                    ?.also { SyncLog.w("optimized speech model unavailable ($it); downloading the upstream ONNX model") }
                    ?: return true
            } else {
                null
            }
            val onnxFailure = downloadAll(context, onnxDirectory(context), AsrModelFiles.legacy) {
                ONNX_DOWNLOAD + ONNX_UPSTREAM_NAMES.getValue(it)
            } ?: return true
            error = listOfNotNull(ortFailure, onnxFailure).joinToString("; ")
            return false
        } finally {
            downloading.set(false)
            publish(context, error = error)
        }
    }

    /** Downloads the missing [assets] into [directory]. Returns null on success, otherwise a short reason. */
    private fun downloadAll(
        context: Context,
        directory: File,
        assets: List<AsrModelFiles.Asset>,
        url: (name: String) -> String,
    ): String? {
        if (!directory.isDirectory && !directory.mkdirs()) return "could not create model directory"
        val totalBytes = assets.sumOf { it.size }
        var doneBytes = 0L
        for (asset in assets) {
            val file = File(directory, asset.name)
            if (!file.isFile || file.length() != asset.size) {
                val onBytes: (Long) -> Unit = { bytes ->
                    publish(context, progress = (doneBytes + bytes).toFloat() / totalBytes)
                }
                fetch(url(asset.name), directory, asset, onBytes)?.let { return it }
            }
            doneBytes += asset.size
        }
        return if (AsrModelFiles.isComplete(directory, assets)) null else "downloaded files are incomplete"
    }

    /** Returns null on success, otherwise a short reason. */
    private fun fetch(url: String, directory: File, asset: AsrModelFiles.Asset, onBytes: (Long) -> Unit): String? {
        var connection: HttpURLConnection? = null
        return try {
            var location = URL(url)
            var redirects = 0
            // HttpURLConnection does not follow cross-host redirects (GitHub release or Hugging Face -> CDN).
            while (true) {
                val current = (location.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                connection = current
                if (current.responseCode !in 300..399) break
                if (++redirects > 5) return "too many redirects"
                val next = current.getHeaderField("Location") ?: return "missing redirect location"
                location = URL(location, next)
                current.disconnect()
            }
            val response = checkNotNull(connection)
            if (response.responseCode != 200) {
                val code = response.responseCode
                SyncLog.w("model download HTTP $code for $url")
                return "HTTP $code"
            }
            // Size and SHA-256 are verified on the .part file before it is moved into place.
            response.inputStream.use { input ->
                AsrModelFiles.saveVerified(input, directory, asset, onBytes)
            }
            null
        } catch (failure: Exception) {
            SyncLog.w("model download failed for $url: ${failure.message}")
            failure.message ?: failure.javaClass.simpleName
        } finally {
            connection?.disconnect()
        }
    }
}
