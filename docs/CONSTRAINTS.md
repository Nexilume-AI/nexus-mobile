# Nexus Mobile Constraints

This document defines the non-negotiable constraints for `nexus_mobile`, the
Android accessibility bridge that lets Nexus agents observe and control a phone.

## Trust Boundary

`nexus_mobile` is a high-risk runtime. Treat every mobile command as remote
control of a user's personal device.

- The Android app is the only component allowed to hold the mobile pairing
  token.
- The Android app must receive pairing values through the `nexus-mobile://pair`
  QR deep link and display an explicit server/device confirmation before saving.
- Release pairing URLs must use HTTPS. Local HTTP is limited to debug builds and
  recognized local-development hosts.
- Pairing values must be encrypted at rest with Android Keystore.
- Nexus Server stores only `token_hash` and `token_prefix`; plaintext tokens are
  returned once during pairing or rotation.
- Agent/API-key credentials must never be stored in the Android app.
- Mobile pairing tokens must not be accepted on normal Nexus control-plane or
  model-gateway endpoints.
- Normal Nexus API keys must not be accepted on Android device callback
  endpoints.

## Permission Model

Nexus Server must enforce these permission levels:

- `mobile.read`: list/read device status, observations, and command history.
- `mobile.use`: create commands and call the device MCP tools.
- `mobile.admin`: create/delete devices, rotate tokens, approve/reject commands,
  and change approval mode.

Tenant owners/admins inherit these permissions through the existing IAM role
system. Device creators may manage their own devices, but shared multi-user
control must be implemented with explicit Nexus IAM grants.

## Command Lifecycle

Commands must move through this state machine:

```text
pending_approval -> queued -> running -> succeeded
pending_approval -> rejected
queued -> canceled
queued -> running -> failed
queued -> canceled when expired
```

Rules:

- Android clients may only fetch `queued` commands.
- Android clients may not fetch any command until the paired heartbeat reports
  the Accessibility capability. The server returns `MOBILE_DEVICE_NOT_READY`.
- Fetching a command atomically changes it to `running`.
- Android clients may only complete `running` commands.
- Expired `pending_approval` or `queued` commands are canceled before dispatch.
- Commands in terminal states must not be re-dispatched.

## Risk And Approval

The default approval mode is `confirm_high_risk`.

- `manual`: every command requires approval.
- `confirm_high_risk`: only high-risk commands require approval.
- `auto`: commands are queued immediately.

Risk classification:

- Low: observe, wait, back.
- Medium: tap, swipe, open app, and one-time screen capture.
- High: text entry and any action that appears to confirm payment, deletion, or
  irreversible submission.

High-risk examples include text or labels containing `pay`, `send`, `delete`,
`购买`, `支付`, or `删除`.

## Observation Privacy

Observations are useful to agents but can contain private data.

- Password fields, payment screens, OTP screens, and secure surfaces must be
  redacted when detectable.
- Screenshot support must remain optional. Accessibility tree observation must
  work without screenshots.
- Screenshots are captured only after an explicit Console request on Android
  11 or newer, and only the latest image is retained for up to five minutes.
- Screenshot bytes must not be retained in command history.
- `FLAG_SECURE` screens must not be bypassed.
- Visible password, verification-code, PIN, card, and payment contexts must
  reject screenshot capture.
- Observations should be bounded in size and depth before upload.

## Android Accessibility Boundaries

The Android app may use:

- `AccessibilityService` for UI tree observation, click, text input, global back,
  gestures, and Android 11+ one-time screenshot requests.
- Foreground service for command polling and result reporting.

The Android app must not:

- Request device admin, root, ADB, or notification listener permissions in the
  default build.
- Hide its foreground-service notification.
- Execute commands before the user explicitly enables accessibility and pairs
  the device.
- Require the user to type pairing tokens on the phone. The phone should scan
  the Nexus Web QR code and save the deep-link payload automatically.
- Attempt to control apps or screens that Android prevents it from observing.

## Agent Tool Surface

Agent-facing tools must stay semantic and narrow:

- `mobile_observe`
- `mobile_tap_text`
- `mobile_tap_coordinates`
- `mobile_type_text`
- `mobile_swipe`
- `mobile_press_back`
- `mobile_open_app`
- `mobile_wait_for_state`

Do not expose arbitrary Android intents, shell execution, filesystem access, or
raw accessibility node mutation to agents without a separate security review.

## Auditability

Nexus Server must audit:

- Device creation, update, deletion, and token rotation.
- Command creation, approval, rejection, cancellation.
- MCP tool calls that create commands.

Audit logs must not contain plaintext pairing tokens, passwords, OTPs, or full
screen captures.

## Required Test Coverage

Every mobile control change should preserve these tests:

- Pairing token is returned once and redacted from list/detail responses.
- Wrong or rotated tokens cannot call device endpoints.
- High-risk commands require approval before Android polling.
- Full lifecycle works: create device, heartbeat, MCP command, approval,
  Android fetch, Android result.
- MCP initialize/tools/list/tools/call return valid JSON-RPC responses.
- Android command polling does not dispatch pending approval or expired commands.
- A paired device remains `setup_required` and cannot poll commands before
  Accessibility is active.
- Android pairing parsing, observation redaction, and structured command JSON
  pass local unit tests.

## Local Android Build Assumptions

This workspace is configured for:

```text
sdk.dir=D:\Android\Sdk
org.gradle.java.home=D:\Program Files\Android\Android Studio\jbr
compileSdk=36
targetSdk=36
android.overridePathCheck=true
```

Use the checked-in `gradlew.bat`. When the workspace path contains non-ASCII
characters, pass `-PnexusMobileBuildDir=D:/tmp/nexus-mobile-build` to keep
Gradle's test-worker classpath in an ASCII path.
