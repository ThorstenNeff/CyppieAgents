# testTag-Schema — Operator Grant-UI (Human `canWrite`, CYP-189)

> Owner: UIUX-Designer · Story **CYP-189** · Stand: 2026-07-02 · Status: Vorschlag
> Erweitert `acl/AclMatrixTags.kt` (Area `aclMatrix`, CYP-19/CYP-48) — verifiziert gg. develop `3832bb4` (echtes Objekt gelesen).
> **Schema (Test-Contract v0.5 §2):** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, **prefixless**. Segmentwerte
> `[A-Za-z0-9-]+` (**keine Punkte** — Trenner). `identityId` = Kratos-UUID (Hyphen, **kein Punkt**) → als Selektor-Segment safe,
> passt in denselben `<agentId>`-Slot wie Agenten-IDs.
> **Vertrag zwischen Dev und QA (CYP-7):** diese Tags sind eine **API**, nicht still umbenennen; koordiniert über den PO.

Macht die Human-Grant-Erweiterung der ACL-Matrix stabil adressierbar — insbesondere die **Disclosure-Anker**
(Human-vs-Agent-Achse, Operator-only-Enumeration, granted/pending/enforced, Ghost-Channel-404).

---

## 0. Konvention — Reuse des Subjekt-Slots

Die ACL-Matrix modelliert Subjekte als `<agentId>`-Segment. Ein Mensch ist ein weiteres Subjekt → **die bestehenden Zell-/
Toggle-Tags werden unverändert reused**, mit `identityId` im `<agentId>`-Slot:

- `aclMatrix.cell.<channelId>.<identityId>` — Grant-Zelle je (Channel × Mensch)
- `aclMatrix.cell.<channelId>.<identityId>.read` / `.write` — Read-/Write-Toggle (Reuse `AclMatrixTags.read/write`)
- `aclMatrix.cell.<channelId>.<identityId>.readonly` — Read-only-Chip (Nicht-Operator; s. u. — Menschen-Zellen sind aber
  operator-only, also praktisch nie readonly-für-Mensch, s. Invariante E)
- `aclMatrix.cell.<channelId>.<identityId>.<pending|enforced|conflict>` — Disclosure-Qualifier (Reuse `CellQualifier`)
- `aclMatrix.colHeader.<identityId>` — Spaltenkopf je Mensch (Reuse `AclMatrixTags.colHeader`)

**Kein neuer Zell-/Toggle-/Qualifier-Tag** — der Subjekt-Slot trägt `identityId` genauso wie `agentId`.

---

## 1. Neue Tags (3) — Human-vs-Agent-Achse + Enumeration

| Element | testTag | Zweck |
|---|---|---|
| Agenten-Gruppen-Kopf | `aclMatrix.agentsGroup` | Band-Überschrift „Agenten" (`acl_agents_group`) — trennt Subjekt-Bänder |
| Menschen-Gruppen-Kopf | `aclMatrix.humansGroup` | Band-Überschrift „Menschen" (`acl_humans_group`); **nur bei Operator gemountet** (Enumeration) |
| Human-Spalten-Marker | `aclMatrix.colHeader.<identityId>.human` | Qualifier an der Menschen-Spalte → QA prüft „Subjekt ist Mensch" (nicht Agent) |

> **Vorschlag `AclMatrixTags`-Ergänzung:**
> ```kotlin
> const val AGENTS_GROUP = "aclMatrix.agentsGroup"
> const val HUMANS_GROUP = "aclMatrix.humansGroup"
> fun humanMarker(identityId: String) = "${colHeader(identityId)}.human"  // aclMatrix.colHeader.<identityId>.human
> ```
> Alternativ die Human-Kennzeichnung als `CellQualifier.HUMAN("human")` — **abgelehnt**, weil „Mensch" eine **Subjekt-**
> (Spalten-)Eigenschaft ist, keine **Zell-**Eigenschaft; ein colHeader-Qualifier ist die korrekte Ebene.

---

## 2. Test-relevante Disclosure-Anker (für QA/CYP-7)

Diese Tags existieren **gerade**, damit die Honesty-Eigenschaften der Human-Grant-UI testbar sind:

- **Human vs Agent (Unterscheidung 1):** `aclMatrix.humansGroup` präsent + jede Menschen-Spalte trägt
  `aclMatrix.colHeader.<identityId>.human`; Agenten-Spalten tragen ihn **nicht** → QA trennt die Achsen ohne Pixel-Prüfung.
- **Operator-only-Enumeration (Invariante E, Spec §4):** im **Nicht-Operator/`partialView`-Baum** sind `aclMatrix.humansGroup`
  **und** alle `aclMatrix.colHeader.<identityId>[.human]` / `aclMatrix.cell.<channelId>.<identityId>.*` **ABWESENT** (strukturelle
  Auslassung, nicht disabled). QA prüft das Nicht-Leaken des Rosters über die **Abwesenheit** dieser Knoten — analog dem
  operator-only-Roster-Fenster.
- **granted vs not (Unterscheidung 2):** Menschen-`.write`-Toggle an/aus (Reuse; `acl_granted`/`acl_denied`). Grant/Revoke =
  Toggle-Zustand.
- **Pending ≠ Enforced:** nach Toggle `…​.pending`; erst nach `AclEvent`-Echo (nicht nach PUT-200) `…​.enforced` (Reuse
  `CellQualifier`). Ein Test darf einen Menschen-Grant **nie** schon im Pending/nach-200 als durchgesetzt werten.
- **Menschen auf allen Channels grantbar (Spec §3.1):** eine Menschen-Zelle trägt `.read`/`.write`-Toggles **auch** auf
  Kanälen, deren `members` der Mensch nicht ist — **kein** `.nonMember`-Qualifier auf Menschen-Zellen (die Membership-`„—"`-
  Auslassung ist Agenten-Achse). QA: Menschen-Zelle hat Toggles, nicht `nonMember`.
- **Ghost-Channel-404 (Spec §5):** ein Menschen-Grant-PUT auf eine verschwundene `channelId` endet in Revert + `acl_channel_gone`
  (nicht-retrybar), **nicht** in `…​.enforced`. (Kein eigener Tag nötig — der Banner-Text/Revert ist über den bestehenden
  Notice-Pfad + `acl_channel_gone`-Key adressierbar; falls QA einen dedizierten Anker braucht, koordiniert nachziehen.)

---

## 3. Was NICHT für Menschen gilt (bewusste Auslassung)

- **`.nonMember`** — nicht auf Menschen-Zellen (Menschen sind auf allen Channels grantbar, Spec §3.1).
- **`.poCritical` / `lockoutDialog` / `selfBlindWarning` / `.protected`** — PO-/Operator-Leitplanken; Menschen sind nicht PO →
  kein Lockout-Pfad auf Menschen-Spalten (Spec §7.2). Diese Tags bleiben Agenten-/PO-Achse, unverändert.
- **`presetRestore`/`preset*`** — Hub-and-Spoke-Preset betrifft Agenten-Topologie, nicht Menschen-Grants; unverändert.

---

## 4. Hand-off-Hinweis (Dev + QA)

- Ergänzungen gehören in das bestehende `AclMatrixTags`-Objekt (`:app:shared`, `acl/`), **mit dem Tester (CYP-7) geteilt** —
  Änderungen koordiniert über den PO.
- **Shared-Tag-Drift:** Tags sind ein geteilter Vertrag; das konsumierende Test-Modul zieht zeitgleich nach (mit dem
  CYP-189-Dev-Slice timen).

---

## 5. Zähl-/Validierungs-Block (Selbst-Validierung)

- **Neue Tags gesamt: 3** — `aclMatrix.agentsGroup`, `aclMatrix.humansGroup`, `aclMatrix.colHeader.<identityId>.human`
  (1 Konvention/Funktion).
- **Reuse (keine neuen Tags):** `aclMatrix.cell.<channelId>.<identityId>` + `.read`/`.write`/`.readonly` + `CellQualifier`
  (`pending`/`enforced`/`conflict`) + `aclMatrix.colHeader.<identityId>` — der `<agentId>`-Slot trägt `identityId` identisch.
- **0 Kollision** gg. `AclMatrixTags.kt` @ `3832bb4` (kein `agentsGroup`/`humansGroup`/`.human`-Qualifier vorhanden).
- **Punktfrei-Invariante:** `identityId` (Kratos-UUID) enthält keine Punkte → als Selektor-Segment gültig.
- **Reuse-gegen-Code verifiziert:** `AclMatrixTags.cell/read/write/colHeader` + `CellQualifier` existieren real (`AclMatrixTags.kt`
  gelesen); die 3 neuen Tags erweitern dasselbe Objekt.
