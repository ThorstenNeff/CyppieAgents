// CYP-425 (App-Assembly) — the REST side of the hub, behind an interface so the store/App can be driven by a fake
// in tests. Channels/ACL ride the generated contract types (they appear in the WS schema too). The mode-change
// DTOs are REST-only and NOT yet in the generated contract (only asyncapi/WS DTOs are exported) — hand-modeled
// here as an interim, to be replaced by the generated types once CYP-426 lands the openapi/REST export.
import { RestClient, RestError, contractResponse} from '../net/rest'
import { operatorToken } from '../platform/operatorToken'
import {
  AgentSchema,
  ApiKeyViewSchema,
  AuthMeSchema,
  CapacitySchema,
  ChannelReadStateSchema,
  RepoConfigViewSchema,
} from '../types/generated/contractSchemas'
import { z } from 'zod'
import type {
  ChannelReadState,
  AclEntry,
  Agent,
  AgentDetail,
  Preset,
  ApiKeyView,
  Channel,
  ChannelShareView,
  Message1,
  AgentRunStateEvent,
  NewAgentSpec,
  AgentEdit,
  RepoConfigView,
  RepoConfigRequest,
  ReprovisionPreview,
  EventPage,
  ConnectorsView,
  ReportSnapshot,
  GenerateReportRequest,
  AuthMe,
  ProjectsView,
  Project,
  Capacity,
  CompactStatus,
  CompactConfig,
  WorkspaceMember,
  OperatorAudit,
  ClaudeMdView,
  ClaudeMdUpdate,
} from '../types/generated/contract'
import { buildEventsQuery, type EventFilter } from '../eventlog/eventBrowse'
import type { ConnectorKind } from '../connector/connectorModel'

/** CYP-426 interim: `:core` TerminalMode. The server maps this to the terminal-control state machine. */
export type TerminalMode = 'ORCHESTRATION' | 'TERMINAL'

/** CYP-450: the removed agent's worktree fate. Default KEEP (non-destructive); DELETE is the explicit, warned path
 *  → maps to the server's `?worktree=delete` query (default keep). */
export type WorktreeFate = 'keep' | 'delete'

/** CYP-651. DELETE /api/projects/{id} response — the cascade-delete receipt (hand-modeled; not in the generated
 *  contract, like the other REST DTOs). Content-free counts of what was torn down. */
export interface ProjectDeleteReceipt {
  projectId: string
  configRemoved: boolean
  eventsRemoved: number
  worktreesRemoved: number
}

export interface HubRepo {
  /** GET /api/agents — the typed roster (id/name/role/…). The real source of the agent list + PO identity (CYP-444),
   *  replacing the channel-derived interim + the CYPPIE_PO_AGENT_ID config guess. */
  fetchAgents(): Promise<Agent[]>
  fetchChannels(): Promise<Channel[]>
  /** CYP-705. GET /api/read-state (OPERATOR-tier) — the caller's per-channel cursors. A channel is present IFF a
   *  cursor exists; ABSENT ⇒ UNKNOWN (never an implied zero). A 403/failure leaves the view unavailable, which
   *  renders a visible "unknown" marker, never a silent all-clear. */
  fetchReadState(): Promise<ChannelReadState[]>
  /** CYP-705. POST /api/channels/{id}/read {upToSeq} — advances the caller's cursor to max(existing, upToSeq) and
   *  returns the updated state. NON-OPTIMISTIC: the badge clears on this echo (or the ReadStateEvent), never on
   *  the local scroll that triggered it. */
  markRead(channelId: string, upToSeq: number): Promise<ChannelReadState>
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
  /** CYP-453. PUT /api/config/repo (operator) — save url/branch (+ CYP-465 discardUnpushed). Non-optimistic: takes
   *  effect on new worktrees / next boot (the caller shows the amber effect-hint). Reject: invalid_repo_url. */
  putRepoConfig(req: RepoConfigRequest): Promise<RepoConfigView>
  /** CYP-465/466 (operator). GET /api/config/repo/reprovision-preview — the LIVE at-risk agents ({ reprovisionPending,
   *  atRisk:[{worktree,uncommitted,unpushed}] }). Fetched FRESH each time the discard dialog opens, NEVER cached — the
   *  operator confirms the loss they can SEE at that moment (§3). */
  getReprovisionPreview(): Promise<ReprovisionPreview>
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
  /** CYP-470. GET /api/auth/me (PUBLIC) — the content-free whoami {authenticated, role?, verified}. Drives the
   *  resolve-then-render session gate + operator/member (fail-closed to none/member). */
  fetchAuthMe(): Promise<AuthMe>
  /** CYP-467/94. GET /api/projects — the registry + active pointer ({activeProjectId, projects}). Drives the Event-
   *  Browse cross-project axis (operator-only): null=active(server-forced) → other project → 'all'. */
  getProjects(): Promise<ProjectsView>
  /** CYP-651 (operator). POST /api/projects {id,name} — create a project. 400 invalid_project_id · 409 project_exists.
   *  Non-optimistic: the caller refetches getProjects; never optimistically inserts. */
  createProject(id: string, name: string): Promise<Project>
  /** CYP-651 (operator). POST /api/projects/switch {projectId} — flip the active pointer. HEAVYWEIGHT + NON-OPTIMISTIC:
   *  the server drains/stops sessions (cap==1) and awaits BEFORE the flip; the caller keeps the switch PENDING until
   *  the 200 and never flips the pointer early. 404 project_not_found. Switch-to-active is a safe no-op. */
  switchProject(projectId: string): Promise<ProjectsView>
  /** CYP-651 (operator). PUT /api/projects/{id} {name} — rename (id is immutable). 400 invalid · 404 not_found. */
  renameProject(id: string, name: string): Promise<Project>
  /** CYP-651 (operator). DELETE /api/projects/{id}?deleteWorktrees= — HARD cascade-delete. `deleteWorktrees` defaults
   *  FALSE (worktrees kept). NO confirm-token/body: the client name-echo is a UX guard only. The SERVER ProjectGuard
   *  is authoritative: 404 project_not_found · 409 last_project · 409 active_project_protected (the active project must
   *  be switched away FIRST). A 2nd delete = 404 = 'already gone' → benign. */
  deleteProject(id: string, deleteWorktrees: boolean): Promise<ProjectDeleteReceipt>
  /** CYP-642 (S-G). GET /api/capacity — the server-authoritative hub-capacity snapshot ({current, estimatedMax?}).
   *  MEMBER-tier (all users get the readout). Drives the capacity pill; a null estimatedMax = max not yet estimated. */
  getCapacity(): Promise<Capacity>
  /** CYP-649. GET /api/compact/status (read-tier) — the server-owned CompactStatus (allowed/threshold/armed/running/
   *  lastRun/timings). null is never defaulted by the caller: an UNKNOWN status renders the facts absent. */
  getCompactStatus(): Promise<CompactStatus>
  /** CYP-649. POST /api/compact/config (OPERATOR, 403 else) — set allowed / threshold / timings. Non-optimistic: the
   *  UI reflects the server via a follow-up status read, never the local draft. Server range-validates (400). */
  setCompactConfig(config: CompactConfig): Promise<void>
  /** CYP-650 (OPERATOR-only). GET /api/workspace/members — the workspace member roster ({identityId, tier,
   *  displayName?}). Operator-only egress (enumeration seam): the caller mounts this only for an operator; a member
   *  never fetches it. Rows show a SHORT non-identifying label + the tier text. */
  getWorkspaceMembers(): Promise<WorkspaceMember[]>
  /** CYP-650 (OPERATOR-only). GET /api/audit?limit — recent operator actions ({actor, method, path, tsMs}). Content-
   *  free (verb + path, no bodies). Operator-only, same gate as the roster. */
  getOperatorAudit(limit?: number): Promise<OperatorAudit[]>
  /** CYP-657. GET /api/agents/{id}/claude-md — the LIVE CLAUDE.md ({content, exists, version?}). `version` is the
   *  optimistic-concurrency token; `exists=false` = no file yet (first write is expect-absent). A load failure fails
   *  closed in the panel (an error line, never a blank buffer that a save would clobber). */
  getClaudeMd(agentId: string): Promise<ClaudeMdView>
  /** CYP-657 (operator). POST /api/agents/{id}/claude-md {content, expectedVersion?} — write the persona file with an
   *  if-match. The SERVER is authoritative: a stale expectedVersion → 409 `claude_md_stale` (the caller opens the
   *  conflict dialog, never silently overwrites). Returns the FRESH ClaudeMdView echo so the caller re-syncs its
   *  buffer + version. Restart-deferred: the change takes effect on the agent's next spawn (amber effect hint). */
  updateClaudeMd(agentId: string, update: ClaudeMdUpdate): Promise<ClaudeMdView>
  /** CYP-658 (operator). PUT /api/agents/{id} with only `{avatar: preset}` — the DiceBear preset write path (JSON).
   *  Write-DTO is Preset-only (a client cannot forge an Upload ref; those are server-minted). Returns the fresh Agent
   *  echo so the caller re-syncs `agent.avatar` (non-optimistic — the server's avatar truth, never the local pick).
   *  Restart-agnostic: the avatar is cosmetic and takes effect immediately (not a spawn-deferred config). */
  setAvatarPreset(agentId: string, preset: Preset): Promise<Agent>
  /** CYP-658 (operator). POST /api/agents/{id}/avatar as multipart — the upload IS the commit (no server pre-preview).
   *  The server sniffs magic bytes (PNG/JPEG only), re-crops to 256×256, strips EXIF, and mints the Upload `ref`.
   *  Returns the fresh AgentDetail echo (new avatar + `ref` cache-bust). A reject is a UNIFORM 400 `avatar_rejected`
   *  with no reason → the caller shows a GENERIC error, never a fabricated why. */
  uploadAvatar(agentId: string, file: File): Promise<AgentDetail>
  /** CYP-658 (operator). DELETE /api/agents/{id}/avatar — clear back to the initials/colour fallback. 204, idempotent,
   *  no confirm token → the "reset to default?" confirm is the CLIENT's to own. */
  removeAvatar(agentId: string): Promise<void>
  /** CYP-659. GET /api/channels/{id}/share (read-tier — the badge/status is NOT a secret; scoped by canRead). The
   *  cross-project share view { shared, sharedAt?, reachableScope? }. */
  getChannelShare(channelId: string): Promise<ChannelShareView>
  /** CYP-659 (operator). PUT /api/channels/{id}/share { sharedWith } — SET/REPLACE the grantee project set. An empty
   *  set revokes (the store removes the record). Returns the fresh ChannelShareView echo → non-optimistic re-sync.
   *  403 `operator_required` / 404 `channel_not_found` are server-authoritative (surfaced, never pre-guessed). */
  shareChannel(channelId: string, sharedWith: string[]): Promise<ChannelShareView>
  /** CYP-659 (operator). DELETE /api/channels/{id}/share — revoke. Idempotent; the server responds 200 with the fresh
   *  ChannelShareView echo ({ shared:false }), NOT 204 — so this returns the echo (re-sync from it, non-optimistic). */
  unshareChannel(channelId: string): Promise<ChannelShareView>
}

export class RestHubRepo implements HubRepo {
  private readonly rest: RestClient
  constructor(private readonly apiBase: string) {
    this.rest = new RestClient(apiBase)
  }
  fetchAgents(): Promise<Agent[]> {
    // CYP-737: validated — a malformed roster otherwise becomes "no agents", an invented fact.
    return this.rest.get('/api/agents', contractResponse('Agent[]', z.array(AgentSchema)))
  }
  fetchChannels(): Promise<Channel[]> {
    return this.rest.get<Channel[]>('/api/channels')
  }
  fetchAcl(): Promise<AclEntry[]> {
    return this.rest.get<AclEntry[]>('/api/acl')
  }
  fetchReadState(): Promise<ChannelReadState[]> {
    // CYP-737: a malformed entry would otherwise become a confident unread count.
    return this.rest.get('/api/read-state', contractResponse('ChannelReadState[]', z.array(ChannelReadStateSchema)))
  }
  markRead(channelId: string, upToSeq: number): Promise<ChannelReadState> {
    return this.rest.post<ChannelReadState>(`/api/channels/${encodeURIComponent(channelId)}/read`, { upToSeq })
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
    // CYP-737: a malformed view otherwise reads as "kein Schlüssel hinterlegt" — a false, leak-sensitive claim.
    return this.rest.get('/api/config/apikey', contractResponse('ApiKeyView', ApiKeyViewSchema))
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
    // CYP-737 (F1's root): a 200 without `configured` used to read as "hub not set up".
    return this.rest.get('/api/config/repo', contractResponse('RepoConfigView', RepoConfigViewSchema))
  }
  putRepoConfig(req: RepoConfigRequest): Promise<RepoConfigView> {
    return this.rest.put<RepoConfigView>('/api/config/repo', req)
  }
  getReprovisionPreview(): Promise<ReprovisionPreview> {
    return this.rest.get<ReprovisionPreview>('/api/config/repo/reprovision-preview')
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
  fetchAuthMe(): Promise<AuthMe> {
    // CYP-737: the auth state gates the whole app — an uninterpretable body must fail, not default.
    return this.rest.get('/api/auth/me', contractResponse('AuthMe', AuthMeSchema))
  }
  getProjects(): Promise<ProjectsView> {
    return this.rest.get<ProjectsView>('/api/projects')
  }
  createProject(id: string, name: string): Promise<Project> {
    return this.rest.post<Project>('/api/projects', { id, name })
  }
  switchProject(projectId: string): Promise<ProjectsView> {
    return this.rest.post<ProjectsView>('/api/projects/switch', { projectId })
  }
  renameProject(id: string, name: string): Promise<Project> {
    return this.rest.put<Project>(`/api/projects/${encodeURIComponent(id)}`, { name })
  }
  deleteProject(id: string, deleteWorktrees: boolean): Promise<ProjectDeleteReceipt> {
    return this.rest.delete<ProjectDeleteReceipt>(`/api/projects/${encodeURIComponent(id)}?deleteWorktrees=${deleteWorktrees}`)
  }
  getCapacity(): Promise<Capacity> {
    // CYP-737: capacity drives a pill that must never invent numbers.
    return this.rest.get('/api/capacity', contractResponse('Capacity', CapacitySchema))
  }
  getCompactStatus(): Promise<CompactStatus> {
    return this.rest.get<CompactStatus>('/api/compact/status')
  }
  async setCompactConfig(config: CompactConfig): Promise<void> {
    await this.rest.post<unknown>('/api/compact/config', config)
  }
  getWorkspaceMembers(): Promise<WorkspaceMember[]> {
    return this.rest.get<WorkspaceMember[]>('/api/workspace/members')
  }
  getOperatorAudit(limit = 200): Promise<OperatorAudit[]> {
    return this.rest.get<OperatorAudit[]>(`/api/audit?limit=${encodeURIComponent(String(limit))}`)
  }
  getClaudeMd(agentId: string): Promise<ClaudeMdView> {
    return this.rest.get<ClaudeMdView>(`/api/agents/${encodeURIComponent(agentId)}/claude-md`)
  }
  updateClaudeMd(agentId: string, update: ClaudeMdUpdate): Promise<ClaudeMdView> {
    // The POST returns the fresh ClaudeMdView (content + new version) — the caller re-syncs baseline+version from it.
    return this.rest.post<ClaudeMdView>(`/api/agents/${encodeURIComponent(agentId)}/claude-md`, update)
  }
  setAvatarPreset(agentId: string, preset: Preset): Promise<Agent> {
    // AgentEdit with ONLY avatar set (role omitted = preserve; avoids the last-PO guard false-positive). Returns the
    // edited Agent so the caller re-syncs agent.avatar from the server (non-optimistic).
    return this.rest.put<Agent>(`/api/agents/${encodeURIComponent(agentId)}`, { avatar: preset })
  }
  async uploadAvatar(agentId: string, file: File): Promise<AgentDetail> {
    // Multipart — RestClient can't send FormData; a raw fetch mirroring its auth (operator Bearer if present, else the
    // session cookie). NO content-type header → the browser sets multipart/form-data + the boundary.
    const form = new FormData()
    form.append('file', file, file.name)
    const headers: Record<string, string> = { accept: 'application/json' }
    const token = operatorToken()
    if (token !== null) headers['authorization'] = `Bearer ${token}`
    const path = `/api/agents/${encodeURIComponent(agentId)}/avatar`
    const res = await fetch(`${this.apiBase}${path}`, { method: 'POST', headers, credentials: 'include', body: form })
    if (!res.ok) throw new RestError(res.status, 'POST', path, await res.text().catch(() => ''))
    return (await res.json()) as AgentDetail
  }
  async removeAvatar(agentId: string): Promise<void> {
    await this.rest.delete<void>(`/api/agents/${encodeURIComponent(agentId)}/avatar`)
  }
  getChannelShare(channelId: string): Promise<ChannelShareView> {
    return this.rest.get<ChannelShareView>(`/api/channels/${encodeURIComponent(channelId)}/share`)
  }
  shareChannel(channelId: string, sharedWith: string[]): Promise<ChannelShareView> {
    // AuthorizeShareRequest { sharedWith } — REST-only DTO, hand-modeled (not in the asyncapi export).
    return this.rest.put<ChannelShareView>(`/api/channels/${encodeURIComponent(channelId)}/share`, { sharedWith })
  }
  unshareChannel(channelId: string): Promise<ChannelShareView> {
    // The server responds 200 + the fresh view echo ({shared:false}), NOT 204 — RestClient parses the JSON echo.
    return this.rest.delete<ChannelShareView>(`/api/channels/${encodeURIComponent(channelId)}/share`)
  }
}
