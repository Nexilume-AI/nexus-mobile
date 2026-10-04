package com.nexus.mobile

enum class MobileScreenPrivacy { CLEAR, UNKNOWN, SENSITIVE }

/** Unknown windows never emit frames, but a brief Android window transition is not revocation. */
class MobileVideoPrivacyGate(private val unknownTimeoutMs: Long = 5_000) {
    enum class Decision { SEND, DROP, STOP }
    private var unknownSince: Long? = null
    private var stopped = false

    @Synchronized
    fun evaluate(privacy: MobileScreenPrivacy, nowMs: Long): Decision {
        if (stopped) return Decision.STOP
        when (privacy) {
            MobileScreenPrivacy.SENSITIVE -> stopped = true
            MobileScreenPrivacy.CLEAR -> { unknownSince = null; return Decision.SEND }
            MobileScreenPrivacy.UNKNOWN -> {
                val since = unknownSince ?: nowMs.also { unknownSince = it }
                if (nowMs - since < unknownTimeoutMs) return Decision.DROP
                stopped = true
            }
        }
        return Decision.STOP
    }
}
