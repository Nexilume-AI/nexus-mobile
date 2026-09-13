# Nexus Mobile open-source preparation review

Date: 2026-09-13

## Completed

- Standalone Apache-2.0 LICENSE and existing upstream NOTICE.
- English README, contribution/security/privacy guidance, release and real-device acceptance procedures.
- Removed machine-specific Java path; retained configurable ASCII build output.
- Environment-only optional release signing; partial signing configuration fails.
- Restricted debug ADB command receiver with android.permission.DUMP.
- Non-exported state receiver registration across supported Android versions.
- Version-aware WebP encoding and explicit cloud/device-transfer backup exclusions.
- Pinned-action source CI and manual signed-candidate workflow, plus APK verifier tests.
- Clean standalone source export at .local/github-mobile-release/reviewed-source, without parent Git history, local SDK configuration, caches or signing keys.

## Executed locally

- Gradle testDebugUnitTest lintRelease assembleRelease: PASS.
- Android unit tests: 9 passed, no failures/errors/skips.
- Release lint: 0 errors, 12 warnings. Warnings remain; this is not a warning-free build.
- Python hosted AVD helper tests: 4 passed.
- APK verifier regression tests: 4 passed.
- Actual unsigned release APK manifest inspection: PASS.
- APK SHA-256: 0c324354e914360e5592820b37dbff15ef192c79ce87c2726c4587053c885fd7
- Gradle wrapper SHA-256 matches the pinned value in NOTICE and CI.
- Fresh standalone source Gitleaks scan: no leaks found (176419 bytes scanned).
- All 3 GitHub YAML files parse successfully. GitHub-hosted jobs have not run.

## Remaining release gates

- No Android device is attached: complete docs/DEVICE_ACCEPTANCE.md against a test Cloud, including permission changes, revocation, screenshots and background behavior.
- No production signing identity supplied: APK is unsigned and not an end-user installation release. Configure a durable signing identity and verify signed upgrade continuity.
- Configure the mobile-release GitHub environment and its reviewer/branch protections before using signed-candidate.yml. A workflow environment name alone does not create protection rules.
- Run CI from the standalone repository on Linux. Local validation ran on Windows with the installed Android Studio JBR, not the CI Temurin 17 environment.
- Review transitive dependency redistribution terms/notices and current advisories before distributing binaries; build and secret scanning do not constitute a complete dependency/security audit.
- No GitHub repository was created, pushed or made public in this preparation step.
