# Remote User-Verification (UV) Flow — Copy/Keys-Delta (CYP-542, Phase A/B1, Epic CYP-427)

> Owner: UIUX-Designer · Story CYP-542 (Epic CYP-427) · Stand 2026-07-14 · **Dev-AC (copy-paste-fertig)**.
> Begleit-Specs: `remote-uv-flow-ux-spec.md`, `remote-uv-flow-tags.md`. **Rein Doku/Copy, kein Code.**
> Gegroundet READ-ONLY gg. develop `eb705236`. Konvention: Underscore-Realkeys, `%1$s` positional, **DE = Default
> (`values/`)** + **EN-Parität (`values-en/`)**. Argument-Anzahl DE = EN identisch.
>
> **Anti-Duplikat:** Der Löwenanteil der UV-Copy ist bereits FROZEN unter `remote_pop_*` (CYP-460,
> `desktop-remote-operator-keys.md`). Diese Delta fügt **nur** die vier fehlenden Nähte hinzu (Enroll-Bestätigung,
> 1-UV-für-N-Hinweis, Biometrie-Angebot). **Kein `remote_pop_*`-Key wird umgeschrieben.**

---

## Net-new Copy (6 Realkeys + 1 a11y)

### Δ1 — Enroll: „PIN wählen **+ bestätigen**" (füllt die zweifache Eingabe aus CYP-460 §5.2, heute nicht gebaut)
| Key | DE (Default) | EN | Rolle |
|---|---|---|---|
| `remote_pop_enroll_confirm` **(NET-NEW, 0 Arg)** | App-PIN bestätigen | Confirm your app PIN | Label des **Bestätigungs-Felds** (`ENROLL_PIN_CONFIRM`). Twin zu `remote_pop_enroll_pin` („App-PIN festlegen"). |
| `remote_pop_enroll_mismatch` **(NET-NEW, 0 Arg)** | PINs stimmen nicht überein — bitte erneut eingeben. | PINs don't match — please re-enter. | `enrollError.mismatch`. Fehler-Ton (nicht `errorContainer`), retryable, Feld aktiv. |
| `remote_pop_enroll_too_short` **(NET-NEW, 1 Arg)** | PIN zu kurz — mindestens %1$s Zeichen. | PIN too short — at least %1$s characters. | `enrollError.tooShort`. `%1$s` = Threat-Model-Mindestlänge (**nicht** in der Copy hartkodiert; Dev/Threat-Model liefert den Wert). |

### Δ2 — 1-UV-für-N: eine Bestätigung, N Tunnel (heute stumm)
| Key | DE (Default) | EN | Rolle |
|---|---|---|---|
| `remote_pop_uv_coverage` **(NET-NEW, 0 Arg)** | Eine Bestätigung autorisiert die Hubs, die du jetzt öffnest — nicht jeden einzeln. | One confirmation authorizes the hubs you open now — not each one separately. | Neutraler Coverage-Hinweis (`UV_COVERAGE`). **Ehrlich begrenzt:** „die du **jetzt** öffnest", nie „diese Sitzung für immer" (Fenster ist begrenzt). Kein Erfolgs-Grün. |
| `a11y_remote_pop_uv_coverage` **(NET-NEW, 0 Arg)** | Hinweis: eine Bestätigung autorisiert die jetzt geöffneten Hubs. | Note: one confirmation authorizes the hubs opened now. | a11y-Beschreibung des Coverage-Hinweises. |

### Δ3 — Platform-Authenticator als Enhancement-Angebot (Opt-in, PIN bleibt Rückfall)
| Key | DE (Default) | EN | Rolle |
|---|---|---|---|
| `remote_pop_biometric_offer` **(NET-NEW, 1 Arg)** | %1$s verfügbar — als schnellere Bestätigung einrichten? | %1$s available — set it up for faster confirmation? | Opt-in-Angebot (`BIOMETRIC_OFFER`). `%1$s` = Authenticator-Name (Touch-ID/Windows Hello). Enable/Überspringen = Dialog-Chrome (keine Button-Keys frozen, CYP-460-Konvention). |
| `remote_pop_biometric_offer_fallback` **(NET-NEW, 0 Arg)** | Deine App-PIN bleibt als Rückfall. | Your app PIN stays as a fallback. | Ehrlichkeits-Zusatz zum Angebot: Biometrie ist **Enhancement**, ersetzt die PIN nie. |

---

## Reuse — bestehende FROZEN `remote_pop_*` / `remote_connect_*` (CYP-460/CYP-429, NICHT anfassen)
| Key | DE | Rolle hier |
|---|---|---|
| `remote_pop_path_hint` (1 Arg) | Bestätigung über %1$s | Pfad-Header (PIN vs Biometrie, H1) — auch Enroll. |
| `remote_pop_pin_title` / `remote_pop_pin_body` | App-PIN eingeben / Bestätige mit deiner App-PIN … | Setz-Feld-Label + Body (reused fürs Enroll-Setz-Feld). |
| `remote_pop_enroll_title` / `remote_pop_enroll_pin` | Dieses Gerät einrichten / App-PIN festlegen | Enroll-Header + Setz-Feld (Feld 1). |
| `remote_pop_enroll_session_only` | Gilt nur für diese Sitzung — sicherer Geräte-Schlüsselbund folgt. | Session-only-Downgrade (WARN-amber, `DEVICE_SECURE` named-not-built, Q5). |
| `remote_pop_biometric_title` (1 Arg) / `remote_pop_biometric_failed` | Mit %1$s bestätigen / Biometrie fehlgeschlagen — nutze deine App-PIN. | reale Biometrie-Aufforderung + Fallback (nachdem Angebot angenommen). |
| `remote_pop_wrong_pin` (1 Arg) | Falsche PIN. Noch %1$s Versuche. | Fehlversuch-Zähler (`ATTEMPTS`, rate-limit-aware, enumeration-frei). |
| `remote_pop_locked` (1 Arg) | Zu viele Fehlversuche. Erneut in %1$s. | temporäre Sperre + Cooldown (`LOCKED_OUT`). |
| `remote_pop_keystore_unavailable` | Schlüsselbund nicht verfügbar. | Fail-closed (kein Fake-Erfolg). |
| `remote_pop_rejected` | Vom Hub abgelehnt. Bitte neu anmelden. | **terminal** (Hub-Reject, errorContainer). |
| `remote_connect_authenticating` | Operator wird bestätigt … | der `AUTHENTICATING`-Slot, in den der Prompt mountet. |
| `remote_connect_uv_failed` | PIN falsch oder abgebrochen — bitte erneut versuchen. | UV-Fehl an der Connect-Fläche (retryable). |
| `remote_connect_device_not_enrolled` (+ a11y) | Dieses Gerät ist noch nicht eingerichtet — richte es ein, um fortzufahren. | route → Enroll (kein „falsche PIN", Enumeration-Guard). |

---

## Honesty-Anker (für §-QA)
- **HA — Enroll gated:** kein PIN gesetzt, solange `remote_pop_enroll_confirm` ≠ Setz-Feld **oder** Länge < Min;
  `_mismatch`/`_too_short` sind retryable (Fehler-Ton), **nie** stiller Commit einer unbestätigten Eingabe.
- **HB — Coverage ehrlich begrenzt:** `remote_pop_uv_coverage` verspricht **die jetzt geöffneten Hubs**, nicht die ganze
  Sitzung; nach Fenster-Ablauf **neuer** Prompt (nie stille Re-Auth, nie Fake-Coverage).
- **HC — Biometrie = Enhancement:** `remote_pop_biometric_offer_fallback` sagt explizit, dass die PIN Rückfall bleibt;
  `remote_pop_path_hint` nennt immer den echten Pfad; Biometrie-Fehl → `remote_pop_biometric_failed` + PIN sichtbar.
- **HD — Fail-closed, kein Fake-Erfolg:** `remote_pop_keystore_unavailable` (bzw. „UV unavailable") **blockt** Connect,
  gewährt nie still; `granted` zeigt **kein** Erfolgs-Grün.
- **HE — kein Enumeration:** un-enrolled → `remote_connect_device_not_enrolled` (Enroll), nie „falsche PIN"; Zähler/Cooldown
  verraten nur Rate-Limit-State, nie Konto-Existenz. `%1$s` ist **nie** ein PIN/Fingerprint-Wert (nur Count/Wait/Name).

## Self-Validation
- **Net-new: 6 Realkeys** (`remote_pop_enroll_confirm` [0], `_enroll_mismatch` [0], `_enroll_too_short` [1],
  `_uv_coverage` [0], `_biometric_offer` [1], `_biometric_offer_fallback` [0]) **+ 1 a11y**
  (`a11y_remote_pop_uv_coverage` [0]). Alle **DE+EN paritätisch** (Argument-Anzahl identisch).
- **0 Retext, 0 Löschung** — reine Ergänzung; kein `remote_pop_*`/`remote_connect_*` wird angefasst.
- **0 Kollision @ `eb705236`** (grep-verifiziert: keiner der 7 Keys existiert in `values/` oder `values-en/`).
- **Kein content-tragender/sensibler Klartext;** `%1$s` nur Count/Wait/Authenticator-Name.
- **Dev foldet in `OperatorAuthDialog`** (Enroll-Feldpaar + Coverage-Zeile + Biometrie-Angebot) — Copy hier ist der frozen AC.
