# Maestro flows (CYP-11)

UI-gating for UI slices runs on **Maestro against the Web (Wasm) build** (Test-Contract v0.5 §0/§4).
This directory holds one `*.yaml` per flow. `smoke-web.yaml` is the first smoke/gating flow.

## Conventions (from Test-Contract v0.5 §4)
- **Web flow:** `appId`-free in spirit (placeholder `web`); the target is a URL opened via `openLink`.
- **Selectors reference testTags only** (no localizable text selectors). Maestro `id:` is a **regex**,
  so tag selectors are **escaped (`\.`) and anchored (`^…$`)** — e.g. `^agent\.backend\.input$`.
- **Tags (bare, no `@`):** `smoke`, `gating`, `security`, plus slice tags.

## Prerequisites to run `smoke-web.yaml`
1. A served Wasm/Web build at `APP_URL` (default `http://localhost:8080`):
   `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun` (or a static `wasmJsBrowserDistribution` host).
2. **testTags exposed as resource-ids:** enable `testTagsAsResourceId` at the app/window root.
   This is platform-specific (not commonMain — Android has it directly; the Wasm mechanism is still
   to be confirmed with the frontend, matching Test-Contract v0.5 §0 "Wasm-Gating unter Vorbehalt").
   A flagged CYP-11 follow-up, coordinated with the root owner (CYP-10); **not** wired yet.
3. A screen rendering the agent stream for `agentId = "backend"` with enough event rows that
   `agent.backend.event.5` starts **off-screen** (the CYP-6 renderer on a scripted scenario, or a harness).

## Running (tester environment)
```bash
maestro test maestro/smoke-web.yaml -e APP_URL=http://localhost:8080
# filter by tag:
maestro test maestro/ --include-tags smoke
```

## Status / ownership
- **Authored by Dev (CYP-11).** The Compose UI-test infrastructure is verified runnable on JVM
  (`./gradlew :app:shared:jvmTest` → `UiTestInfraTest`, proving testTag addressing + off-screen
  `performScrollToNode`).
- **Runtime verification of this Maestro flow is the tester's (CYP-7) environment** — Dev's sandbox
  has no browser/Maestro runner. The selectors + structure are authored to the contract; the tester
  owns the QA verdict.
