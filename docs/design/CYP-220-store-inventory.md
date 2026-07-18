# CYP-220 — Store-Inventur (Messung, Stand develop `f7916cdf`)

> Owner: UIUX-Designer · Auftrag: PO 2026-07-18 (PL-Vorlauf zu Postgres/BYODB) · **Reine MESSUNG.**
>
> **Grenze, verbindlich:** Dieses Dokument inventarisiert **was heute im Code steht**. Es entscheidet
> **nichts** über Datenmodell-Form und **nichts** darüber, was „bring your own DB" einem Kunden verspricht —
> das entscheidet der Auftraggeber. Aufgetauchte Vertrags-/Modellfragen stehen in §5 **als Fragen**,
> nicht als Vorschlag. Jede Zeile ist mit `datei:zeile` belegt; nicht Belegtes ist als unsicher markiert.

## 0. Das Ergebnis in drei Zahlen

| | |
|---|---|
| **44** Stores insgesamt (server + client) | keine einzelne Datenbank, eine **Flotte** |
| **10** davon haben eine Postgres-Implementierung + Flyway-Migration | der „austauschbare" Teil |
| **8** SQLite-Tabellen haben **keine** Postgres-Entsprechung | der Teil, den BYODB heute **nicht** abdeckt |

Das ist der zentrale Messwert: „Postgres statt SQLite" ist heute **kein Schalter**, sondern gilt für
einen **Teilbestand**. Was außerhalb liegt, liegt nicht am Rand — darunter Hub-Schlüssel, Rollen,
Nachrichten und Token-Verbrauch.

## 1. Stores mit Postgres-Pfad (Interface + SQLite + Pg + MigrationGate)

Diese **zehn** sind die durchgezogene Bahn: Interface → `Sqlite*` **und** `Pg*` → `MigrationGated*`.
Gegengezählt: **10** `Pg*`-Store-Impls (`find -name "Pg*.kt"` = 11 Dateien minus `boot/PgStoreRouting.kt`,
das kein Store ist) und **10** Flyway-Verzeichnisse — die beiden Zählungen decken sich exakt.

| Store | Pfad | Backing heute | Pg-Migration |
|---|---|---|---|
| `SessionStore` | `server/connector/SessionStore.kt:33` | SQLite `session-store.db` (`BootOrchestrator.kt:467`) | `session/V1__session.sql:4` |
| `EventSink` | `server/events/EventSink.kt:93` | SQLite `config.events.sinkPath` (`PlatformWiring.kt:307`) | `eventlog/V1__event_log.sql:5` |
| `AgentEventStore` | `server/agentevents/AgentEventStore.kt:138` | SQLite `.cyppie/agent-events.db` (`PlatformWiring.kt:315`) | `agentevents/V1__agent_events.sql:4` |
| `DeliveryLog` | `server/comm/SqliteDeliveryLog.kt:14` | SQLite `.cyppie/delivery-log.db` (`PlatformWiring.kt:301`) | `delivery/V1__delivery.sql:5` |
| `ChannelShareStore` | `server/comm/ChannelShareStore.kt:38` | SQLite `channel-shares.db` (`BootOrchestrator.kt:306`) | `channelshare/V1__channel_share.sql:5` |
| `ReportStore` | `server/report/ReportStore.kt:25` | SQLite `reports.db` (`BootOrchestrator.kt:936`) | `report/V1__report.sql:5` |
| `ProjectRegistry` | `server/boot/ProjectRegistry.kt:48` | SQLite `projects.db` (`BootOrchestrator.kt:324`) | `projectregistry/V1__project_registry.sql:3,10` |
| `ProjectConfigStore` | `server/boot/ProjectConfigStore.kt:30` | SQLite `project-config.db` (`BootOrchestrator.kt:291`) | `projectconfig/V1__project_config.sql:4` |
| `AgentOverrideStore` | `server/boot/AgentOverrideStore.kt:38` | SQLite `agent-overrides.db` (`BootOrchestrator.kt:431`) | `agentoverride/V1__agent_override.sql:5` |
| `RemoteTokenStore` | `server/boot/RemoteTokenStore.kt:20` | SQLite (`BootOrchestrator.kt:348`) | `remotetoken/V1__remote_token.sql:4` |

### 1.1 Zwei Halb-Fälle (Interface + SQLite, aber **kein** Postgres)

| Store | Pfad | Was fehlt |
|---|---|---|
| `MessageStore` | `server/comm/MessageStore.kt:15` | keine `Pg`-Impl, keine Migration — **die Kern-Fachlichkeit des Hubs (Nachrichten) ist heute nicht BYODB-fähig** |
| `ProjectAgentStore` | `server/boot/ProjectAgentStore.kt:68` | hat Interface + Factory + `MigrationTarget` (`:101`), aber **keine** `Pg`-Impl und **keine** Flyway-Migration — halb auf der Bahn |

## 2. Stores mit SQLite/Datei, aber **ohne** Postgres-Pfad

| Store | Pfad | Backing | Inhalt |
|---|---|---|---|
| `SecretStore` | `server/crypto/SecretStore.kt:23` | SQLite `.cyppie/hub-secrets.db` (`PlatformWiring.kt:363`), AEAD-verschlüsselt | **Hub-Privatschlüssel / benannte Secrets** |
| `RoleStore` | `server/auth/RoleStore.kt:27` | SQLite, Tabelle `role_assignments` (`:119`) | **Rollen-/ACL-Zuweisungen** |
| `TokenUsageStore` | `server/boot/TokenUsageStore.kt:23` | SQLite + JSON `.cyppie/token-usage.json` | Token-Verbrauch je Agent |
| `CompactConfigStore` | `server/boot/CompactConfigStore.kt:18` | SQLite + JSON `.cyppie/compact-config.json` | Compact-Schwellen |
| `AvatarBlobStore` | `server/avatar/AvatarBlobStore.kt:15` | SQLite `avatars.db` + PNGs unter `.cyppie/avatars/` | Avatar-Blobs |
| `DsnRegistry` | `server/db/DsnRegistry.kt:53` | JSON, Passwörter verschlüsselt, atomic-move + 0600 (`:114-124`) | **Postgres-DSNs** |
| `BindingRegistry` | `server/db/StoreBinding.kt:36` | JSON, atomic-move + 0600 (`:91-101`) | `(storeKey, projectId)` → DSN |
| `FinalizedEnrollmentStore` | `server/auth/operator/FinalizedEnrollmentStore.kt:25` | verschlüsselte Binärdatei (AEAD) | Operator-Device-Enrollment-Anker |
| `HubMcpConfigWriter` | `server/connector/HubMcpConfigWriter.kt:49` | `<dir>/<agentId>.mcp.json`, 0600 | MCP-Config inkl. Hub-Token |
| `hub-identity.json` | `server/routing/PlatformWiring.kt:368` | Datei, kein Seam | Hub-Identität |
| `platform.config.json` | `server/Application.kt:36` | Datei, **nur gelesen** (kein Schreibpfad gefunden) | Bootstrap-Config |

> `DsnRegistry` + `BindingRegistry` sind bemerkenswert: die **Konfiguration der BYODB-Anbindung selbst**
> liegt in lokalen JSON-Dateien — sie kann strukturell nicht in der Kunden-DB liegen (Henne/Ei).

## 3. Tabellen-Asymmetrie (die harte Messung für BYODB)

**Flyway/Postgres — 11 Tabellen:** `channel_share`, `session`, `agent_events`, `report`, `project_config`,
`delivery`, `project`, `project_active`, `agent_override`, `remote_token`, `events`.

**SQLite, inline in Kotlin angelegt (kein Flyway, keine Schema-Versionierung) — 17 Tabellen:**
`project_config`, `role_assignments`, `events`, `report`, `hub_secret`, `agent_override`, `registry`,
`agent_events`, `compact_config`, `project_agent`, `token_usage`, `remote_token`, `delivery`,
`avatar_blob`, `messages`, `channel_share`, `session`.

**Ohne Postgres-Gegenstück (8):** `registry`¹, `role_assignments`, `hub_secret`, `compact_config`,
`project_agent`, `token_usage`, `avatar_blob`, `messages`.

¹ `registry` ist kein reines Loch: Postgres teilt dieselben Daten auf `project` + `project_active` auf
(`projectregistry/V1__project_registry.sql:3,10`), SQLite hält sie in **einer** Tabelle. Also kein
fehlendes Feature, sondern **zwei unterschiedliche Formen derselben Sache** — genau die Art Divergenz,
die ein späterer Umzug bezahlen muss.

**`messages` fällt am meisten auf** (s. §1.1): der Nachrichten-Store hat SQLite + Interface, aber keine
Pg-Implementierung und keine Migration — die Kern-Fachlichkeit des Hubs ist heute nicht BYODB-fähig.

## 4. Seam-Befunde (wie austauschbar es *wirklich* ist)

**4.1 Boot verdrahtet konkret, nicht über die Factories.** Für neun Stores existiert eine
`companion object invoke`-Factory (`ProjectRegistry.kt:88`, `RemoteTokenStore.kt:30`,
`AgentOverrideStore.kt:68`, `ProjectAgentStore.kt:86`, `TokenUsageStore.kt:38`, `CompactConfigStore.kt:26`,
`AvatarBlobStore.kt:27`, `ChannelShareStore.kt:59`, `ProjectConfigStore.kt:65`). Der Boot ruft sie
**nicht**, sondern konstruiert direkt `Sqlite*`: `BootOrchestrator.kt:291,306,319,324,348,431,434,442,467,933,952`
und `PlatformWiring.kt:295,301,307,315,363,441`. Die Factories werden nur auf dem
`?: File…(null)`-Fallback erreicht (Tests / dateiloser Boot). **Das Interface existiert, der Produktionspfad
geht daran vorbei.**

**4.2 Die „durablen" Impls erben vom In-Memory-Store.** `JsonFileMessageStore : InMemoryMessageStore()`
(`comm/MessageStore.kt:54`), `JsonFileSessionStore : InMemorySessionStore()` (`connector/SessionStore.kt:82`),
`SqliteReportStore` (`report/SqliteReportStore.kt:23`) und `FileReportStore` (`report/ReportStore.kt:113`)
erweitern die konkrete In-Memory-Klasse und flushen beim Schreiben. Der In-Memory-Index ist damit
**strukturell tragend** in den persistenten Varianten — nicht etwas, das man wegtauscht.

**4.3 `StoreRouter` ist der einzige gemeinsame Seam — und hat kein Interface.** `boot/StoreRouter.kt:35`
und `PgStoreRouting` sind konkrete Klassen; Consumer halten den `StoreRouter` selbst, keine Abstraktion.

**4.4 Interfaces mit genau einer Implementierung:** `SecretStore` → nur `SqliteSecretStore`
(`crypto/SqliteSecretStore.kt:25`) · `DsnRegistry` → nur `FileDsnRegistry` · `BindingRegistry` → nur
`FileBindingRegistry` · `QuotaUsageStore` → nur `InMemoryQuotaUsageStore` (`tier/FreeQuota.kt:42`) ·
`TerminalGrantStore` → nur No-op/In-Memory, **keine durable Impl existiert** (`routing/TerminalAccess.kt:76,108`).

**4.5 Ganz ohne Seam (konkrete Klasse ist der einzige Typ):** `WsTicketStore`, `ParticipantTokenStore`,
`TokenRegistry`, `BackupCodeStore`, `FinalizedEnrollmentStore`, `AgentConfigRegistry`, `RuntimeRegistry`,
`CapabilityRegistry`, `ProviderRegistry`, `SessionRegistry`, `TunnelSessionRegistry`, `ProjectVmStoreManager`.

**4.6 Flüchtiges Auth-/Audit-Material (verifiziert, und im Code offen deklariert):**

| Store | Backing | Konsequenz |
|---|---|---|
| `WsTicketStore` (`auth/WsTicketStore.kt:36`) | `ConcurrentHashMap` | Einmal-WS-Tickets, Neustart = weg (unkritisch, kurzlebig) |
| `ParticipantTokenStore` (`auth/ParticipantTokenStore.kt:59`) | `ConcurrentHashMap`, nur SHA-256-Hashes | Teilnehmer-Token überleben Neustart nicht |
| `TokenRegistry` (`routing/Auth.kt:30`) | `ConcurrentHashMap`, aus Env geseedet | Agent-Token; Quelle ist Env, daher reproduzierbar |
| `BackupCodeStore` (`auth/operator/BackupCodeStore.kt:28`) | `mutableListOf`, gesalzene Hashes | **Operator-Recovery-Codes sind flüchtig** |
| `AuditSink` (prod: `InMemoryAuditSink`, `PlatformWiring.kt:445`) | Ring, Cap 1000 (`auth/AuditSink.kt:36`) | **Operator-Audit-Trail gekappt + bei Neustart weg** |

> **Ehrlichkeits-Notiz, damit das nicht als versteckter Defekt gelesen wird:** beide letzten Punkte sind
> im Code **bewusst und offen** so vermerkt — `AuditSink.kt:32-34` schreibt „durable (SQLite) persistence
> is an optional follow-up", `TerminalAccess.kt:74` schreibt „when a real grant store lands". Das sind
> **bekannte offene Enden**, keine Funde gegen den Bau. Ob sie für BYODB/Betrieb offen bleiben dürfen,
> ist eine Entscheidung — s. §5.

## 5. Was hier eine ENTSCHEIDUNG braucht (Fragen, keine Vorschläge)

Diese Punkte sind bei der Messung aufgetaucht und **nicht** von mir beantwortet:

1. **Deckungsumfang.** Heißt „bring your own DB" *alle* Zustände oder die **10** heute geroutet Stores?
   Die Messung sagt nur: 8 Tabellen liegen heute außerhalb, darunter `messages`.
2. **Hub-Schlüssel.** Gehört `hub_secret` (Hub-Privatschlüssel, `crypto/SqliteSecretStore.kt:142`) in die
   **Kunden-DB** oder bleibt es strukturell bei uns? Das ist eine Vertrauens-/Vertragsfrage, keine technische.
3. **Henne/Ei.** `DsnRegistry` + `BindingRegistry` konfigurieren die Kunden-DB und können nicht in ihr
   liegen. Bleibt dieser lokale Rest explizit im Versprechen benannt?
4. **Audit-Dauerhaftigkeit.** Erwartet ein BYODB-Kunde einen **dauerhaften** Operator-Audit-Trail? Heute
   ist er in-memory und bei 1000 gekappt.
5. **Form-Divergenz.** `registry` (SQLite, 1 Tabelle) vs `project` + `project_active` (Postgres, 2) — welche
   Form ist die verbindliche? **Datenmodell-Entscheidung, ausdrücklich Auftraggeber.**

## 6. Was dieses Dokument NICHT tut

Keine Ziel-Architektur · kein Zielschema · keine Empfehlung, welche Stores wandern sollen · keine Aussage
darüber, was einem Kunden versprochen wird. Nur Bestand.

## 7. Self-Validation

- **44 Stores** gelistet; jeder mit Modul/Pfad, Seam, Backing, Inhalt.
- **Jede Zahl gegengerechnet, und eine korrigiert:** ein Zwischenstand dieses Dokuments sagte „12 Stores
  mit Pg-Pfad". Nachgezählt sind es **10** — `find -name "Pg*.kt"` liefert 11 Dateien, davon ist
  `boot/PgStoreRouting.kt` kein Store; dazu exakt 10 Flyway-Verzeichnisse mit 11 `CREATE TABLE`
  (`project`+`project_active` in einer Datei). `MessageStore` und `ProjectAgentStore` gehörten nie dazu →
  jetzt als §1.1-Halb-Fälle geführt. 11 Flyway-Tabellen + 17 SQLite-Tabellen · 8 ohne Gegenstück (§3).
- **Stichprobenartig direkt am Code gegengeprüft** (nicht nur aus der Recherche übernommen):
  `PlatformWiring.kt:445` (Audit prod = in-memory), `AuditSink.kt:36` (Cap 1000),
  `BackupCodeStore.kt:28` (`mutableListOf`), `ParticipantTokenStore.kt:59` (Hash-Map),
  `TerminalAccess.kt:76,108` (keine durable Impl).
- **Unsicheres markiert statt weggelassen:** Laufzeit-Registries (`RuntimeRegistry`, `CapabilityRegistry`,
  `ProviderRegistry`, `SessionRegistry`, `TunnelSessionRegistry`, `AgentConfigRegistry`) sind Caches, je
  aber alleinige Wahrheitsquelle im laufenden Prozess — Zählung als „Store" ist Definitionssache.
  `HubMcpConfigWriter` ist ein Emitter ohne Lesepfad. `platform.config.json` ohne gefundenen Schreibpfad.
  `SecureSessionStore` ist `expect class` = Compile-Zeit-Seam, **nicht** laufzeit-injizierbar; die
  JVM-`actual` ist in-memory (`SecureSessionStore.jvm.kt:9-12`) → Desktop-Sessions überleben keinen Neustart.
- **Client-`*Repository`-Typen ausdrücklich ausgeschlossen** (HTTP-Clients, kein durabler Zustand) — als
  Nicht-Store benannt statt still übergangen.
- **Grenze eingehalten:** §5 enthält **Fragen** an den Auftraggeber, keine Antworten; §6 sagt explizit,
  was nicht getan wurde.
