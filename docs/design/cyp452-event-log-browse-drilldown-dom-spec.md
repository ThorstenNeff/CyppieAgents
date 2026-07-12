# CYP-452 (P2-c.2) — Event-Log Browse + Drilldown im DOM: die Offline-Inspektions-Fläche

> Owner: UIUX-Designer · Ticket **CYP-452** (Epic **CYP-430** Voller-Ersatz-Cutover, P2-c.2) · Stand 2026-07-12
> Basis `origin/develop` `69a14a3a` · `09-UI-Funktionskatalog` §Event-Log · EVENT-LOG-UI §6 · Docs-only → **Dev5-Referenz**.
> **Port der Compose-Quelle, kein Neuentwurf.** **0 neue Keys/Tags** (33 referenzierte `event_*`-Keys gg. `strings.xml` verifiziert; Tags = `EventBrowseTags`). **Nichts gebaut.**
>
> **Quelle:** `eventlog/EventBrowsePanel.kt` · `EventBrowseViewModel.kt` · `EventContract.kt` (`EventFilter`/`Page`) ·
> `EventRowUi.kt` (geteilte Zeile) · `EventVisuals.kt` (Achsen) · `EventLogTags.kt` (`EventBrowseTags`).
> **Baut auf** CYP-432 (P2-c Live-Tail, **gemergt** `5817bc4d`) + meiner CYP-432-Spec §2 (Browse/Korrelation skizziert).
> **Distinct vom Live-Tail:** der Tail *streamt* (Pause/Puffer/Trim); **Browse** *inspiziert offline* — seq-paged REST,
> Filter, Detail, Drilldown. **Gemeinsam = die Zeile + die drei Achsen (Reuse, §7).**

---

## 0. Die Leak-Grenze zuerst — CYP-432-Erbe, nicht neu verhandelt

Browse ist **derselbe** operator-gated Egress **mit Bodies** wie der Live-Tail (die `detail`-JSON-Payload jedes Events +
die REST-Seite `GET /api/events?…`). Also **gilt dieselbe Grenze**, unverändert:

- **Gating am MOUNT (Omission), NICHT present-but-disabled.** Kein Operator-Token ⇒ die Browse-Route/das Fenster wird
  **gar nicht gemountet** — kein Socket, keine REST-Query, **keine Bodies im DOM**, nichts CSS-hidden. (Vgl. CYP-433
  API-Key = present-but-disabled, **weil der Screen nichts leakt**; Browse leakt Bodies → Omission. Der Kontrast ist
  bewusst — nicht falsch übertragen.)
- **Laufzeit-Entzug fail-closed.** Wird der Zugriff live entzogen (WS 1008 / REST 403), räumt die UI und zeigt
  `eventBrowse.accessRevoked` — der Puffer wird verworfen, keine stale Bodies bleiben stehen.
- Leak-Ketten-Einordnung: CYP-421 content-free → **CYP-432/CYP-452 gated-Bodies** → CYP-433 Klartext-Secret.

> Diese Grenze ist **schon** durch das gemergte CYP-432 etabliert (§0 dort, 4-schichtig fail-closed). Browse **erbt** sie
> über denselben Mount-Gate; sie wird hier nur als bindende Voraussetzung wiederholt, nicht neu erfunden.

---

## 1. Umfang & Abgrenzung zum Live-Tail

| | **Live-Tail (CYP-432, gemergt)** | **Browse (CYP-452, diese Spec)** |
|---|---|---|
| Datenweg | WS-Stream `/ws/events` | seq-paged REST `GET /api/events?…` + `SubscribeEvents(filter)` |
| Zweck | Under-Load live zusehen | offline inspizieren, filtern, korrelieren |
| Zeit-Achse | anhängend, Pause/Puffer/Trim | `Page(afterSeq, limit)`, LoadMore |
| Eigene Mechanik | Pause-Toggle, buffered-count, overflow/trim | **Filter-Bar, Detail-Pane, Drilldown**, Two-/Single-Pane |
| **Gemeinsam** | **die Zeile (3 Achsen), Severity/Glyph, Gap-Zeile, Leak-Gate — EINE Quelle (§7)** | ← |

P2-c.2 portiert `EventBrowsePanel`: **Master-Tabelle + Detail-Pane + Drilldown + Filter-Bar**, responsiv.

---

## 2. Responsive Anatomie (Mirror `EventBrowsePanel`)

Die Tabelle ist breiter als Comm → ein harter 300dp-Detail-Pane würde den Master auf einem schmalen Tile (~140dp) auf
0dp verhungern. Deshalb **zwei Layouts**, Schwelle `PANE_COLLAPSE_WIDTH` = **600dp** (geteilt mit Comm/ACL):

```
BREIT (≥600dp) — Two-Pane                     SCHMAL (<600dp) — Single-Pane (Selection navigiert)
┌ eventBrowse ─────────────────────────┐     ┌ eventBrowse ─────────────────┐
│ ┌ Master (weight 1.4) ┐ ┌ Detail(1) ┐│     │  Tabelle  ODER  Detail  ODER │
│ │ filterBar           │ │ detail    ││     │  Drilldown  (mit ‹ Back)     │
│ │ table / drilldown   │ │  json     ││     │  filterBar bleibt im Master  │
│ │ loadMore            │ │ showRun/  ││     └──────────────────────────────┘
│ └─────────────────────┘ │ showSess. ││     Detail hat ‹ Back → Tabelle
│                         └───────────┘│     Drilldown besitzt die Master-Fläche
└──────────────────────────────────────┘
```

Zwei getrennte Breakpoints, **nicht** verwechseln: **Pane-Collapse 600dp** (Master/Detail) vs.
**`EVENT_ROW_REFLOW_WIDTH` 560dp** (die Zeile selbst reflowt intern auf 2 Zeilen — §7). Im DOM via
`ResizeObserver`/Container-Query an der jeweiligen Fläche gemessen, nicht am Viewport.

---

## 3. Filter-Bar — server-seitige Query, ehrliche Offenlegung (Mirror `FilterBar`)

**Cycle-Chips** (Tap zyklt die Achse `null → … → null`), jeder Tap ⇒ `applyFilter(filter)` ⇒ **server-seitige** Query
(REST-Query-Params / WS-`SubscribeEvents`). **KEIN Client-Post-Filter** über schon geladenen Daten (§6.2 der Quelle):

- `eventBrowse.filter.agent` / `.type` / `.severity` — Agent/Typ/Severity (Zyklen aus `EventFilter`-Achsen).
- `eventBrowse.filter.project` (CYP-94, **Operator-Cross-Project-Linse**): `null`=aktives Projekt (Server erzwingt,
  CYP-102) → andere Projekte → `all` → `null`. Sendet `projectId` nur wenn non-null.
- `eventBrowse.filter.timeWindow` / `.correlation` — present per Kontrakt; die Korrelations-Achse wird über den
  **Drilldown** getrieben (§6), nicht als freier Chip.

**Zwei ehrliche Hinweise (Absence≠all-clear — dieselbe Regel wie im Live-Tail):**
- **Subset-Cue `eventBrowse.filterActive`** — sichtbar sobald `filter ≠ EventFilter()`: „gefiltert" darf **nie** als
  „nichts passiert" gelesen werden. Ohne diesen Cue ist ein gefiltertes Empty ununterscheidbar vom echten Empty.
- **Cross-Project-View-Indikator `eventBrowse.crossProjectView`** (INFO-Ton) — sichtbar sobald `projectId ≠ null`:
  fremde Events dürfen **nie** als Events des aktiven Projekts missgelesen werden. Zusätzlich trägt jede Zeile im
  Cross-View ihre Projekt-Identität (`eventBrowse.row.N.project`, **Text**, nie Farbe allein).

**a11y:** jeder Chip wird als **interaktiver Filter mit aktuellem Wert + Tap-Aktion** angesagt
(`a11y_event_filter_chip`) — nicht nur als sichtbarer „label: value"-Text; das Cycle-Verhalten ist sonst für den
Screenreader unsichtbar (CYP-277).

---

## 4. Master-Tabelle (Mirror `MasterPane`)

- **Seq-paged:** `Page(afterSeq, limit)`, Events mit `seq > afterSeq`. **Ordering IMMER `seq`, nie ein Zeitstring**
  (Anzeige-Zeit ist Display-only, CYP-336). `eventBrowse.loadMore` wenn `hasMore`.
- **Zeilen:** die geteilte `EventRow` (§7), Key = `event.id`, index-stabile Tags `eventBrowse.row.N` +
  `eventBrowse.row.N.<severity|gap>` + `eventBrowse.rowById.<id>`.
- **Error schlägt Empty (CYP-288 — Failure-as-empty-Klasse):** ein fehlgeschlagener **First-Page**-Load rendert
  `eventBrowse.error` + `eventBrowse.error.retry` (Retry = aktuelle Filter erneut), **NICHT** den „keine Events"-Zustand.
  Ein fehlgeschlagener **loadMore** behält die schon geladene Tabelle (Daten nie hinter der Fehlerfläche versteckt).
- **Ehrliches Empty `eventBrowse.empty`** nur wenn wirklich leer **und** nicht ladend.

---

## 5. Detail-Pane (Mirror `DetailPane`) — content-free, nichts erfunden

Kopf: `type · severityLabel`; `HH:MM:SS.mmm · seq N · agentId` (monospace). Dann:

- **`sourceTs` = „beobachtet"** (`eventBrowse.detail.sourceTs`, `event_detail_source_ts`) — informativ, **nie
  autoritativ** (die maßgebliche Ordnung ist `seq`, §5.2 der Quelle).
- **Getippte Summaries — WARN-amber, nie grün:**
  - `COMPACT_ORCHESTRATION_DONE` → `eventBrowse.detail.compactSummary`: X/N aus der content-free Payload;
    **WARN-amber** wenn `pendingAgentIds` (Timeout) **oder** `aborted` — ein Timeout/Abort wird **nie** als Erfolg
    gerendert; ein sauberes N/N ist neutral (**nie grün**). Absent wenn die Counts fehlen.
  - `RESUME_OUTCOME` → `eventBrowse.detail.resumeOutcome`: `CONTEXT_LOST` = **WARN-amber** (unbeabsichtigter
    Gedächtnisverlust), `RESUMED_WITH_CONTEXT`/`FRESH_NO_RESUME` neutral (kein Fehler, keine Warnung, kein Erfolg).
- **Content-free `detail`-JSON as-is** (`eventBrowse.detail.json`) — roh, **nichts fabriziert** (§5.5).
- **Zwei EXPLIZITE Drilldown-Aktionen (der Kern der Ehrlichkeit hier):**
  - `eventBrowse.detail.showRun` — **enabled NUR wenn `correlationId ≠ null`**.
  - `eventBrowse.detail.showSession` — **enabled NUR wenn `sessionId ≠ null`**.
  - **Nie geraten, nie ein konflatierter Fallback** (§6.3): fehlt das Feld, ist der Knopf `disabled`, nicht auf die
    andere Achse umgebogen. „Zeig den ganzen Run" und „zeig die ganze Session" sind **zwei verschiedene Achsen**.
- **Back** (`eventBrowse.back`) nur im Single-Pane (Two-Pane: kein Back, Detail steht neben dem Master).

---

## 6. Drilldown (Mirror `DrilldownView`) — Re-Query auf einer benannten Achse

- **Header `eventBrowse.drilldown.header`** nennt Achse + Scope explizit: „correlated by `<axisValue>`" (
  `event_drilldown_correlated_by`), `axisValue = correlationId ?? sessionId`. **Clear-on-Click** (räumt den Drilldown),
  a11y sagt die Clear-Aktion an (`a11y_event_drilldown_header`, CYP-277).
- **Re-Query** in eine **seq-geordnete Timeline** entlang **einer** Achse (`correlationId` ODER `sessionId`, **nie
  vermischt** — die Trennung aus §5 setzt sich hier fort). Eigene Zeilen-Tags `eventBrowse.drilldown.row.N`.
- Der Drilldown **besetzt die Master-Fläche** (im Single- wie Two-Pane); der Filter-Bar bleibt darüber.

---

## 7. Reuse: die Zeile + die drei Achsen = EINE Quelle (kein Drift ggü. Live-Tail)

Browse und Tail **müssen** identisch rendern. Die reinen Helfer liegen schon in `web-ts/src/eventlog/eventLog.ts`
(CYP-448): **`severityGlyph`**, **`severityLabel`**, **`typeGlyph`**, **`eventRows`** (Gap-Erkennung). Browse nutzt
**dieselben**. Die drei **nicht-kollidierenden** Achsen (EVENT-LOG-UI §1–§4):

1. **Severity = die EINZIGE Farb-Achse** — Rail-Hue + Glyph (`⚠`/`▲`/`ⓘ`/`·`) + Text-Label, **Farbe nie allein**
   (WCAG 1.4.1). Scheme-adaptiv (CYP-274); **WARN = amber, nie `tertiary`** (a0/CYP-300: `tertiary` wird nachts grün →
   eine Warnung läse „ok"); **DEBUG bewusst dim** (rides Glyph+Label, kein ≥3:1-Rail).
2. **Type = Group-Glyph + monospace Wire-Text, KEIN Hue** — `typeGlyph`/`groupGlyph` (⚙▦⤵⚠⏻⇄☂▽⇆▤ⓘ, von mir final
   signiert bei CYP-432). **UNKNOWN bewahrt `rawType`** — nie ein geplättetes „unknown", das verschluckt, welcher Typ
   es war.
3. **Identity = Avatar + Name** (`SenderPalette`/CYP-14, Custom-Farbe/Avatar/Rolle via CYP-224), luminance-adaptiert
   AA-lesbar hell+dunkel (CYP-275). Nie Status.

- **Gap-Zeile** (`LOG_DROPPED`): `errorContainer`-Hintergrund + ⚠ + Count — **nie ein stiller seq-Sprung** (§5.3).
- **Korrelations-Chip:** `correlationId.take(8)` oder „—", **nie geraten** (§4). Auf `onSurfaceVariant`, nicht `outline`
  (lesbare Info braucht 4,5:1, CYP-337).
- **Zeilen-Reflow** bei `< 560dp` (`EVENT_ROW_REFLOW_WIDTH`, **distinct** von der 600dp-Pane-Schwelle) → deterministische
  2-Zeilen-Gruppierung (Triage-Zeile + Identity-Zeile), **gleiche Kind-Knoten + Tags**, nur die Gruppierung ändert sich.

> **Empfehlung an Dev5 (Divergenz-Vermeidung):** die Live-Tail-`EventRow` ist aktuell **inline in `EventLogView.tsx`**.
> Für Browse die Zeile in eine **geteilte Komponente** ziehen (mirror Compose: eine `EventRow`, beide Panels nutzen sie),
> damit ein künftiger Fix nicht an einer Fläche vorbeigeht. Mindestens die `eventLog.ts`-Helfer teilen — **kein**
> zweiter Severity-/Glyph-Pfad in Browse.

---

## 8. Keys & Tags — alles bestehend (0 neu)

**Keys (Reuse, gg. `strings.xml` develop `69a14a3a` verifiziert — 33/33 vorhanden):**
`event_filter_agent/_type/_severity/_timewindow/_correlation/_active/_project/_project_all`,
`event_view_project/_all_projects`, `event_empty`, `event_load_more`, `load_failed`, `event_detail_source_ts`,
`event_drilldown_correlated_by/_show_run/_show_session`, `event_compact_done_summary/_aborted`,
`event_resume_context_lost/_with_context/_fresh`, `event_gap_dropped`, `event_row_project`,
`event_severity_error/_warn/_info/_debug`, `comm_back`,
a11y: `a11y_event_row/_gap/_filter_chip/_drilldown_header`.

**Tags (Reuse, gg. `EventBrowseTags` verifiziert):** `eventBrowse.filterBar`, `.filter.agent/.type/.severity/.timeWindow/.correlation/.project`,
`.filterActive`, `.crossProjectView`, `.table`, `.row.N[.<qual>]`, `.rowById.<id>`, `.row.N.project`, `.error[.retry]`,
`.loadMore`, `.empty`, `.detail[.json/.compactSummary/.resumeOutcome/.sourceTs/.showRun/.showSession]`, `.back`,
`.drilldown[.header/.row.N]`, `.accessRevoked`. Im DOM als `data-testid`, punktfrei-Schema (Test-Contract v0.5, QA CYP-7).

> **Tag-Scheme-Notiz an Dev5:** der gemergte Live-Tail nutzt ein **flacheres** `event.row.<id>` / `event.type.<id>` /
> `event-log-*` statt der `eventTail.*`-Kontrakt-Tags. **Browse folgt dem dokumentierten `EventBrowseTags`-Kontrakt**
> (`docs/design/event-log-tags.md`). Empfehlung: bei Gelegenheit den Tail an den Kontrakt angleichen — **kein Blocker
> für CYP-452**, nur ein Konsistenz-Hinweis (Reconcile über PO/QA).

---

## 9. Ehrlichkeits-Invarianten (Basis der Abnahme)

1. **Leak-Mount-Gating** (CYP-432-Erbe): kein Operator ⇒ nicht gemountet, keine Bodies im DOM; Entzug fail-closed (§0).
2. **Keine erfundene Korrelation** (§5/§6): showRun/showSession **nur** bei vorhandenem Feld; zwei Achsen, nie konflatiert.
3. **Absence≠all-clear:** Subset-Cue bei aktivem Filter; Cross-Project-Indikator; gefiltert ≠ „nichts passiert".
4. **Error schlägt Empty** (CYP-288): fehlgeschlagener First-Page ≠ Leer-Zustand.
5. **`sourceTs` beobachtet, Ordering `seq`** — nie Zeitstring-Sortierung, nie autoritativer `sourceTs`.
6. **WARN amber, nie grün/`tertiary`** (a0); compact-timeout/aborted + resume-context-lost = WARN, sauberes N/N neutral.
7. **Content-free as-is; UNKNOWN=`rawType`; Gap nie stiller Skip** — nichts fabriziert, kein geplättetes „unknown".
8. **EINE Zeilen-/Severity-Quelle Browse↔Tail** (§7): kein zweiter Farb-/Glyph-Pfad.
9. **Farbe nie alleiniger Träger; kein `ellipsis`** auf Offenlegungs-/Fehler-Text.

---

## 10. Abnahme-Zähne (diskriminierend — je mit der falschen Impl, die er ablehnt)

1. **Keine erfundene Korrelation.** showRun aktiv⇔`correlationId≠null`, showSession aktiv⇔`sessionId≠null`.
   **Mutation:** ein Button immer aktiv **oder** einer als Fallback für den anderen (ein Klick zeigt „irgendeine"
   Timeline) ⇒ rot.
2. **Server-seitige Query, kein Client-Post-Filter.** Ein Chip-Tap löst eine neue Query aus. **Mutation:** die Liste
   wird clientseitig über schon geladenen (ungefilterten) Daten gefiltert ⇒ rot.
3. **Subset-Cue.** `filter≠default` ⇒ sichtbarer `filterActive`. **Mutation:** ein gefiltertes Empty ist von einem
   echten Empty ununterscheidbar ⇒ rot.
4. **Error schlägt Empty.** **Mutation:** fehlgeschlagener First-Page-Load rendert „keine Events" statt Error+Retry ⇒ rot.
5. **Leak-Mount-Gating.** **Mutation:** Browse ohne Operator gemountet / Bodies im DOM / CSS-hidden statt un-mounted /
   Entzug lässt stale Bodies stehen ⇒ rot.
6. **`sourceTs` beobachtet, Ordering `seq`.** **Mutation:** `sourceTs` als autoritativ gerendert **oder** Sortierung
   nach Zeitstring ⇒ rot.
7. **WARN amber, nie grün.** **Mutation:** compact-timeout/aborted oder resume-context-lost grün/`tertiary`/„success" ⇒ rot.
8. **Content-free & bewahrend.** **Mutation:** ein erfundenes Feld im Detail / geplättetes „unknown" statt `rawType` /
   ein stiller seq-Sprung ohne Gap-Zeile ⇒ rot.
9. **Eine Zeilen-Quelle Browse↔Tail.** **Mutation:** Browse re-inlined eine eigene, driftende Zeile/Severity/Glyph ⇒ rot.

---

## 11. DOM-/A11y-Spezifika

- **Responsive** via `ResizeObserver`/Container-Query: Pane-Collapse 600dp (Master/Detail), Zeilen-Reflow 560dp (in der
  Zeile) — **getrennt** halten. Der skiko-`BoxWithConstraints`-Seam entfällt; natives Overflow ([[migration-ports-tokens-not-optics]]).
- **Master** = `role="table"`/Liste; Zeilen klickbar (`role="button"`/row), `contentDescription` = severity+type+agent+time
  (`a11y_event_row`). **Detail** = `role="region"`. **Drilldown-Header** clear-on-click angesagt. **Chips** interaktiv +
  Wert + Aktion angesagt. **`accessRevoked`** = `role="alert"`.
- Zielgröße interaktiver Elemente ≥ 24px (Chips, LoadMore, showRun/showSession, Back). **Farbe nie alleiniger Träger.**
  **Kein `text-overflow: ellipsis`** auf Offenlegungs-/Fehler-Text (Aktionslabels/IDs dürfen kürzen).

**Nichts gebaut — Spec + Dev5-Referenz.** Browse ist die Offline-Inspektions-Fläche über **denselben** operator-gated,
mount-gegateten Bodies wie der Live-Tail, mit **derselben** Zeile und **derselben** Severity/Glyph-Quelle — und der
schärfste Zahn ist die **nie erfundene Korrelation**: zwei explizite Drilldown-Achsen, jede nur so weit klickbar, wie das
Feld wirklich da ist.
