// CYP-705 — teeth for unread-of-record (UIUX2 spec §9 at 82e3680b, the three-state revision).
// ★ = the honesty boundary this ticket exists for.
//
// NOTE — these teeth were REWRITTEN after UIUX2's UX-QA. The first version pinned the two-state design (unknown
// and confirmed-read both silent) and therefore passed happily on the defect: it asserted the two were
// indistinguishable in pixels, which is exactly the absence-of-signal trap. A test that locks in the bug is worse
// than no test, so they are flipped rather than extended.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { channelUnread, firstUnreadIndex, isReadStateKnown, READ_STATE_UNAVAILABLE, type ReadState } from './unreadModel'

const available = (channels: Record<string, { unreadCount: number; lastReadSeq: number | null }>): ReadState => ({
  kind: 'available',
  channels,
})

const msgs = (...seqs: number[]) => seqs.map((seq) => ({ seq }))

describe('CYP-705 §9 — three distinct states, none collapsing into another', () => {
  it('① the three states are distinct: count · confirmed-read · unknown', () => {
    const state = available({ x: { unreadCount: 3, lastReadSeq: 10 }, y: { unreadCount: 0, lastReadSeq: 42 } })
    expect(channelUnread(state, 'x')).toEqual({ kind: 'unread', count: 3 })
    expect(channelUnread(state, 'y')).toEqual({ kind: 'read' }) // authoritative: we KNOW it is read
    expect(channelUnread(state, 'never-reported')).toEqual({ kind: 'unknown' })
  })

  it('★ ② UNKNOWN is never the same value as confirmed-read (the absence-of-signal trap)', () => {
    // The defect UIUX2 caught: if these two answer the same, a caller renders both as silence and "we have no
    // idea" reads as "all clear". They must differ in the MODEL so they can differ on screen.
    const unknown = channelUnread(READ_STATE_UNAVAILABLE, 'x')
    const read = channelUnread(available({ x: { unreadCount: 0, lastReadSeq: 1 } }), 'x')
    expect(unknown.kind).toBe('unknown')
    expect(read.kind).toBe('read')
    expect(unknown).not.toEqual(read)
  })

  it('★ ② a channel MISSING from an available response is unknown, not an implied zero (§2 encoding)', () => {
    // Backend2's pinned convention: present-with-0 means read; absent means we were never told.
    const state = available({ other: { unreadCount: 2, lastReadSeq: 5 } })
    expect(channelUnread(state, 'missing')).toEqual({ kind: 'unknown' })
    expect(channelUnread(state, 'other')).toEqual({ kind: 'unread', count: 2 })
  })

  it('the surface-level helper reports whether the read state answered at all', () => {
    expect(isReadStateKnown(READ_STATE_UNAVAILABLE)).toBe(false)
    expect(isReadStateKnown(available({}))).toBe(true) // answered, even if it listed no channels
  })

  it('③ the divider sits at the first message past the cursor; no cursor → no divider', () => {
    const state = available({ x: { unreadCount: 2, lastReadSeq: 20 } })
    expect(firstUnreadIndex(msgs(10, 20, 30, 40), state, 'x')).toBe(2)
    expect(firstUnreadIndex(msgs(10, 20), state, 'x')).toBeNull() // nothing past the cursor
    expect(firstUnreadIndex(msgs(10, 30), available({ x: { unreadCount: 1, lastReadSeq: null } }), 'x')).toBeNull()
    expect(firstUnreadIndex(msgs(10, 30), READ_STATE_UNAVAILABLE, 'x')).toBeNull()
  })

  it('③ ordering is by seq, not by position — an out-of-order list still cuts at the cursor', () => {
    const state = available({ x: { unreadCount: 1, lastReadSeq: 15 } })
    expect(firstUnreadIndex(msgs(10, 12, 16, 14), state, 'x')).toBe(2)
  })

  it('★ ④ non-optimistic: the state is a pure function of the SERVER answer', () => {
    // clearing must follow the server's echoed cursor. Calling repeatedly — as a scroll handler would — cannot
    // move it; only a new read state does.
    const before = available({ x: { unreadCount: 4, lastReadSeq: 7 } })
    for (let scrolls = 0; scrolls < 5; scrolls++) expect(channelUnread(before, 'x')).toEqual({ kind: 'unread', count: 4 })
    expect(channelUnread(available({ x: { unreadCount: 0, lastReadSeq: 11 } }), 'x')).toEqual({ kind: 'read' })
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
    const state = available({ a: { unreadCount: 5, lastReadSeq: 1 }, b: { unreadCount: 0, lastReadSeq: 1 } })
    expect(channelUnread(state, 'a')).toEqual({ kind: 'unread', count: 5 })
    expect(channelUnread(state, 'b')).toEqual({ kind: 'read' })
  })

  it('a nonsensical negative count is treated as read, not as a badge — but the channel was still reported', () => {
    expect(channelUnread(available({ x: { unreadCount: -1, lastReadSeq: 3 } }), 'x')).toEqual({ kind: 'read' })
  })
})
