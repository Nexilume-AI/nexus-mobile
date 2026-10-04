# Privacy and device control

When paired and syncing, Nexus Mobile sends device status and authorized command results to the configured Nexus server. Accessibility observations can include visible app text and UI structure; requested screenshots can include screen content. Pair only with a server you trust.

The app encrypts pairing credentials with Android Keystore and disables Android backup for its application data. Current source uses bundled, Apache-2.0-licensed ZXing for QR decoding, not Google Play services. Camera permission is requested only when you open the built-in scanner. Camera frames are decoded locally, not saved or uploaded, and the camera is released when you leave that screen. The scanner blocks screenshots to protect QR credentials. A decoded QR is held in memory and passed to the existing explicit server/device confirmation; only confirmed pairing credentials are saved. The previously published 0.1.1-beta.1 APK predates this change.

Password fields and detectable payment/OTP contexts are redacted or refused, but arbitrary application content cannot be guaranteed safe. FLAG_SECURE must not be bypassed. Pause sync before sensitive work, disable Accessibility when unused, and revoke pairing in Console when retiring a device.

Server retention and access policies belong to your deployment. Current protocol constraints specify short-lived screenshot retention; users must verify the server actually applies that policy. Uninstalling the app does not erase records already stored by the server.

Live screen sharing is opt-in for each session, requires Android's native
MediaProjection consent and shows an ongoing foreground notification. It
captures the screen, not the microphone or camera. The authenticated Cloud
exchanges bounded, short-lived SDP/ICE signaling; WebRTC sends encrypted media
to the requesting browser, directly or through TURN. Cloud does not record the
video. Signaling is cleared when the session stops or expires. TURN credentials
are short-lived; closing a session also closes its local peer connection.

Leaving the viewer, stopping video, pausing sync or removing pairing stops
capture. Sensitive detected windows stop sharing, and temporarily unknown
windows do not emit frames. Detection remains best-effort: stop sharing before
entering personal information. Browsers and apps must not automatically
reapprove capture after a disconnection or restart. Screen control remains
subject to the same authorized command and approval policy as other actions.
