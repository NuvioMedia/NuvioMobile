package com.nuvio.app.features.profiles

import kotlinx.atomicfu.atomic

internal class ProfilePreload<T : Any> {
    private val entry = atomic<Pair<Int, T>?>(null)

    fun put(profileId: Int, value: T) {
        entry.value = profileId to value
    }

    fun take(profileId: Int): T? = entry.getAndSet(null)?.takeIf { it.first == profileId }?.second
}
