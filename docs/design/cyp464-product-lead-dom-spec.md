# CYP-464 (P2-d) — Product-Lead-Report im DOM: der Snapshot, der nie als Live-Wahrheit auftritt

> Owner: UIUX-Designer · Ticket **CYP-464** (P2-d, Story unter Epic **CYP-430** Voller-Ersatz-Cutover) · Stand 2026-07-12
> Basis `origin/develop` `69a14a3a` · `09-UI-Funktionskatalog` §Product-Lead · CYP-90 (S16) · Docs-only → **Dev5-Referenz**.
> **Port der Compose-Quelle, kein Neuentwurf.** **0 neue Keys/Tags** (16 `report_*` + `a11y_report_*` + `event_severity_*`-Reuse + `comm_back`; Tags = `ProductLeadTags`). **Nichts gebaut.**
>
> **Quelle:** `report/ProductLeadPanel.kt` · `ProductLeadViewModel.kt` · `ProductLeadTags.kt` · `core/model/ReportModel.kt` (`ReportType`/`ReportItem`).
> **Reuse:** die **CommPanel-Master/Detail-Shape** (schon im DOM: `web-ts/src/comm/`) — Liste + read-only Detail-Pane, gleiche responsive Zwei-/Ein-Pane-Mechanik.

---

## 0. Der Kern zuerst: **Snapshot ≠ Live**

Ein Product-Lead-Report ist eine **Punkt-in-Zeit-Beobachtung**, **nie** ein stehender/aktueller Zustand. Die ganze
Fläche existiert, um das ehrlich zu halten:

- Jeder Snapshot trägt ein **prominentes „As of: `<ts>`"** (in der Liste **und** als Detail-Heading) + den Hinweis
  **„kann veraltet sein"** (`report_snapshot_hint`) + die **Provenance** (welche Quellen, welches Beobachtungsfenster).
- Ein Report wird **nie** als „aktueller Stand" gerendert. Er ist beobachtet-zu-einem-Zeitpunkt, nicht live.

> Das ist dieselbe Familie wie [[forecast-vs-observed-disclosure]]: dort Vorhersage ≠ Beobachtung, hier
> **Beobachtung-zu-einem-Zeitpunkt ≠ Live-Zustand**. In beiden Fällen darf die eine Zahl nie als die andere gelesen werden.

---

## 1. Umfang (Mirror `ProductLeadPanel`, CYP-90 S16)

Das Product-Lead-Fenster: eine **on-demand Trigger-Bar** + eine **newest-first Snapshot-Liste** + ein **read-only
Detail-Pane** (Reuse der CommPanel-Liste+Detail-Shape). **Operator-gated/fail-closed.** Drei Report-Typen:
**USAGE · STATUS · DEFECTS**. Items sind **content-free**.

---

## 2. Anatomie (responsive, Schwelle `PANE_COLLAPSE_WIDTH` = 600dp)

```
BREIT (≥600dp) — Two-Pane                     SCHMAL (<600dp) — Single-Pane
┌ productLead.panel ───────────────────┐      ┌ productLead.panel ──────────┐
│ productLead.trigger (bleibt stehen)   │      │ trigger (bleibt)            │
│ ┌ list (0.4) ┐ ┌ detail (0.6) ──────┐ │      │ list  ODER  detail (+ back) │
│ │ snapshot.N │ │ detail.asOf        │ │      └─────────────────────────────┘
│ │  .ts       │ │ provenance/advisory │ │      Selection navigiert; Detail hat ‹ Back
│ └────────────┘ │ sections/defects   │ │
│                └────────────────────┘ │
└───────────────────────────────────────┘
```

Die **Trigger-Bar bleibt stehen** (kein Pane) — nur Liste/Detail kollabiert (CYP-156). Detail hat `‹ Back` nur im
Single-Pane. `verticalScroll` → nativer `overflow-y` im DOM.

---

## 3. Operator-Gate — fail-closed (Mirror `!state.accessible`)

Kein Operator-Token ⇒ das Panel ist **nur der Gate-Hint** `productLead.gateHint` (`report_access_denied`) —
**keine** Trigger, **keine** Liste. Ton = **GATED neutral** (`onSurfaceVariant`), **nie `tertiary`/grün**: „denied" ist
ein **Gate, kein Fehler** (a0/CYP-300 — `tertiary` wird nachts grün und läse „ok"). **Text trägt die Bedeutung**, nie die
Farbe allein.

---

## 4. Trigger-Bar — on-demand Snapshot-Erzeugung (Mirror `TriggerBar`)

`productLead.trigger` (FlowRow → im DOM **flex-wrap**, damit die 3 Buttons auf schmal umbrechen statt zu quetschen).
Label `report_generate` + drei Buttons: `trigger.usage`/`.status`/`.defects` (`report_type_usage/_status/_defects`,
`maxLines:1`). **Disabled während `generating`** (kein Doppel-Trigger).

- **Generating-Hint** `productLead.generating` (`report_generating`): **neutral** `onSurfaceVariant` — in-progress ist
  **nie grün** (a0).
- **Error-Hint** `productLead.error` (`report_error`): error-Ton (echter Fehler, distinct vom Gate/in-progress).

---

## 5. Snapshot-Liste (Mirror `SnapshotList`)

`productLead.list`: newest-first `metas`, jede Zeile `productLead.snapshot.<id>` = **Typ-Label** + **„As of: `<ts>`"**
(`productLead.snapshot.<id>.ts`, `report_as_of`). Selektierbar (Detail).

- **Empty gated on `!loading`** (`productLead.empty`, `report_empty`): „keine Reports" darf **nie** während des
  initialen/Wechsel-Fetch-Fensters **flashen** (CYP-279 — ein Projekt, das Snapshots HAT, zeigt kurz sonst fälschlich
  „leer"). Ehrliche Leere nur, wenn wirklich geladen **und** leer.
- a11y: die Zeile wird als **selektierbarer Snapshot (Typ + As-of)** angesagt (`a11y_report_snapshot_select`), nicht nur
  als zwei Kind-Texte (CYP-277).

---

## 6. Detail-Pane — Snapshot ≠ Live, ehrlich (Mirror `DetailPane`)

- **Prominentes „As of: `<ts>`"** `productLead.detail.asOf` (`report_as_of`, **Heading**) + `report_snapshot_hint`
  („kann veraltet sein"). Snapshot ≠ Live, wiederholt an der maßgeblichen Stelle (§0).
- **Provenance** `productLead.detail.provenance` (`report_provenance`): **benannte Quellen + Beobachtungsfenster**
  („beobachtet, nicht autoritativ"). Keine erfundene Vollständigkeit.
- **DEFECTS = advisory** `productLead.detail.advisory` (`report_advisory`: „observed, not exhaustive/confirmed") —
  das Defekt-Register ist **nie** ein vollständiges/autoritatives Verdikt.
- **Sections** `productLead.detail.section.<key>`; Defekt-Zeilen `productLead.detail.defect.<index>` (+
  `.<severity>`-Qualifier): **Severity-Rail** (§7). Content-free items (`text` + optionales `refLabel`).
- **Back** `productLead.back` nur im Single-Pane.

---

## 7. Severity — Reuse, mit einer Glyph-Notiz

**Reuse, kein zweites Severity-Schema** (ProductLeadTags-KDoc): der Defekt-Rail nutzt `severityColor` + die
`event_severity_*`-**Labels**. **Farbe nie allein** — Glyph **+** Farbe **+** Label. Das Label ist die **a11y
`aria-label`** des Glyphs, **nicht** daneben gerendert (CYP-345: die falsche Prämisse „ein sichtbares Label stützt den
Glyph" ist genau das, was die Kontrast-Ausnahme redundanz-basiert aussehen ließ — `severityColor(DEBUG)` = `outline`
besteht ≥3:1 auf der Zahl selbst).

> **Glyph-Konsistenz-Notiz (kein Blocker, PO/Dev5-Call):** die Compose-Quelle nutzt hier einen **eigenen** Glyph-Satz
> **✕ / ! / i / ·** (ERROR/WARN/INFO/DEBUG), **distinct** vom Event-Log-Satz (⚠ / ▲ / ⓘ / ·). Für den DOM-Port: **Farbe
> + Label kommen aus einer Quelle** (das ist Pflicht); für den **Glyph** empfehle ich, den Event-Log-`severityGlyph`
> (`eventLog.ts`) **wiederzuverwenden** → eine Severity-Glyph-Quelle im ganzen Haus. Falls der report-eigene Satz
> intentional ist (report-/Checklist-Anmutung), bewahren — dann als **eine** report-lokale Funktion, nicht inline
> dupliziert. Ich signiere den finalen Glyph auf deinen Zuruf (wie bei CYP-432).

---

## 8. Keys & Tags — alles bestehend (0 neu)

**Keys (Reuse, gg. `strings.xml` develop `69a14a3a` verifiziert):** `report_title`, `report_generate`, `report_generating`,
`report_type_usage/_status/_defects`, `report_as_of`, `report_snapshot_hint`, `report_provenance`, `report_advisory`,
`report_empty`, `report_error`, `report_access_denied`, `a11y_report_snapshot_select`/`a11y_report_generate`,
`event_severity_error/_warn/_info/_debug` (Reuse), `comm_back` (Reuse).

**Tags (Reuse, gg. `ProductLeadTags` verifiziert):** `productLead.panel`, `.trigger[.usage/.status/.defects]`,
`.generating`, `.error`, `.gateHint`, `.list`, `.empty`, `.snapshot.<id>[.ts]`, `.detail[.asOf/.provenance/.advisory]`,
`.detail.section.<key>`, `.detail.defect.<index>[.<severity>]`, `.back`. **Fenster-Reuse:** `window.productLead[.content]`.
Im DOM als `data-testid`, punktfrei-Schema (Test-Contract v0.5, QA CYP-7).

---

## 9. Ehrlichkeits-Invarianten (Basis der Abnahme)

1. **Snapshot ≠ Live:** prominentes „As of" (Liste + Detail) + „kann veraltet sein" + Provenance; nie als aktueller
   Stand (§0/§6).
2. **Advisory, nie autoritativ:** DEFECTS-Register „observed, not exhaustive/confirmed"; nie vollständiges Verdikt (§6).
3. **Provenance = benannte Quellen + Fenster** (beobachtet, nicht autoritativ) — keine erfundene Vollständigkeit (§6).
4. **Operator-gated fail-closed:** kein Token → nur Gate-Hint (keine Trigger/Liste); GATED **neutral, nie grün** (§3).
5. **Severity = Farbe + Glyph + Label**, nie Farbe allein; generating/denied/in-progress **neutral, nie grün** (§7).
6. **Empty gated on `!loading`** — nie „keine Reports" während des Fetch flashen (§5).
7. **Content-free items;** kein `ellipsis` auf Provenance/Advisory/As-of/Gate-Text.

---

## 10. Abnahme-Zähne (diskriminierend — je mit der falschen Impl, die er ablehnt)

1. **Snapshot ≠ Live.** Jeder Snapshot zeigt „As of" + may-be-outdated. **Mutation:** ein Report ohne As-of / als
   „aktueller Stand" gerendert ⇒ rot.
2. **DEFECTS advisory.** **Mutation:** das Defekt-Register als vollständig/autoritativ („alle Defekte") ohne den
   advisory-Hinweis ⇒ rot.
3. **Provenance sichtbar.** **Mutation:** ein Report-Detail ohne benannte Quellen/Fenster (erfundene Vollständigkeit) ⇒ rot.
4. **Operator-Gate fail-closed.** **Mutation:** Trigger/Liste ohne Operator-Token sichtbar **oder** Gate-Hint im
   `tertiary`/grünen Ton ⇒ rot.
5. **Severity Farbe + Glyph + Label.** **Mutation:** Severity nur über Farbe **oder** „generating"/„denied" grün ⇒ rot.
6. **Empty gated on `!loading`.** **Mutation:** „keine Reports" flasht während des initialen/Wechsel-Fetch ⇒ rot.
7. **On-demand, disabled while generating.** **Mutation:** Trigger klickbar während `generating` (Doppel-Erzeugung) ⇒ rot.

---

## 11. DOM-/A11y-Spezifika

- **Reuse der CommPanel-Liste+Detail** (`web-ts/src/comm/`): gleiche responsive Zwei-/Ein-Pane-Mechanik, Trigger-Bar
  bleibt stehen. `FlowRow` → **flex-wrap**; `verticalScroll` → natives `overflow-y`; 600dp-Pane via `ResizeObserver`.
- **Snapshot-Zeile** = `role="button"` (as-of + Typ angesagt). **Detail „As of"** = `role="heading"`. **Gate-Hint /
  generating** = `role="status"`, **Error** = `role="alert"`. **Severity-Glyph** trägt `aria-label` = das
  Severity-Label (§7).
- Zielgröße interaktiver Elemente ≥ 24px (Trigger-Buttons, Snapshot-Zeilen, Back). **Farbe nie alleiniger Träger.**
  **Kein `text-overflow: ellipsis`** auf Provenance-/Advisory-/As-of-/Gate-Text (brechen um). **RTL** gespiegelt.

**Nichts gebaut — Spec + Dev5-Referenz.** Der Product-Lead-Report ist die on-demand Snapshot-Fläche, die sich **nie** als
Live-Wahrheit ausgibt: prominentes „As of" + Provenance + advisory-DEFECTS, operator-gated fail-closed, Severity als
Farbe **+** Glyph **+** Label — und die Leere ist erst dann leer, wenn wirklich geladen wurde.
