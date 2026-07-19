// CYP-744 §3-ii — the PARITY ORACLE for the Phase-2 server resolver, emitted as language-neutral JSON.
//
// The spec requires the server's `mentions` to reproduce the CYP-704 client rule "bit-exact, proven with the OLD
// CLIENT FIXTURES". Those fixtures currently exist only as assertions inside `mentionModel.test.ts` — TypeScript,
// with the expectations spread across 22 `it()` blocks. For a Kotlin implementation to prove parity against that,
// someone has to READ my test file and re-derive the cases by hand, and a hand-transcribed oracle is not a shared
// source of truth: it is a second interpretation of the rule, which is exactly the drift CYP-744 exists to end.
//
// So this file emits the cases as data (`docs/design/cyp744-mention-parity-fixtures.json`), in the `MentionSpan`
// shape the server must produce, COMPUTED FROM the client implementation — never hand-written. Both sides then
// test against one artifact.
//
// WHY IT IS A TEST AND NOT A SCRIPT: a checked-in fixture that nobody verifies becomes a third source that drifts
// silently from the implementation it claims to describe. This test recomputes every span on each run and fails if
// the committed JSON disagrees — so the oracle cannot rot, in either direction. Regenerate deliberately with
// `UPDATE_MENTION_FIXTURES=1 vitest run src/comm/mentionParityFixtures.test.ts`; a diff in that file is then a
// visible, reviewable change to the RULE, not an invisible one.
import { describe, it, expect } from 'vitest'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { mentionSegments } from './mentionModel'

const FIXTURE_PATH = resolve(process.cwd(), '../docs/design/cyp744-mention-parity-fixtures.json')

/** The roster used by most cases. Kept small and explicit so a reader can check a case by eye. */
const ROSTER = ['frontend', 'front', 'dev5', 'backend', 'po', 'frontend-dev', 'ui_ux']

interface Case {
  readonly name: string
  readonly why: string
  readonly body: string
  readonly rosterIds: readonly string[]
}

// Every case below is one honesty rule from CYP-704, carried forward verbatim. The `why` travels WITH the data:
// a fixture that says only "expected: []" teaches nothing when it fails, and a server author reading this file
// should not have to guess which rule a case is defending.
const CASES: readonly Case[] = [
  { name: 'plain-mention', why: 'a roster-known token is one mention; surrounding text stays intact', body: 'hi @frontend please look', rosterIds: ROSTER },
  { name: 'unknown-token-fail-closed', why: 'FALSE-POSITIVE GUARD: an unknown handle is NOT a mention — claiming someone was addressed when nobody is called that is the core lie', body: 'hi @nobody there', rosterIds: ROSTER },
  { name: 'email-not-a-mention', why: 'a sigil mid-word is not a mention: in mail@dev5.de the preceding `l` is an id character', body: 'write to mail@dev5.de please', rosterIds: ROSTER },
  { name: 'empty-roster-resolves-nothing', why: 'UNLOADED or FAILED roster ⇒ plain text, never a guessed mention (CYP-288 tie-in)', body: 'hi @frontend', rosterIds: [] },
  { name: 'multiple-mentions', why: 'several mentions in one body; the connecting text stays text', body: '@po and @dev5 sync please', rosterIds: ROSTER },
  { name: 'longest-match-wins', why: 'with both `front` and `frontend` on the roster, `@frontend` resolves to the LONGER id', body: 'ping @frontend', rosterIds: ROSTER },
  { name: 'boundary-terminated', why: 'THE WRONG-PERSON GUARD: `@frontend-dev` must not highlight `frontend`, because `-` is itself a legal id character', body: 'ping @frontend-dev now', rosterIds: ['frontend', 'front'] },
  { name: 'boundary-terminated-resolves-full-id', why: 'the same body DOES resolve when the full id is on the roster', body: 'ping @frontend-dev now', rosterIds: ROSTER },
  { name: 'punctuation-stops-at-id', why: 'longest-match stops at the id; trailing punctuation stays text', body: 'ok @frontend! thanks', rosterIds: ROSTER },
  { name: 'bracketed', why: 'Tester2 F2: (@dev5) is an ordinary way to write a mention', body: 'see (@dev5) for this', rosterIds: ROSTER },
  { name: 'quoted-inline', why: 'Tester2 F2: "@dev5" resolves while the email guard still holds', body: 'the "@dev5" ticket', rosterIds: ROSTER },
  { name: 'case-insensitive-canonical-id', why: 'people type @Frontend; the id reported is CANONICAL, the text stays verbatim', body: 'hey @Frontend', rosterIds: ROSTER },
  { name: 'underscore-id', why: '`_` is an id character, not a break', body: 'ping @ui_ux please', rosterIds: ROSTER },
  { name: 'fenced-code-exempt', why: 'a handle in a snippet is being SHOWN, not addressed — must not summon anyone', body: 'look:\n```\nrun @frontend now\n```\ndone', rosterIds: ROSTER },
  { name: 'inline-code-exempt', why: 'same rule for inline code', body: 'the flag `@dev5` is literal', rosterIds: ROSTER },
  { name: 'unterminated-fence-exempt-to-end', why: 'FAIL-CLOSED: an unterminated fence exempts to the end rather than half-parsing the body', body: 'oops:\n```\n@frontend still in code', rosterIds: ROSTER },
  { name: 'mention-after-closed-fence', why: 'the exemption ENDS with the block — a later mention still resolves', body: '```\n@po in code\n```\nnow really @po', rosterIds: ROSTER },
  { name: 'backtick-inside-fence-is-content', why: 'a backtick inside a fence is content, not an inline delimiter', body: '```\na ` b @frontend\n```', rosterIds: ROSTER },
  { name: 'code-exempt-beats-sigil-boundary', why: 'the widened sigil boundary must not turn a backtick into a licence to mention', body: 'text `@dev5` more', rosterIds: ROSTER },
  { name: 'quoted-line-still-mentions', why: 'quoting someone who addressed you is STILL addressing — quotes are included, unlike code', body: '> hey @dev5 look\nyes', rosterIds: ROSTER },
  { name: 'bare-sigil', why: 'a bare `@` resolves to nothing and stays text', body: 'email @ me', rosterIds: ROSTER },
  { name: 'blank-roster-id-ignored', why: 'a blank/whitespace roster id must never swallow a bare sigil', body: 'hi @ there', rosterIds: ['', '   ', 'dev5'] },
  { name: 'adjacent-mentions', why: 'two mentions separated only by punctuation keep distinct spans', body: '@po,@dev5', rosterIds: ROSTER },
  { name: 'mention-at-string-start', why: 'position 0 is a valid sigil boundary', body: '@dev5 first', rosterIds: ROSTER },
  {
    name: 'utf16-offsets-after-astral-char',
    why:
      'OFFSET UNITS ARE UTF-16 CODE UNITS, end-EXCLUSIVE. An emoji outside the BMP counts as TWO units, so a ' +
      'server using code POINTS would report a span two short here. Without this case both conventions agree on ' +
      'every ASCII fixture and the disagreement ships silently.',
    body: '🎉 party @dev5 now',
    rosterIds: ROSTER,
  },
]

/** Convert the client segments into the server-facing `MentionSpan` shape: {start, end, id}, end-exclusive. */
function spansOf(body: string, rosterIds: readonly string[]): { start: number; end: number; id: string }[] {
  const spans: { start: number; end: number; id: string }[] = []
  let offset = 0
  for (const seg of mentionSegments(body, rosterIds)) {
    if (seg.kind === 'mention') spans.push({ start: offset, end: offset + seg.text.length, id: seg.id })
    offset += seg.text.length
  }
  return spans
}

const build = () => ({
  $comment:
    'CYP-744 §3-ii parity oracle. GENERATED from the CYP-704 client resolver (web-ts mentionModel.ts) — do not ' +
    'hand-edit; regenerate with UPDATE_MENTION_FIXTURES=1. Offsets are UTF-16 code units over `body`, `end` is ' +
    'EXCLUSIVE. The server implementation of CYP-744 must reproduce `expected` exactly for every case.',
  cases: CASES.map((c) => ({ ...c, expected: spansOf(c.body, c.rosterIds) })),
})

describe('CYP-744 §3-ii — the parity oracle stays identical to the rule it claims to describe', () => {
  it('the committed fixture matches what the client resolver actually produces', () => {
    const current = JSON.stringify(build(), null, 2) + '\n'
    if (process.env.UPDATE_MENTION_FIXTURES === '1') {
      writeFileSync(FIXTURE_PATH, current)
      return
    }
    expect(existsSync(FIXTURE_PATH), `missing ${FIXTURE_PATH} — regenerate with UPDATE_MENTION_FIXTURES=1`).toBe(true)
    // A drift here means the client rule changed without the oracle following (or vice versa). Either way the
    // server would be proving parity against a rule nobody runs any more.
    expect(readFileSync(FIXTURE_PATH, 'utf8')).toBe(current)
  })

  it('★ the oracle is DISCRIMINATING — it contains both positives and fail-closed negatives', () => {
    // An oracle of only-positives would be satisfied by a server that mentions everything; an oracle of
    // only-negatives by one that mentions nothing. Parity is only meaningful if both directions are pinned.
    const built = build().cases
    expect(built.some((c) => c.expected.length > 0)).toBe(true)
    expect(built.some((c) => c.expected.length === 0)).toBe(true)
  })

  it('★ every span is well-formed and actually points at the text it claims', () => {
    // The property the CLIENT will enforce on server spans at render time (0≤start<end≤len, non-overlapping,
    // in-order). Pinning it on the oracle means a malformed expectation can never be handed to the server.
    for (const c of build().cases) {
      let prevEnd = 0
      for (const s of c.expected) {
        expect(s.start, c.name).toBeGreaterThanOrEqual(prevEnd)
        expect(s.end, c.name).toBeGreaterThan(s.start)
        expect(s.end, c.name).toBeLessThanOrEqual(c.body.length)
        // the slice must be the literal typed token, sigil included — this is what makes the offsets checkable
        expect(c.body.slice(s.start, s.end).toLowerCase(), c.name).toBe(`@${s.id.toLowerCase()}`)
        prevEnd = s.end
      }
    }
  })

  it('★ the astral case really is discriminating — its mention starts past the BMP boundary', () => {
    // Guards the guard: if someone later "simplifies" the emoji out of that body, the code-unit-vs-code-point
    // question silently stops being tested and both conventions pass again.
    const astral = build().cases.find((c) => c.name === 'utf16-offsets-after-astral-char')!
    expect(astral.expected).toHaveLength(1)
    expect(astral.body.codePointAt(0)! > 0xffff).toBe(true)
    // code-POINT offsets would be exactly one lower per astral char before the span
    expect(astral.expected[0].start).toBe([...astral.body].slice(0, 8).join('').length)
  })
})
