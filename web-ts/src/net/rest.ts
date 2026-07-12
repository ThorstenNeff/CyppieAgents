// CYP-400 (W2) — the REST client base for /api/* repositories. Auth per Spec 14 §6: the operator-token global
// (Bearer) when present, OR the first-party session cookie (credentials:"include") — the server accepts either.
// Public/MEMBER serve has no operator global -> Bearer omitted -> the cookie carries the logged-in end user.
import { operatorToken } from '../platform/operatorToken'

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

// CYP-470: a global 401 handler. Every /api/* 401 (session expired/revoked) fires it → the AuthGate re-auth-redirects
// (clears operator UI, no stale, no retry loop). Module-level so every repo/client routes through the one handler
// without threading it; the AuthGate installs it on mount. The failing call still throws RestError(401) as usual.
let onUnauthorized: (() => void) | null = null
export function setOnUnauthorized(handler: (() => void) | null): void {
  onUnauthorized = handler
}

export class RestClient {
  constructor(private readonly baseUrl: string) {}

  get<T>(path: string): Promise<T> {
    return this.request<T>('GET', path)
  }

  post<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('POST', path, body)
  }

  put<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('PUT', path, body)
  }

  delete<T>(path: string): Promise<T> {
    return this.request<T>('DELETE', path)
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
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

    if (res.status === 401) onUnauthorized?.() // CYP-470: session expired/revoked → re-auth redirect (AuthGate)
    if (!res.ok) throw new RestError(res.status, method, path, await res.text().catch(() => ''))
    if (res.status === 204) return undefined as T
    return (await res.json()) as T
  }
}
