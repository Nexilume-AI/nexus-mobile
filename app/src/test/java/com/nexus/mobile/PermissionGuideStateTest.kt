package com.nexus.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionGuideStateTest {
    @Test fun requiredAccessibilityAlwaysComesFirst() {
        for (notificationReady in listOf(false, true)) {
            for (deferred in listOf(false, true)) {
                val state = PermissionGuideState.fromPermissions(false, notificationReady, deferred)
                assertEquals(PermissionGuideStep.ACCESSIBILITY, state.step)
                assertFalse(state.allPermissionsGranted)
            }
        }
    }

    @Test fun onlyAnActualGrantAdvancesToNotifications() {
        // Opening/returning from Settings without granting leaves the same observed state.
        repeat(3) { assertEquals(PermissionGuideStep.ACCESSIBILITY,
            PermissionGuideState.fromPermissions(false, false, false).step) }
        assertEquals(PermissionGuideStep.NOTIFICATIONS,
            PermissionGuideState.fromPermissions(true, false, false).step)
    }

    @Test fun deferringNotificationsDoesNotClaimPermission() {
        val state = PermissionGuideState.fromPermissions(true, false, true)
        assertEquals(PermissionGuideStep.FINISHED, state.step)
        assertTrue(state.notificationsDeferred)
        assertFalse(state.notificationReady)
        assertFalse(state.allPermissionsGranted)
    }

    @Test fun grantingBothFinishesWithoutImplyingACloudConnection() {
        val state = PermissionGuideState.fromPermissions(true, true, true)
        assertEquals(PermissionGuideStep.FINISHED, state.step)
        assertTrue(state.allPermissionsGranted)
        assertFalse(state.notificationsDeferred)
    }

    @Test fun revokedPermissionBecomesRequiredAgain() {
        assertEquals(PermissionGuideStep.FINISHED,
            PermissionGuideState.fromPermissions(true, true, false).step)
        assertEquals(PermissionGuideStep.ACCESSIBILITY,
            PermissionGuideState.fromPermissions(false, true, false).step)
        assertEquals(PermissionGuideStep.NOTIFICATIONS,
            PermissionGuideState.fromPermissions(true, false, false).step)
    }
}
