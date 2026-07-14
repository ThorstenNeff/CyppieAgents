# Remote UV-Flow B1 — Visual/M3-Spec (net-new UI-Elemente) (CYP-542, Epic CYP-427)

> Owner: UIUX-Designer · Story CYP-542 (Phase A/B1) · Stand 2026-07-14 · Status: **Visual-Spec, ratifikations-reif**
> (Keep-busy bis zur §-QA; PO `1526533836…`). **Design/QA, kein Code.** Begleit: `remote-uv-flow-tokens.json` (Token-Werte),
> `remote-uv-flow-{ux-spec,tags,keys,qa-checklist}.md`. Gegroundet READ-ONLY @ `eb705236`.
>
> **Scope:** das **visuelle Design** der Elemente, die's im gebauten CYP-460-`OperatorAuthDialog` **noch nicht** gibt —
> damit Dev die UI-gg-CYP-460-Slice gegen ein Design baut, nicht nur gegen Verhaltens-ACs. **4 Elemente:** (1) Passphrase-
> Enroll-Eingabe · (2) Strength-Meter (3 Verdict-States) · (3) Blocklisted-Fehler-Präsentation · (4) Auto-Weiterlauf.
>
> **Nahtstelle UIUX2 (non-colliding):** UIUX2 macht die **Eingabe-Interaktion + a11y** (Fokus/IME/Reveal-Verhalten,
> SR-Ansagen des Felds + Meters, Fokus-Reihenfolge). **Ich** mache das **visuelle Design** (Farb-Rollen, Ton-Trennung,
> Layout, Typo, die visuelle Sprache des Meters, WCAG-Farbe+Glyph-Paarung). Wo sich das berührt, ist's markiert `→UIUX2`.

## 0. Palette-Grounding (echte App-Theme, nicht erfunden)
Quelle: `MaritimeTheme.kt` (CYP-268 R1 + CYP-304 Night, WCAG-AA-auditiert) + `EventVisuals.kt` (Severity-Ampel,
scheme-adaptiv). **Keine neue Farbe.** Verbindlich:
- **`primary`** = Hafenblau (`#0A5AA0` light / `#6FBEEA` dark) — die **affirmative** Brand-Farbe (OK-Zustand), **nicht** Grün.
- **`error`** = Rot (`#B3261E` light / `#F2B8B5` dark) — bleibt Rot (Datenverlust-Bedeutung).
- **`onSurface`/`onSurfaceVariant`** = Tiefmarine/gedämpft — neutrale Fakten.
- **`outline`/`outlineVariant`/`surfaceVariant`** — Track/Rahmen/Container.
- **WARN-Amber** = `severityColor(Severity.WARN)` (`#9A6400` light / `#FFC857` dark) + Container `warnContainer` — **ich
  ratifiziere diese Hex für diese Fläche** (EventVisuals-KDoc: „UIUX ratifiziert exakte WARN-hex am §9"). Glyph `▲`.
- **VERBOTEN:** `tertiary`/`#40D6A0` (Signal-Grün) ist **Brand-only, NIE Status** (§9-Inv.1) — kein Erfolgs-Grün im Meter.

---

## 1. Element — Passphrase-Enroll-Eingabe (Diceware-Default prominent · type-your-own gated)
**Hierarchie = die Ehrlichkeit:** der generierte Default ist der **visuell dominante** Common-Path; type-your-own ist die
leise Ausnahme.

**1a. Empfohlener Diceware-Default (prominent, oben):**
- **Container:** `surfaceVariant`-Fill, `outline`-Rahmen (1dp), 12dp Radius, 16dp Padding — ein ruhiger „Karten"-Block.
- **Label:** `remote_pop_enroll_suggested` „Empfohlen — 6 zufällige Wörter" — `labelMedium`, `onSurfaceVariant`. **Kein**
  Grün, kein „✓" — „Empfohlen" trägt der Text, nicht die Farbe.
- **Die Wörter:** `bodyLarge`, **monospace**, `onSurface`, Wort-getrennt (großzügiges Spacing) — lesbar + abschreibbar,
  selektierbar. `→UIUX2`: Selektion/Copy-Interaktion + SR-Ansage.
- **Primär-Aktion:** `remote_pop_enroll_suggest_use` „Diese Passphrase verwenden" — **gefüllter `primary`-Button**
  (`onPrimary`-Text), volle Breite. Der One-Click-Accept ist die visuell stärkste Aktion.
- **Regenerate:** `remote_pop_enroll_suggest` „Andere vorschlagen" — **Text-/Outlined-Button** (`primary`-Text), sekundär.
- **Save-Hinweis:** `remote_pop_enroll_suggested_save` „Notiere sie sicher …" — `labelSmall`, `onSurfaceVariant`,
  **neutral-informativ** (kein WARN — es ist Guidance, kein Downgrade). Nah an den Wörtern.

**1b. Type-your-own (sekundär, entwertet):**
- Einstieg: `remote_pop_enroll_type_own` „Eigene Passphrase eingeben" — **leiser Text-Button** (`onSurfaceVariant`/
  `primary`), unter dem Default. Signalisiert „Ausnahme, nicht der Default".
- Expandiert das **Feldpaar:** Setz-Feld (`remote_pop_enroll_passphrase`) + Bestätigungs-Feld
  (`remote_pop_enroll_passphrase_confirm`), beide **reuse `AuthPasswordField`** (maskiert, Text-Label-Reveal — `→UIUX2`
  Reveal-Interaktion/a11y; **ich**: Reveal ist Text-Label, **kein Emoji**, `primary`/`onSurfaceVariant`-Stil).
- Darunter der **Strength-Meter** (§2).

---

## 2. Element — Strength-Meter mit 3 Verdict-States (Ton-Trennung: WARN vs error)
**Der Meter** (nur type-your-own; der Default ist by-construction stark):
- **Track:** `surfaceVariant`, Höhe 6dp, voll gerundet. **Fill:** proportional zur Entropie-Schätzung, **`primary`**
  (neutral-affirmativ, **nie** Grün). `→UIUX2`: Live-SR-Ansage der Stärke-Änderung.
- **Level-Label** (`labelSmall`, `onSurfaceVariant`): `remote_pop_strength_{weak,fair,strong}` — **Text-Label, Farbe nie
  alleiniger Träger** (WCAG 1.4.1).

**Die 3 Verdict-States (je eigene visuelle Sprache):**

| Verdict | Bedeutung | Fill | Verdict-Zeile (Farbe + Glyph + Label) | Ton |
|---|---|---|---|---|
| **OK** | ≥64-bit-Floor ∧ !blocklist | `primary`, bis „stark"-Zone | „Stark" (`remote_pop_strength_strong`), `onSurfaceVariant`/`primary`, **kein Alarm-Glyph** | affirmativ-neutral (**kein Grün**) |
| **TOO_WEAK** | < Entropie-Floor | `primary`, proportional (klein) | `▲` + `remote_pop_enroll_too_weak`, **WARN-Amber** (`severityColor(WARN)`) | **advisory** („noch nicht stark genug") |
| **BLOCKLISTED** | auf Blocklist (unabh. Länge) | **gedämpft `outline`/neutral — NICHT „voll/stark"** | `remote_pop_enroll_blocklisted`, **error-Ton** (`colorScheme.error`) | **härterer Reject** („andere wählen") |

**★ Kern-Ehrlichkeit (BLOCKLISTED-Fill):** eine lange Common-Phrase (z. B. „correcthorsebatterystaple") könnte strukturell
„stark" messen — der Meter darf dann **NICHT** einen vollen/`primary`-Fill zeigen (das widerspräche dem Reject). Bei
BLOCKLISTED wird der Fill **auf neutral/`outline` gedämpft** → die Optik lügt nicht „stark", während die Verdict-Zeile
„zu verbreitet" sagt. (H1 anti-Konflation: die *strukturelle* Stärke und das *Blocklist*-Urteil sind getrennte Wahrheiten,
und die Optik folgt dem strengeren.)

**Ton-Trennung (die drei sind visuell distinkt, PO-Anker):** OK = `primary`/neutral · TOO_WEAK = **WARN-Amber + `▲`** ·
BLOCKLISTED = **error-Ton**. Jeder trägt ein Text-Label (WCAG 1.4.1). Keiner nutzt `errorContainer` (das ist **nur** der
terminale Hub-Reject) und keiner nutzt `tertiary`-Grün.

---

## 3. Element — Blocklisted-Fehler-Präsentation (die neue ehrliche Copy visuell verankert)
- **Copy:** `remote_pop_enroll_blocklisted` „Diese Passphrase ist zu verbreitet — sie steht auf einer Liste bekannt-
  schwacher Passphrasen. Bitte eine andere." — `bodySmall`, **`colorScheme.error`-Ton** (nicht `errorContainer`).
- **Feld-Kopplung:** das Setz-Feld schaltet `isError = true` → `error`-Outline; Fehlerzeile **direkt darunter** → Feld +
  Meldung lesen als **eine** retryable Einheit. Feld **bleibt aktiv** (retryable, kein Sackgassen-State).
- **Ton-Position (3-Stufen, verbindlich):** WARN-Amber (`too_weak`, advisory) **<** error-Ton (`blocklisted`, retryable
  reject) **<** `errorContainer` (terminal Hub-Reject, **nicht** im Enroll). Distinkte Ursache `enrollError.blocklisted` ≠
  `tooWeak` — die Farbe folgt der Ursache.
- **WCAG 1.4.1:** die Copy ist **selbstbeschreibend** (sagt *warum*) → Farbe nie alleiniger Träger; optional `▲`/Marker,
  aber der Text trägt bereits die Bedeutung. **Kein Enumeration-Oracle** (Enroll = eigenes Secret; CYP-543-Neutralität nur
  Auth-Pfad).

---

## 4. Element — Auto-Weiterlauf (Enroll→AUTHENTICATING sichtbar, kein toter Moment)
- Nach Accept/Set: die Enroll-Karte **weicht** dem AUTHENTICATING-Zustand — **kontinuierliche Bewegung, kein leerer
  Zwischenzustand** (G2).
- **Visual:** neutraler Fortschritt — `CircularProgressIndicator` (`primary` auf `surface`) + `remote_connect_authenticating`
  „Operator wird bestätigt …" (`bodyMedium`, `onSurfaceVariant`). **Kein** Erfolgs-Grün, **kein** Fake-Success-Screen —
  der Zustand ist *verifying*, nicht *granted*.
- **1-UV-für-N-Hint** (falls Fensterung greift): `remote_pop_uv_coverage` erscheint hier **neutral** (`onSurfaceVariant`,
  optional `ⓘ`) — „eine Bestätigung → die jetzt geöffneten Hubs". Kein zweiter Prompt (in-hand-Passphrase = erste UV).
- **granted** → weiter zur Hub-Liste, **ohne Erfolgs-Grün** (neutral). `→UIUX2`: Fokus-Übergabe/SR-Ansage des
  Zustandswechsels.

---

## 5. WCAG / Cross-cut (verbindlich)
- **1.4.1 Farbe nie alleiniger Träger:** jeder Zustand = Farbe **+** Glyph/Marker **+** Text-Label. Meter-Level = Text
  (`weak/fair/strong`); TOO_WEAK = `▲`; BLOCKLISTED = selbstbeschreibende Copy; OK = „Stark".
- **1.4.3 Kontrast:** alle Töne aus der AA-auditierten Maritime-Palette + severityColor (WARN 5.0:1 light / AA dark; error
  6.5:1 light). onColor-Paare der Container ≥AA.
- **Ton-Grammatik (verbindlich):** `primary` = affirmativ/OK · **WARN-Amber = advisory-Downgrade** (`too_weak`) ·
  **error-Ton = retryable-Reject** (`blocklisted`/mismatch) · **`errorContainer` = NUR terminal** (Hub-Reject) ·
  `tertiary`-Grün = **nie Status**.
- **Beide Schemes:** Light + Dark (Night: Fill `#6FBEEA`, WARN `#FFC857`, error `#F2B8B5`).
- **DE = Default + EN-Parität** (alle Copy-Anker aus `-keys.md`).

## 6. Nahtstellen
- **UIUX2 (Interaktion/a11y):** Feld-Fokus/IME/Reveal-Verhalten, SR-Ansagen (Feld, Meter-Live-Stärke,
  Zustandswechsel), Fokus-Reihenfolge (Default→Accept→Regenerate→type-own). **Ich** liefere die visuellen Zustände +
  Farb-/Glyph-/Label-Paarung, an denen die a11y ansetzt.
- **Dev (S-UV2):** baut die Elemente in `OperatorAuthDialog`/`HubConnectFlow` gegen diese Optik + `-tokens.json`.
- **Backend (S-UV1):** Entropie-Schätzung + Blocklist-Boolean (`meets()`) + Generierungsquelle liefern die *Zustände*,
  die diese Optik zeichnet.

## 7. Self-Validation
- **Keine neue Farbe** — nur Maritime-Rollen + severityColor(WARN)/error, alle gegen `MaritimeTheme.kt`/`EventVisuals.kt`
  @ `eb705236` verifiziert (Code = Source of Truth).
- **`tertiary`-Grün nirgends als Status;** `errorContainer` nur als der (hier nicht gerenderte) terminale Verweis.
- **Ton-Trennung der 3 Verdicts** distinkt (WARN vs error vs affirmativ), jede mit Text-Label (1.4.1).
- **BLOCKLISTED-Fill-Ehrlichkeit** verankert (Optik lügt nie „stark" bei blocklist-hit).
- Begleit-`-tokens.json` trägt die Rollen/Hex 1:1. Kein Bau, keine develop-Berührung; docs-only.
