// CYP-735 §3.2 — "I dismissed the setup wizard" as a remembered UI PREFERENCE.
//
// WHY LOCAL STORAGE IS LEGITIMATE HERE, HAVING ARGUED AGAINST IT IN CYP-705. There the question was "has this been
// read?" — a claim about the world that must be durable and cross-device, so a local marker faked certainty the
// client could not have. This is the opposite kind of fact: it is about THIS browser's UI, nobody else can
// contradict it, and losing it costs one dismissed dialog. A preference may live locally; a truth may not.
//
// AND IT CANNOT HIDE ANYTHING (UIUX2's ratified conditions):
//   (a) skipping never suppresses the unconfigured banner or the agent-start gating — those come from the server
//       status and are not consulted here;
//   (b) there is always a resume path back into the gate (see `clearSetupSkipped`);
//   (c) once the server reports `configured`, both the banner and the gate resolve on their own — the gate mode is
//       derived from status, so a stale skip flag cannot keep anything hidden or shown.
// The skip therefore only decides whether GUIDANCE is in the way, never what is TRUE.
//
// Fails soft: storage can be unavailable (private mode, disabled cookies, quota). An unreadable preference means
// "not skipped" — the guidance reappears, which is the harmless direction.

const KEY = 'cyppie.firstrun.skipped'

function storage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null // access itself can throw when storage is blocked
  }
}

/** Has the operator dismissed the guided setup in THIS browser? Unknown/unavailable ⇒ false (show the guidance). */
export function isSetupSkipped(): boolean {
  try {
    return storage()?.getItem(KEY) === '1'
  } catch {
    return false
  }
}

export function setSetupSkipped(): void {
  try {
    storage()?.setItem(KEY, '1')
  } catch {
    // ignore: a preference that cannot be stored simply is not remembered — nothing breaks.
  }
}

/** The resume path (condition b): forget the dismissal so the gate guides again. */
export function clearSetupSkipped(): void {
  try {
    storage()?.removeItem(KEY)
  } catch {
    // ignore
  }
}
