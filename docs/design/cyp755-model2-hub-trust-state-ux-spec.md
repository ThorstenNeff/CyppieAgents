# Modell 2 — Per-Hub-Vertrauenszustand + Hub-Wechsel-Flow · konkrete UX-Spec (web-ts)

**Für:** Dev5 (Client-Render) + Team-1 (Trust-Frame-Abhängigkeiten) — **über den Koordinator** · **Von:** UIUX2 (Team-2)
**Baseline (am Objekt):** develop, web-ts. **Folgt auf** `model2-multihub-client-needs.md` (CYP-748, die Bedarfe). Dies ist die **konkrete UX-Spec** für die Teile, die auf den **ratifizierten Weichen** tragen:
**Weichen (PL, 2026-07-19):** **Q1 = SEAT (n)** · **Q4 = ONE-ACTIVE-HUB** (nicht parallel — Registry = Liste + aktiver-Pointer, switch-first; Abstraktion hält parallel-später offen).
**⚠ Trust-Frame-Grenze:** exakte Zustands-**Übergänge** (was PENDING→TRUSTED auslöst), **Credential-Präsentations-Mechanik** und das **Widerruf-Signal** (push vs. lazy → STALE-Timing) **warten auf Team-1s Trust-Frame** und sind unten als **[TF]** markiert. Alles Nicht-[TF] trägt auf seat+one-active **jetzt**.
**Scope:** **NUR web-ts / TypeScript** (`uiux2/CyppieAgents/web-ts`). **★ Abgrenzung:** die **Compose/wasmJs**-Trust-Fläche (**CYP-797**, `IssuerNotTrusted`) ist **Team-1** — hier **kein Compose**.
**IST-Stand frisch gemessen (2026-07-21):** web-ts ist heute **single-origin/single-hub** — `state/hubConfig.ts` **ein** `apiBase`/`wsBase`; `platform/operatorToken.ts` **ein** globales `CYPPIE_OPERATOR_TOKEN` (sonst first-party same-origin Cookie, spannt nicht cross-origin); **ein** whoami/Rolle; **globaler** `/api`-401 kippt die ganze App. Genau diese Annahmen bricht Modell 2.
**Single-source:** dies ist die **maßgebliche Quelle**; die frühere `po2/`-Kopie ist entfernt (Drift-Vermeidung).
**★ Promotion-Kontext (Backend2-objekt-bestätigt):** die Trust-State/Connect-Flow-Schicht liegt heute in **`app/shared`, noch NICHT in `:core`** — sie muss erst **promotet** werden, bevor web-ts sie konsumieren kann. **Diese Spec ist damit auch Anforderungs-Input für die `:core`-Promotion (Team-1) — siehe §4b.**

---

## §0 Was jetzt baubar ist vs. was wartet
- **Baubar jetzt (seat + one-active):** das **5-Zustands-Render** (Glyph/Label/Tone/testid/aria), die **Platzierung** (always-visible aktiver-Hub-Indikator + Switcher-Liste), der **Teardown/Setup-Flow-Rahmen**, die **Fehler-Ursachen-Trennung** (Copy-Register), die **Honesty-Invarianten**.
- **[TF] wartet auf Team-1:** die konkreten **Trigger** je Übergang, das **Reject-Code-Taxonomie**-Set (welche `error.code`s), das **Widerruf-Signal** (bestimmt, wie schnell STALE ehrlich eintritt), die **Credential-Präsentations**-Schritte. Ich spezifiziere die **Zustände + Renders + Flow**; die **Kanten** dockt Team-1 an.

---

## §1 Der Per-Hub-Vertrauenszustand — das 5-Zustands-Render (konkret)

**Modell (Client-seitig, eine Quelle):** `HubTrustState = 'unknown' | 'pending' | 'trusted' | 'rejected' | 'stale'`. **Fail-closed Default = `unknown`.** Distinkt gerendert — **colour-never-sole** (jeder Zustand trägt eine distinkte **Glyph-Form + Label-Text**, Farbe ist sekundär), und **over-alarm-vermeidend** (unknown/pending sind **neutral**, kein Alarm; nur rejected/stale tragen Handlungsgewicht). **`trusted` ist ebenfalls neutral-gerendert (definit, aber keine positive-Affirmation)** — siehe Ruling unten.

| Zustand | Bedeutung | Glyph (Form-Achse) | Label (DE) | Tone | render-Regel |
|---|---|---|---|---|---|
| **unknown** | Hub noch nicht kontaktiert / lädt | `◯` (leerer Ring) | „Vertrauen nicht geprüft" | neutral | **fail-closed default**, sichtbar (nicht Stille) |
| **pending** | Proof präsentiert, Hub verifiziert noch [TF-Trigger] | `◔` (Viertel-Füllung) | „wird geprüft…" | neutral | nur bei **echt laufender** Verifikation — **nie** für terminal reject/stale |
| **trusted** | Hub hat affirmiert [TF-Trigger] | `●` (voll) | „vertraut" | **neutral-definit** | **keine positive-Affirmation** — Form `●` (voll) trägt „evaluiert-gültig", nicht der Ton (s. Ruling) |
| **rejected** | Hub hat das Proof abgelehnt [TF-code] | `⊘` (durchgestrichen) | „abgelehnt" | warnend | distinkt von Netzfehler (§4) |
| **stale** | war trusted, Proof abgelaufen/widerrufen [TF-Signal] | `◑` (halb, „war voll") | „abgelaufen — erneut bestätigen" | handlungs-neutral | fail-closed: hub-Flächen **nicht** stale-vertraut weiterzeigen |

**Honesty-Kern (nicht verhandelbar):**
- **unknown ≠ pending ≠ trusted:** drei distinkte Renders — [[absence-reads-as-all-clear]] / [[reconcile-not-collapse-distinct-states]]. Insbesondere **pending ≠ unknown**: „wird geprüft" darf **nur** für laufende Verifikation stehen, **nie** als Sammel-Label für „nicht verbunden" — genau der Fehler, den ich im Honesty-Sweep als Fund #5 (Tier-Strip „Wird geprüft" für revoked/offline) markiert habe. Hier vermeidet das 5-Zustands-Modell ihn per Konstruktion.
- **fail-closed:** ohne Affirmation ist der Zustand `unknown` (nie optimistisch `trusted`). Fehlt das Trust-Signal → `unknown`-Render, nie geraten.
- **abgeleitet ≠ nativ:** `trusted` heißt „dieser Hub vertraut deinem Aussteller-vouch" — die Copy/Disclosure macht das **widerrufbar**-Wesen sichtbar, **nie** durables natives Konto (Bedarf N3/Honesty-Kern aus CYP-748).

**★ Ruling `trusted` = neutral-gepinnt, nicht positive-Affirmation (CYP-803, DS-Owner-Entscheid §4a):**
`trusted` ist **abgeleitet + widerrufbar, nie native Identitäts-Affirmation** — dieselbe „derived≠native"-Invariante, die die Trust-Ratifikation (CYP-798/747) schützt. Ein positiver/affirmativer Render (Emphase-Akzent) behandelt einen widerrufbaren Zustand wie eine **Garantie** = Overclaim (Spiegel von [[over-alarm-is-also-dishonest]]: die ehrliche Mitte für einen abgeleitet-gültigen Zustand ist **neutral-definit**, nicht affirmativ). Das **löst die interne §1↔§5b-Spannung auf** (§5b flaggte bereits „Wert nicht als natives Identsein typen", während §1 `positive` wählte). Konvergiert mit Team-1-UIUX (CYP-802).
- **Exakter Token** (am gemergten Objekt `hubTrustView.ts` / `index.css` verifiziert):
  - **`TONE.TRUSTED`: `'positive'` → `'neutral'`** (in `comm/hubTrustView.ts`; Kommentar „the ONLY positive state" mit-anpassen).
  - **CSS-Glyph `.hub-trust-trusted .hub-trust-glyph`: `--md-sys-color-primary` → `--md-sys-color-on-surface`** (NICHT `on-surface-variant`). Begründung: `on-surface` = **voll-emphase-neutral** (definit/evaluiert), distinkt von unknown/pending (`on-surface-variant`, gedämpft = „noch nicht bekannt") → **kein** Kollaps in die Absence-Zustände, aber **kein** Affirm-Akzent. `on-surface`-auf-`surface-variant` ist im DS bereits ein AA-Text-Paar (Label nutzt es, `index.css`), Glyph als Graphik ≥3:1 gedeckt.
  - **Label bleibt „vertraut"** — **NICHT** „gepinnt". „pinned" ist in Model-2 bereits belegt (`pinnedOperatorId`, ONE-ACTIVE-HUB/aktiver-Pointer Q4); „gepinnt" als Trust-Label würde Trust-Zustand mit dem Aktiv-Hub-Pointer/pinned-Operator konflatieren (frische Kollision, `tier*`≠`trust*`-Klasse). Der Overclaim saß im **Ton/der Farbe**, nicht im Wort; „vertraut" (DE: „vertraut/bekannt") ist neutral genug.
  - **a11y bleibt** — trägt schon den ehrlichen Qualifier: „Aussteller-vouched, **widerrufbar**".
  - **Form `●` bleibt** — die FORM-Achse trägt die Distinktion (colour-never-sole), unangetastet.

**Reuse-Anker (kein Neubau):** dieselbe Mechanik wie **`RemoteSecurityTierBadge`** (`remoteSecurityTierModel.ts` — distinkte Glyph-Form `●`/`◐`/`·` + Label + immer-sichtbar fail-closed, nie native-grün) und wie **`statusDotSpec`/`dotRoleVar`** (`lifecycleStatus.ts`, CYP-431 — UNKNOWN=Ring-**Form** distinkt, nicht farb-only). Der Trust-Badge ist eine **Instanz desselben Musters**, nicht ein drittes Dot-Vokabular.

**a11y / testid (je Zustand — ausgebaut):**
- **Container:** `data-testid="hub.trust.{hubId}"`, `role="status"`, `aria-live="polite"` — Zustandswechsel werden **ruhig** angesagt. **Ausnahme (handlungsrelevant):** ein Wechsel des **aktiven** Hubs **in** `rejected`/`stale` mid-session → `aria-live="assertive"` (bzw. eigene `role="alert"`-Ansage). **Nur** dieser aktive-Hub-Wechsel ist assertiv — die stille Switcher-Liste **nicht** (sonst Ansage-Sturm, [[over-alarm-is-also-dishonest]]).
- **Zustands-Marker:** `data-testid="hub.trust.{hubId}.{state}"`; `aria-label` = der **Label-Text** (das Bedeutungs-Wort), **nie** nur Glyph/Farbe.
- **colour-never-sole (WCAG 1.4.1):** jeder der fünf Zustände trägt eine **distinkte Glyph-Form + ein distinktes Wort** — ein farbenblinder/monochromer Nutzer unterscheidet alle fünf **ohne Farbe**. Tone/Farbe ist nur Verstärkung.
- **`unknown` sichtbar, nicht Stille:** der fail-closed-Default rendert einen **sichtbaren neutralen** Marker (nicht Abwesenheit) — sonst liest „kein Marker" als all-clear ([[absence-reads-as-all-clear]]).
- **Klasse:** `hub-trust-{state}` (Tone via CSS-Var, wie `comm-status-{connection}`).

**Casing/Token-Map (Wire UPPERCASE ↔ Präsentation lowercase) — bestätigt für Dev5s CYP-801-Landing-Wiring:**
Muster wie `remoteSecurityTierModel` (Wire-Enum uppercase → Präsentations-Token lowercase, Map am Seam). Die 5 Werte sind **einwortig** → **triviale `.toLowerCase()`**, **KEIN Sonderfall** (anders als `browser-gateway`→`browserGateway` im Tier-Modell — hier gibt es keinen).

| Wire (`:core`, UPPERCASE) | Präsentations-Token | Klasse | testid | Glyph | Label |
|---|---|---|---|---|---|
| `UNKNOWN` | `unknown` | `hub-trust-unknown` | `hub.trust.{id}.unknown` | `◯` | Vertrauen nicht geprüft |
| `PENDING` | `pending` | `hub-trust-pending` | `hub.trust.{id}.pending` | `◔` | wird geprüft… |
| `TRUSTED` | `trusted` | `hub-trust-trusted` | `hub.trust.{id}.trusted` | `●` | vertraut |
| `REJECTED` | `rejected` | `hub-trust-rejected` | `hub.trust.{id}.rejected` | `⊘` | abgelehnt |
| `STALE` | `stale` | `hub-trust-stale` | `hub.trust.{id}.stale` | `◑` | abgelaufen — erneut bestätigen |

- **★ malformed/⚠ ist NICHT in dieser Map** (PL-ratifiziert CYP-798 §4b: **kein Trust-Zustand**, getrennt vom `HubTrustState`-Enum). Auf malformed: Trust-Badge = `unknown` (fail-closed) **+** das **verbindliche** ⚠-Upstream-Signal in einem **distinkten Token-Namespace — NICHT `hub-trust-*`** (sonst wird ein Descriptor-/Upstream-Fehler mit einem Trust-Zustand konflatiert — exakt die Tier-Modell-Disziplin `tier*` ≠ `trust*`, „distinct names so UI/tests/copy never conflate the two"). **Vorschlag:** Klasse `hub-descriptor-invalid`, testid `hub.trust.{id}.upstreamError` — der **exakte Slug richtet sich nach PLs CYP-798-§4b-Signalnamen** (dann angleichen).

---

## §2 Wo es rendert (one-active-hub)

Zwei Orte, beide **Reuse**:
1. **Aktiver-Hub-Indikator — always-visible Chrome-Strip.** Neben/analog dem CYP-733-Tier-Strip (`App.tsx:969-971`, „always visible, a window can be closed"). Der **aktive** Hub + sein Trust-Zustand steht im App-Chrome, **nie** nur in einem schließbaren Fenster. **Wichtig (Sweep-Fund #5-Lehre):** der Strip zeigt den **echten** Zustand des aktiven Hubs — ein toter/abgelehnter aktiver Hub rendert `rejected`/`stale`/`unknown`, **nicht** ein optimistisches „wird geprüft".
2. **Hub-Switcher — Liste + aktiver-Pointer.** Reuse der **`ProjectSwitcher`**-Form (`App.tsx:957`, „always-visible switcher, active pointer"). Jeder Hub-Eintrag trägt sein **eigenes** Trust-Glyph (at-a-glance), der aktive ist markiert. **Honesty:** inaktive Hubs, deren Zustand nicht frisch ist, rendern `unknown` (nicht das letzte gecachte `trusted` — [[forecast-vs-observed-disclosure]]: nicht behaupten, was nicht frisch beobachtet ist).

> **one-active-hub (Q4):** es gibt **genau einen** aktiven Hub mit Live-Verbindung; die Switcher-Liste zeigt die **anderen** als wählbar mit ihrem letzten bekannten (oder `unknown`) Zustand. Kein paralleler Live-State — die Registry-Abstraktion (Liste + Pointer) hält parallel-später offen, ohne es jetzt zu rendern.

---

## §3 Der Hub-Wechsel: Teardown/Setup-Flow (N5)

**Prinzip:** Wechsel = **Teardown A → Setup B**, **resolve-then-render** (nie optimistisch „verbunden"). Reuse der **`AuthGate`**-Doktrin (`AuthGate.tsx` — whoami VOR Render, kein unauth/operator-Flash): Hub-B-Flächen rendern **erst**, wenn B seinen Zustand aufgelöst hat.

**Schritt-Sequenz (Nicht-[TF]-Rahmen):**
1. **Nutzer wählt Hub B** im Switcher (A ist aktiv/`trusted`).
2. **Teardown A:** A's Live-Verbindungen (comm/lifecycle/…) sauber schließen; A's Surfaces **nicht** stale weiterzeigen (A geht auf `unknown` in der Liste, bis erneut kontaktiert).
3. **Setup B — `pending`:** B wird aktiv, Trust-Zustand `pending` („wird geprüft…"), **Credential-Präsentation [TF]**. **Kein fake-instant „verbunden"** — die Chrome zeigt `pending`, die hub-abhängigen Surfaces (comm/roster/…) rendern **noch nicht** als live.
4. **Auflösung:**
   - **`trusted` [TF-Trigger]** → B-Surfaces rendern live; **Rolle/Tier neu aus B's whoami** auflösen, **fail-closed least-privilege** bis aufgelöst (N5b — Operator@A ⇏ Operator@B; reuse `AuthGate`-whoami-Auflösung pro Hub).
   - **`rejected`/Netzfehler/Widerruf [TF-code]** → §4, B-Surfaces **nicht** live, ehrliche Ursache + Recovery.

**Was trägt / was nicht (N5b):** **nichts Identitäts-/Rechte-Bezogenes reist** von A nach B — Rolle/Tier wird pro Hub re-resolved, fail-closed. Persönliche UI-Prefs (Theme, History-Size) sind hub-agnostisch und dürfen tragen. **Kein** Ausleihen von A's `trusted` für B.

---

## §4 Fehlerpfad beim Wechsel — Ursachen trennen (N4)

**Vier distinkte Ursachen, vier distinkte Copys + Recoverys** ([[reconcile-not-collapse-distinct-states]]) — **nie** ein generisches „Verbindung fehlgeschlagen". **★ `malformed` ist ein eigenes diagnostisches Signal, KEIN Trust-Zustand** (PL-Präzisierung, CYP-798): ein korrupter/böswilliger Descriptor muss **diagnostizierbar** bleiben, nicht still als `unknown` verschwinden.

| Ursache | Zustand/Signal | Copy (Register) | Recovery |
|---|---|---|---|
| **Netz / Hub unerreichbar** (transient) | `unknown` (nicht `rejected`!) | „Hub nicht erreichbar" | „erneut versuchen" (manuell) |
| **Ungültiger/korrupter Descriptor** (malformed/uninterpretierbar) | **distinktes Upstream-Signal — KEIN Trust-Zustand** (weder `rejected` noch still `unknown`) | „Ungültiger Hub-Descriptor — Status nicht interpretierbar" | diagnostizierbar/meldbar (kein Trust-Verdikt, kein benigner Retry) |
| **Proof abgelehnt** (Trust) [TF-code] | `rejected` | „Hub hat den Zugang abgelehnt" | Re-Präsentation/neues Proof [TF] |
| **Aussteller vouch't nicht mehr** (Widerruf) [TF-signal] | `stale` | „Zugang zu diesem Hub entzogen" | **kein** Client-Retry heilt das — ehrlich benennen |

- **Register-Trennung:** Netz-Fehler ist **nicht** `rejected` (System ≠ Trust-Verdikt) — dieselbe Linie wie CYP-515 `unavailable ≠ rejected` (reuse `loginFlow.ts`-Doktrin, enumeration-safe/pre-credential).
- **★ `malformed` ≠ `unknown` (PL, CYP-798):** `unknown` ist die **benigne Abwesenheit** einer Bestimmung (noch nicht geprüft); `malformed` ist ein **Fehler-/evtl.-Angriffs-Signal** (invalider Descriptor) und muss **diagnostizierbar** sichtbar sein — nicht ins benigne `unknown` gefaltet (sonst verschwindet ein korrupter/böswilliger Descriptor still — dieselbe distinguishable≠distinguished-Falle). `malformed→unknown` (mein Prototyp) ist fail-closed-korrekt (raus aus `rejected`), aber die **Produktions-Rendering** trägt das **distinkte Upstream-Signal**. **PL präzisiert die exakte Signal-Form bei der CYP-798-Ratifikation.**
- **★ Produktions-Auflage (PL, CYP-798, VERBINDLICH):** das ⚠-„Upstream-Fehler"-Signal wird bei `malformed` **IMMER** getragen — **nicht „optional"**. So ist der distinkte Upstream-Fehler **nie still weg**; ein korrupter/böswilliger Descriptor ist garantiert sichtbar/diagnostizierbar. Dev5s Produktions-Komponente rendert es **unbedingt** (nicht bedingt/aufklappbar).
- **401/Trust-Verlust hub-scoped:** ein 401 von Hub B **kippt nicht** die (heute globale) App — nur B's Surfaces gehen fail-closed. **[Flag an Dev5]** der heutige globale `setOnUnauthorized` (`net/rest.ts`, `AuthGate`) muss für Multi-Hub **hub-scoped** werden — sonst reißt B's 401 A mit. (Bedarf N4b; Umsetzung mit dem Trust-Frame.)

---

## §4b Needs an die `:core`-Promotion — was die Wire-Fläche je Zustand liefern muss

**Kontext:** die Trust-State/Connect-Flow-Schicht liegt heute in **`app/shared`, nicht `:core`** (Backend2-objekt-bestätigt) → sie muss **promotet** werden, bevor web-ts sie konsumiert. Dieser Abschnitt = die **Client-Anforderungen an genau diese Promotion**: was die `:core`-Wire-Fläche **je Zustand** tragen muss, damit der Client ehrlich rendert. **Anforderungen, kein Kontrakt** — Team-1 besitzt den Mechanismus.

**Je-Zustand (die 5 aus §1):**
- **P1 — `unknown` first-class representierbar:** die Wire trägt „keine Trust-Bestimmung / noch nicht geprüft" als **eigenen Wert**, **nicht** als `null`/Absence, die client-seitig zu einem Default kollabiert. Ohne das ist fail-closed unmöglich ([[absence-reads-as-all-clear]]).
- **P2 — `pending` distinkt von `unknown`:** ein **in-Verifikation**-Signal, das der Client vom bloßen unknown trennt — damit „wird geprüft" nur während echter Verifikation steht (Zahn 2, kein Sammel-Label).
- **P3 — `trusted` = explizite Affirmation:** ein **positives, vom Hub ausgestelltes** Signal; der Client darf `trusted` **nie** aus Absence/Optimismus ableiten. *(Der [TF]-`pending→trusted`-Trigger dockt genau hier an.)*
- **P4 — `rejected` = distinkter Reject + Maschinen-Code:** ein Reject mit **maschinenlesbarem Code**, den der Client von **Netzfehler UND von Widerruf** unterscheidet (das N4-Register muss **auf der Wire** liegen, nicht client-geraten).
- **P5 — `stale` = beobachtbares Widerruf-/Ablauf-Signal:** die Wire exponiert Widerruf/Ablauf als **Zustandswechsel**, nicht nur als impliziten Fehlschlag beim nächsten Call — damit `trusted → stale` ehrlich eintritt. **Push vs. lazy** bestimmt das Timing ([TF], Team-1 sub-weiche (a)).

**Querschnitt-Needs:**
- **P6 — per-`hubId`-Keying:** jeder Trust-Wert stabil an eine `hubId` gebunden (N1) — damit one-active + Switcher-Liste je Hub korrekt keyen.
- **P7 — hub-scoped Auth-Fehler:** ein 401/Trust-Verlust muss einem **bestimmten Hub zuschreibbar** sein (nicht global) — Voraussetzung für N4b (hub-scoped statt globaler App-Kippe).
- **P8 — per-Hub whoami/Rolle:** Rolle/Tier **pro Hub** auflösbar (N5 — Rolle reist nicht); die Promotion muss whoami je Hub tragen.

**Beziehung zu [TF]:** die [TF]-Kanten (§5) sind die **Teilmenge** dieser Needs, die erst mit dem Trust-Frame fixiert wird (P3-Trigger, P4-Code-Set, P5-Signal-Modus, Credential-Präsentation). P1/P2/P6/P7/P8 sind **struktur**-Anforderungen an die Promotion, die **unabhängig** vom finalen Trust-Frame benannt werden können.

---

## §5 Trust-Frame-abhängig — [TF] (wartet auf Team-1)
Ich spezifiziere Zustände/Renders/Flow; **diese Kanten** dockt Team-1s Trust-Frame an — **nicht** von mir geraten:
- **PENDING→TRUSTED-Trigger:** welches beobachtbare Affirmations-Signal (N3) B liefert.
- **Reject-Taxonomie:** die konkreten `error.code`s für rejected vs. Netz vs. Widerruf (§4 mappt darauf, sobald sie stehen).
- **Widerruf-Signal:** push (aktiv/beobachtbar) vs. lazy (erst beim nächsten Call) — **bestimmt, wie schnell `stale` ehrlich eintritt.** (Meine Präferenz aus CYP-748: aktiv/beobachtbar, sonst stale-lit-Risiko — Team-1s Call, = deren sub-weiche (a).)
- **Credential-Präsentations-Schritte:** die konkrete Mechanik in §3-Schritt 3.

---

## §5b Präzise Wire-Anforderungen P3/P4/P5 (Bau-Input für die kleine `:core`-Promotion)

**Framing:** dies sind **Client-Anforderungen an die Wire-Fläche** (Feld/Code/Signal je [TF]-Zustand) — **kein** Kontrakt; **exakte Feldnamen/Enum-Werte = Team-1/Backend final** (wie CYP-744). Ich pinne die **Form + die Mindest-Trennschärfe**, die der Client zum ehrlichen Rendern braucht. Umfang klein: **1 Zustands-Enum + 1 kleines Reject-Reason-Enum + die Übergangs-/Provenance-Regeln.**

**Vorgeschlagene Minimal-Form (illustrativ — Team-1 final):**
```
HubTrustState = UNKNOWN | PENDING | TRUSTED | REJECTED | STALE   // §1; fail-closed default UNKNOWN
TrustRejectReason = PROOF_DECLINED | ISSUER_UNTRUSTED | …         // klein, closed; Werte = Team-1 final
// Netzfehler ist KEIN HubTrustState-Wert — Transport-Ebene → Zustand bleibt UNKNOWN (N4).
```

- **P3 — `TRUSTED` (Affirmation):** Wire trägt `TRUSTED` als **positiven, hub-ausgestellten** Zustand (**Provenance = Hub**). **Client-Anforderung:** der Zustand ist **lesbar** und wird **nie** client-seitig aus „Verbindung steht"/Absence abgeleitet. *Mindestens:* der Zustand. *Ideal:* als **beobachtbarer Übergang** `PENDING→TRUSTED` (koppelt an P5-Modus). **Naming-Vorsicht an Team-1:** den Wert **nicht** so benennen/typen, dass er **natives** Identsein impliziert — `trusted` ist **abgeleitet/widerrufbar** (Honesty-Kern §1).
- **P4 — `REJECTED` + Maschinen-Code:** Wire trägt `REJECTED` **plus** `reason: TrustRejectReason` (closed set). **Mindest-Trennschärfe (nicht verhandelbar):** der Code trennt **Trust-Reject** von **(a) Netzfehler** (= kein Trust-Verdikt → Zustand `UNKNOWN`, **nicht** `REJECTED`), **(b) Widerruf** (→ `STALE`) **und (c) malformed/invalider Descriptor** (→ **distinktes diagnostizierbares Upstream-Signal, KEIN Trust-Zustand**, weder `REJECTED` noch still `UNKNOWN` — PL, CYP-798). `TrustRejectReason` gilt **nur** für echte Trust-Eval-Rejects (nicht für Netz/malformed). Feinere Sub-Reasons (bad-audience / expired-proof / unknown-issuer) = **Team-1-Option**; Client-**Minimum** = diese Top-Level-Ursachen-Trennung. Client mappt **`code`→kuratierte Copy**, nie message-string-match (Reuse `net/rest.ts` `restErrorCode`-Muster, `{ error: { code } }`).
  - **Wire-Need (malformed):** die Promotion muss einen **Descriptor-Validitäts-/Upstream-Fehler** als **eigenes Signal** exponieren (getrennt vom `HubTrustState`-Enum), damit der Client einen korrupten/böswilligen Descriptor **diagnostizierbar** rendert statt ihn in `UNKNOWN` zu verlieren. **Der Client rendert das ⚠-Signal bei malformed VERBINDLICH/IMMER** (PL, CYP-798 — nicht optional). Exakte Signal-Form = **PL bei CYP-798**.
- **P5 — `STALE` + Widerruf-Signal:** Wire trägt `STALE` **distinkt** (nie in `REJECTED`/`TRUSTED` gefaltet) **plus** ein **Widerruf-/Ablauf-Signal**, das `TRUSTED→STALE` bewegt. **Modus** (push/beobachtbar vs. lazy/erst-beim-Next-Call) = **Team-1 sub-weiche (a)**. **Client-Anforderung modus-unabhängig:** `STALE` ist ein **erreichbarer** Zustand; **bei lazy** muss der Next-Call-Fehlschlag den **Widerruf-Code** tragen (distinkt vom transienten Netzfehler), damit der Client **`STALE` setzt statt `UNKNOWN`/generisch**. **Meine Präferenz:** push/beobachtbar — sonst stale-lit-Risiko (trusted bleibt sichtbar nach Widerruf).

**Was die Promotion damit minimal braucht:** das 5-Werte-Zustands-Enum (P1/P2 tragen `UNKNOWN`/`PENDING` schon frame-unabhängig) · das kleine `TrustRejectReason`-Enum (P4) · die Regel „`TRUSTED` nur hub-ausgestellt" (P3) · das Widerruf-Signal `TRUSTED→STALE` (P5). Die **Werte/Modi von P3-Provenance, P4-Reason-Set, P5-Signal** sind die **[TF]-Teilmenge** — der Rest (Enum-Existenz, `UNKNOWN`/`PENDING`, per-`hubId`, hub-scoped-401) ist **frame-unabhängig** und jetzt promotierbar.

---

## §6 Diskriminierende Zähne (Honesty-Tests)
1. **fail-closed default** — kein Trust-Signal → `unknown`-Render. *(Mutation: default `trusted`/optimistisch → RED.)*
2. **pending ≠ unknown ≠ trusted** — drei distinkte Glyph-Formen+Labels. *(Mutation: „wird geprüft" für nicht-verbunden ODER unknown==trusted-Look → RED = Sweep-Fund-#5-Klasse.)*
3. **stale statt fake-trusted** — abgelaufenes/widerrufenes Proof → `stale`, hub-Surfaces nicht mehr live. *(Mutation: `trusted` bleibt nach Ablauf → RED = stale-lit.)*
4. **Ursache-Trennung** — Netz `unknown` ≠ Trust `rejected` ≠ Widerruf `stale`, je eigene Copy. *(Mutation: generisches „fehlgeschlagen" für alle → RED.)*
5. **Rolle reist nicht** — nach Switch Rolle/Tier aus B's whoami, fail-closed. *(Mutation: Operator@A → Operator@B geerbt → RED = Über-Berechtigung.)*
6. **kein-optimistischer-Switch** — B-Surfaces rendern erst nach `trusted`-Auflösung (resolve-then-render). *(Mutation: B-Surfaces live während `pending` → RED.)*
7. **colour-never-sole** — jeder Zustand trägt Glyph-Form + Label, nie nur Farbe (WCAG 1.4.1).
8. **inaktive Hubs nicht stale-trusted** — Switcher-Liste zeigt nicht-frische Hubs als `unknown`, nicht letztes `trusted`.
9. **malformed ≠ unknown (PL, CYP-798)** — ein invalider/korrupter Descriptor rendert ein **distinktes diagnostizierbares Upstream-Signal**, nicht still `unknown` und nicht `rejected`. Das ⚠-Signal ist **immer/verbindlich** präsent. *(Mutation: malformed → still `unknown` → RED = korrupter/böswilliger Descriptor verschwindet; malformed → `rejected` → RED = erfundenes Trust-Verdikt; ⚠-Signal optional/weggelassen bei malformed → RED = distinkter Upstream still weg.)*
10. **`trusted` neutral-definit, keine Affirmation (CYP-803)** — beide Richtungen gezahnt: (a) `trusted` rendert **`data-tone="neutral"`** (nicht `"positive"`) und Glyph-Token **`on-surface`** (nicht `primary`/Affirm-Akzent). *(Mutation: `trusted` → `tone:'positive'` / Glyph `--md-sys-color-primary` → RED = Garantie-Overclaim, derived≠native verletzt.)* (b) `trusted` bleibt **distinct von `unknown`**: Form `●`≠`◯`, Wort „vertraut"≠„Vertrauen nicht geprüft", Glyph-Token `on-surface`≠`on-surface-variant`. *(Mutation: `trusted`-Glyph → `on-surface-variant` (= unknown-Ton) → RED = Über-Neutralisierung kollabiert evaluiert-gültig in die Absence-Zustände, [[reconcile-not-collapse-distinct-states]].)*

---

## §7 Reuse-Ledger & nächster Schritt
- **Reuse:** `RemoteSecurityTierBadge`/`remoteSecurityTierModel` (Glyph+fail-closed+always-visible) · `statusDotSpec`/`dotRoleVar` (Form-Achse UNKNOWN) · `ProjectSwitcher` (Liste+aktiver-Pointer) · `AuthGate` (resolve-then-render + per-Hub-whoami-Rolle) · `loginFlow` (unavailable≠rejected Register) · Connection-Banner-Muster (`comm-status-{state}` distinkt text+tone).
- **Kontrakt-Landung:** die aria-label-Strings + `HubTrustState`-Enum landen **mit Dev5s Impl** (i18n-Keys mit der Impl, [[shared-key-landing]] — Sync flaggen). Der `error.code`→Ursache-Mapping landet, **wenn** Team-1 die Reject-Taxonomie fixiert.
- **Nächster Schritt:** die **§4b-Promotion-Needs** speisen Team-1s `app/shared → :core`-Promotion (über PL). Sobald `:core` die Trust-State/Connect-Flow-Fläche trägt **und** Team-1s Trust-Frame steht, fülle ich die **[TF]-Kanten** (Trigger/Codes/Widerruf-Signal/Credential-Schritte) ein → dann ist die Spec build-vollständig für Dev5 (der web-ts dann `:core` konsumiert).
