# Cross-Projekt — i18n-Keys (CYP-93 / CYP-94)

> Owner: UIUX-Designer · Epic CYP-79 · Stand 2026-06-28 · Status: Vorlauf-Entwurf — wartet auf PO-/Dev-Gegenlesen
> Konvention (verifiziert gg. `app/shared/.../composeResources/values/strings.xml`, develop `3705b68`):
> **Underscore-Realkeys** (keine Punkte), positionsbasierte Argumente `%1$s`. **DE = Default** (`values/`),
> **EN** (`values-en/`). Parität Pflicht.
> Modul: `:app:shared` compose.resources → **Shared-Key-Sync mit der Impl timen** (CYP-93/94).

## Neue Keys — CYP-93 Cross-Projekt-Kanal-Autorisierung
| Key | DE | EN |
|---|---|---|
| `crossproject_title` | Projektübergreifende Freigabe | Cross-project sharing |
| `crossproject_badge` | Projektübergreifend | Cross-project |
| `crossproject_status_shared` | Projektübergreifend freigegeben am %1$s · erreicht: %2$s | Cross-project, authorized %1$s · reaches: %2$s |
| `crossproject_status_not_shared` | Nur in diesem Projekt – nicht projektübergreifend freigegeben. | This project only — not authorized across projects. |
| `crossproject_authorize` | Projektübergreifend freigeben | Authorize across projects |
| `crossproject_revoke` | Freigabe zurücknehmen | Revoke sharing |
| `crossproject_dialog_scope` | Dieser Kanal erreicht dann diese Agenten: %1$s | This channel will then reach these agents: %1$s |
| `crossproject_member_access` | %1$s (Projekt %2$s) – %3$s | %1$s (project %2$s) — %3$s |
| `crossproject_member_project` | Aus Projekt %1$s | From project %1$s |
| `crossproject_access_read` | lesend | read |
| `crossproject_access_write` | schreibend | write |
| `crossproject_owner_consent` | Ich gebe diesen Kanal als Eigentümer projektübergreifend frei. | As the owner, I authorize this channel across projects. |
| `crossproject_human_only` | Nur du als Eigentümer gibst frei – niemals ein Agent oder eine Nachricht. | Only you, the owner, authorize this — never an agent or a message. |
| `crossproject_single_owner_note` | Single-User: ein Eigentümer gibt frei. Gegenseitige Zustimmung mehrerer Eigentümer folgt später. | Single-user: one owner authorizes. Mutual consent of multiple owners comes later. |
| `crossproject_confirm` | Freigeben | Authorize |
| `crossproject_cancel` | Abbrechen | Cancel |
| `crossproject_error` | Freigabe fehlgeschlagen | Authorization failed |
| `crossproject_operator_required` | Nur der Eigentümer (Operator-Token) kann freigeben. | Only the owner (operator token) can authorize. |
| `a11y_crossproject_badge` | Projektübergreifender Kanal | Cross-project channel |

> **`crossproject_human_only` ist load-bearing (Sicherheit):** macht die Anti-Injection-Invariante
> sichtbar — nur der Mensch/Eigentümer autorisiert, nie ein Agent/eine Nachricht (Spec §2.6). Wortlaut
> nicht abschwächen.
> **`crossproject_single_owner_note` = Disclosure-Honesty:** Single-Owner ≠ bilateral (S18). Nicht so
> formulieren, dass gegenseitige Zustimmung impliziert wird (Spec §2.2).
> **Kein Over-Widen (Reviewer-Leitplanke + PO-§6-Addendum c):** `crossproject_dialog_scope` /
> `crossproject_status_shared` nennen **konkrete Member-Agenten dieses Kanals**, nie „Projekt B" pauschal.
> Jeder Member wird über `crossproject_member_access` („%1$s (Projekt %2$s) – %3$s") gerendert; %3$s =
> `crossproject_access_read` (**Default** neuer Cross-Projekt-Member) bzw. `crossproject_access_write` (nur
> per expliziter ACL). `crossproject_member_project` markiert einen fremd-projektigen Member in der
> Mitglieder-/ACL-Ansicht (Membership-Transport-Disclosure, §2.4/§2.7). **`sharedBy` (wer) = S18-deferred**
> (§6.2) → `crossproject_status_shared` trägt **nur** `sharedAt` + Reichweite, kein „von wem".

## Neue Keys — CYP-94 Event-Log-Projekt-Filter (in der bestehenden `event_*`-Familie)
| Key | DE | EN |
|---|---|---|
| `event_filter_project` | Projekt | Project |
| `event_filter_project_all` | Alle Projekte | All projects |
| `event_view_project` | Sicht: Projekt %1$s | View: project %1$s |
| `event_view_all_projects` | Sicht: alle Projekte (projektübergreifend) | View: all projects (cross-project) |
| `event_row_project` | Projekt: %1$s | Project: %1$s |

> Diese reihen sich in die **bestehende** Event-Log-Surface-Familie (`event_filter_agent`/`_type`/
> `_severity`/`_timewindow`/`_correlation`/`event_filter_active` — verifiziert `strings.xml` @ `3705b68`).
> **Gleiche Surface**, daher Namens-Reuse der `event_*`-Konvention (kein Cross-Surface-Drift).
> `event_view_*` ist die **Scope**-Schwester von `event_filter_active` (Teilmenge); `event_row_project`
> wird **nur in der projektübergreifenden Sicht** gerendert (Pro-Zeile-Identität als Text).

## Reuse (bestehende Keys/Komponenten — NICHT neu anlegen)
| Reuse | Quelle | Zweck hier |
|---|---|---|
| `event_filter_active` | CYP-41 | Präzedenz für den Scope-Sicht-Indikator (`event_view_*` ist die Scope-Variante) |
| `event_severity_*` / `event_detail_source_ts` | CYP-34 | unverändert; Projekt-Achse ist orthogonal zu Severity/Provenienz |
| `ui/TonedHint.kt` + `HintTone` | CYP-99 (Komponente) | alle Hinweiszeilen (status/scope/humanOnly/singleOwner/gate/error) — Code-Reuse, kein Key-Reuse |
| `acl_po_protected` / `operator_required`-Muster | CYP-49 | Server-autoritative Gate-Ablehnung (Freigabe = Zugriffsänderung, fail-closed) |

> Kein Cross-Surface-Reuse von `agent_`/`acl_`-Keys (Drift-Vermeidung, vgl. CYP-51); `crossproject_cancel`
> bleibt surface-lokal. Tonal-Styling kommt aus der geteilten `TonedHint`-Komponente.

## Self-Validation
- **24 neue Keys** (19 CYP-93 `crossproject_*`/`a11y_` + 5 CYP-94 `event_*`), alle DE+EN; alle im Spec
  (`CROSS-PROJECT.md`) bzw. an ihren Tags in `cross-project-tags.md` referenziert.
- **Argument-Keys:** `crossproject_status_shared` (%1$s=`sharedAt`-Zeit, %2$s=erreichte Agenten);
  `crossproject_dialog_scope` (%1$s=Agentenliste); `crossproject_member_access` (%1$s=Agent, %2$s=Projekt,
  %3$s=Zugriff); `crossproject_member_project` (%1$s=Projekt); `event_view_project`/`event_row_project`
  (%1$s=Projekt-id/-name). **Kein** content-tragender/sensibler Klartext interpoliert (nur Projekt-/
  Agent-Identität + Zeit; Events sind ohnehin content-frei).
- **Kollision:** `crossproject_*` greenfield; `event_filter_project`/`_all`/`event_view_*`/`event_row_project`
  existieren nicht in `strings.xml` @ `f73a527` (nur `event_filter_{active,agent,type,severity,timewindow,
  correlation}` vorhanden) → 0 Kollision.
- DE/EN-Parität: jede Zeile beidseitig befüllt; gleiche Argument-Anzahl je Sprache.
