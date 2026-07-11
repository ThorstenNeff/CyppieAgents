// CYP-425 (App-Assembly) — the pure hub state + reducers: the "live-socket VM" core, framework-free and fully
// unit-tested. The Zustand shell (hubStore.ts) and the socket wiring (liveHub.ts) call these; all the honesty
// rules (non-optimistic ACL, id-dedup, deny-wins) live where they can be proven, not in a component.
//
// Idempotency (Spec 14 §2/§8 tooth 3): comm messages dedup by Message.id, so a /ws/comm reconnect replay adds
// no duplicates. Non-optimistic ACL (§W9.2): a PUT records a *pending* flag; the ENFORCED value flips only when
// the AclEvent echo arrives (applyAclEntry), which also clears that cell's pending — never the optimistic click.
import type { AclEntry, Channel, Message1, CommWsServerEvent, AgentTerminalControlEvent } from '../types/generated/contract'
import { pendingKey, type AclDimension, type PendingAcl } from '../comm/aclModel'
import type { TerminalControlState } from '../agentview/terminalModeSelection'

/** The /ws/comm connection posture the CommPanel banner reflects (CYP-438 wires connecting→live via onOpen; the
 *  offline/revoked distinction is CYP-437's banner work). */
export type CommConnection = 'live' | 'connecting' | 'offline' | 'revoked'

export interface HubState {
  channels: readonly Channel[]
  /** derived from channels' members (sorted, deduped) — the ACL columns + which agent windows to open. */
  agents: readonly string[]
  aclEntries: readonly AclEntry[]
  /** cells with an in-flight PUT awaiting the AclEvent echo (keyed channel|agent|dim → requested value). */
  pendingAcl: PendingAcl
  /** comm timeline store, deduped by Message.id. The timeline UI (CommPanel) integrates with CYP-424; the VM
   *  already keeps + dedups the messages so that integration is a render, not a re-plumb. */
  messagesByChannel: ReadonlyMap<string, readonly Message1[]>
  /** the server-confirmed per-agent terminal-control state (drives the non-optimistic mode toggle). */
  terminalStateByAgent: ReadonlyMap<string, TerminalControlState>
  /** the /ws/comm connection posture (CommPanel banner). */
  commConnection: CommConnection
}

export const emptyHubState: HubState = {
  channels: [],
  agents: [],
  aclEntries: [],
  pendingAcl: new Map(),
  messagesByChannel: new Map(),
  terminalStateByAgent: new Map(),
  commConnection: 'connecting',
}

/** Fold a batch of fetched history messages into state (each deduped by id — safe to overlap with live). */
export function ingestMessages(state: HubState, msgs: readonly Message1[]): HubState {
  return msgs.reduce((s, m) => applyMessage(s, m), state)
}

/** The agent roster derived from channel membership (interim until CYP-426 exports the real Agent roster). The
 *  PO identity is NOT derivable here (role isn't on a Channel) — poAgentId comes from explicit config, never a
 *  `po-<worker>` name guess. */
export function deriveAgents(channels: readonly Channel[]): string[] {
  const set = new Set<string>()
  for (const ch of channels) for (const m of ch.members) set.add(m)
  return [...set].sort()
}

export function applyChannels(state: HubState, channels: readonly Channel[]): HubState {
  return { ...state, channels, agents: deriveAgents(channels) }
}

export function applyAcl(state: HubState, entries: readonly AclEntry[]): HubState {
  return { ...state, aclEntries: entries }
}

const sameCell = (e: AclEntry, channelId: string, agentId: string): boolean =>
  e.channelId === channelId && e.agentId === agentId

/** Upsert one enforced ACL cell (the AclEvent echo or a PUT response is authoritative for that cell): replace any
 *  existing entries for (channel,agent) with the echoed one, and clear that cell's pending flags — the echo is
 *  what flips the switch, per the non-optimistic rule. */
export function applyAclEntry(state: HubState, entry: AclEntry): HubState {
  const aclEntries = state.aclEntries.filter((e) => !sameCell(e, entry.channelId, entry.agentId)).concat(entry)
  const pendingAcl = new Map(state.pendingAcl)
  pendingAcl.delete(pendingKey(entry.channelId, entry.agentId, 'read'))
  pendingAcl.delete(pendingKey(entry.channelId, entry.agentId, 'write'))
  return { ...state, aclEntries, pendingAcl }
}

/** Record an in-flight PUT (the switch shows aria-busy; `checked` stays the enforced value until the echo). */
export function setAclPending(state: HubState, channelId: string, agentId: string, dim: AclDimension, requested: boolean): HubState {
  const pendingAcl = new Map(state.pendingAcl)
  pendingAcl.set(pendingKey(channelId, agentId, dim), requested)
  return { ...state, pendingAcl }
}

/** Clear an in-flight PUT that will NOT be echoed — i.e. the server REJECTED it (409 po_lockout_protected, 4xx).
 *  Without this the switch stays aria-busy forever, because applyAclEntry only clears pending on the AclEvent echo,
 *  which a rejected PUT never sends (CYP-435). */
export function clearAclPending(state: HubState, channelId: string, agentId: string, dim: AclDimension): HubState {
  const key = pendingKey(channelId, agentId, dim)
  if (!state.pendingAcl.has(key)) return state
  const pendingAcl = new Map(state.pendingAcl)
  pendingAcl.delete(key)
  return { ...state, pendingAcl }
}

/** Append a comm message, deduped by id (reconnect replay is idempotent). */
export function applyMessage(state: HubState, msg: Message1): HubState {
  const existing = state.messagesByChannel.get(msg.channelId) ?? []
  if (existing.some((m) => m.id === msg.id)) return state // idempotent: drop the duplicate
  const messagesByChannel = new Map(state.messagesByChannel)
  messagesByChannel.set(msg.channelId, [...existing, msg])
  return { ...state, messagesByChannel }
}

/** Fold a /ws/comm server event into state (the three server variants: acl echo, channels snapshot, message). */
export function applyCommEvent(state: HubState, event: CommWsServerEvent): HubState {
  switch (event.type) {
    case 'acl':
      return applyAclEntry(state, event.entry)
    case 'channels':
      return applyChannels(state, event.channels)
    case 'message':
      return applyMessage(state, event.message)
  }
}

/** Fold a /ws/terminal-state event: the server-confirmed control state for one agent. */
export function applyTerminalControl(state: HubState, ev: AgentTerminalControlEvent): HubState {
  const terminalStateByAgent = new Map(state.terminalStateByAgent)
  terminalStateByAgent.set(ev.agentId, ev.state)
  return { ...state, terminalStateByAgent }
}
