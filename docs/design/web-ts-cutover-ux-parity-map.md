# Web→TS-Cutover — UX-Paritäts-Map (gegen `09-UI-Funktionskatalog`)

> Owner: UIUX-Designer · **Stand 2026-07-12 (Refresh)** · Basis `origin/develop` `947eea0d` · **UX-Teil des Cutover-Gates**
> (der Tester macht den funktionalen e2e-Teil). Docs-only.
>
> **Gate-Frage:** Bevor die web-ts-UI **Default** wird — deckt sie **jede** K-Funktion aus `09-UI-Funktionskatalog`
> mit **gleicher oder besserer UX** ab? Jede Lücke ist ein Cutover-Blocker (K) bzw. eine Nachzieh-Notiz (MP/MU).
>
> **Was sich seit `3213a1b3` geändert hat (groß):** die App ist **assembliert** — `App.tsx` ist die **laufende App**
> (CYP-425), **nicht** mehr der W0-Skeleton. Damit ist End-to-End-Parität je Bildschirm **jetzt verifizierbar**. Die
> meisten K-Blocker sind **gemergt** (Lifecycle, Agenten-Verwaltung, Comm, ACL, Event-Log-Tail, API-Key, Settings) und
> im **UX-Konsistenz-Pass** (2026-07-12) geprüft. Vier K-Flächen sind **spec'd, Impl pending**; **eine** echte GAP ohne
> Spec bleibt: **§10 Auth (P2-i)**.

---

## 0. Method & Legende

**Parität** = die web-ts-UX leistet, was die Compose-UX leistet — **gleich (=)** oder **besser (+)**; nie schlechter,
nie stiller Verlust. Gemessen an der Compose-Implementierung + den Rollen-/Spec-Regeln.

| Symbol | Bedeutung |
|---|---|
| **✓✓ merged+konsistenz** | Impl auf develop **und** im UX-Konsistenz-Pass (2026-07-12) geprüft; Politur-Rest → **CYP-468** |
| **✓ merged** | Impl auf develop (per-Slice-QA/Konsistenz teils noch separat) |
| **◐ spec'd** | **UIUX-Spec geliefert**, Impl vom PO gepaced → Verdikt bei Impl-QA |
| **○ offen** | **keine Spec**, Scope offen — der Cutover-Rest |
| **— (Stufe)** | MP/MU — außerhalb des K-Cutover-Scopes, erwartet später |

**Ehrlichkeit:** Ich behaupte **keine** Deckung, die ich im Baum nicht sehe. „merged" = die Komponente ist auf develop;
„◐ spec'd" = nur die Spec, **kein** Code. **Stand `947eea0d`:** Browse (CYP-452), Connector (CYP-461) + Live-Tail-Pause
(CYP-448) sind **inzwischen gemergt** (QA offen/erledigt); **noch ◐ spec-only:** Product-Lead (CYP-464), Work-Guard
(CYP-465). Auth (CYP-470) spec'd. Verifiziert am Baum.

**web-ts-Coverage (develop `947eea0d`):** W0–W7 (CYP-398..405) + **W8 Toggle**, **W9 Comm/ACL**, **CYP-425 Assembly**,
**P2-a Lifecycle (CYP-431/445)**, **P2-b Agenten-Verwaltung (CYP-450)**, **P2-c Event-Log-Tail (CYP-432)**,
**P2-e API-Key (CYP-433)**, **P2-f Settings (CYP-453)** — alle gemergt. **Token-Ebene** (CYP-423/436/437) gemergt →
die früheren W6-Styling-Findings (Thumb-Alpha/senderAccent) sind **behoben + re-QA'd grün**.

---

## 1. Die Map (je 09-Kapitel)

### §1 Projekt-Verwaltung
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| Projekt anlegen / umbenennen / löschen ★ | MP | `project/` | — (MP) | außerhalb K-Cutover; Löschen = irreversibel+Folgen (Querschnitt §3) |
| Projekt öffnen / wechseln (Switcher) | MP | `project/` | — (MP) | ab MP zentral; für K-Cutover entbehrlich |
| **Repo konfigurieren (URL, Branch)** | **K** | `settings/` | **✓✓ merged+konsistenz** (CYP-453) | Repo-Section in `SettingsPanel.tsx`: url/branch/save, unset-Ehrlichkeit, amber Effect-Hint „gespeichert≠aktiv". Verdikt **=** |
| **Repo-Re-Provision Work-Guard + Discard** (destruktiv) | **K** (safety) | `settings/`,`boot/` | **◐ spec'd CYP-465** | neuer Flow (Compose hatte ihn nicht): default-safe + irreversibel-bestätigt; HONEST-END aktiv (konkrete `AtRiskAgent`-Liste, live) — **Backend-Add CYP-466** (`reprovision-preview`). Impl+Backend pending |

### §2 Agenten-Verwaltung (Konfiguration)
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| **Agent hinzufügen** (Rolle, CLAUDE.md, launch, worktree) ★ | **K** | `agentmgmt/` | **✓✓ merged+konsistenz** (CYP-450) | `AgentManagementPanel.tsx`: server-autoritativ (id-Frei = Server-Reject, keine Race-Lüge), anlegen≠starten. Verdikt **=** |
| **Agent entfernen** (stoppt Session, worktree-Schicksal) ★ | **K** | `agentmgmt/` | **✓✓ merged+konsistenz** (CYP-450) | irreversibel = alertdialog + Datenverlust-Warnung, Worktree default-behalten, last-PO-Guard = Server-Reject. Verdikt **=** |
| **Agent-Konfig ändern** (Rolle/CLAUDE.md/launch) | **K** | `agentmgmt/` | **✓✓ merged+konsistenz** (CYP-450) | edit sperrt id+worktree, amber Effect-Hint → P2-a-Restart, kein Restart-Control hier. Verdikt **=** |
| **Connector-Auswahl** (A stream-json / B MCP, Opt-in) | **K** | `connector/` | **✓ merged (CYP-461)** | `ConnectorPicker.tsx` gemergt. **QA'd 2026-07-12: GO auf Security/Logik/a11y** (Anti-Injection wasserdicht, Preview-fail-closed, advisory≠observed) — 1 Visual-Finding (unstyled → **CYP-468-F3**). Verdikt **=** |

### §3 Agenten-Lebenszyklus & -Fenster
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| Fenster anordnen (verschieben/Größe/Fokus) | K | `window/` | **✓ merged W4** (CYP-402) | DOM-Fenster-Manager (Reducer+Store, Pointer-Drag/Resize/Fokus, CYP-26-Clamp). Verdikt **=** |
| **Agent starten / stoppen / neu starten** | **K** | `agentview/` | **✓✓ merged+konsistenz** (CYP-431/445) | `LifecycleHeader.tsx`: Enablement-Matrix pro Control, present-but-disabled, nicht-optimistisch aus `/ws/lifecycle`, Aktions-Fehler-Zeile. Verdikt **=** |
| Strukturiertes Terminal (stream-json-Renderer) | K | `agentview/` | **✓ merged W3** (CYP-401) | Renderer + XSS-Guard (JSX-Escape, kein innerHTML). Verdikt **=** |
| Nachricht an Agenten senden | K | `agentview/` | **✓ merged W5** (CYP-403) | Composer + Input-History. Eingabe=„Nachricht", kein Shell-Prompt (Querschnitt §4). Verdikt **=** |
| Ansicht-Toggle (ORCH / Shell) | K | `agentview/` | **✓✓ merged+konsistenz** (W8) | `ModeToggle.tsx`: `aria-checked`=enforced (Server-Selection, nie Klick), `aria-busy`=pending. Verdikt **=** |
| (später) rohe Shell im worktree | später | `terminal/` | **✓ merged W7** (CYP-405) | xterm-Shell + operator-only + Öffnen-Warnung. Verdikt **+** (früher als geplant) |
| Autoscroll/Scrollbar (trägt das Terminal) | K | `ui/`,`agentview/` | **✓ merged W6** (CYP-404) | **Styling-Findings BEHOBEN** (Token-Ebene CYP-423/436/437 re-QA'd grün: Thumb solid ≥3.5:1, min-height 24, senderAccent scheme-adaptiv). Verdikt **=** |

### §4 Kommunikation & Kanäle
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| Kanalliste anzeigen | K | `comm/` | **✓✓ merged+konsistenz** (W9) | `CommPanel.tsx`, server-gefilterte Kanäle, `aria-current`. Verdikt **=** |
| Timeline pro Kanal (Historie+live) | K | `comm/` | **✓ merged W9** | reuse W5/W6-Scroll; senderAccent scheme-adaptiv (CYP-436). Verdikt **=** |
| Operator/Mensch sendet in Kanal | K (opt) | `comm/` | **✓ merged W9** | revoke ersetzt Composer durch `comm-revoked-lock` (CYP-437). Verdikt **=** |
| Cross-Projekt-Kanal anlegen/freigeben | MP | `crossproject/` | — (MP) | Autorisierungsakt; nach K-Cutover |

### §5 Zugriffsrechte (ACL)
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| ACL-Matrix anzeigen & bearbeiten | K | `acl/` | **✓✓ merged+konsistenz** (W9) | `AclMatrix.tsx`: natives `<table>`, `aria-checked`=enforced (nie optimistisch), `aria-busy`=pending, deny-wins. Verdikt **=** |
| Preset „Hub-and-Spoke" wiederherstellen | K | `acl/` | **✓ merged W9** | `aclPreset.ts`, nicht-atomar (`presetPartial`). Verdikt **=** |

### §6 Observability (Event-Log)
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| **Live-Tail (mit Pause)** | **K** | `eventlog/` | **✓ merged (CYP-432/448)** | `EventLogView.tsx`: Mount-Gating (defence-in-depth, Secret-Grenze server-seitig §2), Gap-Zeilen, Severity 3-Achsen. **Pause = K-Funktion, IMPLEMENTIERT** (CYP-448: `pausedAtSeq`-Freeze + Buffer, `paused≠live` §5.6, revoke-clears-pause fail-closed) → **braucht Parity-QA-Zahn (blockierend), NICHT Politur**. QA offen. |
| **Event-Log browsen** (Master-Detail, Filter) | **K** | `eventlog/` | **✓ merged (CYP-452)** | `EventBrowsePanel.tsx` gemergt: Master/Detail/Filter, server-Query, Error-schlägt-Empty. **`EventRow.tsx` als geteilte Zeile extrahiert** (Browse+Tail eine Quelle — meine Architektur-Auflage umgesetzt). **QA offen** (Rezept in `p2-impl-qa-recipes.md`). |
| **Korrelations-Drilldown** | **K** | `eventlog/` | **✓ merged (CYP-452)** | keine erfundene Korrelation (showRun⇔correlationId / showSession⇔sessionId). **QA offen**. |
| Projekt-Filter im Event-Log | MP | `eventlog/` | — (MP) | nach K-Cutover |

### §7 Aufsicht (Scanner / Warden)
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| **Warden-Eskalationen sichtbar** | **K** (leicht) | `eventlog/` (`stall.escalated`) | **✓ merged (CYP-432)** | `stall.*` als filterbare/sichtbare Event-Typen, **nicht steuerbar**. Verdikt **=** |

### §8 Product Lead
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| **Product Lead on-demand auslösen** | **K** | `report/` | **◐ spec'd CYP-464** | Trigger-Bar (USAGE/STATUS/DEFECTS), operator-gated fail-closed. Impl pending |
| **Berichte ansehen** (Guide/Status/Defekt-Register) | **K** | `report/ProductLeadPanel` | **◐ spec'd CYP-464** | **Snapshot≠Live** (As-of + Provenance), DEFECTS advisory, report-lokaler Glyph. Impl pending |

### §9 Schlüssel & Einstellungen
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| **API-Key hinterlegen/ändern (pro Projekt)** | **K** | `settings/` | **✓✓ merged+konsistenz** (CYP-433) | `ApiKeyPanel.tsx`: Klartext nie im DOM (server-`***last4`), write-only, present-but-disabled, Effect-Hint amber → P2-a-restartBtn. Verdikt **=** |
| **Settings-Ebene** (Rahmen, zwei Gate-Klassen) | **K** | `settings/` | **✓✓ merged+konsistenz** (CYP-453) | `SettingsPanel.tsx`: Projekt-Config operator-gated ↔ persönliche Präferenzen ungated, nie vermischt. Verdikt **=** |
| Monitoring-/Compact-Parameter | später (opt) | `settings/`,`compact/` | — (später) | nicht MVP-Pflicht |

### §10 Multi-User (Auth & Mandanten)
| Funktion | Stufe | Compose-Home | Coverage | Verdikt / Notiz |
|---|---|---|---|---|
| **Login / Auth · Session-Status · Logout · unauth-Redirect** | **K→P2-i** | `auth/` | **○ offen (P2-i)** | **die eine echte GAP ohne Spec.** Scope hängt an der **Kratos↔web-ts-Grenze** (Kratos hostet Login; web-ts rendert Session-Status/Logout/unauth-Redirect) — PO zieht die Naht mit PO1, **dann** spec ich. Cross-Team |
| Nutzer-/Mandanten · bilaterale Freigabe | MU | `workspace/` | — (MU) | außerhalb K-Cutover; erwartet später |

---

## 2. Querschnitt-Regeln — auf **jedem** Bildschirm der assemblierten App zu prüfen

Aus `09` Querschnitt + Rollen-Regeln; das UX-Ehrlichkeits-Raster des Gates. **Der UX-Konsistenz-Pass (2026-07-12) hat 1–6
über die 5 gemergten P2-Flächen geprüft** — Muster tragen surface-übergreifend; offene Politur → **CYP-468**.
1. **Irreversibel = Bestätigung + klare Folgenanzeige** (Agent löschen: *was genau*; Repo-Discard: welche Agenten-Arbeit).
2. **Operator-geschützt = present-but-disabled** (`aria-disabled` + Hinweis, kein Fake-Switch, CYP-317).
   **PRÄZISIERUNG (Reviewer/Tester 4-Quadranten-Check 2026-07-12, am Objekt bestätigt):** das **Event-Log** ist
   **secret-free METADATA**, **keine** Operator-only-Bodies — `EventModel.detail` ist content-free (PRD §3.5), jedes
   Event **vor Egress maskiert** (Gate #3, `EventProjector`), und `/api/events` ist **MEMBER-tier lesbar** (CYP-186,
   `EventRoutes.kt:31`). Die **echte** Secret-/Scope-Grenze sitzt **server-seitig** (`resolveEventScope`
   cross-project-Enum-Block + Masking-Gates). Der **client-seitige** Event-Log-Operator-Gate (App.tsx-Mount-Omission)
   ist damit **Produkt-UX-Sichtbarkeit + defence-in-depth**, **NICHT** die Leak-Barriere. „Omission" bleibt korrektes
   Client-Verhalten (Produkt-Scoping: Event-Log ist ein Operator-Feature), aber **nicht** als Secret-Boundary gerahmt.
3. **API-Key nie im Klartext** (maskiert, letzte 4; auch nicht im `value`/DOM-Baum) — CYP-433 ✓.
4. **Eingabe an Agenten = „Nachricht", keine Shell** — W5 ✓.
5. **„Wirkt erst nach Neustart" transparent** — Effect-Hints **amber, nie grün** (Konsistenz-Pass ✓, eine WARN-Quelle).
6. **Disclosure-Ehrlichkeit** (guaranteed vs advisory), **Farbe nie allein**, **kein `ellipsis` auf Offenlegung**;
   **`aria-checked`=enforced** (nie optimistisch) — Konsistenz-Pass ✓. **Politur (CYP-468):** Gate-Hint-Rolle
   vereinheitlichen (F1), Severity-Palette aus der Token-Gen ableiten (F2).

---

## 3. Headline — Cutover-UX-Readiness (Scope: VOLLER ERSATZ, CYP-430)

**Die meisten K-Blocker sind zu.** Stand:

**✓✓ merged + konsistenz-ok (Verdikt = / +, Politur CYP-468 offen):**
- §1 Repo-Config · §2 Agent hinzufügen/entfernen/ändern · §3 Start/Stop/Restart + Toggle · §4 Comm · §5 ACL ·
  §6 Live-Tail + §7 Warden · §9 API-Key + Settings-Ebene. **Plus** W3/W4/W5/W6/W7 + Assembly (CYP-425).

**✓ merged, QA offen (nicht mehr spec-pending):**
- §6 **Event-Log Browse + Drilldown** (CYP-452) — gemergt inkl. **geteilter `EventRow`**; QA offen.
- §6 **Live-Tail Pause** (CYP-448) — gemergte **K-Funktion**, braucht **blockierenden Parity-QA-Zahn** (nicht Politur).

**◐ spec'd — Impl pending (QA bei Impl):**
- §8 **Product Lead** (auslösen + Berichte) → **CYP-464**.
- §2 **Connector-Auswahl** → **CYP-461** (+ Backend `GET /api/connectors`).
- §1 **Repo-Work-Guard + Discard** → **CYP-465** (+ Backend `CYP-466 reprovision-preview`).

**○ offen — keine Spec (DER Cutover-Rest):**
- §10 **Auth (P2-i)** — Scope an der Kratos↔web-ts-Grenze; PO zieht die Naht mit PO1, dann spec ich. **Einzige echte
  Spec-Lücke.**

**Nachzieh-QA (kein Blocker, an gemergten Flächen):**
- **CYP-448 (Rest)** — Severity-Palette-Bindung + Ring/Trim (Politur). **Pause ist HERAUSGEZOGEN** → gemergte K-Funktion, blockierender Parity-QA-Zahn (oben).
- **CYP-468** — Konsistenz-Politur (Gate-Hint-Rolle, `--event-sev-*` aus Token-Gen, AclPanel-⚠).
- **CYP-446** — Lifecycle clear-on-both + errorCode-Display.

---

## 4. Was zum **Gate-Urteil** noch aussteht

- **QA (gemergt, offen):** CYP-452 Browse (+ geteilte `EventRow`-Prüfung, jetzt umgesetzt), **CYP-448 Live-Tail-Pause
  (blockierender Parity-QA-Zahn)**. **CYP-461 QA'd GO** (Finding → CYP-468-F3). **QA bei Merge:** CYP-464 (Glyph-Fn/
  Snapshot≠Live), CYP-465 (+CYP-466, At-Risk-Liste **live**), CYP-470 Auth.
- **Re-Verify** die Nachzieh-Tickets (CYP-448-Rest Palette/Ring · CYP-468 F1/F2/F3 · CYP-446) an den gemergten Flächen.
- **P2-i Auth (CYP-470)**: spec'd (redirect-only Auftraggeber-ratifiziert) → QA bei Impl. Keine ○-Lücke mehr.
- **Assembly-Ehrlichkeit** (jetzt möglich): je Bildschirm der laufenden App die Querschnitt-Regeln (§2) abhaken —
  nicht nur je Funktion.
- **Ergebnis** = die priorisierte UX-Gate-Liste an den PO (Severity + konkreter Fix).

**Nichts hier gebaut — Cutover-Gate-Sicht. Coverage-Stand = develop `947eea0d`; nachgezogen bei jedem Merge.**
