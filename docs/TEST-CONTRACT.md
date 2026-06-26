# Test-Contract (Entwurf v0.3) — CyppieAgents / KMPCyppieAgents

> Owner: QA/Test · Ticket: **CYP-7** (In Arbeit) · Status: **Entwurf — Gegenlesen Dev (CYP-6)/iOS-Tester ausstehend** · Stand: 2026-06-26
> **Kanonischer Ort:** dieses Dokument im geteilten Repo `KMPCyppieAgents` unter `docs/TEST-CONTRACT.md`
> (geteilte Artefakte gehören ins Git-Repo mit `origin`, nicht in agent-lokale Ordner — PO-Konvention 2026-06-26).
> Verbindliches Modul-Layout & Gating laut PO-Entscheid (Discord, 2026-06-26).
> Dieses Dokument ist der **geteilte Test-Contract** zwischen QA und Dev/Backend: Test-IDs/Tags,
> Test-Platzierung pro Modul, Maestro-Flow-Tags, Severity-Skala, Gating-Zuordnung.
> Der Contract läuft dem Code bewusst voraus.

---

## 0. Verbindliche Rahmenbedingungen (vom PO bestätigt)

- **Modul-Layout (real, maßgeblich):** `:app:{androidApp, desktopApp, webApp, shared, iosApp}`, `:core`, `:server`.
  Die 02/04-Namen (`protocol`/`hub-cli`/`composeApp`) sind **vor-05** und nicht maßgeblich.
  - `:core` = geteiltes Vertrags-/DTO-Modul (KMP common). `:server` = Ktor. `:app:shared` = Compose-UI (commonMain).
  - `:hub-cli` existiert im MVP **nicht** (05: Koordination via stream-json + Backend-Mediation).
- **Basis-Package:** `com.tneff.cyppieagents` (core: `…core`, shared: `…app.shared`).
- **Gating-Fläche „Fertig" (slice-abhängig):**
  - Backend/Logik/Comm-Hub (S0, S3, S4, S5): **`./gradlew check` grün** + Modul-/Server-Tests. Kein Maestro.
  - UI-Slices (S2, S6, S7): **Maestro-Flow grün auf Web (Wasm)** als „≥1 Target" + Desktop (JVM) als manuelle Demo.
    *Wasm-Gating noch unter Vorbehalt der Frontend-Bestätigung; Fallback-Target Android-Tablet (PO klärt mit Dev).*

---

## 1. Ist-Stand des Repos (S0 Walking Skeleton) — verifiziert 2026-06-26

| Modul | Test-Quellsatz | Framework (vorhanden) | Bestehende Tests |
|---|---|---|---|
| `:core` | `commonTest` | `kotlin.test` | – (Skeleton) |
| `:server` | `src/test/kotlin` | `kotlin-test-junit` + `ktor-server-test-host` (`testApplication`) | `ApplicationTest.testRoot` |
| `:app:shared` | `commonTest` / `jvmTest` / `iosTest` / `androidHostTest` | `kotlin.test` | `SharedCommonTest.example` u. a. |

**Lücken, die der Contract voraussetzt (Dev-Asks, siehe §7):**
- `:app:shared` hat **keine Compose-UI-Test-Abhängigkeit** → `testTag`-gestützte UI-Assertions noch nicht ausführbar.
- `:core` hat **kein `kotlinx.serialization`** → DTO-Round-Trip-Tests erst möglich, wenn Plugin/Dep landen (S4).
- **Kein Maestro** im Repo (`maestro/`-Verzeichnis + CI-Hook fehlen).

---

## 2. Test-ID-Schema für UI-Elemente (`Modifier.testTag`) — **konsolidiert**

**Regel:** Jedes interaktive oder zustandstragende Compose-Element, das ein Test/Flow ansteuern muss,
trägt einen stabilen `Modifier.testTag(...)`. Tags sind ein **API zwischen Dev und QA** — nicht raten,
nicht still umbenennen; Änderungen laufen über diesen Contract.

**Konsolidierung zweier Vorschläge (PO-Auftrag CYP-7):**
- QA-Vorschlag: `cyp.<area>.<element>[.<qualifier>]` (Prefix + Qualifier für Matrix-Zellen).
- iOS-Tester-Vorschlag: `<bereich>.<element>` (kurz, prefixlos — z. B. `comm.channelList`, `agent.input`, `agent.stream`).

**→ Einheitliches Schema (gewählt):**

```
<area>.<element>[.<id>][.<qualifier>]
```

- **Prefixlos** (folgt dem iOS-Vorschlag; kürzer, gut lesbar). Namespacing übernimmt ohnehin
  `testTagsAsResourceId` innerhalb der App. *Falls je Tag-Kollisionen mit Library-Tags auftreten,
  führen wir einen `cyp.`-Prefix nachträglich global ein — der Punkt-Aufbau bleibt dann identisch.*
- `<area>` aus **fester Vokabelliste**: `window` · `agent` · `comm` · `acl` · `app`.
- `<element>` = lowerCamel. `<id>` = dynamische Entity-ID (nur beim Adressieren einer Instanz).
  `<qualifier>` = Sub-Teil (z. B. `read`/`write`) — übernimmt die Rolle meines früheren Qualifiers,
  deckt die Matrix-Zellen ab, die im 2-Segment-Schema nicht ausdrückbar wären.

**Segment-Zeichensatz (verbindlich, iOS-Tester-Input v0.3):**
- Jeder Segmentwert — inkl. dynamischer IDs — ist auf **`[A-Za-z0-9-]+`** beschränkt.
  **Kein `.`** in `<id>`/`<qualifier>`/`<element>` (sonst kollidiert es mit dem Punkt-Trenner **und** mit
  Maestros Regex-Selektor, §4). Bindestriche wie `po-frontend` sind erlaubt; UL/UUID-`<msgId>` erfüllen das.
- Trenner zwischen Segmenten ist **ausschließlich** der Punkt `.`.

| Bereich (`area`) | Beispiel-Tag | Slice |
|---|---|---|
| Fenster-Manager | `window.<agentId>.titlebar`, `window.<agentId>.resizeHandle` | S2 |
| Agent-Renderer | `agent.<agentId>.stream`, `agent.<agentId>.input`, `agent.<agentId>.sendBtn` | S1(05)/S8 |
| Comm-Panel | `comm.channelList`, `comm.channel.<channelId>`, `comm.timeline`, `comm.message.<msgId>` | S6 |
| ACL-Matrix | `acl.cell.<channelId>.<agentId>.read`, `…​.write`, `acl.preset.hubSpoke` | S7 |

`<agentId>`/`<channelId>` = stabile IDs aus der Config/DTO (z. B. `po`, `frontend`, `po-frontend`).
`<msgId>` = `message.id` (ULID/UUID) — auch für **Reconnect-Idempotenz** (S6-Risiko) nutzbar.
`agent.<id>.stream` = der scrollende stream-json-Renderer (Terminologie aus iOS-Vorschlag übernommen).

> Maestro auf Wasm/Android konsumiert diese Tags über die **Compose-Semantics/Accessibility-Knoten**
> (`testTagsAsResourceId` aktivieren, damit Tags als Resource-/Accessibility-IDs sichtbar werden).
>
> **Status:** Schema-Vorschlag konsolidiert durch QA; **Gegenlesen durch Dev (CYP-6) + iOS-Tester ausstehend**
> (CYP-7-AC).

---

## 3. Test-Benennung & Platzierung (Unit/Integration)

- **Klassen:** `<Subjekt>Test` (z. B. `AclEnforcementTest`, `MessageStoreJsonTest`, `HealthRouteTest`).
- **Methoden:** sprechend, Verhalten-orientiert: `send_withoutCanWrite_returns403`, `read_withoutCanRead_isExcluded`,
  `messages_survive_serverRestart`. (Backticks erlaubt auf JVM; auf KMP-common neutral halten.)
- **Platzierung pro Modul:**
  - `:core/commonTest` → reine Vertrags-/Logik-Tests: DTO-Serialisierungs-Round-Trips, ACL-Filter (falls pure Funktion).
  - `:server/src/test` → REST-/WS-/ACL-Durchsetzung via `testApplication`; Persistenz In-Memory↔JSON.
  - `:app:shared/commonTest` → plattformneutrale UI-State-/ViewModel-Logik (kotlin.test).
  - `:app:shared` UI-Assertions (`testTag`) → eigener UI-Test-Quellsatz, **sobald Compose-UI-Test-Dep da ist** (§7).

---

## 4. Maestro-Flow-Contract (UI-Slices)

- **Ort:** `maestro/` im Repo-Root von `KMPCyppieAgents`; eine Datei je Flow (`*.yaml`).
- **Web-Flow:** `appId`-frei, `url` + `openLink` (Wasm-Build im Browser/`chromium`).
- **Tags (bare, ohne `@` beim Aufruf):**
  - `smoke` — schneller Durchstich je Slice (Boot + Kernelement sichtbar).
  - `gating` — der Flow, der „Fertig" entscheidet.
  - `security` — Security-Surface-Pässe (z. B. ACL-Durchsetzung, geschützte Screens).
  - Slice-Tags: `s2-windows`, `s4`, `s6-comm`, `s7-acl`.
- **Element-Selektoren** referenzieren ausschließlich die testTags aus §2 (keine Text-Selektoren auf
  lokalisierbaren Strings — Stabilität + Localization-Pass).

**Maestro-Selektor-Konvention (verbindlich, iOS-Tester-Input v0.3):**
- In Maestro ist `id:` ein **Regex** → der Punkt `.` ist dort eine **Wildcard** (matcht jedes Zeichen).
  Ein unescapeter Tag-Selektor matcht daher zu breit (z. B. matchte `acl.cell.po-frontend.frontend.read`
  versehentlich auch ähnliche Tags).
- **Regel:** Tag-Selektoren in Flows **escapen (`\.`) und ankern (`^…$`)**. Beispiel:
  ```yaml
  - tapOn:
      id: "^acl\.cell\.po-frontend\.frontend\.read$"
  ```
- Weil Segmentwerte auf `[A-Za-z0-9-]+` beschränkt sind (§2), bleibt das Escapen mechanisch und eindeutig:
  nur die Trenner-Punkte werden zu `\.`, sonst keine Regex-Sonderzeichen im Tag.

---

## 5. Severity-Skala (geteiltes Vokabular für Findings)

| Severity | Bedeutung | Beispiel |
|---|---|---|
| **S1 — Live-Gap** | reale, ausnutzbare/blockierende Lücke | ACL lässt Worker in fremden Kanal schreiben (403 fehlt) |
| **S2 — Fragilität / funktionale Abweichung** | funktioniert, bricht aber unter Reconnect/Edge/Last — **oder** reale AC-Abweichung ohne Security-Impact | Reconnect dupliziert Nachrichten (fehlende `message.id`-Idempotenz); fehlender `GET /api/health` (CYP-8) |
| **S3 — Kosmetik/Konsistenz** | Drift ohne Funktionsverlust | Empty-State fehlt, Farbcode inkonsistent |

Findings stets **priorisiert** melden (Severity + konkreter Fix + Evidenz). Verifiziert-Gutes ebenfalls
benennen (Trust-Line für den PO). Eine S2-Abweichung kann **slice-DoD-blockierend** werden, wenn der
betroffene Slice ohne den Fix als „fertig" gemeldet wird (so vereinbart für CYP-8/S0).

---

## 6. „Verified" = ausgeführt + Evidenz

Sign-off nur mit Beleg: grüner Run (`./gradlew check`-Ausgabe), Maestro-Report, Screenshot.
Inspektion allein genügt nicht bei allem Testbaren. End-to-End-Pfad prüfen, nicht nur „kompiliert".

---

## 7. Offene Asks an Dev/Backend (Voraussetzung für Teile des Contracts)

1. **Compose-UI-Test-Dep** in `:app:shared` (ui-test + JUnit-Runner je Target) + `testTagsAsResourceId = true`,
   damit `testTag` für UI-Tests **und** Maestro adressierbar ist. (Blockt §2/§4 UI-Assertions.)
2. **`testTag` konsequent setzen** auf allen interaktiven/zustandstragenden Elementen gemäß §2.
3. **`kotlinx.serialization`** in `:core` (Plugin + Dep), wenn DTOs landen (S4) → Round-Trip-Tests.
4. **`maestro/`-Verzeichnis + CI-Hook** anlegen (S2 vor erstem UI-Gating).
5. **Stabile IDs** (`agentId`/`channelId`/`message.id`) als Vertragsbestandteil bestätigen — **`[A-Za-z0-9-]+`** (§2).

---

## 8. Früh-Beobachtungen / offene Findings

- **S0/Health-Endpoint → Bug `CYP-8` (S2, S0-DoD-blockierend, PO-bestätigt):** Server liefert aktuell
  `GET /` → `"Hello, Ktor!"`; Spec-AC für S0/§7 nennt `GET /api/health` → `ok`. Verknüpft mit Epic CYP-4.
  **QA-Re-Verify nach Fix:** `./gradlew :server:check` grün + Health-Route-Test `health_returnsOk`.

---

## Changelog
- v0.3 (2026-06-26): Ins geteilte Repo nach `docs/TEST-CONTRACT.md` verlegt (PO-Konvention). Maestro-Input vom
  iOS-Tester eingearbeitet: (a) `id:`-Regex → Tag-Selektoren in Flows escapen+ankern (`^…\.…$`); (b) Segment-Zeichensatz
  auf `[A-Za-z0-9-]+` beschränkt. Severity-Skala S2 um „funktionale AC-Abweichung" geschärft (CYP-8).
- v0.2 (2026-06-26): Tag-Schema mit iOS-Tester-Vorschlag zu **einem** Schema `<area>.<element>[.<id>][.<qualifier>]`
  konsolidiert (prefixlos); Maestro-Tags `security`/`s4` ergänzt; Health-Beobachtung als CYP-8 verlinkt. (CYP-7 In Arbeit.)
- v0.1 (2026-06-26): Erst-Entwurf gegen reales `KMPCyppieAgents`-Layout (S0-Skeleton), PO-Gating-Entscheid eingearbeitet.
