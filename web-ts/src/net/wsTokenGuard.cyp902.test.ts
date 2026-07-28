// CYP-902 — the WS `?token=` fold guard (CYP-230 credentials parity): a BLANK token is OMITTED so the client never
// sends `?token=` with an empty value; the same-origin cookie authenticates. Polish/defense-in-depth (the server's
// ifBlank→cookie fallback means an empty token doesn't break today), so the tooth is what makes it non-vacuous.
import { describe, it, expect } from 'vitest'
import { wsAuthParams } from './wsTicket'
import { statusFeed, commSocket } from './channels'
import { Backoff } from './backoff'
import { FakeSocketHub } from './testing/fakeSocket'

describe('CYP-902 — wsAuthParams omits a blank token', () => {
  it('★ blank token → NO token= param (cookie authenticates)', () => {
    // MUT: revert to `else p.set('token', token)` → `token=` appears with an empty value → these red.
    expect(wsAuthParams(undefined, '')).toBe('')
    expect(wsAuthParams({ agentId: 'x' }, '')).toBe('agentId=x')
    expect(wsAuthParams(undefined, '   ')).toBe('') // whitespace-only is also blank
  })
  it('★ non-blank token → token= folded, byte-unchanged (operator serve)', () => {
    expect(wsAuthParams(undefined, 't')).toBe('token=t')
    expect(wsAuthParams({ agentId: 'x' }, 't')).toBe('agentId=x&token=t')
  })
  it('★ a ticket wins and never sends token (flag-on path unaffected)', () => {
    expect(wsAuthParams(undefined, 't', 'TKT')).toBe('ticket=TKT')
    expect(wsAuthParams(undefined, '', 'TKT')).toBe('ticket=TKT')
  })
})

const zeroBackoff = () => new Backoff({ initialMs: 0, maxMs: 0, factor: 1 })

describe('CYP-902 — read-feed handshake URL is token-free on a member (empty-token) build', () => {
  it('★ empty token → NO token= in the handshake URL (no bare trailing ? either)', () => {
    // MUT: revert the wsAuthParams guard → the member handshake carries an empty `?token=` → this reds.
    const hub = new FakeSocketHub()
    const base = { baseUrl: 'ws://host', token: '', factory: hub.factory, schedule: hub.runNow, backoff: zeroBackoff(), onEvent: () => {} }
    statusFeed(base).start()
    expect(hub.last().url).toBe('ws://host/ws/status') // clean, token-free, no trailing ?
    commSocket(base).start()
    expect(hub.last().url).toBe('ws://host/ws/comm')
    for (const s of hub.sockets) expect(s.url).not.toContain('token=')
  })

  it('★ non-empty token → token= present (operator serve, byte-unchanged)', () => {
    const hub = new FakeSocketHub()
    const base = { baseUrl: 'ws://host', token: 't', factory: hub.factory, schedule: hub.runNow, backoff: zeroBackoff(), onEvent: () => {} }
    statusFeed(base).start()
    expect(hub.last().url).toBe('ws://host/ws/status?token=t')
  })
})
