package com.nexus.mobile

internal enum class PermissionGuideStep { ACCESSIBILITY, NOTIFICATIONS, FINISHED }

/** Only observed permissions advance the required step; skipping is never a grant. */
internal data class PermissionGuideState(
    val step: PermissionGuideStep,
    val accessibilityReady: Boolean,
    val notificationReady: Boolean,
    val notificationsDeferred: Boolean,
) {
    val allPermissionsGranted: Boolean get() = accessibilityReady && notificationReady

    companion object {
        fun fromPermissions(
            accessibilityReady: Boolean,
            notificationReady: Boolean,
            notificationsDeferred: Boolean,
        ) = PermissionGuideState(
            step = when {
                !accessibilityReady -> PermissionGuideStep.ACCESSIBILITY
                !notificationReady && !notificationsDeferred -> PermissionGuideStep.NOTIFICATIONS
                else -> PermissionGuideStep.FINISHED
            },
            accessibilityReady = accessibilityReady,
            notificationReady = notificationReady,
            notificationsDeferred = notificationsDeferred && !notificationReady,
        )
    }
}
