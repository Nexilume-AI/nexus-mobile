package com.nexus.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class NexusMobileService : Service() {
    private val running = AtomicBoolean(false)
    private val apiClient = NexusApiClient()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification(SyncState.CONNECTING, "Preparing secure sync"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                disconnect(clearPairing = false)
                return START_NOT_STICKY
            }
            ACTION_UNPAIR -> {
                disconnect(clearPairing = true)
                return START_NOT_STICKY
            }
        }
        if (running.compareAndSet(false, true)) {
            thread(name = "nexus-mobile-sync") { syncLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        MobileVideoSharing.stop(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun syncLoop() {
        var lastHeartbeatAt = 0L
        var lastReady: Boolean? = null
        var retryDelayMs = COMMAND_POLL_INTERVAL_MS
        var pendingResult: MobileResultOutbox.Pending? = null
        while (running.get()) {
            val config = MobileConfigStore.load(this)
            if (!config.isConfigured()) {
                updateState(SyncState.NOT_PAIRED, "Scan a Nexus pairing QR to connect.")
                stopServiceLoop()
                return
            }
            if (config.isPairingExpired() && MobileConfigStore.loadRuntime(this).lastSyncAt == 0L) {
                updateState(SyncState.PAIRING_EXPIRED, "Pairing QR expired. Generate a new QR in Nexus Console.")
                stopServiceLoop()
                return
            }
            val accessibility = NexusAccessibilityServiceHolder.service
            val ready = accessibility != null
            val observation = accessibility?.observeBlocking() ?: NexusAccessibilityService.lastObservation
            try {
                if (pendingResult?.scope != MobileResultOutbox.scope(config)) pendingResult = null
                pendingResult = pendingResult ?: MobileResultOutbox.load(this, config)
                pendingResult?.let { pending ->
                    // Persist before sending. On disk or transport failure retain
                    // the in-memory result too, and do not execute another action.
                    MobileResultOutbox.save(this, config, pending)
                    if (!handleResponse(apiClient.reportResultBody(config, pending.commandId, pending.body))) return
                    MobileResultOutbox.acknowledge(this, config, pending)
                    pendingResult = null
                }
                val now = System.currentTimeMillis()
                if (now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS || lastReady != ready) {
                    if (ready) {
                        if (MobileConfigStore.loadRuntime(this).state != SyncState.ONLINE) {
                            updateState(SyncState.CONNECTING, "Connecting securely to ${config.serverLabel()}")
                        }
                    } else {
                        updateState(SyncState.SETUP_REQUIRED, "Enable Nexus Mobile Control to finish setup.")
                    }
                    val heartbeat = apiClient.heartbeat(
                        config,
                        NexusJson.encodeHeartbeat(
                            observation = observation,
                            accessibilityReady = ready,
                            appVersion = BuildConfig.VERSION_NAME,
                            sdkVersion = Build.VERSION.SDK_INT,
                            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                        ),
                    )
                    if (!handleResponse(heartbeat)) return
                    lastHeartbeatAt = now
                    lastReady = ready
                }

                if (!ready) {
                    updateState(SyncState.SETUP_REQUIRED, "Enable Nexus Mobile Control to finish setup.")
                    retryDelayMs = COMMAND_POLL_INTERVAL_MS
                    sleepWhileRunning(SETUP_POLL_INTERVAL_MS)
                    continue
                }

                val next = apiClient.nextCommand(config)
                if (next.errorCode == "MOBILE_DEVICE_NOT_READY") {
                    updateState(SyncState.SETUP_REQUIRED, "Enable Nexus Mobile Control to finish setup.")
                    sleepWhileRunning(SETUP_POLL_INTERVAL_MS)
                    continue
                }
                if (!handleResponse(next)) return
                val video = apiClient.videoPoll(config)
                if (!handleResponse(video)) return
                MobileVideoSharing.updatePending(this, video.data?.optJSONObject("session"))
                val command = NexusJson.parseCommand(next)
                if (command != null) {
                    val result = MobileCommandExecutor.execute(this, command)
                    val pending = MobileResultOutbox.pending(config, command.id, result)
                    pendingResult = pending
                    MobileResultOutbox.save(this, config, pending)
                    val reported = apiClient.reportResultBody(config, pending.commandId, pending.body)
                    if (!handleResponse(reported)) return
                    MobileResultOutbox.acknowledge(this, config, pending)
                    pendingResult = null
                }
                updateState(SyncState.ONLINE, "Protected actions are ready.", System.currentTimeMillis())
                retryDelayMs = COMMAND_POLL_INTERVAL_MS
                sleepWhileRunning(COMMAND_POLL_INTERVAL_MS)
            } catch (error: Exception) {
                val safeMessage = when (error) {
                    is java.net.SocketTimeoutException -> "Nexus did not respond before timeout."
                    is java.net.UnknownHostException -> "The Nexus server could not be found."
                    is javax.net.ssl.SSLException -> "The secure connection to Nexus failed."
                    else -> "Nexus is temporarily unreachable."
                }
                updateState(SyncState.OFFLINE, safeMessage)
                sleepWhileRunning(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }
    }

    private fun handleResponse(response: MobileApiResponse): Boolean {
        if (response.isSuccessful) return true
        when {
            response.errorCode == "MOBILE_PAIRING_TOKEN_EXPIRED" -> {
                updateState(SyncState.PAIRING_EXPIRED, "Pairing expired. Generate and scan a new QR.")
                stopServiceLoop()
            }
            response.statusCode == 401 || response.statusCode == 403 -> {
                updateState(SyncState.AUTH_FAILED, "This pairing is no longer valid. Re-pair the device.")
                stopServiceLoop()
            }
            else -> throw MobileApiException(
                response.errorMessage.ifBlank { "Nexus returned HTTP ${response.statusCode}." },
            )
        }
        return false
    }

    private fun disconnect(clearPairing: Boolean) {
        MobileVideoSharing.stop(this)
        if (!running.compareAndSet(true, false)) running.set(false)
        val config = MobileConfigStore.load(this)
        thread(name = "nexus-mobile-disconnect") {
            if (config.isConfigured()) {
                runCatching {
                    apiClient.disconnect(
                        config,
                        NexusAccessibilityServiceHolder.service?.observeBlocking()
                            ?: NexusAccessibilityService.lastObservation,
                    )
                }
            }
            if (clearPairing) {
                MobileConfigStore.clear(this)
                updateState(SyncState.NOT_PAIRED, "Pairing removed.")
            } else {
                updateState(SyncState.STOPPED, "Sync is paused on this phone.")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateState(
        state: SyncState,
        message: String,
        lastSyncAt: Long = MobileConfigStore.loadRuntime(this).lastSyncAt,
    ) {
        val previous = MobileConfigStore.loadRuntime(this)
        val changed = previous.state != state || previous.message != message
        MobileConfigStore.updateRuntime(this, MobileRuntimeState(state, message, lastSyncAt))
        if (changed) {
            sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        }
        if (changed || state == SyncState.OFFLINE) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(state, message))
        }
    }

    private fun stopServiceLoop() {
        MobileVideoSharing.stop(this)
        running.set(false)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun sleepWhileRunning(durationMs: Long) {
        var remaining = durationMs
        while (running.get() && remaining > 0) {
            val slice = remaining.coerceAtMost(500)
            Thread.sleep(slice)
            remaining -= slice
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Nexus Mobile connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows when protected Nexus device control is active."
            },
        )
    }

    private fun notification(state: SyncState, message: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NexusMobileService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationTitle(state))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_nexus_notification)
            .setContentIntent(openIntent)
            .setOngoing(state in setOf(SyncState.CONNECTING, SyncState.ONLINE, SyncState.SETUP_REQUIRED))
            .addAction(android.R.drawable.ic_media_pause, "Pause", stopIntent)
            .build()
    }

    private fun notificationTitle(state: SyncState): String = when (state) {
        SyncState.ONLINE -> "Nexus Mobile is online"
        SyncState.SETUP_REQUIRED -> "Nexus Mobile needs permission"
        SyncState.OFFLINE -> "Nexus Mobile is offline"
        SyncState.PAIRING_EXPIRED, SyncState.AUTH_FAILED -> "Nexus Mobile needs re-pairing"
        SyncState.STOPPED -> "Nexus Mobile is paused"
        else -> "Nexus Mobile is connecting"
    }

    companion object {
        const val ACTION_STATE_CHANGED = "com.nexus.mobile.STATE_CHANGED"
        private const val ACTION_STOP = "com.nexus.mobile.STOP_SYNC"
        private const val ACTION_UNPAIR = "com.nexus.mobile.UNPAIR"
        internal const val CHANNEL_ID = "nexus_mobile_sync"
        private const val NOTIFICATION_ID = 7
        private const val COMMAND_POLL_INTERVAL_MS = 2_000L
        private const val SETUP_POLL_INTERVAL_MS = 5_000L
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, NexusMobileService::class.java))
        }

        fun pause(context: Context) {
            context.startService(
                Intent(context, NexusMobileService::class.java).setAction(ACTION_STOP),
            )
        }

        fun unpair(context: Context) {
            context.startForegroundService(
                Intent(context, NexusMobileService::class.java).setAction(ACTION_UNPAIR),
            )
        }

        fun requestRefresh(context: Context) {
            context.sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(context.packageName))
        }
    }
}

private class MobileApiException(message: String) : RuntimeException(message)
