// CYP-401 (W3) / CYP-425 (App-Assembly) — wires /ws/agent -> mapper -> fold -> React state, and exposes `send`
// so the composer can post a human/operator turn on the SAME socket (one socket per agent window). One mapper per
// mount (its readySessions / toolCalls persist across reconnects within the mount); the socket auto-reconnects
// with `?since=` replay and seq-idempotency (W2), so the folded rows stay correct across a drop. The socket
// factory/scheduler are injectable so an agent window renders under jsdom without a real WebSocket (CYP-425 tests).
import { useCallback, useEffect, useRef, useState } from 'react'
import { AgentSocket } from '../net/agentSocket'
import { StreamJsonMapper } from './streamJsonMapper'
import { foldEvent } from './transcriptFolding'
import type { AgentEvent } from './agentEvent'
import type { SocketFactory, Scheduler } from '../net/reconnectingSocket'

export interface UseAgentTranscriptOptions {
  baseUrl: string
  agentId: string
  readyNoticeText: string
  factory?: SocketFactory
  schedule?: Scheduler
}

export interface AgentTranscriptHandle {
  rows: readonly AgentEvent[]
  /** Send a human/operator turn to the agent (the mediator injects it on stdin). Returns false if the socket is down. */
  send: (text: string) => boolean
}

export function useAgentTranscript(opts: UseAgentTranscriptOptions): AgentTranscriptHandle {
  const { baseUrl, agentId, readyNoticeText, factory, schedule } = opts
  const [rows, setRows] = useState<readonly AgentEvent[]>([])
  const socketRef = useRef<AgentSocket | null>(null)

  useEffect(() => {
    setRows([])
    const mapper = new StreamJsonMapper(readyNoticeText)
    const socket = new AgentSocket({
      baseUrl,
      agentId,
      factory,
      schedule,
      onEvent: (stored) => {
        const mapped = mapper.map(stored.event, stored.tsMs)
        if (mapped.length > 0) setRows((prev) => mapped.reduce<readonly AgentEvent[]>((acc, e) => foldEvent([...acc], e), prev))
      },
    })
    socketRef.current = socket
    socket.start()
    return () => {
      socket.close()
      socketRef.current = null
    }
  }, [baseUrl, agentId, readyNoticeText, factory, schedule])

  const send = useCallback((text: string) => socketRef.current?.send({ text }) ?? false, [])
  return { rows, send }
}
