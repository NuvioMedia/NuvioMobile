package com.nuvio.app.features.simkl

import com.nuvio.app.features.addons.RawHttpResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimklApiClientTest {
    @Test
    fun `response classification retries only documented transient statuses`() {
        assertEquals(SimklResponseAction.SUCCESS, classifySimklResponse(200))
        assertEquals(SimklResponseAction.REAUTHENTICATE, classifySimklResponse(401))
        assertFalse(shouldRefreshAfterSimklUnauthorized("user_token_required"))
        assertFalse(shouldRefreshAfterSimklUnauthorized("invalid_client"))
        assertTrue(shouldRefreshAfterSimklUnauthorized(null))
        assertTrue(shouldRefreshAfterSimklUnauthorized("invalid_token"))
        assertEquals(SimklResponseAction.RETRY, classifySimklResponse(429))
        assertEquals(SimklResponseAction.RETRY, classifySimklResponse(500))
        assertEquals(SimklResponseAction.RETRY, classifySimklResponse(502))
        assertEquals(SimklResponseAction.RETRY, classifySimklResponse(503))
        assertEquals(SimklResponseAction.FAIL, classifySimklResponse(400))
        assertEquals(SimklResponseAction.FAIL, classifySimklResponse(409))
        assertEquals(SimklResponseAction.SOFT_SUCCESS, classifySimklResponse(409, true))
        assertEquals(SimklResponseAction.FAIL, classifySimklResponse(412))
    }

    @Test
    fun `retry schedule uses exponential backoff and respects retry after`() {
        assertEquals(1_000L, retryDelayMs(0, null, 0L))
        assertEquals(2_000L, retryDelayMs(1, null, 0L))
        assertEquals(4_000L, retryDelayMs(2, null, 0L))
        assertEquals(8_000L, retryDelayMs(3, null, 0L))
        assertEquals(16_000L, retryDelayMs(4, null, 0L))
        assertEquals(30_250L, retryDelayMs(0, "30", 250L))
        assertEquals(60_000L, retryDelayMs(0, "120", 1_000L))
    }

    @Test
    fun `callers cannot override mandatory application metadata`() {
        val url = buildSimklApiUrl(
            path = "/sync/activities",
            query = mapOf(
                "client_id" to "spoofed",
                "app-name" to "spoofed",
                "app-version" to "spoofed",
            ),
        )

        assertFalse("spoofed" in url)
    }

    @Test
    fun `every request carries metadata query and required headers`() = runBlocking {
        val engine = RecordingEngine(response(200))
        val harness = TestHarness(engine)

        harness.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = "/sync/activities",
            ),
        )

        val request = engine.requests.single()
        assertTrue("client_id=" in request.url)
        assertTrue("app-name=" in request.url)
        assertTrue("app-version=" in request.url)
        assertEquals("Bearer token", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Accept"])
        assertTrue(request.headers.getValue("User-Agent").contains('/'))
    }

    @Test
    fun `bodyless post sends json content type without inventing a payload`() = runBlocking {
        val engine = RecordingEngine(response(200))
        val harness = TestHarness(engine)

        harness.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/users/settings",
            ),
        )

        val request = engine.requests.single()
        assertEquals("", request.body)
        assertEquals("application/json", request.headers["Content-Type"])
    }

    @Test
    fun `sync response diagnostics exclude response bodies and query values`() {
        val body = """{"movies":[{"movie":{"title":"Private title"}}]}"""
        val request = SimklApiRequest(
            method = SimklHttpMethod.GET,
            path = "/sync/all-items/",
            query = mapOf("date_from" to "2026-07-22T08:48:07Z"),
        )

        val message = simklSyncResponseLogMessage(
            request = request,
            response = response(
                status = 200,
                body = body,
                headers = mapOf("Content-Type" to "application/json"),
            ),
            attempt = 1,
        )

        assertTrue("queryKeys=[date_from]" in message)
        assertTrue("bodyChars=${body.length}" in message)
        assertFalse("Private title" in message)
        assertFalse("2026-07-22T08:48:07Z" in message)
    }

    @Test
    fun `single use unauthenticated posts keep metadata and never retry`() = runBlocking {
        val engine = RecordingEngine(response(503), response(200))
        val harness = TestHarness(engine)

        assertFailsWith<SimklApiException> {
            harness.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/oauth/token",
                    body = "{}",
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                ),
            )
        }

        val request = engine.requests.single()
        assertTrue("client_id=" in request.url)
        assertTrue("app-name=" in request.url)
        assertTrue("app-version=" in request.url)
        assertTrue(request.headers.getValue("User-Agent").contains('/'))
        assertFalse("Authorization" in request.headers)
        assertTrue(harness.sleeps.isEmpty())
        assertFalse(harness.wasUnauthorized)
    }

    @Test
    fun `unauthenticated 401 does not invalidate an existing session`() = runBlocking {
        val engine = RecordingEngine(response(401))
        val harness = TestHarness(engine)

        assertFailsWith<SimklApiException> {
            harness.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/oauth/token",
                    body = "{}",
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                ),
            )
        }

        assertFalse(harness.wasUnauthorized)
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `authenticated requests are serialized at documented method rates`() = runBlocking {
        val engine = RecordingEngine(response(200), response(200), response(200), response(200))
        val harness = TestHarness(engine)

        harness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/one"))
        harness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/two"))
        harness.client.execute(SimklApiRequest(SimklHttpMethod.POST, "/three", body = "{}"))
        harness.client.execute(SimklApiRequest(SimklHttpMethod.POST, "/four", body = "{}"))

        assertEquals(listOf(100L, 1_000L), harness.sleeps)
        assertEquals(listOf(0L, 100L, 100L, 1_100L), engine.requests.map { it.atEpochMs })
    }

    @Test
    fun `post cooldown starts after the previous response completes`() = runBlocking {
        val engine = RecordingEngine(response(200), response(200))
        val harness = TestHarness(engine, responseDurationMs = 400L)

        harness.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/oauth/token",
                body = "{}",
                requiresAuthentication = false,
                retryPolicy = SimklRetryPolicy.NEVER,
            ),
        )
        harness.client.execute(SimklApiRequest(SimklHttpMethod.POST, "/users/settings"))

        assertEquals(listOf(1_000L), harness.sleeps)
        assertEquals(listOf(0L, 1_400L), engine.requests.map { it.atEpochMs })
    }

    @Test
    fun `transient responses retry sequentially and deterministic errors do not`() = runBlocking {
        val transientEngine = RecordingEngine(response(503), response(502), response(200))
        val transientHarness = TestHarness(transientEngine)

        transientHarness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/retry"))

        assertEquals(listOf(1_000L, 2_000L), transientHarness.sleeps)
        assertEquals(3, transientEngine.requests.size)

        val deterministicEngine = RecordingEngine(response(400, """{"error":"wrong_parameter","code":400}"""))
        val deterministicHarness = TestHarness(deterministicEngine)
        val error = assertFailsWith<SimklApiException> {
            deterministicHarness.client.execute(SimklApiRequest(SimklHttpMethod.POST, "/bad", body = "{}"))
        }
        assertEquals("wrong_parameter", error.errorCode)
        assertEquals(1, deterministicEngine.requests.size)
    }

    @Test
    fun `transient failures stop after five total attempts`() = runBlocking {
        val engine = RecordingEngine(
            response(503),
            response(503),
            response(503),
            response(503),
            response(503),
            response(200),
        )
        val harness = TestHarness(engine)

        assertFailsWith<SimklApiException> {
            harness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/unavailable"))
        }

        assertEquals(5, engine.requests.size)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), harness.sleeps)
    }

    @Test
    fun `sync write lock is retried once and other bad requests are not`() = runBlocking {
        val lockedEngine = RecordingEngine(
            response(400, """{"error":"rate_limit","error_description":"Another sync is in progress"}"""),
            response(200),
        )
        val lockedHarness = TestHarness(lockedEngine)

        lockedHarness.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history",
                body = "{}",
                retryPolicy = SimklRetryPolicy.SYNC_WRITE,
            ),
        )

        assertEquals(2, lockedEngine.requests.size)
        assertEquals(listOf(3_000L), lockedHarness.sleeps)

        val invalidEngine = RecordingEngine(
            response(400, """{"error":"wrong_parameter"}"""),
            response(200),
        )
        val invalidHarness = TestHarness(invalidEngine)

        assertFailsWith<SimklApiException> {
            invalidHarness.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/sync/history",
                    body = "{}",
                    retryPolicy = SimklRetryPolicy.SYNC_WRITE,
                ),
            )
        }

        assertEquals(1, invalidEngine.requests.size)
    }

    @Test
    fun `retry after and unauthorized handling are applied once`() = runBlocking {
        val retryEngine = RecordingEngine(
            response(429, headers = mapOf("Retry-After" to "3")),
            response(200),
        )
        val retryHarness = TestHarness(retryEngine)
        retryHarness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/limited"))
        assertEquals(listOf(3_000L), retryHarness.sleeps)

        val unauthorizedEngine = RecordingEngine(response(401))
        val unauthorizedHarness = TestHarness(unauthorizedEngine)
        assertFailsWith<SimklApiException> {
            unauthorizedHarness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/private"))
        }
        assertTrue(unauthorizedHarness.wasUnauthorized)
        assertEquals(1, unauthorizedEngine.requests.size)
    }

    @Test
    fun `authenticated 401 refreshes once and retries with the new token`() = runBlocking {
        val engine = RecordingEngine(
            response(401),
            response(200),
        )
        val harness = TestHarness(engine)
        harness.refreshedToken = "refreshed-token"

        harness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/private"))

        assertTrue(harness.wasUnauthorized)
        assertEquals(2, engine.requests.size)
        assertEquals("Bearer token", engine.requests[0].headers["Authorization"])
        assertEquals("Bearer refreshed-token", engine.requests[1].headers["Authorization"])
    }

    @Test
    fun `user token required does not trigger refresh`() = runBlocking {
        val engine = RecordingEngine(response(401, """{"error":"user_token_required"}"""))
        val harness = TestHarness(engine)

        assertFailsWith<SimklApiException> {
            harness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/private"))
        }

        assertFalse(harness.wasUnauthorized)
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `duplicate scrobble stop is a soft success`() = runBlocking {
        val engine = RecordingEngine(response(409))
        val harness = TestHarness(engine)

        val result = harness.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/scrobble/stop",
                body = "{}",
                scrobbleStopConflictIsSuccess = true,
            ),
        )

        assertEquals(409, result.status)
        assertTrue(result.isSoftSuccess)
        assertFalse(harness.wasUnauthorized)
    }

    private class TestHarness(
        engine: RecordingEngine,
        responseDurationMs: Long = 0L,
    ) {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        var wasUnauthorized = false
        var refreshedToken: String? = null
        val client = SimklApiClient(
            engine = engine.also { recording ->
                recording.now = { now }
                recording.onResponse = { now += responseDurationMs }
            },
            accessToken = { "token" },
            onUnauthorized = {
                wasUnauthorized = true
                refreshedToken
            },
            nowEpochMs = { now },
            sleep = { delayMs ->
                sleeps += delayMs
                now += delayMs
            },
            retryJitterMs = { 0L },
        )
    }

    private class RecordingEngine(vararg responses: RawHttpResponse) : SimklHttpEngine {
        private val queuedResponses = responses.toMutableList()
        val requests = mutableListOf<RecordedRequest>()
        var now: () -> Long = { 0L }
        var onResponse: () -> Unit = {}

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String,
        ): RawHttpResponse {
            requests += RecordedRequest(method, url, headers, body, now())
            return queuedResponses.removeAt(0).also { onResponse() }
        }
    }

    private data class RecordedRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String,
        val atEpochMs: Long,
    )

    private companion object {
        fun response(
            status: Int,
            body: String = "{}",
            headers: Map<String, String> = emptyMap(),
        ) = RawHttpResponse(
            status = status,
            statusText = "",
            url = "https://api.simkl.com/test",
            body = body,
            headers = headers,
        )
    }
}
