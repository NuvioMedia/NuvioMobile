package com.nuvio.app.features.servers.plex

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlexArtworkTest {
    private val link = PlexArtworkLink.picture("http://192.168.1.20:32400", "c1", "/library/metadata/1/thumb/2", 400, 600)!!

    @Test
    fun linksNameTheConnectionButCarryNoToken() {
        assertTrue(link.startsWith("http://192.168.1.20:32400/photo/:/transcode?width=400&height=600"))
        assertTrue(link.endsWith("#nuvio-plex=c1"))
        assertEquals("c1", PlexArtworkLink.connectionId(link))
        assertEquals(
            "https://image.tmdb.org/a.jpg",
            PlexArtworkLink.picture("https://media.example", "c1", "https://image.tmdb.org/a.jpg", 1, 1),
        )
        assertNull(PlexArtworkLink.picture("https://media.example", "c1", "relative/path", 1, 1))
        assertNull(PlexArtworkLink.connectionId("https://image.tmdb.org/a.jpg"))
    }

    @Test
    fun signingAddsTheTokenAndDropsTheMark() {
        val signed = Url(PlexArtworkLink.signed(link, "http://192.168.1.20:32400", "server-token")!!)

        assertEquals("server-token", signed.parameters["X-Plex-Token"])
        assertEquals("/library/metadata/1/thumb/2", signed.parameters["url"])
        assertEquals("", signed.fragment)
    }

    @Test
    fun followsTheServerToItsCurrentAddress() {
        val signed = Url(PlexArtworkLink.signed(link, "https://203-0-113-5.abc.plex.direct:32400", "server-token")!!)

        assertEquals("203-0-113-5.abc.plex.direct", signed.host)
        assertEquals(32400, signed.port)
        assertEquals("/photo/:/transcode", signed.encodedPath)
        assertEquals("400", signed.parameters["width"])
    }

    @Test
    fun onlyPictureLinksAreSigned() {
        assertNull(PlexArtworkLink.signed("http://192.168.1.20:32400/library/parts/5/file.mkv#nuvio-plex=c1", "http://x", "t"))
    }
}
