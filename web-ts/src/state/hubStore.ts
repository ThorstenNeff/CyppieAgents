// CYP-425 (App-Assembly) — the Zustand shell over the pure hub reducers (hubReducers.ts). A thin reactive
// wrapper: every action just dispatches a reducer, so the honesty rules stay in the tested pure layer. Mirrors
// the windowStore pattern (all math in windowReducer). Components select the slices they need.
import { create } from 'zustand'
import {
  emptyHubState,
  applyChannels,
  applyRoster,
  applyAcl,
  applyCommEvent,
  applyTerminalControl,
  setAclPending,
  clearAclPending,
  ingestMessages,
  applyRunState,
  setLifecyclePending,
  clearLifecyclePending,
  applyBusyState,
  applyTokenUsage,
  type HubState,
  type CommConnection,
  type LifecycleAction,
} from './hubReducers'
import type { UnreadView } from '../comm/unreadModel'
import type {
  AclEntry,
  Agent,
  Channel,
  Message1,
  CommWsServerEvent,
  AgentTerminalControlEvent,
  AgentRunStateEvent,
  AgentBusyStateEvent,
  AgentTokenUsageEvent,
} from '../types/generated/contract'
import type { AclDimension } from '../comm/aclModel'

export interface HubStore extends HubState {
  setRoster: (roster: readonly Agent[]) => void
  setChannels: (channels: readonly Channel[]) => void
  /** CYP-705: seed the read-state view from GET /api/read-state (the WS ReadStateEvent keeps it fresh). */
  setUnreadView: (view: UnreadView) => void
  setAcl: (entries: readonly AclEntry[]) => void
  onCommEvent: (event: CommWsServerEvent) => void
  onTerminalControl: (event: AgentTerminalControlEvent) => void
  markAclPending: (channelId: string, agentId: string, dim: AclDimension, requested: boolean) => void
  clearAclPending: (channelId: string, agentId: string, dim: AclDimension) => void
  ingestMessages: (msgs: readonly Message1[]) => void
  setCommConnection: (connection: CommConnection) => void
  onRunState: (event: AgentRunStateEvent) => void
  markLifecyclePending: (agentId: string, action: LifecycleAction) => void
  clearLifecyclePending: (agentId: string) => void
  onBusyState: (event: AgentBusyStateEvent) => void
  onTokenUsage: (event: AgentTokenUsageEvent) => void
}

export const useHubStore = create<HubStore>((set) => ({
  ...emptyHubState,
  setRoster: (roster) => set((s) => applyRoster(s, roster)),
  setChannels: (channels) => set((s) => applyChannels(s, channels)),
  setUnreadView: (view) => set((s) => ({ ...s, unreadView: view })),
  setAcl: (entries) => set((s) => applyAcl(s, entries)),
  onCommEvent: (event) => set((s) => applyCommEvent(s, event)),
  onTerminalControl: (event) => set((s) => applyTerminalControl(s, event)),
  markAclPending: (channelId, agentId, dim, requested) => set((s) => setAclPending(s, channelId, agentId, dim, requested)),
  clearAclPending: (channelId, agentId, dim) => set((s) => clearAclPending(s, channelId, agentId, dim)),
  ingestMessages: (msgs) => set((s) => ingestMessages(s, msgs)),
  setCommConnection: (commConnection) => set({ commConnection }),
  onRunState: (event) => set((s) => applyRunState(s, event)),
  markLifecyclePending: (agentId, action) => set((s) => setLifecyclePending(s, agentId, action)),
  clearLifecyclePending: (agentId) => set((s) => clearLifecyclePending(s, agentId)),
  onBusyState: (event) => set((s) => applyBusyState(s, event)),
  onTokenUsage: (event) => set((s) => applyTokenUsage(s, event)),
}))
