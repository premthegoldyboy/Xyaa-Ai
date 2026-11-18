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

## Combined tasks
“Search the web for the latest Oppo K13 specifications and make a PDF” composes web research, source grounding, document writing, and PDF export. “Make a PDF explaining photosynthesis” selects document writing and PDF export without forcing an unnecessary search.

## Boundaries
- Routing is deterministic and adds no model latency or API cost.
- Gemini Search grounding is preferred; Tavily is the independent retrieval fallback.
- Groq and DeepSeek are answer-generation fallbacks, not web-search providers.
- Local device actions and management remain on-device.
- Dependent stages run sequentially; independent specialists may be parallelized later when their results do not depend on one another.
