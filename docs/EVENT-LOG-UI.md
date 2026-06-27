# Event-Log-UI — Browse (Master-Detail + Drilldown) & Live-Tail (v0.1)

> Owner: UIUX-Designer · Tickets: **CYP-41** (ST7 Browse-UI) + **CYP-42** (ST8 Live-Tail-UI), Epic **CYP-34** · Status: **Entwurf — wartet auf Dev-Gegenlesen** · Stand: 2026-06-27
> **Kanonischer Ort:** geteiltes Repo `KMPCyppieAgents` unter `docs/EVENT-LOG-UI.md`.
> Begleit-Artefakte: `docs/design/event-log-tokens.json`, `docs/design/event-log-keys.md`.
> **Quelle der Wahrheit:** `docs/prd/06-observability-event-log.md` (Epic-PRD, auf develop `c794d1f`). Diese Spec rendert dessen Schema (§4) — sie erfindet **kein** Vokabular.
> **Reuse-First:** baut auf **CYP-14** (Farbcodierung/`colorSlot`), **CYP-12** (Status/Disclosure-Wortregeln), **CYP-17** (Master-Detail-Muster, Reconnect-Idempotenz) auf — keine Neuerfindung. **Brand:** CyppieAgents (Anti-Hype).

Definiert Layout, Zustände und **Disclosure-Honesty** der beiden Lese-Oberflächen des Event-Logs. Keine Implementierungsvorgabe.

> **Warum eine Spec für zwei Stories?** Beide UIs teilen die **Event-Zeile**, die Severity-/Typ-Kodierung, die Tokens und die i18n-Keys. Sie getrennt zu spezifizieren würde genau den Anti-Pattern (divergente One-offs) erzeugen, den wir vermeiden. Aufbau: §1–§5 = **gemeinsames Fundament**, §6 = **Browse (CYP-41)**, §7 = **Live-Tail (CYP-42)**. (Branch-Präzedenz: wie `docs/a11y-keys` über CYP-22+CYP-23.)

---

## 0. Bezugsrahmen (verifiziert gegen PRD + Code, 2026-06-27)

- **Datenmodell (PRD §4, final: `Event`-DTO in `:core`):** Wirbelsäule `id` (ULID), `ts` (Long, **maßgeblich**, im `append` gesetzt), `seq` (Long, **monoton → Totalordnung**), `sourceTs` (Long?, **informativ**), `agentId`, `teamId`, `sessionId?`, `correlationId?`, `type` (Enum), `severity` (Enum `debug`/`info`/`warn`/`error`), `detail` (JSON, **inhaltsfrei**).
- **Typen-Enum (PRD §4.1, kontrolliertes Vokabular):** 6 Gruppen — Agententätigkeit, Token-Grenzen, Hooks, Fehler/Unterbrechung, Lebenszyklus, Komm-Metadaten. Tolerantes Dekodieren bei unbekanntem Typ (07-Erweiterung additiv).
- **Datenquellen (PRD §3.2 / §6, Vertrag):** REST `GET /api/events` (gepaged/gefiltert, `query(filter, page)`) · WS `/ws/events` (`subscribe(filter)`). Beide **Operator-only, fail-closed** (PRD §7).
- **`log.dropped` (PRD §3.3):** bei voller Queue wird **drop-newest** verworfen **und** als Meta-Event/Metrik `log.dropped` (mit Anzahl) sichtbar. „No silent caps."
- **Banding (PRD §4.2):** `context.usage` wird **nur** bei 10%-Bandwechsel + Compact-Grenze persistiert → **gesampelt, nicht kontinuierlich**.
- **Fenster-Host:** beide UIs sind Inhalt **je eines** Fensters im Fenster-Manager (CYP-10, `WindowHost`/`content`-Slot). Chrome (Titelleiste/Resize) kommt vom Host; das Panel rendert nur seinen Innenbereich. Live-Tail ist bewusst ein **eigenes** Fenster (PRD §6).
- **Reuse-Anker (Code, develop):** `comm/SenderPalette.kt` (`colorSlot`→Farbe, CYP-14), `agentview/AgentStatus.kt` (Status ≠ Severity), `comm/CommViewModel.kt`/`CommWsClient.kt` (StateFlow + Reconnect-Muster), `comm/CommPanel.kt` (Master-Detail).

---

## 1. Drei **getrennte** Farb-Achsen (Kollision verboten)

Das Event-Log mischt drei Bedeutungsdimensionen in einer dichten Tabelle. Damit keine als die andere fehlgelesen wird, hat **jede eine eigene visuelle Sprache** — und sie überlappen sich nicht:

| Achse | Bedeutung | Visuelle Sprache | Quelle |
|---|---|---|---|
| **Identität** | *wer* (`agentId`) | Avatar-Initialen + Identitäts-Hue | **Reuse CYP-14** `colorSlot(agentId)` / `SenderPalette` |
| **Severity** | *wie dringend* (`severity`) | Severity-Hue **+ Icon + Text-Label** | **Reuse CYP-12** Status-Hues (s. §2) |
| **Typ** | *was* (`type`) | **Gruppen-Icon + monospace Typ-Text** — **kein eigener Hue** | PRD §4.1 (s. §3) |

> **Disclosure-Kern:** Identitäts-Hues (CYP-14) meiden bereits per Design die Status-Hues (CYP-12) — siehe `color-coding-tokens.json → reserved_status_hues_DO_NOT_REUSE`. Der **Typ** bekommt **bewusst keinen** eigenen Hue (zu viele Typen, würde mit beiden anderen Achsen kollidieren), sondern Icon + Text. So bleibt „grüner Agent" nie als „OK-Event" lesbar und umgekehrt.

---

## 2. Severity-Kodierung (`severity`) — reuse CYP-12, nie Farbe allein

Severity ist die **eine farbtragende** Achse der Zeile (linker Severity-Rail/Akzent). Hues sind **wiederverwendet** aus `state-tokens.json` (CYP-12) — semantisch deckungsgleich (Fehler = Fehler):

| `severity` | Reuse-Token (CYP-12) | Hue dark/light | Icon | Label-Key |
|---|---|---|---|---|
| `error` | `state.error` | `#FF6B6B` / `#C5221F` | `alert-triangle` | `event_severity_error` |
| `warn`  | `state.waiting` | `#FFC857` / `#B26A00` | `attention` | `event_severity_warn` |
| `info`  | `state.notice` | `#A0A4AD` / `#5F6368` | `info` | `event_severity_info` |
| `debug` | `state.offline` | `#5A5E66` / `#9AA0A6` | `dot` | `event_severity_debug` |

**Regel (verbindlich):** Severity **nie** allein über Farbe (WCAG 1.4.1). Jede Zeile trägt Severity als **Rail-Farbe + Icon + (im Detail) Text-Label**. Severity ist ein **Schnellfilter** (PRD §4), kein Werturteil über Korrektheit — `info`/`debug` sind ruhig, nicht „gut".

---

## 3. Typ-Kodierung (`type`) — Icon + Text, gruppiert (kein Hue)

Der `type` wird als **monospace Text** (exakter Enum-Wert, aggregierbar/greppbar konsistent) plus **Gruppen-Icon** dargestellt. Gruppierung folgt PRD §4.1:

| Gruppe | Beispiel-Typen | Gruppen-Icon (Vorschlag) |
|---|---|---|
| Agententätigkeit | `turn.start` `turn.end` `tool.call` `tool.result` `file.changed` `result.final` | `bolt` / `terminal` |
| Token-Grenzen | `context.usage` `compact.triggered` `compact.completed` | `gauge` |
| Hooks | `hook.fired` | `webhook` |
| Fehler/Unterbrechung | `error.model` `error.tool` `error.ratelimit` `process.exit` `timeout` `ws.disconnect` | `alert-triangle` |
| Lebenszyklus | `agent.spawned` `agent.restarted` `agent.stopped` `session.recycled` | `lifecycle`/`power` |
| Komm-Metadaten | `comm.sent` `comm.received` | `swap`/`forum` |

- **Unbekannter Typ** (07-Erweiterung, tolerantes Dekodieren): Roh-String monospace + neutrales Icon (`info`) anzeigen — **nie verschlucken** (sonst lügt die Anzeige über Vollständigkeit). Optional dezenter „unbekannter Typ"-Hinweis.
- Das Gruppen-Icon ist **Navigations-/Scan-Hilfe**, nicht severity — `error.*`-Typen tragen ihre Dringlichkeit über die **Severity**-Achse (§2), nicht über das Typ-Icon.

---

## 4. Die gemeinsame **Event-Zeile** (beide UIs)

Eine kompakte, scanbare, virtualisierungstaugliche Zeile (fixe Höhe). Reihenfolge start→end (RTL spiegelt):

```
│▌│ 14:03:07.214  ⚙ tool.call      ⬡FE  frontend   correlationId·a1b2…  ⓘ
 │   └ ts (lokal,   └ Gruppen-Icon  └ Identität     └ Korrelations-Chip   └ Detail
 │      ms-genau)      + type (mono)   (Avatar+Name)    (klickbar, §6.3)      öffnen
 └ Severity-Rail (Farbe+impliziert Icon)
```

Spalten (Master-Tabelle):
- **Severity-Rail** (linke Kante, Farbe §2) + Severity-**Icon**.
- **Zeit** — `ts` lokalisiert, **millisekundengenau** (Belastungstest braucht Auflösung). Sortiert wird intern nach **`seq`** (§5), nicht nach `ts`.
- **Typ** — Gruppen-Icon + monospace `type` (§3).
- **Identität** — Avatar (Initialen `agentId`) + Name, Hue `colorSlot(agentId)` (CYP-14); PO-Slot für den Hub.
- **Korrelations-Chip** — gekürzte `correlationId` (oder `sessionId`), **klickbar** → Drilldown (§6.3). Fehlt sie, Chip leer/`—`, nie geraten.
- **Detail-Affordanz** — öffnet Detail (§6.2 / Live-Tail: Inline-Expand).

**a11y:** Jede Zeile trägt ein zusammenfassendes `contentDescription` (Key `a11y_event_row`, §event-log-keys), das Severity, Typ, Agent und Zeit **als Text** nennt — Screenreader-tauglich ohne Farbe/Icon (WCAG 1.3.1/4.1.2). Virtualisierte Liste mit stabilen Keys (`Event.id`).

---

## 5. Ordnung, Zeit & Disclosure-Honesty (verbindlich — beide UIs)

Diese Punkte sind der eigentliche Wert meiner Spec; sie sind **nicht verhandelbar**:

1. **`seq` ist die Ordnungs-Wahrheit, nicht `ts`.** Anzeige/Paging/Live-Append sortieren nach **`seq`** (monoton, PRD §5). Wall-Clock `ts` kann springen (NTP) — als Spalte zeigen, aber **nie** als Sortierschlüssel. Bei sichtbarem `ts`-Rücksprung keine Umsortierung, die `seq` widerspricht.
2. **`sourceTs` ist „beobachtet", nicht „maßgeblich".** Nur bei Hook-Events (PRD §3.6) vorhanden. Im Detail **getrennt** und beschriftet zeigen („beobachtet: …", Key `event_detail_source_ts`) — nie als die autoritative Zeit ausgeben.
3. **`log.dropped` / Lücken sind SICHTBAR.** Ein `log.dropped`-Event erscheint als **eigene, hervorgehobene Gap-Zeile** („⚠ N Events verworfen — Log unvollständig in diesem Fenster", Key `event_gap_dropped`) — in **beiden** UIs, an Ort und Stelle in der `seq`-Ordnung. **Nie** stilles Überspringen, nie als normale Info-Zeile verstecken. Telemetrie darf Unvollständigkeit nicht kaschieren (PRD §3.3, Reviewer-Gate „No silent caps").
4. **`context.usage` ist gesampelt, nicht kontinuierlich.** Wo Token-Füllstand gezeigt wird (Drilldown-Timeline, §6.3), als **Band-Stützstellen** kennzeichnen („10% → 20% → … (gesampelt)", Key `event_usage_banded_hint`) — nie als lückenlose Kurve suggerieren (PRD §4.2).
5. **Metadaten-only — nichts erfinden.** Das Detail zeigt nur das, was im `detail`-JSON steht (inhaltsfrei per Konstruktion, PRD §3.5). Fehlt ein Feld (weil es Inhalt wäre), wird **nichts fabriziert/rekonstruiert** — kein Platzhalter, der Inhalt suggeriert.
6. **Operator-only, fail-closed.** Bei fehlendem/abgelehntem Operator-Token (PRD §7): **kein** Teildaten-Render, sondern ein expliziter Berechtigungs-Zustand (§8). Nie ausgegraute „echte" Events andeuten.

---

## 6. Browse-UI — CYP-41 (ST7): Master-Detail + Korrelations-Drilldown

Historisches Browsen über `query(filter, page)`. **Kern-Mehrwert ist der Drilldown**, nicht der Zeilen-Viewer.

### 6.1 Layout (Master-Detail, reuse CYP-17-Muster)

```
┌─────────────── Filterleiste (Agent · Typ · Severity · Zeitfenster · correlationId) ───────────────┐
├──────────────────────────────────────────┬───────────────────────────────────────────────────────┤
│ Master: virtualisierte Event-Tabelle      │ Detail: voller Event (§6.2)                            │
│  (gepaged über seq, §4-Zeilen)            │  ─ Wirbelsäulen-Felder + detail-JSON (aufklappbar)     │
│  ▌ row …                                  │  ─ [ Zeig den ganzen Lauf ] → §6.3                      │
│  ▌ row …  (gewählt)                       │                                                        │
│  … Paging/„mehr laden" (kein Voll-Load)   │                                                        │
└──────────────────────────────────────────┴───────────────────────────────────────────────────────┘
```

- **Wide (Two-Pane):** Tabelle (start) + Detail (main). **Narrow (Single-Pane):** Tabelle **oder** Detail; Auswahl navigiert zum Detail, **Zurück** zur Tabelle (reuse `comm_back`). Breakpoint an Panel-Innenbreite (Vorschlag < ~560dp → Single-Pane; Tabelle ist breiter als Comm).
- **RTL:** start/end; Filterleiste und Spalten spiegeln.
- **Virtualisiert/gepaged:** bei dem Volumen **nie Voll-Load** — `query(filter, page)`, Endlos-Scroll oder „mehr laden". Paging stabil über `seq` (PRD ST5).

### 6.2 Filter & Detail

- **Filterachsen (genau die Index-/Wirbelsäulen-Felder, PRD §4):** `agentId`, `type` (Mehrfachauswahl über Gruppen/Einzeltypen), `severity` (≥-Schwelle oder Set), Zeitfenster, `correlationId`. Filter wirken serverseitig (`EventFilter`), nicht clientseitig nachträglich (sonst falsche „Vollständigkeit").
- **Aktiver-Filter-Ehrlichkeit:** sichtbar machen, **dass** gefiltert ist (Chips + „Filter aktiv — Teilmenge"), damit ein leeres/kurzes Ergebnis nicht als „nichts passiert" fehlgelesen wird.
- **Detail:** alle Wirbelsäulen-Felder + `detail`-JSON aufklappbar (Pretty-Print, monospace, kopierbar). `sourceTs` getrennt (§5.2). Severity/Typ als Text-Labels (nicht nur Icon).

### 6.3 Korrelations-Drilldown — „Zeig den ganzen Lauf" (der Kern)

Aus einem Event eine Aktion **„Zeig den ganzen Lauf"** (`event_drilldown_show_run`): lädt **alle** Events derselben `correlationId` (bzw. `sessionId` als Fallback/Alternative) in **`seq`-Reihenfolge** — als **Lauf-Timeline**:

- **Lauf-Timeline** = dieselbe Event-Zeile (§4), chronologisch nach `seq`, mit `turn.*`/`tool.*`/`hook.fired`/`context.usage`/`error.*`/`result.final` **im Kontext zueinander** — „die Token-Schwellen und Hook-Auslösungen drumherum" (PRD §1/§6).
- **Token-Verlauf** als Band-Stützstellen markiert (§5.4) — nie als kontinuierliche Kurve.
- **Lücken-Ehrlichkeit:** liegt ein `log.dropped` im Lauf-Fenster, erscheint die **Gap-Zeile** (§5.3) **innerhalb** der Lauf-Timeline — der Lauf wird **nicht** als „vollständig" dargestellt, wenn er es nicht ist. (Kritisch: „zeig den ganzen Lauf" darf keinen vollständigen Lauf *vortäuschen*.)
- **`correlationId`-Herkunft offen (PRD Appendix, Dev-Ask c):** pro Turn / pro Aufgabe / = `sessionId`-bis-Compact. Die UI ist davon **unabhängig** (sie korreliert über das gelieferte Feld), benennt aber im Drilldown-Header **welche** Achse korreliert wurde (`correlationId` vs `sessionId`), damit der Operator den Scope versteht.

---

## 7. Live-Tail-UI — CYP-42 (ST8): eigenes Fenster, Pause

Echtzeit-Strom über `subscribe(filter)` (WS, gleiches Muster wie `comm/CommWsClient.kt`). **Bewusst getrennt** vom Browse (PRD §6).

### 7.1 Layout

```
┌── Kopf: ● Live  ·  Filter (Agent/Typ/Severity)  ·  [ ⏸ Pause ] ──────────────┐
├──────────────────────────────────────────────────────────────────────────────┤
│ Auto-scroll Strom neuer Event-Zeilen (§4), neueste unten                       │
│  ▌ row … (live)                                                                │
│  ▌ row …                                                                       │
└──────────────────────────────────────────────────────────────────────────────┘
```

- Neue Events streamen ein (Append nach `seq`), Auto-Scroll am unteren Rand. Zeile = §4 (identisch zu Browse → Reuse).
- Filter wie Browse-Teilmenge (Agent/Typ/Severity) — als `EventFilter` an `subscribe` (serverseitig), keine Inhalts-Filterung clientseitig.

### 7.2 Pause — die Disclosure-kritische Mechanik

- **Pause friert die Ansicht ein**; der Client **puffert weiter** (kein Verlust, PRD §6). Ein **Zähler** zeigt ehrlich „⏸ Pausiert — N gepuffert" (`event_tail_paused`, `event_tail_buffered_count`).
- **Resume holt nach** (gepufferte Events in `seq`-Ordnung anhängen), dann wieder live.
- **Verbindungs-/Pause-Ehrlichkeit (reuse CYP-17 §5-Prinzip):**
  - **Pausiert ≠ live:** Live-Indikator (●) **aus**/„pausiert", solange eingefroren — die Ansicht **nie** als aktuell/live ausgeben, während sie eingefroren ist.
  - **WS getrennt:** ehrlicher Banner „Verbindung getrennt — Stand HH:MM" (reuse `comm_status_offline`-Prinzip; Token `event.connection.offline`); Strom **nicht** als live darstellen. Auto-Reconnect wie Comm-WS.
- **Pufferschranke:** der **Client**-Puffer ist endlich. Läuft er bei langer Pause über, gilt dieselbe Honesty-Regel wie serverseitig: **sichtbar** kappen (Gap-Hinweis „Puffer voll — älteste pausierte Events verworfen", `event_tail_buffer_overflow`), **nie still**. (Analog zu `log.dropped`, nur clientseitig.)

### 7.3 Dedupe & Idempotenz (reuse CYP-17 §6)

- **Dedupe über `Event.id`/`seq`** — nie über (ts,type,agent) raten. Reconnect: ab letztem bekannten `seq` nachladen/mergen → keine Dubletten, keine Lücke.
- **Ordering strikt nach `seq`** (Tiebreaker bei Bedarf `id`).

---

## 8. Zustände & Disclosure (verbindlich, beide UIs)

| Zustand | Darstellung | Disclosure-Regel |
|---|---|---|
| **Lädt** (Browse-Seite) | Skeleton/Spinner | nicht als „leer/keine Events" zeigen |
| **Leeres Ergebnis** | Empty-State (`event_empty`) | bei aktivem Filter: „Filter aktiv — Teilmenge" (nicht „nichts passiert") |
| **Live verbunden** | ●-Indikator | nur wenn WS wirklich offen |
| **Pausiert** (Live-Tail) | „⏸ Pausiert — N gepuffert" | Ansicht **nie** als live ausgeben |
| **Reconnecting/Offline** | ehrlicher Banner mit Zeitstempel | Strom/Timeline nicht als live/aktuell |
| **`log.dropped` / Lücke** | hervorgehobene **Gap-Zeile** in `seq`-Position | nie still überspringen; Lauf nie als vollständig vortäuschen |
| **Client-Puffer voll** (Live-Tail) | Gap-Hinweis | sichtbar kappen, nie still |
| **Kein Operator-Token** | expliziter Berechtigungs-Zustand (`event_access_denied`) | **fail-closed**: kein Teildaten-Render, keine ausgegrauten Events |
| **Unbekannter Typ** | Roh-String + neutrales Icon | nie verschlucken |

**Kern-Disclosure (Zusammenfassung):**
1. **Vollständigkeit ehrlich:** Lücken (`log.dropped`, Pufferüberlauf) **immer sichtbar**; Sampling (`context.usage`) als solches gekennzeichnet.
2. **Zeit/Ordnung ehrlich:** `seq` ordnet; `ts` ist Anzeige; `sourceTs` ist „beobachtet".
3. **Live ehrlich:** pausiert/getrennt nie als live.
4. **Zugriff ehrlich:** Operator-only, fail-closed; keine Teildaten.
5. **Inhalt ehrlich:** metadaten-only; nichts erfinden, was nicht im `detail` steht.

---

## 9. Tokens & i18n

- **Tokens:** überwiegend **Reuse** — Identität aus CYP-14 (`color-coding-tokens.json`), Severity aus CYP-12 (`state-tokens.json`), Verbindungs-/Pending-Muster aus CYP-17 (`comm-panel-tokens.json`). **Nur wenige neue** Event-Log-Tokens (Severity-Rail-Mapping, Gap-Zeile, Korrelations-Chip, Pause/Puffer, Live-Tail-Connection) in `docs/design/event-log-tokens.json`.
- **i18n:** `compose.resources` / Underscore-Real-Keys, Platzhalter positional (`%1$s`) — `docs/design/event-log-keys.md`.

> **⚠ Shared-Key-Drift:** Beide Sets landen in `:app:shared`-Resources → das **konsumierende Modul (CYP-41/CYP-42-Impl) muss re-syncen**, sonst bricht ein geteilter Check. **Lieferung/Merge mit der jeweiligen Impl timen** — nicht isoliert.

---

## 10. Offene Punkte / Dev-Asks (über PO)

1. **`Event`-DTO-Sicht:** PO-final = `Event` in `:core`, von der UI direkt genutzt (PRD Appendix Dev-Ask a). Bestätigt — diese Spec geht davon aus. Falls doch ein separates Read-DTO käme, ändert sich nur das Mapping, nicht das Layout.
2. **`correlationId`-Herkunft** (PRD Dev-Ask c): pro Turn / pro Aufgabe / = `sessionId`-bis-Compact. Prägt den Drilldown-Mehrwert (§6.3) — die UI ist feld-unabhängig, benennt aber die korrelierte Achse. **PO/Backend-Entscheidung.**
3. **`EventFilter`/`Page`-Vertrag** (Felder, Page-Cursor über `seq`) in `:core` — existiert noch nicht; vor CYP-41/42-Impl als Vertrag fixieren (analog zu fehlenden WS-Frame-DTOs in CYP-17).
4. **WS-Auth-Gating** des `/ws/events`-Upgrades (Operator-Token + Origin, PRD §7) ist Backend (ST6); die UI muss den fail-closed-/Reject-Fall **ehrlich** rendern (§8) — Vertrag (Reject-Signal) abstimmen.
5. **Severity-Defaults pro Typ** (PRD §8 `severityDefaults`) sind Config/Backend; die UI färbt nur die gelieferte Severity — keine UI-seitige Severity-Ableitung.
6. **Live-Tail-Client-Pufferschranke** (§7.2): konkreter Wert mit Dev kalibrieren (Honesty-Regel steht; Zahl ist Tuning).
