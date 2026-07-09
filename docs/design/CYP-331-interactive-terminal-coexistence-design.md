# CYP-331 — Interactive Terminal ⇄ Mediated Orchestration Coexistence (Design, **first pass** 2026-07-09)

> Status: **ERSTER AUFSCHLAG** (design-pass only, **no build**) — for Auftraggeber ratification. Owner: UIUX.
> Companion basis: `04-Frontend-Compose-Multiplatform.md` (Terminal-Strategie/Fenster-Manager),
> `05-MVP-Scope-Entscheidungen.md` (D4/D6 + §4 "optional raw-shell window"), maritime+M3 design language,
> `WINDOW-RESPONSIVE.md` (CYP-26), `window-badges-tags.md` (CYP-55), `CYP-326-compact-orchestration-design.md`
> (the mediation / "mouth-and-ears" vocabulary this spec extends).
> **Architecture dependencies are marked `⟂ARCH-Sn` — the PO relays these to Backend.** Nothing here is settled
> against backend feasibility yet; where my UX rests on a technical answer, it says so.

---

## 1. The direction & the one honesty that governs the whole design

**Direction (PO, decided):** agents should be usable in **real interactive terminal windows** (full Claude-Code
comfort — the adoption gate for a Claude-Code-native audience), with the **hub mediating in the background**.
**Coexistence requirement:** the *same* agent session must be usable **both** in today's structured stream-json
renderer **and** in a real interactive terminal — same agent, same context.

**The load-bearing constraint (and my central disclosure concern):** one `claude` process is launched in
**exactly one** I/O mode — *either* headless `--output-format stream-json` (the mediated path today, D4/§2 of
`05`) *or* an **interactive TUI** (raw PTY, ANSI, keystrokes — doc `04`'s JediTerm/xterm path that D6 dropped).
These are **mutually exclusive launch modes of the same CLI**. So "same agent, same context, both views" **cannot
honestly mean two live render-surfaces onto one running process at the same instant.** It must mean:

> **One continuous session context, presented through one of two modes at a time; moving between them is a
> HAND-OFF, not a split screen.** ⟂ARCH-S1 (below) is the backend confirmation this whole model rests on.

This matters because the moment a human is at the interactive TUI, **the hub is blind** for that stretch (the
PO said so explicitly). If our UI kept showing a live "mediated" surface next to it, we'd be **implying the hub
still sees the agent when it doesn't** — the exact overstatement my lane exists to prevent (lineage:
never-optimistic CYP-312, failed≠remote CYP-315, null≠0 CYP-316, filtering≠revoking CYP-319). So the design's
spine is a **single, always-truthful control-state** per agent (§3), and the mode surfaces follow from it.

---

## 2. Window model (UX-Frage 1) — recommendation

Three window *kinds*, but only **two of them are the same agent**:

| Kind | What it is | Process | Hub relationship |
|---|---|---|---|
| **(i) Orchestration view** (today) | Structured stream-json renderer — assistant text streams, tool-call rows, result markers, event log | the agent's long-lived stream-json session | **mediated** — hub is mouth+ears |
| **(ii) Interactive Terminal** | Full Claude-Code TUI in a real PTY (doc `04`: JediTerm desktop / xterm.js web) | **the same agent session**, re-attached in TUI mode | **blind** — human has the wheel |
| **(iii) Worktree shell** (optional) | A plain shell in the agent's worktree, *no agent* | a separate `bash`/PTY process | n/a — not an agent surface |

**Recommendation — (i) and (ii) are ONE window that switches modes; (iii) is a separate window.**

- **(i) ⇄ (ii): one per-agent window with a mode toggle.** They are two guises of **one** session — modelling them
  as two competing windows would visually imply two live sessions on one context (the fiction §1 forbids). The
  window is **per-agent**; a **mode segment control** in its titlebar switches *Orchestrierung* ⇄ *Terminal*. The
  switch **is** the hand-off act (§4). At most one mode is live at a time → the window can only ever show what is
  actually true. ⟂ARCH-S1/S2.
  - **This is the existing agent window**, not a new system-window id. Verified: the window manager dispatches
    content by `window.id` in the `windowContent` `when` (`AgentShell.kt`), and the **`else` branch already renders
    `AgentWindow`** for any id in `agentVms`. CYP-331's *Terminal* mode is a **new content branch inside that agent
    window** (an interactive-terminal composable when the mode is INTERACTIVE), *not* a new `COMPACT_WINDOW_ID`-style
    system window. **This resolves the fork the code exposes** (a terminal window could either be a plain
    system-window *or* inherit agent chrome via `agentById[id]`): CYP-331 takes the **agent-id path** so the
    terminal window keeps its identity avatar, badges, and — mode-permitting — the busy/token markers (§5).
- **(iii) is its own window.** A raw worktree shell is a *different process with no agent context* — it is exactly
  `05 §4`'s "optional later window type." Making it a mode of the agent window would blur "this is the agent" vs
  "this is just a shell next to the agent." Keep it a distinct, clearly-labelled window kind (own titlebar,
  no agent badge, no token count — there's no agent turn to count). Out of scope to *build* here; reserved so the
  model stays coherent.

> **Why not "always two side-by-side windows, one per mode"?** Because during interactive take-over the
> orchestration window would have **nothing honest to show** (hub blind) — it would sit there looking live while
> being deaf. A single mode-switching window makes the blind stretch *visible as a state*, not hidden behind a
> stale-looking twin. (If the Auftraggeber prefers two windows for screen-real-estate reasons, the honesty rule
> still binds: the inactive-mode window must render the §3 blind/gap state, never a frozen-live look.)

---

## 3. The spine — per-agent **control-state** (the honest single source)

Every agent is in exactly **one** control-state at a time. This is the truth the titlebar, badges, timeline, and
both modes all read from — never guessed, always the real backend fact.

| State | Meaning | Who acts | Hub sees | Signal (see §5) |
|---|---|---|---|---|
| **MEDIATED** (default) | stream-json session, hub is mouth+ears | platform + operators via composer | **yes** (full) | busy `*` when a turn runs (CYP-324) |
| **INTERACTIVE** | a human is at the TUI on this session | one named human | **no — blind** | distinct **human-control** marker + owner identity + since-time |
| **HANDING-OVER / -BACK** | transient: attaching/detaching the session between modes | platform | partial | GATED "wird übergeben…" spinner, no fake completion |
| **CONTEXT-LOST** *(new — PO 2026-07-09)* | session resumed but came back **without prior memory** (after hand-back **or** any lifecycle restart) | platform | yes, but agent has **no history** | WARN-toned **"ohne vorherigen Kontext zurück"** marker + discontinuity line in the timeline |
| *(SHELL — window kind iii)* | raw shell, no agent | any operator | n/a | plain shell window; no agent state at all |

**Rules (my lane):**
1. **INTERACTIVE never shows a fabricated agent status.** No busy `*`, no "IDLE", no guessed progress — the hub
   *cannot* know. The only honest thing to display is **"unter menschlicher Kontrolle seit HH:MM — Hub vermittelt
   nicht"** plus **who** holds it. (Twin of CYP-326 §7's "System message is clearly not agent output.")
2. **The transition is never optimistic.** The window flips to INTERACTIVE only **after** the backend confirms the
   session is attached in TUI mode (adopt-server-state, exactly like CompactViewModel `setAllowed`/`setThreshold`).
   A failed attach leaves the window **MEDIATED, unchanged** + an inline ERROR — no half-flip.
3. **The blind stretch is marked in the orchestration timeline, not silently dropped.** When a take-over starts,
   the mediated event log for that agent gets a **gap marker** event *"Interaktiv übernommen — Hub blind ab HH:MM"*;
   hand-back writes *"Zurückgegeben HH:MM"*. Between them the timeline is **honestly empty with a reason**, not a
   suspiciously quiet live feed. (Same principle as filtering≠revoking, null≠0.)
4. **A resume that lost its memory says so — never faked continuity (§4.5).** When the backend signals the resumed
   session came back context-free, the agent enters **CONTEXT-LOST**: an honest *"Agent ohne vorherigen Kontext
   zurück — Verlauf nicht wiederhergestellt"* — because the visible scrollback is **client history, not the agent's
   memory**, and letting it *imply* the agent still remembers would be the exact continuity-overstatement §4.1
   guards against. This holds identically after a **hand-back** and after a **deploy/crash restart** (the way the
   PO agent lost its memory).

---

## 4. Hand-off UX (UX-Frage 2)

### 4.1 Take-over ("Übernehmen")

- **Affordance:** an **"Übernehmen"** action in the agent window's titlebar (operator-gated —
  `isOperatorAccess`; non-operators see a read-only chip, the CYP-317 "no fake switch" pattern).
- **Idle-gate (reuse CYP-324):** the platform should **gate take-over on the agent being IDLE** — never seize a
  running mediated turn silently. If a turn is in flight, offer an explicit choice: **"Warten bis Turn fertig"**
  (default) or **"Turn unterbrechen & übernehmen"** (destructive-styled, honest about interrupting). ⟂ARCH-S5 —
  backend must expose "interrupt current turn" for the seize path.
- **Transition:** window enters **HANDING-OVER** (GATED spinner, "Sitzung wird an Terminal übergeben…"). On backend
  confirm, it flips to **INTERACTIVE**, the mode segment snaps to *Terminal*, and the **persistent hand-off
  banner** appears (see §4.4). On failure → stays MEDIATED + ERROR hint.
- **Continuity promise (honesty):** the banner says **"gleicher Agent, gleicher Kontext"** *only if* ⟂ARCH-S1
  confirms the interactive attach truly resumes the same session/context. If backend can only give a *fresh*
  interactive session, the copy must **not** claim continuity — it becomes "neues interaktives Terminal (frischer
  Kontext)". **I will not ship the continuity claim until backend confirms it.** And even when S1 says *yes* at
  design time, a resume can still come back **empty at runtime** — that flips the agent into CONTEXT-LOST (§4.5),
  which withdraws the continuity copy on the spot.

### 4.2 Hand-back ("Zurückgeben")

- **Affordance:** **"Zurückgeben an Hub"** in the interactive window's frame (see §5 Z-order — it lives in the
  **frame**, not overlaid on the terminal).
- **Transition:** HANDING-BACK (GATED spinner) → on confirm, mediated reader re-attaches to the same session, mode
  snaps back to *Orchestrierung*, banner clears, timeline writes *"Zurückgegeben HH:MM"*.
- **The gap is disclosed, not smoothed over:** an INFO note in the orchestration view — *"Verlauf während der
  interaktiven Phase liegt im Terminal, nicht im Orchestrierungs-Log"* — because the hub genuinely didn't record
  those turns. The interactive transcript is the record for that stretch; the mediated log honestly shows a gap.

### 4.3 What *other* operators see while a human is interactive

The hub can't report the agent's work content (it's blind), so the honest cross-operator signal is **state +
identity + time**, never guessed status:
- On the window: the §5 **human-control marker** replacing the busy `*`, + **"@name hat übernommen · seit HH:MM"**.
- In any roster/overview: the agent row shows **INTERACTIVE (menschlich)**, not "läuft"/"idle".
- **No content leak:** other operators do **not** see the interactive keystrokes/output (that surface belongs to
  the human who took over) unless ⟂ARCH-S3 says the PTY is shareable *and* the Auftraggeber wants a read-only
  observer view — flagged, not assumed.

### 4.4 The persistent hand-off banner (the anti-overstatement device)

A **GATED/WARN-toned, always-visible** strip pinned in the window **frame** while INTERACTIVE:

> ⚠ **Interaktiv übernommen — der Hub vermittelt nicht.** @name · seit 14:03 · [Zurückgeben an Hub]

Tone: **not** green, **not** the neutral INFO blue used for benign facts — this is a *consequence* state (the hub
is deaf), so it reads as WARN-amber (reuse `severityColor(WARN)`, the same weight CYP-326 gives aborted/timeout).
It is **persistent** (not a toast) because the blind condition persists. It is **in the frame** (§5) so the
real terminal never paints over it.

### 4.5 Context-lost after hand-back or restart (PO 2026-07-09 — the continuity-honesty state)

**The finding (from Backend's continuity result, PO-relayed):** a resumed session can come back **context-free** —
the same agent id is live again, but its *memory of the prior conversation is gone*. This is exactly how the PO
agent lost its memory on a deploy-restart. It can happen after a **hand-back** (the mediated re-attach resumed an
emptied session) **or** after any **lifecycle restart** (deploy/crash/manual). It is the **runtime** counterpart
of ⟂ARCH-S1's "no-continuity" branch — not a static design fork but a state the running system can enter at any
time, so the UX must carry it as a first-class state, not an edge note.

**The subtle honesty trap this defuses:** the client scrollback is **our** history — it survives even when the
agent's context doesn't. If we just let the old transcript sit there looking live, the UI **implies the agent
still remembers** everything above. It doesn't. So:

- **Honest signal (WARN-toned, spirit of §4.4):** a marker/strip **"Agent ohne vorherigen Kontext zurück — Verlauf
  nicht wiederhergestellt."** Not green, not neutral INFO — a *consequence* state, reuse `severityColor(WARN)`.
- **A discontinuity line in the timeline** at the resume point: *"— Kontext verloren HH:MM · der Agent erinnert
  sich ab hier nicht an das Darüberstehende —"*. The scrollback above it stays **visible as history** but is
  **visually demoted** (e.g. dimmed / "Verlauf"-labelled) so it reads as *record*, not *agent memory*.
- **No faked continuity anywhere:** the "gleicher Kontext" promise from §4.1 is **withdrawn the instant** this
  state is entered; any "resumed"/"fortgesetzt" copy is replaced by the context-lost copy.
- **Recovery is the agent's next real turn** — the state clears when the agent produces fresh context (new
  contextTokens accrue from ~0); until then it stays honestly marked.

**Depends on ⟂ARCH-S6** (below): the backend must **tell** the UI "resumed **with** context" vs "resumed
**context-free**" — the client cannot infer it reliably (a near-zero contextTokens reading is ambiguous between an
*intended* compaction (CYP-326) and an *unintended* memory-loss restart; only the backend knows which).

---

## 5. Integration with the window manager + maritime/M3 (UX-Frage 3)

**Reuse, don't reinvent** (verified conventions): `FloatingWindow`/`WindowHost`/`WindowCanvas`
(`WindowManager.kt`), drag/resize/focus/z-index + `WindowReducer.tile()` (`WindowManagerState.kt`),
`WindowTestTags` (`window.host`/`window.<id>`/`.titlebar`/`.content`/resize-handle), per-window badges
`windowBadge.<id>` (CYP-55), the CYP-316 **context-token count** in the titlebar, CYP-26 responsive robustness
(`COMPOSER_MIN_WIDTH`, tile caps). CYP-331 adds **states and a mode toggle to the existing frame** — no new
window-manager.

**The Z-order rule is back and load-bearing (doc `04 §5`).** A real interactive terminal is a
`SwingPanel`/JediTerm (desktop) or an xterm.js **DOM overlay** (web) — both render **above** the Compose layer.
D6 had *escaped* this by dropping real terminals; CYP-331 **re-introduces it**. Therefore, verbatim from `04 §5`:
- **Window chrome is a FRAME, never an overlay.** Titlebar, mode toggle, badges, token count, and the §4.4
  hand-off banner sit **around** the terminal rectangle, not on top of it. In INTERACTIVE mode the terminal
  owns its rectangle entirely.
- **Popups over the terminal** (context menu, tooltip) are done as Swing/DOM popups on their platform, or avoided.
- **Drag/resize move the terminal bounds in lockstep** with the frame (`SwingPanel`/`HtmlView` bounds coupled to
  the window offset/size — CYP-26 clamp path).

**Titlebar composition per mode:**
- **MEDIATED:** as today — name, token count (CYP-316, `null≠0`), busy `*` (CYP-324), badges (CYP-55). **+ new:**
  mode segment `[ Orchestrierung | Terminal ]`, **+** "Übernehmen" (operator).
- **INTERACTIVE:** name, **human-control marker** (a *distinct glyph/shape* — not the busy `*`, a11y: form not
  colour, per CYP-55 rule), **"@name · seit HH:MM"**, mode segment (now on *Terminal*), "Zurückgeben".
  **Token count is suppressed or greyed** — the hub isn't counting during the blind stretch; showing a live number
  would lie. (Honest: show last-known, greyed, with "seit Übernahme eingefroren", **or** hide — Auftraggeber pick.)
- **Badges:** a **new `WindowBadge` sealed variant** `Control` (INTERACTIVE / human-controlled), tag
  `WindowBadgeTags.control(id)` alongside the existing `Count`/`SeverityLevel`/`Attention` — the honest
  fail-closed rules of CYP-55 apply (no "nothing" value; `badgeFor(id)` → `null` → no node; absent unless truly in
  that state; no phantom). Reuse the confirmed frame tags: busy `window.$id.busy` (CYP-324, "unknown≠busy") is
  **suppressed** in INTERACTIVE; token `window.$id.contextTokens` (null≠0) per Q-B.

**Maritime + M3, and the terminal's own colours (an honesty note):** the window **frame** stays maritime/M3
(the tokens I already own). But the **terminal content is ANSI-owned** — Claude-Code's TUI paints its own 16/256
colours; we do **not** restyle it, and we must **not** imply the maritime palette governs inside the terminal.
The frame is ours; the terminal interior is the CLI's. One deliberate exception worth a token: pick a **frame
accent for INTERACTIVE** (reuse the WARN/attention hue, *not* `tertiary` — which is **green at night** and would
falsely read as "success"; same trap I flagged on CYP-322/326). Night contrast for any new marker/label must
clear **WCAG-AA (≥4.5:1)** against its surface — I'll compute exact ratios at spec-closure, as I did for
CYP-322 (15:1) / CYP-323 (7.3–10.9:1).

---

## 6. Architecture seams to confirm with Backend (UX-Frage 4)

My UX above is honest *given* these; each is a place where the design bends to the technical answer. **PO relays.**

| Seam | Question to Backend | What my UX does with each answer |
|---|---|---|
| **⟂ARCH-S1** *(load-bearing)* | The **primitive already exists** — `ClaudeCodeSession` binds `--resume <session_id>` with stale-fallback (CYP-167) and `SessionStore`/`PgSessionStore` persist the ids. Open question narrows to: can an **interactive TUI** attach to the **same stored session id** the mediated process holds, and who owns stdin during that (concurrency/ownership)? | **Yes** → the whole "same agent, same context, hand-off" model + the continuity banner copy stand (a real primitive backs it, not a hope). **No** → interactive = *fresh context*; I **drop the continuity claim** and the toggle becomes "neues Terminal", not "übernehmen". |
| **⟂ARCH-S2** | Can a window **live-switch** modes, or must the mediated process **exit** to free the session for interactive attach (and vice-versa)? Latency/turn-loss cost? | Sets whether the mode toggle is instant or shows a real HANDING-OVER spinner with a cost warning; sets whether an in-flight turn must finish first (ties to S5). |
| **⟂ARCH-S3** | During INTERACTIVE, is the hub **fully blind**, or can it keep a **read-only tail** of the PTY? Is the PTY **shareable** to a read-only observer window? | Fully blind → §3/§4.4 as written. Read-only tail → I can offer a truthful **"Hub beobachtet mit (read-only)"** variant instead of "blind" — different, more permissive disclosure; only if backend confirms. |
| **⟂ARCH-S4** | Terminal transport = a **genuinely new primitive** — verified there is **no PTY/pty4j/JediTerm/TtyConnector/xterm anywhere** today; the whole stack is D4 piped-stdio headless stream-json (`ProcessBuilderSpawner`). Revive doc `04`'s `/ws/terminal` PTY (pty4j) → JediTerm (desktop) / xterm.js (web)? Likely a **new `ConnectorKind`** (today's enum = `STREAM_JSON`/`MCP`; note "Connector" here is the WS abstraction, **not** a JediTerm `TtyConnector`). | Confirms the frame/Z-order rules (§5) and **re-imports doc `04`'s biggest risk on web**: Kotlin/Wasm-HTML-interop is Beta (the very risk D6 had eliminated). I flag the **web interactive terminal** as the highest-risk target; desktop (JediTerm) is the safe first path — recommend desktop-first, exactly doc `04 §9`. |
| **⟂ARCH-S5** | Can the platform **interrupt the current mediated turn** on demand (for a "seize now" take-over), and is the **CYP-324 IDLE signal** the take-over gate? | Yes → §4.1's "Turn unterbrechen & übernehmen" destructive path is buildable. No → take-over always waits for IDLE (safer, simpler; I'll drop the seize option). |
| **⟂ARCH-S6** *(feeds §4.5)* | On a resume/restart (hand-back **or** lifecycle), can the backend emit **"resumed WITH context" vs "resumed context-free"**? The client can't infer it — a near-zero `contextTokens` reading is **ambiguous** between an *intended* compaction (CYP-326) and an *unintended* memory-loss restart. | Yes → §4.5 CONTEXT-LOST state fires only when truthfully context-free. No → I can only show a weaker, hedged "Kontext möglicherweise nicht wiederhergestellt" — I **flag that as a disclosure gap** and push for the explicit signal, because guessing here either fakes continuity or cries wolf. |

---

## 7. Disclosure-honesty summary (my lane — the acceptance teeth)

1. **No two-live-surfaces fiction** — one session shows through one mode at a time (§1/§2).
2. **INTERACTIVE never fakes agent status** — no busy `*`, no IDLE, no progress guess; only "human-controlled +
   who + since" (§3-1).
3. **Blind means blind** — the hand-off banner is a WARN-toned *consequence*, not a neutral note; never green (§4.4).
4. **Transitions are non-optimistic** — flip only on backend confirm; failure = unchanged + ERROR (§3-2).
5. **The gap is disclosed** — timeline gap markers on take-over/hand-back; the interactive stretch is honestly
   "not in the orchestration log" (§3-3/§4.2).
6. **No content leak across operators** — others see state+identity, not the human's keystrokes (§4.3), unless S3+
   Auftraggeber opt in.
7. **The terminal interior is not ours to restyle** — maritime governs the frame, ANSI governs inside; we don't
   imply otherwise (§5).
8. **Continuity is claimed only if true** — the "gleicher Kontext" promise ships only on ⟂ARCH-S1 = yes (§4.1);
   and it is **withdrawn at runtime** the instant a resume comes back context-free (§4.5).
9. **A memory-less resume says so** — CONTEXT-LOST (§4.5) is a first-class WARN state after hand-back *or* restart;
   the visible scrollback is demoted to *history*, never left implying the agent still remembers (needs ⟂ARCH-S6).

---

## 8. Open questions for the Auftraggeber (to ratify) + first-pass status

**For the Auftraggeber to decide:**
- **Q-A** — One mode-switching window per agent (my recommendation, §2) **vs.** two side-by-side windows? (If two,
  the honesty rule still binds the inactive one.)
- **Q-B** — Interactive token count in the titlebar: **greyed "eingefroren"** vs **hidden** during INTERACTIVE (§5)?
- **Q-C** — Worktree shell (kind iii): reserve-only now, or in scope for a companion story?
- **Q-D** — If ⟂ARCH-S3 allows it: do we *want* a read-only "hub observes" variant, or is **hard-blind** the
  intended, simpler honesty?

**First-pass status:** this is the **erster Aufschlag** — the window model, control-state spine, hand-off flow,
and honesty teeth are specified; **exact tokens, i18n keys, testTags, and WCAG ratios are deliberately deferred to
spec-closure** (companion artefacts `CYP-331-*-tokens.json`/`-keys.md`/`-tags.md`, muster CYP-17), because §6's
architecture answers can still move the surfaces. **Nothing is built.** Recommend: settle ⟂ARCH-S1 first (it
gates the core model), then I close the spec + artefacts for build.
