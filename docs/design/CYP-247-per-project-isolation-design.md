# CYP-247 — Per-project code-base isolation (design pass)

> Status: **design · awaiting Auftraggeber ratification** (no code)
> Parent epic of the landed sub-slices CYP-247.1–.4 / CYP-255 (.4a/.4b) / CYP-256 (.5a).
> Grounded against a live code inventory @ develop `53f7102` (post-CYP-320). Model-before-build per the CYP-308 lesson.

## 0. TL;DR + the decision ask

CYP-247 has **two axes**, and they are at very different maturity:

- **Axis 2 — de-singletonisation of the runtime (the ticket's stated core): ~90 % already landed.** The per-agent
  lifecycle bundle (`LifecycleManager`, `ConnectorSessions`, `AgentConfigRegistry`, `CapabilityRegistry`,
  `ProviderRegistry`, `AgentTokenUsageTracker`, `WorktreeManager`, `AgentManagement`) is **already per-project** —
  separate instances per `ProjectRuntime`, minted by `ProjectRuntimeFactory`, keyed by `projectId` in
  `RuntimeRegistry` (CYP-247.1–.4 / CYP-255 .4b). **Do not rebuild it.** Exactly **one** residual singleton needs a
  `projectId` dimension: `SessionRegistry.sessionToAgent` (§3).
- **Axis 1 — per-project git CLONE isolation: the genuinely unbuilt gap.** Today there is **one shared clone**
  `gitRoot/repo`; every project's worktrees are `git worktree add` against it, always off the **boot** branch. The
  per-project repo URL/branch is *fully modelled as data* (`ProjectConfigStore`, `/api/config/repo`, PG
  `project_config`) but is **only ever read once, at boot, for the boot project**. Two projects cannot target
  different repos/branches. This is the staging fragility (default→flutterdriver re-point) and the real work of CYP-247.

**Six decisions need Auftraggeber sign-off before any build — collected in §8.** My recommendations are inlined.

---

## 1. Where we are today (grounded, so we don't rebuild)

### 1a. Already PER-PROJECT — leave alone

`ProjectRuntime` (`boot/ProjectRuntime.kt:29-40`) bundles 9 members; `ProjectRuntimeFactory`
(`boot/BootOrchestrator.kt:489-528`) mints **distinct** instances per non-boot project; `RuntimeRegistry`
(`boot/RuntimeRegistry.kt`) keys them by `projectId` with a fail-closed `active()` resolver.

| Component | Per-project proof |
|---|---|
| `LifecycleManager` · `ConnectorSessions` · `AgentConfigRegistry` · `CapabilityRegistry` · `ProviderRegistry` · `AgentTokenUsageTracker` · `AgentManagement` | boot instances → runtime `BootOrchestrator.kt:463-475`; factory `p*` instances `:490-526` |
| `WorktreeManager` | boot `worktrees`; factory `worktrees.forProject(pid)` `:497` (per-project **worktree dir**, but **shared clone** — see 1c) |

Isolation is by **separate instances, each keyed by `agentId` internally** — not by composite keys. That is correct and
sufficient *inside* a runtime. The boot runtime is built separately from the factory on purpose (its instances are
already wired into the boot deliverer/projector/override overlay; re-minting would orphan them — `:484-488`).

### 1b. Still SHARED — the mediation spine (deliberately, keep shared)

`ProjectRuntime`'s own doc states the comm layer stays out of the runtime (`ProjectRuntime.kt:19-21`). These are **one
instance**, isolated by resolving `runtimeRegistry.active().*` at call time + the atomic switch ordering
`getOrCreate → rescope → rehydrate → onActivated` (`routing/PlatformWiring.kt:122-126`):

- `HubState` — single instance, `rescope` flips `activeProjectId` and swaps the agent slice via `stashedAgents`
  (`comm/HubState.kt:263-269`, `:92`).
- `MessageDeliverer` (the "shared ear"), `EventProjector`, `Connector`, `Hub`/`MessageStore`, `EventRecorder`/sinks,
  `MediationRouter`, `AgentEventStore` — all single, resolving `active().*`.

**CYP-247 must NOT try to per-project the comm spine.** That is the intended architecture; per-projecting it would
duplicate the deliverer/projector ears and break replay-on-attach. The isolation guarantee there is the switch ordering
— which we only need to *confirm*, plus one keying fix (§3).

### 1c. The clone reality (Axis 1 gap)

- **One shared clone** `gitRoot/repo` (`WorktreeManager.kt:44`); `ensureClone` short-circuits on `repo/.git`
  (`:59-67`) and is called from **exactly one site — boot** (`BootOrchestrator.kt:205`,
  `ensureClone(projectConfig.resolvedRepo(config.projectId))`).
- Every `ensureWorktree` call passes **`config.repo.branch`** — the boot branch, not the per-project resolved branch
  (`:418, :440, :504, :515, :569`).
- Per-project repo config **exists as data**: `ProjectConfigStore.resolvedRepo(pid)` / `setRepo(pid,url,branch)`
  (`boot/ProjectConfigStore.kt:35,48`), `GET/PUT /api/config/repo` (operator-gated, `routing/ConfigRoutes.kt:42-55`),
  File + PG (`project_config(project_id PK, repo_url, repo_branch, …)`). But it feeds git **only** at boot for the boot
  project. `setRepo` mid-run writes the row only — "Repo change takes effect at the next boot" (`BootOrchestrator.kt:204`)
  — and even a restart no-ops `ensureClone` because `repo/.git` already exists → the **stale/two-clone fragility**.
- On-disk layout today: `gitRoot/repo` (shared clone) · `gitRoot/projects/<pid>/<name>` (worktrees, backed by the shared
  clone) · sidecar stores under `gitRoot/*.json` + `.cyppie/`.
- `deleteProject(pid)` (`WorktreeManager.kt:129-148`, cascade CYP-91 via `ProjectDeleter.kt:70`) force-removes a
  project's worktrees + prunes — but there is **no cleanup on re-point**, only on project delete.

---

## 2. Axis 1 — per-project clone + worktree isolation (the core build)

**Target:** each project has its **own clone** (its own remote + branch, from `ProjectConfigStore.resolvedRepo(pid)`)
and its own worktrees off that clone; two projects run different repos/branches concurrently.

### 2a. Layout (decision D1)

- **Recommended (minimal-ripple):** per-project clone at **`gitRoot/clones/<pid>/`**, keep worktrees where they are
  at **`gitRoot/projects/<pid>/<name>`** (`git worktree add` off `clones/<pid>`). This leaves the worktree paths
  untouched, so **CYP-315 `worktreePath`, the connector cwd (`worktreesRoot`), and the `deleteProject` guards do not
  change**.
- **Alternative (cleaner, higher ripple):** `gitRoot/projects/<pid>/{repo, worktrees/<name>}`. Reads nicer but shifts
  every worktree path → touches CYP-315, `ClaudeCodeConnector.worktreesRoot`, `deleteProject`'s strict-child guard, and
  the on-disk migration. Not worth the blast radius for MVP.

### 2b. `WorktreeManager` rebuild

- `repoDir` becomes **per-project**: `File(gitRoot, "clones/$projectId")` (was `gitRoot/repo`). `forProject(pid)`
  already returns a per-project manager — extend it so its **`repoDir` is also per-project** (today it reuses the shared
  one).
- `ensureClone` takes the **per-project** `resolvedRepo(pid)` (URL **and** branch); idempotent per project on
  `clones/<pid>/.git`.
- `ensureWorktree(name)` uses **`resolvedRepo(pid).branch`** as the base branch, not `config.repo.branch`. (Single-source
  the branch from the same resolver the clone uses — no drift.)
- **When the clone happens (decision D6):** boot for the boot project (as today, new path); **lazily on first need for a
  non-boot project** — i.e. `ensureWorktree`/`AgentManagement.add`/`lifecycle.start` first calls `ensureClone(pid)` if
  the project's clone is absent. Recommended over eager-on-project-create (a project may be created and never activated).
- **Fallback repo (decision D2):** a non-boot project with **no** `setRepo` override resolves to the boot repo today
  (`resolvedRepo` falls back). Recommend **keep that fallback** (back-compat, "same repo, isolated worktrees" is a valid
  mode); a project only diverges when an operator sets its own repo. (Alternative: require an explicit repo → fail-closed
  no-clone; rejected as friction for the common single-repo case.)

### 2c. Repo re-point as a first-class re-provision (decision D4)

Today a `PUT /api/config/repo` on a live project is a silent no-op against git. Make it explicit:

- **Recommended (re-provision):** `setRepo(pid,…)` marks the project's clone **stale**; the next activation / agent
  (re)start **tears down** the project's worktrees + clone (reusing `deleteProject`'s worktree partition) and
  **re-clones** into a fresh `clones/<pid>`. Guarded by the **same uncommitted/unpushed-work warning** the agent-remove
  path uses (§AGENT-MANAGEMENT) — never silently discard work. `EFFECT_DEFERRED`: the UI shows a "re-provision on
  restart" hint, mirroring CYP-310's CLAUDE.md restart hint.
- **Rejected (in-place remote swap):** `git remote set-url + fetch + reset` on a live clone with existing agent
  branches/worktrees is fragile (dirty trees, diverged `agent/<name>` branches). Re-provision is cleaner and reuses
  existing teardown code.

---

## 3. Axis 2 — the ONE residual singleton to key

The lifecycle bundle is already per-project (§1a). The only in-memory map without a `projectId` dimension is:

- **`SessionRegistry.sessionToAgent`** — a single global `ConcurrentHashMap<String,String>` session-id → agentId
  (`mediation/SessionRegistry.kt:11`). Two projects with the same `agentId` (`po`, `dev1`) rely on session-id
  uniqueness alone; there is no project dimension.

**Proposal:** key it `(projectId, agentId)` / `(projectId, sessionId)` — the **exact pattern**
`AgentOverrideStore` and `ProjectAgentStore` already use (§5). Small, contained; no store, no schema. This closes the
last cross-project collision surface in the mediation layer. Everything else stays shared-and-`active()`-resolved as
designed. (Confirm-only: the switch ordering in `PlatformWiring.kt:122-126` remains the isolation guarantee for the
shared spine.)

---

## 4. Left-project sessions — trade-off + recommendation (decision D3)

**Today:** background-live with a hard **LRU cap K = 3** (`RuntimeSuspensionPolicy`, `runtimeSuspensionCap`,
`BootOrchestrator.kt:197`). On switch the left project **keeps running** unless it is the LRU victim beyond the cap;
"suspend" = **kill the `claude` processes, keep the cheap runtime object** (session ids persisted CYP-167 →
`--resume` on re-entry). Full runtime reclaim is deferred (CYP-247.5). CYP-306/".5b" full-eviction is **not implemented**
(only CYP-256 .5a rehydration exists).

| Option | Behaviour | Pro | Con |
|---|---|---|---|
| **A — background-live (current)** | left projects run until LRU cap K | instant switch-back; away-agents keep progressing | N concurrent `claude` **and now N active clones/worktrees** → CPU / token / disk burn |
| **B — teardown on switch** | suspend left project's sessions immediately (K=1) | bounded to the active project only | away-agents stop; re-entry re-spawns (`--resume`) |
| **C — expose the cap as policy (RECOMMEND)** | keep A's machinery; make K + "suspend-on-switch" **config** (default K=3), add a per-project "keep hot" pin | operator owns the resource ceiling; teardown-on-switch is just `cap=1`; no new mechanism | needs a small settings surface |

**Recommendation: C.** The suspension machinery already exists; we *expose* it rather than hard-code a policy. Default
stays background-live K=3; an operator who wants strict isolation sets cap=1 (= Option B). This is the natural home for
the CYP-306/CYP-247.5 resource governance later, without pre-committing it now. Per-project clones make the resource
argument sharper (each hot project is a working tree + processes), which is exactly why the ceiling should be an
operator decision.

---

## 5. CYP-220 intersection — build once, not twice

- **Repo config is already a CYP-220 store.** `ProjectConfigStore` = `project_config(project_id PK, repo_url,
  repo_branch, api_key_ct…)`, dual File/PG, operator-gated endpoints. Axis 1 **consumes** `resolvedRepo(pid)` — it adds
  **no new store**.
- **`(projectId, agentId)` keying already exists.** `AgentOverrideStore` and `ProjectAgentStore` are `PK(project_id,
  agent_id)` (`boot/AgentOverrideStore.kt:38-64`, `boot/ProjectAgentStore.kt:68-82`); `RemoteTokenStore` too. The Axis 2
  `SessionRegistry` fix (§3) should follow this **same** pattern — no divergent keying convention.
- **The per-project clone dir is NOT a store.** It is derived from `projectId`; "is this project cloned?" is answerable
  from disk (`clones/<pid>/.git` exists). No new registry/persistence — the durable truth is already
  `ProjectRegistry` (which projects) + `ProjectConfigStore` (their repos) + `ProjectAgentStore` (their agents).
- **Secrets stay File-only until the cipher** (`remote_token` gated). Reaffirm Spec 02 §11: **repo credentials come from
  the host env (SSH / `gh`), never a store, never the repo** — per-project clones do not change this.

---

## 6. Migration / cleanup of the current (fragile) state

The default→flutterdriver symptom = one legacy `gitRoot/repo` pointing at a stale remote, `projects/driver/*` worktrees
whose `.git` links point at that clone, residual agents. Plan:

1. **One-time legacy migration (first boot after CYP-247 ships):** detect the legacy single `gitRoot/repo`; for the boot
   project, re-provision into `clones/<config.projectId>` (re-clone fresh + prune the legacy worktree registrations).
   Idempotent, logged.
2. **Boot-time reconciler:** for each project in `ProjectRegistry`, ensure `clones/<pid>` matches `resolvedRepo(pid)`;
   a URL mismatch → re-provision (§2c, D4). For residual `projects/<pid>/*` **not** in the registry → prune (reuse
   `deleteProject`'s `git worktree remove --force` + `git worktree prune`).
3. **Destructiveness (decision D5):** recommend the prune be **opt-in** (a flag mirroring `?deleteWorktrees=`), default
   **preserve + log what WOULD be pruned** — never auto-delete a working tree that might hold unpushed work.

---

## 7. Proposed slicing (for the gated build, after ratification)

Each slice independently gated; the **e2e money-tooth = two projects, different repos, activated concurrently, no
collision** (worktree, session, config, clone).

- **S1** — per-project clone: `WorktreeManager.repoDir` per-project + `ensureClone(pid)` on lazy first-need + per-project
  base branch. (Axis 1 core.)
- **S2** — repo re-point as re-provision (setRepo → stale → re-clone) with the uncommitted-work warning.
- **S3** — `SessionRegistry` `(projectId, …)` keying (Axis 2 residual).
- **S4** — legacy migration + boot reconciler + opt-in stale-prune.
- **S5 (defer)** — CYP-247.5 full runtime reclaim + expose the suspension cap as policy (folds CYP-306/.5b resource
  governance).

---

## 8. Open decisions for ratification (the ask)

| # | Decision | Recommendation |
|---|---|---|
| **D1** | Clone/worktree layout | `clones/<pid>/` + keep worktrees at `projects/<pid>/<name>` (min ripple; preserves CYP-315 path) |
| **D2** | Non-boot project with no own repo | **inherit boot repo** as fallback; diverge only on explicit `setRepo` |
| **D3** | Left-project sessions | **Option C** — expose the LRU cap as policy, default background-live K=3 |
| **D4** | Repo re-point | **re-provision** (tear down + re-clone, with work-warning), not in-place remote swap |
| **D5** | Stale-dir cleanup | **opt-in** (flag), default preserve + log |
| **D6** | When to clone a non-boot project | **lazy** on first need (activation/add/start), not eager on create |

Once D1–D6 are ratified, I slice per §7 and build risk-first (S1 + the concurrent-different-repos e2e money-tooth first).
