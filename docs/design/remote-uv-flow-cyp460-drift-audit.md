# Remote UV-Flow — CYP-460-Dialog × CYP-542-Contract Drift-Audit (Interim, pre-Slice)

> Owner: UIUX-Designer · Story CYP-542 (Phase A/B1) · Stand 2026-07-14 · Status: **Interim-Audit** (ungated, pre-
> Verdrahtung; PO `1526541081…`). **Design/QA, kein Code.** READ-ONLY @ `eb705236`.
>
> **Zweck:** Wo **driftet** der schon-gebaute CYP-460-`OperatorAuthDialog` gegen die finale CYP-542-Sprache
> (22 Realkeys + 2 a11y · 9 Const + 1 Fn · 3-Stufen-Ton-Leiter · capability-conditional Credential)? Damit Dev die
> UI-Slice **gegen die neue Sprache** baut und nicht die alte naiv erweitert. **D = Drift (Dev muss reconcilen)** ·
> **✓ = konsistent (Precedent zum Reuse)**.

## D — Drift-Befunde (an Dev, über PO)

- **D1 [scharf] — `tooWeak`-Ton:** der gebaute Dialog rendert **jeden** nicht-terminalen Fehler als `colorScheme.error`
  (`OperatorAuthDialog.kt:133-139`). Die finale Ton-Leiter trennt: **`tooWeak` = WARN-Amber** (advisory „noch nicht stark
  genug") ≠ error-Ton. **Falle:** routet Dev die Enroll-`tooWeak`-Validierung durch den bestehenden generischen
  Local-Error-Pfad, wird sie **error-rot statt WARN-amber** → widerspricht der ratifizierten Leiter. `tooWeak` muss den
  **WARN-Amber-Pfad** (wie Session-only `:100-116`) nutzen, nicht den generischen Error-Pfad. `blocklisted`/`mismatch`
  bleiben error-Ton.
- **D2 — Credential-Terminologie:** die gebaute Copy sagt **universell „App-PIN"** (`remote_pop_enroll_pin` „App-PIN
  festlegen", `remote_pop_pin_title` „App-PIN eingeben"); der Dialog hat **keinen Capability-Branch**. Finale Sprache:
  **no-hardware → „App-Passphrase"** (neue Keys), hardware-backed → „App-PIN" (Reuse der frozen Copy). Dev muss die
  **capability-conditional Copy-Wahl** einziehen (Signal = Platform-Authenticator vorhanden?, S-UV1).
- **D3 — `enrollError.*` ≠ `error.*` (H1):** der gebaute Dialog kennt nur `error(cause)` (**Auth-Zeit**:
  pinWrong/biometricFailed/…). Die Enroll-Validierung nutzt die **neue** Fn `enrollError(cause)` (`mismatch`/`tooShort`/
  `tooWeak`/`blocklisted`, **Setup-Zeit**). **Nicht mergen** — distinkte Wahrheiten, distinkte Tags.
- **D4 — Enroll-Step ist heute ein Text-Stub:** `OperatorAuthStep.Enroll` (`:94-118`) rendert nur Titel +
  `remote_pop_enroll_pin`-Text + Session-only — **kein** Feldpaar, **kein** Meter, **kein** Diceware-Default. Der ganze
  U1-Bau (visual-spec §1/§2) ist net-new; er ersetzt den Stub (nicht erweitern).
- **D5 — BLOCKLISTED-Fill-Ehrlichkeit (kein Precedent → leicht zu verpassen):** der gebaute Dialog hat **keinen** Meter;
  Dev baut ihn frisch. **Muss:** bei `blocklisted` den Fill **dämpfen** (nicht „voll/stark" zeigen) — die Optik darf nicht
  „stark" lügen, während der Verdict „zu verbreitet" sagt (visual-spec §2, H1 anti-Konflation). Ohne Precedent besonders
  betonen.

## ✓ — Konsistent (Precedent zum Reuse, NICHT neu erfinden)
- **Session-only = WARN-Amber `▲`** (`:100-116`, `severityColor(WARN)` + separater Glyph-Node) — **der Ton-Precedent**, auf
  dem `tooWeak` (D1) aufsetzt. Reuse identisch.
- **Terminal = errorContainer / lokal = error-Ton** (`:121-149`) — die Leiter-Basis; CYP-542 ergänzt nur die WARN-Stufe
  darunter (`tooWeak`) und den error-Ton-Reject (`blocklisted`). `errorContainer` bleibt **nur** terminal (Hub-Reject).
- **`WrongPin` + `ATTEMPTS` + `LOCKED_OUT`** (`:140-147`) — Rate-Limit/Lockout-Rendering; **Reuse as-is** (bleibt error-Ton,
  nicht WARN — konsistent mit §6). Enumeration-frei (nur Count/Cooldown).
- **Reveal = Text-Label** (`AuthPasswordField`, `PinField :154-167`) — kein Emoji, kein a11y-Leak; die neuen Passphrase-
  Felder reusen exakt dasselbe Primitiv.
- **`granted` ohne Erfolgs-Grün** / **`tertiary` nie Status** — bereits eingehalten (MaritimeTheme `tertiary` = Brand-only).
- **`pathHint` benennt den echten Pfad** (`:71-76`) — trägt schon PIN/Biometrie; erweitert um „App-Passphrase" (D2), Muster
  identisch.

## Fazit / Handover
5 Drift-Punkte (D1 scharf = `tooWeak`-Ton) + 6 Reuse-Precedents. **Kein Bau-Loch — alles reconcilebar an der UI-Slice**;
D1–D3 sind Ton-/Namespace-Disziplin, D4/D5 sind net-new-Bau gg. visual-spec. Über den PO an Dev. Kein Bau, keine
develop-Berührung; docs-only.
