import { defineConfig } from '@playwright/test';

/**
 * web-e2e (§8) — Playwright against a REAL Ktor test server.
 *
 * `webServer` boots the hermetic platform via the `:e2e:webE2eServer` Gradle task (FakeSpawner/FakeGit — no
 * real claude/key/repo) on WEB_E2E_PORT, serving the reference fixture same-origin. Playwright waits on
 * `/api/health` before the teeth run and tears the server down after. Manual, no CI: `reuseExistingServer`
 * lets an already-running harness be reused across iterative local runs.
 */
const PORT = process.env.WEB_E2E_PORT ?? '8791';
const BASE = `http://127.0.0.1:${PORT}`;

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  forbidOnly: false,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: BASE,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: './gradlew -q :e2e:webE2eServer',
    cwd: '..', // repo root, where ./gradlew lives
    url: `${BASE}/api/health`,
    reuseExistingServer: !process.env.CI,
    timeout: 300_000, // first boot compiles the :e2e test classpath — allow a cold Gradle run
    env: { WEB_E2E_PORT: PORT },
  },
});
