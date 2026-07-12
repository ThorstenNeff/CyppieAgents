import { test, expect } from './fixtures';

/**
 * CYP-422 §A6 — the Event-Log Browse panel (CYP-452, P2-c.2): server-paged REST history at the DOM, driven against
 * the real harness same-origin. This is the SEPARATE path from the live-tail (which is live-only by design): Browse
 * queries GET /api/events (seq-paged + server-side filter). The harness boot-seeds an Event-Log corpus with
 * distinctive "BROWSE-SEED-*" detail markers — they appear here (REST history) but NOT in the live-only tail.
 * Operator posture (the injected operator token); Browse is operator-only (App mounts it only for an operator).
 */
test.describe('A6 · Event-Log Browse (CYP-452) — server-paged REST history at the DOM', () => {
  test('the Browse panel loads the seeded history via GET /api/events — distinct from the live-only tail', async ({ page }) => {
    await page.goto('/');
    // the Browse window (own operator-only window) fetches its first page on mount.
    const table = page.locator('[data-testid="eventBrowse.table"]');
    await expect(table).toBeVisible();
    // the boot-seeded corpus is present (REST history) — a distinctive marker row.
    await expect(table).toContainText('BROWSE-SEED');

    // the two-path discriminator: the LIVE-tail replays no history, so the boot-seeded markers never appear there —
    // only Browse (REST history) surfaces them. (Live-only tail is by design, not a defect.)
    await expect(page.locator('[data-testid="event-log"]')).not.toContainText('BROWSE-SEED');
  });

  test('selecting a Browse row opens the master-detail pane with the raw content-free detail JSON', async ({ page }) => {
    await page.goto('/');
    const table = page.locator('[data-testid="eventBrowse.table"]');
    await expect(table).toBeVisible();
    // select the first row (dispatch click — the Browse window tiles under others; occlusion is a WM artifact).
    await page.locator('[data-testid="eventBrowse.row.0"]').dispatchEvent('click');
    await expect(page.locator('[data-testid="eventBrowse.detail"]')).toBeVisible();
    // the detail pane shows the raw detail JSON (content-free, as-is — never fabricated).
    await expect(page.locator('[data-testid="eventBrowse.detail.json"]')).toBeVisible();
  });

  test('tapping a severity filter issues a SERVER-side GET /api/events query (not a client post-filter)', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('[data-testid="eventBrowse.table"]')).toBeVisible();

    // the discriminating check (spec §3 / tooth 2): a filter tap must issue a NEW server query carrying the
    // severity param — never a client-side post-filter over already-loaded rows.
    const filteredReq = page.waitForRequest((r) => r.url().includes('/api/events') && /[?&]severity=/.test(r.url()));
    await page.locator('[data-testid="eventBrowse.filter.severity"]').dispatchEvent('click');
    await filteredReq;

    // the subset cue appears — a filtered view is never read as "nothing happened" (tooth 3).
    await expect(page.locator('[data-testid="eventBrowse.filterActive"]')).toBeVisible();
  });
});
