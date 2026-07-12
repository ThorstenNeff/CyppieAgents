// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, cleanup, fireEvent, act, waitFor } from '@testing-library/react'
import { ConnectorPicker } from './ConnectorPicker'
import type { ConnectorsView } from '../types/generated/contract'

const view: ConnectorsView = {
  connectors: [
    { kind: 'stream_json', capabilities: { structuredUsage: 'available', toolGranularity: 'available', reliableResult: 'available', rateLimitSignal: 'available', coordination: 'available', kind: 'stream_json' } },
    { kind: 'mcp', capabilities: { structuredUsage: 'limited', toolGranularity: 'limited', reliableResult: 'limited', rateLimitSignal: 'unavailable', coordination: 'limited', kind: 'mcp' } },
  ],
  default: 'stream_json',
}
const okConnectors = () => vi.fn().mockResolvedValue(view)

afterEach(cleanup)

describe('ConnectorPicker (CYP-461)', () => {
  it('A is the preselected default; B is NEVER preselected (tooth 1)', () => {
    const { getByTestId } = render(
      <ConnectorPicker kind="stream_json" initialKind="stream_json" mode="add" editable getConnectors={okConnectors()} onConfirm={vi.fn().mockResolvedValue(undefined)} />,
    )
    expect((getByTestId('connector.picker.streamJson') as HTMLInputElement).checked).toBe(true)
    expect((getByTestId('connector.picker.mcp') as HTMLInputElement).checked).toBe(false)
  })

  it('selecting B does NOT switch — it opens the opt-in dialog; onConfirm not called yet (tooth 2)', () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined)
    const { getByTestId, queryByTestId } = render(
      <ConnectorPicker kind="stream_json" initialKind="stream_json" mode="add" editable getConnectors={okConnectors()} onConfirm={onConfirm} />,
    )
    expect(queryByTestId('connector.optInDialog')).toBeNull()
    fireEvent.click(getByTestId('connector.picker.mcp'))
    expect(getByTestId('connector.optInDialog')).toBeTruthy() // opened, not switched
    expect(onConfirm).not.toHaveBeenCalled()
    expect((getByTestId('connector.picker.mcp') as HTMLInputElement).checked).toBe(false) // still A settled
  })

  it('confirm is disabled until BOTH the preview loads AND risk is acknowledged; then settles B (teeth 2/3)', async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined)
    const { getByTestId, findByTestId } = render(
      <ConnectorPicker kind="stream_json" initialKind="stream_json" mode="add" editable getConnectors={okConnectors()} onConfirm={onConfirm} />,
    )
    fireEvent.click(getByTestId('connector.picker.mcp'))
    await findByTestId('connector.optInDialog.capabilityPreview') // advisory preview visible
    // preview loaded but not acknowledged → still disabled
    expect((getByTestId('connector.optInDialog.confirm') as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(getByTestId('connector.optInDialog.ack'))
    expect((getByTestId('connector.optInDialog.confirm') as HTMLButtonElement).disabled).toBe(false)
    await act(async () => {
      fireEvent.click(getByTestId('connector.optInDialog.confirm'))
    })
    expect(onConfirm).toHaveBeenCalledWith('mcp')
  })

  it('preview-fail-closed: if GET /api/connectors fails, confirm stays disabled even after ack (tooth 3)', async () => {
    const { getByTestId, findByTestId } = render(
      <ConnectorPicker kind="stream_json" initialKind="stream_json" mode="add" editable getConnectors={vi.fn().mockRejectedValue(new Error('down'))} onConfirm={vi.fn()} />,
    )
    fireEvent.click(getByTestId('connector.picker.mcp'))
    expect(await findByTestId('connector.optInDialog.previewFailed')).toBeTruthy()
    fireEvent.click(getByTestId('connector.optInDialog.ack')) // acknowledge without a visible preview
    expect((getByTestId('connector.optInDialog.confirm') as HTMLButtonElement).disabled).toBe(true) // still fail-closed
  })

  it('risk lines are amber notes (not error), human-only note visible (teeth 5 / anti-injection §6)', () => {
    const { getByTestId } = render(
      <ConnectorPicker kind="stream_json" initialKind="stream_json" mode="add" editable getConnectors={okConnectors()} onConfirm={vi.fn()} />,
    )
    fireEvent.click(getByTestId('connector.picker.mcp'))
    for (const t of ['riskBypass', 'riskAccount', 'riskFragile']) {
      expect(getByTestId(`connector.optInDialog.${t}`).getAttribute('role')).toBe('note') // amber note, not alert
    }
    expect(getByTestId('connector.optInDialog.humanOnly')).toBeTruthy()
  })

  it('edit effect-hint shows only when the draft differs from the server kind; add never shows it (tooth 5/§5)', () => {
    const { getByTestId, queryByTestId, rerender } = render(
      <ConnectorPicker kind="mcp" initialKind="stream_json" mode="edit" editable getConnectors={okConnectors()} onConfirm={vi.fn()} />,
    )
    expect(getByTestId('connector.picker.effectHint')).toBeTruthy() // edit + draft≠initial
    rerender(<ConnectorPicker kind="stream_json" initialKind="stream_json" mode="edit" editable getConnectors={okConnectors()} onConfirm={vi.fn()} />)
    expect(queryByTestId('connector.picker.effectHint')).toBeNull() // draft==initial → no hint
    rerender(<ConnectorPicker kind="mcp" initialKind="stream_json" mode="add" editable getConnectors={okConnectors()} onConfirm={vi.fn()} />)
    expect(queryByTestId('connector.picker.effectHint')).toBeNull() // add → never a restart hint
  })
})
