# CYP-326 — Compact Orchestration (Design, ratified 2026-07-09)

> Status: **RATIFIED** by the Auftraggeber 2026-07-09 (via PO). Design-pass-first, risk-first, spike-gated.
> Companion: the empirical spike + reconciliation record (below §2). Build order in §7.

## 1. Goal

When the **PO agent's** context exceeds a configurable threshold (**default 500K tokens**) **and** a global
**"compact allowed"** checkbox is on, the platform orchestrates a **team-wide compaction**:

1. A **"prepare for compact"** message to the PO, then to each **worker 1 minute apart** (staggered).
2. **+10 minutes**, a **compact** instruction to the PO, then to each worker 1 minute apart.
3. A **completion report** to the PO once **all** agents have compacted — or an **honest X/N partial** at the
   per-round 10-minute timeout.

## 2. Spike + reconciliation (why this is buildable)

**Empirically verified (real `claude` 2.1.205, the exact platform spawn).** A first, **isolated** spike was
**wrong** (it used a *one-shot* `claude -p` session — single stdin line + EOF — so the harness treated
`/compact` as literal text). The **real platform path is a LONG-LIVED multi-turn stream-json session**, and
there the harness **intercepts `/compact` as a slash command**:

- Flags: `-p --input-format stream-json --output-format stream-json --verbose --dangerously-skip-permissions`
  (= `ConnectorDefaults.BASE_STREAM_JSON_FLAGS` + the CYP-321 bypass), byte-identical `UserTurn.toNdjsonLine`
  framing, injected programmatically on stdin (= the mediator `sendMessage` seam).
- Result: `{"type":"system","subtype":"status","status":"compacting"}` → `{"…","compact_result":"success"}` in
  the **output stream**; **PreCompact + PostCompact hooks both fired**; **contextTokens dropped**.

**Decision (1A):** compact = injecting the literal **`/compact`** command over the mediator into the agent's
long-lived session. The agent does not "self-compact from prose" — that path is confirmed negative; the
harness slash-command path is what works.

**Completion signal (2B) — RATIFIED primary = the `compact_result:"success"` stream event.** It is immediate,
unambiguous, and **self-attributed** (it comes from the very session we sent `/compact` to → no
`correlationId`-window heuristic needed). The CYP-325 **contextTokens-drop is corroboration only**: it is
*delayed* because the `/compact` turn's own result carries degenerate usage, so the CYP-325 defect-1 fix
(`null`/`≤0` → keep last) holds the pre-compact value until the agent's **next** real turn (and stalls if the
agent goes idle). The **PostCompact hook** is a documented alternative but needs per-agent hook config; the
stream event needs only a small parse-add to the reader we already run.

## 3. Locked decisions

| # | Decision |
|---|---|
| 1A | Compact = inject literal `/compact` over the mediator `sendMessage` seam into the long-lived session. |
| 2B | Completion = `compact_result:"success"` system/status stream event (token-drop = corroboration only). |
| (b) | **No prepare-ack** — fixed **+10-min** timing between the prepare round and the compact round. |
| (c) | **PO compacts FIRST**, then workers 1 minute apart (both rounds). |
| — | Threshold **500K, configurable** (one named constant/config knob), **re-arm** like `ContextUsageBander`. |
| — | **Per-round 10-min timeout** → honest **X/N** partial (pending agents = WARN, **never faked**). |
| — | **Platform-side Orchestrator** (NOT the PO agent — survives the PO's own compact; Warden/Scanner pattern). |
| — | Global **"compact allowed"** checkbox, **persisted**; deploy pre-authorized. |

## 4. Architecture

**Platform-side `CompactOrchestrator`** (pattern of the CYP-58 Warden/Scanner — runs on the boot scope, not
inside any agent):

- **Threshold-watch:** subscribes to the PO's `contextTokens` (the CYP-325 feed). On an **up-crossing** of the
  configured threshold **and** "compact allowed" on → **arm** and start a run. **Re-arm** only after the value
  falls back below the threshold (fire-once-per-crossing, like `ContextUsageBander`).
- **Sequence engine (staggered):** Round 1 (prepare) → PO immediately, then each worker at +1 min. Round 2
  (compact) at **+10 min** from Round 1 → PO first, then each worker at +1 min. **PO is always position 0.**
- **Idle-gate (CYP-324):** before **every** `/compact` send, gate on the target agent being **IDLE** (the CYP-324
  busy signal). Never inject `/compact` into a running turn; bounded wait, else that agent → timeout-WARN.
- **Completion detection (per agent):** the run records, per agent, the **send time** of its `/compact`; a
  `compact_result:"success"` for that agent **within the 10-min window** marks it **completed**. No signal in the
  window → **pending/WARN** (X/N honesty — never faked, same line as CYP-325 `null≠0`).
- **Reporting:** when all agents completed → `compact.orchestration.done` with N/N. At the round timeout →
  `compact.orchestration.done` with X/N and the pending set.

**Config/Status endpoint (operator-gated):** the "compact allowed" checkbox (global, persisted via the store
seam), the configurable threshold, and a **status** read (armed?, running?, last-run X/N + timestamp).

## 5. Seams (build)

1. **The compact-completion seam (naht-first, empirically proven):**
   - `:core` `SystemEvent` += `status: String?`, `compactResult: String?` (`@SerialName("compact_result")`) +
     `val compactCompleted: Boolean get() = compactResult == "success"`. Additive, tolerant, cross-compiles.
   - `EventProjector.onCompactCompleted: ((agentId) -> Unit)?` — fired in the `SystemEvent` branch when
     `compactCompleted`. Boot-baked + active-routed (twin of CYP-324 `onBusy` / CYP-316 `onContextTokens` on the
     same reader). Ungated by capabilities (compaction is process-level).
2. **`:core` event types:** `compact.prepare.sent`, `compact.request.sent`, `compact.orchestration.done` (new
   `EventType`s); `compact.completed` already exists / add if absent. Content-free metadata (agentId, round,
   X/N) — no bodies.
3. **`CompactOrchestrator`** (`:server`) — threshold-watch + sequence engine + idle-gate + completion tracking +
   event emission. Injected the PO's token feed, the busy feed, the compact-completed signal, the mediator send
   seam, and a clock (injectable for deterministic tests).
4. **Config/Status store + endpoint** — "compact allowed" + threshold persisted (mirror `AgentOverrideStore` /
   the CYP-96 config store); operator-gated REST + a status DTO.

## 6. Events (content-free)

`compact.prepare.sent` (agentId, round) · `compact.request.sent` (agentId) · `compact.completed` (agentId) ·
`compact.orchestration.done` (completed X, total N, pendingAgentIds). Emitted via the CYP-34 `EventSink`.

## 7. Build order (risk-first, PO-ratified)

1. **This design doc.**
2. **Naht-first:** the `compact_result` parse + `onCompactCompleted` seam (§5.1) — the one empirically-proven
   piece, lowest risk.
3. **Contract-first:** the `:core` event types (§5.2) + config/status DTO — **pushed early**, SHA relayed to Dev.
4. **Orchestrator** (§5.3) + config/status endpoint (§5.4).

**Teeth (throughout):** PO-first + 1-min stagger · +10-min round gap · idle-gate before `/compact` · completion
via `compact_result` · timeout → honest X/N (never faked) · threshold re-arm. Time-driven logic uses an
injectable clock so the staggered/timeout behaviour is deterministic in tests.

## 8. Reuse

CYP-325 (threshold token feed) · CYP-324 (idle-gate busy signal) · CYP-58 (platform-side scanner pattern) ·
CYP-34 (event emission) · CYP-96 / AgentOverrideStore (config persistence seam) · CYP-321 (the bypass flag the
real spawn already carries).
