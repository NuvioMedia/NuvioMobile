package com.nuvio.app.features.audiosync

import com.nuvio.app.features.audiosync.asr.AsrModelFiles
import com.nuvio.app.features.audiosync.asr.format
import com.nuvio.app.features.audiosync.asr.provider
import com.nuvio.app.features.audiosync.asr.saveVerified
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsrModelFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun verifiedTransferPublishesOnlyTheCompleteFile() {
        val directory = temporary.newFolder()
        val bytes = ByteArray(600_000) { (it % 251).toByte() }
        val asset = asset(bytes)
        val progress = ArrayList<Long>()
        AsrModelFiles.saveVerified(ByteArrayInputStream(bytes), directory, asset) {
            if (it < bytes.size) assertFalse(File(directory, asset.name).exists())
            progress += it
        }
        assertContentEquals(bytes, File(directory, asset.name).readBytes())
        assertFalse(File(directory, asset.name + ".part").exists())
        assertEquals(bytes.size.toLong(), progress.last())
    }

    @Test
    fun corruptedSameSizeDownloadCannotReplaceAnExistingModel() {
        val directory = temporary.newFolder()
        val expected = "correct model".toByteArray()
        val asset = asset(expected)
        File(directory, asset.name).writeBytes(expected)
        assertFailsWith<IOException> {
            AsrModelFiles.saveVerified(ByteArrayInputStream(ByteArray(expected.size)), directory, asset)
        }
        assertContentEquals(expected, File(directory, asset.name).readBytes())
        assertFalse(File(directory, asset.name + ".part").exists())
    }

    @Test
    fun rejectsTruncatedAndOversizeDownloads() {
        for (size in listOf(2, 4)) {
            val directory = temporary.newFolder()
            val asset = asset(ByteArray(3))
            assertFailsWith<IOException> {
                AsrModelFiles.saveVerified(ByteArrayInputStream(ByteArray(size)), directory, asset)
            }
            assertFalse(File(directory, asset.name).exists())
            assertFalse(File(directory, asset.name + ".part").exists())
        }
    }

    @Test
    fun failedInputLeavesNoPartialModel() {
        val directory = temporary.newFolder()
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("Connection lost")
        }
        val asset = asset(ByteArray(3))
        assertFailsWith<IOException> { AsrModelFiles.saveVerified(broken, directory, asset) }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun legacyModelRemainsSelectableDuringAPartialOrtUpgrade() {
        val directory = temporary.newFolder()
        sparseFiles(directory, AsrModelFiles.legacy)
        sparseFiles(directory, AsrModelFiles.optimized.take(1))
        assertEquals(AsrModelFiles.Format.ONNX, AsrModelFiles.format(directory))
        assertEquals("cpu", AsrModelFiles.provider(directory, AsrModelFiles.Format.ONNX))
        assertFalse(File(directory, "cpu.conf").exists())
    }

    @Test
    fun incompleteMixedFormatsAreNeverPassedToTheNativeRuntime() {
        val directory = temporary.newFolder()
        sparseFiles(directory, AsrModelFiles.legacy.drop(1))
        sparseFiles(directory, AsrModelFiles.optimized.take(1))
        assertFailsWith<IOException> { AsrModelFiles.format(directory) }
    }

    @Test
    fun completeOrtModelUsesTheCpuWithoutRepeatingGraphOptimization() {
        val directory = temporary.newFolder()
        sparseFiles(directory, AsrModelFiles.optimized)
        assertEquals(AsrModelFiles.Format.ORT, AsrModelFiles.format(directory))
        assertEquals("cpu:${File(directory, "cpu.conf").absolutePath}",
            AsrModelFiles.provider(directory, AsrModelFiles.Format.ORT))
        assertEquals(
            "GraphOptimizationLevel=0\n" +
                "SessionConfig.session.use_memory_mapped_ort_model=1\n" +
                "SessionConfig.session.use_ort_model_bytes_for_initializers=1\n",
            File(directory, "cpu.conf").readText(),
        )
    }

    @Test
    fun lowMemoryTvsTurnTheMemoryPoolOffForEitherFormat() {
        val ort = temporary.newFolder()
        sparseFiles(ort, AsrModelFiles.optimized)
        AsrModelFiles.provider(ort, AsrModelFiles.Format.ORT, lowMemory = true)
        assertEquals(
            "GraphOptimizationLevel=0\n" +
                "SessionConfig.session.use_memory_mapped_ort_model=1\n" +
                "SessionConfig.session.use_ort_model_bytes_for_initializers=1\n" +
                "EnableCpuMemArena=0\n",
            File(ort, "cpu.conf").readText(),
        )
        // The ONNX fallback keeps the runtime's graph optimization; only the pool changes.
        val onnx = temporary.newFolder()
        sparseFiles(onnx, AsrModelFiles.legacy)
        assertEquals("cpu:${File(onnx, "cpu.conf").absolutePath}",
            AsrModelFiles.provider(onnx, AsrModelFiles.Format.ONNX, lowMemory = true))
        assertEquals("EnableCpuMemArena=0\n", File(onnx, "cpu.conf").readText())
    }

    @Test
    fun aSettingsChangeRewritesAnEarlierConfig() {
        val directory = temporary.newFolder()
        sparseFiles(directory, AsrModelFiles.optimized)
        AsrModelFiles.provider(directory, AsrModelFiles.Format.ORT, lowMemory = true)
        AsrModelFiles.provider(directory, AsrModelFiles.Format.ORT)
        assertFalse(File(directory, "cpu.conf").readText().contains("EnableCpuMemArena"))
    }

    private fun sparseFiles(directory: File, assets: List<AsrModelFiles.Asset>) {
        assets.forEach { asset ->
            RandomAccessFile(File(directory, asset.name), "rw").use { it.setLength(asset.size) }
        }
    }

    private fun asset(bytes: ByteArray) = AsrModelFiles.Asset(
        "model.ort", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
    )
}
