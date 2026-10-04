package com.nexus.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileHomeActionTest {
    @Test fun unpairedPhoneOnlyOffersPairing() {
        for (state in SyncState.entries) assertEquals(MobileHomeAction.PAIR,
            MobileHomeAction.forState(false, state, false))
    }
    @Test fun expiredAndRevokedPairingRequiresANewQr() {
        for (state in listOf(SyncState.PAIRING_EXPIRED, SyncState.AUTH_FAILED)) {
            for (ready in listOf(false, true)) assertEquals(MobileHomeAction.PAIR,
                MobileHomeAction.forState(true, state, ready))
        }
    }
    @Test fun missingAccessibilityAlwaysOffersSetupBeforeStarting() {
        for (state in listOf(SyncState.STOPPED, SyncState.OFFLINE, SyncState.CONNECTING, SyncState.SETUP_REQUIRED, SyncState.ONLINE)) {
            assertEquals(MobileHomeAction.SETUP, MobileHomeAction.forState(true, state, false))
        }
    }
    @Test fun activeSyncKeepsPauseReachable() {
        for (state in listOf(SyncState.ONLINE, SyncState.CONNECTING, SyncState.SETUP_REQUIRED)) {
            assertEquals(MobileHomeAction.PAUSE, MobileHomeAction.forState(true, state, true))
        }
    }
    @Test fun pausedAndOfflineDevicesCanRestart() {
        for (state in listOf(SyncState.STOPPED, SyncState.OFFLINE)) {
            assertEquals(MobileHomeAction.START, MobileHomeAction.forState(true, state, true))
        }
    }
}
