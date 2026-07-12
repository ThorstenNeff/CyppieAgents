// CYP-433 (P2-e) — the API-key section: the leak-MOST-sensitive surface. The plaintext key is the secret; the UI
// touches it only WRITE-ONLY and TRANSIENTLY, then clears it. Hard rules (spec §0):
//  1. The plaintext key NEVER lands persistently in the DOM. The stored key shows ONLY as the server-masked
//     `***last4` (the client never has the plaintext — the server sends only `masked`); the input is write-only and
//     starts EMPTY (the stored key is never loaded into it).
//  2. The only plaintext in the DOM is the NEW key the operator is typing — transient in the input value:
//     type="password" (default), autocomplete="new-password", no persisting name, the value is NEVER reflected into
//     data-*/aria-*/title, and the field is CLEARED after a successful save.
//  3. Never logged — no console.*, and NO error message ever contains the value (generic copy only).
// Gating is present-but-disabled (NOT omission, unlike CYP-432): the masked status leaks nothing, so a non-operator
// still sees the screen + the gate hint but cannot edit. Effect hint is AMBER ("saved ≠ active") and points at the
// P2-a restart — there is NO restart control here.
import { useState } from 'react'
import type { ApiKeyView } from '../types/generated/contract'

export interface ApiKeyPanelProps {
  view: ApiKeyView | null
  operator: boolean
  /** write-only save: resolves on success (the parent refreshes the masked view), rejects on failure. The panel
   *  never surfaces the key on failure — only a generic message. */
  onSave: (apiKey: string) => Promise<void>
}

export function ApiKeyPanel({ view, operator, onSave }: ApiKeyPanelProps) {
  const [draft, setDraft] = useState('') // the transient NEW key — never persisted, cleared after save
  const [reveal, setReveal] = useState(false) // un-masks ONLY this input, never the stored status
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [saving, setSaving] = useState(false)

  const canSave = operator && draft.trim() !== '' && !saving

  const masked = view?.set ? `Hinterlegt: ${view.masked}` : 'Kein Schlüssel hinterlegt'

  const save = () => {
    if (!canSave) return
    setError(null)
    setSaved(false)
    setSaving(true)
    onSave(draft)
      .then(() => {
        setDraft('') // §0.2 clear-after-save: the typed key must not linger in the DOM
        setReveal(false)
        setSaved(true)
      })
      .catch(() => setError('Speichern fehlgeschlagen.')) // §0.3 generic — NEVER the value
      .finally(() => setSaving(false))
  }

  return (
    <section className="settings-section apikey" data-testid="settings.section.apiKey">
      <h2 role="heading" aria-level={2}>
        API-Schlüssel
      </h2>

      {/* status: server-masked only, a plain text node, never interactive, never the value */}
      <p className="apikey-masked" data-testid="settings.apiKey.masked">
        {masked}
      </p>

      <div className="apikey-entry">
        <input
          className="apikey-input"
          data-testid="settings.apiKey.input"
          type={reveal ? 'text' : 'password'}
          autoComplete="new-password"
          aria-label="Neuen API-Schlüssel eingeben"
          placeholder="Neuen Schlüssel eingeben…"
          value={draft}
          disabled={!operator}
          onChange={(e) => {
            setDraft(e.target.value)
            setError(null)
            setSaved(false)
          }}
        />
        <button
          type="button"
          className="apikey-reveal"
          data-testid="settings.apiKey.reveal"
          aria-label={reveal ? 'Schlüssel verbergen' : 'Schlüssel anzeigen'}
          disabled={!operator}
          onClick={() => setReveal((r) => !r)}
        >
          {reveal ? 'Verbergen' : 'Anzeigen'}
        </button>
      </div>

      {!operator && (
        <p className="apikey-gate-hint" role="note" data-testid="settings.apiKey.gateHint">
          Nur mit Operator-Token änderbar.
        </p>
      )}

      <button type="button" className="apikey-save" data-testid="settings.apiKey.save" disabled={!canSave} onClick={save}>
        Speichern
      </button>

      {error !== null && (
        <p className="apikey-error" role="alert" data-testid="settings.apiKey.error">
          {error}
        </p>
      )}

      {saved && (
        <p className="apikey-effect-hint" role="status" data-testid="settings.apiKey.effectHint">
          Gespeichert. Wirkt erst beim nächsten Start des Agenten — jetzt neu starten, damit der neue Schlüssel zieht.
        </p>
      )}
    </section>
  )
}
