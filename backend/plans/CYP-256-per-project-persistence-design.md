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

## 3. Rehydration — **LAZY, per activation** (CR2 ratified — EAGER was wrong; the .4b model is lazy)

Rehydration is **LAZY**, consistent with .4b's lazy runtime model — NOT an eager boot-time repopulate-every-
project, and **no new boot-stash mechanism**. One `rehydrateActiveProject()` runs against the ACTIVE project:
- **at boot** — the boot project is already active, so it ADDS its stored runtime-added agents ON TOP of its
  config seed;
- **per switch, AFTER `rescope`** — a non-boot project's in-memory slice is empty after a restart, so the store
  refills it the moment the project is activated (the `getOrCreate` runtime is live by then).

It feeds, while the project is active (so `active()` = its runtime, `state.agents` = its slice):
- the `HubState` slice (topology + spoke + ACL, projectId-stamped) via `HubState.addAgent`;
- the runtime `AgentConfigRegistry` (launch/persona/kind) via `active().agentConfigs.put`;
- `active().lifecycle.register` (known + **STOPPED**; start is the CYP-73 lifecycle) + an **idempotent
  `ensureWorktree`** (D3: reuse the existing worktree — no re-clone / re-`git worktree add`).

**Idempotent**: skips an agent already in the slice (config-seeded or previously rehydrated), so repeated
switches are no-ops. It **bypasses `AgentManagement.add`** (writes directly to state/configs/lifecycle) so
rehydration does NOT write back to the store (no rehydrate→persist loop). This closes "a restart loses
non-boot projects' agents".

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
  - **D3 (ratified) — worktree reuse:** assert the rehydrate REUSES the existing `projects/B/worker` worktree —
    no re-clone / re-`git worktree add` churn (`ensureWorktree` idempotent). E.g. the worktree dir + its inode /
    a marker file created at the original `add` survive the "restart" untouched (the FakeGit records no second
    `worktree add` for it), so rehydration is a pure metadata rebuild, not a disk re-provision.
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

## 9. Ratified decisions (PO, 2026-07-06) + build gating

- **D1 — edit persistence split ✅ RATIFIED (store = full truth for runtime-added; overlay = boot-seed only):**
  `ProjectAgentStore` is the single source for runtime-added agents; `AgentOverrideStore` stays ONLY the overlay
  for config-seeded boot agents. **No double-write.** `AgentManagement.edit` routes by "is this a stored
  (runtime-added) agent?" → `ProjectAgentStore`, else → `AgentOverrideStore`. Single-source per agent class → no
  drift. (Implementation seam: a `ProjectAgentStore.contains(projectId, agentId)` / `agentsFor` membership check
  decides the route at edit time.)
- **D2 — teardown model ✅ RATIFIED (straight full-eviction beyond K in .5b):** the LRU/cap core
  (`RuntimeSuspensionPolicy`, unit-tested) is UNCHANGED — only its suspend/resume actions become evict/rehydrate.
  The session-suspension intermediate band is a **documented tuning follow-up, ONLY IF** rapid toggling across K
  shows visible switch-back churn (the expensive part — the process `--resume` — exists in both models). **Not
  built now.**
- **D3 — worktree on rehydrate ✅ RATIFIED (idempotent reuse):** confirmed; the .5a restart-gate asserts reuse of
  the existing worktree (no re-clone / re-add churn) — see §7.
- **Residency ✅ RATIFIED:** follow the CYP-220 tier policy — `ProjectAgentStore` is non-secret (identity/config;
  tokens stay in `RemoteTokenStore`) → migratable, consistent with `ProjectConfig` / `AgentOverride`. No new
  residency posture.
- **Sizing ✅ RATIFIED:** **.5a** = `ProjectAgentStore` + boot rehydration + write-through + per-project-spawn-
  survives-restart gate (the core "work in a new project durably") · **.5b** = full runtime-eviction upgrade
  (the Push 3 follow-up) · **.5c** = Pg impl + migration parity (rides the CYP-220 Postgres track). Build .5a
  first; .5b/.5c stack.

**Build gating (both required before .5a):** (1) the .4b monolith is MERGED (the base) + (2) the PO-Assistant's
design second opinion (PO fetches it once out of the monolith gate). Until both land, this stays design-only.

### .5a change-requests (PO, at build-approval) — as-built

- **CR1 (load-bearing):** the D1 no-double-write route (`contains` → store, else overlay) is applied at **ALL
  six** `overrides.*` write sites in `AgentManagement`, not just `edit`: `add`, `edit`-fields, `edit`-avatar,
  `uploadAvatar`, `clearAvatar`, `remove` (via `persistRuntimeOrOverlay`). A runtime-added agent's avatar can
  never leak into the overlay → no rehydrate drift.
- **CR2:** rehydration is **LAZY** (§3, corrected).
- **CR3:** a `ProjectAgentStore.put` failure is **never swallowed** — it throws; `add` persists BEFORE the
  in-memory point-of-no-return, `edit`/others propagate → the route surfaces it (no live-but-not-durable).
- **CR4:** `.5a` scopes to **LOCAL** agents (`RemoteTokenStore` is verified agentId-GLOBAL, not per-project);
  remote-agent per-project durability couples to **CYP-264** (remote BYOA is off in MVP).
