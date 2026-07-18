// CYP-704 Phase 1 — teeth for mention resolution, against UIUX2's spec §8 (frozen 834ecfab). ★ = security-bearing;
// each was mutation-verified RED against the obvious wrong implementation.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { mentionSegments, mentionedIds } from './mentionModel'

const ROSTER = ['frontend', 'po', 'dev5'] as const

/** Render segments compactly: mentions as `[@id]`, text verbatim — so a wrong split is visible in the diff. */
const seg = (body: string, roster: readonly string[] = ROSTER) =>
  mentionSegments(body, roster).map((s) => (s.kind === 'mention' ? `[@${s.id}]` : s.text))

describe('CYP-704 §8 — mention DISPLAY resolves fail-closed against the roster', () => {
  it('① a roster-known token becomes one mention; surrounding text stays intact', () => {
    expect(seg('bitte @frontend schauen')).toEqual(['bitte ', '[@frontend]', ' schauen'])
  })

  it('★ ② fail-closed: an unknown token keeps NO mention styling', () => {
    // the defect this prevents is the false positive — claiming someone is addressed when nobody is called that.
    expect(seg('hi @foo')).toEqual(['hi @foo'])
    expect(mentionSegments('hi @foo', ROSTER).every((s) => s.kind === 'text')).toBe(true)
  })

  it('★ ③ email-safe: a sigil mid-word is not a mention', () => {
    // `mail@frontend.de` alone would NOT discriminate — but `mail@frontend` has a token that IS a roster id, so
    // only the boundary rule keeps it plain. Both kept: the second is the tooth, the first is the real-world shape.
    expect(seg('schreib an mail@frontend')).toEqual(['schreib an mail@frontend'])
    expect(seg('schreib an mail@frontend.de')).toEqual(['schreib an mail@frontend.de'])
  })

  it('⑤ several mentions in one body; the connecting text stays text', () => {
    expect(seg('@po und @frontend')).toEqual(['[@po]', ' und ', '[@frontend]'])
  })

  it('⑥ punctuation: longest-match stops at the id, the rest stays text', () => {
    expect(seg('@frontend!')).toEqual(['[@frontend]', '!'])
    expect(seg('@front')).toEqual(['@front']) // not an id → plain text
  })

  it('★ ⑦ an unloaded / failed roster yields plain text — never a guessed mention (CYP-288 tie-in)', () => {
    expect(seg('@frontend @po', [])).toEqual(['@frontend @po'])
  })

  it('longest-match prefers the longer id when both are on the roster', () => {
    expect(seg('@frontend', ['front', 'frontend'])).toEqual(['[@frontend]'])
  })

  it('resolves case-insensitively but reports the CANONICAL id (text stays verbatim)', () => {
    const m = mentionSegments('moin @FrontEnd', ROSTER).find((s) => s.kind === 'mention')
    expect(m).toMatchObject({ kind: 'mention', id: 'frontend', text: '@FrontEnd' })
  })

  it('★ lossless — concatenating every segment reproduces the body exactly', () => {
    // the model can neither DROP nor INJECT content while re-segmenting; also covers the escaping story, since the
    // UI renders these strings as text nodes.
    for (const body of ['', '@frontend', 'a @po b @frontend c', 'mail@frontend.de @foo @po!', '@@po', 'trails @']) {
      expect(mentionSegments(body, ROSTER).map((s) => s.text).join('')).toBe(body)
    }
  })

  it('a blank roster id never swallows a bare sigil', () => {
    expect(seg('hi @ da', ['   ', ...ROSTER])).toEqual(['hi @ da'])
  })

  it('mentionedIds de-duplicates in first-appearance order', () => {
    expect(mentionedIds(mentionSegments('@po @frontend @po', ROSTER))).toEqual(['po', 'frontend'])
  })

  it('★ the model carries NO delivery/read state — advisory is structural, not a copy promise', () => {
    // A UI cannot render "delivered"/"read" if the model has no such field. This scan fails the day someone adds
    // one, which is exactly when the advisory-only AC would otherwise be lost silently.
    const src = readFileSync(new URL('./mentionModel.ts', import.meta.url), 'utf8')
    const code = src.replace(/^\s*(\/\/.*|\*.*|\/\*.*)$/gm, '') // the header prose discusses these words on purpose
    const forbidden = ['delivered', 'readAt', 'seen', 'notifiedAt', 'deliveredAt', 'receipt', 'acknowledged']
    expect(forbidden.filter((w) => new RegExp(`\\b${w}\\b`, 'i').test(code))).toEqual([])
  })
})
