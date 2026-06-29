# Provider-Anzeige — testTag-Vertrag (CYP-137)

> Owner: UIUX-Designer · Epic CYP-130 · Story CYP-137 · Stand 2026-06-29 · Status: Vorlauf — wartet auf Gegenlesen. (Reuse-Linie CYP-118/119/123.)
> Test-Contract v0.5 §2 (`docs/TEST-CONTRACT.md`): **prefixless** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`,
> Segment-Werte `[A-Za-z0-9-]+` (camelCase, **keine Punkte** im Wert). **Geteilte API mit QA (CYP-7) — nicht
> still umbenennen, über den PO koordinieren.**
> **Keine neue Area.** Der Provider lebt in den **bestehenden** Areas: `connector` (Agentenfenster/Panel,
> CYP-119) und — als **Forward-prep** — `eventBrowse`/`eventTail` (Event-Log, CYP-41/42).
> Verifiziert gg. develop `608c8ad` (Anchor-Tags real vorhanden, siehe Reuse-Tabelle).

## Teil 1 — Provider am Agentenfenster + Capability-Panel (Area `connector`, CYP-119-Familie)

Host-verankert in `agent.<id>.header` (CYP-73) und im bestehenden `connector.<id>.capabilityPanel` (CYP-119).
Eigene Connector-Tags (kein Reuse fremder Tag-Werte). Erweitert das bestehende `ConnectorTags`-Objekt.

| Tag (Funktion/Konstante) | Wert | Zweck |
|---|---|---|
| `provider(agentId)` | `connector.<agentId>.provider` | **Zwei Render-Stellen, ein Tag-Konzept:** (a) der kompakte Provider-Qualifier-Chip im Header (neben Identität/Status) **und** (b) die oberste Identitätszeile im Capability-Panel (über `activeConnector`). **Präsent ⇔ Provider bekannt**; `null` ⇒ Chip **absent** (fail-closed, kein Phantom), Panel-Zeile zeigt `connector_provider_unknown`. _(Falls Dev Header-Chip und Panel-Zeile separat assertieren will: gleiche Funktion, der Aufrufkontext unterscheidet — Header-Chip lebt unter `agent.<id>.header`, Panel-Zeile unter `connector.<agentId>.capabilityPanel`. Bei Bedarf optionaler Qualifier `connector.<agentId>.provider.header` / `.panel` — Dev-Kalibrierung, **über PO/QA koordinieren**, nicht still einführen.)_ |
| `providerModel(agentId)` | `connector.<agentId>.provider.model` | **GEHALTEN/OPTIONAL (§-Ask 2):** gedämpfte Modell-Sekundärzeile im Panel (z. B. „Claude Opus 4.8"), **nur** wenn Backend ein Modell liefert + PO es freigibt. Bis dahin **nicht** gerendert (Anti-Hype). Forward-prep-Tag. |

> **a11y:** der sichtbar kurze Header-Chip („Claude") trägt `contentDescription` aus `a11y_provider`
> („Anbieter: %1$s") — assertierbar als Text (Farbe nie allein). Die Panel-Zeile ist über `connector_provider`
> bereits gelabelt.

## Teil 2 — Provider im Event-Log (Areas `eventBrowse`/`eventTail`, FORWARD-prep, CYP-41/42)

> **Forward-prep — landet mit der Event-Log-Impl (CYP-41/42), nicht jetzt.** Das Event-Log ist
> Design-abgenommen, aber **noch nicht implementiert** (EVENT-LOG-UI §10). Diese Tags ergänzen den
> **bestehenden** Event-Log-Tag-Vertrag (`event-log-tags.md`, `EventBrowseTags`/`EventTailTags`) **additiv**;
> sie sind hier **vorgemerkt**, damit Identitätszelle + Provider von Anfang an konsistent getaggt sind.
> **Doc-Sync-Flag an PO (Spec §3 / §-Ask 3):** beim Event-Log-Bau diese zwei Tags in `event-log-tags.md`
> aufnehmen und die Provider-Zeile in `EVENT-LOG-UI.md` §4 nachziehen — timed mit CYP-41/42-Impl.

| Tag (Funktion) | Wert | Zweck |
|---|---|---|
| `rowProvider(index)` (Browse) | `eventBrowse.row.<index>.provider` | Provider-Sekundär-Detail in der Identitätszelle der n-ten Browse-Zeile. Präsent ⇔ Provider am Event bekannt; sonst absent (nie geraten). |
| `rowProvider(index)` (Tail) | `eventTail.row.<index>.provider` | dito für die Live-Tail-Zeile. |

> **Identität bleibt führend:** der Identitäts-Hue (`colorSlot`) + Name tragen die Achse (EVENT-LOG-UI §1/§4);
> der Provider-Tag adressiert nur den **neutralen Zusatz**. Die Zeilen-a11y (`a11y_event_row`) wird um den
> Provider erweitert, wenn vorhanden.

## Reuse (bestehende Tags/Anchor — NICHT neu anlegen; verifiziert @ `608c8ad`)
| Reuse-Tag/Anchor | Quelle | Rolle hier |
|---|---|---|
| `agent.<id>.header` (`AgentViewTags.header`) | CYP-73 | Host-Anchor des kompakten Provider-Chips |
| `connector.<agentId>.capabilityPanel` (`ConnectorTags.capabilityPanel`) | CYP-119/123 | Host der Provider-Zeile (oberste Identitätszeile) |
| `connector.<agentId>.activeConnector` (`ConnectorTags.activeConnector`) | CYP-119/123 | Connector-Kind-Zeile **unter** der Provider-Zeile (Reihenfolge Provider → Kind → Fidelity) |
| `eventBrowse.row.<index>` / `eventTail.row.<index>` (`EventBrowseTags`/`EventTailTags`) | CYP-41/42 | Host der `rowProvider`-Qualifier (forward-prep) |
| `window.<id>.titlebar` (`WindowTestTags.titleBar`) | CYP-10/55 | **bleibt unverändert** — bare Teilnehmer-Identität, **kein** Provider im Titel (Spec §2.1) |

## Self-Validation
- **3 neue Tag-Identifier**: Teil 1 = `provider(agentId)` + `providerModel(agentId)` (Area `connector`,
  erweitert `ConnectorTags`); Teil 2 = `rowProvider(index)` (Areas `eventBrowse`/`eventTail`, **eine**
  Funktionssignatur je Tags-Objekt, forward-prep) = **3** Identifier. (`providerModel` gehalten/optional;
  `rowProvider` forward-prep bis Event-Log-Impl.)
- Alle Werte erfüllen `[A-Za-z0-9-]+` je Segment (camelCase, keine Punkte im Wert).
- Jeder Tag ist in `provider-display-spec.md` verankert; 5 Reuse-Anchor real auf develop `608c8ad`
  verifiziert (kein Neuanlegen): `agent.<id>.header`, `connector.<id>.capabilityPanel`/`.activeConnector`,
  `eventBrowse.row.*`/`eventTail.row.*`, `window.<id>.titlebar`.
- **Kollision:** kein bestehender `connector.<id>.provider*`-Tag im Code; `eventBrowse/eventTail.row.*.provider`
  ist ein additiver Qualifier auf dem bestehenden Zeilen-Tag (kein Konflikt mit den Severity-/`gap`-Qualifiern).
- **Keine neue Area** — Provider lebt in `connector` (CYP-119) bzw. `eventBrowse`/`eventTail` (CYP-41/42).
