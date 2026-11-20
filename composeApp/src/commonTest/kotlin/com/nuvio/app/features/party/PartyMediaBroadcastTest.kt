package com.nuvio.app.features.party

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartyMediaBroadcastTest {

    @Test
    fun `the host can move the party to media the server already holds`() {
        assertTrue(acceptsMediaBroadcast("host", "host", "movie|tt1||", "movie|tt1||"))
    }

    @Test
    fun `a member cannot move the party`() {
        assertFalse(acceptsMediaBroadcast("guest", "host", "movie|tt2||", "movie|tt2||"))
    }

    @Test
    fun `media the server does not hold is ignored`() {
        assertFalse(acceptsMediaBroadcast("host", "host", "movie|tt2||", "movie|tt1||"))
        assertFalse(acceptsMediaBroadcast("host", "host", "movie|tt2||", null))
        assertFalse(acceptsMediaBroadcast("host", "host", null, null))
    }

    @Test
    fun `payloads without a sender still need the server to hold the media`() {
        assertTrue(acceptsMediaBroadcast(null, "host", "series|tt9|1|2", "series|tt9|1|2"))
        assertFalse(acceptsMediaBroadcast(null, "host", "series|tt9|1|3", "series|tt9|1|2"))
    }
}
