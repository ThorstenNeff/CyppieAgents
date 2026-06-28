# Product-Lead — On-Demand Report-Feature (Desktop) (v0.1)

> Owner: UIUX-Designer · Epic: **CYP-77** (S16) · Stories: **CYP-89** (Backend: Report-/Endpunkt-Vertrag, S16a) + **CYP-90** (UI: Trigger + Berichte ansehen, S16) · Status: **Entwurf — wartet auf Dev-Gegenlesen** · Stand: 2026-06-28
> **Kanonischer Ort:** geteiltes Repo `KMPCyppieAgents` unter `docs/PRODUCT-LEAD.md`.
> Begleit-Artefakte (Muster CYP-17): `docs/design/product-lead-tokens.json`, `product-lead-keys.md`, `product-lead-tags.md`.
> **Brand:** CyppieAgents (Anti-Hype). Desktop/`commonMain`-tauglich. Basis: aktueller develop (`570d178`; Reuse-Anker gg. `e80cfa8` verifiziert).

> **Bezug (im Code verifiziert, develop):**
> - **Report/Snapshot = greenfield** (kein `/api/reports`, kein `productLead`/Report-Konzept heute).
> - **Datenquellen (READ) vorhanden:** `core/.../model/EventModel.kt` → `Event(id, ts, seq, sourceTs?, agentId, teamId, sessionId?, correlationId?, type: EventType, severity: Severity, detail)`; `Severity{DEBUG,INFO,WARN,ERROR}`; `EventType` u. a. `error.model/tool/ratelimit`, `timeout`, `process.exit`, `log.dropped`, `agent.spawned/stopped/restarted`, `turn.*`, `result.final`, `context.usage`, `comm.sent/received`. Lese-Vertrag: `events/EventSink.kt` → `EventFilter(agentId?,type?,severity?,since?,until?,correlationId?,sessionId?)`, `Page(afterSeq?,limit)`, `query(): EventPage`. Comm: `GET /api/channels|/api/inbox`, `Message`. Agenten: `GET /api/agents`.
> - **Operator-Gate:** `routing/Auth.kt` → `requireOperator()` wirft **403 `operator_required`**; Event-Observability ist operator-gated (fail-closed).
> - **As-of-/Beobachtungs-Disclosure (Reuse):** `event_connection_offline` „… Stand %1$s", `event_detail_source_ts` „Beobachtet: %1$s", seq≠ts-Ehrlichkeit (CYP-34).
> - **Severity-Token (Reuse):** `docs/design/event-log-tokens.json` → `severity_rail` (error/warn/info/debug = CYP-12-Hues + Icon + Text).
> - **List+Detail + Operator-Gate-UI (Reuse):** `comm/CommPanel.kt` (Liste links / Detail rechts), `AgentShell.kt`-Mount.
> - **projektId (S12.1):** `Event.teamId`/`Channel.projectId` additiv; ein Bericht gilt **je Projekt** (MVP=1 impliziter Kontext, kein Switcher).

Spezifiziert das **Product-Lead-Feature**: ein **on-demand ausgelöster Report-Generator**, der Plattform-Zustand **liest** und **zeitgestempelte, read-only Momentaufnahmen** erzeugt (3 Report-Typen). **Liest, baut nicht.** Keine Implementierungsvorgabe — Verhalten, Disclosure-Leitplanken, der Report-/Endpunkt-Vertrag (CYP-89) und die UI (CYP-90).

---

## 0. Geltungsbereich & Nicht-Ziele

| | |
|---|---|
| **In Scope** | Ein **Product-Lead-Fenster** (`window.<id>` = `productLead`): **on-demand-Trigger** („wo stehen wir?") für 3 Report-Typen + **Snapshot-Liste + Detail** (read-only). Report-/Endpunkt-Vertrag-Skizze für Backend (CYP-89). Operator-Gating/fail-closed, Snapshot-Disclosure. testTags + a11y de/en. |
| **Nicht-Ziele** | **KEINE Roster-Rolle/ACL** (s. §1.6 — entkoppelt von **CYP-98**). Das Feature **baut/ändert nichts** (kein Repo-/Agenten-Eingriff). **Kein** Live-Dashboard (Snapshots, kein Stream). **Kein** Jira-/Git-/Test-Connector (greenfield, §2). Kein Projekt-Switcher (S13/[MP]). |

**Abgrenzung CYP-98 (verbindlich):** Product-Lead ist hier ein **Feature/Service**, kein Teilnehmer mit Rolle/ACL. Die Product-Lead-**Rollen/ACL-Posture bleibt geparkt (CYP-98, Auftraggeber-Sign-off)**. Dieses Design **hängt nicht davon ab** und **entscheidet sie nicht vor**. Wo ein Posture-Aspekt gestreift wird, ist er **„Vorschlag-mit-Sign-off"**, nicht entschieden.

---

## 1. Disclosure-Prinzipien (Kern dieses Features)

1. **Snapshot ≠ Live-Wahrheit.** Jeder Bericht ist eine **Momentaufnahme je Lauf** mit **„Stand: <ts>"** prominent (Liste + Detail). Die UI stellt einen Bericht **nie** als fortlaufend gültigen Zustand dar — er kann unmittelbar nach Erzeugung veralten. (Reuse `… Stand %1$s`.)
2. **Liest, baut nicht — keine Garantie.** Das Feature **beobachtet**; es ändert nichts. Das **Defekt-/Lücken-Register ist advisory/beobachtet** aus benannten Quellen — **keine** Zusicherung der Vollständigkeit und **keine** Bestätigung, dass jeder Eintrag ein echter Defekt ist. Wortlaut: „beobachtete Defekte/Lücken aus <Quellen>", nie „alle Defekte" / „garantiert".
3. **Provenienz sichtbar.** Jeder Bericht nennt **Quellen + Beobachtungsfenster** (`since/until`) und „beobachtet" vs. autoritativ (`sourceTs` ≠ `ts`, Reuse CYP-34). Abgeleitet, nicht orakelhaft.
4. **Operator-gated, fail-closed, kein Leak.** Berichte aggregieren **operator-gated Observability** (Events). Daher sind **Trigger + Ansehen operator-gated**; **kein erlaubter Zugriff → kein Bericht** (kein Teil-/Phantom-Bericht). Der Report darf **kein Leak-Vektor um das Operator-Gate** werden (Disziplin wie CYP-55).
5. **Immutable, je Lauf neu.** Ein Trigger erzeugt einen **neuen** Snapshot; nie wird ein früherer überschrieben/gemerged. Die Liste ist eine **Historie von Momentaufnahmen** (Vergleich zeigt Veränderung über Zeit; keiner ist „der" Status).
6. **Entkoppelt von Rolle/ACL (CYP-98).** Das Trigger-Gate ist das **Operator-Token**, **nicht** eine Product-Lead-Roster-Rolle. Farbe/Label nie als Berechtigungsträger.

---

## 2. CYP-89 — Report-/Endpunkt-Vertrag (Backend, **Skizze für Dev/Backend**)

> Greenfield am Backend; die Datenquellen (READ) existieren (Event-Store/`EventSink.query`, `GET /api/agents|channels|inbox`). Der Generator **liest** sie und faltet sie zu Snapshots. **Jira/Git/Test-Connector existieren NICHT** → Berichte beziehen sich im MVP auf **plattform-interne** Beobachtung (Events/Comm/Agenten), nicht auf Repo-/Ticket-Wahrheit. → flag.

**Endpunkte (Vorschlag, finale Form = PO/Backend):**

| Endpunkt | Gate | Body / Wirkung | Fehler |
|---|---|---|---|
| `POST /api/reports` | **Operator** | `{ type: "usage"\|"status"\|"defects", since?, until? }` → erzeugt **neuen** Snapshot, gibt ihn zurück | 401 / **403 `operator_required`** / 400 `invalid_report_type` |
| `GET /api/reports` | **Operator** | → Liste Snapshot-Metadaten `[{id,type,generatedAt,summary}]`, neueste zuerst | 401 / 403 |
| `GET /api/reports/{id}` | **Operator** | → voller Snapshot | 401 / 403 / 404 `report_not_found` |

**Report-Datenmodell (Snapshot, Vorschlag → `:core`):**
```
ReportSnapshot { id, type, generatedAt (ts), projectId, window {since,until}, sources: [..], sections: [ReportSection] }
ReportSection  { key, title, items: [ReportItem] }
ReportItem     { text, severity?: Severity, refs?: {agentId?, correlationId?, sessionId?, eventId?} }
```
- **Immutable**, je Lauf neu; nie gemutiert. Persistenz greenfield (kann Event-Store-nah liegen).
- **Content-frei** wie der Event-Store: Items tragen **Metadaten/Refs**, keine sensiblen Bodies (Leak-Disziplin).

**Drei Report-Typen (je Snapshot):**
| Typ | „wo stehen wir?"-Frage | READ-Quellen (MVP) | Disclosure |
|---|---|---|---|
| **Usage-Guide** (`usage`) | „Wie ist diese Plattform aufgesetzt / wie nutze ich sie?" | aktuelle Agenten/Rollen (`GET /api/agents`), Kanäle (`/api/channels`), Setup | Momentaufnahme des Setups, kein How-To-Versprechen über den Stand hinaus |
| **Status-Report** (`status`) | „Wo stehen wir gerade?" | Lifecycle/Aktivität aus Events (`turn.*`, `result.final`, `agent.*`), Status-Messages (`/api/inbox`, `MessageKind.STATUS`) | **beobachteter** Stand zum `generatedAt`, nicht Live |
| **Defekt-/Lücken-Register** (`defects`) | „Was ist offen/kaputt/fehlt?" | `error.*`, `timeout`, `process.exit`, **`log.dropped`** (= Telemetrie-Lücke), Severity-Aggregat | **advisory/beobachtet**, NICHT vollständig/bestätigt; `log.dropped` ehrlich als „Beobachtungslücke" |

> **Datenpfad-Flags (an PO/Backend):** (a) **operator-gated + fail-closed + content-frei** (kein Leak um das Event-Gate). (b) **MVP = plattform-interne Beobachtung** (kein Jira/Git/Test). (c) **pro Projekt** (`projectId`/`teamId`; MVP=1). (d) **Shared-Key-Sync**: neue i18n-Keys als `:app:shared` (DE+EN) mit der Impl timen; Report-DTOs in `:core`.

---

## 3. CYP-90 — UI (Trigger + Berichte ansehen)

**Fenster `window.productLead`** wie Comm/Settings montiert; **operator-gated** (ohne Operator-Token: kein Bericht — Gate-Hinweis, fail-closed).

**Layout (Reuse CommPanel List+Detail):**
- **Trigger-Leiste** `productLead.trigger`: „Bericht erzeugen" mit 3 Typ-Aktionen `…trigger.usage` / `…trigger.status` / `…trigger.defects`. On-demand; bei Erzeugung `productLead.generating` (Fortschritt, „Wird erzeugt…").
- **Snapshot-Liste links** `productLead.list`: je Zeile `productLead.snapshot.<id>` mit **Typ + „Stand: <ts>"** (`…snapshot.<id>.ts`). Neueste zuerst. Leer → `productLead.empty` („Noch keine Berichte").
- **Detail rechts** `productLead.detail`:
  - **`productLead.detail.asOf`** — prominent „Stand: <ts>" (Snapshot ≠ live).
  - **`productLead.detail.provenance`** — Quellen + Fenster (`since/until`), „beobachtet" (Reuse `event_detail_source_ts`).
  - **`productLead.detail.advisory`** — beim Defekt-Register: „Beobachtete Defekte/Lücken — nicht vollständig/bestätigt." (Info-Ton).
  - **Sections** `productLead.detail.section.<key>`; Defekt-Zeilen `productLead.detail.defect.<index>` mit **Severity-Rail (Reuse `severity_rail` + `event_severity_*`)** — Farbe **+ Icon + Text**, nie nur Farbe.

**Zustände & Disclosure:**
| Zustand | UI | Tag |
|---|---|---|
| **Kein Operator-Token** | kein Bericht, Trigger+Liste gesperrt, Gate-Hinweis (fail-closed) | `productLead.gateHint` |
| **Keine Berichte** | ehrlicher Empty-State | `productLead.empty` |
| **Erzeugung läuft** | Fortschritt „Wird erzeugt…" | `productLead.generating` |
| **Bericht offen** | „Stand: <ts>" + Provenienz immer sichtbar | `productLead.detail.asOf` |
| **Defekt-Register** | advisory-Banner + Severity-Rail je Zeile | `productLead.detail.advisory` |
| **Fehler** (`invalid_report_type`/404/403) | Fehlerzeile, Code-Mapping | `productLead.error` |

**a11y/RTL:** Trigger/Buttons mit Rollen+Labels; „Stand"/Provenienz als Text+a11y (nicht nur visuell); Severity nie nur Farbe; RTL spiegelt (Liste/Detail-Seiten folgen Start/End automatisch).

---

## 4. Reuse (statt Eigenbau)

| Element | Reuse-Quelle | Verifiziert |
|---|---|---|
| Fenster-Chrome / Mount | `WindowTestTags.window/content/titleBar`, `AgentShell.kt` | ✅ Code |
| List+Detail-Layout + Operator-Gate-UI | `comm/CommPanel.kt` (Liste/Detail), `editable=operatorToken!=null` | ✅ Code |
| READ-Datenvertrag | `events/EventSink.query` + `EventFilter`/`Page`/`EventPage`; `GET /api/agents\|channels\|inbox` | ✅ Code |
| As-of-/Beobachtungs-Disclosure | `event_connection_offline` „Stand %1$s", `event_detail_source_ts` „Beobachtet: %1$s", seq≠ts | ✅ Code |
| Severity-Achse (Defekt-Register) | `event-log-tokens.json#severity_rail` + `event_severity_*` (error/warn/info/debug) | ✅ Code |
| Operator-Gate | `requireOperator` → 403 `operator_required` | ✅ Code |

**Keine Dubletten:** kein zweites Severity-Schema (Reuse CYP-34), kein eigener Offline/As-of-Stil, kein Event-Stream nachbauen (Snapshot statt Live).

---

## 5. Offene Punkte (PO / Backend)

1. **Endpunkt-Vertrag bestätigen** (§2): Pfade/Codes (`invalid_report_type`, `report_not_found`), `{type, since?, until?}`-Form.
2. **Snapshot-Persistenz** (§2): dauerhaft (Historie) vs. ephemer/zwischengespeichert? (Empfehlung: persistent, immutable, je Lauf — Historie ist der Wert.)
3. **Trigger-Gate** (§1.4): Operator-Token bestätigt? (Empfehlung: ja, fail-closed, weil Events operator-gated; **das ist KEIN Product-Lead-Rollen-Gate** — Posture bleibt CYP-98.)
4. **MVP-Quellen** (§2): bleibt es bei plattform-interner Beobachtung (Events/Comm/Agenten)? Jira/Git/Test = späterer Connector (eigenes Ticket).
5. **Report-Inhaltstiefe je Typ:** welche Sektionen genau (kalibriert Dev/Backend gegen echte Event-Daten) — Designwerte sind Defaults.

> **Posture-Hinweis (Vorschlag-mit-Sign-off, NICHT entschieden):** Falls Product-Lead später eine Roster-Rolle wird (CYP-98), könnte dieses Feature ihr „natürlicher Ort" sein — **aber** das ist ausdrücklich **geparkt** und **präjudiziert hier nichts**.

**Gate (später):** Reviewer + Desktop-`runComposeUiTest` (Tags in `product-lead-tags.md`). Diese Spec ist **docs-only**; sie definiert den Report-/Endpunkt-Vertrag + die UI, baut sie nicht.
