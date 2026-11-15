# XYAA Android app

Native Kotlin + Jetpack Compose Android app.

## Build
- Android Studio with JDK 17
- Open this repository and let Gradle sync
- Run the `:app` configuration, or run `gradle assembleDebug`

## Voice setup
XYAA uses Android's selected system Text-to-Speech engine. This keeps the app compatible with installed system TTS engines instead of bundling a large model or hard-coding a single voice.

For offline neural voices, HayaiTTS is an independent open-source Android TTS engine: https://github.com/HayaiApp/HayaiTTS
1. Review its release and license information.
2. Install it from its official GitHub releases.
3. Tap **Choose XYAA's voice** in XYAA.
4. Select HayaiTTS as the preferred engine, then choose an available voice in HayaiTTS.
5. Return to XYAA; its speech will use the system-selected engine and voice.

Voice availability, quality, and download size depend on the selected model and device. XYAA does not bundle HayaiTTS or its voice models. The spoken assistant name is normalized to “Zyaa”.

## Current scope
- Native Compose shell
- Android speech recognition
- System TTS playback and voice-engine settings shortcut
- Direct launch of uniquely matched installed apps

Gemini conversation, persistent memory, and broader device actions are not implemented yet.
