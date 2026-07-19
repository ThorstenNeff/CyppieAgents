# Modell 2 — Per-Hub-Vertrauenszustand + Hub-Wechsel-Flow · konkrete UX-Spec (web-ts)

**Für:** Dev5 (Client-Render) + Team-1 (Trust-Frame-Abhängigkeiten) — **über den Koordinator** · **Von:** UIUX2 (Team-2)
**Baseline (am Objekt):** develop, web-ts. **Folgt auf** `model2-multihub-client-needs.md` (CYP-748, die Bedarfe). Dies ist die **konkrete UX-Spec** für die Teile, die auf den **ratifizierten Weichen** tragen:
**Weichen (PL, 2026-07-19):** **Q1 = SEAT (n)** · **Q4 = ONE-ACTIVE-HUB** (nicht parallel — Registry = Liste + aktiver-Pointer, switch-first; Abstraktion hält parallel-später offen).
**⚠ Trust-Frame-Grenze:** exakte Zustands-**Übergänge** (was PENDING→TRUSTED auslöst), **Credential-Präsentations-Mechanik** und das **Widerruf-Signal** (push vs. lazy → STALE-Timing) **warten auf Team-1s Trust-Frame** und sind unten als **[TF]** markiert. Alles Nicht-[TF] trägt auf seat+one-active **jetzt**.

---

## §0 Was jetzt baubar ist vs. was wartet
- **Baubar jetzt (seat + one-active):** das **5-Zustands-Render** (Glyph/Label/Tone/testid/aria), die **Platzierung** (always-visible aktiver-Hub-Indikator + Switcher-Liste), der **Teardown/Setup-Flow-Rahmen**, die **Fehler-Ursachen-Trennung** (Copy-Register), die **Honesty-Invarianten**.
- **[TF] wartet auf Team-1:** die konkreten **Trigger** je Übergang, das **Reject-Code-Taxonomie**-Set (welche `error.code`s), das **Widerruf-Signal** (bestimmt, wie schnell STALE ehrlich eintritt), die **Credential-Präsentations**-Schritte. Ich spezifiziere die **Zustände + Renders + Flow**; die **Kanten** dockt Team-1 an.

---

## §1 Der Per-Hub-Vertrauenszustand — das 5-Zustands-Render (konkret)

**Modell (Client-seitig, eine Quelle):** `HubTrustState = 'unknown' | 'pending' | 'trusted' | 'rejected' | 'stale'`. **Fail-closed Default = `unknown`.** Distinkt gerendert — **colour-never-sole** (jeder Zustand trägt eine distinkte **Glyph-Form + Label-Text**, Farbe ist sekundär), und **over-alarm-vermeidend** (unknown/pending sind **neutral**, kein Alarm; nur rejected/stale tragen Handlungsgewicht).

| Zustand | Bedeutung | Glyph (Form-Achse) | Label (DE) | Tone | render-Regel |
|---|---|---|---|---|---|
| **unknown** | Hub noch nicht kontaktiert / lädt | `◯` (leerer Ring) | „Vertrauen nicht geprüft" | neutral | **fail-closed default**, sichtbar (nicht Stille) |
| **pending** | Proof präsentiert, Hub verifiziert noch [TF-Trigger] | `◔` (Viertel-Füllung) | „wird geprüft…" | neutral | nur bei **echt laufender** Verifikation — **nie** für terminal reject/stale |
| **trusted** | Hub hat affirmiert [TF-Trigger] | `●` (voll) | „vertraut" | positiv | **einziger** positiver Render |
| **rejected** | Hub hat das Proof abgelehnt [TF-code] | `⊘` (durchgestrichen) | „abgelehnt" | warnend | distinkt von Netzfehler (§4) |
| **stale** | war trusted, Proof abgelaufen/widerrufen [TF-Signal] | `◑` (halb, „war voll") | „abgelaufen — erneut bestätigen" | handlungs-neutral | fail-closed: hub-Flächen **nicht** stale-vertraut weiterzeigen |

**Honesty-Kern (nicht verhandelbar):**
- **unknown ≠ pending ≠ trusted:** drei distinkte Renders — [[absence-reads-as-all-clear]] / [[reconcile-not-collapse-distinct-states]]. Insbesondere **pending ≠ unknown**: „wird geprüft" darf **nur** für laufende Verifikation stehen, **nie** als Sammel-Label für „nicht verbunden" — genau der Fehler, den ich im Honesty-Sweep als Fund #5 (Tier-Strip „Wird geprüft" für revoked/offline) markiert habe. Hier vermeidet das 5-Zustands-Modell ihn per Konstruktion.
- **fail-closed:** ohne Affirmation ist der Zustand `unknown` (nie optimistisch `trusted`). Fehlt das Trust-Signal → `unknown`-Render, nie geraten.
- **abgeleitet ≠ nativ:** `trusted` heißt „dieser Hub vertraut deinem Aussteller-vouch" — die Copy/Disclosure macht das **widerrufbar**-Wesen sichtbar, **nie** durables natives Konto (Bedarf N3/Honesty-Kern aus CYP-748).

**Reuse-Anker (kein Neubau):** dieselbe Mechanik wie **`RemoteSecurityTierBadge`** (`remoteSecurityTierModel.ts` — distinkte Glyph-Form `●`/`◐`/`·` + Label + immer-sichtbar fail-closed, nie native-grün) und wie **`statusDotSpec`/`dotRoleVar`** (`lifecycleStatus.ts`, CYP-431 — UNKNOWN=Ring-**Form** distinkt, nicht farb-only). Der Trust-Badge ist eine **Instanz desselben Musters**, nicht ein drittes Dot-Vokabular.

**testid/aria (je Zustand):**
- Container `data-testid="hub.trust.{hubId}"`, `role="status"`, `aria-live="polite"` (Übergänge sind ansagbar).
- Zustands-Marker `data-testid="hub.trust.{hubId}.{state}"`; `aria-label` = der Label-Text (nie nur Glyph/Farbe).
- Klasse `hub-trust-{state}` (Tone via CSS-Var, wie `comm-status-{connection}`).

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

**Drei distinkte Ursachen, drei distinkte Copys + Recoverys** ([[reconcile-not-collapse-distinct-states]]) — **nie** ein generisches „Verbindung fehlgeschlagen":

| Ursache | Zustand | Copy (Register) | Recovery |
|---|---|---|---|
| **Netz / Hub unerreichbar** (transient) | `unknown` (nicht `rejected`!) | „Hub nicht erreichbar" | „erneut versuchen" (manuell) |
| **Proof abgelehnt** (Trust) [TF-code] | `rejected` | „Hub hat den Zugang abgelehnt" | Re-Präsentation/neues Proof [TF] |
| **Aussteller vouch't nicht mehr** (Widerruf) [TF-signal] | `stale` | „Zugang zu diesem Hub entzogen" | **kein** Client-Retry heilt das — ehrlich benennen |

- **Register-Trennung:** Netz-Fehler ist **nicht** `rejected` (System ≠ Trust-Verdikt) — dieselbe Linie wie CYP-515 `unavailable ≠ rejected` (reuse `loginFlow.ts`-Doktrin, enumeration-safe/pre-credential).
- **401/Trust-Verlust hub-scoped:** ein 401 von Hub B **kippt nicht** die (heute globale) App — nur B's Surfaces gehen fail-closed. **[Flag an Dev5]** der heutige globale `setOnUnauthorized` (`net/rest.ts`, `AuthGate`) muss für Multi-Hub **hub-scoped** werden — sonst reißt B's 401 A mit. (Bedarf N4b; Umsetzung mit dem Trust-Frame.)

---

## §5 Trust-Frame-abhängig — [TF] (wartet auf Team-1)
Ich spezifiziere Zustände/Renders/Flow; **diese Kanten** dockt Team-1s Trust-Frame an — **nicht** von mir geraten:
- **PENDING→TRUSTED-Trigger:** welches beobachtbare Affirmations-Signal (N3) B liefert.
- **Reject-Taxonomie:** die konkreten `error.code`s für rejected vs. Netz vs. Widerruf (§4 mappt darauf, sobald sie stehen).
- **Widerruf-Signal:** push (aktiv/beobachtbar) vs. lazy (erst beim nächsten Call) — **bestimmt, wie schnell `stale` ehrlich eintritt.** (Meine Präferenz aus CYP-748: aktiv/beobachtbar, sonst stale-lit-Risiko — Team-1s Call, = deren sub-weiche (a).)
- **Credential-Präsentations-Schritte:** die konkrete Mechanik in §3-Schritt 3.

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

---

## §7 Reuse-Ledger & nächster Schritt
- **Reuse:** `RemoteSecurityTierBadge`/`remoteSecurityTierModel` (Glyph+fail-closed+always-visible) · `statusDotSpec`/`dotRoleVar` (Form-Achse UNKNOWN) · `ProjectSwitcher` (Liste+aktiver-Pointer) · `AuthGate` (resolve-then-render + per-Hub-whoami-Rolle) · `loginFlow` (unavailable≠rejected Register) · Connection-Banner-Muster (`comm-status-{state}` distinkt text+tone).
- **Kontrakt-Landung:** die aria-label-Strings + `HubTrustState`-Enum landen **mit Dev5s Impl** (i18n-Keys mit der Impl, [[shared-key-landing]] — Sync flaggen). Der `error.code`→Ursache-Mapping landet, **wenn** Team-1 die Reject-Taxonomie fixiert.
- **Nächster Schritt:** sobald Team-1s Trust-Frame steht, fülle ich die **[TF]-Kanten** (Trigger/Codes/Widerruf-Signal/Credential-Schritte) ein → dann ist die Spec build-vollständig für Dev5.
