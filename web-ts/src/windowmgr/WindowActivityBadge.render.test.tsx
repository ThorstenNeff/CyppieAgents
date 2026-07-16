// @vitest-environment jsdom
// CYP-641 — render teeth for the activity badge. jsdom computes no layout, but presence/absence of atoms + their
// a11y labels ARE checkable here. The StrictMode test is the "kein Doppel-Mount" proof the coordinator asked for:
// under React StrictMode (mount→unmount→mount, effects double-invoked in dev) the rendered atoms must appear
// EXACTLY ONCE — a stray effect-driven side node would duplicate.
//
// Queries are scoped to the returned `container` (not the global screen) — this repo's vitest config registers no
// auto-cleanup between renders, so a document-wide getByTestId would see prior tests' nodes (matches the
// WindowFrame.render.test container-scoped pattern).
import { StrictMode } from 'react'
import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { WindowActivityBadge } from './WindowActivityBadge'
import type { WindowActivity } from './activityBadge'

const busy: WindowActivity = { attention: false, busy: true, contextTokens: 12345 }
const attention: WindowActivity = { attention: true, busy: false, contextTokens: null }
const q = (c: HTMLElement, id: string) => c.querySelector(`[data-testid="${id}"]`)

describe('WindowActivityBadge (render)', () => {
  it('busy + token count → both atoms, no attention; token compact-formatted; a11y labels name the window', () => {
    const { container } = render(<WindowActivityBadge title="Backend" activity={busy} />)
    expect(q(container, 'window-activity.tokens')?.textContent).toBe('12.3k')
    expect(q(container, 'window-activity.tokens')?.getAttribute('aria-label')).toContain('Backend')
    expect(q(container, 'window-activity.busy')?.getAttribute('aria-label')).toBe('Backend: arbeitet')
    expect(q(container, 'window-activity.attention')).toBeNull()
  })

  it('attention (ERROR) → the warning atom with the error a11y label; busy/token suppressed', () => {
    const { container } = render(<WindowActivityBadge title="PO" activity={attention} />)
    expect(q(container, 'window-activity.attention')?.getAttribute('aria-label')).toBe('PO: Agent-Fehler')
    expect(q(container, 'window-activity.busy')).toBeNull()
    expect(q(container, 'window-activity.tokens')).toBeNull()
  })

  it('token count 0 still renders (an explicit 0 is a real number, not "unknown")', () => {
    const { container } = render(
      <WindowActivityBadge title="X" activity={{ attention: false, busy: false, contextTokens: 0 }} />,
    )
    expect(q(container, 'window-activity.tokens')?.textContent).toBe('0')
  })

  it('StrictMode renders each atom EXACTLY once (kein Doppel-Mount)', () => {
    const { container } = render(
      <StrictMode>
        <WindowActivityBadge title="Backend" activity={busy} />
      </StrictMode>,
    )
    expect(container.querySelectorAll('[data-testid="window-activity"]')).toHaveLength(1)
    expect(container.querySelectorAll('[data-testid="window-activity.busy"]')).toHaveLength(1)
    expect(container.querySelectorAll('[data-testid="window-activity.tokens"]')).toHaveLength(1)
  })
})
