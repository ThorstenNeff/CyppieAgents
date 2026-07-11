// @vitest-environment jsdom
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { ContentViewSwitch } from './ContentViewSwitch'

afterEach(cleanup)

describe('ContentViewSwitch (CYP-406 — mount both, toggle visibility, never unmount)', () => {
  it('keeps BOTH views mounted; only the inactive one is hidden; toggling flips hidden without unmounting', () => {
    const orchestration = <div data-testid="orch-body">ORCH</div>
    const shell = <div data-testid="shell-body">SHELL</div>

    const { queryByTestId, getByTestId, rerender } = render(
      <ContentViewSwitch active="orchestration" orchestration={orchestration} shell={shell} />,
    )
    // both mounted from the start (the retention rule)
    expect(queryByTestId('orch-body')).not.toBeNull()
    expect(queryByTestId('shell-body')).not.toBeNull()
    expect(getByTestId('view-orchestration').hasAttribute('hidden')).toBe(false)
    expect(getByTestId('view-shell').hasAttribute('hidden')).toBe(true)

    rerender(<ContentViewSwitch active="shell" orchestration={orchestration} shell={shell} />)
    // still BOTH mounted (no unmount on toggle) — only `hidden` flipped
    expect(queryByTestId('orch-body')).not.toBeNull()
    expect(queryByTestId('shell-body')).not.toBeNull()
    expect(getByTestId('view-orchestration').hasAttribute('hidden')).toBe(true)
    expect(getByTestId('view-shell').hasAttribute('hidden')).toBe(false)
  })
})
