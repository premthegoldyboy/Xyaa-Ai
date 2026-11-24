# XYAA specialist-agent routing

XYAA uses a specialist team behind a deterministic front-door router. Only agents relevant to the request are selected; unrelated agents are not run just to increase agent count.

## Routing order
1. **Document agent** — detects PDF creation first. It uses web retrieval only for explicit research/current-fact intent or fact-heavy specifications. Otherwise it writes the requested content and opens Android's PDF save picker.
2. **Management agent** — handles local notes and the PDF library, including exact-match file operations.
3. **Link agent** — builds destination links when requested.
4. **Conversation-memory agent** — retrieves matching recent turns from on-device memory.
5. **Web-research agent** — researches explicit search, lookup, comparison, and current-information requests.
6. **Android-skills agent** — handles local device actions, reminders, timers, and app launching.
7. **General conversation agent** — handles everything else with the configured primary/fallback model.

## Parallel execution policy

Independent workstreams and independent specialist stages should progress concurrently when they do not depend on one another. Within a task, preserve required dependency order (for example, inspect repository before editing, edit before testing, and test before reporting a build as verified). Parallel execution must not mean racing writes to the same file, branch, database record, or external resource.

The XYAA Intelligence Core has two independent product tracks that begin together:
- **Personal Memory:** durable, consent-based user preferences, interests, plans, and project context.
- **Code Studio:** approved coding tasks executed in isolated build workers, with diffs, tests, and APK artifacts.

Neither track is a prerequisite for the other. They share versioned context, approval, and audit interfaces. See `docs/XYAA_INTELLIGENCE_CORE.md` for the architecture, privacy boundaries, execution safeguards, and parallel delivery plan.

## Combined tasks

“Search the web for the latest Oppo K13 specifications and make a PDF” composes web research, source grounding, document writing, and PDF export in that order. Independent retrieval or analysis subtasks may run concurrently when their inputs are available.

## Boundaries
- Routing is deterministic and adds no model latency or API cost.
- Gemini Search grounding is preferred; Tavily is the independent retrieval fallback.
- Groq and DeepSeek are answer-generation fallbacks, not web-search providers.
- Local device actions and management remain on-device.
- Dependent stages run sequentially; independent stages and the two Intelligence Core tracks may progress in parallel.
- Code execution must run outside the Android app in a constrained sandbox. Repository writes and publishing actions require explicit approval.
- Personal memory is distinct from recent-turn transcript storage and saves durable facts only with user consent.
