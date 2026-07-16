// @vitest-environment jsdom
// CYP-646 — render teeth for the Count/Severity window badge. Container-scoped (no auto-cleanup in this repo).
import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { WindowBadge } from './WindowBadge'

const q = (c: HTMLElement, id: string) => c.querySelector(`[data-testid="${id}"]`)

describe('WindowBadge (render)', () => {
  it('count → a number pill, "9+" overflow, a11y names the window + unread', () => {
    const { container } = render(<WindowBadge title="Kommunikation" badge={{ kind: 'count', count: 12 }} />)
    const b = q(container, 'window-badge.count')
    expect(b?.textContent).toBe('9+')
    expect(b?.getAttribute('aria-label')).toContain('Kommunikation')
    expect(b?.getAttribute('aria-label')).toContain('12')
  })

  it('severity → a glyph pill carrying the event-sev tone class + a11y severity label', () => {
    const { container } = render(<WindowBadge title="Ereignis-Protokoll" badge={{ kind: 'severity', severity: 'error' }} />)
    const b = q(container, 'window-badge.severity')
    expect(b?.classList.contains('event-sev-error')).toBe(true) // tone via the shared severity class
    expect(b?.getAttribute('aria-label')).toContain('Schweregrad')
    expect(b?.querySelector('.event-sev-glyph')).not.toBeNull() // the glyph shape carries the level (not colour alone)
  })
})
