# Privacy and device control

When paired and syncing, Nexus Mobile sends device status and authorized command results to the configured Nexus server. Accessibility observations can include visible app text and UI structure; requested screenshots can include screen content. Pair only with a server you trust.

The app encrypts pairing credentials with Android Keystore and disables Android backup for its application data. QR scanning uses Google Play services Code Scanner and is subject to Google's component terms. The source tree does not include Google Play services implementation.

Password fields and detectable payment/OTP contexts are redacted or refused, but arbitrary application content cannot be guaranteed safe. FLAG_SECURE must not be bypassed. Pause sync before sensitive work, disable Accessibility when unused, and revoke pairing in Console when retiring a device.

Server retention and access policies belong to your deployment. Current protocol constraints specify short-lived screenshot retention; users must verify the server actually applies that policy. Uninstalling the app does not erase records already stored by the server.
