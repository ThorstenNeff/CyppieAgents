# CYP-748 Modell-2 Multi-Hub Client — QA Tooth-Plan (per Need: was RÖTET ihn)

**Von:** Tester2 (Team-2 QA) · **An:** a2-po · **Pairt mit:** `docs/design/model2-multihub-client-needs.md` (UIUX2, N1–N5)
**Art:** **Plan, kein Bau.** Pro Client-Need: was der Zahn behauptet, **welche Mutation ihn rot macht**, und **ab wann er baubar ist**.
**Baseline am Objekt (develop):** heutiger single-hub-Client — `state/hubConfig.ts` (einwertige `apiBase/wsBase/token`), `platform/operatorToken.ts`, `platform/appConfig.ts`, `auth/authConfig.ts`, `auth/AuthGate.tsx` (ein globaler whoami + ein globaler `/api`-401), `net/rest.ts` (`restErrorCode`, `setOnUnauthorized`).

---

## §A Disziplin, die für JEDEN Zahn unten gilt (sonst ist der Plan hohl)

1. **Eine Mutation pro Prüfzeile — die Mutation, die DIESE Zeile bezeugt.** Positivkontrollen sind als solche markiert und nennen ihre **Kreuz-Bedingung** (unter welcher fremden Mutation sie GRÜN bleiben muss). Kein „alle rot unter einer Mutation" (die Case-B-Lehre CYP-759).
2. **Fail-closed heißt: das ROT feuert auf das OPTIMISTISCHE-GRÜN.** Der Zahn muss röten, wenn der Client einen NICHT-affirmierten Zustand als „vertraut/verbunden/grün" rendert — nicht nur, wenn er abstürzt.
3. **★ Die Grenze ist der EMPFÄNGER-HUB, nicht das Client-Gate** ([[client-gate-is-not-the-boundary]]). Für N2/N5 hat jeder Zahn **zwei** Prüfzeilen: (a) Client-Verhalten (Defence-in-Depth) UND (b) **der Empfänger-Hub weist strukturell ab** — und (b) ist die tragende, die gegen einen echten Hub rot-fähig sein muss. Ein Zahn, der nur (a) prüft, misst nicht die Grenze.
4. **Non-Vacuity beidseitig** (CYP-776): wo ein Zahn zwei Mengen/Seiten vergleicht, beide Extraktionen `> 0` **zuerst** — ∅ ⊆ X ist vakuös wahr.
5. **★ N/A, nicht PASS, gegen eine nicht-existente Fläche** (PL-0089 vorwärts). Der Modell-2-**Kontrakt existiert noch nicht** (Team-1 designt Aussteller/Proof). Ein Zahn, dessen Signal (Trust-Affirmation, Widerruf, Audience-Binding) es heute nicht gibt, ist **N/A bis der Kontrakt das Signal liefert** — **nicht** grün, nicht gebaut. Jeder Need unten markiert seinen **Baubarkeits-Auslöser**. Das ist der Kern des „kein Bau jetzt": die meisten Zähne sind erst rot-fähig, wenn die Fläche existiert; sie jetzt grün zu bauen wäre die vakuöse Falle.

**Buildable-JETZT vs. GATED** (Ehrlichkeit über den Reifegrad):
- **Jetzt baubar:** **N1.1** (Registry-statt-Global-Struktur-Scan) · **N4.b** (kein-globaler-401) · **N3.UNKNOWN** (Kein-Signal-Render). Diese drei brauchen **kein** Affirmations-/Audience-/Widerruf-Signal.
- **Gated auf Team-1-Kontrakt:** alles, was ein **Hub-B-Affirmations-/Widerruf-/Audience-Signal** oder **per-Hub-Rollen-Auflösung** braucht (N2b, N3 PENDING/TRUSTED/REJECTED/STALE, N4 issuer-revoke, **N5.b**). Auslöser je Need benannt.

> **★ Korrektur (Assist2-Zweitlinse, 2026-07-21, objekt-bestätigt) — N5.b ist NICHT buildable-now.** Meine erste Fassung listete N5.b („fail-closed-Rolle") als jetzt-baubar. Falsch: `auth/authModel.ts:18` löst die Rolle **single-global** auf (`me.role === AUTH_ROLE.OPERATOR`, ein whoami → ein `operator`-Bool); **es gibt keinen per-Hub-Rollen-Pfad** (grep leer). Beide N5.b-Mutationen (Hub-A-Rolle auf Hub-B übertragen · vor Auflösung Operator annehmen) **haben keinen Locus** → jetzt gebaut ginge der Zahn **grün-durch-Abwesenheit** = exakt die Vakuitäts-Falle, die dieser Plan (§A.5) selbst verbietet. **N5.b → Q4/Q5-gegatet** (neben N5.a). Dafür wird **N3.UNKNOWN** aus dem Kontrakt-Gate nach buildable-now gezogen: der Kein-Signal-Render ist die **Abwesenheits-Render** (dieselbe Familie wie das heutige `me===null → None` fail-closed in authModel.ts:16) und braucht **kein** Affirmations-Signal — nur den Default des ersten Trust-Modell-Stubs. Detail: `po2/PL-0089-forward-vacuity-cyp748-cyp755.md`.

---

## §B Pro Need — die Zähne

### N1 — Registry statt Global (mehrere Hub-Identitäten ge-keyed)
**Zahn N1.1 (buildable JETZT, Struktur):** coverage-by-construction — **kein** hub-bezogener Zustand (endpoint, credential, rolle/tier, verbindungs-/trust-state) wird aus einem **einwertigen Global** gelesen; alles ist per `hubId` ge-keyed.
- *Reddening mutation:* ein hub-bezogener Wert wird als Modul-Global (statt `registry[hubId]`) gelesen → Scan rot. (Analog dem 776/751-Source-Scan, comment-safe.)
- *Positivkontrolle:* die Registry-API existiert und ist nicht-leer für ≥1 Hub. **Kreuz-Bedingung:** bleibt grün unter der Global-Mutation (die Registry-Existenz hängt nicht am Lese-Ort).
- *Baseline heute:* der single-hub-Code liest `apiBase` global → der Scan würde HEUTE rot sein. Also: **N1.1 ist der Regressions-Zahn, der die Migration weg vom Global BEZEUGT** — er ist erwartet-rot vor der Umstellung, grün danach. (Als solcher etikettiert, nicht „kaputt".)

**Zahn N1.2 (gated auf Kontrakt-`hubId`):** `hubId` ist **stabil + kollisionsfrei** (kein stiller re-key-Drift), idealerweise Aussteller-verankert, nicht der rohe Origin-String.
- *Reddening mutation:* zwei Hubs kollidieren auf denselben Key / ein re-key ändert den State-Zuordnungs-Schlüssel still → Verwechslungs-Zahn rot.
- *Baubarkeits-Auslöser:* Team-1 legt die `hubId`-Quelle fest (Q1-verankert). **Bis dahin N/A.**

### N2 — Credential audience-gebunden, nie ambient-global (★ adversarial)
**Zahn N2.a (Client-Defence-in-Depth):** der Client sendet **nie** das Credential von Hub A an Hub B; jedes Proof ist je Ziel-Audience ge-keyed.
- *Reddening mutation:* Routing so verbiegen, dass ein Hub-A-Handle in einen Hub-B-Request geht → **Leak-Zahn rot**. (Das ist die „injizierter fremder Credential/viewer-proof → darf nicht raus"-Prüfung.)

**★ Zahn N2.b (DIE GRENZE — der Empfänger-Hub weist ab):** ein an Hub B präsentiertes **falsch-adressiertes** Proof (Audience = Hub A) wird von **Hub B strukturell abgelehnt (403/refuse)** — Test-beweisbar, nicht Absicht.
- *Reddening mutation:* Hub B akzeptiert ein wrong-audience-Proof (Audience-Check aus) → **N2.b rot = Leak an der echten Grenze.**
- *Warum getrennt von N2.a:* N2.a ist Defence-in-Depth; **N2.b ist die Grenze** ([[client-gate-is-not-the-boundary]]). Ein grüner N2.a bei rotem N2.b heißt „Client routet brav, aber der Hub würde ein geleaktes Proof fressen" — die gefährliche Kombi. Beide Prüfzeilen, jede ihre Mutation.
- *Positivkontrolle:* ein **korrekt** audience-gebundenes Proof wird von Hub B **akzeptiert** (sonst wäre N2.b vakuös „lehnt alles ab"). **Kreuz-Bedingung:** bleibt grün unter der wrong-audience-Mutation.
- *Baubarkeits-Auslöser:* das Proof **trägt** eine Audience-Bindung (Team-1, N2-Kontrakt) + ein zweiter Hub existiert (Q4). **Bis dahin N/A** — heute gibt es weder Audience-Feld noch Hub B; ein „grüner" N2 heute wäre vakuös.

### N3 — Per-Hub-Vertrauenszustand, fail-closed (★ der Honesty-Kern)
Die fünf Zustände (UNKNOWN/PENDING/TRUSTED/REJECTED/STALE) **kollabieren nie** auf „verbunden/grün"; **default = NICHT-vertraut** bis Hub B affirmiert.
**Ein Zahn PRO Zustand (eine Prüfzeile je Zustand — keine Sammel-Zeile):**
- **N3.UNKNOWN (★ buildable-JETZT, F4):** Hub nicht kontaktiert → neutraler „noch nicht geprüft"-Render, **testid distinkt**, **nie** der TRUSTED-Marker. *Mutation:* UNKNOWN rendert den TRUSTED/„verbunden"-Marker → rot. (Die absence-als-all-clear-Falle, CYP-288/705-Familie.) **Braucht KEIN Affirmations-Signal** — es ist die Kein-Signal-/Default-Render (Familie: `authModel.ts:16` `me===null → None`), assertierbar sobald der erste Trust-Modell-Stub seinen Default hat. **Der eine früh-landbare Honesty-Zahn der N3-Familie.**
- **N3.PENDING:** Proof präsentiert, Hub prüft noch → „wird geprüft", **nie** vorab-vertraut. *Mutation:* PENDING → positiver Trust-Render → rot. (Die „grün sobald ich's *versucht* habe"-Falle — dieselbe Klasse wie operatorToken fail-OPEN, CYP-749.)
- **N3.TRUSTED:** **nur** hier positiv. *Mutation:* der positive Marker erscheint auch ohne Affirmations-Signal → rot (Positiv-Render darf **ausschließlich** aus einem beobachteten Hub-B-Affirmations-Signal folgen, nie aus Client-Hoffnung).
- **N3.REJECTED:** distinkt von Netzfehler (→ N4). *Mutation:* REJECTED rendert wie transient-Netzfehler → rot.
- **N3.STALE:** war TRUSTED, Proof abgelaufen/widerrufen → **fail-closed**, Hub-B-Flächen **nicht** stale-weiter-vertraut. *Mutation:* nach Ablauf/Widerruf bleibt der TRUSTED-Marker stehen → rot (stale-lit-Falle).
- **★ Distinktheits-Kontrolle (ein Zahn):** die fünf Render-Marker sind **paarweise verschieden** (kein Paar teilt denselben testid/Ton). *Mutation:* zwei Zustände auf denselben Marker mappen → rot. Non-Vacuity: alle fünf Marker existieren (sonst prüft die Distinktheit Luft).
- **Colour-never-sole (WCAG 1.4.1):** je Zustand trägt Glyph/Text die Bedeutung, nicht Farbe allein (committed-CSS-Guard-Muster wie CYP-760/437). *Mutation:* der Zustand nur über Farbe → rot.
- *Baubarkeits-Auslöser je Zustand:* **N3.UNKNOWN = buildable-JETZT** (Kein-Signal-Render, kein Affirmations-Kontrakt nötig — s.o. F4). **PENDING/TRUSTED/REJECTED** brauchen das **Affirmations-/Reject-Signal** (Team-1, N3-Kontrakt); **STALE** braucht zusätzlich ein **Widerruf-/Ablauf-Signal** (Q2 Lebensdauer + Q3 Widerruf beobachtbar). **Ohne Q3-Signal kann STALE gar nicht eintreten → N3.STALE ist N/A bis Q3, NICHT grün.** Das ist explizit zu melden, nicht zu überspielen.

### N4 — Re-Auth & Fehlerpfad hub-scoped (Ursachen trennen, Ausfall isolieren)
**Zahn N4.a (Ursache-ehrliche Fehler):** Netz-unerreichbar vs. Proof-abgelehnt (Trust) vs. Aussteller-Widerruf sind **drei distinkte** Copy+Recovery, nie ein generisches „Verbindung fehlgeschlagen".
- *Mutation (eine pro Ursache):* Ursache X wird auf die generische/eine falsche Meldung gemappt → rot. Dispatch via maschinen-lesbarem Code (`{error:{code}}`/`restErrorCode`), **nie** Message-String-Match (coverage-by-construction, wie CYP-776). *Mutation:* Message-String-Dispatch eingebaut → rot.
- *Positivkontrolle:* jede der drei Ursachen erreicht ihre eigene Meldung. **Kreuz-Bedingung:** die drei bleiben paarweise verschieden.

**★ Zahn N4.b (Ausfall-Isolation — buildable-Teil JETZT als Regressions-Baseline):** ein 401/Trust-Verlust an **Hub B** reißt die **Hub-A-Sitzung nicht** mit.
- *Reddening mutation:* der Hub-B-401 ruft den **globalen** `setOnUnauthorized` (heutiges `rest.ts`-Muster) → die ganze App kippt auf Re-Login → **rot**. Der Zahn bezeugt genau, dass Re-Auth **hub-scoped** wird.
- *Baseline heute:* der single-hub-Client hat **einen globalen** 401-Handler → N4.b ist HEUTE strukturell rot-fähig als „darf nicht global sein"-Regressions-Zahn (erwartet-rot vor der Umstellung, grün danach; als solcher etikettiert). **Das ist ein Need, dessen Anti-Muster schon am Objekt existiert** → guter früher Zahn.
- *Baubarkeits-Auslöser für den vollen Zahn:* ein zweiter Hub-Scope (Q4). Bis dahin baubar als **Struktur-Assertion** (kein globaler 401-Handler mehr, sobald Multi-Hub-Registry steht).

### N5 — Hub-Wechsel-UX (aktiver Hub eindeutig, Rolle reist nicht)
**Zahn N5.a (aktiver Hub unzweideutig):** jede Ansicht/Aktion zeigt **eindeutig** den Ziel-Hub; beim Wechsel **kein** fake-instant „verbunden" — der per-Hub-Zustand (N3) führt.
- *Mutation:* nach Wechsel auf Hub B rendert die UI „verbunden", bevor Hub B affirmiert (überspringt N3.PENDING) → rot.

**★ Zahn N5.b (Rolle/Tier per Hub, fail-closed — GATED Q4/Q5, NICHT buildable-now):** Operator-Seat an Hub A impliziert **nicht** Operator an Hub B; Rolle wird je Hub aus **dessen** whoami aufgelöst, **fail-closed auf least privilege** bis aufgelöst. **Kein-Locus-heute:** die Rolle ist single-global (`authModel.ts:18`), kein per-Hub-Pfad → beide Mutationen unten hätten nichts, woran sie greifen → grün-durch-Abwesenheit. Erst baubar mit per-Hub-whoami.
- *Reddening mutation:* die Hub-A-Rolle wird auf Hub B **übertragen/angenommen** (statt neu aufgelöst) → rot. UND: vor Auflösung wird **Operator** angenommen statt least-privilege → rot (fail-OPEN).
- *Direkter Bezug am Objekt:* das ist **exakt** die CYP-745/751-Logik — Scope reist nicht, er wird pro Ort re-aufgelöst, fail-closed. Die Mutation ist dieselbe Familie wie operatorToken fail-OPEN (CYP-749: nicht-genuines Signal → least privilege, nie optimistisch Operator).
- *Positivkontrolle:* eine **an Hub B aufgelöste** Operator-Rolle rendert Operator. **Kreuz-Bedingung:** bleibt grün, wenn die Hub-A-Übertragungs-Mutation entfernt wird.
- *Baubarkeits-Auslöser:* per-Hub-whoami (Q5: kann derselbe Aussteller verschiedene Rollen je Hub geben?) + zweiter Hub (Q4). **Bis dahin N/A** — nicht als „fail-closed-default-Assertion" vorziehbar, weil es keinen per-Hub-Rollen-Locus gibt, an dem der Default gemessen würde (Assist2-Korrektur).

---

## §C Cross-cutting: die §2-Honesty-Invarianten als geteilte Zähne
Jede ist ein eigener Wächter (nicht in einen Need versteckt), Muster wie meine gemergten CYP-749/751/760/776:
- **INV-1 fail-closed default** (kein Trust-Render ohne Affirmation) → deckt N3.TRUSTED/PENDING/UNKNOWN.
- **INV-2 abgeleitetes ≠ natives Vertrauen** (Disclosure: Hub-B-Zugang als vouched/widerrufbar, nie durables Identsein) → Wortregister-/certainty-Zahn (status-word-certainty-Familie).
- **INV-3 distinkte Zustände** → N3-Distinktheits-Kontrolle.
- **INV-4 Ursache-ehrliche Fehler** → N4.a.
- **INV-5 kein Cross-Hub-Leak** → N2.a + **N2.b (Grenze)**.
- **INV-6 colour-never-sole** → committed-CSS-Guard je Zustand (CYP-760-Muster).

---

## §D Abhängigkeiten, die Baubarkeit gaten (ehrlich, damit nichts vakuös grün wird)
| Zahn | Gated auf | Bis dahin |
|---|---|---|
| **N1.1** (registry-not-global), **N4.b** (kein-globaler-401) | nichts Neues — Anti-Muster existiert am single-hub-Objekt | **jetzt baubar** als Regressions-/Struktur-Baseline (erwartet-rot vor der Migration) |
| **N3.UNKNOWN** (Kein-Signal-Render) | nur der erste Trust-Modell-Default (kein Affirmations-Signal) | **jetzt/früh baubar** — Abwesenheits-Render, F4 |
| N1.2 (hubId-Stabilität) | Team-1 `hubId`-Quelle (Q1) | N/A |
| N2.a/N2.b (Audience) | Proof trägt Audience (N2-Kontrakt) + Hub B existiert (Q4) | N/A |
| N3 PENDING/TRUSTED/REJECTED | Hub-B-Affirmations-/Reject-Signal (N3-Kontrakt) | N/A |
| **N3.STALE** | **Widerruf-/Ablauf-Signal beobachtbar (Q2+Q3)** — **ohne Q3 kann STALE nicht eintreten** | **N/A bis Q3, explizit gemeldet** |
| N4.a (Ursachen-Codes) | maschinen-lesbare Reject-Codes (N4-Kontrakt) | N/A |
| N5.a (aktiver Hub) / **N5.b (Rolle per Hub)** | per-Hub-whoami + zweiter Hub (Q4/Q5) — **N5.b hat heute KEINEN Locus (Rolle single-global), nicht vorziehbar** | N/A |

**Empfehlung:** die drei **jetzt-/früh-baubaren** Zähne — **N1.1, N4.b** (Struktur-Anti-Muster am single-hub-Objekt) **+ N3.UNKNOWN** (Kein-Signal-Render, F4) — können als frühe Baseline landen (die N1.1/N4.b-Struktur-Zähne erwartet-rot vor der Migration, grün danach — als solche etikettiert, **kein „EXPECTED-RED"-stale-Kommentar-Fehler wie CYP-750**). **N5.b gehört NICHT dazu** (Assist2-Korrektur: kein per-Hub-Rollen-Locus → grün-durch-Abwesenheit). Alle affirmations-/audience-/widerruf-/per-Hub-rollen-abhängigen Zähne bleiben **geplant + N/A**, bis Team-1s Kontrakt das jeweilige Signal liefert — dann re-verify am Objekt zum Bau-Zeitpunkt (Basis = dann-aktueller develop-Tip, `fetch --prune` zuerst).
