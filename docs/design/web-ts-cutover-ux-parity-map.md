# Web→TS-Cutover — UX-Paritäts-Map (gegen `09-UI-Funktionskatalog`)

> Owner: UIUX-Designer · Stand 2026-07-11 · Basis `origin/develop` `3213a1b3` · **UX-Teil des Cutover-Gates**
> (der Tester macht den funktionalen e2e-Teil). Docs-only.
>
> **Gate-Frage:** Bevor die web-ts-UI **Default** wird — deckt sie **jede** Funktion aus `09-UI-Funktionskatalog`
> mit **gleicher oder besserer UX** ab? Jede Lücke ist ein Cutover-Blocker (auf K-Stufe) bzw. eine Nachzieh-Notiz
> (MP/MU).
>
> **PREP-Status:** Diese Map ist **jetzt** gebaut und mit dem **heutigen** Coverage-Stand gefüllt; die
> `◑ Pending`-Zellen fülle ich final, sobald die Slices mergen (W8/W9) — dann ist die Map das ausführbare Gate.
> **Ehrlichkeit:** Ich behaupte **keine** Deckung, die ich im Baum nicht sehe; „keine bekannte Slice" ist eine
> **Lücke**, kein „wird schon".

---

## 0. Method & Legende

**Parität** = die web-ts-UX leistet, was die Compose-UX leistet — **gleich (=)** oder **besser (+)**; nie
schlechter, nie stiller Verlust. Gemessen an der bestehenden Compose-Implementierung + den Rollen-/Spec-Regeln.

| Coverage-Symbol | Bedeutung |
|---|---|
| **✓ Wx** | in web-ts vorhanden (Slice Wx / CYP-40x), UX-Verdikt in der Notiz |
| **◑ Pending Wx** | Slice geplant/spezifiziert, noch nicht auf develop → Verdikt bei Merge |
| **✗ GAP** | **keine bekannte web-ts-Slice** — Cutover-Blocker (K) bzw. Nachzieh (MP/MU) |
| **— (Stufe)** | MP/MU — außerhalb des K-Cutover-Scopes, erwartet später |

**Wichtiger Gesamtbefund vorweg:** `web-ts/App.tsx` ist **heute noch der W0-Walking-Skeleton** — die portierten
Module (Transcript, Fenster-Manager, Composer, Shell) existieren, sind aber **noch nicht zu einer laufenden App
verdrahtet**. „✓" heißt also **Modul vorhanden**; die **End-to-End-Parität** je Bildschirm ist erst nach der
Assemblierung (späte W-Slice) verifizierbar. Das ist selbst eine Gate-Bedingung (§4).

**web-ts-Slices bis heute (develop `3213a1b3`):** W0 Skeleton (CYP-398) · W1 Contract-Export (CYP-409) · W2 WS-Kanäle
(CYP-400) · W3 Transcript-Renderer (CYP-401) · W4 DOM-Fenster-Manager (CYP-402) · W5 Composer/History (CYP-403) ·
W6 Scrollbar/Autoscroll (CYP-404) · W7 xterm-Shell (CYP-405). **Pending:** W8 Toggle · W9 Comm/ACL · W10 Cutover.

---

## 1. Die Map (je 09-Kapitel)

### §1 Projekt-Verwaltung
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| Projekt anlegen / umbenennen / löschen ★ | MP | `project/` | — (MP) | außerhalb K-Cutover; Löschen = irreversibel+Folgenanzeige (Querschnitt §3) |
| Projekt öffnen / wechseln (Switcher) | MP | `project/` | — (MP) | ab MP zentral; für K-Cutover entbehrlich |
| **Repo konfigurieren (URL, Branch)** | **K** | `project/`/`connect/` | **✗ GAP** | K-Funktion, keine web-ts-Slice sichtbar → Cutover-Blocker (§3) |

### §2 Agenten-Verwaltung (Konfiguration)
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| **Agent hinzufügen** (Rolle, CLAUDE.md, launch, worktree) ★ | **K** | `agentmgmt/` | **✗ GAP** | keine web-ts-Slice → Blocker |
| **Agent entfernen** (stoppt Session, worktree-Schicksal) ★ | **K** | `agentmgmt/` | **✗ GAP** | Blocker; irreversibel-Folgenanzeige nötig |
| **Agent-Konfig ändern** (Rolle/CLAUDE.md/launch) | **K** | `agentmgmt/`,`agentsettings/` | **✗ GAP** | Blocker; „wirkt erst nach Neustart" transparent (Querschnitt §5) |

### §3 Agenten-Lebenszyklus & -Fenster
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| Fenster anordnen (verschieben/Größe/Fokus) | K | `window/` | **✓ W4** (CYP-402) | DOM-Fenster-Manager (Reducer+Zustand-Store, Pointer-Drag/Resize/Fokus, CYP-26-Clamp). Verdikt **=** (bei Merge-QA verifizieren) |
| **Agent starten / stoppen / neu starten** | **K** | `agentview/` (AgentWindow-Header) | **✗ GAP** | Header mit StatusIndicator + Lifecycle-Controls **nicht** portiert → Blocker. (Ring-Fix CYP-396 gilt dann 1:1: border statt background.) |
| Strukturiertes Terminal (stream-json-Renderer) | K | `agentview/` | **✓ W3** (CYP-401) | Renderer + XSS-Guard (JSX-Escape, kein innerHTML). Verdikt **=** |
| Nachricht an Agenten senden | K | `agentview/` | **✓ W5** (CYP-403) | Composer + Input-History (QA'd GO). Eingabe = „Nachricht", kein Shell-Prompt (Querschnitt §4) ✓. Verdikt **=** |
| (später) rohe Shell im worktree | später | `terminal/` | **✓ W7** (CYP-405) | **voraus** — xterm-Shell + operator-only + Öffnen-Warnung. Verdikt **+** (früher als geplant) |
| Autoscroll/Scrollbar (trägt das Terminal) | K | `ui/`,`agentview/` | **✓ W6** (CYP-404) | QA: Logik **=**, aber **Styling-Findings offen** (Thumb-Alpha <3:1, min-height, Hover) — s. W6-QA-Befund |

### §4 Kommunikation & Kanäle
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| Kanalliste anzeigen | K | `comm/` | **◑ Pending W9** | Spec `980073c8` W9; Verdikt bei Merge |
| Timeline pro Kanal (Historie+live) | K | `comm/` | **◑ Pending W9** | reuse W5/W6-Scroll; server-gefilterte Kanäle |
| Operator/Mensch sendet in Kanal | K (opt) | `comm/` | **◑ Pending W9** | PO-Entscheidung Mensch-im-Hub |
| Cross-Projekt-Kanal anlegen/freigeben | MP | `crossproject/` | — (MP) | Autorisierungsakt; nach K-Cutover |

### §5 Zugriffsrechte (ACL)
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| ACL-Matrix anzeigen & bearbeiten | K | `acl/` | **◑ Pending W9** | Spec `980073c8` W9: natives `<table>`, `aria-checked`=enforced, pending≠enforced |
| Preset „Hub-and-Spoke" wiederherstellen | K | `acl/` | **◑ Pending W9** | nicht-atomar, `presetPartial` |

### §6 Observability (Event-Log)
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| **Event-Log browsen** (Master-Detail, Filter) | **K** | `eventlog/` | **✗ GAP** | keine web-ts-Slice → Blocker (gepaged/virtualisiert) |
| **Korrelations-Drilldown** | **K** | `eventlog/` | **✗ GAP** | Blocker |
| **Live-Tail (mit Pause)** | **K** | `eventlog/` | **✗ GAP** | Blocker; eigene Oberfläche |
| Projekt-Filter im Event-Log | MP | `eventlog/` | — (MP) | nach K-Cutover |

### §7 Aufsicht (Scanner / Warden)
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| **Warden-Eskalationen sichtbar** | **K** (leicht) | `eventlog/` (`stall.escalated`) | **✗ GAP** | hängt am Event-Log (§6) → mit dessen Lücke offen |

### §8 Product Lead
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| **Product Lead on-demand auslösen** | **K** | `report/` | **✗ GAP** | keine web-ts-Slice → Blocker |
| **Berichte ansehen** (Guide/Status/Defekt-Register) | **K** | `report/ProductLeadPanel` | **✗ GAP** | Blocker; zeitgestempelte Momentaufnahmen |

### §9 Schlüssel & Einstellungen
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| **API-Key hinterlegen/ändern (pro Projekt)** | **K** | `settings/` | **✗ GAP** | keine web-ts-Slice → Blocker. **maskiert, nie zurückgerendert, operator-geschützt** (Querschnitt §2/§3) — genau ein Bildschirm, an dem eine schlampige Portierung leakt |
| Monitoring-/Compact-Parameter | später (opt) | `settings/`,`compact/` | — (später) | nicht MVP-Pflicht |

### §10 Multi-User (Auth & Mandanten)
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| Login / Auth · Nutzer-/Mandanten · bilaterale Freigabe | MU | `auth/`,`workspace/` | — (MU) | außerhalb K-Cutover; erwartet später |

---

## 2. Querschnitt-Regeln — auf **jedem** portierten Bildschirm zu prüfen (nicht nur je Funktion)

Aus `09` Querschnitt + den Rollen-Regeln; diese sind das UX-Ehrlichkeits-Raster des Gates:
1. **Irreversibel = Bestätigung + klare Folgenanzeige** (Projekt/Agent löschen: *was genau* wird gelöscht).
2. **Operator-geschützte Aktion = gegen Operator-Token gesichert** (Key/ACL/Cross-Projekt-Freigabe) — im DOM
   **kein Fake-Switch**: `aria-disabled` + Hinweis, nie ein totes aktives Control (CYP-317-Muster).
3. **API-Key nie im Klartext** zurückrendern (maskiert, letzte 4) — im DOM auch nicht im `value`-Attribut/DOM-Baum.
4. **Eingabe an Agenten = „Nachricht", keine Shell** — durchgängig so beschriftet (W5 ✓: `aria-label`/Placeholder).
5. **„Wirkt erst nach Neustart" transparent** (Key/CLAUDE.md/launch) — kein stiller Nicht-Effekt.
6. **Disclosure-/Offenlegungs-Ehrlichkeit** (guaranteed vs advisory), **Farbe nie alleiniger Träger**,
   **kein `ellipsis` auf Offenlegungssätzen** — die stehenden UX-QA-Regeln, auf jedem Bildschirm.

---

## 3. Headline — die K-Stufe-Lücken = die Cutover-Blocker (für den PO)

Damit die web-ts-UI **Default** werden kann, müssen **diese K-Funktionen** gedeckt sein. Heute **ohne bekannte
web-ts-Slice** (✗ GAP):

- **§3 Agent starten/stoppen/neu starten** + der **Status-/Lifecycle-Header** des Agentenfensters (inkl. CYP-396-Ring).
- **§6 Event-Log:** browsen + Korrelations-Drilldown + Live-Tail (3 Funktionen) — und §7 **Warden-Eskalationen** hängt dran.
- **§8 Product Lead:** auslösen + Berichte ansehen.
- **§9 API-Key** hinterlegen/ändern (maskiert, operator-geschützt).
- **§2 Agenten-Verwaltung:** hinzufügen / entfernen / Konfig ändern.
- **§1 Repo konfigurieren** (URL/Branch).

**◑ Pending (spezifiziert, kommt mit W8/W9):** Comm-Panel (§4) + ACL-Matrix (§5) + Toggle (§3).

**Die eine Frage an den PO (Scope, nicht UX):** ist der Cutover als **vollständiger Ersatz** der Compose-UI
gedacht (dann sind obige ✗ echte Blocker und brauchen W-Slices), **oder** ein **partieller** Cutover (web-ts wird
Default nur für die gedeckten Bereiche — Agentenfenster/Comm/ACL —, Compose bleibt für Event-Log/Product-Lead/
Settings/Agenten-Verwaltung, bis nachgezogen)? **Das entscheidet, ob die Liste oben Blocker oder Roadmap ist.**
Ich rate das nicht — es ist eine Cutover-Scope-Entscheidung.

---

## 4. Was ich beim „Ausführen" (alle Slices gemergt) tue

- Die **◑ Pending**-Zellen (W8/W9) auf **✓/Verdikt** setzen — via UX-QA gegen die W8/W9-Spec (`980073c8`).
- Die **Assemblierungs-Bedingung** prüfen: sind die ✓-Module in `App.tsx` zu den realen Bildschirmen verdrahtet
  (nicht nur als Einzelteile vorhanden)? Erst dann ist End-to-End-Parität je Funktion belegbar.
- Die **Querschnitt-Regeln** (§2) je Bildschirm abhaken (nicht nur je Funktion).
- Den offenen **W6-Styling-Fix** (Thumb-Alpha/min-height/Hover) als erledigt verifizieren.
- Restlücken als **priorisierte Blocker-Liste** (Severity + konkreter Fix) an den PO — der UX-Teil des Gate-Urteils.

**Nichts hier gebaut — Cutover-Gate-Vorbereitung. Coverage-Stand = develop `3213a1b3`; wird bei jedem Slice-Merge
nachgezogen.**
