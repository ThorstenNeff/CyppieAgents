// CYP-401 (W3) — wires /ws/agent -> mapper -> fold -> React state. One mapper per mount (its readySessions /
// toolCalls persist across reconnects within the mount); the socket auto-reconnects with `?since=` replay and
// seq-idempotency (W2), so the folded rows stay correct across a drop. Mounted per agent window by W4.
import { useEffect, useState } from 'react'
import { AgentSocket } from '../net/agentSocket'
import { StreamJsonMapper } from './streamJsonMapper'
import { foldEvent } from './transcriptFolding'
import type { AgentEvent } from './agentEvent'

export interface UseAgentTranscriptOptions {
  baseUrl: string
  agentId: string
  token: string
  readyNoticeText: string
}

export function useAgentTranscript(opts: UseAgentTranscriptOptions): readonly AgentEvent[] {
  const { baseUrl, agentId, token, readyNoticeText } = opts
  const [rows, setRows] = useState<readonly AgentEvent[]>([])

  useEffect(() => {
    setRows([])
    const mapper = new StreamJsonMapper(readyNoticeText)
    const socket = new AgentSocket({
      baseUrl,
      agentId,
      token,
      onEvent: (stored) => {
        const mapped = mapper.map(stored.event, stored.tsMs)
        if (mapped.length > 0) setRows((prev) => mapped.reduce<readonly AgentEvent[]>((acc, e) => foldEvent([...acc], e), prev))
      },
    })
    socket.start()
    return () => socket.close()
  }, [baseUrl, agentId, token, readyNoticeText])

  return rows
}
