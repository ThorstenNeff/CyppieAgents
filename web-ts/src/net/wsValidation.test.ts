// CYP-420 — the untrusted-frame boundary, proven on the REAL channel factories and the REAL generated schemas.
// Deliberately NOT tested through a hand-written stub schema: the point of the ticket is that every production
// ingress validates, so these drive commSocket/eventsSocket/terminalSocket/the four feeds/AgentSocket exactly as
// App wires them. A stub would prove the helper works while the channels stayed unvalidated (the "seam exists but
// nothing uses it" state this ticket exists to fix).
import { describe, it, expect, afterEach } from 'vitest'
import { commSocket, eventsSocket, terminalSocket, lifecycleFeed, tokenUsageFeed, busyStateFeed, terminalStateFeed } from './channels'
import { AgentSocket } from './agentSocket'
import { setOnFrameRejected, type FrameRejection } from './wsValidation'
import { Backoff } from './backoff'
import { FakeSocketHub } from './testing/fakeSocket'

const zeroBackoff = () => new Backoff({ initialMs: 0, maxMs: 0, factor: 1 })

/** Every server->client ingress, with a MINIMAL VALID frame (shapes derived from the :core contract) and an
 *  invalid sibling. `invalid` is well-formed JSON and plausible — it is the shape a stale/hostile peer sends. */
const INGRESSES = [
  {
    name: 'CommWsServerEvent (/ws/comm)',
    open: (o: never) => commSocket(o),
    valid: { type: 'acl', entry: { channelId: 'c', agentId: 'a', canRead: true, canWrite: false } },
    invalid: { type: 'acl', entry: { channelId: 'c', agentId: 'a', canRead: 'yes', canWrite: false } }, // canRead: string
  },
  {
    name: 'EventsWsServerEvent (/ws/events)',
    open: (o: never) => eventsSocket(o),
    valid: { type: 'caughtup' },
    invalid: { type: 'not-a-member' }, // unknown discriminant
  },
  {
    name: 'TerminalServerFrame (/ws/terminal)',
    open: (o: never) => terminalSocket({ ...(o as object), agentId: 'a' } as never),
    valid: { type: 'exit', code: 0 },
    invalid: { type: 'exit', code: 'zero' }, // code: string
  },
  {
    name: 'AgentRunStateEvent (/ws/lifecycle)',
    open: (o: never) => lifecycleFeed(o),
    valid: { agentId: 'a', runState: 'RUNNING' },
    invalid: { agentId: 'a', runState: 'DANCING' }, // outside the enum
  },
  {
    name: 'AgentTokenUsageEvent (/ws/token-usage)',
    open: (o: never) => tokenUsageFeed(o),
    valid: { agentId: 'a', contextTokens: 12 },
    invalid: { contextTokens: 12 }, // agentId missing
  },
  {
    name: 'AgentBusyStateEvent (/ws/busy-state)',
    open: (o: never) => busyStateFeed(o),
    valid: { agentId: 'a', busy: true },
    invalid: { agentId: 'a', busy: 'true' }, // busy: string
  },
  {
    name: 'AgentTerminalControlEvent (/ws/terminal-state)',
    open: (o: never) => terminalStateFeed(o),
    valid: { agentId: 'a', state: 'MEDIATED' },
    invalid: { agentId: 'a', state: 'ELSEWHERE' }, // outside the enum
  },
] as const

const openIngress = (ix: (typeof INGRESSES)[number], hub: FakeSocketHub, got: unknown[]) =>
  ix.open({
    baseUrl: 'ws://host',
    token: 't',
    onEvent: (e: unknown) => got.push(e),
    factory: hub.factory,
    schedule: hub.runNow,
    backoff: zeroBackoff(),
  } as never)

afterEach(() => setOnFrameRejected(null))

describe('CYP-420 — WS boundary runtime validation (every ingress)', () => {
  it.each(INGRESSES.map((i) => [i.name, i] as const))('%s accepts a well-formed frame', (_n, ix) => {
    const hub = new FakeSocketHub()
    const got: unknown[] = []
    const feed = openIngress(ix, hub, got)
    feed.start()
    hub.last().emitOpen()
    hub.last().emitMessage(JSON.stringify(ix.valid))
    expect(got).toEqual([ix.valid]) // non-vacuum: validation must not reject what the contract allows
  })

  it.each(INGRESSES.map((i) => [i.name, i] as const))('%s DROPS a malformed frame (fail-closed, never delivered)', (_n, ix) => {
    const hub = new FakeSocketHub()
    const got: unknown[] = []
    const rejected: FrameRejection[] = []
    setOnFrameRejected((r) => rejected.push(r))
    const feed = openIngress(ix, hub, got)
    feed.start()
    hub.last().emitOpen()
    hub.last().emitMessage(JSON.stringify(ix.invalid))
    expect(got).toEqual([]) // never delivered, never rendered, never in the store
    expect(rejected).toHaveLength(1) // dropped HONESTLY, not silently swallowed
  })

  it.each(INGRESSES.map((i) => [i.name, i] as const))('%s survives a bad frame and keeps delivering (per-frame, not per-connection)', (_n, ix) => {
    const hub = new FakeSocketHub()
    const got: unknown[] = []
    setOnFrameRejected(() => undefined)
    const feed = openIngress(ix, hub, got)
    feed.start()
    hub.last().emitOpen()
    hub.last().emitMessage(JSON.stringify(ix.invalid))
    hub.last().emitMessage(JSON.stringify(ix.valid))
    expect(got).toEqual([ix.valid]) // one malformed frame must not become a denial of service on the channel
  })

  it('the rejection report leaks NO field values — only paths + codes (server-side masking stays intact)', () => {
    const hub = new FakeSocketHub()
    const got: unknown[] = []
    const rejected: FrameRejection[] = []
    setOnFrameRejected((r) => rejected.push(r))
    const feed = openIngress(INGRESSES[0], hub, got)
    feed.start()
    hub.last().emitOpen()
    // a frame whose INVALID field carries something secret-shaped
    hub.last().emitMessage(JSON.stringify({ type: 'acl', entry: { channelId: 'c', agentId: 'a', canRead: 'sk-live-SUPERSECRET', canWrite: false } }))
    expect(got).toEqual([])
    const dump = JSON.stringify(rejected)
    expect(dump).not.toContain('sk-live-SUPERSECRET') // the value must never reach a log
    expect(dump).toContain('entry.canRead') // the PATH is reported, so it stays debuggable
  })

  it('AgentSocket validates BEFORE the seq cursor moves — a bogus seq cannot suppress later real events', () => {
    const hub = new FakeSocketHub()
    const got: { seq: number }[] = []
    setOnFrameRejected(() => undefined)
    const sock = new AgentSocket({
      baseUrl: 'ws://host',
      agentId: 'a',
      onEvent: (e) => got.push(e),
      factory: hub.factory,
      schedule: hub.runNow,
      backoff: zeroBackoff(),
    })
    sock.start()
    hub.last().emitOpen()
    // invalid frame carrying a huge seq: if the cursor moved first, everything below 9_999_999 would be suppressed
    hub.last().emitMessage(JSON.stringify({ seq: 9_999_999, agentId: 'a', projectId: 'p', tsMs: 1, event: { type: 'nope' } }))
    hub.last().emitMessage(JSON.stringify({ seq: 1, agentId: 'a', projectId: 'p', tsMs: 2, event: { type: 'system' } }))
    expect(got.map((e) => e.seq)).toEqual([1]) // the real event still arrives
  })

  it('AgentSocket accepts a well-formed StoredAgentEvent (non-vacuum)', () => {
    const hub = new FakeSocketHub()
    const got: unknown[] = []
    const sock = new AgentSocket({
      baseUrl: 'ws://host',
      agentId: 'a',
      onEvent: (e) => got.push(e),
      factory: hub.factory,
      schedule: hub.runNow,
      backoff: zeroBackoff(),
    })
    sock.start()
    hub.last().emitOpen()
    const frame = { seq: 5, agentId: 'a', projectId: 'p', tsMs: 3, event: { type: 'result' } }
    hub.last().emitMessage(JSON.stringify(frame))
    expect(got).toEqual([frame])
  })
})
