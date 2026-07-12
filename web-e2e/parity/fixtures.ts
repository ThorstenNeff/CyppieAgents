import { test as base, expect } from '@playwright/test';

/**
 * CYP-422 parity — the product web-ts SPA driven against the REAL Ktor harness in the CONFIRMED production
 * topology: SAME-ORIGIN (PO1-confirmed reverse-proxy = one origin). The browser talks only to Vite on :8080,
 * which proxies /api + /ws to the harness on :8791 (vite.parity.config.ts). So we inject ONLY the operator token
 * (`CYPPIE_OPERATOR_TOKEN` — the deploy's operator-serve signal, per operatorToken.ts). We deliberately do NOT
 * inject CYPPIE_API_BASE/WS_BASE: appConfig then falls back to location.origin (:8080), i.e. same-origin — which
 * is the whole point (no CORS, allowCredentials=false correct). PO identity is NOT injected either: since CYP-444
 * it comes from the typed roster's role==PO, not a CYPPIE_PO_AGENT_ID global.
 */
const OPERATOR_TOKEN = 'e2e-operator-token'; // E2ePlatform.OPERATOR_TOKEN (operator serve)

export const test = base.extend({
  page: async ({ page }, use) => {
    await page.addInitScript((op) => {
      (globalThis as Record<string, unknown>).CYPPIE_OPERATOR_TOKEN = op;
    }, OPERATOR_TOKEN);
    await use(page);
  },
});

export { expect };
export const SEED = { OPERATOR_TOKEN, AGENT: 'backend', CHANNEL: 'po-backend' };
