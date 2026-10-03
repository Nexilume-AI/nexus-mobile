# Contributing

Use JDK 17 and the Gradle wrapper. Run testDebugUnitTest, lintRelease and assembleRelease before proposing changes. Preserve the trust and privacy rules in docs/CONSTRAINTS.md. Add regression tests for pairing validation, command authorization or observation redaction changes.

Never include real QR codes, device tokens, account screenshots, signing keys or local.properties. Use synthetic test identities. Keep debug-only helpers under src/debug and preserve the restricted broadcast permission. Review release APK contents, not just source manifests, after build changes.

## Contribution licensing

Nexus-authored changes are distributed under the Apache License 2.0 (modified).
Read [LICENSE](LICENSE), [LICENSING.md](LICENSING.md) and the
[Nexus Contributor License Agreement](CONTRIBUTOR_LICENSE_AGREEMENT.md).
Every contributing author must explicitly accept that agreement for their PR
before merge; maintainers must record the acceptance as described there.
Contributors retain copyright while permitting commercial use, dual licensing
and future relicensing. Historical contributions and third-party code are not
automatically subject to the new grant. Preserve all upstream notices.

Licensing inquiries: cary.nexilume@outlook.com. Every contributor must explicitly
accept the contributor agreement for the PR. Checking a template box or a
maintainer's declaration does not constitute another author's consent.
