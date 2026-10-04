package com.nexus.mobile

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import org.json.JSONObject
import org.webrtc.*
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Ephemeral state only. Projection consent and SDP are never written to disk. */
object MobileVideoSharing {
    @Volatile var pending: JSONObject? = null
        private set
    @Volatile var activeId: String? = null
        internal set
    @Volatile var live = false
        internal set

    fun updatePending(context: Context, session: JSONObject?) {
        val next = session?.takeIf { it.optString("state") in setOf("pending", "connecting") && activeId == null }
        val changed = next?.optString("id") != pending?.optString("id")
        pending = next
        if (changed) NexusMobileService.requestRefresh(context)
    }
    fun matchesSession(id: String?) = id != null && id == activeId && live
    fun stop(context: Context) {
        pending = null
        live = false
        context.stopService(Intent(context, MobileScreenShareService::class.java))
        NexusMobileService.requestRefresh(context)
    }
}

/** Only an explicit foreground user action may obtain a new Android projection grant. */
class MobileScreenShareActivity : Activity() {
    private var sessionId: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = MobileVideoSharing.pending?.optString("id")
        if (sessionId.isNullOrBlank() || MobileVideoSharing.activeId != null) { finish(); return }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val request = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(
            MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(request, 71)
    }
    @Deprecated("Android result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = sessionId
        if (requestCode == 71 && id != null && resultCode == RESULT_OK && data != null &&
            MobileVideoSharing.pending?.optString("id") == id) {
            startForegroundService(Intent(this, MobileScreenShareService::class.java)
                .putExtra("session_id", id).putExtra("projection", data))
        } else if (id != null) {
            thread { runCatching { NexusApiClient().videoSignal(MobileConfigStore.load(this), id,
                JSONObject().put("type", "state").put("state", "failed")
                    .put("error_code", "MOBILE_VIDEO_CONSENT_DENIED").put("message_id", UUID.randomUUID().toString())) } }
        }
        finish()
    }
}

class MobileScreenShareService : Service() {
    private val running = AtomicBoolean(false)
    private val outbox = LinkedBlockingQueue<JSONObject>(96)
    private var worker: Thread? = null
    private var servingId: String? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY
        val id = intent?.getStringExtra("session_id")
        @Suppress("DEPRECATION")
        val projection = intent?.getParcelableExtra<Intent>("projection")
        if (id == null || projection == null || MobileVideoSharing.pending?.optString("id") != id) {
            running.set(false); stopSelf(); return START_NOT_STICKY
        }
        val stop = PendingIntent.getService(this, 81, Intent(this, MobileScreenShareService::class.java).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 82, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(81, Notification.Builder(this, NexusMobileService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nexus_notification).setContentTitle(getString(R.string.video_sharing))
            .setContentText(getString(R.string.video_consent)).setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.video_stop), stop).build())
        MobileVideoSharing.activeId = id
        servingId = id
        MobileVideoSharing.live = false
        if (BuildConfig.DEBUG) android.util.Log.i("NexusVideo", "projection service started")
        NexusMobileService.requestRefresh(this)
        worker = thread(name = "nexus-mobile-video") { share(id, projection) }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        running.set(false)
        if (MobileVideoSharing.activeId == servingId) {
            MobileVideoSharing.live = false
            MobileVideoSharing.activeId = null
        }
        NexusMobileService.requestRefresh(this)
        super.onDestroy()
    }
    private fun enqueue(body: JSONObject) {
        body.put("message_id", UUID.randomUUID().toString())
        if (!outbox.offer(body)) running.set(false)
    }
    private fun share(id: String, permission: Intent) {
        val config = MobileConfigStore.load(this)
        val api = NexusApiClient(connectTimeoutMs = 4000, readTimeoutMs = 4000)
        var factory: PeerConnectionFactory? = null
        var peer: PeerConnection? = null
        var egl: EglBase? = null
        var source: VideoSource? = null
        var track: VideoTrack? = null
        var helper: SurfaceTextureHelper? = null
        var capturer: ScreenCapturerAndroid? = null
        var cursor = 0
        val remoteIce = MobileVideoIceQueue<IceCandidate>()
        var failure = ""
        try {
            val initial = api.videoPoll(config).data?.optJSONObject("session")
                ?: error("No active video request")
            check(initial.optString("id") == id)
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
            egl = EglBase.create()
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
            val ice = mutableListOf<PeerConnection.IceServer>()
            val servers = initial.optJSONArray("ice_servers")
            if (servers != null) for (i in 0 until servers.length()) {
                val item = servers.getJSONObject(i)
                val urls = item.getJSONArray("urls")
                ice.add(PeerConnection.IceServer.builder((0 until urls.length()).map { urls.getString(it) })
                    .setUsername(item.optString("username")).setPassword(item.optString("credential")).createIceServer())
            }
            val rtc = PeerConnection.RTCConfiguration(ice).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                if (initial.optString("ice_transport_policy") == "relay") iceTransportsType = PeerConnection.IceTransportsType.RELAY
            }
            val connectivity = MobileVideoConnectivity(android.os.SystemClock.elapsedRealtime())
            peer = factory.createPeerConnection(rtc, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    if (BuildConfig.DEBUG) android.util.Log.i("NexusVideo", "ICE state: ${state.name}")
                    if (MobileVideoSharing.activeId == id) MobileVideoSharing.live = state in setOf(PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED)
                    connectivity.update(state.name, android.os.SystemClock.elapsedRealtime())
                    if (state == PeerConnection.IceConnectionState.FAILED) {
                        failure = "MOBILE_VIDEO_CONNECTION_LOST"; running.set(false)
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidate(candidate: IceCandidate) { enqueue(JSONObject().put("type", "ice")
                    .put("candidate", JSONObject().put("candidate", candidate.sdp).put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex))) }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(channel: DataChannel) { channel.close() }
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
            }) ?: error("Peer unavailable")
            source = factory.createVideoSource(true)
            helper = SurfaceTextureHelper.create("nexus-screen-capture", egl.eglBaseContext)
            val output = source.capturerObserver
            val privacyGate = MobileVideoPrivacyGate()
            capturer = ScreenCapturerAndroid(permission, object : MediaProjection.Callback() {
                override fun onStop() { if (BuildConfig.DEBUG) android.util.Log.i("NexusVideo", "Android stopped projection"); running.set(false) }
            })
            capturer.initialize(helper, this, object : CapturerObserver {
                override fun onCapturerStarted(success: Boolean) { output.onCapturerStarted(success) }
                override fun onCapturerStopped() { output.onCapturerStopped() }
                override fun onFrameCaptured(frame: VideoFrame) {
                    val access = NexusAccessibilityServiceHolder.service
                    if (!running.get()) return
                    if (access == null) {
                        failure = "MOBILE_VIDEO_SCREEN_BLOCKED"; running.set(false); return
                    }
                    when (privacyGate.evaluate(access.sharingPrivacy, android.os.SystemClock.elapsedRealtime())) {
                        MobileVideoPrivacyGate.Decision.SEND -> output.onFrameCaptured(frame)
                        MobileVideoPrivacyGate.Decision.DROP -> Unit
                        MobileVideoPrivacyGate.Decision.STOP -> {
                            if (BuildConfig.DEBUG) android.util.Log.i("NexusVideo", "privacy stop: ${access.sharingPrivacy.name}")
                            failure = "MOBILE_VIDEO_SCREEN_BLOCKED"; running.set(false)
                        }
                    }
                }
            })
            var geometry = NexusAccessibilityServiceHolder.service?.screenGeometry() ?: error("Control unavailable")
            fun format(g: Triple<Int, Int, Int>) {
                val scale = minOf(1.0, 1280.0 / maxOf(g.first, g.second))
                capturer.changeCaptureFormat((g.first * scale).toInt().coerceAtLeast(2), (g.second * scale).toInt().coerceAtLeast(2), 15)
            }
            val scale = minOf(1.0, 1280.0 / maxOf(geometry.first, geometry.second))
            capturer.startCapture((geometry.first * scale).toInt(), (geometry.second * scale).toInt(), 15)
            if (BuildConfig.DEBUG) android.util.Log.i("NexusVideo", "capture initialized")
            track = factory.createVideoTrack("nexus-screen", source)
            peer.addTrack(track, listOf("nexus-mobile"))
            val activePeer = peer
            var lastStateAt = 0L
            var lastPrivacyAt = 0L
            while (running.get()) {
                if (connectivity.expired(android.os.SystemClock.elapsedRealtime())) {
                    failure = "MOBILE_VIDEO_CONNECTION_LOST"; break
                }
                val currentConfig = MobileConfigStore.load(this)
                check(currentConfig.deviceId == config.deviceId && currentConfig.token == config.token)
                while (outbox.isNotEmpty()) {
                    check(api.videoSignal(config, id, outbox.poll() ?: continue).isSuccessful)
                }
                val response = api.videoPoll(config, id, cursor)
                check(response.isSuccessful)
                val session = response.data?.optJSONObject("session") ?: error("Session unavailable")
                if (session.optString("state") in setOf("stopped", "failed", "expired")) break
                val signals = session.optJSONArray("signals")
                if (signals != null) for (i in 0 until signals.length()) {
                    val signal = signals.getJSONObject(i)
                    when (signal.optString("type")) {
                        "offer" -> activePeer.setRemoteDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                if (BuildConfig.DEBUG) android.util.Log.i("NexusVideo", "remote offer applied")
                                // JNI can synchronously wait on this callback's signaling
                                // thread. Drain under lock, then apply candidates outside it.
                                remoteIce.activate().forEach { activePeer.addIceCandidate(it) }
                                activePeer.createAnswer(object : SimpleSdpObserver() {
                                    override fun onCreateSuccess(answer: SessionDescription) {
                                        if (BuildConfig.DEBUG) android.util.Log.i("NexusVideo", "answer created")
                                        activePeer.setLocalDescription(object : SimpleSdpObserver() {
                                            override fun onSetSuccess() {
                                                if (BuildConfig.DEBUG) android.util.Log.i("NexusVideo", "local answer applied")
                                                enqueue(JSONObject().put("type", "answer").put("sdp", answer.description))
                                            }
                                        }, answer)
                                    }
                                }, MediaConstraints())
                            }
                        }, SessionDescription(SessionDescription.Type.OFFER, signal.getString("sdp")))
                        "ice" -> {
                            val c = signal.getJSONObject("candidate")
                            val candidate = IceCandidate(c.optString("sdpMid"), c.getInt("sdpMLineIndex"), c.getString("candidate"))
                            remoteIce.receive(candidate).forEach { activePeer.addIceCandidate(it) }
                        }
                    }
                }
                cursor = session.getInt("sequence")
                val now = System.currentTimeMillis()
                if (now - lastPrivacyAt >= 1000) {
                    NexusAccessibilityServiceHolder.service?.observeBlocking()
                    lastPrivacyAt = now
                }
                val nextGeometry = NexusAccessibilityServiceHolder.service?.screenGeometry() ?: error("Control unavailable")
                if (nextGeometry != geometry) { geometry = nextGeometry; format(geometry) }
                if (now - lastStateAt >= 2000 && MobileVideoSharing.matchesSession(id)) {
                    enqueue(JSONObject().put("type", "state").put("state", "live").put("geometry", JSONObject()
                        .put("screen_width", geometry.first).put("screen_height", geometry.second).put("rotation", geometry.third)))
                    lastStateAt = now
                }
                Thread.sleep(400)
            }
        } catch (cause: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("NexusVideo", "video stopped: ${cause.javaClass.simpleName}")
            if (failure.isBlank()) failure = "MOBILE_VIDEO_CONNECTION_LOST"
        } finally {
            if (BuildConfig.DEBUG) android.util.Log.i("NexusVideo", "video cleanup: ${failure.ifBlank { "stopped" }}")
            if (MobileVideoSharing.activeId == id) MobileVideoSharing.live = false
            runCatching { capturer?.stopCapture() }
            runCatching { capturer?.dispose() }
            runCatching { track?.dispose() }; runCatching { source?.dispose() }
            runCatching { peer?.close(); peer?.dispose() }; runCatching { helper?.dispose() }
            runCatching { factory?.dispose() }; runCatching { egl?.release() }
            runCatching { api.videoSignal(config, id, JSONObject().put("type", "state")
                .put("state", if (failure.isBlank()) "stopped" else "failed").put("error_code", failure)
                .put("message_id", UUID.randomUUID().toString())) }
            if (MobileVideoSharing.activeId == id) MobileVideoSharing.updatePending(this, null)
            stopSelf()
        }
    }
    private open inner class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(message: String) { if (BuildConfig.DEBUG) android.util.Log.i("NexusVideo", "SDP creation failed"); running.set(false) }
        override fun onSetFailure(message: String) { if (BuildConfig.DEBUG) android.util.Log.i("NexusVideo", "SDP apply failed"); running.set(false) }
    }
}
