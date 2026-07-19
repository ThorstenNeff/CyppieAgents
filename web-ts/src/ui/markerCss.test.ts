import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// CYP-741 (rebase) — a CSS-PRESENCE tooth for the channel/roster markers, in the shape of commBannerCss (CYP-437).
//
// WHY THIS EXISTS, and why it was written during a rebase rather than with the feature: every render tooth for
// these markers is headless. jsdom mounts the markup but never applies index.css, so a marker whose RULE is
// missing still renders — as bare inline text — and every existing test stays green. That is not hypothetical:
// `.comm-unread` shipped in CYP-705 with markup and NO rule, and nothing caught it until a human looked. CYP-740
// added the missing rule; CYP-741 then collided with it in index.css at a shared insertion point.
//
// A hand-resolved CSS conflict is exactly where a rule block gets dropped, and the 844-green suite could not have
// seen it — the resolution would have been "verified" by a measurement structurally blind to the thing at risk.
// So the guard is on the SHIPPED FILE: the rules exist, and the two adjacent channel markers stay visually
// distinct (they state different facts — "you were mentioned" vs "N unread" — and must not read as one run).
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')

/**
 * The union of EVERY rule body that targets `selector`, grouped selectors included.
 *
 * Not an incidental detail: these markers are styled by two rules each — a grouped one carrying the shared pill
 * geometry (`.comm-mention-cue, .comm-unread { … }`) and an individual one carrying the tint. A helper that
 * stopped at the first match read the geometry for BOTH selectors, concluded they were identical, and would have
 * reported a distinctness failure that the shipped CSS does not have. The union is what the browser actually
 * applies, so it is what the guard must inspect.
 *
 * `(?![\w-])` keeps `.comm-unread` from matching `.comm-unread-unknown`.
 */
const styleFor = (selector: string): string => {
  const re = new RegExp(`\\${selector}(?![\\w-])[^{}]*\\{([^}]*)\\}`, 'g')
  const bodies = [...css.matchAll(re)].map((m) => m[1])
  expect(bodies.length, `no CSS rule for ${selector} — markup without styling renders as bare text`).toBeGreaterThan(0)
  return bodies.join('\n')
}

describe('channel + roster marker CSS is actually shipped (CYP-705 / 740 / 741)', () => {
  it('★ every marker the markup emits has a rule — absence here is invisible to the render teeth', () => {
    for (const sel of ['.comm-mention-cue', '.comm-unread', '.comm-unread-unknown', '.agent-mgmt-dot', '.agent-mgmt-busy']) {
      expect(styleFor(sel).trim().length, `${sel} has an empty rule body`).toBeGreaterThan(0)
    }
  })

  it('★ the two adjacent channel markers are visually DISTINCT — they state different facts', () => {
    // Same pill geometry (they sit side by side), different tint. If both carried the same background they would
    // read as one marker, which is precisely what the separate testids exist to prevent.
    const cue = styleFor('.comm-mention-cue')
    const unread = styleFor('.comm-unread')
    expect(cue).toMatch(/background:/)
    expect(unread).toMatch(/background:/)
    expect(cue).not.toBe(unread)
  })

  it('★ the unread COUNT is tabular — a changing count must not reflow the channel row', () => {
    expect(styleFor('.comm-unread')).toMatch(/tabular-nums/)
  })

  it('★ the roster dot never collapses: it keeps its box and its own width at any row width', () => {
    // `flex: 0 0 auto` is load-bearing — a shrinking dot in a narrow roster row would silently become no dot,
    // turning "unobserved" (a ring) into nothing at all.
    const dot = styleFor('.agent-mgmt-dot')
    expect(dot).toMatch(/display:\s*inline-block/)
    expect(dot).toMatch(/flex:\s*0\s+0\s+auto/)
  })

  it('★ busy is its own marker, not folded into the dot — run-state and busy are different facts', () => {
    expect(styleFor('.agent-mgmt-busy')).not.toBe(styleFor('.agent-mgmt-dot'))
  })
})
