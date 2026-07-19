// CYP-744 — teeth for applyMentionSpans: the render-side slicer that turns server MentionSpans into text/mention
// segments. ★ = security/honesty-bearing; each mutation-verified RED against the obvious wrong implementation.
import { describe, it, expect } from 'vitest'
import { applyMentionSpans, type MentionSegment } from './mentionSpans'
import type { MentionSpan } from '../types/generated/contract'

/** Render segments compactly: mentions as `[id:text]`, text verbatim — so a wrong split shows in the diff. */
const seg = (body: string, spans: MentionSpan[]) =>
  applyMentionSpans(body, spans).map((s: MentionSegment) => (s.kind === 'mention' ? `[${s.id}:${s.text}]` : s.text))

describe('applyMentionSpans — slice the body at server offsets', () => {
  it('no spans ⇒ the whole body is one text segment', () => {
    expect(seg('hallo welt', [])).toEqual(['hallo welt'])
    expect(applyMentionSpans('', [])).toEqual([]) // empty body ⇒ no segments at all
  })

  it('one span splits into text / mention / text; the mention text is the slice, id preserved', () => {
    expect(seg('bitte @frontend schauen', [{ start: 6, end: 15, id: 'frontend' }])).toEqual([
      'bitte ',
      '[frontend:@frontend]',
      ' schauen',
    ])
  })

  it('spans at the very start and very end produce no empty leading/trailing text', () => {
    expect(seg('@po', [{ start: 0, end: 3, id: 'po' }])).toEqual(['[po:@po]'])
  })

  it('two spans interleave correctly with the connecting text', () => {
    expect(seg('@po und @frontend', [{ start: 0, end: 3, id: 'po' }, { start: 8, end: 17, id: 'frontend' }])).toEqual([
      '[po:@po]',
      ' und ',
      '[frontend:@frontend]',
    ])
  })

  it('adjacent spans (no gap) produce no empty text segment between them', () => {
    // '@po@x' with spans [0,3) and [3,5) — the segments must be exactly the two chips, no '' between.
    expect(seg('@po@x', [{ start: 0, end: 3, id: 'po' }, { start: 3, end: 5, id: 'x' }])).toEqual(['[po:@po]', '[x:@x]'])
  })

  it('★ LOSSLESS — concatenating every segment text reproduces the body exactly (no drop, no inject)', () => {
    // Load-bearing for the XSS/text-node invariant: the panel renders these strings as text nodes, so the slicer
    // must neither lose nor invent a character. Covered across gaps, edges, and empty input.
    const cases: [string, MentionSpan[]][] = [
      ['', []],
      ['plain text only', []],
      ['@frontend', [{ start: 0, end: 9, id: 'frontend' }]],
      ['a @po b @frontend c', [{ start: 2, end: 5, id: 'po' }, { start: 8, end: 17, id: 'frontend' }]],
      ['trailing @po', [{ start: 9, end: 12, id: 'po' }]],
    ]
    for (const [body, spans] of cases) {
      expect(applyMentionSpans(body, spans).map((s) => s.text).join('')).toBe(body)
    }
  })

  it('unsorted spans are handled in body order (input order does not matter)', () => {
    expect(seg('@po und @frontend', [{ start: 8, end: 17, id: 'frontend' }, { start: 0, end: 3, id: 'po' }])).toEqual([
      '[po:@po]',
      ' und ',
      '[frontend:@frontend]',
    ])
  })

  it('★ fail-closed: a span whose end is past the body is DROPPED, the body still renders whole', () => {
    expect(seg('hallo', [{ start: 2, end: 999, id: 'x' }])).toEqual(['hallo'])
  })

  it('★ fail-closed: an inverted/empty span (end ≤ start) is DROPPED', () => {
    expect(seg('hallo', [{ start: 3, end: 3, id: 'x' }])).toEqual(['hallo'])
    expect(seg('hallo', [{ start: 4, end: 2, id: 'x' }])).toEqual(['hallo'])
  })

  it('★ fail-closed: a negative start is DROPPED (never slices out of range)', () => {
    expect(seg('hallo', [{ start: -1, end: 3, id: 'x' }])).toEqual(['hallo'])
  })

  it('★ fail-closed: OVERLAPPING spans — the first wins, the overlapping one is dropped, still lossless', () => {
    // '@frontend' [0,9) and a bogus [3,6) inside it: the second must not re-slice the middle of the first chip.
    const out = applyMentionSpans('@frontend', [{ start: 0, end: 9, id: 'frontend' }, { start: 3, end: 6, id: 'ont' }])
    expect(out).toEqual([{ kind: 'mention', text: '@frontend', id: 'frontend' }])
    expect(out.map((s) => s.text).join('')).toBe('@frontend')
  })

  it('★ UTF-16 UNITS, end-EXCLUSIVE: an astral emoji before the span shifts offsets by TWO, and slicing agrees', () => {
    // The exact convention the parity oracle pins. '🎉' is two UTF-16 units, so '@dev5' begins at index 9 (not 8).
    // A slicer using code points would mis-slice here; String.slice is UTF-16-indexed, so it matches by construction.
    const body = '🎉 party @dev5 now'
    const start = body.indexOf('@dev5') // 9 in UTF-16 units
    expect(start).toBe(9)
    expect(seg(body, [{ start, end: start + 5, id: 'dev5' }])).toEqual(['🎉 party ', '[dev5:@dev5]', ' now'])
  })
})
