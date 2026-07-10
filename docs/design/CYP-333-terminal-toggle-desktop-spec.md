# CYP-333 — Single-Window Mode-Toggle Desktop UX-Spec (Orchestrierung ⇄ Shell → Terminal)

> Status: **IMPLEMENTATION-READY for Dev.** Story under Epic **CYP-331** (Option D ratified: **Desktop + JediTerm
> only — no Web/Wasm**). Owner: UIUX. Builds on the ratified companion `docs/design/
> CYP-331-interactive-terminal-coexistence-design.md` (in develop). Priority High.
> **Backend-seam dependencies are marked `⟂BE-n` — the PO relays; Dev builds the client against this spec.**
> **Shared-key/tag drift:** the new `:app:shared` string keys + `AgentViewTags` entries below must land **with**
> Dev's implementation slice (and be re-synced with the tester, CYP-7) — I flag the drift, Dev times the landing.

Grounded against the real code (develop `8abc2ed`): `agentview/AgentWindow.kt` (the content slot:
`AgentHeader` + `AgentTranscript` weight-1f + `MessageComposer`), `window/WindowManager.kt` (`FloatingWindow`
frame: titlebar drag, busy `*`, token, badge, ⋮), `AgentViewTags`/`WindowTestTags`, `compact/CompactViewModel`
(the non-optimistic adopt pattern), `MaritimeTheme.kt`.

---

## 1. Scope & the one rule that shapes everything

**In scope (Desktop):** one per-agent window that **toggles** its content between the existing structured
**Orchestrierung** renderer and a **second view** — which comes in **two honestly-distinct phases** (§1.1): a
**bash worktree-Shell** now, and the true hub-mediated **Terminal hand-off** later. The Z-order frame layout; and —
for the Terminal phase — the take-over / hand-back, the WARN "Hub blind" banner, the `CONTEXT-LOST` state, and the
INTERACTIVE frame-titlebar behaviour.

**Out of scope:** Web/Wasm terminal (Option D dropped it); the backend orchestrator/session mechanics (⟂BE seams
only); a **second interactive `claude`** in the worktree — the Auftraggeber **rejected** it (two auto-approving
agents in one worktree = worst-case risk), which is exactly why the interim view is a plain **Shell** (§1.1).

### 1.1 Two honest phases (Auftraggeber ruling 2026-07-10) — the honesty spine

The window's second view means **two different things at two times**, and the UI must never blur them:

| | **Phase 1 — Interim `Shell`** *(ships now, CYP-334 scaffold)* | **Phase 2 — `Terminal` hand-off** *(later, needs ⟂BE-1..3)* |
|---|---|---|
| What it is | a **bash worktree-shell** — a read/work *Einblick* into the agent's git worktree | the **same `claude` session**, re-attached interactively, hub-mediated hand-off |
| Process | a **separate `bash` PTY** in the worktree cwd — **not** the agent's session | the agent's own long-lived session (CYP-331 §1) |
| Is it a hand-off? | **No.** The mediated `claude` session **keeps running**; the hub is **not** blind | **Yes.** The human takes the wheel; the hub is blind for the stretch |
| Hand-off chrome | **MUST be absent** — no take-over/hand-back, no "Hub blind" banner, no frozen token, no `CONTEXT_LOST`, no human-control marker. Showing any of it would be a **fabricated statement** (the hub is *not* blind) | present — that is the whole of §2, §4.2–4.5, §5, §6 |
| Toggle label | `[ Orchestrierung \| Shell ]` (truthful; Dev already renamed "Terminal"→"Shell") | the **Terminal** meaning returns with the hand-off (BE-2) |

> **Load-bearing honesty:** the interim `Shell` is a **different process next to** the agent, not the agent's
> conversation. During Phase 1 the frame's busy `*` / token keep reflecting the **still-live, still-observed**
> mediated session — truthful. Every hand-off surface below is tagged **[P2]**; nothing tagged **[P2]** may render
> in Phase 1. What ships and gets §-QA'd first is the **[P1]** set (§11).

**The rule for Phase 2 (from CYP-331 §1, ratified):** a `claude` process runs in **exactly one** I/O mode — headless
stream-json (mediated) **or** interactive TUI. The Terminal toggle is therefore a **hand-off on one continuous
session, not a split-screen**. The UI **never guesses** continuity — the backend tells it (§6, ⟂BE-3). *(Phase 1 is
unaffected by this rule: the Shell is a separate `bash` process, so it genuinely coexists with the live session.)*

---

## 2. Control-state model (client mirror of backend truth — §9-1 spine) · **[P2]**

> **Phase 1 has no control-state to speak of:** the agent is always effectively `MEDIATED` while the Shell view is
> open — switching to the Shell does **not** change the agent's state (it's a separate `bash` process). The state
> machine below is **Phase 2 only** and requires ⟂BE-1..3.

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

The JediTerm `SwingPanel` (**both** the Phase-1 Shell **and** the Phase-2 Terminal are PTY widgets) **renders above**
the Compose layer (JetBrains Z-order limit). Therefore **all chrome is a frame around the content rectangle, never
drawn over it** — this rule holds in **both phases**. Verified anchor: today `AgentWindow` is a `Column`
(`AgentHeader` → `AgentTranscript` → `MessageComposer`), and the `FloatingWindow` frame titlebar sits above the
content slot — both are Compose rows **outside** the content rectangle, so they are safe frame regions.

```
┌ window.<id> (FloatingWindow frame — Compose) ──────────────────────────────┐
│  window.<id>.titlebar : drag · «Name» · [P2 mode-marker §5.1] · busy * ·    │  ← frame (safe)
│                          token(P1 live · P2 frozen/greyed) · badge · ⋮      │
├────────────────────────────────────────────────────────────────────────────┤
│  agent.<id>.header : status · [ Orchestrierung | Shell ]  (P1)              │  ← frame (safe)
│                      · [P2] Übernehmen / Zurückgeben (operator)             │
│  [P2] agent.<id>.handoffBanner  (INTERACTIVE only) ⚠ "Hub vermittelt nicht…"│  ← frame (safe)
│  [P2] agent.<id>.contextLostBanner (CONTEXT_LOST only) ⚠ "ohne …Kontext"    │  ← frame (safe)
├────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│   CONTENT RECTANGLE  (window.<id>.content)                                 │
│   • Orchestrierung        → AgentTranscript (Compose, today's renderer)    │
│   • P1 Shell              → TerminalView(bash-worktree session)   ← OVER   │
│   • P2 Terminal/INTERACTIVE → TerminalView(claude session)       ← OVER   │
│                                                                            │
├────────────────────────────────────────────────────────────────────────────┤
│  composer : Orchestrierung → claude input · Shell → the shell's own input  │  ← frame (safe)
└────────────────────────────────────────────────────────────────────────────┘
```

**Rules for Dev (both phases):**
1. The PTY view occupies **only** the content rectangle; every interactive chrome element (toggle, buttons,
   banners, titlebar) lives in a Compose row **above or below** it — never in an `overlay`/`Box` z-stacked on top.
2. On drag/resize the `SwingPanel` bounds follow the content-rectangle bounds in lockstep (couple to the existing
   `FloatingWindow` offset/size; reuse the CYP-26 clamp path). No separate terminal geometry state.
3. Popups that would appear over the PTY (context menu) are **Swing popups** on the jvm side, or avoided.
4. The PTY view uses the **`expect/actual TerminalView` seam** (doc 04 §3), matching CYP-334 as built:
   `expect fun TerminalView(session, modifier)` in **`commonMain`**. This is required, not optional — `AgentWindow`
   lives in commonMain and **chooses the content rectangle there** (transcript vs. PTY), so the PTY must be
   **commonMain-callable**; a jvmMain-only widget would tear apart the commonMain window composition. **Option D
   constrains the *actuals*, not the seam:** the **jvm actual = JediTerm** in a `SwingPanel` with a `TtyConnector`
   bound to the terminal WS (doc 04 §4.1 `WsTtyConnector` sketch); the **`wasmJs`/`js`/`android`/`ios` actuals are
   inert Stubs that never render** in Option D (compile-completeness only). Keeps doc-04's Kotlin/Wasm-HTML-interop
   risk **out of scope** — cross-target seam, Desktop-only real actual. **The same `TerminalView` seam serves both
   the Phase-1 bash session and the Phase-2 claude session** — only the bound `TerminalSession` differs. **⟂BE-4** —
   the PTY + WS transport `/ws/terminal?agentId=` is CYP-332 (no PTY existed under D4 piped-stdio); the Phase-1 Shell
   binds a **bash-worktree** session, Phase-2 the **claude** session.

---

## 4. The mode toggle, the Shell view, & the hand-off

### 4.1 The toggle — Phase 1 `[ Orchestrierung | Shell ]` **[P1]**

A 2-segment control in `agent.<id>.header` (reuse M3 `SegmentedButton`). Tag `agent.<id>.modeToggle`; segments
`…modeToggle.orch` / `…modeToggle.shell`. In Phase 1 the toggle is a **pure view switch**, **not** a hand-off:
- **Orchestrierung → Shell** just changes which content the window shows (transcript ↔ bash PTY). It does **not**
  change the agent's control-state, does **not** take the hub blind, does **not** touch the `claude` session (which
  keeps running, still observed). No non-optimistic gating is needed — there is no backend state to confirm; the
  view flips immediately.
- **a11y:** the toggle announces `a11y_terminal_mode` "Ansicht: %1$s" (View: %1$s) with the current segment label.
- **Operator-gating of the Shell (confirmed, PO 2026-07-10):** a bash worktree-shell is **fully mutating**
  (`git commit`/`rm`/file-edit/`push` into the repo worktree) — there is **no "read-only shell" without a sandbox we
  don't have. So **opening the Shell is operator-gated** (`canControl`); a non-operator with a full worktree shell
  would be a **privilege escalation** (sharper with CYP-321 skip-permissions on). Non-operator → **read-only** toggle
  (`enabled=false`) + the reused `workspace_operator_only` hint (CYP-317 "no fake switch"), **fail-closed**. This
  matches Dev's impl (the VM refuses the Shell/Terminal without `canControl`). Loosening this later (Shell for
  non-operators) is the **risk-increasing** direction → an explicit Auftraggeber decision; the default stays tight.

### 4.1b The Shell view (`agent.<id>.shell`) **[P1]**

The content rectangle hosts a **bash worktree-shell** — a `TerminalView` bound to a bash-`TerminalSession` in the
agent's worktree cwd (CYP-332 PTY/WS). **Honesty:** it is labelled and understood as a **shell into the worktree**,
**not** the agent's conversation — so **none** of the Phase-2 hand-off chrome appears while it is open (see §1.1;
the fail-closed absence is a §11 [P1] tooth). The shell has **its own input** (bash); the mediated `claude` composer
belongs to the Orchestrierung view — no separate claude composer is drawn under the shell. The frame busy `*` /
token continue to reflect the **still-live** mediated session (truthful, not frozen).

### 4.1c The toggle — Phase 2 adds `Terminal` (hand-off) **[P2]**

When ⟂BE-1..3 land, the hub-mediated **Terminal** (the same `claude` session, taken over interactively) returns as
the second meaning. Whether it is a third segment `[ Orchestrierung | Shell | Terminal ]` or is entered via the
take-over affordance is a Phase-2 decision to settle when BE-1/2 are specced; **the take-over/hand-back model,
non-optimistic flip, and all INTERACTIVE chrome (§2, §4.2–4.5, §5, §6) are Phase 2.** In Phase 2 the toggle
**expresses intent, the state machine decides** (the segment does not select "Terminal" until the backend confirms
`INTERACTIVE`).

### 4.2 Take-over ("Übernehmen") **[P2]**

- Button `agent.<id>.takeover`, **operator-gated** (`canControl`), **IDLE-gated** (CYP-324 busy signal): `enabled`
  only when the agent is **not** mid-turn. While a turn runs, the button is disabled with the hint
  `terminal_takeover_wait` ("Warte, bis der aktuelle Turn fertig ist"). **⟂BE-5** — optional "Seize" (interrupt the
  running turn) is a **separate, destructive** action `agent.<id>.seize` (button style = error-toned, confirm
  dialog `terminal_seize_confirm`); ship only if the backend exposes turn-interrupt, else omit (IDLE-wait only).
- On click → `HANDING_OVER`; the toggle shows pending; on backend confirm → `INTERACTIVE` (banner §4.5 appears,
  content swaps to the terminal). On reject/fail → stay `MEDIATED` + inline error `terminal_takeover_failed` on
  `agent.<id>.lifecycleError` (reuse the existing error row).

### 4.3 Hand-back ("Zurückgeben") **[P2]**

- Button `agent.<id>.handback` in the frame (never overlaid on the terminal), operator-gated. On click →
  `HANDING_BACK`; on backend `resume-result` → `MEDIATED` **or** `CONTEXT_LOST` (§6). Banner clears; content swaps
  back to the transcript.
- **The gap is disclosed:** a transcript notice (reuse `AgentEvent.Notice`, `outline` tone) —
  `terminal_handback_gap` "Verlauf während der interaktiven Phase liegt im Terminal, nicht im Orchestrierungs-Log."

### 4.4 Composer in INTERACTIVE **[P2]**

The mediated composer (`agent.<id>.input`) is **suppressed** while `INTERACTIVE` — injecting a stream-json user
turn is exactly the mediated path that is off during a human take-over; leaving it live would let two inputs race
one session. The terminal's own input **is** the input. On hand-back the composer returns. *(Distinct from Phase 1:
there the composer is not "suppressed" — it simply belongs to the Orchestrierung view, and the still-live session
is untouched.)*

### 4.5 The WARN hand-off banner (`agent.<id>.handoffBanner`, INTERACTIVE only) **[P2]**

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

## 5. What other operators & the roster see (INTERACTIVE) **[P2]**

The hub is blind, so cross-operator signal = **state + identity + time, never guessed status:**
- Frame titlebar: the busy `*` (`window.<id>.busy`) is **suppressed** and the **mode-marker** (`window.<id>.mode`,
  fully specified in **§5.1**) shows **INTERACTIVE** instead. *(Supersedes the earlier `WindowBadge.Control` idea:
  the mode-marker is a **titlebar element twin to busy-`*`/token**, not a badge — badges are the single-slot count/
  severity/attention axis, not shaped for a 4-state control axis. §5.1 is the single source.)*
- Token count (`window.<id>.contextTokens`): **frozen + greyed** with a11y `a11y_terminal_token_frozen` "seit
  Übernahme eingefroren" (Auftraggeber picked *grey-frozen* over *hidden*, Q-B) — showing a live number would lie
  while the hub isn't counting.
- **No content leak:** other operators see the marker + "@{name} · seit {HH:MM}", **never** the human's keystrokes/
  terminal output (Option D single-operator desktop; a read-only observer view is out of scope, ⟂BE deferred).

### 5.1 Titlebar mode-marker `window.<id>.mode` — twin to busy-`*`/token **[P2]** (per PO 2026-07-10)

The per-window marker for `TerminalControlState` — a **host-injected titlebar element**, the **twin of** busy-`*`
(CYP-324) and the token count (CYP-316): same `FloatingWindow` titlebar seam, fed by a `controlStateFor(id)` host
map (twin of `busyFor`/`contextTokensFor` in `AgentShell`). **Not** a `WindowBadge`.

**Principles (align with Dev's):** **absent == MEDIATED** — no marker in the default, exactly like `busy == idle`
(fail-closed; its absence is the test contract for MEDIATED). **Form + label, never colour alone** (WCAG 1.4.1 —
the *label text* carries the meaning; glyph and tone only reinforce). **Content-free** — never keystrokes/terminal
output, only the state.

**Rendering / WCAG (confirmed with Dev 2026-07-10):** the **label** renders in **`barContent`** — the AA-guaranteed
content colour on the agent-toned titlebar BG, exactly like busy-`*`/token — so the meaning is legible on **any**
bar. The **glyph carries the WARN-amber/neutral tone as reinforcement only.** Because the meaning lives in the
label **and** in the glyph's distinct *form* (`◉`/`→`/`←`/`∅`), the amber **never needs to meet contrast** on an
exotic bar BG — a washed-out amber loses no information (the WARN vs neutral distinction is already in the labels
"Interaktiv"/"Kontext verloren" vs "Übergabe…"/"Rückgabe…"). **So: no amber-on-barBg token, and the label is not
tinted amber** (that would fight the agent identity BG and can't be AA-guaranteed across arbitrary bar colours).
**QA criterion:** the marker's meaning is AA-legible via the `barContent` label regardless of bar colour; the tone
is decorative reinforcement, not a contrast-bearing element.

| State | Glyph | Label (DE / EN) | Tone (reinforcement only) |
|---|---|---|---|
| `MEDIATED` | — (absent) | — | — (no node) |
| `INTERACTIVE` | `◉` | **Interaktiv** / Interactive | WARN-amber (`severityColor(WARN)`) — hub blind = consequence |
| `HANDING_OVER` | `→` | **Übergabe…** / Handing over… | neutral `onSurfaceVariant` (a0: in-progress, never `tertiary`/green) |
| `HANDING_BACK` | `←` | **Rückgabe…** / Handing back… | neutral `onSurfaceVariant` |
| `CONTEXT_LOST` | `∅` | **Kontext verloren** / Context lost | WARN-amber — consequence (twin of §6) |

> Glyphs are distinct **forms** (and distinct from the titlebar's only other glyphs: busy `*`, token digits); Dev
> may swap to a house glyph **iff** the four forms stay mutually distinct and the label still carries the meaning.
> The transient `…` on HANDING_OVER/_BACK reads as in-progress (like "Startet…"/"Neustart…", CYP-262/330).

**Placement (order/spacing):** the mode-marker **leads the status cluster**, immediately after the title and
**before** busy-`*`/token — because the control-state governs how the rest reads (in `INTERACTIVE` busy is
suppressed; in `CONTEXT_LOST` the token accrues from ~0). Titlebar order:

```
[avatar] · «title» · [MODE-MARKER] · busy * · token · badge · ⋮
```

Same spacing as the existing markers (the titlebar's `spacedBy(8.dp)` cluster); monospace `labelSmall` glyph +
`labelMedium` label, matching busy-`*`/token typography. **testTag `window.<id>.mode`** (twin of `window.<id>.busy`
/ `.contextTokens`) — present only in a visible (non-MEDIATED) state; **absent == MEDIATED** (fail-closed, no
phantom). a11y: the node carries `a11y_terminal_mode_marker` "Sitzungszustand: %1$s" (Session state: %1$s) so a
screen reader announces the state even where the glyph is decorative.

**Phase:** all of this is **[P2]** — it is fed by ⟂BE-1 (`TerminalControlState`). In Phase 1 the agent is always
MEDIATED → the marker is **absent** (nothing to build for the CYP-334 scaffold; the [P1] fail-closed-absence tooth
already asserts no `window.<id>.mode` node exists).

---

## 6. `CONTEXT-LOST` — the memory-less resume (⟂BE-3, the ratified tri-state) **[P2]**

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

| Phase | Element | testTag | Presence contract |
|---|---|---|---|
| P1 | Mode toggle | `agent.<id>.modeToggle` (+ `.orch` / `.shell`) | always on an agent window; `enabled` only for operator |
| P1 | Shell view | `agent.<id>.shell` | present iff the Shell segment is selected (content rectangle) |
| P1 | Operator gate hint | `agent.<id>.modeToggle.gateHint` | non-operator only (reused copy) |
| P2 | Terminal segment | `agent.<id>.modeToggle.term` | present only once Phase 2 lands (⟂BE-1..3) |
| P2 | Take-over | `agent.<id>.takeover` | present; `enabled` = operator ∧ IDLE ∧ `MEDIATED` |
| P2 | Seize (opt, ⟂BE-5) | `agent.<id>.seize` | present only if backend turn-interrupt exists |
| P2 | Hand-back | `agent.<id>.handback` | present only in `INTERACTIVE`/`HANDING_BACK` |
| P2 | Hand-off banner | `agent.<id>.handoffBanner` | **present iff `INTERACTIVE`** (fail-closed absence otherwise) |
| P2 | Context-lost banner | `agent.<id>.contextLostBanner` | **present iff `CONTEXT_LOST`** |
| P2 | Discontinuity line | `agent.<id>.event.<index>.contextBreak` | present iff a context break exists in the stream |
| P2 | Mode-marker (§5.1) | `window.<id>.mode` | present iff a **visible** state (INTERACTIVE/HANDING_OVER/HANDING_BACK/CONTEXT_LOST); **absent == MEDIATED**; busy `*` absent in INTERACTIVE |

**QA anchors — [P1] fail-closed absence (what CYP-334 §-QA checks):** while the Shell view is open, **none** of the
[P2] nodes exist — no `takeover`/`handback`/`seize`, no `handoffBanner`, no `contextLostBanner`, no
`WindowBadge.Control`; busy `*` and token **remain live** (the session is not blind). **[P2] fail-closed absence:**
no `handoffBanner` unless `INTERACTIVE`; no `contextLostBanner` unless `CONTEXT_LOST`; busy `*` and composer
**absent** in `INTERACTIVE`; token node present-but-frozen (not removed).

---

## 8. i18n keys (all NEW; DE default + EN parity mandatory; land with Dev's slice)

| P | Key | DE | EN |
|---|---|---|---|
| P1 | `terminal_mode_orchestration` | Orchestrierung | Orchestration |
| P1 | `terminal_mode_shell` | Shell | Shell |
| P1 | a11y `a11y_terminal_mode` | Ansicht: %1$s | View: %1$s |
| P1 | a11y `a11y_terminal_shell` | Worktree-Shell (nicht die Agenten-Sitzung): %1$s | Worktree shell (not the agent session): %1$s |
| P2 | `terminal_mode_terminal` | Terminal | Terminal |
| P2 | `terminal_marker_interactive` (§5.1) | Interaktiv | Interactive |
| P2 | `terminal_marker_handing_over` (§5.1) | Übergabe… | Handing over… |
| P2 | `terminal_marker_handing_back` (§5.1) | Rückgabe… | Handing back… |
| P2 | `terminal_marker_context_lost` (§5.1) | Kontext verloren | Context lost |
| P2 | a11y `a11y_terminal_mode_marker` (§5.1) | Sitzungszustand: %1$s | Session state: %1$s |
| P2 | `terminal_takeover` | Übernehmen | Take over |
| P2 | `terminal_handback` | Zurückgeben | Hand back |
| P2 | `terminal_seize` *(opt)* | Turn unterbrechen & übernehmen | Interrupt turn & take over |
| P2 | `terminal_seize_confirm` *(opt)* | Der laufende Turn wird unterbrochen. Fortfahren? | The running turn will be interrupted. Continue? |
| P2 | `terminal_takeover_wait` | Warte, bis der aktuelle Turn fertig ist | Wait for the current turn to finish |
| P2 | `terminal_takeover_failed` | Übernahme fehlgeschlagen — Sitzung bleibt vermittelt | Take-over failed — session stays mediated |
| P2 | `terminal_handoff_banner` | Interaktiv übernommen — der Hub vermittelt nicht. · %1$s · seit %2$s | Taken over interactively — the hub is not mediating. · %1$s · since %2$s |
| P2 | `terminal_gap_takeover` | — Interaktiv übernommen · Hub blind ab %1$s — | — Taken over interactively · hub blind from %1$s — |
| P2 | `terminal_gap_handback` | — Zurückgegeben %1$s — | — Handed back %1$s — |
| P2 | `terminal_handback_gap` | Verlauf während der interaktiven Phase liegt im Terminal, nicht im Orchestrierungs-Log. | The interactive-phase history lives in the terminal, not in the orchestration log. |
| P2 | `terminal_context_lost` | Agent ohne vorherigen Kontext zurück — Verlauf nicht wiederhergestellt. | Agent back without prior context — history not restored. |
| P2 | `terminal_context_break` | — Kontext verloren %1$s · der Agent erinnert sich ab hier nicht an das Darüberstehende — | — Context lost %1$s · the agent does not remember anything above this point — |
| P2 | `terminal_fresh_session` | Neue Sitzung (kein Vorlauf). | New session (no prior context). |
| P2 | `terminal_token_frozen` | eingefroren | frozen |
| P2 | a11y `a11y_terminal_handoff` | Interaktiv übernommen von %1$s seit %2$s. Der Hub vermittelt nicht. | Taken over interactively by %1$s since %2$s. The hub is not mediating. |
| P2 | a11y `a11y_terminal_context_lost` | Agent ohne vorherigen Kontext zurück. Verlauf nicht wiederhergestellt. | Agent returned without prior context. History not restored. |
| P2 | a11y `a11y_terminal_history_prefix` | Historie, nicht im Agenten-Gedächtnis: %1$s | History, not in the agent's memory: %1$s |
| P2 | a11y `a11y_terminal_token_frozen` | Kontext-Tokens seit Übernahme eingefroren: %1$s | Context tokens frozen since take-over: %1$s |

**Reused (no new key):** `workspace_operator_only` (mode-toggle gate hint, both phases), the error row
(`lifecycleError`), the `AgentEvent.Notice` row for gap/discontinuity notices. **Only the [P1] keys are needed for
the CYP-334 scaffold**; the [P2] keys land with the Phase-2 hand-off slice.

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

| Seam | Phase | What the client needs |
|---|---|---|
| **⟂BE-4** | **P1** | PTY + WS transport `/ws/terminal?agentId=` (JediTerm `TtyConnector` binds to it) = **CYP-332** (running). In Phase 1 it serves a **bash-worktree** session. |
| **⟂BE-1** | P2 | Per-agent `TerminalControlState` (MEDIATED/HANDING_OVER/INTERACTIVE/HANDING_BACK/CONTEXT_LOST) pushed like the CYP-324 busy feed, so the client **mirrors** it (never infers). |
| **⟂BE-2** | P2 | `Übernehmen`/`Zurückgeben` operations returning **confirm/reject** (non-optimistic flip); include the holder identity + since-time for the banner. |
| **⟂BE-3** | P2 | `ResumeOutcome` = `RESUMED_WITH_CONTEXT` / `CONTEXT_LOST` / `FRESH_NO_RESUME` on every resume/restart (§6). The client cannot infer it. |
| **⟂BE-5** | P2 | *(optional)* turn-interrupt for the "Seize" path; without it, take-over is IDLE-wait only. |

---

## 11. Acceptance teeth (for §-QA after build)

**[P1] — the CYP-334 scaffold §-QA (what ships now; the PO triggers this pass first):**
1. **Z-order:** the Shell PTY occupies only the content rectangle; **no** Compose chrome (toggle, titlebar) is
   z-stacked over it — chrome is a frame above/below. ⭐
2. **No fabricated hand-off statement:** while the Shell view is open, **none** of the [P2] chrome renders — no
   take-over/hand-back, no "Hub blind" banner, no frozen token, no `CONTEXT_LOST`, no human-control marker. Busy `*`
   and token stay **live** (the mediated session is not blind). ⭐ *(the core honesty tooth of the ruling)*
3. **Honest label:** the second segment reads **"Shell"** (not "Terminal"); the view is a bash **worktree**-shell,
   not the agent's session (a11y `a11y_terminal_shell`).
4. **Operator-gating:** opening the Shell is operator-gated; non-operator sees a read-only toggle +
   `workspace_operator_only`, never a fake switch.
5. **a11y:** the toggle announces `a11y_terminal_mode` "Ansicht: %1$s"; DE+EN parity for the [P1] keys; tags
   (`agent.<id>.modeToggle`/`.orch`/`.shell`, `agent.<id>.shell`) synced with QA (CYP-7).

**[P2] — the hand-off §-QA (after ⟂BE-1..3 + the Phase-2 slice):**
6. Toggle/flip is **non-optimistic** — the Terminal selects only after backend confirm; reject leaves the prior
   state + error (adopt-on-confirm, `CompactViewModel` twin). ⭐
7. Take-over is **operator-gated ∧ IDLE-gated**; `INTERACTIVE` shows **no fabricated status** — busy `*` suppressed,
   composer suppressed, token frozen+greyed, human-control marker with identity+since; WARN banner **WARN-amber,
   persistent, in the frame** (never green/overlaid).
8. `CONTEXT_LOST` fires **only** on the backend `CONTEXT_LOST` outcome; scrollback above the break is **dimmed to
   history** (a11y prefix), the break line present, continuity copy withdrawn; recovers on the next real turn.
   Timeline **gap markers** on take-over/hand-back.
9. DE+EN parity for [P2] keys; no green SUCCESS anywhere; fail-closed absence of `handoffBanner`/
   `contextLostBanner`/human-control marker unless their state holds.

---

## 12. Reuse & drift

Reuses: `AgentWindow`/`AgentHeader` frame, `FloatingWindow` geometry + CYP-26 clamp, `AgentViewTags` schema,
`WindowBadge`/`WindowBadgeTags` (+`Control` variant), CYP-324 busy/IDLE, CYP-316 token, CYP-317 no-fake-switch,
CYP-323/326 transcript-row + Notice patterns, `severityColor(WARN)`, `compact_status_running` "seit" precedent,
doc 04 §4.1 `WsTtyConnector` sketch (Desktop-only). **Drift:** the new string keys + `AgentViewTags`/`WindowBadge`
entries compile in `:app:shared`; land them **with** Dev's slice and re-sync the tester. **Nothing built here — spec
only.**
