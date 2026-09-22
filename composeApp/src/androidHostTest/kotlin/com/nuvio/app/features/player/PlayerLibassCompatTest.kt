package com.nuvio.app.features.player

import androidx.media3.exoplayer.ExoPlayer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerLibassCompatTest {
    @Test
    fun everySubtitleModeCanOpenAndCloseWithoutRetainingThePlayerHandler() {
        val context = RuntimeEnvironment.getApplication()
        LibassRenderType.entries.forEach { mode ->
            val player = ExoPlayer.Builder(context).buildWithAssSupportCompat(
                context = context,
                renderType = mode.toAssRenderType(),
            )
            try {
                val handler = assertNotNull(player.getAssHandlerCompat())
                assertEquals(mode.toAssRenderType(), handler.renderType)
                assertEquals(0, handler.config.maxRenderPixels)
            } finally {
                player.releaseWithAssSupportCompat()
            }
            assertNull(player.getAssHandlerCompat())
        }
    }

    @Test
    fun standardPlayerStillClosesWithoutAnAssHandler() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        assertNull(player.getAssHandlerCompat())
        player.releaseWithAssSupportCompat()
    }
}
