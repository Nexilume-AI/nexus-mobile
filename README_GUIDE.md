# Nexus Mobile

An Android companion that lets authorized Nexus agents observe and interact with a phone. Pairing and Android Accessibility permission are explicit; synchronization uses a visible foreground service.

## Requirements

- Android 8.0 (API 26) or newer. One-time screenshots require Android 11+.
- A compatible Nexus Cloud deployment and permission to register a Mobile device.
- Google Play services for the Google Code Scanner pairing flow. This is not a verified Google-free Android distribution.
- To build: JDK 17, Android SDK platform 36, and the checked-in Gradle wrapper. AGP 8.7.3 currently uses an explicit compileSdk 36 compatibility-warning suppression; this is tracked in RELEASE.md rather than treated as broad device certification.

## Download the Android Beta

[Download Nexus Mobile 0.1.1-beta.1 APK](https://github.com/Nexilume-AI/nexus-mobile/releases/download/v0.1.1-beta.1/nexus-mobile-0.1.1-beta.1.apk) · [Release notes and checksums](https://github.com/Nexilume-AI/nexus-mobile/releases/tag/v0.1.1-beta.1)

The APK is a signed, non-debuggable release build with the Nexus launcher and notification logo. On Android, allow installation from the browser or file manager you use to open it. Review the release notes before enabling Accessibility. Requires Android 8.0+; the pairing scanner requires Google Play services.

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
2. Open Nexus Mobile, scan the QR, review the server/device identity, and select **Confirm and connect**.
3. Enable **Nexus Mobile Control** in Android Accessibility settings.
4. Allow notifications and start sync. Check that the foreground notification remains visible and Console reports the device online.
5. Attach the device to an Agent Run and grant only the Mobile scopes needed for that Run.

Release pairing requires HTTPS with a certificate trusted by Android. Debug permits local-development HTTP hosts. Pairing credentials are encrypted using Android Keystore; they are not Agent API keys. Pairing alone does not authorize every Agent to control the phone.

## Pause, disconnect and revoke

Use **Pause Nexus sync** or the notification's Pause action before entering sensitive information. Use the app's disconnect/unpair flow to remove local pairing. Revoke/delete or rotate the device credential in Console to stop server authorization. After revocation, verify the device stops receiving new commands. Disable Accessibility when you no longer want device control. A command already dispatched may have executed; revocation does not roll back its effects.

## Privacy and limitations

Observations redact detectable password, payment and verification contexts. Detection is best-effort, not a guarantee that arbitrary app content contains no personal data. Screenshots are optional, must not bypass FLAG_SECURE, and require a supported OS and an explicit command. Do not use financial or personal accounts for testing.

See [PRIVACY.md](PRIVACY.md), [SECURITY.md](SECURITY.md), [constraints](docs/CONSTRAINTS.md), and [device acceptance](docs/DEVICE_ACCEPTANCE.md).

## Troubleshooting

| Problem | Check |
| --- | --- |
| Build cannot find Java or Android SDK | JAVA_HOME / ANDROID_HOME and JDK 17; do not commit local.properties |
| QR scanner is unavailable | Google Play services availability and the scanner module download |
| Pairing rejected | QR expiry, server identity and trusted HTTPS certificate |
| setup_required | Accessibility service is not active |
| No commands arrive | Sync status, Cloud availability, device authorization and Run scopes |
| Screenshot denied | Android version, secure surfaces and sensitive screen content |
| Background sync stops | Foreground notification, OS background restrictions and battery policy |
| APK update rejected | Same application ID, signing certificate and increased versionCode required |

## License and contributions

Nexus-authored source uses [Apache License 2.0 (modified)](LICENSE). Preserve [NOTICE](NOTICE); Google components and other dependencies keep their own licenses. See [CONTRIBUTING.md](CONTRIBUTING.md). This repository does not contain the Nexus Cloud implementation or grant access to privately distributed Cloud packages.

### Licensing conditions

Nexus is licensed under a modified version of the Apache License 2.0, with the following additional conditions. Multi-tenant service operation and removal of existing Nexus UI branding require prior written authorization. Earlier Apache-2.0 grants and third-party licenses remain unchanged. Contributions require explicit agreement permitting commercial use and future relicensing. See [LICENSING.md](LICENSING.md). Authorization contact: **cary.nexilume@outlook.com**.
