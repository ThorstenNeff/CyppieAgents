// @vitest-environment jsdom
// CYP-288 — the shared load-error+retry primitive: renders an alert + a retry button that fires onRetry; derives the
// `.retry` testid from the wrapper testid.
import { describe, it, expect, afterEach, vi } from 'vitest'
import { render, fireEvent, cleanup } from '@testing-library/react'
import { LoadErrorRetry, LOAD_ERROR_RETRY_LABEL } from './LoadErrorRetry'

afterEach(cleanup)

describe('LoadErrorRetry (CYP-288 shared primitive)', () => {
  it('renders a role=alert error + a retry button; retry fires onRetry', () => {
    const onRetry = vi.fn()
    const { getByTestId } = render(<LoadErrorRetry testId="demo.loadError" onRetry={onRetry} />)
    const box = getByTestId('demo.loadError')
    expect(box.getAttribute('role')).toBe('alert') // failed load is announced, not silent
    const retry = getByTestId('demo.loadError.retry') // testid derived from the wrapper
    expect(retry.textContent).toBe(LOAD_ERROR_RETRY_LABEL)
    fireEvent.click(retry)
    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})
