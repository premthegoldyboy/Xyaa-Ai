# XYAA Code Studio — secure GitHub runner connection

## Current state

The Android client currently stores encrypted local tasks and plans. The repository has a manual
GitHub Actions workflow at `.github/workflows/code-studio-ai-worker.yml` that accepts a task,
runs the allowlisted AI patch worker in a disposable GitHub-hosted runner, executes smoke tests
and an Android debug build, and uploads a patch/APK artifact. It does not push or modify a branch.

**The Android app is not yet connected to that workflow.** The current GitHub connector available
to development tooling is read-oriented for workflow operations; it does not expose the
`workflow_dispatch` POST operation. No GitHub App registration, installation, client ID, backend
deployment, or Gemini Actions secret is configured by this code change. The app must continue to
show “runner not connected” until all required setup and an end-to-end test are complete.

## Production connection (recommended)

Use a small HTTPS orchestrator API between Android and GitHub. Do not put a GitHub App private key,
GitHub token, Gemini key, or signing key in the APK.

1. Register a dedicated GitHub App for XYAA. Enable user authorization (device/browser flow as
   appropriate) and configure only the repository permissions required by the integration:
   - Actions: read and write (dispatch and inspect runs/artifacts).
   - Contents: read (identify the selected base ref and repository state).
   - No Contents write, Pull requests write, Administration, or workflow-file write permission.
2. Install that app only on the intended XYAA repository. Use short-lived installation tokens
   server-side; keep the GitHub App private key in the orchestrator's secret manager.
3. Android authenticates to the orchestrator using a short-lived XYAA session. The orchestrator
   verifies the user's repository installation and authorization for every operation.
4. The orchestrator validates a strict request schema (repository allowlist, task length, allowed
   base refs, idempotency key, per-user rate limits), records an audit event, and dispatches only
   `code-studio-ai-worker.yml` with `task` and `base_ref` inputs.
5. Poll run status through the orchestrator. Return only normalized status, timestamps, logs
   summaries, artifact IDs, expiry, and checksums. Download artifacts through a short-lived,
   user-bound URL or authenticated streaming endpoint.
6. The app displays the patch and build results for review. Applying a patch to the user's working
   branch, pushing, creating a PR, merging, signing, publishing, or deploying remains a separate
   explicit approval action. The current worker does none of these writes.
7. Store the XYAA session refresh material using Android Keystore-backed encryption; support
   disconnect/revoke, token expiry, account switching, and local task deletion.

## Required server API contract

- `POST /v1/github/connect/start` — begin the configured GitHub App authorization flow.
- `POST /v1/github/connect/complete` — exchange/verify authorization server-side; never accept a
  GitHub App private key or Gemini key from the Android client.
- `GET /v1/github/connection` — return connected account and repository installation status.
- `POST /v1/code-tasks` — submit an approved task; require exact task ID, plan hash, approval
  timestamp, repository, base ref, and idempotency key.
- `GET /v1/code-tasks/{id}` — normalized queued/running/succeeded/failed status.
- `GET /v1/code-tasks/{id}/artifacts` — list patch/APK metadata and expiry.
- `GET /v1/code-tasks/{id}/artifacts/{artifact}` — authenticated short-lived artifact handoff.
- `DELETE /v1/github/connection` — revoke/disconnect.

Every endpoint must enforce TLS, authentication, authorization, input validation, rate limits,
audit logging without secrets, and bounded request/response sizes. The worker remains ephemeral,
read-only with respect to GitHub credentials, and patch-allowlisted.

## Deployment gates

- [ ] GitHub App registered, configured, and installed on the selected repository.
- [ ] Orchestrator deployed with private key in a secret manager and TLS enabled.
- [ ] `GEMINI_API_KEY` added as a GitHub Actions repository secret (never to Android).
- [ ] Dispatch, status polling, artifact download, expiry, and disconnect tested end-to-end.
- [ ] Android session storage and account-switch/revocation tests pass.
- [ ] User sees an explicit approval for each task's exact plan hash and base ref.
- [ ] Existing validation and Android build workflows pass.

Until these gates are met, the Android UI must not expose a fake Connect/Run action or imply that
a task has been dispatched. A GitHub App client ID and backend URL alone are not sufficient:
the app installation and server-side credentials must also be configured.
