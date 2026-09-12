package com.nuvio.app.features.mdblist

import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MdbListHttpClientTest {
    @Test
    fun `read retries transient failures with bounded backoff`() = runTest {
        val harness = MdbListTestHarness()
        harness.reply(503)
        harness.reply(502)
        harness.reply(body = "response")

        val result = harness.http.execute(get())

        assertEquals("response", result.body)
        assertEquals(listOf(1_000L, 2_000L), harness.sleeps)
        assertEquals(3, harness.engine.requests.size)
    }

    @Test
    fun `write failures never retry uncertain mutations`() = runTest {
        val harness = MdbListTestHarness()
        harness.reply(503)

        assertEquals(503, harness.http.execute(post()).status)
        assertEquals(1, harness.engine.requests.size)
        assertTrue(harness.sleeps.isEmpty())

        harness.engine.intercept = { throw IOException("Bearer should-not-leak") }
        val error = expectMdbListFailure<MdbListApiException> { harness.http.execute(post()) }

        assertEquals(2, harness.engine.requests.size)
        assertFalse(error.toString().contains("should-not-leak"))
        assertEquals(null, error.cause)
    }

    @Test
    fun `daily quota blocks subsequent calls without holding the coroutine until tomorrow`() = runTest {
        val harness = MdbListTestHarness()
        harness.reply(429, headers = mapOf("Retry-After" to "3600"))

        val first = expectMdbListFailure<MdbListApiException> { harness.http.execute(get()) }
        val second = expectMdbListFailure<MdbListApiException> { harness.http.execute(get()) }

        assertEquals(harness.now + 3_600_000L, first.retryAtEpochMs)
        assertEquals(first.retryAtEpochMs, second.retryAtEpochMs)
        assertEquals(1, harness.engine.requests.size)
        assertTrue(harness.sleeps.isEmpty())
        harness.now += 3_600_000L
        harness.reply()
        assertEquals(200, harness.http.execute(get()).status)
    }

    @Test
    fun `successful last allowed request blocks later reads until reset`() = runTest {
        val harness = MdbListTestHarness()
        harness.reply(headers = mapOf(
            "x-ratelimit-remaining" to "0",
            "x-ratelimit-reset" to ((harness.now + 60_000L) / 1_000L).toString()
        ))

        assertEquals(200, harness.http.execute(get()).status)
        expectMdbListFailure<MdbListApiException> { harness.http.execute(get()) }

        assertEquals(1, harness.engine.requests.size)
    }

    @Test
    fun `one account rate limit does not prevent another account request`() = runTest {
        val harness = MdbListTestHarness()
        harness.reply(429)
        expectMdbListFailure<MdbListApiException> { harness.http.execute(get("first")) }
        harness.reply()

        assertEquals(200, harness.http.execute(get("second")).status)
    }

    @Test
    fun `long server retry after returns immediately`() = runTest {
        val harness = MdbListTestHarness()
        harness.reply(503, headers = mapOf("Retry-After" to "120"))

        val error = expectMdbListFailure<MdbListApiException> { harness.http.execute(get()) }

        assertEquals(harness.now + 120_000L, error.retryAtEpochMs)
        assertTrue(harness.sleeps.isEmpty())
    }

    @Test
    fun `cancellation is propagated without retry`() = runTest {
        val harness = MdbListTestHarness()
        harness.engine.intercept = { throw CancellationException("cancelled") }

        expectMdbListFailure<CancellationException> { harness.http.execute(get()) }

        assertEquals(1, harness.engine.requests.size)
        assertTrue(harness.sleeps.isEmpty())
    }

    @Test
    fun `queued old profile request is discarded before network dispatch`() = runTest {
        val harness = MdbListTestHarness()
        val scope = harness.store.scope()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.reply()
        harness.engine.intercept = { started.complete(Unit); release.await() }
        val first = async { harness.http.execute(get()) }
        started.await()
        val second = async {
            expectMdbListFailure<CancellationException> {
                harness.http.execute(get(), checkScope = { harness.store.checkScope(scope) })
            }
        }
        runCurrent()
        harness.store.selectProfile(2)
        release.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, harness.engine.requests.size)
    }

    @Test
    fun `response and request diagnostics redact credentials and body`() {
        val request = MdbListHttpRequest(
            MdbListHttpMethod.POST, "/oauth/token/", body = "private-body", accessToken = "private-token",
            form = mapOf("device_code" to "private-code")
        )
        val response = MdbListHttpResponse(400, "private-body", mapOf("Authorization" to "private-token"))

        assertFalse(request.toString().contains("private"))
        assertFalse(response.toString().contains("private"))
    }

    @Test
    fun `retry after supports HTTP dates and rejects invalid or overflowing values`() {
        assertEquals(1_000L, retryAfterEpochMs("1", 0))
        assertEquals(1_445_412_480_000L, retryAfterEpochMs("Wed, 21 Oct 2015 07:28:00 GMT", 0))
        assertEquals(null, retryAfterEpochMs("-1", 0))
        assertEquals(null, retryAfterEpochMs(Long.MAX_VALUE.toString(), 0))
        assertEquals(null, retryAfterEpochMs("invalid", 0))
    }

    private fun get(limitKey: String = "account") = MdbListHttpRequest(MdbListHttpMethod.GET, "/sync/playback", limitKey = limitKey)
    private fun post() = MdbListHttpRequest(MdbListHttpMethod.POST, "/scrobble/stop")
}
