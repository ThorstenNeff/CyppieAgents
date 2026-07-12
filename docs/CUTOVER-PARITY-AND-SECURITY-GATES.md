# Cutover Parity & Security Gates — web-ts (CYP-422)

> Status: Prep-plan v0.2 (QA/Team2) · Ticket: **CYP-422** (Cutover-Ausführung / Gate) · Owner-GO erteilt.
> **Auftraggeber-Entscheid: voller Ersatz** — Baseline = der GANZE 09-Katalog (siehe §A).
> Begleitend: `09-UI-Funktionskatalog`, `05-MVP-Scope-Entscheidungen` (D6/D7 stream-json-Renderer),
> `web-ts/contract/README.md` (CONTRACT_REQUIRE_REAL), `docs/E2E-TEST-PLAN.md`,
> `docs/QA-WEB-TESTING-OPTIONS-CYP-352.md` + `docs/QA-WEB-FLOW-ROLLBACK-CYP-352.md` (Vor-Arbeit Web-Test),
> `docs/*` (Feature-Specs je Funktion).

**Zweck.** Die **funktionale Evidenz fürs Cutover-Gate**: bevor die neue TS-UI (`web-ts/`, Dev5) Default
wird, beweisen wir (A) **funktionale Parität** gegen `09-UI-Funktionskatalog` und (B) **grüne Cutover-
Security-Gates** (XSS/CSP + Contract-Real-Drift). **Stand develop `7accf768`: W8/W9/W10 sind gemergt** — der Pass ist
jetzt lauffähig. Der **Phase-1-Kern läuft grün** (6/6, `web-e2e/parity/` gegen den echten Ktor-Harness:
Assembly · Comm-Fenster · Orchestration↔Shell-Toggle · stream-json-Transkript · ACL-Matrix · Preset). Die
**Phase-2-Flächen sind gelandet** (Lifecycle-Header CYP-431/445/446, API-Key CYP-433, Event-Log CYP-432) und
unten in **§A-P2** zu konkreten, am DOM assertierbaren Zähnen ausbuchstabiert (noch zu-laufen, nicht grün).
E2E-Basis ist die **CYP-418-Harness** (`web-e2e/`, Playwright gegen den echten Ktor-Server via der
hermetischen CYP-106-Plattform — kein claude/Key/Repo), auf `develop`.

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

**Auftraggeber-Entscheid: VOLLER ERSATZ** → die Cutover-Baseline ist der **GANZE 09-Katalog**, nicht nur die
Kern-MVP-Fläche. Jede Katalog-Zeile ist ein Pflicht-Haken, **sobald ihre Fläche portiert ist**; die
Tier-Marker **[K]/[MP]/[MU]** sagen jetzt nur noch die **Reihenfolge des Hineinwachsens** (K zuerst, dann die
Phase-2-Flächen), **nicht** „optional vs. Pflicht" — am Ende müssen alle grün sein. Der Pass wächst mit den
portierten Flächen; bis eine Fläche assembliert ist, steht ihre Zeile als **pending** (kein grüner Haken, aber
auch nicht gestrichen). Jede Zeile: **Funktion → Parität-Check (am web-ts-DOM, via CYP-418 gegen den echten
Server) → Zustände → Guardrail**. Pflicht-Zustände überall: **empty / loading / error / reconnect**
(09-Querschnitt + S6-Risiko Dedup-über-`id`).

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

## A-P2 · Phase-2-Flächen jetzt gelandet — konkrete Zähne (Stand `7accf768`)

Drei §A-Zeilen sind auf `develop` assembliert und damit von **pending** zu **lauffähig** gewechselt. Hier die
konkreten, am web-ts-DOM (via CYP-418 gegen den echten Server) assertierbaren Zähne — mit **echten Testids** und
den Ehrlichkeits-/Leak-Zähnen. Noch **zu-laufen** (Design steht; Playwright-Specs folgen), daher `[ ]`.

### A-P2-a · Lifecycle-Header → realisiert **A3 · start/stop/restart** — gelandet: CYP-431 + CYP-445 + CYP-446
Testids: `lifecycle.header.<id>` · `lifecycle.status.<id>` · `lifecycle.dot.<id>` (`data-shape`/`data-role`) ·
`lifecycle.start|stop|restart.<id>` · `lifecycle.operatorOnly.<id>` · `lifecycle.error.<id>` (transienter
Reject) · `lifecycle.errorReason.<id>` (durable ERROR-Grund).
- [ ] **Non-optimistisch (CYP-351-Klasse).** Der Status folgt der **server-bestätigten** Feed-State, nicht dem
  Klick. Während `pending` zeigt das Label die transiente Form (`Startet…`/`Stoppt…`/`Neustart…`) und der Dot
  `data-role="neutral"` (nie eine aufgelöste Farbe); erst der bestätigte Run-State setzt `Aktiv`/`Gestoppt`/
  `Fehler`. **Diskriminierend:** eine gependete Aktion ohne Server-Antwort darf NIE zu `Aktiv` auflösen — Label
  bleibt `Startet…`, Dot bleibt neutral (Mutation: optimistisch auflösen → ROT).
- [ ] **Enablement-Matrix (`lifecycleControlEnabled`, CYP-445 §5)** — genau die Matrix assertieren, nicht ein
  Flag: `+pending` ⇒ alle aus; `!operator` ⇒ alle aus (present-but-disabled, `lifecycle.operatorOnly` sichtbar).

  | State | Start | Stopp | Neustart |
  |---|---|---|---|
  | RUNNING | **aus** | an | an |
  | STOPPED | an | **aus** | an |
  | ERROR | an | **aus** | an |
  | UNKNOWN | an | **aus** | an |

  **Zahn (CYP-445 §8.4):** Start MUSS `aria-disabled=true` sein während RUNNING (die alte `!operator||pending`-
  Logik ließ ihn klickbar) — der diskriminierende Punkt gegen ein Ein-Flag-Gate.
- [ ] **ERROR-Reason getrennt & fail-closed (CYP-446).** `lifecycle.errorReason` existiert **nur** in ERROR, ist
  ein **eigener** Knoten (nie mit dem transienten `lifecycle.error` verschmolzen), zeigt einen kuratierten Satz je
  Code und fällt bei unbekanntem/fehlendem Code auf „Fehler — Grund nicht gemeldet." — **nie der rohe Enum, nie
  leer, nie erfunden**.
- [ ] **Farbe nie alleiniges Signal (WCAG 1.4.1):** das Text-Label trägt die Bedeutung, der Dot verstärkt nur.

### A-P2-e · API-Key → realisiert **A9** — gelandet: CYP-433 (Leak-MOST-sensitive Fläche)
Testids: `settings.section.apiKey` · `settings.apiKey.masked` · `settings.apiKey.input` · `settings.apiKey.reveal`
· `settings.apiKey.gateHint` · `settings.apiKey.save` · `settings.apiKey.error` · `settings.apiKey.effectHint`.
- [ ] **Klartext nie persistent im DOM (§0.1).** Der gespeicherte Key erscheint NUR als server-maskiertes
  `***last4` in `settings.apiKey.masked` (der Client hat nie den Klartext — Server sendet nur `masked`); das Input
  startet **leer** (der gespeicherte Key wird nie hineingeladen). **Zahn:** kein DOM-Knoten enthält je mehr als die
  letzten 4; über einen Reload bleibt das Input leer.
- [ ] **Getippter Klartext transient & write-only (§0.2).** Die einzige Klartext-Stelle ist der neu getippte Key
  im Input-`value`: `type="password"` (default), `autocomplete="new-password"`, kein persistierender `name`, der
  Wert wird **NIE** in `data-*`/`aria-*`/`title` reflektiert, und das Feld wird nach erfolgreichem Save **geleert**.
  **Diskriminierend:** Key tippen → speichern → Input-`value === ''` **und** kein Attribut/Textknoten unter
  `settings.section.apiKey` enthält den getippten Wert (Mutation: clear-after-save entfernen → ROT). `reveal`
  un-maskiert NUR das Input, nie den gespeicherten Status.
- [ ] **Nie geloggt, Fehler generisch (§0.3).** `settings.apiKey.error` trägt nur generische Copy, **nie** den
  Wert; kein `console.*`. (→ B-4 „Key nie im Klartext".)
- [ ] **Operator-Gate present-but-disabled (nicht Omission).** Der maskierte Status leckt nichts → Nicht-Operator
  sieht die Sektion + `settings.apiKey.gateHint`, aber Input/Save sind `disabled`. **Negativ-Zahn (B-4):** Save
  ohne Operator-Token → serverseitig 403, UI zeigt generischen Fehler, nie den Key.
- [ ] **Effekt-Hinweis ehrlich (saved ≠ active).** `settings.apiKey.effectHint` (amber) sagt „wirkt erst beim
  nächsten Start" und zeigt auf den A-P2-a-Restart — **kein** Restart-Control hier (kein stiller Nicht-Effekt,
  09-Querschnitt).

### A-P2-c · Event-Log Live-Tail → realisiert **A6 · Live-Tail + Browsen** — gelandet: CYP-432
Testids: `event-log` · `event-log-status` (`Live`/`Verlauf lädt…`) · `event-log-rows` · `event-log-empty` ·
`event.row.<id>` · `event.gap.<afterSeq>` · `event-log-operator-only` · `event-log-revoked`.
- [ ] **Operator-only Mount-Gating — Defence-in-Depth, DREI Schichten.** (1) **Fenster** nur bei `cfg.operator`
  gemountet (App.tsx:175) — ein Nicht-Operator bekommt gar kein Event-Fenster; (2) **Socket** `/ws/events`-Handler
  nur für Operator verdrahtet (App.tsx:138–140) → ein Nicht-Operator öffnet den Socket **nie** (fail-closed);
  (3) **Bodies** selbst wenn ein Fenster existierte, nie für Nicht-Operator gerendert → `event-log-operator-only`-
  Platzhalter (der W10-Backstop, falls der Proxy je das Operator-Token leakt). **Zahn:** ohne Operator-Token →
  kein `event-log`-Fenster **und** keine `/ws/events`-Verbindung (Netzwerk-Assert) **und**, falls forciert
  gemountet, nur `event-log-operator-only`.
- [ ] **Revoke fail-closed.** `/ws/events`-Close `1008` → gepufferte Events verworfen + `event-log-revoked`
  (keine alten Bodies nach Entzug weiterzeigen).
- [ ] **Live-Tail vs. Verlauf getrennt.** `event-log-status` = `Live` erst wenn `caughtUp`, sonst `Verlauf lädt…`;
  der Autoscroll-Pin folgt dem Tail — **Pause** = wegscrollen löst den Pin (der DOM-Assert für „Live-Tail mit
  Pause" der A6-Zeile).
- [ ] **Seq-Gap laut, nie stumm („no silent caps").** Eine Lücke in der monotonen `seq` erscheint als **amber
  Alert-Zeile** `event.gap.<afterSeq>` mit korrekter Anzahl, nie stumm übersprungen — verankert den gapless-`seq`-
  Kontrakt (CYP-198-Klasse) am DOM. **Diskriminierend:** ein absichtlich gedroppter `seq` → Gap-Zeile erscheint.
- [ ] **XSS-inert (→ B-1).** Alle Felder (`detail`, `type`, `agentId`) sind React-Text-Kinder (escaped), kein
  `innerHTML`; `detail` über `JSON.stringify`+Slice als Text. Unbekannte Typen zeigen ihren **rohen** String (nie
  geschluckt). Konkretisiert den B-1-Sink „Event-Log-Detail".

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

1. ~~**Cutover-Scope**~~ **ENTSCHIEDEN (Auftraggeber): voller Ersatz** → der ganze 09-Katalog ist Pflicht-
   Parität, jede Zeile grün sobald ihre Fläche portiert ist (§A). Kein „nur [K]".
2. **CSP-Owner:** setzt der Ktor-Server den CSP-Header (SPA same-origin ausgeliefert) oder das Hosting?
   (Gate B-2 hängt daran.)
3. **CONTRACT_REQUIRE_REAL-Wiring:** wo wird `=1` im Cutover-Build gesetzt (Dev5s `contract:gen`-Aufruf)?
4. **Report-Fixtures:** Product-Lead-Berichte (A8) brauchen einen deterministischen Seed — reicht ein
   injizierter Report, oder wird der echte Generator gefahren?
5. **Cross-Origin-Deploy-Topologie (aus dem Phase-1-Lauf).** Wenn die SPA cross-origin zum Server ausgeliefert
   wird (SPA-Herkunft ≠ API-Herkunft, wofür die CORS-Allowlist da ist), bricht der aktuelle Stand **jeden**
   `/api/*`-Call: `web-ts/src/net/rest.ts` sendet `credentials:'include'` auf jedem Fetch, während
   `installRestrictedCors` (`server/.../routing/Cors.kt`) `allowCredentials=false` setzt → Browser blockt
   (`net::ERR_FAILED`). Same-origin (Proxy) verdeckt es. **Entscheidung vor Cutover:** entweder Client droppt
   `credentials:'include'` bei Bearer-Auth, **oder** Server setzt `allowCredentials=true` für Allowlist-Herkünfte —
   und der Cutover-Pass fährt dann **cross-origin** (nicht nur same-origin), damit dieser Pfad real geprüft ist.
