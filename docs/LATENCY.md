# Arise — Latency & Performance Strategy

Target device class: **low-end Android 8+**, often 2 GB RAM, single-core-ish CPU, flaky
networks. Everything below is about keeping the *perceived* response under ~1.5 s for
common commands while using almost no idle power.

## Why it feels fast

1. **No always-on speech recognizer.** A tiny VAD gate (`audio/WakeWordDetector.kt`)
   buffers short mic frames on one daemon thread and computes a noise-floor-adaptive
   energy envelope. The heavy `SpeechRecognizer` only starts *after* speech is detected —
   so idle CPU is a fraction of 1% of one core.
2. **Local rule router runs first.** `intent/IntentParser.kt` matches commands against
   regex rules on a background dispatcher. “Open WhatsApp”, “stop music”, “what time is
   it”, “send a text to X”, “arise, set a 10 minute alarm” never touch the network.
3. **Fastest legitimate execution path first**: native API → app intent → accessibility.
   No action is attempted on a slower path while a faster one exists.
4. **AI is the fallback, not the default.** Only if the local router marks the request
   `needsModel` does the engine call an AI provider. Simple commands stay on-device.
5. **Wake acknowledgment is early.** As soon as the wake word matches, the engine starts
   an acknowledgment utterance and hands the mic to the recognizer for the *next* phrase —
   the ack and the command capture overlap in user perception.

## Latency instrumentation

`engine/AriseEngine.kt` records a `LatencyReport` per turn:

| Field | Meaning |
|---|---|
| `wakeMs` | VAD-detected speech burst → wake matched |
| `ackMs` | wake matched → ack started speaking |
| `sttMs` | recognizer started → final text received |
| `parseMs` | text → intent decision (local rules) |
| `aiMs` | intent sent to AI → full reply/tool-call received (0 when local) |
| `execMs` | tool executed (native intent launch, SMS send, etc.) |
| `verifyMs` | verification step (UI tree re-check, broadcast result) |
| `totalMs` | wake (or push-to-talk tap) → spoken response started |

The chat debug panel shows per-turn breakdown; the ring buffer keeps history.
On a typical mid-low device: wake→ack ≈ 150–400 ms, local command → response
start ≈ 600–1000 ms, first-token streaming ≈ 300–800 ms after AI call.

## Power & memory rules (enforced in code)

- One `AudioRecord` at a time; TTS, STT, and the wake VAD never hold the mic simultaneously.
- When TTS speaks, the engine waits for speech end before the next capture (`waitForSpeechEnd`)
  so Arise never hears itself and loops.
- The engine is a single-threaded FSM on `Dispatchers.Main`; no locks, no races.
- Network/AI runs on `Dispatchers.IO`; accessibility actions are posted to the main thread
  via `onMain` (framework requirement).
- Idle state = VAD gate only. The recognizer is stopped immediately when not needed.
- `AriseLogoView` runs a lightweight choreographer; quality drops to “low” under battery
  saver / thermal hint, and `reduced_motion` disables animation entirely.

## What does NOT run in the background

- No on-device LLM, no model inference loop (by design — see ARCHITECTURE).
- No audio is persisted (wake and command audio are processed live).
- The foreground “listening” service is a thin host + notification; it does no audio work
  itself. It exists to keep the mic indicator visible on Android 10+ while hands-free is on.
