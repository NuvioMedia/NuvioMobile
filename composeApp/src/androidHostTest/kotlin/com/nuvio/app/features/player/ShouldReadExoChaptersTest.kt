package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShouldReadExoChaptersTest {

    @Test
    fun chaptersAreReadForTheChapterUiOrForSkipIntro() {
        assertTrue(shouldReadExoChapters(chaptersEnabled = true, skipIntroEnabled = false, containerMimeType = "video/x-matroska"))
        assertTrue(shouldReadExoChapters(chaptersEnabled = false, skipIntroEnabled = true, containerMimeType = "video/x-matroska"))
        assertFalse(shouldReadExoChapters(chaptersEnabled = false, skipIntroEnabled = false, containerMimeType = "video/x-matroska"))
    }

    @Test
    fun onlyMatroskaAndWebmOrAnUnknownContainerAreRead() {
        assertTrue(shouldReadExoChapters(chaptersEnabled = true, skipIntroEnabled = true, containerMimeType = "video/webm"))
        assertTrue(shouldReadExoChapters(chaptersEnabled = true, skipIntroEnabled = true, containerMimeType = null))
        assertFalse(shouldReadExoChapters(chaptersEnabled = true, skipIntroEnabled = true, containerMimeType = "video/mp4"))
        assertFalse(shouldReadExoChapters(chaptersEnabled = true, skipIntroEnabled = true, containerMimeType = "video/mp2t"))
    }
}
