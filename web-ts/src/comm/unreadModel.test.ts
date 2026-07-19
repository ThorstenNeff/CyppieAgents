// CYP-705 — teeth for unread-of-record (UIUX2 spec §9 at 82e3680b, the three-state revision).
// ★ = the honesty boundary this ticket exists for.
//
// NOTE — these teeth were REWRITTEN after UIUX2's UX-QA. The first version pinned the two-state design (unknown
// and confirmed-read both silent) and therefore passed happily on the defect: it asserted the two were
// indistinguishable in pixels, which is exactly the absence-of-signal trap. A test that locks in the bug is worse
// than no test, so they are flipped rather than extended.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import {
  channelUnread,
  firstUnreadIndex,
  hasAuthoritativeSeq,
  isReadStateKnown,
  markReadUpTo,
  unreadViewFrom,
  READ_STATE_UNAVAILABLE,
  type UnreadView,
} from './unreadModel'

/** Build the view the way the wire does — channelId lives INSIDE each entry (contract shape). */
const available = (channels: Record<string, { unreadCount: number; lastReadSeq: number }>): UnreadView => ({
  kind: 'available',
  channels: Object.fromEntries(Object.entries(channels).map(([id, e]) => [id, { channelId: id, ...e }])),
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
    // A null cursor is FORBIDDEN by the contract (lastReadSeq is required) — so this cast deliberately simulates
    // untrusted wire data violating it. Without the runtime guard `seq > null` becomes `seq > 0` and EVERY message
    // reads as unread, dropping the divider at the top. The type being honest does not make the edge unchecked.
    const nullCursor = available({ x: { unreadCount: 1, lastReadSeq: null as unknown as number } })
    expect(firstUnreadIndex(msgs(10, 30), nullCursor, 'x')).toBeNull()
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

// ── CYP-705 #4 (cursor path) — the wire→view fold, the seq sentinel, and mark-read ───────────────────────────
describe('CYP-705 #4 — wire fold, seq authority, mark-read', () => {
  const entry = (channelId: string, lastReadSeq: number, unreadCount: number) => ({ channelId, lastReadSeq, unreadCount })

  it('★ the fold is the ONE place the presence rule lives: listed ⇒ known, absent ⇒ unknown', () => {
    const view = unreadViewFrom([entry('a', 5, 2), entry('b', 9, 0)])
    expect(channelUnread(view, 'a')).toEqual({ kind: 'unread', count: 2 })
    expect(channelUnread(view, 'b')).toEqual({ kind: 'read' }) // present with 0 = authoritative all-clear
    expect(channelUnread(view, 'c')).toEqual({ kind: 'unknown' }) // never listed = never told
  })

  it('★ an EMPTY list is "server answered, no cursors yet" — every channel unknown, none fabricated as read', () => {
    const view = unreadViewFrom([])
    expect(isReadStateKnown(view)).toBe(true) // the surface DID answer
    expect(channelUnread(view, 'a')).toEqual({ kind: 'unknown' }) // …but said nothing about this channel
  })

  it('★ malformed wire entries are dropped → the channel stays UNKNOWN, never a coerced 0', () => {
    // untrusted input: the contract types these as required, but a violating payload must not become "read".
    const bad = [
      { channelId: 'a', lastReadSeq: null, unreadCount: 3 },
      { channelId: '', lastReadSeq: 1, unreadCount: 1 },
      { channelId: 'c', lastReadSeq: 1, unreadCount: 'many' },
    ] as unknown as Parameters<typeof unreadViewFrom>[0]
    const view = unreadViewFrom(bad)
    expect(channelUnread(view, 'a')).toEqual({ kind: 'unknown' })
    expect(channelUnread(view, 'c')).toEqual({ kind: 'unknown' })
  })

  it('★ seq 0 and undefined both mean "no authoritative order" — 0 is the legacy sentinel, not position zero', () => {
    expect(hasAuthoritativeSeq({ seq: 7 })).toBe(true)
    expect(hasAuthoritativeSeq({ seq: 0 })).toBe(false)
    expect(hasAuthoritativeSeq({})).toBe(false)
  })

  it('★ a legacy row cannot BE the boundary, but does not suppress the divider either', () => {
    // fail-closed = omit the guessed claim, not switch the feature off. One legacy row must not silence the
    // divider for a whole channel — that silence would itself read as "nothing new".
    const view = unreadViewFrom([entry('x', 10, 2)])
    expect(firstUnreadIndex([{ seq: 0 }, { seq: 5 }, { seq: 12 }, { seq: 20 }], view, 'x')).toBe(2)
    expect(firstUnreadIndex([{ seq: 0 }, { seq: undefined }], view, 'x')).toBeNull() // no authoritative boundary
  })

  it('★ markReadUpTo sends only authoritative seqs — and nothing at all when there are none', () => {
    expect(markReadUpTo([{ seq: 3 }, { seq: 9 }, { seq: 4 }])).toBe(9)
    expect(markReadUpTo([{ seq: 0 }, { seq: undefined }])).toBeNull() // never claim to have read "up to 0"
    expect(markReadUpTo([])).toBeNull()
  })
})
