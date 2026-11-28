package com.nuvio.android

import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.features.audiosync.asr.AsrModel
import com.nuvio.app.features.audiosync.asr.AsrModelFiles
import com.nuvio.app.features.audiosync.asr.SherpaSpeechToText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run on each supported Android ABI after downloading the ORT model from Settings. */
class AsrOrtCompatibilityTest {
    @Test
    fun installedOrtModelLoadsAndRunsThroughTheActualAndroidJniLibrary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Download the ORT speech model from Settings first", AsrModel.isReady(context))
        val recognizer = SherpaSpeechToText(AsrModel.directory(context), threads = 2)
        try {
            assertEquals(AsrModelFiles.Format.ORT, recognizer.modelFormat)
            // Exercise feature extraction, encoder, decoder and joiner kernels, not just parsing.
            val words = recognizer.transcribe(FloatArray(16_000))
            assertTrue(words.all { (time, _) -> time.isFinite() && time >= 0.0 })
        } finally {
            recognizer.close()
        }
    }
}
