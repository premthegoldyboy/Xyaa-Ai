# OpenJarvis Android client

A native Kotlin + Jetpack Compose client for the original OpenJarvis server. The upstream Python project is pinned in the repository's `openjarvis/` Git submodule. Inference, agent orchestration, tool execution, and long-term memory remain implemented by OpenJarvis itself.

## Current Android capabilities

- Connect to an OpenJarvis server using a configurable URL.
- Discover and select chat models through `GET /v1/models`.
- Chat through OpenJarvis's `POST /v1/chat/completions` API.
- Test server connectivity through `GET /health`.
- Keep a local conversation history across app restarts.
- Browse managed agents and pause/resume them when the server's Agent Manager is configured.
- Browse the server's registered tools.
- View memory backend status and search long-term memory.
- Encrypt the saved API key with an AES-GCM key held by Android Keystore.

The Android client currently uses non-streaming chat. Agent and tool operations execute on the configured OpenJarvis server; the app does not pretend those operations run locally.

## Backend setup

Clone the branch and its upstream submodule:

```bash
git clone --branch openjarvis-android-alternative --recurse-submodules https://github.com/premthegoldyboy/Xyaa-Ai.git
cd Xyaa-Ai/openjarvis
uv sync --extra server
uv run jarvis serve --host 0.0.0.0 --port 8000
```

On an Android emulator, `http://10.0.2.2:8000` reaches the host computer. On a physical phone, enter the computer's LAN IP (for example `http://192.168.1.10:8000`) and keep both devices on the same trusted network.

## Build

Open the `android/` directory in Android Studio with JDK 17 and Android SDK 35, or run:

```bash
gradle -p android :app:assembleDebug
```

GitHub Actions builds the debug APK on pushes to this branch. The APK is published as the `openjarvis-android-debug-apk` workflow artifact.

## Security notes

- Use HTTPS and a secured OpenJarvis server for connections outside a trusted local network.
- The app currently permits cleartext HTTP to support local development and LAN server connections. Do not expose an unauthenticated server to the public internet.
- The optional API key is encrypted at rest with Android Keystore. Use a unique server credential and rotate it if the device is lost.
- Agent pause/resume controls affect the server-side managed agent. Review your server's security and capability policy before enabling powerful tools.
