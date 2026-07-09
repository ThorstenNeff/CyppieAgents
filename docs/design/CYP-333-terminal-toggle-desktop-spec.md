# CYP-333 — Single-Window Mode-Toggle Desktop UX-Spec (Orchestrierung | Terminal)

> Status: **IMPLEMENTATION-READY for Dev.** Story under Epic **CYP-331** (Option D ratified: **Desktop + JediTerm
> only — no Web/Wasm**). Owner: UIUX. Builds on the ratified companion `docs/design/
> CYP-331-interactive-terminal-coexistence-design.md` (in develop). Priority High.
> **Backend-seam dependencies are marked `⟂BE-n` — the PO relays; Dev builds the client against this spec.**
> **Shared-key/tag drift:** the new `:app:shared` string keys + `AgentViewTags` entries below must land **with**
> Dev's implementation slice (and be re-synced with the tester, CYP-7) — I flag the drift, Dev times the landing.

Grounded against the real code (develop `f8063c1`): `agentview/AgentWindow.kt` (the content slot:
`AgentHeader` + `AgentTranscript` weight-1f + `MessageComposer`), `window/WindowManager.kt` (`FloatingWindow`
frame: titlebar drag, busy `*`, token, badge, ⋮), `AgentViewTags`/`WindowTestTags`, `compact/CompactViewModel`
(the non-optimistic adopt pattern), `MaritimeTheme.kt`.

---

## 1. Scope & the one rule that shapes everything

**In scope (Desktop):** one per-agent window that **toggles** between the existing structured **Orchestrierung**
renderer and a real **Terminal** (JediTerm in a `SwingPanel`); the take-over / hand-back hand-off; the WARN
"Hub blind" banner; the `CONTEXT-LOST` state; the Z-order frame layout; frame-titlebar behaviour in INTERACTIVE.

**Out of scope:** Web/Wasm terminal (Option D dropped it), the raw worktree-shell window (CYP-331 kind iii,
reserved), the backend orchestrator/session mechanics (⟂BE seams only).

**The rule (from CYP-331 §1, ratified):** a `claude` process runs in **exactly one** I/O mode — headless
stream-json (mediated) **or** interactive TUI. The toggle is therefore a **hand-off on one continuous session, not
a split-screen**. At most one mode is live; the window always shows what is actually true. The UI **never guesses**
continuity — the backend tells it (§6, ⟂BE-3).

---

## 2. Control-state model (client mirror of backend truth — §9-1 spine)

One `TerminalControlState` per agent, **mirrored from the backend, never inferred** (adopt-on-confirm exactly like
`CompactViewModel.setAllowed`: the client value moves **only** after the server event; a failed request leaves the
prior state **unchanged** + an inline error — no optimistic flip).

| State | Meaning | Live mode shown | Hub | Frame busy `*` / token |
|---|---|---|---|---|
| `MEDIATED` *(default)* | stream-json session, hub is mouth+ears | Orchestrierung | sees all | busy `*` per CYP-324; token per CYP-316 |
| `HANDING_OVER` | transient: attaching TUI to the session | Orchestrierung (spinner) | detaching | busy suppressed; token frozen (greyed) |
| `INTERACTIVE` | a human is at the JediTerm TUI | Terminal | **blind** | **busy suppressed**; token **frozen+greyed**; human-control marker |
| `HANDING_BACK` | transient: re-attaching mediated reader | Terminal (spinner) | reattaching | as INTERACTIVE until confirmed |
| `CONTEXT_LOST` | resume returned **without prior memory** | Orchestrierung (marked) | sees, agent has no history | busy per live turns; token accrues from ~0 |

**Transitions (all backend-confirmed):**
```
MEDIATED ──"Übernehmen"(IDLE-gated)──▶ HANDING_OVER ──BE confirm attach──▶ INTERACTIVE
   ▲                                        │ BE reject / attach fail
   └────────────────────────────────────────┘  (stay MEDIATED + error)
INTERACTIVE ──"Zurückgeben"──▶ HANDING_BACK ──BE resume-result──▶ MEDIATED | CONTEXT_LOST
CONTEXT_LOST ──agent's next real turn (fresh context accrues)──▶ MEDIATED
(any lifecycle restart while MEDIATED, ⟂BE-3) ──resume-result──▶ MEDIATED | CONTEXT_LOST
```
**⟂BE-1** — the backend must expose the current `TerminalControlState` per agent (a `/ws/*` field or the busy-feed
twin) so the client mirrors, not guesses. **⟂BE-2** — `Übernehmen`/`Zurückgeben` are backend operations that
return a confirm/reject; the client flips only on confirm.

---

## 3. Window anatomy & the Z-order frame layout (Z-Order-Regel, 04 §5)

The JediTerm `SwingPanel` **renders above** the Compose layer (JetBrains Z-order limit). Therefore **all chrome is
a frame around the terminal rectangle, never drawn over it.** Verified anchor: today `AgentWindow` is a `Column`
(`AgentHeader` → `AgentTranscript` → `MessageComposer`), and the `FloatingWindow` frame titlebar sits above the
content slot — both are Compose rows **outside** the content rectangle, so they are safe frame regions.

```
┌ window.<id> (FloatingWindow frame — Compose) ──────────────────────────────┐
│  window.<id>.titlebar : drag · «Name» · [human-control marker] ·            │  ← frame (safe)
│                          token(frozen/greyed) · badge · ⋮                   │
├────────────────────────────────────────────────────────────────────────────┤
│  agent.<id>.header : status · [ Orchestrierung | Terminal ] · Übernehmen/   │  ← frame (safe)
│                       Zurückgeben (operator)                                 │
│  agent.<id>.handoffBanner  (INTERACTIVE only) ⚠ "Hub vermittelt nicht…"     │  ← frame (safe)
│  agent.<id>.contextLostBanner (CONTEXT_LOST only) ⚠ "ohne vorherigen Kontext"│ ← frame (safe)
├────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│   CONTENT RECTANGLE  (window.<id>.content)                                 │
│   • MEDIATED / CONTEXT_LOST → AgentTranscript (Compose, today's renderer)  │
│   • INTERACTIVE            → TerminalView (SwingPanel + JediTerm)  ← OVER   │
│                                                                            │
├────────────────────────────────────────────────────────────────────────────┤
│  agent.<id>.input  composer  — SUPPRESSED in INTERACTIVE (see §4.4)         │  ← frame (safe)
└────────────────────────────────────────────────────────────────────────────┘
```

**Rules for Dev:**
1. The terminal occupies **only** the content rectangle; every interactive chrome element (toggle, buttons,
   banners, titlebar) lives in a Compose row **above or below** it — never in an `overlay`/`Box` z-stacked on top.
2. On drag/resize the `SwingPanel` bounds follow the content-rectangle bounds in lockstep (couple to the existing
   `FloatingWindow` offset/size; reuse the CYP-26 clamp path). No separate terminal geometry state.
3. Popups that would appear over the terminal (context menu) are **Swing popups** on the jvm side, or avoided.
4. Terminal transport uses the **`expect/actual TerminalView` seam** (doc 04 §3), matching CYP-334 as built:
   `expect fun TerminalView(session, modifier)` in **`commonMain`**. This is required, not optional — `AgentWindow`
   lives in commonMain and **chooses the content rectangle there** (transcript vs. terminal at INTERACTIVE), so the
   terminal must be **commonMain-callable**; a jvmMain-only widget would tear apart the commonMain window
   composition. **Option D constrains the *actuals*, not the seam:** the **jvm actual = JediTerm** in a `SwingPanel`
   with a `TtyConnector` bound to the terminal WS (doc 04 §4.1 `WsTtyConnector` sketch); the **`wasmJs`/`js`/
   `android`/`ios` actuals are inert Stubs that never render a real terminal** in Option D (compile-completeness
   only). This keeps doc-04's Kotlin/Wasm-HTML-interop risk **out of scope** exactly as Option D decided — the seam
   is cross-target, but only the Desktop actual is real. **No UX consequence:** the rendered result is identical;
   this is purely the code-seam mechanism. **⟂BE-4** — the terminal PTY + WS transport (`/ws/terminal?agentId=`) is
   new backend (no PTY exists today = CYP-332); the client's `TerminalSession` binds to it.

---

## 4. The mode toggle & hand-off

### 4.1 The toggle `[ Orchestrierung | Terminal ]`

A 2-segment control in `agent.<id>.header` (reuse M3 `SegmentedButton`). Tag `agent.<id>.modeToggle`; segments
`…modeToggle.orch` / `…modeToggle.term`. **The toggle expresses intent, the state machine decides:**
- **Orchestrierung → Terminal** = the take-over request (§4.2). The segment does **not** visually select "Terminal"
  until the backend confirms INTERACTIVE (non-optimistic; during `HANDING_OVER` the segment shows a pending
  spinner, the live selection stays "Orchestrierung").
- **Terminal → Orchestrierung** = the hand-back request (§4.3), same non-optimistic rule.
- **Non-operator:** the toggle is **read-only** (shows the live mode, `enabled=false`) + the reused
  `workspace_operator_only` hint — the CYP-317 "no fake switch" pattern. A non-operator can *see* which mode is
  live, never drive the hand-off.

### 4.2 Take-over ("Übernehmen")

- Button `agent.<id>.takeover`, **operator-gated** (`canControl`), **IDLE-gated** (CYP-324 busy signal): `enabled`
  only when the agent is **not** mid-turn. While a turn runs, the button is disabled with the hint
  `terminal_takeover_wait` ("Warte, bis der aktuelle Turn fertig ist"). **⟂BE-5** — optional "Seize" (interrupt the
  running turn) is a **separate, destructive** action `agent.<id>.seize` (button style = error-toned, confirm
  dialog `terminal_seize_confirm`); ship only if the backend exposes turn-interrupt, else omit (IDLE-wait only).
- On click → `HANDING_OVER`; the toggle shows pending; on backend confirm → `INTERACTIVE` (banner §4.5 appears,
  content swaps to the terminal). On reject/fail → stay `MEDIATED` + inline error `terminal_takeover_failed` on
  `agent.<id>.lifecycleError` (reuse the existing error row).

### 4.3 Hand-back ("Zurückgeben")

- Button `agent.<id>.handback` in the frame (never overlaid on the terminal), operator-gated. On click →
  `HANDING_BACK`; on backend `resume-result` → `MEDIATED` **or** `CONTEXT_LOST` (§6). Banner clears; content swaps
  back to the transcript.
- **The gap is disclosed:** a transcript notice (reuse `AgentEvent.Notice`, `outline` tone) —
  `terminal_handback_gap` "Verlauf während der interaktiven Phase liegt im Terminal, nicht im Orchestrierungs-Log."

### 4.4 Composer in INTERACTIVE

The mediated composer (`agent.<id>.input`) is **suppressed** while `INTERACTIVE` — injecting a stream-json user
turn is exactly the mediated path that is off during a human take-over; leaving it live would let two inputs race
one session. The terminal's own input **is** the input. On hand-back the composer returns.

### 4.5 The WARN hand-off banner (`agent.<id>.handoffBanner`, INTERACTIVE only)

A persistent, full-width strip in the frame (above the content rectangle):

> ⚠ **Interaktiv übernommen — der Hub vermittelt nicht.**  ·  @{name}  ·  seit {HH:MM}  ·  [ Zurückgeben ]

- Tone = **WARN-amber** (`severityColor(WARN)` — same weight CYP-326 gives aborted/timeout). **Not** green, **not**
  neutral INFO-blue: the hub being deaf is a *consequence*. Icon `⚠` is decorative; the label text carries meaning
  (WCAG 1.4.1). **Persistent** (not a toast) — the blind condition persists.
- Copy key `terminal_handoff_banner` (with `%1$s` = holder name, `%2$s` = since-time via the
  `compact_status_running`-style "seit %1$s" precedent). a11y `a11y_terminal_handoff` announces the full state.
- **Timeline gap markers:** on take-over, emit into the transcript a notice `terminal_gap_takeover`
  "— Interaktiv übernommen · Hub blind ab {HH:MM} —"; on hand-back `terminal_gap_handback` "— Zurückgegeben
  {HH:MM} —". Between them the transcript is honestly empty-with-reason.

---

## 5. What other operators & the roster see (INTERACTIVE)

The hub is blind, so cross-operator signal = **state + identity + time, never guessed status:**
- Frame titlebar: the busy `*` (`window.<id>.busy`) is **suppressed**; a distinct **human-control marker** appears
  instead — a **new `WindowBadge.Control`** sealed variant (glyph e.g. `☺`/hand, a11y by **form+label** not colour,
  CYP-55 rule), tag via `WindowBadgeTags` (`window.<id>` badge node, `.control` selector). Fail-closed: absent
  unless truly `INTERACTIVE`; no phantom.
- Token count (`window.<id>.contextTokens`): **frozen + greyed** with a11y `a11y_terminal_token_frozen` "seit
  Übernahme eingefroren" (Auftraggeber picked *grey-frozen* over *hidden*, Q-B) — showing a live number would lie
  while the hub isn't counting.
- **No content leak:** other operators see the marker + "@{name} · seit {HH:MM}", **never** the human's keystrokes/
  terminal output (Option D single-operator desktop; a read-only observer view is out of scope, ⟂BE deferred).

---

## 6. `CONTEXT-LOST` — the memory-less resume (⟂BE-3, the ratified tri-state)

**Backend delivers an explicit classification on every resume/restart — the UI does NOT guess** (this is the
ratified answer to CYP-331 ⟂ARCH-S6):

| Backend `ResumeOutcome` | Client state | Surface |
|---|---|---|
| `RESUMED_WITH_CONTEXT` | `MEDIATED` | continuity intact — no marker |
| `CONTEXT_LOST` | `CONTEXT_LOST` | banner + discontinuity line + dimmed scrollback (below) |
| `FRESH_NO_RESUME` | `MEDIATED` (fresh) | a neutral notice "neue Sitzung (kein Vorlauf)"; **not** a loss claim |

Fires after **hand-back** *and* after any **lifecycle restart** (deploy/crash — how the PO agent lost its memory).

**Surfaces when `CONTEXT_LOST`:**
1. **Banner** `agent.<id>.contextLostBanner` (WARN-amber, frame): `terminal_context_lost` "Agent ohne vorherigen
   Kontext zurück — Verlauf nicht wiederhergestellt." a11y `a11y_terminal_context_lost`.
2. **Discontinuity line** inserted into the transcript at the resume point (reuse the row mechanism; dedicated
   node `agent.<id>.event.<index>.contextBreak` or a `Notice` variant): `terminal_context_break` "— Kontext
   verloren {HH:MM} · der Agent erinnert sich ab hier nicht an das Darüberstehende —".
3. **Dimmed scrollback:** every transcript row **above** the discontinuity is visually demoted (reduce alpha /
   `onSurfaceVariant`, add an a11y prefix `a11y_terminal_history_prefix` "Historie (nicht im Agenten-Gedächtnis): ")
   so it reads as **record, not memory**. Rows below render normally.
4. **Continuity copy is withdrawn** the instant this state is entered (any "fortgesetzt"/"gleicher Kontext" text is
   replaced by the loss copy).
5. **Recovery:** the state clears to `MEDIATED` on the agent's next real turn (fresh `contextTokens` accrue from
   ~0). Until then it stays honestly marked. **⟂BE-3** — the backend must send the `ResumeOutcome`; the client
   cannot infer it (a near-zero `contextTokens` is ambiguous between an intended CYP-326 compaction and an
   unintended memory-loss restart).

---

## 7. testTags contract (new — `AgentViewTags` + `WindowBadgeTags`, shared with QA CYP-7)

Prefixless `agent.<agentId>.<element>` (segment values `[A-Za-z0-9-]+`, no dots), consistent with the existing
object. **New entries** (Dev adds to `AgentViewTags`; frame marker to `WindowBadgeTags`):

| Element | testTag | Presence contract |
|---|---|---|
| Mode toggle | `agent.<id>.modeToggle` (+ `.orch` / `.term`) | always on an agent window; `enabled` only for operator |
| Take-over | `agent.<id>.takeover` | present; `enabled` = operator ∧ IDLE ∧ `MEDIATED` |
| Seize (opt, ⟂BE-5) | `agent.<id>.seize` | present only if backend turn-interrupt exists |
| Hand-back | `agent.<id>.handback` | present only in `INTERACTIVE`/`HANDING_BACK` |
| Hand-off banner | `agent.<id>.handoffBanner` | **present iff `INTERACTIVE`** (fail-closed absence otherwise) |
| Context-lost banner | `agent.<id>.contextLostBanner` | **present iff `CONTEXT_LOST`** |
| Discontinuity line | `agent.<id>.event.<index>.contextBreak` | present iff a context break exists in the stream |
| Operator gate hint | `agent.<id>.modeToggle.gateHint` | non-operator only (reused copy) |
| Human-control marker | `window.<id>` badge, `WindowBadge.Control` | **present iff `INTERACTIVE`**; busy `*` absent then |

QA anchors (fail-closed absence): no `handoffBanner` unless `INTERACTIVE`; no `contextLostBanner` unless
`CONTEXT_LOST`; busy `*` and the composer **absent** in `INTERACTIVE`; token node present-but-frozen (not removed).

---

## 8. i18n keys (all NEW; DE default + EN parity mandatory; land with Dev's slice)

| Key | DE | EN |
|---|---|---|
| `terminal_mode_orchestration` | Orchestrierung | Orchestration |
| `terminal_mode_terminal` | Terminal | Terminal |
| `terminal_takeover` | Übernehmen | Take over |
| `terminal_handback` | Zurückgeben | Hand back |
| `terminal_seize` *(opt)* | Turn unterbrechen & übernehmen | Interrupt turn & take over |
| `terminal_seize_confirm` *(opt)* | Der laufende Turn wird unterbrochen. Fortfahren? | The running turn will be interrupted. Continue? |
| `terminal_takeover_wait` | Warte, bis der aktuelle Turn fertig ist | Wait for the current turn to finish |
| `terminal_takeover_failed` | Übernahme fehlgeschlagen — Sitzung bleibt vermittelt | Take-over failed — session stays mediated |
| `terminal_handoff_banner` | Interaktiv übernommen — der Hub vermittelt nicht. · %1$s · seit %2$s | Taken over interactively — the hub is not mediating. · %1$s · since %2$s |
| `terminal_gap_takeover` | — Interaktiv übernommen · Hub blind ab %1$s — | — Taken over interactively · hub blind from %1$s — |
| `terminal_gap_handback` | — Zurückgegeben %1$s — | — Handed back %1$s — |
| `terminal_handback_gap` | Verlauf während der interaktiven Phase liegt im Terminal, nicht im Orchestrierungs-Log. | The interactive-phase history lives in the terminal, not in the orchestration log. |
| `terminal_context_lost` | Agent ohne vorherigen Kontext zurück — Verlauf nicht wiederhergestellt. | Agent back without prior context — history not restored. |
| `terminal_context_break` | — Kontext verloren %1$s · der Agent erinnert sich ab hier nicht an das Darüberstehende — | — Context lost %1$s · the agent does not remember anything above this point — |
| `terminal_fresh_session` | Neue Sitzung (kein Vorlauf). | New session (no prior context). |
| `terminal_token_frozen` | eingefroren | frozen |
| a11y `a11y_terminal_handoff` | Interaktiv übernommen von %1$s seit %2$s. Der Hub vermittelt nicht. | Taken over interactively by %1$s since %2$s. The hub is not mediating. |
| a11y `a11y_terminal_context_lost` | Agent ohne vorherigen Kontext zurück. Verlauf nicht wiederhergestellt. | Agent returned without prior context. History not restored. |
| a11y `a11y_terminal_history_prefix` | Historie, nicht im Agenten-Gedächtnis: %1$s | History, not in the agent's memory: %1$s |
| a11y `a11y_terminal_token_frozen` | Kontext-Tokens seit Übernahme eingefroren: %1$s | Context tokens frozen since take-over: %1$s |
| a11y `a11y_terminal_mode` | Ansicht: %1$s | View: %1$s |

**Reused (no new key):** `workspace_operator_only` (mode-toggle gate hint), the error row (`lifecycleError`), the
`AgentEvent.Notice` row for gap/discontinuity notices.

---

## 9. Colour & WCAG (Desktop, maritime M3 — the frame; the terminal interior is ANSI-owned)

- **Frame accent for INTERACTIVE / CONTEXT_LOST = WARN-amber**, reusing `severityColor(WARN)`. **Never `tertiary`**
  — `#40D6A0` night is annotated in-code as "signal-green, brand accent ONLY, never status"; green would falsely
  read as success (the CYP-322/326 trap).
- **Terminal interior is not restyled** — JediTerm paints its own ANSI palette; we theme only the frame. Do not
  imply maritime governs inside the terminal.
- Every new marker/label must clear **WCAG-AA ≥ 4.5:1** on its surface. I will pin exact ratios at build-review
  time as I did for CYP-322 (15:1) / CYP-323 (7.3–10.9:1); the roles chosen (WARN-amber container text,
  `onSurfaceVariant` for dimmed history) are AA-safe both schemes per the CYP-268/304 audit, to be re-verified on
  the rendered frame.

---

## 10. Backend seams (marked — PO relays; client builds against these)

| Seam | What the client needs |
|---|---|
| **⟂BE-1** | Per-agent `TerminalControlState` (MEDIATED/HANDING_OVER/INTERACTIVE/HANDING_BACK/CONTEXT_LOST) pushed like the CYP-324 busy feed, so the client **mirrors** it (never infers). |
| **⟂BE-2** | `Übernehmen`/`Zurückgeben` operations returning **confirm/reject** (non-optimistic flip); include the holder identity + since-time for the banner. |
| **⟂BE-3** | `ResumeOutcome` = `RESUMED_WITH_CONTEXT` / `CONTEXT_LOST` / `FRESH_NO_RESUME` on every resume/restart (§6). The client cannot infer it. |
| **⟂BE-4** | Terminal PTY + WS transport `/ws/terminal?agentId=` (JediTerm `TtyConnector` binds to it). New backend — no PTY exists today (D4 piped-stdio). |
| **⟂BE-5** | *(optional)* turn-interrupt for the "Seize" path; without it, take-over is IDLE-wait only. |

---

## 11. Acceptance teeth (for §-QA after build)

1. Toggle/flip is **non-optimistic** — mode changes only after backend confirm; reject leaves the prior state +
   error (adopt-on-confirm, `CompactViewModel` twin). ⭐
2. Take-over is **operator-gated ∧ IDLE-gated**; non-operator sees a read-only toggle + `workspace_operator_only`,
   never a fake switch.
3. `INTERACTIVE` shows **no fabricated status** — busy `*` suppressed, composer suppressed, token frozen+greyed,
   human-control marker present with identity+since.
4. WARN banner is **WARN-amber, persistent, in the frame** (never green, never overlaid on the terminal).
5. Z-order: the terminal occupies only the content rectangle; **no** Compose chrome is z-stacked over it.
6. `CONTEXT_LOST` fires **only** on the backend `CONTEXT_LOST` outcome; scrollback above the break is **dimmed to
   history** (a11y prefix), the break line is present, continuity copy withdrawn; recovers on the next real turn.
7. Timeline **gap markers** on take-over/hand-back; hand-back emits the honest gap notice.
8. DE+EN parity for all new keys; new tags synced with QA (CYP-7); no green SUCCESS anywhere.
9. Fail-closed absence: `handoffBanner`/`contextLostBanner`/human-control marker exist **iff** their state holds.

---

## 12. Reuse & drift

Reuses: `AgentWindow`/`AgentHeader` frame, `FloatingWindow` geometry + CYP-26 clamp, `AgentViewTags` schema,
`WindowBadge`/`WindowBadgeTags` (+`Control` variant), CYP-324 busy/IDLE, CYP-316 token, CYP-317 no-fake-switch,
CYP-323/326 transcript-row + Notice patterns, `severityColor(WARN)`, `compact_status_running` "seit" precedent,
doc 04 §4.1 `WsTtyConnector` sketch (Desktop-only). **Drift:** the new string keys + `AgentViewTags`/`WindowBadge`
entries compile in `:app:shared`; land them **with** Dev's slice and re-sync the tester. **Nothing built here — spec
only.**
