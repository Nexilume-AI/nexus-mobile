package com.nexus.mobile

/** Brief packet loss must not revoke projection; failed/expired ICE still closes it. */
internal class MobileVideoConnectivity(startedAt: Long) {
    private var deadline = startedAt + 30_000
    @Synchronized fun update(state: String, now: Long) {
        when (state) {
            "CONNECTED", "COMPLETED" -> deadline = Long.MAX_VALUE
            "DISCONNECTED" -> if (deadline == Long.MAX_VALUE) deadline = now + 5_000
            "FAILED", "CLOSED" -> deadline = now
        }
    }
    @Synchronized fun expired(now: Long) = now >= deadline
}
