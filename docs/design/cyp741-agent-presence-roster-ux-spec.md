# CYP-741 — Konsolidierter Agent-Presence-Dot im Management-Roster (web-ts) · UX-Spec (agent-scoped)

**Für:** Dev5 · **Von:** UIUX2 (Team-2) · **Baseline:** develop `f8a0960c` (am Objekt) · **Aus:** CYP-703 Gap-Map (n)-Gap #2
**human-identity:** **n** (agent-scoped — Agent-**Run-State** + Busy, NIE ein Mensch) · **Tooling-Grenze:** headless render-test-messbar.

---

## §0 Scope + Grenze
Der Management-Roster (`AgentManagementPanel`) zeigt den Agent-Run-State heute **nur als Text** (`AgentManagementPanel.tsx:113-114` — `lifecycleLabel(runStateByAgent.get(a.id) ?? 'UNKNOWN', undefined)`), **ohne Dot, ohne Busy**. Der `LifecycleHeader` (`LifecycleHeader.tsx:38-44`) hat denselben Fakt **als Dot**. Ergebnis: zwei Orte, ein Fakt, **inkonsistent** dargestellt.
**Diese Spec:** den **Status-Dot** (reuse `statusDotSpec`/`dotRoleVar`, CYP-431) + einen **Busy-Marker** (reuse `busyByAgent`, CYP-641) in die Roster-Zeile ziehen — **denselben Dot wie der Header**, damit Presence an beiden Orten **identisch** liest.
- **★ Grenze:** dies ist **Agent-Run-State-Presence** (RUNNING/STOPPED/ERROR/UNKNOWN + busy) — **`human-identity: n`**. Die stärkere „**Mensch**-online/away"-Präsenz (die wüsste/zeigte, **welcher Mensch**) = **`j` → PL-Weiche → NICHT hier** (identity-blocked, content-free AuthMe).

## §1 Der Honesty-Kern — UNKNOWN ist ein RING, kein „offline"
Der Dot ist **reiner Port** von `statusDotSpec` (CYP-431/CYP-396), keine neue Logik:
| Run-State | Dot | Rolle (Token) |
|---|---|---|
| RUNNING | **fill** (Scheibe) | `primary` |
| STOPPED | **fill** | `outline` |
| ERROR | **fill** | `error` |
| **★ UNKNOWN** (noch **kein** Lifecycle-Event) | **ring** (Kontur) | `outline` |
| **pending** (Start/Stop läuft) | **fill** | `neutral` |
**★ Der Kern:** ein Agent, dessen Run-State wir **noch nicht beobachtet** haben, ist **UNKNOWN = Ring** — eine **andere Achse** als STOPPEDs gefüllte Scheibe, **nicht** „offline/gestoppt". Unbeobachtet ≠ aus. Ein voreiliges „STOPPED"-Grau für einen nie-beobachteten Agent wäre die Lüge (unknown≠zero, fail-closed). Der Roster nutzt **denselben** `?? 'UNKNOWN'`-Default wie heute (`:114`) — nur bekommt UNKNOWN jetzt seine ehrliche **Ring**-Form statt nur ein Wort.

## §2 Rendering (reuse den LifecycleHeader-Dot verbatim)
Exakt das Markup aus `LifecycleHeader.tsx:38-44` — **keine Divergenz**:
```
const spec  = statusDotSpec(state, pending !== undefined)  // state = runStateByAgent.get(a.id) ?? 'UNKNOWN'
const color = dotRoleVar(spec.role)
// fill: { width:8, height:8, borderRadius:'50%', background: color }
// ring: { width:8, height:8, borderRadius:'50%', border:`2px solid ${color}`, boxSizing:'border-box' }
```
- **★ colour-never-sole (WCAG 1.4.1):** die **Text-Label bleibt** (`agentMgmt.item.{id}.status`, heute `:114`) — der Dot **verstärkt** nur, trägt nie allein. Der Dot ist `aria-hidden`; die Bedeutung trägt das Label (`lifecycleLabel`) + `data-shape`.
- **testTags:** Dot `agentMgmt.item.{id}.dot` (+ `data-shape="fill|ring"`), Label bleibt `agentMgmt.item.{id}.status`.
- **Busy-Marker (reuse `busyByAgent`, CYP-641):** ein **distinkter** Marker, wenn der Agent **aktiv arbeitet** — `busyByAgent.get(a.id) ?? false`. testTag `agentMgmt.item.{id}.busy`, `aria-label` „arbeitet". **Nicht** in den Run-State-Dot einebnen ([[reconcile-not-collapse-distinct-states]]): Run-State und Busy sind **verschiedene** Fakten (ein RUNNING-Agent kann idle sein).

## §3 Honesty-Zähne (diskriminierend)
1. **★ UNKNOWN = Ring, nicht gefüllt** — nie-beobachteter Agent → **ring/outline**, **nicht** STOPPEDs Scheibe. *(Mutation: UNKNOWN rendert eine gefüllte Scheibe / „offline" → RED = unbeobachtet als aus behauptet.)*
2. **★ Busy fail-closed** — `busyByAgent` **absent → false** (kein Busy-Marker); nie ein geratenes „arbeitet". *(Mutation: fehlender Busy-Wert → Busy-Marker an → RED = faked working.)*
3. **★ Busy unter ERROR unterdrückt** — bei `runState === 'ERROR'` **kein** Busy-Marker (kein Misch-Signal „arbeitet + Fehler"), Spiegel von `deriveWindowActivity` (`activityBadge.ts`). *(Mutation: ERROR + busy zeigt beide → RED = mixed signal.)*
4. **★ pending nicht-optimistisch** — ein laufender Start/Stop → **neutraler** fill-Dot (`pending`), **nicht** schon die Ziel-Farbe (primary/outline). *(Mutation: pending zeigt schon RUNNING-primary → RED = resolved-vor-Event.)*
5. **colour-never-sole** — das Text-Label bleibt; entfernt man es und lässt nur den Dot, geht Bedeutung farb-only verloren. *(Mutation: nur-Dot, Label entfernt → RED.)*
6. **agent-scoped** — Dot + Busy lösen `agentId`/Run-State auf, **nie** einen Menschen. *(Mutation: der Roster braucht/zeigt „welcher Mensch" → RED = das wäre (j).)*

## §4 Konsistenz-Anker (der eigentliche Wert)
Roster-Dot **===** Header-Dot: **eine** Quelle (`statusDotSpec`), **ein** Verhalten. Ein diskriminierender Test kann beide Orte mit demselben State speisen und **gleiche `data-shape`/Rolle** verlangen — driftet einer, RED. Das ist der Consolidation-Punkt aus CYP-703 (n)-Gap #2: nicht ein neuer Dot, sondern **derselbe** Dot am zweiten Ort.

## §5 Übergabe
- **Reuse, kein neuer Store, keine neuen i18n-Keys:** `statusDotSpec`/`dotRoleVar`/`lifecycleLabel` (CYP-431) + `busyByAgent` (CYP-641) + das Header-Dot-Markup (`:38-44`). Der Dot braucht **keinen** neuen Key.
- **★ Wiring-Flag (Prop-Sync):** `AgentManagementPanel` bekommt heute **`runStateByAgent`** (`:37`), **aber nicht `busyByAgent`** — der Dev muss `busyByAgent` als **Prop aus dem Store** durchreichen (wie `App.tsx:238`, `useHubStore((s) => s.busyByAgent)`). Das ist geteilter State, keine neue Naht — aber die Panel-Signatur ändert sich → **mit der Impl timen**, nicht separat.
- **Agent-scoped (`human-identity: n`)** — Run-State + Busy sind Agent-Fakten; nie ein Mensch.
- **Grenze:** „welcher **Mensch** ist präsent" = **(j) STOP** (identity-blocked); diese Spec ist rein Agent-Run-State + Busy.
