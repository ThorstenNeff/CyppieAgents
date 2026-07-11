// CYP-425 (App-Assembly) — the "live-socket VM": opens the /ws/comm and /ws/terminal-state sockets and folds
// every inbound event into the store (ACL echoes, channel snapshots, comm messages, per-agent terminal state).
// The socket factory/scheduler are injectable (SocketDeps) so tests drive the whole VM with fake sockets — no
// real WebSocket. Returns a stop() that closes both sockets (called on App unmount). Per-agent /ws/agent and
// /ws/terminal sockets are owned by the agent windows themselves (one socket per mounted window), not here.
import { commSocket, terminalStateFeed } from '../net/channels'
import type { HubConfig, SocketDeps } from './hubConfig'
import type { CommWsServerEvent, AgentTerminalControlEvent } from '../types/generated/contract'

export interface HubActions {
  onCommEvent: (event: CommWsServerEvent) => void
  onTerminalControl: (event: AgentTerminalControlEvent) => void
  /** fired when /ws/comm (re)connects — drives the CommPanel connection banner (CYP-438). */
  onCommOpen?: () => void
  /** fired on an unexpected /ws/comm drop (code 1008 = revoked) — offline/revoked banner (CYP-437). */
  onCommClose?: (code?: number) => void
}

export interface LiveHubHandle {
  stop: () => void
}

export function startLiveHub(config: HubConfig, actions: HubActions, deps: SocketDeps = {}): LiveHubHandle {
  const common = { baseUrl: config.wsBase, token: config.token, factory: deps.factory, schedule: deps.schedule }
  const comm = commSocket({ ...common, onEvent: actions.onCommEvent, onOpen: actions.onCommOpen, onClose: actions.onCommClose })
  const terminal = terminalStateFeed({ ...common, onEvent: actions.onTerminalControl })
  comm.start()
  terminal.start()
  return {
    stop: () => {
      comm.close()
      terminal.close()
    },
  }
}
