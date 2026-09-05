# Arise · Hands-Free, Siri-Style Android AI Agent

**Fast · Hands-free · Custom wake word · Optional speaker verification · Maximum legitimate Android control · Cloud AI when needed · Low resource use · Dimensional identity · Secure administration**

> *“Arise.” → “Yes?” → “Send Ali a WhatsApp message saying I’ll be on my way.” → Verified → spoken result.*

Arise is a lightweight Siri-style assistant for low-end Android phones (Android 8.0+, API 26). It is built around one pipeline and refuses to claim success without verification:

```
WAKE → LOGO → VERIFY(optional) → UNDERSTAND → ROUTE → EXECUTE → VERIFY → RESPOND → RESULT
```

---

## Contents

| Area | Where |
|---|---|
| Android app (Kotlin) | [`android/`](android/) |
| Admin backend + dashboard (FastAPI) | [`admin/`](admin/) |
| Architecture / engineering notes | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| Security & privacy model | [`docs/SECURITY.md`](docs/SECURITY.md) |
| Latency & performance strategy | [`docs/LATENCY.md`](docs/LATENCY.md) |
| Build toolchain scripts | [`toolchain/`](toolchain/) |

---

## Highlights

- **Wake word (configurable, default “Arise”).** Low-power VAD gate runs continuously; the real speech recognizer only activates when an utterance is detected — no heavy model running in the background.
- **Push-to-talk & text fallback** so everything works without the wake word too.
- **Optional local speaker verification** — an explicit convenience layer (settings → enroll voice). Stated honestly in-app: *not* a secure biometric, recordings never leave the device, one tap deletes the profile.
- **Three-level execution, fastest legitimate method first:**
  1. **Native Android** — open apps, volume, flashlight, brightness, alarms, notifications, device status (official APIs + intents).
  2. **App integrations** — WhatsApp (deep-link pre-fill → accessibility press-send), SMS (native `SmsManager`, verified by delivery report), calls (dialer only — Arise never places silent calls), email (compose), browser search, web weather (Open-Meteo, no key).
  3. **Universal agent** — an `AccessibilityService` reads the UI tree and can click, long-click, type, scroll, swipe, Back/Home, take screenshots only on explicit commands. Screen contents never leave the device.
- **AIProvider abstraction** — OpenAI-compatible endpoints (OpenAI, OpenRouter, Groq, Together, Azure, Ollama/LM-Studio as “local”), fast + powerful model tiers, structured tool-call JSON. Simple commands bypass AI entirely.
- **Tool registry** with parameter validation, capability checks, honest status (`SUCCESS/PARTIAL/BLOCKED/NO_PERMISSION/FAILED/CANCELLED`) and *no* false success.
- **Dimensional logo** — pure-Canvas, hardware-accelerated glass orb with depth, particles, parallax, per-state motion (Idle/Wake/Verify/Ack/Listen/Process/Speak/Confirm/Success/Error), voice-reactive levels, quality & reduced-motion settings, automatic battery-saving fallback.
- **Latency instrumentation** — wake/STT/AI/exec/verify/response breakdowns on the debug panel and in local logs.
- **Lock-screen safe** — Arise never bypasses a lock screen; while locked it performs no external action (refuses politely) and uses only official Android mechanisms.
- **Secure control plane** — FastAPI admin backend + dashboard with Argon2-class hashing, TOTP MFA, HMAC-signed rotating sessions, rate limiting, roles (`super_admin`, `release_manager`, `support_admin`, `billing_admin`, `analytics_viewer`), full audit log, releases/staged rollouts, feature flags, AI profiles, subscriptions, aggregate metrics & crash intake, announcements. No credentials live in the APK.

---

## Repository layout

```
arise/
├─ android/                  # Kotlin/Android Studio project (AGP 8.5, Kotlin 1.9, minSdk 26)
│  └─ app/src/main/java/com/arise/assistant/
│     ├─ MainActivity.kt     # home: logo, status, chat, mic/wake/stop, text input
│     ├─ SettingsActivity.kt # all settings (built programmatically)
│     ├─ engine/             # AriseEngine FSM + LatencyReport + model types
│     ├─ intent/             # fast local intent parser (rule-first, no AI for simple commands)
│     ├─ tools/              # ToolRegistry + native / access / messaging / info executors
│     ├─ access/             # AccessibilityService (Level-3 universal agent)
│     ├─ speech/  audio/  speaker/   # TTS, STT capture, wake VAD, speaker verifier
│     ├─ ai/                 # AIProvider + OpenAI-compatible client + control-plane client
│     ├─ ui/                 # AriseLogoView (dimensional logo) + chat adapter
│     ├─ service/  billing/  # wake foreground service, boot receiver, Play Billing wrapper
│     └─ util/  log/  settings/
├─ admin/
│  ├─ server/app.py          # FastAPI admin control plane (single file, SQLite)
│  ├─ server/static/index.html  # dependency-free dashboard
│  ├─ server/tests/          # pytest e2e (10 tests, all passing)
│  └─ server/requirements.txt
├─ toolchain/                # SDK/JDK bootstrap scripts used for CI-class builds
├─ docs/                     # ARCHITECTURE, SECURITY, LATENCY, PHASES, TESTING
├─ .github/workflows/        # CI
└─ LICENSE
```

---

## Build the Android app

Requirements: JDK 17, Android SDK (platform 34, build-tools 34.0.0), Gradle 8.7+.

```bash
cd android
./gradlew assembleDebug        # produces app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # local unit tests (intent parser, latency report)
```

> The `gradle-wrapper.jar` is generated by `gradle wrapper` (see below); CI uses the same wrapper.

**Verified in this sandbox:** JDK 17 + SDK 34 + Gradle 8.7 compile cleanly on a 2-core / 2 GB machine (swap recommended, see `toolchain/setup-android.sh`).

---

## Run the Admin backend

```bash
cd admin
python3 -m venv .venv && .venv/bin/pip install -r server/requirements.txt
ARISE_ADMIN_SECRET="$(openssl rand -hex 32)" \
ARISE_ADMIN_EMAIL="you@example.com" \
ARISE_ADMIN_PASSWORD="$(openssl rand -base64 18)" \
ARISE_CLIENT_SECRET="$(openssl rand -hex 24)" \
  .venv/bin/uvicorn server.app:app --host 0.0.0.0 --port 8000
```

Then open `http://localhost:8000/` (dashboard) or `/docs` (OpenAPI). Tests:

```bash
cd admin && .venv/bin/python -m pytest server/tests -q   # 10 passed
```

**Environment variables (never hardcode in the APK):**

| Var | Purpose |
|---|---|
| `ARISE_ADMIN_SECRET` | HMAC key for admin session tokens (≥ 24 chars) |
| `ARISE_ADMIN_EMAIL` / `ARISE_ADMIN_PASSWORD` | seeded root Super Admin on first run |
| `ARISE_CLIENT_SECRET` | shared secret the Android app sends as `X-Arise-Secret` |
| `ARISE_DB` | SQLite path (default `admin/arise_admin.db`) |

The Android app reads control-plane values through `ControlPlaneClient` (`/v1/app/config`) — releases/min-version/feature flags/announcements — and posts opt-in aggregate metrics + crash summaries to `/v1/app/metrics` and `/v1/app/crash`. Telemetry is off by default and contains no content.

---

## Behavior rules enforced in code

1. Never bypass authentication/lock screens/PIN/biometrics.
2. No silent recording — persistent “Microphone in use” notification & mic indicator while hands-free.
3. Sensitive intents (money, deletion, security, factory reset) are refused or confirmed; never auto-performed.
4. Confirmations default to *sensitive-only*; configurable to always/never.
5. Tools verify before reporting `SUCCESS`; anything unverifiable is `PARTIAL` and spoken honestly.
6. Accessibility data stays on-device; screenshots only on explicit command.
7. WhatsApp flow: open compose (pre-filled) → press Send via accessibility → confirm the text left the input before saying “sent”.
8. Logs redact secrets; diagnostics are opt-in and anonymized.

---

## Roadmap / phased build

The project was developed in the 22 ordered phases from the product spec — see [`docs/PHASES.md`](docs/PHASES.md) for the mapping of every phase to code + build/test step. Compile was verified incrementally (resources → Kotlin → unit tests → full APK).

---

## Contributing

PRs welcome. Keep tools honest (status reflects reality), keep cloud calls minimal, and add a unit test with any parser/registry change.

## License

Apache-2.0 — see [LICENSE](LICENSE).
