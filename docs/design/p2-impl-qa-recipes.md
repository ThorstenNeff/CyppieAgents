# P2-Impl — diskriminierende QA-Rezepte (QA-on-Merge, vorgestaged)

> Owner: UIUX-Designer · Stand 2026-07-12 · **Vorlauf für QA-on-Merge** der pending P2-Impls · für **mich + Tester2**.
> Der PO reicht das jeweilige Rezept mit dem Merge weiter. Docs-only.
>
> **Methode (verbindlich):** Jeder Check nennt die **falsche Impl, die er ablehnt** (nicht „wird's grün?", sondern
> „welche plausible Fehl-Impl ließe er durch?"). **Am Objekt messen** — testID assert, Kontrast rechnen (fg über der
> realen surface, **mit** Alpha, **hell UND dunkel**), Grep = Annahme / vitest+Code-Read = Messung. Ordnung: der
> **schärfste Zahn** je Fläche fängt den wahrscheinlichsten Slip.
>
> **Toolchain:** `git checkout origin/develop -- web-ts && cd web-ts && npm install && npx vitest run <pfad>` — danach
> **aggressiv aufräumen** (`git reset HEAD web-ts; git checkout -- web-ts; git clean -fdxq web-ts`), sonst Disk-Quota.
> **CWD-Falle:** git-Inspektion **immer vom Repo-Root**, nie aus `web-ts/` (sonst path-scoped-Fehlschluss).

---

## CYP-452 (P2-c.2) — Event-Log Browse + Drilldown · Spec `feature/CYP-452-…` @ `9eb7059e`
**Merged-Quelle lesen:** `web-ts/src/eventlog/EventBrowse*.tsx` + `eventBrowse*.ts` · reuse `eventLog.ts` (severityGlyph/typeGlyph/eventRows).

| # | Check (assert) | Falsche Impl, die er ablehnt |
|---|---|---|
| **1 ★schärfster** | Event mit `correlationId≠null, sessionId=null` → `eventBrowse.detail.showRun` **enabled**, `.showSession` **disabled** (und umgekehrt) | Buttons immer aktiv **oder** einer als Fallback für den anderen → **erfundene Korrelation** |
| 2 | Filter-Chip-Tap feuert **neue Query** (applyFilter→REST/store-refetch) | client-seitiger Post-Filter über schon geladenen Daten |
| 3 | Fehlgeschlagener **First-Page**-Load → `eventBrowse.error`+`.error.retry`, **nicht** `eventBrowse.empty` | failure-as-empty (CYP-288) |
| 4 | `filter≠default` → `eventBrowse.filterActive` präsent | gefiltertes Empty ununterscheidbar vom echten Empty |
| 5 | **Nicht-Operator:** kein `eventBrowse.table`/Socket im DOM (Route **nicht gemountet**) | mounted+hidden / Bodies im DOM / CSS-hidden |
| 6 | `sourceTs` als „beobachtet" gelabelt; Zeilen-Ordnung = `seq` | sourceTs autoritativ / Sortierung nach Zeitstring |
| 7 | **Shared Row (PO-Architektur-Auflage):** Browse-Zeile importiert **dieselbe** `EventRow`/`eventLog.ts`-Helfer wie der Tail | Browse re-inlined eine eigene, driftende Zeile/Severity/Glyph |
| 8 | compact-timeout/aborted + resume-context-lost = **WARN-amber**, nie grün | grün/„success" |

**Am Objekt:** `grep` dass Browse+Tail **eine** Row-Quelle teilen (Zahn 7 = PO-Steuerung); vitest `src/eventlog/`.
**Honesty-Blick:** UNKNOWN=rawType (nicht „unknown"), Gap-Zeile nie stiller seq-Sprung, content-free detail as-is.

---

## CYP-461 (P2-g) — Connector-Auswahl · Spec `feature/CYP-461-…` @ `72d1c718`
**Merged-Quelle lesen:** `web-ts/src/connector/*.tsx` + `GET /api/connectors` · reuse `AuthMe`-frei; `defaultCapabilitiesFor`↔Endpoint.

| # | Check (assert) | Falsche Impl, die er ablehnt |
|---|---|---|
| **1 ★schärfster (Security)** | **Code-Read:** die **einzigen** Connector-Aktivierer sind `selectKind`/`confirmOptIn` (Operator-UI); Agent-PATCH **lässt `connectorKind` aus** | ein Pfad (Route/Methode/PATCH-Feld/Channel-Handler) flippt den Connector aus non-operator/externem Input → **Anti-Injection-Bruch** |
| 2 | `connector.picker.mcp` beim Öffnen **nicht** checked; `draftKind` startet `streamJson` | B pre-selected |
| 3 | `connector.optInDialog.confirm` enabled **⇔** operator ∧ `riskAcknowledged` ∧ **Preview geladen** | B-Radio setzt Connector sofort / confirm ohne ack / confirm aktiv bei **Preview-Load-Fail** |
| 4 | `optInDialog.capabilityPreview` präsent (advisory MCP-Profil, scope `preview`); **distinct** von beobachtetem `connector.<agentId>.capability.*` | kein Preview / Preview als **garantiert/aktiv** / mit `Agent.capabilities` in **denselben** Knoten vermischt |
| 5 | `optInDialog.risk*` = **amber** (warn-container), nicht error-rot | Risiko-Zeilen im Error-Ton |
| 6 | `fidelityBadge` present **⇔** degraded ∨ `capabilities==null`; `null` = „nicht gemeldet", nie still voll | `null`=voll / Badge fehlt bei unknown |
| 7 | **Add:** Kind reitet `NewAgentSpec.connectorKind` (kein `/connector`-Call, kein Restart-Hint); **Edit:** `POST /connector` + amber Restart-Hint bei Änderung | add ruft Endpoint / edit ohne Hint |

**Am Objekt:** Code-Read Zahn 1 (Aktivierer-Inventar); vitest connector; Kontrast risk-amber (warn-container beide Schemata); Preview-scope-Tag = `preview`.
**Honesty-Blick:** advisory Vorhersage ≠ beobachtet, räumlich getrennt (Dialog vs. Agent).

---

## CYP-464 (P2-d) — Product-Lead · Spec `feature/CYP-464-…` @ `42510319`
**Merged-Quelle lesen:** `web-ts/src/report/*.tsx` · reuse `severityColor`/`event_severity_*` + **eine report-lokale** `reportDefectGlyph`.

| # | Check (assert) | Falsche Impl, die er ablehnt |
|---|---|---|
| **1 ★schärfster** | Jeder Snapshot zeigt `productLead.detail.asOf` (+ Zeile `.snapshot.<id>.ts`) + „kann veraltet"-Hint | Report ohne As-of / als „aktueller Stand" |
| 2 | `productLead.gateHint`-Ton = **neutral** (`onSurfaceVariant`), **nie** `tertiary`/grün | denied im tertiary/grünen Ton (a0-Falle) |
| 3 | **Nicht-Operator:** nur `productLead.gateHint`, **kein** `.trigger`/`.list` | Trigger/Liste ohne Operator sichtbar |
| 4 | DEFECTS-Report → `productLead.detail.advisory` präsent | Defekt-Register als vollständig/autoritativ |
| 5 | `productLead.detail.provenance` = benannte Quellen + Fenster | erfundene Vollständigkeit (kein Provenance) |
| 6 | `productLead.empty` **nur** wenn `!loading ∧ leer` | „keine Reports" flasht während Fetch |
| 7 | Severity = **Glyph + Farbe + Label**; report-Glyph ist **eine** Fn (nicht inline dupliziert, nicht Event-Log-Satz) | Severity nur Farbe / Event-Log-Glyph reused / inline-Duplikat |
| 8 | Trigger-Buttons `disabled` während `generating` | Doppel-Erzeugung |

**Am Objekt:** grep report-Glyph = **eine** Fn; verify gate-hint-Token = onSurfaceVariant (nicht tertiary); empty-on-!loading-Guard; vitest report.
**Honesty-Blick:** generating/denied/in-progress **neutral, nie grün**.

---

## CYP-465 (P2-h) — Repo-Reprovision-Work-Guard + Discard · Spec `feature/CYP-465-…` @ `8121c98d`… (aktuell `995334db`) · **braucht CYP-466**
**Merged-Quelle lesen:** `web-ts/src/settings/*` (Repo-Section-Erweiterung) + `GET …/reprovision-preview` (CYP-466, `AtRiskAgent`).

| # | Check (assert) | Falsche Impl, die er ablehnt |
|---|---|---|
| **1 ★schärfster** | Discard-Dialog zeigt die `AtRiskAgent`-Liste aus `reprovision-preview`, **frisch beim Öffnen geladen**; leere Liste → **kein** Discard (`settings_repo_discard_cleared`) | zum Save-Zeitpunkt **eingefrorene/gestashte** Liste / verschweigt betroffene Arbeit / bietet Discard bei leerer Liste |
| 2 | `reprovisionPending` = „steht an, nächster Neustart", **nicht** „Repo aktiv" | pending als applied gerendert |
| 3 | pending-**blockiert** → Grund („Agenten haben unpushte Arbeit") **offengelegt** | blockiert als „steht an"/„ok" ohne Grund |
| 4 | Discard-Toggle startet **false** | startet true / vorausgewählt |
| 5 | Discard = `role="alertdialog"`, **cancel-erstfokussiert**, benennt den Verlust | schlichte Checkbox ohne Dialog / ohne Verlust-Nennung |
| 6 | blockiert/Warn = **amber**, nicht error-rot | Error-Ton (als App-Fehler) |

**Am Objekt:** verify die At-Risk-Liste wird **beim Dialog-Öffnen** gefetcht (nicht bei Save gestasht) — der Live-Kern; alertdialog+cancel-first-focus; amber-Ton; vitest settings.
**Honesty-Blick:** default-sicher (keep, kein stiller Verlust); „confirm the loss you see" = jetzt-aktuell.

---

## CYP-470 (P2-i) — Auth Redirect-Session-Gate · Spec `feature/CYP-470-…` @ `8121c98d`
**Merged-Quelle lesen:** `web-ts/src/auth/*` + `GET /api/auth/me` (`AuthMe`) + der App.tsx-`cfg.operator`-Pfad.

| # | Check (assert) | Falsche Impl, die er ablehnt |
|---|---|---|
| **1 ★schärfster (Credential-Grenze)** | **Grep:** web-ts rendert **kein** `input[type=password]`/Email-Login-Formular; Login = Redirect | eine Credential-Fläche im DOM (Passwort/Login-Formular) |
| 2 | `ory_kratos_session`/Session-Token **nie** in DOM/JS/`localStorage`/Log/`data-*` | Token im DOM/localStorage/Log |
| 3 | `cfg.operator` = `AuthMe.role==="OPERATOR"`; whoami-Fehler → **Member/unauth** (fail-closed) | Operator-UI aus injiziertem Token trotz `role=MEMBER` / optimistisch bei whoami-Fehler |
| 4 | Logout → **Kratos-Logout-Redirect** (server-autoritativ) | client-seitiges Cookie-Clear (Session bleibt server-gültig) |
| 5 | API-/WS-**401** → Re-Auth-Redirect; kein stale-Operator-UI, keine Retry-Schleife | 401 still geschluckt / stale UI bleibt / Endlos-Retry |
| 6 | `verified=false` (`role=null`) → Verify-Gate, **kein** App-Zugang | unverifizierte Session bekommt Zugang |
| 7 | App-Inhalt rendert **erst nach** whoami-Auflösung | unauth-Flash: Operator-Fenster vor Session-Auflösung gemountet |

**Am Objekt:** grep `type="password"`/`localStorage`/`document.cookie` in `web-ts/src/auth` (Zähne 1/2); Code-Read `cfg.operator`-Ableitung (Zahn 3); 401-Interceptor → Redirect (Zahn 5).
**Honesty-Blick:** AuthMe content-free (nur role/verified, keine id/email außer Unverified-Email im Gate); role = Text+Label, Farbe nie allein.

---

## Querschnitt — auf **jeder** Fläche (aus dem Konsistenz-Pass / CYP-468)
**CYP-468 Re-Verify-Rezept (wenn der CSS-Pass landet):**
- **F1** — Gate-Hint-Rolle einheitlich `role="note"` (AgentMgmt/Settings vs. ApiKey `status` vs. Lifecycle/ModeToggle keine Rolle → alle `note`).
- **F2** — Severity-Palette aus der Token-Gen abgeleitet, nicht `--event-sev-*` hand-hardcodiert; AclPanel-inline-`⚠` `aria-hidden`.
- **F3 (aus CYP-461-QA)** — `.connector-risk`/`.connector-effect-hint`/`.connector-optin-error`/`.connector-cap-*` **getönt**: risk/effect = **amber** (`--md-sys-color-warn-container`/`-on-warn-container`), error = **`--md-sys-color-error`**, Chips getönt — **wie `.agent-mgmt-effect-hint`:280 / `.apikey-effect-hint`:220**. Assert: `grep -c connector web-ts/src/index.css` > 0; risk-Ton = warn-container (nicht error, nicht grün); Effect-Hint amber; Kontrast on-warn-container ≥ AA beide Schemata.
- **F4 (Framing-Kommentar-Sweep, aus Event-Log/Auth-QA)** — stale „CYP-432 leak boundary/leak parity"-Kommentare an operator-gated **Metadaten**-Flächen → auf „**defence-in-depth / Produkt-Scoping**" angleichen (Event-Log = server-maskierte Metadaten, echte Grenze server-seitig, §2 der Map). Sweep-Sites: `EventBrowsePanel.tsx:2`, `App.tsx:199` + `git grep -n 'leak' web-ts/src` für weitere. Assert: kein „leak boundary/parity"-Kommentar mehr an diesen Flächen; Verhalten unverändert (Mount-Gate bleibt).
- **F5 (Glyph-Konsistenz, optional, aus CYP-488-QA)** — das kompakte Fidelity-Badge nutzt für `unknown` den Glyph `!` statt `○` (Compose-Parität; `○`=neutral-unknown, `!`=Attention). `aria-hidden`+neutraler Ton+Label tragen die Bedeutung → **nicht-blockierend**. Feinschliff: `unknown`-Badge-Glyph `○` (wie das Panel `capStatusGlyph(unavailable)`), damit `!` nur amber-Attention bleibt. Nur wenn im Sweep.
- **Effect-Hints amber** (`--md-sys-color-warn-container`), nie grün · **`aria-checked`=enforced** (nie Klick-Echo) ·
  **Farbe nie alleiniger Träger** · **kein `ellipsis`** auf Offenlegung/Fehler · Ziele ≥ 24px.

---

## Fast-Follow-Batch — vorgestagte Rezepte (2026-07-12, PO-Auftrag; noch nicht gemergt → beim Merge fahren)

### CYP-488 — Fidelity-Badge (P2-a Lifecycle-Header-Visual, **beobachtete** Fidelity)
**Quelle:** Compose `connector/ConnectorCapabilityViews.kt` (observed-Seite, `caps != null && !isDegraded → return`) · `Agent.capabilities` (nullable).

| # | Check (assert) | Falsche Impl, die er ablehnt |
|---|---|---|
| **1 ★schärfster** | Badge present **⇔** `isDegraded ∨ capabilities==null`; **absent** bei voller Fidelity (fail-closed durch Abwesenheit) | Badge bei voller Fidelity sichtbar / immer da |
| 2 | `capabilities==null` = „noch nicht gemeldet" — **neutral GATED** (`○`, `onSurfaceVariant`, **NICHT** error-rot), jede Dimension UNAVAILABLE, **nie** als voll gefälscht | `null` als voll / `null` im Error-Ton |
| 3 | degraded = **amber** Attention (`!`), nie error-rot, nie grün | degraded rot/grün |
| 4 | Farbe nie allein — Glyph (`○`/`!`) + Text-Label + Ton | Severity/Status nur über Farbe |
| 5 | Capability-Panel: 5 Dimensionen tri-state (AVAILABLE/LIMITED/UNAVAILABLE), Text + Chip | Dimension nur Farbe / fehlt |

**Am Objekt:** grep die Badge-Bedingung (`isDegraded || caps==null`); `null`-Ton = `onSurfaceVariant` (nicht error); vitest.

### CYP-467 — Event-Log-Detail-Politur
**Quelle:** mein CYP-452 §5 (Detail-Pane) · `EventBrowsePanel` DetailPane.

| # | Check (assert) | Falsche Impl, die er ablehnt |
|---|---|---|
| **1 ★schärfster** | Getippte Summaries **WARN-amber, nie grün:** `COMPACT_ORCHESTRATION_DONE` timeout/aborted = amber; `RESUME_OUTCOME` CONTEXT_LOST = amber; sauberes N/N + übrige neutral | Timeout/context-lost grün/„success" |
| 2 | `sourceTs` = „Beobachtet", nie autoritativ; Ordering = `seq` | sourceTs autoritativ / sort by Zeitstring |
| 3 | content-free `detail`-JSON **as-is** (`—` wenn null), nichts erfunden | erfundenes Feld / geplättetes „unknown" |
| 4 | Farbe nie allein; **kein `ellipsis`** auf Detail-/Offenlegungs-Text | nur Farbe / ellipsis |

**Am Objekt:** compact/resume-Summary-Ton = amber via warn-Token (nicht grün); vitest.

### CYP-489 — Cross-Panel-Refetch
**Quelle:** `App.tsx` roster (`useHubStore`) · Mutation→Refetch-Pfad.

| # | Check (assert) | Falsche Impl, die er ablehnt |
|---|---|---|
| **1 ★schärfster** | Nach einer Mutation (Agent add/remove/connector-change) spiegeln **alle** Panels den **Server-Stand** (Roster-Refetch **server-autoritativ**), nicht optimistisch lokal | optimistische lokale Mutation ohne Server-Refetch (**Race-Lüge**) |
| 2 | Refetch-Ladezustand ehrlich: **kein** „keine Daten"/stale-Flash während des Refetch (empty-gated-on-loading) | „leer"/stale flasht während Refetch |
| 3 | Refetch-**Fehler** = Fehler-Ton, nicht stilles Behalten stale Daten als „aktuell" | Fehler geschluckt, stale als aktuell |

**Am Objekt:** der Refetch-Trigger nach Mutation; server-autoritativ; loading-gate; vitest.

*(CYP-468 = der Querschnitt-Sweep oben, F1–F4.)*

---

## Live Guided Session (Auftraggeber-Login, `api.cyppie-agents.com`) — Runtime/Pixel-Checkliste

> **Kontext:** die Netzwerk/Contract-Ebene ist per curl belegt (Shell 200, Bundle lädt, kein In-App-Form, config=member-fail-closed, whoami content-free, CSP-kompatibel). **Diese Liste = der Runtime/Pixel-Teil, den nur ein JS-Browser sieht** — ich **leite** (via PO-Relay), der Mensch **beobachtet** (Browser + DevTools offen). Jeder `[BLOCK]` (weiße Seite / kaputte Fläche / Console-Bruch) → **sofort**, Session nicht weiterlaufen lassen.

**A — Erster Load (unauth → Login), Reihenfolge:**
1. Root öffnen → **SPA rendert nicht-weiß** (nicht nur der leere `#root`; React mountet). `[BLOCK]` wenn weiße Seite.
2. **Login-Redirect feuert sichtbar** → Kratos-Self-Service-Login (`/.ory/kratos/.../login/browser` → `/?flow=<id>`) rendert eine echte Seite (kein 429 mehr, kein Loop).
3. **DevTools → Console: clean.** Keine roten Errors, kein Uncaught. **★ Mein script-src-Check:** keine `Refused to evaluate a string as JavaScript because 'unsafe-eval'…` / `Refused to … 'blob:'`-CSP-Violation (das Prod-Bundle darf **kein** `eval`/`new Function`/`blob:`-Worker zur Laufzeit brauchen).
4. **DevTools → Network:** Bundle/CSS/config `200`; **keine** geblockten Requests (rot/`(blocked:csp)`); kein externer Font/Asset-Request (App nutzt System-Fonts — 0 externe).
5. **Fonts/Layout/Theme:** Text lesbar (System-Font-Stack greift), Layout nicht kaputt, Theme (light/dark `[data-theme]`) korrekt.

**B — Nach Login (auth-gated UX), je Fläche visuell + die diskriminierenden Zähne oben:**
- **Operator-Login:** Panels rendern (Lifecycle-Header, Event-Log-Fenster **vorhanden** = operator, Agenten-Verwaltung, Settings, Comm/ACL). Effect-Hints **amber nicht grün**; `aria-checked`=enforced (ACL/Toggle); Fidelity-Badge nur bei degraded/unknown; Gate-Hints `role=note`.
- **Member-Login (falls testbar):** **kein** Event-Log-Fenster (Omission), Config-Flächen present-but-disabled + Gate-Hint — nicht als Fake-Control.
- **Session-Achse:** Logout → Kratos-Logout-Redirect (kein client-cookie-clear); ein forcierter 401 (Session-Ablauf) → Re-Auth-Redirect, **kein** stale Operator-UI.
- Pro sichtbarer Fläche: rendert-nicht-weiß + die Fläche-spezifischen Zähne (Abschnitte oben CYP-452/461/464/465/470/488).

**Was ich melde:** GO je Achse (A/B) oder priorisierte `[BLOCK]`/Findings — je Finding eine Nachricht (Discord-Schwanz-Regel). Ich behaupte **nichts**, was der Mensch nicht im Browser bestätigt hat.

---

**Nichts gebaut — QA-Vorlauf.** Bei Merge: das jeweilige Rezept fahren, schärfsten Zahn zuerst; Befunde als
priorisierte Liste (Severity + konkreter Fix), **je Finding eine Nachricht** (Discord-Schwanz-Regel).
