// CYP-425 (App-Assembly) — the pure hub state + reducers: the "live-socket VM" core, framework-free and fully
// unit-tested. The Zustand shell (hubStore.ts) and the socket wiring (liveHub.ts) call these; all the honesty
// rules (non-optimistic ACL, id-dedup, deny-wins) live where they can be proven, not in a component.
//
// Idempotency (Spec 14 §2/§8 tooth 3): comm messages dedup by Message.id, so a /ws/comm reconnect replay adds
// no duplicates. Non-optimistic ACL (§W9.2): a PUT records a *pending* flag; the ENFORCED value flips only when
// the AclEvent echo arrives (applyAclEntry), which also clears that cell's pending — never the optimistic click.
import { READ_STATE_UNAVAILABLE, type UnreadView } from '../comm/unreadModel'
import type {
  ReadState as ReadStateWsEvent,
  AclEntry,
  Agent,
  Channel,
  DeliveredMessage,
  CommWsServerEvent,
  AgentTerminalControlEvent,
  AgentRunStateEvent,
  AgentBusyStateEvent,
  AgentTokenUsageEvent,
} from '../types/generated/contract'
import { pendingKey, type AclDimension, type PendingAcl } from '../comm/aclModel'
import type { TerminalControlState } from '../agentview/terminalModeSelection'

/** The /ws/comm connection posture the CommPanel banner reflects (CYP-438 wires connecting→live via onOpen; the
 *  offline/revoked distinction is CYP-437's banner work). */
// CYP-834: `skew` is a TERMINAL protocol-skew — the server sent a frame this client cannot decode against :core (a
// deploy mismatch). Like `revoked` it is terminal (no reconnect, composer locked) but DISTINCT: revoked = access
// removed (1008); skew = version/schema mismatch (needs an app update, not a re-auth).
export type CommConnection = 'live' | 'connecting' | 'offline' | 'revoked' | 'skew'

/** Server-confirmed process run-state (from the /ws/lifecycle feed). */
export type AgentRunState = AgentRunStateEvent['runState']
/** The ERROR-state reason CODE (CYP-446/CYP-421 wave) — a curated reason, not the raw code, is shown. */
export type AgentErrorCode = NonNullable<AgentRunStateEvent['errorCode']>
/** An in-flight lifecycle request (client-only, transient — resolved by the next AgentRunStateEvent). */
export type LifecycleAction = 'start' | 'stop' | 'restart'

export interface HubState {
  channels: readonly Channel[]
  /** the typed roster (GET /api/agents) — the real source of agent identity + roles (CYP-444). */
  roster: readonly Agent[]
  /** the agent id set for windows/ACL columns: the roster's ids ∪ any live channel member (so a runtime-added
   *  agent still surfaces before a roster refetch). Sorted, deduped. */
  agents: readonly string[]
  /** CYP-705: the server's read-state, keyed by channel. `unavailable` until GET /api/read-state answers —
   *  and an UNAVAILABLE view renders a visible "unknown" marker, never a silent all-clear (unknown ≠ zero). */
  unreadView: UnreadView
  aclEntries: readonly AclEntry[]
  /** cells with an in-flight PUT awaiting the AclEvent echo (keyed channel|agent|dim → requested value). */
  pendingAcl: PendingAcl
  /** comm timeline store, deduped by Message.id. CYP-744: entries are the DeliveredMessage ENVELOPE (stored
   *  message + server-resolved mention spans), not the bare message — the timeline renders chips from the spans,
   *  and the stored message rides untouched inside. The VM keeps + dedups so integration is a render, not a
   *  re-plumb. */
  messagesByChannel: ReadonlyMap<string, readonly DeliveredMessage[]>
  /** the server-confirmed per-agent terminal-control state (drives the non-optimistic mode toggle). */
  terminalStateByAgent: ReadonlyMap<string, TerminalControlState>
  /** CYP-644: the FULL last terminal-control event per agent (state + heldBy + since) — drives the handoff /
   *  context-lost landmark banner, which needs heldBy/since the enum map above discards. Same source (set together
   *  in applyTerminalControl), so it never drifts from terminalStateByAgent. */
  terminalControlByAgent: ReadonlyMap<string, AgentTerminalControlEvent>
  /** the /ws/comm connection posture (CommPanel banner). */
  commConnection: CommConnection
  /** server-confirmed process run-state per agent (CYP-431 lifecycle header); absent → UNKNOWN until the feed. */
  runStateByAgent: ReadonlyMap<string, AgentRunState>
  /** the ERROR reason code per agent (CYP-446), when the feed supplies one; drives the errorReason node. */
  errorCodeByAgent: ReadonlyMap<string, AgentErrorCode>
  /** a lifecycle request in flight per agent (CYP-431) — transient "Startet…/Neustart…", cleared by the next
   *  AgentRunStateEvent. Non-optimistic: the STATE flips only on that event, never on the click. */
  lifecyclePending: ReadonlyMap<string, LifecycleAction>
  /** CYP-641: the live `/ws/busy-state` flag per agent (drives the window-title activity marker). Absent → not busy
   *  (unknown ≠ busy); only an explicit busy=true lights it, an explicit false clears it. */
  busyByAgent: ReadonlyMap<string, boolean>
  /** CYP-641: the live `/ws/token-usage` context-token count per agent (drives the title-bar number). Absent OR a
   *  null value → no number (unknown ≠ 0). */
  contextTokensByAgent: ReadonlyMap<string, number | null>
}

export const emptyHubState: HubState = {
  channels: [],
  roster: [],
  agents: [],
  aclEntries: [],
  unreadView: READ_STATE_UNAVAILABLE,
  pendingAcl: new Map(),
  messagesByChannel: new Map(),
  terminalStateByAgent: new Map(),
  terminalControlByAgent: new Map(),
  commConnection: 'connecting',
  runStateByAgent: new Map(),
  errorCodeByAgent: new Map(),
  lifecyclePending: new Map(),
  busyByAgent: new Map(),
  contextTokensByAgent: new Map(),
}

/** Fold a /ws/lifecycle event: the server-confirmed run-state for one agent, which also RESOLVES any pending
 *  lifecycle request for it (the click's transient label ends when the server confirms — CYP-431 non-optimistic). */
export function applyRunState(state: HubState, ev: AgentRunStateEvent): HubState {
  const runStateByAgent = new Map(state.runStateByAgent)
  runStateByAgent.set(ev.agentId, ev.runState)
  // CYP-446: track the ERROR reason code — set it in ERROR, clear it on any other state (ERROR is never resolved,
  // but a fresh ERROR event without a code must not keep a stale reason). null/undefined code in ERROR → cleared →
  // the display falls to the fail-closed "reason not reported".
  const errorCodeByAgent = new Map(state.errorCodeByAgent)
  if (ev.runState === 'ERROR' && ev.errorCode != null) errorCodeByAgent.set(ev.agentId, ev.errorCode)
  else errorCodeByAgent.delete(ev.agentId)
  const lifecyclePending = new Map(state.lifecyclePending)
  lifecyclePending.delete(ev.agentId)
  return { ...state, runStateByAgent, errorCodeByAgent, lifecyclePending }
}

/** Mark a lifecycle request in flight (transient label + neutral dot); the STATE stays put until the feed. */
export function setLifecyclePending(state: HubState, agentId: string, action: LifecycleAction): HubState {
  const lifecyclePending = new Map(state.lifecyclePending)
  lifecyclePending.set(agentId, action)
  return { ...state, lifecyclePending }
}

/** Clear a lifecycle request that will NOT be confirmed by a feed event (the REST call was rejected). */
export function clearLifecyclePending(state: HubState, agentId: string): HubState {
  if (!state.lifecyclePending.has(agentId)) return state
  const lifecyclePending = new Map(state.lifecyclePending)
  lifecyclePending.delete(agentId)
  return { ...state, lifecyclePending }
}

/** Fold a batch of fetched history envelopes into state (each deduped by id — safe to overlap with live). */
export function ingestMessages(state: HubState, msgs: readonly DeliveredMessage[]): HubState {
  return msgs.reduce((s, m) => applyMessage(s, m), state)
}

/** The agent id set for windows/ACL columns: the typed roster's ids ∪ every channel member (CYP-444). The roster
 *  is the real source; channel members are unioned in so a runtime-added agent still surfaces before a roster
 *  refetch (and keeps the CYP-438 live-channels→new-window behaviour). Sorted, deduped. */
export function deriveAgents(channels: readonly Channel[], roster: readonly Agent[] = []): string[] {
  const set = new Set<string>()
  for (const a of roster) set.add(a.id)
  for (const ch of channels) for (const m of ch.members) set.add(m)
  return [...set].sort()
}

/** The PO's agent id from the typed roster (role === 'PO') — the real PO identity for the W9 lockout advisory,
 *  replacing the CYPPIE_PO_AGENT_ID config guess (CYP-444). Null until the roster loads (advisory just won't fire). */
export function rosterPoAgentId(roster: readonly Agent[]): string | null {
  return roster.find((a) => a.role === 'PO')?.id ?? null
}

export function applyChannels(state: HubState, channels: readonly Channel[]): HubState {
  return { ...state, channels, agents: deriveAgents(channels, state.roster) }
}

/** Fold the typed roster (GET /api/agents) — recomputes the agent id set (roster ∪ channel members). */
export function applyRoster(state: HubState, roster: readonly Agent[]): HubState {
  return { ...state, roster, agents: deriveAgents(state.channels, roster) }
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

/** Append a comm envelope, deduped by the stored message's id (reconnect replay is idempotent). */
// CYP-816 (§4a windowed retention-cap, from CYP-814 G6) — the per-channel in-memory timeline cap. A long-lived,
// high-traffic channel would otherwise grow this array + the DOM unboundedly. Messages are durably server-persisted
// (MessageStore) and re-fetchable via getMessages, so trimming the OLDEST loses nothing permanent — a memory WINDOW,
// not data loss. The timeline is NOT virtualized (plain `.map` render, App.tsx), so N ≈ the retained DOM-node budget:
// at ~4-5 nodes/message, 500 keeps a channel at ~2-3k nodes / <1MB — smooth, well above typical traffic. REVERSIBLE:
// tune with a render-perf pass (Dev5/Tester2). Scroll-back beyond the window returns on channel re-open (loadMessages
// re-fetches full history today); an incremental `before`/limit re-fetch is the dual-gate follow-up (the messages
// endpoint has no backward pagination yet — only forward `since`).
export const MESSAGE_TIMELINE_CAP = 500

export function applyMessage(state: HubState, delivered: DeliveredMessage): HubState {
  const { channelId, id } = delivered.message
  const existing = state.messagesByChannel.get(channelId) ?? []
  if (existing.some((d) => d.message.id === id)) return state // idempotent: drop the duplicate
  const appended = [...existing, delivered]
  // CYP-816: tail-cap, oldest-out. Covers live AND history-ingest — ingestMessages folds through here, so each fold
  // trims → net keeps the last N with no unbounded intermediate. Trims oldest-ARRIVED (≈ oldest-chronological).
  const capped =
    appended.length > MESSAGE_TIMELINE_CAP ? appended.slice(appended.length - MESSAGE_TIMELINE_CAP) : appended
  const messagesByChannel = new Map(state.messagesByChannel)
  messagesByChannel.set(channelId, capped)
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
      // CYP-744: the /ws/comm message frame now carries the DeliveredMessage envelope (`delivered`), not a bare
      // message — the WireProtocol/BYOA wire is unchanged (stored Message untouched), spans ride the frontend
      // envelope only. Reading `.delivered` here is the whole client half of that split.
      return applyMessage(state, event.delivered)
    // CYP-705 — the self-only read-state echo. THIS is what makes the badge non-optimistic: a local scroll only
    // asks (POST …/read); the count changes here, when the server confirms its own cursor.
    case 'readState':
      return applyReadState(state, event)
    default:
      // Exhaustiveness guard: adding a CommWsServerEvent variant without handling it fails to COMPILE here.
      // `noImplicitReturns` alone would not catch it — a `default: return state` swallows a new variant silently
      // ("handled", but wrongly). This makes the omission structural instead of per-reducer discipline.
      return assertNever(event)
  }
}

/** Fold one channel's server-confirmed read-state into the view (server-computed count — the client never adds). */
function applyReadState(state: HubState, ev: ReadStateWsEvent): HubState {
  const base = state.unreadView.kind === 'available' ? state.unreadView.channels : {}
  return {
    ...state,
    unreadView: {
      kind: 'available',
      // CYP-799: thread the server-computed hasUnreadMention straight through from the readState event — NEVER a
      // client-fabricated default. The server owns this bit (a mention landed in a channel past the read cursor);
      // the store slot must carry exactly what the server said, so a later mention-cue reads a real value, not `false`.
      channels: {
        ...base,
        [ev.channelId]: {
          channelId: ev.channelId,
          lastReadSeq: ev.lastReadSeq,
          unreadCount: ev.unreadCount,
          hasUnreadMention: ev.hasUnreadMention,
        },
      },
    },
  }
}

/** Compile-time proof that every union case is handled; unreachable at runtime by construction. */
function assertNever(x: never): never {
  throw new Error(`unhandled CommWsServerEvent variant: ${JSON.stringify(x)}`)
}

/** Fold a /ws/terminal-state event: the server-confirmed control state for one agent. */
export function applyTerminalControl(state: HubState, ev: AgentTerminalControlEvent): HubState {
  const terminalStateByAgent = new Map(state.terminalStateByAgent)
  terminalStateByAgent.set(ev.agentId, ev.state)
  // CYP-644: keep the FULL event too (heldBy/since for the banner) — set from the same ev so the two never drift.
  const terminalControlByAgent = new Map(state.terminalControlByAgent)
  terminalControlByAgent.set(ev.agentId, ev)
  return { ...state, terminalStateByAgent, terminalControlByAgent }
}

/** CYP-641: fold a /ws/busy-state event — the live "is this agent working" flag. An explicit false is STORED (not
 *  deleted) so it authoritatively clears a prior true; an agent never seen stays absent (→ the derive reads it as
 *  not busy). Fail-closed: unknown ≠ busy. */
export function applyBusyState(state: HubState, ev: AgentBusyStateEvent): HubState {
  const busyByAgent = new Map(state.busyByAgent)
  busyByAgent.set(ev.agentId, ev.busy)
  return { ...state, busyByAgent }
}

/** CYP-641: fold a /ws/token-usage event — the live context-token count. Stores `contextTokens ?? null`; a null (or
 *  an omitted field) authoritatively means "no number" (unknown ≠ 0), so the title bar shows nothing for it. */
export function applyTokenUsage(state: HubState, ev: AgentTokenUsageEvent): HubState {
  const contextTokensByAgent = new Map(state.contextTokensByAgent)
  contextTokensByAgent.set(ev.agentId, ev.contextTokens ?? null)
  return { ...state, contextTokensByAgent }
}
