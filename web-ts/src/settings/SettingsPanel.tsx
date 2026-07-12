// CYP-453 (P2-f) — the Settings level: the frame around the PROJECT config. Ported from Compose SettingsPanel. The
// load-bearing rule (spec §0): TWO gate classes that must never be mixed — project config (repo + API key) is
// operator-gated present-but-disabled; personal preferences (theme, composer-history) belong to the USER and stay
// UNGATED in the app bar (Compose parity), never dragged behind the operator gate. This panel therefore holds ONLY
// the project config: the Repo-Section (full port) + the API-key section FRAMED from CYP-433 (no second impl).
//
// The Repo-Section and the API-key section share ONE deferred-effect disclosure ("saved ≠ active", amber, points at
// the P2-a per-agent restart — no restart control here). This file is the "authoritative place" for that pattern (§5).
import { useEffect, useState } from 'react'
import type { RepoConfigView, RepoConfigRequest, ApiKeyView } from '../types/generated/contract'
import { ApiKeyPanel } from './ApiKeyPanel'
import { canSaveRepo, repoSaveRejectMessage, SETTINGS_TEXT as T } from './settingsModel'

export interface SettingsPanelProps {
  operator: boolean
  repoConfig: RepoConfigView | null
  onSaveRepo: (req: RepoConfigRequest) => Promise<void>
  // framed API-key section (CYP-433) — passed straight through, never re-implemented here.
  apiKeyView: ApiKeyView | null
  onSaveApiKey: (apiKey: string) => Promise<void>
}

export function SettingsPanel({ operator, repoConfig, onSaveRepo, apiKeyView, onSaveApiKey }: SettingsPanelProps) {
  return (
    <div className="settings-panel transcript-scroll" data-testid="settings.panel">
      <RepoSection operator={operator} config={repoConfig} onSave={onSaveRepo} />
      {/* API-key section: FRAMED from CYP-433 (one source for the leak model) — placed as the 2nd project-config
          section under the repo section, sharing the same operator gate + amber effect-hint pattern (§4/§5). */}
      <section role="group" className="settings-section apikey-frame" data-testid="settings.section.apiKey.frame">
        <h3>{T.apiKeySection}</h3>
        <ApiKeyPanel view={apiKeyView} operator={operator} onSave={onSaveApiKey} />
      </section>
    </div>
  )
}

function RepoSection({
  operator,
  config,
  onSave,
}: {
  operator: boolean
  config: RepoConfigView | null
  onSave: (req: RepoConfigRequest) => Promise<void>
}) {
  const [url, setUrl] = useState('')
  const [branch, setBranch] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false) // shows the amber effect-hint after a confirmed save
  const [busy, setBusy] = useState(false)

  // Prefill from the server config once it loads.
  useEffect(() => {
    if (config) {
      setUrl(config.url ?? '')
      setBranch(config.branch ?? '')
    }
  }, [config])

  const submit = async () => {
    setError(null)
    setSaved(false)
    setBusy(true)
    try {
      await onSave({ url: url.trim(), branch: branch.trim() || undefined })
      setSaved(true) // saved ≠ active → the amber deferred-effect hint (§5)
    } catch (e) {
      setError(repoSaveRejectMessage(e)) // server-authoritative (invalid_repo_url)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section role="group" className="settings-section repo" data-testid="settings.section.repo">
      <h3>{T.repoSection}</h3>

      {/* honest unset: names the consequence, INFO/WARN tone (not error-red) — never silently omitted (§3). */}
      {config !== null && !config.configured && (
        <p className="settings-repo-status" role="status" data-testid="settings.repo.status">
          {T.statusUnset}
        </p>
      )}

      <label>
        {T.urlLabel}
        <input
          data-testid="settings.repo.url.input"
          aria-label={T.urlLabel}
          value={url}
          disabled={!operator}
          onChange={(e) => setUrl(e.target.value)}
        />
      </label>
      <label>
        {T.branchLabel}
        <input
          data-testid="settings.repo.branch.input"
          aria-label={T.branchLabel}
          value={branch}
          disabled={!operator}
          onChange={(e) => setBranch(e.target.value)}
        />
      </label>

      {/* present-but-disabled, never a fake dead field (CYP-317): the gate hint is visible for a non-operator. */}
      {!operator && (
        <p className="settings-gate-hint" role="note" data-testid="settings.repo.gateHint">
          {T.operatorRequired}
        </p>
      )}

      {error !== null && (
        <p className="settings-error" role="alert" data-testid="settings.repo.error">
          {error}
        </p>
      )}

      {/* amber "saved ≠ active" → points at the P2-a restart; NO restart control here (§5.2). */}
      {saved && (
        <p className="settings-effect-hint" role="status" data-testid="settings.repo.effectHint">
          {T.repoEffectHint}
        </p>
      )}

      <button
        type="button"
        data-testid="settings.repo.save"
        disabled={!canSaveRepo(operator, url) || busy}
        aria-disabled={!canSaveRepo(operator, url)}
        onClick={submit}
      >
        {T.save}
      </button>
    </section>
  )
}
