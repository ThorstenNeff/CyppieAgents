# testTag-Schema — Event-Log-UI (Browse + Live-Tail) (v0.1)

> Owner: UIUX-Designer · Tickets: **CYP-41** (Browse) + **CYP-42** (Live-Tail), Epic **CYP-34** · Status: **Entwurf** · Stand: 2026-06-27
> Begleitend zu `docs/EVENT-LOG-UI.md`, `docs/design/event-log-tokens.json`, `docs/design/event-log-keys.md`.
> **Schema (Test-Contract v0.5 §2, `docs/TEST-CONTRACT.md`):** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, **prefixless**. Segmentwerte `[A-Za-z0-9-]+` (**keine Punkte** — kollidieren mit dem Trenner und Maestros Regex-Selektor).
> **Vertrag zwischen Dev und QA (CYP-7):** Diese Tags sind eine **API**, nicht still umbenennen. Single source of truth; Vorbild `comm/CommTags.kt` + `agentview/AgentViewTags.kt`.

Macht beide Surfaces für Compose-UI-Tests + Maestro stabil adressierbar. **Entgated Devs Visual-Layer** (PO-Punkt 1). Vorschlag für zwei `:app:shared`-Objekte: `EventBrowseTags` (`eventBrowse`-Area) und `EventTailTags` (`eventTail`-Area).

---

## 0. Konventionen

- **Zwei Areas, je Single-Instance** (je ein Fenster, nicht instanz-scoped wie `agent.<agentId>`): `eventBrowse` (CYP-41), `eventTail` (CYP-42).
- **Zeilen-Selektor = Index** (0-basiert, additiv-stabile Renderreihenfolge) — wie `AgentViewTags.event(agentId, index)`.
- **Qualifier-Vokabular** für Zeilen: Severity (`error`/`warn`/`info`/`debug`) **oder** `gap` (für `log.dropped`-Gap-Zeilen) — erlaubt typ-/severity-basierte Assertions.
- **Optionaler id-stabiler Selektor** `rowById.<eventId>` (ULID ist `[A-Za-z0-9]`, punktfrei → safe) für Dedupe-/Idempotenz-Assertions im Live-Tail. Primär bleibt der Index.

---

## 1. Browse-UI — Area `eventBrowse` (CYP-41)

| Element | testTag | Zweck |
|---|---|---|
| Filterleiste | `eventBrowse.filterBar` | Container der Filter |
| Filter Agent | `eventBrowse.filter.agent` | Agent-Filter-Control |
| Filter Typ | `eventBrowse.filter.type` | Typ-Filter-Control |
| Filter Severity | `eventBrowse.filter.severity` | Severity-Filter-Control |
| Filter Zeitfenster | `eventBrowse.filter.timeWindow` | Zeitfenster-Control |
| Filter Korrelation | `eventBrowse.filter.correlation` | correlationId-Filter |
| Aktiver-Filter-Indikator | `eventBrowse.filterActive` | „Filter aktiv – Teilmenge" |
| Tabelle (virtualisiert) | `eventBrowse.table` | LazyColumn/Grid der Events |
| Event-Zeile (Index) | `eventBrowse.row.<index>` | n-te Zeile, additiv-stabil |
| Event-Zeile + Qualifier | `eventBrowse.row.<index>.<error\|warn\|info\|debug\|gap>` | severity-/gap-basierte Assertion |
| Event-Zeile (id-stabil, optional) | `eventBrowse.rowById.<eventId>` | id-stabile Assertion |
| „Mehr laden" | `eventBrowse.loadMore` | Paging-Trigger |
| Empty-State | `eventBrowse.empty` | keine Events |
| Detail-Bereich | `eventBrowse.detail` | Detail-Pane |
| Detail-JSON | `eventBrowse.detail.json` | aufgeklapptes `detail` |
| Detail sourceTs | `eventBrowse.detail.sourceTs` | „Beobachtet: …" (nur Hooks) |
| Aktion „Ganzer Lauf" | `eventBrowse.detail.showRun` | Drilldown über `correlationId` |
| Aktion „Ganze Session" | `eventBrowse.detail.showSession` | Drilldown über `sessionId` |
| Zurück (Single-Pane) | `eventBrowse.back` | Navigation Detail→Tabelle |
| Drilldown-Timeline | `eventBrowse.drilldown` | Lauf-/Session-Timeline-Container |
| Drilldown-Header | `eventBrowse.drilldown.header` | „Korreliert über … %1$s" |
| Drilldown-Zeile (Index) | `eventBrowse.drilldown.row.<index>` | Zeile in der Timeline |
| Zugriff entzogen (Laufzeit) | `eventBrowse.accessRevoked` | Fallback bei Token-Entzug (§5.6) |

> **Window-Präsenz:** Ohne Operator-Token wird das Fenster **gar nicht angeboten** (Omission) — dann existiert **kein** `eventBrowse.*`-Knoten. QA prüft die Omission über die **Abwesenheit** von `eventBrowse.table`, nicht über einen „kein Zugriff"-Tag.

---

## 2. Live-Tail-UI — Area `eventTail` (CYP-42)

| Element | testTag | Zweck |
|---|---|---|
| Strom (Ring) | `eventTail.stream` | scrollender Live-Strom |
| Event-Zeile (Index) | `eventTail.row.<index>` | n-te Zeile, additiv-stabil |
| Event-Zeile + Qualifier | `eventTail.row.<index>.<error\|warn\|info\|debug\|gap>` | severity-/gap-basierte Assertion |
| Event-Zeile (id-stabil, optional) | `eventTail.rowById.<eventId>` | Dedupe-/Idempotenz-Assertion |
| Pause/Resume-Toggle | `eventTail.pauseToggle` | ⏸/▶ (ein Toggle) |
| Live-Indikator | `eventTail.liveIndicator` | ● Live (nur wenn offen + nicht pausiert) |
| Pausiert-Indikator | `eventTail.pausedIndicator` | „Pausiert" |
| Puffer-Count (Pause) | `eventTail.bufferedCount` | „N neue (pausiert)" |
| Pause-Puffer-Overflow | `eventTail.bufferOverflow` | „Puffer voll – älteste verworfen" |
| Live-Ring-Trim | `eventTail.trimmed` | „ältere getrimmt (N)" |
| Verbindungs-Banner | `eventTail.connection` | Offline/Reconnect (ehrlich) |
| Filter Agent | `eventTail.filter.agent` | Agent-Filter |
| Filter Typ | `eventTail.filter.type` | Typ-Filter |
| Filter Severity | `eventTail.filter.severity` | Severity-Filter |
| Empty-State | `eventTail.empty` | noch nichts gestreamt |
| Zugriff entzogen (Laufzeit) | `eventTail.accessRevoked` | Fallback bei Token-Entzug (§5.6) |

> **Window-Präsenz:** wie Browse — ohne Operator-Token kein `eventTail.*`-Knoten; Omission über Abwesenheit von `eventTail.stream` prüfen.

---

## 3. Test-relevante Disclosure-Anker (für QA/CYP-7)

Diese Tags existieren **gerade**, damit die Honesty-Eigenschaften testbar sind — nicht nur kosmetisch:

- **Gap sichtbar:** `*.row.<i>.gap` muss erscheinen, wenn ein `log.dropped`-Event im Strom liegt (nie still übersprungen).
- **Client-Caps sichtbar:** `eventTail.trimmed` (Live-Ring) und `eventTail.bufferOverflow` (Pause-Puffer) müssen bei Überlauf erscheinen.
- **Pausiert ≠ live:** bei aktiver Pause ist `eventTail.liveIndicator` **abwesend** und `eventTail.pausedIndicator` **präsent**.
- **Operator-Omission:** ohne Token **keine** `eventBrowse.*`/`eventTail.*`-Knoten (Abwesenheits-Assertion).
- **Zwei getrennte Drilldowns:** `eventBrowse.detail.showRun` und `…showSession` sind zwei Knoten; jeder nur aktiv, wenn das Feld am Event vorhanden ist.

---

## 4. Hand-off-Hinweis (Dev + QA)

- Diese Tags gehören als `EventBrowseTags`/`EventTailTags`-Objekte in `:app:shared` (wie `CommTags`/`AgentViewTags`) und sind **mit dem Tester (CYP-7) zu teilen** — Änderungen koordiniert über den PO.
- **Shared-Key-Drift** gilt auch hier sinngemäß: Tags sind ein geteilter Vertrag; das konsumierende Test-Modul muss zur selben Zeit nachziehen.
