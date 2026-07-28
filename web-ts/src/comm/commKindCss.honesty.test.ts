import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// CYP-868 committed-CSS guard. Reads the SHIPPED src/index.css. The orchestration-type badges are colour-never-sole
// (the kind WORD is in the badge), but the TONE must not mislead: a message TYPE (TASK/STATUS) is neither an error
// (never error-red — a task is not a crash) nor a success (never a green all-clear). Comments are stripped so comment
// prose can't trip the tone regex.
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8')
const ruleBody = (selector: string): string => {
  const re = new RegExp(`\\${selector}\\s*\\{([^}]*)\\}`, 'g')
  let last: string | null = null
  for (const m of css.matchAll(re)) last = m[1]
  expect(last, `rule for ${selector} must exist in the shipped index.css`).not.toBeNull()
  return (last as string).replace(/\/\*[\s\S]*?\*\//g, '')
}

describe('CYP-868 — orchestration-type badge tone (never error/success — a type is not a crash or an all-clear)', () => {
  for (const sel of ['.comm-kind-task', '.comm-kind-status']) {
    it(`★ ${sel} carries a neutral/action tone, never error-red or success-green`, () => {
      const body = ruleBody(sel)
      expect(body).toMatch(/-container|surface|primary/) // a real neutral/action container tone
      expect(body).not.toMatch(/error|-red/) // a TASK/STATUS is not an error
      expect(body).not.toMatch(/success|-green|tertiary/) // nor an affirming all-clear
    })
  }
})
