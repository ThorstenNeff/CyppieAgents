# Design-Spec — CYP-319: ACL-Matrix-Skalierung (Sticky-Header + Kanal-Filter)

> **Status:** DESIGN-SPEC (kein Bau) · Owner: UIUX · Story (Med) · docs-only auf `feature/CYP-319-acl-scaling-spec` (off develop `d3f3793`).
> **Herkunft:** die geschärfte Umsetzung von §5/§9-7 aus `cyp317-acl-flexible-grants.md`. CYP-317 machte **jede** Zelle grantbar → der Grid wird dichter; CYP-319 macht ihn bei **vielen Agenten × vielen Kanälen bedienbar**. Kern-Feature (CYP-317) shippt eigenständig; dies greift erst bei Größe.
> **Grounding (echter Code, `AclPanel.kt`):** `WideGrid` (Z.160) = **eine** `Column` mit **gleichzeitig** `horizontalScroll` **und** `verticalScroll` (Z.172-173) → alle Header-Zeilen (Subject-Band Z.176, Agent-Header Z.189, und die Kanal-Spalte je Zeile Z.210) **scrollen mit weg** → Orientierung geht bei Größe verloren. `CHANNEL_COL_WIDTH = 150.dp`, `AGENT_COL_WIDTH = 190.dp`, `PANE_COLLAPSE_WIDTH = 600.dp` (< → `NarrowCards` Per-Kanal-Auswahl, bleibt unberührt). Zellen = `AclCellView`; Reducer/VM unverändert.

---

## §1 — Ziel & Scope

- **Sticky-Header** im Wide-Grid: die **Kanal-Spalte** (linke Row-Header) und die **Agent-/Subjekt-Header** (obere Zeilen) bleiben beim Scrollen **fixiert**.
- **Kanal-Filter**: eine Teilmenge Kanäle (Zeilen) einblenden, um den Grid zu verkleinern.
- **Agent-Filter (optional)**: analog für Spalten.
- **Out of scope:** die `NarrowCards` (< 600.dp) bleiben unverändert (Per-Kanal-Fokus ist dort schon die Skalierung). Keine Änderung an Reducer/VM/Enforcement — **reine View/Layout + Client-Filter-State**.

---

## §2 — Sticky-Header: das „Frozen Row/Column"-Layout (der Kern)

Das heutige „eine Column, beide Scrolls" trägt kein Sticky. Umbau in das klassische **Vier-Quadranten-Frozen-Layout** mit **geteilten ScrollStates**:

```
            ┌────────────────┬──────────────────────────────┐
            │  Q1 Ecke (fix) │  Q2 Agent-Header  →H          │   ← nur horizontal scrollend
            ├────────────────┼──────────────────────────────┤
   Kanal-   │  Q3 Kanal-Spalte │  Q4 Zellen-Body  →H ↓V      │   ← Body: beide Achsen
   Spalte ↓V│  (nur vertikal)  │                              │
            └────────────────┴──────────────────────────────┘
```

- **Q1 (Ecke):** fix, `CHANNEL_COL_WIDTH` × Header-Höhe. Trägt die Filter-Affordanz (§3) oder bleibt leer.
- **Q2 (Agent-/Subjekt-Header):** `Modifier.horizontalScroll(hScroll)` — **kein** vertical. = das heutige Subject-Band (`acl_agents_group`/`acl_humans_group`) + die Agent-Spalten-Köpfe (`colHeader(id)`, PO-Badge, `◆`-Operator, Human-Köpfe).
- **Q3 (Kanal-Spalte):** `Modifier.verticalScroll(vScroll)` — **kein** horizontal. = die linke Kanal-Namen-Spalte (heute `Text(channel.name, width=CHANNEL_COL_WIDTH)` je Zeile).
- **Q4 (Body):** `Modifier.horizontalScroll(hScroll).verticalScroll(vScroll)` — die `AclCellView`-Zellen.
- **⭐ Geteilte ScrollStates:** `val hScroll = rememberScrollState(); val vScroll = rememberScrollState()` — Q2 **und** Q4 teilen `hScroll`; Q3 **und** Q4 teilen `vScroll`. So laufen die Header **synchron** zum Body (der Body treibt, die Header folgen deckungsgleich). Genau **ein** `hScroll` + **ein** `vScroll` für den ganzen Grid.
- **Ausrichtung:** Spalten-Breiten bleiben `AGENT_COL_WIDTH` in Q2 **und** Q4 (identisch), Q1/Q3 `CHANNEL_COL_WIDTH` — sonst driften Header und Zellen. Row-Höhen in Q3 **und** Q4 identisch halten (dieselbe Zell-`padding`/Content-Höhe), sonst vertikaler Drift.

> **Compose-Hinweis:** es gibt kein eingebautes Frozen-Grid — das geteilte-`ScrollState`-Muster ist der etablierte Weg. `LazyColumn.stickyHeader` löst **nur** die vertikale Kanal-Achse und **nicht** die eingefrorene Kanal-**Spalte** (horizontal) → das Vier-Quadranten-Layout ist nötig. (Alternativ `LazyTable`/`LazyLayout` — Overkill fürs MVP; das geteilte-Scroll-Muster ist schlanker.)

---

## §3 — Kanal-Filter (Zeilen reduzieren)

- **Affordanz:** eine schlanke Filter-Zeile **über** dem Grid (oder in Q1) — Empfehlung: ein **Suchfeld** „Kanäle filtern" (Substring auf `channel.name`, case-insensitive) **und/oder** eine Kanal-Auswahl. MVP: Suchfeld genügt.
- **Wirkung:** filtert `state.channels` **nur für die Anzeige** (Client-`remember`-State), **bevor** `WideGrid` die Zeilen rendert. **Kein** VM-/Server-Touch — der Filter ist eine **View-Operation**, ändert **nie** den durchgesetzten ACL-Zustand (Honesty: Filtern ≠ Entziehen).
- **Leerzustand:** filtert alles weg → dezente „keine Kanäle passen"-Zeile (kein Fehler, kein `acl_empty`-Miss — unterscheidbar von „echt leer").
- **Reset:** ein „×"/Clear stellt die volle Liste her.
- **Persistenz:** Filter-State an die VM-Instanz gekoppelt (`remember(viewModel)`), damit ein Projektwechsel ihn zurücksetzt (wie `NarrowCards` `selected`, AclPanel.kt:244 — dieselbe CYP-283-Falle vermeiden).

**Agent-Filter (optional, analog):** Substring auf `agent.name`/`memberLabel` filtert die Spalten. Für v1 nachrangig — Kanal-Filter zuerst (Zeilen wachsen mit dem Hub-and-Spoke schneller als Agenten).

---

## §4 — Honesty & Disclosure (Skalierung darf nie täuschen)

- **Filtern ≠ Entziehen.** Ein ausgefilterter Kanal/Agent ist **verborgen, nicht geändert** — der durchgesetzte Zustand bleibt. Der Filter ist rein visuell; niemand verliert dadurch ein Recht.
- **Sichtbarer Filter-Zustand.** Wenn ein Filter aktiv ist, MUSS das erkennbar sein (aktives Suchfeld / „n von m Kanälen"-Hinweis), damit „ich sehe nur 3 Zellen" nicht als „es gibt nur 3" fehlgelesen wird — sonst läse ein gefilterter Grid als vollständige ACL (falsche Vollständigkeits-Behauptung).
- **Alle CYP-317-Marker bleiben** in den sichtbaren Zellen unverändert (pending/enforced/`kein Mitglied`/conflict/`⚑`) — Skalierung berührt die Zell-Semantik nicht.

---

## §5 — Copy-Keys (DE + EN)

**NEU (je DE+EN):**
| Key | DE | EN |
|---|---|---|
| `acl_filter_channels` | Kanäle filtern | Filter channels |
| `acl_filter_none` | Keine Kanäle passen zum Filter | No channels match the filter |
| `a11y_acl_filter_clear` | Filter zurücksetzen | Clear filter |

(Agent-Filter, falls gebaut: `acl_filter_agents` „Agenten filtern"/„Filter agents" — sonst weglassen.)

> **⚠️ Shared-Key-Drift:** synchron in `values/strings.xml` (DE) **+** `values-en/strings.xml` (EN) mit dem Bau. Timing abstimmen.

---

## §6 — testTags (`aclMatrix.*`, shared QA/CYP-7)

**NEU:**
| Tag | Element |
|---|---|
| `aclMatrix.channelFilter` | das Kanal-Filter-Feld |
| `aclMatrix.channelFilter.clear` | der Clear-/Reset-Knopf |
| `aclMatrix.filterEmpty` | der „keine Kanäle passen"-Leerzustand |
| `aclMatrix.stickyCorner` | Q1-Ecke (optional, für QA-Verankerung des Frozen-Layouts) |

- **Reuse unverändert:** `aclMatrix.grid`, `colHeader(id)`, `rowHeader(id)`, `cell(...)` + alle Zell-Qualifier — die Sticky-Umstrukturierung darf diese Tags **nicht** umbenennen/verlieren (QA/CYP-7-Vertrag). Vor Landung gegen `AclMatrixTags.kt` verifizieren ([[verify-reuse-testtags-against-code]]).

---

## §7 — Invarianten (= UX-QA-Abnahme)

1. **Sticky-Header** — beim horizontalen Scroll bleibt die **Kanal-Spalte** sichtbar; beim vertikalen Scroll bleibt der **Agent-/Subjekt-Header** sichtbar; Ecke (Q1) fix.
2. **Deckungsgleiche Synchronität** — Header folgen dem Body ohne Drift (geteilte `hScroll`/`vScroll`; identische Spaltenbreiten/Zeilenhöhen Q2↔Q4 / Q3↔Q4).
3. **Kanal-Filter reduziert Zeilen** — Substring-Filter blendet Kanäle nur visuell aus; Clear stellt alles her.
4. **Filtern ≠ Entziehen (Honesty)** — kein VM-/Server-Touch; durchgesetzter Zustand unverändert; aktiver Filter ist **sichtbar** (nie als „vollständige ACL" fehllesbar).
5. **CYP-317-Zell-Semantik unberührt** — grantbare Nicht-Member, pending/enforced/`kein Mitglied`/conflict/`⚑` unverändert in sichtbaren Zellen.
6. **Narrow-Modus unverändert** — `NarrowCards` (< 600.dp) bleibt Per-Kanal-Fokus; Sticky/Filter sind Wide-only.
7. **Tags erhalten** — bestehende `aclMatrix.*`-Tags nicht umbenannt/verloren; neue Filter/Sticky-Tags ergänzt.
8. **i18n + a11y** — 3 (+1 opt.) Keys DE+EN; Filter-Feld beschriftet, Clear mit a11y-Name; kein grüner SUCCESS.

---

## §8 — Hand-off

- **Kein Bau, kein Merge.** Docs-only auf `feature/CYP-319-acl-scaling-spec` (distinkter Worktree `CYP-319-spec`).
- **Reuse-Anker:** `WideGrid`-Inhalt (Subject-Band, `colHeader`, `rowHeader`, `AclCellView`) — **umlagern** in das Vier-Quadranten-Frozen-Layout, **nicht** neu bauen; `rememberScrollState` (geteilt); `NarrowCards` unverändert; CYP-283-`remember(viewModel)`-Muster für den Filter-State.
- **Keys/Tags** synchron mit dem Bau landen (shared QA/CYP-7); Tags gegen `AclMatrixTags.kt` verifizieren.
- **UX-QA nach Bau:** die 8 §7-Invarianten, gerendert — Sticky (h+v), Filter (inkl. Leerzustand + Clear + sichtbarer aktiver Zustand), Honesty (Filtern ≠ Entziehen), CYP-317-Semantik unberührt, DE+EN.
