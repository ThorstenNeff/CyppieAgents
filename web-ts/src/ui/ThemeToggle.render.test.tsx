// @vitest-environment jsdom
// CYP-643 — render teeth for the theme toggle. Container-scoped queries (no auto-cleanup in this repo's config).
import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent } from '@testing-library/react'
import { ThemeToggle } from './ThemeToggle'

const q = (c: HTMLElement, id: string) => c.querySelector(`[data-testid="${id}"]`)

describe('ThemeToggle (render)', () => {
  it('renders a labeled select of the three modes, reflecting the active mode', () => {
    const { container } = render(<ThemeToggle mode="dark" onChange={() => {}} />)
    const select = q(container, 'theme-toggle.select') as HTMLSelectElement
    expect(select.getAttribute('aria-label')).toContain('Thema')
    expect(select.value).toBe('dark') // the selected value IS the active-mode marker (parity of CMP's ●)
    expect([...select.options].map((o) => o.value)).toEqual(['system', 'light', 'dark'])
    // colour-never-sole: each option carries a fill-fraction glyph
    expect([...select.options].map((o) => o.textContent)).toEqual(['◐ System', '○ Hell', '● Dunkel'])
  })

  it('fires onChange with the chosen mode', () => {
    const onChange = vi.fn()
    const { container } = render(<ThemeToggle mode="system" onChange={onChange} />)
    const select = q(container, 'theme-toggle.select') as HTMLSelectElement
    fireEvent.change(select, { target: { value: 'light' } })
    expect(onChange).toHaveBeenCalledWith('light')
  })
})
