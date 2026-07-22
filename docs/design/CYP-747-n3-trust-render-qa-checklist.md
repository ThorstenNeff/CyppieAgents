# CYP-747 / N3 — Trust-State-Render §-QA-Checkliste (Vorab-Prep, gatet den S1c-UI-Bau)

> Owner: UIUX-Designer · **Design-QA-Prep, kein Bau** · Stub-parallel erstellt, während CYP-802 (S1c-UI-Bau)
> gehalten ist — damit der Bau eine **scharfe Gate-Liste** vorfindet statt sie erst am Build zu erfinden.
> Grundlage: die **gelandeten** CYP-798-Enums `core/.../model/HubTrust.kt` @ develop `275494e0`.
> Verwandt: `CYP-747-issuer-not-trusted-ui-spec.md` (Achse c) · `A11Y-ANNOUNCEMENTS.md` · `COLOR-CODING.md`
> (Green-Deoverload E1/CYP-300) · safe-but-silent (§4a) · Pre-Read (`CYP-738 §4`) · CYP-789 „marked, not hidden".

---

## 0. Was gebaut wird (Contract, am Code geerdet)

CYP-798 hat die **sprach-neutrale Trust-Vokabular** in `:core` gelandet (Compose **und** web-ts sprechen EINEN
Contract). Drei Enums + die Achsen-Trennung:

| Typ | Werte | Achse |
|---|---|---|
| `HubTrustState` | `UNKNOWN` · `PENDING` · `TRUSTED` · `REJECTED` · `STALE` | **a** (TOFU / Hub-Key) |
| `TrustRejectReason` (closed) | `KEY_CHANGED` · `OOB_REJECTED` | **a**, nur bei `REJECTED` |
| `HubDescriptorValidity` | `VALID` · `MALFORMED` | **separates Upstream-Signal (4. N4-Achse)** |
| *(Issuer, `RemoteIssuerTrustState`, `:server`)* | *`REMOTE_NOT_CONFIGURED`/`ISSUER_NOT_TRUSTED`/`ISSUER_TRUSTED`* | **c** — meine CYP-747-`IssuerNotTrusted`-Fläche, **nie** in a falten |

**`HubTrustState` ist heute von KEINEM Render konsumiert** (nur der Enum in `:core`) → der S1c-Bau verdrahtet
ihn; **diese Liste ist sein UX-Gate.**

---

## 1. Der N4-Nicht-Falt-Kern (die Ehrlichkeits-Achse dieser Liste)

Die PL-Ratifizierung (§4b) verlangt, dass der Client **vier verschiedene Wahrheiten NIE zusammenfaltet** — jede
routet woanders hin und muss **eigenständig** rendern:

| Ursache | Zustand | **NICHT** |
|---|---|---|
| Konnte nicht evaluieren (Netzfehler / noch nicht bewertet) | `HubTrustState.UNKNOWN` | ≠ REJECTED, ≠ „vertraut" |
| Evaluiert **und** verweigert/abgebrochen | `HubTrustState.REJECTED` (+ `TrustRejectReason`) | ≠ UNKNOWN, ≠ STALE |
| War vertraut, jetzt nicht mehr bestätigbar (Revocation / non-LIVE) | `HubTrustState.STALE` | ≠ TRUSTED (nie frisch vorgetäuscht) |
| Deskriptor unbrauchbar (non-base64 / ≠32 Byte) | `HubDescriptorValidity.MALFORMED` | ≠ REJECTED, ≠ Trust-Zustand |
| Aussteller nicht etabliert | *Achse c (`IssuerNotTrusted`)* | ≠ Achse-a-Zustände (Cyp443) |

> **Das ist die zentrale §-QA-Frage pro Zustand:** *Ist er von seinen N4-Nachbarn **vor dem Lesen**
> unterscheidbar (Ton/Glyph/Form), nicht nur im Fließtext?* (Pre-Read, `CYP-738 §4`.) Ein Fold zweier N4-Ursachen
> in denselben Look = **Blocker**.

---

## 2. Render-Kriterien pro `HubTrustState` (5 Zustände)

Jede Zeile ist eine §-QA-Prüfung. **Ton aus dem Bestand** (`severityColor`, `HintTone`, kein neuer Farbwert);
**Copy reuse vor Neu** (§6).

### 2.1 `UNKNOWN` — Default, fail-closed
- **Ton/Glyph:** **neutral** (`onSurfaceVariant`), **kein** Trust-Grün, **kein** Alarm-Rot/Amber (es ist kein
  Fehler, es ist *nicht bewertet*). Glyph optional neutral (`·`/kein), nie `✓`.
- **Fail-closed-Zahn (load-bearing):** UNKNOWN darf **NIE** im vertrauten Look rendern („never green-by-default",
  safe-but-silent §4a). Ein Default `= TRUSTED` oder ein leerer→trusted-Fallback = **Blocker**.
- **Distinkt von `PENDING`:** UNKNOWN = *keine* Bewegung/kein Prozess; PENDING = *laufende* Prüfung. Sichtbar
  verschieden (kein Spinner bei UNKNOWN).
- **a11y:** `stateDescription` = „nicht bewertet"/„unbekannt"; **Polite** (Öffnen-/Ruhezustand einer Fläche,
  kein abgeschicktes Ergebnis).
- **Copy:** neu (§6).

### 2.2 `PENDING` — Prüfung läuft, distinkt von UNKNOWN
- **Ton/Glyph:** **in-progress neutral** (Spinner / `⟳`, `onSurfaceVariant`) — **nie grün** („in-progress is never
  green", Haus-Konvention `ProductLeadPanel`/`CompactPanel`). Reuse der OOB-Confirm-/`TRUST_CHECK`-Optik.
- **Zahn (PL-freeze §5b-P2):** PENDING ist ein **eigener** Zustand, **≠ UNKNOWN** — nicht in „unbekannt" falten.
  Prüfen: bei offenem OOB-Fingerprint-Confirm steht PENDING, nicht UNKNOWN.
- **Copy reuse:** `remote_connect_trust_check` („Hub-Vertrauen wird geprüft …") passt; ggf. `TRUST_PROVISIONAL`
  für die vorläufige Phase.
- **a11y:** **Polite** (Öffnen-Zustand); bei aktivem Warten `Role`/`stateDescription` „wird geprüft".

### 2.3 `TRUSTED` — **neutral-definit** (PL-adjudiziert 2026-07-22, cross-team-verbindlich)
> **RESOLVED (PL-Adjudikation, CYP-803-Ruling `ddf1578c` maßgeblich):** TRUSTED = **neutral-definit**, **nicht**
> positiv/affirmativ, **nicht** `primary`/blau, **nicht** literal-grün. Mein ursprünglicher neutral-Befund war
> richtig; das web-ts-§1 „positiv" (`0ac8215e`) war der **Ausreißer** und wird von `ddf1578c` auf den
> **Desktop-Pattern** gebracht: `remote-trust-recovery-ux-spec.md §2.2 `TrustResolution.Pinned` = „neutral,
> **NIE grün**". **Der Overclaim sitzt im affirmativen TON, nicht nur der Grünheit** — ein blau-positiver Render
> behandelt einen **widerrufbaren** TOFU-Pin wie eine **Garantie** (derived≠native).

- **Token (CYP-803, exakt):** Glyph = **`on-surface`** (voll-emphase-neutral), Tone = `neutral`.
  **`on-surface`, NICHT `on-surface-variant`** — voll-emphase hält TRUSTED **distinkt von unknown/pending** (die
  `on-surface-variant`/gedämpft sind); kein Affirm-Akzent, aber auch **kein** Kollaps in die Absence-Zustände.
  > ⚠ **Token-Nuance vs Desktop:** Desktop-`TrustResolution.Pinned` nutzt `onSurfaceVariant`; im **5-Zustands**-Modell
  > braucht TRUSTED aber **`on-surface` (voll)**, weil hier die Nachbarn unknown/pending existieren
  > (Über-Neutralisierungs-Zahn §5.8). **NICHT** blind Desktops `onSurfaceVariant` übernehmen — sonst kollabiert
  > TRUSTED in den unknown-Ton.
- **Form:** `●` (voll) trägt die Distinktion (colour-never-sole), unverändert.
- **Copy (CYP-803):** Label = **„vertraut"** — **NICHT** „gepinnt" (kollidiert mit `pinnedOperatorId`/aktivem-Hub-
  Pointer; `tier*`≠`trust*`-Klasse; der Overclaim saß im Ton, nicht im Wort).
- **a11y:** trägt den ehrlichen Qualifier **„vouched, widerrufbar"** — **nicht** „sicher"/„verifiziert"
  (kein Garantie-Overclaim).
- **⚠ Achse-a ⊥ Achse-c am Render (PL-0107):** dieses `HubTrustState.TRUSTED` (Hub-Key-Pin) ist **nicht** das
  Issuer-`HubIssuerTrust.TRUSTED` (`:core`-Carrier, CYP-804; Aussteller-Anker). Zwei „TRUSTED", zwei Wahrheiten →
  im Render **distinkt**, nie konflatieren (Cyp443 auf Label/Fläche). Kriterium: §5-Zahn 9.

### 2.4 `REJECTED` — evaluiert & verweigert, terminal (trägt `TrustRejectReason`)
- **Ton/Glyph:** **WARN-amber `▲` Verify-OOB-Familie** (wie `TrustChanged`/`TrustRejected` heute, **nicht**
  error-red) — es ist ein Trust-Stopp, kein „kaputt". **Kein Retry-, kein Grant-Button** (OOB). Deckungsgleich
  mit meiner `IssuerNotTrusted`-Grammatik (CYP-797) — konsistente Trust-Terminal-Optik über a **und** c.
- **Copy = Reason-spezifisch (§3), reuse vorhanden.**
- **a11y:** **Assertive** (Ergebnis eines abgeschickten Connect-Versuchs, `A11Y-ANNOUNCEMENTS §1`).

### 2.5 `STALE` — war vertraut, nicht mehr bestätigbar — „marked, not hidden"
- **Ton/Glyph:** **WARN-amber**, aber **distinkt von REJECTED** (STALE ist kein terminaler Verweiger, sondern
  „Frische nicht bestätigbar"). Muster = `titleBarTokenStale`/CYP-789 „marked, not hidden": der zuletzt bekannte
  Zustand wird **markiert** (nicht als frisch vorgetäuscht, nicht versteckt).
- **Fail-closed-Zahn:** STALE rendert **nie** im TRUSTED-Look (kein „grün/gepinnt frisch") — die Frische-Lücke
  ist sichtbar. Auslöser können mehrere sein (Issuer-Revocation, non-LIVE-Feed) → Copy nennt **„nicht bestätigt"**,
  nicht die konkrete Ursache (die ist evtl. Achse c).
- **Distinkt von UNKNOWN:** STALE = *hatte* Trust; UNKNOWN = *nie* bewertet. Pre-Read verschieden (STALE trägt den
  Rest-Trust markiert, UNKNOWN ist leer/neutral).
- **a11y:** `stateDescription` „zuletzt bekannt, nicht bestätigt" (Muster wie `a11y_agent_context_tokens_stale`);
  **Polite** (Zustand einer Fläche, kein frisches Ergebnis).

---

## 3. `TrustRejectReason` (closed) — Copy-Reuse + Distinktheit

**Beide Copy-Strings existieren bereits — REUSE, keine divergente Neu-Copy (mein Konsistenz-Mandat):**

| Reason | Bestehender Key (reuse) | DE (Bestand) |
|---|---|---|
| `KEY_CHANGED` | `remote_connect_trust_changed` | „Hub-Schlüssel geändert — Verbindung blockiert. Neu-Pinnen nötig." |
| `OOB_REJECTED` | `remote_connect_trust_rejected` | „Fingerprint abgelehnt — nicht verbunden. Zum Verbinden Out-of-Band bestätigen." |

- **§-QA-Zahn:** `KEY_CHANGED` ≠ `OOB_REJECTED` — **distinkte Copy + distinkter Tag** (heute
  `error("trustChanged")` vs `error("trustRejected")`). Beide WARN-amber `▲`, **kein** Retry. Der geschlossene
  Enum darf **nicht** über String-Match gerendert werden (PL-Regel: machine-code); eine **neue** Reason erfordert
  **PL-Re-Ratifizierung** — falls der Bau eine dritte Reason einführt → **Flag an PO**.
- **Ausgeschlossene-Ursachen-Zahn:** Netzfehler → `UNKNOWN` (nicht REJECTED); Revocation → `STALE`; malformed →
  `MALFORMED`. Der Bau darf **keine** davon in einen Reject falten (N4, §1).

---

## 4. `HubDescriptorValidity.MALFORMED` — das separate Upstream-Signal

- **„Always-on-malformed"-Regel (PL-BINDING):** bei `MALFORMED` **MUSS** die UI **immer** ein **`⚠`** zeigen —
  der Client konnte Trust **nicht einmal evaluieren** (non-base64 / ≠32 Byte → kein Fingerprint). Fail-closed:
  **nie** vertraut.
- **Zahn (Nicht-Falten):** MALFORMED ist **weder** ein `TrustRejectReason` (keine evaluierte Entscheidung)
  **noch** ein `HubTrustState`-Wert — eigener Enum, damit ein Fold es nicht still in „reject"/„trust" kollabiert.
  **§-QA:** MALFORMED rendert als **eigenes** `⚠`-Signal, nicht als REJECTED-Alarm und nicht als UNKNOWN-neutral.
- **Ton:** `⚠` + WARN (Korruption/MITM/Bug möglich) — aber **distinkt** von REJECTED (das ist eine *Entscheidung*;
  malformed ist *„nicht auswertbar"*). Distinktheit via Copy + eigenem Tag.
- **Copy:** neu (§6) — nennt „Deskriptor unbrauchbar/nicht auswertbar", **nicht** „abgelehnt".
- **a11y:** Assertive, wenn es ein Ergebnis eines Ladens ist; sonst Polite. `⚠` nie alleiniger Träger (Text dazu).

---

## 5. Die load-bearing Ehrlichkeits-Zähne (die eigentliche Gate-Liste)

1. **Never-affirmative / never-green (PL/CYP-803)** — `TRUSTED` ist **neutral-definit `on-surface`**, **nicht**
   positiv/`primary`/blau/grün (§2.3); `UNKNOWN`/`PENDING`/`STALE` nie im vertrauten Look. *(Prüf: Default,
   leer-Fallback, Nacht-Theme; Mutation `tone:'positive'` / Glyph `primary` → RED = Garantie-Overclaim.)*
2. **N4-Nicht-Falten** — die 4 Ursachen (network/reject/stale/malformed) + Achse c rendern **je eigenständig**,
   **pre-read-distinkt** (§1). *(Prüf: sieht jede anders aus als ihre Nachbarn, bevor man liest?)*
3. **Fail-closed** — `UNKNOWN` = Default, `MALFORMED` = immer `⚠`, nichts kippt still auf trusted.
4. **safe-but-silent (§4a)** — `STALE` ist **markiert**, nicht als frisch vorgetäuscht; keine stille
   Degradierung in den TRUSTED-Look.
5. **Reuse vor Neu** — bestehende Copy/Tags (`trust_changed`/`trust_rejected`/`trust_check`/`TRUST_PINNED`)
   wiederverwenden; kein drittes Vokabular für dieselbe Sache.
6. **Achsen-Firewall (Cyp443)** — die `HubTrustState`-Fläche (a) referenziert **keine** Issuer-Typen (c);
   der Carry-forward-`issuer-home ⊥ a/b`-Scan (CYP-797, → S1b) bleibt getrackt.
7. **Kein Garantie-Overclaim (PL/CYP-803)** — `TRUSTED`-a11y sagt **„vouched, widerrufbar"**, **nicht**
   „sicher/verifiziert"; der Overclaim sitzt im **affirmativen Ton**, nicht nur der Farbe (blau-positiv = Garantie
   auf einem widerrufbaren TOFU-Pin).
8. **Keine Über-Neutralisierung (CYP-803 §6-Z.10, Gegen-Falle)** — `TRUSTED` darf **nicht** in `on-surface-variant`
   (= unknown/pending-Ton) kollabieren; „evaluiert-gültig" bleibt distinkt von den Absence-Zuständen (Form `●`≠`◯`,
   Token `on-surface`≠`on-surface-variant`). *(Mutation: TRUSTED-Glyph → `on-surface-variant` → RED.)* **Beide**
   Richtungen — Overclaim-Rückkehr **und** Über-Neutralisierung — sind gezahnt.
9. **Zwei-`TRUSTED`-Distinktheit (Achse a ⊥ c am Render, PL-0107 / CYP-805-③)** — es gibt **ZWEI** „TRUSTED": Achse-a
   **`HubTrustState.TRUSTED`** (Hub-Key-TOFU-Pin; neutral-definit `on-surface`, Label „vertraut", CYP-803) **UND**
   Achse-c **Issuer-Trust `HubIssuerTrust.TRUSTED`** (der `:core`-Carrier — CYP-804-frozen/PL-ratifiziert:
   `enum HubIssuerTrust { TRUSTED, NOT_TRUSTED, REMOTE_NOT_CONFIGURED }`; spiegelt die `:server`-interne Quelle
   `RemoteIssuerTrustState`; landet mit **Backends CYP-804-Build**, im Bau). Sie bedeuten **verschiedene Wahrheiten** — *Schlüssel gepinnt* vs. *Aussteller-Anker etabliert* —
   und **dürfen im Render NICHT konflatieren**: dieselbe Cyp443-Achsen-Firewall, jetzt auf **Label/Glyph/Fläche**-
   Ebene. **§-QA:** distinkte Copy (nicht zweimal nacktes „vertraut"), die die **Achse benennt** (Hub-Key ↔ Aussteller);
   keine geteilte „TRUSTED"-Fläche, die beide Achsen suggeriert; Pre-Read-distinkt (`CYP-738 §4`) über die Achsen.
   *(Prüf: Kann der Operator „Hub-Key-vertraut" mit „Aussteller-vertraut" verwechseln? → Kontext/Copy muss die Achse
   tragen. Mutation: beide als nacktes „vertraut" ohne Achsen-Kontext → RED = Achsen konflatiert.)*

---

## 6. Copy-Anker (Reuse-Tabelle + Neu-Vorschläge — final am Bau)

**Reuse (vorhanden, nicht neu erfinden):** `remote_connect_trust_changed` (KEY_CHANGED) ·
`remote_connect_trust_rejected` (OOB_REJECTED) · `remote_connect_trust_check` (PENDING) · `TRUST_PINNED`-**Neutral-Treatment**
(für TRUSTED — aber Label **„vertraut"**, NICHT „gepinnt", CYP-803: „gepinnt" kollidiert mit
`pinnedOperatorId`) · `TRUST_PROVISIONAL` (vorläufig).

**Neu nötig (Vorschlag DE / EN — final + Key-Namen am Bau, Dev landet `strings.xml`):**

| Zustand | DE | EN |
|---|---|---|
| `UNKNOWN` | Vertrauensstatus unbekannt — noch nicht bewertet. | Trust status unknown — not yet evaluated. |
| `STALE` | Zuletzt vertraut — Frische nicht bestätigt. | Last trusted — currency not confirmed. |
| `MALFORMED` | Hub-Kennung unbrauchbar — Vertrauen ließ sich nicht prüfen. | Hub descriptor unusable — trust could not be checked. |
| `TRUSTED` (neutral-definit) | vertraut | trusted |

**Wortlaut-Disziplin:** kein „sicher/verifiziert" bei TRUSTED (Overclaim); „unbrauchbar/nicht geprüft" bei
MALFORMED (nicht „abgelehnt"); „nicht bestätigt" bei STALE (nicht die konkrete — evtl. Achse-c — Ursache);
„noch nicht bewertet" bei UNKNOWN (nicht „Fehler").

---

## 7. Self-Validation

- **Am gelandeten Contract geerdet:** `HubTrust.kt` @ develop `275494e0` real gelesen — 5 `HubTrustState`, 2
  `TrustRejectReason`, `HubDescriptorValidity` + die Achse-a/c-Trennung; **nicht** aus der PO-Nachricht geraten
  (deren Namen waren informell — die echten stehen hier).
- **Bestehende Copy/Tags reuse-geprüft:** `trust_changed`/`trust_rejected`/`trust_check`/`TRUST_PINNED` existieren
  → als Reuse markiert; Neu-Copy nur für UNKNOWN/STALE/MALFORMED/TRUSTED-neutral.
- **★ Cross-Team-Divergenz gefunden, verify-statt-blind, PL-adjudiziert:** die `HubTrustState`-KDoc „only green
  state" + web-ts-§1 „positiv" (`0ac8215e`, 00:28) kollidierten mit dem **neueren** CYP-803-Ruling (`ddf1578c`,
  08:57) + eurem Desktop-Pattern (`TrustResolution.Pinned` neutral). Statt die (stale-§1-basierte)
  „positiv-blau"-Korrektur blind nachzuziehen → am Objekt verifiziert, den Widerspruch **gesurfaced**.
  **PL-Entscheid 2026-07-22: TRUSTED = neutral-definit `on-surface`, cross-team-verbindlich** — mein
  Original-neutral-Befund bestätigt. **Beide** Richtungen gezahnt (Overclaim-Rückkehr §5.7 **und**
  Über-Neutralisierung §5.8). *(Sync-Check: `hubTrustView.ts` `TONE.TRUSTED` evtl. noch `'positive'` — `ddf1578c`
  änderte nur das Spec-`.md`; der Token-Flip `'positive'→'neutral'` läuft separat, §-QA ich mit.)*
- **N4-Nicht-Falten als Pre-Read-Anwendung** (`CYP-738 §4`): jede der 5+ Wahrheiten muss vor dem Lesen
  unterscheidbar sein — die zentrale Gate-Frage.
- **Achse a ⊥ c respektiert:** die Liste faltet die `IssuerNotTrusted`-Fläche (c) **nicht** in `HubTrustState`
  (a); der CYP-797-Carry-forward bleibt getrackt.
- **Prep, kein Alleingang:** dies ist die **§-QA-Gate-Liste** für den S1c-Bau (CYP-802), keine Bau-Anweisung;
  finale Copy/Key-Namen + der green-vs-neutral-Entscheid fallen am Bau (ich flagge, PO/Bau entscheidet den
  Render, ich verifiziere gegen diese Zähne).
- **Keine Zeile Bau:** `strings.xml`/Kotlin nicht angefasst; Enums nur gelesen.
