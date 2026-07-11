// CYP-403 (W5) — the composer. Its input is "a message to the AGENT" (05-D7: the mediator injects it on the
// agent's stdin), NOT a shell command. Single-line (spec §0): ↑/↓ are pure history recall (InputHistory +
// ComposerRecall, ported from :app:shared/CYP-387); Enter sends. `historySize` is the live global-N supplier so
// a settings change takes effect without rebuilding the store. `onSend` is the caller's send+local-echo wiring.
import { useRef, useState } from 'react'
import { InputHistory } from './inputHistory'
import { ComposerRecall } from './composerRecall'

export function Composer({ onSend, historySize }: { onSend: (text: string) => void; historySize: () => number }) {
  const [draft, setDraft] = useState('')
  const historyRef = useRef<InputHistory | null>(null)
  if (historyRef.current === null) historyRef.current = new InputHistory(historySize)
  const recallRef = useRef<ComposerRecall | null>(null)
  if (recallRef.current === null) recallRef.current = new ComposerRecall()
  const history = historyRef.current
  const recall = recallRef.current

  const submit = () => {
    const text = draft.trim()
    if (text === '') return
    onSend(text) // a sent recall-edit is captured as a NEW newest entry; the original stays (spec §2.2)
    history.record(text)
    recall.reset()
    setDraft('')
  }

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowUp') {
      const recalled = recall.older(history.entries, draft)
      if (recalled !== null) {
        e.preventDefault()
        setDraft(recalled)
      }
    } else if (e.key === 'ArrowDown') {
      const recalled = recall.newer(history.entries, draft)
      if (recalled !== null) {
        e.preventDefault()
        setDraft(recalled)
      }
    } else if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      submit()
    }
  }

  return (
    <input
      className="composer-input"
      data-testid="composer-input"
      aria-label="Nachricht an den Agenten"
      placeholder="Nachricht an den Agenten…"
      value={draft}
      onChange={(e) => setDraft(e.target.value)}
      onKeyDown={onKeyDown}
    />
  )
}
