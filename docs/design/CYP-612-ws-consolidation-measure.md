# CYP-612 — web-ts WebSocket Consolidation: `measure-before-decompose`

> **Status:** MEASURE ONLY. This document is the design-pass *input* — the current WS landscape measured at the
> object. It is **not** a decomposition plan and carries **no build/decompose commit**. The decompose decision
> (what to mux, in what order, touching which contract) is the coordinator + PL's, informed by this measure.
>
> **Measured on:** `develop` @ `18fc52fd` · web-ts (`web-ts/`) · Dev5 · 2026-07-27.
> All claims below are verified in code with `file:line` evidence.

---

## 0. Why this doc exists (and what it corrects)

The CYP-612 ticket (Auftraggeber, 2026-07-15) asks: *"Warum 14 WS gleichzeitig?"* and proposes consolidating
**"7 global singleton streams"** (comm/events/acl/lifecycle/token-usage/busy-state/terminal-state) → 1–2 muxed
streams to relieve tunnel-pool pressure (CYP-610) and make the DoS-cap bump (CYP-611, 16→24) unnecessary.

Measuring at the object corrects two load-bearing premises of that ticket:

1. **It is 6 global sockets, not 7.** `acl` is **not a socket** — there is no `/ws/acl` and no `aclSocket`
   anywhere in the client; ACL rides `/ws/comm` as an `Acl` frame inside `CommWsServerEvent`
   (`src/types/generated/contract.ts:11`). And `/ws/events` is **operator-conditional** — for a non-operator it
   is never opened at all (§4), so a non-operator runs **5** globals.
2. **"Shared reconnect logic" is already realized.** All 8 client sockets sit on a single
   `ReconnectingSocket` that owns `?token=` auth, auto-reconnect + backoff, and 1008-terminal handling
   (`src/net/reconnectingSocket.ts:1` — *"the channel-agnostic reconnecting WebSocket that ALL 8 frontend WS
   channels share"*). So the only open opportunity is **socket-COUNT reduction (multiplex)** — not de-duplicating
   reconnect code, which is done.

The distinct-**path** count is **8**, not 14. The "14" in the ticket counts *live instances* (per-agent sockets
× N mounted agent windows); the number of distinct WS **paths** the client speaks is 8.

---

## 1. Measured inventory — 8 distinct `/ws/` paths

**6 GLOBAL** (opened once per workspace in `startLiveHub`, `src/state/liveHub.ts`) + **2 PER-AGENT** (one per
mounted window). Grep of the client for opened paths returns exactly:
`ws/agent · ws/busy-state · ws/comm · ws/events · ws/lifecycle · ws/terminal · ws/terminal-state · ws/token-usage`.

| # | Path | Factory (file:line) | Scope | Feeds (store slice / VM) |
|---|------|---------------------|-------|--------------------------|
| 1 | `/ws/comm` | `commSocket` — `net/channels.ts:48` | GLOBAL (`liveHub.ts:48`) | `applyCommServerEvent` (`hubReducers.ts:216`): messages, channels snapshot, **ACL echoes**, read-state |
| 2 | `/ws/terminal-state` | `terminalStateFeed` — `channels.ts:75` | GLOBAL (`liveHub.ts:51`) | `terminalControlByAgent` (`hubReducers.ts:58`) |
| 3 | `/ws/lifecycle` | `lifecycleFeed` — `channels.ts:63` | GLOBAL (`liveHub.ts:52`) | `runStateByAgent` (`hubReducers.ts:62`) |
| 4 | `/ws/busy-state` | `busyStateFeed` — `channels.ts:71` | GLOBAL (`liveHub.ts:56`) | `busyByAgent` (`hubReducers.ts:70`) — CYP-641 |
| 5 | `/ws/token-usage` | `tokenUsageFeed` — `channels.ts:67` | GLOBAL (`liveHub.ts:57`) | `contextTokensByAgent` (`hubReducers.ts:73`) — CYP-641 |
| 6 | `/ws/events` | `eventsSocket` — `channels.ts:52` | GLOBAL **operator-only** (`liveHub.ts:64-70`) | event-log VM (`eventlog/eventLog.ts`) |
| 7 | `/ws/agent` | `AgentSocket` — `net/agentSocket.ts:29` | PER-AGENT (`useAgentTranscript.ts:35`) | structured transcript rows |
| 8 | `/ws/terminal` | `terminalSocket` — `channels.ts:57` | PER-AGENT (`XtermView.tsx:28`) | xterm PTY bytes |

`acl` intentionally absent: the ACL flip echo arrives via `/ws/comm` (`src/state/aclCommit.ts:2`).

---

## 2. Shared transport (already consolidated)

`src/net/reconnectingSocket.ts:42` `ReconnectingSocket` owns, for all 8:
- `?token=`/URL computation via a caller `url: () => string` recomputed on every (re)connect (`:24`) — lets a
  channel fold a cursor into the URL.
- Auto-reconnect + backoff (`:68-80`; `backoff.reset()` on open `:62`; `src/net/backoff.ts`).
- **1008 terminal close** handled ONCE (`:73-78`): code `1008` → `closed = true` → never reconnects → `onClose(code)`
  forwarded. (CYP-815 fixed the OneWayFeed forwarding of this; CYP-437/432 the consumers.)

Three thin wrappers add per-channel concerns (each ~50 LOC, structurally identical):
- `OneWayFeed<T>` (server→client) — `net/oneWayFeed.ts:29` — the 4 status feeds (#2–#5).
- `BidiFeed<TServer,TClient>` (adds typed `send`) — `net/bidiFeed.ts:27` — comm, events, terminal.
- `AgentSocket` (adds `since=` seq cursor + idempotent drop) — `agentSocket.ts:29` — the only replay-cursor wrapper.

Uniform validation boundary: `makeFrameValidator` + `deliverIfValid`/`rejectUnvalidated` (`net/wsValidation.ts`) in
every wrapper.

---

## 3. Per-socket properties that gate mux-ability

| Socket | Frame typing | Replay semantics | Gating | Mux verdict |
|--------|--------------|------------------|--------|-------------|
| lifecycle | flat `AgentRunStateEvent` (no `type` tag) | stateless live-only | participant (bearer) | **MUX candidate** |
| token-usage | flat `AgentTokenUsageEvent` | stateless live-only | participant | **MUX candidate** |
| busy-state | flat `AgentBusyStateEvent` | stateless live-only | participant | **MUX candidate** |
| terminal-state | flat `AgentTerminalControlEvent` | stateless live-only | participant | **MUX candidate** |
| comm | discriminated `CommWsServerEvent` (`type`) | snapshot-resend + upsert-by-id | participant | muxable (2nd tier) |
| events | discriminated `EventsWsServerEvent` | replay history + `Caughtup` marker; bounded ring `MAX_LIVE_EVENTS=1000` (`eventLog.ts:11`) | **operator-only egress** | **KEEP SEPARATE** |
| /ws/agent | `StoredAgentEvent` + `since=` seq cursor | full replay then `since=<lastSeq>`, drop `seq<=lastSeq` (`agentSocket.ts:36-57`) | **cookie auth (not `?token=`)** | **KEEP per-agent** |
| /ws/terminal | `TerminalServerFrame`/`ClientFrame` | none (PTY live) | ticket-token | **KEEP per-agent** |

The 4 status feeds are the clean mux target: same wrapper (`OneWayFeed`), same gate (participant bearer), stateless
live-only, low rate. Their only mux cost is that they carry **flat single-interface frames with no `type`
discriminator** — a mux envelope must synthesize a per-stream tag for them (the other 4 already self-discriminate).

---

## 4. Operator-gating / egress (why events must not be muxed into a participant stream)

`/ws/events` carries message **bodies** → operator-only egress. It is not merely hidden: for a non-operator the
socket is **never opened** (`liveHub.ts:64-70` opens it only when `onEventsEvent` is wired, which `App.tsx:407-410`
does only `if operator`; fail-closed defence-in-depth `App.tsx:258-259`; comment `liveHub.ts:30-32`). The other 5
globals are participant-gated by the same `?token=` bearer and carry no bodies (`liveHub.ts:53-55`). Muxing events
into a participant-gated stream would dissolve this egress boundary — so events stays a separate socket (or, if ever
muxed, only behind an operator-gated sub-channel, which reintroduces the very separation a mux was meant to remove).

---

## 5. Costs / risks — the HALT reasons (finalized)

A client-local mux is **not possible**; every consolidation route changes wire framing. In order of weight:

1. **Cross-team contract change (largest cost).** Frame framing lives in the generated contract, not the client:
   `contract.ts:1-4` — *"AUTO-GENERATED (CYP-399) — DO NOT EDIT … Source: contract/asyncapi.json (:core
   build-export)."* A mux envelope (a new tagged workspace-stream frame) is a change to `asyncapi.json` in the
   shared contract module + the 4 **server** producers (Backend2), then the web-ts consumer fan-out. This is a
   days-scale, multi-team refactor — exactly why the Auftraggeber deferred it under the noon deadline.
2. **Per-substream replay semantics diverge.** stateless (4 status) vs snapshot-resend+upsert (comm) vs
   replay+`Caughtup` (events) vs seq-cursor+idempotent-drop (agent). A mux must preserve each sub-stream's replay
   contract independently, or it regresses one of them. The 4 status feeds are safe to mux precisely because they
   share the *same* (trivial, stateless) semantics; mixing in comm/events/agent would force the envelope to carry
   per-substream cursor/replay state.
3. **Operator-egress boundary on events** (§4) — must never be folded into a participant-gated stream.
4. **Flat status frames need synthesized discriminators** — the 4 mux-candidate feeds have no `type` tag today; the
   envelope adds one (a contract change, ties to risk 1).
5. **`/ws/agent` auth-model divergence** — cookie session (not `?token=`) + seq cursor → least mux-compatible;
   keep per-agent.
6. **`/ws/hub` is deliberately edge-excluded** — repo-wide grep for `ws/hub` in web-ts returns zero hits; a mux
   must not reintroduce a hub-level socket into the browser surface.
7. **Interaction with CYP-610/611 is server-side.** Whether the consolidation lets CYP-611's cap revert 24→16 is a
   server pool-pressure question, not a web-ts one; the client mux only reduces the *count* it opens.

---

## 6. Bounded opportunity (measure output — NOT a committed decompose)

- **Tractable first cut:** mux the **4 stateless status OneWayFeeds** (lifecycle/token-usage/busy-state/
  terminal-state) → **1 "workspace-status" stream**. Distinct paths **8 → 5**; per-workspace globals **6 → 3**
  (status-mux + comm + events[operator]). Lowest risk: same wrapper, same gate, same (stateless) replay.
  Cost = the envelope-discriminator contract change (risk 4) + Backend2 producers.
- **Optional 2nd tier:** fold `comm` in (globals 3 → 2) — adds snapshot-resend replay to the envelope (risk 2).
- **Keep separate (measured):** `events` (operator egress + bounded ring), `/ws/agent` (cookie auth + seq cursor),
  `/ws/terminal` (per-agent PTY).

## 7. Open questions for the decompose (coordinator + PL + Backend2)

- Is the 4→1 status mux worth a cross-team contract change now, or does it wait behind Option-A multi-hub?
- Does Backend2 want one envelope frame type or a small family? (drives the asyncapi.json shape)
- After a status mux, does the server pool let CYP-611 revert 24→16? (server-side call)
- Multi-hub (CYP-807) opens *per-hub* stream sets — does the mux design need to be per-hub-aware from the start so
  it doesn't get redone? (sequencing question: measure vs Option-A land order)

> **Next:** CYP-612 parks pending the PL decompose decision (build not scope-approved). This measure informs it.
