// @vitest-environment jsdom
import { describe, it, expect, afterEach } from 'vitest'
import { render, cleanup } from '@testing-library/react'
import { EventLogView } from './EventLogView'
import type { EventSurrogate } from '../types/generated/contract'

afterEach(cleanup)

const ev = (id: string, seq: number, over: Partial<EventSurrogate> = {}): EventSurrogate => ({
  id,
  seq,
  ts: seq,
  agentId: 'backend',
  projectId: 'p',
  type: 'agent.activity',
  severity: 'info',
  ...over,
})

describe('EventLogView (CYP-432)', () => {
  it('shows the empty state, then event rows with severity glyph+label, type (monospace) and agent', () => {
    const empty = render(<EventLogView events={[]} caughtUp={false} />)
    expect(empty.queryByTestId('event-log-empty')).not.toBeNull()
    cleanup()
    const view = render(<EventLogView events={[ev('a', 1, { severity: 'error', type: 'errors.spawn' })]} caughtUp={true} />)
    const row = view.getByTestId('event.row.a')
    expect(row.textContent).toContain('⚠') // severity glyph (colour never alone)
    expect(row.textContent).toContain('Fehler') // severity label
    expect(row.textContent).toContain('errors.spawn') // type as text
    expect(row.textContent).toContain('backend') // agent identity
  })

  it('renders an explicit amber gap row for a dropped seq — never a silent skip', () => {
    const { getByTestId } = render(<EventLogView events={[ev('a', 1), ev('c', 3)]} caughtUp={true} />)
    const gap = getByTestId('event.gap.1')
    expect(gap.textContent).toContain('verworfen')
    expect(gap.getAttribute('role')).toBe('alert')
  })

  it('the status reflects replay vs live (Caughtup)', () => {
    const replaying = render(<EventLogView events={[]} caughtUp={false} />)
    expect(replaying.getByTestId('event-log-status').textContent).toContain('Verlauf lädt')
    cleanup()
    const live = render(<EventLogView events={[]} caughtUp={true} />)
    expect(live.getByTestId('event-log-status').textContent).toContain('Live')
  })
})
