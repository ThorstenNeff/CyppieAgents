# CYP-747 — God-Token Authority-Boundary: die Security-Invariante, die S3 + S4 gatet (Design-Pass)

> Status: **DESIGN-PASS v1 — zur Ratifikation an PL + Auftraggeber (2 Prinzipale), VOR jedem Bau.** Owner: Backend.
> Bezug: CYP-747 Modell-2 Aussteller-Vertrauen (Design `db1f879c`, `docs/design/CYP-747-model2-issuer-trust-design.md`).
> Basis: develop `80ba0071`. **KEIN Bau-Gate.** Output dieses Docs = die ratifizierbare Invariante; es baut nichts an
> **S3** (god-token `tunnelPorts`-Closure) oder **S4** (C3 aktiver Widerruf). Jede Behauptung ist gegen `80ba0071`
> path:line-belegt — nichts erfunden.
>
> **Warum dieser Pass existiert:** S3 und S4 sind die letzten zwei CYP-747-Slices, und beide fassen **dieselbe Kante**
> an — die Grenze, welche Operator-Autorität auf welcher Fläche erreichbar/überlebbar ist. Eine falsch gezogene Grenze
> (ein ungeschützter Tunnel-Port; ein Widerruf, der bei Registry-Ausfall fail-open geht) reißt still genau die
> Seizure-/Replay-Klasse wieder auf, die Modell-2 schließen soll. Deshalb wird die Grenze **als Eigenschaft ratifiziert,
> bevor** einer der beiden Slices gebaut wird.

---

## 0. Was dieses Doc liefert (und was NICHT)

- **Liefert:** (a) was der god-token HEUTE ist; (b) die tragende Invariante als **oberflächen-agnostische
  Eigenschaft**, plus ihre zwei Spezialisierungen S3/S4; (c) Angriffsflächen + fail-closed-Defaults; (d) Migrations-/
  Kompat-Impact; (e) Weichen zur Ratifikation; (f) non-vakuöse Akzeptanz-Zähne für den adversarialen Review.
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
Lifecycle). **Kein TTL, kein `exp`, kein Widerruf** — er umgeht Op-Session-TTL und aktiven Widerruf strukturell
(die CYP-484-Achse existiert nur für die Kratos-/CP-Session, nicht für den statischen Token).

### 1.4 Lebensdauer

Prozess-Lebensdauer, statisch. Es gibt **keinen** Ablauf- oder Widerrufs-Hebel gegen den statischen Token selbst —
genau das ist der Grund, warum er auf remote-erreichbaren Flächen strukturell verboten sein muss (§2).

### 1.5 Halter

Der **lokale Operator** (Mensch an der Konsole / lokale UI) auf dem **öffentlichen bzw. Loopback-Connector**. Der
**remote Operator nutzt ihn NIE** — er authentifiziert über eine **CP-scoped Kratos-Operator-Session** (schmaler UND
widerrufbar; `TunnelGodTokenGuard.kt:16-21`).

### 1.6 Das Kill-Switch (Never-Lock-Out-Downgrade)

`operatorTokenDisabled` via Env `CYPPIE_OPERATOR_TOKEN_DISABLED` (`PlatformConfig.kt:99-102`, aufgelöst
`PlatformWiring.kt:453`; **nie zur Laufzeit / über kein Endpoint setzbar**). Gesetzt **UND** ein Kratos-OPERATOR
existiert → der statische Token ist **INERT**, herabgestuft zu `MachineAgent(null)` (MEMBER read-tier),
`Principal.kt:125-132`. Never-lock-out: er trägt, bis der erste Mensch OPERATOR bootstrappt.

### 1.7 Containment HEUTE — zwei unabhängige per-Tunnel-Gates

1. **`TunnelGodTokenGuard`** (`server/.../routing/TunnelGodTokenGuard.kt`) — verweigert den god-token (Bearer **UND**
   `?token=`-WS/Query-Fallback) auf dem **tunnel-scoped 127.0.0.1-Connector**. Diskriminator = `call.request.local.
   localPort ∈ tunnelPorts` — ein **server-seitiger Fakt, den der Client nicht beeinflussen kann** (er wählt nie,
   welcher Connector seine Bytes annimmt). Läuft auf `ApplicationCallPipeline.Plugins` **vor** Routing/Route-Auth;
   fail-closed 401 + `finish()`. Auf dem **öffentlichen** Connector unverändert erlaubt (lokale UI).
2. **RR3-Tunnel-Auth-Gate** (`Rr3TunnelGate.authorize`, einmal pro Tunnel vor Bridge-Start) — CpJwt **AND**
   Operator-Device-PoP, beide an die live `h_i` gebunden. Der statische `OPERATOR_TOKEN` ist **kein** valider
   RR3-Credential (Modell-2-Design §5, `db1f879c`).

**Production-Wiring HEUTE:** `installTunnelGodTokenGuard(config.hub.tunnelPort, booted.tokenRegistry::isOperator)`
(`PlatformWiring.kt:425`) → **EIN** `config.hub.tunnelPort` (Default `8786`, `PlatformConfig.kt:137`) →
Singleton-Port-Set (`installTunnelGodTokenGuardOnPorts({ setOf(tunnelPort()) }, …)`, `TunnelGodTokenGuard.kt:40-41`).
MVP-Topologie: **alle N Tunnel bridgen in EINEN geteilten Tunnel-Connector** (`LoopbackBridge loopbackPort =
config.hub.tunnelPort`, `PlatformWiring.kt:395`), daher deckt das Singleton-Set alle N. Der Guard ist bereits als
**Set** geschrieben (`installTunnelGodTokenGuardOnPorts`, `TunnelGodTokenGuard.kt:53`), damit ein per-Tunnel-Port-
Design keinen Tunnel-Port **still ungeschützt** lassen kann — eine **positive Allowlist** über Tunnel-Ports, keine
Einzel-Gleichheit (die eingefrorene WS6-Haltung).

### 1.8 Widerruf HEUTE

- **Lokal-aktiv:** `TunnelSessionRegistry.revokeOperator(operatorId)` (`transport/TunnelSessionRegistry.kt:34`) reißt
  **sofort** jeden live Tunnel für einen Operator auf **DIESEM** Hub ab — kein Phone-Home, seltener Push. Plus passiver
  Op-Session-TTL-Teardown im Handler (CYP-484).
- **Die Lücke (S4):** nichts propagiert einen **Aussteller-/Relay-Level-Widerruf** an die **ANDEREN** Hubs, auf denen
  der Operator live Sessions hält. Cross-Hub-Widerruf = heute lazy ≤TTL, kein Push, keine Registry
  (Modell-2-Design §7, `db1f879c`).

---

## 2. (b) Die tragende Invariante — als oberflächen-agnostische EIGENSCHAFT

> **★ INVARIANTE (Eigenschaft, KEINE Flächen-Aufzählung):** *Statische, ungescopte, nicht-widerrufbare
> Operator-Autorität (der god-token) ist erreichbar **ausschließlich** auf der **lokalen** Vertrauensfläche (dem
> Loopback-/öffentlichen Connector, den der Mensch an der Konsole hält) und ist auf **JEDER** remote-erreichbaren Fläche
> **strukturell verweigert**. Jede Operator-Autorität, die remote **doch** erreichbar ist, MUSS (1) aussteller-gemintet
> und **hub-lokal via Device-PoP verankert** sein (der Anti-Seizure-Zweitfaktor) **UND** (2) **aktiv widerrufbar** mit
> einem fail-closed **≤TTL-Floor**. Es existiert **keine** Fläche, auf der ein Credential zugleich remote-erreichbar UND
> (ungescopt ODER nicht-widerrufbar) ist.*

Diese **eine** Eigenschaft gatet **beide** Slices; jeder Slice erzwingt eine Hälfte **strukturell** (by construction),
nicht per Aufzählung:

### 2.1 S3 = die „strukturell-verweigert-remote"-Hälfte (der `tunnelPorts`-Closure)

**Ziel:** die Guard-Port-Menge ist **beweisbar gleich** der VOLLEN Menge tunnel-scoped Connector-Ports — **abgeleitet /
single-sourced** aus derselben Stelle, an der die Bridge ihre Loopback-Connectoren bindet, nicht ein handgepflegtes
Literal. **Closure heißt:** guard-set ≡ bridge-set **by construction** — Drift ist **unmöglich**, nicht bloß getestet.

- Heute Singleton (ein geteilter Connector) → verhaltensidentisch, bis per-Tunnel-Ports existieren (§4).
- **Ports/Capabilities, die NIE remote erreichbar sein dürfen** (die NEVER-Liste als Konsequenz der Eigenschaft, nicht
  als Ersatz für sie):
  - der statische `OPERATOR_TOKEN` als **Bearer ODER `?token=`** auf **irgendeinem** tunnel-scoped Connector;
  - jede Capability, die Op-Session-TTL / aktiven Widerruf über eine remote Fläche umgeht;
  - die ungescopte `MachineOperator`-Autorität (ACL-Mutation, Agent/Token-Mint, config/apikey-Writes, Lifecycle) —
    remote **nur** über den widerrufbaren CP-Session-Pfad, **nie** über den statischen Token.
- **Positiv-Kontrolle (nicht brechen):** der god-token auf dem **öffentlichen** Connector bleibt volle lokale
  OPERATOR-Autorität — die lokale UI darf S3 nicht kaputt machen.

### 2.2 S4 = die „aktiv-widerrufbar-mit-Floor"-Hälfte (C3 aktiver Widerruf)

**Ziel:** aktiver Aussteller-Widerruf im **Hub↔Relay-Protokoll** + **Cross-Hub-Session-Registry** (C3). Widerruf →
Push **STALE/REJECTED über ALLE** live Hub-Sessions des Operators **sofort** (nicht erst ≤TTL). **Schließt CYP-697**
(Operator aus Verzeichnis entfernt → Agenten/Sessions verstummen sofort).

- **Der aktive Pfad** verallgemeinert das bestehende lokale `TunnelSessionRegistry.revokeOperator`
  (`TunnelSessionRegistry.kt:34`) von **ein-Hub** auf **Cross-Hub** (via Relay-propagierten Widerruf).
- **★ Fail-closed-Floor (PL-A1, Modell-2-Design §11-7b):** fällt die C3-Registry aus / partitioniert, degradiert die
  Widerruf-Wirksamkeit auf den **≤TTL-lazy-Floor — NIE länger, NIE fail-open**. **Strukturell erzwungen:** der Floor
  wird vom **registry-UNABHÄNGIGEN** passiven per-Hub-`exp`-Check (`NOT_EXPIRED`) getragen — eine Partition kann den
  Floor nicht mit-abschalten. Der Floor = die **bestehende** `exp` schon ausgestellter Credentials, **kein Re-Mint**
  (sonst dehnt sich der Floor still).
- **Op-Session-TTL ist single-sourced** (`RemoteRelayWiring.resolveOpSessionTtlMs` / `DEFAULT_OP_SESSION_TTL_MS`,
  Override `CYPPIE_OP_SESSION_TTL_MIN`): sowohl das Ticket-`exp` (`LiveHubTicketMinter.kt:21-25`) als auch die
  Hub-Tunnel-Cap lesen dieselbe Quelle (CYP-563 schloss die Drift). Der Floor erbt diese eine Quelle.
- **Der Zweitfaktor bleibt AND-gekoppelt:** die remote-erlaubte Autorität ist CP-Session **AND** Device-PoP über den
  hub-lokalen Anker (`Rr3TunnelGate`, Modell-2-Design §3/§5). S4 fasst das PoP-Gate **nicht** an — Widerruf ist ein
  zusätzlicher Hebel, kein Ersatz für den Anker.

### 2.3 Warum beide GEMEINSAM gated sind

S3 ohne S4: der statische Token ist remote verweigert, aber die remote **Ersatz**-Autorität (CP-Session) bleibt im
≤TTL-Fenster wirkungslos widerrufbar → Kompromittierung im Fenster hat null Hebel. S4 ohne S3: aktiver Widerruf
existiert, aber ein ungeschützter Tunnel-Port lässt den **nicht-widerrufbaren** statischen Token durch → Widerruf ist
gegen genau den gefährlichsten Credential wirkungslos. **Nur zusammen** ziehen sie die Grenze vollständig: der
god-token behält **volle** Autorität lokal, **null** Autorität remote (S3); der remote Ersatz ist **bounded**
(gescopt + PoP-verankert + aktiv widerrufbar mit Floor, S4).

---

## 3. (c) Angriffsflächen + fail-closed-Defaults

| # | Angriffsfläche | Fail-closed-Default | Beleg / Anker |
|---|----------------|---------------------|---------------|
| A1 | god-token als **Bearer** auf einem Tunnel-Port | 401 + `finish()` vor Routing | `TunnelGodTokenGuard.kt:58-67` |
| A2 | god-token als **`?token=`** WS/Query-Fallback auf einem Tunnel-Port | dito (beide Achsen gedeckt) | `TunnelGodTokenGuard.kt:58` |
| A3 | **NEUER Tunnel-Port** (per-Tunnel-Design) still nicht im Guard-Set | **Closure:** guard-set ≡ bridge-set by construction → neuer Port automatisch gedeckt | S3 (§2.1) — **das Kern-Bau-Ziel** |
| A4 | Client versucht, den Connector zu wählen (Origin/Marker-Spoof) | irrelevant: Diskriminator ist der **lokale Port**, server-seitig | `TunnelGodTokenGuard.kt:22-27,57` |
| A5 | kompromittiertes **Relay** mintet CP-Session an kalten Hub, präsentiert eigenen Key | Device-PoP-Anker + `needs-OOB-warming` (First-Enroll re-walkt TOFU OOB) | Modell-2-Design §3/§8 (`db1f879c`) |
| A6 | Widerruf-**Registry down/partitioniert** | degradiert auf ≤TTL-Floor via registry-**unabhängige** `exp`; **nie fail-open, nie > Floor** | S4 (§2.2), Modell-2 §11-7b |
| A7 | Widerruf **umgangen** durch Re-Mint (Floor-Dehnung) | Floor = bestehende `exp`, **kein Re-Mint** | S4 (§2.2) |
| A8 | Cross-Hub-Leak: Widerruf reißt nur lokalen Hub ab, Hub-B-Session überlebt | aktive Propagierung über ALLE live Sessions (die S4-Kernwirksamkeit) | S4 (§2.2), schließt CYP-697 |
| A9 | Über-Guarding: Guard verweigert god-token auf dem **öffentlichen** Connector → lokale UI tot | exakter Port-Diskriminator; single-source verhindert Fehlklassifikation | Weiche **W1** (§5) |

**Fail-closed-Grundhaltung (beide Slices):** die **sichere Fläche ist der Default**. Ein übersehener/zukünftiger
Tunnel-Port bekommt **deny** (S3-Closure), ein Registry-Ausfall bekommt den **≤TTL-Floor** (S4) — die Grenze wird in
der **Ableitung** UND im **Default** fail-closed gezogen, nicht bloß im Happy-Path (vgl. `safe-but-silent-default`).

---

## 4. (d) Migrations- / Kompat-Impact

- **S3:** Production ist **heute Singleton** (`config.hub.tunnelPort`, ein geteilter Connector) → die Umstellung des
  Guards auf ein **abgeleitetes Set** ist **verhaltensidentisch**, bis per-Tunnel-Ports existieren. **Kein Wire-Change,
  kein Config-Change, kein Client-Impact.** Einzige Kompat-Bedingung: **kein** Deployment darf sich darauf verlassen,
  dass der god-token einen Loopback-Connector erreicht, der **auch** ein Tunnel-Port ist (das wäre bereits heute das
  Loch). Am Bau verifizieren (PL-B1-Scan).
- **S4:** additiv & rückwärtskompatibel. Der passive **≤TTL-Floor existiert bereits** (CYP-484 + per-Hub-`exp`), also
  degradiert ein Hub/Relay, der das aktive Widerruf-Protokoll **noch nicht** spricht, auf **exakt das heutige
  Verhalten** (lazy ≤TTL). Die Registry ist **neuer State**, dessen Ausfallmodus **der Floor** ist → ein
  Registry-Rollout kann nicht unter heute regredieren. Neue Hub↔Relay-Nachricht(en) für den Widerruf-Push; Shape ist
  Bau-Detail (§5-W3), aber der Floor (PL-A1) ist ratifizierte Randbedingung, keine Weiche.
- **Kill-Switch-Kompat:** `CYPPIE_OPERATOR_TOKEN_DISABLED` bleibt unverändert; seine Interaktion mit der Grenze ist
  Weiche **W4** (§5).

---

## 5. Weichen zur Ratifikation (PL + Auftraggeber — NICHT unilateral entschieden)

> Design-Pass-first: die folgenden Punkte gehen an die zwei Prinzipale. Ich empfehle, entscheide aber nicht.

- **W1 — S3-Closure-Mechanismus.** *Empfehlung:* **exaktes Single-Source** (guard-set ≡ bridge-set, aus der
  Connector-Topologie abgeleitet) — Drift by construction unmöglich, öffentlicher Connector exakt ausgenommen (A9
  bleibt geschlossen). *Alternative:* deny-Default über **alle** Loopback-Connectoren (maximal fail-closed, aber
  riskiert lokale-UI-Bruch, wenn ein öffentlicher Loopback-Port fehlklassifiziert). Trade offen zur Ratifikation.
- **W2 — Behält der god-token JE eine remote-erreichbare Capability?** *Empfehlung:* **NEIN** — null remote-Autorität,
  volle lokale. Das ist die **Kern-Grenze**, die ratifiziert werden muss; alles andere folgt daraus.
- **W3 — C3-Registry-Placement/Ownership** (relay-seitig vs. hub-seitig vs. beide) **+ Widerruf-Nachrichten-Shape.**
  Konventionelles Bau-Detail (Modell-2-Design §10: „PO/Backend"). Der **Floor** (PL-A1) ist **keine** Weiche.
- **W4 — Kill-Switch × Grenze.** Soll ein Deploy, das **null** statische Operator-Autorität will, den Token **voll**
  deaktivieren können (nicht nur downgrade-wenn-Kratos-existiert)? Zur Ratifikation.

---

## 6. Akzeptanz-Zähne, die der Bau tragen MUSS (non-vakuös, mutations-beweisbar)

> Für den adversarialen Review. Jeder Zahn nennt seine Mutation (die ihn EXAKT rötet) — ein Zahn ohne rötende Mutation
> ist vakuös.

- **T1 (S3, beide Achsen):** god-token auf **JEDEM** tunnel-scoped Port → 401 (Bearer **und** `?token=`). *Mutation:*
  Guard-Set auf eine echte Teilmenge schrumpfen (einen Tunnel-Port droppen) → dieser Port authentifiziert den god-token
  → rötet. **Positiv-Kontrolle:** god-token auf dem **öffentlichen** Connector → weiter 200 (lokale UI intakt).
- **T2 (S3-Closure — die Kern-Eigenschaft):** guard-set ≡ bridge-set **by construction**. *Mutation:* einen
  Tunnel-Connector hinzufügen, **ohne** seinen Port in die abgeleitete Guard-Menge → ein Ableitungs-Test rötet (die
  Mengen divergieren). Muss eine **abgeleitete Gleichheit** sein, keine zwei Literale.
- **T3 (S4-aktiv, cross-hub):** nach Aussteller-Widerruf → **ALLE** live Hub-Sessions des Operators **sofort**
  STALE/REJECTED (< ≤TTL). *Mutation:* Widerruf reißt nur den **lokalen** Hub ab → 2-Hub-Test, in dem die Hub-B-Session
  überlebt, rötet. **CYP-697-Fall explizit** (Operator entfernt → Agenten verstummen).
- **T4 (S4-Floor, fail-closed — drei Mutationen):** Registry down → Widerruf degradiert auf ≤TTL-Floor, getragen vom
  registry-**unabhängigen** `exp`. *Mutationen:* (a) Registry-down → Widerruf ignoriert (**fail-open**) rötet; (b)
  Registry-down → degradiert **über** ≤TTL hinaus rötet; (c) Floor via **Re-Mint** verlängert rötet.
- **T5 (Zweitfaktor hält — keine Regression):** Issuer-JWS **ohne** Device-PoP über einen gewärmten Hub → **REJECT**
  (das Modell-2 §11-1 Drei-Arm-Differential, identisch außer PoP). Stellt sicher, dass der widerrufbare remote-Pfad den
  Anti-Seizure-Anker weiter **AND**-koppelt (S4 lockert ihn nicht).

---

## 7. Provenance

- **god-token-Identität/Custody:** `server/.../routing/Auth.kt:33`, `boot/Secrets.kt:75-76,32`,
  `boot/BootOrchestrator.kt:354`, `auth/Principal.kt:125-132,126`.
- **Kill-Switch:** `boot/PlatformConfig.kt:99-102`, `routing/PlatformWiring.kt:453`.
- **Containment:** `routing/TunnelGodTokenGuard.kt` (v.a. `:22-27,40-41,53,57-67`), Wiring
  `routing/PlatformWiring.kt:395,425`, `boot/PlatformConfig.kt:137`.
- **Widerruf-Seams:** `transport/TunnelSessionRegistry.kt:34` (lokal-aktiv), `controlplane/LiveHubTicketMinter.kt:21-25`
  (Op-Session-TTL single-source), `transport/TunnelSessionRegistry.kt:7` (CYP-484 ②).
- **Modell-2-Kontext:** `docs/design/CYP-747-model2-issuer-trust-design.md` @ `db1f879c` (§3 Invariante, §5 Trust-Anker,
  §7 Widerruf/C3, §11-1 Drei-Arm, §11-7b Floor/PL-A1, §10 Weichen-Status).
- **N-Tunnel-Kontext:** `docs/design/CYP-536-C1-tunnel-credential-pop-format.md` (§5 god-token-reject per Tunnel),
  `docs/design/M2-A-ntunnel-workstream-split-and-contract.md` (§C3 TunnelPoolState).

**Rev.1 (2026-07-27, Backend):** Erstfassung. Design-only, zur Ratifikation an PL + Auftraggeber vor Bau von S3/S4.
