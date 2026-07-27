// CYP-425 (App-Assembly) — the "live-socket VM": opens the /ws/comm and /ws/terminal-state sockets and folds
// every inbound event into the store (ACL echoes, channel snapshots, comm messages, per-agent terminal state).
// The socket factory/scheduler are injectable (SocketDeps) so tests drive the whole VM with fake sockets — no
// real WebSocket. Returns a stop() that closes both sockets (called on App unmount). Per-agent /ws/agent and
// /ws/terminal sockets are owned by the agent windows themselves (one socket per mounted window), not here.
import { commSocket, terminalStateFeed, lifecycleFeed, busyStateFeed, tokenUsageFeed, eventsSocket } from '../net/channels'
import type { FrameRejection } from '../net/wsValidation'
import type { HubConfig, SocketDeps } from './hubConfig'
import type {
  CommWsServerEvent,
  AgentTerminalControlEvent,
  AgentRunStateEvent,
  AgentBusyStateEvent,
  AgentTokenUsageEvent,
  EventsWsServerEvent,
} from '../types/generated/contract'

export interface HubActions {
  onCommEvent: (event: CommWsServerEvent) => void
  onTerminalControl: (event: AgentTerminalControlEvent) => void
  /** fired when /ws/comm (re)connects — drives the CommPanel connection banner (CYP-438). */
  onCommOpen?: () => void
  /** fired on an unexpected /ws/comm drop (code 1008 = revoked) — offline/revoked banner (CYP-437). */
  onCommClose?: (code?: number) => void
  /** CYP-834: fired when a /ws/comm frame fails :core-schema decode (unknown type / missing-required / unknown-enum) —
   *  a TERMINAL protocol-skew. The socket has already stopped itself (no reconnect); the view surfaces the skew banner. */
  onCommSkew?: (rejection: FrameRejection) => void
  /** server-confirmed per-agent run-state — drives the CYP-431 lifecycle header (non-optimistic). */
  onRunState?: (event: AgentRunStateEvent) => void
  /** CYP-641: live per-agent busy flag (/ws/busy-state) — drives the window-title activity marker. */
  onBusyState?: (event: AgentBusyStateEvent) => void
  /** CYP-641: live per-agent context-token count (/ws/token-usage) — drives the title-bar number. */
  onTokenUsage?: (event: AgentTokenUsageEvent) => void
  /** /ws/events feed (replay + Caughtup) — drives the CYP-432 event log. Provided ONLY for an operator: the event
   *  log carries message bodies (operator-only egress), so a non-operator must never open this socket (CYP-432). */
  onEventsEvent?: (event: EventsWsServerEvent) => void
  /** /ws/events dropped (code 1008 = access revoked) → fail-closed event log (CYP-432). */
  onEventsClose?: (code?: number) => void
  /** CYP-815: an UNEXPECTED drop on ANY of the 4 read-only status feeds (lifecycle/token/busy/terminal-state).
   *  A 1008 (auth revoked) is session-wide (same bearer) — surfaces the visible revoked signal so the run-state/
   *  token/busy/terminal indicators don't freeze silently claiming "still running" (safe-but-silent). Parity with
   *  onCommClose/onEventsClose. `code` is the WS close code (1008 = revoked). */
  onStatusClose?: (code?: number) => void
}

export interface LiveHubHandle {
  stop: () => void
}

export function startLiveHub(config: HubConfig, actions: HubActions, deps: SocketDeps = {}): LiveHubHandle {
  const common = { baseUrl: config.wsBase, token: config.token, factory: deps.factory, schedule: deps.schedule }
  const comm = commSocket({ ...common, onEvent: actions.onCommEvent, onOpen: actions.onCommOpen, onClose: actions.onCommClose, onReject: actions.onCommSkew })
  // CYP-815: the 4 read-only status feeds forward onClose too (parity with comm/events) — a 1008 revoke must not
  // freeze the run-state/token/busy/terminal indicators silently.
  const terminal = terminalStateFeed({ ...common, onEvent: actions.onTerminalControl, onClose: actions.onStatusClose })
  const lifecycle = lifecycleFeed({ ...common, onEvent: (e) => actions.onRunState?.(e), onClose: actions.onStatusClose })
  // CYP-641: the two read-only activity feeds — one global socket each, upserted by agentId in the store. Mounted
  // unconditionally (participant-gated by the server, same bearer as lifecycle); they carry no message bodies, so
  // no operator gate is needed (unlike /ws/events).
  const busy = busyStateFeed({ ...common, onEvent: (e) => actions.onBusyState?.(e), onClose: actions.onStatusClose })
  const tokenUsage = tokenUsageFeed({ ...common, onEvent: (e) => actions.onTokenUsage?.(e), onClose: actions.onStatusClose })
  comm.start()
  terminal.start()
  lifecycle.start()
  busy.start()
  tokenUsage.start()

  // CYP-432 fail-closed: only OPEN /ws/events when the caller wired onEventsEvent (operator). A non-operator never
  // starts the bodies-carrying socket at all — the client mount-gate is load-bearing defence-in-depth.
  const onEventsEvent = actions.onEventsEvent
  const events = onEventsEvent
    ? eventsSocket({ ...common, onEvent: onEventsEvent, onClose: actions.onEventsClose })
    : null
  events?.start()

  return {
    stop: () => {
      comm.close()
      terminal.close()
      lifecycle.close()
      busy.close()
      tokenUsage.close()
      events?.close()
    },
  }
}
