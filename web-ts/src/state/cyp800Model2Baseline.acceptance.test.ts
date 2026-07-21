import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { resolve, join, relative } from 'node:path'
import { setOnUnauthorized } from '../net/rest'

// CYP-800 — buildable-now acceptance/guard baseline for two Model-2 client-needs (docs/plans/po2-cyp748-model2-tooth-plan.md).
// N3.UNKNOWN is NOT here (measured blocked: no per-hub trust type exported to web-ts). Fold-base: Dev5's registry migration
// (b13d8b8d). Dev5's BEHAVIORAL teeth (hubRegistry.test: endpointFor(unknown)===null; hubConfig.test: hubConfigFrom→throw)
// cover the in-file regression; THIS scan covers NEW / co-located BYPASS files/functions.
//
// ★ N1.1 — rename-robust + comment-safe SOURCE SCAN: no production .ts under src/ reads the global endpoint accessors
// apiBaseUrl()/wsBaseUrl() OUTSIDE the allowed exception. Assist2 granularity finding (F-B would reopen otherwise):
//   • platform/appConfig.ts = pure DEFINITION module → file-exact exception (safe).
//   • state/hubConfig.ts = CONSUMER module (home of the anti-pattern) → NOT a whole-file exception (that would hide a
//     co-located bypass like readHubConfigV2 next to the seed). Exception is FUNCTION-SCOPED to `bootstrapLocalHub`
//     (the one legit seed that reads globals once). A global read in hubConfig.ts OUTSIDE bootstrapLocalHub REDDENS.
//   • the registry (src/net/hubRegistry.ts) is PURE (no globals) → needs NO exception.
// operatorToken() is deliberately OUT of scope: it stays global in THIS migration (per-hub token = N2/audience-bound,
// CYP-798-gated), so scanning it would red-now AND red-after → never flips green (false-red on the correct migration).
//
// State: RED on develop (readHubConfig reads the globals, outside the not-yet-existing bootstrapLocalHub) → GREEN on the
// migration (readHubConfig registry-keyed; only the seed reads globals, excepted). MERGE ONLY WITH THE N1/N4 MIGRATION.

const SRC = resolve(process.cwd(), 'src')

/** Strip a `//` line comment only when the `//` is OUTSIDE string quotes (quote-aware; not a naive split). */
function codeOnly(line: string): string {
  let s = false
  let d = false
  let t = false
  for (let i = 0; i < line.length; i++) {
    const c = line[i]
    if (c === '\\') { i++; continue }
    if (c === "'" && !d && !t) s = !s
    else if (c === '"' && !s && !t) d = !d
    else if (c === '`' && !s && !d) t = !t
    else if (c === '/' && line[i + 1] === '/' && !s && !d && !t) return line.slice(0, i)
  }
  return line
}
const isBlockComment = (l: string) => /^\s*(\*|\/\*)/.test(l)
const GLOBAL_CALL = /\b(?:apiBaseUrl|wsBaseUrl)\s*\(\s*\)/

/** 0-indexed [start,end] line span of `fnName`'s body (brace-matched over comment-stripped code), or null. */
function bodySpan(lines: string[], fnName: string): [number, number] | null {
  const decl = new RegExp(`\\b(?:function\\s+${fnName}\\b|(?:const|let|var)\\s+${fnName}\\s*=|\\b${fnName}\\s*\\([^)]*\\)\\s*(?::[^={]*)?[={])`)
  const start = lines.findIndex((l) => decl.test(codeOnly(l)))
  if (start < 0) return null
  let depth = 0
  let seen = false
  for (let i = start; i < lines.length; i++) {
    for (const ch of codeOnly(lines[i])) {
      if (ch === '{') { depth++; seen = true } else if (ch === '}') depth--
    }
    if (seen && depth <= 0) return [start, i]
  }
  return null
}

const srcFiles = (dir: string): string[] =>
  readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = join(dir, e.name)
    if (e.isDirectory()) return e.name === 'generated' || e.name === 'node_modules' ? [] : srcFiles(p)
    return /\.ts$/.test(e.name) && !/\.test\.ts$/.test(e.name) ? [p] : []
  })

/** Real (non-comment) global-accessor calls that are NOT in an allowed exception. Exceptions:
 *  appConfig.ts = whole file; hubConfig.ts = only inside `bootstrapLocalHub`. Every other call is an offender. */
function globalAccessorOffenders(): string[] {
  const out: string[] = []
  for (const file of srcFiles(SRC)) {
    const rel = relative(SRC, file).replace(/\\/g, '/')
    if (rel === 'platform/appConfig.ts') continue // pure definition module — file-exact exception
    const lines = readFileSync(file, 'utf8').split('\n')
    const seedSpan = rel === 'state/hubConfig.ts' ? bodySpan(lines, 'bootstrapLocalHub') : null
    lines.forEach((line, i) => {
      if (isBlockComment(line) || !GLOBAL_CALL.test(codeOnly(line))) return
      if (seedSpan && i >= seedSpan[0] && i <= seedSpan[1]) return // legit seed read (function-scoped exception)
      out.push(`${rel}:${i + 1}: ${line.trim()}`)
    })
  }
  return out
}

describe('CYP-800 / N1.1 — no prod file reads the global endpoint accessors outside the seed (rename-robust, comment-safe)', () => {
  it('★ acceptance: only appConfig(def) + hubConfig.bootstrapLocalHub(seed) read apiBaseUrl()/wsBaseUrl()', () => {
    // RED on develop (readHubConfig reads them, outside the not-yet-existing seed). GREEN on the migration (registry-keyed
    // readHubConfig; only the seed reads globals). Catches ANY other file OR a co-located bypass in hubConfig.ts.
    expect(globalAccessorOffenders()).toEqual([])
  })

  it('discrimination (green now): the detector catches a bypass anywhere + a co-located one, and ignores comments/seed', () => {
    const offenders = (rel: string, src: string): boolean => {
      const lines = src.split('\n')
      const span = rel === 'state/hubConfig.ts' ? bodySpan(lines, 'bootstrapLocalHub') : null
      return lines.some((line, i) => !isBlockComment(line) && GLOBAL_CALL.test(codeOnly(line)) && !(span && i >= span[0] && i <= span[1]))
    }
    // a bypass in any other file → offender
    expect(offenders('state/hubX.ts', `export function x() { return apiBaseUrl() }`)).toBe(true)
    // ★ a co-located bypass in hubConfig.ts OUTSIDE the seed → offender (the F-B case Assist2 flagged)
    expect(offenders('state/hubConfig.ts', `function bootstrapLocalHub() { seed(apiBaseUrl()) }\nfunction readHubConfigV2() { return wsBaseUrl() }`)).toBe(true)
    // the legit seed read INSIDE bootstrapLocalHub → exempt
    expect(offenders('state/hubConfig.ts', `function bootstrapLocalHub() { seed(apiBaseUrl(), wsBaseUrl()) }`)).toBe(false)
    // a comment mentioning the accessor → ignored (comment-safe)
    expect(offenders('state/other.ts', `// builds from apiBaseUrl()\nexport const x = 1`)).toBe(false)
  })
})

/** The param list of the ACTUAL imported function (rename-robust; not a regex over a named call site). */
const paramList = (fn: (...a: never[]) => unknown): string => fn.toString().match(/\(([^)]*)\)/)?.[1] ?? ''

describe('CYP-800 / N4.b — setOnUnauthorized is per-hubId (runtime signature of the imported symbol)', () => {
  it('★ acceptance: the exported setOnUnauthorized takes a hubId (not one global 401 seam)', () => {
    expect(/\bhubId\b/.test(paramList(setOnUnauthorized))).toBe(true) // red now (handler-only), green when (hubId, handler)
  })
  it('discrimination (green now): the param check greens on a hubId-taking fn, reds on the single-global form', () => {
    expect(/\bhubId\b/.test(paramList((hubId: string, h: unknown) => { void hubId; void h }))).toBe(true)
    expect(/\bhubId\b/.test(paramList((h: unknown) => { void h }))).toBe(false)
  })
})
