# CYP-395 — Local-Mode Hub: Architektur-Spine (Design-Pass)

> **Status:** Entwurf v1 · **DESIGN-PASS — kein Bau** · Autor: Backend-Strang
> **Epic:** CYP-395 (High) · **Konzept:** `13-cyppie-hub-architektur.md` (Auftraggeber, 2026-07-11)
> **Branch:** `feature/CYP-395-arch-spine-design` (von develop `a2f66ae8`)
> **Auftraggeber-Entscheidungen:** Phase 1 = **Lokal-Modus**; **bestehenden `:server` additiv evolvieren** (kein Greenfield);
> Live-Stand + Default-Agenten **preserve-sicher**; Remote-E2E/Noise = **Phase 2** (nur Naht/Stub).
> **Parallele Stränge:** Reviewer = Krypto/Auth/Zero-Knowledge-Threat-Model · Developer = Client-Architektur · UIUX = Connect-/Hub-/Modus-UX.
> **Cross-Strang-Nahtstellen (`:core`-Wire-Kontrakt + Auth-Kontrakt) werden über den PO synchronisiert, nicht direkt.**

---

## 0. Kernthese

Der heutige `:server` ist **bereits zu ~80 % ein Lokal-Modus-Hub.** Die tragenden Schichten — ACL/Enforcement,
der In-Process-Mediations-Bus, die Store-Naht (schon multi-backend), der `:core`-Wire-Kontrakt und die
per-Projekt-Runtime-Isolation — sind **schon transport-agnostisch und mehr-Impl-fähig.** Die Evolution ist
überwiegend **Formalisierung + wenige additive Nähte**, kein Neubau.

Die vier größten *neuen* Flächen sind: (1) eine explizite **Session-Manager-Naht** vor die heute Ktor-inline
liegende Projekt-Switch-Orchestrierung; (2) **JWT-Verifikation als neue `IdentityProvider`-Impl** (heute
existiert 0 JWT-Code); (3) **Hub-Keypair + native Keystore** für Credentials (heute Klartext-0600); (4) ein
**Ressourcen-Governor** (heute komplett greenfield). Alle vier sind **additiv** und hängen an bereits sauberen
Nähten.

> **★ Sofort-Flag an PO (verify-don't-trust):** Die Prämisse „**7 Default-Agenten**" deckt sich **nicht** mit
> dem Ist-Stand. Der ausgelieferte Default (`platform.config.example.json`) ist **exakt 3**: `po` (PO),
> `frontend`/`backend` (WORKER). `platform.config.json` ist **gitignored** — die reale Agenten-Menge liegt nur
> unversioniert auf der Betreiber-Box. Das `Role`-Enum kennt nur `PO`/`WORKER`/`PRODUCT_LEAD`. **Preserve-sicher
> ist NICHT die Zahl**, sondern der kodifizierte Invariant (§9). Das Design ist bewusst zahl-agnostisch. → **Bitte
> Preserve-Ziel bestätigen** (Open Decision D1).

---

## 1. Vertrauenszonen → Ist-Stand-Mapping

| Zone (Konzept) | Heute im `:server` | Phase-1-Δ |
|---|---|---|
| **Control Plane** (`api.cyppie-agents.com`) — Identität, Hub-Registry, kein Klartext | **Existiert nicht als eigener Dienst.** Identität heute = **lokales Kratos** (`KratosIdentityProvider` → whoami). | Minimaler CP-Dienst (§5) **außerhalb** `:server`; Hub bekommt Registrar- + JWKS-Client. |
| **Hub** — Code/Repo/Credentials/Agenten-Zustand, einzige Nutzdaten-Zone | **= der heutige `:server`** (Worktrees, `ProjectConfigStore`, Connector-Sessions, Stores unter `gitRoot/.cyppie/`) | Additiv: Hub-Identität, Keystore, Ressourcen-Governor, Session-Manager-Naht. |
| **Frontend** — Sitzungsschlüssel, Anzeige | KMP-Client (`:app:shared`) über `:core` + REST/WS | Modus-/Hub-Auswahl = **Developer/UIUX-Strang**. |

Der Hub ist gehört-genau-einem-Nutzer → **Phase-1-Lokal-Modus ist effektiv single-tenant pro Prozess.** Das
entschärft (aber tilgt nicht) den unten geflaggten Cross-Tenant-Event-Leak (R5).

---

## 2. Hub-Split: transport-agnostische Session-Manager-Naht

### Ist-Stand
- **Sauber & transport-agnostisch (direkt wiederverwendbar):** `Hub.postAsAgent` (die **eine** zentrale
  ACL-Enforcement-Stelle, alle Transporte funneln durch sie), `HubState`/`AclMatrix` (`:core`, pure,
  member-AND-flag, deny-wins), `RuntimeRegistry.active()`→`ProjectRuntime`, `MessageDeliverer`, `MediationRouter`,
  `ConnectorSessions`, `ConnectorRouter`.
- **Ktor-gekoppelt (die eigentliche Reibung):** die gesamte Mount-Schicht in `routing/PlatformWiring.kt`
  (`installPlatform`) **und** — kritisch — die **Projekt-Switch-Orchestrierung als Inline-Lambda** in
  `installPlatform`s `projectRoutes(onActiveSwitch = { pid -> getOrCreate → drainProject → state.rescope →
  rehydrateActiveProject → suspensionPolicy.onActivated })`. Dieselbe Sequenz nochmal inline in
  `BootOrchestrator.boot()` beim Boot-Switch. **Das ist echte Geschäftslogik in einem `routing{}`-Closure.**

### Design-Δ (Naht-Skizze)
Eine dünne **`SessionManager`**-Naht extrahieren, die den Transport nicht kennt:

```
                 ┌──────────────── SessionManager (neu, transport-agnostisch) ───────────────┐
 Local API ─────▶│  switchProject(pid) | activeRuntime() | resolvePrincipal(Credential)      │
 (Ktor, Phase 1) │  → drain → state.rescope → rehydrate → suspensionPolicy   (heute inline)  │
                 │  hält KEINEN Ktor-Typ; nimmt eine transport-neutrale Principal/Credential  │
 CP-Connector ──▶│                                                                            │
 (Phase-2 STUB)  └────────────┬───────────────────────────────────────────────────────────────┘
                              │ ruft die bereits sauberen Kerne
                 Hub · HubState · RuntimeRegistry · ConnectorSessions · MediationRouter
```

- **Phase 1:** Local API (die bestehenden Ktor-Routen) bleibt der **einzige** Eingang und ruft `SessionManager`
  statt der Inline-Lambda. Minimal-invasiv: nur `onActiveSwitch`-Rumpf + `active()`-Auflösung wandern in den
  Service; die Routen bleiben dünn.
- **Phase 2 (nur benennen):** der **Control-Plane-Connector** ist ein **zweiter Transport** (Outbound-WS,
  E2E-Tunnel) → mündet in **denselben** `SessionManager`. In Phase 1 als **`interface ControlPlaneConnector` +
  No-Op-Stub** anlegen, nicht implementieren.
- **Principal-Naht:** `resolvePrincipal(deps)` hängt heute an `ApplicationCall`. Für den zweiten Transport später
  eine **transport-neutrale `Credential`** vorsehen; `SessionCredential.Source{HEADER,COOKIE}` modelliert das
  Token-im-Header schon. Phase 1: unverändert lassen, nur die Naht dokumentieren.

**Aufwand:** klein–mittel. Reines Herausziehen; kein Verhaltens-Δ. Contract-Drift-Tests (CYP-234) + e2e-Boot
sichern preserve.

---

## 3. Embedded In-Process-Bus (formalisieren)

### Ist-Stand — es gibt bereits **zwei** eingebettete Busse (beide Ktor-frei):
1. **Comm-/Mediations-Bus** (die Nutz-Topologie): `Hub.postAsAgent` (Enforcement-Chokepoint) → `SharedFlow` +
   `onPosted` → **`MessageDeliverer`** injiziert an berechtigte Empfänger-Sessions (`session.sendTurn`, durabel via
   `DeliveryLog`, at-least-once, per-Agent-`Mutex`); Rückweg **`MediationRouter.onResult`** (Session→`agentId` via
   `SessionRegistry`, →Spoke via `HubState.spokeChannelFor`, →`Hub.postAsAgent`). Topologie/ACL/Hub-and-Spoke sind
   schon **Code** (`HubState.hubAndSpoke`, `AclMatrix`), nicht externe Berechtigungen.
2. **Observability-/Supervisions-Bus:** `EventSink` (Ordering+Persistenz-Naht) → `Scanner` (Sense) → `Warden`
   (Decide+Act). Separater Consumer-Plane.

### Design-Δ
- Den Comm-Bus als **explizite `interface InProcessBus`** benennen (post/subscribe/deliver), Impl =
  heutige Mediations-Spine. Reine **Konsolidierung/Benennung**, kein Umbau — die Teile sind schon so verdrahtet
  (`BootOrchestrator`: `hub.onPosted = deliverer::onPosted`, `sessions.addRegisterListener(...)`).
- **NATS-Swap-Naht benennen:** die `InProcessBus`-Schnittstelle so schneiden, dass eine spätere NATS-Impl
  Subject-Routing (`project.<pid>.agent.<id>`) auf die bestehenden Channel-/Spoke-IDs mappt. **Nur Naht, kein NATS
  in Phase 1** (Konzept §„Später — NATS"). Beide Busse bleiben in-process; extern-Broker bleibt ausgeschlossen.
- **Explizit auslassen:** die Agent↔Hub-Kommunikation läuft heute für Connector-A **nicht** über stdout-`hub_send`
  (CYP-146 retired), sondern über den in-process **Hub-MCP-Server** (`/mcp/hub`) + Turn-End-Router. Der Bus
  formalisiert genau diese bestehende Mündung.

---

## 4. Embedded SQLite hinter der Store-Naht — **CYP-220-Überschneidung aufgelöst**

### Ist-Stand (der Kern der Aufgabe)
Es gibt **EINE** Store-Naht, **nicht zwei konkurrierende Abstraktionen.** Die Naht = eine Familie von
Per-Store-**Interfaces** (jeweils `companion operator fun invoke(...)`-Factory) + **ein** Platzierungspunkt
`StoreRouter`→`PgStoreRouting`. Hinter jedem Interface koexistieren bereits **InMemory / JsonFile / Sqlite / Pg**.

- **SQLite ist heute schon Live-Prod-Backend** für 3 Interfaces: `SqliteEventSink : EventSink`,
  `SqliteAgentEventStore : AgentEventStore`, `SqliteRoleStore : RoleStore` (alle WAL, unter `.cyppie/`).
- **Die gesamte CYP-220-Postgres-Vertikale ist DARK** (0 Prod-Caller): `StoreRouter`/`PgStoreRouting` „inert by
  construction", `StoreMigrator`, `DsnRegistry`/`BindingRegistry`/`ConnectionProvider`, alle `Pg*`-Impls +
  `MigrationGated*`-Wrapper, die **Tink-AEAD-Spine** (`SecretCipher`/`TinkSecretCipher`, fail-closed: kein
  Master-Key → cipher null → Secret-Stores bleiben File). Kein Boot-Code konstruiert DB-Infra oder einen Cipher.
- **Heutiger Default = Hybrid:** SQLite (3 Stores) + JSON-File (Config/Registry/Shares/Session/Overrides/…) +
  **In-Memory für Messages** (`InMemoryMessageStore` hart in `BootOrchestrator.kt:149` — **Messages überleben
  keinen Neustart**).

### Auflösung (die gemeinsame Naht, nicht doppeln)
> **SQLite-für-lokal und Postgres-für-Cloud sind zwei Impls hinter DERSELBEN Interface-Familie.** Das ist keine
> neue Abstraktion — es ist exakt die Form, die schon existiert (InMemory/JsonFile/Sqlite/Pg koexistieren pro
> Interface). Ein lokaler `Sqlite<X>Store` ist ein **Geschwister** der vorhandenen `Pg<X>`-Impls.

**Empfehlung Phase 1 (Open Decision D2):** die verbleibenden File-basierten Stores als lokale **`Sqlite*`-Impls**
direkt in `BootOrchestrator` verdrahten — **genau wie die 3 bereits live Sqlite-Stores**, **ohne** den dunklen
`StoreRouter`/`PgStoreRouting` anzufassen. Begründung:
- `StoreRouter`/`PgStoreRouting` ist der **Cloud-/Multi-DSN-Router** (Residency, Migrations-Fenster, BYO-DB) — ein
  Phase-2/Cloud-Anliegen. Ihn für einen Single-Tenant-Lokal-Hub „anzuschalten" verheddert Phase 1 mit der
  Cloud-Migration.
- Direkt-Wiring ist **preserve-sicher** (ändert nichts Live-Wirksames — der Router ist dark) und folgt dem
  etablierten Muster.
- **Residency-Modell bleibt gültig:** `StoreResidencies` (fail-closed Allow-Liste) beschreibt bereits, welche
  Stores „user-DB-fähig" sind vs. `MUST_STAY_HOME`. Der **lokale Embedded-SQLite = die „home"-Residency**;
  CYP-220-Postgres = die „BYO/managed"-Residency. **Dasselbe Modell, zwei Deployments.**

**Additiver Gewinn / Flag (Open Decision D4):** **`SqliteMessageStore : MessageStore` einführen** und die
In-Memory-Hardcodierung ersetzen → Messages werden neustart-durabel (schließt eine echte Lücke). Verhaltens-Δ:
Nachrichten überleben künftig Neustart (heute: weg) — strikt besser, aber preserve-relevant → bewusst flaggen.

**Interfaces, die eine lokale Sqlite-Impl bekommen könnten** (alle heute File): `MessageStore`,
`ProjectConfigStore`, `ProjectRegistry`, `SessionStore` (heute JsonFile), `DeliveryLog` (heute JsonFile),
`ReportStore`, `RemoteTokenStore`, `AgentOverrideStore`, `ChannelShareStore`, `ProjectAgentStore`,
`TokenUsageStore`, `CompactConfigStore`, `AvatarBlobStore`. **Kein neues Interface nötig.**

---

## 5. Minimale Control Plane (Phase 1) + Hub-Registrierung

> **Abgrenzung:** Die CP ist ein **separater Dienst** (`api.cyppie-agents.com`), **nicht** Teil von `:server`.
> Mein Strang spezifiziert (a) **was der Hub von ihr braucht** und (b) die **hub-seitigen Clients**. CP-Betrieb
> und der Krypto-/Device-Code-Flow = Reviewer-Strang (über PO).

**CP muss in Phase 1 exponieren (minimal):**
1. **Identität** — OIDC über das **bestehende Kratos** (Email-Magic-Link / GitHub). Kein neuer IdP.
2. **Hub-Registry** — `register(hubId, ownerId, pubKey, name, defaultPort)` + `list()` (Presence online/offline
   = Phase 2 via Connector-WS; Phase-1-`list` liefert nur Metadaten).
3. **JWKS-Auslieferung** — der öffentliche **JWT-Verifikationsschlüssel** der CP, damit der Hub das Frontend-JWT
   **offline** (gecacht) verifizieren kann.

**Hub-seitige neue Komponenten (Phase 1, additiv):**
- **`HubIdentity`** — erzeugt beim Erststart ein **Ed25519-Keypair**; privat → Keystore (§6), public → Registrar.
  Idempotent (einmal erzeugen, danach wiederverwenden).
- **`ControlPlaneRegistrar`** — tauscht Device-Code + Hub-PublicKey → Registry-Eintrag/Hub-Zertifikat. **Der
  Device-Code-Flow (headless startender Hub) ist der sicherheitskritischste Teil → Reviewer-Strang.**
- **`JwksCache`** — holt + cached den CP-Verifikationsschlüssel für den Offline-Local-Handshake.

**Auth-Naht (die zentrale Cross-Strang-Stelle → über PO):** Heute existiert **0 JWT-Code** (kein `auth-jwt`,
kein JWKS, keine PublicKey-Verifikation; Identität = opake Kratos-Session via whoami-HTTP). Der **saubere Hook**
für „CP-ausgestelltes JWT gegen gecachten PublicKey verifizieren" ist eine **dritte `IdentityProvider`-Impl**
(`auth/IdentityProvider.kt`): `resolve(SessionCredential)` verifiziert JWT-Signatur gegen `JwksCache` statt
Kratos-whoami und liefert `ResolvedIdentity(identityId, verified=true)`. **Alles darunter**
(`AuthPrincipal.Human`, `RoleStore`, `AuthGuard`, `requireCommReader/Writer`) ist **signatur-agnostisch** und
bleibt unverändert; einzige Verdrahtungsänderung: `AuthDeps.idp`-Konstruktion in `bootPlatform`. → **Auth-Kontrakt
(Token-Format, Claims, JWKS-Rotation) mit Reviewer über PO klären.**

---

## 6. Nativer Keystore (JVM) + Verhältnis zu Tink

### Ist-Stand
- Anthropic-Key: `resolveApiKey = { pid -> projectConfig.resolvedApiKey(pid) }` (Lambda-Naht) → escaped nur in
  die Child-Prozess-ENV (`ANTHROPIC_API_KEY`). At-rest heute **Klartext** in `project-config.json` (0600,
  tmp-then-atomic-move, nie geloggt, `***<last4>`-Maskierung nach außen). `remote-tokens.json` ebenso Klartext-0600.
- **Verschlüsselungspfad existiert, ist aber dark:** `PgProjectConfigStore` verschlüsselt via Tink-`SecretCipher`
  (AAD `project_config|{pid}|api_key`) — nur aktiv, wenn ein Pg-Store geroutet wird (nie in Prod).

### Design-Δ (Keystore-vs-Tink sauber aufgelöst — Open Decision D3)
Der Konzept-Keystore (macOS Keychain / Linux libsecret / Android Keystore / iOS Keychain) ist **KMP-expect/actual**
— aber **der Hub ist JVM-only** (der Client hat *seine eigenen* Session-Keys, ein Developer/UIUX-Anliegen). Für
den **Hub-Credential-Store** empfehle ich die **Hybrid-Auflösung, die die vorhandene Tink-Spine wiederverwendet
statt sie zu doppeln:**

> **OS-Keystore hält den KEK (Master-Key); Tink-AEAD (CYP-220, schon gebaut) macht die Envelope-Verschlüsselung
> der At-Rest-Blobs.** Heute kommt der Master-Key aus `CYPPIE_MASTER_KEY` (env, BOX-Modus) — ersetze/ergänze das
> durch **libsecret/Keychain als KEK-Quelle** (`SecretCipherFactory` bekommt eine dritte Key-Quelle neben env/KMS).

Vorteile: kein selbstgebauter Krypto-Blob; Tink liefert misuse-resistant AEAD + Key-Rotation schon; der
OS-Keystore liefert hardware-gestützte KEK-Verwahrung. **Reuse statt Doppel.** Alternativen (für PO): (a) reiner
OS-Keystore pro Secret ohne Tink; (b) Status-quo Klartext-0600 beibehalten (nur wenn Keystore Phase 2 wird).
**Credentials gehen nie an die CP** — bleibt so (BYOA), nur die At-Rest-Verwahrung härtet.

Naht: eine **`expect/actual`-`KeyVault`**-Abstraktion **nur für die JVM-Seite** (libsecret via JNA / Keychain via
Security.framework) als KEK-Provider für `SecretCipherFactory`. Android/iOS-`actual` = Client-Strang.

---

## 7. Ressourcen-Selbstüberwachung (produktisiert die OOM-Lektion)

### Ist-Stand — **greenfield.**
Einziger JVM-Arg: `-Dio.netty.jfr.enabled=false`. **Kein `-Xmx`, keine Selbstüberwachung** (kein
`Runtime.maxMemory`/`availableProcessors`/`MemoryMXBean`), **kein gebundener Thread-Pool** (ein unbegrenzter
`Dispatchers.IO + SupervisorJob()`-App-Scope aus `main`). Einzige Kapazitäts-Mechanik: `RuntimeSuspensionPolicy`
(LRU-Cap, Default `1`) — eine **Projekt-Session-LRU**, kein Ressourcen-Governor. Prozess-Spawn =
`ProcessBuilderSpawner` ohne cgroup/ulimit/Count-Guard.

### Design-Δ (additiv, klein)
Neue **`ResourceGovernor`**-Komponente im Hub-Paket (Konzept §„Ressourcenbewusstsein"):
- **Schätzt Kapazität** aus `Runtime.maxMemory()`/`availableProcessors()` → ein **Agenten-/Team-Budget**.
- **Hookt in den Spawn-Pfad** (`LifecycleManager.bootAgent` / die Boot-Spawn-Schleife): weiterer Spawn, der das
  Budget sprengt → **fail-closed abweisen mit klarer Nutzer-Meldung** (statt OOM-Crash). Speist
  `RuntimeSuspensionPolicy` (Cap resource-aware statt fix `1`).
- **Setzt reale Limits:** `-Xmx`/`-XX:MaxRAMPercentage` + eine **gebundene Dispatcher-Parallelität**
  (`limitedParallelism`) statt des unbegrenzten IO-Scope. **Genau unsere Team2-OOM-Lektion, produktisiert.**
- **Meldet** Kapazität/Überlast als content-freies Event (bestehende `EventSink`-Naht) → UI (UIUX-Strang).

**Naht:** `ResourceGovernor.admitSpawn(agentId): Admit|Reject(reason)` vor jedem Spawn; Konstruktion in
`BootOrchestrator`, Budget-Quelle als Seam (testbar mit fixem Budget).

---

## 8. `:protocol`-Wire-Kontrakt (= das bestehende `:core` formalisieren)

### Ist-Stand
Es gibt **kein Modul `:protocol`** — der As-built-Kontrakt ist **`:core`** (KMP: commonMain/jvm/js/wasmJs/
android/ios), byte-identisch in **Server** (`api(projects.core)`) **und Client** (`:app:shared`) kompiliert, mit
**einem** `CommJson`-Codec (`classDiscriminator="type"`). Governance = **CYP-234**: hand-authored
`RestContract.REST_OPS` + Drift-Tests, `SchemaWalker`→OpenAPI-3.1/AsyncAPI-2.6, Versionierungs-Parität
(`/api`↔`/api/v1`), Schema-Tightness, `NoSecretInReadResponseTest`. Sealed WS-Familien pro Richtung
(`CommWs*`, `EventsWs*`, `Terminal*`, `Lifecycle*`, `TerminalControl`, `StreamJsonEvent`, `WireProtocol`).

### Design-Δ
- **„`:protocol`" = das bestehende `:core` als Lokal-Modus-Kontrakt benennen** — nichts neu erfinden. Die
  komplette Local-API-Fläche (`/api`+`/api/v1` REST, `/ws/*`) existiert und ist drift-getestet → **das ist der
  Phase-1-Eingang.**
- **Additive DTOs (Phase 1, Cross-Strang → über PO):** Hub-Identität/Registrierung (`HubDescriptor{hubId, name,
  ownerId, defaultPort, online}`, `HubList`), Modus-/Connect-Metadaten, JWT-Handshake-Frames. Jede `:core`-Ergänzung
  muss durch die CYP-234-Gates (REST_OPS-Eintrag, Schema-Tightness, No-Secret-in-Read) — **das ist der Preserve-Mechanismus für den Kontrakt.**
- **Wichtig:** `GET /hubs` ist eine **CP-Fläche** (nicht `:server`), aber die Frontend-DTOs dafür gehören in
  `:core`. Naht zum Developer-/UIUX-Strang → über PO.

---

## 9. Migration / Koexistenz (preserve-sicher)

**Die Evolution ist additiv.** Kodifizierte Preserve-Invarianten, die geschützt bleiben MÜSSEN:
- **Agenten-Seeding:** Config-Agenten werden **jeden Boot** aus `platform.config.json` in
  `HubState.hubAndSpoke(agents, OPERATOR_ID, config.projectId)` geseedet — **config.json ist die durable Quelle,
  bewusst NICHT store-persistiert** (vermeidet config↔store-Drift, „die CYP-220-Lektion"). **Permanent besessen von
  `config.projectId`** (CYP-308). **Zahl-agnostisch** (3 heute, N morgen).
- **`PlatformConfig`-Invarianten:** ≥1 Agent, **genau ein PO**, eindeutige IDs.
- **Wire-Kontrakt:** die `/api`+`/api/v1`+`/ws/*`-Fläche via CYP-234-Drift-Tests unverändert.
- **Store-Layout:** der Hybrid unter `gitRoot/.cyppie/` (0600-Secrets, WAL-SQLite) bleibt; SQLite-Ergänzungen sind
  Geschwister, keine Ersetzung der Live-3.
- **Runtime-Isolation:** `RuntimeRegistry`/`ProjectRuntime`/`rescope`-Semantik unverändert.

**Neu & preserve-relevant (flaggen):**
- `SqliteMessageStore` ändert Neustart-Verhalten (Messages künftig durabel) — Δ, aber Verbesserung (D4).
- Keypair-Erststart ist idempotent; Keystore-KEK-Migration von `CYPPIE_MASTER_KEY`→OS-Keystore muss den
  bestehenden Klartext-At-Rest **einmalig migrieren** (Reviewer-Strang für die Krypto-Prozedur).
- `port`/`host` müssen **config-getrieben** werden (heute Compile-Konstanten `8787`/`127.0.0.1` in
  `main`/`PlatformWiring`) — Hub-Packaging braucht das (D6).

---

## 10. Evolvierte Boot-Sequenz (Skizze)

Additive Schritte in **fett**; alles andere ist die bestehende `BootOrchestrator.boot()`-Reihenfolge.

```
main(): host/port AUS CONFIG (statt const)                         ← D6
 └─ bootPlatform:
    1. PlatformConfig.load
    2. **HubIdentity.ensure()**  → Ed25519-Keypair (idempotent, priv→KeyVault)   §5/§6
    3. **KeyVault/SecretCipherFactory(KEK aus OS-Keystore)**                        §6
    4. **ControlPlaneRegistrar.ensureRegistered(pubKey)** + **JwksCache.warm()**   §5
    5. Secrets.fromEnv · WorktreeManager
    6. BootOrchestrator.boot():
         … (unverändert) … ABER:
         - MessageStore = **SqliteMessageStore** statt InMemory                     §4/D4
         - **ResourceGovernor** konstruieren; admitSpawn vor der Spawn-Schleife      §7
    7. AuthDeps: idp = **JwtIdentityProvider(JwksCache)** ODER Kratos (Naht)         §5
    8. installPlatform: Routen rufen **SessionManager** statt Inline-Switch-Lambda   §2
```

Boot-Reihenfolge-Kern (unverändert, zur Orientierung): Config → Clone/adopt → Agenten→Domain → HubState.hubAndSpoke
→ RuntimeRegistry → Hub/Sessions/TokenRegistry → Event-Pipeline → MediationRouter → MessageDeliverer → Connector A/B
→ Observability-Consumer → Trackers → LifecycleManager → **Spawn-Schleife** → ReportStore → CompactOrchestrator →
PtyManager → `BootedPlatform`.

---

## 11. Daten-Modell (hub-lokal, additiv)

| Entität | Speicherort | Felder | Zone |
|---|---|---|---|
| **HubIdentity** | KeyVault (priv) + `.cyppie/hub-identity.json` (pub/meta) | `hubId`, `ed25519PubKey`, `createdAt` | Hub-only |
| **Registrierung** | `.cyppie/hub-registration.json` | `hubId`, `ownerId`, `hubCert`, `registeredAt` | Hub-Cache CP-Eintrag |
| **CP-JWKS-Cache** | `.cyppie/cp-jwks.json` | `keys[]`, `fetchedAt`, `ttl` | Hub-Cache |
| **Credential (Anthropic)** | Tink-Envelope in `project-config.json`; KEK im OS-Keystore | `apiKey`(enc), AAD `project_config|{pid}|api_key` | Hub-only, nie CP |
| **Ressourcen-Budget** | Laufzeit (aus `Runtime`), kein Persistenz-Bedarf | `maxAgents`, `usedAgents` | Hub-only |

Alles unter `gitRoot/.cyppie/`, 0600, gitignored — konsistent mit dem Ist-Layout.

---

## 12. Offene Entscheidungen (für Auftraggeber/PO)

| # | Entscheidung | Empfehlung |
|---|---|---|
| **D1** | **3 vs 7 Default-Agenten** — Repo liefert **3** (`po`/`frontend`/`backend`); „7" ist nicht im Code. Was ist das Preserve-Ziel? | Preserve = der **Invariant** (§9), nicht die Zahl. Bitte reale `platform.config.json` bestätigen. |
| **D2** | Lokales SQLite **direkt in BootOrchestrator** (wie die 3 Live-Sqlite-Stores) vs. durch `StoreRouter`/`PgStoreRouting` | **Direkt-Wiring** (Router bleibt der Cloud-Strang; preserve-sicher). |
| **D3** | Secret-at-rest: **OS-Keystore-KEK + Tink-Envelope** (Reuse) vs. reiner OS-Keystore vs. Status-quo Klartext-0600 | **OS-Keystore-KEK + Tink** (kein Doppel, Rotation gratis). |
| **D4** | **`SqliteMessageStore`** jetzt einführen (schließt In-Memory-Message-Lücke; Neustart-Durabilität) | **Ja** — echte Lücke, additiv. |
| **D5** | **JWT-`IdentityProvider`** als neue Auth-Achse neben Kratos-Session (CP stellt JWT/JWKS) | Ja; Format/Claims/Rotation = Reviewer über PO. |
| **D6** | `port`/`host` **config-getrieben** machen (heute Compile-Konstanten) | Ja — Packaging-Voraussetzung. |
| **D7** | `SessionManager`-Extraktion jetzt vs. später | **Jetzt** (klein, entkoppelt Phase-2-Transport). |
| **D8** | `-Xmx`/gebundener Dispatcher als Teil des `ResourceGovernor` | Ja — heute unbegrenzt = reales OOM-Risiko. |
| **D9** | Zwei Frontends auf denselben Hub (Konzept-Strang „Hub-Lebenszyklus") | Deferred → eigener Strang; hier nur benannt. |

---

## 13. Risiken & Go/No-Go

| # | Risiko | Schwere | Mitigation |
|---|---|---|---|
| R1 | **Auth/JWT ist sicherheitskritisch** (Device-Code, Offline-Verify, Rotation) | Hoch | **Reviewer-Strang** gated die Krypto; mein Beitrag = nur die saubere `IdentityProvider`-Naht. Go für die Naht, No-Go für Bau vor Threat-Model. |
| R2 | `BootOrchestrator.boot()` = **900-Zeilen-Monolith**; Session-Manager-Extraktion fasst ihn an | Mittel | Additive Extraktion hinter Contract-Drift- + e2e-Boot-Tests; kein Verhaltens-Δ. |
| R3 | `SqliteMessageStore` ändert Neustart-Semantik (Messages künftig durabel) | Niedrig | Bewusst geflaggt (D4); Migration = leer (neue DB). |
| R4 | **Keystore-KEK-Migration** von Klartext/env → OS-Keystore | Mittel | Einmal-Migration; Prozedur = Reviewer-Strang. |
| R5 | `EVENT_SCOPE_ALL == null` → Cross-Tenant-Event-Leak (S18-Flag im Code) | Niedrig (Lokal=single-tenant) | Für Lokal-Hub effektiv moot (ein Hub = ein Nutzer); vor echtem Multi-Tenant schließen. |
| R6 | `StoreRouter`/`PgStoreRouting` dark — Versuchung, ihn für SQLite „anzuschalten" | Niedrig | D2: **nicht** anfassen; Cloud-Strang. |
| R7 | Unbegrenzter `Dispatchers.IO`-Scope | Mittel | `ResourceGovernor` (§7/D8). |

**Go/No-Go-Empfehlung:** **GO** für den Spine-Entwurf — die Evolution ist überwiegend Formalisierung an bereits
sauberen Nähten und **additiv/preserve-sicher.** **Gating-Unbekannte vor Bau:** (a) **D1** (3-vs-7 Klärung), (b)
der **Auth-/Krypto-Threat-Model** des Reviewer-Strangs (R1/R4), (c) Ratifikation der `:core`-Kontrakt-Ergänzungen
(§8). Kein Bau vor Auftraggeber-Ratifikation.

---

## 14. Cross-Strang-Nahtstellen (Synthese über PO)

1. **`:core`/`:protocol`-Kontrakt** (→ Developer/UIUX): additive DTOs `HubDescriptor`/`HubList`/Connect-Metadaten/
   JWT-Handshake — müssen durch CYP-234-Gates. **Wer besitzt `GET /hubs`-DTOs?** (CP-Fläche, Frontend-konsumiert.)
2. **Auth-Kontrakt** (→ Reviewer): JWT-Format/Claims/JWKS-Rotation, Device-Code-Flow, KEK-Migrationsprozedur.
   Meine Naht: `JwtIdentityProvider` + `JwksCache` + `KeyVault`-KEK-Quelle — **Formen, nicht Krypto.**
3. **Keystore expect/actual** (→ Developer für Android/iOS-`actual`): meine JVM-`actual` (libsecret/Keychain) ist
   Hub-seitig; Client-Session-Keys sind separater Strang.
4. **Ressourcen-/Modus-UX** (→ UIUX): Kapazitäts-/Überlast-Events + Hub-/Modus-Auswahl konsumieren meine
   `EventSink`-/`HubDescriptor`-Flächen.

*— Ende Entwurf v1. Melde mich zur Synthese/Ratifikation.*
