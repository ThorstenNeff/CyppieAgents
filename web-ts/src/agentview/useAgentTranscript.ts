// CYP-401 (W3) / CYP-425 (App-Assembly) / CYP-890 (NR-3) — the React view over an agent's SHARED VM. The socket + folded
// rows + composer draft no longer live in this hook's component-local state; they live in the module-level agentVmStore
// keyed by agentId (see agentVmStore.ts). This hook acquires the shared VM (create-if-absent, keep-alive) and subscribes
// to it via useSyncExternalStore. Consequence: two render sites of the same agent (the Canvas floating window and the
// fullscreen nav destination) share ONE socket + ONE transcript, and switching between them does NOT reconnect or reset
// — the load-bearing NR-3 invariant. `send` posts a human/operator turn on that same shared socket.
import { useCallback, useMemo, useSyncExternalStore } from 'react'
import { acquireAgentVm, subscribeAgentVm, agentRows, agentDraft, setAgentDraft, sendToAgent, type AgentVmOptions } from './agentVmStore'
import type { AgentEvent } from './agentEvent'

export type UseAgentTranscriptOptions = AgentVmOptions

export interface AgentTranscriptHandle {
  rows: readonly AgentEvent[]
  /** Send a human/operator turn to the agent (the mediator injects it on stdin). Returns false if the socket is down. */
  send: (text: string) => boolean
  /** The shared composer draft for this agent — survives a destination switch (lives in the VM, not the component). */
  draft: string
  setDraft: (text: string) => void
}

export function useAgentTranscript(opts: UseAgentTranscriptOptions): AgentTranscriptHandle {
  const { agentId } = opts
  // Acquire the shared VM (create-if-absent, keep-alive). Idempotent — a re-mount or a StrictMode double-invoke reuses
  // the existing socket, never opening a second one. Kept in useMemo so the VM exists before the subscriptions read it.
  useMemo(() => acquireAgentVm(opts), [agentId, opts.baseUrl, opts.readyNoticeText, opts.factory, opts.schedule])

  const subscribe = useCallback((cb: () => void) => subscribeAgentVm(agentId, cb), [agentId])
  const rows = useSyncExternalStore(subscribe, () => agentRows(agentId))
  const draft = useSyncExternalStore(subscribe, () => agentDraft(agentId))

  const send = useCallback((text: string) => sendToAgent(agentId, text), [agentId])
  const setDraft = useCallback((text: string) => setAgentDraft(agentId, text), [agentId])
  return { rows, send, draft, setDraft }
}
