import { describe, it, expect } from 'vitest'
import { readFileSync, existsSync, readdirSync } from 'node:fs'
import { resolve, dirname, join } from 'node:path'
import { AUTH_ROLE } from './authRole'

// CYP-751 — independent second lens on the operator-role parity, AFTER the source fix (authRole.ts single-sources
// AUTH_ROLE; authModel/rosterModel compare against AUTH_ROLE.OPERATOR, not the string 'OPERATOR'). This is NOT the
// fix-proof (the fix + the dev's authRoleParity.test.ts, which pins AUTH_ROLE to :core AuthMe.kt, already ship). It is
// a STANDING anti-drift / anti-regression guard, deliberately independent of the dev test in TWO ways:
//   (1) coverage-by-construction — NO code in src may compare a role/tier against a RAW 'OPERATOR'/'MEMBER' literal;
//       the single-sourced constant is the only allowed form. Catches a future re-introduction of the magic string,
//       which value-pinning alone cannot see.
//   (2) a DIFFERENT authority — it cross-checks AUTH_ROLE against the :server `AuthRole` ENUM in RoleStore.kt (whose
//       `.name` is the emitted wire token), where the dev test uses AuthMe.kt. Agreement across both authorities is
//       stronger than either alone.
// One mutation per check line (per the CYP-759 Case-B discipline): authority-mutation and mirror-mutation each redden
// the parity line; a re-introduced magic string reddens the coverage line. Proven at the object on this branch.

function readUp(rel: string): string {
  let dir = process.cwd()
  for (;;) {
    const p = resolve(dir, rel)
    if (existsSync(p)) return readFileSync(p, 'utf8')
    const up = dirname(dir)
    if (up === dir) throw new Error(`could not locate ${rel} from ${process.cwd()} upwards`)
    dir = up
  }
}

/** The :server AuthRole enum constant names — these become the wire tokens via `.name`. My authority (the dev test's
 *  is AuthMe.kt; using the enum source keeps this an independent oracle). */
function authRoleConstants(): string[] {
  const src = readUp('server/src/main/kotlin/com/tneff/cyppieagents/auth/RoleStore.kt')
  const m = src.match(/enum class AuthRole\s*\{([^}]*)\}/)
  expect(m, 'AuthRole enum must exist in RoleStore.kt (authority present)').not.toBeNull()
  return m![1].split(',').map((s) => s.trim()).filter((s) => /^[A-Z_]+$/.test(s))
}

/** Strip a `//` line comment, but only when the `//` is OUTSIDE string quotes — a naive `split('//')` would also cut
 *  a `//` inside a URL/string literal. Quote-aware scan (borrowed from the .deb-twin guard). Also returns '' for a
 *  whole-line comment. Trailing comments matter here: `role==='OPERATOR'` appears legitimately in a TRAILING comment
 *  in authModel.ts, and must not be mistaken for code (the trailing-comment trap — caught on this guard's first run). */
function codeOnly(line: string): string {
  let inS = false
  let inD = false
  let inT = false
  for (let i = 0; i < line.length; i++) {
    const c = line[i]
    if (c === '\\') { i++; continue }
    if (c === "'" && !inD && !inT) inS = !inS
    else if (c === '"' && !inS && !inT) inD = !inD
    else if (c === '`' && !inS && !inD) inT = !inT
    else if (c === '/' && line[i + 1] === '/' && !inS && !inD && !inT) return line.slice(0, i)
  }
  return line
}

/** Every non-test .ts/.tsx under web-ts/src that compares something against a RAW 'OPERATOR'/'MEMBER' string in CODE
 *  (comments excluded — a comment can echo the old form harmlessly; only executable code is a regression). */
function rawRoleLiteralOffenders(): string[] {
  const isBlockComment = (l: string) => /^\s*(\*|\/\*)/.test(l)
  const cmp = /===\s*['"](OPERATOR|MEMBER)['"]/
  const walk = (dir: string): string[] =>
    readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
      const p = join(dir, e.name)
      if (e.isDirectory()) return e.name === 'generated' || e.name === 'node_modules' ? [] : walk(p)
      return /\.(ts|tsx)$/.test(e.name) && !/\.test\.(ts|tsx)$/.test(e.name) ? [p] : []
    })
  const offenders: string[] = []
  for (const file of walk(resolve(process.cwd(), 'src'))) {
    readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
      if (!isBlockComment(line) && cmp.test(codeOnly(line))) offenders.push(`${file.replace(/.*\/src\//, 'src/')}:${i + 1}: ${line.trim()}`)
    })
  }
  return offenders
}

const sorted = (xs: readonly string[]): string[] => [...xs].sort()

describe('CYP-751 — operator-role parity is single-sourced (anti-drift / anti-regression)', () => {
  const authority = authRoleConstants()

  it('non-vacuity: the authority declares OPERATOR + MEMBER', () => {
    expect(authority).toContain('OPERATOR')
    expect(authority).toContain('MEMBER')
  })

  it('★ AUTH_ROLE values equal the :server AuthRole enum exactly (cross-source, different authority than the dev test)', () => {
    expect(sorted(Object.values(AUTH_ROLE))).toEqual(sorted(authority))
    expect(AUTH_ROLE.OPERATOR).toBe('OPERATOR') // the operator wire token specifically
  })

  it('★ NO code in src compares role/tier against a raw OPERATOR/MEMBER string — the constant is the only form', () => {
    // discrimination is self-evident: this scan reddens the instant a `=== 'OPERATOR'` reappears in code.
    expect(rawRoleLiteralOffenders()).toEqual([])
  })

  it('the two consumers reference AUTH_ROLE.OPERATOR (the fix is wired, not merely defined)', () => {
    for (const rel of ['src/auth/authModel.ts', 'src/workspace/rosterModel.ts']) {
      const src = readUp(`web-ts/${rel}`)
      expect(src, `${rel} must import AUTH_ROLE`).toMatch(/import\s*\{[^}]*\bAUTH_ROLE\b[^}]*\}\s*from/)
      expect(src, `${rel} must compare against AUTH_ROLE.OPERATOR`).toMatch(/AUTH_ROLE\.OPERATOR/)
    }
  })
})
