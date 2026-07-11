// CYP-400 (W2-rest) — the fail-closed contract-input decision (consumer side of the boundary). Extracted so it is
// unit-testable. Backend2 owns the export + the schema drift-test; this owns the FLIP: in CI/release set
// CONTRACT_REQUIRE_REAL so a MISSING real export is an error (exit 1), never a silent fall back to the fixture.
// Locally (flag unset) the provisional fixture is still allowed with a loud warning.

/**
 * @param {boolean} realExists  whether the real :core export (contract/asyncapi.json) is present
 * @param {boolean} requireReal whether CONTRACT_REQUIRE_REAL is set
 * @returns {'real' | 'provisional'}
 */
export function selectContractInput(realExists, requireReal) {
  if (!realExists && requireReal) {
    throw new Error(
      '[CYP-400] CONTRACT_REQUIRE_REAL is set but the real :core export (contract/asyncapi.json) is absent — ' +
        'fail-closed: refusing to generate types from the provisional fixture in CI/release.',
    )
  }
  return realExists ? 'real' : 'provisional'
}

/** Parse the env flag: 1/true/yes/on (case-insensitive) → true; anything else → false. */
export function contractRequireReal(envValue) {
  return /^(1|true|yes|on)$/i.test(envValue ?? '')
}
