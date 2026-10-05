package com.nexus.mobile

internal enum class MobileHomeAction {
    PAIR, SETUP, START, PAUSE;

    companion object {
        fun forState(configured: Boolean, state: SyncState, accessibilityReady: Boolean): MobileHomeAction = when {
            !configured || state == SyncState.PAIRING_EXPIRED || state == SyncState.AUTH_FAILED -> PAIR
            !accessibilityReady -> SETUP
            state in setOf(SyncState.ONLINE, SyncState.CONNECTING, SyncState.SETUP_REQUIRED, SyncState.CONTROL_DISCONNECTED) -> PAUSE
            else -> START
        }
    }
}
