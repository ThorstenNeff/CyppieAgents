// CYP-744 Gate 2 — the acceptance tooth: after Phase 2, NO production/render module computes mentions client-side.
// Resolution is the server's job (delivered as MentionSpans on the envelope, proven == the old rule by the parity
// oracle). The CYP-704 parser (mentionModel.ts) survives ONLY as the oracle's reference, imported by TESTS alone.
//
// This is a fail-closed SOURCE guard, not a one-shot check: the day a component reaches back for mentionSegments /
// mentionedIds / imports mentionModel, this reds — the "two resolvers drift again" backstop the ticket demands.
import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = join(dirname(fileURLToPath(import.meta.url)), '..')

/** Every production .ts/.tsx under src/ — EXCLUDING tests (the oracle legitimately imports the parser), the
 *  machine-generated tree, the parser definition itself, and this guard (it names the tokens in prose). */
function productionFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = join(dir, e.name)
    if (e.isDirectory()) return e.name === 'generated' ? [] : productionFiles(p)
    if (!(p.endsWith('.ts') || p.endsWith('.tsx'))) return []
    if (p.endsWith('.test.ts') || p.endsWith('.test.tsx')) return [] // tests may reference the parity reference
    if (p.endsWith('/mentionModel.ts')) return [] // the parser definition, kept as the oracle reference
    if (p.endsWith('/noClientMentionCompute.test.ts')) return [] // unreachable (tests excluded) but explicit
    return [p]
  })
}

// Client-side mention COMPUTE: a call to the parser, or an import of the parser module. Patterns match real usage
// (a call needs `(`, an import needs the module path), not a KDoc mention of the word.
const CLIENT_COMPUTE: readonly RegExp[] = [
  /\bmentionSegments\s*\(/, // the parser entry point
  /\bmentionedIds\s*\(/, // its companion
  /from\s+['"][^'"]*\/mentionModel['"]/, // importing the parser module at all
]

describe('CYP-744 Gate 2 — no client-side mention compute in the render path', () => {
  it('no production module calls the CYP-704 parser or imports mentionModel', () => {
    const files = productionFiles(SRC)
    // Non-vacuous: a broken walk that finds nothing must not pass silently as "all clear".
    expect(files.length).toBeGreaterThan(50)
    const offenders = files.filter((f) => {
      const text = readFileSync(f, 'utf8')
      return CLIENT_COMPUTE.some((re) => re.test(text))
    })
    expect(offenders).toEqual([])
  })
})
