# XYAA OpenJarvis Alternative

This is a separate Android application module on the `openjarvis-android-alternative` branch. It does not replace, modify, or merge into the existing XYAA app module. The Android app is a lightweight client for the official OpenJarvis API server.

## Start the OpenJarvis backend

OpenJarvis is a Python framework and inference runtime, not a model file or Android SDK. It runs on a computer/server (or a supported local machine); the Android app connects to its HTTP API.

Install OpenJarvis using its official instructions, then from its checkout:

```bash
uv sync --extra server
jarvis serve --host 0.0.0.0 --port 8000 --engine ollama --model qwen3:8b --agent orchestrator
```

The selected model must already be available in the configured inference engine. For the Ollama example, install/start Ollama and pull the model separately according to the OpenJarvis and Ollama documentation.

For a private network deployment, restrict access to trusted devices. Configure an API key with OpenJarvis server authentication before exposing the service beyond localhost or a trusted LAN. Prefer HTTPS for any network outside a private development environment.

## Build the Android app

Open this repository in Android Studio and select the `jarvis-app` run configuration, or build the module:

```bash
./gradlew :jarvis-app:assembleDebug
```

APK output: `jarvis-app/build/outputs/apk/debug/jarvis-app-debug.apk`.

## Connect a phone

- Android emulator: default URL `http://10.0.2.2:8000`.
- Physical phone: enter the computer's private LAN address, e.g. `http://192.168.x.x:8000`, and ensure both devices are on the same trusted network.
- Enter a model ID served by OpenJarvis and an API key only if server authentication is enabled.

The app stores connection settings in app-private SharedPreferences. The optional API key is stored locally by Android app storage; do not use this simple prototype with a valuable production secret. The manifest currently permits cleartext HTTP for local-network development; use HTTPS and disable cleartext for production.

## Current scope

- Independent launcher app ID: `ai.xyaa.openjarvis`.
- Compose chat UI, editable server URL/model/API key, bounded conversation history, and non-streaming chat completions.
- Uses OpenJarvis `POST /v1/chat/completions` with its documented OpenAI-compatible payload.
- No OpenJarvis Python runtime is embedded in Android; inference and agent orchestration run on the configured OpenJarvis host.
- Device skills, wake word, live voice, and XYAA's local memory are not copied into this prototype.

## Upstream

OpenJarvis source and docs: https://github.com/open-jarvis/OpenJarvis
API server docs: https://github.com/open-jarvis/OpenJarvis/blob/main/docs/deployment/api-server.md
