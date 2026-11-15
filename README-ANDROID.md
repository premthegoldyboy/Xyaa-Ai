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
