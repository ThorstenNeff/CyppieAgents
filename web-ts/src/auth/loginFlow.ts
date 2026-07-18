// CYP-515 (a) — the in-app login-core wire driver. Drives the Kratos same-origin BROWSER flow (#4 locked (b),
// Backend2-verified): GET /self-service/login/browser with `Accept: application/json` → Kratos returns the flow as
// JSON (no 303 redirect) and, on a successful submit, sets the httpOnly `ory_kratos_session` cookie NATIVELY (no
// custom proxy, no JS token). Parse {id, csrf} → POST /self-service/login?flow=<id> with the credentials IN THE BODY.
// Security invariants baked in per the spec §2.3:
//   ① credentials go in the request BODY (never the URL/query → no referrer/history/log leak); we NEVER read the
//      login response body — the session is the server-set httpOnly cookie, never a JS token (tooth ②);
//   ② a bad-credential 4xx collapses to ONE generic `rejected` (no enumeration). CYP-515 splits SYSTEM failures
//      out into `unavailable` — this does NOT weaken ②: `unavailable` fires PRE-CREDENTIAL, so it is byte-identical
//      for every e-mail (existing or not) and carries zero per-account signal;
//   ④ this uses DIRECT fetch, NOT the RestClient — so a login 401 does NOT fire the global setOnUnauthorized re-auth
//      hook (that would loop on the login surface, CYP-515). The 401 is just a generic reject here;
//   ⑤ a 429 is surfaced honestly with the server Retry-After hint, never a fake success, never an auto-retry;
//   ⑥ the browser flow is initialised FRESH per submit and REQUIRES the flow csrf_token → a flow with no id/csrf is
//      malformed/stale → fail-closed (never submit blind or with a missing token); a fresh flow is never stale.
import type { AuthMe } from '../types/generated/contract'
import { kratosBase } from './authConfig'
import { loginOutcomeFromAuth, type LoginResult } from './authModel'

export interface LoginDeps {
  /** Raw fetch (injectable for tests). MUST be a plain fetch — NOT the RestClient — so a login 401 never fires the
   *  global re-auth hook (tooth ④). */
  fetchImpl: typeof fetch
  /** The content-free whoami read (GET /api/auth/me) — the server is the source of truth for the post-login state. */
  fetchAuthMe: () => Promise<AuthMe>
  /** Kratos public base; defaults to the same-origin mount (authConfig.kratosBase()). */
  kratos?: string
}

interface FlowInit {
  id: string
  csrf: string | null
}

/** Parse the minimal slice of a Kratos login flow-init: the flow `id` + the `csrf_token` node value (version-tolerant,
 *  never reads `ui.messages` — no enumeration cue is ever parsed). Missing id → blank → the caller fails closed. */
function parseFlowInit(json: unknown): FlowInit {
  const root = (json ?? {}) as Record<string, unknown>
  const id = typeof root.id === 'string' ? root.id : ''
  let csrf: string | null = null
  const ui = root.ui as Record<string, unknown> | undefined
  const nodes = ui?.nodes
  if (Array.isArray(nodes)) {
    for (const n of nodes) {
      const attrs = (n as Record<string, unknown> | null)?.attributes as Record<string, unknown> | undefined
      if (attrs && attrs.name === 'csrf_token' && typeof attrs.value === 'string' && attrs.value !== '') {
        csrf = attrs.value
        break
      }
    }
  }
  return { id, csrf }
}

/** Build the in-app login submitter. Fail-closed throughout; enumeration-safe. A 4xx rejection of the credential
 *  POST is `rejected` (the ONLY "check your input" path); every earlier/system failure is `unavailable` (CYP-515). */
export function createLogin(deps: LoginDeps): (email: string, password: string) => Promise<LoginResult> {
  const kratos = deps.kratos ?? kratosBase()
  return async (email, password) => {
    try {
      // 1. Fresh BROWSER flow init (per submit → the flow/csrf token is never stale, §2.3⑥). `Accept: application/json`
      //    makes Kratos return the flow as JSON instead of a 303 redirect.
      const initRes = await deps.fetchImpl(`${kratos}/self-service/login/browser`, {
        method: 'GET',
        headers: { accept: 'application/json' },
        credentials: 'include',
      })
      // CYP-515 §3: the flow could not be STARTED — a system fault, not a credential verdict.
      if (!initRes.ok) return { kind: 'unavailable' }
      const flow = parseFlowInit(await initRes.json())
      // The browser flow REQUIRES the csrf_token from the flow JSON. No id OR no csrf = malformed/stale → fail-closed:
      // never submit blind or with a missing token (§2.3⑥).
      // CYP-515 §3: the proxy answered but broke the flow contract (no id / no csrf — e.g. HTML instead of the
      // flow JSON). Still fail-closed (we never submit blind), but reported as a SYSTEM failure. This is also the
      // Content-Type guard: a non-JSON body fails `initRes.json()` into the catch below, or lands here as no-id.
      if (flow.id === '' || flow.csrf === null) return { kind: 'unavailable' }

      // 2. Submit credentials in the BODY (never the URL/query). We do not read the response body: on success Kratos
      //    sets the httpOnly session cookie natively; web-ts never sees/stores a token (tooth ①/②).
      const body: Record<string, string> = { method: 'password', identifier: email, password, csrf_token: flow.csrf }
      const res = await deps.fetchImpl(`${kratos}/self-service/login?flow=${encodeURIComponent(flow.id)}`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', accept: 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body),
      })
      if (res.status === 429) return { kind: 'rateLimited', retryAfter: res.headers.get('retry-after') }
      if (!res.ok) return { kind: 'rejected' } // 400/401 bad creds → GENERIC; direct fetch → no global re-auth (tooth ④)

      // 3. Success: the httpOnly session cookie is now set. Ask the server (source of truth) for the verified state.
      const me = await deps.fetchAuthMe()
      return loginOutcomeFromAuth(me)
    } catch {
      // CYP-515 §3: init/parse/transport failure → SYSTEM, never a credential verdict. This deliberately also
      // covers a transport failure ON the credential POST (spec §3 "Naht-Ehrlichkeit" left the fine-tuning to me):
      // the network dying mid-submit is not the user getting their password wrong, and "check your input" would be
      // a false accusation. It likewise covers a post-2xx whoami transport failure — the copy then slightly
      // understates ("could not be started"), but misattributing a system fault to the credentials is the worse
      // error, and AuthGate re-resolves whoami anyway. Enumeration-safety is unaffected: no per-account variance.
      return { kind: 'unavailable' }
    }
  }
}
