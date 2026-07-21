// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { RestHubRepo } from './restRepo'

// CYP-750 — the ONE REST body CYP-737 structurally missed. `uploadAvatar` (restRepo.ts) is a hand-rolled multipart
// `fetch` (RestClient can't send FormData), so it never flowed through the seam 737 validates. It USED to end
// `return (await res.json()) as AgentDetail` — the exact unchecked cast 737 exists to kill; a 200 whose body was
// missing/mistyped became a plausible AgentDetail the agent settings/detail UI then made confident statements about
// (persona, launch) after an avatar change.
//
// CYP-750's fix IS MERGED: uploadAvatar now runs `validateResponse('POST', path, contractResponse('AgentDetail',
// AgentDetailSchema), …)`. This tooth is the STANDING REGRESSION GUARD for it — implementation-AGNOSTIC (it pins the
// observable contract "a 200 is not an answer", holding for any validator), so it reddens if anyone reverts the
// validation to a naked cast.
//
// STATUS against the merged develop: the ★ cases are GREEN — the malformed bodies REJECT because uploadAvatar now
// validates. The positive control (a valid body resolves) is green independently of the validation, so it stays green
// under the prove-red mutation — it witnesses the wiring, not the validation.
// PROVE-RED (a mutation, NOT the current status): revert uploadAvatar to `as AgentDetail` → the 4 ★ go RED, the
// positive control stays GREEN. Verified at the object on this branch's base (develop with the fix live): 5/5 GREEN.

const afterHooks: Array<() => void> = []
afterEach(() => {
  vi.unstubAllGlobals()
  afterHooks.splice(0).forEach((h) => h())
})

/** Stub global fetch to answer the avatar POST with `status`/`body`, duck-typed to what uploadAvatar reads. */
function stubFetch(body: unknown, status = 200) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: status >= 200 && status < 300,
      status,
      json: async () => body,
      text: async () => JSON.stringify(body),
    })),
  )
}

const repo = () => new RestHubRepo('http://x')
const pngFile = () => new File(['\x89PNG'], 'a.png', { type: 'image/png' })

const VALID_DETAIL = { id: 'backend', name: 'Backend', role: 'WORKER', worktree: 'backend', launch: 'bash', persona: null }

describe('CYP-750 uploadAvatar validates its response body (the 737 miss)', () => {
  it('positive control: a VALID AgentDetail body resolves (the guard is not vacuously rejecting)', async () => {
    stubFetch(VALID_DETAIL)
    const out = await repo().uploadAvatar('backend', pngFile())
    expect(out).toMatchObject({ id: 'backend', role: 'WORKER' })
  })

  it('★ a 200 with an EMPTY body must reject — never resolve to a plausible AgentDetail', async () => {
    stubFetch({})
    await expect(repo().uploadAvatar('backend', pngFile())).rejects.toBeDefined()
  })

  it('★ a 200 MISSING required fields (only id) must reject', async () => {
    stubFetch({ id: 'backend' })
    await expect(repo().uploadAvatar('backend', pngFile())).rejects.toBeDefined()
  })

  it('★ a 200 with a MISTYPED field (role as a number) must reject', async () => {
    stubFetch({ ...VALID_DETAIL, role: 123 })
    await expect(repo().uploadAvatar('backend', pngFile())).rejects.toBeDefined()
  })

  it('★ a 200 that is actually the ERROR ENVELOPE (a 200 is not an answer) must reject', async () => {
    stubFetch({ error: { code: 'avatar_too_large', message: 'x' } })
    await expect(repo().uploadAvatar('backend', pngFile())).rejects.toBeDefined()
  })
})
