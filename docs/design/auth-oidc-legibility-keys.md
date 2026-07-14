# Auth-OIDC-Legibilität — neue Keys (CYP-575 + CYP-576)

> Owner: UIUX-Designer · Stand 2026-07-14 · **Erweitert** `auth-keys.md` §P2 (GitHub-OIDC). **Underscore-Realkeys**,
> `%1$s` positional, **DE = Default** (`values/strings.xml`), **EN** (`values-en/strings.xml`). Gegroundet @ `58cf2207`.
> Begleit: `auth-oidc-legibility-ux-spec.md` + `auth-oidc-legibility-tags.md`.

## Neue Keys (5 Realkeys + 1 Retext)

| Key | DE (Default) | EN | Rolle / Honesty |
|---|---|---|---|
| `auth_github_handoff_fallback` | Falls sich der Browser nicht öffnet, öffne diesen Link: | If your browser doesn't open, open this link: | §2 CYP-575 — sichtbarer manueller Ausweg. Neutral, **kein** „Browser geöffnet". |
| `auth_github_url_copy` | Link kopieren | Copy link | §2 — Copy-Affordanz der Fallback-URL (`→UIUX2`-Interaktion). URL ist kein Secret. |
| `auth_github_timeout` | Das dauert länger als erwartet. | This is taking longer than expected. | §3 — Timeout **advisory/INFO**, nicht Fehler. Retryable. |
| `auth_github_retry` | Erneut versuchen | Try again | §3/§4 — expliziter Retry (`startGithub`), von Timeout **und** Error geteilt. |
| `auth_github_cancelled` | GitHub-Anmeldung abgebrochen. | GitHub sign-in was cancelled. | §4 — Nutzer-**Abbruch**, neutral/INFO (kein Alarm). Nur wenn Backend cancel≠error trägt (Seam). |

### Retext (bestehender Key, Copy geändert — Honesty)
| Key | ALT | NEU DE | NEU EN | Warum |
|---|---|---|---|---|
| `auth_github_error` | „GitHub-Anmeldung fehlgeschlagen oder abgebrochen." | Die GitHub-Anmeldung wurde nicht abgeschlossen. Bitte erneut versuchen oder oben mit E-Mail anmelden. | GitHub sign-in didn't complete. Please try again, or sign in with email above. | §4 CYP-576 — **entkonfundiert** (kein „oder abgebrochen" bei echtem Server-Fehler) + **aktionabel** (Retry + E-Mail-Verweis). |

> **Retext-Flag (Dev-Sync):** `auth_github_error` ist ein **bestehender** Key — die Copy-Änderung ist ein reiner
> Wert-Swap in `values/` + `values-en/`, **kein** neuer Key, **keine** Signatur-/Arg-Änderung (0 Args, bleibt 0).

## Reuse (keine neuen Keys nötig)
- `remote_login_browser_handoff` „Weiter im Browser…" — bleibt die Handoff-Primärzeile (bestehend, CYP-460).
- `auth_github_button` „Mit GitHub anmelden" — der bestehende Start/implizite-Retry-Button bleibt.
- E-Mail/Passwort-Login-Copy (`auth_submit_login` etc.) — die immer-präsente Alternative, unverändert.

## a11y
| Key | DE | EN | Rolle |
|---|---|---|---|
| `a11y_auth_github_url` | GitHub-Anmelde-Link — zum Öffnen kopieren | GitHub sign-in link — copy to open | contentDescription der Fallback-URL (`→UIUX2` verdrahtet die Live-Region). |

## Zähl-/Validierungs-Block (Selbst-Validierung)
- **Net-new: 5 Realkeys** (`auth_github_handoff_fallback`, `auth_github_url_copy`, `auth_github_timeout`,
  `auth_github_retry`, `auth_github_cancelled`) **+ 1 a11y** (`a11y_auth_github_url`) **+ 1 Retext** (`auth_github_error`, bestehend).
- **Args:** alle **0 Args** (keine `%1$s`). DE == EN Argument-Anzahl (0) für jeden.
- **0 Kollision @ `58cf2207`:** grep-verifiziert, keiner der 6 neuen Keys existiert in `values/` oder `values-en/`.
- **DE = Default + EN paritätisch** (jede Zeile beidseitig).
- **Namespace-konsistent:** alle unter `auth_github_*` (auth-keys.md §P2), keine Divergenz.
