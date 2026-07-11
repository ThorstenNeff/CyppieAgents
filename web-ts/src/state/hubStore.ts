// CYP-425 (App-Assembly) — the Zustand shell over the pure hub reducers (hubReducers.ts). A thin reactive
// wrapper: every action just dispatches a reducer, so the honesty rules stay in the tested pure layer. Mirrors
// the windowStore pattern (all math in windowReducer). Components select the slices they need.
import { create } from 'zustand'
import {
  emptyHubState,
  applyChannels,
  applyAcl,
  applyCommEvent,
  applyTerminalControl,
  setAclPending,
  clearAclPending,
  type HubState,
} from './hubReducers'
import type { AclEntry, Channel, CommWsServerEvent, AgentTerminalControlEvent } from '../types/generated/contract'
import type { AclDimension } from '../comm/aclModel'

export interface HubStore extends HubState {
  setChannels: (channels: readonly Channel[]) => void
  setAcl: (entries: readonly AclEntry[]) => void
  onCommEvent: (event: CommWsServerEvent) => void
  onTerminalControl: (event: AgentTerminalControlEvent) => void
  markAclPending: (channelId: string, agentId: string, dim: AclDimension, requested: boolean) => void
  clearAclPending: (channelId: string, agentId: string, dim: AclDimension) => void
}

export const useHubStore = create<HubStore>((set) => ({
  ...emptyHubState,
  setChannels: (channels) => set((s) => applyChannels(s, channels)),
  setAcl: (entries) => set((s) => applyAcl(s, entries)),
  onCommEvent: (event) => set((s) => applyCommEvent(s, event)),
  onTerminalControl: (event) => set((s) => applyTerminalControl(s, event)),
  markAclPending: (channelId, agentId, dim, requested) => set((s) => setAclPending(s, channelId, agentId, dim, requested)),
  clearAclPending: (channelId, agentId, dim) => set((s) => clearAclPending(s, channelId, agentId, dim)),
}))
