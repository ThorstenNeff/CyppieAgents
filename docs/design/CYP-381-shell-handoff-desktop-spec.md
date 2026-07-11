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
transcript **discontinuity line** + **dimmed scrollback as history** (a11y "nicht im Agenten-Gedächtnis"),
recovery on the next real turn. The UI **never guesses** — both the event and the state come from the backend
(seam #5); a near-zero token count is *not* used to infer loss (ambiguous vs a CYP-326 compaction).

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
| **Context-lost banner** (CYP-333 §6) | `agent.<id>.contextLostBanner` | present iff `CONTEXT_LOST` |
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

*(The CYP-333 §6 discontinuity-line / dimmed-history a11y keys apply if not yet landed; reuse if already present.)*

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
5. **ResumeOutcome 3-level** — `CONTEXT_LOST`=WARN, the other two INFO (no green); rendered as an Event-Log event; the persistent `CONTEXT_LOST` state lights marker+banner+dimmed-history; never inferred from token count.
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
