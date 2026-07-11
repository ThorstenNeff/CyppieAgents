// CYP-425 (App-Assembly) — the runtime config bundle the assembly needs: where the API/WS live, the operator
// posture, and the PO identity. Injectable socket deps let the whole app be driven by fake sockets in tests.
import { apiBaseUrl, wsBaseUrl, operatorToken, isOperatorServe } from '../platform/appConfig'
import type { SocketFactory, Scheduler } from '../net/reconnectingSocket'

export interface HubConfig {
  /** REST base (http[s]) — apiBaseUrl(). */
  apiBase: string
  /** WebSocket base (ws[s]) — wsBaseUrl(). */
  wsBase: string
  /** the `?token=` for WS + the Bearer for REST on the operator serve; '' on the member serve (cookie carries REST). */
  token: string
  /** operator serve (operator-token global present) → operator surfaces (shell, ACL writes, mode toggle) are live. */
  operator: boolean
  /**
   * The PO agent id, for the W9 lockout advisory (isPoLockoutChange needs role==PO, which a Channel does NOT
   * carry). Read from the explicit `CYPPIE_PO_AGENT_ID` deploy global — NEVER inferred from a `po-<worker>`
   * channel name. Null until injected (or until CYP-426 lands the real typed roster and we read role there).
   */
  poAgentId: string | null
}

/** The PO id the deploy injects alongside the operator token (interim; superseded by the CYP-426 roster's role==PO). */
export function configuredPoAgentId(): string | null {
  return (globalThis as { CYPPIE_PO_AGENT_ID?: string }).CYPPIE_PO_AGENT_ID ?? null
}

export function readHubConfig(): HubConfig {
  return {
    apiBase: apiBaseUrl(),
    wsBase: wsBaseUrl(),
    token: operatorToken() ?? '',
    operator: isOperatorServe(),
    poAgentId: configuredPoAgentId(),
  }
}

/** Injectable socket plumbing — real by default, fakes in tests (so App renders with no live WebSocket). */
export interface SocketDeps {
  factory?: SocketFactory
  schedule?: Scheduler
}
