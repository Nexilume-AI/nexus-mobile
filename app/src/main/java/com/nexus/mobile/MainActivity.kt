package com.nexus.mobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode

class MainActivity : Activity() {
    private var pairingMessage: String = ""
    private var pendingPairing: PairingPayload? = null
    private var receiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            render()
        }
    }

    private val scanner by lazy {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        render()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun render() {
        val config = MobileConfigStore.load(this)
        val runtime = MobileConfigStore.loadRuntime(this)
        val accessibilityReady = isAccessibilityEnabled()
        if (runtime.state == SyncState.ONLINE && pairingMessage.startsWith("Pairing confirmed")) {
            pairingMessage = ""
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        content.addView(text("Nexus Mobile", 26f, Color.rgb(23, 32, 51), Typeface.BOLD))
        content.addView(
            text(
                "Secure Android control for your Nexus workspace",
                15f,
                Color.rgb(95, 107, 122),
                Typeface.NORMAL,
            ).withTopMargin(4),
        )
        if (BuildConfig.DEBUG && pendingPairing == null) {
            content.addView(
                secondaryButton("Open Mobile E2E Surface") {
                    startActivity(
                        Intent().setClassName(
                            packageName,
                            "$packageName.MobileE2EActivity",
                        ),
                    )
                }.withTopMargin(14),
            )
        }
        content.addView(space(22))

        pendingPairing?.let { content.addView(pairingConfirmationCard(it)) }

        if (pairingMessage.isNotBlank()) {
            content.addView(messageCard(pairingMessage))
        }

        if (!config.isConfigured()) {
            content.addView(notPairedCard())
            content.addView(space(14))
            content.addView(permissionCard(accessibilityReady))
        } else {
            content.addView(connectionCard(config, runtime))
            content.addView(space(14))
            content.addView(permissionCard(accessibilityReady))
            content.addView(space(14))
            content.addView(controlCard(config, runtime, accessibilityReady))
            content.addView(space(14))
            content.addView(privacyCard())
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(244, 247, 251))
            addView(content)
        }
        setContentView(scroll)
    }

    private fun notPairedCard(): View = card {
        addView(sectionTitle("Connect this Android device"))
        addView(
            body(
                "Create a Mobile device in Nexus Console, then scan its secure pairing QR. " +
                    "The token stays encrypted on this phone.",
            ),
        )
        addView(
            primaryButton("Scan pairing QR") {
                startQrScan()
            }.withTopMargin(16),
        )
    }

    private fun pairingConfirmationCard(payload: PairingPayload): View = card(
        borderColor = Color.rgb(23, 105, 224),
        backgroundColor = Color.rgb(239, 246, 255),
    ) {
        addView(sectionTitle("Confirm Nexus pairing"))
        addView(body("Only continue if you recognize this Nexus server."))
        addView(fact("Server", payload.config.serverLabel()).withTopMargin(14))
        addView(fact("Device", compactId(payload.config.deviceId)))
        if (payload.warning.isNotBlank()) {
            addView(
                text(payload.warning, 14f, Color.rgb(154, 91, 0), Typeface.BOLD)
                    .withTopMargin(10),
            )
        }
        addView(
            primaryButton("Confirm and connect") {
                confirmPairing(payload)
            }.withTopMargin(16),
        )
        addView(
            secondaryButton("Cancel") {
                pendingPairing = null
                pairingMessage = "Pairing canceled."
                render()
            }.withTopMargin(8),
        )
    }

    private fun connectionCard(config: MobileConfig, runtime: MobileRuntimeState): View = card {
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(
                sectionTitle("Connection").apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(statusPill(runtime.state))
        }
        addView(row)
        addView(body(runtime.message.ifBlank { statusMessage(runtime.state) }).withTopMargin(10))
        addView(fact("Nexus server", config.serverLabel()).withTopMargin(14))
        addView(fact("Device", compactId(config.deviceId)))
        addView(
            fact(
                "Last secure sync",
                if (runtime.lastSyncAt > 0) {
                    DateUtils.getRelativeTimeSpanString(
                        runtime.lastSyncAt,
                        System.currentTimeMillis(),
                        DateUtils.SECOND_IN_MILLIS,
                    ).toString()
                } else {
                    "Waiting for first ready heartbeat"
                },
            ),
        )
    }

    private fun permissionCard(accessibilityReady: Boolean): View = card {
        addView(sectionTitle("Permissions"))
        addView(body("Nexus fetches actions only after Accessibility control is enabled."))
        addView(
            permissionRow(
                "Accessibility control",
                accessibilityReady,
                if (accessibilityReady) "Ready for protected actions" else "Required before actions can run",
            ).withTopMargin(14),
        )
        addView(
            secondaryButton(if (accessibilityReady) "Review Accessibility settings" else "Enable Accessibility control") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.withTopMargin(12),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationReady =
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            addView(
                permissionRow(
                    "Connection notification",
                    notificationReady,
                    if (notificationReady) "Visible while sync is active" else "Recommended for clear status",
                ).withTopMargin(14),
            )
            if (!notificationReady) {
                addView(
                    secondaryButton("Allow notifications") {
                        requestNotificationPermission()
                    }.withTopMargin(12),
                )
            }
        }
    }

    private fun controlCard(
        config: MobileConfig,
        runtime: MobileRuntimeState,
        accessibilityReady: Boolean,
    ): View = card {
        addView(sectionTitle("Connection controls"))
        addView(
            body(
                if (runtime.state == SyncState.ONLINE) {
                    "Pause sync before handing the phone to another person or entering sensitive information."
                } else {
                    "Start sync after reviewing the server and Android permissions."
                },
            ),
        )
        if (runtime.state in setOf(SyncState.ONLINE, SyncState.CONNECTING, SyncState.SETUP_REQUIRED)) {
            addView(
                secondaryButton("Pause Nexus sync") {
                    NexusMobileService.pause(this@MainActivity)
                    pairingMessage = "Sync pause requested."
                    render()
                }.withTopMargin(16),
            )
        } else {
            addView(
                primaryButton(if (accessibilityReady) "Start Nexus sync" else "Continue device setup") {
                    if (!accessibilityReady) {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } else {
                        requestNotificationPermission()
                        NexusMobileService.start(this@MainActivity)
                    }
                }.withTopMargin(16),
            )
        }
        addView(
            secondaryButton("Scan a new pairing QR") {
                startQrScan()
            }.withTopMargin(8),
        )
        addView(
            dangerButton("Remove pairing") {
                confirmRemovePairing(config)
            }.withTopMargin(8),
        )
    }

    private fun privacyCard(): View = card {
        addView(sectionTitle("Privacy protection"))
        addView(
            body(
                "Passwords, likely verification codes, and payment-card values are redacted before observation upload. " +
                    "Screenshots are captured only when requested from Nexus Console and are blocked on sensitive screens.",
            ),
        )
    }

    private fun confirmPairing(payload: PairingPayload) {
        runCatching {
            MobileConfigStore.save(this, payload.config)
            MobileConfigStore.updateRuntime(
                this,
                MobileRuntimeState(SyncState.CONNECTING, "Pairing confirmed. Checking permissions."),
            )
        }.onFailure {
            pairingMessage = "Pairing could not be stored securely."
            render()
            return
        }
        pendingPairing = null
        pairingMessage = "Pairing confirmed. Complete Android permissions to come online."
        requestNotificationPermission()
        NexusMobileService.start(this)
        render()
    }

    private fun confirmRemovePairing(config: MobileConfig) {
        AlertDialog.Builder(this)
            .setTitle("Remove Nexus pairing?")
            .setMessage(
                "Sync will stop and the encrypted token for ${config.serverLabel()} will be removed from this phone.",
            )
            .setNegativeButton("Keep pairing", null)
            .setPositiveButton("Remove") { _, _ ->
                NexusMobileService.unpair(this)
                pairingMessage = "Removing pairing and stopping sync."
                render()
            }
            .show()
    }

    private fun startQrScan() {
        pairingMessage = ""
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                handlePairingValue(barcode.rawValue.orEmpty())
                render()
            }
            .addOnCanceledListener {
                pairingMessage = "QR scan canceled."
                render()
            }
            .addOnFailureListener {
                pairingMessage = "QR scanner is unavailable. Open the pairing QR with this app instead."
                render()
            }
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

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
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
        text(value, 18f, Color.rgb(23, 32, 51), Typeface.BOLD)

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
            SyncState.ONLINE -> Triple("Online", Color.rgb(232, 248, 240), Color.rgb(19, 122, 85))
            SyncState.SETUP_REQUIRED -> Triple("Setup required", Color.rgb(255, 246, 224), Color.rgb(154, 91, 0))
            SyncState.OFFLINE -> Triple("Offline", Color.rgb(255, 246, 224), Color.rgb(154, 91, 0))
            SyncState.PAIRING_EXPIRED, SyncState.AUTH_FAILED ->
                Triple("Re-pair", Color.rgb(255, 235, 232), Color.rgb(180, 35, 24))
            SyncState.STOPPED -> Triple("Paused", Color.rgb(238, 242, 247), Color.rgb(95, 107, 122))
            else -> Triple("Connecting", Color.rgb(239, 246, 255), Color.rgb(23, 105, 224))
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

    private fun dangerButton(label: String, action: () -> Unit): Button =
        actionButton(label, Color.WHITE, Color.rgb(180, 35, 24), action, Color.rgb(244, 181, 174))

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
        SyncState.ONLINE -> "Protected actions are ready."
        SyncState.SETUP_REQUIRED -> "Enable Nexus Mobile Control to finish setup."
        SyncState.CONNECTING -> "Connecting securely to Nexus."
        SyncState.OFFLINE -> "Nexus is temporarily unreachable."
        SyncState.PAIRING_EXPIRED -> "Generate and scan a new pairing QR."
        SyncState.AUTH_FAILED -> "This pairing is no longer valid."
        SyncState.STOPPED -> "Sync is paused on this phone."
        SyncState.NOT_PAIRED -> "Scan a pairing QR to connect."
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
    }
}
