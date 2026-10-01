<div align="center">

# Nexus Mobile

**Connect your phone. Stay in control.**

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-17251d.svg)](LICENSE)
[![Documentation](https://img.shields.io/badge/Read-the_docs-b8ef73.svg)](README_GUIDE.md)
[![Cite this software](https://img.shields.io/badge/Cite-this_software-e8e9e4.svg)](#citation)
[![Repository checks](https://github.com/Nexilume-AI/nexus-mobile/actions/workflows/ci.yml/badge.svg)](https://github.com/Nexilume-AI/nexus-mobile/actions/workflows/ci.yml)

`Android` · `Explicit pairing` · `Scoped access`

**English** · [简体中文](README_zh.md)

[Highlights](#highlights) · [Quick start](#quick-start) · [Documentation](#documentation) · [Ecosystem](#ecosystem) · [Contributing](#contributing) · [Citation](#citation)

</div>

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

## Quick start

**Requirements:** Android 8.0+, a compatible Nexus Cloud, and Google Play services for the scanner flow. One-time screenshots require Android 11+.

Build with JDK 17 and Android SDK platform 36:

```sh
sh gradlew testDebugUnitTest lintRelease assembleRelease --no-daemon
```

On Windows use `./gradlew.bat` with the same arguments. Follow [RELEASE.md](RELEASE.md) to sign and verify an installable package.

> [!IMPORTANT]
> `app-release-unsigned.apk` is a build artifact, not an installable public release. Debug APKs are for controlled development, not public distribution. See the [build reference](README_GUIDE.md#build-from-source) for complete instructions and toolchain caveats.

## Pair, attach, run

1. In Nexus Console, create a Mobile device and display its pairing QR.
2. Scan in Nexus Mobile; review the identity and choose **Confirm and connect**.
3. Enable **Nexus Mobile Control** in Android Accessibility settings.
4. Allow notifications and start sync. Verify the visible foreground notification and online state in Console.
5. Attach the device to a Run and grant only the scopes the Agent needs.

**Success looks like:** the authorized device is online and a supported Agent action returns its result. Pairing is not blanket authorization for every Agent.

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
| [Cloud Community](https://github.com/Nexilume-AI/nexus-cloud-community) | Server, Web Console and bundled Cloud Relay | Main workspace |
| [Python SDK](https://github.com/Nexilume-AI/nexus-agent-sdk-python) | Agent applications and outbound Computer Runtime | Yes |
| [OpenWrt](https://github.com/Nexilume-AI/nexus-openwrt) | Edge registration and capability routing | Optional |
| [Mobile](https://github.com/Nexilume-AI/nexus-mobile) | Authorized Android device integration | Optional |
| [Documentation](https://github.com/Nexilume-AI/nexus-docs) | User guides and reference | Read online or build locally |

Repository access, release availability and compatibility determine which integrations you can install. Cloud installation does not install device runtimes.

## Contributing

Start with [CONTRIBUTING.md](CONTRIBUTING.md). Small reproducible fixes, clearer tutorials, translations and sanitized examples are welcome. Use [Issues](https://github.com/Nexilume-AI/nexus-mobile/issues) for reproducible bugs; include versions and redacted diagnostics, never credentials or private files.

Follow [SECURITY.md](SECURITY.md) for security reports. Release checks and CI are not a guarantee of production readiness on every platform.

## Citation

If this software helps your work, cite the repository and record the exact release or commit you used. [CITATION.cff](CITATION.cff) provides machine-readable software metadata; this is a **software citation**, not a claim of a peer-reviewed paper or DOI.

```bibtex
@misc{nexus_mobile,
  author       = {{Nexus contributors}},
  title        = {Nexus Mobile},
  howpublished = {\url{https://github.com/Nexilume-AI/nexus-mobile}},
  note         = {Software; specify the release or commit used}
}
```

## License

Nexus-authored source is distributed under [Apache-2.0](LICENSE). Third-party components retain their own licenses and notices. Documentation does not grant rights to separately distributed Enterprise implementation.
