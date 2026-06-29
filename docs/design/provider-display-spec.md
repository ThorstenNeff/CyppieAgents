# Provider-Anzeige — UX/UI-Spec (CYP-137)

> Owner: UIUX-Designer · Epic **CYP-130** (Lokale & Remote Connectoren + Hub-Wire-Protokoll — Doc 12 + Doc 10 Provider) · Story **CYP-137** · Stand 2026-06-29
> Reuse-Linie: baut auf der Connector-Capabilities-Familie **CYP-118/119/123** auf (siehe §7).
> Status: **Design-Vorlauf (docs-only) — wartet auf PO-Gegenlesen. PO merged, nicht selbst mergen.**
> Quellen: `10-Connector-Vertrag-und-Capabilities.md` (§1 Connectoren, §3 Capabilities), `09-UI-Funktionskatalog.md`
> (§3 Agentenfenster), `06-observability-event-log.md` (Event-Schema).
> **Reconcile mit der Degradations-Linie:** baut direkt auf **CYP-119/123** (`connector-capabilities-spec.md`)
> auf — Provider ist „Connector-Fidelity"-verwandte Info und lebt in **derselben Area `connector`** + im
> bestehenden Capability-Panel. Kein neues Surface, keine Neuerfindung.
> Grounded gegen **develop `608c8ad`** (Reuse-Anker real verifiziert, siehe §8).
> Begleit-Artefakte: `provider-display-tokens.json`, `provider-display-keys.md`, `provider-display-tags.md`.

---

## 0. Was hier (nicht) entworfen wird

**Im Scope (Anzeige-Schicht, docs-only):**
1. **Provider sichtbar machen** — wer (welches Werkzeug/welcher Anbieter) hinter einem Agenten steckt
   (PO-Beispiel „PO (Claude)") — **im Agentenfenster** und **im Event-Log**.
2. **Reconcile mit der bestehenden Degradations-/Capability-Anzeige** (CYP-119/123): Provider + Connector-Kind
   + Fidelity sind verwandte „Connector-Fidelity"-Infos und gehören gebündelt ins **bestehende**
   Capability-Panel — Provider als dessen oberste Identitätszeile.

**Wire-Form ZURÜCKGEHALTEN (wie CYP-119 die Capability-DTO bis CYP-120 hielt):**
`Agent.provider` existiert **heute nicht** am DTO (verifiziert §8). Dev implementiert **nach Backends
Provider-DTO (CYP-137-Backend)**. Design-Form festgezurrt, Feldname offen → **§-Ask 1**:
- **Design-Annahme (additiv, nullable):** ein **menschenlesbares Provider-Label** am Agent-DTO, additiv +
  defaulted **`null`** (genau wie `capabilities`/`connectorKind` es vormachen) → `null` = **noch nicht
  gemeldet** ⇒ fail-closed (Qualifier weglassen / im Panel „noch nicht gemeldet", **nie** ein Provider
  erfunden). Optionales Modell-Detail (z. B. „Claude Opus 4.8") ist **gehalten/optional** → §-Ask 2.

**Außerhalb des Scope:** der Connector-Vertrag/Spawn (Backend, Doc 10 §2), die Capability-Degradations-
*Mechanik* (das ist CYP-119/123 — hier nur referenziert/erweitert), Modell-/Versions-Telemetrie als eigenes
Feature (Anti-Hype, §5).

---

## 1. Leitprinzip: Provider ist eine **vierte, eigene Achse** — nie mit Identität verschmolzen

Doc 10 §3, wörtlich: ein „Agent im Kanal" ist **unabhängig vom Werkzeug dahinter**. Die Teilnehmer-Identität
ist **anbieter-agnostisch** — sie darf nicht zum Provider-Label degradieren. Daraus folgt die
**Achsen-Trennung** (Fortschreibung der CYP-14- / Connector-Disziplin):

| Achse | Bedeutung | Visuelle Sprache | Quelle |
|---|---|---|---|
| **Identität** | *wer* (Teilnehmer, `agentId`/`name`) — **anbieter-agnostisch** | Name (+ Avatar/Initialen) + Identitäts-Hue `colorSlot` | CYP-14 (`SenderPalette`) |
| **Provider** *(NEU)* | *womit* (Werkzeug/Anbieter, „Claude") | **untergeordneter, neutraler Text-Qualifier** — kein Hue, kein Logo | CYP-137 |
| **Connector-Kind** | *wie* gesprochen wird (A stream-json / B MCP) | Picker/Panel-Text (Identitäts-Text) | CYP-119/123 |
| **Fidelity** | *was* der Connector kann (Degradation) | Tri-State-Chip (`✓`/`!`/`○`) + Badge | CYP-119/123 |

**Verbindliche Regeln:**
- **Identität bleibt anbieter-agnostisch.** Der **Teilnehmer-Name / Fenstertitel wird NICHT umbenannt** und
  **nicht** zu „PO (Claude)" verschmolzen. Das „(Claude)" entsteht durch **Subordination**: der Provider
  steht als **separater, visuell untergeordneter** Qualifier **neben/unter** der Identität (§2).
- **Provider ≠ Connector-Kind ≠ Fidelity ≠ Identität** — vier getrennte Achsen, **nie** ineinander
  gefärbt. Provider trägt **keinen** Identitäts-Hue (sonst läse man Provider als Identität) und **keine**
  Severity-Farbe (Provider ist kein Zustand) — neutral (`onSurfaceVariant`).
- **Farbe nie alleiniger Träger (WCAG 1.4.1):** Provider lebt im **Text**; jede Provider-Anzeige hat ein
  Text-Label / `contentDescription`.

---

## 2. Provider im Agentenfenster (CYP-137, Teil 1)

### 2.1 Kompakter Provider-Qualifier am Header

- **Ort:** im bestehenden Agentenfenster-Header (Host-Anchor `agent.<id>.header`, CYP-73), **bei** der
  Identität/Status — als **eigene Achse**, **nicht** in den Lifecycle-Status oder den Fidelity-Badge gemischt.
- **Inhalt:** **neutraler Text-Chip** mit dem Provider-Label (sichtbarer Wert = der Provider, z. B. „Claude").
  `contentDescription` nennt ihn **gelabelt** (`a11y_provider`, „Anbieter: %1$s") — der Screenreader hört
  „Anbieter: Claude", auch wenn der Chip kurz „Claude" zeigt.
- **Untergeordnet, nicht prominent:** kleinere/gedämpfte Typo (`onSurfaceVariant`, `labelSmall`-Klasse) —
  liest als **Qualifier** der Identität, nicht als zweite Identität. Kein Hue, kein Icon-Schmuck, **kein
  Logo**.
- **Fail-closed durch Absenz (kein Phantom):** Provider **unbekannt / nicht gemeldet** (`null`) ⇒ der Chip
  wird **gar nicht gezeigt** — **nie** „Claude" o. Ä. erfunden. Der Agent bleibt voll über seine Identität
  benannt; der Provider ist ein **additiver** Qualifier, kein Pflicht-Identitätsteil.

> **Warum nicht in den Fenstertitel?** Der Fenstertitel (`window.<id>.titlebar`, zeigt `window.title` = den
> bare Agenten-Namen) ist **reine Teilnehmer-Identität** (Window-Manager-Chrome) und bleibt **anbieter-
> agnostisch**. Den Provider dort hineinzuschreiben würde Identität und Werkzeug koppeln (Verstoß gegen §1 /
> Doc 10 §3). Der „PO (Claude)"-Eindruck entsteht top-to-bottom: Titelleiste „PO" → Header-Qualifier „Claude"
> direkt darunter. Identität und Provider bleiben **getrennt adressierbar** (eigener Tag, eigene a11y).

### 2.2 Verhältnis zum Fidelity-Badge (zwei Marker, distinkte Achsen)

Der Header kann jetzt **zwei** Connector-Area-Marker tragen — sie sind **bewusst verschieden** und dürfen
nicht verwechselt werden:

| Marker | Achse | Sichtbarkeit | Tönung |
|---|---|---|---|
| **Provider-Chip** (`connector.<id>.provider`) | Provider (Werkzeug) | **präsent, wenn bekannt** (sonst Absenz) | **neutral** `onSurfaceVariant`, untergeordnet |
| **Fidelity-Badge** (`connector.<id>.fidelityBadge`, CYP-123) | Fidelity (Degradation) | **präsent nur bei Degradation / nicht gemeldet** (fail-closed durch Absenz) | amber `!` (EFFECT_DEFERRED) |

> Sie tragen **gegensätzliche** Anwesenheits-Logik: der Provider-Chip ist **da, wenn alles normal** ist (er
> ist *Identitäts*-Info); der Fidelity-Badge ist **da, wenn etwas reduziert** ist (er ist ein *Ausnahme*-
> Marker). Der Provider ist neutral, der Fidelity-Badge ist amber — so liest man „Claude" nie als Warnung
> und „eingeschränkt" nie als Provider.

### 2.3 Provider im Capability-Panel — die Reconcile-Stelle (oberste Identitätszeile)

Provider ist „Connector-Fidelity"-verwandt → er gehört **gebündelt** ins **bestehende** Capability-Panel
(`connector.<agentId>.capabilityPanel`, CYP-119 §2.3). **Keine Panel-Kopie, kein zweites Surface** — nur
**eine** neue Zeile **oben**:

```
┌─ Connector-Fähigkeiten (connector_capabilities_title) ─────────────────┐
│  Anbieter: Claude                  ← NEU (connector.<id>.provider)      │  Identität (Werkzeug)
│  Aktiver Connector: stream-json    ← CYP-119 (connector_active)         │  Identität (Kind A/B)
│  ─────────────────────────────────────────────────────────────────     │
│  Token-Nutzung pro Turn       ✓ verfügbar      Speist: …               │  Fidelity (5 Dim)
│  Tool-Aufruf-Detail           ! eingeschränkt  Speist: …               │
│  … (5 Dimensionen, CYP-119 §2.3)                                        │
│  Eingeschränkte/​nicht verfügbare Fähigkeiten werden ehrlich markiert … │  (connector_degraded_note)
└─────────────────────────────────────────────────────────────────────────┘
```

- **Provider-Zeile** `connector.<agentId>.provider` (Key `connector_provider` = „Anbieter: %1$s") — **ganz
  oben**, als **Identitäts-Text** (Identität ≠ Fidelity). Liest die Achsen-Reihenfolge ehrlich von außen
  nach innen: *womit* (Provider) → *wie* (Connector-Kind) → *was möglich ist* (Fidelity).
- **Fail-closed:** Provider `null` ⇒ Zeile zeigt `connector_provider_unknown` („Anbieter noch nicht
  gemeldet") — analog `connector_fidelity_unknown`. **Nie** weggelassen-als-ob-voll, **nie** erfunden.
- **Optionales Modell-Detail (gehalten, §-Ask 2):** liefert das Backend ein Modell (z. B. „Claude Opus
  4.8"), **darf** es als **gedämpfte Sekundärzeile** unter dem Provider erscheinen
  (`connector.<id>.provider.model`) — **kein** Badge, **kein** „neuestes/bestes". Bis CYP-137-Backend das
  klärt: **nicht** rendern.

---

## 3. Provider im Event-Log (CYP-137, Teil 2)

Das Event-Log (CYP-41/42, `EVENT-LOG-UI.md`) hat eine **Identitäts-Achse** in der Event-Zeile (Avatar +
Name + `colorSlot(agentId)`, dort §1/§4). Provider wird dort **derselbe untergeordnete Qualifier** wie im
Header — **kein** neuer Hue, **keine** neue Spalte:

- **Ort:** in der **Identitätszelle** der Event-Zeile (`EVENT-LOG-UI.md` §4), als **gedämpftes
  Sekundär-Detail** neben/unter dem Namen. Beispiel: `⬡FE  frontend` · darunter klein „Claude".
- **Identität bleibt führend:** der Identitäts-Hue (`colorSlot`) und der Name tragen die Achse; der Provider
  ist ein **neutraler Zusatz** (`onSurfaceVariant`), nie der Identitäts-Hue, nie eine Severity-Farbe.
- **a11y:** die Zeilen-Zusammenfassung (`a11y_event_row`, EVENT-LOG-UI §4) wird um den Provider **erweitert,
  wenn vorhanden** („… Agent frontend (Anbieter Claude) …") — Provider als Text im a11y-Baum.
- **Fail-closed:** Provider unbekannt am Event ⇒ **kein** Provider-Sekundärtext (nie geraten). Die
  Identität allein bleibt vollständig.

> **Forward-prep / Doc-Sync:** Das Event-Log ist **Design-abgenommen, aber noch nicht implementiert**
> (CYP-41/42-Impl-Timing, EVENT-LOG-UI §10). Diese Provider-Erweiterung der Identitätszelle landet **mit der
> Event-Log-Impl** — die Tag-/Key-Ergänzungen (§ `provider-display-tags.md` Teil 2) sind **additive
> Forward-prep** zum Event-Log-Tag-Vertrag (`event-log-tags.md`). **Flag an PO:** beim Event-Log-Bau die
> Provider-Zeile in EVENT-LOG-UI §4 nachziehen (kleiner Doc-Sync, timed mit CYP-41/42-Impl). Keine
> Selbst-Änderung an der fremden Spec jetzt.

---

## 4. Zustands-Matrix

| Surface | Provider bekannt | Provider unbekannt (`null`) | Anbieter-Marketing |
|---|---|---|---|
| Header-Chip (`connector.<id>.provider`) | neutraler Text-Chip „Claude" (+ a11y „Anbieter: Claude") | **absent** (kein Phantom) | **verboten** — kein Logo/„Powered by"/Superlativ |
| Capability-Panel (oberste Zeile) | „Anbieter: %1$s" (Identitäts-Text) | „Anbieter noch nicht gemeldet" (fail-closed) | neutral; Modell-Detail optional/gehalten (§-Ask 2) |
| Event-Log-Identitätszelle | gedämpftes Provider-Sekundär-Detail | kein Provider-Text (nur Identität) | neutral, untergeordnet |
| Fenstertitel (`window.<id>.titlebar`) | **unverändert** — bare Teilnehmer-Identität | unverändert | nie Provider im Titel |

---

## 5. Disclosure-Ehrlichkeits-Regeln (verbindlich)

1. **Identität anbieter-agnostisch:** Teilnehmer-Name/Fenstertitel **nie** zum Provider verschmelzen; ein
   „Agent im Kanal" bleibt unabhängig vom Werkzeug (Doc 10 §3). Provider ist **subordiniert**.
2. **Kein Phantom-Provider (fail-closed):** Provider unbekannt ⇒ Qualifier weglassen (Header/Event-Log) bzw.
   ehrlich „noch nicht gemeldet" (Panel). **Nie** „Claude" o. Ä. annehmen/erfinden.
3. **Provider = Info, nicht Marketing (Anti-Hype):** neutraler Text, **kein** Logo, **kein** „Powered
   by …", **kein** „neuestes/bestes/schnellstes". Modell-/Versions-Detail nur neutral, optional, gehalten.
4. **Vier getrennte Achsen:** Provider ≠ Connector-Kind ≠ Fidelity ≠ Identität; Farbe nie alleiniger
   Träger; Provider neutral (kein Identitäts-Hue, keine Severity-Farbe).
5. **Keine Secrets:** der Provider-Wert ist ein **neutrales Label** (Werkzeug-/Anbietername) — **kein**
   Schlüssel/Token/Endpoint. API-Keys bleiben maskiert in den Einstellungen (CYP-85). Diese Spec rendert
   nichts Geheimes.
6. **Nicht operator-gated:** Provider ist **öffentliche, neutrale** Identitäts-Info (gleiche Exposition wie
   der Rest des `Agent`-DTO) — kein Gate (anders als die operator-gated Connector-*Auswahl*).

---

## 6. RTL / a11y / Lokalisierung

- **RTL:** Provider-Chip/-Zeile via `Row` + `Arrangement.spacedBy` (richtungsneutral); kein harter L/R-Pixel.
  Im Event-Log spiegelt die Identitätszelle mit der Zeile (EVENT-LOG-UI §4).
- **a11y:** der sichtbar kurze Chip („Claude") trägt **gelabelte** `contentDescription` `a11y_provider`
  („Anbieter: %1$s"); die Panel-Zeile ist bereits gelabelt (`connector_provider`); Event-Log-Zeile erweitert
  `a11y_event_row`. Farbe nie allein (neutral + Text).
- **i18n:** Texte über `connector_provider*` / `a11y_provider` (DE Default `values/`, EN `values-en/`),
  Parität Pflicht, positionsbasierte Argumente `%1$s`. **Shared-Key-Sync mit der Impl timen** (CYP-137):
  das konsumierende Modul (Header/Panel = CYP-137-Impl; Event-Log = CYP-41/42-Impl) muss nach Key-Landung
  re-syncen, sonst bricht ein Shared-Check. **Key-Lieferung mit der jeweiligen Impl timen.**

---

## 7. Reuse statt Neuerfindung (verifiziert gg. develop `608c8ad`, siehe §8)

| Reuse | Quelle (Code/Spec) | Zweck hier |
|---|---|---|
| `connector.<agentId>.capabilityPanel` + `connector_active`-Muster | CYP-119/123 | Provider als oberste Identitätszeile im **bestehenden** Panel (kein neues Surface) |
| Area `connector` (Tags) + Anchor `agent.<id>.header` (`AgentViewTags.header`) | CYP-119/123 / CYP-73 | Host-Anchor des Provider-Chips |
| `colorSlot(agentId)` / `SenderPalette` (Identitäts-Hue) | CYP-14 | Identitäts-Achse bleibt führend; Provider ist neutral daneben |
| Event-Zeile Identitätszelle (`EVENT-LOG-UI.md` §4) + `a11y_event_row` | CYP-41/42 | Provider als Sekundär-Detail in der Identitätszelle |
| `TonedHint`/`HintTone` neutral (`onSurfaceVariant`) — Tonalitäts-Disziplin | CYP-99 | neutrale, untergeordnete Provider-Tönung (kein eigener Hue) |
| Fail-closed-durch-Absenz (WindowBadges) | CYP-55 | Provider-Chip nur wenn bekannt |
| Achsen-Trennung Identität/Recht/Severity/Fidelity | CYP-14 / CYP-119 | Provider als **vierte** distinkte Achse |

> **Keine neue Area, keine neuen Farben.** Provider-Chip/-Zeile leben in der bestehenden Area `connector`
> (Agentenfenster) bzw. `eventBrowse`/`eventTail` (Event-Log, forward-prep). Kein Cross-Surface-Key-Reuse von
> `agent_`/`acl_` (Drift, CYP-51); die **wenigen** `connector_provider*`-Keys sind **bewusst** surface-
> neutral (identisches Wording auf Agentenfenster **und** Event-Log — derselbe Datentyp „Anbieter").

---

## 8. Code-Verifikation (Source of Truth)

Gegen develop `608c8ad` real gelesen — bestätigt:
- **`Agent` (`core/.../model/CommModel.kt`):** Felder `id, name, role, worktree, runState, capabilities:
  Capabilities? = null, connectorKind: ConnectorKind = ConnectorKind.STREAM_JSON`. **Kein `provider`-Feld**
  → Wire-Form korrekt zurückgehalten (§0, §-Ask 1). Additiv-nullable-Muster (`capabilities`/`connectorKind`)
  ist die Vorlage für `provider`.
- **`agentview/AgentWindow.kt`:** `AgentHeader` = `Row` mit `StatusIndicator` + `ConnectorCapabilityBadge`
  (fail-closed) + `Spacer(weight)` + Lifecycle-Controls; Anchor `AgentViewTags.header(agentId)` =
  `agent.<id>.header`. Provider-Chip reiht sich **bei der Identität/Status** ein (eigener Tag).
- **`window/WindowManager.kt`:** Titelleiste rendert `window.title` (bare Name) unter `WindowTestTags
  .titleBar(id)` = `window.<id>.titlebar` — bleibt **unverändert** (Identität, §2.1).
- **CYP-119/123-Panel:** `connector.<agentId>.capabilityPanel`, `connector.<agentId>.activeConnector`
  (`connector_active` = „Aktiver Connector: %1$s") — Provider-Zeile setzt **darüber** an.
- **`comm/SenderPalette.kt` + `core/.../model/ColorSlot.kt`:** Identitäts-Hue (CYP-14) — Provider trägt ihn
  **nicht**.
- **`EVENT-LOG-UI.md` §1/§4:** Identitäts-Achse = Avatar + Name + `colorSlot`; Event-Log **noch nicht
  implementiert** (§10 Impl-Timing) → Provider-Erweiterung der Identitätszelle = forward-prep (§3).
- **Kollision:** `provider` / `anbieter` / `vendor` existieren **nicht** in `values/strings.xml` **noch** in
  `values-en/strings.xml` @ `608c8ad` → **0 Kollision** (DE+EN).

---

## 9. §-Asks (für PO / Backend CYP-137-Backend)

1. **Provider-Wire-Form** *(offen bis CYP-137-Backend, wie CYP-119 bis CYP-120):* Design-Annahme =
   menschenlesbares Provider-Label, **additiv + nullable** am `Agent`-DTO (`null` = noch nicht gemeldet,
   fail-closed). **Bitte bestätigen:** Feldname/Form (reiner `String?` „Claude" vs. strukturiert
   `{ id, displayName }`). Sobald gemergt, **folde ich die Wire-Form 1:1** ein (analog CYP-119-Wire-Fold).
2. **Modell-Detail ja/nein:** Soll das Panel optional ein **Modell** („Claude Opus 4.8") als gedämpfte
   Sekundärzeile zeigen, wenn das Backend es liefert? **Mein Default: nein/gehalten** (Anti-Hype — Modell als
   Badge riecht nach Marketing). Bis Entscheid: nicht rendern, Slot vorgesehen (`connector.<id>.provider.model`).
3. **Event-Log-Doc-Sync-Timing:** Die Provider-Zeile in `EVENT-LOG-UI.md` §4 + die `eventBrowse`/`eventTail`-
   Provider-Tags landen **mit der CYP-41/42-Impl**. Bestätigen, dass ich das **dann** (timed) nachziehe und
   jetzt nicht die fremde Spec ändere.
4. **Provider-Quelle = Connector-abgeleitet?** Liegt der Provider 1:1 am Connector (A/B beide „Claude" im
   MVP) oder ist er ein eigenes Feld (zukünftig Nicht-Claude-Werkzeuge, Doc 10 §1 „später")? Beeinflusst nur,
   ob die Anzeige heute fast immer „Claude" zeigt — die UI ist **quellen-unabhängig** (rendert, was gemeldet
   ist; `null` ⇒ weglassen).

---

## 10. Self-Validation

- **Counts:** Spec referenziert **3 neue Keys** (`-keys.md`: `connector_provider`, `connector_provider_unknown`,
  `a11y_provider`) und **3 neue Tag-Identifier** (`-tags.md`: `provider(agentId)` in Area `connector` +
  `providerModel(agentId)` gehalten/optional + Event-Log-Forward-prep-Qualifier); **Token-Slots** in
  `-tokens.json` (Provider-Text-Treatment, neutral). Counts stimmen mit den drei Begleit-Dateien überein.
- **Disclosure:** Identität anbieter-agnostisch (Titel unverändert), kein Phantom-Provider (fail-closed),
  Provider = Info nicht Marketing, vier getrennte Achsen, keine Secrets, nicht gated — alle in §5 verankert.
- **Reuse vor Neuerfindung:** 7 verifizierte Reuse-Punkte (§7/§8); **keine** neue Area, **keine** neue Farbe.
- **Wire-Form korrekt zurückgehalten** (§0/§9-Ask-1); Design-Form festgezurrt, Feldname offen.
