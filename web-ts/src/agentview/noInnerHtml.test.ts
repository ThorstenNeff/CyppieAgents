// CYP-401 (W3) security AC — fail-closed source guard: no raw-HTML / dynamic-eval XSS sinks anywhere in src/.
// Untrusted agent output is only ever rendered via React text children (escaped). If a future change reaches for one
// of these sinks, this reds — the XSS backstop, not a one-shot check.
// CYP-456 (CYP-422-prep): widened beyond dangerouslySetInnerHTML / innerHTML to the rest of the DOM-injection +
// dynamic-code family (outerHTML, insertAdjacentHTML, document.write(ln), eval, new Function) — all 0 in src/ today,
// so the test stays green while the backstop now catches those regressions too.
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

// The XSS sink family. Patterns match real USAGE (a JSX prop, an assignment, a call), not prose mentions in KDoc:
// the *HTML assignments require `=`, the injection/eval calls require `(`. CYP-456 widened this set.
const XSS_SINKS: readonly RegExp[] = [
  /dangerouslySetInnerHTML\s*=/, // React raw-HTML prop
  /\.(?:inner|outer)HTML\s*=/, // element.innerHTML / .outerHTML assignment
  /\.insertAdjacentHTML\s*\(/, // element.insertAdjacentHTML(...)
  /\bdocument\.write(?:ln)?\s*\(/, // document.write / writeln
  /\beval\s*\(/, // dynamic eval
  /\bnew\s+Function\s*\(/, // Function-constructor eval
]

describe('XSS source guard', () => {
  it('no raw-HTML injection or dynamic-eval sink in src/', () => {
    const offenders: string[] = []
    for (const file of walk(SRC)) {
      const text = readFileSync(file, 'utf8')
      if (file.endsWith('noInnerHtml.test.ts')) continue // this guard names the tokens in prose
      if (XSS_SINKS.some((re) => re.test(text))) offenders.push(file)
    }
    expect(offenders).toEqual([])
  })
})
