# CYP-805 — IssuerNotTrusted terminaler Block (web-ts, Achse c): Copy / Tone / Glyph

> UX/UI-Owner-Deliverable. **Shape gefroren (PL-0107):** Issuer-Verdikt `NOT_TRUSTED` → **terminaler Block**
> (Verbindung angehalten); alle anderen Zustände → proceed. Dev5 baut das Render-Scaffolding, ich liefere
> **Copy + Tone + Glyph + a11y + Struktur**; Tester2s F3-Teeth greifen. Achse **c** (Issuer-Verdikt) — **distinkt**
> von Achse a (Hub-Key-TOFU-Trust, CYP-803 neutral) und Achse b (Operator-Identität / AuthRejected).

## 0. Ruling in einem Satz
Der Block rendert als **WARN-amber HARD BLOCK** (▲ + `warn-container` + Label) — **NICHT error-rot**, **NICHT neutral**,
mit **assertiver a11y**, **OOB-Text-Hinweis** und **keinem Retry/Proceed-Pfad**. Das **spiegelt Team-1s bereits
ratifizierte** Compose-Entscheidung (CYP-747 §5-C2) 1:1 auf web-ts — Cross-Surface-Konsistenz, kein web-ts-Eigenweg.

## 1. Warum WARN-amber, nicht error-rot (der Kern-Entscheid)
IssuerNotTrusted ist eine **schützende, fail-closed Verweigerung**, kein Defekt: der Hub ist registriert, aber es ist
**kein vertrauenswürdiger Aussteller** an ihm etabliert → **keine Operator-Berechtigung** (fail-closed). Das System
arbeitet **korrekt**. Darum:
- **NICHT `error-container`/⚠ (ERROR = „broken"):** error-rot behauptet einen Fehlschlag/Malfunktion, den es nicht gibt
  — **over-alarm ist auch unehrlich** ([[over-alarm-is-also-dishonest]]). Team-1 verwirft error-rot hier **explizit**
  (`HubConnectSelection.kt`: „a verify-OOB WARN-amber HARD BLOCK … **NOT error-red 'broken'**"). Und error-rot ist in
  web-ts der **AuthRejected**-Klasse vorbehalten (`HintTone.ERROR`) — **Achse b**, nicht c.
- **NICHT neutral (`on-surface`, wie CYP-803 `trusted`):** es ist **kein Status-Badge**, sondern ein **Block** — neutral
  würde einen harten Stopp herunterspielen. (PO-Auflage: distinkt vom Achse-a-Trust-Badge, **nicht derselbe Token**.)
- **Die „Terminal"-Härte trägt die STRUKTUR** (kein Retry, kein Connected, assertive Ansage, OOB-Hinweis), **nicht der
  Ton** — genau Team-1s Muster. Die ehrliche Mitte: amber-Aufmerksamkeit + struktureller Hard-Block, weder Downplay
  (neutral) noch Über-Alarm (rot).

> **Cross-Surface (CYP-803-Muster: geteiltes Prinzip, per-Surface-Token):** Prinzip = „WARN-amber, protektiv-nicht-broken,
> Härte via Struktur". Token pro Surface = web-ts `warn-container` ⇄ Compose `severityColor(Severity.WARN)` — **gleiche
> Semantik**. Ich hatte kurz `error-container`/⚠ erwogen (web-ts „terminal ⇒ errorContainer"-Muster aus CommPanel-Revoke)
> — **verworfen**, weil hier die Terminal-Härte schon strukturell getragen wird und error-rot die protektive Verweigerung
> als Defekt über-alarmieren + von Compose divergieren würde.

## 2. Tone (Token)
| Rolle | Token (web-ts) | Compose-Parität |
|---|---|---|
| Block-Hintergrund | `--md-sys-color-warn-container` | `severityColor(Severity.WARN)`-Fläche |
| Text / Label | `--md-sys-color-on-warn-container` (Text-Rolle ≥4.5:1) | s. o. |
| Glyph ▲ | `--md-sys-color-on-warn-container` (Graphik ≥3:1) | `severityColor(Severity.WARN)` |

**NICHT** `error-container`/`on-error-container` (Achse-b/AuthRejected + „broken"-Register) · **NICHT** `on-surface`/
`on-surface-variant` (Achse-a-Trust-Badge, CYP-803).

## 3. Glyph
**▲** — der etablierte **web-ts-WARN-Glyph** (dokumentierte Severity-Sprache: `AclPanel.tsx` „**WARN glyph ▲** (not the
ERROR glyph ⚠ …)", `eventLog.ts` warn→▲/error→⚠; `remoteSecurityTierModel.ts` „deliberately NOT ▲ which is WARN").
Parität mit Compose (▲). **`aria-hidden="true"`** — der **Text trägt die Bedeutung** (colour/glyph nie alleiniger
Träger, WCAG 1.4.1). **NICHT ⚠** (ERROR/„broken"), **NICHT** die Achse-a-Kreis-Glyphen `◯◔●⊘◑`.

## 4. Copy (DE-inline — Parität mit Team-1s ratifizierten Strings, CYP-747 §5-C2)
web-ts ist DE-inline (kein i18n). **Wortgleich** zu Compose übernehmen (keine divergente Zweitformulierung — Reuse):

- **Haupt-Zeile** (`remote_connect_issuer_not_trusted`):
  > Verbindung angehalten: Dieser Hub ist registriert, aber es ist kein vertrauenswürdiger Aussteller an ihm etabliert.
  > Ohne etabliertes Aussteller-Vertrauen wird keine Operator-Berechtigung erteilt.
- **OOB-Hinweis-Zeile** (`remote_connect_issuer_oob`, **eigene Zeile, Text — kein Button**):
  > Ein vertrauenswürdiger Aussteller wird außerhalb der App am Hub etabliert (durch den Betreiber/PO). Danach erneut verbinden.
- **a11y-Beschreibung** (`a11y_remote_connect_issuer_not_trusted`, am Block-Container):
  > Verbindung angehalten: kein vertrauenswürdiger Aussteller am Hub etabliert. Keine Operator-Berechtigung.
  > Aussteller-Vertrauen wird außerhalb der App etabliert; danach erneut verbinden.

*(EN-Parität existiert bereits in `values-en/strings.xml` — falls web-ts je i18n bekommt, dort spiegeln.)*

**Honesty — den Issuer-String NICHT als autoritativen Namen zeigen:** das Verdikt trägt `issuer: String?` (die
selbst-behauptete Aussteller-ID eines **nicht** vertrauenswürdigen Gegenübers). **Nicht** roh in die sichtbare Copy
interpolieren — einen ungeprüften, selbst-asserierten Namen als Fakt zu rendern ist eine Spoofing-Fläche. Team-1s
sichtbare Copy interpoliert ihn ebenfalls nicht (das Feld dient State/Logging/testid, nicht der Anzeige). Gleich halten.

## 5. Struktur & a11y (HARD BLOCK, fail-closed)
- **Container:** `role="alert"`, `aria-live="assertive"` — ein **unaufgeforderter terminaler Trust-Stopp, den der
  Operator hören muss** (Parität mit Compose `LiveRegionMode.Assertive`). Die volle a11y-Beschreibung (§4) am Container.
  *(Assertiv ist hier gerechtfertigt — dieselbe enge Ausnahme wie CYP-755 aktiv-Hub→rejected/stale; nicht inflationär.)*
- **Inhalt:** ▲ + Haupt-Zeile · darunter OOB-Hinweis-Zeile.
- **KEIN Retry-Button, KEIN „Verbunden"/Proceed, KEIN Grant.** Recovery ist **OOB-only** (Issuer-Entscheidungen sind die
  PO/OOB-Grenze — der Hinweis ist **Text, kein Button**, spiegelt Compose).
- **Namespace (Achse-c, eigen):** Klasse/testid im **Issuer**-Namespace, z. B. `issuer-not-trusted` /
  testid `remote.connect.error.issuerNotTrusted` + `remote.connect.issuerOob` (Parität zu Compose
  `RemoteConnectTags.error("issuerNotTrusted")` / `ISSUER_OOB`). **NIE `hub-trust-*`** (Achse a) und **nie** der
  Operator-Auth-Namespace (Achse b) — die a/b ⊥ c-Trennung (`Cyp443TrustAxisSeparationGuardTest`) gilt auch in der
  web-ts-Namensgebung.

## 6. Distinkt von den Nachbarflächen (Ursache-Ehrlichkeit, drei Achsen)
| Verdikt | Achse | Ton/Glyph | Register |
|---|---|---|---|
| **IssuerNotTrusted** (CYP-805) | c (Issuer) | **▲ WARN-amber**, HARD BLOCK, OOB, kein Retry | protektive fail-closed Verweigerung |
| Hub-Trust `rejected` (CYP-803, Achse a) | a (Hub-Key-TOFU) | ⊘ Status-Glyph, warn-Ton, **Badge** | per-Hub-Status, kein Block |
| AuthRejected (Achse b) | b (Operator-Identität) | `HintTone.ERROR` (rot) | „broken"/Credential-Fehlschlag |
| Verbindung verloren / offline | — | WARN-amber **transient** (comm-status) | **reconnectable** Blip |

IssuerNotTrusted darf **nicht** als generischer „Verbindung verloren"-Banner lesen (reconnectable) und **nicht** als
AuthRejected (rot/Credential). Die Copy trägt die **spezifische Ursache** (Aussteller-Vertrauen fehlt), die Struktur die
**Terminal-Härte**.

## 7. Discriminating Teeth (Tester2 F3)
1. **WARN, nicht ERROR/neutral** — Block rendert `warn-container` + ▲; **nicht** `error-container`/⚠ und **nicht**
   `on-surface`/`hub-trust-*`. *(Mutation: error-rot/⚠ → RED = over-alarm + Compose-Divergenz; neutral/on-surface → RED =
   Block als Status heruntergespielt / Achse-a-Konflation.)*
2. **HARD BLOCK fail-closed** — **kein** Retry-Button, **kein** Connected/Proceed/Grant im gerenderten Block.
   *(Mutation: irgendein Retry-/Grant-/Proceed-Affordance → RED.)*
3. **OOB-Hinweis präsent, als TEXT nicht Button** — die OOB-Zeile rendert; sie ist nicht klickbar/aktionabel.
   *(Mutation: OOB-Zeile fehlt → RED; OOB als Button/Retry → RED.)*
4. **a11y assertiv** — Container `aria-live="assertive"`/`role="alert"`. *(Mutation: polite/none → RED.)*
5. **Achse-c-Namespace** — testid/Klasse im Issuer-Namespace, **nie** `hub-trust-*`, nie der Operator-Auth-Namespace.
   *(Mutation: reuse `hub-trust-*` oder der neutrale Achse-a-Token → RED = Achsen-Konflation, Cyp443-Klasse.)*
6. **Issuer-ID nicht als autoritativer Name gezeigt** — die selbst-behauptete `issuer`-ID erscheint nicht als
   vertrauenswürdiger/echter Name in der sichtbaren Copy. *(Mutation: roher issuer-String in die Haupt-Copy interpoliert
   → RED = Spoofing-Fläche.)*
7. **Ursache-distinkt** — Copy ist die Issuer-Trust-Ursache, **nicht** „Verbindung verloren" und **nicht** AuthRejected.

## 8. Reuse-Ledger
- **Copy/Semantik:** 1:1 aus Team-1 CYP-747 §5-C2 (`values/strings.xml`: `remote_connect_issuer_not_trusted`,
  `remote_connect_issuer_oob`, `a11y_remote_connect_issuer_not_trusted`; Render `HubConnectSelection.kt`).
- **Severity-Sprache:** web-ts ▲=WARN / ⚠=ERROR (`AclPanel`, `eventLog`, `remoteSecurityTierModel`).
- **Terminal-vs-transient-Disziplin:** `CommPanel.tsx` („WARN-amber offline = reconnectable ≠ errorContainer revoke =
  terminal") — hier via **Struktur** getragen, Ton bleibt protektiv-amber.
- **Achsen-Trennung:** `Cyp443TrustAxisSeparationGuardTest` (a/b ⊥ c) — gilt auch für web-ts-Namensgebung.
- **Kein Neubau:** keine divergente Zweitformulierung, kein neuer Ton, kein neuer Glyph — reine Instanz der etablierten
  Sprache + Cross-Surface-Parität.
