# Changelog

## 0.1.2-beta.2 — 2026-10-05

### Fixed

- Keep WebRTC's JNI-only entry points in minified APKs, preventing the native initialization crash after screen-sharing consent.
- Pause safely when Android's background sync time limit expires. Preserve pairing and pending results, reject late worker updates, and explain how to resume from the app.
- Distinguish Accessibility permission from a connected control service. Refresh readiness after service reconnection and provide a targeted recovery action without asking users to pair again.

### Verification

- Add real minified-release native initialization and service-timeout smoke tests using an isolated Android emulator.
- Inspect actual DEX class definitions before publishing to reject APKs whose required JNI classes have been stripped.
- Physical Samsung screen-consent acceptance and Android 15+ system-enforced timeout validation remain pending. This is a prerelease, not broad device certification.
