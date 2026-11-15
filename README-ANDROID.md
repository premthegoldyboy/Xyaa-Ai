# XYAA Android app

Native Kotlin + Jetpack Compose Android app.

## Build
Open the repository in Android Studio with JDK 17, sync Gradle, then run the app. CI builds a debug APK.

## Neural voice setup
XYAA uses Android's selected system Text-to-Speech engine. To use offline neural speech, install [HayaiTTS](https://github.com/HayaiApp/HayaiTTS), open **Choose XYAA's voice**, select HayaiTTS as the preferred engine, and choose an available voice inside its app. XYAA does not bundle HayaiTTS or its models. Voice choice and performance depend on the selected model and device. Spoken “XYAA” is normalized to “Zyaa”.

## Current scope
Native UI, Android speech recognition, system TTS playback/settings shortcut, and direct launch of uniquely matched installed apps. Gemini conversation and persistent memory remain future work.
