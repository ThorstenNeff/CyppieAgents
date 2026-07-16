# First-Run Operator-Setup — i18n-Keys

> Owner: UIUX-Designer · Epic CYP-623 · Story CYP-629 · Stand 2026-07-16 ·
> Status: **Vorschlag — wartet auf Dev-Gegenlesen.** Companion zu `first-run-setup-ux-spec.md` / `-tags.md` / `-tokens.json`.
> Konvention gg. `app/shared/src/commonMain/composeResources/values/strings.xml` (DE-Default) +
> `values-en/strings.xml` (EN), snake_case Realkeys, positional `%1$s`, verifiziert @ develop `2f664e33`.
> **DE → `values/strings.xml` · EN → `values-en/strings.xml`.**

## Neue Keys

### Shell / Orientierung (Schritt 1) — `INFO`
| Key | DE | EN |
|---|---|---|
| `first_run_title` | Hub einrichten | Set up your hub |
| `first_run_intro` | Dein Hub ist installiert und läuft — aber noch nicht eingerichtet. Richte den API-Key und das Repository ein, dann kann dein Team arbeiten. | Your hub is installed and running — but not configured yet. Set the API key and repository, then your team can work. |
| `first_run_degraded_note` | Das ist bei einem frischen Hub normal: er läuft bereits, kann aber noch keine Agenten starten, bis Key und Repository gesetzt sind. | This is normal for a fresh hub: it's already running, but it can't start any agents until the key and repository are set. |
| `first_run_step_apikey` | API-Key | API key |
| `first_run_step_repo` | Repository | Repository |
| `first_run_step_team` | Team | Team |

### API-Key (Schritt 2) — Posture `INFO` · Bestätigung `INFO`
| Key | DE | EN |
|---|---|---|
| `first_run_apikey_posture` | Der API-Key wird server-seitig gespeichert und überall nur maskiert angezeigt (***<letzte 4>) — noch nicht verschlüsselt at-rest (Verschlüsselung folgt in einem späteren Update). Behandle den Hub-Host als vertrauenswürdig. | The API key is stored on the server and only ever shown masked (***<last 4>) — not yet encrypted at rest (encryption is coming in a later update). Treat the hub host as trusted. |
| `first_run_apikey_saved` | Gespeichert. Der Key wird beim ersten Start deiner Agenten verwendet. | Saved. The key will be used when your agents first start. |

### Repository (Schritt 3) — Bestätigung/Clone `INFO` · Fehler `ERROR`
| Key | DE | EN |
|---|---|---|
| `first_run_repo_saved` | Repository gesetzt. Der Hub klont es jetzt. | Repository set. The hub is cloning it now. |
| `first_run_repo_cloning` | Repository wird geklont … | Cloning repository… |
| `first_run_repo_clone_ok` | Repository geklont. Dein Team kann arbeiten. | Repository cloned. Your team can work. |
| `first_run_repo_clone_failed` | Das Repository konnte nicht geklont werden. Prüfe URL und Zugriffsrechte des Hub-Hosts und setze es erneut. | The repository couldn't be cloned. Check the URL and the hub host's access, then set it again. |
| `first_run_repo_clone_failed_url` | Das Repository konnte nicht geklont werden: die URL ist nicht erreichbar. Prüfe die Adresse und setze das Repository erneut. | The repository couldn't be cloned: the URL isn't reachable. Check the address and set the repository again. |
| `first_run_repo_clone_failed_auth` | Das Repository konnte nicht geklont werden: der Hub-Host hat keinen Zugriff. Hinterlege die Zugangsdaten am Host (SSH/gh) und setze das Repository erneut. | The repository couldn't be cloned: the hub host has no access. Provide credentials on the host (SSH/gh) and set the repository again. |

### Team (Schritt 4) — `INFO`
| Key | DE | EN |
|---|---|---|
| `first_run_team_intro` | Dein Team startet mit einem PO. Füge jetzt weitere Agenten hinzu — oder später im Workspace. | Your team starts with one PO. Add more agents now — or later in the workspace. |

### Abschluss / Überspringen (Schritt 5) — `INFO`
| Key | DE | EN |
|---|---|---|
| `first_run_complete_title` | Einrichtung abgeschlossen | Setup complete |
| `first_run_complete_body` | Key und Repository sind gesetzt. Dein Hub ist arbeitsbereit. | The key and repository are set. Your hub is ready to work. |
| `first_run_open_workspace` | Workspace öffnen | Open workspace |
| `first_run_skip` | Später einrichten | Set up later |
| `first_run_skip_note` | Du kannst das jederzeit in den Projekt-Einstellungen nachholen. Bis dahin läuft der Hub, kann aber keine Agenten starten. | You can do this anytime in Project Settings. Until then the hub runs but can't start any agents. |

### Degradierter Workspace (Konsument des Skip; Area `workspace`) — `INFO`
| Key | DE | EN |
|---|---|---|
| `workspace_unconfigured_banner` | Hub noch nicht eingerichtet — Agenten können nicht starten. In den Projekt-Einstellungen einrichten. | Hub not configured yet — agents can't start. Set it up in Project Settings. |

> **Kommentar (nicht user-facing):** `first_run_apikey_posture` — die at-rest-Verschlüsselung ist die
> spätere Slice **CYP-220**. Der Ticket-Key steht **bewusst nicht** im sichtbaren String (interner Tracking-Ref
> gehört nicht in UI-Copy). Anker der Formulierung: CYP-199 („not encrypted at rest yet — say so" / „treat the
> hub host as trusted"). Siehe ux-spec §9-Flag (1 offener Auftraggeber-Entscheid).
> **`workspace_unconfigured_banner`** vor dem Bau gegen einen evtl. bestehenden Workspace-Degraded-Banner
> verifizieren; bei Fund dessen Key reusen (ux-spec §6.2 Reuse-Check).

## Reuse (bestehende Keys — NICHT neu anlegen)
- **API-Key-Feld:** `settings_apikey_section`, `settings_apikey_masked` (`%1$s`=`***<last4>`), `settings_apikey_unset`,
  `settings_apikey_placeholder`, `settings_apikey_save_failed`, `a11y_settings_apikey_input`,
  `a11y_settings_apikey_reveal`, `a11y_settings_apikey_hide`, `settings_save`.
  **Bewusst NICHT reused im First-Run:** `settings_apikey_effect_hint` (Restart-Hint) → ersetzt durch
  `first_run_apikey_saved` (ux-spec §3.2).
- **Repo-Feld:** `settings_repo_section`, `settings_repo_url_label`, `settings_repo_branch_label`,
  `settings_repo_status_unset`, `settings_repo_url_invalid`, `a11y_settings_repo_url`, `a11y_settings_repo_branch`.
  **Bewusst NICHT reused:** `settings_repo_effect_hint` („neue worktrees / nächster Boot") → ersetzt durch
  `first_run_repo_saved` + Clone-Status (ux-spec §4.1).
- **Roster/Team:** `agent_add`, `agent_add_id_label`, `agent_add_name_label`, `agent_add_role_label`,
  `agent_add_launch_label`, `agent_add_worktree_label`, `agent_add_confirm`, `agent_add_spawn_hint`,
  `agent_add_id_exists`, `agent_add_po_exists`, `agent_add_error`, `a11y_agent_add_id`, `a11y_agent_add_persona`,
  `agent_mgmt_title`, `agent_mgmt_operator_required` (der komplette bestehende Add-Flow, unverändert eingebettet).
- **Operator-Gate:** kein Reuse nötig — der Operator ist upstream authentifiziert (ux-spec §2), kein Gate-Hint.

## Self-Validation
- **21 neue Keys** (20 × `first_run_*` + 1 × `workspace_unconfigured_banner`), alle DE+EN befüllt.
- **Argument-Anzahl:** **alle 0 Args** (kein `%n$s`); DE/EN-Argument-Anzahl identisch (0 == 0) je Zeile. Der
  Text „***<letzte 4>" / „***<last 4>" in `first_run_apikey_posture` ist **literale Erklärung**, kein Format-Arg.
- **Kein content-tragendes/sensibles Klartext:** kein Key-Rohwert, kein Token, kein Fingerprint in der Copy;
  Maskierung wird nur *beschrieben*, nicht materialisiert.
- **Kollision:** 0 — `first_run_*` ist greenfield (`grep name="first_run_"` @ `2f664e33` liefert nichts);
  `workspace_unconfigured_banner` vor Bau gegen `name="workspace_"` verifizieren (Reuse-Check §6.2).
- **DE/EN-Parität:** jede Zeile beidseitig.
- **Reuse vs neu sauber getrennt:** 2 bestehende Effekt-Hints bewusst NICHT reused (Honesty §3.2/§4.1),
  begründet; der Rest der Config-/Roster-Keys 1:1 reused.
- Jeder Key ist in `first-run-setup-ux-spec.md` verankert und in `-tags.md` einem Tag + Ton + a11y-Politeness zugeordnet.
