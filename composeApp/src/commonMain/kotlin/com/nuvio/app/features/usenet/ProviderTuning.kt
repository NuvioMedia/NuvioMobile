package com.nuvio.app.features.usenet

// What a provider tuning run reports, as NuvioTV's tuner does. The run itself is Android's.

/** One measured setting: download speed with [connections] connections on [port]. */
data class TuneReading(val port: Int, val tls: Boolean, val connections: Int, val mbps: Double)

/** What a run is doing now; [readings] are the settings measured so far. */
data class TuneProgress(
    val phase: Phase,
    /** Connections signed in so far while the limit is checked, or the count being measured. */
    val connections: Int,
    val port: Int,
    val tls: Boolean,
    val readings: List<TuneReading>
) {
    enum class Phase { LIMIT, PORT, CONNECTIONS }
}

data class TuneResult(
    /** Connections the provider accepted before it refused one; null when no refusal was confirmed. */
    val limit: Int?,
    /** Confirmed simultaneous connections, including the refused slot if a limit was found. */
    val tried: Int,
    val port: Int,
    val tls: Boolean,
    /** The connection allowance to use: within the limit, never lower than needed for the measured speed. */
    val connections: Int,
    /** The fewest connections within reach of the best measured speed. */
    val fastestConnections: Int,
    val mbps: Double,
    val readings: List<TuneReading>,
    val downloadedBytes: Long
)

enum class TuneFailure {
    /** Nothing could be measured: the provider is unreachable, refused, or the sign-in failed. See [TuneOutcome.connect]. */
    CONNECT,
    /** The test NZB could not be downloaded. */
    TEST_DATA,
    /** The provider does not have the test articles. */
    MISSING_ARTICLES,
    /** Too little arrived to measure a speed. */
    TOO_SLOW
}

sealed interface TuneOutcome {
    data class Success(val result: TuneResult) : TuneOutcome
    data class Failed(val reason: TuneFailure, val connect: UsenetProviderTestResult? = null) : TuneOutcome
}
