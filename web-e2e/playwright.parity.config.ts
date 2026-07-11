import { defineConfig } from '@playwright/test';

/**
 * CYP-422 Phase-1 parity config — the PRODUCT web-ts SPA (Vite :8080) against the REAL Ktor harness (:8791).
 * Two webServers: the hermetic CYP-106 platform (no claude/key/repo) and the Vite dev server (types are
 * regenerated from the real :core export first, CONTRACT_REQUIRE_REAL=1 = fail-closed on a missing export).
 * Globals are injected per-page in parity/fixtures.ts. Distinct from playwright.config.ts (the reference
 * fixture) — this one drives the real product UI. Manual, no CI.
 */
const KTOR = 'http://127.0.0.1:8791';
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
      command: './gradlew -q :e2e:webE2eServer',
      cwd: '..',
      url: `${KTOR}/api/health`,
      reuseExistingServer: !process.env.CI,
      timeout: 300_000,
    },
    {
      // Regenerate the generate-don't-commit artifacts (types from the REAL export, fail-closed; maritime token
      // CSS), then serve the SPA — the same prelude `npm run build` runs.
      command: 'sh -c "CONTRACT_REQUIRE_REAL=1 npm --prefix web-ts run contract:gen && npm --prefix web-ts run tokens:gen && npm --prefix web-ts run dev"',
      cwd: '..',
      url: APP,
      reuseExistingServer: !process.env.CI,
      timeout: 180_000,
    },
  ],
});
