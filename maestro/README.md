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

## iOS variants (CYP-69, ungated prep — pre-CYP-67)

The `*-ios.yaml` siblings (`eventlog-presence-ios.yaml`, `phone-pager-ios.yaml`, `acl-matrix-ios.yaml`,
`eventlog-browse-ios.yaml`, `eventlog-tail-ios.yaml`) mirror the Android flows for the iOS Simulator,
authored as **drafts** so they can be wired the moment CYP-67 lands.

### iOS conventions
- **appId** — single iOS bundle `com.tneff.cyppieagents.KMPCyppieAgents` (from
  `app/iosApp/Configuration/Config.xcconfig`). The iosApp launches the shared `App()` via
  `MainViewController()`.
- **Toolchain (verbindlich)** — Xcode 16.4, lauffähig auf iOS 26.6.
- **Same testTags** as Android (no platform-specific tag rename).

### Known pre-flight blockers
1. **`enableTestTagsAsResourceId()` is a no-op in iosMain** = **CYP-67** (iOSDev-16.4). Maestro `id:`
   selectors do not resolve on iOS until CYP-67 wires the actual that surfaces testTags as
   `accessibilityIdentifier`.
2. **No iOS demo target** = **CYP-70** (iOSDev-16.4, parallel to CYP-67). The Android device-verify
   of the operator-gated panels (Browse, Live-Tail, ACL) relies on `:app:androidAppDemo`
   (`DemoActivity` with `demo.tab.{browse,tail,acl,pager}` mounting panels full-screen with stub
   sources). Until the iOS equivalent lands, `acl-matrix-ios.yaml`, `eventlog-browse-ios.yaml` and
   `eventlog-tail-ios.yaml` remain drafts.

### Prod-path flows (no demo target needed; only blocked by CYP-67)
* `eventlog-presence-ios.yaml` — operator-gated **omission** proof against the prod shell.
* `phone-pager-ios.yaml` — Phone-Pager (Compact iPhone) on the prod shell. The default page order
  is **po → frontend → backend → comm (Kommunikation) → acl (Zugriffsrechte)** (confirmed by PO
  2026-06-28 via 26.6-iOS-Dev's S10-smoke; matches `AgentShell.kt`).

### Toolchain pairs (PO 2026-06-28)
* This worktree is the **16.4-pair floor**: verify on Xcode 16.4 (+ iOS 18.6 SDK locally as
  acceptable secondary). **Do NOT install iOS 26.6** — the 26.6-pair (iOS-Tester) cross-verifies
  the 26.6 runtime separately.

### Running (after CYP-67 + iOS demo entry)
```bash
maestro test maestro/eventlog-presence-ios.yaml
maestro test maestro/ --include-tags ios
```
