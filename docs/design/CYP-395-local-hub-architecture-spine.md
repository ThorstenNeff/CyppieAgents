# CYP-395 — Local-Mode Hub: Architektur-Spine (Design-Pass)

> **Status:** Entwurf **v2** · **DESIGN-PASS — kein Bau** · Autor: Backend-Strang
> **v2-Δ:** Krypto-/Auth-Nahtstellen an den Reviewer-Threat-Model-Strang (`14-threat-model-auth-krypto.md`
> §8) angeglichen — **entscheidungs-agnostisch, aber anker-korrekt**: die Nähte tragen beide F1-Varianten,
> trennen Signing-/DH-Schlüssel (F2), machen Register-PoP zum Pflichtfeld (F3), Master-Key-Custody zur
> injizierten Policy (F5), und verankern die BYOA-/E2E-Egress-Invariante (F4). Präjudiziert **keine** der
> offenen [RATIFIKATION]-Punkte.
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
liegende Projekt-Switch-Orchestrierung; (2) eine **`IdentityToken`-Verifier-Naht**, die **beide** Auftraggeber-
Optionen trägt (online Kratos-Opaque **oder** offline CP-JWT — F1, nicht vorwegnehmen; heute existiert 0
JWT-Code); (3) **getrennte Hub-Schlüssel** (`signingKey` Ed25519 + `dhKey` X25519, F2) + ein **headless-fähiger,
fail-closed `SecretStore`** für Credentials (heute Klartext-0600); (4) ein **Ressourcen-Governor** (heute
komplett greenfield). Alle vier sind **additiv** und hängen an bereits sauberen Nähten.

> **★ Sofort-Flag an PO (verify-don't-trust):** Die Prämisse „**7 Default-Agenten**" deckt sich **nicht** mit
> dem Ist-Stand. Der ausgelieferte Default (`platform.config.example.json`) ist **exakt 3**: `po` (PO),
> `frontend`/`backend` (WORKER). `platform.config.json` ist **gitignored** — die reale Agenten-Menge liegt nur
> unversioniert auf der Betreiber-Box (die „7" = Live-Staging-Roster). Das `Role`-Enum kennt nur
> `PO`/`WORKER`/`PRODUCT_LEAD`. **Preserve-sicher ist NICHT die Zahl**, sondern der kodifizierte Invariant (§9);
> Deploy preserved die Live-`config.json` byte-identisch. Das Design ist bewusst zahl-agnostisch (D1 = Klarstellung,
> keine Auftraggeber-Entscheidung).

### 0.1 Bewegliche Nähte (Design-Awareness — gegen den Post-Merge-Stand entwerfen)
Zwei Team-2-Merges bewegen **genau** zwei meiner Spine-Nähte; der Entwurf ist bewusst robust gegen ihre Bewegung
(der PO merged sie **vor** jedem 395-Bau):
- **CYP-394** stellt `/ws/terminal` von read-tier (`wsReaderOrNull`) auf **write-tier-Gating** um: neue
  `routing/TerminalAccess.kt` (`mayOpenTerminal`, MEMBER/participant→1008, operator-only), Edits in
  `TerminalSocket.kt`+`PlatformWiring.kt`. **Das ist die konkrete Ausprägung meiner `IdentityToken`/Principal-Naht
  (§5)** — `mayOpenTerminal` ist der Ist-Stand, an den die variant-agnostische Verifikation andockt. (Der
  `TerminalGrantStore` darin ist prod-no-op und **nicht** die Store-/CYP-220-Naht §4.)
- **CYP-351** landet `LifecycleManager`/`ClaudeCodeSession`/`ResumingSession`/`ConnectorSession`/`EventProjector`
  — **genau die Naht, die mein Hub-Split/Session-Manager (§2) evolviert.** Die Extraktion setzt auf dem
  Post-351-Stand auf.

Beide noch nicht auf develop (kommen mit gepinnten SHAs); Bau erst danach + nach Ratifikation.

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
  No-Op-Stub** anlegen, nicht implementieren. **Egress-Invariante schon in der Naht verankern (F4/BYOA):** es gibt
  **genau einen** CP-gebundenen Ausgang, geführt durch `SecretMasker` (inkl. Fehler-/Crash-/Telemetrie-Pfade);
  die Regel **„kein Nutzdaten-/Credential-Byte verlässt den Hub ohne E2E"** wird als **Test** verankert (das
  Anthropic-Credential überquert den CP-Connector nie). So kann die Phase-2-Impl die Invariante nicht später
  verletzen.
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
- **`HubIdentity`** — erzeugt beim Erststart **zwei getrennte Schlüssel** (F2): `signingKey` (**Ed25519**,
  Identität/Registrierungs-PoP/JWT-Sig-Kontext) **und** `dhKey` (**X25519**, DH-Basis für Phase-2-Noise). Ed25519
  ist **kein** DH-Schlüssel — ein einzelner Ed25519-Key hätte in Phase-2-Noise keinen brauchbaren DH-Anteil. Beide
  **privat → `SecretStore` (§6)**; beide **PublicKeys** werden registriert, im **Noise-fertigen Rohformat**
  (32-Byte, klar kodiert) — das ist der Phase-1-Anker, der sonst Phase-2 bricht. Idempotent.
- **`ControlPlaneRegistrar`** — `register(hubId, ownerId, name, defaultPort, signingPubKey, dhPubKey, pop)`.
  **`pop` ist Pflichtfeld (F3, NO-GO ohne):** Proof-of-Possession = Hub signiert einen **CP-ausgegebenen Nonce**
  mit `signingKey`, gebunden an die approbierte **Device-Code-Sitzung**. Ohne PoP kann ein untergeschobener
  PublicKey den gesamten Phase-2-E2E-Anker unbemerkt vergiften. Der Device-Code-Flow (headless) + „Hub-Zertifikat"
  (unter-spezifiziert) = **Reviewer-Strang**.
- **`IdentityToken`-Verifier + optionaler `JwksCache`** — siehe Auth-Naht unten.

**Auth-Naht (die zentrale Cross-Strang-Stelle → über PO; F1 offen):** Heute existiert **0 JWT-Code** (kein
`auth-jwt`/JWKS/PublicKey-Verifikation; Identität = opake Kratos-Session via whoami-HTTP). **F1 ist eine
Auftraggeber-[RATIFIKATION]** und wird hier **nicht vorweggenommen** — der Lokal-Modus kann (A) einen offline
verifizierbaren **CP-JWT** (neue Signier-+Verifier-Achse) **oder** (B) den online-`whoami` gegen die CP nutzen.
Die Spine baut die **variant-agnostische `IdentityToken`-Naht**:

> `interface IdentityToken { suspend fun verify(token, hub): Principal? }` — **fail-closed**, mit **Pflicht-Checks
> unabhängig von der Variante**: `aud == dieser Hub` **UND** `sub == hub.ownerId` (F6; ein gültig CP-signierter
> Token eines **anderen** Nutzers darf **diesen** Hub nicht öffnen), plus `iss==CP`/`exp`/`nbf`. Zwei Impls hinter
> der Naht: **`KratosOpaqueVerifier`** (F1-B, = der bestehende `KratosIdentityProvider`, online whoami) und
> **`CpJwtVerifier`** (F1-A, offline gegen `JwksCache`, mit **alg-Pinning** — kein `alg:none`, keine
> RS/HS-Confusion — und `kid`-basierter Rotation + hartem Offline-Fenster/TTL). **Offline-JWT NICHT fest
> verdrahten.**

Die Naht wickelt sich um den bestehenden **`IdentityProvider`-Seam** (`auth/IdentityProvider.kt`): F1-B ist
`KratosIdentityProvider` unverändert; F1-A ist eine **zweite Impl**, die JWT gegen `JwksCache` verifiziert.
**Alles darunter** (`AuthPrincipal.Human`, `RoleStore`, `AuthGuard`, `requireCommReader/Writer`) ist
**verifikations-agnostisch** und bleibt unverändert; einzige Verdrahtungsänderung: `AuthDeps.idp` in
`bootPlatform`. → **Token-Format/Claims/JWKS-Rotation + F1-Entscheidung mit Reviewer über PO.**

**Device-Code-Redaktion (F7, CYP-190-Klasse):** `device_code`/`user_code` sind **neue** Secret-Shapes; die
bestehenden Redaktoren (`TokenRedactor`, `SecretMasker`) kennen sie nicht → **vor** Live-Gang erweitern. Naht:
die Redaktions-Muster sind eine zentrale, testbare Liste.

---

## 6. Nativer Keystore (JVM) + Verhältnis zu Tink

### Ist-Stand
- Anthropic-Key: `resolveApiKey = { pid -> projectConfig.resolvedApiKey(pid) }` (Lambda-Naht) → escaped nur in
  die Child-Prozess-ENV (`ANTHROPIC_API_KEY`). At-rest heute **Klartext** in `project-config.json` (0600,
  tmp-then-atomic-move, nie geloggt, `***<last4>`-Maskierung nach außen). `remote-tokens.json` ebenso Klartext-0600.
- **Verschlüsselungspfad existiert, ist aber dark:** `PgProjectConfigStore` verschlüsselt via Tink-`SecretCipher`
  (AAD `project_config|{pid}|api_key`) — nur aktiv, wenn ein Pg-Store geroutet wird (nie in Prod).

### Design-Δ (`SecretStore`-Naht — F5, entscheidungs-agnostisch)
> **★ Reviewer-Korrektur an meiner v1:** Native Keychains (libsecret/Keychain) brauchen eine **interaktive**
> entsperrte Sitzung — ein **headless startender Server-Hub** hat die oft **nicht** (libsecret = D-Bus/Login-
> Session). „OS-Keystore hält den KEK" ist also **nicht** die sichere Default-Antwort, sondern **eine** Custody-
> Option unter mehreren. **F5 ist [RATIFIKATION].**

Die Spine baut daher die **`SecretStore` (expect/actual)** als Naht mit fixen Invarianten, ohne die Custody
vorwegzunehmen:
- **Default-Impl = die bestehende Tink-`SecretCipher`-Spine** (CYP-220, schon an Token-at-rest verdrahtet:
  AEAD-Envelope, key-versioniert/Rotation, injektive AAD-Bindung `(storeKey, projectId, field)`, **fail-closed
  Decrypt**). **Nicht neu bauen.**
- **Master-Key-Custody = explizit injizierte Policy**, nicht hardcoded: `interface MasterKeyCustody`, Impls
  wählbar per F5-Ratifikation — **KMS** / **TPM/Secure-Enclave-gebunden** / **Operator-Passphrase (KDF beim
  Hub-Start)** / **OS-Keychain (nur wo interaktiv verfügbar)** / heutiger `CYPPIE_MASTER_KEY` (env, BOX). Es gibt
  auf fremder Hardware **keinen „umsonst"-Weg** — die Wahl gehört dem Auftraggeber.
- **Headless fail-closed, nie silent-plaintext:** fehlt die Custody-Quelle, wirft der `SecretStore` (Boot bricht,
  wie `Secrets.fromEnv`) — er degradiert **niemals** stillschweigend auf Klartext-on-disk (**genau die
  `local.properties`-/CYP-190-Klasse, die NICHT geerbt werden darf**).
- Verwahrt **beide Hub-Private-Keys (`signingKey`/`dhKey`, §5) + das Anthropic-Credential** at rest. **Credentials
  gehen nie an die CP** (BYOA) — nur die At-Rest-Verwahrung härtet.

`expect/actual` gilt für die JVM-Hub-Seite (Custody-Provider); Android/iOS-`actual` = Client-Strang. Die
Cloud-Zukunft (Managed-Worker) verschiebt „Custody auf Nutzer-Hardware" zu „Custody in unserer Cloud" und
**schwächt die BYOA-Garantie strukturell** — die Naht darf das nicht verbauen, aber es ist eine **separate**
Vertrauensentscheidung (kommunikativ trennen).

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
- Keypair-Erststart ist idempotent; die Umstellung des bestehenden **Klartext-At-Rest** (`project-config.json`/
  `remote-tokens.json`) auf den `SecretStore` (Tink-Envelope, §6) braucht eine **einmalige Migration** (Prozedur +
  Master-Key-Custody-Wahl = Reviewer-Strang/F5).
- `port`/`host` müssen **config-getrieben** werden (heute Compile-Konstanten `8787`/`127.0.0.1` in
  `main`/`PlatformWiring`) — Hub-Packaging braucht das (D6).

---

## 10. Evolvierte Boot-Sequenz (Skizze)

Additive Schritte in **fett**; alles andere ist die bestehende `BootOrchestrator.boot()`-Reihenfolge.

```
main(): host/port AUS CONFIG (statt const)                         ← D6
 └─ bootPlatform:
    1. PlatformConfig.load
    2. **SecretStore(SecretCipher + injizierte MasterKeyCustody)** — fail-closed     §6/F5
    3. **HubIdentity.ensure()** → signingKey(Ed25519)+dhKey(X25519), priv→SecretStore §5/F2
    4. **ControlPlaneRegistrar.ensureRegistered(bothPubKeys, pop=sig(CP-Nonce))** + **JwksCache.warm()** (nur F1-A) §5/F3
    5. Secrets.fromEnv · WorktreeManager
    6. BootOrchestrator.boot():
         … (unverändert) … ABER:
         - MessageStore = **SqliteMessageStore** statt InMemory                     §4/D4
         - **ResourceGovernor** konstruieren; admitSpawn vor der Spawn-Schleife      §7
    7. AuthDeps: idp = **IdentityToken**-Naht → CpJwtVerifier(F1-A) ODER KratosOpaque(F1-B) §5/F1
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
| **HubIdentity** | `SecretStore` (priv) + `.cyppie/hub-identity.json` (pub/meta) | `hubId`, `signingPubKey`(Ed25519), `dhPubKey`(X25519, Noise-Rohformat), `createdAt` | Hub-only |
| **Registrierung** | `.cyppie/hub-registration.json` | `hubId`, `ownerId`, `hubCert`(unter-spez.→Reviewer), `pop`(Sig über CP-Nonce, Pflicht F3), `registeredAt` | Hub-Cache CP-Eintrag |
| **CP-JWKS-Cache** | `.cyppie/cp-jwks.json` | `keys[]`(mit `kid`), `fetchedAt`, `ttl`/Offline-Fenster | Hub-Cache (nur F1-A) |
| **Credential (Anthropic)** | Tink-Envelope via `SecretStore`; Master-Key-Custody = injizierte Policy (F5) | `apiKey`(enc), AAD `project_config|{pid}|api_key` | Hub-only, nie CP |
| **Ressourcen-Budget** | Laufzeit (aus `Runtime`), kein Persistenz-Bedarf | `maxAgents`, `usedAgents` | Hub-only |

Alles unter `gitRoot/.cyppie/`, 0600, gitignored — konsistent mit dem Ist-Layout.

---

## 12. Offene Entscheidungen (für Auftraggeber/PO)

| # | Entscheidung | Empfehlung |
|---|---|---|
| **D1** | **3 vs 7 Default-Agenten** — Repo liefert **3** (`po`/`frontend`/`backend`); „7" ist nicht im Code. Was ist das Preserve-Ziel? | Preserve = der **Invariant** (§9), nicht die Zahl. Bitte reale `platform.config.json` bestätigen. |
| **D2** | Lokales SQLite **direkt in BootOrchestrator** (wie die 3 Live-Sqlite-Stores) vs. durch `StoreRouter`/`PgStoreRouting` | **Direkt-Wiring** (Router bleibt der Cloud-Strang; preserve-sicher). |
| **D3** | **Master-Key-Custody (F5)** für den `SecretStore`: KMS / TPM-Enclave / Operator-Passphrase / OS-Keychain(interaktiv) / env-`CYPPIE_MASTER_KEY` | **Auftraggeber-[RATIFIKATION]** — kein „umsonst"-Weg auf fremder Hardware. Spine baut die injizierte Policy-Naht (Default-Impl Tink), präjudiziert die Wahl **nicht**. |
| **D4** | **`SqliteMessageStore`** jetzt einführen (schließt In-Memory-Message-Lücke; Neustart-Durabilität) | **Ja** — echte Lücke, additiv. |
| **D5** | **F1 — Offline-Verifikation:** (A) CP-signierter JWT (neue Sig-/Verifier-Achse) **oder** (B) online-`whoami` | **Auftraggeber-[RATIFIKATION]** — Spine baut die variant-agnostische `IdentityToken`-Naht (aud+sub==ownerId Pflicht), verdrahtet Offline-JWT **nicht** fest. Format/Claims/JWKS = Reviewer über PO. |
| **D6** | `port`/`host` **config-getrieben** machen (heute Compile-Konstanten) | Ja — Packaging-Voraussetzung. |
| **D7** | `SessionManager`-Extraktion jetzt vs. später | **Jetzt** (klein, entkoppelt Phase-2-Transport). |
| **D8** | `-Xmx`/gebundener Dispatcher als Teil des `ResourceGovernor` | Ja — heute unbegrenzt = reales OOM-Risiko. |
| **D9** | Zwei Frontends auf denselben Hub (Konzept-Strang „Hub-Lebenszyklus") | Deferred → eigener Strang; hier nur benannt. |

---

## 13. Risiken & Go/No-Go

| # | Risiko | Schwere | Mitigation |
|---|---|---|---|
| R1 | **Auth-Anker sicherheitskritisch** (F1 Token-Modell, F6 Verifier-Härtung, Device-Code) | Hoch | **Reviewer-Strang** gated die Krypto; mein Beitrag = die variant-agnostische `IdentityToken`-Naht (aud+sub==ownerId Pflicht). GO für die Naht, **NO-GO für Bau vor F1/F6**. |
| R2 | `BootOrchestrator.boot()` = **900-Zeilen-Monolith**; Session-Manager-Extraktion fasst ihn an | Mittel | Additive Extraktion hinter Contract-Drift- + e2e-Boot-Tests; kein Verhaltens-Δ. |
| R3 | `SqliteMessageStore` ändert Neustart-Semantik (Messages künftig durabel) | Niedrig | Bewusst geflaggt (D4); Migration = leer (neue DB). |
| R4 | **Klartext-At-Rest → `SecretStore`-Migration** + Master-Key-Custody auf fremder Hardware (F5) | Hoch | Headless fail-closed, **nie silent-plaintext**; Custody = injizierte Policy (D3); Prozedur = Reviewer-Strang. |
| R5 | **Falsches Schlüsselmaterial / fehlender PoP** vergiftet den Phase-2-Noise-Anker **irreparabel** (F2/F3) | Hoch | Getrennte `signingKey`/`dhKey` + PoP-Pflichtfeld **schon in Phase-1-Registrierung** (§5/§11); Anker jetzt korrekt legen. |
| R6 | **CP = Pubkey-Autorität** → kompromittierte CP kann Remote-MITM (F4); ZK-Aussage nur für Nutzdaten, nicht Metadaten | Hoch (Phase 2) | Frontend-Pinning (TOFU) + OOB-Fingerprint = Reviewer/Client-Strang; Registry-Format **pin-fähig/Noise-fertig** jetzt festlegen. |
| R7 | **Lokal-Modus im LAN** (nicht echtem Loopback) → Bearer/JWT + Stream mitschneidbar (F8) | Mittel–Hoch | Anker heute = `bootHost=127.0.0.1` (Loopback); LAN ⇒ Remote/E2E erzwingen **oder** lokal TLS/Noise = [RATIFIKATION]. |
| R8 | `EVENT_SCOPE_ALL == null` → Cross-Tenant-Event-Leak (S18-Flag im Code) | Niedrig (Lokal=single-tenant) | Für Lokal-Hub effektiv moot (ein Hub = ein Nutzer); vor echtem Multi-Tenant schließen. |
| R9 | Unbegrenzter `Dispatchers.IO`-Scope / `StoreRouter` dark-Versuchung | Mittel/Niedrig | `ResourceGovernor` (§7/D8); Router **nicht** anfassen (D2). |

**Go/No-Go-Empfehlung:** **GO** für den Spine-Entwurf — die Evolution ist überwiegend Formalisierung an bereits
sauberen Nähten und **additiv/preserve-sicher**; die Nähte abstrahieren die offenen Ratifikationen sauber statt
sie vorwegzunehmen. **Gating-Unbekannte vor Bau (kein Bau vor Auftraggeber-Ratifikation):** (a) **D1** (3-vs-7
Klärung); (b) der **Krypto-/Auth-Threat-Model** des Reviewer-Strangs — insb. **F1** (Token-Modell) + **F2**
(Schlüssel) entschieden, **F3** (Register-PoP) + **F6** (Verifier-Härtung) zugesichert (§8/§9 dort); (c)
Ratifikation der `:core`-Kontrakt-Ergänzungen (§8).

---

## 14. Cross-Strang-Nahtstellen (Synthese über PO)

Deckungsgleich mit den **fünf Kontrakt-Nahtstellen** des Reviewer-Threat-Models (`14-…` §8) — als Interfaces mit
fixen Invarianten gebaut, **ohne** die offenen Ratifikationen vorwegzunehmen:

1. **`IdentityToken`-Naht** (→ Reviewer): `verify(token, hub): Principal?`, **fail-closed**, **Pflicht** `aud` +
   `sub==hub.ownerId` variantenunabhängig; trägt F1-A (CP-JWT, alg-Pin/`kid`/Offline-Fenster) **und** F1-B
   (Kratos-Opaque online). Offline-JWT nicht fest verdrahtet. (§5) — Token-Format/JWKS/F1-Entscheidung = Reviewer.
2. **`HubKeypair`/`HubIdentity`** (→ Reviewer): getrennt `signingKey`(Ed25519) + `dhKey`(X25519); Registry
   persistiert **beide** PublicKeys **Noise-fertig** (§5/§11). F2-Anker.
3. **`Register(hub)`-Call** (→ Reviewer/CP): **`pop` als Pflichtfeld** (Sig über CP-Nonce, Device-Code-gebunden) —
   ohne PoP unvollständig (F3, NO-GO). (§5/§11)
4. **`SecretStore` (expect/actual)** (→ Reviewer für Custody, Developer für Android/iOS-`actual`): headless,
   fail-closed, **nie silent-plaintext**; Default = Tink-`SecretCipher`; **Master-Key-Custody = injizierte Policy**
   (D3/F5). Client-Session-Keys = separater Strang. (§6)
5. **CP-Egress-Grenze** (→ Reviewer/Developer): **ein** CP-Connector-Ausgang durch `SecretMasker`; Invariante
   „kein Nutzdaten-/Credential-Byte ohne E2E" **testbar** (BYOA). (§2/Phase-2-Stub)

Zusätzlich: **`:core`/`:protocol`-Kontrakt** (→ Developer/UIUX): additive DTOs `HubDescriptor`/`HubList`/
Connect-Metadaten/Handshake-Frames — durch CYP-234-Gates. **Wer besitzt `GET /hubs`-DTOs?** (CP-Fläche,
Frontend-konsumiert.) · **Ressourcen-/Modus-UX** (→ UIUX): Kapazitäts-/Überlast-Events konsumieren meine
`EventSink`-/`HubDescriptor`-Flächen.

*— Ende Entwurf v2 (Krypto/Auth an Reviewer-Strang angeglichen). Melde mich zur Synthese/Ratifikation.*
