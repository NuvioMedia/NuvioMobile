package com.nuvio.app.features.profiles

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileBackgroundUrlTest {
    @Test
    fun acceptsHttpAndHttpsImageAndGifUrls() {
        assertTrue("https://cdn.example.com/background.jpg".isValidAvatarUrl())
        assertTrue("https://cdn.example.com/background.gif".isValidAvatarUrl())
        assertTrue("http://cdn.example.com/background.webp".isValidAvatarUrl())
    }

    @Test
    fun rejectsNonHttpAndMalformedUrls() {
        assertFalse("file:///tmp/background.jpg".isValidAvatarUrl())
        assertFalse("javascript:alert(1)".isValidAvatarUrl())
        assertFalse("https://example.com/background image.gif".isValidAvatarUrl())
        assertFalse("".isValidAvatarUrl())
    }
}
