# Projekt-Settings — testTags (CYP-84 / CYP-85)

> Owner: UIUX-Designer · Epic CYP-75 · Stand 2026-06-28 · Status: Vorschlag — wartet auf Dev-Gegenlesen
> Schema (Test-Contract v0.5 §2, Dev/QA-Vertrag CYP-7): **prefixlos** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, Segmentwerte `[A-Za-z0-9-]+` (camelCase ok, **keine Punkte** im Wert). Area hier = `settings`.
> **Code = Source of Truth:** Reuse-Tags unten gegen die echten `*Tags.kt` (develop `4758360`) verifiziert.

## Neue Tags (Area `settings`)
| Tag | Element |
|---|---|
| `settings.panel` | Wurzel-Container im Fensterinhalt |
| `settings.section.repo` | Abschnitt Repo-Config (CYP-84) |
| `settings.section.apiKey` | Abschnitt API-Key (CYP-85) |
| **CYP-84** | |
| `settings.repo.url.input` | Repository-URL-Eingabe |
| `settings.repo.branch.input` | Branch-Eingabe |
| `settings.repo.save` | Speichern (Repo) |
| `settings.repo.status` | Status-/„nicht konfiguriert"-Zeile |
| `settings.repo.gateHint` | Operator-Gate-Hinweis (read-only) |
| `settings.repo.effectHint` | Effekt-deferred-Hinweis (neue Worktrees / nächster Boot) |
| `settings.repo.error` | Feldfehler (ungültige URL / Save-Fehler) |
| **CYP-85** | |
| `settings.apiKey.masked` | Maskierte Status-Zeile (`***last4` / „nicht gesetzt") |
| `settings.apiKey.input` | Write-only Neuer-Schlüssel-Eingabe |
| `settings.apiKey.reveal` | Anzeigen/Verbergen-Toggle (nur aktuelle Eingabe) |
| `settings.apiKey.save` | Speichern (Key) |
| `settings.apiKey.gateHint` | Operator-Gate-Hinweis |
| `settings.apiKey.effectHint` | Effekt-deferred-Hinweis (Restart nötig) |
| `settings.apiKey.error` | Save-Fehler / Gate-Ablehnung |

## Reuse (bestehende Tags — gegen Code verifiziert, NICHT neu definieren)
| Tag | Quelle (`*Tags.kt`) | Zweck hier |
|---|---|---|
| `window.<id>` (= `window.settings`) | `WindowTestTags.window(id)` | Settings-Fenster im Manager |
| `window.<id>.content` | `WindowTestTags.content(id)` | Fensterinhalt (Settings.panel hängt darunter) |
| `window.<id>.titlebar` | `WindowTestTags.titleBar(id)` | Fenster-Titelleiste (Drag) |
| `agent.<id>.restartBtn` | `AgentViewTags.restartBtn(id)` | Restart-Aktivierung des API-Key-Effekts (CYP-85, Reuse CYP-73) — die Settings-UI rendert KEINEN eigenen Restart-Button, sondern verweist/koppelt auf diesen Pfad |

## Self-Validation
- 18 neue Tags; Schema-konform (camelCase-Werte wie `gateHint`/`effectHint`/`apiKey` analog zu bestehendem `composerInput`/`resizeHandle`; keine Punkte in Segmentwerten).
- Reuse-Tags **gegen Code verifiziert** (develop `4758360`): `WindowTestTags.window/content/titleBar`, `AgentViewTags.restartBtn` — exakte Strings, kein Drift (Lehre aus CYP-26-Tag-Drift).
- Fenster-id `settings` ist Vorschlag; finaler Konstantenname (`SETTINGS_WINDOW_ID`) = Dev, Tag folgt der id.
