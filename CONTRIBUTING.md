# Contributing

Use JDK 17 and the Gradle wrapper. Run testDebugUnitTest, lintRelease and assembleRelease before proposing changes. Preserve the trust and privacy rules in docs/CONSTRAINTS.md. Add regression tests for pairing validation, command authorization or observation redaction changes.

Never include real QR codes, device tokens, account screenshots, signing keys or local.properties. Use synthetic test identities. Keep debug-only helpers under src/debug and preserve the restricted broadcast permission. Review release APK contents, not just source manifests, after build changes.
