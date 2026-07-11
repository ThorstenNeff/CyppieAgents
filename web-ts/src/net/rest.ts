// CYP-400 (W2) — the REST client base for /api/* repositories. Auth per Spec 14 §6: the operator-token global
// (Bearer) when present, OR the first-party session cookie (credentials:"include") — the server accepts either.
// Public/MEMBER serve has no operator global -> Bearer omitted -> the cookie carries the logged-in end user.
import { operatorToken } from '../platform/operatorToken'

export class RestError extends Error {
  constructor(
    readonly status: number,
    readonly method: string,
    readonly path: string,
    body: string,
  ) {
    super(`${method} ${path} -> ${status}${body ? `: ${body}` : ''}`)
    this.name = 'RestError'
  }
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

    if (!res.ok) throw new RestError(res.status, method, path, await res.text().catch(() => ''))
    if (res.status === 204) return undefined as T
    return (await res.json()) as T
  }
}
