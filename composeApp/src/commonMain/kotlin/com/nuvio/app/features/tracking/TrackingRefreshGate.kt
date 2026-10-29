package com.nuvio.app.features.tracking

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class TrackingRefreshGateOutcome {
    COALESCED,
    EXECUTED,
    FRESHNESS_SKIPPED,
}

internal class TrackingRefreshGate {
    private val mutex = Mutex()
    private val completionSequence = atomic(0L)
    private var lastCompletedProfileGeneration: Long? = null

    suspend fun runIfNeeded(
        profileGeneration: Long,
        shouldRun: () -> Boolean,
        block: suspend () -> Unit,
    ): TrackingRefreshGateOutcome {
        val observedSequence = completionSequence.value
        return mutex.withLock {
            if (
                completionSequence.value != observedSequence &&
                lastCompletedProfileGeneration == profileGeneration
            ) {
                return@withLock TrackingRefreshGateOutcome.COALESCED
            }
            if (!shouldRun()) return@withLock TrackingRefreshGateOutcome.FRESHNESS_SKIPPED

            try {
                block()
                TrackingRefreshGateOutcome.EXECUTED
            } finally {
                lastCompletedProfileGeneration = profileGeneration
                completionSequence.incrementAndGet()
            }
        }
    }
}
