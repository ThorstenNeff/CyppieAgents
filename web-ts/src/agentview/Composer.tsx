// CYP-403 (W5) — the composer. Its input is "a message to the AGENT" (05-D7: the mediator injects it on the
// agent's stdin), NOT a shell command. Single-line (spec §0): ↑/↓ are pure history recall (InputHistory +
// ComposerRecall, ported from :app:shared/CYP-387); Enter sends. `historySize` is the live global-N supplier so
// a settings change takes effect without rebuilding the store. `onSend` is the caller's send+local-echo wiring.
//
// CYP-890 (NR-3): the draft is CONTROLLED (`draft`/`onDraftChange`) so it can live in the shared agent VM and survive a
// nav-destination switch. `disabled` renders a read-only composer + reason hint — the honest CLIENT hint of the server
// truth (a PRODUCT_LEAD reviewer has canWrite=false; postAsAgent 403s their send). The server stays the authority; this
// is render-mirrors-authority, not the gate itself.
import { useRef, useState } from 'react'
import { InputHistory } from './inputHistory'
import { ComposerRecall } from './composerRecall'

export function Composer({
  onSend,
  historySize,
  draft: draftProp,
  onDraftChange,
  disabled = false,
  disabledReason,
}: {
  onSend: (text: string) => void
  historySize: () => number
  /** CYP-890: OPTIONAL controlled draft. When both are given (AgentWindow → shared agent VM) the draft is controlled and
   *  survives a nav-destination switch; when omitted (e.g. CommPanel) the composer keeps its own local draft as before. */
  draft?: string
  onDraftChange?: (v: string) => void
  disabled?: boolean
  disabledReason?: string
}) {
  const [internalDraft, setInternalDraft] = useState('')
  const controlled = draftProp !== undefined && onDraftChange !== undefined
  const draft = controlled ? draftProp : internalDraft
  const onDraft = controlled ? onDraftChange : setInternalDraft
  const historyRef = useRef<InputHistory | null>(null)
  if (historyRef.current === null) historyRef.current = new InputHistory(historySize)
  const recallRef = useRef<ComposerRecall | null>(null)
  if (recallRef.current === null) recallRef.current = new ComposerRecall()
  const history = historyRef.current
  const recall = recallRef.current

  const submit = () => {
    if (disabled) return
    const text = draft.trim()
    if (text === '') return
    onSend(text) // a sent recall-edit is captured as a NEW newest entry; the original stays (spec §2.2)
    history.record(text)
    recall.reset()
    onDraft('')
  }

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (disabled) return
    if (e.key === 'ArrowUp') {
      const recalled = recall.older(history.entries, draft)
      if (recalled !== null) {
        e.preventDefault()
        onDraft(recalled)
      }
    } else if (e.key === 'ArrowDown') {
      const recalled = recall.newer(history.entries, draft)
      if (recalled !== null) {
        e.preventDefault()
        onDraft(recalled)
      }
    } else if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      submit()
    }
  }

  return (
    <div className="composer">
      <input
        className="composer-input"
        data-testid="composer-input"
        aria-label="Nachricht an den Agenten"
        placeholder="Nachricht an den Agenten…"
        value={draft}
        disabled={disabled}
        onChange={(e) => onDraft(e.target.value)}
        onKeyDown={onKeyDown}
      />
      {disabled && disabledReason !== undefined && (
        <p className="composer-readonly-hint" data-testid="composer-readonly-hint">
          {disabledReason}
        </p>
      )}
    </div>
  )
}
