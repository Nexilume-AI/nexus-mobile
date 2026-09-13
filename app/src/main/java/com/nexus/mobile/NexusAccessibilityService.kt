package com.nexus.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class NexusAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        NexusAccessibilityServiceHolder.service = this
        lastObservation = observe()
        NexusMobileService.requestRefresh(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.className?.toString()?.takeIf { it.isNotBlank() }?.let { lastActivityName = it }
        lastObservation = observe()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (NexusAccessibilityServiceHolder.service === this) {
            NexusAccessibilityServiceHolder.service = null
        }
        screenshotExecutor.shutdownNow()
        NexusMobileService.requestRefresh(this)
        super.onDestroy()
    }

    fun observeBlocking(): MobileObservation =
        callOnMain(MobileObservation()) { observe() }

    fun captureScreenBlocking(): MobileScreenshotCapture {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return screenshotFailure(
                "SCREEN_CAPTURE_UNSUPPORTED",
                "Screen capture requires Android 11 or newer.",
            )
        }
        val sensitiveContext = callOnMain(false) { hasSensitiveContext(rootInActiveWindow) }
        if (sensitiveContext) {
            return screenshotFailure(
                "SCREEN_CAPTURE_BLOCKED",
                "Screen capture is blocked while a password or other sensitive field is visible.",
            )
        }

        val result = AtomicReference(
            screenshotFailure("SCREEN_CAPTURE_TIMEOUT", "Android did not return a screenshot in time."),
        )
        val latch = CountDownLatch(1)
        runCatching {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val bitmap = runCatching {
                            Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        hardwareBuffer.close()
                        result.set(
                            if (bitmap == null) {
                                screenshotFailure(
                                    "SCREEN_CAPTURE_FAILED",
                                    "Android returned an unreadable screenshot.",
                                )
                            } else {
                                encodeScreenshot(bitmap)
                            },
                        )
                        bitmap?.recycle()
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        result.set(
                            screenshotFailure(
                                "SCREEN_CAPTURE_BLOCKED",
                                "Android blocked this screenshot. Close protected or sensitive content and try again.",
                            ),
                        )
                        latch.countDown()
                    }
                },
            )
        }.onFailure {
            result.set(
                screenshotFailure(
                    "SCREEN_CAPTURE_FAILED",
                    it.message ?: "Android could not capture the current screen.",
                ),
            )
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return result.get()
    }

    fun tapTextBlocking(text: String): Boolean = callOnMain(false) {
        val node = findByText(rootInActiveWindow, text) ?: return@callOnMain false
        clickableAncestor(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    fun typeTextBlocking(text: String): Boolean = callOnMain(false) {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return@callOnMain false
        if (!node.isEditable || !node.isEnabled) return@callOnMain false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressBackBlocking(): Boolean =
        callOnMain(false) { performGlobalAction(GLOBAL_ACTION_BACK) }

    fun tapNormalizedBlocking(x: Float, y: Float): Boolean {
        val metrics = resources.displayMetrics
        return tapPixelsBlocking(
            metrics.widthPixels * x.coerceIn(0f, 1f),
            metrics.heightPixels * y.coerceIn(0f, 1f),
        )
    }

    fun tapPixelsBlocking(x: Float, y: Float): Boolean =
        gestureBlocking(x, y, x, y, 1)

    fun swipeNormalizedBlocking(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ): Boolean {
        val metrics = resources.displayMetrics
        return swipePixelsBlocking(
            metrics.widthPixels * startX.coerceIn(0f, 1f),
            metrics.heightPixels * startY.coerceIn(0f, 1f),
            metrics.widthPixels * endX.coerceIn(0f, 1f),
            metrics.heightPixels * endY.coerceIn(0f, 1f),
            durationMs,
        )
    }

    fun swipePixelsBlocking(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ): Boolean = gestureBlocking(
        startX,
        startY,
        endX,
        endY,
        durationMs.coerceIn(50, 5_000),
    )

    private fun gestureBlocking(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ): Boolean {
        val completed = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        mainHandler.post {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        latch.countDown()
                    }
                },
                mainHandler,
            )
            if (!accepted) latch.countDown()
        }
        latch.await(durationMs + 2_000, TimeUnit.MILLISECONDS)
        return completed.get()
    }

    private fun observe(): MobileObservation {
        val root = rootInActiveWindow
        val sensitiveContext = hasSensitiveContext(root)
        return MobileObservation(
            packageName = root?.packageName?.toString().orEmpty(),
            activityName = lastActivityName,
            nodes = root?.let { flattenNodes(it, sensitiveContext = sensitiveContext) }.orEmpty().take(80),
        )
    }

    private fun findByText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser || !node.isEnabled) return null
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) return node
        if (node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) return node
        for (index in 0 until node.childCount) {
            val found = findByText(node.getChild(index), text)
            if (found != null) return found
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            if (current?.isClickable == true && current?.isEnabled == true) return current
            current = current?.parent
        }
        return null
    }

    private fun hasSensitiveContext(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        if (node == null || depth > 5) return false
        if (!node.isVisibleToUser) return false
        if (node.isPassword) return true
        if (PrivacyRedactor.indicatesSensitiveContext(node.text?.toString())) return true
        if (PrivacyRedactor.indicatesSensitiveContext(node.contentDescription?.toString())) return true
        for (index in 0 until node.childCount) {
            if (hasSensitiveContext(node.getChild(index), depth + 1)) return true
        }
        return false
    }

    private fun encodeScreenshot(source: Bitmap): MobileScreenshotCapture {
        var working = scaledBitmap(source, maxWidth = 720, maxHeight = 1280)
        var bytes = compressWebp(working, quality = 72)
        if (bytes.size > MAX_SCREENSHOT_BYTES) {
            bytes = compressWebp(working, quality = 52)
        }
        if (bytes.size > MAX_SCREENSHOT_BYTES) {
            val smaller = scaledBitmap(working, maxWidth = 540, maxHeight = 960)
            if (smaller !== working) {
                if (working !== source) working.recycle()
                working = smaller
            }
            bytes = compressWebp(working, quality = 45)
        }
        val width = working.width
        val height = working.height
        if (working !== source) working.recycle()
        if (bytes.isEmpty() || bytes.size > MAX_SCREENSHOT_BYTES) {
            return screenshotFailure(
                "SCREEN_CAPTURE_TOO_LARGE",
                "The current screen could not be reduced to the secure upload limit.",
            )
        }
        return MobileScreenshotCapture(
            succeeded = true,
            bytes = bytes,
            width = width,
            height = height,
        )
    }

    private fun scaledBitmap(source: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val scale = minOf(
            1f,
            maxWidth.toFloat() / source.width.coerceAtLeast(1),
            maxHeight.toFloat() / source.height.coerceAtLeast(1),
        )
        if (scale >= 1f) return source
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun compressWebp(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            bitmap.compress(format, quality, output)
            output.toByteArray()
        }

    private fun screenshotFailure(code: String, message: String): MobileScreenshotCapture =
        MobileScreenshotCapture(
            succeeded = false,
            errorCode = code,
            errorMessage = message,
        )

    private fun flattenNodes(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        sensitiveContext: Boolean,
    ): List<MobileNode> {
        if (depth > 8) return emptyList()
        val sensitive = node.isPassword || (sensitiveContext && node.isEditable)
        val current = MobileNode(
            text = PrivacyRedactor.sanitize(node.text?.toString(), node.isPassword, sensitiveContext),
            description = PrivacyRedactor.sanitize(
                node.contentDescription?.toString(),
                node.isPassword,
                sensitiveContext,
            ),
            className = node.className?.toString().orEmpty().take(120),
            clickable = node.isClickable,
            editable = node.isEditable,
            enabled = node.isEnabled,
            sensitive = sensitive,
        )
        return buildList {
            add(current)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let {
                    addAll(flattenNodes(it, depth + 1, sensitiveContext))
                }
                if (size >= 80) break
            }
        }
    }

    private fun <T> callOnMain(fallback: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrDefault(fallback)
        val result = AtomicReference(fallback)
        val latch = CountDownLatch(1)
        mainHandler.post {
            result.set(runCatching(block).getOrDefault(fallback))
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return result.get()
    }

    companion object {
        private const val MAX_SCREENSHOT_BYTES = 1024 * 1024

        @Volatile
        var lastObservation: MobileObservation = MobileObservation()

        @Volatile
        private var lastActivityName: String = ""
    }
}
