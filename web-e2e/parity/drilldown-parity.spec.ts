import { test, expect } from './fixtures';

/**
 * CYP-422 §A6 — Korrelations-Drilldown (CYP-452, a PORT of the old WASM showRun/showSession parity feature — so a
 * blocking [K] row, not an enhancement). The Browse detail exposes two drill axes, each ENABLED only when the event
 * carries the field (correlationId → showRun, sessionId → showSession) — never guessed, never conflated (the
 * no-invented-correlation honesty). A drill fires a SERVER-side query on that axis. Against the real harness
 * same-origin; the harness seeds one event WITH correlationId+sessionId and others without.
 */
test.describe('A6 · Korrelations-Drilldown (CYP-452) — field-presence-gated + server-side', () => {
  test('operator: a correlated event enables showRun → server-side drilldown query + header', async ({ page }) => {
    await page.goto('/');
    const table = page.locator('[data-testid="eventBrowse.table"]');
    await expect(table).toBeVisible();

    // select the CORRELATED event (has correlationId + sessionId) → its detail drill axes are ENABLED.
    await table.locator('[data-testid^="eventBrowse.row."]', { hasText: 'CORRELATED-SEED' }).first().dispatchEvent('click');
    await expect(page.locator('[data-testid="eventBrowse.detail"]')).toBeVisible();
    await expect(page.locator('[data-testid="eventBrowse.detail.showRun"]')).toHaveAttribute('aria-disabled', 'false');
    await expect(page.locator('[data-testid="eventBrowse.detail.showSession"]')).toHaveAttribute('aria-disabled', 'false');

    // click showRun → a SERVER-side drilldown query carrying the correlationId (not a client-side filter of loaded
    // rows) + the drilldown header naming the run.
    const drillReq = page.waitForRequest((r) => r.url().includes('/api/events') && /[?&]correlationId=run-alpha/.test(r.url()));
    await page.locator('[data-testid="eventBrowse.detail.showRun"]').dispatchEvent('click');
    await drillReq;
    await expect(page.locator('[data-testid="eventBrowse.drilldown.header"]')).toBeVisible();
  });

  test('operator: a NON-correlated event keeps showRun disabled (no-invented-correlation)', async ({ page }) => {
    await page.goto('/');
    const table = page.locator('[data-testid="eventBrowse.table"]');
    await expect(table).toBeVisible();

    // a plain BROWSE-SEED event has no correlationId → the drill axis is DISABLED (the field-presence gate).
    await table.locator('[data-testid^="eventBrowse.row."]', { hasText: 'BROWSE-SEED-A' }).first().dispatchEvent('click');
    await expect(page.locator('[data-testid="eventBrowse.detail"]')).toBeVisible();
    await expect(page.locator('[data-testid="eventBrowse.detail.showRun"]')).toHaveAttribute('aria-disabled', 'true');
  });
});
