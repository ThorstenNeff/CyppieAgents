# Agenten-Verwaltung — i18n-Keys (CYP-86 / CYP-87 / CYP-88)

> Owner: UIUX-Designer · Epic CYP-76 · Stand 2026-06-28 · Status: Vorschlag — wartet auf Dev-Gegenlesen
> Konvention (verifiziert gg. `app/shared/.../composeResources/values/strings.xml`, develop `cb2e9b5`): **Underscore-Realkeys** (keine Punkte), positionsbasierte Argumente `%1$s`. **DE = Default** (`values/`), **EN** (`values-en/`). Parität Pflicht.
> Modul: `:app:shared` compose.resources → **Shared-Key-Sync mit der Impl timen** (CYP-86/87/88).

## Neue Keys

### Gemeinsam (Fenster + Liste)
| Key | DE | EN |
|---|---|---|
| `agent_mgmt_title` | Agenten-Verwaltung | Agent management |
| `agent_mgmt_operator_required` | Nur mit Operator-Token änderbar | Editable only with an operator token |
| `agent_role_worker` | Worker | Worker |
| `agent_add` | Agent hinzufügen | Add agent |
| `agent_edit` | Bearbeiten | Edit |
| `agent_remove` | Entfernen | Remove |
| `agent_save` | Speichern | Save |
| `agent_cancel` | Abbrechen | Cancel |

### CYP-86 — hinzufügen
| Key | DE | EN |
|---|---|---|
| `agent_add_id_label` | Agent-ID | Agent ID |
| `agent_add_name_label` | Name | Name |
| `agent_add_role_label` | Rolle | Role |
| `agent_add_persona_label` | Persona / CLAUDE.md | Persona / CLAUDE.md |
| `agent_add_launch_label` | Startkommando | Launch command |
| `agent_add_worktree_label` | Worktree-Ordner | Worktree folder |
| `agent_add_confirm` | Anlegen | Create |
| `agent_add_spawn_hint` | Angelegt. Der Agent startet noch nicht – über die Lifecycle-Steuerung starten. | Created. The agent is not started yet — start it via the lifecycle controls. |
| `agent_add_id_exists` | Diese Agent-ID existiert bereits | This agent ID already exists |
| `agent_add_po_exists` | Es gibt bereits einen PO – nur ein PO pro Projekt möglich. | A PO already exists — only one PO per project is allowed. |
| `agent_add_error` | Anlegen fehlgeschlagen | Create failed |
| `a11y_agent_add_id` | Agent-ID eingeben | Enter agent ID |
| `a11y_agent_add_persona` | Persona / CLAUDE.md eingeben | Enter persona / CLAUDE.md |

### CYP-87 — entfernen (irreversibel)
| Key | DE | EN |
|---|---|---|
| `agent_remove_title` | Agent „%1$s" entfernen? | Remove agent “%1$s”? |
| `agent_remove_consequences` | Die laufende Session wird gestoppt. | The running session will be stopped. |
| `agent_remove_worktree_keep` | Worktree behalten | Keep worktree |
| `agent_remove_worktree_delete` | Worktree löschen | Delete worktree |
| `agent_remove_worktree_warning` | Nicht committete/nicht gepushte Arbeit in „%1$s" geht unwiderbringlich verloren. | Uncommitted/unpushed work in “%1$s” will be permanently lost. |
| `agent_remove_confirm` | Agent entfernen | Remove agent |
| `agent_remove_confirm_delete` | Endgültig löschen | Delete permanently |
| `agent_remove_last_po` | Der einzige PO kann nicht entfernt werden – Hub-and-Spoke bräche. | The only PO cannot be removed — hub-and-spoke would break. |
| `agent_remove_error` | Entfernen fehlgeschlagen | Remove failed |

### CYP-88 — Konfig ändern
| Key | DE | EN |
|---|---|---|
| `agent_edit_title` | Agent „%1$s" bearbeiten | Edit agent “%1$s” |
| `agent_edit_effect_hint` | Gespeichert. Wirkt erst beim nächsten Start des Agenten – jetzt neu starten, damit die neue Konfiguration zieht. | Saved. Takes effect on the agent's next start — restart now so the new configuration applies. |
| `agent_edit_id_locked_hint` | ID und Worktree sind fest und hier nicht änderbar. | ID and worktree are fixed and cannot be changed here. |
| `agent_edit_po_exists` | Rolle PO ist belegt – nur ein PO pro Projekt. | The PO role is taken — only one PO per project. |
| `agent_edit_last_po` | Der einzige PO kann die Rolle nicht abgeben – Hub-and-Spoke bräche. | The only PO cannot give up the PO role — hub-and-spoke would break. |
| `agent_edit_error` | Speichern fehlgeschlagen | Save failed |

> **`agent_edit_po_exists` vs. `agent_edit_last_po` — zwei getrennte Fälle (CYP-101):** `…po_exists` = Rolle PO **bei einem anderen** Agenten belegt (kein zweiter PO). `…last_po` = der **einzige** PO will die PO-Rolle **abgeben** (Guardrail `editWouldDropLastPo`). Der ursprüngliche Impl mappte beide auf `…po_exists` (falsche Botschaft fürs Abgeben) — `agent_edit_last_po` schließt das. Wortlaut parallel zu `agent_remove_last_po`. Design-Wortlaut ist **authoritativ**; Dev verdrahtet darauf.

## Reuse (bestehende Keys — NICHT neu anlegen)
| Key | Quelle | Zweck hier |
|---|---|---|
| `agent_role_po` | CYP-51/CYP-73 | Rollen-Label PO im Picker/Liste (DE/EN „PO") |
| `agent_ctl_restart` | CYP-73 | Restart-Aktivierung des Effekt-Hinweises (CYP-88) — DE „Neustart"/EN „Restart" (verifiziert `cb2e9b5`) |
| `agent_status_running` / `_stopped` / `_error` / `_unknown` | CYP-73 | Lifecycle-Status in der Listenzeile (Reuse, nicht neu) |
| `agent_ctl_start` / `_stop` | CYP-73 | falls Inline-Lifecycle in der Liste gezeigt (Reuse) |

> **Konsolidierungs-Hinweis (PO/Dev):** `agent_save`/`agent_cancel` überlappen semantisch mit `acl_continue`/`acl_cancel` (=„Fortfahren"/„Abbrechen"). Bewusst **nicht** die `acl_`-Keys über die Surface-Grenze wiederverwendet (Cross-Surface-Drift-Risiko, vgl. CYP-51-Befund). Falls das Team einen generischen Shared-Key-Satz (`generic_save/cancel`) will → eigenes Konsolidierungs-Ticket; bis dahin surface-lokal.

## Self-Validation
- 43 neue Keys (8 gemeinsam + 14 CYP-86 + 9 CYP-87 + **6 CYP-88** + 6 a11y verteilt), alle DE+EN; alle im Spec + `agent-management-tags.md` referenziert. (`agent_edit_last_po` ergänzt im CYP-101-Fix-Backfill 2026-06-28.)
- Argument-Keys: `agent_remove_title`/`_worktree_warning`, `agent_edit_title` (`%1$s`=Agentname/Worktree-Pfad). Persona-/Key-Klartext wird nie interpoliert.
- Reuse-Keys gegen `strings.xml` (`cb2e9b5`) verifiziert: `agent_role_po`, `agent_ctl_restart`, `agent_status_*` vorhanden; `agent_role_worker` ist **neu** (existiert nicht).
