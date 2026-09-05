# Arise — Security & privacy model

## Never

- Bypass lock screens, PIN/password/pattern, biometrics, encryption or any authentication.
- Perform sensitive actions silently (money, deletion, security changes, factory reset).
- Secretly record audio or video; take screenshots without an explicit command + user-enabled feature.
- Read messages/photos/files/credentials or unrelated apps without explicit authorization.
- Exfiltrate UI content or audio anywhere (except the exact text you send to *your* configured AI provider).
- Require or obtain root. Refuse `WRITE_SECURE_SETTINGS`, `SYSTEM_ALERT_WINDOW`-style privileged paths.

## While the phone is locked

The wake listener may stay active only if the user enabled hands-free. When a
command arrives while the keyguard is up, the engine refuses external actions and
speaks “Your phone is locked…”. Authentication is always handled by Android's
official flow — Arise only observes `KeyguardManager` state.

## Android permissions (all explained in Settings)

| Permission | When |
|---|---|
| `RECORD_AUDIO` | voice features (wake/commands/enrollment) |
| `POST_NOTIFICATIONS` (13+) | persistent “listening” indicator |
| `READ_CONTACTS` | only to resolve “send Ali a WhatsApp” on-device |
| `SEND_SMS` | only to send SMS directly; otherwise compose fallback |
| `CAMERA` | only flashlight toggle |
| Accessibility | only for Level-3 actions the user asked for |

## Confirmation policy

Default *sensitive-only*. `financial_action`, `delete_content`,
`factory_reset` never auto-execute; recipients/purchases that are ambiguous
require a spoken or on-screen yes. Confirmation timeout defaults to 12 s.

## Data minimization & logging

- Wake/STT audio is processed live; no wake audio is persisted. Enrollment
  profiles are acoustic feature files in app-private storage, deletable.
- Local log ring buffer (on-device, `LogLine`) redacts secrets; contains no content.
- Diagnostics are opt-in, aggregate, device-hash-scoped — never content.
- API keys live only in encrypted app storage and an env-provided server secret; **never** in the APK or git.

## Admin backend

- Passwords: PBKDF2-HMAC-SHA256 120k iterations by default (Argon2id available).
- TOTP MFA (pyotp), HMAC-signed stateless-ish session tokens stored hashed server-side, TTL + logout invalidation, per-IP rate limiting, lockout after 5 failures.
- RBAC: `super_admin > release_manager > support_admin > billing_admin > analytics_viewer`; every privileged action writes to `audit_log`.
- Transport must be TLS in production; admin secret from env (`ARISE_ADMIN_SECRET`).

## Release integrity

Play distribution + (for external links) SHA-256 verification. The control plane
records `apk_sha256` and the app can be pointed at signed artifacts; no silent
update installation is ever performed — Android's own installer handles this.
