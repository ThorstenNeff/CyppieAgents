// CYP-834 — the terminal protocol-skew path on /ws/comm. The DETECTION already exists (makeFrameValidator +
// CommWsServerEventSchema); this pins the new TERMINAL HANDLING: a schema-violation on a channel that wired `onReject`
// fires it + stops the socket (no reconnect), while a channel WITHOUT onReject keeps today's global drop-and-continue
// (additive/backward-compatible — the coordinator's Auflage). Driven through the REAL commSocket + generated schema
// against a fake socket (node env). NOTE: `kind:null` (nullable-enum) false-skew is CYP-843 (zod-gen fix) — not tested
// here (the current schema would wrongly flag it until CYP-843 lands before/with this).
import { describe, it, expect, vi, afterEach } from 'vitest'
import { commSocket } from './channels'
import { deliverIfValid, setOnFrameRejected, FrameValidationError } from './wsValidation'
import { FakeSocketHub } from './testing/fakeSocket'
import { Backoff } from './backoff'

afterEach(() => setOnFrameRejected(null))

// ── the 3 confirmed skew triggers + the two non-triggers, as raw /ws/comm frames ──────────────────────────────────
const VALID_ACL = JSON.stringify({ type: 'acl', entry: { channelId: 'c', agentId: 'a', canRead: true, canWrite: false } })
const EXTRA_FIELD = JSON.stringify({ type: 'acl', entry: { channelId: 'c', agentId: 'a', canRead: true, canWrite: false }, futureField: 'x' })
const UNKNOWN_TYPE = JSON.stringify({ type: 'somethingNew', payload: 1 })
const MISSING_REQUIRED = JSON.stringify({ type: 'readState', channelId: 'c', lastReadSeq: 0, unreadCount: 0 }) // no hasUnreadMention
const UNKNOWN_ENUM = JSON.stringify({ type: 'channels', channels: [{ id: 'c', name: 'C', kind: 'MYSTERY', members: [] }] })

function wireComm(hub: FakeSocketHub, onReject?: (r: { schema: string; issues: readonly string[] }) => void) {
  const onEvent = vi.fn()
  const conn = commSocket({ baseUrl: 'wss://h', token: 't', onEvent, onReject, factory: hub.factory, schedule: hub.runNow, backoff: new Backoff() })
  conn.start()
  hub.last().emitOpen()
  return { onEvent, conn }
}

describe('CYP-834 — deliverIfValid onReject routing (additive / backward-compatible)', () => {
  const throwing = () => {
    throw new FrameValidationError({ schema: 'X', issues: ['f:invalid_type'] })
  }

  it('★ a schema violation routes to onReject when wired (and NOT to the global sink)', () => {
    // MUT: route to onFrameRejected even when onReject is given → the global spy fires + onReject does not; reds.
    const globalSpy = vi.fn()
    setOnFrameRejected(globalSpy)
    const onReject = vi.fn()
    const deliver = vi.fn()
    deliverIfValid(throwing, '{}', deliver, onReject)
    expect(onReject).toHaveBeenCalledTimes(1)
    expect(globalSpy).not.toHaveBeenCalled()
    expect(deliver).not.toHaveBeenCalled()
  })

  it('★ WITHOUT onReject a schema violation keeps the global drop (backward-compat — existing callers unaffected)', () => {
    // MUT: always require onReject / drop the else-branch → existing no-onReject callers stop reporting; reds.
    const globalSpy = vi.fn()
    setOnFrameRejected(globalSpy)
    const deliver = vi.fn()
    deliverIfValid(throwing, '{}', deliver)
    expect(globalSpy).toHaveBeenCalledTimes(1)
    expect(deliver).not.toHaveBeenCalled()
  })

  it('★ malformed JSON is NOT routed to onReject (transient, stays the global drop) even when onReject is wired', () => {
    // MUT: route SyntaxError to onReject too → a proxy HTML error page would terminal-skew; reds. Malformed≠deploy-skew.
    const globalSpy = vi.fn()
    setOnFrameRejected(globalSpy)
    const onReject = vi.fn()
    deliverIfValid(() => undefined, 'not json', vi.fn(), onReject)
    expect(onReject).not.toHaveBeenCalled()
    expect(globalSpy).toHaveBeenCalledTimes(1)
  })
})

describe('CYP-834 — commSocket end-to-end: schema-skew is TERMINAL (onReject + socket stopped, no reconnect)', () => {
  for (const [name, frame] of [
    ['unknown union type', UNKNOWN_TYPE],
    ['missing required (hasUnreadMention)', MISSING_REQUIRED],
    ['unknown enum (ChannelKind)', UNKNOWN_ENUM],
  ] as const) {
    it(`★ ${name} → onReject('CommWsServerEvent') fires, socket closed, NO reconnect`, () => {
      // MUT: drop the this.rs.close() in bidiFeed's onReject wrapper → socket stays open / reconnects; the closed +
      // no-reconnect assertions red. MUT: don't route to onReject → onReject not called; reds.
      const hub = new FakeSocketHub()
      const skew = vi.fn()
      const onEvent = vi.fn()
      const w = commSocket({ baseUrl: 'wss://h', token: 't', onEvent, onReject: skew, factory: hub.factory, schedule: hub.runNow, backoff: new Backoff() })
      w.start()
      hub.last().emitOpen()
      const n = hub.sockets.length
      hub.last().emitMessage(frame)
      expect(skew).toHaveBeenCalledTimes(1)
      expect(skew.mock.calls[0][0].schema).toBe('CommWsServerEvent')
      expect(hub.last().closed).toBe(true) // socket stopped (terminal)
      expect(hub.sockets.length).toBe(n) // NO reconnect
      expect(onEvent).not.toHaveBeenCalled()
    })
  }

  it('★ a VALID frame delivers normally (onEvent called, no skew, socket alive)', () => {
    const hub = new FakeSocketHub()
    const skew = vi.fn()
    const onEvent = vi.fn()
    const w = commSocket({ baseUrl: 'wss://h', token: 't', onEvent, onReject: skew, factory: hub.factory, schedule: hub.runNow, backoff: new Backoff() })
    w.start()
    hub.last().emitOpen()
    hub.last().emitMessage(VALID_ACL)
    expect(onEvent).toHaveBeenCalledTimes(1)
    expect(skew).not.toHaveBeenCalled()
    expect(hub.last().closed).toBe(false)
  })

  it('★ an EXTRA unknown field is NOT skew (forward-compat: stripped, delivered) — ignoreUnknownKeys parity', () => {
    // MUT: make the schema .strict() → extra field would skew; reds. Pins that a new server field does not terminal-kill.
    const hub = new FakeSocketHub()
    const skew = vi.fn()
    const onEvent = vi.fn()
    const w = commSocket({ baseUrl: 'wss://h', token: 't', onEvent, onReject: skew, factory: hub.factory, schedule: hub.runNow, backoff: new Backoff() })
    w.start()
    hub.last().emitOpen()
    hub.last().emitMessage(EXTRA_FIELD)
    expect(onEvent).toHaveBeenCalledTimes(1)
    expect(skew).not.toHaveBeenCalled()
  })

  it('★ WITHOUT onReject a schema-invalid frame keeps the channel ALIVE (backward-compat: drop-and-continue)', () => {
    // MUT: make skew terminal for ALL comm sockets (ignore the onReject-absent case) → this socket would close; reds.
    const hub = new FakeSocketHub()
    const globalSpy = vi.fn()
    setOnFrameRejected(globalSpy)
    const { onEvent } = wireComm(hub) // no onReject
    hub.last().emitMessage(UNKNOWN_TYPE)
    expect(globalSpy).toHaveBeenCalledTimes(1) // dropped + reported globally
    expect(onEvent).not.toHaveBeenCalled()
    expect(hub.last().closed).toBe(false) // channel stays alive (today's behavior unchanged)
  })
})
