// CYP-642 (P2, Epic CYP-640) — the hub overload-reject banner. Parity with CMP OverloadBanner (CYP-417): a
// full-width WARN-amber strip (glyph ▲) — NEVER error-red (nothing crashed) and NEVER success-green (protection is
// not success). Rendered ONLY on a REAL server-side reject (the App wires it to the `capacity_exceeded` 503 from a
// spawn/start — H5, never invented from the estimate). Dismissable; a11y is assertive (role="alert") because the
// user just tried to add/start an agent and the honest rejection must announce at once. Honesty split (H2): the fact
// ("Spawn abgelehnt") and the estimated reason ("würde die geschätzte Kapazität überschreiten") are stated separately.
export function OverloadBanner({ onDismiss }: { onDismiss: () => void }) {
  return (
    <div className="overload-banner" data-testid="overload-banner" role="alert">
      <span className="overload-glyph" aria-hidden="true">
        ▲
      </span>
      <span className="overload-text">
        Spawn abgelehnt — würde die geschätzte Hub-Kapazität überschreiten.
      </span>
      <button type="button" className="overload-dismiss" data-testid="overload-banner.dismiss" onClick={onDismiss}>
        Ausblenden
      </button>
    </div>
  )
}
