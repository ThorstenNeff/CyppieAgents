# Multi-User MEMBER/OPERATOR — i18n-Keys (CYP-80 / S18)

> Owner: UIUX-Designer · Story CYP-80 · Stand 2026-07-02 · Status: Vorschlag — **Key-Sync-Punkt** (PO koordiniert CYP-7 zwischen UIUX/Dev/Tester)
> Konvention (verifiziert gg. `app/shared/src/commonMain/composeResources/values/strings.xml`, develop `8cf393a`):
> **Underscore-Realkeys** (keine Punkte), Argumente `%1$s`. **DE = Default** (`values/`), **EN** (`values-en/`). Parität Pflicht.
> **Prefix `workspace_` + `a11y_workspace_` NEU** — 0 Kollision (kein `workspace_`-Key im Katalog). Shared-Key-Sync mit der
> Impl timen (Keys landen, wenn Dev das Tier-Modul konsumiert).
> **Achsen-Trennung:** diese Keys sind die **User-Tier** (Mensch: Operator/Mitglied) — **nicht** die Agent-Rolle
> (`agent_role_po`/`_worker`/`_product_lead`, bestehen weiter, unangetastet).

## Neue Keys (unbedingt)

### Rollen-Indikator + Operator-Identität
| Key | DE | EN |
|---|---|---|
| `workspace_role_indicator_operator` | Du bist Operator | You are the operator |
| `workspace_role_indicator_member` | Du bist Mitglied | You are a member |
| `workspace_operator_is` | Operator: %1$s | Operator: %1$s |

> `%1$s` = Anzeigename des Operators (kein E-Mail/Kontakt-Dump, §3.3). Neutral, kein Prestige.

### Mitglieder-Roster (nur OPERATOR)
| Key | DE | EN |
|---|---|---|
| `workspace_members_title` | Mitglieder | Members |
| `workspace_tier_operator` | Operator | Operator |
| `workspace_tier_member` | Mitglied | Member |
| `workspace_member_you` | Du | You |

> `workspace_tier_*` = kurze Zeilen-Labels im Roster; `workspace_role_indicator_*` = die Ich-Form für die Top-Leiste.

### a11y
| Key | DE | EN |
|---|---|---|
| `a11y_workspace_role` | Deine Rolle: %1$s | Your role: %1$s |
| `a11y_workspace_members` | Mitgliederliste | Member list |

### Member-facing GATED-Hinweis (§6 — Access-Modell C entschieden 2026-07-02)

| Key | DE | EN |
|---|---|---|
| `workspace_operator_only` | Nur der Operator kann das ändern | Only the operator can change this |

> **Unbedingt** (nach Auftraggeber-Entscheid **C/Hybrid**): rollen-ehrliche member-facing Formulierung der GATED-Hinweise.
> Ein MEMBER sieht **diesen** Hinweis (nicht die token-zentrischen `*_operator_required`). Die 5 bestehenden Keys
> (`settings_operator_required`, `agent_mgmt_operator_required`, `project_mgmt_operator_required`,
> `crossproject_operator_required`, `acl_operator_required`) bleiben für den **operator-/bootstrap-seitigen Token-Break-Glass**-
> Kontext bestehen (nicht member-facing) — kleiner Dev-Fold beim Verdrahten, kein neuer Key.

---

## Zähl-/Validierungs-Block (Selbst-Validierung)

- **Neue Keys gesamt: 10** — 8 `workspace_*` + 2 `a11y_workspace_*` (inkl. `workspace_operator_only`, **unbedingt** nach
  dem C-Entscheid 2026-07-02). **DE+EN-Parität 10/10.**
- **Argument-Keys (`%1$s`): 2** — `workspace_operator_is`, `a11y_workspace_role` (DE+EN gleiche Argument-Zahl).
- **0 Kollision** gg. `strings.xml`/`values-en` @ `8cf393a` (Prefix `workspace_`/`a11y_workspace_` neu — im Push via `grep`
  gegengeprüft).
- **Reuse (keine neuen Keys):** GATED-Hinweise/`comm_readonly_hint`/`acl_partial_view`/`acl_granted`/`acl_denied` bleiben;
  die Tier-Differenzierung reused sie (nur Boolean-Quelle wechselt). Agent-Rollen-Keys (`agent_role_*`) unangetastet.
- **Kein Secret in Keys:** keine E-Mail/Token/Endpoints; Operator-Name = neutraler Anzeigename.
