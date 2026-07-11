// @vitest-environment jsdom
import { describe, it, expect, afterEach } from 'vitest'
import { render, fireEvent, cleanup } from '@testing-library/react'
import { ShellGate } from './ShellGate'

afterEach(cleanup)

const Term = () => <div data-testid="term-stub">TERMINAL</div>

describe('ShellGate (CYP-405 operator gate + open-warning)', () => {
  it('non-operator: operator-only notice, no open affordance, terminal never mounts', () => {
    const { queryByTestId } = render(
      <ShellGate operator={false}>
        <Term />
      </ShellGate>,
    )
    expect(queryByTestId('shell-denied')).not.toBeNull()
    expect(queryByTestId('shell-open')).toBeNull()
    expect(queryByTestId('term-stub')).toBeNull()
  })

  it('operator: open → WARNING → confirm mounts the terminal; close unmounts; reopen warns AGAIN', () => {
    const { queryByTestId, getByTestId } = render(
      <ShellGate operator>
        <Term />
      </ShellGate>,
    )
    expect(queryByTestId('term-stub')).toBeNull()

    fireEvent.click(getByTestId('shell-open'))
    expect(queryByTestId('shell-warning')).not.toBeNull() // warning BEFORE the shell
    expect(queryByTestId('term-stub')).toBeNull() // not mounted yet

    fireEvent.click(getByTestId('shell-confirm'))
    expect(queryByTestId('term-stub')).not.toBeNull() // mounted only after acknowledgement

    fireEvent.click(getByTestId('shell-close'))
    expect(queryByTestId('term-stub')).toBeNull() // unmounted on close (disposes the socket/PTY)

    fireEvent.click(getByTestId('shell-open'))
    expect(queryByTestId('shell-warning')).not.toBeNull() // EVERY open shows the warning again
  })

  it('cancel from the warning returns to closed without mounting the terminal', () => {
    const { getByTestId, queryByTestId } = render(
      <ShellGate operator>
        <Term />
      </ShellGate>,
    )
    fireEvent.click(getByTestId('shell-open'))
    fireEvent.click(getByTestId('shell-cancel'))
    expect(queryByTestId('shell-open')).not.toBeNull()
    expect(queryByTestId('term-stub')).toBeNull()
  })
})
