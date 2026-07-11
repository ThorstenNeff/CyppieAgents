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
  TerminalServerFrame,
  TerminalClientFrame,
} from '../types/generated/contract'
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
}

// --- bidirectional channels -------------------------------------------------------------------------------
export function commSocket(o: ChannelBase & { onEvent: (e: CommWsServerEvent) => void }): BidiFeed<CommWsServerEvent, CommWsClientEvent> {
  return new BidiFeed<CommWsServerEvent, CommWsClientEvent>({ ...o, path: '/ws/comm' })
}

export function eventsSocket(o: ChannelBase & { onEvent: (e: EventsWsServerEvent) => void }): BidiFeed<EventsWsServerEvent, EventsWsClientEvent> {
  return new BidiFeed<EventsWsServerEvent, EventsWsClientEvent>({ ...o, path: '/ws/events' })
}

/** Per-agent PTY transport (Base64 byte frames + resize/exit). xterm rendering is W7. */
export function terminalSocket(o: ChannelBase & { agentId: string; onEvent: (f: TerminalServerFrame) => void }): BidiFeed<TerminalServerFrame, TerminalClientFrame> {
  const { agentId, ...base } = o
  return new BidiFeed<TerminalServerFrame, TerminalClientFrame>({ ...base, path: '/ws/terminal', query: { agentId } })
}

// --- one-way read-only feeds (global; the view layer upserts by agentId) -----------------------------------
export function lifecycleFeed(o: ChannelBase & { onEvent: (e: AgentRunStateEvent) => void }): OneWayFeed<AgentRunStateEvent> {
  return new OneWayFeed<AgentRunStateEvent>({ ...o, path: '/ws/lifecycle' })
}

export function tokenUsageFeed(o: ChannelBase & { onEvent: (e: AgentTokenUsageEvent) => void }): OneWayFeed<AgentTokenUsageEvent> {
  return new OneWayFeed<AgentTokenUsageEvent>({ ...o, path: '/ws/token-usage' })
}

export function busyStateFeed(o: ChannelBase & { onEvent: (e: AgentBusyStateEvent) => void }): OneWayFeed<AgentBusyStateEvent> {
  return new OneWayFeed<AgentBusyStateEvent>({ ...o, path: '/ws/busy-state' })
}

export function terminalStateFeed(o: ChannelBase & { onEvent: (e: AgentTerminalControlEvent) => void }): OneWayFeed<AgentTerminalControlEvent> {
  return new OneWayFeed<AgentTerminalControlEvent>({ ...o, path: '/ws/terminal-state' })
}
