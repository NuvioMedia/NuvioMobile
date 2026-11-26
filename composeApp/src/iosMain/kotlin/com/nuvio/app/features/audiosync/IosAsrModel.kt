package com.nuvio.app.features.audiosync

import com.nuvio.app.features.audiosync.asr.AsrModelFiles
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

internal object IosAsrModel {
    class RecognizerFiles(
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
        val provider: String,
    )

    private val downloading = atomic(false)

    @OptIn(ExperimentalForeignApi::class)
    fun initialize() {
        SubtitleSyncStatus.modelActions = object : SpeechModelActions {
            override fun download() {
                startAudioSyncThread("NuvioAsrModelDownload") { downloadModel() }
            }

            override fun delete() {
                if (downloading.value) return
                val fileManager = NSFileManager.defaultManager
                ortDirectory()?.let { fileManager.removeItemAtPath(it, null) }
                onnxDirectory()?.let { fileManager.removeItemAtPath(it, null) }
                publish()
            }
        }
        publish()
    }

    fun isReady(): Boolean = readyDirectory() != null

    fun recognizerFiles(): RecognizerFiles? {
        val (directory, format) = readyDirectory() ?: return null
        return RecognizerFiles(
            encoder = "$directory/encoder.int8.${format.extension}",
            decoder = "$directory/decoder.int8.${format.extension}",
            joiner = "$directory/joiner.int8.${format.extension}",
            tokens = "$directory/${AsrModelFiles.TOKENS}",
            provider = provider(directory, format),
        )
    }

    private fun baseDirectory(): String? {
        val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return null
        return "$support/audiosync/asr"
    }

    private fun ortDirectory(): String? = baseDirectory()?.let { "$it/${AsrModelFiles.ORT_VERSION}" }

    private fun onnxDirectory(): String? = baseDirectory()?.let { "$it/${AsrModelFiles.ONNX_VERSION}" }

    private fun readyDirectory(): Pair<String, AsrModelFiles.Format>? {
        ortDirectory()?.takeIf { isComplete(it, AsrModelFiles.optimized) }?.let { return it to AsrModelFiles.Format.ORT }
        onnxDirectory()?.takeIf { isComplete(it, AsrModelFiles.legacy) }?.let { return it to AsrModelFiles.Format.ONNX }
        return null
    }

    private fun isComplete(directory: String, assets: List<AsrModelFiles.Asset>): Boolean =
        assets.all { asset -> fileSize("$directory/${asset.name}") == asset.size }

    @OptIn(ExperimentalForeignApi::class)
    private fun fileSize(path: String): Long? {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return null
        return (attributes[NSFileSize] as? NSNumber)?.longLongValue
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun provider(directory: String, format: AsrModelFiles.Format): String {
        val settings = AsrModelFiles.providerSettings(format, lowMemory = false)
        if (settings.isEmpty()) return "cpu"
        val path = "$directory/cpu.conf"
        val text = settings.joinToString(separator = "\n", postfix = "\n")
        val current = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
        if (current != text) NSString.create(string = text).writeToFile(path, true, NSUTF8StringEncoding, null)
        return "cpu:$path"
    }

    private fun publish(progress: Float = 0f, error: String? = null) {
        SubtitleSyncStatus.publishSpeechModel(
            SpeechModelState(
                supported = AudioSyncNativeRegistry.bridge != null,
                sizeMb = AsrModelFiles.DOWNLOAD_MB,
                downloaded = isReady(),
                downloading = downloading.value,
                progress = progress,
                error = error,
            ),
        )
    }

    private fun downloadModel() {
        if (isReady()) {
            publish()
            return
        }
        val bridge = AudioSyncNativeRegistry.bridge ?: return publish(error = "unsupported")
        if (!downloading.compareAndSet(expect = false, update = true)) return
        var error: String? = null
        try {
            publish()
            val ortHost = AsrModelFiles.ORT_DOWNLOAD
            val ortFailure = if (ortHost != null) {
                val directory = ortDirectory() ?: return
                downloadAll(bridge, directory, AsrModelFiles.optimized) { ortHost + it } ?: return
            } else {
                null
            }
            val directory = onnxDirectory() ?: return
            error = downloadAll(bridge, directory, AsrModelFiles.legacy) {
                AsrModelFiles.ONNX_DOWNLOAD + AsrModelFiles.ONNX_UPSTREAM_NAMES.getValue(it)
            }?.let { onnxFailure -> ortFailure?.let { "$it; $onnxFailure" } ?: onnxFailure }
        } finally {
            downloading.value = false
            publish(error = error)
        }
    }

    private fun downloadAll(
        bridge: AudioSyncNativeBridge,
        directory: String,
        assets: List<AsrModelFiles.Asset>,
        url: (String) -> String,
    ): String? {
        val total = assets.sumOf { it.size }.coerceAtLeast(1L)
        var done = 0L
        for (asset in assets) {
            val path = "$directory/${asset.name}"
            if (fileSize(path) == asset.size) {
                done += asset.size
                continue
            }
            val base = done
            val failure = bridge.downloadFile(
                url = url(asset.name),
                destinationPath = path,
                expectedSize = asset.size,
                sha256 = asset.sha256,
                progress = object : AudioSyncNativeDownloadProgress {
                    override fun onBytes(written: Long) {
                        publish(progress = ((base + written).toFloat() / total).coerceIn(0f, 1f))
                    }
                },
            )
            if (failure != null) return failure
            done += asset.size
        }
        return null
    }
}
