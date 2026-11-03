package com.lagradost.cloudstream3.utils

/** CloudStream's small observer primitive, retained for plugin binary compatibility. */
class Event<T> {
    private val observers = mutableSetOf<(T) -> Unit>()
    val size: Int get() = synchronized(observers) { observers.size }

    operator fun plusAssign(observer: (T) -> Unit) {
        synchronized(observers) { observers.add(observer) }
    }

    operator fun minusAssign(observer: (T) -> Unit) {
        synchronized(observers) { observers.remove(observer) }
    }

    operator fun invoke(value: T) {
        val snapshot = synchronized(observers) { observers.toList() }
        snapshot.forEach { it(value) }
    }
}

class EmptyEvent {
    private val observers = mutableSetOf<Runnable>()
    val size: Int get() = synchronized(observers) { observers.size }

    operator fun plusAssign(observer: Runnable) {
        synchronized(observers) { observers.add(observer) }
    }

    operator fun minusAssign(observer: Runnable) {
        synchronized(observers) { observers.remove(observer) }
    }

    operator fun invoke() {
        val snapshot = synchronized(observers) { observers.toList() }
        snapshot.forEach(Runnable::run)
    }
}
