package com.nexus.mobile

internal enum class MobileHomeAction {
    PAIR, SETUP, START, PAUSE;

    companion object {
        fun forState(configured: Boolean, state: SyncState, accessibilityReady: Boolean): MobileHomeAction = when {
            !configured || state == SyncState.PAIRING_EXPIRED || state == SyncState.AUTH_FAILED -> PAIR
            !accessibilityReady -> SETUP
            state == SyncState.ONLINE || state == SyncState.CONNECTING || state == SyncState.SETUP_REQUIRED -> PAUSE
            else -> START
        }
    }
}
