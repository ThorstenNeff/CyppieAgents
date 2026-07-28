// CYP-888 (NR-1) — a thin hook that feeds the live viewport into the pure gate (navRailGate.ts). It re-reads on
// `resize` and `orientationchange` so rotating a tablet or resizing the window flips the rail in/out live. The gate
// logic itself lives in the pure function (the unit-tooth target); this only owns the DOM subscription.
import { useEffect, useState } from 'react'
import { shouldShowNavRail, type Viewport } from './navRailGate'

function readViewport(): Viewport {
  return { width: window.innerWidth, height: window.innerHeight }
}

/** True when the vertical nav-rail should be shown for the current viewport (landscape + short-edge ≥ ~600dp). */
export function useNavRailVisible(): boolean {
  const [viewport, setViewport] = useState<Viewport>(readViewport)
  useEffect(() => {
    const onChange = (): void => setViewport(readViewport())
    window.addEventListener('resize', onChange)
    window.addEventListener('orientationchange', onChange)
    return () => {
      window.removeEventListener('resize', onChange)
      window.removeEventListener('orientationchange', onChange)
    }
  }, [])
  return shouldShowNavRail(viewport)
}
