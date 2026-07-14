# CYP-542 B1-Enroll — §-QA + Design-QA Ergebnis (beide Achsen, gg. develop `a3730297`)

> Owner: UIUX-Designer · Story CYP-542 (Phase A/B1) · Stand 2026-07-14 · **QA-Ergebnis, an PO gemeldet.**
> Zwei Achsen in einem Pass: **Verhalten/Honesty** (`remote-uv-flow-qa-checklist.md`) + **Design-System-Fidelity**
> (`remote-uv-flow-design-qa-checklist.md`). Gegroundet READ-ONLY gg. **develop `a3730297`** (B1 gemergt): die gebauten
> `RemoteOperatorAuthSteps.kt`, `EnrollStrengthPresentation.kt`, `OperatorAuthTags.kt`, `AuthComponents.kt`,
> `values/strings.xml` + `values-en/`. **Design/QA, kein Code.**

## Gesamt-Urteil: 🟢 **GO mit 7 Befunden** (0 blockierend fürs Dogfood; 3 Medium, 2 Low, 2 INFO)
Der **Kern ist treu gebaut** — die Honesty-Leiter, die Glyph-Grammatik, die rollen-basierte Farbe, `§1b`-kein-Reveal und
fail-closed stimmen exakt. Die Befunde sind Verfeinerungen, keine Honesty-Brüche in der gebauten Substanz. Befunde →
Follow-up-Tickets (PO).

---

## ✅ PASS — was treu gebaut ist (beide Achsen)

### Meter + Verdicts (Design-Fidelity D1–D8, Render-Oracle 1:1)
- **Track** `surfaceVariant`, Höhe **6dp**, `RoundedCornerShape(3dp)`; **Fill** custom `Box` (nicht `LinearProgressIndicator`) → Fill-**Rolle exakt**. ✅ D1/D2
- **Fill-Rolle:** `if (fillDamped) outline else primary` — **BLOCKLISTED = `outline`-gedämpft**, `meterFraction=0.1f`; OK=`primary` `1f`; TOO_WEAK=`primary` `0.25f`. **Die Kern-Ehrlichkeit** (Optik lügt nie „stark") ist gebaut. ✅ D6/★
- **Glyph-Grammatik exakt:** OK `●` `primary` (+ „Stark", `onSurfaceVariant`) · TOO_WEAK `▲` `severityColor(WARN)` · BLOCKLISTED **kein Glyph** + error-Ton + selbstbeschreibende Copy. ✅ D4/D5/D7 — der Dev-Render-Test `Cyp542StrengthMeterRenderTest` assertet genau diese Grammatik.
- **`errorContainer` nirgends** im Enroll; **`tertiary`/Grün nirgends**. ✅ D8

### Honesty/Verhalten (§-QA A/B/C/D)
- **§1b kein Reveal-Toggle:** `AuthPasswordField` gated `revealTag != null` → `PassphraseInput` übergibt **keinen** `revealTag` ⇒ Feld maskiert, **kein** Toggle. ✅ C3 (PO-Ruling `63e32c84` in Prod wahr)
- **Diceware = angezeigt lesbar** (`SelectionContainer`, nicht maskiert) — HG2-Ehrlichkeit (muss abschreibbar sein). ✅
- **Clipboard-Egress-Disclosure ehrlich:** Notice **past-tense** + **nur nach echtem Copy** (`if (copied)`), neutral `onSurfaceVariant`, **kein** Glyph, **kein** „sicher/gelöscht"-Versprechen. **Vorbildlich** — UIUX2-① exakt getroffen. ✅ A12
- **Copy-Ton-Leiter:** WARN-amber (tooWeak, advisory) · error-Ton `colorScheme.error` (mismatch/blocklisted, retryable) · **nie** `errorContainer`. ✅ D1
- **fail-closed:** beide Nicht-OK-Verdicts `blocks=true` → Enroll geblockt. ✅ D4/§4-#4
- **a11y:** Meter = Polite-Live-Region, Fehler = Assertive; Coverage/Clipboard tragen a11y-`contentDescription`. ✅ (UIUX2-Lane, bestätigt sauber)
- **Copy 1:1 DE+EN, Parität intakt** — alle ratifizierten Strings verbatim gelandet (Manifest-Pull sauber). ✅ D3
- **Crown-Jewel-Handling** (über Spec hinaus): `CharArray`-e2e, `suggestion` zeroized on dispose+regenerate, kein Klartext-String am maskierten Input, Feld-State stirbt mit dem Composable. ✅ (positive Überdeckung)

### Positive Abweichung (kein Befund)
- **`remote_pop_enroll_copy`** = „Passphrase kopieren"/„Copy passphrase" — Dev hat statt meines gespecten Reuse von `remote_recovery_codes_copy` („Codes kopieren", semantisch falsch) einen **dedizierten, korrekten** Key gebaut. **Besser als spezifiziert** — angenommen.

---

## ⚠ BEFUNDE (priorisiert: Severity + konkreter Fix + Lane)

### #1 — [Medium · Tester-kritisch] Tag-Namespace-Drift: `enrollError.<cause>` (meine Docs) vs `error.<cause>` (Build+Test)
- **Ist:** Build nutzt `OperatorAuthTags.error("tooWeak")` / `error("blocklisted")` → `remote.authStep.error.<cause>`; der Dev-Render-Test assertet genau diese. **Meine 3 Docs** (`-render-oracle`, `-tags`, `-design-qa-checklist`) nennen `remote.authStep.enrollError.<cause>`.
- **Wirkung:** Tester2s Render-QA läuft gg. mein Render-Oracle → würde **nicht-existente Tags** asserten (False-Failures).
- **Honesty:** H1 (Setup ≠ Auth) bleibt **gewahrt** — die Ursachen-Namen (`tooWeak`/`blocklisted`) sind distinkt und kommen nur aus dem Setup; nur der **Namespace-String** ist ein statt zwei. Die Build-Wahl ist vertretbar.
- **Fix (meine Lane, docs-only):** meine 3 Docs auf `error.<cause>` reconcilen (Code+Test = Source of Truth für das, was Tester assertet). **Kein** Dev-Rebuild. `-tags.md` ist QA-geteilter Contract (CYP-7) → ich **halte die Edit bis zum PO-Go** (nicht unilateral am geteilten Contract), führe sie auf Signal sofort aus.

### #2 — [Medium · Honesty/Intent] Diceware-Default nicht vor-generiert → der „starke One-Click-Default" ist initial verborgen
- **Ist:** `SetPassphraseStep` startet `suggestion = null`. Der Reveal + der `primary`-Button „Diese Passphrase verwenden" erscheinen **erst nach** Klick auf „Andere vorschlagen". Initial gibt's **keine** angezeigte Passphrase, nur das Label + „Andere vorschlagen".
- **Wirkung:** widerspricht der ratifizierten **U1/HG2** (PO `1526525087…`: „mach den Diceware-Vorschlag zum STARKEN One-Click-Default … prominent als empfohlene Default"). Accept-by-Click ist real **zwei** Klicks; „Andere vorschlagen" ist für die **erste** Generierung fehlbenannt (es gibt noch kein „anderes").
- **Fix (Dev, klein):** Vorschlag **beim Betreten vor-generieren** (`LaunchedEffect(Unit){ suggestion = viewModel.suggestPassphrase() }`) → Diceware + „Diese Passphrase verwenden" sind der **Default-Zustand**; „Andere vorschlagen" regeneriert dann korrekt.

### #3 — [Medium · Honesty/Copy] Cancel-Buttons mit `remote_pop_enroll_type_own` fehlbenannt
- **Ist:** beide Cancel-`OutlinedButton` (Auth `cancelPassphrase` + Enroll `cancelEnroll`) tragen `remote_pop_enroll_type_own` = „Eigene Passphrase eingeben"/„Type your own passphrase". Ein Cancel-Control liest sich als „Passphrase eingeben" — **misrepräsentiert die Aktion**. Auf dem Prompt-Screen steht es zudem verwechselbar nah am Submit („App-Passphrase eingeben" vs „Eigene Passphrase eingeben"); auf dem Enroll-Screen erscheint derselbe String **doppelt** (korrekt als type-your-own-Header, falsch als Cancel).
- **Fix (Dev, winzig):** beide Cancels auf einen **neutralen Cancel-String** relabeln. **Existiert schon zum Reuse:** `acl_cancel`/`agent_cancel` = „Abbrechen"/„Cancel" — oder ein neuer `remote_connect_cancel`. Key liefere ich auf Wunsch.

### #4 — [Low-Med · Honesty] `UvCoverageLine` unbedingt auf dem Auth-Prompt (auch bei Einzel-Hub)
- **Ist:** die 1-UV-für-N-Zeile „Eine Bestätigung autorisiert die Hubs … — nicht jeden einzeln" rendert **unbedingt** in `PassphrasePromptStep`, auch bei einem Single-Hub-Connect (N=1).
- **Wirkung:** widerspricht meinem ratifizierten **C1** („present ⇔ Fensterung greift real / N>1 — kein Phantom bei Einzel-Hub"). „nicht jeden einzeln" impliziert Mehrzahl; bei N=1 leicht über-versprochen. (Copy ist gehedged „die du **jetzt** öffnest" → grenzwertig, nicht grob.)
- **Fix (Dev):** die Zeile an **reale Fensterung** koppeln (N>1 / caching-aktiv) — braucht die Anzahl im State.

### #5 — [Low · Fidelity] DicewareReveal-Typo/Container-Drift vs §1a
- **Ist:** die Wörter rendern `bodyMedium`, **nicht-monospace**; der Reveal-Text hat `surfaceVariant`-bg bei **8dp** Radius / 12dp Padding, **kein** `outline`-Rahmen; der ganze §1a-„Karten"-Block ist nicht als umrandete Karte gestylt.
- **Soll (§1a):** Wörter `bodyLarge` **monospace** (Glyph-distinkt fürs Abschreiben einer Passphrase — `l/1/I`, `O/0`), Karte `surfaceVariant`+`outline` 1dp+**12dp** Radius+**16dp** Padding.
- **Fix (Dev, kosmetisch):** Wörter → `bodyLarge` monospace; optional die Karten-Umrandung. Rein visuell, kein Honesty-Impact.

### #6 — [Low/INFO · a11y] Enroll-Spinner-a11y-Label = „App-Passphrase festlegen"
- **Ist:** der `CircularProgressIndicator` (enrolling) announced polite `remote_pop_enroll_passphrase` („Set App-Passphrase") statt eines Fortschritt-Strings.
- **Fix (Dev/UIUX2):** einen „wird eingerichtet …"-String announcen (Reuse `remote_connect_authenticating` „Operator wird bestätigt …" möglich). Minor SR-Genauigkeit.

### #7 — [INFO] Dual-Key-Namespace im Präsentations-Modell (kein User-Impact)
- `EnrollStrengthUi.messageKey` trägt logische Labels `enroll_strength_ok`/`enroll_error_too_weak`/`enroll_error_blocklisted` — **keine** Resource-Keys; das Composable rendert die echten `remote_pop_*`-Keys. Bedeutungs-konsistent; der `messageKey` ist ein Modell-/Test-Label. **Nur Notiz** (ein künftiger Leser könnte sie für Resource-Keys halten).

---

## Follow-up-Ticket-Vorschlag (PO)
| # | Severity | Titel | Lane |
|---|---|---|---|
| #1 | Med (Tester-krit.) | Doc-Reconcile `enrollError.*`→`error.*` (Render-Oracle/Tags/Design-QA) | UIUX (docs, PO-Go) |
| #2 | Med | Diceware-Default vor-generieren (One-Click-Default sichtbar) | Dev |
| #3 | Med | Cancel-Buttons relabeln (neutraler Cancel-String) | Dev (+UIUX-Key) |
| #4 | Low-Med | UvCoverageLine an reale N>1-Fensterung koppeln | Dev |
| #5 | Low | DicewareReveal bodyLarge-monospace + §1a-Karte | Dev |
| #6 | Low | Enroll-Spinner-a11y-Fortschritt-String | Dev/UIUX2 |

## Self-Validation
- Beide Achsen gefahren, jede gg. den **gebauten** Code @ `a3730297` (nicht gg. Annahme) — file:symbol verankert.
- **PASS-Substanz zuerst**, dann Befunde **priorisiert** (Severity + konkreter Fix + Lane), wie vom Coordinator gefordert.
- **#1 nicht unilateral** am QA-geteilten Tag-Contract gefixt → PO-Go abgewartet (Shared-Contract-Koordination).
- Kein Bau, keine develop-Berührung; docs-only auf `feature/CYP-542-uv-ui-spec`.
