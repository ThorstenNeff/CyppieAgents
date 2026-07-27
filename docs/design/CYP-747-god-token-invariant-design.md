# CYP-747 — God-Token Authority-Boundary: die Security-Invariante, die S3 + S4 gatet (Design-Pass)

> Status: **DESIGN-PASS v2 — zur Ratifikation an PL + Auftraggeber (2 Prinzipale), VOR jedem Bau.** Owner: Backend.
> Bezug: CYP-747 Modell-2 Aussteller-Vertrauen (Design `db1f879c`, `docs/design/CYP-747-model2-issuer-trust-design.md`).
> Basis: develop `dea4bac1`. **KEIN Bau-Gate.** Output dieses Docs = die ratifizierbare Invariante; es baut nichts an
> **S3** (god-token remote-Verweigerungs-Closure) oder **S4** (C3 aktiver Widerruf).
>
> **Rev.2 folds den adversarialen Reviewer-Pass (F1-Blocker + F2–F6):** **★ F1 (BLOCKER, am Objekt bestätigt):** die
> Invariante „god-token strukturell remote-verweigert" ist HEUTE **NICHT** erzwungen — die TOKEN-Achse auf dem
> öffentlichen Connector ist off-loopback offen (nur die Cookie-Achse ist via S-AAL2b loopback-gated). S3 wächst damit
> von „nur Tunnel-Port-Closure" auf **„Tunnel-Port-Closure UND öffentlicher-Connector-Token-Achsen-Loopback-Gate"**.
> **F1 ist an den PL eskaliert (Security-Boundary/Architektur-Ratifikation).** F2–F6 präzisieren Closure-Quelle,
> Registry-Integrität, Post-Widerruf-Re-Enroll, Weichen-Split und das Kill-Switch-Bootstrap-Fenster (§-Verweise inline).
>
> **Warum dieser Pass existiert:** S3 und S4 fassen **dieselbe Kante** an — die Grenze, welche Operator-Autorität auf
> welcher Fläche erreichbar/überlebbar ist. Eine falsch gezogene Grenze (eine ungeschützte Achse; ein Widerruf, der bei
> Registry-Ausfall fail-open geht) reißt still die Seizure-/Replay-Klasse wieder auf, die Modell-2 schließen soll.
> Deshalb wird die Grenze **als Eigenschaft am Chokepoint ratifiziert, bevor** einer der Slices gebaut wird.

---

## 0. Was dieses Doc liefert (und was NICHT)

- **Liefert:** (a) was der god-token HEUTE ist **inkl. der F1-Lücke**; (b) die tragende Invariante als
  **oberflächen-agnostische Eigenschaft am Chokepoint**, plus ihre Spezialisierungen S3/S4; (c) Angriffsflächen +
  fail-closed-Defaults; (d) Migrations-/Kompat-Impact; (e) Weichen zur Ratifikation; (f) non-vakuöse Akzeptanz-Zähne.
- **Liefert NICHT:** Code, Wire-Shapes, Registry-Implementierung, Merge. Alles gated hinter PL/Auftraggeber-GO.

---

## 1. (a) Der god-token HEUTE — Scope, Aussteller, Lebensdauer, Halter, Containment

### 1.1 Identität — genau EIN statischer String

Der „god-token" ist der **statische Maschinen-Operator-Token**, identitätsgleich mit `TokenRegistry::isOperator`:

```kotlin
fun isOperator(token: String?): Boolean = operatorToken != null && token == operatorToken   // Auth.kt:33
```

`isOperator` ist wahr **nur** für den einen konfigurierten String (`server/.../routing/Auth.kt:33`). Kein Muster,
keine Klasse — Gleichheit gegen einen einzelnen Wert.

### 1.2 Aussteller & Custody

- **Aussteller:** der Mensch, der die Box betreibt, via Host-Env `OPERATOR_TOKEN`. **Fail-closed Pflicht am Boot:**
  fehlt/leer → `error("missing required env OPERATOR_TOKEN")` (`server/.../boot/Secrets.kt:75-76`). In Logs maskiert
  (`Secrets.kt:32`, letzte-4). Verdrahtet als `TokenRegistry(secrets.agentTokens, secrets.operatorToken)`
  (`BootOrchestrator.kt:354`).
- **NICHT CP-/Kratos-/Relay-gemintet.** Rein lokal gesetzt, statisch über die Prozess-Lebensdauer.

### 1.3 Scope / Autorität

Ein präsentierter god-token löst zu **`AuthPrincipal.MachineOperator`** auf (`Principal.kt:126`) — **volle,
ungescopte OPERATOR-Autorität** über ALLE Operator-Kanten (ACL-Mutation, Agent-CRUD/Token-Mint, config/apikey-Writes,
Lifecycle). **Kein TTL, kein `exp`, kein Widerruf** — er umgeht Op-Session-TTL und aktiven Widerruf strukturell.

### 1.4 Lebensdauer

Prozess-Lebensdauer, statisch. **Kein** Ablauf- oder Widerrufs-Hebel gegen den statischen Token selbst — genau deshalb
muss er auf remote-erreichbaren Flächen strukturell verboten sein (§2).

### 1.5 Halter

Der **lokale Operator** (Mensch an der Konsole / lokale UI) — beabsichtigt **nur** über einen **Loopback-gebundenen**
öffentlichen Connector. Der **remote Operator nutzt ihn NIE** — er authentifiziert über eine **CP-scoped
Kratos-Operator-Session** (schmaler UND widerrufbar; `TunnelGodTokenGuard.kt:16-21`). **★ Aber „beabsichtigt" ≠
„erzwungen" — siehe §1.9 (F1).**

### 1.6 Das Kill-Switch (Never-Lock-Out-Downgrade)

`operatorTokenDisabled` via Env `CYPPIE_OPERATOR_TOKEN_DISABLED` (`PlatformConfig.kt:99-102`, aufgelöst
`PlatformWiring.kt:453`; **nie zur Laufzeit / über kein Endpoint setzbar**). Gesetzt **UND** ein Kratos-OPERATOR
existiert → der statische Token ist **INERT**, herabgestuft zu `MachineAgent(null)` (MEMBER read-tier),
`Principal.kt:125-132`. Never-lock-out: er trägt, bis der erste Mensch OPERATOR bootstrappt (das **Bootstrap-Fenster**,
das mit F1 off-loopback interagiert — §5-W4/F6).

### 1.7 Containment HEUTE (Tunnel-Fläche) — zwei unabhängige per-Tunnel-Gates

1. **`TunnelGodTokenGuard`** (`server/.../routing/TunnelGodTokenGuard.kt`) — verweigert den god-token (Bearer **UND**
   `?token=`-WS/Query-Fallback) auf dem **tunnel-scoped 127.0.0.1-Connector**. Diskriminator = `call.request.local.
   localPort ∈ tunnelPorts` — ein **server-seitiger Fakt, den der Client nicht beeinflussen kann**. Läuft auf
   `ApplicationCallPipeline.Plugins` **vor** Routing/Route-Auth; fail-closed 401 + `finish()`.
2. **RR3-Tunnel-Auth-Gate** (`Rr3TunnelGate.authorize`, einmal pro Tunnel) — CpJwt **AND** Operator-Device-PoP, beide an
   die live `h_i` gebunden. Der statische `OPERATOR_TOKEN` ist **kein** valider RR3-Credential (Modell-2 §5).

**Production-Wiring HEUTE:** `installTunnelGodTokenGuard(config.hub.tunnelPort, …)` (`PlatformWiring.kt:425`) → **EIN**
`config.hub.tunnelPort` (Default `8786`, `PlatformConfig.kt:137`) → Singleton-Port-Set (`TunnelGodTokenGuard.kt:40-41`).
Der Guard ist bereits als **Set** geschrieben (`installTunnelGodTokenGuardOnPorts`, `:53`) — positive Allowlist, keine
Einzel-Gleichheit.

### 1.8 Widerruf HEUTE

- **Lokal-aktiv:** `TunnelSessionRegistry.revokeOperator(operatorId)` (`transport/TunnelSessionRegistry.kt:34`) reißt
  **sofort** jeden live Tunnel für einen Operator auf **DIESEM** Hub ab. Plus passiver Op-Session-TTL-Teardown (CYP-484).
- **Die Lücke (S4):** nichts propagiert einen **Aussteller-/Relay-Level-Widerruf** an die **ANDEREN** Hubs des Operators.
  Cross-Hub-Widerruf = heute lazy ≤TTL, kein Push, keine Registry (Modell-2 §7).

### 1.9 ★ F1 (BLOCKER) — die Invariante ist HEUTE NICHT erzwungen: das öffentliche-Connector-Token-Achsen-Loch

Der öffentliche Connector bindet **`config.hub.host`** (`Application.kt:62`) — ein **überschreibbarer** Wert (Default
`127.0.0.1`, aber Deploy-/Env-setzbar auf `0.0.0.0` / eine LAN-/Public-IP). Auf diesem Connector existieren **ZWEI**
Operator-Auth-Achsen im **einen** Chokepoint `resolvePrincipal` (`auth/Principal.kt`):

- **Cookie-Achse (Kratos):** `role==OPERATOR && (!aal2 || !browserOperatorPostureEnabled) → null` (`Principal.kt:148`).
  `browserOperatorPostureEnabled` wird **single-sourced** aus `isLoopbackHost(config.hub.host)` gesetzt (S-AAL2b,
  `PlatformWiring.kt`). ⟹ off-loopback ist die Cookie→OPERATOR-Posture **deaktiviert** (fail-closed).
- **★ TOKEN-Achse (statischer god-token):** `if (isOperator(bearer) && …) return MachineOperator` (`Principal.kt:125`)
  — **KEIN Loopback-Check.** ⟹ ist der öffentliche Connector off-loopback gebunden, **authentifiziert der statische
  god-token als voller OPERATOR über das Netz** — Bearer **und** `?token=`-Fallback. Die Tunnel-Gates (§1.7) greifen
  hier nicht (anderer Connector), und S-AAL2b gated **nur** die Cookie-Achse, **nicht** die Token-Achse.

**⟹ Die Invariante-Hälfte „remote strukturell verweigert" ist HALB gebaut:** Tunnel-Fläche ✅, öffentlicher-Connector-
Token-Achse ❌. Das ist der F1-Blocker. **Fix = §2.1(b).** (Symmetrie-Argument: dieselbe Krankheit, die S-AAL2b für die
Cookie-Achse schloss, ist auf der Token-Achse offen; der Fix spiegelt S-AAL2b **single-sourced am selben Chokepoint**.)

---

## 2. (b) Die tragende Invariante — als oberflächen-agnostische EIGENSCHAFT

> **★ INVARIANTE (Eigenschaft, KEINE Flächen-Aufzählung):** *Statische, ungescopte, nicht-widerrufbare
> Operator-Autorität (der god-token) ist erreichbar **ausschließlich** auf der **lokalen (loopback-gebundenen)**
> Vertrauensfläche und ist auf **JEDER** remote-erreichbaren Fläche **strukturell verweigert**. Jede Operator-Autorität,
> die remote **doch** erreichbar ist, MUSS (1) aussteller-gemintet und **hub-lokal via Device-PoP verankert** sein (der
> Anti-Seizure-Zweitfaktor) **UND** (2) **aktiv widerrufbar** mit einem fail-closed **≤TTL-Floor**. Es existiert
> **keine** Fläche, auf der ein Credential zugleich remote-erreichbar UND (ungescopt ODER nicht-widerrufbar) ist.*

Diese **eine** Eigenschaft gatet **beide** Slices; jeder Slice erzwingt eine Hälfte **strukturell** (by construction).

### 2.1 S3 = die „strukturell-verweigert-remote"-Hälfte — ZWEI Flächen (F1 macht (b) NEU load-bearing)

S3 muss die remote-Verweigerung auf **beiden** remote-erreichbaren Flächen schließen:

**(a) Tunnel-Fläche — die Guard-Port-Closure (F2-korrigierte Quelle).** Die Guard-Port-Menge ist **beweisbar gleich**
der VOLLEN Menge tunnel-scoped Connector-Ports. **★ F2 — die Closure-Quelle ist die SERVER-Connector-Topologie, NICHT
die Client-Bridge:** die maßgebliche Menge ist die `embeddedServer`-Connector-Deklaration (`Application.kt:62-63`, die
`connector { }`-Literal-Blöcke), die der Server **selbst bindet**. S3 muss diese Deklaration **aufzählbar /
single-sourced** machen, so dass **Guard und Server dieselbe Menge lesen** — sonst vergleicht T2 die Guard-Menge gegen
eine Client-seitige Vorstellung, die der Server gar nicht durchsetzt (vakuöse Closure). **Closure heißt:** guard-set ≡
server-connector-topology **by construction** — Drift unmöglich, nicht bloß getestet.

**(b) ★ Öffentlicher-Connector-Token-Achse — der F1-Fix (Loopback-Gate, spiegelt S-AAL2b).** Die god-token-TOKEN-Achse
im Chokepoint `resolvePrincipal` (`Principal.kt:125`) wird mit demselben `isLoopbackHost(config.hub.host)`-Signal
gated wie die Cookie-Achse (S-AAL2b): off-loopback → der statische Token löst **NICHT** zu `MachineOperator` auf
(401 / MEMBER-Downgrade, **fail-closed**). **Single-sourced:** dasselbe `isLoopbackHost`, dieselbe Chokepoint-Funktion —
keine zweite Wahrheit. **Alternativ/ergänzend:** Boot-`require`, dass bei aktivem statischem Token der Host loopback ist
(Weiche §5-W2b). Als **Eigenschaft am Chokepoint** (nicht als Kanten-Liste): weil `resolvePrincipal` der **einzige**
Token→Operator- **und** Cookie→Operator-Pfad ist, deckt das Gate dort **alle** Operator-Kanten — keine 21., ungelistete
Kante kann das Loch wieder aufreißen (das Reviewer-Kriterium: F1 **by construction**, keine neue adjazente Fläche).

**Ports/Capabilities, die NIE remote erreichbar sein dürfen** (Konsequenz der Eigenschaft, nicht ihr Ersatz):
- der statische `OPERATOR_TOKEN` als **Bearer ODER `?token=`** auf **irgendeinem** tunnel-scoped Connector **ODER** auf
  einem **off-loopback** öffentlichen Connector;
- jede Capability, die Op-Session-TTL / aktiven Widerruf über eine remote Fläche umgeht;
- die ungescopte `MachineOperator`-Autorität — remote **nur** über den widerrufbaren CP-Session+PoP-Pfad.

**Positiv-Kontrolle (nicht brechen) — F1-korrigiert:** der god-token auf dem öffentlichen Connector bleibt volle lokale
OPERATOR-Autorität **NUR wenn dieser LOOPBACK-gebunden** ist. „public-LOOPBACK → 200" (lokale UI intakt);
„public-OFF-LOOPBACK → 401" (F1). Die alte v1-Formulierung „öffentlicher Connector unverändert erlaubt" war
**unbedingt falsch** und ist hiermit ersetzt.

### 2.2 S4 = die „aktiv-widerrufbar-mit-Floor"-Hälfte (C3 aktiver Widerruf)

**Ziel:** aktiver Aussteller-Widerruf im **Hub↔Relay-Protokoll** + **Cross-Hub-Session-Registry** (C3). Widerruf →
Push **STALE/REJECTED über ALLE** live Hub-Sessions des Operators **sofort** (nicht erst ≤TTL). **Schließt CYP-697.**

- **Der aktive Pfad** verallgemeinert `TunnelSessionRegistry.revokeOperator` (`TunnelSessionRegistry.kt:34`) von
  **ein-Hub** auf **Cross-Hub** (via Relay-propagierten Widerruf).
- **★ Fail-closed-Floor (PL-A1, Modell-2 §11-7b):** fällt die C3-Registry aus / partitioniert, degradiert die
  Widerruf-Wirksamkeit auf den **≤TTL-lazy-Floor — NIE länger, NIE fail-open**. **Strukturell erzwungen:** der Floor
  wird vom **registry-UNABHÄNGIGEN** passiven per-Hub-`exp`-Check (`NOT_EXPIRED`) getragen. Floor = die **bestehende**
  `exp` schon ausgestellter Credentials, **kein Re-Mint**.
- **Op-Session-TTL single-sourced** (`RemoteRelayWiring.resolveOpSessionTtlMs` / `DEFAULT_OP_SESSION_TTL_MS`, Override
  `CYPPIE_OP_SESSION_TTL_MIN`): Ticket-`exp` (`LiveHubTicketMinter.kt:21-25`) und Hub-Tunnel-Cap lesen dieselbe Quelle
  (CYP-563). Der Floor erbt sie.
- **★ F3 — Registry INTEGRITÄT (nicht nur Verfügbarkeit):** die C3-Registry trägt **Liveness** (aktiver Teardown),
  **NIE den Trust-Anker**. Ein **geforgter/kompromittierter** Registry-Eintrag darf Autorität **nicht gewähren oder
  verlängern** — der Trust-Anker bleibt per-Hub `AND(Issuer-JWS, Device-PoP)` + `exp`. Schlimmstfall eines Forge =
  **verpasster** Früh-Widerruf (Degradation auf den registry-unabhängigen `exp`-Floor), nie Eskalation. (Siehe A10.)
- **Der Zweitfaktor bleibt AND-gekoppelt:** die remote-erlaubte Autorität ist CP-Session **AND** Device-PoP über den
  hub-lokalen Anker (`Rr3TunnelGate`, Modell-2 §3/§5). S4 fasst das PoP-Gate **nicht** an.

### 2.3 Warum beide GEMEINSAM gated sind

S3 ohne S4: der statische Token ist remote verweigert, aber die remote **Ersatz**-Autorität bleibt im ≤TTL-Fenster
wirkungslos widerrufbar → Kompromittierung im Fenster hat null Hebel. S4 ohne S3: aktiver Widerruf existiert, aber eine
ungeschützte remote-Achse (Tunnel-Port **oder** off-loopback-Token, F1) lässt den **nicht-widerrufbaren** statischen
Token durch → Widerruf ist gegen den gefährlichsten Credential wirkungslos. **Nur zusammen** ziehen sie die Grenze:
god-token **volle** Autorität lokal, **null** remote (S3); der remote Ersatz **bounded** (gescopt + PoP-verankert +
aktiv widerrufbar mit Floor, S4).

### 2.4 ★ Die Chokepoint-Eigenschaft über Hub / Relay / Identity / BYOA (für die PL-Architektur-Ratifikation)

F1 wird **als Eigenschaft am einen Chokepoint** geschlossen, nicht als per-Fläche-Liste. Explizit über die vier
Architektur-Flächen (Reviewer-Kriterium: keine NEUE adjazente Fläche):

| Fläche | Operator-Autoritäts-Pfad | Durchsetzung nach S3 |
|--------|--------------------------|----------------------|
| **Hub (lokale API)** | `resolvePrincipal` Token-Achse (`Principal.kt:125`) + Cookie-Achse (`:148`) | beide **loopback-gated** am Chokepoint → off-loopback kein `MachineOperator`/kein Cookie→OP; deckt ALLE `/api`+WS+Terminal-Operator-Kanten by-construction |
| **Relay (remote Tunnel)** | tunnel-scoped Connector | `TunnelGodTokenGuard` (Port-SET) + `Rr3TunnelGate` (CpJwt∧PoP); statischer Token ist kein RR3-Credential → nie remote |
| **Identity (Kratos)** | Cookie→OPERATOR | S-AAL2b: `browserOperatorPostureEnabled = isLoopbackHost(host)` → off-loopback deaktiviert (bereits gebaut) |
| **BYOA (mitgebrachter Agent, remote)** | müsste über Token/Tunnel kommen | Token-Achse loopback-gated (F1) + Tunnel-Guard + RR3 ⟹ der einzige remote Operator-Pfad ist die **widerrufbare** CP-Session+PoP; der statische Token erreicht BYOA **nie** |

**Load-bearing:** `resolvePrincipal` ist der **einzige** Chokepoint für **beide** Achsen (Token + Cookie). Beide dort zu
gaten = eine **Eigenschaft**, keine Liste — die Reviewer-Bedingung „F1 by-construction, keine neue adjazente Fläche".

---

## 3. (c) Angriffsflächen + fail-closed-Defaults

| # | Angriffsfläche | Fail-closed-Default | Beleg / Anker |
|---|----------------|---------------------|---------------|
| A1 | god-token als **Bearer** auf einem Tunnel-Port | 401 + `finish()` vor Routing | `TunnelGodTokenGuard.kt:58-67` |
| A2 | god-token als **`?token=`** auf einem Tunnel-Port | dito (beide Achsen gedeckt) | `TunnelGodTokenGuard.kt:58` |
| A3 | **NEUER Tunnel-Port** still nicht im Guard-Set | Closure: guard-set ≡ **server-connector-topology** by construction (F2) → neuer Port automatisch gedeckt | S3 (§2.1a) |
| A4 | Client wählt den Connector (Origin/Marker-Spoof) | irrelevant: Diskriminator ist der **lokale Port**, server-seitig | `TunnelGodTokenGuard.kt:22-27,57` |
| A5 | kompromittiertes **Relay** mintet CP-Session an kalten Hub, eigener Key | Device-PoP-Anker + `needs-OOB-warming` (First-Enroll re-walkt TOFU OOB) | Modell-2 §3/§8 |
| A6 | Widerruf-**Registry down/partitioniert** | degradiert auf ≤TTL-Floor via registry-**unabhängige** `exp`; **nie fail-open, nie > Floor** | S4 (§2.2), Modell-2 §11-7b |
| A7 | Widerruf **umgangen** durch Re-Mint (Floor-Dehnung) | Floor = bestehende `exp`, **kein Re-Mint** | S4 (§2.2) |
| A8 | Cross-Hub-Leak: Widerruf reißt nur lokalen Hub ab | aktive Propagierung über ALLE live Sessions | S4 (§2.2), schließt CYP-697 |
| **A9** | **★ F1 — öffentlicher Connector OFF-LOOPBACK: statischer god-token authentifiziert über Netz** (Bearer/`?token=`), da nur die Cookie-Achse loopback-gated ist | **Token-Achse loopback-gated** am Chokepoint (`isLoopbackHost(config.hub.host)`, spiegelt S-AAL2b) → off-loopback kein `MachineOperator` | §1.9, §2.1b; `Principal.kt:125,148` |
| **A10** | **★ F3 — Widerruf-Registry-Eintrag GEFORGT (Integrität, nicht nur Verfügbarkeit)** | Registry trägt **Liveness, NIE Trust-Anker**; Forge kann nur Früh-Widerruf **verpassen** (→ registry-unabhängiger `exp`-Floor), nie Autorität gewähren/verlängern | §2.2 (F3) |
| A11 | Über-Guarding: Loopback-Gate sperrt god-token auf einem **validen LOCAL-LOOPBACK** Connector → lokale UI tot | Gate prüft `isLoopbackHost` (deckt `127.0.0.0/8`/`::1`/`localhost`), NICHT String-`==` → local-loopback bleibt 200 | §2.1 Positiv-Kontrolle |

**Fail-closed-Grundhaltung:** die **sichere Fläche ist der Default** — eine übersehene remote-Achse bekommt **deny**
(S3), ein Registry-Ausfall/-Forge den **≤TTL-Floor / kein-Anker** (S4). Grenze in der **Ableitung** UND im **Default**
(vgl. `safe-but-silent-default`).

---

## 4. (d) Migrations- / Kompat-Impact

- **S3(a) Tunnel-Closure:** heute Singleton (`config.hub.tunnelPort`) → Umstellung auf die **server-connector-topology-
  abgeleitete** Menge ist **verhaltensidentisch**, bis per-Tunnel-Ports existieren. Kein Wire-/Config-/Client-Impact.
- **★ S3(b) Token-Achsen-Loopback-Gate (F1):** für den **unterstützten Default** (`config.hub.host = 127.0.0.1`,
  loopback) **verhaltensidentisch** — der god-token funktioniert lokal weiter. **Impact NUR für off-loopback-Deploys:**
  dort **verliert** der statische Token die Operator-Posture — **das ist der beabsichtigte Fix**, kein Regress: ein
  off-loopback-gebundener statischer Operator-Token IST das Loch. Ein off-loopback-Deploy, der Operator-Zugang will,
  nutzt den revocablen CP-Session+PoP-Pfad (Modell-2). **Am Bau explizit machen (PL-B1-Scan):** kein Deployment darf
  sich auf off-loopback-Token-Operator verlassen.
- **S4:** additiv & rückwärtskompatibel. Der passive **≤TTL-Floor existiert bereits** (CYP-484 + per-Hub-`exp`) → ein
  Hub/Relay ohne das aktive Protokoll degradiert auf **heutiges** Verhalten (lazy ≤TTL). Registry = neuer State, dessen
  Ausfall-/Forge-Modus **der Floor / kein-Anker** ist → kein Regress unter heute.
- **Kill-Switch-Kompat:** `CYPPIE_OPERATOR_TOKEN_DISABLED` unverändert; Interaktion mit F1 = Weiche §5-W4/F6.

---

## 5. Weichen zur Ratifikation (PL + Auftraggeber — NICHT unilateral entschieden)

- **W1 — S3(a)-Closure-Mechanismus.** *Empfehlung:* **exaktes Single-Source** aus der Server-Connector-Topologie
  (F2) — Guard und Server lesen dieselbe Menge, Drift by construction unmöglich. *Alternative:* deny-Default über alle
  Loopback-Connectoren (maximal fail-closed, riskiert lokale-UI-Bruch bei Fehlklassifikation).
- **★ W2 (F5-Split) — die Kern-Grenze, zweigeteilt:**
  - **W2a — null-remote:** Behält der god-token JE eine remote-erreichbare Capability? *Empfehlung:* **NEIN** — null
    remote-Autorität, volle lokale. Die zu ratifizierende Kern-Grenze; alles folgt daraus.
  - **W2b — öffentlicher-Connector-Loopback-Mechanismus (F1-Fix-Form):** **Posture-Gate** am Chokepoint (spiegelt
    S-AAL2b, `role==OP`-Downgrade off-loopback) **vs.** Boot-`require`(host loopback, wenn statischer Token aktiv) **vs.
    beide**. *Empfehlung:* **beide** — Posture-Gate als laufzeit-fail-closed (deckt auch Runtime-Rebind), Boot-`require`
    als fail-LOUD-Klarheit. Single-sourced auf `isLoopbackHost`.
- **W3 — C3-Registry-Placement/Ownership** (relay-/hub-seitig/beide) **+ Widerruf-Nachrichten-Shape.** Konventionelles
  Bau-Detail (Modell-2 §10). Der **Floor** (PL-A1) und die **Integrität** (F3/A10) sind **keine** Weichen.
- **★ W4 (F6) — Kill-Switch × F1-Bootstrap-Fenster.** Der Never-Lock-Out-Pfad (§1.6) lässt den statischen Token tragen,
  **bis** der erste Mensch OPERATOR bootstrappt. **Off-loopback interagiert das mit F1:** ist der öffentliche Connector
  off-loopback, ist der statische Token durch das F1-Gate bereits nicht-operator → das **Bootstrap-Fenster existiert
  off-loopback gar nicht** (fail-closed: kein Bootstrap eines OPERATOR über einen off-loopback statischen Token). *Frage
  zur Ratifikation:* soll ein Deploy, das **null** statische Operator-Autorität will, den Token **voll** deaktivieren
  können (nicht nur downgrade-wenn-Kratos-existiert), und wie wird der **erste** OPERATOR off-loopback gebootstrappt
  (nur über den loopback-lokalen Erstkontakt bzw. CP-Session+PoP)?

---

## 6. Akzeptanz-Zähne, die der Bau tragen MUSS (non-vakuös, mutations-beweisbar)

- **T1 (S3a, Tunnel, beide Achsen):** god-token auf **JEDEM** tunnel-scoped Port → 401 (Bearer **und** `?token=`).
  *Mutation:* Guard-Set auf eine echte Teilmenge schrumpfen → dieser Port authentifiziert → rötet.
- **★ T1b (S3b, F1 — öffentlicher Connector):** *Positiv-Kontrolle eingeengt:* god-token, public-Connector
  **LOOPBACK**-gebunden → **200**. *Negativ-Zahn (NEU):* god-token, public-Connector **OFF-LOOPBACK** (`config.hub.host`
  = z. B. `0.0.0.0`/LAN-IP), Bearer **und** `?token=` → **401**. *Mutation:* das Loopback-Gate auf der Token-Achse
  entfernen (`Principal.kt:125` ungated) → der off-loopback-Token authentifiziert als OPERATOR → **rötet**.
- **T2 (S3a-Closure — F2-korrigierte Quelle):** guard-set ≡ **server-connector-topology** by construction. *Mutation:*
  einen tunnel-scoped Connector zur `embeddedServer`-Deklaration hinzufügen, **ohne** dass die abgeleitete Guard-Menge
  ihn mitzieht → ein Ableitungs-Test rötet (die Mengen divergieren). Muss gegen die **Server**-Connector-Deklaration
  ableiten, nicht gegen eine Client-Bridge-Vorstellung (sonst vakuös).
- **T3 (S4-aktiv, cross-hub):** nach Aussteller-Widerruf → **ALLE** live Hub-Sessions **sofort** STALE/REJECTED (< ≤TTL).
  *Mutation:* Widerruf reißt nur den lokalen Hub ab → 2-Hub-Test, Hub-B-Session überlebt, rötet. **CYP-697 explizit.**
- **T4 (S4-Floor, fail-closed — drei Mutationen):** Registry down → Widerruf degradiert auf ≤TTL-Floor via
  registry-**unabhängigen** `exp`. *Mutationen:* (a) Registry-down → Widerruf ignoriert (fail-open) rötet; (b)
  degradiert **über** ≤TTL rötet; (c) Floor via **Re-Mint** verlängert rötet.
- **★ T4b (F3 — Registry-Integrität):** ein **geforgter** Registry-Eintrag gewährt/verlängert **keine** Autorität — nur
  der per-Hub `AND(Issuer-JWS, Device-PoP)`+`exp`-Anker zählt. *Mutation:* Registry-Eintrag als Trust-Anker
  (statt nur Liveness) lesen → ein Forge gewährt Autorität → rötet.
- **T5 (Zweitfaktor hält — keine Regression):** Issuer-JWS **ohne** Device-PoP über einen gewärmten Hub → **REJECT**
  (Modell-2 §11-1 Drei-Arm, identisch außer PoP). S4 lockert den Anker nicht.
- **★ T6 (F4 — Post-Widerruf-Re-Enroll):** nach einem Widerruf verlangt ein Re-Enroll **weiter** den OOB-PoP-Anker
  (`OobFingerprintConfirmer`), **kein** registry-cached-Shortcut. *Mutation:* Re-Enroll akzeptiert einen
  registry-gecachten Credential ohne frischen Device-PoP/OOB → rötet (Modell-2 §7-C1 „device-recovery = re-TOFU").

---

## 7. Provenance

- **god-token-Identität/Custody:** `routing/Auth.kt:33`, `boot/Secrets.kt:75-76,32`, `boot/BootOrchestrator.kt:354`,
  `auth/Principal.kt:125-132,126`.
- **★ F1-Achsen (Chokepoint):** Token-Achse `auth/Principal.kt:125` (`isOperator→MachineOperator`, ungated); Cookie-Achse
  `:148` (`role==OP && (!aal2 || !browserOperatorPostureEnabled)→null`, S-AAL2b); `browserOperatorPostureEnabled =
  isLoopbackHost(config.hub.host)` in `routing/PlatformWiring.kt`; `isLoopbackHost` `auth/Principal.kt:110`.
- **★ F2-Closure-Quelle:** die `embeddedServer`-Connector-Deklaration `Application.kt:62-63`.
- **Kill-Switch:** `boot/PlatformConfig.kt:99-102`, `routing/PlatformWiring.kt:453`.
- **Containment (Tunnel):** `routing/TunnelGodTokenGuard.kt` (`:22-27,40-41,53,57-67`), Wiring `PlatformWiring.kt:395,425`.
- **Widerruf-Seams:** `transport/TunnelSessionRegistry.kt:34,7` (CYP-484 ②), `controlplane/LiveHubTicketMinter.kt:21-25`.
- **Modell-2-Kontext:** `docs/design/CYP-747-model2-issuer-trust-design.md` @ `db1f879c` (§3, §5, §7, §11-1, §11-7b, §10).
- **N-Tunnel-Kontext:** `docs/design/CYP-536-C1-tunnel-credential-pop-format.md` (§5), `M2-A-ntunnel-…-contract.md` (§C3).

**Rev.1 (2026-07-27, Backend):** Erstfassung.
**Rev.2 (2026-07-27, Backend):** Reviewer-Pass gefoldet — **F1-Blocker** (öffentlicher-Connector-Token-Achse off-loopback
offen; S3 wächst auf zwei Flächen; Loopback-Gate spiegelt S-AAL2b, Eigenschaft am Chokepoint über Hub/Relay/Identity/BYOA
§2.4) · **F2** (Closure-Quelle = Server-Connector-Topologie §2.1a/T2) · **F3** (Registry-Integrität §2.2/A10/T4b) · **F4**
(Post-Widerruf-Re-Enroll OOB-Anker §6-T6) · **F5** (W2-Split → W2a/W2b §5) · **F6** (Kill-Switch-Bootstrap-Fenster × F1
§5-W4). F1 an PL eskaliert; Bau bleibt gated auf PL-Ratifikation der v2. Design-only.
