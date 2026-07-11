/// <reference types="vite/client" />

interface Window {
  /**
   * Operator token, injected by the deploy/proxy as a global BEFORE the bundle (CYP-152/188 pattern, Spec 14 §6).
   * Absent on the public/MEMBER serve -> fail-closed. Never committed, never compiled into the bundle.
   */
  CYPPIE_OPERATOR_TOKEN?: string
}
