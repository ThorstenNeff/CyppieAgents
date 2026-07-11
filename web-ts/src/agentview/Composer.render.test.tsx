// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, fireEvent, cleanup } from '@testing-library/react'
import { Composer } from './Composer'

afterEach(cleanup) // no globals → RTL auto-cleanup isn't registered; unmount between tests so queries don't collide

describe('Composer (CYP-403)', () => {
  it('Enter sends the trimmed draft and clears; a blank draft is ignored', () => {
    const onSend = vi.fn()
    const { getByTestId } = render(<Composer onSend={onSend} historySize={() => 20} />)
    const input = getByTestId('composer-input') as HTMLInputElement

    fireEvent.change(input, { target: { value: '  hello  ' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onSend).toHaveBeenCalledWith('hello')
    expect(input.value).toBe('')

    fireEvent.keyDown(input, { key: 'Enter' }) // empty → no send
    expect(onSend).toHaveBeenCalledTimes(1)
  })

  it('ArrowUp recalls the last sent message', () => {
    const onSend = vi.fn()
    const { getByTestId } = render(<Composer onSend={onSend} historySize={() => 20} />)
    const input = getByTestId('composer-input') as HTMLInputElement

    fireEvent.change(input, { target: { value: 'first' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    fireEvent.keyDown(input, { key: 'ArrowUp' })
    expect(input.value).toBe('first')
  })
})
