package com.nexus.mobile

import org.junit.Assert.*
import org.junit.Test

class AccessibilityControlStateTest {
    @Test fun grantAloneNeverEnablesControl() {
        val state = AccessibilityControlState(permissionGranted = true, connected = false)
        assertFalse(state.connected)
        assertEquals(SyncState.CONTROL_DISCONNECTED, state.syncState)
        assertEquals(MobileHomeAction.SETUP, MobileHomeAction.forState(true, state.syncState, state.connected))
    }

    @Test fun missingGrantAndDisconnectedBindingAreDifferent() {
        assertEquals(SyncState.SETUP_REQUIRED, AccessibilityControlState(false, false).syncState)
        assertEquals(SyncState.CONTROL_DISCONNECTED, AccessibilityControlState(true, false).syncState)
    }

    @Test fun reconnectResumesSyncButNeverResumesUserPause() {
        assertEquals(SyncState.CONNECTING, AccessibilityControlState(true, true).syncState)
        assertEquals(MobileHomeAction.PAUSE, MobileHomeAction.forState(true, SyncState.CONTROL_DISCONNECTED, true))
        assertEquals(MobileHomeAction.START, MobileHomeAction.forState(true, SyncState.STOPPED, true))
    }
}
