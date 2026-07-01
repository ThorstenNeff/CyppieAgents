# End-User-Authentifizierung — i18n-Keys (CYP-176)

> Owner: UIUX-Designer · Epic CYP-176 · Stand 2026-07-01 · Status: Vorschlag — **Key-Sync-Punkt** (PO koordiniert CYP-7 zwischen UIUX/Dev/Tester)
> Konvention (verifiziert gg. `app/shared/src/commonMain/composeResources/values/strings.xml`, develop `09def21`):
> **Underscore-Realkeys** (keine Punkte), positionsbasierte Argumente `%1$s`. **DE = Default** (`values/`), **EN**
> (`values-en/`). Parität Pflicht. Modul `:app:shared` compose.resources → **Shared-Key-Sync mit der Impl timen**
> (Keys erst landen, wenn Dev das Auth-Modul konsumiert, sonst bricht ein Shared-Check).
> **Prefix `auth_` + `a11y_auth_` sind NEU** — 0 Kollision gg. bestehende Keys (greenfield, keine `auth_`-Keys im
> Katalog). **Enumeration-sichere Wortlaute PO-eingefroren 2026-07-01** (§-Ask 1): neutral, nie existenz-verratend.

## Neue Keys

### Gemeinsam (Felder · Buttons · Links · Bootstrap)
| Key | DE | EN |
|---|---|---|
| `auth_email_label` | E-Mail | Email |
| `auth_password_label` | Passwort | Password |
| `auth_password_confirm_label` | Passwort bestätigen | Confirm password |
| `auth_submit_login` | Anmelden | Sign in |
| `auth_submit_register` | Konto erstellen | Create account |
| `auth_submit_forgot` | Link senden | Send link |
| `auth_submit_reset` | Passwort ändern | Change password |
| `auth_submitting` | Wird verarbeitet… | Working… |
| `auth_link_to_register` | Konto erstellen | Create an account |
| `auth_link_to_login` | Zurück zur Anmeldung | Back to sign in |
| `auth_link_forgot` | Passwort vergessen? | Forgot password? |
| `auth_or_divider` | oder | or |
| `auth_loading` | Anmeldung wird geprüft… | Checking sign-in… |

### Login
| Key | DE | EN |
|---|---|---|
| `auth_login_title` | Anmelden | Sign in |
| `auth_login_error_generic` | E-Mail oder Passwort ist falsch. | Email or password is incorrect. |

> `auth_login_error_generic` ist **bewusst generisch** (§-Ask 1): unterscheidet nicht „kein Konto" vs. „falsches Passwort".

### Rate-Limit (gemeinsam für Login / Register / Forgot / Resend)
| Key | DE | EN |
|---|---|---|
| `auth_rate_limited` | Zu viele Versuche. Bitte in einigen Minuten erneut versuchen. | Too many attempts. Please try again in a few minutes. |
| `auth_rate_limited_wait` | Zu viele Versuche. Bitte in %1$s erneut versuchen. | Too many attempts. Please try again in %1$s. |

> **429 ehrlich, nie Fake-„gesendet".** `%1$s` = verbleibende Wartezeit, nur wenn der Server `retryAfter` liefert.

### Registrieren
| Key | DE | EN |
|---|---|---|
| `auth_register_title` | Konto erstellen | Create account |
| `auth_register_password_rule` | Mindestens 8 Zeichen. | At least 8 characters. |
| `auth_register_email_invalid` | Bitte eine gültige E-Mail-Adresse eingeben. | Please enter a valid email address. |
| `auth_register_pw_mismatch` | Die Passwörter stimmen nicht überein. | The passwords do not match. |
| `auth_register_error_generic` | Registrierung fehlgeschlagen. Bitte erneut versuchen. | Registration failed. Please try again. |

> `auth_register_password_rule` = **ehrliche** Mindestanforderung (kein Fake-Stärke-Meter). Bei Server-Ablehnung
> **generisch** (`auth_register_error_generic`), nie „E-Mail bereits vergeben" (§-Ask 1).

### E-Mail bestätigen (Pending = hartes Gate / Success)
| Key | DE | EN |
|---|---|---|
| `auth_verify_title` | E-Mail bestätigen | Confirm your email |
| `auth_verify_pending_body` | Falls ein Konto zu %1$s existiert, ist eine Bestätigungs-Mail unterwegs. Öffne den Link darin, um fortzufahren. | If an account exists for %1$s, a confirmation email is on its way. Open the link in it to continue. |
| `auth_verify_gate_hint` | Bitte bestätige deine E-Mail, um fortzufahren. | Please confirm your email to continue. |
| `auth_verify_resend` | Mail erneut senden | Resend email |
| `auth_verify_resend_done` | Falls nötig, ist eine neue Mail unterwegs. | If needed, a new email is on its way. |
| `auth_verify_success_title` | E-Mail bestätigt | Email confirmed |
| `auth_verify_success_body` | Deine E-Mail wurde bestätigt. | Your email has been confirmed. |
| `auth_verify_continue` | Weiter | Continue |
| `auth_verify_token_invalid` | Dieser Bestätigungslink ist ungültig oder abgelaufen. | This confirmation link is invalid or has expired. |
| `auth_logout` | Abmelden | Sign out |

> `%1$s` = E-Mail (Orientierung, kein Existenz-Beweis). Body + Resend sind **neutral** (§-Ask 1). Gate-Hinweis =
> `HintTone.GATED` (neutral), **kein** Fehler.

### Passwort vergessen (Request)
| Key | DE | EN |
|---|---|---|
| `auth_forgot_title` | Passwort vergessen | Forgot password |
| `auth_forgot_body` | Gib deine E-Mail-Adresse ein. Wir senden dir einen Link zum Zurücksetzen. | Enter your email address. We'll send you a reset link. |
| `auth_forgot_sent` | Falls ein Konto zu %1$s existiert, haben wir einen Link zum Zurücksetzen gesendet. | If an account exists for %1$s, we've sent a reset link. |

> `auth_forgot_sent` = **neutral** (§-Ask 1), unabhängig davon, ob das Konto existiert.

### Passwort zurücksetzen (Set-New, via Deep-Link)
| Key | DE | EN |
|---|---|---|
| `auth_reset_title` | Neues Passwort setzen | Set a new password |
| `auth_reset_new_label` | Neues Passwort | New password |
| `auth_reset_success` | Passwort geändert. Du kannst dich jetzt anmelden. | Password changed. You can sign in now. |
| `auth_reset_token_invalid` | Dieser Link ist ungültig oder abgelaufen. Fordere einen neuen an. | This link is invalid or has expired. Request a new one. |
| `auth_reset_error` | Zurücksetzen fehlgeschlagen. Bitte erneut versuchen. | Reset failed. Please try again. |

### P2 — „Mit GitHub anmelden" (OIDC)
| Key | DE | EN |
|---|---|---|
| `auth_github_button` | Mit GitHub anmelden | Sign in with GitHub |
| `auth_github_redirect` | Weiter zu GitHub… | Continuing to GitHub… |
| `auth_github_returning` | Anmeldung wird abgeschlossen… | Completing sign-in… |
| `auth_github_error` | GitHub-Anmeldung fehlgeschlagen oder abgebrochen. | GitHub sign-in failed or was cancelled. |

### a11y (contentDescription / Reveal-Label)
| Key | DE | EN |
|---|---|---|
| `a11y_auth_email` | E-Mail-Adresse eingeben | Enter email address |
| `a11y_auth_password` | Passwort eingeben | Enter password |
| `a11y_auth_password_confirm` | Passwort erneut eingeben | Re-enter password |
| `a11y_auth_password_show` | Passwort anzeigen | Show password |
| `a11y_auth_password_hide` | Passwort verbergen | Hide password |

> `a11y_auth_password_show`/`_hide` doppeln als **sichtbares Reveal-Text-Label UND `contentDescription`** — exakt das
> CYP-99-Muster aus `SettingsPanel` (Text statt Emoji, CYP-54). Kein separater sichtbarer Label-Key nötig.

---

## Zähl-/Validierungs-Block (Selbst-Validierung)

- **Neue Keys gesamt: 49** — davon **44** `auth_*` + **5** `a11y_auth_*`. **DE+EN-Parität: 49/49** (jede Zeile beide
  Spalten gefüllt).
- **Argument-Keys (`%1$s`): 4** — `auth_rate_limited_wait`, `auth_verify_pending_body`, `auth_forgot_sent` (+ derselbe
  `%1$s` je einmal); alle positionsbasiert, DE+EN gleiche Argument-Zahl.
- **0 Kollision** gg. `strings.xml`/`values-en` @ `09def21` (Prefix `auth_`/`a11y_auth_` existiert noch nicht — im
  Push-Schritt via `grep` gegengeprüft).
- **Reuse (keine neuen Keys):** Keine — Auth ist eine neue Fläche; keine bestehende Formulierung passt 1:1. (Der
  Reveal-Toggle reused das **Muster**, nicht die settings-scoped Keys.)
- **Kein Secret in Keys:** keine Token/Passwörter/Endpunkte in Werten.
