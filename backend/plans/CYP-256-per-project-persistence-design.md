# CYP-256 (CYP-247.5) — Per-Project Spawn/CRUD + Agent-Set Persistence + Full Runtime-Eviction: Design

> Status: **DESIGN-ONLY — ratification artifact, no code.** Authored off develop while the CYP-255 (.4b)
> monolith gates. Build begins ONLY after the monolith merges + this note is ratified. Grounds on the
> CYP-220 store-seam (the same `MigrationSource`/`MigrationTarget` + `StoreRouter` naht — .5 does NOT invent
> a second persistence mechanic). Canonical predecessors: M/CYP-246 (per-project agent slices), .4b/CYP-255
> (per-project runtimes + switch + session-suspension teardown).

## 0. What .4b already enabled (so .5's scope is precise)

After the monolith, a NON-boot project already:
- gets its OWN runtime on activation (`getOrCreate` mints distinct sessions/lifecycle/configs/worktrees);
- accepts `POST /api/agents` into its runtime (`agentMgmtRoutes` → `active().agentManagement.add`), which
  registers the agent in the active project's slice + its runtime's `AgentConfigRegistry` + lifecycle;
- `POST /api/agents/{id}/start` spawns it in that project's worktree (`active().lifecycle` → `connector.open`).

So **per-project spawn/CRUD largely WORKS at runtime already.** What .4b left OPEN — and what .5 owns:

1. **Durability.** A non-boot project's agent SET (existence + config) is **in-memory only**: `AgentManagement.add`
   writes the live `HubState` slice + the runtime `AgentConfigRegistry`, neither persisted. A restart loses every
   non-boot project's agents. (`AgentOverrideStore` persists per-agent *edits*, not agent *existence*.)
2. **Full runtime-eviction is blocked by (1).** Push 3 shipped **session-suspension** (keep the runtime object,
   kill processes) precisely because dropping the runtime would lose the in-memory-only config. Persistence
   unblocks full eviction (drop the runtime, rehydrate on re-entry).
3. **add-PO-to-fresh-project** — needs an explicit check that a fresh (empty-slice) project can gain its first PO
   so hub-and-spoke forms.

## 1. Single-source target

The per-project agent set gets ONE durable source of truth over the CYP-220 seam. The live `HubState` slice +
runtime `AgentConfigRegistry` become a working CACHE rehydrated from that store at boot / on re-entry; every
mutation writes through. No second persistence mechanic — the store rides the existing seam.

**Boundary with existing stores (no overlap):**
- **`platform.config.json`** — the BOOT project's *initial* agents (hand-authored, never rewritten). Stays.
- **`AgentOverrideStore`** (CYP-210) — per-agent *customization overlay* (name/color/persona/launch/avatar) for
  config-seeded agents. Stays; unchanged.
- **NEW `ProjectAgentStore`** (this ticket) — the *existence + full record* of every **runtime-added** agent, per
  project. This is the missing "which agents does project P have" truth.

## 2. `ProjectAgentStore` — over the CYP-220 store-seam

Mirror the exact seam convention (cf. `AgentOverrideStore`, `ProjectConfigStore`, the Pg store slices):

```
interface ProjectAgentStore {                       // the seam (CYP-223 pattern)
    fun agentsFor(projectId: String): List<StoredAgent>          // rehydrate a project
    fun put(projectId: String, agent: StoredAgent)               // add/edit write-through
    fun remove(projectId: String, agentId: String): Boolean      // agent removal
    fun removeProject(projectId: String): Int                    // project cascade-delete partition
    companion object { operator fun invoke(file: File?): ProjectAgentStore = FileProjectAgentStore(file) }
}
class FileProjectAgentStore(file: File?) : ProjectAgentStore, MigrationTarget   // .cyppie/project-agents.json
class PgProjectAgentStore(...)            : ProjectAgentStore, MigrationTarget   // the Pg impl
```

- **`StoredAgent`** = the full record needed to rebuild an agent WITHOUT config: `id, name, role, worktree,
  color?, avatar?, connectorKind, persona?, launch, remote`. It is the union of the `Agent` topology fields +
  the `AgentRuntimeConfig` (persona/launch) — so one row rehydrates BOTH the `HubState` slice entry AND the
  `AgentConfigRegistry` entry. **Secret-free** (a remote agent's *token* stays in `RemoteTokenStore`, CYP-171 —
  NOT here; this store carries only the identity/config, so it can migrate to a user-Postgres like the other
  non-secret stores).
- **Keying** `projectId → agentId` (like `AgentOverrideStore`), so a project cascade-delete drops exactly its
  rows and no cross-project unscoped clear is possible (fail-closed on blank projectId).
- **`MigrationTarget`** (`exportRows`/`importRows`) so it joins the CYP-220 Postgres migration via `StoreMigrator`
  (read-only-window → copy → row-count + checksum verify → atomic rebind, A retained). Routed through
  `StoreRouter`/`PgStoreRouting` per `(storeKey="project_agents", projectId)` — File default, Pg when bound.
- **Import-time QA rule (PO-flagged):** this store is a **pure keyed set — NO monotonic counter** (agents are
  keyed by id, not a sequence), so the "realign the open-time counter in `importRows`" rule (event_log seq /
  report rep-N) does NOT bite here. The OTHER half of the rule DOES: the roundtrip tooth MUST load into a
  **FRESH instance** (`importRows` then a new store reading the rows) to catch a load-from-rows defect — a
  vacuous same-instance roundtrip would pass a broken importRows. Checksum non-vacuous (mutation drop a field
  from the row codec → checksum diverges → RED).

## 3. Boot rehydration

At boot, after the `ProjectRegistry` is built, for EACH registered project: load `ProjectAgentStore.agentsFor(p)`
and repopulate — the boot project ADDS its stored runtime-added agents ON TOP of its config seed; a non-boot
project is built ENTIRELY from its stored rows. Rehydration feeds:
- the project's `HubState` slice (topology + spoke channels + ACL, projectId-stamped) — via the existing
  `HubState.addAgent` per project (the M/CYP-246 stash is populated from the store, not left empty);
- the project's runtime `AgentConfigRegistry` (persona/launch/kind) — the `ProjectRuntimeFactory` gains a
  rehydrate step: a non-boot runtime is no longer minted EMPTY (.4b) but seeded from `ProjectAgentStore`.

Agents rehydrate as **STOPPED** (no auto-spawn at boot for non-active projects — same posture as `add`); the
active project's agents spawn as today. This closes "a restart loses non-boot projects' agents".

## 4. Write-through (single-sourced with the live cache)

`AgentManagement` (already per-runtime, per active project) writes through on every mutation, INSIDE the same
lock that mutates the live state (so the store and the `HubState`/`AgentConfigRegistry` cache never drift):
- `add` → `ProjectAgentStore.put(activeProjectId, storedAgent)` (after the CYP-259(c) fail-closed validation +
  ensureWorktree; before returning — a crash after persist is recoverable, the agent is durable).
- `edit` → `put` the merged record (persona/launch/name/color live here now; the `AgentOverrideStore` overlay
  stays for config-seeded boot agents — **decision D1 below**).
- `remove` → `ProjectAgentStore.remove` (+ the existing `overrides.removeAgent`, `avatarBlobs.delete`).
- Project cascade-delete (`ProjectDeleter`) gains the `ProjectAgentStore.removeProject` partition.

## 5. add-PO-to-fresh-project

`AgentMgmtGuard.validateAdd` already checks the **active project's** slice (per-project post-.4b), so a fresh
project (empty slice) has no PO → adding the first PO is already permitted by the "po_already_exists" rule (it
only fires when a PO already exists in THAT slice). **.5 verifies this end-to-end** (a fresh project + add a PO
+ add a worker → hub-and-spoke `po-worker` spoke forms) and persists it. If the guard turns out to block it
(e.g. a global-PO assumption slipped in), .5 relaxes it to per-project. Gate: the fresh-project-PO e2e.

## 6. Full runtime-eviction (the Push 3 follow-up — now unblocked)

With the agent set durable, the teardown UPGRADES from session-suspension to **full runtime-eviction**:
- Beyond the LRU cap K, the victim runtime is **dropped** (`RuntimeRegistry` evicts it → `of(pid)==null`),
  reclaiming ALL its in-memory registries (not just the processes). Its `runtimeState` stays SUSPENDED.
- On re-entry, `getOrCreate` mints a FRESH runtime and the factory **rehydrates** it from `ProjectAgentStore`
  (config) + re-spawns with `--resume` (session ids from CYP-167 `sessionStore`). No config is lost — the store
  is the truth. This is the "SUSPENDED → `of(pid)==null`" model the .4b contract originally described, now
  correct because the config survives the drop.
- **Decision D2 (PO):** keep session-suspension as an intermediate band (keep the runtime for the K+1..K+M
  most-recent, full-evict beyond) OR go straight to full-eviction beyond K. Recommend **straight full-eviction
  beyond K** once persistence lands (simpler, one policy; the runtime object is cheap but the rehydrate is fast
  from the store). The `RuntimeSuspensionPolicy` suspend/resume actions become evict/rehydrate actions —
  the LRU/cap decision core (unit-tested) is unchanged.

## 7. Gate (per-project-spawn e2e is the ratified gate)

- **Per-project spawn survives restart (THE gate):** boot project A; create + switch to B; add a PO + a worker
  to B via real `POST /api/agents`; start the worker; assert it spawned in `projects/B/worker`; **simulate a
  restart** (re-`boot()` over the SAME store files) → B's agents are back (rehydrated), startable, in B's slice.
  Mutation: skip the `ProjectAgentStore.put` in `add` → after "restart" B is empty → RED.
- **Full-eviction rehydration e2e:** 4 projects, K=3, switch to force-evict the LRU (`of(pid)==null`), switch
  back → rehydrated from the store + `--resume`; agents + config intact. Mutation: skip factory rehydrate →
  re-entered project is empty → RED.
- **Store roundtrip (fresh-instance) + checksum non-vacuous** (§2), Pg parity (File == Pg) like the other
  CYP-220 slices.
- Dual-gate `:server:check` + `:e2e:test`; keep J8 (collision) + J9 (runtimeState) green.

## 8. Migration / rollout

No existing durable data to migrate: non-boot agents were ephemeral pre-.5, so the store starts empty and fills
as agents are added post-.5. The BOOT project stays config-sourced (its config-seeded agents are NOT copied into
the store; only agents ADDED to it at runtime persist there) — so the operator's `platform.config.json` remains
the single hand-authored source for the boot seed, non-invasive. `bootPlatform` supplies the out-of-repo,
gitignored `.cyppie/project-agents.json` (File default); the Pg path is opt-in per the CYP-220 residency policy.

## 9. Risks + open questions (PO)

- **D1 — edit persistence split:** config-seeded boot agents' edits currently go to `AgentOverrideStore`
  (overlay). Runtime-added agents' full records go to `ProjectAgentStore`. An edit to a runtime-added agent
  should write the store, not the overlay. Cleanest: `AgentManagement.edit` routes by "is this a stored agent?"
  — OR (simpler) the store subsumes overrides for runtime-added agents and the overlay stays ONLY for
  config-seeded ones. **PO to confirm the split** (I recommend: store = full truth for runtime-added; overlay
  = boot-seed overlay only — no double-write).
- **D2 — teardown model** (§6): full-eviction-beyond-K vs. a session-suspension intermediate band. Recommend
  full-eviction (simpler, persistence makes it lossless).
- **D3 — worktree on rehydrate:** a rehydrated agent's worktree already exists on disk (created at its original
  `add`); `ensureWorktree` is idempotent, so re-spawn reuses it. Confirm no re-clone/re-add churn.
- **Residency:** `ProjectAgentStore` is non-secret (identity/config only; tokens stay in `RemoteTokenStore`), so
  it MAY live on a user-Postgres like the other non-secret stores — vs. staying home. Default: follow the
  CYP-220 tier policy (non-secret → migratable). PO/residency confirm.
- **Sizing:** M–L (a store slice + rehydration + write-through + the eviction upgrade + gates). Suggest cutting
  **.5a = ProjectAgentStore + boot rehydration + write-through + per-project-spawn-survives-restart gate**
  (the core "work in a new project durably"), **.5b = full runtime-eviction upgrade** (the Push 3 follow-up),
  **.5c = Pg impl + migration parity** (rides the CYP-220 Postgres track). Build .5a first; .5b/.5c stack.
