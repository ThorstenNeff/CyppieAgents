# Remote-Operating-Surface-Chrome — testTag-Vertrag (M2, Epic CYP-427, CYP-449 §4-Seams)

> Owner: UIUX-Designer · **Dev-AC (copy-paste-fertig)** · Stand 2026-07-13 · Begleit-Keys: `remote-operating-chrome-keys.md`.
> Test-Contract v0.5 §2 (`docs/TEST-CONTRACT.md`): **prefixless** `<area>[.<scopeId>].<element>[.<qualifier>]`, Segment-Werte
> `[A-Za-z0-9-]+` (**camelCase**, kein Underscore/Punkt im Wert). **Geteilte API mit QA (CYP-7) — nicht still umbenennen,
> über den PO mit Tester + DS einfrieren.**
> Erweitert das bestehende **`WorkspaceTags` (Area `workspace`)** um **3 Tags**. Verifiziert gg. `WorkspaceTags.kt`,
> `RemoteConnectTags.kt`, `RemoteRevokeTags.kt` @ develop `5fbd9e9c`.

---

## 0. Anti-Duplikat-Grounding (Pflicht)

Die drei Nähte hängen an der **einen gebauten** Banner-Region (top-of-workspace, `ProjectSwitcherBar`-Slot
`remoteContextBanner`, gespeist von `AgentShell.remoteContext`). **Kein** `remote.context.*` / `shell.hubContext`-Namespace
(CYP-449 §4 nannte sie — **nie gebaut**). Reuse-Anker im Build @ `5fbd9e9c`:
- `WorkspaceTags.REMOTE_CONTEXT = "workspace.remoteContext"` (CYP-527, KDoc „UIUX-locked") — die Banner-Node.
- `RemoteConnectTags.RELAY_DROP = "remote.relayDrop"` — Connect-Flow-Row (**andere Fläche**, nicht geteilt).
- `RemoteConnectTags.TRUST_PINNED = "remote.connect.trustPinned"` — Connect-Trust (Copy-Reuse, kein Tag-Share).
- `RemoteRevokeTags.{END,CONFIRM,SCOPE_NOTE,TTL_HINT,ENDED}` (`remote.revoke.*`) — Revoke-Control (gebaut, gemountet nur im Connect-Flow).

---

## Seam #1 — Kontext-Banner affirmative Form: 1 net-new Sub-Tag

Die **Banner-Node bleibt `workspace.remoteContext`** (Zustandsvariante: WARN-partial ↔ affirmativ — **eine** Node, kein
neuer Tag für den Haupt-Text). Net-new ist **nur** der Gepinnt-Sub-Indikator.

| Tag (Konstante) | Wert | Zweck |
|---|---|---|
| `REMOTE_CONTEXT_PINNED` **(NET-NEW)** | `workspace.remoteContext.pinned` | Der E2E-gepinnt-Indikator im affirmativen Banner (Copy `remote_connect_trust_pinned`). **Present-iff** echter Fingerprint-Pin (nicht provisorisch). Eigener Sub-Node ⟹ Tester kann „gepinnt da" von „Banner da" trennen (H1). |

Kotlin (an `WorkspaceTags` anhängen):
```kotlin
/** CYP-427/M2 — E2E-gepinnt-Indikator im affirmativen Remote-Kontext-Banner. Present ⇔ identity real-pinned
 *  (HubTrust-Pin, NICHT provisorisch); Copy = remote_connect_trust_pinned. Sub-Node von [REMOTE_CONTEXT];
 *  neutral/primary-Ton, NIE grün (Transport/Identität ≠ Erfolg). */
const val REMOTE_CONTEXT_PINNED = "workspace.remoteContext.pinned"
```

> Die affirmative Haupt-Zeile trägt weiterhin `workspace.remoteContext` (unverändert). Der Zustand (partial vs. tunnel)
> ist **kein** Tag-Wechsel — Tester unterscheidet über die **gerenderte Copy** (`_partial` vs `_tunnel`) + Ton, und über die
> **Präsenz** von `.pinned`.

---

## Seam #2 — In-Betrieb-Relay-Drop-Fläche: 2 net-new Tags

Eigene, **workspace-scoped** Tags (Area `workspace`, In-Betrieb) — **nicht** `remote.relayDrop` (das ist die
Connect-Flow-Row; geteiltes Tag bräche Tester-Distinguierbarkeit zwischen Pre-Connect und In-Betrieb).

| Tag (Konstante) | Wert | Zweck |
|---|---|---|
| `RELAY_DROP` **(NET-NEW)** | `workspace.relayDrop` | **Eine** globale In-Betrieb-Relay-Drop-Fläche (Reconnect-Banner, Copy `remote_connect_relay_dropped`). Present-iff `RemoteSessionState.conn == RECONNECTING`. WARN-Amber `▲`, **nie** N per-Fenster-Chips. |
| `RELAY_DROP_UNCERTAIN` **(NET-NEW)** | `workspace.relayDrop.uncertain` | Die „Aktionen unbestätigt"-Warnung (Copy `workspace_relay_uncertain`), Sub-Node der Drop-Fläche. Present-iff `inFlightUncertain == true`. Der H4-Konsum — eigener Node ⟹ Tester prüft „ungewiss da" separat. |

Kotlin (an `WorkspaceTags` anhängen):
```kotlin
/** CYP-427/M2 — EINE globale In-Betrieb-Relay-Drop-Fläche (Reconnect-Banner). Present ⇔ conn==RECONNECTING;
 *  WARN-Amber ▲, nie rot (Sitzung verbindet neu, ist nicht tot; terminal = LOST = anderer Pfad). Copy =
 *  remote_connect_relay_dropped. Getrennt von RemoteConnectTags.RELAY_DROP (Connect-Flow-Row, andere Fläche). */
const val RELAY_DROP = "workspace.relayDrop"

/** CYP-427/M2 — H4-Konsum: laufende Aktionen ehrlich UNGEWISS bei Drop (nie still erledigt). Present ⇔
 *  inFlightUncertain==true. Sub-Node von [RELAY_DROP], WARN-Amber. Copy = workspace_relay_uncertain. */
const val RELAY_DROP_UNCERTAIN = "workspace.relayDrop.uncertain"
```

---

## Seam #3 — Revoke-Control-Mount: 0 net-new (gebaute Tags)

`RemoteRevokeControl` trägt bereits die gebauten Tags (`RemoteRevokeTags`, verifiziert @ `5fbd9e9c`) — **unverändert**:

| Tag | Wert | Rolle |
|---|---|---|
| `RemoteRevokeTags.END` | `remote.revoke.end` | „Fern-Sitzung beenden"-Trigger (IDLE) |
| `RemoteRevokeTags.CONFIRM` | `remote.revoke.confirm` | Destructive-Confirm-Button im Dialog |
| `RemoteRevokeTags.SCOPE_NOTE` | `remote.revoke.scopeNote` | „nur diese Verbindung — kein globales Revoke" (advisory) |
| `RemoteRevokeTags.TTL_HINT` | `remote.revoke.ttlHint` | ≤TTL-Hinweis — **seam-gated**, absent ohne Operator-Session-TTL (`ttl==null`) |
| `RemoteRevokeTags.ENDED` | `remote.revoke.ended` | Ergebnis „Fern-Sitzung beendet" |

**Diese Slice legt keinen Revoke-Tag an** — sie bewegt den Mount-Ort (siehe `-ux-spec.md` §Seam-3). Der Vertrag ist frozen.

---

## Binding & Guards — present-iff (der ehrliche Kern, Pflicht-ACs)

Die Banner-Region ist **transport-/state-getrieben, nicht klick-getrieben.** Eine Region (`remoteContextBanner`-Slot),
Zustandsmaschine nach `RemoteSessionState`:

| # | Zustand | Gerendert | Node(s) | Ton |
|---|---|---|---|---|
| B1 | **Lokal** (`LocalHubTransport`) | nichts (fail-closed absent) | — | — |
| B2 | Remote **CONNECTED**, Daten **noch nicht** über Tunnel | WARN-partial (gebaut) | `workspace.remoteContext` + `_partial`-Copy | WARN `▲` |
| B3 | Remote **CONNECTED**, Daten **echt** über Tunnel (CR3-Capability true) | **affirmativ** (Seam #1) | `workspace.remoteContext` + `_tunnel`-Copy · `.pinned` iff echt gepinnt | neutral `●` |
| B4 | Remote **RECONNECTING** | Relay-Drop-Fläche (Seam #2) | `workspace.relayDrop` · `.uncertain` iff `inFlightUncertain` | WARN `▲` |
| B5 | Remote **LOST** (terminal) | Sitzung-beendet-Pfad (**out-of-scope**, bestehend) | — | — |

| # | Guard | Regel |
|---|---|---|
| G1 | **B3 capability-gegated** | Die affirmative Form (`_tunnel`) erscheint **nur**, wenn ein **reales** „Daten laufen über den Tunnel"-Capability-Signal `true` ist — **nie** allein auf `conn==CONNECTED`. Fehlt das Signal (heute Stub), **bleibt B2** (WARN-partial). Kein optimistischer Flip. (CYP-527 G5: dieselbe Node, von der echten Capability neu gespeist.) |
| G2 | **`.pinned` present-iff echt gepinnt** | Der Gepinnt-Indikator ist **präsent ⇔ realer Fingerprint-Pin** (`HubTrust`-Resolution = Pin, nicht provisorisch). Bei provisorischem Trust: **absent** (nie „gepinnt" vortäuschen). |
| G3 | **B4 present-iff RECONNECTING** | `workspace.relayDrop` **präsent ⇔ `conn==RECONNECTING`** (nicht bei CONNECTED/LOST). Node absent sonst (nicht nur unsichtbar). |
| G4 | **`.uncertain` present-iff `inFlightUncertain`** | Der Ungewissheits-Sub-Node **präsent ⇔ `inFlightUncertain==true`**. Clear (absent) sobald `inFlightUncertain=false` (reconnect, `RemoteHubSession.kt:177`). **Nie still als erledigt.** |
| G5 | **Region-Exklusivität** | Genau **eine** Banner-Zeile je Zustand (B2/B3/B4) — nie zwei gleichzeitig (CONNECTED-Banner UND Drop-Banner). Der Slot rendert die zustands-passende Node; Übergang ist ein Node-Swap, kein Stapeln. |
| G6 | **Revoke present-iff Remote-aktiv (Seam #3)** | `RemoteRevokeControl` gemountet **iff** remote CONNECTED (B2/B3); absent lokal / nach Teardown. `onEndSession` → `backToHubList()`/`RemoteHubSession.close()` (garantierter lokaler Teardown, non-optimistisch — Details `-ux-spec.md`). |
| G7 | **Forward-compat** | Landet der echte Transport-Modus (RR5), speist er B1–B4 aus `HubTransportMode`/Capability neu — **Tags/Copy/Nodes unverändert** (stabile Naht). |

---

## Fail-closed-/Ton-Anker (für §-QA)
- **B3 affirmativ = neutral `●`/`primary`** — **kein** `errorContainer`, **kein** `tertiary`/Erfolgs-Grün. `.pinned` =
  Transport/Identität, **nie grün**, nie als „alles sicher" (H3).
- **B4 = WARN-Amber `▲`** (`severityColor(Severity.WARN)`) — **nie** Rot (Drop ≠ tot; reconnectet). `.uncertain` = WARN.
- **Kein Tag trägt Erfolgs-Grün.** WCAG 1.4.1: Bedeutung im Text/`contentDescription`, Glyph (`●`/`▲`) + Farbe nur Verstärkung.
- **Present-iff transport-/state-getrieben** (G1–G6): keine Node erscheint klick-getrieben oder auf einem Lokal-Pfad; keine
  stale-Node nach Teardown. Läuft das Dogfood noch auf Stub/`LOCAL`, bleibt die ganze Region **korrekt absent** (B1).

## Reuse (bestehende Tags/Slots — NICHT neu anlegen; verifiziert @ `5fbd9e9c`)
| Reuse | Quelle | Rolle hier |
|---|---|---|
| `WorkspaceTags` (Area `workspace`) | CYP-80/186/417/527 | die 3 neuen Tags hängen ans bestehende Objekt (Area etabliert) |
| `WorkspaceTags.REMOTE_CONTEXT` | CYP-527 | die Banner-Node (B2↔B3 Zustandsvariante); `.pinned` ist ihr Sub-Node |
| `remoteContextBanner`-Slot (`ProjectSwitcherBar`, `AgentShell.remoteContext`) | CYP-527/417 | Platzierungs-/Slot-Muster für die ganze Banner-Region (optionaler Full-Width-`@Composable`) |
| `RemoteConnectTags.RELAY_DROP` | CYP-471 | **Nachbar-Konzept, NICHT geteilt** — Connect-Flow-Row ≠ In-Betrieb-Fläche |
| `RemoteRevokeTags.*` | CYP-480 | Seam #3, unverändert (0 net-new) |

## Self-Validation
- **Net-new: 3 Tags** in Area `workspace` — `REMOTE_CONTEXT_PINNED = workspace.remoteContext.pinned` · `RELAY_DROP =
  workspace.relayDrop` · `RELAY_DROP_UNCERTAIN = workspace.relayDrop.uncertain`. Kein dynamischer Qualifier.
- **0 Kollision** @ `5fbd9e9c` — verifiziert gg. `WorkspaceTags.kt` (bestehend: `roleIndicator`/`operatorName`/`members`/
  `member.*`/`capacity`/`capacity.full`/`overloadBanner`/`overloadBanner.dismiss`/`remoteContext` — kein `remoteContext.pinned`/
  `relayDrop`). `workspace.relayDrop` ≠ `remote.relayDrop` (verschiedene Areas/Objekte, verschiedene Werte). ✓
- **Charset/Konvention:** alle drei = camelCase-Segmente, `[A-Za-z0-9-]+`, kein Underscore/Punkt im Segment-Wert. ✓
- **Geteilte API mit QA (CYP-7):** die 3 Werte über den PO mit Tester + DS einfrieren (Frozen-Contract).
- **Present-iff/Guards (G1–G7)** = der behaviorale §-QA-Kern: B3 iff echte Tunnel-Capability · `.pinned` iff echt gepinnt ·
  B4 iff RECONNECTING · `.uncertain` iff inFlightUncertain · Region-Exklusiv · Revoke iff remote-aktiv.
- **Seam #3: 0 net-new** — `RemoteRevokeTags.*` gebaut, Vertrag frozen (nur Mount-Ort-Naht).
- Jeder net-new Tag ist im `-keys.md` (Copy) + `-ux-spec.md` (Verhalten) verankert.
