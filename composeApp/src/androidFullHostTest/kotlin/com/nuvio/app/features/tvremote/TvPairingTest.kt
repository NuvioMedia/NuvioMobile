package com.nuvio.app.features.tvremote

import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvPairingTest {
    private fun code(expiry: Long = 100_001, host: String = "192.168.1.2", pin: String = "a".repeat(64)) =
        Uri.Builder().scheme("nuvio-tv").authority("pair").appendQueryParameter("v", "1")
            .appendQueryParameter("id", "tv-device-123456789").appendQueryParameter("name", "Living room")
            .appendQueryParameter("host", host).appendQueryParameter("port", "8443")
            .appendQueryParameter("pin", pin).appendQueryParameter("secret", "b".repeat(43))
            .appendQueryParameter("expires", expiry.toString()).build().toString()
    @Test fun validCodeCarriesIdentityPinAndOneTimeSecret() {
        val result = PairingCode.parse(code(), now = 1)
        assertEquals("tv-device-123456789", result.tv.deviceId)
        assertEquals("a".repeat(64), result.tv.pin)
        assertEquals("b".repeat(43), result.secret)
        assertEquals("", result.tv.credential)
    }
    @Test fun expiredCodeAndPublicOrDnsAddressesAreRejected() {
        assertFailsWith<IllegalArgumentException> { PairingCode.parse(code(expiry = 1), now = 1) }
        for (host in listOf("example.com", "8.8.8.8", "127.0.0.1")) {
            assertFailsWith<IllegalArgumentException> { PairingCode.parse(code(host = host), now = 1) }
        }
        assertFailsWith<IllegalArgumentException> { PairingCode.parse(code(pin = "bad"), now = 1) }
    }
    @Test fun metadataProjectionContainsNoSourceOrCredentials() {
        val snapshot = RemoteSnapshot(deviceId = "tv", deviceName = "TV", sessionId = "episode",
            title = "Series", episodeTitle = "Pilot", season = 1, episode = 2, state = "buffering")
        val json = remoteJson.encodeToString(RemoteSnapshot.serializer(), snapshot)
        assertEquals("S1 · E2 · Pilot", snapshot.subtitle())
        assertFalse(json.contains("stream", ignoreCase = true))
        assertFalse(json.contains("credential", ignoreCase = true))
        assertEquals(snapshot, remoteJson.decodeFromString<RemoteSnapshot>(json))
    }
}
