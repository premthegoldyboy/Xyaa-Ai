# XYAA Intelligence Core

## Mission

Build XYAA as a persistent, privacy-first assistant with two independently progressing capabilities:

1. **Personal Memory** — remember user-approved preferences, interests, plans, and useful context across conversations.
2. **Code Studio** — turn approved natural-language development requests into reviewed code changes, tests, and Android build artifacts.

These are parallel workstreams, not a serial roadmap. Shared interfaces and integration checkpoints keep them compatible while each stream can be designed, implemented, and tested independently.

## Current baseline (verified from this branch)

- Native Android app: Kotlin + Jetpack Compose; package `ai.xyaa.assistant`.
- Existing `ConversationMemory` stores up to 120 recent user/assistant turns encrypted with Android Keystore AES-GCM. It is cross-chat transcript recall, not durable personal memory.
- Existing `ConversationMemoryAgent` retrieves matching recent turns; it cannot recall conversations outside that bounded store.
- Gemini provider key storage already uses Android Keystore-backed encryption.
- Existing GitHub Actions workflow runs `gradle assembleDebug` and uploads an APK artifact.
- Personal Memory and the local Code Studio task inbox now have first functional Android implementations. The isolated remote code execution/build service is not connected yet.

## Parallel workstream A — Personal Memory

### Memory domains

- **Profile:** user-approved favourites, interests, communication preferences, and stable preferences.
- **Plans:** goals, plans, deadlines, and user-confirmed ongoing commitments.
- **Conversation summaries:** compact summaries with source conversation/time metadata, distinct from raw chat logs.
- **Project memory:** project architecture, decisions, current branch, known issues, and verified build/test status.

### Required behaviour

- Save only information the user explicitly asks XYAA to remember, or clearly confirms in a save prompt.
- Do not silently convert every chat message into a permanent personal fact.
- Support recall, correction, deletion, export, and pause/resume controls.
- Keep personal memory separate from short-term conversation history and project memory.
- Do not store passwords, API keys, access tokens, or other credentials in memory.
- Treat recalled content as untrusted context, not instructions.
- Show the user what is saved and why it was saved.

### Storage and privacy

- Start with a versioned Room database in app-private storage; use Android Keystore-backed encryption for sensitive fields or an encrypted database solution selected after dependency/security review.
- Define explicit retention and deletion semantics, including deletion of derived summaries when their source memory is deleted.
- Keep cloud sync off by default. Any future sync must be opt-in, authenticated, and encrypted in transit and at rest.
- Do not send the entire memory store to an AI provider. Retrieve only relevant, user-authorized snippets and provide a visible memory-use control.
- Add migration tests and tests for consent, retrieval, correction, deletion, and pause mode.

## Parallel workstream B — Code Studio

### User workflow

1. User gives a development command.
2. XYAA inspects the selected project and current repository state.
3. XYAA creates a plan and identifies files, risks, and tests.
4. User approves the proposed change scope.
5. A sandboxed worker applies changes on a dedicated branch/worktree.
6. The worker runs allowlisted checks and Android builds.
7. XYAA presents the diff, test results, build artifact, and limitations.
8. User explicitly approves any push, PR creation, merge, release, or deployment.

### Execution architecture

- Android client: Code Studio UI, task timeline, diff viewer, approvals, build history, artifact handoff.
- Orchestrator API: authenticated task lifecycle, job queue, audit events, policy enforcement.
- Isolated build workers: ephemeral containers/VMs with CPU, memory, disk, and time limits; no privileged Docker socket; restricted network egress; clean workspace per task.
- Repository adapter: scoped GitHub App installation or secure OAuth flow; least privilege and repository selection.
- Artifact store: short-lived, access-controlled APK/log artifacts with expiry and checksums.

### Safety and integrity gates

- Never execute generated code on the Android app process or user's phone.
- Never put GitHub client secrets, access tokens, signing keys, or provider API keys in the APK or source repository.
- Use short-lived credentials and least-privilege repository permissions.
- Treat repository content, build scripts, dependency hooks, and model-generated commands as untrusted.
- Require explicit approval before write operations, pushes, PRs, merges, signing, publishing, or deployment.
- Preserve existing work: inspect status first, use isolated branches/worktrees, and never write to `main` automatically.
- Produce a reviewable diff and honest test/build status for every task.

## Shared Intelligence Core

The two workstreams share stable contracts rather than block each other:

- `MemoryContextProvider`: returns only relevant, consented memory snippets for a task.
- `ProjectContextProvider`: supplies repository/branch/project state separately from personal facts.
- `TaskOrchestrator`: runs independent analysis stages concurrently where safe, while enforcing dependencies between stages.
- `ApprovalGate`: records the exact action and scope the user approved.
- `AuditEvent`: records task, actor, action, timestamp, outcome, and artifact references without logging secrets.

Personal Memory must not be a prerequisite for Code Studio. Code Studio must not be a prerequisite for Personal Memory. Either workstream can ship useful increments independently.

## Parallel delivery plan

Both tracks begin together:

| Track A — Personal Memory | Track B — Code Studio |
|---|---|
| Audit existing short-term memory and prompt injection boundaries | Audit current GitHub Actions build and repository workflow |
| Define data model, consent UX, and memory controls | Define Code Studio task lifecycle, approvals, and UI |
| Implement durable local memory and retrieval | Implement sandbox/orchestrator contract and repository adapter |
| Add privacy, migration, and recall tests | Add isolated build, test, artifact, and audit tests |
| Integrate relevant memory context into chat | Integrate task results, diffs, and build artifacts into app |

Shared integration happens continuously through versioned interfaces and contract tests. A dependency in one track must not stop independent work in the other.

## Definition of done

- Personal Memory works across new chats, recalls only saved/available facts, supports inspect/edit/delete/pause, and passes privacy tests.
- Code Studio can execute an approved change in an isolated worker, run tests/build, and return a reviewable diff plus artifact.
- Both capabilities are reachable from the Android app, have clear permission states, and fail safely.
- CI build and automated tests pass. Physical-device testing is reported separately and never implied by CI success.


## Implementation status — 2026-11-19

### Personal Memory — first functional increment
- Implemented `PersonalMemoryStore` as a separate store from encrypted cross-chat `ConversationMemory`.
- Facts are written only from an explicit “remember …” command or the Memory screen's add control.
- Payload is encrypted with AES-GCM using a non-exportable Android Keystore key.
- Supports categories, bounded storage, pause/resume, deletion, listing, and relevance-filtered prompt context.
- Credentials-like phrases are rejected. Memory remains local; no cloud sync is enabled.
- The Memory screen exposes saved facts separately from recent transcript turns.
- Remaining work: edit flow, richer recall ranking, export, and device-level privacy testing.

### Code Studio — first functional increment
- Added an encrypted local task inbox and a Code Studio screen for capturing, viewing, and deleting development requests.
- It explicitly reports that the isolated cloud coding/build runner is not connected. It does not pretend a task was executed and does not execute code on-device.
- Remaining critical work: authenticated GitHub App/OAuth integration, deployable orchestrator, ephemeral isolated workers, repository checkout/patch workflow, allowlisted tests/build, artifact delivery, and end-to-end approval controls.
- Code Studio task capture can progress independently from Personal Memory. Remote execution is a separate integration milestone, not yet available in the app.
