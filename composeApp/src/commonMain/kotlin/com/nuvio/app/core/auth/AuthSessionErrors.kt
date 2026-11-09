package com.nuvio.app.core.auth

import io.github.jan.supabase.exceptions.RestException

private val invalidSessionMarkers = listOf(
    "session not found",
    "session_not_found",
    "invalid session",
    "invalid token",
    "invalid jwt",
    "user not found",
    "user does not exist",
)

private val invalidRefreshMarkers = listOf(
    "invalid refresh token",
    "refresh token is not valid",
    "refresh token not found",
    "refresh_token_not_found",
    "invalid_grant",
    "session not found",
    "session_not_found",
    "invalid session",
)

internal inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

internal fun Throwable.isJwtExpiredError(): Boolean = "jwt expired" in authMessages()

internal fun Throwable.isInvalidSessionError(): Boolean {
    if (findCause<RestException>()?.statusCode == 401) return true
    val messages = authMessages()
    return invalidSessionMarkers.any(messages::contains) || isDeletedAccountError()
}

internal fun Throwable.isInvalidRefreshError(): Boolean {
    val statusCode = findCause<RestException>()?.statusCode ?: return false
    if (statusCode !in setOf(400, 401, 403)) return false
    val messages = authMessages()
    return invalidRefreshMarkers.any(messages::contains)
}

internal fun Throwable.isDeletedAccountError(): Boolean {
    val messages = authMessages()
    return (
        "user" in messages &&
            ("does not exist" in messages || "not found" in messages || "deleted" in messages)
        ) || (
        "foreign key" in messages &&
            ("auth.users" in messages || "user_id" in messages)
        )
}

private fun Throwable.authMessages(): String = buildString {
    var current: Throwable? = this@authMessages
    while (current != null) {
        append(current.message.orEmpty())
        append(' ')
        if (current is RestException) {
            append(current.error)
            append(' ')
            append(current.description.orEmpty())
            append(' ')
        }
        current = current.cause
    }
}.lowercase()
