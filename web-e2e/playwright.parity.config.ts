import { defineConfig } from '@playwright/test';

/**
 * CYP-422 parity config — the PRODUCT web-ts SPA against the REAL Ktor harness, in the CONFIRMED production
 * topology: SAME-ORIGIN (PO1-confirmed reverse-proxy = one origin; the auth stack — CYP-230 cookie-session,
 * CYP-31 origin-guard, /ws/agent cookie — is same-origin by design). The browser only ever talks to Vite on
 * :8080, which proxies /api + /ws to the hermetic harness on :8791 (vite.parity.config.ts) — so there is NO CORS
 * and no allowCredentials question (allowCredentials=false is correct/stronger CSRF posture). The harness runs
 * with WEB_TS_ORIGINS= (empty) → it installs no CORS at all. The cross-origin pass is HELD (a non-prod topology).
 *
 * Two webServers: the hermetic CYP-106 platform (no claude/key/repo) and Vite with the parity config (types
 * regenerated from the real :core export first, CONTRACT_REQUIRE_REAL=1 = fail-closed on a missing export).
 * Only the operator token is injected per-page (parity/fixtures.ts); the API/WS base is left to appConfig's
 * same-origin fallback. Distinct from playwright.config.ts (the reference fixture). Manual, no CI.
 */
const APP = 'http://127.0.0.1:8080';

export default defineConfig({
  testDir: './parity',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-parity' }]],
  use: {
    baseURL: APP,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: [
    {
      // WEB_TS_ORIGINS= (empty) → the harness installs NO CORS (same-origin topology; the Vite proxy is the only
      // hop the browser sees). Health is checked on :8791 directly (server-side), not through the proxy.
      command: 'sh -c "WEB_TS_ORIGINS= ./gradlew -q :e2e:webE2eServer"',
      cwd: '..',
      url: 'http://127.0.0.1:8791/api/health',
      reuseExistingServer: !process.env.CI,
      timeout: 300_000,
    },
    {
      // Regenerate the generate-don't-commit artifacts (types from the REAL export, fail-closed; maritime token
      // CSS), then serve the SPA with the same-origin parity Vite config (proxies /api + /ws to :8791). Run from
      // web-ts so Vite's root is web-ts; the config path is relative to that cwd.
      command: 'sh -c "CONTRACT_REQUIRE_REAL=1 npm --prefix web-ts run contract:gen && npm --prefix web-ts run tokens:gen && cd web-ts && npx vite --config vite.parity.config.ts"',
      cwd: '..',
      url: APP,
      reuseExistingServer: !process.env.CI,
      timeout: 180_000,
    },
  ],
});
