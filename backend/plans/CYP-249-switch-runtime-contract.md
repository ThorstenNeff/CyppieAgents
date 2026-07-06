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
| **BACKGROUND** (live, not active) | visited before, not currently active, within the LRU cap K | `runtimeRegistry.of(pid) != null` AND not active | **stay LIVE** (agents keep running in the background) |
| **SUSPENDED** (evicted) | beyond the LRU cap K — torn down | `runtimeRegistry.of(pid) == null` | killed; session ids persisted (SessionStore); transcript persists (CYP-198) |

**Cap K = 3** (config): the active project + the **2 most-recently-hot** background projects stay live; any
further background project is SUSPENDED. Comm/ACL/events/config/reports remain per-project-scoped for ALL
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

## 3. Eviction transitions (ratified teardown — Push 3, mirror-relevant NOW)

- On a switch, if `liveCount() > K`, the **least-recently-hot BACKGROUND** runtime is **evicted → SUSPENDED**
  via **persist-kill-resume**: persist its agents' session ids (SessionStore / `--resume`), kill the
  processes, drop the runtime from the registry.
- **Re-entry** to a SUSPENDED project = `switch(→ that project)` = `getOrCreate` mints a FRESH runtime and
  **re-spawns its agents with `--resume <session_id>`** (falls back to a clean spawn if `--resume` is flaky /
  version-sensitive). The project returns to **HOT** with its conversations restored.
- Eviction is bounded and lossless at the data plane: the Event-Log, comm history, config, reports, and the
  per-agent transcript (CYP-198) all persist independent of runtime state.

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

## 5. Open (PO / UIUX decision — not blocking the client build)

- **Does the UI need to DISPLAY runtime state** (hot / background / suspended badges per project)? If yes, the
  server adds a per-project `runtimeState` to `ProjectsView` (`GET /api/projects`) — a small additive field,
  server-derived from `runtimeRegistry` (active / `of(pid)!=null` / else suspended). NOT built yet; flag if
  wanted. Without it, the client drives switches + cursor-resume with no runtime-state read (sufficient for MVP).
- **K value** (default 3) — confirm or override via config.
