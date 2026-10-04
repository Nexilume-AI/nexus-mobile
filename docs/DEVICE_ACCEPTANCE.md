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

## Permission coach acceptance

- Verify the current required step has a visible blue outline and one primary
  action. Before entering Android Accessibility settings, review the numbered
  schematic, control/privacy explanation and cancel action.
- Return without granting, deny, and grant manually. The first two cases must
  remain on Accessibility with recovery guidance; only a real grant advances.
  Check the Android 13+ restricted-settings shortcut on a sideloaded APK.
- Deny notifications, deny permanently, and recover via app notification settings.
  Skip notifications and verify the row still says not enabled. Check disabled
  notifications on Android 8–12 as well as the Android 13+ runtime permission.
- Leave the guide, reopen the app, and resume it. Revoke Accessibility after
  completion and verify it becomes required again. No background status update
  should move the current scroll position or open a system prompt.
- On scanner entry, verify the explanation and highlighted Camera action appear
  before the first OS prompt. Camera must never be requested on the main screen.
- Test English/Chinese, TalkBack, rotation, large font and small screens. Nexus
  must not draw over, auto-click or approve any Android authorization dialog.

## Built-in scanner acceptance

Use the current-source APK, not the older Google-dependent 0.1.1-beta.1 release.
Run these on a real camera phone without Google Play services (or with it disabled).
Use synthetic short-lived pairing credentials; never retain a screenshot of the QR.

- Open Scan pairing QR offline and verify camera preview and local decoding work
  without Google services or a module download. Confirming a connection still
  requires network access to the paired Cloud.
- Deny Camera once, retry, deny permanently, and recover through app permissions.
  Verify no permission prompt or camera access occurs outside the scanner.
- Scan an unrelated QR and an expired pairing QR; verify clear guidance and that
  the scanner remains open. Scan a valid QR and verify explicit identity confirmation.
- Cancel/back, background/foreground, rotate the phone and occupy the camera in
  another app. Verify camera release, recoverable retry and no pairing changes on cancel.
- Verify flashlight on/off when supported, screen-reader labels, English/Chinese
  messages, and reachable Cancel/permission actions on small screens.
- Verify no camera frame, QR payload or token appears in app logs/files; the scanner
  must reject screen capture. Test re-pairing without destroying the old pairing
  until the user confirms the new one.

Bundled image-decoder unit tests and a successful APK build do not establish that
OEM camera, permission or lifecycle behavior has passed this checklist.
