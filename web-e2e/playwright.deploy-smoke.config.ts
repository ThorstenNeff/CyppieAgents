import { defineConfig } from '@playwright/test';

/**
 * CYP-422 Post-Deploy smoke config — runs against the LIVE staging web-ts URL (SMOKE_URL), NO local webServer
 * (unlike the parity configs). The unauth §1 CSP active-probe needs no login.
 * Usage: SMOKE_URL=https://<staging> npx playwright test --config=playwright.deploy-smoke.config.ts
 */
export default defineConfig({
  testDir: './deploy-smoke',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.SMOKE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
