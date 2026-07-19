# Modell 2 (Multi-Hub / Aussteller-Vertrauen) — CLIENT-NEEDS als Anforderungen an den Trust-Kontrakt · web-ts

**Für:** Team-1 (Aussteller-/Trust-Kontrakt-Design) — **über den Koordinator** · **Von:** UIUX2 (Team-2) · **Assist2:** Synthese-Hilfe
**Baseline (am Objekt):** develop, web-ts single-hub-Identität — `state/hubConfig.ts`, `platform/operatorToken.ts`, `platform/appConfig.ts`, `auth/authConfig.ts`, `auth/AuthGate.tsx`, `net/rest.ts`.
**Art:** **Design-Vorarbeit.** Dies sind **ANFORDERUNGEN an den Trust-Kontrakt (Input für Team-1s Aussteller-Design)** — **kein** angenommener Kontrakt. Wo unten „der Kontrakt muss …" steht, ist das ein **Bedarf**, keine Festlegung des Mechanismus (Token-Format, Signatur, Aussteller-Protokoll = Team-1 + Reviewer/Security).
**Scope (PL/po-gepinnt, CYP-747):** **mehrere Hub-Identitäten DESSELBEN Operators** — **keine** fremden/Cross-Operator-Hubs. Alles unten ist same-operator-multi-hub; Föderation mit fremden Operatoren ist **außer Scope**.
**human-identity:** **n / seat-scoped — RESOLVED (PL, volle Zuversicht, 2026-07-19).** Die Client-*Bedarfe* (UX/State) sind seat-scoped (n). **Objekt-Grund:** `pinnedOperatorId` + Operator-Device-PoP **IST** der funktionale Operator-Seat (kein modellierter Mensch); Modell 2 **generalisiert genau diesen Seat** über die Hubs; `AuthMe` ist pausiert → ein portabler Mensch ist heute gar nicht baubar. **Die j-Weiche bleibt benannt+markiert** (nicht offen, nicht gelöscht): würde „eine Operator-Identität über Hubs" morgen eine **portable Menschen-/Prinzipal-Identität** meinen (Aussteller = IdP für einen Menschen), wäre das eine **separate, größere §5b/AuthMe-Weiche** — sichtbares Delta, **PL/Auftraggeber**, nicht hier. Siehe §3, §4.Q1.

---

## §0 Was Modell 2 ändert (der Kern, am Objekt gemessen)

Heute ist die web-ts-Identität **single-origin, single-hub** — und *genau* diese drei Annahmen bricht Modell 2:

| Heute (single-hub) | Ort | Modell 2 bricht das, weil … |
|---|---|---|
| **Ein** `apiBase`/`wsBase` (global, einwertig) | `hubConfig.ts`, `appConfig.ts` | der Client mit **N Hubs** redet → N Endpunkte, je Hub ge-keyed. |
| **Ein** globales Auth-Artefakt: `operatorToken()` (ein Bearer, überallhin) **oder** die **first-party same-origin** Kratos-Cookie (`ory_kratos_session`) | `operatorToken.ts`, `rest.ts`, `authConfig.ts` | ein **ambientes same-origin** Artefakt spannt **strukturell nicht** über Origins. Hub B ist ein **anderer Origin** → die Cookie trägt nicht; ein globaler Bearer, naiv überallhin gesendet, wäre ein **Cross-Hub-Credential-Leak** (§2.N2). |
| **Ein** whoami → **eine** Rolle; **ein** globaler `/api`-401 kippt die **ganze** App auf Re-Login | `AuthGate.tsx`, `rest.ts` | Rolle/Tier ist **per Hub**; ein 401 von Hub B darf die Hub-A-Sitzung **nicht** mitreißen (§2.N4). |

**Aussteller-Vertrauen (mein Verständnis, zu bestätigen §5.Q1):** Hub B vertraut der Operator-Identität, **weil ein Aussteller X vouch't** — **nicht**, weil der Operator bei Hub B ein natives Konto hat. Das ist ein **abgeleitetes, widerrufbares** Vertrauen, kein durables natives Identsein. **Diese Unterscheidung ist der Honesty-Kern** (§3).

---

## §1 Client-Needs als Anforderungen (der Auftrag)

> Jeder Bedarf ist als **Anforderung an den Kontrakt / an das Client-Verhalten** formuliert, mit dem **Honesty-/Fail-closed-Grund**. Der Mechanismus bleibt offen für Team-1.

### N1 — Mehrere Hub-Identitäten halten (Registry statt Global)
- **Bedarf:** Der Client hält eine **Menge** `{ hubId → (endpoint, Vertrauens-/Verbindungszustand, aufgelöste Rolle/Tier, Credential-Handle) }` statt der heutigen **einwertigen** Globals. `hubId` ist der Schlüssel für **alles** Hub-Bezogene (Endpoint-Wahl, Credential-Routing, Zustands-Render).
- **Grund:** Ohne Keying kollabiert N-Hub-State auf ein Global → Cross-Hub-Verwechslung (falscher Endpoint, falsches Credential, falscher Zustand).
- **Anforderung an den Kontrakt:** eine **stabile, kollisionsfreie `hubId`** (kein re-key stiller Drift). Idealerweise die **Aussteller-verankerte** Hub-Identität, damit „welcher Hub" nicht am rohen, tauschbaren Origin-String hängt.

### N2 — Credential an Hub B präsentieren (audience-gebunden, nie ambient-global)
- **Bedarf:** Der Client präsentiert Hub B ein **explizites, portables Proof** (kein ambientes same-origin-Cookie — das trägt cross-origin nicht). Das Proof ist **je Hub-Audience ge-keyed**; der Client sendet **nie** das Credential von Hub A an Hub B.
- **Grund (verify-don't-trust · [[client-gate-is-not-the-boundary]]):** Das per-Hub-Keying im Client ist **UX-Korrektheit + Defence-in-Depth** — **nicht** die Grenze. Die **echte** Grenze ist server-seitig: **jeder Hub muss ein fehl-adressiertes (falsche Audience) Credential ablehnen.** Client-Routing allein ist kein Schutz.
- **Anforderung an den Kontrakt (Team-1):** das Proof **trägt/bindet seine Ziel-Audience** (welcher Hub es akzeptieren darf), sodass (a) der Client korrekt routen kann und (b) **der Empfänger-Hub ein wrong-audience-Proof strukturell zurückweist (Test-beweisbar, nicht Absicht).** Lebensdauer/Refresh-Semantik des Proofs → §5.Q2.
- **CSP-Konsequenz (flag, nicht meine Entscheidung):** Cross-Origin-Reden mit Hub B weitet `connect-src` über `'self'` hinaus. Das ist eine **Security-Fläche** — **Team-1/Reviewer** designen die erlaubte Origin-Menge (idealerweise Aussteller-verankert, kein Wildcard). Ich benenne nur die Folge.

### N3 — Per-Hub-Vertrauenszustand: distinkte, ehrliche Zustände (der Honesty-Kern)
- **Bedarf:** Der Client hält **je Hub** einen Vertrauens-/Verbindungszustand und rendert ihn **ehrlich** — **fail-closed default = NICHT-vertraut**, bis Hub B es **affirmiert**. Semantisch distinkte Zustände dürfen **nicht** auf dasselbe „verbunden"/„grün" kollabieren ([[reconcile-not-collapse-distinct-states]], [[absence-reads-as-all-clear]]):

  | Zustand | Bedeutung | Ehrlicher Render (Regel) |
  |---|---|---|
  | **UNKNOWN** | Hub noch nicht kontaktiert / lädt | neutral, sichtbar „noch nicht geprüft" — **nie** „verbunden" |
  | **PENDING** | Proof präsentiert, Hub B verifiziert noch | neutral „wird geprüft" — **nie** vorab „vertraut" |
  | **TRUSTED** | Hub B hat **affirmiert** | positiv — **nur** hier |
  | **REJECTED** | Hub B hat das Proof **abgelehnt** | distinkt von Netzfehler (§2.N4) |
  | **STALE** | war TRUSTED, Proof **abgelaufen/widerrufen** mid-session | fail-closed: Hub-B-Flächen **nicht** stale-vertraut weiter zeigen |

- **Grund:** „grün, sobald ich es *versucht* habe" ist die dishonest-optimistic-Falle — dieselbe Klasse wie stale-lit / unknown-als-all-clear. Vertrauen ist **das, was Hub B affirmiert**, nicht was der Client hofft.
- **Anforderung an den Kontrakt:** ein **beobachtbares Affirmations-/Ablehnungs-Signal** von Hub B (der Client kann TRUSTED **nicht** raten) **und** ein **Widerruf-/Ablauf-Signal** (damit STALE eintreten kann, statt fake-fortzubestehen).

### N4 — Re-Auth & Fehlerpfad beim Hub-Wechsel (Ursachen trennen, Ausfall isolieren)
- **Bedarf a — Ursachen nicht kollabieren:** Ein Fehlschlag an Hub B hat **distinkte Ursachen mit distinkter Copy + distinkter Recovery** ([[reconcile-not-collapse-distinct-states]]):
  - **Netz/Hub-B-unerreichbar** (transient) → „erneut versuchen".
  - **Proof abgelehnt** (Trust) → Re-Präsentation/neues Proof nötig.
  - **Aussteller vouch't nicht mehr** (Issuer-Widerruf) → **kein** Client-Retry heilt das; ehrlich „Zugang zu Hub B entzogen".
  Ein generisches „Verbindung fehlgeschlagen" über alle drei ist unehrlich (verschweigt, was der Nutzer tun kann/nicht kann).
- **Bedarf b — Ausfall je Hub isoliert:** Heute kippt **ein** globaler `/api`-401 die **ganze** App auf Re-Login (`AuthGate` / `rest.ts` `setOnUnauthorized`). Modell 2: ein **401/Trust-Verlust an Hub B** darf die **Hub-A-Sitzung nicht** mitreißen. Re-Auth ist **hub-scoped**, nicht global.
- **Anforderung an den Kontrakt:** Fehler-/Reject-Signale von Hub B **maschinen-lesbar unterscheidbar** (Netz vs. Trust-Reject vs. Issuer-Widerruf — analog dem heutigen `{ error: { code } }`-Envelope, `restErrorCode`), damit der Client **kuratiert** statt string-matcht.

### N5 — Hub-Wechsel-UX (aktiver Hub eindeutig, Rolle reist nicht)
- **Bedarf a — aktiver Hub unzweideutig:** Jede Ansicht/Aktion zeigt **eindeutig, welcher Hub** Ziel ist. Beim Wechsel auf Hub B **kein** fake-instant „verbunden" — der per-Hub-Zustand (§2.N3) führt (evtl. PENDING/Re-Auth, wenn das Proof dort nicht live ist).
- **Bedarf b — Rolle/Tier ist per Hub, fail-closed:** Der Operator-Seat an Hub A impliziert **nicht** Operator an Hub B. Rolle/Tier wird **je Hub** aus **dessen** whoami aufgelöst, **fail-closed auf least privilege** bis aufgelöst. (Direkt analog zur CYP-745-/705-Logik: Scope reist nicht, er wird pro Ort re-aufgelöst.)
- **Grund:** „Operator-überall" anzunehmen, überstellt eine Garantie, die kein Hub gegeben hat — Disclosure-Unehrlichkeit + Über-Berechtigung.

---

## §2 Honesty-Invarianten (meine Lane — der Kontrakt muss sie ZULASSEN)

Nicht verhandelbar, egal welchen Mechanismus Team-1 wählt:
1. **Fail-closed default:** ohne Hub-B-Affirmation ist der Zustand **NICHT-vertraut** (nie optimistisch grün). Fehlt ein Trust-Signal → **kein** Trust-Render (nie geraten).
2. **Abgeleitetes ≠ natives Vertrauen (Disclosure):** die UI stellt Hub-B-Zugang als **vouched/widerrufbar** dar (Aussteller-abgeleitet), **nie** als durables natives Identsein bei Hub B, wenn es das nicht ist ([[forecast-vs-observed-disclosure]], [[status-word-certainty-register]]).
3. **Distinkte Zustände, distinkte Renders:** UNKNOWN/PENDING/TRUSTED/REJECTED/STALE nie kollabieren ([[reconcile-not-collapse-distinct-states]]).
4. **Ursache-ehrliche Fehler:** Netz vs. Trust-Reject vs. Issuer-Widerruf getrennt (§2.N4a).
5. **Kein Cross-Hub-Leak:** Credential je Audience ge-keyed; Client-Keying ist Defence-in-Depth, die **Grenze ist der ablehnende Empfänger-Hub** (§2.N2, [[client-gate-is-not-the-boundary]]).
6. **Colour-never-sole (WCAG 1.4.1):** Vertrauenszustand nie nur über Farbe (Glyph/Text tragen mit).

---

## §3 Was ich BEWUSST NICHT designe (die n/j-Grenze)

- **Ich NICHT:** Proof-/Token-Format, Signatur/Krypto, Aussteller-Protokoll, Credential-Lebensdauer/Rotation, die erlaubte Cross-Origin/CSP-Menge. Das ist **Team-1 + Reviewer/Security**. Ich liefere **Bedarfe/Zustände/UX-Anforderungen** dazu.
- **n — RESOLVED (PL, volle Zuversicht):** die Client-*Bedarfe* sind seat-scoped → **n**. Der Seat ist real am Objekt: `pinnedOperatorId` + Operator-Device-PoP **ist** der funktionale Operator-Seat (kein modellierter Mensch); Modell 2 **generalisiert diesen Seat** über die Hubs. `AuthMe` pausiert → portabler Mensch heute nicht baubar. **Kein Platzhalter.**
- **★ Die j-Weiche (benannt+markiert, nicht offen, nicht gelöscht):** würde „eine Operator-Identität über Hubs" morgen eine **portable Menschen-/Prinzipal-Identität** meinen (Aussteller = IdP für einen *Menschen*), wäre das eine **separate, größere §5b/AuthMe-Weiche** — **PL/Auftraggeber**, separat adjudiziert. Ich halte das **Delta sichtbar** (falls der Scope kippt), **löse es aber nicht** und **blockiere nicht darauf** ([[scope-boundary-is-semantic-not-labeled]]).

---

## §4 Offene Fragen (Input, den ich von Team-1/PL zurück brauche)

- **~~Q1~~ (RESOLVED 2026-07-19, PL, volle Zuversicht): SEAT (n).** „Eine Operator-Identität über Hubs" ist der **fixe Seat** — `pinnedOperatorId` + Operator-Device-PoP generalisiert über die Hubs; kein modellierter Mensch (`AuthMe` pausiert). Die **j-Variante** (portabler Mensch, Aussteller=IdP) bleibt als **separate §5b/AuthMe-Weiche benannt+markiert**, nicht offen-blockierend. (Siehe §0-Header, §3.)
- **Q2:** Proof-**Lebensdauer/Refresh** — kurzlebig+Rotation (dann braucht der Client einen Refresh-/Re-Präsentations-Pfad, §2.N4) oder langlebig? Prägt den STALE-Zustand (§2.N3).
- **Q3:** Ist der Vertrauens-**Widerruf** beobachtbar (Push/Signal) oder nur lazy beim nächsten Call spürbar? Prägt, wie schnell STALE ehrlich eintritt.
- **Q4:** Wird zur Laufzeit **gleichzeitig** mit mehreren Hubs geredet (paralleler State) oder gibt es genau **einen aktiven** Hub (Wechsel = Teardown/Setup)? Prägt N1s Registry-Umfang und die N5-UX.
- **Q5:** Kann derselbe Aussteller einer Identität an **verschiedenen Hubs verschiedene Rollen/Tier** geben (bestätigt N5b) — und wo wird das aufgelöst (Hub-whoami vs. Aussteller-Claim)?

---

## §5 Nächster Schritt (Vorschlag)

Diese Anforderungen sind **Input für Team-1s Aussteller-/Trust-Kontrakt-Design**. Sobald **Q1** (n/j) und **Q4** (paralleler vs. aktiver Hub) beantwortet sind, kann ich (a) den **per-Hub-Vertrauenszustand als konkrete UX-Zustands-Spec** ausdesignen (die §2.N3-Tabelle → Render + testid + aria) und (b) die **Hub-Wechsel-/Re-Auth-Flows** als Screen-Flow spezifizieren — beides erst, wenn der Kontrakt-Rahmen von Team-1 steht (nicht auf unbestätigter Annahme bauen).
