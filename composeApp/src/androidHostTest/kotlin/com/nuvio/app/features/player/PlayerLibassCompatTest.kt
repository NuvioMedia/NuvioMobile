package com.nuvio.app.features.player

import androidx.media3.exoplayer.ExoPlayer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerLibassCompatTest {
    @Test
    fun canOpenAndCloseWithoutRetainingThePlayerHandler() {
        val context = RuntimeEnvironment.getApplication()
        val player = ExoPlayer.Builder(context).buildWithAssSupportCompat(
            context = context,
        )
        try {
            assertNotNull(player.getAssHandlerCompat())
        } finally {
            player.releaseWithAssSupportCompat()
        }
        assertNull(player.getAssHandlerCompat())
    }

    @Test
    fun standardPlayerStillClosesWithoutAnAssHandler() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        assertNull(player.getAssHandlerCompat())
        player.releaseWithAssSupportCompat()
    }
}
