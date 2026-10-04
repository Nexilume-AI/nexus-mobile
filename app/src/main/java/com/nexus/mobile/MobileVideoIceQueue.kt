package com.nexus.mobile

/** Never call JNI while holding this queue's lock: WebRTC callbacks use its signaling thread. */
class MobileVideoIceQueue<T> {
    private var ready = false
    private val pending = mutableListOf<T>()

    @Synchronized fun receive(candidate: T): List<T> {
        if (ready) return listOf(candidate)
        check(pending.size < 64) { "ICE queue full" }
        pending.add(candidate)
        return emptyList()
    }

    @Synchronized fun activate(): List<T> {
        ready = true
        return pending.toList().also { pending.clear() }
    }
}
