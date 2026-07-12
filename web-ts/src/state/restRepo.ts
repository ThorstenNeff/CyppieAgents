// CYP-425 (App-Assembly) — the REST side of the hub, behind an interface so the store/App can be driven by a fake
// in tests. Channels/ACL ride the generated contract types (they appear in the WS schema too). The mode-change
// DTOs are REST-only and NOT yet in the generated contract (only asyncapi/WS DTOs are exported) — hand-modeled
// here as an interim, to be replaced by the generated types once CYP-426 lands the openapi/REST export.
import { RestClient } from '../net/rest'
import type {
  AclEntry,
  Agent,
  AgentDetail,
  ApiKeyView,
  Channel,
  Message1,
  AgentRunStateEvent,
  NewAgentSpec,
  AgentEdit,
  RepoConfigView,
  RepoConfigRequest,
  EventPage,
  ConnectorsView,
  ReportSnapshot,
  GenerateReportRequest,
} from '../types/generated/contract'
import { buildEventsQuery, type EventFilter } from '../eventlog/eventBrowse'
import type { ConnectorKind } from '../connector/connectorModel'

/** CYP-426 interim: `:core` TerminalMode. The server maps this to the terminal-control state machine. */
export type TerminalMode = 'ORCHESTRATION' | 'TERMINAL'

/** CYP-450: the removed agent's worktree fate. Default KEEP (non-destructive); DELETE is the explicit, warned path
 *  → maps to the server's `?worktree=delete` query (default keep). */
export type WorktreeFate = 'keep' | 'delete'

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
  /** CYP-450. GET /api/agents/{id} — the full config (incl. persona + launch, which the roster Agent omits) so the
   *  edit dialog can PREFILL current values rather than blank them out. */
  fetchAgentDetail(id: string): Promise<AgentDetail>
  /** CYP-450 (operator). POST /api/agents — create a config-only agent (NOT started; the caller shows the spawnHint
   *  and starts it via the P2-a lifecycle controls). Returns void: the server's CreatedAgent body carries the new
   *  agent's TOKEN (a secret) — never surfaced; the list refetches instead (non-optimistic). Rejects (agent_exists /
   *  po_already_exists / invalid_agent) are server-authoritative — surfaced from the RestError, never pre-guessed. */
  createAgent(spec: NewAgentSpec): Promise<void>
  /** CYP-450 (operator). PUT /api/agents/{id} — edit role/persona/launch/name (id + worktree are fixed). Takes
   *  effect on next start (the caller shows the amber restart hint). Rejects: po_already_exists / last_po. */
  updateAgent(id: string, edit: AgentEdit): Promise<void>
  /** CYP-450 (operator). DELETE /api/agents/{id}[?worktree=delete] — stop + remove. `fate` defaults to keep
   *  (non-destructive); 'delete' is the warned, destructive path. Reject: last_po (the only PO is undeletable). */
  removeAgent(id: string, fate: WorktreeFate): Promise<void>
  /** CYP-453. GET /api/config/repo (participant) — the project repo config { configured, url?, branch?, … }. Drives
   *  the honest "unset → agents can't start" status + prefills the operator-only inputs. */
  getRepoConfig(): Promise<RepoConfigView>
  /** CYP-453. PUT /api/config/repo (operator) — save url/branch. Non-optimistic: takes effect on new worktrees / next
   *  boot (the caller shows the amber effect-hint). Reject: invalid_repo_url — surfaced from the RestError. */
  putRepoConfig(req: RepoConfigRequest): Promise<RepoConfigView>
  /** CYP-452. GET /api/events?<filter>&afterSeq&limit — seq-paged historical Browse. The filter is applied
   *  SERVER-side (never a client post-filter); returns EventPage { events, nextAfterSeq?, hasMore }. */
  getEvents(filter: EventFilter, afterSeq: number | null, limit: number): Promise<EventPage>
  /** CYP-461/462. GET /api/connectors — the single source for the ADVISORY pre-choice capability preview per kind
   *  ({ connectors:[{kind, capabilities}], default? }). A load failure fails the opt-in closed (§4): no visible
   *  preview → no confirm. The preview is advisory, never a guarantee (§2). */
  getConnectors(): Promise<ConnectorsView>
  /** CYP-461 (operator). POST /api/agents/{id}/connector {connectorKind} — the ONLY path that changes an agent's
   *  connector (server re-checks + audits as connector.optin; anti-injection §6). connectorKind is deliberately NOT
   *  in AgentEdit/PATCH. Used only for EDIT; ADD carries the kind on NewAgentSpec.connectorKind. */
  setConnector(agentId: string, connectorKind: ConnectorKind): Promise<void>
  /** CYP-464 (operator). GET /api/reports — the newest-first snapshot list (full ReportSnapshots). Reports aggregate
   *  operator-gated observability → operator-only; content-free items. */
  fetchReports(): Promise<ReportSnapshot[]>
  /** CYP-464 (operator). POST /api/reports {type, since?, until?} — generate a NEW immutable snapshot (per-run, never
   *  mutated). Returns the new snapshot. */
  generateReport(req: GenerateReportRequest): Promise<ReportSnapshot>
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
  fetchAgentDetail(id: string): Promise<AgentDetail> {
    return this.rest.get<AgentDetail>(`/api/agents/${encodeURIComponent(id)}`)
  }
  async createAgent(spec: NewAgentSpec): Promise<void> {
    // ignore the CreatedAgent body (it carries the new agent's token — a secret); the list refetches instead.
    await this.rest.post<unknown>('/api/agents', spec)
  }
  async updateAgent(id: string, edit: AgentEdit): Promise<void> {
    await this.rest.put<unknown>(`/api/agents/${encodeURIComponent(id)}`, edit)
  }
  async removeAgent(id: string, fate: WorktreeFate): Promise<void> {
    // Default fate = keep (safe); only an explicit ?worktree=delete is destructive (server default is keep).
    const q = fate === 'delete' ? '?worktree=delete' : ''
    await this.rest.delete<void>(`/api/agents/${encodeURIComponent(id)}${q}`)
  }
  getRepoConfig(): Promise<RepoConfigView> {
    return this.rest.get<RepoConfigView>('/api/config/repo')
  }
  putRepoConfig(req: RepoConfigRequest): Promise<RepoConfigView> {
    return this.rest.put<RepoConfigView>('/api/config/repo', req)
  }
  getEvents(filter: EventFilter, afterSeq: number | null, limit: number): Promise<EventPage> {
    return this.rest.get<EventPage>(`/api/events${buildEventsQuery(filter, afterSeq, limit)}`)
  }
  getConnectors(): Promise<ConnectorsView> {
    return this.rest.get<ConnectorsView>('/api/connectors')
  }
  async setConnector(agentId: string, connectorKind: ConnectorKind): Promise<void> {
    // ConnectorChoice { connectorKind } — the audited, operator-only connector change (never via PATCH; §6).
    await this.rest.post<unknown>(`/api/agents/${encodeURIComponent(agentId)}/connector`, { connectorKind })
  }
  fetchReports(): Promise<ReportSnapshot[]> {
    return this.rest.get<ReportSnapshot[]>('/api/reports')
  }
  generateReport(req: GenerateReportRequest): Promise<ReportSnapshot> {
    return this.rest.post<ReportSnapshot>('/api/reports', req)
  }
}
