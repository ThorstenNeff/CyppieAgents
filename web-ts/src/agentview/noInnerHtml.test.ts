// CYP-401 (W3) security AC — fail-closed source guard: no `dangerouslySetInnerHTML` and no raw `innerHTML`
// assignment anywhere in src/. Untrusted agent output is only ever rendered via React text children (escaped).
// If a future change reaches for innerHTML, this reds — the XSS backstop, not a one-shot check.
import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = join(dirname(fileURLToPath(import.meta.url)), '..')

function walk(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = join(dir, e.name)
    if (e.isDirectory()) return e.name === 'generated' ? [] : walk(p) // generated is machine-emitted, no innerHTML
    return p.endsWith('.ts') || p.endsWith('.tsx') ? [p] : []
  })
}

describe('XSS source guard', () => {
  it('no dangerouslySetInnerHTML / innerHTML assignment in src/', () => {
    const offenders: string[] = []
    for (const file of walk(SRC)) {
      const text = readFileSync(file, 'utf8')
      if (file.endsWith('noInnerHtml.test.ts')) continue // this guard names the tokens in prose
      // Match real USAGE (a JSX prop / an assignment), not prose mentions in KDoc.
      if (/dangerouslySetInnerHTML\s*=/.test(text) || /\.innerHTML\s*=/.test(text)) offenders.push(file)
    }
    expect(offenders).toEqual([])
  })
})
