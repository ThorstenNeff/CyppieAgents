# Remote User-Verification (UV) Flow — Copy/Keys-Delta (CYP-542, Phase A/B1, Epic CYP-427)

> Owner: UIUX-Designer · Story CYP-542 (Epic CYP-427) · Stand 2026-07-14 (rev. mit Reviewer-Crypto-Lens, PO
> `1526516045…`) · **Dev-AC (copy-paste-fertig)**.
> Begleit-Specs: `remote-uv-flow-ux-spec.md`, `remote-uv-flow-tags.md`. **Rein Doku/Copy, kein Code.**
> Gegroundet READ-ONLY gg. develop `eb705236`. Konvention: Underscore-Realkeys, `%1$s` positional, **DE = Default
> (`values/`)** + **EN-Parität (`values-en/`)**. Argument-Anzahl DE = EN identisch.
>
> **Anti-Duplikat:** Der Löwenanteil der UV-Copy ist bereits FROZEN unter `remote_pop_*` (CYP-460). Diese Delta fügt **nur**
> die fehlenden Nähte hinzu. **Kein `remote_pop_*`-Key wird umgeschrieben** — die frozen „App-PIN"-Copy bleibt gültig für
> den **hardware-backed Kurz-PIN-Pfad**; die neue Passphrase-Copy bedient den **no-hardware Boden**.

## Credential-Split (Crypto-Lens, PO/Reviewer)
- **no-hardware** ⇒ **App-Passphrase ≥64 bit** — **generierte 6-Wort-Diceware als prominenter empfohlener One-Click-Default
  (HG2, by-construction stark)**, type-your-own sekundär (Strength-Meter + ≥64-bit-Floor-Gate).
- **hardware-backed** (Platform-Authenticator/Enclave präsent) ⇒ **Kurz-PIN** (reuse frozen `remote_pop_pin_*`).
Der aktive Pfad benennt sich ehrlich via `remote_pop_path_hint` (`%1$s` = „App-Passphrase" / „App-PIN" / „Touch-ID").

---

## Net-new Copy (22 Realkeys + 2 a11y)

### Δ1a — Passphrase-Pfad (no-hardware Boden): Setup + Unlock + **generierter Diceware-One-Click-Default (HG2)** + Strength
| Key | DE (Default) | EN | Rolle |
|---|---|---|---|
| `remote_pop_passphrase_title` **(0)** | App-Passphrase eingeben | Enter your app passphrase | Unlock-Label (Feld), no-hardware. Twin zu frozen `remote_pop_pin_title`. |
| `remote_pop_passphrase_body` **(0)** | Bestätige mit deiner App-Passphrase, um remote auf deine Hubs zuzugreifen. | Confirm with your app passphrase to access your hubs remotely. | Unlock-Body. Twin zu `remote_pop_pin_body`. |
| `remote_pop_enroll_passphrase` **(0)** | App-Passphrase festlegen | Set an app passphrase | Enroll-Setz-Feld-Label. Twin zu `remote_pop_enroll_pin`. |
| `remote_pop_enroll_passphrase_confirm` **(0)** | App-Passphrase bestätigen | Confirm your app passphrase | Enroll-**Bestätigungs**-Feld (`ENROLL_PIN_CONFIRM`), Passphrase-Pfad. |
| `remote_pop_enroll_suggested` **(0)** | Empfohlen — 6 zufällige Wörter | Recommended — 6 random words | Label über der **generierten Diceware-Default-Passphrase** (`ENROLL_SUGGESTED`, prominent, One-Click, HG2). Die Wörter selbst = gerenderte Daten, kein Key. **Kein** Bit-Zahl-Literal (Wortlisten-abhängig, driftet) — die Stärke trägt der Meter/„stark". |
| `remote_pop_enroll_suggested_save` **(0)** | Notiere sie sicher — du brauchst sie bei jeder Anmeldung. | Save it securely — you'll need it every time you sign in. | **Ehrlichkeit (HG2):** der generierte Credential muss notierbar sein; kein masked-at-generation-Secret. |
| `remote_pop_enroll_clipboard_notice` **(0)** | In die Zwischenablage kopiert. Leere die Zwischenablage, nachdem du die Passphrase gespeichert hast. | Copied to clipboard. Clear your clipboard after you've saved the passphrase. | **Clipboard-Egress-Disclosure (UIUX2-Reconcile ①), neutral-informativ** (`onSurfaceVariant`, kein WARN). **Nie** „sicher kopiert"/„gelöscht" — Auto-Clear ist plattform-abhängig (best-effort Dev, nicht versprochen); Clear-Onus ehrlich beim Nutzer. Tag `ENROLL_CLIPBOARD_NOTICE`. |
| `a11y_remote_pop_enroll_clipboard_notice` **(0)** | In die Zwischenablage kopiert — Zwischenablage nach dem Speichern leeren. | Copied to clipboard — clear your clipboard after saving. | a11y der Disclosure. |
| `remote_pop_enroll_suggest_use` **(0)** | Diese Passphrase verwenden | Use this passphrase | **One-Click-Accept** des empfohlenen Defaults (Enroll-Affordanz zu `ENROLL_SUGGESTED`; Dev-Konvergenz: darf auch primärer Submit-Button sein). |
| `remote_pop_enroll_suggest` **(0)** | Andere vorschlagen | Suggest another | **Regenerate**-Affordanz (`ENROLL_SUGGEST` — neue Diceware würfeln). |
| `remote_pop_enroll_type_own` **(0)** | Eigene Passphrase eingeben | Type your own passphrase | **sekundäre** Umschaltung auf manuelle Eingabe (`ENROLL_TYPE_OWN`). |
| `remote_pop_enroll_strength_hint` **(0)** | 6 zufällige Wörter oder mindestens 12 Zeichen. | 6 random words or at least 12 characters. | Guidance neben dem Strength-Meter (`ENROLL_STRENGTH`, nur type-your-own), neutral. |
| `remote_pop_enroll_too_weak` **(0)** | Passphrase zu schwach — 6 zufällige Wörter oder 12+ Zeichen. | Passphrase too weak — 6 random words or 12+ characters. | `enrollError.tooWeak`-Gate (Entropie < ≥64 bit; type-your-own). Fehler-Ton, retryable. |
| `remote_pop_strength_weak` **(0)** | Schwach | Weak | Strength-Meter-Level (Text-Label — Farbe **nie** alleiniger Träger, WCAG 1.4.1). |
| `remote_pop_strength_fair` **(0)** | Mittel | Fair | Strength-Meter-Level. |
| `remote_pop_strength_strong` **(0)** | Stark | Strong | Strength-Meter-Level. |

### Δ1b — Kurz-PIN-Pfad (hardware-backed): Bestätigung + Gate
| Key | DE (Default) | EN | Rolle |
|---|---|---|---|
| `remote_pop_enroll_confirm` **(0)** | App-PIN bestätigen | Confirm your app PIN | Enroll-**Bestätigungs**-Feld (`ENROLL_PIN_CONFIRM`), Kurz-PIN-Pfad. |
| `remote_pop_enroll_mismatch` **(0)** | Eingaben stimmen nicht überein — bitte erneut eingeben. | Entries don't match — please re-enter. | `enrollError.mismatch`. **Credential-neutral** (PIN *und* Passphrase). Fehler-Ton, retryable. |
| `remote_pop_enroll_too_short` **(1)** | PIN zu kurz — mindestens %1$s Zeichen. | PIN too short — at least %1$s characters. | `enrollError.tooShort`. `%1$s` = Threat-Model-Min (**nicht** hartkodiert). |
| `remote_pop_enroll_blocklisted` **(0)** | Diese Passphrase ist zu verbreitet — sie steht auf einer Liste bekannt-schwacher Passphrasen. Bitte eine andere. | This passphrase is too common — it's on a list of known-weak passphrases. Please choose another. | `enrollError.blocklisted` (**distinkt** von `tooWeak`, PO-Ruling G1). Sagt ehrlich das *warum*. **Enroll-Pfad**, kein Enumeration-Oracle (Nutzer setzt sein eigenes Secret — CYP-543-Neutralität betrifft nur den Auth-Pfad). |

### Δ2 — 1-UV-für-N: eine Bestätigung, N Tunnel
| Key | DE (Default) | EN | Rolle |
|---|---|---|---|
| `remote_pop_uv_coverage` **(0)** | Eine Bestätigung autorisiert die Hubs, die du jetzt öffnest — nicht jeden einzeln. | One confirmation authorizes the hubs you open now — not each one separately. | Neutraler Coverage-Hinweis (`UV_COVERAGE`), ehrlich begrenzt. Kein Erfolgs-Grün. |
| `a11y_remote_pop_uv_coverage` **(0)** | Hinweis: eine Bestätigung autorisiert die jetzt geöffneten Hubs. | Note: one confirmation authorizes the hubs opened now. | a11y-Beschreibung. |

### Δ3 — Platform-Authenticator als Enhancement-Angebot (Opt-in, Credential bleibt Rückfall)
| Key | DE (Default) | EN | Rolle |
|---|---|---|---|
| `remote_pop_biometric_offer` **(1)** | %1$s verfügbar — als schnellere Bestätigung einrichten? | %1$s available — set it up for faster confirmation? | Opt-in-Angebot (`BIOMETRIC_OFFER`). `%1$s` = Authenticator-Name. |
| `remote_pop_biometric_offer_fallback` **(0)** | Deine App-PIN/Passphrase bleibt als Rückfall. | Your app PIN/passphrase stays as a fallback. | Ehrlichkeit: Biometrie ist Enhancement, ersetzt das Credential nie. |

---

## Reuse — bestehende FROZEN `remote_pop_*` / `remote_connect_*` (CYP-460/CYP-429, NICHT anfassen)
| Key | DE | Rolle hier |
|---|---|---|
| `remote_pop_path_hint` (1) | Bestätigung über %1$s | Pfad-Header — `%1$s` benennt den echten Pfad (Passphrase/PIN/Biometrie, H1). |
| `remote_pop_pin_title` / `remote_pop_pin_body` | App-PIN eingeben / Bestätige mit deiner App-PIN … | **Kurz-PIN-Pfad** (hardware-backed) Unlock. |
| `remote_pop_enroll_title` / `remote_pop_enroll_pin` | Dieses Gerät einrichten / App-PIN festlegen | Enroll-Header + Kurz-PIN-Setz-Feld. |
| `remote_pop_enroll_session_only` | Gilt nur für diese Sitzung — sicherer Geräte-Schlüsselbund folgt. | Session-only-Downgrade (WARN-amber, Q5). |
| `remote_pop_biometric_title` (1) / `remote_pop_biometric_failed` | Mit %1$s bestätigen / Biometrie fehlgeschlagen — nutze deine App-PIN. | reale Biometrie-Aufforderung + Fallback. |
| `remote_pop_wrong_pin` (1) | Falsche PIN. Noch %1$s Versuche. | Fehlversuch-Zähler (`ATTEMPTS`, enumeration-frei). |
| `remote_pop_locked` (1) | Zu viele Fehlversuche. Erneut in %1$s. | temporäre Sperre + Cooldown (`LOCKED_OUT`). |
| `remote_pop_keystore_unavailable` | Schlüsselbund nicht verfügbar. | Fail-closed (kein Fake-Erfolg). |
| `remote_pop_rejected` | Vom Hub abgelehnt. Bitte neu anmelden. | **terminal** (Hub-Reject, errorContainer). |
| `remote_connect_authenticating` | Operator wird bestätigt … | der `AUTHENTICATING`-Slot, in den der Prompt mountet. |
| `remote_connect_uv_failed` | PIN falsch oder abgebrochen — bitte erneut versuchen. | UV-Fehl an der Connect-Fläche (retryable). |
| `remote_connect_device_not_enrolled` (+ a11y) | Dieses Gerät ist noch nicht eingerichtet — richte es ein, um fortzufahren. | route → Enroll (kein „falsche PIN", Enumeration-Guard). |

> **Hinweis (Dev):** `remote_pop_wrong_pin`/`_locked` sind frozen „PIN"-Copy. Auf dem no-hardware-Passphrase-Pfad ist der
> Wortlaut leicht schief (Fehl-Versuch einer Passphrase). Das ist **out-of-scope für CYP-542** (kein Retext frozener Keys);
> falls der Passphrase-Pfad den Zähler-Wortlaut braucht, tickete ich einen kleinen Copy-Follow-up — melde ich dem PO,
> nicht raten.

## Honesty-Anker (für §-QA)
- **HG — Credential-Stärke matcht die Absicherung:** no-hardware ⇒ **Passphrase + Strength-Meter + `too_weak`-Gate**;
  Kurz-PIN nur hardware-backed. Die UI bietet nie einen kurzen, offline-brute-forcebaren PIN an, wo keine Hardware ihn
  schützt. `remote_pop_path_hint` benennt ehrlich (Passphrase vs PIN vs Biometrie).
- **HG2 — starker Common-Path by-construction:** `remote_pop_enroll_suggested` (generierte 6-Wort-Diceware) ist der
  **prominente One-Click-Default**; type-your-own ist sekundär (`_type_own`), Meter + `_too_weak`-Floor bleiben. Der
  generierte Credential wird **angezeigt + notierbar** (`_suggested_save`) — nie masked-at-generation. Bit-Zahl **nicht**
  in der Copy (wortlisten-abhängig, driftet).
- **HA — Enroll gated:** kein Credential gesetzt bei Mismatch / zu-kurz / zu-schwach; retryable (Fehler-Ton), nie stiller
  Commit.
- **HB — Coverage ehrlich begrenzt:** „die jetzt geöffneten Hubs", nicht die ganze Sitzung; nach Ablauf neuer Prompt.
- **HC — Biometrie = Enhancement:** `remote_pop_biometric_offer_fallback` sagt, das Credential bleibt Rückfall.
- **HD — Fail-closed, kein Fake-Erfolg:** `keystore_unavailable`/„unavailable" blockt Connect; `granted` ohne Erfolgs-Grün.
- **HE — kein Enumeration:** un-enrolled → Enroll, nie „falsche PIN"; `%1$s` nie ein Credential-Wert (nur Count/Wait/Name).
- **WCAG 1.4.1:** die Strength-Meter-Level tragen Text-Labels (`_weak`/`_fair`/`_strong`) — Farbe nie alleiniger Träger.

## Self-Validation
- **Net-new: 22 Realkeys + 2 a11y** — Passphrase-Pfad 15 (inkl. 4 HG2-Diceware-Default + `_clipboard_notice` UIUX2-①),
  Kurz-PIN-Pfad 4 (inkl. `_blocklisted`, G1), Coverage 1(+a11y), Biometrie 2. a11y: `_uv_coverage` + `_clipboard_notice`.
  Args: `enroll_too_short` (1), `biometric_offer` (1), `path_hint`-Reuse (1); Rest 0. Alle **DE+EN paritätisch**.
- **0 Retext frozener Keys, 0 Löschung** — reine Ergänzung an CYP-542-eigenen (noch nicht gebauten) Keys; kein
  gebautes/frozen `remote_pop_*`/`remote_connect_*` wird angefasst.
- **0 Kollision @ `eb705236`** (grep-verifiziert: keiner der 24 Keys existiert in `values/` oder `values-en/`).
- **Kein content-tragender/sensibler Klartext;** `%1$s` nur Count/Wait/Authenticator-Name/Min-Länge.
- **Dev foldet in `OperatorAuthDialog`** (Passphrase/PIN-Feldpaar capability-conditional + Strength-Meter + Diceware +
  Coverage-Zeile + Biometrie-Angebot) — Copy hier ist der frozen AC.
