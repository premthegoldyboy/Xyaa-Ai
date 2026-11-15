# XYAA Android app

This is the native Android app foundation, separate from the existing browser/PWA version.

## Build
- Open this repository in Android Studio.
- Open the project root (the folder containing `settings.gradle.kts`).
- Use JDK 17 and allow Gradle sync.
- Run the `:app` configuration on an Android device or emulator.

## Current foundation
- Native Kotlin + Jetpack Compose interface.
- Android speech-recognition prompt and system text-to-speech.
- Direct app launching: commands such as “open YouTube” launch a uniquely matched installed app immediately, without a confirmation step.
- If several apps match, XYAA asks which app name was intended. If no app is installed, it says so.

## Safety and Android limits
Launching an app is a low-risk action and does not ask for confirmation. Actions that communicate externally, place calls, spend money, or share personal information should use separate safeguards.
Android controls which installed apps are visible and launchable. Voice input availability depends on the device's speech-recognition service and microphone permission.

This is an initial native shell, not yet the full AI assistant. Gemini integration, persistent memory, neural voice models, and additional skills are subsequent milestones.
