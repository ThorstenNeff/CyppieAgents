# Multi-User MEMBER/OPERATOR — testTags (CYP-80 / S18)

> Owner: UIUX-Designer · Story CYP-80 · Stand 2026-07-02 · Status: Vorschlag — **testTag-Sync-Punkt** (PO koordiniert CYP-7)
> Schema (Test-Contract v0.5 §2): **prefixlos** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, Segmentwerte
> `[A-Za-z0-9-]+` (camelCase, keine Punkte im Wert). **Area `workspace` NEU** — 0 Kollision gg. die bestehenden `*Tags.kt`
> (Code = Source of Truth @ `8cf393a`). Träger: neues `workspace/WorkspaceTags.kt` (`object WorkspaceTags`), Stil wie `CommTags`.
> **Wichtig:** die **meisten** tier-abhängigen Controls brauchen **keine neuen Tags** — sie reusen die bestehenden GATED-/
> read-only-Tags (`settings.repo.gateHint`, `agentMgmt.gateHint`, `projectMgmt.gateHint`, `crossProject.gateHint`,
> `aclMatrix.partialView`, `comm.composerReadonly`, Lifecycle-`start/stop/restartBtn`), da sich nur der Boolean ändert, nicht
> die Knoten. Neu ist **nur** das Rollen-Surface.

## Neue Tags (Area `workspace`)

| Tag | Element | Sichtbar für |
|---|---|---|
| `workspace.roleIndicator` | Rollen-Indikator in der Top-Leiste („Du bist Operator/Mitglied") | alle |
| `workspace.operatorName` | Operator-Identität, die dem MEMBER gezeigt wird („Operator: %1$s") | MEMBER |
| `workspace.members` | Mitglieder-Roster-Container | **nur OPERATOR** (strukturell ausgelassen für MEMBER) |
| `workspace.member.<id>` | Roster-Zeile je Nutzer (scope = user-id) | nur OPERATOR |
| `workspace.member.<id>.role` | Tier-Label der Zeile (Operator/Mitglied), + `.you`-Qualifier für den eigenen Eintrag | nur OPERATOR |

> `workspace.member.<id>.you` = Qualifier für den eigenen Roster-Eintrag (Markierung „Du"). Kein separater Tag nötig.

## Bedingte / Forward-Tags (nicht materialisiert)

| Tag | Element | Bedingung |
|---|---|---|
| `workspace.member.<id>.promote` | MEMBER → OPERATOR befördern (disabled+GATED) | **§-Ask 3** — nur falls Backends Matrix Operator-Übertragung aufnimmt |
| `workspace.member.<id>.demote` | OPERATOR → MEMBER (Nachfolge) | §-Ask 3 |

> Forward-prep, hier **nicht** gezählt/geliefert. Kämen als additiver Folge-Slice.

---

## Enumerations-Naht (Tag-Ebene)

`workspace.members` + `workspace.member.*` werden für einen MEMBER **gar nicht gemountet** (strukturelle Auslassung, nicht
disabled) — ein MEMBER-Test-Tree enthält diese Knoten nicht. So kann kein Full-Tree/Maestro-Lauf den Roster aus einer
MEMBER-Sitzung enumerieren. (Die API muss den Roster einem MEMBER-Token ohnehin verweigern — Naht an Backend, Spec §3.3.)

---

## Zähl-/Validierungs-Block (Selbst-Validierung)

- **Neue Tags gesamt: 5** — Area `workspace` (`roleIndicator`, `operatorName`, `members`, `member.<id>`, `member.<id>.role`).
- **Forward/bedingt (nicht gezählt): 2** — `member.<id>.promote/.demote` (§-Ask 3).
- **Reuse (keine neuen Tags):** alle bestehenden GATED-/read-only-/Lifecycle-Knoten der §4-Matrix (der Boolean wechselt,
  nicht der Knoten).
- **0 Kollision:** Area `workspace` existiert in keiner `*Tags.kt` (im Push via `grep` gegengeprüft).
- **Kein Secret in Tags:** `member.<id>` = stabile user-id als scope (kein E-Mail/Token als Segment).
