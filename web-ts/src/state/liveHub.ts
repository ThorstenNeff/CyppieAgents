// CYP-425 (App-Assembly) — the "live-socket VM": opens /ws/comm and the muxed /ws/status socket (CYP-844) and folds
// every inbound event into the store (ACL echoes, channel snapshots, comm messages, and the four per-agent status
// kinds — run-state/token/busy/terminal — carried as one StatusFrame stream). The socket factory/scheduler are
// injectable (SocketDeps) so tests drive the whole VM with fake sockets — no real WebSocket. Returns a stop() that
// closes the sockets (called on App unmount). Per-agent /ws/agent and /ws/terminal sockets are owned by the agent
// windows themselves (one socket per mounted window), not here.
import { commSocket, statusFeed, eventsSocket } from '../net/channels'
import type { FrameRejection } from '../net/wsValidation'
import type { HubConfig, SocketDeps } from './hubConfig'
import type {
  CommWsServerEvent,
  AgentTerminalControlEvent,
  AgentRunStateEvent,
  AgentBusyStateEvent,
  AgentTokenUsageEvent,
  EventsWsServerEvent,
  StatusFrame,
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

function assertNever(x: never): never {
  throw new Error(`unhandled StatusFrame variant: ${JSON.stringify(x)}`)
}

// CYP-844: fan a muxed StatusFrame out to the same per-kind reducers the four legacy feeds fed. Exhaustive over the
// discriminant (assertNever) — a NEW status kind fails to COMPILE until it is wired here, so the mux can never silently
// drop a variant. `.event` is unwrapped verbatim (the reducers are unchanged; upsert-by-agentId lives in each store).
function dispatchStatus(frame: StatusFrame, actions: HubActions): void {
  switch (frame.type) {
    case 'lifecycle':
      actions.onRunState?.(frame.event)
      return
    case 'tokenUsage':
      actions.onTokenUsage?.(frame.event)
      return
    case 'busy':
      actions.onBusyState?.(frame.event)
      return
    case 'terminal':
      actions.onTerminalControl(frame.event)
      return
    default:
      assertNever(frame)
  }
}

export function startLiveHub(config: HubConfig, actions: HubActions, deps: SocketDeps = {}): LiveHubHandle {
  const common = { baseUrl: config.wsBase, token: config.token, factory: deps.factory, schedule: deps.schedule }
  const comm = commSocket({ ...common, onEvent: actions.onCommEvent, onOpen: actions.onCommOpen, onClose: actions.onCommClose, onReject: actions.onCommSkew })
  // CYP-844: ONE muxed status socket replaces the four separate feeds (lifecycle/token-usage/busy-state/terminal-state).
  // A StatusFrame is discriminated on `type`; we unwrap `.event` and fan it out to the SAME reducers the four feeds fed
  // (no reducer change — a pure 1:1 mux). Kept transient (OneWayFeed, no onReject): a schema-violated status frame is a
  // single-drop, the exact legacy behavior. onStatusClose keeps the CYP-815 parity (a 1008 revoke must not silently
  // freeze the run-state/token/busy/terminal indicators). Server keeps the four feeds until cutover (additive-parallel).
  const status = statusFeed({ ...common, onEvent: (f) => dispatchStatus(f, actions), onClose: actions.onStatusClose })
  comm.start()
  status.start()

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
      status.close()
      events?.close()
    },
  }
}
