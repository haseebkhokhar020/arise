# Arise — Architecture

## One pipeline, one source of truth

```
mic → [VAD gate] → speech recognizer → wake word match
   → speaker verify (optional, local) → TTS "Yes?"
   → speech recognizer (command) → STT text
   → IntentParser (fast local rules)      ← simple commands never touch the cloud
   → ── known intent → route → ToolRegistry.execute → ToolResult
   └─ unknown/complex → AIProvider (fast or power tier) → optional tool calls → ToolResult
   → VERIFY (tool reports verified / partial / blocked) → speak result → follow-up or sleep
```

## Key components (Kotlin, package `com.arise.assistant`)

| Component | File | Role |
|---|---|---|
| **Engine (FSM)** | `engine/AriseEngine.kt` | Owns the lifecycle; state in `StateFlow<UiState>`; latency `LatencyReport`; all mutations on Main dispatcher |
| **Wake listener** | `audio/WakeWordDetector.kt` | Adaptive-noise-floor VAD on `AudioRecord`; hands mic to recognizer only on a speech burst; thread dies & restarts cleanly |
| **Speech capture** | `speech/SpeechCapture.kt` | One `SpeechRecognizer` wrapper; partial results → fast wake; configurable end-of-speech |
| **Intent parser** | `intent/IntentParser.kt` | Pure JVM regex rule engine; WhatsApp/SMS disambiguation; sensitive-token detection; `needsModel` fallback |
| **Tool registry** | `tools/ToolRegistry.kt` | Every action registered once; used by local router AND cloud model tool calls |
| Native executors | `tools/NativeExecutors.kt` | Level 1 (apps, media, volume, flashlight, alarms, time, status) |
| Access executors | `tools/AccessExecutors.kt` | Level 3 via accessibility (click/type/scroll/read/screenshot) |
| Messaging executors | `tools/MessagingExecutors.kt` | WhatsApp deep-link + accessibility send, SMS via `SmsManager` with delivery report, dialer, email compose |
| Info executors | `tools/InfoExecutors.kt` | Weather via Open-Meteo (no API key) |
| Accessibility service | `access/AriseAccessibilityService.kt` | UI tree snapshot; main-thread actions; screenshot capture on consent |
| AI provider | `ai/AIProvider.kt` | `AIProvider` interface + OpenAI-compatible client + control-plane client |
| TTS / speaker | `speech/`, `speaker/` | `SpeechManager`, `SpeakerVerifier` (local convenience layer) |
| Logo | `ui/AriseLogoView.kt` | Canvas dimensional mark, state + audio reactive |
| Foreground service | `service/` | Wake-word host w/ persistent mic notification; boot receiver honors opt-in |
| Billing | `billing/Billing.kt` | Google Play Billing wrapper (optional support tier) |

## Control plane

`admin/server/app.py` is a single FastAPI module backed by SQLite:
`admins`, `sessions`, `audit_log`, `releases`, `feature_flags`, `announcements`,
`ai_profiles`, `subscriptions`, `metrics_day`, `crash_reports`.

The Android app's `ControlPlaneClient` calls `/v1/app/config` (min version,
flags, announcements) and posts opt-in telemetry to `/v1/app/metrics|crash`,
authenticated by `X-Arise-Secret`.

## Threading & resource rules

- Engine: single-threaded on `Dispatchers.Main` (no locks).
- Wake VAD: one daemon thread + `AudioRecord`; recognizer/TTS own the mic one at a time (Android exclusive-mic rule).
- Network/AI/own-mic capture hop to `Dispatchers.IO`.
- Accessibility actions run through `onMain` post (framework requirement).
- Idle = VAD gate only (tiny CPU); recognizer is never left running.
