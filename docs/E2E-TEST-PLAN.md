# E2E Test Plan — Integrated Multi-Project Platform (S12→S17)

> Owner: QA/Test Engineer · Status: **APPROVED by PO 2026-06-29 — QA leads; build dispatched** · Target: develop `2041d59` (S12→S17 complete)
> Roadmap Phase 3 (E2E). Mobile out of scope — **JVM gate is authoritative**. Reference docs: 02-Spec, 05-Decisions, CROSS-PROJECT.md.

## 0. Purpose & the class of bug E2E catches

Per-component unit/integration coverage is already deep (ChannelShareStore/Routes, EventScope/Override, AclMatrixSharePermit, CrossProject* client, ProjectRegistry/Deleter, ConfigSwitchScoping, …). **E2E exists to catch the cross-component, full-request-path, multi-project journeys that per-component gates miss** — exactly the class CYP-103 was (each ticket green in isolation; the "all active-project surfaces follow a switch" invariant had a hole at config). E2E drives a **real client over the real HTTP/WS surface against a real (embedded) server with real stores and ≥2 projects**, and asserts the end-to-end pre/postconditions + the fail-closed / no-cross-project axes.

## 1. Two tiers (the split the PO asked for)

### Tier A — Hermetic Embedded-Server E2E (the bulk; autonomous, in every JVM gate)
- Real Ktor **client** ↔ embedded Ktor **server** (`testApplication` / `installPlatform` over a real `BootedPlatform`) with **real stores** (InMemory or tmp 0600 files), **real token auth**, **≥2 seeded projects**. **No real agents** — agent sessions are faked at the connector seam (the platform already supports a fake spawner/sessions in tests).
- Deterministic, quota-free, fast → these gate. This is where J1–J7 below live.

### Tier B — Real-Agent E2E (quota-sensitive → **human-gated, NOT autonomous**)
- Spawns a **real `claude` process via stream-json** in a worktree (the S8 durchstich) on the full S12→S17 feature set. Costs quota + money + is version-sensitive.
- **Gating:** explicit **human go** each run; **pin** the CLI version; key from host env (`ANTHROPIC_API_KEY`), never repo/log. Flagged as a **separate, human-released checklist item** (RB1) — the autonomous gate never triggers it.

## 2. Tier A journeys

Each journey: **Pre** (setup) · **Steps** · **Proves** (postcondition) · **Fail-closed / no-cross-project axes**. All run with ≥2 projects (call them **A** = boot/active, **B** = second) unless noted. Operator token vs agent token are both exercised.

### J1 — Multi-Project Lifecycle (create / rename / switch / **delete-cascade**)
- **Pre:** booted platform, project A active.
- **Steps:** `POST /api/projects` create B → `PUT` rename B → `POST .../{B}/switch` → switch back to A → `DELETE .../{B}` (with and without `?worktree=delete`).
- **Proves:** registry reflects each op; create/rename/switch operator-gated; delete cascades **only B's** partitions — events (`deleteByProject`), config override (`remove`), worktrees (opt-in `?worktree=delete`, default KEEP); registry pointer + persisted file consistent across the sequence.
- **Fail-closed axes:** participant token → 403 on every mutation; **active project not deletable** (409 `active_project_protected`); **last project not deletable** (409 `last_project`); a rejected delete **cascades nothing**; **delete B never touches A's** events/config/worktrees (no-cross-project teardown); blank/garbage projectId → fail-closed (no unscoped wipe); `SAFE_ID` rejects path-traversal ids at create (400).

### J2 — Per-Project Isolation follows the active switch (comm / ACL / event-log / config)
- **Pre:** A active, B exists; both have distinct channels/ACL/events/config-at-rest.
- **Steps:** read `/api/channels`, `/acl`, `/api/events`, `/api/config/*` while active=A → `switch` to B → re-read all four → switch back.
- **Proves:** **every** active-project surface re-scopes to B **without restart** (channels/inbox/ACL via `HubState.rescope`; events via live registry pointer; config via the CYP-103 resolver fix); A's data untouched by B-context writes.
- **Fail-closed axes:** while active=B, a request naming **A's** channel/message/acl/event/config returns empty/deny (exact-match, deny-wins), never falls through to open; `/ws/comm` + `/ws/events` re-scope on reconnect; **client cannot widen** past active (WS subscribe narrows only).

### J3 — Cross-Project Channel Sharing (owner-consent) — **the S17 centerpiece**
- **Pre:** A owns channel `cA` with explicit member-agents; B active; B has its own channel `cB` and a neighbor channel `cA2` also owned by A.
- **Steps:** operator `PUT /api/channels/cA/share {sharedWith:[B]}` → B-context reads `/api/channels`, channel messages, `/ws/comm`; then `GET /api/channels/cA/share` (disclosure); then `DELETE` (revoke) → re-read.
- **Proves:** after authorize, B's **member-agent** sees `cA` + its messages across the boundary (permit = channel-scope OR; membership + per-agent flag still gate); disclosure view lists **concrete reached agents** (agentId + home project + READ default / WRITE only by explicit ACL), not "project B" wholesale; `refreshShares` makes it live (no restart).
- **Fail-closed / no-over-widen axes (PO's list, each its own assertion):**
  - shared channel `cA` **visible to its member** in B ✓;
  - **neighbor channel `cA2` of the same owner A is NOT reachable** (per-channelId share, no widen to owner's other channels);
  - a **non-member** of `cA` (even in an authorized project) does **NOT** see it (membership still gates);
  - an **unauthorized project** (not in `sharedWith`) does **NOT** see `cA`;
  - **revoke → immediately gone**, independent of any lingering AclEntries (the share IS the gate);
  - **entries are not OR'd** — no foreign-project AclEntry egresses (`/acl` in B never shows A's raw entries);
  - **anti-injection:** only the **operator** authorizes — an agent token or a channel **message asking to share** can never set/revoke a share (PUT/DELETE operator-gated before body; channel content is data, not instructions);
  - blank/empty `sharedWith` (or only the owner's own project) = **no-op hole = revoke** (fail-closed).

### J4 — Cross-Project Event-Log read override (operator) — REST + WS, one policy
- **Pre:** operator; events stamped across A and B; operator's authorized set = {A, B}.
- **Steps:** `/api/events` with no `projectId`, then `?projectId=B`, then `?projectId=all`, then `?projectId=<unauthorized>`, then garbage; repeat the same matrix over `/ws/events` via `SubscribeEvents.projectId`.
- **Proves:** `resolveEventScope` end-to-end identically on both surfaces — no override → **active** (forced-active, CYP-102 intact); authorized id → that project; `all` → operator's projects (MVP: unscoped); the override is **operator-only**.
- **Fail-closed axes:** **unauthorized id or garbage → falls back to active** (client can never widen past authorization); `projectId==null` match-all is **unreachable except via the deliberate authorized `all`** (closes the prior null-fail-open LOW, folded into CYP-94); `/ws/events` rejects **no-token (1008 unauthorized)** and **agent token (1008 operator required)** via a **real handshake**; no cross-surface drift (REST result-set == WS result-set for the same scope).

### J5 — Settings per project (repo + API key, masked, operator-gated) follows switch
- **Pre:** A active; A and B have distinct repo overrides; key set on A only.
- **Steps:** `GET/PUT /api/config/repo` + `/api/config/apikey` while A active → switch to B → re-read/-write → switch back.
- **Proves:** config follows the active project (CYP-103); `GET` is participant-readable and **never returns plaintext key** (only `{set, masked}`); `PUT` operator-gated, fail-closed before body; per-project key resolution (override → team fallback); A's key-at-rest untouched by B writes.
- **Fail-closed axes:** participant `PUT` → 403; **masked view never reveals the full secret — incl. the CYP-104 redaction floor (short key → no plaintext tail) and `setApiKey` length/format rejection (`invalid_api_key`)** (PO §6.2: CYP-104 folded into J5, no caveat); invalid repo url → 400 `invalid_repo_url`; key never logged / never in any non-config response.

### J6 — Agent Lifecycle (start / stop / restart) + status feed
- **Pre:** booted platform with faked sessions; mix of booted + failed-spawn agents.
- **Steps:** `/api/agents` list (with live runState) → lifecycle `stop`/`start`/`restart` (REST) → observe `/ws/lifecycle` status feed.
- **Proves:** controls operator-gated; status display participant-readable; runState transitions reflected live; restart picks up a changed key on next spawn (D3).
- **Fail-closed axes:** participant → 403 on controls; a **failed-spawn agent has no session and no open `/ws/agent`** (fail-closed); lifecycle controls scoped to the active project's agents.

### J7 — Observability (Event-Log browse + tail + Scanner/Warden S11) — metadata-only end-to-end
- **Pre:** operator; a run that produces events incl. an S11 stall (Scanner→Warden nudge/actuate).
- **Steps:** browse `/api/events` (paged, stable over `seq`) → live-tail `/ws/events` → trigger/replay a stall → observe stall.suspected / nudge.sent (or modelled types) appear; ordering + drop-visibility via the **deterministic drop-injection seam** (PO §6.4: Backend builds a test seam to inject a `log.dropped`, analogous to the existing test-injection).
- **Proves:** browse paging stable + ordered by seq; live-tail pushes in order; Scanner/Warden events surface; an injected `log.dropped` is **visibly** surfaced (drop-visibility honest, not silently swallowed).
- **Fail-closed axes:** **metadata-only projection holds end-to-end** — the two-class needle harness (SECRET_NEEDLE caught by masker + PLAIN_NEEDLE structural) shows **no `TextBlock.text`/`tool.input`/`tool_result.content`/`Message.body`** ever reaches event `detail` over REST **or** WS; operator-only (agent token rejected); reconnect idempotent over event `seq`.

## 3. Cross-cutting axis matrix (assert across journeys, not once)

| Axis | Where enforced | E2E assertion |
|---|---|---|
| Operator vs participant gating | every mutating/operator route | participant token → 403; no-token → 401/1008 |
| Fail-closed default | scope resolvers, guards, stores | blank/garbage/unauthorized → deny/active, never open |
| No-cross-project | AclMatrix, EventScope, cascade-delete, config | A's data invisible/untouched in B-context |
| No secret egress | SecretMasker, EventProjector, ConfigStore | needle-absence + masked-only key, over REST **and** WS |
| Localhost binding + CORS | bootHost, CORS plugin | cross-origin WS upgrade refused (real handshake, 403) |
| Reconnect idempotency | comm `message.id`, events `seq` | no dup-delivery after WS reconnect |
| Anti-injection | share/acl/lifecycle mutations | channel message can never change access/share/pairing |

## 4. Tier B (real-agent, human-gated) — defined, NOT autonomous

**RB1 — Full-stack durchstich on S12→S17:** real `claude` session (stream-json) in a worktree → PO injects task → mediator reads event stream → posts to hub channel → operator observes in Event-Log + comm timeline → agent commits/pushes in its worktree. Replays the S8 happy-path over the integrated platform.
- **Pre-reqs (human-released):** explicit go per run · pinned `@anthropic-ai/claude-code@<fixed>` · `ANTHROPIC_API_KEY` from env (masked, never logged) · localhost only.
- **Proves:** the connector/mediation seam works against the full feature set (events, comm, scoping) with a real agent.
- **Why gated:** quota + cost + non-determinism. The autonomous JVM gate never runs this; it is a manual, scheduled, human-approved pass.

## 5. Build dispatch (proposed to PO)

- **QA (me):** owns this catalogue, the per-journey assertions + fail-closed axes, the cross-cutting matrix, and re-verification. Authors the journey specs.
- **Backend:** the **embedded-server E2E harness** — multi-project `BootedPlatform` boot with real stores + faked connector/sessions + token auth, reusable across J1–J7 server paths.
- **Dev (client):** client-side E2E where the real client Ktor stack is exercised (repository/ViewModel against the embedded server) — much exists at component level (CrossProject*, *HttpRepositoryE2e); extend to multi-project journeys.
- **Sequencing:** harness first (Backend) → J1/J2 (isolation+lifecycle, lowest risk) → J3/J4 (cross-project, highest value) → J5/J6/J7 → RB1 last, human-gated.

## 6. Decisions (PO-approved 2026-06-29)
1. **Harness home:** use the **established embedded-Ktor E2E pattern** (`:app:shared:jvmTest` with a real server + real client repos, where the per-feature E2Es live). **If** multi-project boot slows the regular gate noticeably → Backend isolates it into a **separate tagged E2E source set** that the E2E gate runs (condition: `:server` + real client repos on the classpath; the regular gate stays fast). Backend decides the mechanic by measured boot cost.
2. **CYP-104:** fixed now (PO routes to Backend) → **J5 covers the masking floor** (folded in, no "once lands" caveat).
3. **RB1 (real-agent):** **one-off, human-released** — PO parks it for the human owner and gets the go **once Tier A is green** (RB1 is the keystone). The autonomous gate never triggers RB1.
4. **Drop-injection seam (J7):** **yes** — Backend builds a deterministic test seam to inject a `log.dropped` into the harness (analogous to the existing test-injection).

**Build dispatch + sequence (PO-confirmed):** QA = journey specs + assertions + re-verify · Backend = embedded-server harness (multi-project boot, real stores, faked sessions, drop seam) · Dev = client-E2E where the real client stack runs. **Harness → J1/J2 → J3/J4 (highest value) → J5/J6/J7 → RB1 last (human-gated).** Method (Reviewer-confirmed): real path, non-vacuum by mutation, needle-absence for egress at EVERY hop.

## 7. Harness contract (what the embedded-server E2E harness must expose — for Backend)

A reusable fixture so journeys read as intent, not boilerplate. Sketch (Backend owns the mechanic):
- **`e2ePlatform(projects: List<SeedProject>, now: () -> Long = …)`** → a started embedded server (`installPlatform` over a real `BootedPlatform`) with: real `ProjectRegistry` (≥2 seeded projects, first = active), real `HubState`/`Hub`, real `EventSink` (in-memory or tmp), real `ProjectConfigStore`/`ChannelShareStore` (tmp 0600 or in-memory), real `TokenRegistry` (operator + per-agent tokens), **faked connector/sessions** (no real `claude`).
- **`SeedProject(id, name, agents, channels, acl, repo?, apiKey?)`** — declarative per-project seed so a journey sets up A and B in a few lines.
- **Client factory** `client()` → a real Ktor client (ContentNegotiation + WebSockets) bound to the test server; helpers `asOperator()/asAgent(id)` set the bearer.
- **Drop seam** `injectDroppedEvent(projectId, …)` → deterministically appends a `log.dropped` (J7).
- **Switch helper** `switchActive(projectId)` → drives `POST /api/projects/{id}/switch` (so journeys assert *through the real endpoint*, not by poking the registry).
- **WS helpers** for real `client.webSocket{}` handshakes on `/ws/comm`, `/ws/events`, `/ws/lifecycle` (NEVER `client.get` on a `/ws/...`).

Acceptance for the harness itself: a trivial smoke journey (boot 2 projects, operator GET `/api/projects` returns both, agent GET `/api/channels` returns only its project's channels) green before J1 builds on it.

## 8. J1 / J2 — FINALIZED assertion catalogue (CYP-107, first build slice)

Implementation-ready. Each row: **pre** (via §7 harness `e2ePlatform`/`SeedProject`) · **act** (real endpoint via `asOperator()`/`asAgent(id)`/`switchActive`) · **assert** (postcondition + the fail-closed/no-cross-project axis). Every mutation is **proven by reading back through the real path** (non-vacuum), never by inspecting internal state. Default seed: **A**(active)=`{po,frontend}` + channel `a-fe`; **B**=`{po,backend}` + channel `b-be`.

### J1 — Multi-Project Lifecycle (create / rename / switch / delete-cascade)
| Test | Pre | Act | Assert |
|---|---|---|---|
| `create_operator_addsB_201_listShowsBoth` | only A | operator `POST /api/projects {B}` | 201; `GET /api/projects` = {A,B}; B persisted (re-GET) |
| `create_participant_403_nothingAdded` | only A | agent `POST /api/projects {B}` | 403; list still {A} |
| `create_badId_400_invalidProjectId` | only A | operator POST id ∈ {`../x`,`a/b`,`""`,`a b`} | 400 `invalid_project_id`; list unchanged (SAFE_ID) |
| `create_duplicate_409` | A,B | operator POST {B} again | 409 `project_exists` |
| `rename_operator_ok` / `rename_participant_403` | A,B | PUT rename B | op→200 new name on re-GET; agent→403 name unchanged |
| `switch_operator_flipsActive` | A,B | operator `switchActive(B)` | 200; `GET /api/projects` active=B; agent switch→403 active unchanged |
| `deleteB_nonActive_op_cascadesOnlyB` | A,B both w/ events+config; active=A | operator `DELETE /api/projects/B` | 200; B events (`/api/events?…` after switch attempt → B gone) + B config removed; **A's events + A's config intact** (no-cross-project) |
| `deleteB_noWorktreeFlag_keepsWorktrees` | A,B | DELETE B without `?worktree=delete` | 200; config+events gone; worktrees KEPT (default safe) |
| `delete_active_409_protected_tearsNothing` | A,B active=B | DELETE B | 409 `active_project_protected`; B fully intact (events/config/registry) |
| `delete_last_409_tearsNothing` | only A | DELETE A | 409 `last_project`; A intact |
| `delete_participant_403_nothingRemoved` | A,B | agent DELETE B | 403; B intact |

### J2 — Per-Project Isolation follows the active switch
| Test | Pre | Act | Assert |
|---|---|---|---|
| `channels_followSwitch_AnotVisibleInB` | A,B distinct channels | read `/api/channels` active=A → `switchActive(B)` → re-read | A→{a-fe}, B→{b-be}; `a-fe` NOT in B's list (re-scope, no leak) |
| `acl_followSwitch_noForeignEntriesInB` | A,B distinct ACL | `/acl` active=A → switch B → re-read | only active project's entries each time; no A-entry visible in B |
| `events_followSwitch_AeventsNotInBscope` | events stamped A & B | `/api/events` active=A → switch B → re-read | each returns only active project's events; an A-stamped event never in B's page |
| `config_followSwitch_BdistinctFromA_AuntouchedByBwrite` | A key set, B unset | GET key active=A (`set`) → switch B (`unset`) → PUT key on B → GET A again | **the CYP-103 class e2e:** B write lands on B; A's key at rest unchanged |
| `wsComm_reScopesOnReconnectAfterSwitch` | A,B | open `/ws/comm` active=A (snapshot=A chans) → switch B → reconnect | second snapshot = B's channels only |
| `wsEvents_reScopesOnReconnectAfterSwitch` | A,B events; operator | `/ws/events` active=A → switch B → reconnect | tail scoped to B; no A events pushed |
| `nameAchannelWhileActiveB_deniesForeign_failClosed` | active=B | `GET /api/channels/a-fe/messages` | 403 deny — exact-match miss, no existence leak, no fall-through-open |
| `subscribeCannotWidenPastActive` | active=B; operator | `/ws/events` send `SubscribeEvents` naming A / garbage | scope stays B (resolver re-pins); never widens (CYP-94) |

**Exit for CYP-107:** all rows green in the (E2E) JVM gate, harness smoke green, plan doc committed on `feature/CYP-107-e2e-j1-j2`. Then report → PO routes Reviewer.

## 9. Build status

- **CYP-107 (J1/J2): DONE/merged** → develop `c278727`. `:e2e:test` green; foreign-channel = 403-deny (stronger than the planned "empty").
- **CYP-108 (J3/J4 + raw-byte needle helper): IN PROGRESS.** `:e2e:test` 36/0 (J1 11, J2 7, J3 5, J4 8, smoke 1, NeedleHelperSelfTest 4).
  - **Needle helper (`NeedleAbsence.kt`) + positive-control self-test (`NeedleHelperSelfTest`):** raw-byte grep over `bodyAsText()` + `/ws` stream text for a foreign-projectId needle and a secret needle; the self-test deliberately presents each needle and asserts the helper THROWS (non-vacuous). Retrofitted into J1/J2.
  - **J4 (event override): COMPLETE.** REST + WS; no-override=active, authorized id=that, `all`=operator's projects, unauthorized/garbage=fail-closed→active; WS operator-only (1008 no-token / agent); needle-absence per active-scoped hop.
  - **J3 (cross-project sharing): share-authorization surface complete** — lifecycle, operator-gating, anti-injection (agent can't share/switch/config → 403), unknown-channel 404, no-over-widen at record level (neighbor not auto-shared), no foreign AclEntry egress, secret-needle absence.
  - **J3 grantee-READ axes DEFERRED (blocked):** "shared visible as member / neighbor NOT / non-member NOT / revoke→gone on the actual read" need a cross-project channel-membership to exist. No endpoint provisions one, and the only seam (`PUT /api/acl`) is **broken in any multi-project state** — the PO-lockout guard 409s on an out-of-active-scope hub channel (**filed as a Bug**). Permit DECISION is unit-proven (AclMatrixSharePermitTest / ChannelSharePermitWiringTest). These E2E read-axes land once the guard bug is fixed (QA re-verify).
- **CYP-109 (J5/J6/J7): IN PROGRESS, green.** `:e2e:test` 49/0 total. **J5 Settings** (repo/key per project, masked, operator-gated, follows switch; CYP-104 reject short/whitespace + masked-only, raw key never on wire). **J6 Agent-Lifecycle** (stop/start/restart transitions, operator-gating 403/401, unknown 404, double-start 409, `/ws/lifecycle` snapshot participant + 1008; `spawn_failed`/ERROR axis deferred — FakeSpawner always succeeds, unit-covered). **J7 Observability** (browse paging stable over seq, drop-visibility via `injectDroppedEvent` in browse + live-tail, metadata-only needle-absence over REST AND WS; Scanner/Warden S11 deferred — needs real agent output, unit-covered). Needle helper applied throughout.
- **Next:** CYP-111 fix (Backend) → J3 grantee-read axes re-verify. RB1/CYP-110 parked till Tier A green + human go.
