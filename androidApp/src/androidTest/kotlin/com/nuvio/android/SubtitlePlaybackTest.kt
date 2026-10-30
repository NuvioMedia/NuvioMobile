package com.nuvio.android

import android.graphics.Bitmap
import android.net.Uri
import android.app.UiAutomation
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.SubtitleAppearance
import com.nuvio.app.features.player.SubtitleStyleState
import com.nuvio.app.features.streams.StreamSubtitle
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import java.io.File
import java.util.UUID
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class SubtitlePlaybackTest {
    @get:Rule(order = 0) val orientation = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(Surface.ROTATION_90)
        }

        override fun after() {
            InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun exoPlayerStylesAndClearsTimedCaptions() = exercise(AndroidPlaybackEngine.ExoPlayer)
    @Test fun libmpvStylesAndClearsTimedCaptions() = exercise(AndroidPlaybackEngine.Libmpv)

    private fun exercise(engine: AndroidPlaybackEngine) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "subtitle-test").apply { mkdirs() }
        val fontFile = File(instrumentation.targetContext.filesDir, "subtitle-fonts/custom-${UUID.randomUUID()}")
        fontFile.parentFile!!.mkdirs()
        instrumentation.targetContext.assets.open("composeResources/nuvio.composeapp.generated.resources/font/jetbrains_sans_regular.ttf").use { input ->
            fontFile.outputStream().use(input::copyTo)
        }
        try {
            listOf("video.mp4", "captions.srt", "styled.ass").forEach { name ->
                instrumentation.context.assets.open("subtitles/$name").use { input ->
                    File(directory, name).outputStream().use(input::copyTo)
                }
            }
            var controller: PlayerEngineController? = null
            var snapshot = PlayerPlaybackSnapshot()
            var error: String? = null
            val style = SubtitleStyleState(
                fontSizeSp = 28,
                backgroundColor = Color.Black.copy(alpha = 0.65f),
                appearance = SubtitleAppearance("serif", 16, true, 80, 3),
            )
            compose.setContent {
                NuvioTheme {
                    PlatformPlayerSurface(
                        sourceUrl = Uri.fromFile(File(directory, "video.mp4")).toString(),
                        externalSubtitles = listOf(
                            StreamSubtitle(Uri.fromFile(File(directory, "captions.srt")).toString(), "en", "Captions"),
                            StreamSubtitle(Uri.fromFile(File(directory, "styled.ass")).toString(), "en", "Styled"),
                        ),
                        modifier = Modifier.fillMaxSize().background(Color.Black),
                        playbackEngine = engine,
                        playWhenReady = false,
                        onControllerReady = { controller = it; it.applySubtitleStyle(style) },
                        onSnapshot = { snapshot = it },
                        onError = { error = it },
                    )
                }
            }
            compose.waitUntil(30_000) {
                compose.runOnIdle { snapshot.durationMs > 15_000 && controller?.getSubtitleTracks()?.size == 2 }
            }
            compose.runOnIdle {
                assertNull(error)
                controller!!.selectSubtitleTrack(controller!!.getSubtitleTracks().first().index)
                controller!!.seekTo(3_000)
                controller!!.play()
            }
            awaitText("First caption")
            compose.waitUntil(10_000) { compose.runOnIdle { !snapshot.isLoading } }
            compose.waitUntil(10_000) { compose.runOnIdle { snapshot.positionMs >= 3_400 } }
            compose.runOnIdle { controller!!.pause() }
            compose.onNodeWithText("First caption").assertIsDisplayed()
            screenshot("${engine.name}-rounded.png")
            compose.runOnIdle { controller!!.applySubtitleStyle(style.copy(outlineEnabled = false, appearance = style.appearance.copy(fontId = fontFile.name))) }
            screenshot("${engine.name}-custom-font.png")
            compose.runOnIdle { controller!!.applySubtitleStyle(style.copy(appearance = style.appearance.copy(fontId = "mono", backgroundRadius = 0))) }
            compose.onNodeWithText("First caption").assertIsDisplayed()
            screenshot("${engine.name}-square.png")
            compose.runOnIdle { controller!!.seekTo(6_000); controller!!.play() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("First caption").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle { controller!!.seekTo(9_000) }
            awaitText("A longer line that wraps with larger fonts.\nSecond line with café and 日本語.")
            screenshot("${engine.name}-multiline.png")
            compose.runOnIdle { controller!!.seekTo(13_500); controller!!.play() }
            awaitText("مرحبا بالعالم")
            compose.runOnIdle { controller!!.pause() }
            screenshot("${engine.name}-rtl.png")
            compose.runOnIdle { controller!!.selectSubtitleTrack(-1) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("مرحبا بالعالم", substring = true).fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle {
                controller!!.selectSubtitleTrack(controller!!.getSubtitleTracks().first().index)
                controller!!.seekTo(3_000)
                controller!!.play()
            }
            awaitText("First caption")
            compose.runOnIdle { controller!!.pause() }
            compose.runOnIdle { controller!!.applySubtitleStyle(SubtitleStyleState.DEFAULT) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("First caption").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle {
                controller!!.applySubtitleStyle(style)
                controller!!.selectSubtitleTrack(controller!!.getSubtitleTracks().first { it.label.contains("Styled") }.index)
                controller!!.seekTo(3_000)
                controller!!.play()
            }
            compose.waitUntil(10_000) { compose.runOnIdle { snapshot.positionMs in 3_500..7_000 } }
            compose.runOnIdle { controller!!.pause() }
            compose.onNodeWithText("Styled caption at the top", substring = true).assertDoesNotExist()
            screenshot("${engine.name}-ass.png")
            withSubtitleServer(File(directory, "captions.srt").readBytes()) { url ->
                compose.runOnIdle {
                    controller!!.setSubtitleUri(url)
                    controller!!.seekTo(3_000)
                    controller!!.play()
                }
                awaitText("First caption")
                compose.runOnIdle { controller!!.pause() }
                screenshot("${engine.name}-addon.png")
                compose.runOnIdle { controller!!.setSubtitleDelayMs(5_000) }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("First caption").fetchSemanticsNodes().isEmpty() }
                compose.runOnIdle { controller!!.setSubtitleDelayMs(0) }
                awaitText("First caption")
                compose.runOnIdle { controller!!.clearExternalSubtitle() }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("First caption").fetchSemanticsNodes().isEmpty() }
            }
            compose.runOnIdle { assertNull(error) }
        } finally {
            fontFile.delete()
        }
    }

    private fun awaitText(text: String) {
        try {
            compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            screenshot("failure.png")
            throw AssertionError("Expected $text\n${compose.onRoot(useUnmergedTree = true).printToString()}", error)
        }
    }

    private fun withSubtitleServer(body: ByteArray, block: (String) -> Unit) {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                runCatching {
                    while (!server.isClosed) server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/x-subrip\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body)
                            flush()
                        }
                    }
                }
            }
            block("http://127.0.0.1:${server.localPort}/captions.srt")
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(250)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "subtitle-tests").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
