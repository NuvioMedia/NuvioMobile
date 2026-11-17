package com.nuvio.app.features.mdblist

import io.ktor.utils.io.errors.IOException
import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MdbListHttpClient(
    private val engine: MdbListHttpEngine,
    private val nowEpochMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    private val mutex = Mutex()
    private val blockedUntil = mutableMapOf<String, Long>()

    suspend fun associateAccountLimit(previousKey: String, accountKey: String) = mutex.withLock {
        blockedUntil.remove(previousKey)?.let { until ->
            blockedUntil[accountKey] = maxOf(until, blockedUntil[accountKey] ?: 0L)
        }
    }

    suspend fun execute(
        request: MdbListHttpRequest,
        checkScope: () -> Unit = {}
    ): MdbListHttpResponse = mutex.withLock {
        val attempts = if (request.retrySafe) 3 else 1
        repeat(attempts) { attempt ->
            checkScope()
            val now = nowEpochMs()
            blockedUntil.entries.removeAll { it.value <= now }
            blockedUntil[request.limitKey]?.let { reset ->
                throw MdbListApiException(429, "rate_limited", reset)
            }
            val response = try {
                engine.execute(request)
            } catch (_: IOException) {
                checkScope()
                if (attempt == attempts - 1) throw MdbListApiException(code = "transport_failure")
                sleep(1_000L shl attempt)
                return@repeat
            }
            checkScope()
            val retryAt = response.header("Retry-After")?.let { retryAfterEpochMs(it, nowEpochMs()) }
            if (response.status == 429 || response.header("X-RateLimit-Remaining")?.toLongOrNull() == 0L) {
                val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()
                    ?.takeIf { it in 1..Long.MAX_VALUE / 1_000L }?.times(1_000L)
                blockedUntil[request.limitKey] = maxOf(
                    nowEpochMs() + 1_000L,
                    retryAt ?: reset ?: (nowEpochMs() + 60_000L),
                    reset ?: 0L
                )
            }
            if (response.status == 429) {
                throw MdbListApiException(429, "rate_limited", blockedUntil[request.limitKey])
            }
            if (request.retrySafe && response.status in setOf(500, 502, 503, 504) && attempt < attempts - 1) {
                val wait = maxOf(1_000L shl attempt, (retryAt ?: 0L) - nowEpochMs())
                if (wait > 5_000L) throw MdbListApiException(response.status, retryAtEpochMs = retryAt)
                sleep(wait)
            } else {
                return@withLock response
            }
        }
        throw MdbListApiException(code = "request_failed")
    }
}

internal fun retryAfterEpochMs(value: String, now: Long): Long? {
    val seconds = value.trim().toLongOrNull()
    if (seconds != null) return seconds.takeIf { it >= 0L && it <= (Long.MAX_VALUE - now) / 1_000L }
        ?.let { now + it * 1_000L }
    return runCatching {
        value.fromHttpToGmtDate().timestamp
    }.getOrNull()
}
