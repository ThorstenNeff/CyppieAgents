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
})
