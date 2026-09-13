# Release procedure

## Build and inspect

Use JDK 17, SDK platform 36 and the Gradle wrapper. Run:

```sh
sh gradlew testDebugUnitTest lintRelease assembleRelease --no-daemon
python tools/verify_release.py --apk app/build/outputs/apk/release/app-release-unsigned.apk --aapt /path/to/build-tools/aapt
```

The verifier rejects debug components, debuggable builds, cleartext traffic and backup-enabled applications. It checks the APK manifest, generates a SHA-256, and does not claim device or server acceptance.

## Signing

Keep a long-lived production keystore outside the repository, back it up securely and restrict access. Do not generate a replacement key for each release. Supply all four environment variables through your local secret manager or a protected CI environment:

- NEXUS_MOBILE_KEYSTORE: absolute keystore path
- NEXUS_MOBILE_STORE_PASSWORD
- NEXUS_MOBILE_KEY_ALIAS
- NEXUS_MOBILE_KEY_PASSWORD

Run assembleRelease again. With all four values it produces a signed APK; partial configuration fails. Do not pass passwords on a command line or write them into Gradle files. Signing credentials are never included in ordinary pull request CI.

Run `apksigner verify --verbose --print-certs <signed.apk>`, compare the certificate digest with the maintainer's recorded digest, then run verify_release.py with `--apksigner /path/to/apksigner --require-signed`. Publish only the verified signed APK and its SHA256SUMS. An unsigned APK is not an end-user download.

Increment versionCode and versionName in app/build.gradle.kts. Upgrades require the same application ID and signing certificate. Test `adb install -r <signed.apk>` over the prior signed version, then verify pairing and permissions. Do not uninstall as a substitute for an upgrade test.

## Release gates

- Unit tests, release lint, APK boundary inspection, source secret scan and wrapper checksum pass.
- Real-device checklist in docs/DEVICE_ACCEPTANCE.md passes against a test Cloud.
- Signing certificate continuity and upgrade test pass.
- Review dependency advisories and license notices; no claim of a full dependency vulnerability audit follows from a successful build.
- AGP 8.7.3 with compileSdk 36 currently retains the existing suppression flag. Test the exact toolchain; evaluate an AGP upgrade separately rather than hiding build failures.

Do not publish directly from this workspace or include its Git history, caches, logs, local SDK paths or debug test APKs. Publish only a reviewed standalone source export. Source CI may distribute an explicitly named unsigned review artifact; it must never label that artifact ready to install.
