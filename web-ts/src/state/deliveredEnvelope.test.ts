// CYP-744 — SAME-ENVELOPE consistency tooth. The migration is only safe if ALL THREE transports carry one shape:
// REST history (GET messages), the send echo (POST messages), and the /ws/comm push. If one still spoke the bare
// pre-CYP-744 Message, a consumer would read `.message` off something that isn't an envelope and red at a third,
// non-obvious place — exactly the straggler the gate sweeps for. This pins that they agree, and that the OLD bare
// form is now REJECTED (a lagging server can't silently feed the old shape through).
import { describe, it, expect, vi, afterEach } from 'vitest'
import { RestHubRepo } from './restRepo'
import { ResponseShapeError } from '../net/rest'
import { CommWsServerEventSchema, DeliveredMessageSchema } from '../types/generated/contractSchemas'

const STORED = { id: 'm1', channelId: 'c1', from: 'po', body: 'bitte @frontend', ts: 0 }
const ENVELOPE = { message: STORED, mentions: [{ start: 6, end: 15, id: 'frontend' }] }

const okJson = (body: unknown) =>
  vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body, text: async () => '' } as never)

afterEach(() => vi.unstubAllGlobals())

describe('CYP-744 — REST history, send echo, and WS push all carry the DeliveredMessage envelope', () => {
  it('GET /messages resolves an array of envelopes (spans intact)', async () => {
    vi.stubGlobal('fetch', okJson([ENVELOPE]))
    const out = await new RestHubRepo('local', 'http://x').getMessages('c1')
    expect(out).toHaveLength(1)
    expect(out[0].message.id).toBe('m1')
    expect(out[0].mentions).toEqual([{ start: 6, end: 15, id: 'frontend' }])
  })

  it('★ GET /messages REJECTS a bare pre-CYP-744 message (no envelope) — no silent straggler', async () => {
    vi.stubGlobal('fetch', okJson([STORED]))
    await expect(new RestHubRepo('local', 'http://x').getMessages('c1')).rejects.toBeInstanceOf(ResponseShapeError)
  })

  it('POST /messages resolves the envelope echo', async () => {
    vi.stubGlobal('fetch', okJson(ENVELOPE))
    const out = await new RestHubRepo('local', 'http://x').postMessage('c1', 'bitte @frontend')
    expect(out.message.id).toBe('m1')
  })

  it('★ POST /messages REJECTS a bare message echo — the send path is on the envelope too', async () => {
    vi.stubGlobal('fetch', okJson(STORED))
    await expect(new RestHubRepo('local', 'http://x').postMessage('c1', 'x')).rejects.toBeInstanceOf(ResponseShapeError)
  })

  it('★ the /ws/comm message frame carries `delivered`, and the OLD `{message}` frame is REJECTED', () => {
    expect(CommWsServerEventSchema.safeParse({ type: 'message', delivered: ENVELOPE }).success).toBe(true)
    // the pre-CYP-744 frame shape — must no longer validate, so a lagging server fails closed at the boundary.
    expect(CommWsServerEventSchema.safeParse({ type: 'message', message: STORED }).success).toBe(false)
  })

  it('★ one shape, three doors: the SAME envelope object validates for REST and WS alike', () => {
    // The consistency itself: if the transports diverged, this object could not satisfy both schemas.
    expect(DeliveredMessageSchema.safeParse(ENVELOPE).success).toBe(true)
    expect(CommWsServerEventSchema.safeParse({ type: 'message', delivered: ENVELOPE }).success).toBe(true)
  })
})
