// CYP-401 (W3) — the structured DOM transcript renderer (the /ws/agent stream as rows, NOT xterm — §4). Each
// AgentEvent kind is its own row. **XSS: all agent-supplied text is rendered as JSX text children, which React
// escapes** — there is NO innerHTML / dangerouslySetInnerHTML anywhere (guarded by noInnerHtml.test.ts and proven
// escaped by AgentTranscript.render.test.tsx). The mapper already truncates/flattens summaries; this only escapes.
import type { AgentEvent } from './agentEvent'
import { formatLocalHhMm } from './transcriptTime'
import { useAutoscrollPin } from './useAutoscrollPin'

export function AgentTranscript({ rows }: { rows: readonly AgentEvent[] }) {
  // CYP-404: native-scrolling container with auto-follow/pin (a thin scrollbar via .transcript-scroll CSS).
  const { ref, onScroll } = useAutoscrollPin(tailSignature(rows))
  return (
    <div className="transcript-scroll" ref={ref} onScroll={onScroll} data-testid="transcript-scroll">
      <ol className="transcript" data-testid="transcript">
        {rows.map((row) => (
          <TranscriptRow key={row.id} row={row} />
        ))}
      </ol>
    </div>
  )
}

/** CYP-404: changes when the tail changes — including a streaming assistant row growing in place (same count,
 *  longer text). Keys the follow effect so streaming deltas re-stick to the bottom while pinned. Exported +
 *  unit-tested (tailSignature.test.ts): the `grow` term IS the streaming-follow behaviour, so dropping it must
 *  turn a test red (M2), not pass silently. */
export function tailSignature(rows: readonly AgentEvent[]): string {
  if (rows.length === 0) return ''
  const last = rows[rows.length - 1]
  const grow = last.kind === 'assistantText' ? last.text.length : 0
  return `${rows.length}|${last.id}|${last.kind}|${grow}`
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
