# Arise — Phase-by-Phase Build Map

Development was deliberately incremental: every phase ended in a state that compiles and,
where sensible, has a JVM unit test. This file maps the 22 product phases to code,
build/test evidence, and any acceptance notes.

Legend: ✅ implemented in `android/` — ✳ implemented in `admin/` — 🔬 JVM unit test — 🛠 compiles with Gradle.

| # | Phase | Where | Evidence |
|---|---|---|---|
| 1 | Project scaffold, minSdk 26, Kotlin, AGP | `android/` | 🛠 `:app:compileDebugKotlin` |
| 2 | Settings model (wake word, confirm level, speaker verify on/off, logo style) | `settings/Settings.kt`, `engine/model.kt` | 🛠 |
| 3 | Core data model + honest status enums | `engine/model.kt` | 🔬 `LatencyReportTest` |
| 4 | Intent parser — rule-first, regex, no AI for common commands | `intent/IntentParser.kt` | 🔬 `IntentParserTest` (8 cases) |
| 5 | Tool registry with capability + validation | `tools/ToolRegistry.kt`, `tools/ToolCore.kt` | 🛠 |
| 6 | Native executors (apps, media, volume, flashlight, alarms, time, status) | `tools/NativeExecutors.kt` | 🛠 |
| 7 | Accessibility service UI tree + Level-3 executors (find/click/type/scroll/back/home/read/screenshot/wait) | `access/AriseAccessibilityService.kt`, `tools/AccessExecutors.kt` | 🛠 |
| 8 | Messaging executors (WhatsApp compose+send, SMS verified, dialer-only, email) | `tools/MessagingExecutors.kt` | 🛠 |
| 9 | TTS manager + STT capture wrapper | `speech/SpeechManager.kt`, `speech/SpeechCapture.kt` | 🛠 |
| 10 | Wake-word VAD detector (adaptive floor, hands mic to recognizer) | `audio/WakeWordDetector.kt`, `audio/RecordSample.kt`, `audio/AudioAnalyzer.kt` | 🛠 |
| 11 | Speaker verification (enrollment/verify/delete; honesty labeled “convenience, not security”) | `speaker/SpeakerVerifier.kt` | 🛠 |
| 12 | AIProvider interface + OpenAI-compatible client + control-plane client | `ai/AIProvider.kt` | 🛠 |
| 13 | AriseEngine FSM: wake → ack → capture → route → execute → verify → speak → follow-up | `engine/AriseEngine.kt` | 🔬 partial (`LatencyReport`); 🛠 |
| 14 | Confirmation flow + sensitive-token gating | `engine/AriseEngine.kt`, `intent/IntentParser.kt` | 🛠 |
| 15 | Lock-screen-safe policy (refuses external actions while locked) | `engine/AriseEngine.kt` | 🛠 |
| 16 | Chat/status UI + MainActivity + push-to-talk + text input | `MainActivity.kt`, `ui/ChatAdapter.kt`, `res/layout/*` | 🛠 |
| 17 | Dimensional logo, state-reactive, quality + reduced-motion | `ui/AriseLogoView.kt` | 🛠 |
| 18 | Settings UI (programmatic; permissions w/ rationale, enrollment, privacy, logs, billing) | `SettingsActivity.kt` | 🛠 |
| 19 | Wake foreground service + boot receiver + persistent mic notification (Android 10+) | `service/AriseForegroundService.kt`, `service/BootReceiver.kt` | 🛠 |
| 20 | Play Billing wrapper (optional support tier) | `billing/Billing.kt` | 🛠 |
| 21 | Admin control plane (FastAPI + SQLite) + dashboard | `admin/server/app.py`, `admin/server/static/index.html` | ✳ ✅ pytest 10/10 |
| 22 | Repo packaging: docs, LICENSE, CI, git history | repo root + `.github/` | ✅ in this repo |

## Verified build & test trail (sandbox, 2-core / 2 GB + swap)

```
:app:compileDebugKotlin   → BUILD SUCCESSFUL (all modules)
:app:assembleDebug        → app-debug.apk produced
:app:testDebugUnitTest    → IntentParserTest, LatencyReportTest pass
admin: pytest server/tests -q → 10 passed
```

Toolchain bootstrap (JDK 17 Temurin, SDK 34, build-tools 34.0.0, Gradle 8.7):
`toolchain/setup-android.sh` + `setup-android-2.sh`. Low-RAM Gradle settings live in
`android/gradle.properties` (single in-process compiler JVM, caching on, workers=2).
