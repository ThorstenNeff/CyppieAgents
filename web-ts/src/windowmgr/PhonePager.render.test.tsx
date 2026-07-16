// @vitest-environment jsdom
// CYP-664 — render teeth for the phone-pager: the PagerIndicator (dots ≤ threshold / "N/M" counter beyond, prev/next
// disabled at the ends, active dot by aria-current, onSelect), and the LOAD-BEARING state-preservation invariant —
// switching the size-class (desktop↔compact) keeps every window mounted, so open windows, focus, and in-progress
// inputs survive. jsdom computes no layout (clientWidth=0), so the size-class is driven via the store's setHost.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, act, cleanup } from '@testing-library/react'
import { WindowHost } from './WindowHost'
import { WindowFrame } from './WindowFrame'
import { PagerIndicator } from './PagerIndicator'
import { pagerView } from './pagerModel'
import { useWindowStore } from './windowStore'
import type { WindowState } from './windowState'

const win = (id: string): WindowState => ({ id, title: id.toUpperCase(), x: 0, y: 0, width: 400, height: 300 })
const pages = (...ids: string[]) => ids.map((id) => ({ id, title: id.toUpperCase() }))

beforeEach(() => {
  useWindowStore.setState({ windows: [], windowOrder: [], host: { width: 1000, height: 800 }, contentIds: new Set() })
})

describe('PagerIndicator', () => {
  it('dots up to threshold: active carries aria-current; a dot tap selects its window', () => {
    const onSelect = vi.fn()
    const { container } = render(<PagerIndicator view={pagerView(pages('comm', 'acl', 'x'), 'acl')} onSelect={onSelect} />)
    const active = container.querySelector('[data-testid="phonePager.indicator.dot.acl"]')!
    expect(active.getAttribute('aria-current')).toBe('page')
    expect(container.querySelector('[data-testid="phonePager.indicator.dot.comm"]')!.getAttribute('aria-current')).toBeNull()
    fireEvent.click(container.querySelector('[data-testid="phonePager.indicator.dot.x"]') as HTMLElement)
    expect(onSelect).toHaveBeenCalledWith('x')
  })
  it('prev/next select the neighbour and disable at the ends', () => {
    const onSelect = vi.fn()
    const { container } = render(<PagerIndicator view={pagerView(pages('a', 'b', 'c'), 'a')} onSelect={onSelect} />)
    expect((container.querySelector('[data-testid="phonePager.prev"]') as HTMLButtonElement).disabled).toBe(true) // at first
    fireEvent.click(container.querySelector('[data-testid="phonePager.next"]') as HTMLElement)
    expect(onSelect).toHaveBeenCalledWith('b')
    cleanup()
    const r2 = render(<PagerIndicator view={pagerView(pages('a', 'b', 'c'), 'c')} onSelect={onSelect} />)
    expect((r2.container.querySelector('[data-testid="phonePager.next"]') as HTMLButtonElement).disabled).toBe(true) // at last
  })
  it('over the threshold → the honest "N / M" counter, not dots', () => {
    const ids = ['a', 'b', 'c', 'd', 'e', 'f', 'g'] // 7 > PAGER_DOT_THRESHOLD (6)
    const { container } = render(<PagerIndicator view={pagerView(pages(...ids), 'd')} onSelect={vi.fn()} />)
    expect(container.querySelector('[data-testid="phonePager.indicator.counter"]')!.textContent).toBe('4 / 7')
    expect(container.querySelector('[data-testid="phonePager.indicator.dot.a"]')).toBeNull()
  })
  it('a single window → no indicator at all', () => {
    const { container } = render(<PagerIndicator view={pagerView(pages('only'), 'only')} onSelect={vi.fn()} />)
    expect(container.querySelector('[data-testid="phonePager.indicator"]')).toBeNull()
  })
})

describe('WindowHost — size-class switch preserves state (load-bearing)', () => {
  it('desktop→compact: pager appears, ALL windows stay mounted, focus + an in-progress input survive', () => {
    act(() => {
      useWindowStore.setState({ windows: [win('a'), win('b')], windowOrder: ['a', 'b'], host: { width: 1000, height: 800 }, contentIds: new Set() })
    })
    render(
      <WindowHost>
        {(w) => (
          <WindowFrame window={w}>
            <input data-testid={`inp-${w.id}`} />
          </WindowFrame>
        )}
      </WindowHost>,
    )
    // the mount effect measured clientWidth=0 → force a real desktop size, then focus 'a' (top of z-order = visible page).
    act(() => useWindowStore.getState().setHost(1000, 800))
    act(() => useWindowStore.getState().focus('a'))
    expect(screen.queryByTestId('phonePager.pager')).toBeNull() // desktop: no pager

    // type into window a (uncontrolled input holds its own DOM value — reset iff the element remounts)
    fireEvent.change(screen.getByTestId('inp-a'), { target: { value: 'in-progress text' } })

    // flip to a compact viewport
    act(() => useWindowStore.getState().setHost(400, 800))

    // pager now present, BOTH windows still mounted (no unmount), and the typed input survived the switch
    expect(screen.queryByTestId('phonePager.pager')).not.toBeNull()
    expect(screen.queryByTestId('phonePager.page.a')).not.toBeNull()
    expect(screen.queryByTestId('phonePager.page.b')).not.toBeNull()
    expect((screen.getByTestId('inp-a') as HTMLInputElement).value).toBe('in-progress text')
    // open windows + focus survived (store-backed): the roster is unchanged, 'a' still the focused/top window
    expect(useWindowStore.getState().windows.map((w) => w.id)).toEqual(['b', 'a'])
  })
})
