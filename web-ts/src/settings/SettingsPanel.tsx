// CYP-453 (P2-f) — the Settings level: the frame around the PROJECT config. Ported from Compose SettingsPanel. The
// load-bearing rule (spec §0): TWO gate classes that must never be mixed — project config (repo + API key) is
// operator-gated present-but-disabled; personal preferences (theme, composer-history) belong to the USER and stay
// UNGATED in the app bar (Compose parity), never dragged behind the operator gate. This panel therefore holds ONLY
// the project config: the Repo-Section (full port) + the API-key section FRAMED from CYP-433 (no second impl).
//
// The Repo-Section and the API-key section share ONE deferred-effect disclosure ("saved ≠ active", amber, points at
// the P2-a per-agent restart — no restart control here). This file is the "authoritative place" for that pattern (§5).
import { useEffect, useState } from 'react'
import type { RepoConfigView, RepoConfigRequest, ApiKeyView, ReprovisionPreview } from '../types/generated/contract'
import { ApiKeyPanel } from './ApiKeyPanel'
import { canSaveRepo, repoSaveRejectMessage, atRiskLabel, SETTINGS_TEXT as T } from './settingsModel'

export interface SettingsPanelProps {
  operator: boolean
  repoConfig: RepoConfigView | null
  onSaveRepo: (req: RepoConfigRequest) => Promise<void>
  // framed API-key section (CYP-433) — passed straight through, never re-implemented here.
  apiKeyView: ApiKeyView | null
  onSaveApiKey: (apiKey: string) => Promise<void>
  // CYP-465: the LIVE at-risk preview — fetched fresh each time the discard dialog opens, never cached.
  getReprovisionPreview: () => Promise<ReprovisionPreview>
}

export function SettingsPanel({ operator, repoConfig, onSaveRepo, apiKeyView, onSaveApiKey, getReprovisionPreview }: SettingsPanelProps) {
  return (
    <div className="settings-panel transcript-scroll" data-testid="settings.panel">
      <RepoSection operator={operator} config={repoConfig} onSave={onSaveRepo} getReprovisionPreview={getReprovisionPreview} />
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
  getReprovisionPreview,
}: {
  operator: boolean
  config: RepoConfigView | null
  onSave: (req: RepoConfigRequest) => Promise<void>
  getReprovisionPreview: () => Promise<ReprovisionPreview>
}) {
  const [url, setUrl] = useState('')
  const [branch, setBranch] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false) // shows the amber effect-hint after a confirmed save
  const [busy, setBusy] = useState(false)
  // CYP-465: discard is default-safe KEEP; armed ONLY via the confirmed dialog (never a plain checkbox, §3).
  const [discardArmed, setDiscardArmed] = useState(false)
  const [discardDialogOpen, setDiscardDialogOpen] = useState(false)

  // Prefill from the server config once it loads.
  useEffect(() => {
    if (config) {
      setUrl(config.url ?? '')
      setBranch(config.branch ?? '')
    }
  }, [config])

  const save = async (discardUnpushed: boolean) => {
    setError(null)
    setSaved(false)
    setBusy(true)
    try {
      await onSave({ url: url.trim(), branch: branch.trim() || undefined, discardUnpushed })
      setSaved(true) // saved ≠ active → the amber deferred-effect hint (§5)
    } catch (e) {
      setError(repoSaveRejectMessage(e)) // server-authoritative (invalid_repo_url)
    } finally {
      setBusy(false)
    }
  }
  const submit = () => save(discardArmed)

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

      {/* CYP-465: pending ≠ applied — a pending reprovision "steht an", the agents still run on the OLD clone (§2/tooth 1). */}
      {config?.reprovisionPending && (
        <p className="settings-reprovision-pending" role="status" data-testid="settings.repo.reprovisionPending">
          {T.reprovisionPending}
        </p>
      )}

      {/* CYP-465: discard is NEVER a plain checkbox — arming it opens the confirmed, named, irreversible dialog (§3).
          aria-checked reflects the SETTLED opt-in, not a click echo. Default = keep (the guard blocks, no silent loss). */}
      <label className="settings-discard-toggle">
        <input
          type="checkbox"
          role="checkbox"
          data-testid="settings.repo.discardToggle"
          checked={discardArmed}
          aria-checked={discardArmed}
          disabled={!operator}
          onChange={(e) => {
            if (e.target.checked) setDiscardDialogOpen(true) // arm ONLY via the dialog's confirm
            else setDiscardArmed(false) // un-arming (back to safe keep) needs no confirmation
          }}
        />
        {T.discardLabel}
      </label>

      <button
        type="button"
        data-testid="settings.repo.save"
        disabled={!canSaveRepo(operator, url) || busy}
        aria-disabled={!canSaveRepo(operator, url)}
        onClick={submit}
      >
        {T.save}
      </button>

      {discardDialogOpen && (
        <DiscardDialog
          getReprovisionPreview={getReprovisionPreview}
          onCancel={() => setDiscardDialogOpen(false)}
          onConfirm={async () => {
            setDiscardArmed(true) // settled opt-in
            setDiscardDialogOpen(false)
            await save(true) // apply the discard now (releases a blocked reprovision, §0.3)
          }}
        />
      )}
    </section>
  )
}

function DiscardDialog({
  getReprovisionPreview,
  onCancel,
  onConfirm,
}: {
  getReprovisionPreview: () => Promise<ReprovisionPreview>
  onCancel: () => void
  onConfirm: () => Promise<void>
}) {
  const [preview, setPreview] = useState<ReprovisionPreview | null>(null)
  const [loadFailed, setLoadFailed] = useState(false)
  const [busy, setBusy] = useState(false)

  // Fetch the at-risk list FRESH every time the dialog opens — NEVER cached (the operator confirms the loss they can
  // see AT THIS MOMENT, spec §3 / coordinator load-bearing). A load failure is honest (shows retry), not silent.
  useEffect(() => {
    let live = true
    setLoadFailed(false)
    getReprovisionPreview()
      .then((p) => live && setPreview(p))
      .catch(() => live && setLoadFailed(true))
    return () => {
      live = false
    }
  }, [getReprovisionPreview])

  // THREE distinct states (Assist §6.5): `atRisk` is contract-REQUIRED, so a LOADED-EMPTY list is authoritative
  // "zero risk" (nothing to discard) — NOT "unknown". Only unknown (fetch not-loaded/failed) is advisory.
  const loaded = preview !== null
  const cleared = loaded && (preview?.atRisk.length ?? 0) === 0 // authoritative: no unpushed work → no destructive discard
  const hasAtRisk = loaded && (preview?.atRisk.length ?? 0) > 0
  const unknown = loadFailed // can't name the work → advisory fallback (§3)
  // Confirm the destructive discard ONLY when there's real at-risk work OR the state is genuinely unknown — NEVER on
  // an authoritative empty (that would fire discardUnpushed=true against zero risk, §6.5 red) and never while loading.
  const canConfirm = (hasAtRisk || unknown) && !busy

  return (
    <div role="alertdialog" aria-label={T.discardTitle} className="settings-discard-dialog" data-testid="settings.repo.discardDialog">
      <h4>{T.discardTitle}</h4>

      {cleared ? (
        // authoritative "cleared": no work at risk → no discard offered (§6.5).
        <p className="settings-discard-cleared" role="status" data-testid="settings.repo.discardCleared">
          {T.discardCleared}
        </p>
      ) : (
        <>
          {/* the irreversible consequence, named before the act (§3 / tooth 4). Amber danger, not error-red. */}
          <p className="settings-discard-warning" role="alert" data-testid="settings.repo.discardWarning">
            {T.discardWarning}
          </p>
          {hasAtRisk ? (
            // "can't authorise a consequence you can't see" (CYP-461): the CONCRETE at-risk work.
            <div data-testid="settings.repo.discardAtRisk">
              <p>{T.atRiskHeading}</p>
              <ul>
                {(preview?.atRisk ?? []).map((a) => (
                  <li key={a.worktree}>{atRiskLabel(a)}</li>
                ))}
              </ul>
            </div>
          ) : unknown ? (
            // unknown (fetch failed) → advisory wording, never a sure claim of loss it doesn't know (§3 / tooth 5).
            <p className="settings-discard-advisory" role="note" data-testid="settings.repo.discardAdvisory">
              {T.discardAdvisory}
            </p>
          ) : (
            <p className="settings-discard-loading" role="status" data-testid="settings.repo.discardLoading">
              …
            </p>
          )}
        </>
      )}

      <div className="settings-discard-actions">
        {/* cancel first-focused (the safe default). */}
        <button type="button" data-testid="settings.repo.discardCancel" onClick={onCancel} autoFocus>
          {T.cancel}
        </button>
        {/* the destructive confirm is present only when a discard is actually applicable (not on a cleared/loading state). */}
        {!cleared && (
          <button
            type="button"
            className="settings-discard-confirm"
            data-testid="settings.repo.discardConfirm"
            disabled={!canConfirm}
            aria-disabled={!canConfirm}
            onClick={() => {
              setBusy(true)
              void onConfirm()
            }}
          >
            {T.discardConfirm}
          </button>
        )}
      </div>
    </div>
  )
}
