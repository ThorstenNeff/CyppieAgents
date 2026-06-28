# testTag-Schema — ACL-Matrix-UI (v0.1)

> Owner: UIUX-Designer · Ticket: **CYP-19** (Epic CYP-2, S7) · Status: **Entwurf** · Stand: 2026-06-28
> Begleitend zu `docs/ACL-MATRIX.md`, `docs/design/acl-matrix-tokens.json`, `docs/design/acl-matrix-keys.md`.
> **Schema (Test-Contract v0.5 §2, `docs/TEST-CONTRACT.md`):** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, **prefixless**. Segmentwerte `[A-Za-z0-9-]+` (**keine Punkte** — kollidieren mit dem Trenner und Maestros Regex-Selektor).
> **Vertrag zwischen Dev und QA (CYP-7):** Diese Tags sind eine **API**, nicht still umbenennen. Single source of truth; Vorbild `comm/CommTags.kt` + `agentview/AgentViewTags.kt`. Vorschlag: `:app:shared`-Objekt **`AclMatrixTags`** (`aclMatrix`-Area).

Macht die ACL-Matrix für Compose-UI-Tests + Maestro stabil adressierbar — insbesondere die **Disclosure-Anker** (Pending vs. Enforced, PO-Leitplanke, Nicht-Member, Preset-Teilausfall).

---

## 0. Konventionen

- **Eine Area, Single-Instance:** `aclMatrix` (ein Fenster, nicht instanz-scoped).
- **Zellen-Selektor = (channelId, agentId)** — beide sind punktfrei (`po-frontend`, `frontend`; Hyphen erlaubt, kein Punkt) → als zwei Selektor-Segmente safe: `aclMatrix.cell.<channelId>.<agentId>`.
- **R/W-Schalter** als eigene Knoten unter der Zelle (`.read`/`.write`), damit Tests Lese- **und** Antwortrecht getrennt assertieren.
- **Qualifier-Vokabular** je Zelle: `pending` · `enforced` · `nonMember` · `poCritical` · `conflict` — erlauben Disclosure-Assertions ohne Pixel-Prüfung.

---

## 1. Matrix & Achsen — Area `aclMatrix`

| Element | testTag | Zweck |
|---|---|---|
| Matrix-Container | `aclMatrix.grid` | LazyGrid/Table Kanal × Agent |
| Agenten-Kopf (Spalte) | `aclMatrix.colHeader.<agentId>` | Spaltenkopf je Agent |
| Kanal-Kopf (Zeile) | `aclMatrix.rowHeader.<channelId>` | Zeilenkopf je Kanal |
| Empty-State | `aclMatrix.empty` | keine Kanäle/Agenten |
| Teilansicht-Banner | `aclMatrix.partialView` | „nur deine Kanäle" (Agent/kein Operator) |
| Offline/Stale-Banner | `aclMatrix.connection` | Reuse-Semantik `comm_status_offline` |
| Zugriff entzogen (Laufzeit) | `aclMatrix.accessRevoked` | Operator-Token-Entzug zur Laufzeit (§8) |

## 2. Zelle (Kanal × Agent)

| Element | testTag | Zweck |
|---|---|---|
| Zelle | `aclMatrix.cell.<channelId>.<agentId>` | Schnittpunkt-Container |
| Zelle + Qualifier | `aclMatrix.cell.<channelId>.<agentId>.<pending\|enforced\|nonMember\|poCritical\|conflict>` | Disclosure-Assertion |
| Lese-Schalter (R) | `aclMatrix.cell.<channelId>.<agentId>.read` | `canRead`-Toggle |
| Antwort-Schalter (W) | `aclMatrix.cell.<channelId>.<agentId>.write` | `canWrite`-Toggle |
| Read-only-Chip | `aclMatrix.cell.<channelId>.<agentId>.readonly` | statt Schalter, wenn kein Operator |

> **Nicht-Member:** Zelle trägt `…​.nonMember` und **keine** `.read`/`.write`-Knoten — QA prüft N/A über die **Abwesenheit** der Schalter, nicht über einen deaktivierten Toggle.

## 3. PO-Leitplanke & Server-Schutz

| Element | testTag | Zweck |
|---|---|---|
| Konsequenz-Dialog (PO) | `aclMatrix.lockoutDialog` | Warn-Dialog beim Abschalten PO-kritischer R/W |
| Dialog bestätigen | `aclMatrix.lockoutDialog.confirm` | bewusstes Fortfahren |
| Dialog abbrechen | `aclMatrix.lockoutDialog.cancel` | Default |
| Selbst-Blend-Warnung (Operator) | `aclMatrix.selfBlindWarning` | Entzug eigener Operator-`canRead` |
| Server-Schutz-Ablehnung | `aclMatrix.cell.<channelId>.<agentId>.protected` | CYP-49 lehnt PUT ab → `acl_po_protected` |

## 4. Preset „Hub-and-Spoke wiederherstellen"

| Element | testTag | Zweck |
|---|---|---|
| Preset-Button | `aclMatrix.presetRestore` | startet Wiederherstellung |
| Vorschau/Diff | `aclMatrix.presetPreview` | „N Zellen ändern sich" vor Anwendung |
| Vorschau bestätigen | `aclMatrix.presetPreview.confirm` | Preset anwenden — **eigener** Tag (CYP-48-QA B1), NICHT `lockoutDialog.confirm` wiederverwenden |
| Vorschau abbrechen | `aclMatrix.presetPreview.cancel` | Preset-Vorschau verwerfen |
| Fortschritt | `aclMatrix.presetProgress` | „N/M wiederhergestellt" (nicht-atomar) |
| Teilausfall | `aclMatrix.presetPartial` | „N/M – K fehlgeschlagen" |

---

## 5. Test-relevante Disclosure-Anker (für QA/CYP-7)

Diese Tags existieren **gerade**, damit die Honesty-Eigenschaften testbar sind:

- **Pending ≠ Enforced:** nach Toggle erscheint `…​.pending`; erst nach dem **`AclEvent`-Echo** (Source of Truth, **nicht** schon nach PUT-200) wechselt die Zelle auf `…​.enforced`. Ein Test darf einen Toggle **nie** schon im Pending-Zustand — und auch nicht allein nach PUT-200 — als durchgesetzt werten.
- **Preset-Bestätigung eigener Tag:** `aclMatrix.presetPreview.confirm`/`.cancel` (nicht `lockoutDialog.confirm`) — sonst kollidieren Preset-Apply und PO-Leitplanke im Test-Selektor.
- **Nicht-Member ist N/A:** `…​.nonMember` präsent **und** `.read`/`.write` **abwesent** (kein editierbares Grau).
- **PO-Leitplanke advisory:** Abschalten einer `…​.poCritical`-Zelle öffnet `aclMatrix.lockoutDialog` (kein stiller Toggle).
- **Server ist der Schutz (CYP-49):** ein PO-entkoppelnder PUT endet in `…​.protected` + Rückfall, **nicht** in einem durchgesetzten Entzug.
- **Read-only-Sicht:** ohne Operator-Token tragen Zellen `…​.readonly` (Chip) statt `.read`/`.write`-Schaltern; `aclMatrix.partialView` präsent.
- **Preset nicht-atomar:** bei Teilfehler erscheint `aclMatrix.presetPartial`, **nie** ein „erledigt"-Signal.

---

## 6. Hand-off-Hinweis (Dev + QA)

- Tags gehören als `AclMatrixTags`-Objekt in `:app:shared` (wie `CommTags`/`AgentViewTags`) und sind **mit dem Tester (CYP-7) zu teilen** — Änderungen koordiniert über den PO.
- **Shared-Key-Drift** gilt sinngemäß: Tags sind ein geteilter Vertrag; das konsumierende Test-Modul muss zeitgleich nachziehen (mit CYP-48 timen).
