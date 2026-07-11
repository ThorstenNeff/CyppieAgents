# Web→TS/DOM — UX-Spec W8 (Orchestrierung↔Shell-Toggle) + W9 (Comm-Panel + ACL-Matrix)

> Owner: UIUX-Designer · Web→TS-Epic, Slices **W8** + **W9** · Stand 2026-07-11 · Basis `origin/develop` `b3d08dae`
> Docs-only, „konkrete Empfehlung, kein Pixel-Design". Baut auf dem Migrations-Konzept
> (`web-ts-migration-uiux-concept.md`): **Tokens portieren, nicht Optik; Headless + eigene Token-Schicht.**
> **Kein CYP-Key vergeben** (W-Slices) — sobald Keys stehen, re-anker ich Branch/Datei. **Nichts gebaut — Spec.**
>
> **Leitsatz für beide Slices:** die DOM-Umsetzung ist ein **Port der bestehenden Compose-UX**
> (`comm/CommPanel.kt`, `acl/AclPanel.kt`, CYP-333-Toggle), **nicht** ein Neuentwurf. Alle i18n-Keys, testTags
> und **Ehrlichkeits-Invarianten** gelten wortgleich weiter; nur das Rendermedium wechselt. Wo DOM etwas
> **ehrlicher/einfacher** macht, ist es unten markiert.

---

## 0. Gemeinsame Grundlage (gilt W8 **und** W9)

**0.1 Tokens & Theming.** Maritime-M3-Rollen → CSS-Custom-Properties (`--md-sys-color-*`), Zahlen **identisch**
portiert → die WCAG-Audits (CYP-268/304/322/323/337/359) reisen unverändert mit. Light/Dark: `prefers-color-scheme`
+ `[data-theme]`-Override am Wurzelknoten (beide Richtungen gewinnend).

**0.2 Nicht-optimistischer Spiegel (die zentrale Ehrlichkeits-Invariante beider Slices).** UI ist **Anzeige**,
der Server ist **Quelle der Wahrheit**. Ein UI-Zustand kippt **erst nach der Server-Bestätigung**, nie schon
nach dem Klick oder nach `HTTP 200`:
- W9 ACL: Zelle → `pending` nach Toggle, → `enforced` **erst mit dem `AclEvent`-Echo** (nicht nach `PUT 200`).
- W9 Send: Nachricht → `sending…` bis `message.id` bestätigt.
- W8 Toggle: Segment zeigt „Shell" erst als gewählt, wenn das Backend `INTERACTIVE` bestätigt.

**0.3 Farbe nie alleiniger Träger (WCAG 1.4.1).** Jeder Zustand/jede Identität trägt Form/Text/Label **zusätzlich**
zur Farbe. In DOM heißt das u. a.: ein `aria-checked`/`aria-current`/`aria-live`, nicht nur eine Farbklasse.

**0.4 Offenlegung schlägt Layout (mein stehender Grundsatz).** **Kein `text-overflow: ellipsis` auf
Offenlegungssätzen** (Banner, ACL-Hinweise, Fehler, CONTEXT_LOST) — sie dürfen umbrechen. Aktionsbeschriftungen
(Segment, Buttons) dürfen einzeilig kürzen (Bedeutung überlebt als Glyph + `aria-label`).

**0.5 Responsive.** Der bestehende `PANE_COLLAPSE_WIDTH = 600` (`window/LayoutBreakpoints.kt`) wird eine
**Container-Query** (`@container (min-width: 600px)`), gemessen an der **Panel-Innenbreite** (fenster-agnostisch:
ein Fenster kann schmal gezogen oder im Phone-Pager voll sein) — genau die `BoxWithConstraints`-Semantik von heute.

**0.6 Component-Lib.** Headless-Primitive (React: Radix; Svelte: Melt/Bits) + eigene Tokens. **Kein** optisch
meinungsstarkes Kit. Native semantische Elemente bevorzugen, wo sie das A11y gratis liefern (§W9 Tabelle).

---

# TEIL W8 — Orchestrierung ↔ Shell-Toggle (spiegelt Desktop CYP-333)

Die **Zustandslogik, die Banner, die Marker und die Keys aus CYP-333 gelten unverändert.** Neu im DOM sind nur:
(a) die Shell wird real (xterm.js), (b) die Zustandserhaltung ist eine konkrete Mount-Regel, (c) die Z-Order-Regel
löst sich auf. Ich wiederhole CYP-333 nicht — hier nur die DOM-Konkretisierung.

## W8.1 Der Toggle `[ Orchestrierung | Shell ]`

- Segmentierter Control, DOM: `role="radiogroup"` mit zwei `role="radio"`-Segmenten (oder ein Tablist mit
  `aria-selected`) — **nicht** zwei lose Buttons; die gewählte Ansicht ist ein Einzelwert.
- **Nicht-optimistisch (0.2):** „Shell" wird `aria-checked`/`aria-selected=true` **erst** bei Backend-`INTERACTIVE`.
  Während `HANDING_OVER` zeigt das Segment einen Pending-Spinner (`aria-busy=true`), die Live-Auswahl bleibt
  „Orchestrierung". Symmetrisch für Hand-back.
- **Nicht-Operator:** Toggle read-only (`aria-disabled=true`) + der wiederverwendete `workspace_operator_only`-
  Hinweis (CYP-317 „kein Fake-Switch") — er *sieht* den Live-Modus, treibt ihn nie.
- Keys: `terminal_mode_orchestration` / `terminal_mode_terminal` (aus CYP-333 §8, existieren).

## W8.2 Zustandserhaltung beim Wechsel — die load-bearing Regel

Der Wechsel darf **weder Scroll-Position noch Composer-Puffer noch den Terminal-Scrollback verlieren**:
- **Beide Ansichten bleiben montiert**; Umschalten schaltet **Sichtbarkeit** (`hidden`-Attribut / CSS
  `display:none`), es wird **nie unmountet**. Grund: eine unmountete xterm-Instanz verliert ihren Puffer — die
  interaktive Historie wäre weg.
- Die **xterm-Instanz überlebt** jeden Toggle; `term.dispose()` **erst beim Fensterschluss**, nie beim Moduswechsel.
- Scroll-Offset der Orchestrierungs-Ansicht + Composer-Wert im Fenster-Store halten und beim Wiederzeigen
  restaurieren (die verborgene Ansicht kann ihre Layout-Metriken verlieren, während `hidden`).
- **Test-Anker:** nach `Orchestrierung → Shell → Orchestrierung` sind Scroll-Position und Composer-Text identisch;
  der Shell-Scrollback ist nach der Rückkehr unverändert vorhanden.

## W8.3 Shell = xterm.js (nativ)

- Content-Rechteck bei `INTERACTIVE` = `<div>` mit `@xterm/xterm` + `@xterm/addon-fit`. I/O 1:1 wie der alte
  `WsTtyConnector` (CYP-333 §3, doc-04 §4.1): `term.onData(d => session.send(d))`; WS-`Output` →
  `term.write(bytes: Uint8Array)`; Resize → `fitAddon.fit()` + `Resize(cols,rows)`-Frame.
- **Terminal-Interieur nicht restylen** (CYP-333 §9): xterm malt seine eigene ANSI-Palette; wir themen nur den
  Rahmen. Maritime governt **nicht** im Terminal.

## W8.4 Die Z-Order-Regel löst sich auf — Absicht bleibt

CYP-333 §3 verbot Compose-Chrome **über** dem `SwingPanel`/DOM-Overlay (Z-Order-Limit). **In reinem DOM entfällt
der Mechanismus:** xterm ist ein normaler Knoten im Stacking-Context; Chrome (Header, Banner, Menüs) darf regulär
schichten (`z-index`), auch über dem Terminal. **Die Absicht bleibt semantisch:** keine **gefälschte** Chrome über
lebendem Terminal-Inhalt — ein Kontextmenü/Tooltip darf erscheinen, aber nichts, das Terminal-Zustand vortäuscht.
→ die CYP-333-Design-Regel „Chrome als Rahmen, nicht Overlay" ist im DOM **nicht mehr erzwungen**, nur noch guter Stil.

## W8.5 Was aus CYP-333 wortgleich portiert (nicht neu spezifiziert)

Alle Zustände (`MEDIATED`/`HANDING_OVER`/`INTERACTIVE`/`HANDING_BACK`/`CONTEXT_LOST`), der WARN-amber
Hand-off-Banner (persistent, `role="status"`/`aria-live=polite`), der **human-control-Marker** (`WindowBadge.Control`
→ DOM-Badge mit Form+Label, nicht Farbe allein), der **eingefrorene+gegraute Token**, die `CONTEXT_LOST`-**gedimmte
Historie** + Diskontinuitätslinie + `a11y_terminal_history_prefix`, die Take-over/Hand-back-**Gap-Marker**, alle
`terminal_*`/`a11y_terminal_*`-Keys, und die Seams **⟂BE-1…BE-5**. **Die Migration ändert keine Backend-Semantik.**
DOM-Zugewinn: die a11y-Announcements werden echte `aria-live`-Regionen (funktional besser als in Compose).

---

# TEIL W9 — Comm-Panel + ACL-Matrix im DOM

Port von `comm/CommPanel.kt` (Design CYP-17/CYP-53) und `acl/AclPanel.kt` (Design CYP-19/CYP-48, Server-Schutz
CYP-49, Grant⇒Membership CYP-317). Alle Keys (`comm_*`, `acl_*`, `channel_kind_*`, `agent_role_*`, `message_kind_*`,
`a11y_*`) und testTags (`CommTags`, `AclMatrixTags`) sind **existierender Vertrag** — portieren, nicht neu erfinden.

## W9.1 Comm-Panel — Layout

**Struktur (Mirror):** Master-Kanalliste + Detail-Timeline + Composer.
- **Zwei-Pane (≥ 600):** CSS `grid-template-columns: 220px 1fr` (Kanalliste 220 wie heute, Timeline+Composer 1fr).
- **Single-Pane (< 600):** eine Spalte, Liste **oder** Konversation; Rücksprung über `comm_back` (Reuse).
  Auswahl-Zustand = Route/Store, nicht zwei getrennte DOM-Bäume.
- **Kanalliste** = `<nav>` mit Liste; ausgewählter Kanal trägt `aria-current="true"` (+ `a11y_comm_channel_selected`),
  Ungelesen-Zähler `comm_unread_count` / `a11y_comm_unread`. **Nur ACL-lesbare Kanäle erscheinen** (server-gefiltert —
  keine client-seitige Filterung, sonst lügt die Liste über Zugriff).
- **Timeline** = Scroll-Container; **Scroll-Retention/-Balken aus W5/W6 (CYP-392/393) wiederverwenden** (nativer
  Overflow, kein skiko-Seam). Verbindungs-Banner (`comm_status_live`/`_connecting`/`_offline`) spiegelt den echten
  Socket, `aria-live` für Statuswechsel.
- **Composer** = der **W5-Input-History-Composer (CYP-387)** wiederverwenden (↑/↓, konfigurierbar, Default 20).

**Disclosure-Trennung (CYP-53 §1, wortgleich halten):** `comm_readonly_hint` (proaktiver Read-Only-Zustand,
kein totes Feld) ≠ `comm_send_denied` (abgelehnter Sende-Versuch, ACL) ≠ `comm_send_failed` (generischer Fehler).
Drei Zustände, drei Texte — beim Verdrahten **nicht** zusammenlegen.

**Absender-/Kanal-Farbcodierung (CYP-14):** Absender-Akzent als CSS-Custom-Property pro Agent. **Den bestehenden
Kontrast-sicheren Algorithmus portieren** (`ui/SenderPalette.kt` / `readableNameAccent` — Hue gehasht, aber auf
einen Kontrast-Boden geklemmt), **nicht** rohe `hsl()`-Zufallshues (die fielen unter 3:1/4.5:1). Identität ist
zusätzlich per Text/Avatar getragen (`a11y_message_from`, `channel_kind_*`, PO-Badge `agent_role_po`/`a11y_agent_po`)
— Farbe nie allein.

## W9.2 ACL-Matrix — Layout (der eigentliche neue DOM-Baustein)

**Mirror:** „live mirror of the enforced hub state" (`AclPanel` KDoc). Wide = Grid, narrow (< 600) = Per-Kanal-Karten
(Reuse `comm_back`).

**DOM-Empfehlung: native `<table>` — hier zahlt DOM einen echten A11y-Gewinn aus.**

| Element | DOM | Gewinn ggü. Compose-Grid |
|---|---|---|
| Matrix | `<table>` (`AclMatrixTags.grid`) | Zeilen-/Spaltenbezug **nativ** angesagt |
| Agenten-Spaltenkopf | `<th scope="col">` (`aclMatrix.colHeader.<agentId>`) | Screenreader nennt Agent+Kanal je Zelle ohne Extra-ARIA |
| Kanal-Zeilenkopf | `<th scope="row">` (`aclMatrix.rowHeader.<channelId>`) | — |
| Zelle | `<td>` (`aclMatrix.cell.<channelId>.<agentId>`) | — |
| Sticky-Köpfe | `position: sticky` (Kopfzeile/-spalte) | echtes Sticky statt nachgeführter Offsets |
| Viele Agenten | horizontaler Overflow-Scroll (mirror `horizontalScroll`) | nativ |

- **Zellen-Achsen (Spalten 150/190 wie heute als Startwert):** Kanal-Zeilen × Agenten-Spalten. Column-Set =
  **Menschen + Agenten** (WorkspaceMember, CYP-317 — `acl_humans_group`/`acl_agents_group` existieren).
- **Zwei Schalter je Zelle:** `read` (`acl_read` = „Lesen") und `write` (`acl_write` = **„Antworten"**, nicht
  „Schreiben" — `canWrite` heißt „in den Kanal antworten"). DOM: `role="switch"` mit `aria-checked`. Getrennte
  Knoten `aclMatrix.cell.<ch>.<agent>.read` / `.write`.
- **A11y-Name je Zelle:** `aria-label = a11y_acl_cell` („%1$s in Kanal %2$s: Lesen %3$s, Antworten %4$s"); die
  Toggles tragen `a11y_acl_toggle_read`/`_write`. (Nativer Tabellen-Kontext + expliziter Cell-Name = doppelt
  belastbar; die Zusammenfassung nicht dem Screenreader überlassen.)

**Die Ehrlichkeits-Regeln der Matrix — jede portiert 1:1:**

1. **`pending` ≠ `enforced` (0.2, der Kern).** Toggle → Zelle `aclMatrix.cell.….pending` + `aria-busy=true`,
   **`aria-checked` bleibt auf dem letzten `enforced`-Wert**, bis das `AclEvent`-Echo kommt (nicht `PUT 200`).
   → **DOM-spezifische Falle, die ich benenne:** `aria-checked` **nie** optimistisch auf den Klickwert setzen —
   ein Screenreader, der den Pending-Klick als „an" liest, ist die a11y-Version genau der Lüge, die die visuelle
   Zelle vermeidet. `acl_pending` = „wird übernommen", `acl_enforced` = „durchgesetzt", `acl_change_failed` bei
   Timeout/Reject (Rückfall auf den vorigen Wert).
2. **Nicht-Member ist grantbar, nicht inert (CYP-317).** Zelle `…​.nonMember` = gestrichelte Kontur + „kein
   Mitglied"-Marker (`acl_non_member`) — **kein deaktivierter Toggle** (der spiegelte Editierbarkeit vor). Ein
   Grant fügt server-seitig Mitgliedschaft hinzu (`acl_grant_adds_member`, Grant⇒Membership). QA prüft N/A über die
   **Abwesenheit** eines toten Switches, nicht über einen ausgegrauten.
3. **Konflikt = deny-wins.** Mehrfacheintrag → strengster Wert (`acl_conflict`).
4. **PO-Aussperr-Leitplanke = advisory; der Server ist der Schutz (CYP-49).** Abschalten einer `…​.poCritical`-Zelle
   öffnet den Bestätigungsdialog (`aclMatrix.lockoutDialog` + `.confirm`/`.cancel`, Warnung `acl_po_lockout_warning`) —
   **kein stiller Toggle**. Fährt der Operator fort, ist der **Server** die letzte Instanz: ein PO-entkoppelnder PUT
   endet in `…​.protected` + `acl_po_protected` (**HTTP 409 `po_lockout_protected`**), **nicht** in einem
   durchgesetzten Entzug. **Wortwahl-Trennung wahren:** `acl_po_protected` (409, Änderung unzulässig) ≠
   `acl_operator_required` (403, Berechtigung fehlt). Selbst-Blend-Warnung (`aclMatrix.selfBlindWarning`,
   `acl_self_blind_warning`) beim Entzug der eigenen Operator-`canRead`.
5. **Preset „Hub-and-Spoke wiederherstellen" ist nicht-atomar.** `aclMatrix.presetRestore` → Vorschau
   (`presetPreview` „N Zellen ändern sich", eigener `.confirm`/`.cancel`, **nicht** `lockoutDialog.confirm`) →
   Fortschritt (`presetProgress` „N/M") → bei Teilfehler `presetPartial` (`acl_preset_partial`), **nie** ein stilles
   „erledigt". (Die Keys existieren *gerade*, weil das Preset nicht-atomar ist.)
6. **Read-only-Sicht (kein Operator).** Zellen tragen `…​.readonly`-Chip (`acl_denied`/`acl_granted` neutral) statt
   Schaltern; `aclMatrix.partialView` (`acl_partial_view`, INFO-Ton, `secondaryContainer` — **nicht** Fehler).
7. **Banner-Töne (die CYP-300-a0-Lektion mit-portieren):** offline/stale = **WARN-amber aus der Severity-Quelle**,
   **nicht** `tertiary` (tertiary=Signalgrün „verbunden" — invertiert). Terminal-Revoke (`accessRevoked`,
   1008-Operator-Token-Entzug) = `errorContainer`, **fail-closed**, und **verdrängt** den transienten Offline-Banner
   (ein terminaler Entzug darf nie als reconnectbarer Abbruch lesen).

**Autoritäts-Invariante (unverhandelbar):** **die UI ist Anzeige, der Hub setzt durch.** Optimismus ist
nachrangig; die Zelle spiegelt den `enforced`-Zustand, nie den Wunsch. Ein versehentliches PO-Aussperren verhindert
der **Server** (Leitplanke ist Anzeige-Komfort).

## W9.3 Neue Keys/Tags? — Nein.

W8/W9 sind ein **Medienport**: die `comm_*`/`acl_*`/`channel_kind_*`/`terminal_*`-Keys und die
`CommTags`/`AclMatrixTags`/`AgentViewTags`/`WindowBadgeTags` sind der bestehende Vertrag und **wandern mit**. Kein
neuer Key entsteht aus dem Medienwechsel selbst. **Sollte** die DOM-Umsetzung ein Element zeigen, das heute
gar nicht existiert, liefere ich den Key impl-nah (Shared-Key-Drift wie gehabt: ich entwerfe, der Dev landet mit
der Impl). Der **Test-Contract** (testTag-Schema, `<area>[.<scopeId>].<element>…`, punktfrei) gilt im DOM via
`data-testid` (bzw. `enableTestTagsAsResourceId`-Äquivalent) unverändert — QA (CYP-7) adressiert dieselben Anker.

---

## Anhang A — Offene Backend-Naht-Frage (CYP-351/396 ERROR-Grund, mitgescopt)

Wie vom PO angeregt: der **ERROR-Grund** aus CYP-351 §4 ist eine **Backend-Vertragsfrage**, die gleichermaßen für
Compose **und** DOM gilt (medien-unabhängig). Zustand `ERROR` sagt „Fehler", nennt aber keine Ursache; die
`LifecycleErrorRow` zeigt heute nur Fehler **einer Aktion**, nicht den Grund eines **Zustands**.

> **⟂BE-ERROR-Grund:** Liefert der Server zum `ERROR`-**Zustand** einen Grund?
> **(a)** Ja → die UI zeigt ihn **wörtlich, ohne Ausschmückung** (Compose-Row bzw. DOM-Detail).
> **(b)** Nein → die UI sagt **„Fehler — Grund nicht gemeldet"** und erfindet nichts.
> Bis zur Antwort gilt **(b)** (fail-closed). Antwort holt der PO von Backend2 (raus aus CYP-351). **Nicht** an
> den CYP-396-Ring-Fix koppeln (der ist backend-unabhängig, QA'd GO).

---

## Anhang B — Was DOM ehrlicher/einfacher macht (Zusammenfassung)

1. **Scrollbar/Retention (W5/W6):** nativer Overflow, `scrollbar-width/-color`; der `expect/actual`-skiko-Seam
   entfällt; die CYP-393-Uniform-Geste (Rad+Tastatur+Drag+Touch) ist im nativen `scroll`-Event eingebaut.
2. **ACL-Matrix-Semantik:** natives `<table>` mit `scope`-Köpfen sagt Zeilen-/Spaltenbezug **gratis** an.
3. **W8-Shell:** xterm.js ist *für* den Browser gebaut → das größte Alt-Risiko (Kotlin/Wasm-HTML-Interop,
   Option D) entfällt; die Z-Order-Regel löst sich auf.
4. **a11y-Live-Regionen:** Verbindungs-/Hand-off-/CONTEXT_LOST-Ansagen werden echte `aria-live`-Regionen.

## Anhang C — Ehrlichkeits-/WCAG-Pflichten, die ich in der Umsetzung halte

- Kontrast-Re-Audit auf gerendertem DOM (Disabled-`opacity`, Fokusringe, Placeholder je Kit-Komponente gegen die
  Maritime-Ratios, nicht die Kit-Defaults).
- **`aria-checked` = enforced-Wert, nie der optimistische Klick** (W9.2 Regel 1).
- Kein `text-overflow: ellipsis` auf Offenlegungssätzen/Bannern/Fehlern (0.4).
- Zielgröße ≥ 24px (WCAG 2.5.8) für Toggle-Segmente, ACL-Switches, Preset-Buttons, Resize-Griffe.
- Farbe nie alleiniger Träger: jeder Zustand/jede Identität mit Form/Label/`aria-*` (0.3).

**Nichts hier ist gebaut — Spec + Empfehlung. Keys/Tags sind der bestehende Vertrag, portiert; Pixel-Spec und
etwaige neue Keys folgen impl-nah, sobald W8/W9 CYP-Keys haben.**
