package com.nuvio.app.features.usenet

import java.io.IOException

/** Control-plane failures only; media errors still use the player's recovery ladder. */
internal class UsenetPreparationException(
    message: String,
    val scope: Scope = Scope.SOURCE,
    /** The engine's X-Usenet-Failure kind, such as "missing-article", when it sent one. */
    val failure: String? = null,
    /** The engine's X-Usenet-Providers report: what each provider did for an open that failed. */
    val providers: String? = null
) : IOException(message) {
    enum class Scope { SOURCE, PROVIDER, ENGINE }
}
