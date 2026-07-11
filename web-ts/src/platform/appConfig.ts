// CYP-408 (W10) — runtime config for the port-based coexistence (Spec §6). The SPA is served at the deploy/proxy
// on its own origin; Ktor is API-only on (possibly) a DIFFERENT origin + CORS. So the API/WS base can't be
// assumed same-origin — the deploy injects it as a global (like the operator token), per SPA origin. Fallbacks
// keep a same-origin proxy setup working too (no global → location.origin). This is the one runtime knob the
// port-based topology needs on the client; the CORS allowlist, proxy, :8085 serve + CSP are deploy/server-owned.
import { operatorToken, isOperatorServe } from './operatorToken'

interface DeployGlobals {
  CYPPIE_API_BASE?: string
  CYPPIE_WS_BASE?: string
}

const globals = (): DeployGlobals => globalThis as DeployGlobals
const stripTrailingSlash = (s: string): string => s.replace(/\/+$/, '')

/** REST API base. `CYPPIE_API_BASE` deploy global (cross-origin coexistence) or the same-origin fallback. */
export function apiBaseUrl(): string {
  const injected = globals().CYPPIE_API_BASE
  if (injected !== undefined && injected !== '') return stripTrailingSlash(injected)
  return typeof location !== 'undefined' ? location.origin : ''
}

/** WebSocket base. Explicit `CYPPIE_WS_BASE` global, else derived from the API base (http→ws, https→wss). */
export function wsBaseUrl(): string {
  const injected = globals().CYPPIE_WS_BASE
  if (injected !== undefined && injected !== '') return stripTrailingSlash(injected)
  return apiBaseUrl().replace(/^http/, 'ws')
}

export { operatorToken, isOperatorServe }
