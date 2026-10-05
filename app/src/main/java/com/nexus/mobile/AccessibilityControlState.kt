package com.nexus.mobile

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** Authorization is not a live system binding. Never use it to enable commands. */
internal data class AccessibilityControlState(val permissionGranted: Boolean, val connected: Boolean) {
    val syncState: SyncState get() = when {
        connected -> SyncState.CONNECTING
        permissionGranted -> SyncState.CONTROL_DISCONNECTED
        else -> SyncState.SETUP_REQUIRED
    }

    companion object {
        fun read(context: Context): AccessibilityControlState {
            val connected = NexusAccessibilityServiceHolder.service != null
            val expected = ComponentName(context, NexusAccessibilityService::class.java)
            val enabled = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            }.getOrNull().orEmpty()
            val granted = enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
            return AccessibilityControlState(granted || connected, connected)
        }
    }
}
