package com.nexus.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySetupStateTest {
    @Test
    fun offersHelpOnAndroid13AndLaterWithoutClaimingPermission() {
        for (sdk in listOf(33, 34, 35, 36)) {
            val state = AccessibilitySetupState.forDevice(sdk, false, false)
            assertTrue(state.showRestrictedSettingsHelp)
            assertFalse(state.showReturnHint)
        }
    }

    @Test
    fun doesNotOfferAndroid13InstructionsOnOlderDevices() {
        assertFalse(AccessibilitySetupState.forDevice(32, false, false).showRestrictedSettingsHelp)
    }

    @Test
    fun visitingAppInfoDoesNotCompleteSetup() {
        val state = AccessibilitySetupState.forDevice(36, false, true)
        assertTrue(state.showReturnHint)
        assertTrue(state.showRestrictedSettingsHelp)
    }

    @Test
    fun actualPermissionHidesRecoveryInstructions() {
        for (returned in listOf(false, true)) {
            val state = AccessibilitySetupState.forDevice(36, true, returned)
            assertFalse(state.showReturnHint)
            assertFalse(state.showRestrictedSettingsHelp)
        }
    }
}
