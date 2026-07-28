// CYP-881 (DARK) — the tokenless-cookie read-WS ticket seam. The :server is READY (CYP-286): read sockets accept a
// short-lived single-use `?ticket=` minted at POST /api/ws-ticket, bound to the caller's requireCommReader subject.
// This is DARK-arming-prep: the flag defaults OFF and the DEFAULT read-socket path stays `?token=`/cookie. Activation
// (edge `?token=`-strip + same-origin) is deploy-gated and NOT built here.
import { operatorToken } from '../platform/operatorToken'
import type { WsTicket } from '../types/generated/contract'

/**
 * DARK flag. `globalThis.CYPPIE_WS_TICKET === true` turns on the read-WS `?ticket=` path; ABSENT/false (the default)
 * keeps the byte-unchanged `?token=`/cookie path. Mirrors the operatorToken() global-injection pattern — set by the
 * deploy at cutover, never a client default.
 */
export function wsTicketEnabled(): boolean {
  return (globalThis as { CYPPIE_WS_TICKET?: unknown }).CYPPIE_WS_TICKET === true
}

/**
 * Mint a FRESH single-use read-WS ticket (POST /api/ws-ticket → WsTicket). Same auth as any read: the same-origin
 * session cookie (`credentials:'include'`), plus the operator Bearer on the operator serve. Returns the opaque ticket
 * string to fold into the WS query. Called per (re)connect by reconnectingSocket when the flag is on (single-use-safe).
 */
/**
 * Fold the WS auth into the query string. A `ticket` (flag-on, minted per-connect) → `?…&ticket=<t>` and NO token; no
 * ticket (default / flag-off) → `?…&token=<token>`. The ticket and token are mutually exclusive.
 *
 * CYP-902 (CYP-230 credentials parity): a BLANK token is OMITTED — the client never sends `?token=` with an empty value;
 * the same-origin session cookie authenticates instead (mirrors the REST Bearer guard, which omits an absent operator
 * token). On a member cookie-only build `config.token` is `''`, so this keeps the read-feed handshake token-free rather
 * than sending `?token=` (harmless today given the server's ifBlank→cookie fallback, but consistent + defense-in-depth).
 * A non-blank token is folded exactly as before (byte-unchanged for the operator serve).
 */
export function wsAuthParams(query: Record<string, string> | undefined, token: string, ticket?: string): string {
  const p = new URLSearchParams({ ...(query ?? {}) })
  if (ticket !== undefined) p.set('ticket', ticket)
  else if (token.trim() !== '') p.set('token', token)
  return p.toString()
}

export async function mintWsTicket(apiBase: string): Promise<string> {
  const headers: Record<string, string> = { accept: 'application/json' }
  const token = operatorToken()
  if (token !== null) headers['authorization'] = `Bearer ${token}`
  const res = await fetch(`${apiBase}/api/ws-ticket`, { method: 'POST', headers, credentials: 'include' })
  if (!res.ok) throw new Error(`ws-ticket mint failed: ${res.status}`)
  const body = (await res.json()) as WsTicket
  return body.ticket
}
