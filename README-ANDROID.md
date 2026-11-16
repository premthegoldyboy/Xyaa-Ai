# XYAA Android

Native Kotlin + Jetpack Compose assistant.

## Current capabilities
- Dark chat interface with quick-action suggestions and voice input/output.
- Offline-first skill router for app launching, web/YouTube search, maps, weather lookup, date/time, arithmetic, timers, alarms, local notes, camera, calendar, email, contacts, and common Android settings.
- Gemini 2.5 Flash conversation fallback when the user supplies their own API key in Settings.
- API key encrypted locally with an AES-GCM key held in Android Keystore.

## Setup
1. Install the debug APK from the Android Actions artifact.
2. Open Settings in XYAA and add a Gemini API key to enable general AI conversation.
3. Built-in device skills do not require an API key or network connection (web actions naturally need connectivity).

## Safety and privacy
- XYAA opens the system dialler rather than placing calls automatically.
- Timers and alarms open Android's own confirmation/setup UI.
- Notes remain in app-local preferences.
- The Gemini key is encrypted at rest with Android Keystore. Prompts are sent to Google's Gemini API when AI fallback is used.
- This is an early foundation. Test each device action on the target phone; Android vendors may handle intents differently.


## Seven-upgrade engineering track

The seven upgrades are being developed as a coordinated roadmap. Current branch foundations:

- **Conversation memory:** bounded, on-device recent-turn storage (24 turns; 1,800 characters per turn) with an explicit clear API. API keys are not stored in this memory store.
- **Agent coordination:** deterministic specialist router interface. Specialists must be injected by app code; it does not claim independent agents or external execution.
- **Voice performance:** timing counters for first-audio readiness and accumulated inter-chunk playback gaps. Device profiling is still required.
- **Wake word:** not yet enabled. A production implementation must use an explicit opt-in, visible microphone foreground service, and a tested on-device wake-word model.
- **Android integrations:** existing intent-based skills remain the supported integration surface; broader integrations need per-feature permissions and user confirmation.
- **Background tasks:** not yet enabled. Scheduling must use Android-supported background APIs and visible notifications, with user-controlled cancellation.
- **Real-time voice:** current speech-recognition intent + streamed text-to-speech is not full-duplex audio. A true live session needs a streaming audio transport and lifecycle handling.

These items are intentionally marked by implementation status rather than presented as completed capabilities. Build and device validation are required before release.
