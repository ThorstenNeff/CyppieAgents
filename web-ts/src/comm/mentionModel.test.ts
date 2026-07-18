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

  it('★ Tester2 F1 — the reported false positives produce no mention', () => {
    // measured against d438ebbf: `@dev5x` etc. highlighted `dev5`, pointing at an agent the sender did not mean.
    for (const body of ['@dev5x', '@dev5_backup', '@dev5-hotfix', '@dev52']) {
      expect(seg(body, ['dev5'])).toEqual([body])
    }
  })

  it('★ Tester2 F2 — bracketed/quoted mentions resolve, while the email guard still holds', () => {
    // requiring WHITESPACE before the sigil was too strict: these are ordinary ways to write a mention.
    expect(seg('(@frontend)')).toEqual(['(', '[@frontend]', ')'])
    expect(seg('[@frontend]')).toEqual(['[', '[@frontend]', ']'])
    expect(seg('"@frontend"')).toEqual(['"', '[@frontend]', '"'])
    expect(seg('@frontend, bitte')).toEqual(['[@frontend]', ', bitte'])
    // …and the false-positive guard is unaffected: an id character before the sigil is still no mention.
    expect(seg('mail@frontend')).toEqual(['mail@frontend'])
    expect(seg('x_@frontend')).toEqual(['x_@frontend'])
  })

  it('★ the match must be BOUNDARY-TERMINATED — a prefix of a longer handle is never highlighted', () => {
    // `-` and `_` are legal id characters, so `@frontend-dev` with only `frontend` on the roster must NOT resolve:
    // the sender meant some other (or non-existent) agent, and highlighting `frontend` would point at the wrong
    // person with full confidence. Mirrors the Phase-2 server rule.
    expect(seg('@frontend-dev', ['frontend'])).toEqual(['@frontend-dev'])
    expect(seg('@po_2', ['po'])).toEqual(['@po_2'])
    expect(seg('@frontend-dev', ['frontend', 'frontend-dev'])).toEqual(['[@frontend-dev]']) // exact id → resolves
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

  // Code-exempt / quote-include — the rule shared with the Phase-2 server resolver. If the two sides disagree here,
  // the phase boundary drifts (Phase 1 highlights, Phase 2 never notifies), which is the exact defect the shared
  // rule exists to prevent.
  it('★ fenced code is exempt — a handle in a snippet is shown, not addressed', () => {
    expect(seg('siehe\n```\nhub send @frontend\n```\ndanke')).toEqual(['siehe\n```\nhub send @frontend\n```\ndanke'])
  })

  it('★ inline code is exempt', () => {
    expect(seg('nimm `@frontend` als Beispiel')).toEqual(['nimm `@frontend` als Beispiel'])
  })

  it('★ a QUOTED line still mentions — quoting someone who addressed you is still addressing', () => {
    expect(seg('> @frontend bitte schauen')).toEqual(['> ', '[@frontend]', ' bitte schauen'])
  })

  it('a mention AFTER a closed code block still resolves (the exemption ends with the block)', () => {
    expect(seg('```\n@po\n```\n@frontend')).toEqual(['```\n@po\n```\n', '[@frontend]'])
  })

  it('★ an UNTERMINATED fence exempts to the end — fail-closed, never a half-parsed body', () => {
    expect(seg('```\n@frontend @po')).toEqual(['```\n@frontend @po'])
  })

  it('a backtick INSIDE a fence is content, not an inline delimiter', () => {
    expect(seg('```\n`@po`\n```\n@frontend')).toEqual(['```\n`@po`\n```\n', '[@frontend]'])
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
