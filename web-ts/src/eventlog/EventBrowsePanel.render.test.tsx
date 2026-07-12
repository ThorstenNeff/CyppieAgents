// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, fireEvent, act, waitFor } from '@testing-library/react'
import { EventBrowsePanel } from './EventBrowsePanel'
import { RestError } from '../net/rest'
import type { EventPage, EventSurrogate } from '../types/generated/contract'
import type { EventFilter } from './eventBrowse'

const ev = (id: string, seq: number, over: Partial<EventSurrogate> = {}): EventSurrogate => ({
  id,
  seq,
  ts: seq,
  agentId: 'backend',
  projectId: 'p',
  type: 'tool.call',
  severity: 'info',
  ...over,
})
const page = (events: EventSurrogate[], over: Partial<EventPage> = {}): EventPage => ({ events, hasMore: false, ...over })

afterEach(cleanup)

describe('EventBrowsePanel (CYP-452)', () => {
  it('loads the first page and renders rows via the shared EventRow', async () => {
    const getEvents = vi.fn().mockResolvedValue(page([ev('a', 1), ev('b', 2)]))
    const { findByTestId } = render(<EventBrowsePanel getEvents={getEvents} agentIds={['backend']} />)
    expect(await findByTestId('eventBrowse.row.0')).toBeTruthy()
    expect(await findByTestId('eventBrowse.row.1')).toBeTruthy()
    expect(getEvents).toHaveBeenCalledWith(expect.anything(), null, expect.any(Number)) // first page: afterSeq null
  })

  it('a filter chip tap fires a NEW server query with the changed filter (no client post-filter, tooth 2)', async () => {
    const getEvents = vi.fn().mockResolvedValue(page([ev('a', 1)]))
    const { getByTestId, findByTestId } = render(<EventBrowsePanel getEvents={getEvents} agentIds={['backend']} />)
    await findByTestId('eventBrowse.row.0')
    await act(async () => {
      fireEvent.click(getByTestId('eventBrowse.filter.severity')) // null → debug
    })
    // a new query ran, carrying the server-side severity axis
    await waitFor(() => {
      const lastFilter = getEvents.mock.calls[getEvents.mock.calls.length - 1][0] as EventFilter
      expect(lastFilter.severity).toBe('debug')
    })
    expect(await findByTestId('eventBrowse.filterActive')).toBeTruthy() // subset-cue (tooth 3)
  })

  it('a failed FIRST page shows error+retry, NOT the empty state (error beats empty, tooth 4)', async () => {
    const getEvents = vi.fn().mockRejectedValue(new RestError(500, 'GET', '/api/events', ''))
    const { findByTestId, queryByTestId } = render(<EventBrowsePanel getEvents={getEvents} agentIds={[]} />)
    expect(await findByTestId('eventBrowse.error')).toBeTruthy()
    expect(await findByTestId('eventBrowse.error.retry')).toBeTruthy()
    expect(queryByTestId('eventBrowse.empty')).toBeNull() // never the "no events" state on a failure
  })

  it('a 403 on load fails closed to the revoked lock, no rows (runtime access revoke)', async () => {
    const getEvents = vi.fn().mockRejectedValue(new RestError(403, 'GET', '/api/events', ''))
    const { findByTestId, queryByTestId } = render(<EventBrowsePanel getEvents={getEvents} agentIds={[]} />)
    expect(await findByTestId('eventBrowse.accessRevoked')).toBeTruthy()
    expect(queryByTestId('eventBrowse.table')).toBeNull()
  })

  it('drilldown: showRun/showSession enabled ONLY when the field is present, never conflated (tooth 1)', async () => {
    // one event has only correlationId, another only sessionId
    const getEvents = vi.fn().mockResolvedValue(page([ev('run', 1, { correlationId: 'c1' }), ev('sess', 2, { sessionId: 's1' })]))
    const { getByTestId, findByTestId } = render(<EventBrowsePanel getEvents={getEvents} agentIds={[]} />)
    // select the correlationId-only event → showRun enabled, showSession disabled
    fireEvent.click(await findByTestId('eventBrowse.row.0'))
    expect((getByTestId('eventBrowse.detail.showRun') as HTMLButtonElement).disabled).toBe(false)
    expect((getByTestId('eventBrowse.detail.showSession') as HTMLButtonElement).disabled).toBe(true)
    // select the sessionId-only event → the opposite (no conflation)
    fireEvent.click(getByTestId('eventBrowse.row.1'))
    expect((getByTestId('eventBrowse.detail.showRun') as HTMLButtonElement).disabled).toBe(true)
    expect((getByTestId('eventBrowse.detail.showSession') as HTMLButtonElement).disabled).toBe(false)
  })

  it('clicking showRun re-queries on the correlation axis (drilldown = single-axis server query)', async () => {
    const getEvents = vi.fn().mockResolvedValue(page([ev('run', 1, { correlationId: 'c9' })]))
    const { getByTestId, findByTestId } = render(<EventBrowsePanel getEvents={getEvents} agentIds={[]} />)
    fireEvent.click(await findByTestId('eventBrowse.row.0'))
    await act(async () => {
      fireEvent.click(getByTestId('eventBrowse.detail.showRun'))
    })
    await waitFor(() => {
      const lastFilter = getEvents.mock.calls[getEvents.mock.calls.length - 1][0] as EventFilter
      expect(lastFilter.correlationId).toBe('c9')
      expect(lastFilter.sessionId).toBe(null) // never the other axis
    })
    expect(getByTestId('eventBrowse.drilldown.header')).toBeTruthy()
  })

  it('an honest empty (successful, zero events) shows the empty state', async () => {
    const getEvents = vi.fn().mockResolvedValue(page([]))
    const { findByTestId } = render(<EventBrowsePanel getEvents={getEvents} agentIds={[]} />)
    expect(await findByTestId('eventBrowse.empty')).toBeTruthy()
  })

  it('CYP-489: a parent re-render (new inline getEvents) does NOT refetch; a later filter change uses the latest fn', async () => {
    const getEvents1 = vi.fn().mockResolvedValue(page([ev('a', 1)]))
    const { rerender, getByTestId, findByTestId } = render(<EventBrowsePanel getEvents={getEvents1} agentIds={['backend']} />)
    await findByTestId('eventBrowse.row.0')
    const callsAfterLoad = getEvents1.mock.calls.length // first page load
    // parent re-renders, handing a brand-new closure with the same behaviour → must NOT re-key the effect / refetch
    const getEvents2 = vi.fn().mockResolvedValue(page([ev('a', 1)]))
    await act(async () => {
      rerender(<EventBrowsePanel getEvents={getEvents2} agentIds={['backend']} />)
    })
    expect(getEvents1.mock.calls.length).toBe(callsAfterLoad)
    expect(getEvents2).not.toHaveBeenCalled()
    // …but a real query change now uses the LATEST fn (the ref stayed current)
    await act(async () => {
      fireEvent.click(getByTestId('eventBrowse.filter.severity'))
    })
    await waitFor(() => expect(getEvents2).toHaveBeenCalled())
  })
})

describe('EventBrowsePanel — CYP-467 parity (type/project/timeWindow chips + typed summaries + single-pane)', () => {
  const lastFilter = (getEvents: ReturnType<typeof vi.fn>): EventFilter =>
    getEvents.mock.calls[getEvents.mock.calls.length - 1][0] as EventFilter

  it('the type chip tap runs a NEW server query carrying the type axis (same rule as agent/severity)', async () => {
    const getEvents = vi.fn().mockResolvedValue(page([ev('a', 1)]))
    const { getByTestId, findByTestId } = render(<EventBrowsePanel getEvents={getEvents} agentIds={['backend']} />)
    await findByTestId('eventBrowse.row.0')
    await act(async () => {
      fireEvent.click(getByTestId('eventBrowse.filter.type')) // null → first curated type
    })
    await waitFor(() => expect(lastFilter(getEvents).type).toBe('turn.start'))
  })

  it('the timeWindow parity marker is present but non-interactive (discoverable axis, no fabricated control)', async () => {
    const getEvents = vi.fn().mockResolvedValue(page([]))
    const { findByTestId } = render(<EventBrowsePanel getEvents={getEvents} agentIds={[]} />)
    const marker = await findByTestId('eventBrowse.filter.timeWindow')
    expect(marker.tagName).toBe('SPAN') // not a button — deliberately no picker yet
  })

  it('no project chip when the operator has no project list; present + cycling to the cross-project query when they do', async () => {
    const noProj = vi.fn().mockResolvedValue(page([]))
    const { queryByTestId, unmount } = render(<EventBrowsePanel getEvents={noProj} agentIds={[]} />)
    await waitFor(() => expect(noProj).toHaveBeenCalled())
    expect(queryByTestId('eventBrowse.filter.project')).toBeNull() // no list → no chip
    unmount()

    const getEvents = vi.fn().mockResolvedValue(page([ev('a', 1)]))
    const { getByTestId, findByTestId } = render(
      <EventBrowsePanel
        getEvents={getEvents}
        agentIds={['backend']}
        projects={[{ id: 'team-1', name: 'Team One' }, { id: 'team-2', name: 'Team Two' }]}
        activeProjectId="team-1"
      />,
    )
    await findByTestId('eventBrowse.row.0')
    // null(active) → the OTHER project (team-2), server-side query carries it
    await act(async () => {
      fireEvent.click(getByTestId('eventBrowse.filter.project'))
    })
    await waitFor(() => expect(lastFilter(getEvents).projectId).toBe('team-2'))
    expect(await findByTestId('eventBrowse.crossProjectView')).toBeTruthy() // never misread as the active project
  })

  it('a compact.orchestration.done detail shows the X/N summary — WARN amber on a timeout, neutral on a full run', async () => {
    const timeout = ev('t', 1, { type: 'compact.orchestration.done', detail: { completed: 3, total: 5, pendingAgentIds: ['a', 'b'] } })
    const full = ev('f', 2, { type: 'compact.orchestration.done', detail: { completed: 5, total: 5, pendingAgentIds: [] } })
    const getEvents = vi.fn().mockResolvedValue(page([timeout, full]))
    const { getByTestId, findByTestId } = render(<EventBrowsePanel getEvents={getEvents} agentIds={['backend']} />)
    await findByTestId('eventBrowse.row.0')
    await act(async () => {
      fireEvent.click(getByTestId('eventBrowse.row.0'))
    })
    const s1 = await findByTestId('eventBrowse.detail.compactSummary')
    expect(s1.textContent).toContain('3/5')
    expect(s1.textContent).toContain('Timeout')
    expect(s1.className).toContain('event-browse-summary-warn') // amber (and text carries the meaning)

    await act(async () => {
      fireEvent.click(getByTestId('eventBrowse.row.1'))
    })
    const s2 = await findByTestId('eventBrowse.detail.compactSummary')
    expect(s2.textContent).toContain('5/5')
    expect(s2.className).not.toContain('event-browse-summary-warn') // a full run is neutral — never amber, never green
  })

  it('a resume.outcome detail shows CONTEXT_LOST as WARN amber; an unknown outcome shows no fabricated summary', async () => {
    const lost = ev('l', 1, { type: 'resume.outcome', detail: { outcome: 'CONTEXT_LOST' } })
    const unknown = ev('u', 2, { type: 'resume.outcome', detail: { outcome: 'SOMETHING_NEW' } })
    const getEvents = vi.fn().mockResolvedValue(page([lost, unknown]))
    const { getByTestId, findByTestId, queryByTestId } = render(<EventBrowsePanel getEvents={getEvents} agentIds={['backend']} />)
    await findByTestId('eventBrowse.row.0')
    await act(async () => {
      fireEvent.click(getByTestId('eventBrowse.row.0'))
    })
    const s = await findByTestId('eventBrowse.detail.resumeOutcome')
    expect(s.textContent).toContain('Kontext verloren')
    expect(s.className).toContain('event-browse-summary-warn')

    await act(async () => {
      fireEvent.click(getByTestId('eventBrowse.row.1'))
    })
    await findByTestId('eventBrowse.detail')
    expect(queryByTestId('eventBrowse.detail.resumeOutcome')).toBeNull() // unknown → no summary
  })

  it('single-pane (< PANE_COLLAPSE_WIDTH): a selected event REPLACES the master; Back returns to it', async () => {
    const cbs: Array<(e: unknown) => void> = []
    class FakeRO {
      constructor(cb: (e: unknown) => void) {
        cbs.push(cb)
      }
      observe() {}
      disconnect() {}
    }
    ;(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = FakeRO
    try {
      const getEvents = vi.fn().mockResolvedValue(page([ev('a', 1, { correlationId: 'run-1' })]))
      const { getByTestId, findByTestId, queryByTestId } = render(<EventBrowsePanel getEvents={getEvents} agentIds={['backend']} />)
      await findByTestId('eventBrowse.row.0')
      // go narrow — but with nothing selected the master + filter bar still show
      await act(async () => {
        cbs.forEach((cb) => cb([{ contentRect: { width: 400 } }]))
      })
      expect(getByTestId('eventBrowse.filterBar')).toBeTruthy()
      // select → detail REPLACES the master (filter bar + table gone), only the detail remains
      await act(async () => {
        fireEvent.click(getByTestId('eventBrowse.row.0'))
      })
      expect(await findByTestId('eventBrowse.detail')).toBeTruthy()
      expect(queryByTestId('eventBrowse.filterBar')).toBeNull()
      expect(queryByTestId('eventBrowse.table')).toBeNull()
      // Back returns to the master
      await act(async () => {
        fireEvent.click(getByTestId('eventBrowse.back'))
      })
      expect(await findByTestId('eventBrowse.filterBar')).toBeTruthy()
      expect(await findByTestId('eventBrowse.table')).toBeTruthy()
    } finally {
      delete (globalThis as unknown as { ResizeObserver?: unknown }).ResizeObserver
    }
  })

  it('two-pane (wide): a selected event sits BESIDE the master, both present', async () => {
    const cbs: Array<(e: unknown) => void> = []
    class FakeRO {
      constructor(cb: (e: unknown) => void) {
        cbs.push(cb)
      }
      observe() {}
      disconnect() {}
    }
    ;(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = FakeRO
    try {
      const getEvents = vi.fn().mockResolvedValue(page([ev('a', 1)]))
      const { getByTestId, findByTestId } = render(<EventBrowsePanel getEvents={getEvents} agentIds={['backend']} />)
      await findByTestId('eventBrowse.row.0')
      await act(async () => {
        cbs.forEach((cb) => cb([{ contentRect: { width: 900 } }]))
      })
      await act(async () => {
        fireEvent.click(getByTestId('eventBrowse.row.0'))
      })
      expect(getByTestId('eventBrowse.filterBar')).toBeTruthy() // master chrome stays
      expect(getByTestId('eventBrowse.table')).toBeTruthy()
      expect(getByTestId('eventBrowse.detail')).toBeTruthy() // detail beside it
    } finally {
      delete (globalThis as unknown as { ResizeObserver?: unknown }).ResizeObserver
    }
  })
})
