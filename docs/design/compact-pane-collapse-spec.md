# Compact Pane-Collapse — CommPanel + ProductLeadPanel (CYP-156, Klasse A)

> Owner: UIUX · Stand 2026-06-30 · docs-only · Grounded @ develop `d57dccb`.
> Auslöser: Android-Tester, 411dp-Phone (Pixel_9a, Compact) — CommPanel rendert zweispaltig.
> Scope: **nur Klasse A** (fehlender Pane-Breakpoint). Klasse B (Event-Log-Dichte) = CYP-158, Klasse C
> (ProjectSwitcher-Dropdown) = CYP-159. Gesamt-Audit: `uiux/COMPACT-WIDTH-AUDIT-DESIGN.md`.
> **CYP-156 berührt KEINE CYP-154-Datei → mergt unabhängig.**

## 1. Problem (code-verifiziert)
Zwei Panels haben einen **unbedingten Zwei-Pane-`Row` ohne Breakpoint** → bei jeder Breite zweispaltig,
also auch auf 411dp-Phone:
- **`comm/CommPanel.kt`** Z. 78 `Row(fillMaxSize)` + Z. 84 fixe `Modifier.width(220.dp)`-Channel-Pane.
  (Die zwei `BoxWithConstraints` liegen **nur im Composer**, Z. 276 — **nicht** auf Panel-Ebene.)
  → 220dp Pane + ~191dp Konversation bei 411dp; der Composer ist dann sogar unter seinem Min (CYP-26).
- **`report/ProductLeadPanel.kt`** Z. 81–83 `Row { SnapshotList(weight 0.4f); DetailPane(weight 0.6f) }`,
  kein Breakpoint → 40/60-Split bei 411dp. (Tester sah nur den Gated-State; Code belegt den Defekt mit
  Report-Daten — empirische Bestätigung kommt mit CYP-151-Operator-Token, §5.)

## 2. Soll — ein geteilter Breakpoint, an der Panel-Innenbreite gemessen
- **`PANE_COLLAPSE_WIDTH = 600.dp`** (Material `WindowWidthSizeClass.Compact`-Obergrenze; deckt 411dp und
  alle Phones). **<600dp ⇒ Single-Pane**, ≥600dp ⇒ Two-Pane (heutiges Layout unverändert).
- **An der Panel-Innenbreite messen** via `BoxWithConstraints { maxWidth }` — **nicht** Screen/Window: ein
  Comm-Fenster kann auf großem Desktop schmal gezogen sein (CYP-26), im Phone-Pager (CYP-54) ist das Panel
  Vollbreite. Innenbreiten-Messung ist fenster-agnostisch.
- **Ein geteilter Token** (P1 Adaptive-Scaffold, `uiux/PARITY-DESIGN-GAP-INVENTORY.md`): ersetzt die heutigen
  lokalen Werte. `acl/AclPanel.kt` nutzt bereits `NARROW_BREAKPOINT = 600.dp` (korrekt) → wird auf den
  geteilten Token gehoben. `eventlog/EventBrowsePanel.kt` (`TWO_PANE_MIN_WIDTH = 560.dp`) zieht **in CYP-158**
  auf 600 nach (gehört zu dessen Datei-Scope, hält CYP-156 CYP-154-konfliktfrei).

## 3. Single-Pane-Layouts (= bestehendes AclPanel/EventBrowse-Master/Detail-Muster, KEIN neues Pattern)

**CommPanel:**
```
maxWidth ≥ 600dp → Two-Pane (heute: Channel-Liste 220dp + Konversation)                    [unverändert]
maxWidth <  600dp → Single-Pane:
    kein Kanal gewählt → Channel-Liste (voll-breit)
    Kanal gewählt      → Konversation (voll-breit) + Zurück-Affordanz (Reuse comm_back)
```
- Selektion (der `selectedId`/`channelId`-State existiert bereits, Two-Pane nutzt ihn) leitet „Liste vs.
  Konversation" ab — **kein neuer State**.
- Composer (CYP-26: Min 280dp + Icon-Compaction) bekommt in Single-Pane die volle Breite (heute im
  411dp-Two-Pane nur ~191dp → schon jetzt unter Min, doppelt kaputt).

**ProductLeadPanel:**
```
maxWidth ≥ 600dp → Two-Pane (SnapshotList 0.4f + DetailPane 0.6f)                           [unverändert]
maxWidth <  600dp → Single-Pane:
    kein Snapshot gewählt → SnapshotList (voll-breit)
    Snapshot gewählt      → DetailPane (voll-breit) + Zurück-Affordanz (Reuse comm_back)
```
- **TriggerBar (Report generieren) bleibt oberhalb des Master/Detail, immer sichtbar** — sie ist kein Pane.
- Snapshot≠Live-Disclosure (CYP-89/90) + Severity-Rail (Farbe+Symbol+Label) **unverändert** — nur das
  Pane-Layout adaptiert. Selektion über den bestehenden ausgewählten-Snapshot-State.

## 4. a11y / RTL
- Single-Pane-Navigation führt den Fokus: Auswahl → Detail-Fokus, Zurück → Listen-Fokus. `comm_back` trägt
  bereits `contentDescription` (von ACL/EventBrowse genutzt). Keine Information nur über Layout-Position.
- RTL: Master/Detail + Zurück-Chevron spiegeln mit der Layout-Direction (wie Phone-Pager CYP-54). Breakpoint
  ist richtungsneutral (Breite).

## 5. Verifikation / ⚠️ daten-abhängig
- Code-Verifikation: §1 (CommPanel Z. 78/84; ProductLead Z. 81–83; AclPanel `NARROW_BREAKPOINT=600` Z. 75 als
  Referenz; `comm_back` von AclPanel + EventBrowsePanel Z. 297 genutzt).
- **⚠️ ProductLead-Report empirisch nach CYP-151-Merge** (Operator-Token) bei 411dp nachmessen — der
  Soll-Zustand ist hier bereits spezifiziert; die Messung bestätigt ihn mit echten Report-Daten.

## 6. §-Ask
- `comm_back`-Reuse für **beide** Single-Pane-Zurück (Comm + ProductLead) — Default. Falls ein report-eigener
  Back-Wortlaut gewünscht ist, koordiniert ein neuer Key (sonst Reuse). Siehe keys.md.
