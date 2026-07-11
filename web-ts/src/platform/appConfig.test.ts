import { describe, it, expect, afterEach } from 'vitest'
import { apiBaseUrl, wsBaseUrl } from './appConfig'

const g = globalThis as { CYPPIE_API_BASE?: string; CYPPIE_WS_BASE?: string }
afterEach(() => {
  delete g.CYPPIE_API_BASE
  delete g.CYPPIE_WS_BASE
})

describe('appConfig (CYP-408 coexistence base URLs)', () => {
  it('apiBaseUrl uses the injected global and strips a trailing slash', () => {
    g.CYPPIE_API_BASE = 'https://api.cyppie.example/'
    expect(apiBaseUrl()).toBe('https://api.cyppie.example')
  })

  it('wsBaseUrl uses the explicit global when present', () => {
    g.CYPPIE_WS_BASE = 'wss://api.cyppie.example'
    expect(wsBaseUrl()).toBe('wss://api.cyppie.example')
  })

  it('wsBaseUrl derives from the API base when no WS global (http→ws, https→wss)', () => {
    g.CYPPIE_API_BASE = 'https://api.cyppie.example'
    expect(wsBaseUrl()).toBe('wss://api.cyppie.example')
    g.CYPPIE_API_BASE = 'http://localhost:8787'
    expect(wsBaseUrl()).toBe('ws://localhost:8787')
  })
})
