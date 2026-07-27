// CYP-400 (W2-rest) — typed constructors for the remaining 7 frontend WS channels, wired to the generated
// contract types (W1). Endpoints/params verified against the Kotlin clients: /ws/comm, /ws/events and the four
// read-only feeds are `?token=` only (one global socket each; the view layer upserts by agentId); /ws/terminal
// is per-agent (`agentId`). /ws/agent has its own client (agentSocket.ts, seq-idempotent). The /ws/terminal
// client here is the TRANSPORT only — the xterm rendering is W7.
import type {
  CommWsServerEvent,
  CommWsClientEvent,
  EventsWsServerEvent,
  EventsWsClientEvent,
  AgentRunStateEvent,
  AgentTokenUsageEvent,
  AgentBusyStateEvent,
  AgentTerminalControlEvent,
  StatusFrame,
  TerminalServerFrame,
  TerminalClientFrame,
} from '../types/generated/contract'
import { makeFrameValidator, type FrameRejection } from './wsValidation'
import {
  CommWsServerEventSchema,
  EventsWsServerEventSchema,
  TerminalServerFrameSchema,
  AgentRunStateEventSchema,
  AgentTokenUsageEventSchema,
  AgentBusyStateEventSchema,
  AgentTerminalControlEventSchema,
  StatusFrameSchema,
} from '../types/generated/contractSchemas'
import { OneWayFeed } from './oneWayFeed'
import { BidiFeed } from './bidiFeed'
import type { SocketFactory, Scheduler } from './reconnectingSocket'
import type { Backoff } from './backoff'

interface ChannelBase {
  baseUrl: string
  token: string
  backoff?: Backoff
  factory?: SocketFactory
  schedule?: Scheduler
  /** fired on each (re)connect open — the view layer uses it for a connection banner (CYP-438). */
  onOpen?: () => void
  /** fired on an unexpected drop (code 1008 = auth revoked) — offline/revoked banner (CYP-437). */
  onClose?: (code?: number) => void
  /** CYP-420: override the generated runtime validator (tests only — production uses the contract schema). */
  validate?: (raw: unknown) => never
}

// --- bidirectional channels -------------------------------------------------------------------------------
// CYP-834: comm accepts an optional channel-scoped `onReject` (a schema violation = terminal protocol-skew). It rides
// through `...o` into BidiFeed; absent → the default global-drop behavior is unchanged.
export function commSocket(
  o: ChannelBase & { onEvent: (e: CommWsServerEvent) => void; onReject?: (rejection: FrameRejection) => void },
): BidiFeed<CommWsServerEvent, CommWsClientEvent> {
  return new BidiFeed<CommWsServerEvent, CommWsClientEvent>({ ...o, path: '/ws/comm', validate: o.validate ?? makeFrameValidator('CommWsServerEvent', CommWsServerEventSchema) })
}

export function eventsSocket(o: ChannelBase & { onEvent: (e: EventsWsServerEvent) => void }): BidiFeed<EventsWsServerEvent, EventsWsClientEvent> {
  return new BidiFeed<EventsWsServerEvent, EventsWsClientEvent>({ ...o, path: '/ws/events', validate: o.validate ?? makeFrameValidator('EventsWsServerEvent', EventsWsServerEventSchema) })
}

/** Per-agent PTY transport (Base64 byte frames + resize/exit). xterm rendering is W7. */
export function terminalSocket(o: ChannelBase & { agentId: string; onEvent: (f: TerminalServerFrame) => void }): BidiFeed<TerminalServerFrame, TerminalClientFrame> {
  const { agentId, ...base } = o
  return new BidiFeed<TerminalServerFrame, TerminalClientFrame>({ ...base, path: '/ws/terminal', query: { agentId }, validate: o.validate ?? makeFrameValidator('TerminalServerFrame', TerminalServerFrameSchema) })
}

// --- one-way read-only feeds (global; the view layer upserts by agentId) -----------------------------------
export function lifecycleFeed(o: ChannelBase & { onEvent: (e: AgentRunStateEvent) => void }): OneWayFeed<AgentRunStateEvent> {
  return new OneWayFeed<AgentRunStateEvent>({ ...o, path: '/ws/lifecycle', validate: o.validate ?? makeFrameValidator('AgentRunStateEvent', AgentRunStateEventSchema) })
}

export function tokenUsageFeed(o: ChannelBase & { onEvent: (e: AgentTokenUsageEvent) => void }): OneWayFeed<AgentTokenUsageEvent> {
  return new OneWayFeed<AgentTokenUsageEvent>({ ...o, path: '/ws/token-usage', validate: o.validate ?? makeFrameValidator('AgentTokenUsageEvent', AgentTokenUsageEventSchema) })
}

export function busyStateFeed(o: ChannelBase & { onEvent: (e: AgentBusyStateEvent) => void }): OneWayFeed<AgentBusyStateEvent> {
  return new OneWayFeed<AgentBusyStateEvent>({ ...o, path: '/ws/busy-state', validate: o.validate ?? makeFrameValidator('AgentBusyStateEvent', AgentBusyStateEventSchema) })
}

export function terminalStateFeed(o: ChannelBase & { onEvent: (e: AgentTerminalControlEvent) => void }): OneWayFeed<AgentTerminalControlEvent> {
  return new OneWayFeed<AgentTerminalControlEvent>({ ...o, path: '/ws/terminal-state', validate: o.validate ?? makeFrameValidator('AgentTerminalControlEvent', AgentTerminalControlEventSchema) })
}

// CYP-844: the MUXED status feed — one global socket carrying all four read-only status kinds as a discriminated
// union (StatusFrame.type ∈ {lifecycle,tokenUsage,busy,terminal}, each wrapping its `event` verbatim). Replaces the
// four separate feeds above on the client (server keeps emitting both until cutover — additive-parallel). Kept a
// OneWayFeed with NO onReject: a schema-violated status frame is a TRANSIENT single-drop (channel lives), the exact
// legacy behavior — terminal-skew stays reserved for the content-bearing /ws/comm surface (CYP-834/839 trust context).
export function statusFeed(o: ChannelBase & { onEvent: (f: StatusFrame) => void }): OneWayFeed<StatusFrame> {
  return new OneWayFeed<StatusFrame>({ ...o, path: '/ws/status', validate: o.validate ?? makeFrameValidator('StatusFrame', StatusFrameSchema) })
}
