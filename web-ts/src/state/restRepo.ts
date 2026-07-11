// CYP-425 (App-Assembly) — the REST side of the hub, behind an interface so the store/App can be driven by a fake
// in tests. Channels/ACL ride the generated contract types (they appear in the WS schema too). The mode-change
// DTOs are REST-only and NOT yet in the generated contract (only asyncapi/WS DTOs are exported) — hand-modeled
// here as an interim, to be replaced by the generated types once CYP-426 lands the openapi/REST export.
import { RestClient } from '../net/rest'
import type { AclEntry, Agent, ApiKeyView, Channel, Message1, AgentRunStateEvent } from '../types/generated/contract'

/** CYP-426 interim: `:core` TerminalMode. The server maps this to the terminal-control state machine. */
export type TerminalMode = 'ORCHESTRATION' | 'TERMINAL'

export interface HubRepo {
  /** GET /api/agents — the typed roster (id/name/role/…). The real source of the agent list + PO identity (CYP-444),
   *  replacing the channel-derived interim + the CYPPIE_PO_AGENT_ID config guess. */
  fetchAgents(): Promise<Agent[]>
  fetchChannels(): Promise<Channel[]>
  fetchAcl(): Promise<AclEntry[]>
  /** PUT /api/acl (operator). Returns the server-authoritative entry; the enforced flip also arrives as an AclEvent. */
  putAcl(entry: AclEntry): Promise<AclEntry>
  /** POST /api/agents/{id}/mode (operator). Non-optimistic: the confirmed flip arrives via /ws/terminal-state,
   *  not this response — callers await it only to surface a hard failure. */
  requestMode(agentId: string, target: TerminalMode): Promise<void>
  /** GET /api/channels/{id}/messages — ACL-filtered history; folded into the store (deduped by id, overlaps live). */
  getMessages(channelId: string, since?: number): Promise<Message1[]>
  /** POST /api/channels/{id}/messages — returns the server Message; the same message also echoes over /ws/comm. */
  postMessage(channelId: string, body: string): Promise<Message1>
  /** POST /api/agents/{id}/{start|stop|restart} (operator). Returns the server run-state; the same state also
   *  arrives on /ws/lifecycle — non-optimistic, so the header flips on that event, not the click (CYP-431). */
  setLifecycle(agentId: string, action: 'start' | 'stop' | 'restart'): Promise<AgentRunStateEvent>
  /** GET /api/config/apikey — the MASKED key view ({set, masked:"***last4"}). The plaintext key NEVER round-trips
   *  to the client — there is no field on ApiKeyView that could carry it (CYP-433). */
  getApiKey(): Promise<ApiKeyView>
  /** PUT /api/config/apikey (operator) — write-only: sends the new plaintext key, gets back only the MASKED view.
   *  The response carries no plaintext, so nothing to leak on the way back (CYP-433). */
  putApiKey(apiKey: string): Promise<ApiKeyView>
}

export class RestHubRepo implements HubRepo {
  private readonly rest: RestClient
  constructor(apiBase: string) {
    this.rest = new RestClient(apiBase)
  }
  fetchAgents(): Promise<Agent[]> {
    return this.rest.get<Agent[]>('/api/agents')
  }
  fetchChannels(): Promise<Channel[]> {
    return this.rest.get<Channel[]>('/api/channels')
  }
  fetchAcl(): Promise<AclEntry[]> {
    return this.rest.get<AclEntry[]>('/api/acl')
  }
  putAcl(entry: AclEntry): Promise<AclEntry> {
    return this.rest.put<AclEntry>('/api/acl', entry)
  }
  async requestMode(agentId: string, target: TerminalMode): Promise<void> {
    // ModeChangeRequest { target } — hand-modeled (CYP-426). We ignore the ModeChangeResponse body on purpose:
    // the view flips only on the /ws/terminal-state echo (non-optimistic), so this just proves the POST was accepted.
    await this.rest.post<unknown>(`/api/agents/${encodeURIComponent(agentId)}/mode`, { target })
  }
  getMessages(channelId: string, since?: number): Promise<Message1[]> {
    const q = since !== undefined ? `?since=${since}` : ''
    return this.rest.get<Message1[]>(`/api/channels/${encodeURIComponent(channelId)}/messages${q}`)
  }
  postMessage(channelId: string, body: string): Promise<Message1> {
    // SendMessageRequest { body } — hand-modeled (REST-only DTO, not in the asyncapi export; CYP-426).
    return this.rest.post<Message1>(`/api/channels/${encodeURIComponent(channelId)}/messages`, { body })
  }
  setLifecycle(agentId: string, action: 'start' | 'stop' | 'restart'): Promise<AgentRunStateEvent> {
    return this.rest.post<AgentRunStateEvent>(`/api/agents/${encodeURIComponent(agentId)}/${action}`)
  }
  getApiKey(): Promise<ApiKeyView> {
    return this.rest.get<ApiKeyView>('/api/config/apikey')
  }
  putApiKey(apiKey: string): Promise<ApiKeyView> {
    // write-only: the plaintext goes up in the body; the response is the MASKED view (no plaintext back).
    return this.rest.put<ApiKeyView>('/api/config/apikey', { apiKey })
  }
}
