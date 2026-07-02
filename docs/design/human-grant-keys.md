# i18n-Keys — Operator Grant-UI (Human `canWrite`, CYP-189)

> Owner: UIUX-Designer · Story **CYP-189** · Stand: 2026-07-02 · Status: Vorschlag — **Key-Sync-Punkt** (PO koordiniert CYP-7
> zwischen UIUX/Dev/Tester; Keys landen MIT dem konsumierenden Dev-Slice — Shared-Key-Drift).
> Konvention (verifiziert gg. `app/shared/src/commonMain/composeResources/values/strings.xml`, develop `3832bb4`):
> **Underscore-Realkeys** (keine Punkte), Argumente positional `%1$s`. **DE = Default** (`values/`), **EN** (`values-en/`).
> Parität Pflicht. Prefix `acl_` / `a11y_acl_` — Erweiterung des bestehenden ACL-Katalogs (CYP-19/CYP-48).

## Neue Keys (5)

### Subjekt-Gruppen + Human-Marker (Unterscheidung Human vs Agent, Spec §2)
| Key | DE | EN |
|---|---|---|
| `acl_agents_group` | Agenten | Agents |
| `acl_humans_group` | Menschen | Humans |
| `acl_human` | Mensch | Human |

> `acl_agents_group`/`acl_humans_group` = Band-Köpfe im Wide-Grid; `acl_human` = inline-Marker je Menschen-Subjekt (load-bearing
> in der Narrow-Card-Ansicht, wo die Gruppierung fehlt). Neutral, kein Prestige. **Farbe nie alleiniger Träger** — Text + a11y.

### Server-Naht: Ghost-Channel 404 (Spec §5)
| Key | DE | EN |
|---|---|---|
| `acl_channel_gone` | Kanal existiert nicht mehr – Ansicht aktualisieren | Channel no longer exists – refresh the view |

> **Bewusst distinct von `acl_change_failed`** („erneut versuchen"): ein Ghost-Channel (Grant auf verschwundene `channelId` →
> Backend-404, gebaute Härtung) ist **nicht** durch Retry heilbar, sondern durch Neuladen. Ehrliche, nicht-retrybare Copy.

### a11y
| Key | DE | EN |
|---|---|---|
| `a11y_acl_human_subject` | Mensch %1$s | Human %1$s |

> Macht die Human-Achse für Screen-Reader hörbar (Marker/Farbe nicht alleiniger Träger). `%1$s` = Menschen-Label
> (`displayName ?? shortId`).

---

## Reuse (keine neuen Keys — gg. Code verifiziert @ `3832bb4`)

Die Grant-Zelle je (Channel × Mensch) reused den **kompletten** bestehenden ACL-Wortschatz — 1:1 dieselben Keys wie für
Agenten-Zellen:

| Reused Key | Zweck in der Human-Grant-Zelle |
|---|---|
| `acl_read` / `acl_write` | Read/Write-Toggle-Label („Lesen"/„Antworten") |
| `acl_granted` / `acl_denied` | granted vs not (neutral); Grant/Revoke-Zustand |
| `acl_pending` / `acl_enforced` | Pending ≠ Enforced (AclEvent-Echo = Source of Truth) |
| `acl_change_failed` | Timeout/Fehler bei ausbleibendem Echo („erneut versuchen") |
| `acl_write_only_hint` | `canWrite && !canRead` → „Antworten ohne Lesen – ungewöhnlich" (Mensch sendet blind) |
| `a11y_acl_cell` | „%1$s in Kanal %2$s: Lesen %3$s, Antworten %4$s" (%1$s = Menschen-Label) |
| `a11y_acl_toggle_read` / `a11y_acl_toggle_write` | Toggle-a11y (%1$s = Menschen-Label) |
| `a11y_acl_pending` | Pending-a11y |
| `acl_operator_required` / `acl_partial_view` | Operator-Gate / Teilansicht (unverändert) |

**Nicht member-facing / nicht auf Menschen-Spalten:** `acl_po_critical`, `acl_po_lockout_warning`, `acl_self_blind_warning`,
`acl_po_protected` — Menschen sind **nicht** PO; keine Lockout-Leitplanke auf Menschen-Zellen (Spec §7.2). Kein neuer Key nötig,
diese bleiben Agenten-/PO-Achse.

**Menschen-Label:** kein Key — reiner Reuse der Roster-Helper `memberLabel`/`shortId` (`WorkspaceRosterPanel.kt`); kein
Roster-Tier-Text (`workspace_tier_*`) in der ACL-Matrix (andere Achse, Spec §2).

---

## Zähl-/Validierungs-Block (Selbst-Validierung)

- **Neue Keys gesamt: 5** — `acl_agents_group`, `acl_humans_group`, `acl_human`, `acl_channel_gone` (4× `acl_`) +
  `a11y_acl_human_subject` (1× `a11y_acl_`). **DE+EN-Parität 5/5.**
- **Argument-Keys (`%1$s`): 1** — `a11y_acl_human_subject` (DE+EN gleiche Argument-Zahl).
- **0 Kollision** gg. `strings.xml`/`values-en` @ `3832bb4` (im Push via `grep` gegengeprüft: kein bestehender
  `acl_agents_group`/`acl_humans_group`/`acl_human`/`acl_channel_gone`/`a11y_acl_human_subject`).
- **Reuse (keine neuen Keys):** kompletter ACL-Zell-Wortschatz (Read/Write/granted/denied/pending/enforced/change_failed/
  write_only_hint + a11y) + Roster-`memberLabel`. Agent-Rollen-Keys (`agent_role_*`) und Roster-Tier-Keys (`workspace_tier_*`)
  **unangetastet**.
- **Kein Secret in Keys:** keine E-Mail/Token/Endpoints; Menschen-Label = neutraler Anzeigename/Kurz-ID; `identityId` nur als
  `%1$s`-Argument-Wert (kein roher Key-Dump).
- **⚠ Shared-Key-Drift:** landen in `:app:shared`-Resources → konsumierendes Modul (Dev-Impl + Test-Modul CYP-7) muss re-syncen.
  **Lieferung mit dem CYP-189-Dev-Slice timen** (PO koordiniert).
