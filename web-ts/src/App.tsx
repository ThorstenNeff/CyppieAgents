import { isOperatorServe } from './platform/operatorToken'

/**
 * CYP-398 (W0) — walking skeleton. Deliberately empty beyond proving the app mounts and the operator-token
 * seam (Spec 14 §6) is wired: the serve mode is derived from the injected global, not from anything committed.
 * Real UI (renderer, window manager, comm) arrives in later slices (W2+).
 */
export function App() {
  const serve = isOperatorServe() ? 'operator' : 'member'
  return (
    <main>
      <h1>Cyppie Agents</h1>
      <p>Web (TypeScript) — walking skeleton · CYP-398</p>
      <p>serve: {serve}</p>
    </main>
  )
}
