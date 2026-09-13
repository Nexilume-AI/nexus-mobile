# Physical device acceptance

Use a dedicated test phone and isolated Cloud with synthetic data. Record Android version, model, APK SHA-256, signer digest, Cloud version and date. No physical device was connected during the initial release preparation; these checks must not be marked passed from unit tests.

1. Install the signed release APK and reject expired/untrusted pairing URLs. Confirm server/device identity before pairing.
2. Before Accessibility is enabled, verify no command is fetched/executed. Enable it and confirm visible foreground sync and online heartbeat.
3. Run observe, tap, text entry, swipe, back and app-open on a synthetic test app. Check result reporting and approved scopes.
4. Verify password fields/OTP/payment contexts are redacted; screenshot requests on FLAG_SECURE and sensitive surfaces are rejected. Test Android 8-10 without screenshot capability and Android 11+ separately.
5. Pause sync and verify no new commands execute. Resume, revoke the credential in Cloud and verify command access stops. Unpair and verify local credential deletion; re-pair with a new token.
6. Disable Accessibility while online; verify capability withdrawal and command refusal. Test process restart, network loss and device reboot without silent permission re-enablement.
7. Attempt a debug command broadcast against the release APK; no receiver must exist. Against a debug APK, an ordinary app without DUMP must be denied.
8. Upgrade a prior signed version without uninstalling; verify signature continuity, data preservation and ability to revoke. Record any OEM battery/foreground-service limitations.

Use tools/real_android_gate.py to fail early when no physical ADB device is attached. An emulator pass does not replace this checklist.
