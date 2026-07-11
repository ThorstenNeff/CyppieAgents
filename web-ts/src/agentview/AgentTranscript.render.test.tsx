// @vitest-environment jsdom
// CYP-401 (W3) security AC — untrusted agent output must render as ESCAPED text, never parsed as HTML. React
// escapes JSX text children by construction; this proves it (a regression here would be a stored-XSS hole).
import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { AgentTranscript } from './AgentTranscript'
import type { AgentEvent } from './agentEvent'

describe('AgentTranscript — XSS', () => {
  it('renders agent-supplied text as literal text, not as HTML', () => {
    const payload = '<img src=x onerror="alert(1)"><script>alert(2)</script>'
    const rows: AgentEvent[] = [{ kind: 'assistantText', id: 'm', text: payload, complete: true, tsMs: 0 }]
    const { container } = render(<AgentTranscript rows={rows} />)

    // The markup must NOT have been parsed into live elements…
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('script')).toBeNull()
    // …it appears verbatim as text content.
    expect(container.textContent).toContain(payload)
  })

  it('renders one row per event with its kind class', () => {
    const rows: AgentEvent[] = [
      { kind: 'toolCall', id: 't1', tool: 'Bash', summary: 'ls', status: 'ok', tsMs: 0 },
      { kind: 'result', id: 'result-t1', label: 'done', isError: false, tsMs: 0 },
    ]
    const { container } = render(<AgentTranscript rows={rows} />)
    expect(container.querySelectorAll('li.row')).toHaveLength(2)
    expect(container.querySelector('li.tool.ok')).not.toBeNull()
  })
})
