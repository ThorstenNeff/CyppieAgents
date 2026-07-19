// @vitest-environment jsdom
// CYP-735 §3.2 — render teeth for the guided first-run gate (UIUX2 screen-spec 951823ab §6).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { FirstRunGate } from './FirstRunGate'
import type { FirstRunInputs } from './firstRunModel'
import type { SetupStatus } from '../workspace/setupStatus'

const inputs = (over: Partial<FirstRunInputs> = {}): FirstRunInputs => ({
  setup: { kind: 'configured' } as SetupStatus,
  apiKeySet: true,
  repoCloned: true,
  ...over,
})

const renderGate = (over: Partial<FirstRunInputs> = {}, onSkip = vi.fn()) =>
  render(
    <FirstRunGate
      inputs={inputs(over)}
      apiKeyStep={<div data-testid="stub.apikey" />}
      repoStep={<div data-testid="stub.repo" />}
      onSkip={onSkip}
      onRetry={() => undefined}
      loadErrorSurface={<div data-testid="stub.loadError" />}
    >
      <div data-testid="stub.workspace" />
    </FirstRunGate>,
  )

beforeEach(cleanup)

describe('CYP-735 §3.2 — the gate guides without ever claiming more than it knows', () => {
  it('both core steps done → transparent: the workspace, no gate (non-vacuous control)', () => {
    const { getByTestId, queryByTestId } = renderGate()
    expect(getByTestId('stub.workspace')).toBeTruthy()
    expect(queryByTestId('firstrun.gate')).toBeNull()
  })

  it('★ ⑥.1 an unresolved config is a LOAD surface — never a step, never the workspace', () => {
    for (const kind of ['unknown', 'error'] as const) {
      cleanup()
      const { getByTestId, queryByTestId } = renderGate({ setup: { kind } as SetupStatus })
      expect(getByTestId('firstrun.gate').dataset.mode).toBe('loading')
      expect(getByTestId('stub.loadError')).toBeTruthy()
      expect(queryByTestId('firstrun.step.apikey')).toBeNull() // not "step 1"
      expect(queryByTestId('stub.workspace')).toBeNull() // and not passed through either
    }
  })

  it('★ ⑥.2 a SAVED repo shows "Gespeichert", not "Erledigt" — and the gate stays active', () => {
    const { getByTestId } = renderGate({ setup: { kind: 'configured' } as SetupStatus, repoCloned: null })
    expect(getByTestId('firstrun.gate').dataset.mode).toBe('active')
    expect(getByTestId('firstrun.step.repo.status').dataset.state).toBe('saved')
    expect(getByTestId('firstrun.step.repo.status').textContent).toBe('Gespeichert')
  })

  it('★ ⑥.3 an unresolved API key is "Offen", never done', () => {
    const { getByTestId } = renderGate({ apiKeySet: null, repoCloned: null })
    expect(getByTestId('firstrun.step.apikey.status').dataset.state).toBe('open')
  })

  it('★ ⑥.5 the team step carries no form and never blocks — it is informational only', () => {
    const { getByTestId, container } = renderGate({ repoCloned: null })
    const team = getByTestId('firstrun.step.team')
    expect(team).toBeTruthy()
    expect(team.querySelector('input, form, button')).toBeNull() // nothing to submit, nothing to block on
    expect(container.querySelector('[data-testid="firstrun.skip"]')).toBeTruthy() // and skip is always available
  })

  it('★ ⑥.4 skip is offered WITH its consequence stated — never a bare escape hatch', () => {
    const onSkip = vi.fn()
    const { getByTestId } = renderGate({ repoCloned: null }, onSkip)
    getByTestId('firstrun.skip').click()
    expect(onSkip).toHaveBeenCalledTimes(1)
    // the note tells the user what skipping costs: the hub runs but cannot start agents.
    expect(getByTestId('firstrun.gate').textContent).toContain('kann aber keine Agenten starten')
  })

  it('the steps reuse the existing panels rather than re-implementing them', () => {
    const { getByTestId } = renderGate({ repoCloned: null })
    expect(getByTestId('stub.apikey')).toBeTruthy()
    expect(getByTestId('stub.repo')).toBeTruthy()
  })

  it('★ the copy is the SHARED CMP string, byte-for-byte — a near-miss on a shared key is still a miss', () => {
    // Guards the drift that shipped in §3.1: the parity spec's prose paraphrased the real string, and building
    // from the paraphrase made the two strands say different things. Compared against strings.xml itself.
    const xml = readFileSync(resolve(process.cwd(), '../app/shared/src/commonMain/composeResources/values/strings.xml'), 'utf8')
    const shared = (key: string) => new RegExp(`<string name="${key}">([^<]*)</string>`).exec(xml)?.[1]
    const decode = (s: string | undefined) => s?.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
    const { getByTestId } = renderGate({ repoCloned: null })
    expect(getByTestId('firstrun.gate').textContent).toContain(decode(shared('first_run_intro')))
    expect(getByTestId('firstrun.skip').textContent).toBe(decode(shared('first_run_skip')))
  })
})
