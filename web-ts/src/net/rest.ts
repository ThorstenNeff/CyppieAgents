// CYP-400 (W2) — the REST client base for /api/* repositories. Auth per Spec 14 §6: the operator-token global
// (Bearer) when present, OR the first-party session cookie (credentials:"include") — the server accepts either.
// Public/MEMBER serve has no operator global -> Bearer omitted -> the cookie carries the logged-in end user.
import { operatorToken } from '../platform/operatorToken'
import type { HubId } from './hubRegistry'

export class RestError extends Error {
  constructor(
    readonly status: number,
    readonly method: string,
    readonly path: string,
    readonly body: string,
  ) {
    super(`${method} ${path} -> ${status}${body ? `: ${body}` : ''}`)
    this.name = 'RestError'
  }
}

/** The server's error envelope is `{ error: { code, message } }` (Spec 02 §7). Parse the machine `code` out of a
 *  RestError body so callers can map a specific reject to its curated message — never string-match the message. */
export function restErrorCode(err: unknown): string | null {
  if (!(err instanceof RestError) || err.body === '') return null
  try {
    const parsed = JSON.parse(err.body) as { error?: { code?: unknown } }
    return typeof parsed.error?.code === 'string' ? parsed.error.code : null
  } catch {
    return null
  }
}

// CYP-470/CYP-800: the 401 handler is now HUB-SCOPED (N4.b). Modell 2 talks to N hubs; a 401 from hub B (session
// expired/revoked THERE) must re-auth hub B ONLY — it must NOT tear down the hub-A session. So the handler is keyed
// by hubId, and each RestClient fires the handler for ITS hub. The AuthGate installs one per active hub. A failing
// call still throws RestError(401) as usual. (Full multi-hub wiring is additive; today one 'local' hub is keyed.)
/** Extract zod's `path:code` issues WITHOUT the received values (they can carry secrets/PII — CYP-420 §7.2). */
function issuesOf(e: unknown): string[] {
  const issues = (e as { issues?: { path?: unknown[]; code?: string }[] })?.issues
  if (!Array.isArray(issues)) return ['unparseable']
  return issues.map((i) => `${(i.path ?? []).join('.') || '(root)'}:${i.code ?? 'invalid'}`)
}

const onUnauthorizedByHub = new Map<HubId, () => void>()
/** Install (or, with null, clear) the 401 handler for ONE hub. Hub-scoped so a 401 at hub B never fires hub A's. */
export function setOnUnauthorized(hubId: HubId, handler: (() => void) | null): void {
  if (handler === null) onUnauthorizedByHub.delete(hubId)
  else onUnauthorizedByHub.set(hubId, handler)
}

/**
 * CYP-737 — a response validator: parses the untrusted body, or throws.
 *
 * Deliberately the same shape as a zod `.parse`, so a generated schema can be passed directly.
 */
export interface ResponseValidator<T> {
  /** The contract schema's name — carried so a failure says WHICH shape was expected, not just "invalid". */
  readonly schema: string
  readonly parse: (raw: unknown) => T
}

/**
 * Wrap a generated zod schema as a named validator.
 *
 * The name is required rather than derived: zod does not carry one, and an error that cannot say what it expected
 * sends the next reader to guess. `contractResponse('RepoConfigView', RepoConfigViewSchema)`.
 */
export function contractResponse<T>(schema: string, zod: { parse: (raw: unknown) => T }): ResponseValidator<T> {
  return { schema, parse: (raw) => zod.parse(raw) }
}

/**
 * Validate a parsed response body against a contract validator, or throw a MASKED [ResponseShapeError].
 *
 * The single validation+masking path, shared by [RestClient.request] and the raw-fetch call sites that cannot go
 * through RestClient (the multipart avatar upload, CYP-750). Keeping one path means every consumed body — JSON or
 * multipart-response — fails the same way (path:code only, never the received value) instead of some call sites
 * re-implementing the masking and drifting from it.
 */
export function validateResponse<T>(method: string, path: string, validate: ResponseValidator<T>, raw: unknown): T {
  try {
    return validate.parse(raw)
  } catch (e) {
    throw new ResponseShapeError(method, path, validate.schema, issuesOf(e))
  }
}

/**
 * CYP-737 — a REST response that did not match its contract.
 *
 * Distinct from [RestError] on purpose: the transport SUCCEEDED (a 200 arrived) and the body is still unusable.
 * Collapsing the two would hide which half broke. It carries only the schema name and zod's `path:code` issues —
 * never the received values, which is the same masking rule the WS boundary follows (CYP-420): a validation
 * message must not become an exfiltration channel for the payload it rejected.
 */
export class ResponseShapeError extends Error {
  constructor(
    readonly method: string,
    readonly path: string,
    readonly schema: string,
    readonly issues: readonly string[],
  ) {
    super(`${method} ${path}: response did not match ${schema} (${issues.join(', ')})`)
    this.name = 'ResponseShapeError'
  }
}

export class RestClient {
  // CYP-800 (N4.b): the client carries its hubId so a 401 fires ONLY this hub's re-auth handler, never a global.
  constructor(
    private readonly hubId: HubId,
    private readonly baseUrl: string,
  ) {}

  get<T>(path: string, validate?: ResponseValidator<T>): Promise<T> {
    return this.request<T>('GET', path, undefined, validate)
  }

  post<T>(path: string, body?: unknown, validate?: ResponseValidator<T>): Promise<T> {
    return this.request<T>('POST', path, body, validate)
  }

  put<T>(path: string, body?: unknown, validate?: ResponseValidator<T>): Promise<T> {
    return this.request<T>('PUT', path, body, validate)
  }

  delete<T>(path: string, validate?: ResponseValidator<T>): Promise<T> {
    return this.request<T>('DELETE', path, undefined, validate)
  }

  private async request<T>(method: string, path: string, body?: unknown, validate?: ResponseValidator<T>): Promise<T> {
    const headers: Record<string, string> = { accept: 'application/json' }
    const token = operatorToken()
    if (token !== null) headers['authorization'] = `Bearer ${token}`
    if (body !== undefined) headers['content-type'] = 'application/json'

    const res = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers,
      credentials: 'include',
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })

    if (res.status === 401) onUnauthorizedByHub.get(this.hubId)?.() // CYP-470/CYP-800: re-auth ONLY this hub (N4.b)
    if (!res.ok) throw new RestError(res.status, method, path, await res.text().catch(() => ''))
    if (res.status === 204) return undefined as T
    const raw: unknown = await res.json()
    // CYP-737: without a validator this stays the historical CAST — the honest description of what it is. Adoption
    // is per-call-site (see restRepo), and the coverage guard in rest.validation.test.ts keeps the remaining
    // unvalidated reads VISIBLE rather than quietly assumed safe.
    if (validate === undefined) return raw as T
    // A 200 whose body we cannot interpret is a FAILED read, not an empty one — it surfaces through the same honest
    // error+retry paths as any other failure (CYP-288/679), instead of flowing on as a plausible object with missing
    // fields. F1 is the concrete case: a body without `configured` became "hub not set up". (Shared masking path.)
    return validateResponse(method, path, validate, raw)
  }
}
