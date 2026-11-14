package com.nuvio.app.features.servers.silo

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SiloArtworkTest {
    private val stale = "https://silo.example.com/api/v2/artwork/a.jpg?exp=1000&sig=old"
    private val fresh = "https://silo.example.com/api/v2/artwork/a.jpg?exp=2000000000&sig=new"
    private var fetches = 0
    private val refresher = SiloArtworkRefresher(
        fetch = {
            fetches++
            mapOf(SiloArtworkKind.POSTER to fresh)
        },
        now = { 1_500_000L },
    )

    @Test
    fun aLinkNamesItsTitle() {
        val link = SiloArtworkLink.mark(stale, "c1", "movie-tmdb-6.03", SiloArtworkKind.POSTER)

        assertEquals(SiloArtworkMark("c1", "movie-tmdb-6.03", SiloArtworkKind.POSTER), SiloArtworkLink.parse(link))
        assertEquals(1000L, SiloArtworkLink.expiresAt(link))
        assertNull(SiloArtworkLink.parse(stale))
    }

    @Test
    fun anExpiredLinkGetsAFreshOne() = runTest {
        val link = SiloArtworkLink.mark(stale, "c1", "movie-tmdb-603", SiloArtworkKind.POSTER)
        val mark = SiloArtworkLink.parse(link)!!

        assertEquals(fresh, refresher.fresh(mark, link))
    }

    @Test
    fun theSameLinkIsNotOfferedAgain() = runTest {
        val current = SiloArtworkLink.mark(fresh, "c1", "movie-tmdb-603", SiloArtworkKind.POSTER)

        assertNull(refresher.fresh(SiloArtworkLink.parse(current)!!, current))
    }

    @Test
    fun freshLinksAreKeptPerTitle() = runTest {
        val mark = SiloArtworkMark("c1", "movie-tmdb-603", SiloArtworkKind.POSTER)

        refresher.fresh(mark, stale)
        refresher.fresh(mark.copy(kind = SiloArtworkKind.BACKGROUND), stale)

        assertEquals(1, fetches)
    }
}
