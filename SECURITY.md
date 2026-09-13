# Security

This application is a remote-control runtime. Pairing tokens and Accessibility access must remain user-controlled. Do not post tokens, real QR codes or private observations in public issues. Use private vulnerability reporting when enabled, or ask the maintainer for a private channel without disclosing sensitive details.

Release builds disallow cleartext traffic; pairing parser checks HTTPS; credentials use Android Keystore. Debug-only command tooling is not a production API and requires the system DUMP permission for its ADB broadcast path. Release verification rejects its manifest components.

Redaction is best-effort. Server authorization, expiry, revocation, high-risk approvals and tenant separation must also be tested against a compatible Cloud. Unit tests and manifest inspection alone cannot establish these properties end-to-end.
