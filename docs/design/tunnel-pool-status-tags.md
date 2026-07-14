# Tunnel Pool Status — testTag-Contract (CYP-540 / WS5) — **FROZEN**

> Owner: UIUX-Designer · Epic CYP-427 (M2-A) · Story **CYP-540** (WS5) · Stand 2026-07-14 · Status: **FROZEN** (Dev5 baut dagegen, WS4-Harness assertiert dagegen).
> Test-Contract v0.5 §2 (`docs/TEST-CONTRACT.md`): **prefixless** `<area>[.<scopeId>].<element>[.<selectorId>][.<qualifier>]`,
> Segment-Werte `[A-Za-z0-9-]+` (camelCase, **keine Punkte** im Wert).
> **Geteilte API mit QA (CYP-7) — nicht still umbenennen, über den PO koordinieren.**
> **Neue Area-Familie:** `remote.pool.*` — **Sibling** zu `remote.connect.*` (`RemoteConnectTags`, CYP-471) und `remote.relayDrop`.
> **Kollisionsfrei verifiziert @ develop `7358c853`** (grep `remote\.pool|tunnelPool|poolState|TunnelPoolState`: 0 Treffer im Code).
> Gerendert per `tunnel-pool-status-spec.md`; C3-Shape aus `M2-A-ntunnel-workstream-split-and-contract.md` §4 C3 (frozen).

## Kotlin-Objekt (Konvention wie `RemoteConnectTags`/`RemoteRevokeTags`)

```kotlin
package com.tneff.cyppieagents.connect   // oder net/hub — Dev5-Impl-Wahl; Werte bleiben remote.pool.*

/**
 * CYP-540 (WS5) — testTag-Contract für den N-Tunnel-Pool-Status, area `remote.pool.*` (Sibling zu
 * RemoteConnectTags' `remote.connect.*`). Rendert die frozen C3 `TunnelPoolState` (M2-A §4 C3).
 * Shared API mit QA (CYP-7) — Änderungen über den PO. Present ⇔ echtes Signal (fail-closed); keine Phantome.
 */
object TunnelPoolStatusTags {
    /** DIE eine workspace-scoped Pool-Status-Fläche. Present ⇔ remote-connected UND ≥1 Tunnel emittiert. Absent = INERT (Spec §5.2). */
    const val CONTAINER = "remote.pool"
    /** Aggregat-Summenzeile (active/cap). Present ⇔ CONTAINER present. */
    const val AGGREGATE = "remote.pool.aggregate"
    /** WARN-Qualifier — present ⇔ aggregate.anyBackpressured == true (amber). Absent ⇔ false (fail-closed, kein green-OK). */
    const val AGGREGATE_BACKPRESSURED = "remote.pool.aggregate.backpressured"

    /** Per-Tunnel-Zeile. scopeId = stabiler 0-basierter Pool-Slot-Index. Present ⇔ realer Tunnel an diesem Slot im Emitter. */
    fun tunnel(slot: Int) = "remote.pool.tunnel.$slot"
    /** State-Marker. state ∈ {dialing, up, backpressured, down}. GENAU EINER present je gerenderter Zeile = current C3-state. */
    fun tunnelState(slot: Int, state: String) = "remote.pool.tunnel.$slot.$state"
    /** rendezvousId als assertierter CONTENT (Korrelation) — NICHT als Tag-Segment (rid ist opaker String, nicht [A-Za-z0-9-]+-safe). */
    fun tunnelRid(slot: Int) = "remote.pool.tunnel.$slot.rid"
    /** OPTIONAL/GEHALTEN — dwell-Disclosure aus sinceTs. Nur gerendert, wenn PO dwell will (Anti-Over-Production, Default aus). */
    fun tunnelSince(slot: Int) = "remote.pool.tunnel.$slot.since"
}
```

## Tag-Tabelle

| Tag (Konstante/Funktion) | Wert | Present ⇔ / Semantik |
|---|---|---|
| `CONTAINER` | `remote.pool` | Die **eine** workspace-scoped Fläche. Present ⇔ **remote-connected AND ≥1 emittierter Tunnel**. **Absent = INERT** (lokal / pre-connect / kein Signal) — fail-closed, **nie** ein Phantom-„0/cap". |
| `AGGREGATE` | `remote.pool.aggregate` | Aggregat-Summe. Present ⇔ CONTAINER present. Content = literal `active/cap` (nie gerundet, `active<cap` ≠ Defizit). |
| `AGGREGATE_BACKPRESSURED` | `remote.pool.aggregate.backpressured` | Present ⇔ `aggregate.anyBackpressured == true` (amber WARN). Absent ⇔ false. **Nie inferiert.** |
| `tunnel(slot)` | `remote.pool.tunnel.<slot>` | Per-Tunnel-Zeile; scopeId = **stabiler 0-basierter Pool-Slot-Index**. Present ⇔ realer Tunnel an diesem Slot im emittierten `TunnelPoolState`. |
| `tunnelState(slot, state)` | `remote.pool.tunnel.<slot>.<state>` | state ∈ `{dialing, up, backpressured, down}`. **GENAU EINER present** je gerenderter Zeile = current `state`. Fail-closed: kein Marker ohne echtes State-Signal; nie zwei zugleich; **nie optimistisch `up`**. |
| `tunnelRid(slot)` | `remote.pool.tunnel.<slot>.rid` | Der `rendezvousId` als **assertierter Content** (Korrelation). Present ⇔ Zeile present. |
| `tunnelSince(slot)` | `remote.pool.tunnel.<slot>.since` | **OPTIONAL/GEHALTEN** — dwell aus `sinceTs`. Default **nicht** gerendert; erst wenn PO dwell-Disclosure will. |

## Zwei bewusste Freeze-Entscheidungen (an Dev5 + WS4 + QA)

1. **scopeId = Pool-Slot-Index, nicht rendezvousId.** `rendezvousId` ist ein **opaker `String`** (`NoiseRelayConnector.kt:28`) ohne
   Charset-Garantie — er darf **nicht** roh in ein testTag-Segment (`[A-Za-z0-9-]+`, camelCase, keine Punkte). Daher: **stabiler
   0-basierter Slot-Index** als scopeId (deterministisch für WS4-Assertions), der rendezvousId als **Content** unter `.rid`. **WS4:
   key auf Index, lies rid als Content zur Korrelation.** (Falls WS1/WS2 garantieren, dass rid immer testTag-safe ist, kann das
   revidiert werden — **über den PO**, dann Contract-Revision.)
2. **State per Presence-Marker, nicht per Content-String.** Jeder State ist ein **eigener** Marker (`…​.dialing|up|backpressured|down`),
   von denen **genau einer** present ist. Das erlaubt WS4/QA, „Tunnel N ist BACKPRESSURED" per **Presence** zu assertieren (robuster
   als String-Vergleich) und macht die Fail-closed-Regel (kein Marker ohne echtes Signal) direkt testbar. Spiegelt die
   `RemoteConnectTags`-Linie (jeder Connect-Schritt = eigene Konstante).

## Self-Validation
- **7 Tag-Identifier**: `CONTAINER`, `AGGREGATE`, `AGGREGATE_BACKPRESSURED` (3 Konstanten) + `tunnel`, `tunnelState`, `tunnelRid`, `tunnelSince` (4 Funktionen; `tunnelSince` optional/gehalten).
- **Charset**: alle Segment-Werte erfüllen `[A-Za-z0-9-]+` (camelCase, keine Punkte im Wert). State-Werte `dialing`/`up`/`backpressured`/`down` ✓. slot = Int ✓. rid **nicht** im Tag (§Entscheidung 1).
- **Kollision**: kein bestehender `remote.pool.*`-Tag @ `7358c853` (grep 0 Treffer). Additiv zur `remote.*`-Familie, kein Konflikt mit `remote.connect.*`/`remote.relayDrop`/`remote.authStep.*`.
- **Area**: keine neue Area-Vokabel — `remote` besteht (CYP-429 §7). `remote.pool` = neuer Sub-Baum.
- **1:1 zur frozen C3-shape**: 4 States (enum) + aggregate{active,cap,anyBackpressured} vollständig adressiert; sinceTs als optionaler `.since` reserviert.
- **Ehrlichkeit im Contract verankert**: jede Present-Bedingung ist fail-closed (Signal ⇒ Tag, kein Signal ⇒ absent) — deckt Spec §4-Invarianten 1–5.
