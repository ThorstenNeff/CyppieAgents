# CYP-247 — Per-project code-base isolation (design pass)

> Status: **design · awaiting Auftraggeber ratification** (no code)
> Parent epic of the landed sub-slices CYP-247.1–.4 / CYP-255 (.4a/.4b) / CYP-256 (.5a).
> Grounded against a live code inventory @ develop `53f7102` (post-CYP-320). Model-before-build per the CYP-308 lesson.

## 0. TL;DR + the decision ask

CYP-247 has **two axes**, and they are at very different maturity:

- **Axis 1 — per-project git CLONE isolation: the genuinely unbuilt gap.** Today there is **one shared clone**
  `gitRoot/repo`; every project's worktrees are `git worktree add` against it, always off the **boot** branch. The
  per-project repo URL/branch is *fully modelled as data* (`ProjectConfigStore`, `/api/config/repo`, PG
  `project_config`) but is **only ever read once, at boot, for the boot project**. Two projects cannot target
  different repos/branches. This is the staging fragility (default→flutterdriver re-point) and the real work of CYP-247.
- **Axis 2 — de-singletonisation: the *inbound* lifecycle bundle is done; the *outbound/attribution* path is NOT.**
  The per-agent lifecycle bundle (`LifecycleManager`, `ConnectorSessions`, `AgentConfigRegistry`,
  `CapabilityRegistry`, `ProviderRegistry`, `AgentTokenUsageTracker`, `WorktreeManager`, `AgentManagement`) IS
  per-project (`ProjectRuntime` / `ProjectRuntimeFactory` / `RuntimeRegistry`, CYP-247.1–.4 / CYP-255 .4b) — don't
  rebuild it. **Revision (r1): my first pass under-counted here.** It called the comm spine "confirm-only, one
  residual singleton (`SessionRegistry`)". A code-grounded second opinion is **correct**: the **outbound event path
  reads `active()` / boot `config.projectId` at emit time**, which is only right while `active() == owner`. Axis 1 is
  precisely what breaks that invariant (it makes non-boot projects runnable → the K=3 background-live sessions fire
  **async while another project is active**). Three **code-verified** leaks (§3): the mediation "mouth"
  (`Hub.postAsAgent` + `spokeChannelFor`), the projector (`onContextTokens → active().tokenUsage`), and the
  boot-frozen connector spawn-identity (`resolveApiKey`/`projectIdOf`/`worktreesRoot`). `SessionRegistry` keying is
  **necessary but not sufficient** — the leaks are downstream of it.

**This is a revision round (r1), not a build.** The direction (Axis 1, D1/D2/D6, CYP-220 reuse, §1a bundle) stands;
§3–§4, §7–§8 are revised to fold in the outbound-attribution gap. **Decisions needing sign-off in §8** (now D1–D8);
recommendations inlined.

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
  **re-clones** into a fresh `clones/<pid>`. **Gated by the safe-teardown guard (§2d)** — never silently discard work.
  `EFFECT_DEFERRED`: the UI shows a "re-provision on restart" hint, mirroring CYP-310's CLAUDE.md restart hint.
- **Rejected (in-place remote swap):** `git remote set-url + fetch + reset` on a live clone with existing agent
  branches/worktrees is fragile (dirty trees, diverged `agent/<name>` branches). Re-provision is cleaner and reuses
  existing teardown code.

### 2d. The safe-teardown guard (single-source; used by EVERY destructive path) — r2

**Any teardown that force-removes a worktree or deletes a clone (§2c re-point, §6.1 legacy migration, §6.2 reconciler
re-provision, §6.3 stale-prune) MUST run this one guard first.** A dirty working tree is **not** the only at-risk state:
the branch-per-agent strategy (`agent/<name>`, `WorktreeManager.kt:83`) means an agent can **commit** locally and not
have pushed — `git worktree remove --force` + branch deletion would then destroy **committed-but-unpushed** work
**silently**. The guard checks **both**:

- **(i) dirty working tree** — uncommitted changes in the worktree (`git status --porcelain` non-empty).
- **(ii) unpushed commits on the agent branch** — `agent/<name>` has commits with **no pushed upstream equivalent**:
  either no upstream is configured, or `git log @{u}..agent/<name>` (equivalently `git rev-list --count @{u}..`) is
  non-empty. (Recall §5: `deleteWorktree`/`deleteProject` deliberately do **not** delete the `agent/<name>` branch, so
  the commits survive a *worktree* removal — but a re-provision that also drops the clone loses the branch and its
  commits.)

**On a positive check → warn + block the destruction by default** (surface the exact worktrees/branches at risk); proceed
only on the explicit opt-in (§6.3 / D5). This is the single definition of "unsafe to tear down"; §2c and §6 reference it
rather than restating an ambiguous "unpushed work" (r2 precision — no architecture change).

---

## 3. Axis 2 (revised r1) — the outbound / attribution path leaks under concurrent-live sessions

The **ear** (inbound: `MessageDeliverer` targets `active().connectorSessions` + replay-on-attach) is fine. The **mouth**
(outbound: a session's turn-end → hub post; usage → tracker; spawn identity) is **not**: every outbound step reads
`active()` or boot `config.projectId` at emit time. That is correct only while `active() == owner` — the invariant Axis 1
breaks by making non-boot projects background-live (K=3), so their sessions emit **async while another project is active**.

**Three code-verified leaks** (@ develop `53f7102`):

1. **The mouth mis-attributes / drops.** One shared `MediationRouter` (`BootOrchestrator.kt:280`) on the one shared
   connector. `router.onResult` resolves the agent, then `hub.state.spokeChannelFor(agentId)` (`MediationRouter.kt:45`)
   + `hub.postAsAgent` (`:55`). `postAsAgent` gates `canWrite` against the **active** ACL slice (`Hub.kt:49`) and stamps
   `projectId = state.activeProjectId` (`Hub.kt:64`); `spokeChannelFor` finds `po-<agent>` only in the **active**
   `channels` (`HubState.kt:304`). So B's turn-end while A is active → **dropped** (403; B's spoke is stashed), or on an
   agentId collision (`po`/`dev1`) **mis-routed into A's channel** and **stamped `projectId=A`**. *Keying
   `SessionRegistry` does NOT fix this — the failure is entirely downstream of the agent-id resolution.*
2. **The projector mis-attributes usage.** `onContextTokens = { agentId, tokens -> runtimeRegistry.active().tokenUsage
   .onResult(...) }` (`BootOrchestrator.kt:277`) → B's context tokens land on **A's** `AgentTokenUsageTracker`.
3. **The connector spawn-identity is boot-frozen.** `resolveApiKey = { projectConfig.resolvedApiKey(config.projectId) }`
   (`:350`) and `projectIdOf = { config.projectId }` (`:367`) hard-read the **boot** projectId → a non-boot agent
   spawns with the **boot API key** and its session-resume + `agentEvents` transcript are stamped **boot**. Corollary:
   `worktreesRoot = { active().worktrees.worktreesRoot }` (`:347`) is already `active()`-resolved, so a background-live
   **resume of a non-active** agent would even spawn in the **wrong project's worktree**. The connector's own comment
   admits the frozen assumption ("MVP-correct — an agent doesn't switch project mid-life", `:364-365`) — Axis 1 voids it.

### The root cause + the fix seam

`active()` is a *point-in-time* answer; async outbound events need the **owning** projectId of the session that produced
them. The fix is to **thread the owning `projectId` through the outbound path** instead of reading `active()`:

- **Spawn identity (leak 3) — required for Axis 1 regardless of concurrency.** The per-project lifecycle already knows
  its `pid`; pass it into `connector.open(agentId, worktree, projectId)` and resolve `resolveApiKey(pid)` /
  `projectIdOf = pid` / `worktreesRoot(pid)` from **that**, not `config.projectId`/`active()`. (Even in a
  teardown-on-switch world this is needed: a just-activated non-boot project must spawn with **its** key + stamp.)
- **Mouth + usage (leaks 1–2) — required only if sessions of non-active projects stay live.** The router must post as
  the session's **owner**: `SessionRegistry` gains the `(projectId, …)` dimension (the pattern §5 already uses), and
  `Hub.postAsAgent` / `spokeChannelFor` / `canWrite` must resolve the channel + ACL from the **owning project's slice**
  (active *or* stashed) and stamp the **owning** projectId — i.e. `HubState` must serve *any* project's slice by id, not
  only the one active slice. `onContextTokens` must route to `runtimeRegistry.of(ownerPid).tokenUsage`, not `active()`.

**This makes the left-project-session policy an *architectural fork*, not just a resource knob (see §4).**

---

## 4. Left-project sessions — now the architectural fork (decision D3)

§3 makes this **not** a pure resource knob: it decides whether the mouth-attribution re-architecture (leaks 1–2) is a
**hard prerequisite** of the Axis-1 rollout.

**Today:** background-live, hard **LRU cap K = 3** (`RuntimeSuspensionPolicy`, `runtimeSuspensionCap`,
`BootOrchestrator.kt:197`); "suspend" = kill the `claude` processes, keep the runtime object, `--resume` on re-entry
(session ids persisted, CYP-167). Full runtime reclaim (CYP-247.5) + CYP-306/.5b full-eviction are **not implemented**.
This "works" today only because non-boot projects are barely runnable (no per-project clone) — Axis 1 is what makes them
fire real async outbound events.

| Option | Behaviour | Attribution cost | Trade-off |
|---|---|---|---|
| **A — background-live (K>1, today's default)** | non-active projects keep running | **requires leaks 1–2 fixed** (HubState multi-slice post + `of(pid).tokenUsage` + `SessionRegistry` keying) — the big, risky slice | away-agents progress; N concurrent `claude` + N live worktrees = cost |
| **B — teardown-on-switch (cap=1) (RECOMMEND for Axis-1 MVP)** | only the active project has live sessions → `active() == owner` always | leaks 1–2 **do not bite** (active-reads are correct); only leak 3 (spawn identity) must be fixed | away-agents stop; re-entry re-spawns (`--resume`) — bounded, safe |

**Recommendation: B for the Axis-1 MVP.** Ship per-project clones with **cap=1** (teardown-on-switch), so the invariant
`active() == owner` holds and the shared mouth/projector stay correct with only the spawn-identity fix (leak 3). This
retires Axis-1 risk without re-architecting `HubState` into a concurrent multi-slice post target. **Background-live
(Option A) becomes an explicit later slice** (S5) that lands the mouth-attribution work first — expose the cap as policy
*then*, once posting-as-owner is real. Rationale: don't make non-boot projects concurrently-live until their outbound
events can be correctly attributed; the alternative silently drops/mis-stamps cross-project turn-ends.
(Note: cap=1 is a config value on the existing `RuntimeSuspensionPolicy` — no new mechanism; it does not preclude
restoring K>1 later.)

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
   **Runs the SAME safe-teardown guard (§2d) before any destruction** — the legacy `projects/*` worktrees + their
   `agent/<name>` branches may hold committed-but-unpushed work; a blind re-clone would erase it silently. Idempotent,
   logged; on a positive guard → block + report until the opt-in (§6.3).
2. **Boot-time reconciler:** for each project in `ProjectRegistry`, ensure `clones/<pid>` matches `resolvedRepo(pid)`;
   a URL mismatch → re-provision (§2c, D4) **through §2d**. For residual `projects/<pid>/*` **not** in the registry →
   prune (`deleteProject`'s `git worktree remove --force` + `git worktree prune`), **also gated by §2d**.
3. **Destructiveness (decision D5):** the §2d guard is the safety net; recommend the prune be **opt-in** (a flag
   mirroring `?deleteWorktrees=`), default **preserve + log what WOULD be pruned AND what §2d flagged as at-risk**
   (dirty tree and/or unpushed `agent/<name>` commits) — never auto-destroy either.

---

## 7. Proposed slicing (for the gated build, after ratification)

Each slice independently gated. For the **cap=1 MVP** (D3=B) the money-tooth is **switch to project B (different repo) →
its agent spawns with B's key + B's clone/worktree + B's projectId stamp; switch back → A intact** (serial, `active()==owner`).
The **concurrent** money-tooth (two projects live at once, no cross-attribution) is deferred to S5 with background-live.

- **S1** — per-project clone: `WorktreeManager.repoDir` per-project + `ensureClone(pid)` on lazy first-need + per-project
  base branch. (Axis 1 core.)
- **S1b (paired with S1) — spawn-identity fix (leak 3), MANDATORY.** Thread the spawning `projectId` into
  `connector.open` → `resolveApiKey(pid)` / `projectIdOf=pid` / `worktreesRoot(pid)`. Without this a non-boot agent
  spawns with the boot key + boot-stamped transcript even when it IS active. Not optional — ships with S1.
- **S2** — repo re-point as re-provision (setRepo → stale → re-clone) with the uncommitted-work warning.
- **S3** — set the default to **cap=1 / teardown-on-switch** (D3=B) so `active()==owner` holds for the MVP.
- **S4** — legacy migration + boot reconciler + opt-in stale-prune.
- **S5 (defer — background-live)** — the mouth-attribution re-architecture (leaks 1–2): `SessionRegistry` `(projectId,…)`
  keying + `HubState` posting to any project's slice by owner + `onContextTokens → of(pid).tokenUsage`; THEN expose the
  suspension cap as policy (K>1) + CYP-247.5 runtime reclaim (folds CYP-306/.5b). Concurrent-live money-tooth lands here.

---

## 8. Open decisions for ratification (the ask)

| # | Decision | Recommendation |
|---|---|---|
| **D1** | Clone/worktree layout | `clones/<pid>/` + keep worktrees at `projects/<pid>/<name>` (min ripple; preserves CYP-315 path) |
| **D2** | Non-boot project with no own repo | **inherit boot repo** as fallback; diverge only on explicit `setRepo` |
| **D3** | Left-project sessions **(now the fork, §3–§4)** | **Option B — teardown-on-switch (cap=1) for the Axis-1 MVP**; background-live (K>1) deferred to S5 behind the mouth-attribution fix |
| **D4** | Repo re-point | **re-provision** (tear down + re-clone, with work-warning), not in-place remote swap |
| **D5** | Stale-dir cleanup | **opt-in** (flag), default preserve + log |
| **D6** | When to clone a non-boot project | **lazy** on first need (activation/add/start), not eager on create |
| **D7** (new, r1) | Connector spawn-identity fix (leak 3) | **thread spawning `projectId` into `connector.open`** (key/stamp/cwd from the owner) — **mandatory with S1**, independent of D3 |
| **D8** (new, r1) | When to build the mouth-attribution re-architecture (leaks 1–2) | **only when background-live is wanted** (S5); the cap=1 MVP does not need it — confirm you accept "away-agents pause on switch" for now |

Once D1–D8 are ratified, I slice per §7 and build risk-first: **S1 + S1b (clone + spawn-identity) with the serial
switch money-tooth first**; background-live + concurrent attribution stays behind D8/S5.

---

## 9. Revision log

- **r1** (this pass): folded in the code-verified outbound-attribution gap from the second opinion (§3): three leaks
  (mouth / projector / boot-frozen spawn-identity), verified @ `53f7102`. Corrected the §0 "confirm-only" under-count.
  Reframed §4 as an architectural fork and flipped D3 → teardown-on-switch (cap=1) for the MVP. Added D7 (mandatory
  spawn-identity fix) + D8 (background-live gate) and S1b/S5 in §7. Axis 1 direction + D1/D2/D4/D5/D6 unchanged.
  *(The second opinion's revision list (a)–(d) arrived truncated at (a); this pass addresses the verified gap in full —
  flag if (b)–(d) intended anything beyond the outbound-attribution axis.)*
- **r2** (item (c)): the safe-teardown guard is now **single-sourced as §2d** and must cover **committed-but-unpushed
  work on `agent/<name>` branches**, not just a dirty tree — else re-provision/legacy-migration destroy pushed-less
  commits silently. §2d defines the two checks (dirty tree; `git log @{u}..agent/<name>` non-empty / no upstream); §2c
  and §6.1/6.2/6.3 now all reference it. Confirmed the PO's (a)/(b)/(d) were already covered by r1. Doc-only, no
  architecture change.
