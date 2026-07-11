// CYP-401 (W3) — the structured DOM transcript renderer (the /ws/agent stream as rows, NOT xterm — §4). Each
// AgentEvent kind is its own row. **XSS: all agent-supplied text is rendered as JSX text children, which React
// escapes** — there is NO innerHTML / dangerouslySetInnerHTML anywhere (guarded by noInnerHtml.test.ts and proven
// escaped by AgentTranscript.render.test.tsx). The mapper already truncates/flattens summaries; this only escapes.
import type { AgentEvent } from './agentEvent'
import { formatLocalHhMm } from './transcriptTime'

export function AgentTranscript({ rows }: { rows: readonly AgentEvent[] }) {
  return (
    <ol className="transcript" data-testid="transcript">
      {rows.map((row) => (
        <TranscriptRow key={row.id} row={row} />
      ))}
    </ol>
  )
}

function TranscriptRow({ row }: { row: AgentEvent }) {
  const time = formatLocalHhMm(row.tsMs)
  switch (row.kind) {
    case 'assistantText':
      return (
        <li className="row assistant">
          <time>{time}</time>
          <span className="text">{row.text}</span>
          {!row.complete && <span className="cursor" aria-hidden="true">▋</span>}
        </li>
      )
    case 'toolCall':
      return (
        <li className={`row tool ${row.status}`}>
          <time>{time}</time>
          <span className="tool-name">{row.tool}</span>
          <span className="summary">{row.summary}</span>
          <span className="status">{row.status}</span>
        </li>
      )
    case 'result':
      return (
        <li className={`row result ${row.isError ? 'error' : 'ok'}`}>
          <time>{time}</time>
          <span className="label">{row.label}</span>
        </li>
      )
    case 'notice':
      return (
        <li className="row notice">
          <time>{time}</time>
          <span className="text">{row.text}</span>
        </li>
      )
    case 'userTurn':
      return (
        <li className="row user">
          <time>{time}</time>
          <span className="text">{row.text}</span>
        </li>
      )
    case 'incomingSystem':
      return (
        <li className="row incoming">
          <time>{time}</time>
          <span className="text">{row.text}</span>
        </li>
      )
  }
}
