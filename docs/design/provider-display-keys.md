# Provider-Anzeige — i18n-Keys (CYP-137)

> Owner: UIUX-Designer · Epic CYP-130 · Story CYP-137 · Stand 2026-06-29 · Status: gemergt (develop `abbdd51`); Wire-Form gefolded gg. `:core`-DTO `ProviderInfo{id,displayName}` @ `b57778b`. (Reuse-Linie CYP-118/119/123.)
> Konvention (verifiziert gg. `app/shared/.../composeResources/values/strings.xml`, develop `b57778b`):
> **Underscore-Realkeys** (keine Punkte), positionsbasierte Argumente `%1$s`. **DE = Default** (`values/`),
> **EN** (`values-en/`). Parität Pflicht.
> Modul `:app:shared` compose.resources → **Shared-Key-Sync mit der Impl timen** (CYP-137): die konsumierenden
> Module (Header/Panel = CYP-137-Impl; Event-Log-Identitätszelle = CYP-41/42-Impl) müssen nach Key-Landung
> re-syncen, sonst bricht ein Shared-Check.

## Provider-Label & Identität
| Key | DE | EN |
|---|---|---|
| `connector_provider` | Anbieter: %1$s | Provider: %1$s |
| `connector_provider_unknown` | Anbieter noch nicht gemeldet | Provider not yet reported |
| `a11y_provider` | Anbieter: %1$s | Provider: %1$s |

> **`%1$s` = `Agent.provider.displayName`** (z. B. „Claude") — der **einzige** sichtbare Provider-Text;
> `provider.id` (z. B. `"claude"`) ist nur stabiler Schlüssel, **nie** angezeigt. **Kein** Secret, **kein**
> Endpoint/Token, nur ein neutrales Anzeigelabel (Spec §5.5). Wire-Form gefolded gegen die gemergte `:core`-DTO
> `ProviderInfo { id, displayName }` (CYP-137-Backend @ develop `b57778b`, §-Ask 1 resolved) — die UI rendert
> `displayName`, sie erfindet ihn nie; `provider == null` ⇒ fail-closed.
>
> **`connector_provider`** dient **doppelt** (bewusst, identisches Wording): die **Panel-Zeile**
> (`connector.<id>.provider`, sichtbar gelabelt) **und** die **`contentDescription` des kompakten
> Header-Chips** (dessen sichtbarer Text nur der reine Wert „Claude" ist — der Screenreader hört über
> `a11y_provider` bzw. `connector_provider` „Anbieter: Claude"). So bleibt Farbe nie alleiniger Träger
> (WCAG 1.4.1) und der Chip bleibt unaufdringlich.
>
> **`connector_provider_unknown`** ist der **fail-closed**-Text im Panel (analog `connector_fidelity_unknown`,
> CYP-119): Provider `null` ⇒ „Anbieter noch nicht gemeldet" — **nie** ein Provider erfunden. Im **kompakten**
> Header-Chip und in der **Event-Log-Zelle** gilt bei `null` **Absenz** (Qualifier weglassen, kein Phantom) —
> dort braucht es **keinen** eigenen „unknown"-Text.

## Reuse (bestehende Keys — NICHT neu anlegen; verifiziert @ `b57778b`)
| Reuse | Quelle | Zweck hier |
|---|---|---|
| `connector_active` | CYP-119 | „Aktiver Connector: %1$s" — Connector-Kind-Identitätszeile **unter** der Provider-Zeile im Panel |
| `connector_capabilities_title` | CYP-119 | Panel-Titel (Provider-Zeile reiht sich darunter ein) |
| `connector_fidelity_unknown` | CYP-119 | fail-closed-Vorbild für `connector_provider_unknown` (Muster, kein Key-Reuse) |
| `a11y_event_row` | CYP-41/42 (EVENT-LOG-UI §4) | Zeilen-a11y wird um „(Anbieter %1$s)" **erweitert** wenn vorhanden — landet mit der Event-Log-Impl (forward-prep, Spec §3) |
| `ui/TonedHint.kt` + `HintTone.GATED` (neutral `onSurfaceVariant`) | CYP-99 (Komponente) | neutrale, untergeordnete Provider-Tönung (Code-Reuse, kein Key-Reuse) |
| `colorSlot`/`SenderPalette` | CYP-14 | Identitäts-Hue bleibt führend; Provider trägt ihn nicht |

## Self-Validation
- **3 neue Keys**, alle DE+EN befüllt, gleiche Argument-Anzahl je Sprache:
  `connector_provider` (%1$s) + `connector_provider_unknown` (0 arg) + `a11y_provider` (%1$s) = **3**.
- **Argument-Keys:** `connector_provider` und `a11y_provider` tragen je **genau ein** `%1$s` (Provider-Name);
  `connector_provider_unknown` ohne Argument. DE/EN-Argument-Anzahl identisch.
- **Kein interpolierter, content-tragender oder sensibler Klartext** — `%1$s` ist ausschließlich der
  neutrale Provider-/Werkzeugname (kein Secret/Token/Endpoint).
- **Kollision:** `provider`/`anbieter`/`vendor` existieren **nicht** in `values/strings.xml` **noch** in
  `values-en/strings.xml` @ `b57778b` → **0 Kollision** (DE+EN). Die `connector_provider*`-Keys sind im
  `connector_*`-Namespace greenfield (kein bestehender `connector_provider*`).
- **DE/EN-Parität:** jede Zeile beidseitig. **Bewusst surface-neutral** (`connector_provider*`/`a11y_provider`
  identisch auf Agentenfenster **und** Event-Log — derselbe Datentyp „Anbieter", identisches Wording; das ist
  **kein** divergenz-anfälliger Cross-Surface-Reuse wie bei `agent_`/`acl_` (CYP-51), sondern **ein** Label
  für **ein** Konzept).
- Jeder Key ist in `provider-display-spec.md` / `-tags.md` verankert.
