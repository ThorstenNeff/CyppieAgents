import { describe, it, expect } from 'vitest'
import { messageKind, isOrchestrationKind, replyParent, replyDepth, indexById, MAX_REPLY_DEPTH, type StoredMessage } from './orchestrationMessage'
import type { DeliveredMessage, MessageMeta } from '../types/generated/contract'

const msg = (id: string, meta?: MessageMeta | null): StoredMessage => ({ id, channelId: 'c', from: 'po', body: `b-${id}`, ts: 0, ...(meta !== undefined ? { meta } : {}) })
const dm = (id: string, meta?: MessageMeta | null): DeliveredMessage => ({ message: msg(id, meta) })

describe('CYP-868 — messageKind (server-stamped only; absent ⇒ NOTE, never fabricated TASK/STATUS)', () => {
  it('★ explicit kinds pass through verbatim', () => {
    expect(messageKind({ kind: 'TASK' })).toBe('TASK')
    expect(messageKind({ kind: 'STATUS' })).toBe('STATUS')
    expect(messageKind({ kind: 'NOTE' })).toBe('NOTE')
  })

  it('★ absent/null meta OR absent/null kind ⇒ NOTE — NEVER a fabricated TASK/STATUS (the honesty core)', () => {
    // MUT: default to TASK/STATUS on absent (client-fabricated type) → reds.
    expect(messageKind(undefined)).toBe('NOTE')
    expect(messageKind(null)).toBe('NOTE')
    expect(messageKind({})).toBe('NOTE')
    expect(messageKind({ kind: null })).toBe('NOTE')
    expect(messageKind({ inReplyTo: 'x' })).toBe('NOTE') // has meta, but no kind → still NOTE
  })

  it('isOrchestrationKind: TASK/STATUS are badged, NOTE is the quiet default', () => {
    expect(isOrchestrationKind('TASK')).toBe(true)
    expect(isOrchestrationKind('STATUS')).toBe(true)
    expect(isOrchestrationKind('NOTE')).toBe(false)
  })
})

describe('CYP-868 — replyParent (server inReplyTo only; unknown parent ⇒ NOT a fabricated thread)', () => {
  const parent = dm('p')
  const reply = dm('r', { inReplyTo: 'p' })
  const byId = indexById([parent, reply])

  it('★ a message with inReplyTo → its parent, when the parent is present', () => {
    expect(replyParent(reply.message, byId)?.id).toBe('p')
  })

  it('★ no inReplyTo → null (a top-level message is not a reply)', () => {
    expect(replyParent(parent.message, byId)).toBeNull()
    expect(replyParent(msg('x'), byId)).toBeNull()
  })

  it('★ inReplyTo to an ABSENT parent → null, never a fabricated thread to an unseen message', () => {
    // MUT: return a placeholder / treat as a reply anyway → fabricates a thread we cannot show → reds.
    const orphan = msg('o', { inReplyTo: 'ghost' })
    expect(replyParent(orphan, indexById([dm('o', { inReplyTo: 'ghost' })]))).toBeNull()
  })
})

describe('CYP-868 — replyDepth (server chain only; cycle- and runaway-safe)', () => {
  it('★ top-level = 0, reply = 1, reply-to-reply = 2 (indent from the real server chain)', () => {
    const a = dm('a')
    const b = dm('b', { inReplyTo: 'a' })
    const c = dm('c', { inReplyTo: 'b' })
    const byId = indexById([a, b, c])
    expect(replyDepth(a.message, byId)).toBe(0)
    expect(replyDepth(b.message, byId)).toBe(1)
    expect(replyDepth(c.message, byId)).toBe(2)
  })

  it('a reply whose parent is absent counts depth 0 (no fabricated ancestry)', () => {
    const orphan = dm('o', { inReplyTo: 'ghost' })
    expect(replyDepth(orphan.message, indexById([orphan]))).toBe(0)
  })

  it('★ a cyclic inReplyTo chain terminates (does not loop forever), capped at MAX_REPLY_DEPTH', () => {
    // untrusted server data: a↔b cycle. MUT: drop the visited-set/cap → infinite loop / hang.
    const a = dm('a', { inReplyTo: 'b' })
    const b = dm('b', { inReplyTo: 'a' })
    const byId = indexById([a, b])
    expect(replyDepth(a.message, byId)).toBeLessThanOrEqual(MAX_REPLY_DEPTH)
  })
})
