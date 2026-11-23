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
- Provider keys are encrypted at rest with Android Keystore. Prompts and relevant recent-conversation or user-approved memory context may be sent to the configured Gemini, Groq, or DeepSeek provider to generate a reply.
- This is an early foundation. Test each device action on the target phone; Android vendors may handle intents differently.


## Seven-upgrade engineering track

The seven upgrades are being developed as a coordinated roadmap. Current branch foundations:

- **Conversation memory:** bounded, on-device recent-turn storage (up to 120 turns; 1,800 characters per turn) with an explicit clear API. API keys are not stored in this memory store.
- **Agent coordination:** deterministic specialist router interface. Specialists must be injected by app code; it does not claim independent agents or external execution.
- **Voice performance:** timing counters for first-audio readiness and accumulated inter-chunk playback gaps. Device profiling is still required.
- **Wake word:** implemented as an explicit opt-in foreground microphone service with a visible notification and local sherpa-onnx keyword spotting. The model downloads on first use; physical-device accuracy, battery use and OEM background behavior still need testing.
- **Android integrations:** existing intent-based skills remain the supported integration surface; broader integrations need per-feature permissions and user confirmation.
- **Background tasks:** WorkManager-backed one-time reminders are implemented with a visible notification and notification-permission checks. A broader task scheduler and reminder-management/cancellation UI are not yet implemented.
- **Real-time voice:** Gemini Live uses a WebSocket audio session with microphone PCM streaming and streamed response audio. It requires a Gemini key, microphone permission and network access; device lifecycle, interruption and provider-model compatibility still need physical-device testing. The separate speech-recognition + TTS path remains available for ordinary voice input.

These items are intentionally marked by implementation status rather than presented as completed capabilities. Build and device validation are required before release.


## XYAA Intelligence Core (early implementation)
- **Personal Memory:** explicit user-directed facts are stored separately from recent chat turns, encrypted with Android Keystore AES-GCM, and can be paused or deleted from the Memory screen. Storage is local with no background cloud sync; relevant matching facts may be included in prompts sent to the configured AI provider when you chat.
- **Code Studio:** encrypted task inbox captures development requests. Remote code execution and cloud builds are not connected yet; the app clearly reports this rather than claiming tasks have run.
- These tracks are developed independently and integrated through shared context and approval boundaries.


## Optional Firecrawl web research
- Add your own Firecrawl API key in Settings → AI Providers. XYAA stores it with Android Keystore-backed AES-GCM encryption.
- When configured, Firecrawl Search runs before Gemini Search and returns search results with cleaned page Markdown; Gemini Search and Tavily remain fallbacks.
- The report writer is grounded only in retrieved source extracts, includes source URLs, and treats webpage instructions as untrusted content.
- The key is sent to Firecrawl over HTTPS from the device when a research request runs. This is suitable for personal testing; a distributed production release should route provider calls through an authenticated backend so a user-managed key is not exposed to app instrumentation.
- Firecrawl usage may consume account credits. Review current usage and pricing in your Firecrawl account before enabling it.


## Assistant response quality and recent-trends research
- Gemini streaming ignores response parts explicitly marked as internal thoughts. The chat renders user-facing text as it arrives instead of waiting for generation to finish.
- Groq and DeepSeek fallback requests use streamed assistant-content deltas too; hidden reasoning/protocol wrappers are filtered before display.
- Natural command prefixes such as "please", "can you", and "could you" are normalized before local skill matching. Calls open the dialler or contacts for user confirmation; XYAA never places a call automatically.
- Recent-trends prompts mentioning the last 30 days use date-constrained search where supported, deduplicate sources, rank by topic relevance and freshness, and use public engagement only when the provider actually supplies it. Undated evidence is labelled as undated; stale dated sources are excluded from this mode.
- This is a safe methodology adaptation of the MIT-licensed [last30days-skill](https://github.com/mvanhorn/last30days-skill), not a bundled copy of its CLI engine. XYAA currently uses its configured Firecrawl, Tavily, and Gemini web-search providers; it does not claim full social-platform coverage. Gambling-related sources are excluded.
- Automated regression tests cover thought filtering, streaming contracts, recent-trends routing, and natural command normalization. A successful CI build is not a substitute for device testing across Android versions and providers.
