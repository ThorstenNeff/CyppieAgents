// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { useState } from 'react'
import { render, fireEvent, cleanup } from '@testing-library/react'
import { Composer } from './Composer'

afterEach(cleanup) // no globals → RTL auto-cleanup isn't registered; unmount between tests so queries don't collide

// CYP-890: the draft is controlled — a tiny stateful harness stands in for the shared VM (agentVmStore) here.
function Harness({ onSend, disabled, disabledReason }: { onSend: (t: string) => void; disabled?: boolean; disabledReason?: string }) {
  const [draft, setDraft] = useState('')
  return <Composer onSend={onSend} historySize={() => 20} draft={draft} onDraftChange={setDraft} disabled={disabled} disabledReason={disabledReason} />
}

describe('Composer (CYP-403)', () => {
  it('Enter sends the trimmed draft and clears; a blank draft is ignored', () => {
    const onSend = vi.fn()
    const { getByTestId } = render(<Harness onSend={onSend} />)
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
    const { getByTestId } = render(<Harness onSend={onSend} />)
    const input = getByTestId('composer-input') as HTMLInputElement

    fireEvent.change(input, { target: { value: 'first' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    fireEvent.keyDown(input, { key: 'ArrowUp' })
    expect(input.value).toBe('first')
  })

  it('★ CYP-890 read-only (disabled): the input is disabled, a reason hint shows, and Enter does NOT send', () => {
    // MUT: ignore `disabled` (always enabled) → the input is editable + Enter sends → this reds. The disabled composer
    // is the honest CLIENT hint of the server truth (PRODUCT_LEAD has canWrite=false; the server 403s the send).
    const onSend = vi.fn()
    const { getByTestId } = render(<Harness onSend={onSend} disabled disabledReason="Read-only-Reviewer" />)
    const input = getByTestId('composer-input') as HTMLInputElement
    expect(input.disabled).toBe(true)
    expect(getByTestId('composer-readonly-hint').textContent).toBe('Read-only-Reviewer')
    fireEvent.change(input, { target: { value: 'blocked' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onSend).not.toHaveBeenCalled()
  })
})
