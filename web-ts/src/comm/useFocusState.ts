// CYP-705 follow-up — the browser half of the mark-read focus gate: turn visibility/focus into React state.
//
// Deliberately thin. The RULE lives in `markReadGate.ts` (pure, tested); this only reports what the browser says,
// and re-renders when it changes — the re-render is the point, because returning to the window has to let the
// cursor advance for what you can now actually see.
//
// Defaults are FAIL-CLOSED for a non-DOM environment: if we cannot observe focus, we report "not focused", so the
// cursor does not advance on a guess. Marking something unread that you read is recoverable; marking something
// read that you never saw is not (see markReadGate for why that asymmetry decides the direction).
import { useEffect, useState } from 'react'

export interface BrowserFocus {
  readonly documentVisible: boolean
  readonly windowFocused: boolean
}

function read(): BrowserFocus {
  if (typeof document === 'undefined') return { documentVisible: false, windowFocused: false }
  return {
    documentVisible: document.visibilityState === 'visible',
    windowFocused: typeof document.hasFocus === 'function' ? document.hasFocus() : false,
  }
}

/** Current visibility+focus, kept live. Re-renders on every transition so the gate re-evaluates on return. */
export function useFocusState(): BrowserFocus {
  const [focus, setFocus] = useState<BrowserFocus>(read)

  useEffect(() => {
    if (typeof document === 'undefined' || typeof window === 'undefined') return
    const update = () => setFocus(read())
    document.addEventListener('visibilitychange', update)
    window.addEventListener('focus', update)
    window.addEventListener('blur', update)
    update() // sync once on mount — the state may already differ from the initial render
    return () => {
      document.removeEventListener('visibilitychange', update)
      window.removeEventListener('focus', update)
      window.removeEventListener('blur', update)
    }
  }, [])

  return focus
}
