# Nexus Mobile

An Android companion that lets authorized Nexus agents observe and interact with a phone. Pairing and Android Accessibility permission are explicit; synchronization uses a visible foreground service.

## Requirements

- Android 8.0 (API 26) or newer. One-time screenshots require Android 11+.
- A compatible Nexus Cloud deployment and permission to register a Mobile device.
- A camera and Camera permission for the built-in pairing scanner. Current source bundles ZXing and does not require Google Play services or a scanner-module download.
- To build: JDK 17, Android SDK platform 36, and the checked-in Gradle wrapper. AGP 8.7.3 currently uses an explicit compileSdk 36 compatibility-warning suppression; this is tracked in RELEASE.md rather than treated as broad device certification.

## Download the Android Beta

[Download Nexus Mobile 0.1.2-beta.2 APK](https://github.com/Nexilume-AI/nexus-mobile/releases/download/v0.1.2-beta.2/nexus-mobile-0.1.2-beta.2.apk) · [Release notes and checksums](https://github.com/Nexilume-AI/nexus-mobile/releases/tag/v0.1.2-beta.2)

This signed **0.1.2-beta.2** fixes native WebRTC initialization crashes after screen-sharing consent, safely pauses sync at Android's background time limit, and distinguishes an enabled Accessibility permission from a disconnected control service. Pairing is retained during recovery. Live screen needs compatible Cloud/Web; cross-NAT video may require TURN. Review outstanding physical-device checks before enabling Accessibility.

This is an opt-in **Beta**, not a production/device-compatibility certification. Physical-device end-to-end acceptance and upgrade continuity from an earlier signed release have not yet been verified. A previously installed debug build uses a different signer and cannot be updated in place with this APK; back up anything needed and explicitly remove the debug build only if you choose to migrate.

## Build from source

Install Android Studio or the command-line Android SDK. Set JAVA_HOME to JDK 17 and ANDROID_HOME to your SDK directory. Alternatively create an untracked local.properties with sdk.dir pointing to your SDK. No machine-specific Java path is committed.

Linux/macOS:

```sh
sh gradlew testDebugUnitTest lintRelease assembleRelease --no-daemon
```

Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest lintRelease assembleRelease --no-daemon
```

Unsigned output: `app/build/outputs/apk/release/app-release-unsigned.apk`. Without signing credentials this is a build artifact, not an installable public release. See [RELEASE.md](RELEASE.md) for signing and verification.

For a development APK use `assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Debug is for controlled testing only; do not distribute it as a release. On Windows paths containing non-ASCII characters, use `-PnexusMobileBuildDir=<absolute-ASCII-output-directory>` if test workers cannot load classes; all app output then moves under that directory.

## Pair and connect

1. In Nexus Console create a Mobile device and display its short-lived pairing QR.
   Cloud provides the server address automatically; no URL entry is needed on the phone.
   Deployment requirements and configuration recovery: [Cloud pairing address](docs/CLOUD_PAIRING_ADDRESS.md).
2. Open Nexus Mobile, tap **Scan pairing QR**, review the on-screen explanation,
   then tap the highlighted **Allow camera** button to request Camera permission.
   Scanning and decoding run inside the app; camera frames are neither saved nor uploaded.
   Review the server/device identity, then select **Pair and connect**.
   There is no paste-link or manual-token entry. Canceling does not change an existing pairing.
3. Follow the highlighted **Permissions** step. **Enable device control**
   displays an in-app, numbered Settings path before you open Android settings.
   Enable **Nexus Mobile Control** yourself after reviewing the system prompt.
   If Android refuses access, use **Android blocked access?** in the app.
   **Open App info** goes directly to Nexus Mobile; where available, select
   **⋮ → Allow restricted settings**, review the system confirmation, then return
   and enable Accessibility. The app rechecks the real permission on return;
   opening settings alone never marks setup complete. Menu names vary by device,
   and a managed phone may require its administrator's approval. Only grant this
   access to a trusted APK. [Android restricted-settings guidance](https://support.google.com/android/answer/12623953).
4. Returning to the app checks the actual permission before advancing to
   **Connection notifications**. Notifications are recommended but can be skipped;
   a skipped permission stays visibly disabled, not marked granted. **Later**
   collapses the guide; **Continue setup** resumes it.
   Check Connection status and Console to confirm the device is online. Permission
   readiness is not a successful Cloud connection. Starting sync or confirming
   pairing never automatically opens a notification permission prompt.
5. Attach the device to an Agent Run and grant only the Mobile scopes needed for that Run.

Release pairing requires HTTPS with a certificate trusted by Android. Debug permits local-development HTTP hosts. Pairing credentials are encrypted using Android Keystore; they are not Agent API keys. Pairing alone does not authorize every Agent to control the phone.

The home screen always shows connection status and **Permissions**, including when
permissions are already granted. Missing permissions have a highlighted next step.
Use **⋮ → Connection details**, **Privacy** or **Remove pairing** for less frequent actions. **Pause sync**
stays available while connected, including in the menu when control is not ready.

## Screen preview and control (current source)

Updated Cloud, Web and Android builds support **live screen video** in the
device's Control tab. Choose **Start live video**, open Nexus Mobile, tap
**Share screen**, then approve Android's native screen-sharing prompt. Video
has no audio or recording. **Stop video**, leaving the Control workspace,
pausing sync, or removing pairing stops sharing; the phone also provides an
ongoing notification with **Stop sharing**. Each new session needs new consent.

After live frames arrive, explicitly enable **Control live screen** to tap,
drag to swipe, or hold to long press. Commands still pass through the existing
approval queue. A stale video, changed screen geometry or lost connection
disables control rather than replaying a gesture. The Cloud's authenticated,
short-lived signaling API exchanges SDP/ICE; media is encrypted WebRTC over a
direct connection or the managed TURN relay. Configure Internet reachability
using [the live-video guide](docs/LIVE_VIDEO.md); the Mobile
repository itself does not include the Cloud server.

Sensitive detected screens stop sharing. A brief unknown window transition
drops frames; an unknown window lasting five seconds stops sharing. This
best-effort detection is not a guarantee: stop sharing before sensitive work.
Browsers require HTTPS (localhost is allowed). Old APKs retain the one-time
screenshot flow rather than falsely advertising live video.

The protected one-time screenshot remains independently available.
In the device's Control tab, request a fresh capture, then explicitly enable
**Control screen** to tap, drag to swipe, or hold to long press. A successful
gesture requests a new capture; the device's approval policy still applies.
Home and Recent apps are also available in the action selector. These new
actions require updated Cloud, Web and Android builds; unsupported actions
remain disabled on older apps.

Screen gestures are bound to the displayed capture, expire after 30 seconds,
and cannot reuse a dispatched frame. Rotation invalidates the gesture. A stale
capture remains viewable but requires a fresh capture before further control.

## Pause, disconnect and revoke

Use **Pause Nexus sync** or the notification's Pause action before entering sensitive information. Use the app's disconnect/unpair flow to remove local pairing. Revoke/delete or rotate the device credential in Console to stop server authorization. After revocation, verify the device stops receiving new commands. Disable Accessibility when you no longer want device control. A command already dispatched may have executed; revocation does not roll back its effects.

On Android 15 and later, the system limits background `dataSync` service time.
At that limit Nexus safely pauses, stops sharing, and preserves pairing and any
pending action result. Open the app and choose **Start sync** to reconnect; it
does not automatically restart and loop against Android's exhausted quota.
See [Android's foreground-service time limits](https://developer.android.com/develop/background-work/services/fgs/timeout).

### Minified-release crash regression

Debug builds cannot detect R8 removing JNI-only WebRTC entry points. Before
publishing, run `tools/verify_release.py` on the actual APK. It verifies the
release manifest, branding and required JNI class definitions in DEX. Also run
the native factory and sync-timeout smoke tests using the existing AVD harness:

```bash
sh gradlew -PnexusMobileReleaseSmoke=true assembleRelease assembleReleaseAndroidTest
python tools/release_runtime_smoke.py --sdk "$ANDROID_HOME" \
  --apk app/build/outputs/apk/release/app-release.apk \
  --test-apk app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
```

On Windows use `gradlew.bat`. The opt-in smoke build is minified and signed with
the local debug key, rejects production signing credentials, and must **never
be published**. Test instrumentation exists only in the separate test APK. The
harness creates and removes a fresh API 34 AVD without connecting to Cloud or
touching a phone. It tests actual native initialization, prompt local shutdown,
pairing preservation, late network responses, and explicit resume. API 34 calls
the timeout callback directly; system-enforced timeout and screen-consent
acceptance still require an Android 15+ device or emulator.

## Privacy and limitations

Observations redact detectable password, payment and verification contexts. Detection is best-effort, not a guarantee that arbitrary app content contains no personal data. Screenshots are optional, must not bypass FLAG_SECURE, and require a supported OS and an explicit command. Do not use financial or personal accounts for testing.

See [PRIVACY.md](PRIVACY.md), [SECURITY.md](SECURITY.md), [constraints](docs/CONSTRAINTS.md), and [device acceptance](docs/DEVICE_ACCEPTANCE.md).

## Troubleshooting

| Problem | Check |
| --- | --- |
| Build cannot find Java or Android SDK | JAVA_HOME / ANDROID_HOME and JDK 17; do not commit local.properties |
| Camera permission denied | Allow Camera using the scanner's permission button; if Android will not prompt again, open app permissions from that screen |
| Camera is unavailable | Close other apps using it, check the system Camera access switch, then select Retry camera |
| Invalid or expired QR | Generate a fresh Mobile pairing QR in Nexus Console; the scanner remains open for another attempt |
| Pairing rejected | QR expiry, server identity and trusted HTTPS certificate |
| setup_required | Enable Nexus Mobile Control in Android Accessibility settings |
| control_disconnected | Permission is already enabled, but Android has not bound the service. Select Reconnect control, turn Nexus Mobile Control off and on in system settings, then return. Pairing is retained and video consent is not required. |
| Android denies Accessibility access | Use the in-app restricted-settings guide and App info shortcut; if the option is missing or still blocked, check manufacturer or device-management restrictions |
| No commands arrive | Sync status, Cloud availability, device authorization and Run scopes |
| Screenshot denied | Android version, secure surfaces and sensitive screen content |
| Background sync stops | Foreground notification, OS background restrictions and battery policy |
| APK update rejected | Same application ID, signing certificate and increased versionCode required |

## License and contributions

Nexus-authored source uses [Apache License 2.0 (modified)](LICENSE). Preserve [NOTICE](NOTICE); ZXing, WebRTC and other dependencies keep their own licenses. The APK includes [scanner notices](app/src/main/assets/third_party_scanner.txt) and [WebRTC component notices](app/src/main/assets/third_party_webrtc.txt). See [CONTRIBUTING.md](CONTRIBUTING.md). This repository does not contain the Nexus Cloud implementation or grant access to privately distributed Cloud packages.

### Licensing conditions

Nexus is licensed under a modified version of the Apache License 2.0, with the following additional conditions. Multi-tenant service operation and removal of existing Nexus UI branding require prior written authorization. Earlier Apache-2.0 grants and third-party licenses remain unchanged. Contributions require explicit agreement permitting commercial use and future relicensing. See [LICENSING.md](LICENSING.md). Authorization contact: **cary.nexilume@outlook.com**.
