// CYP-735 (First-Run parity, UIUX2 spec ad741f60 §3.1) — the persistent "hub is not set up" banner + chip.
//
// Parity with the CMP `workspace_unconfigured_banner` / `_chip`. Deliberately NOT dismissable, unlike the overload
// banner: overload is a transient event that has passed, while "not set up" is a STANDING condition — hiding it
// would leave the operator with a workspace whose agents silently cannot start, and no visible reason why.
//
// Tone: this is a call to action, not a failure. Nothing crashed and nothing was rejected, so it carries neither
// the overload banner's WARN-amber alarm nor an error-red. The text states the consequence plainly ("agents cannot
// start") because that is the fact the operator needs; the fix is one click away.
//
// Rendered ONLY on a server-stated `configured: false` (see setupStatus): never while the config is still loading
// and never after a failed load, where "set up your hub" would be confidently wrong.
// CYP-735 §3.2 — copy now comes from the shared source (firstRunText), not a paraphrase. §3.1 shipped
// "Hub nicht eingerichtet …" while the shared CMP string reads "Hub NOCH nicht eingerichtet … In den
// Projekt-Einstellungen einrichten." A near-miss on a shared string is still a miss: the two strands must say the
// same words to the same user, which is the entire reason the key is shared.
import { WORKSPACE_UNCONFIGURED_BANNER, WORKSPACE_UNCONFIGURED_CHIP } from '../firstrun/firstRunText'

export const UNCONFIGURED_BANNER_TEXT = WORKSPACE_UNCONFIGURED_BANNER
export const UNCONFIGURED_CHIP_TEXT = WORKSPACE_UNCONFIGURED_CHIP
export const UNCONFIGURED_ACTION_TEXT = 'Einrichten'

export function UnconfiguredBanner({ onSetUp }: { onSetUp: () => void }) {
  return (
    <div className="unconfigured-banner" data-testid="workspace.unconfiguredBanner" role="status">
      {/* glyph + text + chip: the state is carried by words, colour only reinforces it (WCAG 1.4.1) */}
      <span className="unconfigured-glyph" aria-hidden="true">
        ●
      </span>
      <span className="unconfigured-chip" data-testid="workspace.unconfiguredChip">
        {UNCONFIGURED_CHIP_TEXT}
      </span>
      <span className="unconfigured-text">{UNCONFIGURED_BANNER_TEXT}</span>
      <button
        type="button"
        className="unconfigured-action"
        data-testid="workspace.unconfiguredBanner.action"
        onClick={onSetUp}
      >
        {UNCONFIGURED_ACTION_TEXT}
      </button>
    </div>
  )
}
