# CYP-331 — Interactive Terminals × Hub-Mediation: Architecture Design-Pass

> Status: **DESIGN-PASS (no code)** · Author: Backend/Architecture · For **Auftraggeber ratification**
> Deliverable per CYP-331 (Epic). Scope: options + cost + risk + migration. Direction is **decided**
> (not re-opened): agents run in **real interactive terminals** (full Claude-Code comfort), the **Hub
> mediates** (Hub-and-Spoke + ACL via hub-MCP/CLI), and a **coexistence obligation** holds — the same
> session spawned for stream-json must also be usable interactively (same agent/context), seam =
> `--resume <session_id>`.
>
> Grounding: this doc is factual on the *as-is* wiring (code-mapped) and the *feasibility* (PO's
> authoritative Hooks/OTEL finding + Context7 on Claude Code session storage). Where a claim is
> empirically unconfirmed it is flagged **[SPIKE]**. The **final option choice is the Auftraggeber's**;
> §9 gives a reasoned lean, not a unilateral decision.
>
> **Companion UX doc (the UX half of this deliverable):**
> `docs/design/CYP-331-interactive-terminal-coexistence-design.md` (UIUX, `f96ac9d`, branch
> `feature/CYP-331-interactive-terminal-spec`). Its UX model — **one window per agent with a
> `[Orchestration | Terminal]` mode toggle, where the toggle act *is* the hand-off** (no split-screen,
> matching the protocol either/or of §3) — is **compatible with this architecture** and drives the
> control seams in §4.4. Read the two together as one coherent deliverable.

---

## 1. The linchpin: today, *all* observability + mediation is stream-json-pipe-derived

Every live signal and every hub hand-off is derived from **parsing the `claude` stdout NDJSON
(stream-json) pipe**. One source, one reader:

```
claude stdout (stream-json NDJSON)
 → ClaudeCodeSession reader: decode + EventMasking.mask (Gate #3)
 → SessionObserver.onEvent(agentId, sessionId, correlationId, masked)      [CYP-37 tap]
 → RecordingSessionObserver:
      • AgentEventStore.record(...)                 [CYP-198 durable transcript — CONTENT → agent window]
      • EventProjector.project(...) → EventRecorder [Event-Log — CONTENT-FREE metadata]
 + onBind(system/init sid)   → SessionRegistry.bind + CYP-167 SessionStore.upsert (resume token)
 + onTurnResult(bound only)  → MediationRouter → hub.postAsAgent            [HUB MEDIATION, agent→hub]
```

All derived live feeds hang off the **shared, boot-baked, active-routed `EventProjector` hooks**:

| Feed | Ticket | Source event (from the pipe) |
|---|---|---|
| busy/idle `*` | CYP-324 | `turn.start`→busy; `result`/`processExit`/`stopped`/`restarted`→idle |
| token/context | CYP-316/325 | `ResultEvent.usage.iterations[-1]` (post-turn) |
| compact done | CYP-326 | `SystemEvent compact_result:"success"` |
| injected-turn visibility | CYP-326 #1 | synthetic `UserEvent(injectedSource)` recorded **before** the stdin write |
| PO→agent task inject | 05-D4/D5 | backend writes a user-message on the session **stdin** (`sendTurn`) |

**Consequence.** A real interactive `claude` in a **PTY renders a TUI to the terminal — it does not emit
stream-json NDJSON on stdout.** So in interactive mode the entire spine above **loses its data source**:
busy, token/context, compact-detection, injected-turn visibility, *and* the agent→hub mediation read.
Rebuilding that spine on a non-pipe source is the **central architectural work** of CYP-331 (Q1), and it
is what differentiates the options.

---

## 2. Q1 — Observability rebuild (Hooks / OTEL / hub-MCP)

Per the PO's authoritative feasibility finding (official docs, Claude Code hooks v2.1.200+, 31 events;
a hook can report to the backend over HTTP/file/socket; hook input carries `session_id`,
`transcript_path`, `cwd`):

| Signal | Interactive-mode source | Verdict |
|---|---|---|
| **busy/idle** (CYP-324) | `UserPromptSubmit` (turn-start) + `Stop`/`StopFailure` (turn-end) | ✅ clean parity |
| **compact** (CYP-326) | `PreCompact` (carries `current_context_size_tokens`) + `PostCompact` (new size + ratio) | ✅ clean boundary |
| **tool-calls / injected-turn visibility** | `PreToolUse`/`PostToolUse` + `UserPromptSubmit` | ✅ |
| **live token/context** (CYP-325) | **GAP** — no live counter via hooks; no *documented* OTEL live stream (`CLAUDE_CODE_ENABLE_TELEMETRY` exists but payload is undocumented → do not build on it). Only **post-turn** (`/usage`, or `-p --output-format json → total_cost_usd`) or the **context size at the compact boundary** (`PreCompact`). | ⚠️ **downgrade** |

**The one real regression to flag prominently:** CYP-325's *live* context gauge becomes either a
**post-turn poll (~1–5 s latency)** or **compact-boundary-only**. This is a comfort loss the Auftraggeber
must weigh — it is the strongest single argument for keeping the stream-json path for the *default/
mediated* mode (Option B) rather than a full pivot (Option A).

**Rewired shape (interactive mode):** a small **hook receiver** in `:server` (HTTP endpoint the hooks
POST to) replaces the `SessionObserver` pipe tap; it feeds the *same* `EventProjector`/tracker hooks
(CYP-316/324/326 are already seam'd on the shared projector — the trackers don't change, only their
*feeder* does). This preserves the content-free Event-Log + the busy/compact feeds unchanged downstream.

---

## 3. Q2 — Hub-mediation seam in terminal mode

Today mediation is **stdin-inject (PO→agent)** + **stdout-read (agent→hub)**. Neither survives an
interactive human-driven TUI.

- **agent→hub (emission): already have it.** `HubMcpConfigWriter` (CYP-146) hands the agent a
  `--mcp-config` exposing `hub_send` — the agent emits to the hub via an **MCP tool call**, independent
  of the pipe. This works identically in interactive mode. ✅
- **hub→agent (receive): the real gap.** Getting a PO task *into* a live interactive session without
  disturbing the human is the hard part. Candidates:
  1. **hub-MCP/CLI pull** — the agent receives via an MCP resource / `hub inbox`/`hub watch` (02 §9).
     This **re-activates the original hub-CLI design that 05-D5 deprecated** in favour of stdin-mediation.
     Agent-initiated pull is the only injection that doesn't fight the human's keyboard.
  2. **queue-at-handoff** — inject a turn only during a `--resume` hand-off (not mid-interactive).
  3. **PTY stdin as-if-typed** — rejected for live interactive (corrupts the human's input line).
- **Protocol constraint (from the Q1 finding), decisive for Q3:** CLI `--input-format stream-json` is
  *undocumented + headless-only* (GitHub #24594) and **cannot** be combined with a real interactive human
  terminal; the documented programmatic path is the **Agent SDK** (`query()` + `resume`), which is *also*
  not the interactive TUI. → **You cannot inject platform turns AND let a human drive the same session at
  the same time.** Mediation and interactive use are **mutually exclusive per session** — which forces the
  hand-off model in Q3.

---

## 4. Q3 — Hand-off state machine + session continuity

### 4.1 Hand-off is protocol-mandatory *sequential*

Because turn-injection and the interactive TUI are mutually exclusive (Q2), the same session has **exactly
one live process at a time**. The `--resume <session_id>` seam + the CYP-167 `SessionStore` (durable id)
are the hand-off token:

```
        ┌─────────────────────────┐   stop cleanly + persist sid   ┌────────────────────┐
        │  MEDIATED                │ ─────────────────────────────▶ │  INTERACTIVE       │
        │  (stream-json OR Agent   │                                 │  (real claude TUI  │
        │   SDK; backend drives)   │ ◀───────────────────────────── │   in a PTY; human) │
        └─────────────────────────┘   stop cleanly + `--resume`     └────────────────────┘
             single writer                                              single writer
```

- **Guard:** single-flight per `session_id` — never two live processes (enforced at the `LifecycleManager`
  spawn seam; the CYP-167 store is the mutex token).
- **Transition = graceful stop of the current process → spawn the other mode with `--resume <sid>`.** The
  "graceful stop" is load-bearing for continuity (§4.3).
- **Big fork the Q1 finding opens:** the *mediated* mode could migrate from **pipe-parsing → the Agent SDK**
  (hook-callbacks + `resume`-injection). That would make mediated and interactive **share one substrate**
  (both hook-driven, both `--resume`-based) and retire the bespoke stream-json reader. High-value but
  **[SPIKE]** (SDK maturity, our JVM integration, cost).

### 4.2 Two boot cases (both must end well) — reconciled with CYP-330

1. **Stale boot-resume** → the CYP-330 proactive stale-resume probe (`ResumingSession.start()`) fires **also
   in the boot spawn path** → heals to fresh → **RUNNING but context-free** (no ERROR). This is the state
   **seam #5 (§4.4)** surfaces to the UI as `CONTEXT_LOST` (the `healToFresh` branch) rather than leaving the
   UI to guess from a near-zero token count. (This was **not** what po hit — po resumed cleanly with an
   *intentional* compaction, §4.3 — but it is the exact case the signal exists for.)
2. **Throwing boot-spawn (≠ stale-resume)** → `bootAgent`'s own catch → **ERROR**, and it has **no
   `spawnFresh` rollback** (only `start`/`restart`'s `spawnOrError` got it in CYP-330). **Design point:**
   extend the fresh rollback to `bootAgent` for a guaranteed-RUNNING boot, weighed against boot's
   fail-closed-per-agent contract (a genuinely un-spawnable agent should still surface, not silently
   fresh-loop).

### 4.3 Session continuity — the real pain (core Q3 driver), **two separated risks**

Context is stored by Claude Code itself, **not** by us. Verified (Context7, official docs):
- Transcripts are JSONL at **`~/.claude/projects/<project>/<session-id>.jsonl`** (`<project>` derived from
  the working directory); relocatable via **`CLAUDE_CONFIG_DIR`**; auto-pruned after `cleanupPeriodDays`
  (default 30). Our `SessionStore` persists only the **id**, not the transcript.
- Hook input carries **`transcript_path`** — so the platform can *locate* (and back up / checkpoint) the
  live transcript per session.

**Empirical status (Auftraggeber-corrected): there is NO observed context-loss failure.** The
537 520 → 58 228 token drop that first read as a loss was a **manually-commissioned compaction** shortly
before shutdown (an *intended* shrink); **po resumed correctly with its compacted context, and so did
`test1` — the deploy preserved context for both agents.** On the evidence so far, context preservation over
a hub-restart is **solid**, not best-effort. The two risks below are therefore **forward-looking hygiene**,
**not** fixes for an observed bug. (Tellingly, this very compaction-vs-loss confusion momentarily fooled a
careful analysis — which is exactly what **seam #5 (§4.4)** exists to remove.)

Two **distinct, hypothetical** continuity risks — keep them separate:

- **(a) Transcript-durability** — Claude Code's own hosting doc: session transcripts, `CLAUDE.md`, and
  working-dir artifacts "**do not persist across container restarts, scale-downs, or moves to different
  nodes**." Relevant to a **future cloud/container/ephemeral-FS** deploy — **not** this staging (current
  deploy is a same-host jar-swap with HOME passthrough → `~/.claude/projects` survives). **Mitigation for
  that future:** mount `~/.claude`/`CLAUDE_CONFIG_DIR` on the **same durable volume** as worktrees/`.cyppie`.
- **(b) Hard-kill-mid-turn corruption** — an **unverified hypothesis** (NOT observed): a hard-kill of an
  agent mid-turn (e.g. `bootout` during an active turn) *might* leave the JSONL transcript with a torn last
  line → non-resumable → stale-resume → fresh fallback. **On the evidence this did not happen.** The
  **[SPIKE]** decides whether it is even a real risk — confirm whether a mid-turn hard-kill leaves the
  transcript `--resume`-able (Context7 does not cover torn-transcript tolerance). **If** the spike shows a
  risk, the mitigation is cheap and sensible anyway: **graceful quiesce/drain** before `bootout` (reuse the
  CYP-247 S3 `cancelAndJoin` drain-before-teardown pattern).

**Design stance:** continuity is worth designing for, but as **forward-looking hygiene, not an urgent
bug-fix** — there is no observed loss to chase. (a) durable-mount is a cloud-future item; (b) is
spike-gated (establish the risk before building the drain). **Independently of both,** the boot
`spawnFresh`-rollback gap (§4.2 case 2 — a *throwing* boot-spawn → ERROR with no fresh fallback) is a real,
small robustness gap worth closing on its own, without any urgency framing.

### 4.4 Hand-off control seams (driven by the UIUX one-window mode-toggle)

The UX model is **one window per agent** with a `[Orchestration | Terminal]` toggle; **flipping the toggle
*is* the §4.1 hand-off** (there is no split-screen — which is exactly right, since the two modes are
mutually exclusive per session, §3). The backend must expose five seams so the UI can drive that flip
safely:

1. **Hand-off endpoint with explicit backend confirmation** — `POST /api/agents/{id}/mode`
   `{ target: ORCHESTRATION | TERMINAL }` → the backend performs the sequential hand-off (graceful stop of
   the current process → `--resume <sid>` spawn of the target mode → **confirm only after the target is
   live/bound**), and the UI flips **non-optimistically** (state changes only on the backend's confirmation,
   the CompactVM pattern — never an optimistic UI flip that could desync from a session that failed to
   resume). Fail path returns an error and the UI stays in the prior mode.
2. **IDLE-gate before takeover (reuse CYP-324 busy)** — the mode flip is **gated on the agent being idle**
   (`AgentBusyStateTracker`): no silent turn-hijack. If a turn is in-flight, the default hand-off **defers**
   (bounded-wait, exactly the CYP-326 idle-gate discipline). A **"Seize"** — taking the session mid-turn —
   is available only as an **explicit + destructive** operator action (confirmed, flagged as
   context-risking), never the default.
3. **Hand-off-state broadcast** — a content-free WS channel (twin of `/ws/lifecycle` · `/ws/busy-state`)
   publishing `{ agentId, mode, heldBy (operatorId), since (ts) }` to **all** operators, so a second
   operator sees *who* holds a session interactively and *since when*. **State / identity / time only —
   NEVER keystrokes or terminal content** (no keystroke leak; the interactive PTY stream is single-viewer
   by construction and is not fanned out on this channel). This is the multi-operator coordination seam for
   the single-writer invariant.
4. **Render z-order** — see §5 (UIUX confirms the 04-Doc z-order constraint is back).
5. **Explicit `resumed-with-context` vs `context-free` signal (per session/resume)** — **[VALIDATED by this
   very analysis]** the UI has a first-class **`CONTEXT-LOST`** state (a resumed session that comes back
   context-free — after a hand-back *or* a restart). The UI **cannot derive it**: a near-zero `contextTokens`
   is **ambiguous** between an *intentional* compaction (CYP-326) and an *unintended* memory-loss resume —
   the **exact confusion that momentarily read po's intentional compaction as a context loss** (§4.3). If it
   can fool a careful design analysis, the UI must not be left to guess. **Only the backend knows**, and it
   knows it precisely at the **CYP-330 stale-resume seam** (`ResumingSession`):
   - `--resume <id>` **bound** (BOUND / live-resume) → **`RESUMED_WITH_CONTEXT`**.
   - `--resume <id>` **died unbound → `healToFresh`** (proactive probe *or* turn-path) → **`CONTEXT_LOST`**
     (the unintended loss — po's `CONTEXT-LOST` state).
   - **no durable id** (first start / intentionally cleared) → **`FRESH_NO_RESUME`** (not a loss — a new
     agent by design).

   The backend emits this per-agent/session (content-free: `{ agentId, resumeOutcome, sid?, ts }`) via the
   **hand-off-state broadcast (seam #3)** and/or a dedicated Event, so the UI renders `CONTEXT-LOST`
   **authoritatively** instead of a weaker "context possibly lost" guess. It is **orthogonal to CYP-326**:
   an intentional compaction is its own signal (expected shrink); `CONTEXT_LOST` is unintended
   resume-failure — the UI composes the two (compaction = expected; `CONTEXT_LOST` = memory loss).
   *Source of truth:* the resume outcome is already computed in `ResumingSession` (CYP-330) — this seam only
   **surfaces** it; no new detection logic.

These seams are the same shapes the platform already ships (confirm-then-commit like CompactVM; idle-gate
like CYP-326; content-free per-agent WS twin like CYP-324; and seam #5 reuses the CYP-330 resume-outcome
that already exists) — so they are **incremental, not novel**.

---

## 5. Q4 — Terminal render per target (reverses 05-D6; re-activates the 04 risk)

**CYP-331 reverses decision 05-D6.** D6 chose a `commonMain` renderer over the stream-json event stream
**specifically to eliminate** the Kotlin/Wasm-HTML-interop risk (04 §7). Real interactive terminals bring
that entire 04-Doc strategy — and its risks — **back**:

| Target | Approach (04-Doc) | Maturity / risk |
|---|---|---|
| Desktop (JVM) | JediTerm in `SwingPanel` + `TtyConnector` ↔ PTY-over-WS | **Mature.** pty4j native libs; SwingPanel z-order over Compose (chrome-as-frame, 04 §5) |
| **Web (Wasm)** | **xterm.js DOM-overlay over the Compose canvas via HTML-interop** | ⚠️ **THE Angst-Stück** — 04 §7 "highest technical risk", Beta/experimental. Fallback: a **Kotlin/JS** web build |
| Android | Compose-ANSI renderer *or* WebView+xterm | secondary; keyboard-in-WebView caveat |
| iOS | stub (later `UIKitView` + SwiftTerm) | stub |

**Z-order (UIUX-confirmed).** The 04-Doc constraint is **back**: a real terminal (SwingPanel on Desktop,
DOM overlay on Web) renders **over** the Compose layer and cannot be drawn under Compose chrome. Design
rule (04 §5): window **chrome = a frame around the terminal, never an overlay on top of it** — title bar,
the `[Orchestration | Terminal]` toggle, resize handles, and the hand-off/held-by badge all live in the
**frame**, and any popup that must appear over the terminal is a Swing/DOM popup, not a Compose overlay.
This directly shapes the one-window design (§4.4) — the mode toggle sits in the frame chrome.

**Server-side:** interactive agents need a **real PTY per agent** (pty4j, 02 §10) — this reverses 05-D4
(piped-stdio default) and **brings back the PTY-manager that the MVP never built**. Non-trivial new
server surface (spawn/resize/stream/teardown over WS).

**This is the single biggest risk in a full pivot (Option A).** It is also the strongest reason for a
**per-target Option D** (mature JediTerm interactive on Desktop; keep the stream-json renderer as the Web
path until/unless the Wasm-interop spike clears).

---

## 6. Q5 — Migration

The stream-json renderer windows are the **transition + coexistence surface**; `--resume` coexistence is
the enabler (an agent is mediated *or* interactive on the same session). Phased:

1. **P0 — continuity hygiene (forward-looking; ship regardless of the option):** the boot `spawnFresh`
   rollback (Q3.2 — a real, independent robustness gap) as a small standalone fix now; graceful-drain (Q3-b,
   spike-gated) and durable-mount of `CLAUDE_CONFIG_DIR` (Q3-a, cloud-future) as forward-looking hygiene. No
   observed context-loss bug drives these; decoupled from the terminal work.
2. **P1 — hook-receiver spine:** stand up the `:server` hook endpoint feeding the existing
   CYP-316/324/326 trackers; run it **in parallel** with the pipe (dual-fed) to prove parity before cutover.
3. **P2 — interactive Desktop (JediTerm):** the mature path; PTY-manager + hand-off state machine +
   hub-MCP receive seam (Q2). Desktop-only first.
4. **P3 — Web decision gate:** Wasm-interop spike → xterm.js overlay **or** Kotlin/JS fallback **or** keep
   the stream-json renderer for Web (Option-D split).
5. **P4 — mediated-mode substrate [SPIKE-gated]:** evaluate migrating mediated mode pipe→Agent SDK.

Per feature: **busy/compact/tools** move cleanly at P1; **live-token** degrades at P1 (accept poll/boundary);
**injected-turn visibility** moves at P1; **interactive comfort** lands at P2 (Desktop) / P3 (Web).

---

## 7. Options

| | **A — Full pivot** | **B — Hybrid** | **C — Status-quo + gap-fixes** | **D — Per-target split** |
|---|---|---|---|---|
| Shape | Interactive terminals everywhere + hub-MCP + Hooks/OTEL; retire stream-json | stream-json renderer stays **default**; interactive terminal as a **2nd window type** per agent (pipe orchestration kept) | Keep today's architecture; close the concrete comfort pains individually, no terminal rebuild | **A on Desktop** (JediTerm mature) + **B/stream-json on Web** (Wasm risk deferred) |
| Observability | Full rebuild on Hooks; **live-token downgrade** everywhere | Spine unchanged for default; hooks only for the interactive 2nd window | Unchanged | Rebuild where interactive; spine kept for Web/default |
| Terminal-render risk | **Highest** (Wasm Angst-Stück on the critical path) | Low (interactive is opt-in, Desktop-first) | None | **Contained** (Wasm gated behind a spike, not blocking) |
| Hand-off complexity | High (every agent) | Moderate (only when the user opens the interactive window) | None | Moderate |
| PTY-manager | Required, all targets | Required, interactive window only | Not needed | Desktop first |
| Delivers full CC comfort | ✅ everywhere | ✅ where opened | ❌ (comfort stays partial) | ✅ Desktop now, Web later |
| Regressions | live-token; Web Wasm risk | minimal | none | live-token on Desktop only |
| Effort | **L–XL** | **M–L** | **S–M** | **M–L** |

**Coexistence obligation** (same session usable both ways) is satisfied by A, B, and D (all keep the
`--resume` seam + single-flight hand-off); **C does not** deliver interactive terminals at all, so it meets
the *comfort* ask only partially and does **not** satisfy the decided direction — it is included as the
honest low-risk floor / interim.

---

## 8. Cost / Risk / Migration summary

- **Biggest cost driver:** the PTY-manager + per-target terminal render (Q4), dominated by the **Wasm-interop
  risk** on Web. Desktop (JediTerm) is mature and cheap by comparison.
- **Biggest capability regression:** **live token/context (CYP-325)** in any interactive path → poll or
  boundary-only. Unavoidable per the feasibility finding; must be Auftraggeber-accepted.
- **Independent small win (any option):** the **boot `spawnFresh` rollback** (P0) — a real, standalone
  robustness gap — decoupled from the terminal rebuild. The broader continuity work (drain, durable-mount)
  is **forward-looking hygiene, not an observed-bug fix** (no context-loss was actually seen).
- **Two spikes gate the big commitments:** (1) **Wasm xterm.js-over-Compose interop** (gates Web in A/D);
  (2) **Agent-SDK mediated substrate** + **mid-turn-kill transcript resumability** (gates P4/P5 and confirms
  Q3-b). Neither should block P0–P2.

---

## 9. Recommendation (lean — Auftraggeber ratifies)

**Lean: Option D, delivered in the P0→P3 phasing** — i.e. **B's risk posture with A's Desktop ambition**:

1. Ship the **P0 boot-rollback robustness** independently (small, decoupled); treat the rest of continuity
   (drain, durable-mount) as **forward-looking hygiene, not an urgent fix** — there is no observed
   context-loss bug.
2. Build the **hook-receiver spine dual-fed** (P1) to prove observability parity before any cutover.
3. Land **interactive Desktop via JediTerm** (P2) — the mature, low-risk comfort win, hub-MCP receive seam.
4. **Gate Web on the Wasm spike** (P3): only pivot the Web terminal if the interop spike clears; otherwise
   keep the stream-json renderer for Web (this is exactly the D split, and why D beats a blind A).

This delivers full Claude-Code comfort where it is cheap and safe (Desktop) **now**, contains the single
biggest MVP risk (Wasm), accepts the one unavoidable regression (live-token) with eyes open, and keeps the
coexistence obligation via `--resume`. A full **Option A** is the end-state *if* the Wasm spike clears;
**C** is the fallback floor if the Auftraggeber defers interactive terminals.

---

## 10. Open decisions for Auftraggeber ratification

1. **Option A / B / C / D** (§7) — and whether the **live-token downgrade** (§2) is acceptable.
2. **Web terminal risk appetite** — fund the **Wasm-interop spike** now, or pre-commit to the Kotlin/JS
   fallback, or keep stream-json for Web indefinitely (§5).
3. **Continuity scope** — approve the **boot `spawnFresh` rollback** as a small standalone robustness fix;
   graceful-drain gated on the mid-turn-kill spike (no observed loss to chase);
   approve the **durable-mount of `CLAUDE_CONFIG_DIR`** for the cloud future (§4.3).
4. **Mediated substrate** — authorize the **Agent-SDK spike** (pipe→SDK) and the **mid-turn-kill
   resumability spike** (§4.1/§4.3), or keep pipe-parsing for the mediated default.
5. **Confirmation the direction is locked** (real interactive terminals + hub mediation) so P1/P2 can start
   against it.

> **[SPIKE] register:** (i) Wasm xterm.js-over-Compose interop; (ii) Agent-SDK mediated substrate + JVM
> integration; (iii) mid-turn hard-kill → is the JSONL transcript still `--resume`-able. None block P0.
>
> **Cross-refs:** **Companion UX doc** `CYP-331-interactive-terminal-coexistence-design.md` (UIUX,
> `f96ac9d`) — the one-window mode-toggle model + the §4.4 control seams. As-is code
> (`ClaudeCodeSession`/`RecordingSessionObserver`/`EventProjector`/`LifecycleManager`/`ResumingSession`/
> `HubMcpConfigWriter`/`SessionStore`); CYP-316/324/325/326 (feeds), CYP-330 (stale-resume heal + boot
> reconcile), CYP-146 (hub-MCP), CYP-167 (SessionStore/`--resume`), CYP-247 S3 (drain-before-teardown),
> CompactVM (non-optimistic confirm-then-commit); docs 02 §9/§10, 04 (all), 05 D4/D5/D6; Context7 Claude
> Code session-storage + Agent-SDK hosting; PO Q1 Hooks/OTEL feasibility finding.
