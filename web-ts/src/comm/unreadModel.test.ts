// CYP-705 — teeth for unread-of-record (UIUX2 spec §9, 25cc93ea). ★ = the honesty boundary this ticket exists for.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import {
  unreadBadge,
  firstUnreadIndex,
  isReadStateKnown,
  READ_STATE_UNAVAILABLE,
  type ReadState,
} from './unreadModel'

const available = (channels: Record<string, { unread: number; lastReadSeq: number | null }>): ReadState => ({
  kind: 'available',
  channels,
})

const msgs = (...seqs: number[]) => seqs.map((seq) => ({ seq }))

describe('CYP-705 §9 — unread-of-record is server-authoritative or absent', () => {
  it('① a server unread count produces a badge; a server-confirmed zero produces none', () => {
    const state = available({ x: { unread: 3, lastReadSeq: 10 }, y: { unread: 0, lastReadSeq: 42 } })
    expect(unreadBadge(state, 'x')).toEqual({ count: 3 })
    expect(unreadBadge(state, 'y')).toBeNull() // confirmed read → legitimately silent
  })

  it('★ ② an UNAVAILABLE read state produces no badge — and is never confusable with "all read"', () => {
    // the certainty boundary: both render nothing, but the model must still tell them apart, or a caller will
    // eventually print "0 unread" over an answer the server never gave.
    expect(unreadBadge(READ_STATE_UNAVAILABLE, 'x')).toBeNull()
    expect(isReadStateKnown(READ_STATE_UNAVAILABLE)).toBe(false)
    expect(isReadStateKnown(available({ x: { unread: 0, lastReadSeq: 1 } }))).toBe(true)
  })

  it('★ ② a channel the server did not report is unknown, not zero', () => {
    // an available read state that simply omits a channel must not be read as "nothing unread there".
    const channels = { other: { unread: 2, lastReadSeq: 5 } }
    expect(unreadBadge(available(channels), 'missing')).toBeNull()
    expect(Object.keys(channels)).not.toContain('missing') // no invented entry
  })

  it('③ the divider sits at the first message past the cursor; no cursor → no divider', () => {
    const state = available({ x: { unread: 2, lastReadSeq: 20 } })
    expect(firstUnreadIndex(msgs(10, 20, 30, 40), state, 'x')).toBe(2)
    expect(firstUnreadIndex(msgs(10, 20), state, 'x')).toBeNull() // nothing past the cursor
    expect(firstUnreadIndex(msgs(10, 30), available({ x: { unread: 1, lastReadSeq: null } }), 'x')).toBeNull()
    expect(firstUnreadIndex(msgs(10, 30), READ_STATE_UNAVAILABLE, 'x')).toBeNull()
  })

  it('③ ordering is by seq, not by position — an out-of-order list still cuts at the cursor', () => {
    const state = available({ x: { unread: 1, lastReadSeq: 15 } })
    expect(firstUnreadIndex(msgs(10, 12, 16, 14), state, 'x')).toBe(2)
  })

  it('★ ④ non-optimistic: the badge is a pure function of the SERVER state', () => {
    // clearing must follow the server's echoed cursor. Calling repeatedly — as a scroll handler would — cannot
    // move the badge; only a new read state does. This is the structural half of "no local optimism".
    const before = available({ x: { unread: 4, lastReadSeq: 7 } })
    for (let scrolls = 0; scrolls < 5; scrolls++) expect(unreadBadge(before, 'x')).toEqual({ count: 4 })
    const afterServerEcho = available({ x: { unread: 0, lastReadSeq: 11 } })
    expect(unreadBadge(afterServerEcho, 'x')).toBeNull()
  })

  it('★ no local persistence is reachable from this model — a local marker would fake durable certainty', () => {
    // §0 forbids localStorage/sessionStorage as an unread-of-record source. Scanned structurally so the ban
    // survives a future "just cache it" edit, which is exactly how this defect gets reintroduced.
    const src = readFileSync(new URL('./unreadModel.ts', import.meta.url), 'utf8')
    const code = src.replace(/^\s*(\/\/.*|\*.*|\/\*.*)$/gm, '') // the header discusses these words deliberately
    const forbidden = ['localStorage', 'sessionStorage', 'indexedDB', 'document.cookie']
    expect(forbidden.filter((w) => code.includes(w))).toEqual([])
  })

  it('⑦ unread is per channel — one channel’s count never leaks into another', () => {
    const state = available({ a: { unread: 5, lastReadSeq: 1 }, b: { unread: 0, lastReadSeq: 1 } })
    expect(unreadBadge(state, 'a')).toEqual({ count: 5 })
    expect(unreadBadge(state, 'b')).toBeNull()
  })

  it('a negative or nonsensical server count is treated as nothing to show, not as a badge', () => {
    expect(unreadBadge(available({ x: { unread: -1, lastReadSeq: 3 } }), 'x')).toBeNull()
  })
})
