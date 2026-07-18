// CYP-704 — teeth for mention resolution. The security-bearing ones are marked ★: they are the reason this model
// exists, and each was mutation-verified RED against the obvious wrong implementation.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolveMentions, mentionedIds, PROVISIONAL_SYNTAX, type RosterEntry, type MentionSyntax } from './mentionModel'

const ROSTER: readonly RosterEntry[] = [
  { id: 'dev5', displayName: 'Dev 5' },
  { id: 'po', displayName: 'Product Owner' },
]

const texts = (body: string, roster: readonly RosterEntry[] = ROSTER, syntax?: MentionSyntax) =>
  resolveMentions(body, roster, syntax).map((s) => (s.kind === 'mention' ? `[@${s.id}]` : s.text))

describe('CYP-704 — mentions resolve fail-closed against the controlled roster', () => {
  it('a roster-known token becomes a mention (non-vacuum control)', () => {
    expect(texts('hi @dev5 bitte schauen')).toEqual(['hi ', '[@dev5]', ' bitte schauen'])
  })

  it('★ an UNKNOWN token stays plain text — a body can never manufacture mention styling', () => {
    // the whole point: `@ceo` is not on the roster, so it must render as text, not as an addressed-to cue.
    expect(texts('hi @ceo und @dev5')).toEqual(['hi @ceo und ', '[@dev5]'])
    expect(resolveMentions('hi @ceo', ROSTER).every((s) => s.kind === 'text')).toBe(true)
  })

  it('★ an empty roster resolves NOTHING — no ambient/default mention', () => {
    expect(texts('hi @dev5 @po', [])).toEqual(['hi @dev5 @po'])
  })

  it('★ an embedded @ is not a mention — the @ needs a word boundary', () => {
    // `mail@dev5.de` alone does NOT discriminate: the token would be `dev5.de`, which fails to resolve anyway, so
    // the test would pass even with the boundary removed (measured — the mutation stayed green). `mail@dev5` is the
    // real case: the token IS a roster id, so ONLY the boundary rule keeps it from rendering as a mention.
    expect(texts('schreib an mail@dev5')).toEqual(['schreib an mail@dev5'])
    expect(texts('schreib an mail@dev5.de')).toEqual(['schreib an mail@dev5.de'])
  })

  it('★ a blank roster key never swallows a token', () => {
    // Only reachable through an INJECTED syntax: the default pattern requires ≥1 token char, so the lookup key can
    // never be ''. A custom syntax that admits an empty token would otherwise hand every bare `@` to a blank-id
    // roster entry. (Verified: with the default syntax this mutation stays green — hence the injected one here.)
    const emptyTokenSyntax: MentionSyntax = { ...PROVISIONAL_SYNTAX, pattern: /(^|\s)@([A-Za-z0-9._-]{0,64})/g }
    const withBlank: readonly RosterEntry[] = [{ id: '   ', displayName: 'blank' }, ...ROSTER]
    expect(texts('hi @ da', withBlank, emptyTokenSyntax)).toEqual(['hi @ da']) // bare @ resolves to nothing
  })

  it('resolves case-insensitively but reports the CANONICAL roster id, never the raw token', () => {
    const segs = resolveMentions('moin @DEV5', ROSTER)
    const mention = segs.find((s) => s.kind === 'mention')
    expect(mention).toMatchObject({ kind: 'mention', id: 'dev5', text: '@DEV5' }) // id canonical, text verbatim
  })

  it('★ resolution is lossless — concatenating every segment reproduces the body exactly', () => {
    // guarantees the model can neither DROP nor INJECT content while re-segmenting it.
    for (const body of ['', '@dev5', 'a @dev5 b @po c', 'mail@dev5.de @ceo @po!', '@@dev5', 'ends with @']) {
      const rebuilt = resolveMentions(body, ROSTER)
        .map((s) => s.text)
        .join('')
      expect(rebuilt).toBe(body)
    }
  })

  it('is stable across repeated calls (no leaked regex lastIndex)', () => {
    const body = 'hi @dev5'
    const once = texts(body)
    expect(texts(body)).toEqual(once)
    expect(texts(body)).toEqual(once)
  })

  it('honours an injected syntax — the parser is a parameter, the security rule is not', () => {
    const byDisplayName: MentionSyntax = { ...PROVISIONAL_SYNTAX, keysOf: (e) => [e.displayName.replace(/\s/g, '')] }
    expect(texts('hi @Dev5', ROSTER, byDisplayName)).toEqual(['hi ', '[@dev5]']) // resolved via display name
    expect(texts('hi @nobody', ROSTER, byDisplayName)).toEqual(['hi @nobody']) // still fail-closed
  })

  it('mentionedIds de-duplicates in first-appearance order', () => {
    expect(mentionedIds(resolveMentions('@po @dev5 @po', ROSTER))).toEqual(['po', 'dev5'])
  })

  it('★ the model carries NO delivery/read state — advisory is structural, not a copy promise', () => {
    // A UI cannot render "delivered"/"read" if the model has no such field. This scan is the guard: it fails the
    // day someone adds one, which is when the advisory-only AC would silently be lost.
    const src = readFileSync(new URL('./mentionModel.ts', import.meta.url), 'utf8')
    const code = src.replace(/^\s*(\/\/.*|\*.*|\/\*.*)$/gm, '') // ignore the header prose, which discusses these words
    const forbidden = ['delivered', 'readAt', 'seen', 'notifiedAt', 'deliveredAt', 'receipt', 'acknowledged']
    expect(forbidden.filter((w) => new RegExp(`\\b${w}\\b`, 'i').test(code))).toEqual([])
  })
})
