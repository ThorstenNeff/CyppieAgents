# Projekt-Settings — i18n-Keys (CYP-84 / CYP-85)

> Owner: UIUX-Designer · Epic CYP-75 · Stand 2026-06-28 · Status: Vorschlag — wartet auf Dev-Gegenlesen
> Konvention (verifiziert gg. `app/shared/src/commonMain/composeResources/values/strings.xml`): **Underscore-Realkeys** (compose.resources-identifier-safe, keine Punkte), positionsbasierte Argumente `%1$s`. **DE = Default** (`values/`), **EN** (`values-en/`). Parität Pflicht.
> Modul: `:app:shared` compose.resources → **Shared-Key-Sync mit der Impl timen** (CYP-84/85), sonst bricht ein Shared-Check.

## Neue Keys

### Gemeinsam (Fenster + beide Abschnitte)
| Key | DE | EN |
|---|---|---|
| `settings_title` | Projekt-Einstellungen | Project settings |
| `settings_save` | Speichern | Save |
| `settings_operator_required` | Nur mit Operator-Token änderbar | Editable only with an operator token |

### CYP-84 — Repo-Config
| Key | DE | EN |
|---|---|---|
| `settings_repo_section` | Repository | Repository |
| `settings_repo_url_label` | Repository-URL | Repository URL |
| `settings_repo_branch_label` | Branch | Branch |
| `settings_repo_status_unset` | Kein Repository konfiguriert – Agenten können nicht starten. | No repository configured — agents cannot start. |
| `settings_repo_effect_hint` | Änderung wirkt auf neu angelegte Worktrees / beim nächsten Hochfahren – bestehende Worktrees bleiben unverändert. | Change applies to newly created worktrees / on next startup — existing worktrees are unchanged. |
| `settings_repo_url_invalid` | Ungültige Repository-URL | Invalid repository URL |
| `a11y_settings_repo_url` | Repository-URL eingeben | Enter repository URL |
| `a11y_settings_repo_branch` | Branch eingeben | Enter branch |

### CYP-85 — API-Key
| Key | DE | EN |
|---|---|---|
| `settings_apikey_section` | API-Schlüssel | API key |
| `settings_apikey_masked` | Hinterlegt: %1$s | Stored: %1$s |
| `settings_apikey_unset` | Kein Schlüssel hinterlegt | No key stored |
| `settings_apikey_placeholder` | Neuen Schlüssel eingeben… | Enter a new key… |
| `settings_apikey_effect_hint` | Gespeichert. Wirkt erst beim nächsten Start des Agenten – jetzt neu starten, damit der neue Schlüssel zieht. | Saved. Takes effect on the agent's next start — restart now so the new key applies. |
| `settings_apikey_save_failed` | Speichern fehlgeschlagen | Save failed |
| `a11y_settings_apikey_input` | Neuen API-Schlüssel eingeben | Enter new API key |
| `a11y_settings_apikey_reveal` | Eingabe anzeigen | Show entry |
| `a11y_settings_apikey_hide` | Eingabe verbergen | Hide entry |

## Reuse (bestehende Keys — NICHT neu anlegen)
| Key | Quelle | Zweck hier |
|---|---|---|
| `agent_ctl_restart` | CYP-73 (`AgentHeader`) | Restart-Aktivierung des Effekt-Hinweises (CYP-85 §4.2) — kein neuer Restart-Key. DE „Neustart" / EN „Restart" (gg. `strings.xml` develop `4758360` verifiziert; nicht duplizieren). |

## Self-Validation
- 19 neue Keys, alle DE+EN belegt; alle im Spec (`PROJECT-SETTINGS.md`) + `project-settings-tags.md` referenziert.
- `settings_apikey_masked` ist der **einzige** Key mit Argument (`%1$s` = server-maskiert `***<last4>`); Klartext-Key wird nie in einen String interpoliert.
- `agent_ctl_restart` als Reuse markiert — gegen `strings.xml` (develop `4758360`) als vorhanden verifiziert; nicht neu anlegen.
