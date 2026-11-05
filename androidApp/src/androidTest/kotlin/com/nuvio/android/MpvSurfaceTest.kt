package com.nuvio.android

import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerSettingsRepository
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MpvSurfaceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun restoresVideoAfterSeekingAndRecreatingTheSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val video = File(instrumentation.targetContext.cacheDir, "mpv-surface-test.mp4")
        instrumentation.context.assets.open("subtitles/video.mp4").use { input ->
            video.outputStream().use(input::copyTo)
        }
        val visible = mutableStateOf(true)
        var controller: PlayerEngineController? = null
        var snapshot = PlayerPlaybackSnapshot()
        var playbackError: String? = null
        PlayerSettingsRepository.ensureLoaded()
        val hardwareDecoding = PlayerSettingsRepository.uiState.value.androidLibmpvHardwareDecodingEnabled
        try {
            PlayerSettingsRepository.setAndroidLibmpvHardwareDecodingEnabled(false)
            compose.setContent {
                if (visible.value) {
                    PlatformPlayerSurface(
                        sourceUrl = Uri.fromFile(video).toString(),
                        modifier = Modifier.fillMaxSize(),
                        playbackEngine = AndroidPlaybackEngine.Libmpv,
                        playWhenReady = false,
                        onControllerReady = { controller = it },
                        onSnapshot = { snapshot = it },
                        onError = { playbackError = it },
                    )
                }
            }
            compose.waitUntil(30_000) {
                compose.runOnIdle {
                    assertNull(playbackError)
                    controller != null && snapshot.durationMs > 0
                }
            }
            awaitVideoFrame()
            compose.runOnIdle {
                controller!!.pause()
                controller!!.seekTo(5_000L)
            }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            awaitVideoFrame()
            compose.runOnIdle { controller!!.play() }
            compose.waitUntil(15_000) { compose.runOnIdle { snapshot.isPlaying && snapshot.positionMs > 5_250L } }
            compose.runOnIdle { visible.value = false }
            awaitVideoFrame(visible = false)
            compose.runOnIdle { visible.value = true }
            awaitVideoFrame()
        } finally {
            compose.runOnIdle { visible.value = false }
            PlayerSettingsRepository.setAndroidLibmpvHardwareDecodingEnabled(hardwareDecoding)
            video.delete()
        }
    }

    private fun awaitVideoFrame(visible: Boolean = true) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(15_000) {
            val frame = automation.takeScreenshot() ?: return@waitUntil false
            try {
                val pixel = frame.getPixel(frame.width / 2, frame.height / 2)
                val matchesVideo = Color.red(pixel) in 95..130 &&
                    Color.green(pixel) in 115..150 &&
                    Color.blue(pixel) in 125..160
                matchesVideo == visible
            } finally {
                frame.recycle()
            }
        }
    }
}
