# Provider-Anzeige — UX/UI-Spec (CYP-137)

> Owner: UIUX-Designer · Epic **CYP-130** (Lokale & Remote Connectoren + Hub-Wire-Protokoll — Doc 12 + Doc 10 Provider) · Story **CYP-137** · Stand 2026-06-29
> Reuse-Linie: baut auf der Connector-Capabilities-Familie **CYP-118/119/123** auf (siehe §7).
> Status: **Design gemergt (develop `abbdd51`). Wire-Form gefolded gegen die gemergte `:core`-DTO
> (CYP-137-Backend @ develop `b57778b`) — alle 4 §-Asks resolved (§9). Folge-Push docs-only; PO merged.**
> Quellen: `10-Connector-Vertrag-und-Capabilities.md` (§1 Connectoren, §3 Capabilities), `09-UI-Funktionskatalog.md`
> (§3 Agentenfenster), `06-observability-event-log.md` (Event-Schema).
> **Reconcile mit der Degradations-Linie:** baut direkt auf **CYP-119/123** (`connector-capabilities-spec.md`)
> auf — Provider ist „Connector-Fidelity"-verwandte Info und lebt in **derselben Area `connector`** + im
> bestehenden Capability-Panel. Kein neues Surface, keine Neuerfindung.
> Grounded gegen **develop `b57778b`** (Reuse-Anker + Provider-DTO real verifiziert, siehe §8).
> Begleit-Artefakte: `provider-display-tokens.json`, `provider-display-keys.md`, `provider-display-tags.md`.

---

## 0. Was hier (nicht) entworfen wird

**Im Scope (Anzeige-Schicht, docs-only):**
1. **Provider sichtbar machen** — wer (welches Werkzeug/welcher Anbieter) hinter einem Agenten steckt
   (PO-Beispiel „PO (Claude)") — **im Agentenfenster** und **im Event-Log**.
2. **Reconcile mit der bestehenden Degradations-/Capability-Anzeige** (CYP-119/123): Provider + Connector-Kind
   + Fidelity sind verwandte „Connector-Fidelity"-Infos und gehören gebündelt ins **bestehende**
   Capability-Panel — Provider als dessen oberste Identitätszeile.

**Wire-Form GEFOLDED gegen die gemergte `:core`-DTO (CYP-137-Backend @ develop `b57778b` — real verifiziert §8):**
Das Design war 1:1 deckungsgleich (PO-§-Ask-1: strukturiert statt reiner String); nur die Form präzisiert.
- **`Agent.provider: ProviderInfo? = null`** — additiv am Agent-DTO, **nullable**; **`null` ⇒ fail-closed**
  (noch nicht gemeldet / config-time / alter Payload): Qualifier **weglassen** (Header/Event-Log) bzw. im
  Panel „Anbieter noch nicht gemeldet" — **nie** ein Provider erfunden. Muster wie `capabilities`/`connectorKind`.
- **`data class ProviderInfo(val id: String, val displayName: String)`** — **strukturiert** (PO-§-Ask 1, für
  spätere Realisierung MCP/CLI/HTTP/Extraktion erweiterbar ohne Wire-Bruch). **`displayName` = der EINZIGE
  sichtbare Text** (Header-Chip, Panel-Zeile, Event-Log-Sekundärtext, a11y-`%1$s`); **`id` = stabiler
  Maschinen-Schlüssel** (z. B. `"claude"`) für Diffing/Tests/Telemetrie — **nie als Anzeigewert gerendert**.
- **Werte (Wire):** `provider: { "id": "...", "displayName": "..." }` oder `null`. MVP einziger Provider:
  `ProviderInfo("claude", "Claude")` (`ProviderInfo.CLAUDE`) — beide Connectoren A/B sind „Claude" (§-Ask 4).
- **Provider = eigenes deklariertes Connector-Feld** (PO-§-Ask 4) — **nicht** aus `connectorKind` abgeleitet;
  eigene Pflicht-Dimension (Doc 10 §3). Die UI bleibt **quellen-unabhängig** (rendert `displayName`, `null`⇒weglassen).
- **Modell-Detail (z. B. „Claude Opus 4.8") gehalten** (PO-§-Ask 2 = nein/gehalten, Anti-Hype) — Slot
  `connector.<id>.provider.model` vorgesehen, **nicht** gerendert; landet später additiv.

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
- **Inhalt:** **neutraler Text-Chip** mit dem Provider-Label = **`provider.displayName`** (der einzige
  sichtbare Text, z. B. „Claude"); **`provider.id`** (z. B. `"claude"`) ist **nur** stabiler Schlüssel
  (Tests/Diffing/Telemetrie), **nie** Anzeigewert. `contentDescription` nennt ihn **gelabelt**
  (`a11y_provider`, „Anbieter: %1$s" mit `displayName`) — der Screenreader hört „Anbieter: Claude", auch
  wenn der Chip kurz „Claude" zeigt.
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

- **Provider-Zeile** `connector.<agentId>.provider` (Key `connector_provider` = „Anbieter: %1$s",
  `%1$s` = **`provider.displayName`**) — **ganz oben**, als **Identitäts-Text** (Identität ≠ Fidelity). Liest
  die Achsen-Reihenfolge ehrlich von außen nach innen: *womit* (Provider) → *wie* (Connector-Kind) → *was
  möglich ist* (Fidelity).
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
  Sekundär-Detail** neben/unter dem Namen — sichtbarer Text = **`provider.displayName`** (`id` nie angezeigt).
  Beispiel: `⬡FE  frontend` · darunter klein „Claude".
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

Gegen develop `b57778b` real gelesen (Provider-DTO) bzw. `608c8ad` (Reuse-Anker) — bestätigt:
- **`Agent.provider: ProviderInfo? = null` (`core/.../model/CommModel.kt` @ `b57778b`):** additiv-nullable,
  fail-closed `null`; KDoc zitiert **diese Spec §1** („fourth, separate axis: provider ≠ connectorKind ≠
  fidelity ≠ identity"). **`data class ProviderInfo(val id: String, val displayName: String)`** (keine
  `@SerialName` → Wire-Feldnamen = `id`/`displayName`); Companion **`ProviderInfo.CLAUDE = ProviderInfo(
  "claude", "Claude")`** (MVP-Single-Source, A+B beide Claude). **Design 1:1 deckungsgleich** — Wire-Form
  gefolded (§0): `displayName` = einziger sichtbarer Text, `id` = stabiler Key, `null` ⇒ weglassen.
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
  `values-en/strings.xml` @ `b57778b` → **0 Kollision** (DE+EN).

---

## 9. §-Asks — PO-Resolutions (2026-06-29)

1. **Provider-Wire-Form:** **Resolved (PO 2026-06-29) — gefolded gegen die gemergte `:core`-DTO
   (CYP-137-Backend, develop `b57778b`).** Form = **strukturiert `ProviderInfo { id, displayName }`**
   (additiv + nullable, `null` fail-closed) — **nicht** reiner String; wächst ohne Wire-Bruch für spätere
   Realisierung (MCP/CLI/HTTP/Extraktion). **`displayName` 1:1 in die Anzeige gefolded** (einziger sichtbarer
   Text); **`id` = stabiler Schlüssel**, nie angezeigt. Details §0. **Kein offener Punkt mehr.**
2. **Modell-Detail:** **Resolved (PO 2026-06-29) — nein/gehalten** (Anti-Hype). Slot
   `connector.<id>.provider.model` vorgesehen, **nicht** gerendert; landet später additiv.
3. **Event-Log-Doc-Sync-Timing:** **Resolved (PO 2026-06-29) — bestätigt.** Provider-Zeile in
   `EVENT-LOG-UI.md` §4 + `eventBrowse`/`eventTail`-Provider-Tags ziehe ich **timed mit der CYP-41/42-Impl**
   nach; fremde Spec jetzt nicht geändert; Forward-prep-Tags (Teil 2 `-tags.md`) bleiben vorgemerkt.
4. **Provider-Quelle:** **Resolved (PO 2026-06-29) — eigenes deklariertes Connector-Feld**, **nicht** aus
   `connectorKind` abgeleitet (eigene Pflicht-Dimension, Doc 10 §3). MVP immer „Claude"
   (`ProviderInfo.CLAUDE`, A+B), forward-compat Codex/Gemini/Grok. Die UI bleibt **quellen-unabhängig**
   (rendert `displayName`, `null` ⇒ weglassen) — bestätigt korrekt.

---

## 10. Self-Validation

- **Counts:** Spec referenziert **3 neue Keys** (`-keys.md`: `connector_provider`, `connector_provider_unknown`,
  `a11y_provider`) und **3 neue Tag-Identifier** (`-tags.md`: `provider(agentId)` in Area `connector` +
  `providerModel(agentId)` gehalten/optional + Event-Log-Forward-prep-Qualifier); **Token-Slots** in
  `-tokens.json` (Provider-Text-Treatment, neutral). Counts stimmen mit den drei Begleit-Dateien überein.
- **Disclosure:** Identität anbieter-agnostisch (Titel unverändert), kein Phantom-Provider (fail-closed),
  Provider = Info nicht Marketing, vier getrennte Achsen, keine Secrets, nicht gated — alle in §5 verankert.
- **Reuse vor Neuerfindung:** 7 verifizierte Reuse-Punkte (§7/§8); **keine** neue Area, **keine** neue Farbe.
- **Wire-Form gefolded** (§0/§8/§9-Ask-1) gegen `ProviderInfo { id, displayName }` @ `b57778b`: `displayName`
  = sichtbar, `id` = Key, `null` ⇒ fail-closed. **Alle 4 §-Asks resolved (§9).**
