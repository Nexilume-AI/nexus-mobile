# Server-managed Cloud pairing address

The Android app reads its Cloud address from the Console pairing QR. It does not
ask the phone user to enter a server address. The Console now uses the address
returned by Cloud on device creation and token rotation; it does not infer an
API port from the browser or substitute the Android emulator host.

## Deployment configuration

Set `NEXUS_PUBLIC_BASE_URL` to the phone-accessible HTTPS **origin**, for example
`https://cloud.example.com` or `https://cloud.example.com:8443`. An administrator
may optionally set `NEXUS_MOBILE_PUBLIC_BASE_URL` when phones use a different
HTTPS origin. An empty override uses `NEXUS_PUBLIC_BASE_URL`.

Use the ordinary public listener, not an Edge-only mTLS listener. Install a
certificate the Android device trusts. Loopback, wildcard bind addresses,
link-local, multicast, emulator `10.0.2.2`, HTTP, URL credentials, paths, queries
and fragments are not accepted for Console pairing. Private LAN HTTPS addresses
are accepted, but the phone must be able to reach that LAN. Syntax validation
does not prove connectivity, DNS resolution or certificate trust.

Restart Cloud after changing its deployment environment, then generate a fresh
QR. Existing paired devices are not silently redirected or reconfigured.
The normal Windows Cloud launcher already sets `NEXUS_PUBLIC_BASE_URL` to its
public HTTPS listener; a separate Mobile setting is not required in that case.
Community deployments derive this value from their configured public origin.
For Community Docker Compose, use `NEXUS_ORIGIN=https://your-domain.example` and
the existing `compose.https.yaml` deployment described in the Community guide.
The default loopback-only HTTP Community installation cannot pair a real phone;
do not use the emulator address as a workaround.

## Compatibility and recovery

Device creation and rotation keep their existing token fields and add
`pairing_base_url` and `pairing_error`. When configuration is unavailable, the
device record remains available but Console does not generate a QR. Correct the
deployment configuration and use **Generate QR** again (this rotates the token).
The error never echoes potentially credential-bearing configuration.

A new Console connected to an older Cloud that lacks these fields displays an
upgrade/configuration prompt instead of guessing an address. Old Android clients
continue to consume the same `nexus-mobile://pair` payload; no app upgrade or
Android certificate-validation bypass is introduced.
