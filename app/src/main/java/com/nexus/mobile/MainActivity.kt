package com.nexus.mobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat

internal data class AccessibilitySetupState(
    val showRestrictedSettingsHelp: Boolean,
    val showReturnHint: Boolean,
) {
    companion object {
        fun forDevice(
            sdkInt: Int,
            accessibilityReady: Boolean,
            returnedFromApplicationInfo: Boolean,
        ) = AccessibilitySetupState(
            // Android exposes no portable API for detecting every OEM's restriction.
            // Offer help conditionally, never claim that restriction was detected.
            showRestrictedSettingsHelp = sdkInt >= 33 && !accessibilityReady,
            showReturnHint = !accessibilityReady && returnedFromApplicationInfo,
        )
    }
}

class MainActivity : Activity() {
    private var pairingMessage: String = ""
    private var pendingPairing: PairingPayload? = null
    private var receiverRegistered = false
    private var awaitingApplicationInfoReturn = false
    private var returnedFromApplicationInfo = false
    private var accessibilityNavigationError = ""
    private var awaitingPermissionReturn = ""
    private var permissionReturnHint = ""
    private var screenScroll: ScrollView? = null
    private val guidePreferences by lazy { getSharedPreferences("permission_guide", MODE_PRIVATE) }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingApplicationInfoReturn = savedInstanceState?.getBoolean(AWAITING_APP_INFO) ?: false
        returnedFromApplicationInfo = savedInstanceState?.getBoolean(RETURNED_FROM_APP_INFO) ?: false
        awaitingPermissionReturn = savedInstanceState?.getString(AWAITING_PERMISSION_RETURN).orEmpty()
        permissionReturnHint = savedInstanceState?.getString(PERMISSION_RETURN_HINT).orEmpty()
        handlePairingIntent(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
        render()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(NexusMobileService.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(this, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        if (awaitingApplicationInfoReturn) {
            awaitingApplicationInfoReturn = false
            returnedFromApplicationInfo = true
        }
        if (awaitingPermissionReturn.isNotEmpty()) {
            permissionReturnHint = when {
                awaitingPermissionReturn == "accessibility" && !isAccessibilityEnabled() ->
                    getString(R.string.permission_guide_accessibility_return)
                awaitingPermissionReturn == "notifications" && !notificationsEnabled() ->
                    getString(R.string.permission_guide_notification_return)
                else -> ""
            }
            awaitingPermissionReturn = ""
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(AWAITING_APP_INFO, awaitingApplicationInfoReturn)
        outState.putBoolean(RETURNED_FROM_APP_INFO, returnedFromApplicationInfo)
        outState.putString(AWAITING_PERMISSION_RETURN, awaitingPermissionReturn)
        outState.putString(PERMISSION_RETURN_HINT, permissionReturnHint)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun render() {
        val scrollPosition = screenScroll?.scrollY ?: 0
        val focusedTag = currentFocus?.tag
        val config = MobileConfigStore.load(this)
        val runtime = MobileConfigStore.loadRuntime(this)
        val accessibilityReady = isAccessibilityEnabled()
        if (runtime.state == SyncState.ONLINE && pairingMessage == getString(R.string.home_pairing_confirmed)) {
            pairingMessage = ""
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(text("Nexus Mobile", 26f, Color.rgb(23, 32, 51), Typeface.BOLD).apply {
                if (Build.VERSION.SDK_INT >= 28) setAccessibilityHeading(true)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ghostButton("⋮") { showHomeMenu(it) }.apply {
                textSize = 26f
                contentDescription = getString(R.string.home_more)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        content.addView(text(getString(R.string.home_subtitle), 14f, Color.rgb(95, 107, 122), Typeface.NORMAL))
        content.addView(space(22))

        pendingPairing?.let { content.addView(pairingConfirmationCard(it)) }

        if (pairingMessage.isNotBlank()) {
            content.addView(messageCard(pairingMessage))
        }

        if (pendingPairing == null) {
            content.addView(if (config.isConfigured()) connectionCard(config, runtime) else notPairedCard())
            content.addView(space(14))
            content.addView(permissionCard(accessibilityReady))
            if (MobileVideoSharing.activeId != null) {
                content.addView(primaryButton(getString(R.string.video_stop)) { MobileVideoSharing.stop(this); render() }.withTopMargin(14))
            } else if (MobileVideoSharing.pending != null) {
                content.addView(card {
                    addView(sectionTitle(getString(R.string.video_request)))
                    addView(body(getString(R.string.video_consent)))
                    addView(primaryButton(getString(R.string.video_share)) {
                        startActivity(Intent(this@MainActivity, MobileScreenShareActivity::class.java))
                    }.withTopMargin(12))
                }.withTopMargin(14))
            }
        }
        // Keep the existing debug acceptance hook, but out of the primary workflow.
        if (BuildConfig.DEBUG && pendingPairing == null) {
            content.addView(ghostButton("Open Mobile E2E Surface") {
                startActivity(Intent().setClassName(packageName, "$packageName.MobileE2EActivity"))
            }.withTopMargin(16))
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(244, 247, 251))
            addView(content)
        }
        screenScroll = scroll
        setContentView(scroll)
        // Heartbeat/status refreshes must not move the user away from the current step.
        scroll.post {
            if (screenScroll === scroll) {
                focusedTag?.let { scroll.findViewWithTag<View>(it)?.requestFocus() }
                scroll.scrollTo(0, scrollPosition)
            }
        }
    }

    private fun notPairedCard(): View = card {
        addView(sectionTitle(getString(R.string.home_pair_title)))
        addView(body(getString(R.string.home_pair_body)))
        addView(
            primaryButton(getString(R.string.qr_scan)) {
                startQrScan()
            }.withTopMargin(16),
        )
    }

    private fun pairingConfirmationCard(payload: PairingPayload): View = card(
        borderColor = Color.rgb(23, 105, 224),
        backgroundColor = Color.rgb(239, 246, 255),
    ) {
        addView(sectionTitle(getString(R.string.home_confirm_pairing)))
        addView(body(getString(R.string.home_confirm_body)))
        addView(fact(getString(R.string.home_server), payload.config.serverLabel()).withTopMargin(8))
        addView(fact(getString(R.string.home_device), compactId(payload.config.deviceId)))
        if (payload.warning.isNotBlank()) {
            addView(
                text(payload.warning, 14f, Color.rgb(154, 91, 0), Typeface.BOLD)
                    .withTopMargin(10),
            )
        }
        addView(
            primaryButton(getString(R.string.home_pair_action)) {
                confirmPairing(payload)
            }.withTopMargin(16),
        )
        addView(
            ghostButton(getString(R.string.home_cancel)) {
                pendingPairing = null
                pairingMessage = ""
                render()
            }.withTopMargin(8),
        )
    }

    private fun connectionCard(config: MobileConfig, runtime: MobileRuntimeState): View = card {
        val action = MobileHomeAction.forState(true, runtime.state, isAccessibilityEnabled())
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(
                sectionTitle(getString(R.string.home_connection)).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(statusPill(runtime.state))
        }
        addView(row)
        addView(body(statusMessage(if (action == MobileHomeAction.SETUP) SyncState.SETUP_REQUIRED else runtime.state)))
        addView(text(config.serverLabel(), 14f, Color.rgb(23, 32, 51), Typeface.NORMAL).withTopMargin(14))
        addView(text(getString(R.string.home_last_sync, lastSyncLabel(runtime)), 12f,
            Color.rgb(95, 107, 122), Typeface.NORMAL).withTopMargin(4))
        when (action) {
            MobileHomeAction.PAIR -> addView(primaryButton(getString(R.string.qr_scan_new)) { startQrScan() }.withTopMargin(16))
            MobileHomeAction.START -> {
                val start = { NexusMobileService.start(this@MainActivity) }
                val reviewingNotifications = !notificationsEnabled() &&
                    !guidePreferences.getBoolean(NOTIFICATIONS_DEFERRED, false) &&
                    !guidePreferences.getBoolean(GUIDE_DISMISSED, false)
                addView((if (reviewingNotifications) secondaryButton(getString(R.string.home_start), start)
                    else primaryButton(getString(R.string.home_start), start)).withTopMargin(16))
            }
            MobileHomeAction.PAUSE -> addView(secondaryButton(getString(R.string.home_pause)) {
                NexusMobileService.pause(this@MainActivity)
            }.withTopMargin(16))
            MobileHomeAction.SETUP -> Unit // The highlighted permission action directly below is the sole CTA.
        }
    }

    private fun lastSyncLabel(runtime: MobileRuntimeState): String = if (runtime.lastSyncAt > 0) {
        DateUtils.getRelativeTimeSpanString(runtime.lastSyncAt, System.currentTimeMillis(),
            DateUtils.SECOND_IN_MILLIS).toString()
    } else getString(R.string.home_sync_waiting)

    private fun showHomeMenu(anchor: View) {
        val config = MobileConfigStore.load(this)
        val runtime = MobileConfigStore.loadRuntime(this)
        PopupMenu(this, anchor).apply {
            if (config.isConfigured()) {
                menu.add(0, 2, 1, R.string.home_details)
                menu.add(0, 3, 2, R.string.qr_scan_new)
            }
            menu.add(0, 4, 3, R.string.home_privacy)
            if (config.isConfigured()) menu.add(0, 5, 6, R.string.home_remove_pairing)
            if (config.isConfigured() && runtime.state in setOf(SyncState.ONLINE, SyncState.CONNECTING, SyncState.SETUP_REQUIRED)) {
                menu.add(0, 6, 5, R.string.home_pause)
            }
            setOnMenuItemClickListener {
                when (it.itemId) {
                    2 -> showConnectionDetails(config)
                    3 -> startQrScan()
                    4 -> AlertDialog.Builder(this@MainActivity).setTitle(R.string.home_privacy)
                        .setMessage(R.string.home_privacy_body).setPositiveButton(R.string.home_done, null).showAccessible()
                    5 -> confirmRemovePairing(config)
                    6 -> NexusMobileService.pause(this@MainActivity)
                }
                true
            }
        }.show()
    }

    private fun showConnectionDetails(config: MobileConfig) {
        val runtime = MobileConfigStore.loadRuntime(this)
        AlertDialog.Builder(this).setTitle(R.string.home_details)
            .setMessage(getString(R.string.home_details_body, config.serverLabel(), compactId(config.deviceId),
                lastSyncLabel(runtime), runtime.message.ifBlank { statusMessage(runtime.state) }))
            .setPositiveButton(R.string.home_done, null).showAccessible()
    }

    private fun permissionCard(accessibilityReady: Boolean): View = card {
        val setup = AccessibilitySetupState.forDevice(
            Build.VERSION.SDK_INT, accessibilityReady, returnedFromApplicationInfo,
        )
        val guide = PermissionGuideState.fromPermissions(
            accessibilityReady, notificationsEnabled(), guidePreferences.getBoolean(NOTIFICATIONS_DEFERRED, false),
        )
        val guiding = !guidePreferences.getBoolean(GUIDE_DISMISSED, false)
        addView(sectionTitle(getString(R.string.accessibility_permissions_title)))
        if (!guiding || guide.step == PermissionGuideStep.FINISHED || guide.step == PermissionGuideStep.ACCESSIBILITY) {
            addView(guideStepRow(1, getString(R.string.accessibility_control_title), guide.accessibilityReady,
                guiding && guide.step == PermissionGuideStep.ACCESSIBILITY,
                getString(if (guide.accessibilityReady) R.string.accessibility_ready else R.string.accessibility_required)).withTopMargin(12))
        }
        if (!guiding || guide.step != PermissionGuideStep.ACCESSIBILITY) {
            addView(guideStepRow(2, getString(R.string.permission_guide_notifications), guide.notificationReady,
                guiding && guide.step == PermissionGuideStep.NOTIFICATIONS,
                getString(when {
                    guide.notificationReady -> R.string.permission_guide_notification_ready
                    guide.notificationsDeferred -> R.string.permission_guide_notification_skipped
                    else -> R.string.permission_guide_notification_optional
                })).withTopMargin(8))
        }
        if (!guiding) {
            addView(secondaryButton(getString(R.string.permission_guide_continue)) { restartPermissionGuide() }.withTopMargin(12))
            return@card
        }
        when (guide.step) {
            PermissionGuideStep.ACCESSIBILITY -> {
                addView(primaryButton(getString(R.string.permission_guide_accessibility_action)) {
                    showAccessibilityWalkthrough()
                }.apply { tag = "permission_guide_action" }.withTopMargin(12))
            }
            PermissionGuideStep.NOTIFICATIONS -> {
                addView(body(getString(R.string.permission_guide_notification_body)).withTopMargin(12))
                addView(primaryButton(getString(R.string.permission_guide_notification_action)) {
                    requestNotificationPermission()
                }.apply { tag = "permission_guide_action" }.withTopMargin(12))
                addView(ghostButton(getString(R.string.permission_guide_skip_notifications)) {
                    guidePreferences.edit().putBoolean(NOTIFICATIONS_DEFERRED, true).apply()
                    permissionReturnHint = ""
                    render()
                }.withTopMargin(8))
            }
            PermissionGuideStep.FINISHED -> {
                addView(body(getString(if (guide.allPermissionsGranted) R.string.permission_guide_done
                    else R.string.permission_guide_done_optional)).withTopMargin(12))
                addView(secondaryButton(getString(R.string.accessibility_review)) {
                    showAccessibilityWalkthrough()
                }.withTopMargin(12))
                if (!guide.notificationReady) {
                    addView(secondaryButton(getString(R.string.permission_guide_notification_action)) {
                        guidePreferences.edit().putBoolean(NOTIFICATIONS_DEFERRED, false).apply()
                        render()
                    }.withTopMargin(8))
                }
            }
        }
        if (permissionReturnHint.isNotBlank() && guide.step != PermissionGuideStep.FINISHED) {
            addView(body(permissionReturnHint).withTopMargin(12))
        }
        if (setup.showReturnHint) {
            addView(body(getString(R.string.accessibility_return_hint)).withTopMargin(12))
        }
        if (setup.showRestrictedSettingsHelp) {
            addView(
                ghostButton(getString(R.string.accessibility_restricted_help)) {
                    showRestrictedSettingsGuide()
                }.withTopMargin(8),
            )
        }
        if (accessibilityNavigationError.isNotBlank()) {
            addView(body(accessibilityNavigationError).withTopMargin(12))
        }
        if (guide.step != PermissionGuideStep.FINISHED) {
            addView(ghostButton(getString(R.string.permission_guide_later)) {
                guidePreferences.edit().putBoolean(GUIDE_DISMISSED, true).apply()
                render()
            }.withTopMargin(12))
        }
    }

    private fun restartPermissionGuide() {
        guidePreferences.edit().putBoolean(GUIDE_DISMISSED, false).putBoolean(NOTIFICATIONS_DEFERRED, false).apply()
        permissionReturnHint = ""
        render()
    }

    private fun guideStepRow(number: Int, title: String, ready: Boolean, current: Boolean, detail: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(if (current) Color.rgb(239, 246, 255) else Color.rgb(247, 249, 252),
                if (current) Color.rgb(23, 105, 224) else Color.rgb(217, 225, 236), if (current) 2 else 1, 12)
            if (current) addView(text(getString(R.string.permission_guide_current, number), 12f,
                Color.rgb(23, 105, 224), Typeface.BOLD).withBottomMargin(8))
            addView(permissionRow("$number. $title", ready, detail))
        }

    private fun confirmPairing(payload: PairingPayload) {
        runCatching {
            MobileConfigStore.save(this, payload.config)
            MobileConfigStore.updateRuntime(
                this,
                MobileRuntimeState(SyncState.CONNECTING, "Pairing confirmed. Checking permissions."),
            )
        }.onFailure {
            pairingMessage = getString(R.string.home_pairing_save_failed)
            render()
            return
        }
        pendingPairing = null
        pairingMessage = getString(R.string.home_pairing_confirmed)
        guidePreferences.edit().putBoolean(GUIDE_DISMISSED, false).apply()
        NexusMobileService.start(this)
        render()
    }

    private fun confirmRemovePairing(config: MobileConfig) {
        AlertDialog.Builder(this)
            .setTitle(R.string.home_remove_title)
            .setMessage(getString(R.string.home_remove_body, config.serverLabel()))
            .setNegativeButton(R.string.home_cancel, null)
            .setPositiveButton(R.string.home_remove_pairing) { _, _ ->
                NexusMobileService.unpair(this)
                pairingMessage = ""
                render()
            }
            .showAccessible()
    }

    @Suppress("DEPRECATION") // This platform Activity keeps the existing result/confirmation boundary.
    private fun startQrScan() {
        pairingMessage = ""
        try {
            startActivityForResult(Intent(this, PairingQrScanActivity::class.java), REQUEST_PAIRING_QR)
        } catch (_: ActivityNotFoundException) {
            pairingMessage = getString(R.string.qr_scanner_unavailable)
            render()
        } catch (_: SecurityException) {
            pairingMessage = getString(R.string.qr_scanner_unavailable)
            render()
        }
    }

    @Deprecated("Platform Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PAIRING_QR) return
        val value = data?.getStringExtra(PairingQrScanActivity.EXTRA_PAIRING_QR)
        if (resultCode == RESULT_OK && !value.isNullOrBlank()) {
            // Scanning only proposes a pairing. Saving/connecting still requires confirmation.
            handlePairingValue(value)
        } else {
            pairingMessage = getString(R.string.qr_canceled)
        }
        render()
    }

    private fun handlePairingIntent(intent: Intent?) {
        val value = intent?.dataString ?: return
        handlePairingValue(value)
        intent?.data = null
    }

    private fun handlePairingValue(value: String) {
        runCatching {
            PairingParser.parse(
                rawValue = value,
                allowInsecureLocalhost = BuildConfig.DEBUG,
            )
        }.onSuccess {
            pendingPairing = it
            pairingMessage = ""
        }.onFailure {
            pendingPairing = null
            pairingMessage = it.message ?: "This pairing QR is invalid."
        }
    }

    private fun notificationsEnabled(): Boolean {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
        // A per-channel block is also a real denial, even if the app-wide grant is on.
        val channel = getSystemService(NotificationManager::class.java)
            .getNotificationChannel(NexusMobileService.CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun requestNotificationPermission() {
        if (notificationsEnabled()) {
            render()
            return
        }
        permissionReturnHint = ""
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!guidePreferences.getBoolean(NOTIFICATIONS_REQUESTED, false) ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        ) {
            guidePreferences.edit().putBoolean(NOTIFICATIONS_REQUESTED, true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                })
                awaitingPermissionReturn = "notifications"
            } catch (_: ActivityNotFoundException) {
                permissionReturnHint = getString(R.string.permission_guide_notification_settings_unavailable)
                render()
            } catch (_: SecurityException) {
                permissionReturnHint = getString(R.string.permission_guide_notification_settings_unavailable)
                render()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        permissionReturnHint = if (notificationsEnabled()) "" else
            getString(R.string.permission_guide_notification_return)
        render()
    }

    private fun showAccessibilityWalkthrough() {
        val instructions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
            addView(body(getString(R.string.accessibility_permissions_body)))
            // An in-app schematic, not an overlay on Android's authorization screen.
            for ((index, label) in listOf(
                R.string.permission_guide_settings_step,
                R.string.permission_guide_service_step,
                R.string.permission_guide_enable_step,
            ).withIndex()) {
                addView(text("${index + 1}  ${getString(label)}", 15f,
                    Color.rgb(23, 32, 51), Typeface.BOLD).apply {
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = roundedBackground(Color.rgb(239, 246, 255), Color.rgb(185, 212, 248), 1, 10)
                }.withTopMargin(8))
            }
            addView(body(getString(R.string.permission_guide_settings_return)).withTopMargin(12))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_guide_accessibility_title)
            .setView(ScrollView(this).apply { addView(instructions) })
            .setNegativeButton(R.string.accessibility_not_now, null)
            .setPositiveButton(R.string.permission_guide_open_settings) { _, _ -> openAccessibilitySettings() }
            .showAccessible()
    }

    private fun showRestrictedSettingsGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.accessibility_restricted_title)
            .setMessage(
                getString(R.string.accessibility_restricted_steps) + "\n\n" +
                    getString(R.string.accessibility_restricted_security) + "\n\n" +
                    getString(R.string.accessibility_restricted_missing),
            )
            .setNegativeButton(R.string.accessibility_not_now, null)
            .setPositiveButton(R.string.accessibility_open_app_info) { _, _ -> openApplicationInfo() }
            .showAccessible()
    }

    private fun openApplicationInfo() {
        accessibilityNavigationError = ""
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                },
            )
            awaitingApplicationInfoReturn = true
        } catch (_: ActivityNotFoundException) {
            accessibilityNavigationError = getString(R.string.accessibility_app_info_unavailable)
            render()
        } catch (_: SecurityException) {
            accessibilityNavigationError = getString(R.string.accessibility_app_info_unavailable)
            render()
        }
    }

    private fun openAccessibilitySettings() {
        accessibilityNavigationError = ""
        permissionReturnHint = ""
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            awaitingPermissionReturn = "accessibility"
        } catch (_: ActivityNotFoundException) {
            accessibilityNavigationError = getString(R.string.accessibility_settings_unavailable)
            render()
        } catch (_: SecurityException) {
            accessibilityNavigationError = getString(R.string.accessibility_settings_unavailable)
            render()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (NexusAccessibilityServiceHolder.service != null) return true
        val expected = ComponentName(this, NexusAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun card(
        borderColor: Int = Color.rgb(217, 225, 236),
        backgroundColor: Int = Color.WHITE,
        content: LinearLayout.() -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = roundedBackground(backgroundColor, borderColor, 1, 16)
        content()
    }

    private fun sectionTitle(value: String): TextView =
        text(value, 18f, Color.rgb(23, 32, 51), Typeface.BOLD).apply {
            if (Build.VERSION.SDK_INT >= 28) setAccessibilityHeading(true)
        }

    private fun body(value: String): TextView =
        text(value, 15f, Color.rgb(95, 107, 122), Typeface.NORMAL).apply {
            setLineSpacing(0f, 1.2f)
        }.withTopMargin(6) as TextView

    private fun fact(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(4))
        addView(text(label.uppercase(), 11f, Color.rgb(95, 107, 122), Typeface.BOLD))
        addView(text(value, 15f, Color.rgb(23, 32, 51), Typeface.NORMAL).withTopMargin(2))
    }

    private fun permissionRow(title: String, ready: Boolean, detail: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.TOP
            val indicator = TextView(this@MainActivity).apply {
                text = if (ready) "✓" else "!"
                gravity = android.view.Gravity.CENTER
                textSize = 15f
                setTextColor(Color.WHITE)
                background = roundedBackground(
                    if (ready) Color.rgb(19, 122, 85) else Color.rgb(154, 91, 0),
                    Color.TRANSPARENT,
                    0,
                    20,
                )
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            }
            addView(indicator)
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    addView(text(title, 15f, Color.rgb(23, 32, 51), Typeface.BOLD))
                    addView(text(detail, 13f, Color.rgb(95, 107, 122), Typeface.NORMAL).withTopMargin(2))
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

    private fun statusPill(state: SyncState): TextView {
        val (label, backgroundColor, foreground) = when (state) {
            SyncState.ONLINE -> Triple(getString(R.string.home_status_online), Color.rgb(232, 248, 240), Color.rgb(19, 122, 85))
            SyncState.SETUP_REQUIRED -> Triple(getString(R.string.home_status_setup), Color.rgb(255, 246, 224), Color.rgb(154, 91, 0))
            SyncState.OFFLINE -> Triple(getString(R.string.home_status_offline), Color.rgb(255, 246, 224), Color.rgb(154, 91, 0))
            SyncState.PAIRING_EXPIRED, SyncState.AUTH_FAILED ->
                Triple(getString(R.string.home_status_pair), Color.rgb(255, 235, 232), Color.rgb(180, 35, 24))
            SyncState.STOPPED -> Triple(getString(R.string.home_status_paused), Color.rgb(238, 242, 247), Color.rgb(95, 107, 122))
            else -> Triple(getString(R.string.home_status_connecting), Color.rgb(239, 246, 255), Color.rgb(23, 105, 224))
        }
        return text(label, 12f, foreground, Typeface.BOLD).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(backgroundColor, Color.TRANSPARENT, 0, 20)
        }
    }

    private fun messageCard(message: String): View =
        text(message, 14f, Color.rgb(154, 91, 0), Typeface.BOLD).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(
                Color.rgb(255, 246, 224),
                Color.rgb(234, 190, 105),
                1,
                12,
            )
        }.withBottomMargin(14)

    private fun primaryButton(label: String, action: () -> Unit): Button =
        actionButton(label, Color.rgb(23, 105, 224), Color.WHITE, action)

    private fun secondaryButton(label: String, action: () -> Unit): Button =
        actionButton(label, Color.WHITE, Color.rgb(23, 32, 51), action, Color.rgb(217, 225, 236))

    private fun ghostButton(label: String, action: (View) -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        minHeight = dp(48)
        setTextColor(Color.rgb(95, 107, 122))
        background = roundedBackground(Color.TRANSPARENT, Color.TRANSPARENT, 0, 10)
        setOnClickListener { action(it) }
    }

    private fun AlertDialog.Builder.showAccessible(): AlertDialog = show().also { dialog ->
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE)) {
            dialog.getButton(which)?.apply {
                minHeight = dp(48)
                isAllCaps = false
            }
        }
    }

    private fun actionButton(
        label: String,
        backgroundColor: Int,
        foregroundColor: Int,
        action: () -> Unit,
        borderColor: Int = backgroundColor,
    ): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(foregroundColor)
        typeface = Typeface.DEFAULT_BOLD
        minHeight = dp(48)
        stateListAnimator = null
        background = roundedBackground(backgroundColor, borderColor, 1, 10)
        setOnClickListener { action() }
    }

    private fun text(value: String, size: Float, color: Int, style: Int): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, style)
        }

    private fun roundedBackground(
        fillColor: Int,
        strokeColor: Int,
        strokeWidthDp: Int,
        radiusDp: Int,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fillColor)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeWidthDp > 0) setStroke(dp(strokeWidthDp), strokeColor)
    }

    private fun statusMessage(state: SyncState): String = when (state) {
        SyncState.ONLINE -> getString(R.string.home_online_message)
        SyncState.SETUP_REQUIRED -> getString(R.string.home_setup_message)
        SyncState.CONNECTING -> getString(R.string.home_connecting_message)
        SyncState.OFFLINE -> getString(R.string.home_offline_message)
        SyncState.PAIRING_EXPIRED, SyncState.AUTH_FAILED -> getString(R.string.home_pairing_expired_message)
        SyncState.STOPPED -> getString(R.string.home_paused_message)
        SyncState.NOT_PAIRED -> getString(R.string.home_pair_body)
    }

    private fun compactId(value: String): String =
        if (value.length <= 16) value else "${value.take(8)}…${value.takeLast(6)}"

    private fun space(heightDp: Int): Space = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun <T : View> T.withTopMargin(valueDp: Int): T {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(valueDp) }
        return this
    }

    private fun <T : View> T.withBottomMargin(valueDp: Int): T {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(valueDp) }
        return this
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1001
        private const val REQUEST_PAIRING_QR = 1002
        private const val AWAITING_APP_INFO = "accessibility.awaiting_app_info"
        private const val RETURNED_FROM_APP_INFO = "accessibility.returned_from_app_info"
        private const val AWAITING_PERMISSION_RETURN = "permission.awaiting_return"
        private const val PERMISSION_RETURN_HINT = "permission.return_hint"
        private const val GUIDE_DISMISSED = "guide_dismissed"
        private const val NOTIFICATIONS_DEFERRED = "notifications_deferred"
        private const val NOTIFICATIONS_REQUESTED = "notifications_requested"
    }
}
