# Residual-Exposure Disclosure-Kohärenz-Audit

> Owner: UIUX-Designer · **kein Ticket** (Cross-Cutting-Doktrin-Pass, PO-beauftragt 2026-07-16) · Stand 2026-07-16 ·
> **Design-Pass, kein Bau, kein Auto-Fix.** Gegroundet READ-ONLY gg. develop `75eb2b65` (Code + Doku) und die
> beiden Sicherheits-Doku-Korpora (`docs/`, `docs/security/`, Parent-Workspace `1x-…`, `test/STATUS.md`).
>
> **Auftrag (PO):** Wir haben vier *bewusst akzeptierte* Rest-Expositionen, jede einzeln sauber entschieden.
> Frage, die niemand als **Menge** gestellt hat: **sagt das PRODUKT dem Nutzer kohärent, was es NICHT schützt —
> oder steht die Ehrlichkeit nur in unseren Tickets?** Und: *ist „keine Warnung" bei einer bewusst akzeptierten
> Kante konsistent mit unserer eigenen Doktrin?* Ergebnis „passt alles, keine Lücke" ist zulässig, **wenn** es
> rauskommt.
>
> **Ausdrücklich außerhalb dieses Passes:** die Guardrail-Entscheidung zu ④ (CYP-663, „kein Guardrail auf
> Operator-ACL") ist **gesetzt** und wird hier **nicht** angetastet. Dieser Pass prüft **Disclosure**, nicht
> Guardrails — die Trennung ist §3, und sie ist der Kern der Antwort.

---

## 0. Verdikt zuerst

**Nein — es sind nicht vier beliebige Einzelentscheidungen; es gibt eine latente gemeinsame Linie (§1), und jede
Kante sitzt einzeln auf der richtigen Stufe. Aber die Linie ist nirgends als *eine* Regel aufgeschrieben, deshalb
*liest* es sich wie vier getrennte Entscheidungen — und der Mengen-Blick fördert zwei echte Disclosure-Defekte
zutage, die kantenweise unsichtbar sind.**

- **① Key-plaintext-at-rest** — vorbildlich: auf dem Schirm (`first_run_apikey_posture`) **und** im Design-Doc
  (`docs/CYP-199-…:87` „treat the hub host as trusted"). Das ist das Muster.
- **③ Proxy-Frische** — **jetzt** auf dem Schirm (CYP-656-Marker `~137k`, mein Pass 2026-07-16) auf Verbindungs-
  ebene; der Rest (per-Socket-Mikrofenster) ist transient/selbstheilend → Engineering-Log genügt. **Kohärent.**
- **② Klartext-im-RAM am Gateway** — **dem Nutzer** ehrlich abgedeckt (subsumiert unter ①s „Host vertrauenswürdig");
  **dem Betreiber** nicht: die Wahrheit steht nur im Engineering-Design-Doc (`CYP-638-gateway-design.md §13`), es
  gibt **keine** Betreiber-/Ops-Fläche, die die Mitigationen trägt. **Ein Loch — aber Betreiber-, nicht Nutzer-Loch.**
- **④ Operator-ACL** — **hier bricht die Kohärenz.** „Keine Warnung" stimmt so nicht: es *gibt* eine Warnung —
  aber **nur für READ** (`acl_self_blind_warning`), **WRITE ist komplett stumm** (`AclViewModel.kt:197`), und die
  READ-Warnung benennt ein *Symptom* („dein Live-Feed erblindet"), nicht die *Handlung* („du entziehst dir selbst
  ein Recht"). Das ist eine **Disclosure**-Inkonsistenz **innerhalb einer Fläche** — **unabhängig** von der
  gesetzten Guardrail-Entscheidung.

**Die Guardrail-Entscheidung selbst (④/CYP-663) ist doktrinkonform** (§3): einen harten Block setzt man, wo eine
Handlung eine *Garantie an andere* bricht (PO-Aussperrung bricht Hub-and-Spoke fürs ganze Team → harter 409-Guard
`acl_po_protected`, richtig). Operator-Selbst-Erblindung bricht für niemanden eine Garantie und ist *recoverable*
(Rolle immutable) → **kein** harter Block ist der **richtige** Call. Die einzige Inkohärenz liegt nicht im Guard,
sondern darin, dass die **Disclosure-Hälfte unvollständig geliefert** ist.

---

## 1. Die gemeinsame Linie (das, wonach du gefragt hast)

> **Disclosure folgt dem Publikum, das das Risiko trägt — geliefert im Moment, in dem es handeln kann.**

Daraus fallen **drei Stufen** — und *jede* Rest-Exposition gehört genau auf eine, bestimmt durch **wer** die
Wahrheit braucht und **wann**:

| Stufe | Wann diese Stufe gilt | Fläche |
|---|---|---|
| **A — Auf dem Schirm, im Moment der Handlung/Ansicht** | Die Exposition ändert, was *dieser Nutzer* **jetzt** erwarten darf, und er kann kalibrieren/handeln. | UI-Copy am Handlungs-/Lesepunkt (Posture-Zeile, Marker, Advisory) |
| **B — Betreiber-/Ops-Doku** | Die Exposition ist eine Eigenschaft des **Betreibens/Hostens**, wahr unabhängig von jedem Schirm-Moment; wer sie braucht, **betreibt den Host**, und die Mitigationen sind **Host-Aktionen**. | Betreiber-Posture-/Ops-Runbook |
| **C — Engineering-Log/Ticket genügt** | Die Ungenauigkeit ist **transient, selbstheilend** und führt **nie** zu einer *dauerhaften* Falschannahme. | Eng-Log / Test / Ticket |

**Zuordnung der vier Kanten:**

| Kante | Was es ist | Publikum & Moment | Richtige Stufe | Heute |
|---|---|---|---|---|
| **①** Key plaintext @0600, nicht at-rest-verschlüsselt (CYP-199/220) | Eigenschaft, *diesem Host* ein Secret anzuvertrauen | Operator, **beim Eintippen des Keys** | **A** (+ B fürs Betreiber-Detail) | ✅ A: `first_run_apikey_posture` · ✅ Design: `CYP-199:87` · ⚠ B: kein konsolidierter Betreiber-Eintrag |
| **②** Klartext im RAM am Gateway-Hop; Heap/Core-Dump lesbar (CYP-638 §13) | Eigenschaft des **Hostens** des Gateways; Host-Kompromiss-Threat | Host-Betreiber, **beim Härten/Deployen**; Nutzer: subsumiert unter ① | **B** (Nutzer: A via ①) | ✅ Design-Doc `CYP-638 §13` · ✅ Nutzer via ①s „Host vertrauenswürdig" · ❌ **keine Betreiber-Fläche** |
| **③** Frische: „connection-gated" ≠ „per-socket-fresh" (CYP-656/573) | transiente Ungenauigkeit einer Live-Zahl | Nutzer, **beim Lesen der Zahl** | **A** (Verbindungsebene) + **C** (per-Socket-Mikrofenster) | ✅ A: CYP-656-Marker `~137k` · ✅ C: `test/STATUS.md:11-12` |
| **④** Operator-Selbst-Erblindung ACL, kein Guardrail (CYP-663) | recoverable, selbst-betreffende Handlung | Operator, **im Moment der Handlung + Folgezustand** | **A** (Advisory + Folgezustand — **kein Guard**) | ⚠ **partiell**: READ gewarnt, WRITE stumm; „Live-Feed"-statt-„Zugriff"-Rahmung |

**Antwort auf die Kernfrage:** Die Linie **existiert** und wird kantenweise **richtig** angewandt — aber sie ist
nicht als *eine* Regel niedergeschrieben, und der Mengen-Blick deckt zwei Defekte an ④ (§4) und ein Betreiber-Loch
an ② auf, die einzeln keiner sieht.

---

## 2. Per-Kante-Flächenkarte (mit Zitaten)

### ① API-Key plaintext @0600 — **Stufe A erfüllt, B lückenhaft**
- **Auf dem Schirm (A):** `first_run_apikey_posture` (`docs/design/first-run-setup-keys.md:24`) — DE „… noch nicht
  verschlüsselt at-rest … Behandle den Hub-Host als vertrauenswürdig." / EN „… not yet encrypted at rest …
  Treat the hub host as trusted." Erscheint im First-Run **am Key-Eingabepunkt** — Publikum & Moment stimmen.
- **Design-Doc:** `docs/CYP-199-REMOTE-BRIDGE-VERIFY-AND-CONNECT.md:32,40,87` — „not encrypted at rest yet — treat
  the hub host as trusted" (die eine explizite „trust the host"-Wahrheit). Ergänzt `docs/design/CYP-548`,
  `CYP-542` (Device-Key-at-rest, benachbart).
- **Maskierung (Transit/Anzeige, separat):** `docs/PROJECT-SETTINGS.md:32,54` — GET liefert nie Klartext.
- **Lücke:** ①s *at-rest*-Detail lebt in einem **Design-Doc** (`CYP-199`, „design-only, PO-ratify pending"), nicht
  in einer polierten Betreiber-Posture-Seite. Nutzerseitig ✅; Betreiber-Konsolidierung fehlt (→ §5).

### ② Klartext im RAM am Gateway — **Nutzer via ① ok; Betreiber-Fläche fehlt**
- **Design-Doc (die Wahrheit steht — aber Engineering-Ebene):** `docs/design/CYP-638-gateway-design.md:164` (§13) —
  > „**Cleartext in RAM is definitional** — A2 terminates the tunnel, so decrypted operator↔hub bytes (incl. PTY)
  > exist in the gateway process memory. A heap/core dump, swap, or an attached debugger/ptrace can read them.
  > *Operational mitigation:* core-dumps off, no swap, ptrace restriction, bounded buffers … A tooth can pin only
  > *„no cleartext spill to disk"*, **not** *„no cleartext in RAM"*."
  Plus die Boundary-Beschreibung `:97-103,51`. Parent-Kontext: `17-Sicherheitsarchitektur-PO.md:29` — Hub =
  „die **einzige Klartext-Zone**"; `18-…:147` — „terminiere Noise, reiche **Klartext** … an Ktor."
- **Nutzerseitig (A):** korrekt **subsumiert** unter ①s „Behandle den Hub-Host als vertrauenswürdig" — RAM-Klartext
  ist genau das, was „Host vertrauenswürdig" bedeutet. Es ist **keine** eigene Nutzer-Meldung wert (deine These
  „Betreiber-Eigenschaft, keine Nutzer-Meldung" stimmt).
- **Betreiber-Loch (B fehlt):** die Mitigationen (core-dumps off, no swap, ptrace-restriction) sind **Host-Aktionen**
  — und es gibt **keine** Betreiber-/Ops-Fläche, die sie trägt. Keine `docs/operations|ops|deploy|runbook`;
  **kein CYP-287-Deploy-Runbook** (workspaceweit absent). Die Wahrheit ist real und dokumentiert, aber nur dort, wo
  der Betreiber, der sie anwenden muss, nicht als Erstes hinschaut.

### ③ Proxy-Frische — **jetzt kohärent (A + C)**
- **Auf dem Schirm (A), neu:** CYP-656-Pass (`docs/design/titlebar-token-staleness-spec.md`) — bei `!LIVE` trägt
  ein führendes `~` (`~137k`) + `a11y_agent_context_tokens_stale` („zuletzt bekannt … nicht live") die Frische-
  Ehrlichkeit **am Lesepunkt der Zahl**. Deckt die **Verbindungsebene** von „connection-gated ≠ per-socket-fresh".
- **Engineering-Log (C):** `test/STATUS.md:11-12` — „per-route/per-socket partials remain possible (independent
  conns) but rarer = the corner the lockstep-proxy knowingly accepts." Das **per-Socket-Mikrofenster** (Verbindung
  LIVE, aber ein einzelner Socket kurz gedroppt) ist transient/selbstheilend → **nie dauerhafte Falschannahme** →
  Stufe C genügt. Benachbart: `docs/tomorrow-morning-login-walkthrough.md:112-113` (CYP-573-Watch, stale-Dot als
  erwartet).
- **Kohärent:** die grobe, dauerhaft-täuschende Frische ist jetzt auf dem Schirm; die feine, selbstheilende bleibt
  bewusst im Log. Richtiger Schnitt.

### ④ Operator-ACL Selbst-Erblindung — **partiell inkohärent (siehe §3 + §4)**
- **Was gebaut ist:** `acl_self_blind_warning` (`strings.xml`/`values-en`) feuert als **Advisory** (Continue geht
  durch, kein Block) — **aber nur** wenn der Operator sein eigenes **canRead** abschaltet: Guard
  `selfBlind = agentId == OPERATOR_ID && dimension == READ && !newValue` (`AclViewModel.kt:197`).
- **Der Defekt:** eigenes **canWrite** abschalten fällt **stumm** durch `applyToggle` — **kein** Dialog, **keine**
  Warnung, **keine** Disclosure. Innerhalb *derselben* Selbst-Erblindungs-Fläche: READ gewarnt, WRITE stumm.
- **Rahmung:** die READ-Advisory sagt „dein Live-Feed erblindet … du siehst keine Live-Änderungen mehr" — ein
  *Symptom*, nicht die *Handlung* („du entziehst dir selbst ein Recht").
- **Der echte Guard (korrekt, andere Klasse):** PO-Aussperrung → advisory `acl_po_lockout_warning` + **harter
  Server-409** `acl_po_protected` (`AclReducer.wouldLockoutPo`, `AclReducer.kt:69-72`; `AclViewModel.kt:252`).
- **CYP-296-Banner ≠ Selbst-Fall:** `acl_access_revoked` („Zugriff entzogen – nur für Operatoren. Neu anmelden…")
  feuert nur, wenn *jemand anderes* dir das Token entzieht (1008-Close am eigenen Socket) — es ist die
  *Opfer-seitige* Terminal-Meldung, **nicht** die Warnung im Moment der Selbst-Erblindung.
- **Design-Doc ist ehrlich:** `docs/ACL-MATRIX.md:27` — „der Operator kann sich nicht aus dem Editieren aussperren
  (kann aber seinen Live-Feed **blind** schalten, §5.7)"; `:103,114,117` — „⚠ Ehrliche Grenze (Disclosure): Diese
  UI-Leitplanke ist **advisory, nicht der Durchsetzungspunkt**." Die Doktrin ist im Doc korrekt gedacht — nur die
  gebaute Fläche liefert sie **asymmetrisch**.

---

## 3. Der Kern: Guardrail ≠ Disclosure (die doktrinäre Antwort zu ④)

Deine Frage — *„ist ‚keine Warnung' bei einer bewusst akzeptierten Kante konsistent mit unserer Doktrin?"* —
zerfällt sauber in **zwei Achsen**, und sie werden gerade vermischt:

| | **Guardrail** | **Disclosure** |
|---|---|---|
| Was es ist | harter Block / erzwungene Reibung auf die *Handlung* | die Wahrheit über den *Zustand* sagen |
| Beispiel ④ | „du **darfst** dich nicht selbst erblinden" (409) | „du **hast** dich gerade selbst erblindet — so machst du's rückgängig" |
| Doktrin-Regel | Block, **wenn** die Handlung eine **Garantie an andere** bricht | **immer** — Abwesenheit ohne Erklärung ist ein Ehrlichkeits-Defekt |
| ④/CYP-663-Stand | **gesetzt: kein Block** — richtig (recoverable, immutable Rolle, bricht keine fremde Garantie) | **unvollständig geliefert** (READ ja, WRITE nein; Symptom statt Handlung) |

**Die Regel, die ④ und den PO-Guard *gemeinsam* erklärt:**
> **Garantie-brechend → Guard. Selbst-betreffend & recoverable → offenlegen, nicht blocken.**

- **PO-Aussperrung** bricht die Hub-and-Spoke-**Garantie** fürs ganze Team → harter Guard `acl_po_protected`.
  **Richtig.**
- **Operator-Selbst-Erblindung** bricht für niemanden eine Garantie und ist recoverable → **kein** Guard.
  **Ebenfalls richtig — CYP-663 sitzt korrekt auf der „offenlegen, nicht blocken"-Seite.**

⟹ **Die Guardrail-Entscheidung ist konsistent.** Was **nicht** konsistent ist: „offenlegen" ist die zweite Hälfte
derselben Doktrin-Zeile, und die ist bei WRITE **gar nicht** und bei READ **symptomatisch statt handlungsbenennend**
geliefert. „Keine Warnung" ist also nicht das, was gesetzt wurde — gesetzt wurde „kein **Guard**". Eine Advisory
ist **kein** Guard (der READ-Fall beweist es: sie koexistiert längst mit „kein Block"). Die Defekte in §4 schließen
**keine** Guardrail-Frage wieder auf und ändern **nichts** an CYP-663.

---

## 4. Kohärenz-Defekte (priorisierte Befundliste — für PO-Routing, kein Bau)

> Alle vier sind **Disclosure**, nicht Guardrail. **Keiner** tastet CYP-663 (kein Guard auf Operator-ACL) an.
> Ich liefere Befund + konkreten Fix; **Bau/Entscheid liegt beim PO.**

1. **[HOCH · ④ interne Asymmetrie]** Operator schaltet eigenes **canWrite** ab → **stumm** (`AclViewModel.kt:197`
   ist READ-gated). Dieselbe Fläche wie der READ-Fall, dieselbe Doktrin, gegensätzlich behandelt.
   *Fix (Disclosure, kein Guard):* den `selfBlind`-Zweig auf `dimension == WRITE` ausweiten und eine analoge
   Advisory zeigen (keine neue Copy nötig, wenn eine handlungsbenennende Variante — s. Befund 2 — beide trägt).
2. **[MITTEL · ④ Rahmung]** `acl_self_blind_warning` benennt ein *Symptom* („Live-Feed erblindet"), nicht die
   *Handlung* („du entziehst dir selbst Lese-/Antwortrecht in %1$s; du kannst es als Operator jederzeit
   zurücknehmen"). *Fix:* Copy-Revision, die **Handlung + Recoverability** benennt (Recoverability ist genau die
   Grundlage, auf der CYP-663 auf den Guard verzichtet — sie **gehört** in die Copy).
3. **[MITTEL · ② kein Betreiber-Home]** ②s Wahrheit + Mitigationen leben nur im Engineering-Design-Doc
   (`CYP-638 §13`); die Host-Betreiber-Aktionen (core-dumps off, no swap, ptrace) haben **keine** Betreiber-Fläche.
   *Fix:* Betreiber-Posture-Eintrag (→ §5). **Nicht** ins Nutzer-UI (Nutzer sind via ① abgedeckt).
4. **[NIEDRIG · strukturell]** Keine **einzelne** Fläche behandelt ①②③④ als *eine* bewusst-akzeptierte Menge —
   genau die Lücke, die du intuiert hast. *Fix:* die gemeinsame Linie (§1) + die Flächenkarte (§2) in ein
   Posture-Dokument heben — **Muster existiert** (`docs/security/CYP-576-auth-as-shipped-posture.md §5 „Accepted
   residuals"`), also **erweitern, nicht erfinden** (→ §5).

**Nicht-Befund (positiv):** ①③ sind kohärent; die ④-Guardrail-Entscheidung ist doktrinkonform (§3); ②s
Nutzer-Seite ist korrekt via ① subsumiert. Der Pass kommt **nicht** „alles kaputt" zurück — er kommt „drei sitzen,
④ liefert seine Disclosure-Hälfte asymmetrisch, ② fehlt ein Betreiber-Home, und die Menge ist nirgends als Menge
geschrieben" zurück.

---

## 5. Wohin die Wahrheit gehört, wenn nicht auf den Schirm

Dein „sag mir, wo die Wahrheit hingehört, wenn nicht auf den Schirm" — nach Stufe (§1):

- **Stufe A (Schirm):** ① (Key-Eingabe-Posture ✅), ③ (Lese-Marker ✅), ④ (Advisory am Handlungspunkt +
  Folgezustand — Befunde 1/2). **Nutzer-Wahrheiten im Moment der Handlung.**
- **Stufe B (Betreiber-/Ops-Doku):** ② (RAM-Klartext + Host-Mitigationen) und ①s *at-rest*-Detail-Ebene. **Kein
  neuer Ort nötig** — es existiert bereits ein „Accepted residuals"-Genre: `docs/security/CYP-576-auth-as-shipped-
  posture.md §5`. Der kohärente Zug ist ein **Geschwister-/Erweiterungs-Dokument in `docs/security/`**, das die
  vier Kanten als *eine* Menge trägt (Betreiber-Zielgruppe). Der Parent-Threat-Model-Satz (`14/16/17/18`) ist die
  *Ratifikations*-Ebene, nicht die Betreiber-Anleitung — er deckt heute eine **andere** Residual-Menge ab und
  nennt ①②③④ nicht.
- **Stufe C (Eng-Log/Ticket):** ③s per-Socket-Mikrofenster (`test/STATUS.md`) — **bewusst** kein Schirm, kein
  Ops-Doc; transient/selbstheilend.

> **Lane-Grenze (ehrlich):** das konsolidierte `docs/security/`-Posture-Dokument **schreibe ich nicht allein** —
> ②s Mitigations-Wortlaut und der Threat-Model-Anschluss gehören Security/Backend. Dieses Audit ist das **Skelett
> + die Linie** dafür (Publikum×Moment-Taxonomie + Flächenkarte); die Konsolidierung ist ein **PO-geroutetes
> Cross-Team-Stück**, kein UIUX-Alleingang.

---

## 6. Was dieser Pass ausdrücklich NICHT tut

- **Kein Bau, kein Auto-Fix, kein Ticket-Transition.** Reine Design-/Doktrin-Analyse + Befundliste.
- **Keine Änderung an ④/CYP-663.** Die „kein Guard auf Operator-ACL"-Entscheidung bleibt unangetastet; alle
  ④-Befunde (§4.1/§4.2) sind **Disclosure**, nicht Guardrail (§3). Ob das Schließen einer Disclosure-Lücke als
  „an ④ rühren" zählt, entscheidest **du** — ich lege nur offen, dass „keine Warnung" ≠ „kein Guard" ist und heute
  faktisch asymmetrisch geliefert wird.
- **Keine neuen Keys/Tags final gesetzt.** Befund 2 skizziert eine Copy-Revision; die Ausformulierung
  (DE/EN, Ton, a11y) liefere ich als Triad **erst**, wenn du den Fix beauftragst.

---

## 7. Self-Validation

- **Als Menge geprüft, nicht kantenweise:** die Kohärenz-Aussage (§0/§1) fällt nur im Mengen-Blick — Befund 1
  (READ/WRITE-Asymmetrie) ist kantenweise unsichtbar, weil man READ *oder* WRITE einzeln als „hat/hat-keine
  Warnung" abhakt, nie als Paar.
- **Gegroundet, nicht plausibel:** jede Aussage trägt ein `file:line`-Zitat gegen develop `75eb2b65` (Code:
  `AclViewModel.kt:197`, `AclReducer.kt:69-72`; Copy: `strings.xml`/`values-en`; Doku: `CYP-638 §13:164`,
  `CYP-199:87`, `ACL-MATRIX.md:27,103,114,117`, `test/STATUS.md:11-12`, `docs/security/CYP-576 §5`).
- **Recon-Diskrepanz aufgelöst:** eine breite Doku-Suche schloss „② undokumentiert", weil sie auf CYP-429 und ein
  gleichnamiges „A2" (CP-Web-Exfil, `18-…:171`) ansprang; ein gezielter Grep fand `CYP-638-gateway-design.md §13` —
  ② **ist** dokumentiert (Engineering-Ebene), nur ohne Betreiber-Home. Die Karte nennt die **richtige** Quelle.
- **Guardrail ≠ Disclosure sauber getrennt (§3):** die ④-Befunde reißen CYP-663 nicht auf; ich sage explizit,
  dass „kein Guard" gesetzt und korrekt ist, und trenne es von der unvollständig gelieferten Disclosure-Hälfte.
- **Lane-ehrlich:** das konsolidierte Posture-Doc ist als Cross-Team/PO-Routing markiert, nicht als
  UIUX-Alleingang; ②s Mitigations-Wortlaut bleibt bei Security/Backend.
- **Ergebnis-Ehrlichkeit:** der Pass durfte „passt alles" zurückkommen — tut er **nicht**; er nennt drei sitzende
  Kanten und **konkrete** Defekte an ④/②, statt eine glatte Unbedenklichkeit zu behaupten.
- **Kein Bau:** docs-only auf `docs/residual-exposure-disclosure-audit`, off develop `75eb2b65`.
