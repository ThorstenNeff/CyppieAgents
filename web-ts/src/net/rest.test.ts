// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { RestClient, setOnUnauthorized, RestError } from './rest'

afterEach(() => {
  setOnUnauthorized('local', null)
  vi.unstubAllGlobals()
})

describe('RestClient 401 hook (CYP-470 — session expired → re-auth redirect)', () => {
  it('fires the global onUnauthorized handler on a 401 (and still throws RestError)', async () => {
    const onUnauth = vi.fn()
    setOnUnauthorized('local', onUnauth)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })))
    await expect(new RestClient('local', 'http://x').get('/api/agents')).rejects.toBeInstanceOf(RestError)
    expect(onUnauth).toHaveBeenCalledTimes(1)
  })

  it('does NOT fire on a 200', async () => {
    const onUnauth = vi.fn()
    setOnUnauthorized('local', onUnauth)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } })))
    await new RestClient('local', 'http://x').get('/api/agents')
    expect(onUnauth).not.toHaveBeenCalled()
  })
})
