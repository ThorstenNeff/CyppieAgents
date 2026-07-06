# CYP-247 — Per-Project Agent Lifecycle (L): Design Note

> Status: **DESIGN-FIRST — ratification artifact, NO code.** Owner: Backend. Builds on CYP-246 (M).
> Ratify → then build the sub-stories. The **teardown-policy button** (§4) needs Auftraggeber sign-off.

## 0. TL;DR

CYP-246 (M) partitioned the agent **set** per project (`HubState.stashedAgents`, `rescope` swaps the
active slice; a fresh project shows 0 agents). M explicitly does **not** make an agent **runnable** in a
non-boot project: the spawn/worktree/lifecycle machinery is a set of **agentId-keyed singletons pinned to
the boot project**. L de-singletonizes that machinery to `(projectId, agentId)` so the Auftraggeber can
actually *work* in a new project. The load-bearing decision is the **session-teardown policy on switch**,
which must be **one consistent knob across the server (agent sessions) and the client (per-project VMs /
sockets, CYP-249)** — the Auftraggeber ratifies one button, not two divergent ones.

Recommendation in brief: **per-project runtime holder** (structural isolation, not composite-key
threading) + **teardown = persist-and-kill-with-resume** as the default (bounded resources + conversation
continuity via the existing SessionStore/`--resume`), with **background-live + LRU cap** as the fallback if
`--resume` proves unreliable. Split L into 6 sub-stories, each PO-gated like the CYP-220 phases.

---

## 1. What M left standing (the collision surface)

Everything agent-*lifecycle* is a singleton keyed by bare `agentId`, constructed once for the boot project:

| Component | File | State / keying | Collision when 2 projects share an agent id (`po`, `backend`) |
|---|---|---|---|
| `WorktreeManager` | boot/WorktreeManager.kt:41,45 | `activeProjectId` **fixed at construction** → `projects/$activeProjectId/`. Disk layout *is* per-project; the **instance** is boot-pinned. `deleteProject(projectId)` already takes an explicit id. | Spawns land in `projects/<bootId>/<worktree>` regardless of active project → wrong dir, cross-project git contamination |
| `LifecycleManager` | boot/LifecycleManager.kt:45-46 | `worktreeOf`, `status` = `Map<agentId, …>`; `_events` SharedFlow → `/ws/lifecycle`; holds `sessions` + `spawn` lambda | `register("backend")` in B overwrites A's entry; run-state/stop cross-talk; one stop kills the wrong process |
| `ConnectorSessions` | connector/ConnectorSession.kt:78 | `byAgent = Map<agentId, ConnectorSession>` (live sessions) + register-listeners (the deliverer's ear) | B's live session evicts A's from the map → A's turns route to B's process |
| `AgentConfigRegistry` | boot/AgentConfigRegistry.kt:22 | `configs = Map<agentId, persona/launch/kind>` | B's persona/launch overwrites A's → A respawns with B's CLAUDE.md |
| `CapabilityRegistry` | connector/CapabilityRegistry.kt:17 | `byAgent = Map<agentId, Capabilities>` | fidelity read model bleeds across projects |
| `ProviderRegistry` | connector/ProviderRegistry.kt:14 | `byAgent = Map<agentId, ProviderInfo>` | provider display bleeds across projects |
| `SessionRegistry` (mediation) | mediation/SessionRegistry.kt:11 | `sessionToAgent = Map<streamSessionId, agentId>` | keyed by the connector's own session id (unique) → **low collision risk**, but the resolved `agentId` is still project-ambiguous downstream |

**Already per-project (no L work, they anchor the design):**
- Worktree **disk** layout `projects/<projectId>/<name>` (CYP-82) — only the manager instance is pinned.
- The **durable stores**: `AgentOverrideStore`, avatar blobs, `SessionStore`, event log, etc. are already
  `(projectId, …)`-keyed and routed per project by CYP-220 (`PgStoreRouting`). CYP-246 already made
  `AgentManagement` write overrides under the *active* project. So the **durable** side of a per-project
  agent already has a home; L is the **in-memory runtime** catching up to it.
- The `Hub`/deliverer read `hub.state.activeProjectId` dynamically (rescope-aware) — no change needed.

**Structural risk of the naive fix:** threading `projectId` into every one of these call sites is
**fail-open** — miss one and you get a silent cross-project leak (the exact class of bug M just fixed).
The design must prefer **isolation by construction** over "remember to pass projectId everywhere".

---

## 2. Two architectures

### Arch A — Composite key `(projectId, agentId)` everywhere
Change each `Map<agentId, X>` to `Map<AgentRef, X>` with `AgentRef(projectId, agentId)`; keep single
manager instances.
- **Pros:** smallest structural delta; one `_events` flow; one `sessions` map.
- **Cons:** every call site must resolve+thread the active `projectId` (fail-open if one is missed);
  broadcast flows (`/ws/lifecycle`, `/ws/agent`) must carry+filter projectId; the "isolation" lives in
  discipline, not structure. Hardest to prove exhaustively.

### Arch B — Per-project **runtime holder** (recommended)
A `ProjectRuntime` bundles the per-project instances `{ LifecycleManager, ConnectorSessions,
AgentConfigRegistry, CapabilityRegistry, ProviderRegistry, WorktreeManager-view, agent slice }`. A
`RuntimeRegistry : Map<projectId, ProjectRuntime>` (lazy-created on first activation, seeded for the boot
project). The active runtime is selected by the registry pointer; a switch activates the target runtime.
- **Pros:** **isolation by construction** — there is no shared agentId-keyed map to leak through; a whole
  runtime is the unit; matches the code's own "per-project hub/session re-instancing (S17)" language;
  aligns 1:1 with CYP-220's per-project store routing (a runtime resolves its stores by its projectId);
  the teardown policy has a natural home (tear down / keep a whole runtime).
- **Cons:** larger `BootOrchestrator` refactor (it builds one of each today); N live runtimes = N resource
  sets (this is exactly the teardown question, §4); `/ws/*` routes resolve the runtime per connection.

**Recommendation: Arch B.** The fail-open risk of Arch A is the same failure mode as the bug we are
fixing; structural isolation is worth the larger refactor, and it is the direction the codebase already
names. Arch A's composite key can still be used *inside* shared cross-cutting sinks that must span projects
(e.g. a single event sink stamped by projectId, which already exists).

**Non-goal / keep shared:** the event log + audit already carry `projectId` and are read-scoped; they stay
single sinks stamped per project (do **not** per-project-instance them — that would fragment observability).

---

## 3. The clean cut (build order, no behavior change first)

1. **Runtime seam (scaffold, zero behavior change):** introduce `ProjectRuntime` + `RuntimeRegistry`; boot
   builds the boot project's runtime *through* it; every existing call resolves the active runtime. Pure
   refactor, gated by the full existing suite staying green (the CYP-223 store-seam pattern).
2. **WorktreeManager per-project:** un-pin `activeProjectId`; resolve `projects/<activeProjectId>/` per op
   (the disk layout + `deleteProject(projectId)` already support it). Spawn into the active runtime's root.
3. **Lifecycle/Sessions/Configs/Capabilities/Provider per-project:** these become per-runtime instances
   (Arch B) → agentId collisions structurally impossible. `/ws/lifecycle` + `/ws/agent` resolve the runtime.
4. **Switch orchestration + teardown (§4):** on switch, activate the target runtime and apply the ratified
   teardown to the leaving one. **Client-coupled (CYP-249) — same button.**
5. **Per-project spawn/CRUD enablement:** `POST /api/agents` in a non-boot project spawns into its runtime
   (the actual "work in a new project" capability). Includes the *add-a-PO-to-a-fresh-project* flow (M
   leaves a fresh project with no PO; L lets the user create one so hub-and-spoke can form).
6. **Test strategy (§6).**

---

## 4. ⚠️ The one button — session-teardown policy on switch (needs ratification)

When a user leaves project A for B, what happens to A's live agent sessions (server) **and** A's per-project
VMs/sockets (client, CYP-249)? This must be **one consistent decision across both layers** — the client
mirrors the server. Options:

### Option 1 — Background-live (keep everything running)
Leaving A keeps A's `claude` processes + stdio alive (server) and A's sockets/VMs alive (client). Re-entry
is instant, zero loss.
- **Resource/cost:** unbounded — `N projects × M agents` live child processes + PTYs/stdio + **API token
  burn** if agents are mid-turn + N socket sets on the client. Grows with project count; a power user with
  10 projects pays for 10 idle project-fleets.
- **Fit:** best UX continuity, worst resource story. Needs at least an LRU cap to be safe.

### Option 2 — Teardown-on-switch (kill on leave)
Leaving A stops A's processes (server) + disposes A's VMs/sockets (client). Re-entry cold-respawns.
- **Resource/cost:** bounded — only the active project's fleet lives. Cheapest.
- **Loss:** an in-flight turn/tool execution is interrupted; **conversation continuity depends on resume**
  (see Option 3) — a naive kill without resume loses the conversation, not just the in-flight step.

### Option 3 — Persist-and-kill-with-resume (recommended middle path)
Leaving A **persists each agent's session id** (the `SessionStore` + `--resume <session_id>` path already
exists), **kills** the process, disposes sockets. Re-entering A **re-spawns with `--resume`**, restoring the
conversation.
- **Resource/cost:** bounded like Option 2 (only active fleet lives). No token burn while away.
- **Loss:** only a *mid-execution tool call* at switch-time is interrupted (the agent resumes the
  conversation and re-attempts) — **conversation state is preserved**, unlike a naive Option 2.
- **Dependency/risk:** `--resume` is the version-sensitive, spike-flagged path (CYP session-resume). If it
  is not robust enough in practice, this degrades to Option 2's loss profile.

**Recommendation: Option 3 (persist-kill-resume) as the default**, because it gives most of Option 1's
continuity with Option 2's resource bound. **Fallback: Option 1 background-live with a hard LRU cap** (keep
the last *K* projects' fleets live, evict the oldest via Option 3) if `--resume` reliability is
insufficient — this caps resources while keeping the *K* most-recent projects instant.

**Cross-layer consistency (the actual button):** whichever server option is ratified, the client (CYP-249)
mirrors it — teardown+resume ⟺ client disposes VMs/sockets on leave + re-fetches/reconnects on re-entry;
background-live ⟺ client keeps them. The Auftraggeber ratifies **one** knob; Dev and I implement the two
halves of the same decision. **This is the item I am escalating for sign-off before building §4/§5.**

---

## 5. CYP-220 alignment

- The durable per-project state (overrides, avatar blobs, sessions, event log, delivery log, reports) is
  **already `(projectId, …)`-keyed and routed per project** by CYP-220 (`PgStoreRouting`, memoized for the
  long-lived stores). L's in-memory `(projectId, agentId)` runtime is the **mirror** of that — a
  `ProjectRuntime` resolves its stores through the CYP-220 routing for its own projectId, so in-memory and
  durable agree by construction.
- **No conflict; L consumes CYP-220.** The only new durable need L raises is a **per-project agent-set
  store** (today the agent set is boot-config only; M keeps non-boot projects' sets in-memory). Persisting a
  non-boot project's agent set across restart is its own sub-story (CYP-247.5) and should route through the
  CYP-220 store seam like every other per-project store — **not** a bespoke file.
- The teardown persist path (Option 3) uses the existing `SessionStore` (already per-project via CYP-220).

---

## 6. Test strategy

- **agentId-collision-leak tooth (the core L invariant):** two projects each with an agent id `backend`;
  spawn in A, switch to B, spawn B's `backend`; assert A's worktree (`projects/A/backend`) ≠ B's
  (`projects/B/backend`), A's session ≠ B's, no run-state/event bleed. **Mutation:** revert any one manager
  to agentId-only keying → cross-talk RED. This is the fail-open guard made non-vacuous.
- **worktree isolation:** `projects/A/backend` and `projects/B/backend` are distinct dirs; a commit in one
  never appears in the other (FakeGit assertion).
- **switch-teardown tooth (per ratified policy):** Option 3 → leaving A kills A's process AND re-entering A
  resumes the SAME conversation (SessionStore/`--resume` asserted); Option 1 → leaving A keeps A's process
  alive (assert liveness). The tooth encodes whichever button is ratified.
- **per-project spawn isolation (e2e journey):** create B via the real API, add a PO + worker to B, start
  them, assert they run in B's worktree and don't touch A — extends the CYP-246 e2e harness (which already
  seeds a hub PO per project) to actually *spawn* per project.
- **no regression to M's isolation:** the CYP-246 teeth (fresh project = 0 agents, reload-durable) stay
  green on the runtime-holder refactor.

---

## 7. Sizing + story split (L is too big for one ticket → sub-stories under CYP-247)

| Story | Scope | Size | Gate |
|---|---|---|---|
| CYP-247.1 | Runtime seam scaffold (`ProjectRuntime` + `RuntimeRegistry`), boot builds the boot runtime through it, **zero behavior change** | M | full suite green (refactor) |
| CYP-247.2 | `WorktreeManager` per-project (un-pin activeProjectId, spawn into active runtime's root) | S | worktree isolation tooth |
| CYP-247.3 | Lifecycle/Sessions/Configs/Capabilities/Provider → per-runtime; `/ws/lifecycle`+`/ws/agent` resolve the runtime | L | collision-leak tooth (mutation-RED) |
| CYP-247.4 | Switch orchestration + **ratified teardown policy** (server half); client-coupled to CYP-249 | M | switch-teardown tooth |
| CYP-247.5 | Per-project spawn/CRUD enablement + add-PO-to-fresh-project + per-project agent-set store (via CYP-220 seam) | M | per-project spawn e2e |
| CYP-247.6 | Full e2e per-project spawn-isolation journey + M-isolation regression pass | S | e2e green |

**Overall L ≈ L (1–1.5 wk).** Recommended order 1→2→3→4→5→6; 4 gates on the teardown ratification, so
1→3 can proceed while the Auftraggeber decides the button.

---

## 8. Open questions for ratification

1. **Teardown button (§4)** — Option 3 (persist-kill-resume, my rec) vs Option 1 (background-live + LRU cap)
   vs Option 2 (plain teardown)? **One decision, both layers.** ← the escalation.
2. **Arch A vs B (§2)** — confirm the per-project runtime holder (my rec) over composite-key threading.
3. **Per-project agent-set persistence (§5)** — persist a non-boot project's agent set across restart now
   (CYP-247.5) or defer (in-memory only until then)?
4. **LRU cap K** if Option 1/fallback is chosen — how many recent project fleets stay live?

**No build until this note is ratified and the teardown button is signed off.**
