// CYP-425 (App-Assembly) — the runtime config bundle the assembly needs: where the API/WS live and the operator
// posture. Injectable socket deps let the whole app be driven by fake sockets in tests. (CYP-444: the PO identity
// is no longer here — it comes from the typed roster's role==PO, not a CYPPIE_PO_AGENT_ID config guess.)
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
}

export function readHubConfig(): HubConfig {
  return {
    apiBase: apiBaseUrl(),
    wsBase: wsBaseUrl(),
    token: operatorToken() ?? '',
    operator: isOperatorServe(),
  }
}

/** Injectable socket plumbing — real by default, fakes in tests (so App renders with no live WebSocket). */
export interface SocketDeps {
  factory?: SocketFactory
  schedule?: Scheduler
}
