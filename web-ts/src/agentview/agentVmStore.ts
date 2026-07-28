// CYP-890 (NR-3) — the SHARED agent view-model store. The agent's live VM (the /ws/agent socket + the folded transcript
// rows + the composer draft) is hoisted OUT of the component tree into a module-level registry keyed by agentId, so it
// is shared by every render site of the same agent and SURVIVES unmount/remount. This is what lets the nav-rail switch
// (Canvas floating window ↔ fullscreen destination of the same agent) happen WITHOUT tearing down and reconnecting the
// socket or losing the transcript/draft — the load-bearing NR-3 invariant.
//
// Lifecycle: a VM is created lazily on first acquire and KEPT ALIVE (the socket stays connected) regardless of component
// mount/unmount — components are pure views that subscribe. A VM is closed only when its agent leaves the roster
// (closeAgentVm) or in tests (resetAgentVms). Peak socket count is unchanged: the Canvas already mounts every agent
// window (one socket each); keeping them alive during a fullscreen excursion does not exceed that peak.
import { AgentSocket } from '../net/agentSocket'
import { StreamJsonMapper } from './streamJsonMapper'
import { foldEvent } from './transcriptFolding'
import type { AgentEvent } from './agentEvent'
import type { SocketFactory, Scheduler } from '../net/reconnectingSocket'

export interface AgentVmOptions {
  baseUrl: string
  agentId: string
  readyNoticeText: string
  factory?: SocketFactory
  schedule?: Scheduler
}

interface AgentVm {
  readonly socket: AgentSocket
  rows: readonly AgentEvent[]
  draft: string
  readonly listeners: Set<() => void>
}

const EMPTY_ROWS: readonly AgentEvent[] = []
const vms = new Map<string, AgentVm>()

function emit(vm: AgentVm): void {
  for (const l of vm.listeners) l()
}

/** Acquire the shared VM for an agent — create-if-absent (opening the socket once), else return the existing live VM.
 *  Idempotent: a second call for the same agentId reuses the same socket (no reconnect). First-acquirer's opts win
 *  (baseUrl/readyNotice are app-stable). Never closes on the caller's behalf — the VM outlives any single mount. */
export function acquireAgentVm(opts: AgentVmOptions): void {
  if (vms.has(opts.agentId)) return
  const mapper = new StreamJsonMapper(opts.readyNoticeText)
  const socket = new AgentSocket({
    baseUrl: opts.baseUrl,
    agentId: opts.agentId,
    factory: opts.factory,
    schedule: opts.schedule,
    onEvent: (stored) => {
      const vm = vms.get(opts.agentId)
      if (vm === undefined) return
      const mapped = mapper.map(stored.event, stored.tsMs)
      if (mapped.length > 0) {
        vm.rows = mapped.reduce<readonly AgentEvent[]>((acc, e) => foldEvent([...acc], e), vm.rows)
        emit(vm)
      }
    },
  })
  vms.set(opts.agentId, { socket, rows: EMPTY_ROWS, draft: '', listeners: new Set() })
  socket.start()
}

export function subscribeAgentVm(agentId: string, listener: () => void): () => void {
  const vm = vms.get(agentId)
  if (vm === undefined) return () => {}
  vm.listeners.add(listener)
  return () => {
    vm.listeners.delete(listener)
  }
}

export function agentRows(agentId: string): readonly AgentEvent[] {
  return vms.get(agentId)?.rows ?? EMPTY_ROWS
}

export function agentDraft(agentId: string): string {
  return vms.get(agentId)?.draft ?? ''
}

export function setAgentDraft(agentId: string, draft: string): void {
  const vm = vms.get(agentId)
  if (vm === undefined || vm.draft === draft) return
  vm.draft = draft
  emit(vm)
}

/** Send a human/operator turn to the agent on the shared socket. Returns false if the socket is down. */
export function sendToAgent(agentId: string, text: string): boolean {
  return vms.get(agentId)?.socket.send({ text }) ?? false
}

/** The agentIds with a live VM right now — lets the app reconcile against the roster and close VMs for gone agents. */
export function liveAgentVmIds(): string[] {
  return [...vms.keys()]
}

/** Close + drop an agent's VM — called when the agent leaves the roster (hygiene) or in tests. */
export function closeAgentVm(agentId: string): void {
  const vm = vms.get(agentId)
  if (vm === undefined) return
  vm.socket.close()
  vms.delete(agentId)
}

/** Test helper: tear down all VMs so each test starts from a clean registry. */
export function resetAgentVms(): void {
  for (const vm of vms.values()) vm.socket.close()
  vms.clear()
}
