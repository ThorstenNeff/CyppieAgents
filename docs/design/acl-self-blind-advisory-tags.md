# ACL Operator-Selbst-Erblindung — testTag-Delta

> Owner: UIUX-Designer · Companion zu `acl-self-blind-advisory-spec.md` / `-keys.md` · Stand 2026-07-17 ·
> **Design-Pass, kein Bau.** Schema gg. `AclMatrixTags.kt` (Test-Contract v0.5 §2):
> `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`, prefixless, Segment-Werte `[A-Za-z0-9-]+`
> (keine Punkte). Area `aclMatrix`. **Geteilte API mit QA (CYP-7) — nicht stumm umbenennen, über PO koordinieren.**

## Delta

Die Advisory ist **kein neuer Node** und **kein neuer Top-Level-Tag** — sie ist ein **Disclosure-Qualifier auf der
Zelle**, exakt wie `PROTECTED` (`acl_po_protected`). Reuse der bestehenden `cellQualifier`-Bahn:

| Was | Wert | Herkunft |
|---|---|---|
| **NEU: 1 Enum-Member** `CellQualifier.SELF_BLIND` | wire `"selfBlind"` | reiht sich in `CellQualifier` (PENDING/ENFORCED/NON_MEMBER/PO_CRITICAL/CONFLICT/PROTECTED) |
| **Tag (abgeleitet, kein neuer const)** | `aclMatrix.cell.<channelId>.<agentId>.selfBlind` | `AclMatrixTags.cellQualifier(ch, ag, SELF_BLIND)` — bestehende Funktion, kein Rename |

Der Marker sitzt auf **derselben** Zelle wie das Read-/Write-Toggle (`aclMatrix.cell.<ch>.<ag>`), analog zu
`.protected` — QA assertet die Selbst-Erblindung deterministisch über den Qualifier, nicht über Farbe (WCAG 1.4.1).

## Retire (B1, spec §5)

| Tag | Wert | Schicksal |
|---|---|---|
| `AclMatrixTags.SELF_BLIND_WARNING` | `aclMatrix.selfBlindWarning` | **RETIRE bei B1** — der Selbst-Halte-Dialog entfällt (READ zieht auf die post-commit-Inline-Advisory um). Der **PO-Lockout**-Dialog (`LOCKOUT_DIALOG`/`.confirm`/`.cancel`) **bleibt** (Guarantee-brechend → Guard, unverändert). |

> **Wichtig für QA (CYP-7):** `SELF_BLIND_WARNING` ist heute in einem Test verankert (Selbst-Erblindungs-Dialog).
> Bei B1 wandert die Assertion vom **Dialog-Node** (`aclMatrix.selfBlindWarning`) auf den **Zell-Qualifier**
> (`aclMatrix.cell.<ch>.<ag>.selfBlind`) — **das ist ein geteilter Contract-Change, PO-koordiniert, nicht
> unilateral.** Bei B2 bliebe der Dialog-Tag; B1 ist empfohlen.

## a11y-Verankerung (kein Tag, semantics)

- **Announce (Polite):** beim Erscheinen des Markers wird `a11y_acl_self_blind_read`/`_write` einmal höflich
  angekündigt (kein Assertive — nichts Dringendes; s. spec §7).
- **stateDescription:** solange der Selbst-Erblindungs-Zustand hält, trägt die **Zelle** die Klausel als Teil ihrer
  `a11y_acl_cell`-Zustandsbeschreibung (der SR hört sie auch beim späteren Anfokussieren, nicht nur im Moment).

## Self-Validation

- **0 neue Top-Level-Tag-Consts** · **1 neuer `CellQualifier`-Member** (`SELF_BLIND`/`"selfBlind"`), Segment
  `[A-Za-z0-9-]+`-konform (camelCase, kein Punkt).
- **Reuse-first:** die `cellQualifier`-Bahn + der `acl_po_protected`-`StateMarker`-Präzedenzfall — kein neuer
  Render-Pfad, keine neue Komponente.
- **Retire-Flag ist PO-geteilt:** `SELF_BLIND_WARNING`-Umzug an QA (CYP-7) markiert als koordinierter
  Contract-Change, nicht stumm.
- **Kollision:** `.selfBlind`-Qualifier gg. bestehende `CellQualifier`-Wire-Werte @ `1da14371` = frei.
- Jeder Tag ist in `acl-self-blind-advisory-spec.md` verankert; jeder sichtbare/a11y-Key in `-keys.md`.
