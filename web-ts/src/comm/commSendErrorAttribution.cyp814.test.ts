// CYP-814 Batch-2 G3 — App's comm send-failure ATTRIBUTION. A 403 is an ACL DENIAL → 'comm_send_denied'; any OTHER
// failure is TRANSIENT → 'comm_send_failed' (retryable). The two render DISTINCT disclosures (commDisclosure — already
// pinned), but the `.catch` in App.onSendComm that CHOOSES the code was untested → a mutation mislabeling a transient
// failure as an ACL denial (a dishonest "you're not allowed" for what is really a blip), or vice-versa, survived.
// The arm is an inline `.catch` in App; source-pinned (comment-safe: the literal attribution pattern only appears in code).
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

describe('CYP-814 G3 — a 403 comm send failure is a DENIAL; every other failure is a transient FAILED', () => {
  const src = readFileSync(resolve(process.cwd(), 'src/App.tsx'), 'utf8')

  it('★ onSendComm attributes status===403 → comm_send_denied, else → comm_send_failed (a swap/collapse mutation REDs)', () => {
    // The exact attribution arm. Mutations that break it: `403 ? 'comm_send_failed' : 'comm_send_denied'` (swap),
    // or dropping the 403 test so every failure reads as one code (collapse) — both fail this match.
    expect(src, "App.onSendComm must map 403→denied / else→failed").toMatch(
      /status === 403 \? 'comm_send_denied' : 'comm_send_failed'/,
    )
  })

  it('★ the RestError-status gate is present (a transient failure is never attributed to ACL without a real 403)', () => {
    // non-vacuity: the branch is gated on a genuine RestError 403, not an unconditional label.
    expect(src).toMatch(/err instanceof RestError && err\.status === 403/)
  })
})
