# CYP-255 (.4b) — Per-Project Runtime De-Singletonization: Monolith Build Plan

> Status: **DURABLE BUILD PLAN — take FRESH (highest-stakes L pass).** Docs-only, survives compact.
> Predecessors MERGED → develop `8a76ea1`: M (CYP-246) · L .1 (CYP-252 runtime seam) · .2 (CYP-253
> worktree per-project) · .3 (CYP-254 collision-leak tooth) · .4a (CYP-255 factory primitives).
> `.4b-①③` (egress: /ws/comm pump + report project-scope) = `feature/CYP-255-4b-spine`, at gate.
> **Canonical spec = PO-Assistant's CYP-255 Jira comment (①②③④ + file:line).** This file is the build map.

## 0. Why this is one coupled pass (not slice-able)

A mint-fresh `ProjectRuntimeFactory` forces EVERY mediation-spine consumer to resolve
`runtimeRegistry.active().*` instead of a concrete boot registry. A subtle miss there is
**behavior-identical with ONE runtime** (passes all one-project gates) and only surfaces with
**multiple** runtimes — i.e. it is caught ONLY by the e2e money-tooth. So factory-impl + spine-migration +
② + switch + CYP-259 + distinct-instances + e2e-money-tooth are ONE gated pass. A wrong cut here = a
latent cross-project bleed that ships. **The e2e collision-leak money-tooth is THE merge gate.**

## 1. The boot-reorder trap (READ FIRST)

`BootOrchestrator.boot()` interleaves runtime-component construction with mediation-spine wiring AND a
**boot-time capability/provider intake loop** (`~line 326`) that runs BEFORE the boot `ProjectRuntime` is
registered (`~line 436`). So `runtimeRegistry.active()` is NOT available at the intake loop today.
**Fix:** register the boot runtime via the factory BEFORE the intake loop. The factory needs the connector
(`~line 324`), so the factory runs at ~324, register ~325, then the intake loop (~326) resolves
`active().capabilityRegistry`/`active().providerRegistry`. Order after refactor:
`state → runtimeRegistry(empty) → eventRecorder/projector(caps resolver) → router → deliverer(sessions
resolver) → connector(324) → factory.create(bootProjectId) + register(325) → intake loop(326, active()) →
spawn loop`. Verify nothing calls `active()` between registry-declare and boot-runtime-register.

## 2. Build order (verify :server:check + :e2e green after EACH step; keep the .3 collision tooth + .4b-①③ green)

**Step 1a — spine consumers → `active()` resolvers (behavior-preserving; boot runtime still holds the
existing boot instances, so `active().X == the boot X`).** Migrate:
- `MessageDeliverer.sessions: ConnectorSessions` → `() -> ConnectorSessions`; usages at
  MessageDeliverer.kt:66 + :98 become `sessions().session(...)`. Boot wires
  `sessions = { runtimeRegistry.active().connectorSessions }`. (Deliverer reads `hub.state` already —
  active-scoped. Delivery is active-project-scoped; a background-runtime session-attach resolving active()
  → no-op is acceptable, background projects don't receive cross-project delivery.)
- `EventProjector` capabilities resolver (BootOrchestrator:231, `capabilities = capabilityRegistry::get`) →
  `{ runtimeRegistry.active().capabilityRegistry.get(it) }`.
- Connector persona (BootOrchestrator:306 `personaOf = agentConfigs::personaOf`) →
  `{ runtimeRegistry.active().agentConfigs.personaOf(it) }`; ConnectorRouter kindOf (:322) →
  `{ runtimeRegistry.active().agentConfigs.connectorKindOf(it) }`.
- `ConnectorOptIn` (BootOrchestrator:410, takes agentConfigs + capabilityRegistry) → resolve active()
  internally (or construct per-runtime in the factory).
- The cap/provider intake loop (~:326, `capabilityRegistry.set`/`providerRegistry.set`) →
  `active().capabilityRegistry.set` / `active().providerRegistry.set` — REQUIRES the boot runtime
  registered first (§1). **Caveat:** the boot runtime must hold the SAME instances the intake loop
  populates, else GET /api/agents reads empty caps. So in 1a the boot runtime bundles the EXISTING
  capabilityRegistry/providerRegistry/agentConfigs/sessions.

**Step 1b — factory mints DISTINCT per-runtime instances.** `ProjectRuntimeFactory.create(projectId)`
mints fresh `{CapabilityRegistry, ProviderRegistry, AgentConfigRegistry(personas seed), ConnectorSessions,
WorktreeManager = worktrees.forProject(projectId), LifecycleManager(sessions, connector.open spawn,
ensureWorktree via active()), AgentManagement(...)}` and **wires each fresh sessions →
`deliverer::onSessionAttached`** (so every runtime's sessions feeds the mediator's ear). Boot builds the
boot runtime via the factory. Since 1a made the spine resolve active(), the factory-minted boot instances
are what active() returns → still behavior-identical (one runtime). Shared deps the factory closes over:
hub/state, connector, deliverer, eventRecorder, eventProjector, store, deliveryLog, scope, config,
projectConfig, agentOverrides, avatarBlobs, avatarPresets, connectorOptIn, remoteTokenIssuer,
worktrees(base), runtimeRegistry, personas seed.

**Step 2 — switch orchestration + CYP-259 (DEPLOY-GATE).** `POST /api/projects/switch` onActiveSwitch
(PlatformWiring:111, today `state::rescope`) ALSO `runtimeRegistry.getOrCreate(targetProjectId, factory)`
BEFORE rescope (fail-closed active() ⇒ the runtime must exist before any spawn/worktree op). **CYP-259:**
(a) getOrCreate-on-activation closes the fail-closed non-boot-add 500; (b) a genuinely-not-runnable
`active()` throw → clean **409 "not runnable yet"** (not a 500) at the route boundary; (c) `AgentManagement.add`
**check-before-mutate** (validate fully before any state/lifecycle/worktree mutation → no partial
state/orphan on failure).

**② /ws/agent SESSION (egress leak #2).** Resolve the session via `active().connectorSessions` +
enforce `agentId ∈ active project's agent slice` (else a same-id `backend` in another project could attach
cross-project). Find in AgentSocketRoutes / PlatformWiring:65 `agentSocket(booted.connectorSessions, ...)`.

**④ atomic switch (lower prio, take along).** setActive + rescope under ONE lock (or rescope updates both
the ProjectRegistry active pointer AND HubState.activeProjectId atomically) — a µs-race else mis-stamps a
message posted mid-switch. Check ProjectRegistry.setActive + HubState.rescope call sites (ProjectRoutes:68-72).

**Route-consumer per-request active() migration.** PlatformWiring consumers that CAPTURE boot instances →
resolve `runtimeRegistry.active().*` per request: commRoutes (lifecycle, capabilitiesOf, connectorKindOf,
providerOf), agentSocket (connectorSessions), hubWireRoutes (capabilityRegistry, providerRegistry,
connectorSessions), lifecycleRoutes + lifecycleSocket (lifecycle), agentMgmtRoutes (agentManagement),
connectorRoutes (capabilityRegistry, connectorOptIn). Pass `() -> X` resolvers or the runtimeRegistry.

**e2e seed → distinct.** E2ePlatform seed (currently registers per-project runtimes REUSING boot's shared
lifecycle/sessions — CYP-253 fix) → mint via the REAL factory so each seeded project has DISTINCT
instances (surfaces real isolation for the money-tooth).

## 3. THE GATE — e2e money-tooth (money-tooth = merge gate)

Two projects each with agent id `backend`: spawn A `backend` → switch B → spawn B `backend`. Assert over
REAL requests: A's session ≠ B's, A's worktree = `projects/A/backend` ≠ `projects/B/backend`, A's run-state
independent, NO comm/session/report bleed (exercise ①②③ paths live). **Mutation: any registry reverted to
shared / agentId-only key → cross-talk → RED.** Tester ALSO builds the ①-live-switch-on-held-connection WS
tooth (J2 reconnect doesn't cover it). Keep the .3 collision tooth + .4b-①③ teeth green throughout.

## 4. Teardown (ratified button) + CYP-249 server-contract

**Button = ① Background-live + HARD LRU-Cap K=3 (config param: active + 2 recent-hot), eviction BEYOND K
via ③ Persist-Kill-Resume** (persist session ids via SessionStore/JsonFileSessionStore → kill processes →
re-spawn `--resume` on re-entry; `--resume` version-sensitive → fall back to keep-live if flaky). Client
(CYP-249 / CYP-262-T2) MIRRORS: holds K hot VMs/sockets, disposes+reconnects evicted.
**FIRST DELIVERABLE after the monolith's switch semantics stand = publish the SERVER CONTRACT for CYP-249/
CYP-262-T2:** which projects are hot / background / suspended, and the switch/eviction state transitions →
PO routes Dev for the client half. One knob, both layers.

## 5. Branch / gate discipline

Branch `feature/CYP-255-4b-...` off the merged .4b-①③ tip (or stacked). Commit prefix `CYP-255:`. Dual-gate
`:server:check` + `:e2e:test`; the e2e money-tooth is the merge gate. Verify behavior-preservation after
1a and 1b (full suite green). PO-Assistant adversarial review of the wiring at .4b (per the .4a merge note).
