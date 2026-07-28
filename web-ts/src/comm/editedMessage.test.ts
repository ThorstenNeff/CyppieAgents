// CYP-906 (Edit E1) — the edit-envelope helpers + the upsert-by-id / newer-wins reducer path. The load-bearing invariant:
// a stale reconnect-replay of the original must NEVER clobber a live edit.
import { describe, it, expect } from 'vitest'
import { editedAt, isNewerEdit } from './editedMessage'
import { applyMessage, emptyHubState } from '../state/hubReducers'
import { DeliveredMessageSchema } from '../types/generated/contractSchemas'
import type { DeliveredMessage } from '../types/generated/contract'

// build against the DESIGNED envelope shape (editedAt lands at contract-regen) — cast the fixture until then.
const dm = (id: string, body: string, edited?: number): DeliveredMessage =>
  ({ message: { id, channelId: 'c', from: 'operator', body, ts: 0 }, ...(edited !== undefined ? { editedAt: edited } : {}) }) as DeliveredMessage

const rows = (s: ReturnType<typeof applyMessage>) => s.messagesByChannel.get('c') ?? []

describe('CYP-906 — edit helpers', () => {
  it('editedAt reads the envelope field, null when absent', () => {
    expect(editedAt(dm('m', 'x'))).toBeNull()
    expect(editedAt(dm('m', 'x', 1234))).toBe(1234)
  })

  it('★ isNewerEdit: an edit supersedes an un-edited message; a stale/identical replay does NOT', () => {
    // MUT: return `inc !== null` (drop the exi comparison) → an identical/older replay would wrongly win → reds.
    expect(isNewerEdit(dm('m', 'v2', 100), dm('m', 'v1'))).toBe(true) // first edit over original
    expect(isNewerEdit(dm('m', 'v3', 200), dm('m', 'v2', 100))).toBe(true) // newer edit over older edit
    expect(isNewerEdit(dm('m', 'v1'), dm('m', 'v2', 100))).toBe(false) // ★ stale original replay does NOT clobber the edit
    expect(isNewerEdit(dm('m', 'v2', 100), dm('m', 'v2', 100))).toBe(false) // identical edit replay = no-op
    expect(isNewerEdit(dm('m', 'v1'), dm('m', 'v1'))).toBe(false) // duplicate original = no-op
  })
})

describe('CYP-906 — ignition: the generated DeliveredMessageSchema RETAINS editedAt (wire-level, not a dm() stub)', () => {
  it('★ safeParse keeps editedAt through validation; a never-edited envelope validates with it absent', () => {
    // The load-bearing RUNTIME ignition: if the generated zod schema STRIPPED editedAt (a zod:gen regression / future
    // openapi drift), the E1/E2 flow would go silently DARK while the build stays green — the E1/E2 teeth use dm()
    // direct-build and bypass validation. This guards it at the schema boundary. MUT: a schema without editedAt →
    // stripped → undefined → red.
    const msg = { id: 'a', channelId: 'c', from: 'operator', body: 'x', ts: 0 }
    const parsed = DeliveredMessageSchema.safeParse({ message: msg, editedAt: 123 })
    expect(parsed.success).toBe(true)
    if (parsed.success) expect(parsed.data.editedAt).toBe(123) // survives, not stripped
    const noEdit = DeliveredMessageSchema.safeParse({ message: { ...msg, id: 'b' } })
    expect(noEdit.success).toBe(true)
    if (noEdit.success) expect(noEdit.data.editedAt ?? null).toBeNull() // absent, never fabricated
  })
})

describe('CYP-906 — applyMessage upsert-by-id + newer-wins', () => {
  it('a new id appends', () => {
    const s = applyMessage(emptyHubState, dm('a', 'hi'))
    expect(rows(s).map((d) => d.message.id)).toEqual(['a'])
  })

  it('★ a newer edit REPLACES in place (body updated, position + count kept)', () => {
    // MUT: revert to unconditional drop-the-duplicate → the edit never lands → this reds.
    let s = applyMessage(emptyHubState, dm('a', 'orig'))
    s = applyMessage(s, dm('b', 'other'))
    s = applyMessage(s, dm('a', 'EDITED', 500)) // edit of a
    const r = rows(s)
    expect(r.map((d) => d.message.id)).toEqual(['a', 'b']) // position preserved, not moved to the end
    expect(r[0].message.body).toBe('EDITED')
    expect(editedAt(r[0])).toBe(500)
  })

  it('★ a STALE reconnect-replay of the original does NOT clobber a live edit', () => {
    // MUT: make the upsert unconditional (always replace) → the stale replay overwrites the edit → this reds.
    let s = applyMessage(emptyHubState, dm('a', 'orig'))
    s = applyMessage(s, dm('a', 'EDITED', 500)) // edit lands
    s = applyMessage(s, dm('a', 'orig')) // stale replay of the original (no editedAt) arrives late
    expect(rows(s)[0].message.body).toBe('EDITED') // ★ the edit survives
    expect(editedAt(rows(s)[0])).toBe(500)
  })

  it('a duplicate original is idempotent (dropped, count unchanged)', () => {
    let s = applyMessage(emptyHubState, dm('a', 'orig'))
    s = applyMessage(s, dm('a', 'orig'))
    expect(rows(s)).toHaveLength(1)
  })
})
