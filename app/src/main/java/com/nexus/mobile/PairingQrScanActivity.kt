package com.nexus.mobile

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/** App-private scanner. Frames and pairing payloads are never saved, uploaded or logged. */
class PairingQrScanActivity : Activity() {
    private lateinit var cameraView: DecoratedBarcodeView
    private lateinit var message: TextView
    private lateinit var retry: Button
    private lateinit var flash: Button
    private var permissionRequested = false
    private var permissionRequestInFlight = false
    private var foreground = false
    private var cameraFailed = false
    private var delivered = false
    private var torchOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionRequested = savedInstanceState?.getBoolean(PERMISSION_REQUESTED) ?: false
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setResult(RESULT_CANCELED)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(Color.rgb(247, 249, 252))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(dp(20) + bars.left, dp(16) + bars.top, dp(20) + bars.right, dp(16) + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(dp(20) + insets.systemWindowInsetLeft, dp(16) + insets.systemWindowInsetTop,
                    dp(20) + insets.systemWindowInsetRight, dp(16) + insets.systemWindowInsetBottom)
            }
            insets
        }
        root.addView(TextView(this).apply {
            setText(R.string.qr_scan)
            textSize = 22f
            setTextColor(Color.rgb(23, 32, 51))
            typeface = Typeface.DEFAULT_BOLD
            if (Build.VERSION.SDK_INT >= 28) setAccessibilityHeading(true)
        })
        root.addView(TextView(this).apply {
            setText(R.string.qr_privacy)
            textSize = 14f
            setTextColor(Color.rgb(95, 107, 122))
            setPadding(0, dp(8), 0, dp(12))
        })
        cameraView = DecoratedBarcodeView(this).apply {
            decoderFactory = PairingQrDecoder.factory()
            setStatusText("") // Localized instructions live outside the camera preview.
            contentDescription = getString(R.string.qr_camera_preview)
        }
        root.addView(cameraView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        message = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(23, 32, 51))
            setPadding(0, dp(12), 0, dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(message)
        retry = button(R.string.qr_retry) { recoverCamera() }
        root.addView(retry)
        flash = button(R.string.qr_flash_on) {
            torchOn = !torchOn
            if (torchOn) cameraView.setTorchOn() else cameraView.setTorchOff()
            flash.setText(if (torchOn) R.string.qr_flash_off else R.string.qr_flash_on)
        }.apply { visibility = View.GONE }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(flash, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = dp(8)
                marginEnd = dp(8)
            })
            addView(button(R.string.qr_cancel) { finish() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = dp(8)
            })
        })
        setContentView(root)
        root.requestApplyInsets()

        cameraView.barcodeView.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() = Unit
            override fun previewStarted() {
                if (!foreground || delivered || cameraFailed) return
                message.setText(R.string.qr_point_camera)
                flash.visibility = if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) View.VISIBLE else View.GONE
                flash.isEnabled = true
            }
            override fun previewStopped() = Unit
            override fun cameraClosed() = Unit
            override fun cameraError(error: Exception) {
                if (!foreground || delivered) return
                cameraFailed = true
                cameraView.pause()
                torchOn = false
                flash.visibility = View.GONE
                message.setText(R.string.qr_camera_unavailable)
                retry.setText(R.string.qr_retry)
                retry.visibility = View.VISIBLE
            }
        })
        cameraView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                if (!foreground || delivered || cameraFailed) return
                val value = result.text.orEmpty()
                if (value.length > PairingQrDecoder.MAX_PAYLOAD_LENGTH) {
                    showMessage(R.string.qr_invalid)
                    return
                }
                try {
                    PairingParser.parse(rawValue = value, allowInsecureLocalhost = BuildConfig.DEBUG)
                } catch (error: IllegalArgumentException) {
                    // Never include QR content or decoder errors in UI/logs.
                    showMessage(if (error.message?.startsWith("This pairing QR has expired.") == true) R.string.qr_expired else R.string.qr_invalid)
                    return
                }
                delivered = true
                cameraView.barcodeView.stopDecoding()
                setResult(RESULT_OK, Intent().putExtra(EXTRA_PAIRING_QR, value))
                finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (hasCameraPermission()) startCamera()
        else if (!permissionRequested) showCameraPermissionGuide()
        else if (!permissionRequestInFlight) showPermissionDenied()
    }

    override fun onPause() {
        foreground = false
        if (::cameraView.isInitialized) {
            cameraView.setTorchOff()
            cameraView.pauseAndWait()
        }
        torchOn = false
        if (::flash.isInitialized) flash.setText(R.string.qr_flash_on)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(PERMISSION_REQUESTED, permissionRequested)
        // Do not persist decoded pairing tokens or camera frames.
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        permissionRequestInFlight = false
        if (hasCameraPermission()) startCamera() else showPermissionDenied()
    }

    private fun hasCameraPermission() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!foreground || delivered || cameraFailed || !hasCameraPermission()) return
        flash.visibility = View.GONE
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            cameraFailed = true
            message.setText(R.string.qr_camera_missing)
            retry.visibility = View.GONE
            return
        }
        retry.visibility = View.GONE
        message.setText(R.string.qr_opening_camera)
        try {
            cameraView.resume()
        } catch (_: RuntimeException) {
            cameraFailed = true
            cameraView.pause()
            message.setText(R.string.qr_camera_unavailable)
            retry.setText(R.string.qr_retry)
            retry.visibility = View.VISIBLE
        }
    }

    private fun requestCameraPermission() {
        permissionRequested = true
        permissionRequestInFlight = true
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
    }

    private fun showCameraPermissionGuide() {
        message.setText(R.string.qr_camera_guide_intro)
        retry.setText(R.string.qr_allow_camera)
        retry.visibility = View.VISIBLE
        highlightPermissionAction(true)
        flash.visibility = View.GONE
    }

    private fun highlightPermissionAction(highlight: Boolean) {
        retry.setTextColor(if (highlight) Color.WHITE else Color.rgb(23, 32, 51))
        retry.background = GradientDrawable().apply {
            setColor(if (highlight) Color.rgb(23, 105, 224) else Color.WHITE)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(if (highlight) 2 else 1), Color.rgb(23, 105, 224))
        }
    }

    private fun showPermissionDenied() {
        message.setText(R.string.qr_camera_denied)
        retry.setText(if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) R.string.qr_allow_camera else R.string.qr_camera_settings)
        retry.visibility = View.VISIBLE
        highlightPermissionAction(true)
        flash.visibility = View.GONE
    }

    private fun recoverCamera() {
        if (hasCameraPermission()) {
            cameraFailed = false
            startCamera()
        } else if (!permissionRequested || shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            requestCameraPermission()
        } else {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            } catch (_: ActivityNotFoundException) {
                message.setText(R.string.qr_settings_unavailable)
            } catch (_: SecurityException) {
                message.setText(R.string.qr_settings_unavailable)
            }
        }
    }

    private fun button(label: Int, action: () -> Unit) = Button(this).apply {
        setText(label)
        isAllCaps = false
        minHeight = dp(48)
        setTextColor(Color.rgb(23, 32, 51))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.rgb(217, 225, 236))
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showMessage(resource: Int) {
        val text = getString(resource)
        // Continuous decoding must not repeatedly announce the same invalid QR.
        if (message.text.toString() != text) message.text = text
    }

    companion object {
        const val EXTRA_PAIRING_QR = "com.nexus.mobile.PAIRING_QR"
        private const val REQUEST_CAMERA = 1101
        private const val PERMISSION_REQUESTED = "qr.camera_permission_requested"
    }
}
