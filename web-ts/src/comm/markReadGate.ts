// CYP-705 follow-up — the mark-read FOCUS GATE. Decides whether the read cursor may advance at all.
//
// WHY THIS EXISTS (a defect in the merged #4, found by measuring my own code): the mark-read effect depends on
// `messagesByChannel`, so EVERY arriving message re-fired it and advanced the cursor — including while the tab was
// hidden or the window unfocused. That marks messages read that nobody ever saw.
//
// AND IT IS WORSE THAN A DISPLAY BUG. The badge stays honest either way (it only clears on the server echo), but
// the CURSOR is durable and cross-device: a wrongly advanced cursor erases the unread signal everywhere and
// permanently — on the phone too. The display can be re-rendered; a destroyed "you have not read this" cannot.
//
// SO THE ERROR DIRECTION IS DELIBERATE. Refusing to advance when we are unsure leaves something marked unread that
// you did read — mildly annoying, and self-correcting the moment you look at the channel with the window focused.
// Advancing when we are unsure silently destroys information. Between "annoying and recoverable" and "quiet and
// permanent", the gate fails toward unread.
//
// Split out as a pure function on purpose: the browser signals are awkward to drive in tests, the RULE is the part
// that must be provable, and a rule embedded in an effect is a rule nobody can test.

export interface FocusState {
  /** `document.visibilityState === 'visible'` — false when the tab is hidden/backgrounded. */
  readonly documentVisible: boolean
  /** `document.hasFocus()` — false when another application (or another tab) holds the OS focus. */
  readonly windowFocused: boolean
  /** Whether the comm window is the focused window INSIDE the app's own window manager. */
  readonly commWindowFocused: boolean
}

/**
 * May the read cursor advance right now?
 *
 * All three must hold. They answer different questions and none implies another: a visible tab can sit behind
 * another application (`windowFocused` false), and a focused browser window can be showing the event log rather
 * than the conversation (`commWindowFocused` false). "Visible" is not "being read".
 */
export function canAdvanceReadCursor(f: FocusState): boolean {
  return f.documentVisible && f.windowFocused && f.commWindowFocused
}
