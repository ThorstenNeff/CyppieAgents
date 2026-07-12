// CYP-400 (W2) — the /ws/agent client (the primary channel). Server->client: one text frame == one
// StoredAgentEvent (seq, agentId, projectId, tsMs, event); client->server: one UserTurn {text}. First connect
// omits `since` (full replay); every reconnect resumes `?since=<lastSeq>` and DROPS any frame with seq <= lastSeq,
// so a replay is idempotent (no reconnect duplicates) — the CYP-400 AC. Mirrors the Kotlin AgentWsClient (CYP-204).
// Types come from W1's generated contract (never hand-written).
//
// CYP-454 (PO1 2026-07-11): /ws/agent authenticates via the SAME-ORIGIN Kratos session cookie (CYP-230/413), which
// rides the WSS handshake automatically — NO `?token=` on the query (proxy-log-clean; CYP-31 WS-origin-guard is the
// CSRF defense-in-depth for this cookie handshake). The ticket-token `?token=` path (CYP-286) stays scoped to
// cross-origin (desktop-remote) + /ws/terminal + /ws/comm — those wrappers are unchanged.
import type { StoredAgentEvent, UserTurn } from '../types/generated/contract'
import { ReconnectingSocket, type SocketFactory, type Scheduler } from './reconnectingSocket'
import { Backoff } from './backoff'

export interface AgentSocketOptions {
  baseUrl: string
  agentId: string
  onEvent: (event: StoredAgentEvent) => void
  onOpen?: () => void
  backoff?: Backoff
  factory?: SocketFactory
  schedule?: Scheduler
}

export class AgentSocket {
  private lastSeq: number | null = null
  private readonly rs: ReconnectingSocket

  constructor(opts: AgentSocketOptions) {
    this.rs = new ReconnectingSocket({
      url: () => {
        // No token in the query — the same-origin session cookie authenticates the WSS handshake (CYP-454).
        const p = new URLSearchParams({ agentId: opts.agentId })
        if (this.lastSeq !== null) p.set('since', String(this.lastSeq))
        return `${opts.baseUrl}/ws/agent?${p.toString()}`
      },
      onText: (data) => {
        const event = JSON.parse(data) as StoredAgentEvent
        // Idempotency: drop anything at or before the cursor (a reconnect may re-send the cursor event).
        if (this.lastSeq !== null && event.seq <= this.lastSeq) return
        this.lastSeq = event.seq
        opts.onEvent(event)
      },
      onOpen: opts.onOpen,
      backoff: opts.backoff,
      factory: opts.factory,
      schedule: opts.schedule,
    })
  }

  start(): void {
    this.rs.connect()
  }

  /** Send an operator/human turn to the agent (mediator injects it on stdin). */
  send(turn: UserTurn): boolean {
    return this.rs.send(JSON.stringify(turn))
  }

  /** The last delivered seq — the reconnect cursor (exposed for tests / diagnostics). */
  cursor(): number | null {
    return this.lastSeq
  }

  close(): void {
    this.rs.close()
  }
}
