package com.nuvio.android

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.MainActivity
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import java.io.File
import java.net.URI
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies a completed local fixture download on a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class BackgroundDownloadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun verifyCompletedDownload() {
        val expectedHash = arguments.getString("sha256")
        val expectedBytes = arguments.getString("bytes")?.toLong()
        assumeNotNull(expectedHash, expectedBytes)
        launch().use { scenario ->
            scenario.onActivity { DownloadsRepository.ensureLoaded() }
            val item = DownloadsRepository.uiState.value.items.single { it.videoId == FIXTURE_ID }
            assertEquals(DownloadStatus.Completed, item.status)
            val file = File(URI(requireNotNull(item.localFileUri)))
            assertEquals(expectedBytes, file.length())
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            assertEquals(expectedHash, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(
        Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    companion object {
        private const val FIXTURE_ID = "nuvio-background-download-fixture"
    }
}
