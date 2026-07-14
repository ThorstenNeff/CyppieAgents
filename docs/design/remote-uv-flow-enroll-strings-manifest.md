# CYP-542 Enroll/UV Copy — AUTHORITATIVE Strings-Manifest (pull 1:1, kein Ableiten)

> Owner: UIUX-Designer · Story CYP-542 (Phase A/B1) · Stand 2026-07-14 · **Dev-kritischer Pfad** (PO `1526561777…`).
> **Die eine autoritative Quelle** für die (d)-Enroll/UV-Copy — Dev **kopiert 1:1**, leitet nichts ab (= kein Drift).
> Deckungsgleich mit `remote-uv-flow-keys.md` (Design-Delta) + `remote-uv-flow-tags.md`; **dieses File ist paste-ready**.
>
> **Ziel-Dateien:** DE = `app/shared/src/commonMain/composeResources/values/strings.xml` (Default) · EN =
> `…/values-en/strings.xml`. **22 Realkeys + 2 a11y = 24 net-new** Strings, alle greenfield @ `eb705236` (0-Kollision).
> **Konventionen (Haus, verifiziert):** Apostroph **roh** (`you'll`/`don't`/`it's`, **nicht** `\'`); Em-Dash `—` roh;
> deutsche Anführung `„…"` roh; Format-Arg `%1$s` roh. `&` (keins hier) wäre `&amp;`.
> **Args:** nur `remote_pop_enroll_too_short` (%1$s = Min-Länge) und `remote_pop_biometric_offer` (%1$s = Authenticator-Name)
> tragen **1** Arg; alle anderen **0**. DE- und EN-Arg-Anzahl identisch.

## DE — nach `values/strings.xml` (Default)
```xml
<!-- CYP-542 (Phase A/B1) Remote UV/Enroll — net-new. UIUX-frozen, pull 1:1 (remote-uv-flow-enroll-strings-manifest.md). -->
<string name="remote_pop_passphrase_title">App-Passphrase eingeben</string>
<string name="remote_pop_passphrase_body">Bestätige mit deiner App-Passphrase, um remote auf deine Hubs zuzugreifen.</string>
<string name="remote_pop_enroll_passphrase">App-Passphrase festlegen</string>
<string name="remote_pop_enroll_passphrase_confirm">App-Passphrase bestätigen</string>
<string name="remote_pop_enroll_suggested">Empfohlen — 6 zufällige Wörter</string>
<string name="remote_pop_enroll_suggested_save">Notiere sie sicher — du brauchst sie bei jeder Anmeldung.</string>
<string name="remote_pop_enroll_clipboard_notice">In die Zwischenablage kopiert. Leere die Zwischenablage, nachdem du die Passphrase gespeichert hast.</string>
<string name="a11y_remote_pop_enroll_clipboard_notice">In die Zwischenablage kopiert — Zwischenablage nach dem Speichern leeren.</string>
<string name="remote_pop_enroll_suggest_use">Diese Passphrase verwenden</string>
<string name="remote_pop_enroll_suggest">Andere vorschlagen</string>
<string name="remote_pop_enroll_type_own">Eigene Passphrase eingeben</string>
<string name="remote_pop_enroll_strength_hint">6 zufällige Wörter oder mindestens 12 Zeichen.</string>
<string name="remote_pop_enroll_too_weak">Passphrase zu schwach — 6 zufällige Wörter oder 12+ Zeichen.</string>
<string name="remote_pop_strength_weak">Schwach</string>
<string name="remote_pop_strength_fair">Mittel</string>
<string name="remote_pop_strength_strong">Stark</string>
<string name="remote_pop_enroll_confirm">App-PIN bestätigen</string>
<string name="remote_pop_enroll_mismatch">Eingaben stimmen nicht überein — bitte erneut eingeben.</string>
<string name="remote_pop_enroll_too_short">PIN zu kurz — mindestens %1$s Zeichen.</string>
<string name="remote_pop_enroll_blocklisted">Diese Passphrase ist zu verbreitet — sie steht auf einer Liste bekannt-schwacher Passphrasen. Bitte eine andere.</string>
<string name="remote_pop_uv_coverage">Eine Bestätigung autorisiert die Hubs, die du jetzt öffnest — nicht jeden einzeln.</string>
<string name="a11y_remote_pop_uv_coverage">Hinweis: eine Bestätigung autorisiert die jetzt geöffneten Hubs.</string>
<string name="remote_pop_biometric_offer">%1$s verfügbar — als schnellere Bestätigung einrichten?</string>
<string name="remote_pop_biometric_offer_fallback">Deine App-PIN/Passphrase bleibt als Rückfall.</string>
```

## EN — nach `values-en/strings.xml`
```xml
<!-- CYP-542 (Phase A/B1) Remote UV/Enroll — net-new. UIUX-frozen, pull 1:1. -->
<string name="remote_pop_passphrase_title">Enter your app passphrase</string>
<string name="remote_pop_passphrase_body">Confirm with your app passphrase to access your hubs remotely.</string>
<string name="remote_pop_enroll_passphrase">Set an app passphrase</string>
<string name="remote_pop_enroll_passphrase_confirm">Confirm your app passphrase</string>
<string name="remote_pop_enroll_suggested">Recommended — 6 random words</string>
<string name="remote_pop_enroll_suggested_save">Save it securely — you'll need it every time you sign in.</string>
<string name="remote_pop_enroll_clipboard_notice">Copied to clipboard. Clear your clipboard after you've saved the passphrase.</string>
<string name="a11y_remote_pop_enroll_clipboard_notice">Copied to clipboard — clear your clipboard after saving.</string>
<string name="remote_pop_enroll_suggest_use">Use this passphrase</string>
<string name="remote_pop_enroll_suggest">Suggest another</string>
<string name="remote_pop_enroll_type_own">Type your own passphrase</string>
<string name="remote_pop_enroll_strength_hint">6 random words or at least 12 characters.</string>
<string name="remote_pop_enroll_too_weak">Passphrase too weak — 6 random words or 12+ characters.</string>
<string name="remote_pop_strength_weak">Weak</string>
<string name="remote_pop_strength_fair">Fair</string>
<string name="remote_pop_strength_strong">Strong</string>
<string name="remote_pop_enroll_confirm">Confirm your app PIN</string>
<string name="remote_pop_enroll_mismatch">Entries don't match — please re-enter.</string>
<string name="remote_pop_enroll_too_short">PIN too short — at least %1$s characters.</string>
<string name="remote_pop_enroll_blocklisted">This passphrase is too common — it's on a list of known-weak passphrases. Please choose another.</string>
<string name="remote_pop_uv_coverage">One confirmation authorizes the hubs you open now — not each one separately.</string>
<string name="a11y_remote_pop_uv_coverage">Note: one confirmation authorizes the hubs opened now.</string>
<string name="remote_pop_biometric_offer">%1$s available — set it up for faster confirmation?</string>
<string name="remote_pop_biometric_offer_fallback">Your app PIN/passphrase stays as a fallback.</string>
```

## Key → Tag → Element (Cross-Ref, für die Verdrahtung)
| Realkey | Tag (`remote.authStep.*`) | Element (visual-spec) |
|---|---|---|
| `remote_pop_enroll_suggested` | `enrollSuggested` | §1a Diceware-Default (Label) |
| `remote_pop_enroll_suggested_save` | (im `enrollSuggested`-Block) | §1a Save-Hinweis |
| `remote_pop_enroll_clipboard_notice` (+a11y) | `enrollClipboardNotice` (Icon `enrollCopy`) | §1a Clipboard-Egress-Disclosure |
| `remote_pop_enroll_suggest_use` | (`enrollSuggested`, One-Click) | §1a „Diese verwenden" |
| `remote_pop_enroll_suggest` | `enrollSuggest` | §1a Regenerate |
| `remote_pop_enroll_type_own` | `enrollTypeOwn` | §1b type-your-own |
| `remote_pop_enroll_passphrase` / `_confirm` | `enrollPinSet`(reuse) / `enrollPinConfirm` | §1b Feldpaar (Passphrase) |
| `remote_pop_enroll_confirm` | `enrollPinConfirm` | §1b Feldpaar (Kurz-PIN) |
| `remote_pop_enroll_strength_hint` · `_strength_{weak,fair,strong}` | `enrollStrength` | §2 Meter + Level-Labels |
| `remote_pop_enroll_too_weak` | `error.tooWeak` | §2 Verdict TOO_WEAK (WARN-Amber) |
| `remote_pop_enroll_blocklisted` | `error.blocklisted` | §2/§3 Verdict BLOCKLISTED (error-Ton) |
| `remote_pop_enroll_mismatch` · `_too_short` | `error.mismatch` / `.tooShort` | §1b Gate (`OperatorAuthTags.error(cause)`, gebaut @ `a3730297`) |
| `remote_pop_passphrase_title` / `_body` | `pinField`(reuse) | Unlock (no-hardware) |
| `remote_pop_uv_coverage` (+a11y) | `uvCoverage` | §4 Auto-Weiterlauf/1-UV-für-N |
| `remote_pop_biometric_offer` / `_fallback` | `biometricOffer` | §4.4 Enhancement-Angebot |

## Reuse — schon gebaut @ `eb705236`, **NICHT** re-adden
`remote_pop_path_hint` · `remote_pop_pin_title`/`_body` · `remote_pop_enroll_title`/`_pin` · `remote_pop_enroll_session_only` ·
`remote_pop_biometric_title`/`_failed` · `remote_pop_wrong_pin` · `remote_pop_locked` · `remote_pop_keystore_unavailable` ·
`remote_pop_rejected` · `remote_connect_authenticating` · `remote_connect_uv_failed` · `remote_connect_device_not_enrolled`(+a11y).
Diese existieren bereits im `remote_pop_*`/`remote_connect_*`-Block — Dev wired sie, addiert sie **nicht**.

## Self-Validation
- **24 Strings** (22 Realkeys + 2 a11y), DE + EN **paritätisch** (Zeilen-für-Zeile identische Key-Menge, identische Arg-Anzahl).
- **1:1 mit `remote-uv-flow-keys.md`** (verbatim übertragen, kein Reword).
- **0-Kollision @ `eb705236`** (alle 24 greenfield, grep-verifiziert).
- **Apostroph roh** (Haus-Konvention, verifiziert gg. `values-en/strings.xml`); `%1$s` roh; keine `&`.
- **Dev pullt 1:1** — dieses File ist die autoritative Quelle; bei Abweichung gilt DIESES File.
