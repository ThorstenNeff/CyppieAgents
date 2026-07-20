// @vitest-environment jsdom
// CYP-750 — the multipart avatar upload response is validated like every other consumed body. It was
// `return (await res.json()) as AgentDetail` — an unchecked cast on the RAW-FETCH path (uploadAvatar can't use
// RestClient, it sends FormData), so the CYP-737 `this.rest.*` coverage scan never saw it and a malformed
// AgentDetail (e.g. a dropped avatar ref / cache-bust) flowed on as a plausible object. Now validated + masked.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { RestHubRepo } from './restRepo'
import { ResponseShapeError, RestError } from '../net/rest'

const VALID = { id: 'a1', name: 'A', role: 'WORKER', worktree: 'a', launch: 'bash' }

const okJson = (body: unknown) =>
  vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body, text: async () => '' } as never)

const file = () => new File(['bytes'], 'a.png', { type: 'image/png' })

afterEach(() => vi.unstubAllGlobals())

describe('CYP-750 — uploadAvatar validates its multipart response', () => {
  it('a conforming AgentDetail passes through (non-vacuous control)', async () => {
    vi.stubGlobal('fetch', okJson(VALID))
    const out = await new RestHubRepo('http://x').uploadAvatar('a1', file())
    expect(out).toMatchObject({ id: 'a1', role: 'WORKER' })
  })

  it('★ a malformed AgentDetail (missing `role`) is a FAILED read, not a plausible object', async () => {
    const { role, ...noRole } = VALID
    void role
    vi.stubGlobal('fetch', okJson(noRole))
    await expect(new RestHubRepo('http://x').uploadAvatar('a1', file())).rejects.toBeInstanceOf(ResponseShapeError)
  })

  it('★ a mistyped field is rejected too (not only a missing one)', async () => {
    vi.stubGlobal('fetch', okJson({ ...VALID, role: 'EMPEROR' })) // not in the role enum
    await expect(new RestHubRepo('http://x').uploadAvatar('a1', file())).rejects.toBeInstanceOf(ResponseShapeError)
  })

  it('★ a shape failure is distinct from a transport error — 200 arrived, the body did not conform', async () => {
    vi.stubGlobal('fetch', okJson({}))
    const err = await new RestHubRepo('http://x').uploadAvatar('a1', file()).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ResponseShapeError)
    expect(err).not.toBeInstanceOf(RestError) // collapsing the two would hide which half broke
  })

  it('★ COVERAGE: no unvalidated raw-fetch `res.json() as T` cast remains in restRepo (the CYP-737 blind spot)', () => {
    // The CYP-737 coverage scan only sees `this.rest.<method><T>`; a raw-fetch call site casting the parsed body
    // escapes it. This guard keeps THAT path honest: a new raw-fetch consumed read that casts instead of validating
    // reds here. Matches `res.json()) as <Type>` (the exact anti-pattern CYP-750 removed).
    const src = readFileSync(resolve(process.cwd(), 'src/state/restRepo.ts'), 'utf8')
    expect(src).not.toMatch(/res\.json\(\)\s*\)?\s*as\s+\w/)
  })
})
