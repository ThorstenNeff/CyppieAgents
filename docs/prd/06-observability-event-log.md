# PRD 06 — Observability: Event-Log

> Status: Entwurf v0.1 (zur Review beim PO) · Quelle: `06-Observability-Event-Log.md` (Decision Record)
> Querbezüge: `02-Technische-Spezifikation` §15 (`MessageStore`-Naht), `05-MVP-Scope-Entscheidungen` §2 (Mediation)
> Verifiziert gegen realen Code (Stand develop `51cef1f`): `:server` Connector/Mediation, `:core` DTOs, `:app:shared` UIs.
> **Provisorischer Branch/Key:** `feature/CYP-34-prd-event-log` — **CYP-34 ist ein Platzhalter**, bis der PO das Epic anlegt; Branch ggf. auf den echten Key umbenennen.

---

## 1. Problem & Ziel

Beim Belastungstest der Plattform müssen wir **das Verhalten der Agenten unter Last auswerten können**:
ausgelöste Hooks, Token-Grenzen, Tool-Aktivität, Fehler/Unterbrechungen, Lebenszyklus. Heute ist
das nur durch Greppen unstrukturierter Logs möglich — nicht aggregierbar, nicht korrelierbar.

**Ziel:** Ein strukturiertes, maschinell auswertbares **Event-Log** mit kontrolliertem Typ-Vokabular,
totaler Ordnung und zwei Lese-Oberflächen (historisches Browsen mit Korrelations-Drilldown + Live-Tail),
das **die Messung nicht verfälscht** (kein Observer-Effekt auf dem heißen Mediations-Pfad).

**Erfolgskriterium:** Aus dem Log lässt sich ein kompletter Agentendurchlauf rekonstruieren
(„zeig den ganzen Lauf" über `correlationId`/`sessionId`), inkl. der Token-Schwellen und Hook-Auslösungen
drumherum — als **Verhaltensanalyse**, nicht als Zeilen-Viewer.

---

## 2. Non-Goals (explizit)

- **Keine Hub-Inhalte im Log.** Es werden **nur Metadaten über Ereignisse** persistiert — *dass* um
  14:03 eine Nachricht von `frontend` an `po` floss (Typ/Kanal/Richtung), **nie** *was* drinstand.
  Diese Grenze ist **strukturell durchzusetzen** (siehe §4 Ingestion + AC in Appendix A), nicht nur per Disziplin.
- **07 (Scanner/Warden) wird hier NICHT gebaut.** Das Log wird nur so gebaut, dass 07 später
  **additiv** andockt (Enum offen für `stall.*`-Typen; `subscribe`-Bus auch für reagierende Konsumenten).
- **Kein Multihost im MVP.** Ein einziger Stempler (eine Maschine, eine Uhr). Die `TimeSource`-Abstraktion
  bereitet Multihost vor, aber Timeserver/logische Uhren sind **nicht** Teil dieses MVP.
- **Keine GUI für Konfiguration.** Knöpfe sind **im Code/Config** änderbar (`platform.config.json`),
  kein Operator-UI für Banding-% o. Ä.
- **Keine Retention/Rotation-Automatik** im MVP (einfach halten; nur dokumentierter Schalter).

---

## 3. Architektur

### 3.1 Überblick

```
 stream-json (stdout)                                    Hooks (PreCompact, …)
        │                                                       │ schreiben Zeile
        ▼  ClaudeCodeConnector reader loop                      ▼
  EventMasking.mask(parsed)  ◄── Gate #3                   Spool-Datei (append-only, JSONL)
        │  (single source, BEVOR der Strom forkt)               │ getailt vom Mediator
        ├──────────────► _events.emit  → /ws/agent (UI)         │
        ├──────────────► router.onResult → Hub (bei result)     │
        │                                                       │
        └──► EventProjector (StreamJsonEvent → Event-Metadaten) ◄┘
                    │ offer() — NICHT blockierend (Observer-Effekt!)
                    ▼
         [ bounded In-Memory Queue ]
                    │ einzelne Writer-Coroutine, Batch-Drain
                    ▼
              EventSink (SQLite, WAL, Batch-Insert)
                 ▲ append (Stempelung ts+seq via TimeSource)
                 ├── query(filter, page)  → /api/events  (Browse)
                 └── subscribe(filter)     → /ws/events   (Live-Tail)
```

### 3.2 `EventSink` — die einzige Ordnungs- und Zeit-Autorität (swappable)

Dieselbe Naht-Philosophie wie `MessageStore` (`server/.../comm/MessageStore.kt`, 02 §15): die SQLite-Bindung
lebt hinter einem **schmalen Interface**; Aufrufer kennen nie SQL. **Stempelung passiert im `append`-Pfad**,
nicht bei den Aufrufern — so bleibt die totale Ordnung erhalten, egal welche Komponente schreibt.

```kotlin
interface EventSink {
    suspend fun append(event: Event)                              // Schreibseite (intern über Queue)
    suspend fun query(filter: EventFilter, page: Page): EventPage // Browsen (gepaged)
    fun subscribe(filter: EventFilter): Flow<Event>               // Live-Tail
}
```

> **Delta zu `MessageStore`:** `MessageStore.append` ist *synchron* und flusht bei jedem Append den
> **vollen Snapshot** als JSON. Das wäre für das Event-Log unter Last fatal (O(n) je Event). Deshalb ist
> `EventSink` **asynchron + Queue-entkoppelt + SQLite-Batch** (§3.3). Die *Philosophie* (eine Naht, In-Memory-
> Test-Double + persistente Impl) wird gespiegelt, die *Mechanik* unterscheidet sich bewusst.

**MVP-Impl:** SQLite, eigene `events`-Tabelle (getrennt von Hub-Daten), **WAL-Modus an**, **Batch-Inserts**.
Hinter dem Interface jederzeit gegen Redis Streams / Postgres austauschbar (Exposé §8), ohne Aufrufer-Änderung.
Treiberwahl ist Implementierungsdetail hinter der Naht; **Empfehlung MVP:** `sqlite-jdbc` (xerial) für volle
WAL/Batch-Kontrolle bei kleinster Abhängigkeit; SQLDelight als typisierte Alternative (konsistent mit 02 §15
Upgrade-Pfad). **Treiber/Version pinnen.** `:server`-only (JVM); kein KMP-Zwang für die Persistenz.

### 3.3 Entkoppelter Puffer (nicht verhandelbar)

Das Log wird **für** den Belastungstest gebaut; synchrones Wegschreiben auf dem heißen Pfad würde das
Gemessene verfälschen. Zwischen „Event entsteht" und „Event liegt auf Platte" sitzt eine **begrenzte
In-Memory-Queue** (z. B. `Channel(capacity)`), die eine **einzelne Writer-Coroutine** in Batches in den
`EventSink` leert. Der Projector wirft den Event in die Queue (quasi gratis) und liest weiter.

**Back-Pressure-Regel (Sicherheits-/Korrektheits-relevant):** Der Mediator-Pfad darf **nie blockieren**
(sonst Observer-Effekt). Bei voller Queue wird **nicht blockiert**, sondern **kontrolliert verworfen**
(drop-newest), **und** ein Zähler erhöht, der als eigenes Meta-Event/Metrik `log.dropped` (mit Anzahl)
sichtbar wird. **Kein stilles Abschneiden** — eine Lücke im Log muss erkennbar sein, sonst lügt die
Telemetrie über Vollständigkeit. (Standing-Gate des Reviewers: „No silent caps — log what was dropped".)

### 3.4 Hauptquelle: der Mediator (ein Tap-Punkt)

Die Events entstehen fast alle beim Mediator, weil er ohnehin jeden stream-json-Strom liest. Der **einzige
Tap-Punkt** ist die Reader-Schleife in `ClaudeCodeConnector` (`server/.../connector/ClaudeCodeConnector.kt`),
**direkt nach `EventMasking.mask(parsed)`** — die **eine** Stelle, bevor der Strom zu `_events.emit` (UI-WS)
und `router.onResult` (Hub) forkt:

```kotlin
val masked = EventMasking.mask(parsed)     // Gate #3 — bereits maskiert
// NEU: Observability-Tap (metadaten-only, non-blocking)
eventProjector.onStreamEvent(agentId, masked)   // offer() in die Queue, kein await auf Platte
_events.emit(masked)                        // → /ws/agent (UI)
if (masked is ResultEvent) { router.onResult(masked); … }   // → Hub
```

`eventProjector` wird wie `registry`/`router`/`turnQueue` **konstruktor-injiziert** (Wiring in
`BootOrchestrator.boot()`). Lifecycle-Events (`agent.spawned/stopped/restarted`), die nicht im Strom liegen,
ruft der Connector/Boot direkt auf demselben Projector auf. Hook-Events kommen über die Spool (§3.6).

### 3.5 Projektion StreamJsonEvent → Event (metadaten-only, strukturell inhaltsfrei)

Der `EventProjector` bildet die realen `:core`-Typen (`server/.../model/StreamJsonEvent.kt`) auf das
kontrollierte Vokabular (§ Typen) ab und **projiziert nur Nicht-Inhalts-Felder** ins `detail`:

| Quelle (real) | Event-Typ | `detail` (nur Metadaten) |
|---|---|---|
| `UserTurn` injiziert | `turn.start` | — |
| `ResultEvent` | `turn.end`/`result.final` | `durationMs`, `numTurns`, `totalCostUsd`, `subtype`, `isError` |
| `AssistantEvent` enthält `ToolUseBlock` | `tool.call` | `toolName`, `toolUseId` (**NICHT** `input`) |
| `UserEvent`/`ToolResultBlock` | `tool.result` | `toolUseId`, `isError` (**NICHT** `content`) |
| `ResultEvent.usage` über Bande | `context.usage` | `bandPct`, `inputTokens`, `outputTokens`, `cache*` (Zahlen, kein Text) |
| `ResultEvent.isError`/Subtyp | `error.model`/`error.ratelimit` | Fehlerklasse, kein Roh-Text |
| `RateLimitEvent` | `error.ratelimit` | `rateLimitInfo`-Felder (Zahlen) |
| Prozess-Ende | `process.exit` | `exitCode` |
| Hub-Vermittlung (im Router) | `comm.sent`/`comm.received` | `from`, `to`/`channel`, `kind` (**ohne `body`**) |

> **Strukturelle Durchsetzung des Non-Goals (§2):** Die Projektion liest die Typen feldweise und nimmt
> **niemals** `TextBlock.text`, `ThinkingBlock.thinking`, `ToolUseBlock.input`, `ToolResultBlock.content`
> oder `Message.body` in `detail` auf. **Necessary-but-not-sufficient:** Selbst Metadaten können Inhalt
> durchsickern (ein Tool-Name, ein Dateipfad). Deshalb **zusätzlich**: jedes frei-textige `detail`-Feld läuft
> durch `SecretMasker.mask(...)`. Die Maskierung am Tap-Punkt (§3.4) ist Gürtel; metadaten-only ist Hosenträger.

### 3.6 Hook-Ingestion über Spool

Hooks (PreCompact/PostCompact/SessionStart) sind Shell-Skripte **außerhalb** des stream-json-Kanals und
**schreiben nie direkt in die DB**. Sie hängen eine Zeile (JSONL, mit `sourceTs`) an eine **Spool-Datei**.
Der Mediator **tailt** die Spool, reicht den Eintrag in denselben `EventProjector`/`append`-Pfad, wo er —
wie alle Events — seinen maßgeblichen `ts` + `seq` erhält (`sourceTs` bleibt informativ erhalten).
Vorteile: eine Quelle (der Mediator), Reihenfolge/Zeit in einer Hand, robuster gegen Prozess-Ausfall als ein
direkter Endpoint-Call. Spool wird **idempotent** verarbeitet (Offset/Marker), damit ein Re-Tail nach Neustart
keine Duplikate erzeugt.

---

## 4. Event-Schema

Eine **Wirbelsäule** (gemeinsame Felder, danach gefiltert) plus typisiertes `detail` (im Detail-View aufgeklappt).
Als `@Serializable` im `:core`-Modul (oder server-intern, falls die UI nur das gepagte DTO sieht — siehe Story ST5).

| Feld | Typ | Bedeutung |
|---|---|---|
| `id` | ULID (String) | zeitlich sortierbar, ideal für append-only + Paging |
| `ts` | Long (epoch ms) | **maßgeblich**, im `append`-Pfad via `TimeSource.now()` gesetzt |
| `seq` | Long | monoton (`TimeSource.nextSeq()`) → **totale Ordnung**, auch bei gleicher ms / Uhr-Sprung |
| `sourceTs` | Long? | beobachtete Zeit der Quelle (Hook) — **informativ**, nicht ordnungsbildend |
| `agentId` | String | Verursacher |
| `teamId` | String | Team (Projekt-Token, 05 §3) |
| `sessionId` | String? | Claude-Code-Session — Korrelation auch über eine Compaction hinweg |
| `correlationId` | String? | welcher Arbeitsdurchlauf — für „zeig den ganzen Lauf" |
| `type` | Enum | **kontrolliertes Vokabular** (§ Typen), kein Freitext → aggregierbar |
| `severity` | Enum (`debug`/`info`/`warn`/`error`) | Schnellfilter beim Belastungstest |
| `detail` | JSON | typspezifische, **inhaltsfreie** Nutzlast (§3.5) |

> **Indizes (SQLite):** mindestens auf `(seq)` (Totalordnung/Paging), `(agentId, seq)`, `(type, seq)`,
> `(correlationId)`, `(sessionId)` — die Filterachsen der Browse-UI (§ UI).

### 4.1 Typen-Enum (kontrolliertes Vokabular)

| Gruppe | Typen | Quelle |
|---|---|---|
| **Agententätigkeit** | `turn.start`, `turn.end`, `tool.call`, `tool.result`, `file.changed`, `result.final` | stream-json-Strom |
| **Token-Grenzen** | `context.usage` (Regel §4.2), `compact.triggered`, `compact.completed` | Mediator (Usage) |
| **Hooks** | `hook.fired` (Name: PreCompact/PostCompact/SessionStart + Ausgang) | Spool (§3.6) |
| **Fehler/Unterbrechung** | `error.model`, `error.tool`, `error.ratelimit`, `process.exit`, `timeout`, `ws.disconnect` | Mediator |
| **Lebenszyklus** | `agent.spawned`, `agent.restarted`, `agent.stopped`, `session.recycled` | Mediator/Boot |
| **Kommunikations-Metadaten** | `comm.sent`, `comm.received` (from/to/channel/kind, **ohne Inhalt**) | Mediator/Router |

> `type` ist ein **Enum** (nicht Freitext) — nur dann aggregierbar statt greppbar. Liste erweiterbar:
> **07** ergänzt additiv `stall.suspected`, `nudge.sent`, `stall.recovered`, `stall.escalated`. Unbekannter
> Typ beim Dekodieren wird tolerant behandelt (kein Crash), analog `TolerantToolsSerializer` in `:core`.

### 4.2 `context.usage` — Sonderregel (hochfrequent)

`context.usage` wäre potenziell jeder Turn jedes Agenten und würde die DB dominieren. Deshalb:

- Der **Füllstand** wird **pro Agent In-Memory** gehalten; der Mediator berechnet ihn aus den Usage-Zahlen.
  **Datenquelle (real verifiziert):** `ResultEvent.usage: JsonObject?` (+ `totalCostUsd`/`numTurns`) am Turn-Ende
  — die heute im DTO vorhandene Usage-Fläche. (Falls intra-Turn-Usage je Assistant-Message gewünscht ist,
  fehlt sie aktuell im `AssistantEvent`-DTO und müsste **additiv** ergänzt werden — **Risiko-Notiz**, kein MVP-Blocker.)
- **Persistiert** wird ein `context.usage`-Event **nur** beim **Überschreiten einer 10%-Bande** (10/20/…/100)
  **und zusätzlich** beim Erreichen der konfigurierten **Compact-Grenze** (`CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`, z. B. 75).
- **Bandbreite konfigurierbar** (Default 10%) — passt zu „im Code/Config änderbar".

So bleibt die Token-Dynamik sichtbar, ohne dass die Telemetrie unter Last das Gemessene überlagert.

---

## 5. Zeit & Ordnung

**MVP — ein einziger Stempler.** Mediator und Hook-Skripte laufen auf **derselben Maschine** und teilen eine
Uhr. Das echte Problem ist, dass Wall-Clock **nicht monoton** ist (NTP-Korrektur/Rückwärtssprung). Antwort:
der **`append`-Pfad** setzt `ts` **bei Aufnahme** (`TimeSource.now()`), und `seq` (`TimeSource.nextSeq()`,
monoton) garantiert die **totale Ordnung** unabhängig von Uhr-Sprüngen **und** davon, *welche* Komponente
schreibt (Mediator, später Scanner/Warden). `nextSeq()` ist **thread-safe** (atomar), da mehrere Quellen
und die Writer-Coroutine konkurrieren.

```kotlin
interface TimeSource { fun now(): Long; fun nextSeq(): Long }
```

**Multihost-ready (vorbereitet, nicht gebaut):** Sobald Events von mehreren Hosts kommen, wird der
Einzel-Stempler durch eine andere `TimeSource` (Timeserver/logische/hybride Uhr) ersetzt — **ohne
Schema-Bruch**, weil `sourceTs` + `ts` + `seq` von Anfang an im Schema stehen.

---

## 6. UI — zwei getrennte Oberflächen

Beide als `commonMain`-Compose-Fenstertypen (analog `comm/CommPanel.kt` + `CommViewModel`/`StateFlow`-Muster
in `:app:shared`), gespeist über die `EventSink`-Naht (REST `query` + WS `subscribe`).

**Browsen (historisch, Master-Detail).** Master = filter-/sortierbare, **gepagte/virtualisierte** Tabelle über
den Wirbelsäulen-Feldern (Agent, Typ, Severity, Zeitfenster, `correlationId`) — bei dem Volumen wird nicht
alles geladen, sondern über `query(filter, page)`. Detail = voller Event inkl. `detail`-Payload.
**Korrelations-Drilldown** ist der Kern-Mehrwert: aus einem Event „zeig den ganzen Durchlauf" → alle Events
derselben `correlationId`/`sessionId` in `seq`-Reihenfolge, inkl. Token-Schwellen und Hook-Auslösungen drumherum.

**Live-Tail (Echtzeit, getrennt).** Mitlaufender Strom über `subscribe` (gleiches WS-Muster wie `comm/CommWsClient.kt`),
mit **Pause-Knopf** zum Inspizieren, während die Agenten an die Grenze gefahren werden. Bewusst eine **eigene**
Oberfläche, nicht mit dem Browsen vermischt. Pause friert die Ansicht ein, der Client puffert weiter und holt
beim Resume nach (kein Verlust). Idempotenz über `id`/`seq` (kein Doppeln bei Reconnect — wie Comm-WS).

---

## 7. Zugriff & Sicherheit (Reviewer-Vorgabe)

- **Das Event-Log ist Operator-only.** Es aggregiert **teamweite, agentenübergreifende** Metadaten; läse ein
  einzelner Agent es, wäre das ein **Cross-Agent-Metadaten-Leak** derselben Klasse wie der gefilterte
  `AclEvent` (CYP-18). Deshalb: `/api/events` und `/ws/events` sind **Operator-Token-gated** (wie `PUT /api/acl`),
  **fail-closed** ohne gültiges Operator-Token. Agenten-Token → `403`/Upgrade-Reject.
- **WS ≠ Browser-CORS:** der `/ws/events`-Upgrade muss am Server gegated werden (Origin + Token), Browser
  wenden CORS auf WebSockets nicht an (CYP-30). Regression über **echten Handshake** testen, nicht `client.get`.
- **Metadaten-only ist strukturell** (§3.5) + maskiert (§3.4). Verifikation per **Needle-Absence**: nach einem
  Lauf mit eingeschleustem Secret/Inhalt → Grep über DB-Datei/`/api/events`-Antwort/`/ws/events`-Strom = **absent**.
- **Keine Secrets in der Spool.** Hooks schreiben Metadaten + `sourceTs`, keinen Inhalt; Spool-Pfad nicht im Repo.

---

## 8. Konfig-Knöpfe

In `PlatformConfig` (`server/.../boot/PlatformConfig.kt`) ein neuer `events`-Block (Default-belegt, damit
bestehende Configs weiter laden — `encodeDefaults`/`explicitNulls=false` wie `CommJson`):

| Knopf | Default | Wirkung |
|---|---|---|
| `bandPct` | 10 | Bandbreite der `context.usage`-Persistenz (§4.2) |
| `compactPct` | 75 | zusätzliche Schwelle (`CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`) |
| `batchSize` | z. B. 64 | Writer-Coroutine Batch-Insert-Größe |
| `queueCapacity` | z. B. 4096 | Grenze der In-Memory-Queue (Drop-Politik §3.3) |
| `sinkPath` | z. B. `.cyppie/events.db` | SQLite-Datei (nicht im Repo) |
| `spoolPath` | z. B. `.cyppie/hooks.spool` | Hook-Spool-Datei |
| `severityDefaults` | je Typ | Default-Severity pro Event-Typ (§4) |
| `retention` | aus/simpel | (MVP einfach; nur Schalter) |

---

## 9. 07-Erweiterbarkeit (offen lassen, nicht bauen)

- **Enum offen:** neue Typen `stall.suspected`/`nudge.sent`/`stall.recovered`/`stall.escalated` sind additiv;
  tolerantes Dekodieren verhindert Brüche.
- **`EventSink.subscribe`-Bus dient Anzeige UND Automatik:** der 07-**Scanner** hängt als *reagierender*
  Konsument am selben Strom; der **Warden** schreibt seine Aktions-Events über denselben `append`-Pfad
  (gemeinsame Stempelung). Keine Sonder-Naht nötig — die `EventSink`-Naht trägt 07 bereits.
- **`TimeSource`/`EventSink` austauschbar** (Multihost / Redis-Postgres) ohne Schema- oder Aufrufer-Bruch.

---

## 10. Definition of Done (Epic)

- `EventSink`-Naht + In-Memory-Test-Double + SQLite(WAL/Batch)-Impl; Aufrufer kennen kein SQL.
- Entkoppelte Queue + Writer-Coroutine; Mediator-Tap **non-blocking**; Drop-Politik **sichtbar** (`log.dropped`).
- `TimeSource` stempelt im `append`-Pfad; **totale Ordnung** über `seq` unter nebenläufigen Schreibern bewiesen (Test).
- Vollständiges Typ-Enum; `context.usage`-Banding (konfigurierbar) persistiert nur Bandwechsel + Compact-Grenze.
- Mediator-Projektion verdrahtet; **metadaten-only strukturell** + maskiert; **Needle-Absence** bewiesen.
- Spool-Ingestion idempotent; `sourceTs` erhalten, `ts`/`seq` im `append` gesetzt.
- REST `/api/events` (gepaged, gefiltert) + WS `/ws/events` (subscribe) — **Operator-only, fail-closed**, echter Handshake-Test.
- Browse-UI (Master-Detail + Korrelations-Drilldown) + Live-Tail-UI (Pause) in `commonMain`.
- Test-Contract grün (Ordering, Drop-Sichtbarkeit, Observer-Effekt-Schranke, Metadaten-only, Restart-Durabilität).
- Auf `develop` gemergt (nur PO); `./gradlew check` grün; Kurz-Doku/Changelog aktualisiert.

---

## Appendix A — Jira-Story-Schnitt (Vorschlag; noch NICHT angelegt)

**Epic: „Observability: Event-Log"** — Telemetrie des Agentenverhaltens unter Last; *Metadaten, keine Hub-Inhalte*;
SQLite-MVP hinter swappabler `EventSink`-Naht; zwei Lese-UIs. Vorbereitet für 07 (nicht enthalten).

Dünne vertikale Stories, jede für sich demonstrierbar. Größe S≈1–2 T, M≈3–5 T, L≈1–1,5 Wo.

| # | Story | Akzeptanzkriterien (testbar) | Größe | Hängt ab von | Owner |
|---|---|---|---|---|---|
| **ST1** | **`EventSink`-Naht + Schema + Queue + `TimeSource` + SQLite(WAL/Batch)** | `append` schreibt off-thread; `query` liefert nach `seq` geordnet; **nebenläufige Schreiber → totale Ordnung** (Test mit N Coroutinen, keine Dublette/Lücke in `seq`); **Queue voll → drop-newest, `log.dropped`-Zähler steigt, Mediator-Pfad blockiert nicht** (Test); WAL aktiv; **Restart**: Events überleben Neustart; In-Memory-Double existiert | L | – | Backend |
| **ST2** | **`context.usage`-Banding** | Synthetische Usage-Folge → Event **nur** bei 10%-Bandwechsel + Compact-Grenze persistiert; **Band konfigurierbar** (20% → anderes Verhalten); Füllstand In-Memory pro Agent | M | ST1 | Backend |
| **ST3** | **Mediator-Ingestion (stream-json → Event-Metadaten)** | Tap nach `EventMasking.mask`; Replay-Fixture realer `StreamJsonEvent`s → korrekte Typen (`turn.*`/`tool.*`/`result.final`/`error.*`/`process.exit`); **Needle-Absence**: kein `TextBlock.text`/`tool.input`/`tool_result.content`/`Message.body` im `detail`; Lifecycle-Events (`agent.spawned/stopped`) erzeugt | M/L | ST1 (+ST2 für `context.usage`) | Backend |
| **ST4** | **Spool-Hook-Ingestion** | Hook schreibt Spool-Zeile mit `sourceTs` → `hook.fired` mit erhaltenem `sourceTs` und im `append` gesetztem `ts`/`seq`; **idempotent** (Re-Tail nach Neustart → keine Dubletten); Name + Ausgang im `detail` | M | ST1 | Backend |
| **ST5** | **REST `/api/events` (gepaged, gefiltert)** | Filter Agent/Typ/Severity/Zeitfenster/`correlationId`; Paging stabil über `seq`; **Operator-only**: Agenten-Token → `403`; fehlendes Token → `401` (fail-closed) | M | ST1 | Backend |
| **ST6** | **Live-Tail WS `/ws/events` (`subscribe`)** | **Echter Handshake**-Test (nicht `client.get`); appended Event wird live gepusht; Filter wirkt; **Operator-only**, Agenten-Token-Upgrade rejected; Origin am Upgrade gegated | M | ST1, ST5 (Auth) | Backend |
| **ST7** | **Browse-UI (Master-Detail + Korrelations-Drilldown)** | Filter/Sort in Master; Detail klappt `detail`-JSON auf; **„zeig den ganzen Lauf"** lädt alle Events einer `correlationId`/`sessionId` in `seq`-Reihenfolge; gepaged/virtualisiert (kein Voll-Load) | L | ST5 | UIUX/Dev |
| **ST8** | **Live-Tail-UI (eigenes Fenster, Pause)** | Live-Zeilen streamen; **Pause** friert Ansicht ein, Client puffert weiter, **Resume** holt nach ohne Verlust; Dedupe über `id`/`seq`; getrennt von Browse | M | ST6 | UIUX/Dev |
| **ST9** | **Config-Knöpfe (`events`-Block in `PlatformConfig`)** | Default-belegt (bestehende Config lädt weiter); `bandPct`/`batchSize`/`queueCapacity`/`sinkPath`/`spoolPath`/`severityDefaults` greifen; Load-Test | S | ST1, ST2 | Backend |
| **ST10** | **Test-Contract (Querschnitt-Evidenz)** | Ordering unter Last (totale Ordnung), Drop-**Sichtbarkeit** (nicht still), **Observer-Effekt-Schranke** (`append`/Tap non-blocking, Messung), **Metadaten-only Needle-Absence** end-to-end, Restart-Durabilität — als wiederholbare Suite | M | alle | Tester |

**Schnitt-Logik:** ST1 ist die Wirbelsäule (Bus + Zeit + Persistenz). ST2/ST3/ST4 füllen Events ein
(jede für sich per Fixture/Replay demonstrierbar, ohne UI). ST5/ST6 öffnen die Lese-Naht (REST/WS). ST7/ST8
sind die zwei UIs (parallelisierbar, sobald ST5/ST6 stehen). ST9 zentralisiert die Knöpfe. ST10 ist die
Querschnitts-Evidenz, die die „passing ≠ functional"-Eigenschaften beweist. **Reihenfolge:**
ST1 → (ST2 ∥ ST3 ∥ ST4) → (ST5 → ST6) → (ST7 ∥ ST8), ST9 begleitend, ST10 fortlaufend.

**Offene PO-Entscheidungen:** (a) `Event`-DTO in `:core` (von UI direkt genutzt) **oder** nur ein gepagtes
Read-DTO in `:server` (UI sieht nie das Schreib-Schema)? (b) SQLite-Treiber `sqlite-jdbc` vs. SQLDelight?
(c) `correlationId`-Herkunft: pro Turn neu, pro Aufgabe (PO-Auftrag) stabil, oder = `sessionId` bis Compact?
— prägt den Drilldown-Mehrwert in ST7.
