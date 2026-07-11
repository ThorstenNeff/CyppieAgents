import { test as base, expect } from '@playwright/test';

/**
 * CYP-422 Phase-1 parity — the product web-ts SPA driven against the REAL Ktor harness.
 *
 * The app resolves its API/WS base + operator token + PO identity from `globalThis` deploy-globals
 * (platform/appConfig.ts, hubConfig.ts). We inject them via `addInitScript` BEFORE the bundle runs — the
 * same seam the deploy/proxy uses — pointing the SPA (served by Vite on :8080) at the hermetic Ktor harness
 * (:8791) cross-origin. This is the production topology (SPA origin ≠ API origin + CORS), so the run also
 * exercises the real CORS path.
 */
const API = process.env.CYPPIE_API ?? 'http://127.0.0.1:8791';
const WS = process.env.CYPPIE_WS ?? 'ws://127.0.0.1:8791';
const OPERATOR_TOKEN = 'e2e-operator-token'; // WebE2eSeed operator token
const PO_AGENT_ID = 'po';

export const test = base.extend({
  page: async ({ page }, use) => {
    await page.addInitScript(
      ([api, ws, op, po]) => {
        const g = globalThis as Record<string, unknown>;
        g.CYPPIE_API_BASE = api;
        g.CYPPIE_WS_BASE = ws;
        g.CYPPIE_OPERATOR_TOKEN = op;
        g.CYPPIE_PO_AGENT_ID = po;
      },
      [API, WS, OPERATOR_TOKEN, PO_AGENT_ID],
    );
    await use(page);
  },
});

export { expect };
export const SEED = { OPERATOR_TOKEN, PO_AGENT_ID, AGENT: 'backend', CHANNEL: 'po-backend' };
