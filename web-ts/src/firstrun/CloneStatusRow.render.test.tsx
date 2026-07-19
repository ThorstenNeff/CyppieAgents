// @vitest-environment jsdom
// CYP-735 §3.3 — render teeth for the clone lifecycle row (UIUX2 screen-spec 12924c37 §1/§2/§5).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { CloneStatusRow } from './CloneStatusRow'
import { cloneView, CLONE_SLOW_MS, CLONE_LONG_MS } from './cloneStatusModel'
import type { RepoConfigView } from '../types/generated/contract'

const cfg = (over: Partial<RepoConfigView> = {}): RepoConfigView => ({ configured: true, ...over })
const row = (config: RepoConfigView | null, elapsed = 0) =>
  render(<CloneStatusRow view={cloneView(config, elapsed)} onRetry={vi.fn()} />)

beforeEach(cleanup)

describe('CYP-735 §3.3 — the row says exactly what is known, and nothing more', () => {
  it('a cloned repo reports success (non-vacuous control)', () => {
    expect(row(cfg({ cloneStatus: 'CLONED_OK' })).getByTestId('firstrun.repo.cloneStatus.ok')).toBeTruthy()
  })

  it('★ an UNKNOWN status never renders as OK — absence is not reassurance', () => {
    const { getByTestId, queryByTestId } = row(cfg())
    expect(getByTestId('firstrun.repo.cloneUnknown')).toBeTruthy()
    expect(queryByTestId('firstrun.repo.cloneStatus.ok')).toBeNull()
  })

  it('★ "saved" says the clone is OUTSTANDING — never that the repo works', () => {
    const { getByTestId, queryByTestId } = row(cfg({ cloneStatus: 'CONFIGURED_NEVER_CLONED' }))
    expect(getByTestId('firstrun.repo.cloneStatus.configuredNeverCloned').textContent).toContain('steht aus')
    expect(queryByTestId('firstrun.repo.cloneStatus.ok')).toBeNull()
  })

  it('★ a long-running clone stays CLONING and states its measured DURATION, not a verdict', () => {
    // Not a spinner (asserts unseen progress) and not an alarm (asserts an unobserved failure) — a fact.
    const { getByTestId, queryByTestId } = row(cfg({ cloneStatus: 'CLONING' }), CLONE_LONG_MS)
    expect(getByTestId('firstrun.repo.cloneStatus.cloning')).toBeTruthy() // same phase
    expect(getByTestId('firstrun.repo.cloneSlowHint').textContent).toMatch(/dauert schon \d+ min/)
    expect(queryByTestId('firstrun.repo.cloneStatus.failed')).toBeNull() // never a fabricated failure
    expect(queryByTestId('firstrun.repo.cloneStatus.ok')).toBeNull()
  })

  it('★ the slow hint is advisory and keeps the SAME tone as cloning — a slow clone has not failed', () => {
    const { getByTestId, container } = row(cfg({ cloneStatus: 'CLONING' }), CLONE_SLOW_MS)
    expect(getByTestId('firstrun.repo.cloneSlowHint')).toBeTruthy()
    expect(container.querySelector('.clone-cloning')).toBeTruthy() // not .clone-failed
  })

  it('★ each failure reason names its own fix; UNKNOWN stays honestly undetermined', () => {
    const cases = [
      ['AUTH', 'firstrun.repo.cloneFailReason.auth', /Token|SSH/],
      ['URL_UNREACHABLE', 'firstrun.repo.cloneFailReason.urlUnreachable', /URL/],
      ['UNKNOWN', 'firstrun.repo.cloneFailReason.unknown', /nicht ermittelbar/],
    ] as const
    for (const [reason, tag, copy] of cases) {
      cleanup()
      const { getByTestId } = row(cfg({ cloneStatus: 'CLONE_FAILED', cloneFailReason: reason }))
      expect(getByTestId(tag).textContent).toMatch(copy)
      expect(getByTestId('firstrun.repo.cloneRetry')).toBeTruthy() // every failure is actionable
    }
  })

  it('★ a failure with NO reason renders the undetermined copy — never guessed into auth/url', () => {
    // Guessing would send the operator to rotate a working token or edit a correct URL.
    const { getByTestId, queryByTestId } = row(cfg({ cloneStatus: 'CLONE_FAILED' }))
    expect(getByTestId('firstrun.repo.cloneFailReason.unknown')).toBeTruthy()
    expect(queryByTestId('firstrun.repo.cloneFailReason.auth')).toBeNull()
    expect(queryByTestId('firstrun.repo.cloneFailReason.urlUnreachable')).toBeNull()
  })

  it('an unconfigured repo renders nothing — the step is simply open', () => {
    expect(row(cfg({ cloneStatus: 'NOT_CONFIGURED' })).container.firstChild).toBeNull()
  })
})
