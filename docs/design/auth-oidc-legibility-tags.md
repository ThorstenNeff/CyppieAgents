# Auth-OIDC-Legibilität — neue Tags (CYP-575 + CYP-576)

> Owner: UIUX-Designer · Stand 2026-07-14 · **Erweitert** `auth-tags.md` (Area `auth`, scopeId `github`). Segmentwerte
> `[A-Za-z0-9-]+` (camelCase, **keine Punkte** im Wert). Gegroundet @ `58cf2207`. Begleit: `-ux-spec.md` + `-keys.md`.

## Neue Tags (Area `auth`, scopeId `github`)
| Tag | Element | liveRegion |
|---|---|---|
| `auth.github.handoffUrl` | §2 — die **sichtbare Fallback-URL** (selektierbar) im Browser-Handoff (CYP-575). | — (Polite über den Notice-Text) |
| `auth.github.handoffCopy` | §2 — die **Copy-Affordanz** der Fallback-URL (`→UIUX2` Clipboard). | — |
| `auth.github.timedOut` | §3 — der **Timeout-Hinweis** („Das dauert länger…") nach X s ohne Return. INFO/advisory. | **Polite** |
| `auth.github.retry` | §3/§4 — der **explizite Retry-Button** (`startGithub`), von Timeout **und** Error geteilt. | — |
| `auth.github.cancelled` | §4 — der **Abbruch**-Hinweis (neutral), nur wenn Backend cancel≠error trägt. | **Polite** |

## Reuse (bestehende Tags, unverändert)
| Tag | Rolle |
|---|---|
| `remote.login.browserHandoff` (`OperatorAuthTags.LOGIN_BROWSER_HANDOFF`) | die Handoff-Primärzeile „Weiter im Browser…" (bestehend, `AuthGate.kt:226`). |
| `auth.github.redirecting` | Web-Redirect-Flavor „Weiter zu GitHub…" (bestehend). |
| `auth.github.returning` | Rücksprung „Anmeldung wird abgeschlossen…" (bestehend). |
| `auth.github.error` | der OIDC-Fehler-Hinweis — **Copy retextet** (aktionabel), **Tag unverändert** (`AuthTags.GITHUB_ERROR`). Assertive (bestehend). |
| `auth.login.github` | „Mit GitHub anmelden"-Button (bestehend). |

## liveRegion-Vertrag (additiv zu auth-tags.md §8.2)
- **Assertive:** `auth.github.error` (Interrupt — echter Fehler, bestehend).
- **Polite (neu):** `auth.github.timedOut`, `auth.github.cancelled` (Status-Änderungen, kein Interrupt).

## Zähl-/Validierungs-Block (Selbst-Validierung)
- **Net-new: 5 Tags** (`handoffUrl`, `handoffCopy`, `timedOut`, `retry`, `cancelled`) — alle unter der **bestehenden**
  Area `auth`, scopeId `github`, im **bestehenden** `AuthTags`-Object (kein neues Object, kein neuer Scope).
- **0 Kollision @ `58cf2207`:** `handoffUrl` / `handoffCopy` / `timedOut` / `retry` / `cancelled` existieren nicht im
  bestehenden `auth.github.*`-Satz (grep-verifiziert gg. `AuthTags.kt`).
- **Charset ✓** camelCase, `[A-Za-z0-9-]+`, keine Punkte im Segmentwert.
- **Konsistent** mit `-keys.md` (jedes Tag-Element trägt seinen Key) und `-ux-spec.md` (§2/§3/§4).
- **Reuse verifiziert:** `remote.login.browserHandoff` stammt 1:1 aus `OperatorAuthTags` (`AuthGate.kt` reused es für den nativen Handoff) — **kein** neuer Handoff-Tag erfunden.
