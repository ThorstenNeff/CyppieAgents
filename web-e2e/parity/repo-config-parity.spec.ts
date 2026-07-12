import { test, expect } from './fixtures';

/**
 * CYP-422 §A1 — Repo-Config (CYP-453) parity: the SettingsPanel repo section against the real harness same-origin.
 * A landed-but-untested [K] row (completeness pass) — state-mutating, so it would silently read as "in the gate".
 * GET/PUT /api/config/repo (operator). Non-optimistic (saved ≠ active → restart), so the tooth proves the save
 * PERSISTS via a reload round-trip (the A1 criterion), not merely that a hint appeared.
 */
test.describe('A1 · Repo-Config (CYP-453) — operator save + persistence over reload', () => {
  test('operator: save url/branch → PUT persists → effect hint + the values survive a reload', async ({ page }) => {
    await page.goto('/');
    const section = page.locator('[data-testid="settings.section.repo"]');
    await expect(section).toBeVisible();

    const url = page.locator('[data-testid="settings.repo.url.input"]');
    const branch = page.locator('[data-testid="settings.repo.branch.input"]');
    await expect(url).toBeEnabled(); // operator
    await expect(page.locator('[data-testid="settings.repo.gateHint"]')).toHaveCount(0);

    const REPO = 'git@github.com:org/parity-probe.git';
    const BRANCH = 'parity-branch';
    await url.fill(REPO);
    await branch.fill(BRANCH);
    // dispatch the click (the settings window tiles under others — WM artifact, not a bug).
    await page.locator('[data-testid="settings.repo.save"]').dispatchEvent('click');

    // saved ≠ active: the effect hint appears (points at the restart; there is no restart control here).
    await expect(page.locator('[data-testid="settings.repo.effectHint"]')).toBeVisible();

    // PERSISTENCE: reload → GET /api/config/repo returns the saved values → the inputs reflect them (the PUT
    // actually persisted server-side, not just an optimistic client echo).
    await page.reload();
    await expect(page.locator('[data-testid="settings.repo.url.input"]')).toHaveJSProperty('value', REPO);
    await expect(page.locator('[data-testid="settings.repo.branch.input"]')).toHaveJSProperty('value', BRANCH);
  });
});
