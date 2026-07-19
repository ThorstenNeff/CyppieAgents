// CYP-737 — teeth for REST response validation (F1's systemic root).
//
// F1 was a point defect: a 200 without `configured` became "hub not set up". The ROOT is that every REST response
// was `as T` — an unchecked cast — so any missing or mistyped field silently became a plausible object, and the
// client then made confident statements about it. WS ingress has validated since CYP-420; REST never did.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { RestClient, ResponseShapeError, RestError, contractResponse } from './rest'
import { RepoConfigViewSchema } from '../types/generated/contractSchemas'

const okJson = (body: unknown) =>
  vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body, text: async () => '' } as never)

afterEach(() => vi.unstubAllGlobals())

describe('CYP-737 — a 200 whose body breaks the contract is a FAILED read, not a usable one', () => {
  it('a conforming body passes through unchanged (non-vacuous control)', async () => {
    vi.stubGlobal('fetch', okJson({ configured: true, url: 'git@x:y.git', branch: 'main' }))
    const out = await new RestClient('http://x').get('/api/config/repo', contractResponse('RepoConfigView', RepoConfigViewSchema))
    expect(out).toMatchObject({ configured: true })
  })

  it('★ F1 exactly: a 200 LACKING `configured` throws instead of yielding a plausible object', async () => {
    // Previously this returned `{}` cast to RepoConfigView, and `configured === undefined` read as "not set up".
    vi.stubGlobal('fetch', okJson({ url: 'git@x:y.git' }))
    const call = new RestClient('http://x').get('/api/config/repo', contractResponse('RepoConfigView', RepoConfigViewSchema))
    await expect(call).rejects.toBeInstanceOf(ResponseShapeError)
  })

  it('★ a mistyped field is rejected too — not only a missing one', async () => {
    vi.stubGlobal('fetch', okJson({ configured: 'yes' }))
    await expect(
      new RestClient('http://x').get('/api/config/repo', contractResponse('RepoConfigView', RepoConfigViewSchema)),
    ).rejects.toBeInstanceOf(ResponseShapeError)
  })

  it('★ ADDITIVE server fields must NOT break the client — fail-closed, not fail-brittle', async () => {
    // The generated schemas carry `.catchall(z.any())` for exactly this: a server adding a field is normal and
    // must never become a client-wide outage. A validator that forbids growth would be a deploy trap.
    vi.stubGlobal('fetch', okJson({ configured: true, url: 'u', branch: 'b', somethingNew: { nested: 1 } }))
    const out = await new RestClient('http://x').get('/api/config/repo', contractResponse('RepoConfigView', RepoConfigViewSchema))
    expect(out).toMatchObject({ configured: true })
  })

  it('★ the failure reports path:code and DROPS anything value-bearing (no payload exfiltration)', async () => {
    // Same masking rule as the WS boundary (CYP-420 §7.2): a validation message must not become a channel for the
    // payload it rejected — response bodies carry tokens, emails, repo URLs.
    //
    // MEASURED FIRST, and the reason this test is shaped like it is: the installed zod does NOT put the received
    // VALUE in an issue (only expected/code/path/message), so feeding it a secret proves nothing about OUR
    // masking — it would pass with no masking at all. So the validator here throws a ZodError-shaped error whose
    // issues DO carry the value, which is what a different zod version or a custom validator could produce. This
    // exercises the extraction we wrote, not a behaviour zod happens to have today.
    const secret = 'ghp_SUPERSECRET_TOKEN_VALUE'
    const leaky = {
      schema: 'Leaky',
      parse: () => {
        throw Object.assign(new Error(`Invalid input: received ${secret}`), {
          name: 'ZodError',
          issues: [{ path: ['configured'], code: 'invalid_type', received: secret, message: `got ${secret}` }],
        })
      },
    }
    vi.stubGlobal('fetch', okJson({ configured: secret }))
    const err = await new RestClient('http://x').get('/api/config/repo', leaky).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ResponseShapeError)
    const surfaced = `${(err as Error).message} ${JSON.stringify((err as ResponseShapeError).issues)}`
    expect(surfaced).not.toContain(secret) // neither `received` nor the original `message` may travel
    expect(surfaced).toContain('configured') // the PATH is named — what a debugger actually needs
  })

  it('a ResponseShapeError is distinct from a RestError — transport succeeded, the body did not', async () => {
    vi.stubGlobal('fetch', okJson({}))
    const err = await new RestClient('http://x')
      .get('/api/config/repo', contractResponse('RepoConfigView', RepoConfigViewSchema))
      .catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ResponseShapeError)
    expect(err).not.toBeInstanceOf(RestError) // collapsing the two would hide which half broke
  })

  it('an unvalidated call still behaves as before — adoption is per-call-site, not a big-bang', async () => {
    vi.stubGlobal('fetch', okJson({ anything: true }))
    await expect(new RestClient('http://x').get('/api/whatever')).resolves.toMatchObject({ anything: true })
  })

  it('★ COVERAGE, visible not assumed: the honesty-critical reads are validated, the rest is counted', () => {
    // A partial rollout is fine; a partial rollout that LOOKS complete is not. This prints the remaining gap
    // instead of leaving it to memory, and fails if a validated read silently loses its validator.
    const repo = readFileSync(resolve(process.cwd(), 'src/state/restRepo.ts'), 'utf8')
    const mustValidate = ['RepoConfigView', 'ApiKeyView', 'AuthMe', 'Agent[]', 'ChannelReadState[]', 'Capacity']
    for (const schema of mustValidate) {
      expect(repo).toContain(`contractResponse('${schema}'`)
    }
    const validated = (repo.match(/contractResponse\(/g) ?? []).length
    const bareGets = (repo.match(/this\.rest\.(get|post|put|delete)</g) ?? []).length
    // Not an assertion on the remainder — a record of it, so the next reader sees the real state.
    console.info(`[CYP-737] validated reads: ${validated}; still-cast call sites: ${bareGets}`)
    expect(validated).toBeGreaterThanOrEqual(mustValidate.length)
  })
})
