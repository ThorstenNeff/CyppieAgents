# Cutover Parity & Security Gates — web-ts

> Status: Prep-plan v0.1 (QA/Team2) · Owner-GO erteilt für den Web-ts-Cutover-Gate.
> Begleitend: `09-UI-Funktionskatalog`, `05-MVP-Scope-Entscheidungen` (D6/D7 stream-json-Renderer),
> `web-ts/contract/README.md` (CONTRACT_REQUIRE_REAL), `docs/E2E-TEST-PLAN.md`,
> `docs/QA-WEB-TESTING-OPTIONS-CYP-352.md` + `docs/QA-WEB-FLOW-ROLLBACK-CYP-352.md` (Vor-Arbeit Web-Test),
> `docs/*` (Feature-Specs je Funktion).

**Zweck.** Die **funktionale Evidenz fürs Cutover-Gate**: bevor die neue TS-UI (`web-ts/`, Dev5) Default
wird, beweisen wir (A) **funktionale Parität** gegen `09-UI-Funktionskatalog` und (B) **grüne Cutover-
Security-Gates** (XSS/CSP + Contract-Real-Drift). **Prep jetzt; laufen lassen, sobald W8/W9/W10 gemergt
sind.** E2E-Basis ist die **CYP-418-Harness** (`web-e2e/`, Playwright gegen den echten Ktor-Server via der
hermetischen CYP-106-Plattform — kein claude/Key/Repo), sobald sie gemergt ist.

**Leitprinzip (stehende QA-Linie).** Jeder Zahn läuft **gegen die echte Quelle** (realer Ktor-Server, reale
Wire-Typen), **nicht gegen Mocks** — genau die Lücke, die die typ-gestrippten `vitest`-Units nicht fangen.
Ein grüner Haken zählt nur so viel wie die Frage, die er beantwortet: wo möglich **diskriminierend**
(Payload-Mutation für XSS, `since`-Paar für Replay), nicht bloß „grün".

---

## Vorab: zwei offene Gaps (vor dem Cutover zu schließen — an PO gemeldet)

1. **CSP fehlt.** In `server/src/main` ist **kein** `Content-Security-Policy`-Header gesetzt (auch keine
   `X-Frame-Options`/`X-Content-Type-Options`). Für eine DOM-SPA ist CSP die zweite Verteidigungslinie
   hinter der inert-Render-Disziplin. **Gate B-2 fordert sie** — muss also erst hinzugefügt werden
   (Server-serving oder Hosting). Bis dahin ist B-2 ein **offener** Haken, kein grüner.
2. **`CONTRACT_REQUIRE_REAL` muss im Cutover-Build gesetzt sein.** Default (unset) fällt bei fehlendem
   Real-Export **still auf die Provisional-Fixture** zurück (nur laute Warnung). Fürs Cutover-Gate MUSS
   `CONTRACT_REQUIRE_REAL=1` gesetzt sein → fehlender Real-Export = **fatal (exit 1)**, kein stiller Stub.

---

## A. Funktionaler Paritäts-Pass (09-Katalog × web-ts)

**Cutover-Baseline = die [K]-Funktionen** (Kern-MVP-Grundbetrieb). [MP]/[MU] sind gestaffelt — im Pass als
*staged* markiert; ein [MP]-Punkt wird nur zum Pflicht-Haken, wenn er in der abzulösenden Compose-UI bereits
lebt (PO bestätigt den Cutover-Scope, siehe §D). Jede Zeile: **Funktion → Parität-Check (am web-ts-DOM, via
CYP-418 gegen den echten Server) → Zustände → Guardrail**. Pflicht-Zustände überall: **empty / loading /
error / reconnect** (09-Querschnitt + S6-Risiko Dedup-über-`id`).

### A1 · Projekt-Verwaltung — Spec: `docs/PROJECT-MANAGEMENT.md`, `docs/CROSS-PROJECT.md`
- [ ] **Repo konfigurieren (URL, Branch)** [K] — Form absenden → `POST`/`PUT` am echten Server, Persistenz
  über Reload verifiziert. Zustände: leer/ungültige URL (error) / gespeichert.
- [ ] Projekt anlegen / umbenennen / **löschen** / Switcher [MP, staged] — Löschen ist **irreversibel +
  kaskadiert** → Bestätigung **und** klare Folgenanzeige (worktrees/Hub/Sessions/Event-Partition). Guardrail.

### A2 · Agenten-Verwaltung — Spec: `docs/AGENT-MANAGEMENT.md`
- [ ] **Agent hinzufügen** [K] — Rolle (PO/Worker/Product Lead), CLAUDE.md/Persona, `launch`, worktree →
  erscheint in der Agentenliste; am echten Server angelegt.
- [ ] **Agent entfernen** [K] — stoppt die Session; worktree-Schicksal-Prompt (behalten/löschen). Guardrail:
  Bestätigung + Folgenanzeige.
- [ ] **Agent-Konfig ändern** [K] — wirkt erst beim nächsten Spawn → **UI zeigt „Neustart nötig"** transparent
  (09-Querschnitt: kein stiller Nicht-Effekt).

### A3 · Agenten-Lebenszyklus & -Fenster — Spec: `05` D6/D7, `docs/STATE-VISUALIZATION.md`, `docs/WINDOW-BADGES.md`
- [ ] **Fenster-Manager** (verschieben / Größe / Fokus/Z-Index) [K] — DOM-Interaktionen assertierbar.
- [ ] **start / stop / restart** [K] — am echten Lebenszyklus (`/ws/lifecycle`-Status folgt der Beobachtung,
  CYP-351) → Button-Zustände + Statuspunkt korrekt (RUNNING/STOPPED/ERROR). Zustand error = spawn-fail.
- [ ] **Gerendertes stream-json-Terminal** [K] — Assistant-Text streamt, Tool-Call-Zeilen, Ergebnis-Marker
  (kein rohes xterm). Feed: `/ws/agent` (seq-Transkript). **Reconnect-Idempotenz** (CYP-418-Zahn) + **seq-
  `?since`-Replay** hier verankert. Zustände: leer/streamend/reconnect.
- [ ] **Nachricht an Agenten senden** [K] — Eingabefeld ist **„Nachricht"**, kein Shell-Prompt (durchgängige
  Beschriftung, 09-Querschnitt) → `UserTurn` auf `/ws/agent`; kommt am (Fake-)Session an.

### A4 · Kommunikation & Kanäle — Spec: `docs/COMM-PANEL.md`, `docs/COLOR-CODING.md`
- [ ] **Kanalliste** [K] · **Timeline pro Kanal (Historie + live)** [K] — Historie via REST, live via
  `/ws/comm`; **Dedup über `Message.id`** beim Reconnect (kein Dup-Row). Absender/Kanal-Farbcodierung.
- [ ] Als Operator/Mensch senden [K optional] — PO-Entscheidung; wenn an: Operator-Token-geschützt.
- [ ] Cross-Projekt-Kanal freigeben [MP, staged] — **Autorisierungsakt** (Eigentümer-Zustimmung), nicht bloß
  „Mitglied hinzufügen". Guardrail (siehe B-4).

### A5 · Zugriffsrechte (ACL) — Spec: `docs/ACL-MATRIX.md`
- [ ] **ACL-Matrix anzeigen & bearbeiten** (read/write je Kanal je Agent) [K] — Toggle → `PUT /api/acl` →
  Hub setzt sofort durch; live via `AclEvent`. Optimistisches Update + Bestätigung.
- [ ] **Preset „Hub-and-Spoke" wiederherstellen** [K] — Leitplanke gegen Aussperren des PO.

### A6 · Observability (Event-Log) — Spec: `docs/EVENT-LOG-UI.md`
- [ ] **Browsen** (Master-Detail, Filter Agent/Typ/Severity/Zeit) [K] — gepaged/virtualisiert.
- [ ] **Korrelations-Drilldown** („ganzer Lauf") [K] — über `correlationId`/`sessionId`.
- [ ] **Live-Tail mit Pause** [K] — getrennt vom Browsen; `/ws/events`.
- [ ] Projekt-Filter [MP, staged] — der Bericht zählt Projekt-gefiltert (CYP-353/364-Klasse).

### A7 · Aufsicht (Warden)
- [ ] **Warden-Eskalationen sichtbar** [K leicht] — über Event-Log (`stall.escalated` etc.), ggf.
  hervorgehoben; kein eigenes Steuer-UI.

### A8 · Product Lead — Spec: `docs/PRODUCT-LEAD.md`
- [ ] **On-demand auslösen** („wo stehen wir?") [K].
- [ ] **Berichte ansehen** (Nutzungs-Guide, Status-Report, Defekt-/Lücken-Register) [K] — zeitgestempelte
  Momentaufnahmen je Lauf.

### A9 · Schlüssel & Einstellungen — Spec: `docs/PROJECT-SETTINGS.md`
- [ ] **API-Key hinterlegen/ändern (pro Projekt)** [K] — **maskiert (letzte 4)**, nie zurückgerendert,
  **Operator-geschützt**; Wirkung erst beim nächsten Spawn → **UI zeigt „Restart nötig"**. Guardrail (B-4).

---

## B. Cutover-Security-Gates

### B-1 · XSS-Inertness an JEDEM Render-Sink (nicht nur Message-Body)
Der CYP-418-Zahn (Comm-Body, mutations-belegt: `textContent`→`innerHTML` → ROT) ist das **Muster**; der
Cutover-Pass **erweitert ihn auf jeden DOM-Sink**, an dem Agenten-/Nutzer-Inhalt landet:
- [ ] Agent-Transkript: stream-json Text-/Thinking-/Tool-Blöcke (`agentview/` — die TS-Seite hat bereits
  `noInnerHtml.test.ts` als Unit-Zahn; der E2E-Pass ergänzt ihn mit **echten Server-Payloads**).
- [ ] Event-Log-Detail (`detail`-Felder), Comm-Body, **Agent-/Kanal-/Projekt-Namen**, Report-Inhalt.
- [ ] Für jeden Sink: Payload (`<img src=x onerror=…>` / `<script>`) über den **echten** Feed → im DOM
  **inert** (`textContent`, kein Element injiziert, kein `onerror`). **Diskriminierend** wie B-CYP-418.

### B-2 · CSP (Content-Security-Policy) — **GAP, siehe Vorab-1**
- [ ] Server/Hosting setzt CSP auf der ausgelieferten SPA: mind. `default-src 'self'`, `script-src 'self'`
  (**kein** `unsafe-inline`), `connect-src` auf die API/WS-Herkunft, `object-src 'none'`, `frame-ancestors
  'none'`. Plus `X-Content-Type-Options: nosniff`.
- [ ] Playwright-Zahn: eine injizierte Inline-`<script>` wird vom Browser **geblockt** (CSP wirkt), und der
  Header ist auf der Antwort präsent. **Bis CSP existiert: offener Haken.**

### B-3 · Contract-Real-Drift (`CONTRACT_REQUIRE_REAL`) — gegen den echten Export
Der TS-Client bezieht seine Typen aus `web-ts/contract/asyncapi.json` (Backend2s `:server:exportContract`,
generiert aus `:core`). Drei Haken, zusammen = „TS-Typen beweisbar aus dem echten `:core`-Export, nicht
gedriftet, kein Stub":
- [ ] **`CONTRACT_REQUIRE_REAL=1`** im Cutover-`contract:gen` → fehlender Real-Export = **fatal** (kein
  stiller Provisional-Fallback).
- [ ] **`ContractExportDriftTest`** (`:server:test`) grün → committetes `asyncapi.json` == frisch generiert
  (keine Staleness; sonst „`:server:exportContract` neu laufen + committen").
- [ ] **Diskriminanten-Zahn** (`generate-contract-types.mjs`) → der `type`-Klassendiskriminator ist in jeder
  TS-Union erhalten (sonst fail-closed). (Verankert den `type`-Prüfpunkt aus der §8-Strategie.)

### B-4 · Operator-Schutz & Irreversibilität (09-Querschnitt)
- [ ] Operator-geschützte Aktionen (**Key ändern, ACL ändern, Cross-Projekt-Freigabe**) **ohne**
  Operator-Token → abgelehnt (403), **fail-closed**. Positiv- **und** Negativ-Fall.
- [ ] Irreversible Aktionen (**Projekt/Agent löschen**) → Bestätigung **und** klare Folgenanzeige; kein
  Ein-Klick-Löschen.
- [ ] **API-Key nie im Klartext** zurückgerendert (maskiert, letzte 4) — auch nicht in einem
  Response-/Debug-Feld.

---

## C. Wie es läuft (Mechanik)

- **E2E** über die CYP-418-Harness (Playwright, echter Ktor-Server, keine Mocks). Der Seed der Harness wird
  auf die Katalog-Fläche erweitert (mehrere Projekte, Agenten, Kanäle, Events, ein Report) — additiv über die
  vorhandenen `port`/`extraRoutes`-Seams + die Seed-DSL (`SeedProject`/`SeedAgent`).
- **Gegen den web-ts-Build** (Vite-Serve oder statisches Artefakt), same-origin zum Ktor-Server (kein CORS-
  Confounder) — dasselbe Muster wie die Referenz-Fixture.
- **Manuell, kein CI** (Standard). **Ausführung erst, wenn W8/W9/W10 gemergt** sind und die CYP-418-Harness
  auf `develop` liegt.
- **Evidenz** = grüne Paritäts-Matrix (A) + grüne Security-Gates (B) → die **funktionale Evidenz fürs
  Cutover-Gate** → an PO → PO1.

---

## D. Offene Punkte (PO zu entscheiden, vor Ausführung)

1. **Cutover-Scope:** nur [K] (Kern-MVP) als Pflicht-Parität, oder auch die bereits gebauten [MP]-Funktionen
   (Projekt-CRUD/Switcher, Cross-Projekt, Projekt-Filter)? Bestimmt, welche A-Zeilen Pflicht-Haken sind.
2. **CSP-Owner:** setzt der Ktor-Server den CSP-Header (SPA same-origin ausgeliefert) oder das Hosting?
   (Gate B-2 hängt daran.)
3. **CONTRACT_REQUIRE_REAL-Wiring:** wo wird `=1` im Cutover-Build gesetzt (Dev5s `contract:gen`-Aufruf)?
4. **Report-Fixtures:** Product-Lead-Berichte (A8) brauchen einen deterministischen Seed — reicht ein
   injizierter Report, oder wird der echte Generator gefahren?
