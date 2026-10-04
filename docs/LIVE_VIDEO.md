# Live phone video

Live video requires compatible Nexus Cloud, Console and Android builds. The
published APK's release notes indicate whether it includes this capability;
source support does not automatically upgrade an older installed APK.

## Start and stop

1. Pair the phone and enable Nexus Mobile Control.
2. Open the device's **Control** section in Console and choose **Start live video**.
3. On the phone choose **Share screen**, then approve Android's native recording
   or casting prompt. Each new session requires new consent.
4. Live video and **Screenshot** share one phone preview. Capturing or switching
   to Screenshot stops projection; it never leaves a hidden live share running.
   Once frames arrive, optionally enable **Control live screen**. Tap, drag or
   hold on the displayed screen. Device approvals and command authorization still
   apply; video consent alone does not approve every action.
5. Choose **Stop video** in Console or **Stop sharing** on the phone. Leaving
   Control, pausing sync or removing pairing also ends sharing.

There is no microphone/audio track or Cloud video recording. Sensitive detected
screens stop sharing. Unknown windows temporarily drop frames and stop sharing
after five seconds. This is best-effort detection: stop before sensitive work.

## Deployment

Console must use HTTPS, except localhost development. Release Android pairing
also requires HTTPS with an Android-trusted certificate. Never disable certificate
or hostname verification.

SDP and ICE use the existing authenticated HTTPS API; no additional signaling
port is needed. WebRTC carries encrypted media directly where possible. Phones
behind NAT commonly need TURN. Nexus Cloud's integrated TURN startup and operator
configuration must advertise reachable addresses; a loopback or private TURN address is not
a public deployment. Operators must configure DNS, firewall reachability and
the bounded relay-port range for their deployment, and verify authenticated
UDP/TCP TURN access. TURN credentials are short-lived and caller/session-bound;
never publish its shared signing secret.

When TURN is configured, both endpoints use relay-only ICE. Browser mDNS/host
candidates are not relied upon across NAT. A brief ICE disconnection pauses
controls and gets five seconds to recover; an unsuccessful negotiation ends
within 30 seconds after consent/answer instead of hanging indefinitely.

## Troubleshooting

| Symptom | Recovery |
| --- | --- |
| Waiting for permission | Open Nexus Mobile, choose Share screen and approve Android's prompt |
| Permission declined | Start a new session and approve it on the phone; grants are not replayed |
| Connected but no moving frames | Check WebRTC/TURN reachability; signaling success alone is not media success |
| Screen blocked | Leave the sensitive screen, then request a new sharing session |
| Control disabled | Wait for fresh frames and geometry; enable control explicitly |
| Device offline | Resume phone sync and Accessibility, then start a new session |

## Verification scope

The existing real-emulator harness supports `--cloud-video` against an already
running Cloud and Console. It pairs a real debug APK, approves the native prompt,
checks decoded RTP frames, and verifies browser-origin gestures against native
device effects. Debug emulator-loopback HTTP is only a controlled development
fixture, not public HTTPS, NAT or physical-device certification. Do not label a
mock signaling test as actual phone-video acceptance.

`--cloud-video --video-nat` reuses the same official Cloud and managed TURN. It
temporarily attaches an internal RFC 2544 media network, retains private-peer
denial, verifies relay/relay candidates and decoded phone RTP with normal browser
mDNS, tests interruption and real phone gestures, then restores TURN and removes
the fixture network. It certifies this isolated NAT topology, not public Internet
firewalls, carrier networks or a physical phone.

The NAT fixture disables the UDP client listener to verify TCP TURN fallback
using real decoded video. It also verifies browser-origin tap, long-press and
swipe effects, a brief relay interruption, projection cleanup, and a protected
screenshot in the same phone preview. It never disables the private-peer deny
rules or changes the host firewall.

The same entry point accepts `--cloud-mobile-sdk` instead of `--cloud-video` to
exercise the workspace SDK against the existing Cloud Run delegate and paired
Android emulator. It checks sync/async actions, caller approval for Unicode text,
native tap/long-press/swipe/Home/recent-apps effects, protected capture, scope and
token rejection. It does not launch another Cloud or fake Android command
results. The report contains no pairing/delegate credentials or screen content.
