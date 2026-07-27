# CYP-747 — God-Token Authority-Boundary: die Security-Invariante, die S3 + S4 gatet (Design-Pass)

> Status: **DESIGN-PASS v3.3 — zur Ratifikation an PL + Auftraggeber (2 Prinzipale), VOR jedem Bau.** Owner: Backend.
> (v3.1: V3-1 `Principal:132`=MEMBER-GRANT richtungs-gegatet+T1d. v3.2: Schicht A SOURCE-keyed (roh-Boolean #3 + `:132`
> MEMBER mitgedeckt). **v3.3 (4. Reviewer-Re-Review): Schicht A = GO; Schicht-B-Value-Compare-Closure korrigiert (V3.2-1)**
> — das Secret liegt in **3 Holdern**, 2 davon PUBLIC (`Secrets.operatorToken`, `CommConfig.operatorToken`) → private-val
> deckte nur 1; **Fix A (gewählt): alle 3 klassen-`private` (NICHT `internal`!) + enge ctor-Consumer + `hasOperator`-Bool**
> → by-construction terminal. Siehe Rev.3.1/3.2/3.3. Layer-A/V3-1/F2–F6 unverändert.)
> Bezug: CYP-747 Modell-2 Aussteller-Vertrauen (Design `db1f879c`, `docs/design/CYP-747-model2-issuer-trust-design.md`).
> Basis: develop `4db7cac4`. **KEIN Bau-Gate.** Output dieses Docs = die ratifizierbare Invariante; es baut nichts an
> **S3** (god-token remote-Verweigerungs-Closure) oder **S4** (C3 aktiver Widerruf).
>
> **★ Rev.3 korrigiert einen F1-RESIDUAL-BLOCKER (Reviewer-Re-Review, am Objekt bestätigt) — und ist ein ehrlicher
> Selbstbefund:** v2 §2.4 behauptete `resolvePrincipal` sei der **EINZIGE** Token→OP-Chokepoint. Das war **empirisch
> falsch — ich habe es behauptet, nicht verifiziert.** Der god-token→OP-Grant läuft über das **geteilte Prädikat**
> `TokenRegistry.isOperator` (`Auth.kt:33`), konsumiert an **≥5 Grant-Nähten**; v2 gated nur **eine** (`Principal.kt:125`).
> Die anderen sind off-loopback am öffentlichen Connector **UNGEGATED** (empirisch enumeriert, §1.9). Das ist dieselbe
> „Property am EINEN Caller statt an der GETEILTEN Naht"-Krankheit eine Ebene raus (CYP-698/719/815/819). **v3-Fix
> by-construction:** das Loopback-Gate sitzt an der **geteilten Quelle** (ein `god-token→OP-eligible`-Prädikat,
> single-sourced `isLoopbackHost(config.hub.host)` wie S-AAL2b), so dass **ALLE Konsumenten den Gate ERBEN** — plus ein
> **Token-Achsen-CLOSURE-Zahn** (enumeriert alle Grant-Aufrufer; MUT ungegateter Aufrufer → rot), analog zum bereits
> gebauten `Cyp747OperatorAuthorityClosureTest` der Cookie-Achse. **F1 an PL eskaliert — Exposition GRÖSSER als gedacht**
> (PTY-Take-over, Agent-Stream-Beobachtung, Event-Log-Egress inkl. Message-BODIES / CYP-432-Vertraulichkeitsgrenze, §1.9).
> **Rev.2 folds** F1 (v2-Fassung) + F2–F6 (Closure-Quelle, Registry-Integrität, Post-Widerruf-Re-Enroll, Weichen-Split,
> Kill-Switch-Bootstrap-Fenster). F2–F6 stehen unverändert; Rev.3 fasst nur die F1-Familie (§1.9/§2.1b/§2.4/§6) neu.
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

### 1.9 ★ F1 (BLOCKER) — die Invariante ist HEUTE NICHT erzwungen: die god-token-TOKEN-Achse ist an ≥5 Nähten offen

Der öffentliche Connector bindet **`config.hub.host`** (`Application.kt:62`) — **überschreibbar** (Default `127.0.0.1`,
aber Deploy-/Env-setzbar auf `0.0.0.0` / eine LAN-/Public-IP). Der god-token→OPERATOR-**Grant** läuft über das
**geteilte Prädikat** `TokenRegistry.isOperator(token)` (`Auth.kt:33`, reine String-Gleichheit) und wird an **mehreren
Grant-Nähten** konsumiert. **S-AAL2b gated NUR die Cookie-Achse** (`Principal.kt:148`, via `browserOperatorPostureEnabled
= isLoopbackHost(host)`); **die Token-Achse ist an KEINER Naht loopback-gated.** Empirisch enumeriert (develop
`4db7cac4`, `grep isOperator`) — off-loopback am öffentlichen Connector alle UNGEGATED, Bearer **und** `?token=`:

| # | Grant-Naht | Effekt off-loopback | Fläche |
|---|-----------|---------------------|--------|
| 1 | `Principal.kt:125` `isOperator(bearer)→MachineOperator` | volle OPERATOR-API-Autorität | `/api/*` + WS |
| 2 | `TerminalAccess.kt:173` `isOperator(token)→TerminalPrincipal.Operator` | **volle PTY-Take-over** | `/ws/terminal?token=<god>` |
| 3 | `AgentSocket.kt:65` `isOperator(token)→ true` | **jeden Agenten-Stream beobachten** | `/ws/agent` |
| 4 | `Auth.kt:87` `participantFor: isOperator(token)→OPERATOR_ID` → `EventAclFilter:40 if(isOperator) return true` | **Event-Log-Egress inkl. Message-BODIES** (CYP-432-Vertraulichkeitsgrenze) | `/ws/events` + `/api/events` |
| 5 | `Principal.kt:170` `resolveAuthState: isOperator(bearer)→AuthMe(OPERATOR)` | `/me` behauptet OPERATOR (Split-Brain) | `/api/auth/me` |

**⟹ v2 war falsch:** v2 gated nur Naht #1 und behauptete `resolvePrincipal` sei der einzige Pfad. Tatsächlich ist die
GETEILTE QUELLE `isOperator`; #2–#5 umgehen `resolvePrincipal` komplett. Die Exposition ist **größer als „nur die
API-Rolle"** — sie umfasst PTY-Take-over, Agent-Stream-Beobachtung und Message-Body-Egress. **Fix = §2.1(b) an der
geteilten Quelle** (Symmetrie zu S-AAL2b, aber am Prädikat, nicht am Caller).

> **Wichtige Trennung (empirisch): IDENTITÄT ≠ ELIGIBILITÄT.** Die **einzige** host-unabhängige **REJECT-Identität**
> ist `PlatformWiring.kt:425` (reicht `isOperator` an den `TunnelGodTokenGuard`, um den god-token auf Tunnel-Ports zu
> **verweigern** — identifiziert, grantet nicht). Der Loopback-Gate gehört an die **Eligibilität**, **nicht** an diese
> rohe Reject-Identität — sonst bricht der Reject-Guard.
>
> **★ V3-1 (Reviewer-Re-Review, BLOCKER, bestätigt): `Principal.kt:132` ist KEINE reine Identität — es ist ein
> MEMBER-GRANT.** `:132 if (isOperator(bearer)) return MachineAgent(null)` wird erreicht, wenn `:125` NICHT griff. Mit
> dem operatorEligible-Gate an `:125` gilt off-loopback: `:125` skip → `:128 agentFor`=null → **`:132` → MEMBER, NICHT
> 401** = **grantet cross-agent `/api/events`-Metadaten übers Netz** (der CYP-234b-2-Kommentar direkt darunter warnt
> selbst davor). ⟹ `:132` MUSS ebenfalls gegatet werden — **aber richtungs-diskriminierend:** off-loopback → durchfallen
> → 401; on-loopback Kill-Switch (`operatorTokenDisabled ∧ hasOperator`) → **weiter MEMBER-Downgrade** (der legitime
> never-lock-out-Bootstrap-Fall, den der Fix ERHALTEN muss). Fix + Zähne: §2.1b / §6-T1d.

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

**(b) ★ god-token-TOKEN-Achse — der F1-Fix an der GETEILTEN QUELLE (v3-korrigiert, spiegelt S-AAL2b).** Der Loopback-
Gate sitzt **NICHT** an einem Caller (`resolvePrincipal`), sondern am **geteilten god-token→OP-eligible-Prädikat**, so
dass **alle** Grant-Nähte (§1.9 #1–#5) ihn **erben**:

- Ein **einziges** Eligibilitäts-Prädikat `operatorEligible(token) = isOperator(token) ∧ loopbackPosture`, wobei
  `loopbackPosture = isLoopbackHost(config.hub.host)` — **single-sourced**, dasselbe Signal wie die Cookie-Achse
  (`browserOperatorPostureEnabled`, S-AAL2b). Off-loopback → `operatorEligible == false` an **jeder** Naht → kein
  `MachineOperator` / keine `TerminalPrincipal.Operator` / kein `/ws/agent`-Grant / kein `OPERATOR_ID` / kein `/me`-OP
  (fail-closed).
- **ALLE Grant-Konsumenten (#1–#5) routen über `operatorEligible`, nicht über rohes `isOperator`.** Die **rohe
  Identität** `isOperator` (host-unabhängig) bleibt **NUR** für den einen **REJECT**-Konsumenten (`TunnelGodTokenGuard`-
  Reject `PlatformWiring:425`) — der identifiziert den Token, grantet nicht.
- **★ V3-1 — `Principal:132` (Kill-Switch-Downgrade) ist ein MEMBER-GRANT und wird MIT-gegatet, richtungs-diskriminierend:**
  `if (isOperator(bearer) && loopbackPosture) return MachineAgent(null)`. Off-loopback → durchfallen → **401** (volle
  Denial, NICHT MEMBER — schließt das `/api/events`-Metadaten-Leck). On-loopback + Kill-Switch → **weiter MEMBER**
  (never-lock-out-Bootstrap ERHALTEN). Zwei Richtungen = zwei Zähne (§6-T1d), damit keine Naht die andere absorbiert.
- **Als Eigenschaft an der geteilten Naht, nicht als Kanten-Liste:** weil alle Grant-Nähte **dieselbe** Quelle
  konsumieren, deckt ein Gate dort **alle** by-construction — eine 6., zukünftige Grant-Naht, die `operatorEligible`
  aufruft, erbt den Gate automatisch (Reviewer-Kriterium: F1 by-construction, keine neue adjazente Fläche). Der
  **Closure-Zahn** (§6) erzwingt genau das: jeder god-token→OP-Grant-Aufrufer MUSS `operatorEligible` (nicht rohes
  `isOperator`) nehmen.
- **Ergänzend (Weiche §5-W2b):** Boot-`require`, dass bei aktivem statischem Token der Host loopback ist — fail-LOUD-
  Klarheit zusätzlich zum laufzeit-fail-closed-Gate.

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

### 2.4 ★ Die Eigenschaft an der GETEILTEN QUELLE über Hub / Relay / Identity / BYOA (v3-korrigiert)

F1 wird **als Eigenschaft am geteilten Prädikat** geschlossen, nicht per-Caller. **Die geteilte Quelle sind ZWEI
Prädikate** (empirisch, §1.9): `operatorEligible` (Token-Achse, alle Grant-Nähte) und `browserOperatorPostureEnabled`
(Cookie-Achse, S-AAL2b) — **beide** single-sourced auf `isLoopbackHost(config.hub.host)`. Explizit über die vier
Architektur-Flächen (Reviewer-Kriterium: keine NEUE adjazente Fläche):

| Fläche | Operator-Autoritäts-Pfad (geteilte Quelle) | Durchsetzung nach S3 |
|--------|--------------------------------------------|----------------------|
| **Hub (lokale API + WS)** | Token-Achse: **`operatorEligible`** an ALLEN Grant-Nähten (`Principal:125/170`, `TerminalAccess:173`, `AgentSocket:65`, `Auth:87`→Events); Cookie-Achse: `Principal:148` | beide **loopback-gated an der geteilten Quelle** → off-loopback KEIN Grant an keiner Naht; deckt `/api`+`/ws/terminal`+`/ws/agent`+`/ws/events`+`/me` by-construction |
| **Relay (remote Tunnel)** | tunnel-scoped Connector | `TunnelGodTokenGuard` (Port-SET, nutzt **rohe** `isOperator`-Identität zum Reject) + `Rr3TunnelGate` (CpJwt∧PoP); statischer Token kein RR3-Credential → nie remote |
| **Identity (Kratos)** | Cookie→OPERATOR | S-AAL2b: `browserOperatorPostureEnabled = isLoopbackHost(host)` → off-loopback deaktiviert (bereits gebaut) |
| **BYOA (mitgebrachter Agent, remote)** | müsste über Token/Tunnel kommen | `operatorEligible` loopback-gated (F1) + Tunnel-Guard + RR3 ⟹ der einzige remote Operator-Pfad ist die **widerrufbare** CP-Session+PoP; der statische Token erreicht BYOA **nie** |

**Load-bearing (v3-Korrektur):** der einzige Chokepoint ist **NICHT** `resolvePrincipal` (v2-Fehler) — es ist das
**geteilte Prädikat** `operatorEligible` (Token) neben `browserOperatorPostureEnabled` (Cookie). Beide **an der Quelle**
zu gaten = eine **Eigenschaft**, keine Liste; der Closure-Zahn (§6) erzwingt, dass jeder Grant-Aufrufer die gegatete
Quelle nimmt — genau die Verifikation, die v2 fehlte („behauptet, nicht verifiziert").

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
| **A9** | **★ F1 (RESIDUAL) — off-loopback authentifiziert der statische god-token an ≥5 Grant-Nähten** (API, `/ws/terminal` PTY, `/ws/agent`, `/ws/events`+`/api/events` **Message-BODIES**/CYP-432, `/me`), da NUR die Cookie-Achse loopback-gated ist | **god-token→OP-eligible-Prädikat** loopback-gated an der **geteilten Quelle** → ALLE Grant-Nähte erben; Closure-Zahn erzwingt es | §1.9, §2.1b, §2.4; `Auth.kt:33`, `Principal:125/170`, `TerminalAccess:173`, `AgentSocket:65`, `Auth:87` |
| **A10** | **★ F3 — Widerruf-Registry-Eintrag GEFORGT (Integrität, nicht nur Verfügbarkeit)** | Registry trägt **Liveness, NIE Trust-Anker**; Forge kann nur Früh-Widerruf **verpassen** (→ registry-unabhängiger `exp`-Floor), nie Autorität gewähren/verlängern | §2.2 (F3) |
| A11 | Über-Guarding: Loopback-Gate sperrt god-token auf einem **validen LOCAL-LOOPBACK** Connector → lokale UI tot | Gate prüft `isLoopbackHost` (deckt `127.0.0.0/8`/`::1`/`localhost`), NICHT String-`==` → local-loopback bleibt 200 | §2.1 Positiv-Kontrolle |

**Fail-closed-Grundhaltung:** die **sichere Fläche ist der Default** — eine übersehene remote-Achse bekommt **deny**
(S3), ein Registry-Ausfall/-Forge den **≤TTL-Floor / kein-Anker** (S4). Grenze in der **Ableitung** UND im **Default**
(vgl. `safe-but-silent-default`).

---

## 4. (d) Migrations- / Kompat-Impact

- **S3(a) Tunnel-Closure:** heute Singleton (`config.hub.tunnelPort`) → Umstellung auf die **server-connector-topology-
  abgeleitete** Menge ist **verhaltensidentisch**, bis per-Tunnel-Ports existieren. Kein Wire-/Config-/Client-Impact.
- **★ S3(b) Token-Achsen-Loopback-Gate an der geteilten Quelle (F1):** für den **unterstützten Default**
  (`config.hub.host = 127.0.0.1`, loopback) **verhaltensidentisch an ALLEN Grant-Nähten** (#1–#5) — der god-token
  funktioniert lokal überall weiter (API, PTY, Agent-Stream, Events, /me). **Impact NUR off-loopback:** dort verliert der
  statische Token die Operator-Posture an **allen** Nähten gleichzeitig (das geteilte Prädikat) — **der beabsichtigte
  Fix**, kein Regress: ein off-loopback statischer Operator-Token IST das Loch (inkl. Message-Body-Egress). Off-loopback-
  Operator läuft über den revocablen CP-Session+PoP-Pfad. **Refactor-Impact:** die 5 Grant-Nähte wechseln von rohem
  `isOperator` auf `operatorEligible` — mechanisch, aber **breiter als v2 dachte** (5 statt 1). Der Closure-Zahn (§6)
  fixiert die Vollständigkeit. **Am Bau (PL-B1-Scan):** kein Deployment darf sich auf off-loopback-Token-Operator verlassen.
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
  - **W2b — öffentlicher-Connector-Loopback-Mechanismus (F1-Fix-Form) — v3: an der GETEILTEN QUELLE.** Das Gate sitzt
    am **geteilten `operatorEligible`-Prädikat** (nicht an je-Caller), so dass alle 5 Grant-Nähte erben. Form: **Posture-
    Gate** (spiegelt S-AAL2b, off-loopback → `operatorEligible == false`) **∧/∨ Boot-`require`**(host loopback, wenn
    statischer Token aktiv). *Empfehlung:* **beide** — Posture-Gate laufzeit-fail-closed (deckt Runtime-Rebind),
    Boot-`require` fail-LOUD. Single-sourced auf `isLoopbackHost`. **Nuance (empirisch, §1.9):** die rohe `isOperator`-
    Identität (host-unabhängig) bleibt für Reject-Guard/Kill-Switch — Gate NUR an der Eligibilität.
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
- **★ T1b (S3b, F1 — öffentlicher Connector, ALLE Grant-Nähte):** *Positiv-Kontrolle:* god-token, public-Connector
  **LOOPBACK** → **200/grant** an jeder Naht. *Negativ-Zähne (NEU, PRO Naht):* god-token, public-Connector
  **OFF-LOOPBACK** (`config.hub.host` = `0.0.0.0`/LAN-IP), Bearer **und** `?token=` → **deny** an **#1 `/api`** (401),
  **#2 `/ws/terminal`** (kein `TerminalPrincipal.Operator`), **#3 `/ws/agent`** (1008), **#4 `/ws/events`+`/api/events`**
  (KEINE fremden Message-Bodies — CYP-432), **#5 `/me`** (nicht OPERATOR). *Mutation:* das Loopback-Gate an der
  geteilten Quelle (`operatorEligible`) entfernen → **alle 5** authentifizieren off-loopback → rötet.
- **★ T1c (S3b-CLOSURE — V3.2: SOURCE-keyed, shape-blind-frei, ZWEI Schichten):** die v3.1-Fassung keyte auf **4
  getypte Operator-Konstruktionen** (`MachineOperator`/`TerminalPrincipal.Operator`/`OPERATOR_ID`/`AuthMe(OPERATOR)`) —
  deckt **NICHT** alle Grant-Shapes: `AgentSocket:65` grantet ein rohes **`Boolean`** (`isOperator(token)→true`), `:132`
  ein **`MachineAgent(null)`** (MEMBER); beide sind keine getypte Operator-Konstruktion → ein 4-Typ-Key ließe sie durch
  (und der „T1c flaggt `:132` selbst"-Bonus war inakkurat). Die Invariante ist **„zero remote" INKL. MEMBER + Boolean**.
  **Fix — auf die QUELLE keyed, zwei Schichten:**
  - **(A) `isOperator(`-Aufruf-Closure:** nach dem V3-1-Fix wird `TokenRegistry.isOperator(` an **GENAU ZWEI** Sites
    aufgerufen — der `operatorEligible`-Definition und dem Reject-Guard `PlatformWiring:425`. T1c: rohes `isOperator(`
    erscheint **AUSSCHLIESSLICH** in dieser 2-Site-Whitelist; **jeder Autoritäts-Pfad (Operator/MEMBER/Boolean, egal
    Shape) ruft `operatorEligible`.**
  - **(B) ★ Value-Compare-Closure (die ECHTE terminal-Achse — STRUKTURELL by-visibility über ALLE Holder; V3.2-1/V3.3):**
    `isOperator` IST intern `token == operatorToken` (`Auth.kt:33`). Der gefährliche Vektor ist ein **Value-Compare**
    `X == <holder>.operatorToken` (präsentierter Token gegen das Secret = verdeckter Grant, umginge `operatorEligible`
    UND den `isOperator(`-Scan). **NICHT als Symbol-Scan bauen** (das Symbol erscheint an ~11 legitimen Plumbing-Sites →
    Plumbing ≠ Compare). **★ V3.2-1 (Reviewer, bestätigt): das Secret liegt in DREI Holdern, nicht einem** —
    `TokenRegistry.operatorToken` (`private val`, `Auth.kt:28`) **+ `Secrets.operatorToken` (PUBLIC val, `Secrets.kt:16`)
    + `CommConfig.operatorToken` (PUBLIC val, `CommRoutes.kt:57`)**; beide public → `== secrets.operatorToken` /
    `== config.operatorToken` ist EXTERN möglich → das private-val-Argument deckte nur 1 von 3.
    **Fix A (gewählt — by-construction, beendet die Klasse; Ripple am Objekt verifiziert = KLEIN, 3 Read-Sites):** alle
    **drei** Holder klassen-**`private`** — **★ `private`, NICHT `internal`** (`:server` ist EIN Modul → `internal` ließe
    den Compare modulweit offen = stiller Blind-Spot); (i) kein public Member exponiert den Wert (keine `data class` →
    kein `copy`/`componentN`; kein Getter; kein Klartext-`toString` — `Secrets`+`CommConfig` sind **plain class**
    verifiziert, `Secrets.toString` maskiert `:32`); (ii) der Ctor-Feed läuft über einen **engen Consumer**
    (z. B. `secrets.buildTokenRegistry(...)` statt `TokenRegistry(…, secrets.operatorToken)` an `BootOrchestrator:354`;
    analog `CommConfig` an `CommRoutes:87`); (iii) der null-Check `CommRoutes:84` `if(config.operatorToken != null)`
    braucht nur EXISTENZ → ersetzt durch `config.hasOperator: Boolean` (kein Wert-Read). **⟹ by-visibility ist
    `== operatorToken` für ALLE 3 Holder auf ihre je-Klasse gebannt** (TokenRegistry: `{Auth:33, Auth:45}`; Secrets/
    CommConfig: nur der interne Consumer) → **strukturell terminal, kein Scan-Zahn**. Der Zahn pinnt die STRUKTUR:
    alle 3 Holder klassen-`private` ohne wert-exponierenden public Member; `isOperator` = einziger öffentlicher
    Identifikator. *Mutation:* einen Holder `public`/`internal` machen ODER einen Value-Accessor (Getter/`copy`/
    Klartext-`toString`) hinzufügen → ein externer `== operatorToken` wird möglich → **rötet**. **(Fallback B, falls der
    Ripple zu groß wäre — hier nicht nötig: Value-COMPARE-Scan `X == *.operatorToken` exkl. `!= null`, Whitelist
    {`Auth:33`, `Auth:45`}; MUT `== secrets.operatorToken`-Grant→rot.)**
  - **Mutationen (Schicht A):** **MUT-A** — eine Grant-Naht (explizit **#3 `AgentSocket:65` roh-Boolean** ODER **`:132`
    MEMBER**) auf rohes `isOperator` umstellen → 3. rohe `isOperator(`-Referenz → **rötet** (beweist Boolean+MEMBER
    mitgedeckt). **MUT-B** — eine 6., NEUE rohe `isOperator(`-Grant-Site **beliebigen Shapes** hinzufügen → **rötet**
    (der Beweis, den weder eine feste 5-Liste noch ein 4-Typ-Key liefert). Analog `Cyp747OperatorAuthorityClosureTest`;
    macht „zero remote an der geteilten Quelle" **prüfbar statt behauptet** (der Zahn, der v2/v3 fehlte).
- **★ T1d (V3-1 — `:132` MEMBER-Downgrade, ZWEI Zähne für ZWEI Richtungen, damit keine Naht die andere absorbiert):**
  (a) **off-loopback** god-token (public-Connector), Bearer/`?token=` → **401** (volle Denial, **NICHT** `MachineAgent(null)`/
  MEMBER). *Mutation:* `:132` roh lassen (`if (isOperator) …`) → off-loopback → MEMBER (statt 401) → rötet (= das
  `/api/events`-Metadaten-Leck). (b) **on-loopback + Kill-Switch** (`operatorTokenDisabled ∧ hasOperator`) → **MEMBER**
  (never-lock-out-Bootstrap ERHALTEN). *Mutation:* das Loopback-`&&` zu streng fassen (auch on-loopback denyen) → der
  never-lock-out-Downgrade regrediert → rötet. Getrennte Zähne, weil ein einzelner die jeweils andere Richtung
  absorbieren würde (L#24).
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
- **★ F1 geteilte Quelle + ≥5 Grant-Nähte (empirisch, develop `4db7cac4`):** Prädikat `TokenRegistry.isOperator`
  `routing/Auth.kt:33`. Grant-Nähte: #1 `auth/Principal.kt:125` (API `MachineOperator`) · #2 `routing/TerminalAccess.kt:173`
  (`TerminalPrincipal.Operator`, PTY) · #3 `routing/AgentSocket.kt:65` (`tokenAuthorize`) · #4 `routing/Auth.kt:87`
  (`participantFor→OPERATOR_ID`) → `routing/EventAclFilter.kt:40` (`if(isOperator) return true`, Message-Bodies) · #5
  `auth/Principal.kt:170` (`/me`). **Reine Reject-Identität (host-unabhängig, NICHT graten):** NUR
  `routing/PlatformWiring.kt:425` (Tunnel-Guard). **★ V3-1: `auth/Principal.kt:132` (Kill-Switch-Downgrade) ist ein
  MEMBER-GRANT** → richtungs-gegatet (off-loopback→401, on-loopback-Kill-Switch→MEMBER), KEINE reine Identität. Cookie-Achse
  `Principal.kt:148`; `browserOperatorPostureEnabled =
  isLoopbackHost(config.hub.host)` `routing/PlatformWiring.kt`; `isLoopbackHost` `auth/Principal.kt:110`.
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
**Rev.3 (2026-07-27, Backend):** **F1-RESIDUAL-Blocker korrigiert** (Reviewer-Re-Review). v2 §2.4 behauptete
`resolvePrincipal` sei der einzige Token→OP-Chokepoint — **empirisch falsch (behauptet, nicht verifiziert):** der Grant
läuft über das GETEILTE Prädikat `TokenRegistry.isOperator` an **≥5 Nähten** (API, `/ws/terminal`-PTY, `/ws/agent`,
`/ws/events`+`/api/events` Message-Bodies/CYP-432, `/me`); v2 gated nur eine. **v3-Fix: Loopback-Gate an die geteilte
Quelle** (`operatorEligible`-Prädikat, alle Konsumenten erben) **+ Closure-Zahn** (T1c, enumeriert alle Grant-Aufrufer,
MUT ungegatet→rot — der Zahn, der v2 fehlte) **+ Identität-vs-Eligibilität-Trennung** (rohe `isOperator` bleibt für
Reject-Guard/Kill-Switch). §1.9/§2.1b/§2.4/§4-S3b/§5-W2b/§6-T1b+T1c neu gefasst; F2–F6 unverändert. **Exposition an PL
neu-eskaliert (größer: PTY + Agent-Streams + Message-Body-Egress).** Lektion: „Property am geteilten Seam, nicht am einen
Caller" — dieselbe Klasse wie CYP-698/719/815/819. Design-only; Bau gated auf PL-Ratifikation v3.
**Rev.3.1 (2026-07-27, Backend):** zweiter Reviewer-Re-Review — **V3-1 + V3-2 gefoldet** (Reviewer-Kriterien vorab
fixiert). **V3-1 (BLOCKER, ehrlicher Zweit-Selbstbefund):** v3 klassifizierte `Principal:132` als reine Identität —
falsch, es ist ein **MEMBER-GRANT** (off-loopback `:125`-skip → `:132` → MEMBER, nicht 401 = `/api/events`-Metadaten-Leck).
Fix: `:132` richtungs-gegatet (`isOperator ∧ loopbackPosture`) — off-loopback→401, on-loopback-Kill-Switch→MEMBER
(never-lock-out ERHALTEN); **zwei Zähne für zwei Richtungen** (T1d, damit keine Naht die andere absorbiert, L#24). Die
reine host-unabhängige Reject-Identität ist damit **NUR** `PlatformWiring:425`. **V3-2:** T1c von „feste 5-Nähte-Liste"
auf **Autoritäts-KONSTRUKTION gekeyed** (jede `MachineOperator`/`TerminalPrincipal.Operator`/`OPERATOR_ID`/`AuthMe(OPERATOR)`
via `operatorEligible`) + minimale Identitäts-Whitelist; **2 Mutationen** MUT-A (Grant-Naht→roh→rot) + **MUT-B (6.
Konstruktions-Site→rot** — der Beweis, den die feste Liste nicht liefert). §1.9/§2.1b/§6-T1c+T1d + §7 neu gefasst; F2–F6
unverändert. Design-only; Bau gated auf PL-Ratifikation **v3.1**.
**Rev.3.2 (2026-07-27, Backend):** dritter Reviewer-Re-Review. **V3-1 clean → GO** (`:132` beide Richtungen sauber,
T1d). **V3.1-1 (T1c-Gap, bestätigt):** die v3.1-„construction-keyed"-T1c (4 getypte Operator-Konstruktionen) deckte
**NICHT** alle Grant-Shapes — `AgentSocket:65` grantet roh-`Boolean`, `:132` `MachineAgent(null)`-MEMBER; ein 4-Typ-Key
ließe beide (und den „T1c flaggt :132 selbst"-Bonus inakkurat) durch. Die Invariante = „zero remote" **inkl. MEMBER +
Boolean**. **Fix: T1c SOURCE-keyed, zwei Schichten** — (A) rohes `isOperator(` nur in {`operatorEligible`-Def,
`PlatformWiring:425`}; (B) **Value-Compare-Closure — STRUKTURELL by-visibility, NICHT Symbol-Whitelist** (Reviewer-
Verfeinerung vor Bau): der Vektor ist ein Value-Compare `X == operatorToken`, nicht das Symbol (das an ~11 legitimen
Plumbing-Sites erscheint → Symbol-Scan false-positivt). Weil `operatorToken` `private val` (`Auth.kt:28`) OHNE
wert-exponierenden Accessor ist, ist `== operatorToken` by-visibility auf `{Auth:33 isOperator, Auth:45 mint}` gebannt —
keine Enumeration. Zahn pinnt die STRUKTUR: (i) `operatorToken` bleibt private-val ohne Value-Accessor; (ii) `isOperator`
= einziger öffentlicher Identifikator; MUT „Value-Accessor hinzufügen"→rot. MUT-A (Grant→roh `isOperator(`, deckt
#3-Boolean+`:132`-MEMBER)→rot · MUT-B (6. roher Grant beliebigen Shapes)→rot. §6-T1c neu gefasst; V3-1/T1d + F2–F6
unverändert. Objekt-verifiziert (Auth.kt `private val`+maskiertes toString, ~11 Symbol-Sites). Design-only; Bau gated auf
PL-Ratifikation **v3.2**.
**Rev.3.3 (2026-07-27, Backend):** vierter Reviewer-Re-Review. **Schicht A = GO** (V3.1-1 sauber). **V3.2-1 (bestätigt):
das private-val-Argument (v3.2-B) deckte nur 1 von 3 Secret-Value-Holdern** — dasselbe Secret liegt in zwei weiteren
**PUBLIC vals** (`Secrets.operatorToken` `Secrets.kt:16`, `CommConfig.operatorToken` `CommRoutes.kt:57`) → `== secrets.
operatorToken`/`== config.operatorToken` extern grantet Operator. **Fix A gewählt (by-construction; Ripple am Objekt
verifiziert = KLEIN, 3 externe Read-Sites: `BootOrchestrator:354` ctor · `CommRoutes:84` `!=null`-Presence · `CommRoutes:87`
ctor):** alle 3 Holder klassen-**`private`** — **★ `private`, NICHT `internal`** (`:server`=EIN Modul → `internal` ließe
den Compare modulweit offen, 5.-Runden-Blind-Spot) — kein value-exponierender public Member (keine `data class`:
`Secrets`+`CommConfig` sind **plain class** verifiziert; Ctor-Feed via engen Consumer `secrets.buildTokenRegistry(...)`;
`CommRoutes:84`→`hasOperator: Boolean`). ⟹ by-visibility deckt ALLE 3 Holder → strukturell terminal, kein Scan-Zahn.
(Fallback B: Value-Compare-Scan — hier nicht nötig.) §6-T1c(B) neu gefasst; Schicht A/V3-1/T1d + F2–F6 unverändert.
Objekt-verifiziert (Secrets `:13`/CommConfig `:53` plain class, 3 Read-Sites). Design-only; Bau gated auf PL-Ratifikation
**v3.3**.
