<div align="center">

# Nexus Mobile

**Connect your phone. Stay in control.**

[![License: Apache-2.0 modified](https://img.shields.io/badge/License-Apache--2.0_modified-17251d.svg)](LICENSE)
[![Try online](https://img.shields.io/badge/Try-Nexus_Cloud-b8ef73.svg)](https://cloud.nexilume.com/)
[![Documentation](https://img.shields.io/badge/Read-the_docs-b8ef73.svg)](README_GUIDE.md)
[![Cite the technical report](https://img.shields.io/badge/Cite-technical_report-e8e9e4.svg)](#citation)
[![Repository checks](https://github.com/Nexilume-AI/nexus-mobile/actions/workflows/ci.yml/badge.svg)](https://github.com/Nexilume-AI/nexus-mobile/actions/workflows/ci.yml)

`Android` · `Explicit pairing` · `Scoped access`

**English** · [Chinese](README_zh.md)

[Highlights](#highlights) · [Quick start](#quick-start) · [Documentation](#documentation) · [Ecosystem](#ecosystem) · [Contributing](#contributing) · [Citation](#citation)

</div>

> **[Try Nexus Cloud online](https://cloud.nexilume.com/)**: Explore Nexus Cloud in your browser, or self-host to get started.

An Android companion for caller-authorized Agent workflows. Pair explicitly, enable the required permissions, and keep device synchronization visible.

![Nexus Mobile: illustrated workflow](docs/media/overview.svg)

*Workflow illustration, not a product screenshot. Connections require the setup and authorization described below.*

## Highlights

| Step | You remain in control |
| --- | --- |
| **Pair** | Scan a short-lived QR and confirm the server/device identity |
| **Authorize** | Enable Accessibility explicitly, then grant required Run scopes in Cloud |
| **Observe and act** | Supported Agent workflows can use authorized phone capabilities |
| **Pause or revoke** | Pause visible synchronization, disconnect locally or revoke in Cloud |

## Download the Android Beta

[Download Nexus Mobile 0.1.2-beta.2 APK](https://github.com/Nexilume-AI/nexus-mobile/releases/download/v0.1.2-beta.2/nexus-mobile-0.1.2-beta.2.apk) · [Release notes and checksums](https://github.com/Nexilume-AI/nexus-mobile/releases/tag/v0.1.2-beta.2)

This signed **0.1.2-beta.2** fixes native WebRTC initialization crashes after screen-sharing consent, safely pauses sync at Android's background time limit, and provides targeted recovery when Accessibility is enabled but control is disconnected. Pairing is retained during recovery. Review the release notes before enabling Accessibility.

This is an opt-in **Beta**, not a production/device-compatibility certification. Physical-device end-to-end acceptance remains pending; see the release notes for the verified upgrade scope. A previously installed debug build uses a different signer and cannot be updated in place with this APK; back up anything needed and explicitly remove the debug build only if you choose to migrate.

## Quick start

Live screen requires compatible Cloud/Web and explicit Android screen-sharing
consent. Cross-NAT video may require TURN. See [Live video](docs/LIVE_VIDEO.md).

**Requirements:** Android 8.0+, a compatible Nexus Cloud, and a camera with Camera permission for the built-in scanner. One-time screenshots require Android 11+. Physical Google-free device acceptance remains pending.

Build with JDK 17 and Android SDK platform 36:

```sh
sh gradlew testDebugUnitTest lintRelease assembleRelease --no-daemon
```

On Windows use `./gradlew.bat` with the same arguments. Follow [RELEASE.md](RELEASE.md) to sign and verify an installable package.

> [!IMPORTANT]
> `app-release-unsigned.apk` is a build artifact, not an installable public release. Debug APKs are for controlled development, not public distribution. See the [build reference](README_GUIDE.md#build-from-source) for complete instructions and toolchain caveats.

## Pair, attach, run

1. In Nexus Console, create a Mobile device and display its pairing QR.
2. Tap **Scan pairing QR**, then **Allow camera**. Review the server/device identity and choose **Pair and connect**. Camera frames stay on the phone.
3. Follow the highlighted **Permissions** step. **Enable device control** explains the Settings path; enable **Nexus Mobile Control** yourself. If Android blocks it, use **Android blocked access?** for restricted-settings guidance.
4. Notifications are recommended, not required. Permissions stays visible on the home screen; **Later** collapses the guide without hiding its status. Check Connection and Console to confirm the device is online.
5. Attach the device to a Run and grant only the scopes the Agent needs.

**Success looks like:** the authorized device is online and a supported Agent action returns its result. Pairing is not blanket authorization for every Agent.

The home screen keeps pairing/connection and **Permissions** visible. Use the **⋮** menu for connection details, privacy and removing pairing. Opening Settings does not grant a permission; notification prompts require an explicit action.

## Privacy by workflow

Use test apps and synthetic data. Pause synchronization before entering sensitive information. Detection/redaction is best-effort; screenshots must respect secure surfaces. Revocation stops authorization for new commands but cannot undo an action already performed.

## Documentation

| Goal | Guide |
| --- | --- |
| Build, install and troubleshoot | [Setup reference](README_GUIDE.md) |
| Sign and verify a release | [Release guide](RELEASE.md) |
| Understand data handling | [Privacy](PRIVACY.md) |
| Review platform limitations | [Constraints](docs/CONSTRAINTS.md) |
| Check real-device validation requirements | [Device acceptance](docs/DEVICE_ACCEPTANCE.md) |

Android support is not a claim of iOS or Google-free device compatibility. Cloud is installed separately.

## Ecosystem

| Project | Role | Install separately? |
| --- | --- | --- |
| [Nexus Cloud](https://github.com/Nexilume-AI/nexus-cloud-community) | Server, Web Console and bundled Cloud Relay | Main workspace |
| [Python SDK](https://github.com/Nexilume-AI/nexus-agent-sdk-python) | Agent applications and outbound Computer Runtime | Yes |
| [OpenWrt](https://github.com/Nexilume-AI/nexus-openwrt) | Edge registration and capability routing | Optional |
| [Mobile](https://github.com/Nexilume-AI/nexus-mobile) | Authorized Android device integration | Optional |
| [Documentation](https://github.com/Nexilume-AI/nexus-docs) | User guides and reference | Read online or build locally |

Repository access, release availability and compatibility determine which integrations you can install. Cloud installation does not install device runtimes.

## Contributing

Start with [CONTRIBUTING.md](CONTRIBUTING.md). Small reproducible fixes, clearer tutorials, translations and sanitized examples are welcome. Use [Issues](https://github.com/Nexilume-AI/nexus-mobile/issues) for reproducible bugs; include versions and redacted diagnostics, never credentials or private files.

Follow [SECURITY.md](SECURITY.md) for security reports. Release checks and CI are not a guarantee of production readiness on every platform.

## Citation

If Nexus supports your research or engineering work, please cite the technical report below, rather than the software repository. [CITATION.cff](CITATION.cff) provides the same report metadata through `preferred-citation`.

Nexilume Research. *Nexus: Operating AI Agents Beyond the Cloud*. Technical Report NX-SYS-2026-001, September 2026.

```bibtex
@techreport{nexilume2026nexus,
  author      = {{Nexilume Research}},
  title       = {{Nexus}: Operating {AI} Agents Beyond the Cloud},
  institution = {Nexilume Research},
  type        = {Technical Report},
  number      = {NX-SYS-2026-001},
  year        = {2026},
  month       = sep
}
```

## License

Nexus-authored source is distributed under [Apache License 2.0 (modified)](LICENSE). Third-party components retain their own licenses and notices. Documentation does not grant rights to separately distributed Enterprise implementation.

### Licensing conditions

Nexus is licensed under a modified version of the Apache License 2.0, with the following additional conditions. Multi-tenant service operation and removal of existing Nexus UI branding require prior written authorization. Earlier Apache-2.0 grants and third-party licenses remain unchanged. Contributions require explicit agreement permitting commercial use and future relicensing. See [LICENSING.md](LICENSING.md). Authorization contact: **cary.nexilume@outlook.com**.
