package com.nuvio.app.features.usenet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UsenetSidecarRedactionTest {
    @Test fun `logged engine messages keep only each URL's host`() {
        val message = "missing article in https://indexer.test/api?t=get&id=1&apikey=secret from nntps://user:pass@news.test:563/20"
        val logged = UsenetSidecar.redacted(message)
        assertEquals("missing article in https://indexer.test/… from nntps://news.test:563/…", logged)
        assertFalse(logged.contains("secret") || logged.contains("pass"))
    }
}
