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
  → 220dp Pane + **nur 132dp** Thread/Konversation bei 411dp (Tester-Overlay, Pixel_9a, Split x=656);
  der **Composer ist auf ~117dp gequetscht** — weit unter seinem Min (CYP-26, 280dp).
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
  411dp-Two-Pane nur ~117dp → schon jetzt weit unter Min, doppelt kaputt — Tester-Overlay bestätigt).
- **Tester-Validierung (Overlay, 2026-06-30):** „<600dp → Thread+Composer als **Nav-Push** statt
  side-by-side" deckt sich exakt mit diesem `comm_back`-Single-Pane-Muster.

**ProductLeadPanel:**
```
maxWidth ≥ 600dp → Two-Pane (SnapshotList 0.4f + DetailPane 0.6f)                           [unverändert]
maxWidth <  600dp → Single-Pane:
    kein Snapshot gewählt → SnapshotList (voll-breit)
    Snapshot gewählt      → DetailPane (voll-breit) + Zurück-Affordanz (Reuse comm_back)
```
- **TriggerBar (Report generieren) bleibt oberhalb des Master/Detail, immer sichtbar** — sie ist kein Pane.
  **Aber sie hat selbst eine Dichte-Lücke** (Tester-Nachmessung mit Operator-Token, §3.1) → reflowt, s. u.
- Snapshot≠Live-Disclosure (CYP-89/90) + Severity-Rail (Farbe+Symbol+Label) **unverändert** — nur das
  Pane-Layout adaptiert. Selektion über den bestehenden ausgewählten-Snapshot-State.

## 3.1 Control-Row-Dichte auf den CYP-156-Panels (Class-B-Mechanik, hier gehomet)

Die ⚠️-Nachmessung (Operator-Token, Pixel_9a 411dp) fand auf den CYP-156-Panels **Control-Rows**, die
horizontal quetschen — **dieselbe Mechanik wie Klasse B** (Control-Row-Dichte ohne Wrap), aber **Ticket-
Heimat = CYP-156**: dieselben Dateien (`ProductLeadPanel.kt`, `AgentManagementPanel.kt`), Herkunft = die
CYP-156-⚠️-Nachmessung, **keine CYP-154-Datei** → CYP-156 bleibt konfliktfrei. (CYP-158 bleibt rein
Event-Log; nichts vermischt.) Mechanik geteilt mit CYP-158 (`FlowRow`) — nur die Foundation-API, **kein**
geteilter Code/Token → keine Merge-Kopplung.

**🔴 ProductLead-TriggerBar (gemessen):** `report/ProductLeadPanel.kt` `TriggerBar` Z. 89–100 = ein
`Row(fillMaxWidth, spacedBy 8.dp)` aus **Label `report_generate` + 3 Buttons** (`report_type_usage/_status/
_defects`), **kein Wrap**. Bei 411dp summieren sich Label + 3 Buttons + Spacing über die Innenbreite → der
schmalste Button (Tester: ~47dp) bricht den Text **2-zeilig** („Status/report").
- **Soll:** `Row` → **`FlowRow`** (Label + Buttons umbrechen in 2 Reihen statt einen Button zu zerbrechen) —
  konsistent mit dem CYP-158-Control-Bar-Muster. (Alternative: deterministische 2-Reihen — Label-Zeile +
  Button-Reihe darunter, lt. Tester. Mein Default: FlowRow; minimal-invasiv, gleiche Knoten/Tags.)
- **Buttons brechen nie intern:** jeder Button-Text `maxLines = 1` (kein „Status/report"-Bruch mehr).
- **Tags unverändert:** `productLead.trigger` + `.trigger.usage/.status/.defects` bleiben (nur Container
  Row→FlowRow). **Kein neuer Tag, kein neuer Key.**

**🟡 AgentMgmt-AgentRow (Soll spezifiziert, empirisch später — server-gestützt):** `agentmgmt/
AgentManagementPanel.kt` `AgentRow` Z. 142 = `Row` { Name `weight(1f)` maxLines=1 Ellipsis · Rolle · Status
· Edit · Remove } = **5 Spalten**; lange DE-Button-Labels („Bearbeiten"/„Entfernen") erdrücken bei 411dp die
Name-Spalte. **Heute nicht messbar** (Empty-State; Token schaltet nur Surface-Sichtbarkeit, keine Agent-
Reihen) → braucht **server-gestützte** Testumgebung; bleibt offene CYP-156-Restmessung.
- **Soll:** unter `PANE_COLLAPSE_WIDTH` (600dp, an der Zeilen-Innenbreite gemessen) reflowt die Zeile
  **deterministisch 2-zeilig**: Zeile 1 = Name + Rolle + Status (Identität/Zustand), Zeile 2 = Aktionen
  (Edit/Remove). Alternative: Aktionen in ein Overflow-(⋮)-Menü. Mein Default: 2-Zeilen (sichtbarer als ein
  verstecktes Menü; Guardrails [nur-PO-nicht-entfernbar] bleiben sichtbar/disabled vor der Aktion).
- **Tags unverändert:** `agentMgmt.item.<id>` + `.itemEdit/.itemRemove` + `agentView.status.<id>` bleiben
  (nur Layout). **Kein neuer Tag, kein neuer Key.** (Name-`weight(1f)` + Ellipsis verhindern Char-Bruch der
  Name-Spalte bereits; der Rereflow rettet die Aktionen.)

## 4. a11y / RTL
- Single-Pane-Navigation führt den Fokus: Auswahl → Detail-Fokus, Zurück → Listen-Fokus. `comm_back` trägt
  bereits `contentDescription` (von ACL/EventBrowse genutzt). Keine Information nur über Layout-Position.
- RTL: Master/Detail + Zurück-Chevron spiegeln mit der Layout-Direction (wie Phone-Pager CYP-54). Breakpoint
  ist richtungsneutral (Breite).

## 5. Verifikation / ⚠️ daten-abhängig
- Code-Verifikation: §1 (CommPanel Z. 78/84; ProductLead Z. 81–83; AclPanel `NARROW_BREAKPOINT=600` Z. 75 als
  Referenz; `comm_back` von AclPanel + EventBrowsePanel Z. 297 genutzt).
- **ProductLead-TriggerBar (§3.1): gemessen** (Operator-Token, 411dp) — Dichte-Bruch bestätigt; Soll = FlowRow.
- **⚠️ ProductLead-Report-Pane** (Single-Pane mit echten Report-Daten) + **⚠️ AgentMgmt-AgentRow** (§3.1):
  empirisch erst mit **server-gestützter** Umgebung nachmessbar (Operator-Token schaltet nur Surface-
  Sichtbarkeit, keine Daten/Reihen). Soll-Zustand hier spezifiziert; Messung bestätigt später.

## 6. §-Ask
- `comm_back`-Reuse für **beide** Single-Pane-Zurück (Comm + ProductLead) — Default. Falls ein report-eigener
  Back-Wortlaut gewünscht ist, koordiniert ein neuer Key (sonst Reuse). Siehe keys.md.
