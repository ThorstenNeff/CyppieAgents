# CYP-249 / CYP-262-T2 — Server Contract: Per-Project Runtime States + Switch/Eviction Transitions

> Status: **server contract, published for the client half (Dev CYP-249 + UIUX-T2).** Authored off the
> CYP-255 (.4b) monolith once the switch semantics stood (Push 2). Server-authoritative — the client MIRRORS
> this state model. The eviction transitions (§3) are the ratified teardown (Push 3); the switch transitions
> (§2) are LIVE now.

## 1. The three runtime states (server-authoritative)

A **project runtime** = the per-project lifecycle bundle (its own `ConnectorSessions`, `LifecycleManager`,
`AgentConfigRegistry`, capability/provider registries, `WorktreeManager`, `AgentManagement`). At any time a
project's runtime is in exactly one of:

| State | Meaning | Server predicate | Agent sessions |
|---|---|---|---|
| **HOT** (active) | the ONE active project | `runtimeRegistry.active()` resolves it | live; `/ws/agent`, `/ws/comm`, `/ws/lifecycle`, `GET /api/agents` all resolve to it |
| **BACKGROUND** (live, not active) | activated before, not currently active, within the LRU cap K | in the policy's live set | agents keep **running** in the background |
| **SUSPENDED** (session-suspended) | activated before, beyond the LRU cap K | in the policy's suspended set | agent **processes killed** (session ids persisted, CYP-167); transcript persists (CYP-198) |

> **MVP realization = session-suspension, NOT full runtime eviction.** Beyond K, a project's expensive
> `claude` **processes** are killed (session ids persisted → `--resume`), but the **cheap runtime object stays
> in memory** (its registries). Full runtime eviction (`of(pid)==null`) needs durable per-project agent config
> (a non-boot project's config is in-memory-only today), which is deferred to CYP-247.5 / CYP-220 — so it is
> **not** done here. The **client-facing contract is identical either way**: a SUSPENDED project's processes
> are off + resumable; re-entry = reconnect + `--resume`. Only the server-internal reclaim differs.

**A never-activated project reads `HOT`** (the no-indicator fail-safe): BACKGROUND/SUSPENDED both mean "was
live", which a fresh project never was — so it must not show a stale badge. The `runtimeState` field
(`RuntimeState { HOT, BACKGROUND, SUSPENDED }`) is additive on `Project` in `GET /api/projects`, server-derived.

**Cap K = 3** (config): the active project + the **2 most-recently-hot** projects keep their sessions live;
any further one is session-suspended. Comm/ACL/events/config/reports remain per-project-scoped for ALL
projects regardless of runtime state (that is the shared HubState + stores, not the runtime — a SUSPENDED
project's data is intact and correct; only its live agent processes are gone until re-entry).

## 2. Switch transitions (LIVE now — Push 2)

- **boot** → the boot project is **HOT** (its runtime is pre-built + registered at boot).
- **`POST /api/projects/switch` (A → B):**
  1. `getOrCreate(B, factory)` — mint B's runtime if this is B's first activation (or B was SUSPENDED),
     BEFORE B becomes active (fail-closed: `active()` never resolves a runtime-less project). Atomic per key.
  2. registry pointer → B, then `HubState.rescope(B)` → B is **HOT**.
  3. A → **BACKGROUND** (its runtime + sessions stay live).
- A switch to a project with **no live runtime** that cannot be made runnable yields a clean **409
  `project_not_runnable`** (`ProjectNotRunnableException`), never a 500.
- A **fresh** project (created via `POST /api/projects`, never activated) has **no runtime and 0 agents**
  until first switched-to; `GET /api/agents` for it (once active) is empty until the operator adds agents.

## 3. Suspension transitions (session-suspension teardown — Push 3, LIVE)

- On a switch, if the number of projects with live sessions would exceed **K=3**, the **least-recently-hot
  BACKGROUND** project is **session-suspended**: its RUNNING agents are stopped (`lifecycle.stop` → process
  killed; session ids already persisted by the connector, CYP-167). The runtime OBJECT is kept in memory.
- **Re-entry** to a SUSPENDED project = `switch(→ that project)` → the policy **resumes** it: `lifecycle.start`
  each previously-suspended agent → the connector re-spawns with `--resume <session_id>` (falls back to a
  clean spawn if `--resume` is flaky / version-sensitive). The project returns to **HOT**, conversations
  restored. The stop/spawn run async off the switch response; the `runtimeState` bookkeeping is synchronous.
- Bounded + lossless at the data plane: Event-Log, comm history, config, reports, per-agent transcript
  (CYP-198) all persist independent of runtime state.
- **Deferred (tracked follow-up):** full runtime eviction (drop the runtime object, `of(pid)==null`) — needs
  durable per-project agent config (CYP-247.5 / CYP-220). Until then the cheap runtime objects of visited
  projects stay resident; the expensive processes are what K bounds.

## 4. What the client (CYP-249 / CYP-262-T2) must mirror

- **Hold the HOT project fully live** (windows + `/ws/agent` per agent + `/ws/comm` + `/ws/lifecycle`).
- **On switch:** the newly-active project is HOT; dispose/re-open sockets for it. The previously-active
  project's windows may be kept warm (client's choice) OR disposed — the server keeps its runtime live while
  it is within K, so a quick switch-back is cheap.
- **Do NOT assume a background project's live session survives indefinitely** — beyond K it is SUSPENDED, its
  live agent processes gone. On switch-back the server re-spawns (`--resume`); the client **reconnects fresh**
  and replays the transcript from its cursor (**CYP-198 history-then-live**, **CYP-204 cursor-resume**) — no
  assumption of an unbroken socket. Treat a reconnect as the normal re-entry path, not an error.
- **Mirror K = 3** if the client caps warm VMs/sockets: keep the active + 2 recent hot; dispose the rest,
  reconnecting on re-entry.

## 5. Decided / built

- **`runtimeState` field — BUILT (PO + UIUX ratified).** `RuntimeState { HOT, BACKGROUND, SUSPENDED }` in
  `:core` (compiler-shared), additive `Project.runtimeState` on `ProjectsView` (`GET /api/projects`),
  server-derived from the live suspension policy, filled per project. Never-activated → `HOT` (no indicator).
- **K value** = **3** (default; `BootOrchestrator.runtimeSuspensionCap`, config-overridable). ≤ 0 disables
  suspension (unbounded background-live).
