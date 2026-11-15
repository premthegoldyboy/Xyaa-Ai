# XYAA

XYAA is a browser-based voice assistant PWA.

## Publish
In GitHub open **Settings → Pages**, select **Deploy from a branch**, choose `main` and `/(root)`, then save. Open the published URL in Chrome and use **Add to Home screen**.

## Setup
1. Open Settings and paste your Gemini API key.
2. Choose a listening language and speech voice.
3. Allow microphone access.
4. Tap the microphone or type a message. Hands-free mode listens repeatedly while the page stays open.

## Features
- Gemini-powered chat with short conversation history.
- Speech input/output with language and voice selection.
- Google and YouTube search, opening web URLs, phone dialler and WhatsApp launch, timer and time.
- Input validation and confirmation before call/WhatsApp actions.
- Versioned service-worker cache and PWA install metadata.

## Limitations and security
Speech recognition differs by browser. Hands-free listening and timers are not guaranteed when the browser is backgrounded, the page is closed, or the phone suspends it. This app cannot run a permanent wake word or change Android settings. The Gemini API key is stored in browser local storage, which is not a secure secret vault; do not share the browser profile or key.