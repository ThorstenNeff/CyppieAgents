# CYP-381 — Shell-Verdrahtung: Real Hand-off (Mode-Toggle ↔ Motor + ControlState/ResumeOutcome) — Desktop UX-Spec

> Status: **IMPLEMENTATION-READY for Dev.** Story under Epic **CYP-331**. Owner: UIUX. Priority High. Dual-gate,
> merge via PO. Builds directly on **CYP-333 §§2/4/5.1/6** (the [P2] hand-off machinery — much of it already built).
> **Backend contracts:** CYP-354 (control-state feed) **✅ live**; CYP-355 (mode motor) + CYP-356 (ResumeOutcome)
> **not yet built → build the client against the stubs specified here (§3/§7), real-swap when they land.**
> **Shared-key/tag drift:** the net-new keys + testTags land **with** Dev's slice; re-sync the tester (CYP-7).

Grounded against develop `2aa5f8dd`: `core/model/TerminalControlModel.kt` (CYP-354 DTO, live),
`agentview/AgentWindow.kt` `ModeToggleRow` (the interim `[Orchestrierung|Shell]` toggle), `window/WindowManager.kt`
L724-751 (the titlebar mode-marker, my §5.1, **already rendering** `◉/→/←/∅`), `AgentShell.kt` L745-751
(`controlStateFor` host seam), `TerminalControlStateViewModel` (client mirror), `.worktrees/CYP-356-be3`
(ResumeOutcome enum + Event-Log delivery).

---

## 1. What changes — and the one honesty this raises **(read first)**

The `[Orchestrierung | Shell]` toggle (CYP-333/348) is re-pointed: today "Shell" opens an **interim bash-worktree-
shell** (CYP-348) whose own honest note reads *"bash-Worktree-Shell … git status/ls/reinschauen — **KEIN
Agenten-Terminal**"*. CYP-381 flips it to the **real hand-off**: "Shell" now attaches the terminal to the agent's
**real `--resume` claude session** (interactive Claude-Code), the mediated session stops, and — per CYP-331 §3
(mutually-exclusive I/O) — **the hub goes blind** for that stretch.

> ⚠ **Honesty flag (my lane) — the label `Shell` now means the *opposite* of what it was taught.** The interim
> UI explicitly told operators "Shell = bash, **not** the agent." CYP-381 makes it = **driving the agent's real
> session, hub blind.** The same word carried an inverted risk profile and still *read* as "a bash prompt," so an
> operator's muscle memory (type `ls`/`git status`) would misfire: input now goes to the agent's interactive
> session, not a shell.
>
> **✅ RULING (PO 2026-07-11): relabel the segment `Shell` → `Terminal`.** The toggle is **`[ Orchestrierung |
> Terminal ]`** — "Terminal" matches the `INTERACTIVE` control-state and the CYP-333 [P2] name for exactly this
> real-session hand-off, and drops the misleading bash-prompt cue. **The rename is clean, not cosmetic:** Dev
> renames the existing `Shell` strings **and** testTags, not just the display label (§8 checklist). The old
> "bash / KEIN Agenten-Terminal" note is rewritten to the real-session truth (§8). *(PO reports the naming to the
> Auftraggeber as an FYI — he had used "Shell".)*

Everything else in this spec was already label-independent.

---

## 2. Two enums — keep them straight (grounded)

| Enum | Where | Values | Role |
|---|---|---|---|
| `AgentContentMode` | client view-selection | `ORCHESTRATION` · `TERMINAL` | which content the window shows = the motor's `target` (§3) |
| `TerminalControlState` | CYP-354 feed (`:core`, live) | `MEDIATED` · `HANDING_OVER` · `INTERACTIVE` · `HANDING_BACK` · `CONTEXT_LOST` | the **backend truth** the client mirrors (marker §5) |

The toggle expresses a **`target`** (ORCHESTRATION/TERMINAL); the **control-state** is what the backend reports
back. They are **not** the same axis — the client never sets the control-state, it mirrors it (absent==MEDIATED).

---

## 3. Toggle → mode motor (CYP-355) — non-optimistic, with the handing-off transient

**Contract to build against (define fresh — CYP-355 not built; PO relays to Backend):**
```
POST /api/agents/{id}/mode      body: { "target": "ORCHESTRATION" | "TERMINAL" }
  → 200 { agentId, mode }       confirm ONLY after the target process is live/bound (graceful stop → --resume spawn)
  → 4xx/5xx { error }           reject: the session did not swap
```
**Non-optimistic flip (CompactVM pattern — the CYP-333 §2 spine, mandatory):**
1. Operator taps the `Shell`/`Terminal` segment → client POSTs `{target: TERMINAL}`. The segment does **not**
   visually select the new mode yet.
2. While in flight, the CYP-354 feed reports **`HANDING_OVER`** → the window shows the **transient**: segment
   shows a pending spinner, the live selection stays `Orchestrierung`, the titlebar marker shows `→ Übergabe…`
   (already built, §5). Copy: reuse `terminal_ctl_handing_over`.
3. **On confirm** (`INTERACTIVE`): the content rectangle swaps to the terminal (PTY, §5-Z-order), the segment now
   reads the terminal mode, banner/holder appear (§6).
4. **On reject/fail**: the window stays in the **prior** mode (`Orchestrierung`) + an inline error
   `terminal_mode_swap_failed` on the existing `agent.<id>.lifecycleError` row. **No optimistic flip** — the state
   never shows a mode the session didn't actually enter.
5. **Hand-back** = the same, `{target: ORCHESTRATION}` → `HANDING_BACK` (`← Rückgabe…`) → `MEDIATED` **or**
   `CONTEXT_LOST` (§7). Content swaps back to the transcript.

Gating unchanged from CYP-333: operator-gated (`canControl`) + IDLE-gated (§4); non-operator sees the read-only
toggle + `workspace_operator_only`. The existing `terminal_gated_pending` (backend-absent) note stays until the
motor lands.

---

## 4. IDLE-defer (reuse CYP-324) — bounded-wait, never a silent hijack

A hand-off must not seize a running turn. Toggle **while a turn is in flight** (CYP-324 busy):
- The flip **defers** (bounded-wait) — it does **not** abort the turn. The toggle enters a **pending-defer**
  visual (spinner + hint), and completes automatically when the turn ends and the motor confirms.
- Hint copy `terminal_idle_defer` = **"Wartet, bis der aktuelle Turn fertig ist"** (a11y `a11y_terminal_idle_defer`),
  on a new node `agent.<id>.modeToggle.deferHint`. Distinct from `terminal_gated_pending` (backend-absent) and from
  the operator gate.
- **Seize** (take the session mid-turn) is **not** in CYP-381's default — it is an explicit, destructive,
  confirmed operator action (arch §4.4 #2), deferred to a later slice; do **not** wire a silent seize.

---

## 5. The titlebar mode-marker (§5.1, already built) + surfacing holder + since

The CYP-354 marker `window.<id>.mode` is **live** (WindowManager L724-751): `◉ Interaktiv` (WARN-amber) ·
`→ Übergabe…` / `← Rückgabe…` (neutral) · `∅ Kontext verloren` (WARN-amber) · MEDIATED = absent. Label in
`barContent` (AA-guaranteed), tone = reinforcement only (the WCAG contract from CYP-333 §5.1, already honored).

**CYP-381 delta — surface `heldBy` + `since`.** The event carries them but the marker currently passes only
`.state` (`controlStateFor = { controlStates[id]?.state }`, AgentShell L751). CYP-381 **widens the seam** to pass
the whole `AgentTerminalControlEvent` so the frame can render **who** holds the interactive session and **since
when** — the multi-operator coordination signal (arch §4.4 #3), **state/identity/time only, never keystrokes**:
- When `INTERACTIVE`/`HANDING_*` **and** `heldBy != null`: render **"@{heldBy} · seit {HH:MM}"** next to the
  marker (frame, `labelSmall`, `onSurfaceVariant`), tag `window.<id>.mode.holder`. `heldBy == null` → omit (an
  operator's own take-over may render "Du"/"You" — Dev's call from the session identity).
- `since` formats as local `HH:MM` (reuse the `compact_status_running` "seit %1$s" precedent). Copy
  `terminal_held_by` = **"%1$s · seit %2$s"**, a11y `a11y_terminal_held_by` = "Interaktiv gehalten von %1$s seit %2$s".
- **Z-order:** holder text lives in the **frame** (titlebar cluster), never over the PTY (§ CYP-333 §3).

---

## 6. Hub-blind consequence (INTERACTIVE) — the honest "you are driving the real session"

While `INTERACTIVE`, the mediated session is stopped and **the hub does not mediate** (CYP-354 DTO doc + arch §3).
That must be stated, not implied:
- A **persistent WARN-amber frame strip** (CYP-333 §4.5) while `INTERACTIVE`: `terminal_handoff_banner` =
  **"Interaktiv übernommen — der Hub vermittelt nicht. · %1$s · seit %2$s"** (%1$s=holder, %2$s=since), tag
  `agent.<id>.handoffBanner`. WARN-amber `severityColor(WARN)`, **not** green, persistent, in the frame. a11y
  `a11y_terminal_handoff`.
- This is complementary to the §5 marker: the **marker** is the compact cross-window signal (titlebar), the
  **banner** is the in-window consequence for the operator who is (or is watching) the interactive session.
  *(If the Auftraggeber finds banner+marker redundant on the same screen, the marker+holder alone can carry it —
  flag, my honest default is to keep the explicit "Hub vermittelt nicht" once, because "someone holds it" ≠ "the
  hub is deaf".)*
- **No content leak:** other operators see marker + holder + since, **never** the interactive keystrokes/PTY
  output (single-viewer by construction, arch §4.4 #3).

---

## 7. ResumeOutcome (CYP-356) — 3-level display + the CONTEXT_LOST composition

**Delivery (decided, PO 2026-07-10): a generic Event-Log `Event`** over `/ws/events`, **not** a dedicated socket:
`{ type: "resume.outcome", agentId, sessionId, detail: { outcome }, ts }`, `outcome ∈` `RESUMED_WITH_CONTEXT` ·
`CONTEXT_LOST` · `FRESH_NO_RESUME`. Two surfaces, honestly distinct:

**(a) The discrete audit event — in the Event-Log** (the content-free timeline, reuse `EventRow`):

| `outcome` | Severity / tone | Event-Log copy (`event_resume_*`) |
|---|---|---|
| `RESUMED_WITH_CONTEXT` | **INFO** (quiet — success is neutral, no green) | "Sitzung mit Kontext fortgesetzt" |
| `CONTEXT_LOST` | **WARN** (amber `▲`) | "Kontext verloren — Sitzung ohne vorherigen Verlauf" |
| `FRESH_NO_RESUME` | **INFO** (neutral — a new agent by design, **not** a loss) | "Neue Sitzung (kein Vorlauf)" |

Reuse the CYP-326 severity discipline: WARN never green, `CONTEXT_LOST`=WARN, the other two INFO. No new EventRow
UI — a new `EventType`/`type` string `resume.outcome` + the 3 copies. It is **orthogonal to CYP-326 compaction**
(an intentional compaction is its own expected-shrink signal; `CONTEXT_LOST` is unintended resume-failure).

**(b) The persistent state — in the agent window** (carried by CYP-354 `CONTEXT_LOST`, my CYP-333 §6, some already
built): the titlebar marker `∅ Kontext verloren` (WARN-amber, live), the WARN banner
`agent.<id>.contextLostBanner` ("Agent ohne vorherigen Kontext zurück — Verlauf nicht wiederhergestellt"), the
transcript **discontinuity line** + **receded scrollback as history** (a11y "nicht im Agenten-Gedächtnis"),
recovery on the next real turn. The UI **never guesses** — both the event and the state come from the backend
(seam #5); a near-zero token count is *not* used to infer loss (ambiguous vs a CYP-326 compaction). **The exact
transcript chrome is §7.1.**

---

## 7.1 CONTEXT_LOST transcript chrome — Dev-ready (#3, grounded on `AgentWindow.kt`)

> Motor is live (`768c072d`) → this was the "deferred-with-motor" piece; now due. Grounded on `AgentWindow.kt`
> `AgentTranscript` (L536-587) — a `LazyColumn` tagged `agent.<id>.stream` (L550), `itemsIndexed(events, key=id)`
> (L554), rows via `TranscriptRow` (L622-637), **no dividers**, `Arrangement.spacedBy(6.dp)` only (L552). The
> content rectangle is `agent.<id>.content` (L191); `ORCHESTRATION` selects `AgentTranscript` (L192-200).

### 7.1.0 The split that makes this honest — **live-state chrome vs durable landmark**

`CONTEXT_LOST` is a *momentary* control-state: `CONTEXT_LOST ──next real turn──▶ MEDIATED` (`TerminalControlModel.kt`
L15). But the fact *"the agent does not remember the scrollback above the loss point"* is **permanent** for that
buffer — it never becomes false. So the chrome is **two-tier**, driven by two different sources:

| Tier | Driven by | Lifetime | Elements |
|---|---|---|---|
| **Live-state** (transient) | CYP-354 `TerminalControlState == CONTEXT_LOST` (momentary) | clears when state leaves `CONTEXT_LOST` (next real turn) | titlebar `∅` marker (**built**, WindowManager L724-751) · window WARN banner `agent.<id>.contextLostBanner` (§7b/§10) |
| **Durable landmark** (permanent) | the **`resume.outcome` Event** `outcome==CONTEXT_LOST` + its `ts` (§7a, Event-Log stream) | **persists** as long as the pre-loss rows are in the buffer — survives recovery to MEDIATED | the transcript **discontinuity row** + the **receded history** above it |

⭐ **Decision (my lane): the discontinuity line is anchored to the `resume.outcome:CONTEXT_LOST` event `ts`, NOT to
the live control-state.** Tying it to the momentary state would make the boundary *vanish the instant the agent
produces its first fresh turn* — dishonest, because the "not in memory" fact is still true. The Event-Log event is
the durable record (it already exists for §7a); reuse its `ts` as the transcript anchor. This mirrors my §7
two-surface split exactly: **event = durable record, state = momentary flag.**

### 7.1.1 The discontinuity row — new `TranscriptDiscontinuityRow` (sibling of `NoticeRow`)

**Placement.** Insert one boundary item into the `AgentTranscript` `LazyColumn`, positioned by `ts`: **before the
first event whose `ts >= lossTs`**, else at the **tail** (loss just happened, no fresh turn yet). Chronology:
`[ historic rows ] → [ ∅ discontinuity row ] → [ fresh rows ]`. Each `resume.outcome:CONTEXT_LOST` event = **one**
durable landmark; the ts-insertion generalizes to multiple losses without special-casing (rare; MVP norm = one).

**Visual — a full-width WARN *landmark band*, reusing the Event-Log `GapRow` idiom** (`eventlog/EventRowUi.kt`
L185-210) but **WARN, not error** (this is a warning, the session is fine — its memory isn't; `GapRow` is `error`
because dropped-events is data-integrity loss):
- Container: `warnContainer` background (**existing token**, `EventVisuals.kt` L104-108 — dark `0xFF4A3A10`/text
  `0xFFFFC857`, light `0xFFFFE7B0`/text `0xFF5A3D00`), full-width, `padding(horizontal=10.dp, vertical=6.dp)`.
- Leading **rail** 4.dp × height, `severityColor(Severity.WARN)` (reuse `GapRow`'s rail; `EventVisuals` `railColor`).
- Glyph **`∅`** — *deliberately the same glyph as the titlebar marker* (`terminal_ctl_context_lost`), so "context
  lost" reads as one vocabulary across title bar and transcript. **Not** `GapRow`'s generic `⚠`. Glyph is
  decorative → `clearAndSetSemantics {}` (empty), the house pattern (AgentWindow L719-724 etc.).
- Label: `transcript_context_lost` (below), `FontWeight.SemiBold` on `warnContainer`-text, `labelMedium`.

**Copy is robust to a trimmed buffer** — it does **not** say "above" (history rows may have aged out), it states
the fact: `transcript_context_lost` DE **"Kontext verloren — der Agent hat den vorherigen Verlauf nicht im
Gedächtnis."** / EN "Context lost — the agent does not remember the previous history."

**a11y.** The band is a **static landmark**, not a live announcement — the *live* announcement is the window
banner (`a11y_terminal_context_lost`, `liveRegion=Polite`, already speced §7b/§10) which fires **once** on the
state transition. The band therefore carries only a static `contentDescription` = `a11y_transcript_context_lost`
DE **"Verlaufsbruch: Kontext verloren — der obige Verlauf ist nicht im Gedächtnis des Agenten."** (merged node,
`semantics(mergeDescendants=true)`, the WindowManager marker idiom L742-743). **Do not** put a `liveRegion` on the
band — it must not re-announce every recomposition/scroll.

### 7.1.2 The receded history — role-demotion + gutter rail, **NOT text-alpha** ⚠

> **Honesty/WCAG flag (my lane) — you asked for "gedimmte Historie *(alpha)*". The codebase explicitly forbids
> alpha on text**, and documents why in two places: `NoticeRow` (AgentWindow L833-838: `onSurfaceVariant` is used
> *instead of* alpha because it "still reads quieter than `onSurface` … `outline` was never needed to sound soft")
> and `TimeCell` (L645-646: "needs no alpha — damping comes from size and role"). The one `copy(alpha=)` in the
> shared UI is a **decorative pager dot**, never text (WindowManager L481). An alpha multiplier on the historic
> rows would drag their carefully-tuned contrast (assistant `onSurface` 15.6:1; user `secondary`; tool
> `onSurfaceVariant`) **below AA** and, worse, make the human's own reference scrollback **hard to read** — but the
> human still needs to *read* it (it's their record of what happened; only the *agent* forgot it). So "dim" here
> means **recede, stay legible**, achieved the house way:

- **Demote the loudest historic rows by role, not alpha:** historic `AssistantTextRow` body `onSurface` (15.6:1)
  → **`onSurfaceVariant`** (8.69:1 / 9.80:1 — still comfortably AA, measurably quieter). Rows already at
  `onSurfaceVariant`/`secondary` (tool, system, notice, user) **stay as-is** — already quiet, and demoting further
  risks AA. This is the exact "quiet = role + type, never alpha" pattern the two comments above establish.
- **A continuous gutter rail** down the historic block: a 2.dp vertical rule in the `TimeCell` gutter lane,
  `outlineVariant` (border role, decorative — `clearAndSetSemantics {}`), signalling "this whole run is set-apart
  history" **without touching any text contrast**. (Rail = structure; `outline`/`outlineVariant` are legitimate as
  a *rule*, only forbidden as *text*.)
- **No background wash by default.** A `surfaceVariant` fill behind the historic rows would re-seat every row's
  text on a new background and require re-verifying each role's AA on `surfaceVariant` — out of proportion for the
  signal. **If** the Auftraggeber wants a literal "alpha" look, it may only be a **non-text scrim** (a low-alpha
  `surfaceVariant` layer *behind* the block) **and Dev must re-verify each historic row's text AA on the resulting
  background at build-review** (as CYP-322/323). Flag to me if desired; my honest default is rail + role-demotion,
  which needs no re-verification.

**Recovery persistence.** When the live state leaves `CONTEXT_LOST` (next real turn → `MEDIATED`), the **live-state
chrome clears** (titlebar `∅`, window banner) but the **discontinuity row + receded history stay** — they are
anchored to the durable `resume.outcome` event, not the state. Fresh post-loss rows render **below** the band at
**full** emphasis (`onSurface`), visually confirming "from here on, this *is* the agent's memory." This contrast
(receded above / full below) is the honest payload — the human sees exactly where the agent's memory begins.

### 7.1.3 testTags (add to `AgentViewTags`; shared with QA CYP-7)

| Element | testTag | State |
|---|---|---|
| Discontinuity band (net-new) | `agent.<id>.contextLostDivider` | present iff a `resume.outcome:CONTEXT_LOST` landmark is in the buffer |
| Receded-history block (net-new, optional QA anchor) | `agent.<id>.priorHistory` | wraps the rows above the divider; absent when no landmark |

The gutter rail and glyph are **decorative** (no tag, `clearAndSetSemantics {}`). QA anchors on the divider +
(optionally) the receded block; the durable/transient split is testable — the divider **persists** after the state
flips back to `MEDIATED`, the titlebar `∅`/banner **clear**.

### 7.1.4 i18n (net-new; DE default + EN parity; land with Dev's slice)

| Key | DE | EN |
|---|---|---|
| `transcript_context_lost` | Kontext verloren — der Agent hat den vorherigen Verlauf nicht im Gedächtnis. | Context lost — the agent does not remember the previous history. |
| `a11y_transcript_context_lost` | Verlaufsbruch: Kontext verloren — der obige Verlauf ist nicht im Gedächtnis des Agenten. | History discontinuity: context lost — the agent does not remember the history above. |

*(The window-level banner keys `terminal_context_lost` / `a11y_terminal_context_lost` from §10 are unchanged — the
band's copy is transcript-scoped and distinct, so the SR hears "der obige Verlauf" in the timeline where the break
is, and the banner's live "Agent ohne vorherigen Kontext zurück" once at the top. No duplication of announcement.)*

### 7.1.5 Tone / teeth

- **WARN, never error, never green.** `warnContainer` + `severityColor(WARN)`; not `errorContainer` (that is
  `GapRow`'s data-loss red), not `tertiary` (night-green = false success, §11).
- **Durable ≠ live** — the divider persists after recovery; the marker/banner clear. This *is* the honesty test.
- **Legible recede** — history stays AA-readable (role-demotion, no text-alpha); only a non-text scrim may carry a
  literal alpha, AA-re-verified.
- **Anchored, never guessed** — the divider comes from the `resume.outcome:CONTEXT_LOST` event `ts`; a low token
  count never conjures one.

---

## 8. The `Shell → Terminal` clean rename + the honest note flip (PO ruling 2026-07-11)

The rename is **clean, not cosmetic** — Dev renames the display label, the honest note, the source comment, **and**
the `Shell`-bearing keys/testTags. Checklist (grounded on strings.xml L565-576 + `AgentViewTags`):

**Label + note copy (values):**

| Key (after rename) | Was | DE (new) | EN (new) |
|---|---|---|---|
| `terminal_mode_terminal` *(rename from `terminal_mode_shell`)* | "Shell" | **Terminal** | **Terminal** |
| `terminal_session_note` *(rename from `terminal_shell_note`)* | "bash-Worktree-Shell" | **Terminal — die echte, interaktive Agenten-Sitzung; der Hub vermittelt in diesem Modus nicht.** | Terminal — the agent's real, interactive session; the hub does not mediate in this mode. |
| `terminal_gated_pending` *(re-copy)* | "Shell verfügbar, sobald das Worktree-Shell-Backend steht" | **Terminal verfügbar, sobald der Hand-off-Motor steht** | Terminal available once the hand-off motor is up |

- **The source comment at strings.xml L565-576 must invert too** — it currently asserts "EHRLICHE Worktree-Shell …
  bash: git status/ls … **KEIN Agenten-Terminal**." After CYP-381 it is exactly the agent's session; leaving the
  old comment would be a stale false-honest note.
- The note renders in/near the terminal content (frame), on the tag renamed below.

**Keys/testTags that still say `shell` → rename (no orphan "shell" in the terminal path):**
- string key `terminal_mode_shell` → `terminal_mode_terminal`; `terminal_shell_note` → `terminal_session_note`.
- testTag `agent.<id>.modeToggle.shellNote` → **`agent.<id>.modeToggle.terminalNote`** (rename the tag constant
  `modeToggleShellNote` → `modeToggleTerminalNote`; re-sync QA CYP-7). *(The segment tag `…modeToggle.term` and
  `…modeToggle.terminalGated`/`.gateHint` already carry no "shell" — leave them.)*
- The note's job: make crystal-clear **typing goes to the agent's live session, not a bash prompt.**

---

## 9. testTags (reuse built + net-new; shared with QA CYP-7)

| Element | testTag | State |
|---|---|---|
| Mode toggle (built) | `agent.<id>.modeToggle` (+ `.orch` / `.term`) | reuse (label "Terminal") |
| Real-session note (built, **tag renamed** `.shellNote`→`.terminalNote`, §8) | `agent.<id>.modeToggle.terminalNote` | rename + re-copy |
| Operator gate hint (built) | `agent.<id>.modeToggle.gateHint` | reuse |
| Backend-absent note (built) | `agent.<id>.modeToggle.terminalGated` | reuse |
| **IDLE-defer hint** (net-new) | `agent.<id>.modeToggle.deferHint` | present iff a flip is deferred on a running turn |
| Mode-marker (built, §5.1) | `window.<id>.mode` | present iff non-MEDIATED |
| **Holder + since** (net-new) | `window.<id>.mode.holder` | present iff `heldBy != null` |
| **Hand-off banner** (CYP-333 §4.5) | `agent.<id>.handoffBanner` | present iff `INTERACTIVE` |
| **Context-lost banner** (CYP-333 §6, live-state) | `agent.<id>.contextLostBanner` | present iff `CONTEXT_LOST` (clears on recovery) |
| **Context-lost divider** (net-new §7.1, durable) | `agent.<id>.contextLostDivider` | present iff a `resume.outcome:CONTEXT_LOST` landmark is in the buffer (**persists** after recovery) |
| **Receded-history block** (net-new §7.1, optional) | `agent.<id>.priorHistory` | wraps rows above the divider |
| Mode-swap error (net-new, reuses row) | `agent.<id>.lifecycleError` | present on motor reject |

**Fail-closed anchors:** marker/holder/banner absent unless their state holds; no optimistic mode change (the
segment never shows a mode the backend didn't confirm).

---

## 10. i18n keys (reuse + net-new; DE default + EN parity; land with Dev's slice)

**Reuse (already in strings.xml, no new key):** `terminal_mode_orchestration`, `a11y_terminal_mode`,
`terminal_ctl_interactive`, `terminal_ctl_handing_over`, `terminal_ctl_handing_back`, `terminal_ctl_context_lost`,
`a11y_terminal_ctl`, `workspace_operator_only`, the `lifecycleError` row.

**Rename + re-copy (§8, PO ruling):** `terminal_mode_shell` → `terminal_mode_terminal` ("Terminal");
`terminal_shell_note` → `terminal_session_note` (real-session note); `terminal_gated_pending` re-copied to the
hand-off-motor gate.

**Net-new:**

| Key | DE | EN |
|---|---|---|
| `terminal_mode_swap_failed` | Moduswechsel fehlgeschlagen — Sitzung bleibt im vorherigen Modus | Mode switch failed — session stays in the previous mode |
| `terminal_idle_defer` | Wartet, bis der aktuelle Turn fertig ist | Waiting for the current turn to finish |
| `a11y_terminal_idle_defer` | Moduswechsel wartet, bis der aktuelle Turn fertig ist | Mode switch is waiting for the current turn to finish |
| `terminal_held_by` | %1$s · seit %2$s | %1$s · since %2$s |
| `a11y_terminal_held_by` | Interaktiv gehalten von %1$s seit %2$s | Held interactively by %1$s since %2$s |
| `terminal_handoff_banner` | Interaktiv übernommen — der Hub vermittelt nicht. · %1$s · seit %2$s | Taken over interactively — the hub is not mediating. · %1$s · since %2$s |
| `a11y_terminal_handoff` | Interaktiv übernommen von %1$s seit %2$s. Der Hub vermittelt nicht. | Taken over interactively by %1$s since %2$s. The hub is not mediating. |
| `terminal_context_lost` | Agent ohne vorherigen Kontext zurück — Verlauf nicht wiederhergestellt. | Agent back without prior context — history not restored. |
| `a11y_terminal_context_lost` | Agent ohne vorherigen Kontext zurück. Verlauf nicht wiederhergestellt. | Agent returned without prior context. History not restored. |
| `event_resume_with_context` | Sitzung mit Kontext fortgesetzt | Session resumed with context |
| `event_resume_context_lost` | Kontext verloren — Sitzung ohne vorherigen Verlauf | Context lost — session without prior history |
| `event_resume_fresh` | Neue Sitzung (kein Vorlauf) | New session (no prior context) |
| `transcript_context_lost` *(§7.1 divider)* | Kontext verloren — der Agent hat den vorherigen Verlauf nicht im Gedächtnis. | Context lost — the agent does not remember the previous history. |
| `a11y_transcript_context_lost` *(§7.1 divider)* | Verlaufsbruch: Kontext verloren — der obige Verlauf ist nicht im Gedächtnis des Agenten. | History discontinuity: context lost — the agent does not remember the history above. |

*(The §7.1 transcript-divider keys are distinct from the window-banner `terminal_context_lost` — banner = live
announcement once at the top, divider = durable landmark in the timeline. No duplicated announcement.)*

---

## 11. Colour / WCAG / tone

- WARN states (`INTERACTIVE` marker, `CONTEXT_LOST` marker+banner, `resume.outcome:CONTEXT_LOST`) = WARN-amber
  `severityColor(WARN)`, **never `tertiary`** (night-green = false success). Marker label in `barContent` (the
  §5.1 WCAG contract, already honored — tone is reinforcement, meaning is in the label).
- The terminal interior is **ANSI/JediTerm-owned** — we theme the frame, not the PTY. AA ratios re-verified on the
  rendered frame at build-review (as CYP-322/323).

---

## 12. Acceptance teeth (for §-QA after build) + backend contracts + drift

**Teeth:**
1. **Non-optimistic** — the segment/mode changes **only** on the CYP-355 confirm; reject → prior mode + `lifecycleError`. ⭐
2. **IDLE-defer** — a flip during a running turn defers (bounded-wait), never aborts; the defer hint shows; no silent seize.
3. **Holder honesty** — `heldBy`+`since` shown when present; **no keystroke/PTY content** ever on the state channel.
4. **Hub-blind stated** — `INTERACTIVE` shows the WARN "Hub vermittelt nicht" (never green); marker `◉` present.
5. **ResumeOutcome 3-level** — `CONTEXT_LOST`=WARN, the other two INFO (no green); rendered as an Event-Log event; the persistent `CONTEXT_LOST` state lights marker+banner (live) and the **durable transcript divider + receded history** (§7.1); never inferred from token count.
   - **Transcript chrome (§7.1) teeth:** the discontinuity divider is **WARN not error** (`warnContainer`, `∅`), **anchored to the `resume.outcome:CONTEXT_LOST` event ts** (durable) so it **persists after the state recovers to MEDIATED** (the marker/banner clear, the divider stays); history recedes by **role-demotion + gutter rail, never text-alpha** (AA-preserved, still human-legible); fresh post-loss rows render below at full emphasis.
6. **Clean rename `Shell→Terminal`** — label, note (`terminal_session_note` reads the real-session truth, not "bash"), keys, and the `.terminalNote` tag are renamed together; the stale source comment inverted; no orphan "shell" in the terminal path.
7. Fail-closed absence (marker/holder/banner/deferHint iff their state); DE+EN parity; tags synced with QA (CYP-7); no green SUCCESS.

**Backend contracts (PO relays):**
- **CYP-355** — `POST /api/agents/{id}/mode {target: ORCHESTRATION|TERMINAL}`, confirm-only-when-live, reject→error (§3). *Client builds against a stub until it lands.*
- **CYP-356** — `resume.outcome` Event over `/ws/events` (`detail:{outcome}`), enum RESUMED_WITH_CONTEXT/CONTEXT_LOST/FRESH_NO_RESUME (§7). *Stub until it lands.*
- **CYP-354** — ✅ live; CYP-381 **widens `controlStateFor`** to pass the full `AgentTerminalControlEvent` (holder+since), a small host-seam change (AgentShell L751 + WindowManager marker).

**Drift:** net-new keys + `deferHint`/`mode.holder` tags compile in `:app:shared`; land with Dev's slice, re-sync
the tester. **Nothing built here — spec only.**

---

## 13. Status — settled

**Label decided (PO 2026-07-11): `Shell` → `Terminal`** (§1/§8). No open UX decisions remain. Dev builds §3–§12
against the CYP-355/356 stubs now and carries the clean `Shell→Terminal` rename (labels + note + keys + testTags,
§8). Real-swap when CYP-355/356 land; §-QA on the PO's trigger after build. Dual-gate, merge via PO.

**#3 CONTEXT_LOST transcript chrome delivered (2026-07-11, motor live `768c072d`): §7.1** — Dev-ready, grounded on
`AgentWindow.kt` `AgentTranscript`. Durable `resume.outcome`-anchored discontinuity divider (WARN not error, `∅`,
persists past recovery) + role-demotion/gutter-rail recede (no text-alpha, AA-preserved). One open flag for the
Auftraggeber only: literal "alpha" is reserved for a non-text scrim, AA-re-verified — my honest default needs none.
