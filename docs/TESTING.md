# Arise — Testing

## Layers

| Layer | Tooling | Command |
|---|---|---|
| JVM unit tests (parser, model) | JUnit 4 | `cd android && ./gradlew testDebugUnitTest` |
| Android compile gate | Kotlin compiler | `./gradlew :app:compileDebugKotlin` |
| Full debug APK | Gradle | `./gradlew assembleDebug` |
| Admin backend e2e | pytest + FastAPI TestClient | `cd admin && .venv/bin/python -m pytest server/tests -q` |
| CI | GitHub Actions | `.github/workflows/android.yml` |

## Android unit tests (`app/src/test/java/com/arise/assistant/`)

- **`IntentParserTest`** — 8 cases: open-app detection, WhatsApp “message X about Y”
  vs SMS disambiguation, media control, timer/alarm, sensitivity tagging (money/delete),
  unknown-intent fallback to AI (`needsModel`), stop-word handling.
- **`LatencyReportTest`** — `LatencyReport` accumulates named stages correctly and the
  engine’s total/latency invariant holds.

These run on the JVM only (no Android runtime needed) so they execute fast in CI.

## Admin e2e (`admin/server/tests/test_admin.py`)

10 tests through the real FastAPI app with a temp SQLite DB:
login + bad-password lockout; RBAC denial (analytics viewer cannot publish);
release lifecycle (draft → staged rollout → live) incl. `force_update` surfacing in
`/v1/app/config`; feature flags → client config; announcements; AI profiles;
metrics + crash intake → analytics summary; subscriptions;
TOTP enable → `totp-challenge` login; audit trail capture.

Run: `cd admin && .venv/bin/python -m pytest server/tests -q`

## On-device manual pass (recommended before release)

1. Grant mic / notifications / (optional) contacts / SMS; enable accessibility.
2. Settings → hands-free on; persistent mic notification appears (Android 10+).
3. Wake → “Yes?” ack; then “open WhatsApp”, “what time is it”, “set a 5-minute alarm”.
4. WhatsApp: “send Ali a WhatsApp saying on my way” → opens compose prefilled, auto-send via
   accessibility, spoken verification.
5. SMS flow with SIM; confirm delivery report drives honest SUCCESS vs PARTIAL.
6. Lock the phone → wake + command → engine must refuse politely and take no action.
7. Confirm-mode sensitive command (delete, money) requires yes/no on screen.
8. Toggle AI provider in settings; send an out-of-rule question → falls back to provider.
9. Battery: idle with hands-free on drains ≈ idle (VAD gate only, no recognizer running).

## v0.2.0 hardening & diagnostics (2026)

- **Crash shield:** every engine coroutine now routes failures to a recovery handler
  (logs + recovers to sleep) instead of killing the app; `SpeechCapture` and
  `SpeechManager` never throw, check availability first, and ignore stale callbacks;
  the wake VAD and speaker-recording paths release the mic safely. Any leftover issue
  is reported in-app rather than as a force-close.
- **AI setup simplified:** Settings → “Cloud AI” shows three presets — Google Gemini,
  OpenAI, Groq. Selecting one pre-fills endpoint + models; the user only pastes an API
  key and can tap “Test cloud connection”.
- **Diagnostics:** Settings → “Run diagnostics” reports, in plain language: microphone
  permission, Google speech service presence, TTS engine, internet, cloud-AI setup,
  notification permission, accessibility state — plus device/Android/version line.
