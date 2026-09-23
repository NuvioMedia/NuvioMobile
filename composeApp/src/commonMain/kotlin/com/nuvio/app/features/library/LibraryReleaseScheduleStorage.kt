package com.nuvio.app.features.library

/** Persists the Library release calendar (episode schedule) per profile across app launches. */
internal expect object LibraryReleaseScheduleStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
