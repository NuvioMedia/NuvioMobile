package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeroTrailerFullscreenTest {
    @Test
    fun `opens fullscreen only when the pull is far enough and the trailer is ready`() {
        assertTrue(shouldOpenHeroTrailerFullscreen(stretchPx = 110f, maxStretchPx = 200f, trailerReady = true))
        assertTrue(shouldOpenHeroTrailerFullscreen(stretchPx = 200f, maxStretchPx = 200f, trailerReady = true))
    }

    @Test
    fun `ignores a light rubber-band pull`() {
        assertFalse(shouldOpenHeroTrailerFullscreen(stretchPx = 40f, maxStretchPx = 200f, trailerReady = true))
        assertFalse(shouldOpenHeroTrailerFullscreen(stretchPx = 109f, maxStretchPx = 200f, trailerReady = true))
    }

    @Test
    fun `does not open fullscreen when the backdrop trailer is not playing`() {
        assertFalse(shouldOpenHeroTrailerFullscreen(stretchPx = 200f, maxStretchPx = 200f, trailerReady = false))
        assertFalse(shouldOpenHeroTrailerFullscreen(stretchPx = 200f, maxStretchPx = 0f, trailerReady = true))
    }

    @Test
    fun `keeps the current stretch until the expand animation starts`() {
        assertEquals(
            80f,
            heroTrailerFullscreenExtraPx(progress = 0f, stretchPx = 80f, startExtraPx = 80f, fullExtraPx = 400f),
        )
    }

    @Test
    fun `interpolates from the pulled hero height to the full viewport`() {
        assertEquals(
            240f,
            heroTrailerFullscreenExtraPx(progress = 0.5f, stretchPx = 80f, startExtraPx = 80f, fullExtraPx = 400f),
        )
        assertEquals(
            400f,
            heroTrailerFullscreenExtraPx(progress = 1f, stretchPx = 80f, startExtraPx = 80f, fullExtraPx = 400f),
        )
    }

    @Test
    fun `locks phones to the short side instead of growing to the portrait viewport`() {
        assertEquals(
            -520f,
            heroTrailerFullscreenTargetExtraPx(
                viewportWidthPx = 1080,
                viewportHeightPx = 2400,
                heroHeightPx = 1600,
                lockToLandscape = true,
            ),
        )
        assertEquals(
            -520f,
            heroTrailerFullscreenTargetExtraPx(
                viewportWidthPx = 2400,
                viewportHeightPx = 1080,
                heroHeightPx = 1600,
                lockToLandscape = true,
            ),
        )
    }

    @Test
    fun `fills the current viewport when landscape lock is not used`() {
        assertEquals(
            800f,
            heroTrailerFullscreenTargetExtraPx(
                viewportWidthPx = 1600,
                viewportHeightPx = 2560,
                heroHeightPx = 1760,
                lockToLandscape = false,
            ),
        )
    }

    @Test
    fun `shrinks the hero when landscape is shorter than the portrait backdrop`() {
        assertEquals(
            -120f,
            heroTrailerFullscreenExtraPx(progress = 1f, stretchPx = 80f, startExtraPx = 80f, fullExtraPx = -120f),
        )
    }

    @Test
    fun `chrome stays visible at the start of the expand then fades out`() {
        assertEquals(1f, heroTrailerFullscreenChromeAlpha(0f))
        assertTrue(heroTrailerFullscreenChromeAlpha(0.3f) > 0.7f)
        assertEquals(0f, heroTrailerFullscreenChromeAlpha(1f))
    }

    @Test
    fun `collapses back to the hero height`() {
        assertEquals(
            200f,
            heroTrailerFullscreenExtraPx(progress = 0.5f, stretchPx = 0f, startExtraPx = 0f, fullExtraPx = 400f),
        )
        assertEquals(
            0f,
            heroTrailerFullscreenExtraPx(progress = 0f, stretchPx = 0f, startExtraPx = 0f, fullExtraPx = 400f),
        )
    }
}
