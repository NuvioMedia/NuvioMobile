package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class ClearKeyDrmSessionManagerTest {

    @Test
    fun `the license is a W3C JSON Web Key set in unpadded base64url`() {
        val kid = ByteArray(16) { (it * 0x11).toByte() }
        val key = ByteArray(16) { (0xff - it * 0x11).toByte() }
        assertEquals(
            "{\"keys\":[{\"kty\":\"oct\",\"kid\":\"ABEiM0RVZneImaq7zN3u_w\",\"k\":\"_-7dzLuqmYh3ZlVEMyIRAA\"}]," +
                "\"type\":\"temporary\"}",
            ClearKeyDrmSessionManager.licenseResponse(listOf(kid to key)),
        )
    }
}
