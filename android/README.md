# OpenJarvis Android client

A native Android client for the upstream OpenJarvis server. Agent orchestration, inference, tools, and memory remain in the pinned upstream `openjarvis/` submodule; this app does not reimplement them.

## Backend

From the repository root after cloning with submodules:

```bash
cd openjarvis
uv sync --extra server
uv run jarvis serve --host 0.0.0.0 --port 8000
```

On an Android emulator, `http://10.0.2.2:8000` reaches the host computer. On a physical phone, enter the computer's LAN IP (for example `http://192.168.1.10:8000`) and keep both devices on the same trusted network.

## Build

Open this `android/` directory in Android Studio (JDK 17, Android SDK 35), or run `gradle :app:assembleDebug` from this directory.

The app uses OpenJarvis's OpenAI-compatible `POST /v1/chat/completions` endpoint and `GET /health`. It supports a configurable server URL, model, and optional API key. The first client release uses non-streaming chat; streaming and model discovery are follow-up work.

Use HTTPS and a secured server for connections outside a trusted local network.
