# Connector-Vertrag & Capabilities — UX/UI-Spec (CYP-119)

> Owner: UIUX-Designer · Epic **CYP-118** · Story **CYP-119** · Stand 2026-06-29
> Status: **Design-Vorlauf (docs-only) — PO-Gegenlesen GO (2026-06-29), 7 §-Ask-Resolutions in §9 gefolded;
> §-Ask 1 (Wire-Form) offen bis CYP-120.** Merge-bereit; **PO merged, nicht selbst mergen**.
> Quellen: `10-Connector-Vertrag-und-Capabilities.md` (§1–§6), `09-UI-Funktionskatalog.md` (§3 Agentenfenster, §9 Einstellungen).
> Grounded gegen **develop `ac70819`** (Reuse-Komponenten/Tags/Keys real verifiziert, siehe §8).
> Begleit-Artefakte: `connector-capabilities-tokens.json`, `-keys.md`, `-tags.md`.

---

## 0. Was hier (nicht) entworfen wird

**Im Scope (Anzeige-/Interaktions-Schicht, docs-only):**
1. **Capability-/Degradations-Anzeige pro Agent** — die fünf Dimensionen aus Doc 10 §3 ehrlich darstellen
   (verfügbar / eingeschränkt / nicht verfügbar), **nie vorgetäuscht**.
2. **Connector-Auswahl + Opt-in für Connector B** — A (stream-json) als erstklassiger Default, B (MCP/Abo)
   als bewusster Opt-in-Akt mit Risiko-Aufklärung, **human-only**.

**Zurückgehalten (PO reicht durch, sobald Backend `CYP-120` gemergt ist):** die finale
**`:core`-Capability-DTO-Wire-Form** (Feldnamen). Diese Spec zurrt die **Design-Form** fest und benutzt die
vom PO durchgereichten **Design-Namen** als Platzhalter (kursiv markiert) — Feldnamen bleiben offen:
- `ConnectorKind` ∈ { *STREAM_JSON*, *MCP* }
- `CapabilityStatus` ∈ { *AVAILABLE*, *LIMITED*, *UNAVAILABLE* }
- fünf Dimensionen: `structuredUsage`, `toolGranularity`, `reliableResult`, `rateLimitSignal`, `coordination`

**Außerhalb des Scope:** der Connector-Vertrag selbst (Backend, Doc 10 §2), der MCP-Server-Aufbau (Doc 10 §5),
die Abrechnungs-/Spike-Fragen (Doc 10 §6.1/§6.2) — siehe **§9 Offene §-Asks**.

---

## 1. Leitprinzip: ehrlich degradiert, nie vorgetäuscht

Doc 10 §3, wörtlich umgesetzt: **„nicht verfügbar" schlägt ein falsches Grün.** Was ein Connector an
Signalen nicht liefern kann, darf die UI **nicht** als vorhanden zeichnen. Das ist dieselbe
Disclosure-Ehrlichkeit wie beim Product Lead und bei Cross-Projekt (CYP-79):

- **Drei getrennte Achsen, nie vermischt** (wie EVENT-LOG-UI / CYP-14): **Identität** (welcher Connector) ≠
  **Recht/Gate** (wer darf konfigurieren) ≠ **Fidelity** (was der Connector kann). Fidelity ist **keine**
  Severity — sie wird **nicht** rot eingefärbt, nur weil sie reduziert ist.
- **Fail-closed (keine Phantom-Status):** Eine Dimension ohne gemeldeten Status gilt als **nicht
  verfügbar / noch nicht gemeldet** — **nie** als „verfügbar" angenommen. Das Fehlen selbst ist sichtbar.
- **Farbe nie alleiniger Träger (WCAG 1.4.1):** jeder Status trägt zusätzlich einen **Glyph** und ein
  **Text-Label**; der Sinn lebt im Text.
- **Connector-Grenze ≠ Fehler:** „eingeschränkt"/„nicht verfügbar" ist ein **deklarierter** Zustand, kein
  App-Fehler → **kein ERROR-Rot**. Echtes Rot bleibt echten Fehlern (Aktivierung fehlgeschlagen, Gate-403).

---

## 2. Capability-Anzeige (CYP-119, Teil 1)

Zweistufig: **kompakter Marker** am Agentenfenster + **ausführliches Panel** auf Abruf — analog
Master/Detail (Event-Log) und der zwei-phasigen Cross-Projekt-Anzeige.

### 2.1 Tri-State → Tonalität (Reuse `TonedHint`/`HintTone`, CYP-99)

Vom PO bestätigt. Die drei Status nutzen die **bestehende** Tonalitäts-Palette (Glyph + Farbe), pro Status
ein **eigener Glyph** (Farbe nie allein):

| `CapabilityStatus` | Label-Key | Glyph | Tonalität / Farbe | Begründung |
|---|---|---|---|---|
| *AVAILABLE* (verfügbar) | `connector_cap_available` | `✓` | **neutral** (`secondary`, INFO-Familie) | ehrlicher Fakt, **kein** gesättigtes Erfolgs-Grün (Anti-Hype) |
| *LIMITED* (eingeschränkt) | `connector_cap_limited` | `!` | **EFFECT_DEFERRED** (amber `onTertiaryContainer`) | „degradiert/markiert" — Attention, kein Fehler |
| *UNAVAILABLE* (nicht verfügbar) | `connector_cap_unavailable` | `○` | **GATED** (neutral `onSurfaceVariant`) | „aus", **sichtbar** — schlägt falsches Grün, ist aber kein ERROR-Rot |

> Glyphen sind Plain-Text (kein Emoji, CYP-54), Desktop-JVM-sicher (vgl. `✕`/`·`/`⇄` im Bestand).
> Der **Status-Chip** je Dimension ist ein eigener kleiner Visual (`connector.<scope>.capability.<dim>.status`),
> trägt Glyph **und** Label und (optional) die Achs-Tönung — **nie** Farbe allein.

### 2.2 Kompakter Marker — Fidelity-Badge am Agentenfenster-Header

- **Ort:** im bestehenden Agentenfenster-Header (Host-Anchor `agent.<id>.header`, CYP-73), **neben** dem
  Lifecycle-Status — als **eigene Achse** (Fidelity), nicht in den Lifecycle-Status gemischt.
- **Fail-closed durch Absenz** (wie WindowBadges CYP-55): der Badge ist ein **Ausnahme-Marker** —
  **präsent ⇔ Fidelity < voll oder noch nicht gemeldet**. Ein voll-fideler Agent (Default-Connector A,
  alle Dimensionen *AVAILABLE*) zeigt **keinen** Badge (kein Lärm, kein falsches „alles grün"-Siegel).
- **Inhalt:** Form/Glyph (`!`) + Label `connector_fidelity_badge` („Eingeschränkt"). Bei **nicht
  gemeldeten** Capabilities: Label `connector_fidelity_unknown` („Fähigkeiten noch nicht gemeldet") — der
  fail-closed-Fall, **nicht** als „voll" getarnt.
- **Aktion:** klick-/fokussierbar (Hit-Area 48dp, `a11y_connector_fidelity_badge`) → öffnet das
  ausführliche Panel (§2.3). Connector-**Identität** (A/B) steht im Panel, nicht im kompakten Badge.

### 2.3 Ausführliches Panel — Capability-Detail pro Agent

- **Container** `connector.<agentId>.capabilityPanel`; Titel `connector_capabilities_title`.
- **Aktiver Connector** (`connector.<agentId>.activeConnector`, Key `connector_active` = „Aktiver
  Connector: %1$s") — Identitäts-Text (Identität ≠ Fidelity).
- **Fünf Dimensions-Zeilen** (`connector.<agentId>.capability.<dim>`), je: Dimensions-Label +
  **Status-Chip** (§2.1) + gedämpfte „speist"-Sekundärzeile (welche Funktion degradiert):

  | Dimension | Label-Key | speist (Key) — Doc 10 §3 |
  |---|---|---|
  | `structuredUsage` | `connector_dim_structured_usage` | `connector_feeds_structured_usage` (Token-Schwellen & Compact) |
  | `toolGranularity` | `connector_dim_tool_granularity` | `connector_feeds_tool_granularity` (Event-Log-Tiefe) |
  | `reliableResult` | `connector_dim_reliable_result` | `connector_feeds_reliable_result` („Agent fertig"-Erkennung) |
  | `rateLimitSignal` | `connector_dim_rate_limit_signal` | `connector_feeds_rate_limit_signal` (Warden-Stall-Erkennung) |
  | `coordination` | `connector_dim_coordination` | `connector_feeds_coordination` (Mediation/Koordination) |

- **Ehrlichkeits-Fußnote** `connector_degraded_note`: „Eingeschränkte oder nicht verfügbare Fähigkeiten
  werden ehrlich markiert – nie vorgetäuscht."
- **Erweiterbar (Doc 10 §6.3; PO Resolved 2026-06-29):** die Dimensionsliste ist **datengetrieben**. Eine
  **unbekannte** künftige Dimension rendert **generisch** statt zu verschwinden — Haupt-Label
  `connector_dim_unknown` („Unbekannte Dimension"), die **Wire-Id nur als gedämpftes Sekundär-Detail**
  (kein roher Key-Dump als Haupt-Label). Das wahrt „nie vorgetäuscht": Unbekanntes wird **nicht
  stillschweigend gedroppt**, sondern ehrlich als unbekannt markiert (Status weiter Tri-State, fail-closed
  ⇒ *UNAVAILABLE*/unbekannt bis gemeldet).

---

## 3. Connector-Auswahl + Opt-in B (CYP-119, Teil 2)

### 3.1 Ort & Default

- **Ort:** in der **Agenten-Konfiguration** (Doc 09 §2; Host-Anchor `agentMgmt.add.dialog` **und**
  `agentMgmt.edit.dialog`) — Connector ist **pro Agent** (Doc 10: „pro Agent an/aus"). § siehe §-Ask 2.
- **Picker** `connector.picker` (spiegelt das bestehende `RolePicker`-Muster, RadioRow):
  - Option **A** `connector.picker.streamJson` (Key `connector_kind_stream_json`) — **vorausgewählt,
    Default, erstklassig.**
  - Option **B** `connector.picker.mcp` (Key `connector_kind_mcp`) — **fail-closed nie vorausgewählt.**
- **Default-Notiz** `connector_default_note`: „stream-json ist der erstklassige Standard. Connector B ist
  eine Opt-in-Alternative." (Macht den fail-closed-Default explizit.)

> **Keine Kosten-Garantie in der UI (Disclosure-Ehrlichkeit):** B wird **neutral** als „Claude Code via MCP
> (Abo, interaktiv)" beschriftet — **nicht** als „günstiger/spart". Ob B sein Kostenmotiv erfüllt, ist offen
> (Doc 10 §6.1, Spike) → kein UI-Versprechen, das wir nicht garantieren können. → §-Ask 5.

### 3.2 B-Aktivierung = bewusster Akt mit Risiko-Aufklärung

Auswahl von B öffnet den **Opt-in-Dialog** `connector.optInDialog` (spiegelt das CYP-79-Owner-Consent- +
Deliberate-Act-Muster, `AlertDialog`):

1. **Intro** `connector_optin_intro` — was B ist (Opt-in-Alternative, geringere Fidelity, erklärtes Risiko).
2. **Risiko-Aufklärung, drei Zeilen** (Doc 10 §5, Tonalität = **EFFECT_DEFERRED**/amber „Achtung", **kein**
   ERROR-Rot — es ist eine Gefahren-Aufklärung, kein App-Fehler):
   - `connector_optin_risk_bypass` — umgeht interaktiv/autonom-Trennung; Anthropic erkennt die Muster aktiv.
   - `connector_optin_risk_account` — Risiko (Drosselung/Sperrung) trägt **dein eigenes Nutzerkonto**, nicht
     die Plattform.
   - `connector_optin_risk_fragile` — technisch brüchig; hängt am interaktiven Claude-Code-Verhalten.
3. **Degradierte-Capabilities-Vorschau** `connector.optInDialog.capabilityPreview`
   (`connector_optin_preview_title` = „Was sich dadurch reduziert:") — rendert die §2.3-Tri-State-Zeilen für
   **Connector B** (Scope `preview`), **vor** der Bestätigung. Ehrliche Folgenanzeige: man sieht, was man
   eintauscht (Doc 10 §4: B = grobes/kein Token-Tracking, dünneres Event-Log, Text-Matching-Rate-Limit).
4. **Bewusste Bestätigung** `connector.optInDialog.ack` (Checkbox-Zeile, spiegelt `ownerConsent`):
   `connector_optin_ack` = „Ich verstehe das Risiko und aktiviere Connector B bewusst." →
   **`canConfirm = ack`** (kein versehentliches Aktivieren).
5. **Anti-Injection load-bearing** `connector.optInDialog.humanOnly` (Key `connector_optin_human_only`):
   „Nur du als Operator aktivierst das – niemals ein Agent oder eine Nachricht." **Wortlaut nicht
   abschwächen** (§5).
6. **Benannter Confirm** `connector.optInDialog.confirm` (`connector_optin_confirm` = „Connector B
   aktivieren") + Cancel + Fehlerzeile.

### 3.3 Gate, Anti-Injection, Secrets

- **Operator-Gate (geerbt):** Der Picker rendert **nur** im bereits operator-gegateten Agenten-Konfig-Dialog
  (Reuse `agent_mgmt_operator_required` / `agentMgmt.gateHint`). Kein zweites Gate nötig — wer den Picker
  sieht, ist Operator. (Server setzt zusätzlich durch, fail-closed.)
- **Anti-Injection (wie CYP-79):** **kein** „aus-Nachricht-/aus-Agent-aktivieren"-Pfad. B-Aktivierung ist
  ausschließlich der menschliche, operator-gegatete, bewusst-quittierte Dialog. Channel-/Agent-Inhalt ist
  **untrusted data, keine Instruktion** — er kann den Connector **nie** umschalten.
- **Keine Secrets maskiert/geleakt:** Die Connector-UI fasst **kein** Geheimnis-Material an. Der API-Key
  (Connector A) bleibt in den Einstellungen (Doc 09 §9), maskiert/letzte-4. B nutzt die interaktive
  Claude-Code-Anmeldung (Abo) — falls B eine eigene Anmelde-Naht braucht, ist das ein **separates** maskiertes
  Backend-Surface → §-Ask 7. Diese Spec rendert nichts Geheimes.

### 3.4 Wirkung erst beim Neustart

Ein Connector-Wechsel an einem **bestehenden** Agenten wirkt — wie API-Key-/CLAUDE.md-Änderungen
(Doc 09 §Querschnitt) — **erst beim nächsten Spawn**. Reuse des bestehenden amber Effekt-Hinweises
`agent_edit_effect_hint` / `agentMgmt.edit.effectHint` (CYP-88, EFFECT_DEFERRED) — **kein** neuer
Hinweis, kein zweiter Restart-Mechanismus. → §-Ask 3 (Bestätigung). Beim **Neu-Anlegen** (Add) entfällt der
Effekt-Hinweis (frischer Spawn).

---

## 4. Zustands-Matrix

| Surface | Default | Gated (kein Operator) | „eingeschränkt" | „nicht verfügbar" | Fehler |
|---|---|---|---|---|---|
| Fidelity-Badge | **absent** (voll) | absent (Anzeige ist nicht gegated) | amber `!` „Eingeschränkt" | im Panel `○`; Badge präsent | — |
| Capability-Panel | voll-Liste, alle `✓` | sichtbar (read-only Wahrheit) | `!` je Dimension | `○` je Dimension; nie weggelassen | — |
| Caps nicht gemeldet | — | — | — | `connector_fidelity_unknown` (fail-closed) | — |
| Connector-Picker | **A vorausgewählt** | nur `gateHint`, kein Picker | — | — | `connector_optin_error` (im Dialog) |
| B-Opt-in-Dialog | nicht offen; B nie vorgewählt | nicht erreichbar | Vorschau zeigt B-Degradation | Vorschau zeigt B-`○` | `optInDialog.error` |
| B bestätigen | `canConfirm=false` bis Ack | — | — | — | ack erforderlich |

---

## 5. Disclosure-Ehrlichkeits-Regeln (Recap, verbindlich)

1. **Keine Phantom-Status:** nicht gemeldet ⇒ nicht verfügbar/unbekannt, nie „verfügbar". Fail-closed.
2. **„nicht verfügbar" schlägt falsches Grün:** UNAVAILABLE ist sichtbar (Glyph + Label + neutrale Tönung),
   nie weggeblendet, nie grün.
3. **Fidelity ≠ Severity ≠ Recht ≠ Identität:** vier getrennte Achsen; Degradation ist **kein** ERROR-Rot.
4. **B fail-closed:** A Default/vorausgewählt; B nie vorgewählt; B nur per bewusst-quittiertem,
   operator-gegatetem, menschlichem Akt.
5. **Anti-Injection load-bearing:** kein Pfad, der B aus einer Nachricht/von einem Agenten aktiviert.
6. **Keine Kosten-/Fidelity-Versprechen, die wir nicht halten:** B neutral beschriftet; Degradation ehrlich
   gezeigt; kein „spart Geld" vor dem Spike (§6.1).
7. **Keine Secrets:** Connector-UI rendert kein Geheimnis; Key bleibt maskiert in Settings.

---

## 6. RTL / a11y / Lokalisierung

- **RTL:** Zeilen-/Chip-Layout spiegelt mit dem Layout-Direction (`Row` + `Arrangement.spacedBy`); Glyphen
  sind richtungsneutral (`✓`/`!`/`○`). Keine harten Links/Rechts-Pixel.
- **a11y:** Status-Chip trägt Glyph **und** Label im a11y-Baum (Farbe nie allein); Fidelity-Badge hat
  `contentDescription` (`a11y_connector_fidelity_badge`); Ack-Checkbox ist über die ganze Zeile togglebar
  (Hit-Area), `canConfirm` an die Checkbox gebunden.
- **i18n:** alle Texte über `connector_*`-Keys (DE Default `values/`, EN `values-en/`), Parität Pflicht,
  positionsbasierte Argumente `%1$s`. **Shared-Key-Sync mit der Impl timen** (CYP-119) — das konsumierende
  Modul muss nach Key-Landung re-syncen, sonst bricht ein Shared-Check (Standard-Auflage).

---

## 7. Reuse statt Neuerfindung (verifiziert gg. develop `ac70819`, siehe §8)

| Reuse | Quelle (Code) | Zweck hier |
|---|---|---|
| `ui/TonedHint.kt` + `HintTone{INFO,EFFECT_DEFERRED,GATED,ERROR}` | CYP-99 | alle Hinweis-/Status-Töne + Tri-State-Semantik |
| `RolePicker`/`RadioRow`-Muster (`AgentManagementPanel.kt`) | CYP-86/88 | Connector-A/B-Picker |
| `AuthorizeDialog`-Muster (`CrossProjectControls.kt`) + `ownerConsent`-Checkbox + `humanOnly`-Note | CYP-93 | B-Opt-in-Dialog, Ack-Gate, Anti-Injection |
| `agent_edit_effect_hint` / `AgentMgmtTags.EDIT_EFFECT_HINT` | CYP-88 | „gespeichert ≠ aktiv – Neustart" beim Connector-Wechsel |
| `agent_mgmt_operator_required` / `AgentMgmtTags.GATE_HINT` | CYP-86 | Config-Operator-Gate (geerbt) |
| `agent.<id>.header` (`AgentViewTags.header`) | CYP-73 | Host-Anchor des Fidelity-Badges |
| CYP-14 ColorSlot-Disziplin (Identität ≠ Recht ≠ Severity) | CYP-14 | drei/vier getrennte Achsen |
| Fail-closed-durch-Absenz (WindowBadges) | CYP-55 | Fidelity-Badge nur bei Degradation |

> **Neue Area `connector`** (eigene Tags, kein Reuse fremder Tag-Werte) — host-verankert in
> `agent.<id>.header` / `agentMgmt.*.dialog`. Kein Cross-Surface-Key-Reuse von `agent_`/`acl_` (Drift, CYP-51);
> `connector_optin_cancel` bleibt surface-lokal (wie `crossproject_cancel`).

---

## 8. Code-Verifikation (Source of Truth)

Gegen develop `ac70819` real gelesen — bestätigt:
- `TonedHint(text, tone, tag, modifier)`; `HintTone{EFFECT_DEFERRED,GATED,INFO,ERROR}`; Glyphen `!`/`✕`/`i`/`·`;
  Farben EFFECT_DEFERRED→`onTertiaryContainer` (amber Banner), ERROR→`error`, INFO→`secondary`,
  GATED→`onSurfaceVariant`.
- `AgentManagementPanel.kt`: `RolePicker`/`RadioRow` (Add+Edit), `EDIT_EFFECT_HINT` als EFFECT_DEFERRED
  „restart to apply", Gate via `agent_mgmt_operator_required`.
- `CrossProjectControls.kt`: `AuthorizeDialog` mit `ownerConsent`-Checkbox (`canConfirm`), `humanOnly`-Note
  (load-bearing), Badge form/glyph (`⇄`) — Muster 1:1 übertragbar.
- `AgentViewTags.header(agentId)` = `agent.<id>.header`; `AgentMgmtTags.{ADD_DIALOG,EDIT_DIALOG,GATE_HINT,
  EDIT_EFFECT_HINT}`; `WindowBadgeTags` (fail-closed-durch-Absenz).
- **Kollision:** `connector*`/`capabilit*`/`fidelity*`/`degrad*` existieren **nicht** in `values/strings.xml`
  @ `ac70819` → 0 Kollision. **Kein** `connector`-Feld im Code (Wire-Form steht aus, CYP-120).

---

## 9. §-Asks — PO-Resolutions (2026-06-29)

1. **Capability-DTO-Wire-Form (Feldnamen):** **OFFEN bis CYP-120** (PO reicht durch). Design-Form steht,
   Feldnamen kursiv/offen. — _einziger offener Punkt._
2. **Connector pro Agent:** **Resolved (PO 2026-06-29) — BESTÄTIGT** (Doc 10 §3 „pro Agent an/aus").
   Picker-Ort = Agenten-Konfig (`agentMgmt.{add,edit}.dialog`) **final**.
3. **A↔B-Wechsel wirkt erst beim Neustart:** **Resolved (PO 2026-06-29) — BESTÄTIGT.** Reuse
   `agent_edit_effect_hint`; **kein** neuer Hinweis/Mechanismus (konsistent mit Key/CLAUDE.md, Doc 09
   Querschnitt).
4. **Capability-Set erweiterbar:** **Resolved (PO 2026-06-29) — JA, datengetrieben mit generischem
   Fallback.** Die 5 sind das gelabelte MVP-Set; eine **unbekannte** künftige Dimension rendert generisch
   (Haupt-Label `connector_dim_unknown` = „Unbekannte Dimension", Wire-Id nur als **Sekundär-Detail**) statt
   zu verschwinden — wahrt „nie vorgetäuscht" (Unbekanntes nicht stillschweigend droppen). **Kein roher
   Key-Dump als Haupt-Label.** Umgesetzt in §2.3.
5. **Abrechnungs-Unsicherheit:** **Resolved (PO 2026-06-29) — jede Kosten-Aussage WEGLASSEN** (mein Default).
   B neutral beschriftet, kein „spart/günstiger"; die Account-Risiko-Zeile (`connector_optin_risk_account`)
   deckt das Nötige; **keine** spekulative Kosten-Unsicherheits-Zeile (Anti-Hype).
6. **Panel-Erreichbarkeit:** **Resolved (PO 2026-06-29) — Header-Badge → Panel + Vorschau im Konfig-Dialog
   reicht für MVP.** **KEINE** zusätzliche Settings-Kopie (Doc 09 §3 verortet es am Agentenfenster; Duplikat
   vermeiden, später nachrüstbar). §-Ask-6-Variante „Panel in Settings" damit **verworfen**.
7. **B-Anmelde-Naht:** **Resolved (PO 2026-06-29) — kein neues maskiertes UI-Surface in CYP-119.** Connector
   B nutzt die **interaktive Claude-Code-Abo-Anmeldung host-seitig** (OAuth-Abo, wie die echten Läufe), **nicht**
   einen Key in unserer UI. Genaue Auth-Verdrahtung bestätigt Backend in **CYP-122**. Diese Spec rendert
   nichts Geheimes — korrekt zurückgehalten.

---

## 10. Self-Validation

- **Counts:** Spec referenziert **35 neue Keys** (`-keys.md`, inkl. `connector_dim_unknown` aus PO-§-Ask-4)
  und **17 neue Tags** (`-tags.md`), beide 1:1 hier verankert; **13 semantische Token-Slots** (`-tokens.json`).
  Counts stimmen mit den drei Begleit-Dateien überein.
- **Disclosure:** keine Phantom-Status, fail-closed Default (B aus, Caps unbekannt⇒unavailable), Fidelity ≠
  ERROR-Rot, Anti-Injection load-bearing, keine Secrets, keine ungedeckten Versprechen — alle in §5 verankert.
- **Reuse vor Neuerfindung:** 8 verifizierte Reuse-Punkte (§7/§8); neue Area `connector` host-verankert.
- **Wire-Form korrekt zurückgehalten** (§0/§9-Ask-1); Design-Form festgezurrt, Feldnamen offen.
